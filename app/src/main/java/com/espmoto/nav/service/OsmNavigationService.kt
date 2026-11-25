package com.espmoto.nav.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.espmoto.nav.MainActivity
import com.espmoto.nav.ble.EspMotoBleClient
import com.espmoto.nav.ble.ManeuverType
import com.espmoto.nav.ble.NavFormatter
import com.espmoto.nav.ble.NavPacket
import com.espmoto.nav.ble.SimObdPacket
import com.espmoto.nav.osm.GeoCoord
import com.espmoto.nav.osm.NominatimPlace
import com.espmoto.nav.osm.OsmApiClient
import com.espmoto.nav.osm.OsmRoute
import com.espmoto.nav.osm.RouteStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NavUiState(
    val currentLocation: GeoCoord = GeoCoord(40.4168, -3.7038),
    val hasGpsFix: Boolean = false,
    val route: OsmRoute? = null,
    val activeStepIndex: Int = 0,
    val isNavigating: Boolean = false,
    val isSimulating: Boolean = false,
    val isSearching: Boolean = false,
    val searchResults: List<NominatimPlace> = emptyList(),
    val currentPacket: NavPacket? = null,
    val errorMessage: String? = null
)

object NavigationController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(NavUiState())
    val uiState: StateFlow<NavUiState> = _uiState

    private var simJob: Job? = null
    private var heartbeatJob: Job? = null
    private var rerouteJob: Job? = null
    private var offRouteCounter = 0

    @Volatile
    private var lastSpeedMps: Double = 0.0

    @Volatile
    private var appContextRef: Context? = null

    fun refreshLastKnownLocation(context: Context) {
        appContextRef = context.applicationContext
        HelmetAudioAlerts.load(context)
        if (!hasLocationPermission(context)) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val candidates = listOfNotNull(
            runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
            runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        )
        val best = candidates.maxByOrNull { it.time } ?: return
        _uiState.value = _uiState.value.copy(
            currentLocation = GeoCoord(best.latitude, best.longitude),
            hasGpsFix = true
        )
    }

    fun searchAndRoute(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter a destination address")
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                errorMessage = null
            )

            val searchResult = OsmApiClient.searchAddress(trimmed)
            searchResult.onFailure { err ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    errorMessage = err.message ?: "Address lookup failed"
                )
            }.onSuccess { places ->
                if (places.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        searchResults = emptyList(),
                        errorMessage = "No places found for \"$trimmed\""
                    )
                    return@onSuccess
                }

                _uiState.value = _uiState.value.copy(
                    searchResults = places
                )
                fetchRouteTo(places.first())
            }
        }
    }

    fun selectDestination(place: NominatimPlace) {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                errorMessage = null
            )
            fetchRouteTo(place)
        }
    }

    fun routeFromSharedIntent(rawText: String, subject: String? = null) {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                isSearching = true,
                searchResults = emptyList(),
                errorMessage = null
            )
            OsmApiClient.resolveSharedLocation(rawText, subject)
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        isSearching = false,
                        errorMessage = err.message ?: "Could not resolve shared location"
                    )
                }
                .onSuccess { place ->
                    fetchRouteTo(place)
                }
        }
    }

    private suspend fun fetchRouteTo(place: NominatimPlace) {
        stopSimulationOnly()
        val origin = _uiState.value.currentLocation
        val routeResult = OsmApiClient.fetchRoute(
            start = origin,
            end = place.coord,
            destinationName = place.shortName
        )

        routeResult.onFailure { err ->
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                errorMessage = err.message ?: "Route calculation failed"
            )
        }.onSuccess { route ->
            HelmetAudioAlerts.resetStepTracking()
            val initialPacket = buildPacketForStep(route, 0, origin, useSegmentDistance = true)
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                route = route,
                activeStepIndex = 0,
                currentPacket = initialPacket,
                errorMessage = null
            )
        }
    }

    fun startGpsNavigation(context: Context) {
        val route = _uiState.value.route ?: run {
            _uiState.value = _uiState.value.copy(errorMessage = "Search an address first")
            return
        }
        if (!hasLocationPermission(context)) {
            _uiState.value = _uiState.value.copy(errorMessage = "Location permission required for GPS nav")
            return
        }

        val appCtx = context.applicationContext
        appContextRef = appCtx
        HelmetAudioAlerts.load(appCtx)
        HelmetAudioAlerts.resetStepTracking()
        stopSimulationOnly()
        offRouteCounter = 0

        _uiState.value = _uiState.value.copy(
            isNavigating = true,
            isSimulating = false,
            errorMessage = null
        )

        val intent = Intent(appCtx, OsmNavigationService::class.java).apply {
            action = OsmNavigationService.ACTION_START
        }
        ContextCompat.startForegroundService(appCtx, intent)

        sendCurrentStepPacket(useSegmentDistance = !_uiState.value.hasGpsFix)
        startHeartbeat()
    }

    fun startSimulation(context: Context? = appContextRef) {
        val route = _uiState.value.route ?: run {
            _uiState.value = _uiState.value.copy(errorMessage = "Search an address first")
            return
        }

        val appCtx = context?.applicationContext ?: appContextRef
        if (appCtx != null) {
            appContextRef = appCtx
            HelmetAudioAlerts.load(appCtx)
        }

        lastSpeedMps = 0.0
        HelmetAudioAlerts.resetStepTracking()
        stopGpsNavigationOnly(stopService = false)
        stopSimulationOnly()

        _uiState.value = _uiState.value.copy(
            isSimulating = true,
            isNavigating = false,
            activeStepIndex = 0,
            errorMessage = null
        )

        if (appCtx != null && (hasLocationPermission(appCtx) || hasBluetoothConnectPermission(appCtx))) {
            val intent = Intent(appCtx, OsmNavigationService::class.java).apply {
                action = OsmNavigationService.ACTION_START_SIM
            }
            runCatching { ContextCompat.startForegroundService(appCtx, intent) }
        }

        simJob = scope.launch {
            var simTick = 0
            val totalSegments = route.segmentCount()
            for (segIdx in 0 until totalSegments) {
                if (!isActive) break
                val currentSeg = route.currentSegment(segIdx)
                val targetStep = route.upcomingStepForSegment(segIdx) ?: break
                val segDist = (currentSeg?.distanceMeters ?: 100.0).coerceAtLeast(15.0)
                val sampleDistances = if (segDist < 40.0) {
                    listOf(segDist, (segDist * 0.3).coerceAtMost(20.0))
                } else {
                    listOf(segDist, segDist * 0.45, (segDist * 0.15).coerceAtMost(55.0))
                }

                for (remainingSegDist in sampleDistances) {
                    if (!isActive) break
                    val progress = (1.0 - (remainingSegDist / segDist)).coerceIn(0.0, 1.0)
                    val pos = interpolateSegmentPosition(
                        start = currentSeg?.maneuverLocation ?: route.origin,
                        end = targetStep.maneuverLocation,
                        geometry = currentSeg?.geometry.orEmpty(),
                        progress = progress
                    )
                    val remDuration = route.remainingDurationSeconds(segIdx, remainingSegDist)
                    val packet = NavPacket(
                        active = true,
                        maneuver = targetStep.maneuver,
                        distance = NavFormatter.formatDistance(remainingSegDist),
                        street = targetStep.streetName,
                        eta = NavFormatter.formatEta(remDuration)
                    )
                    _uiState.value = _uiState.value.copy(
                        activeStepIndex = segIdx,
                        currentLocation = pos,
                        currentPacket = packet
                    )
                    HelmetAudioAlerts.evaluateTurnDistance(
                        routeId = System.identityHashCode(route),
                        stepIndex = segIdx,
                        maneuver = targetStep.maneuver,
                        distMeters = remainingSegDist,
                        segmentTotalMeters = segDist,
                        speedMps = effectiveSpeedMps(currentSeg)
                    )
                    EspMotoBleClient.sendNavPacket(packet)
                    EspMotoBleClient.sendSimObdPacket(
                        buildSimulatedObdPacket(currentSeg, remainingSegDist, segDist, simTick++)
                    )
                    appContextRef?.let { OsmNavigationService.updateNotification(it, packet) }
                    delay(1500L)
                }
            }

            if (isActive) {
                val arrivedPacket = NavPacket(
                    active = true,
                    maneuver = ManeuverType.DESTINATION,
                    distance = "0 m",
                    street = route.destinationName,
                    eta = NavFormatter.formatEta(0.0)
                )
                _uiState.value = _uiState.value.copy(
                    activeStepIndex = (route.steps.lastIndex).coerceAtLeast(0),
                    currentLocation = route.destination,
                    currentPacket = arrivedPacket,
                    isSimulating = false
                )
                EspMotoBleClient.sendNavPacket(arrivedPacket)
                EspMotoBleClient.sendSimObdPacket(
                    SimObdPacket(
                        active = true,
                        rpm = 1200,
                        speedKmh = 0,
                        gear = 0,
                        engineTempC = 92,
                        batteryVolts = 13.8f,
                        throttlePct = 0
                    )
                )
                delay(1500L)
                EspMotoBleClient.sendSimObdPacket(SimObdPacket(active = false))
                stopForegroundService(appContextRef)
            }
        }
    }

    fun nextStep() {
        val route = _uiState.value.route ?: return
        val maxSegment = (route.segmentCount() - 1).coerceAtLeast(0)
        val next = (_uiState.value.activeStepIndex + 1).coerceAtMost(maxSegment)
        HelmetAudioAlerts.resetStepTracking()
        _uiState.value = _uiState.value.copy(activeStepIndex = next)
        sendCurrentStepPacket(useSegmentDistance = true)
    }

    fun prevStep() {
        val next = (_uiState.value.activeStepIndex - 1).coerceAtLeast(0)
        HelmetAudioAlerts.resetStepTracking()
        _uiState.value = _uiState.value.copy(activeStepIndex = next)
        sendCurrentStepPacket(useSegmentDistance = true)
    }

    fun stopNavigation(context: Context? = appContextRef) {
        stopSimulationOnly()
        stopGpsNavigationOnly(stopService = true, context = context)
        HelmetAudioAlerts.resetStepTracking()

        val endPacket = NavPacket(
            active = false,
            maneuver = ManeuverType.NONE,
            distance = "--",
            street = "Navigation paused",
            eta = "--:--"
        )
        _uiState.value = _uiState.value.copy(
            isNavigating = false,
            isSimulating = false,
            currentPacket = endPacket
        )
        EspMotoBleClient.sendNavPacket(endPacket)
    }

    fun cancelRoute(context: Context? = appContextRef) {
        stopSimulationOnly()
        stopGpsNavigationOnly(stopService = true, context = context)
        HelmetAudioAlerts.resetStepTracking()

        val resetPacket = NavPacket(
            active = false,
            maneuver = ManeuverType.NONE,
            distance = "--",
            street = "Waiting for route...",
            eta = "--:--"
        )
        EspMotoBleClient.sendNavPacket(resetPacket)
        _uiState.value = _uiState.value.copy(
            route = null,
            activeStepIndex = 0,
            isNavigating = false,
            isSimulating = false,
            searchResults = emptyList(),
            currentPacket = null,
            errorMessage = null
        )
    }

    internal fun onGpsLocationUpdate(location: Location) {
        val coord = GeoCoord(location.latitude, location.longitude)
        val state = _uiState.value
        val route = state.route ?: run {
            _uiState.value = state.copy(currentLocation = coord, hasGpsFix = true)
            return
        }

        if (!state.isNavigating) {
            _uiState.value = state.copy(currentLocation = coord, hasGpsFix = true)
            return
        }

        if (location.hasSpeed() && location.speed > 0f) {
            lastSpeedMps = location.speed.toDouble()
        }

        var stepIdx = state.activeStepIndex.coerceIn(0, (route.steps.lastIndex).coerceAtLeast(0))
        val targetStep = route.upcomingStepForSegment(stepIdx)
        if (targetStep != null) {
            val distToTurn = coord.distanceToMeters(targetStep.maneuverLocation)
            val maxSegment = (route.segmentCount() - 1).coerceAtLeast(0)
            if (distToTurn <= 25.0 && stepIdx < maxSegment) {
                stepIdx += 1
            }
        }

        val currentSeg = route.currentSegment(stepIdx)
        val currentSegDist = currentSeg?.distanceToGeometryMeters(coord) ?: Double.MAX_VALUE
        val minRouteDist = if (currentSegDist <= 85.0) currentSegDist else route.minDistanceToRouteMeters(coord)
        if (minRouteDist > 85.0) {
            offRouteCounter += 1
            if (offRouteCounter >= 3 && rerouteJob?.isActive != true) {
                offRouteCounter = 0
                triggerReroute(coord, route)
            }
        } else {
            offRouteCounter = 0
        }

        val upcoming = route.upcomingStepForSegment(stepIdx)
        if (upcoming != null) {
            val distToUpcoming = coord.distanceToMeters(upcoming.maneuverLocation)
            HelmetAudioAlerts.evaluateTurnDistance(
                routeId = System.identityHashCode(route),
                stepIndex = stepIdx,
                maneuver = upcoming.maneuver,
                distMeters = distToUpcoming,
                segmentTotalMeters = currentSeg?.distanceMeters ?: distToUpcoming,
                speedMps = effectiveSpeedMps(currentSeg)
            )
        }

        _uiState.value = _uiState.value.copy(
            currentLocation = coord,
            hasGpsFix = true,
            activeStepIndex = stepIdx
        )
        sendCurrentStepPacket(useSegmentDistance = false)
    }

    private fun effectiveSpeedMps(segment: RouteStep?): Double {
        if (lastSpeedMps > 0.5) return lastSpeedMps
        if (segment != null && segment.durationSeconds > 1.0 && segment.distanceMeters > 1.0) {
            return (segment.distanceMeters / segment.durationSeconds).coerceIn(5.0, 38.0)
        }
        return 11.0
    }

    private fun triggerReroute(from: GeoCoord, oldRoute: OsmRoute) {
        HelmetAudioAlerts.playOffRouteRerouting()
        rerouteJob = scope.launch {
            OsmApiClient.fetchRoute(from, oldRoute.destination, oldRoute.destinationName)
                .onSuccess { newRoute ->
                    HelmetAudioAlerts.resetStepTracking()
                    _uiState.value = _uiState.value.copy(
                        route = newRoute,
                        activeStepIndex = 0,
                        errorMessage = null
                    )
                    sendCurrentStepPacket(useSegmentDistance = false)
                }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _uiState.value.isNavigating) {
                delay(2000L)
                if (_uiState.value.isNavigating) {
                    sendCurrentStepPacket(useSegmentDistance = !_uiState.value.hasGpsFix)
                }
            }
        }
    }

    private fun sendCurrentStepPacket(useSegmentDistance: Boolean) {
        val state = _uiState.value
        val route = state.route ?: return
        val packet = buildPacketForStep(
            route = route,
            segmentIndex = state.activeStepIndex,
            currentLocation = state.currentLocation,
            useSegmentDistance = useSegmentDistance
        ) ?: return

        _uiState.value = _uiState.value.copy(currentPacket = packet)
        EspMotoBleClient.sendNavPacket(packet)
        if (state.isNavigating || state.isSimulating) {
            appContextRef?.let { OsmNavigationService.updateNotification(it, packet) }
        }
    }

    private fun buildPacketForStep(
        route: OsmRoute,
        segmentIndex: Int,
        currentLocation: GeoCoord,
        useSegmentDistance: Boolean
    ): NavPacket? {
        val targetStep = route.upcomingStepForSegment(segmentIndex) ?: return null
        val currentSeg = route.currentSegment(segmentIndex)
        val distMeters = if (useSegmentDistance && currentSeg != null) {
            currentSeg.distanceMeters
        } else {
            currentLocation.distanceToMeters(targetStep.maneuverLocation)
        }
        val remSeconds = route.remainingDurationSeconds(segmentIndex, distMeters)
        val distText = if (targetStep.maneuver == ManeuverType.DESTINATION && distMeters <= 20.0) {
            "0 m"
        } else {
            NavFormatter.formatDistance(distMeters)
        }
        return NavPacket(
            active = true,
            maneuver = targetStep.maneuver,
            distance = distText,
            street = targetStep.streetName,
            eta = NavFormatter.formatEta(remSeconds)
        )
    }

    private fun interpolateSegmentPosition(
        start: GeoCoord,
        end: GeoCoord,
        geometry: List<GeoCoord>,
        progress: Double
    ): GeoCoord {
        if (geometry.size >= 2) {
            val idx = ((geometry.size - 1) * progress).toInt().coerceIn(0, geometry.lastIndex)
            return geometry[idx]
        }
        val lat = start.lat + (end.lat - start.lat) * progress
        val lon = start.lon + (end.lon - start.lon) * progress
        return GeoCoord(lat, lon)
    }

    private fun buildSimulatedObdPacket(
        segment: RouteStep?,
        remainingSegDist: Double,
        segDist: Double,
        tick: Int
    ): SimObdPacket {
        val baseKmh = (effectiveSpeedMps(segment) * 3.6).coerceIn(18.0, 120.0)
        // Decelerate smoothly when approaching a turn
        val speedFactor = when {
            remainingSegDist <= 25.0 -> 0.45
            remainingSegDist <= 55.0 && segDist > 70.0 -> 0.72
            else -> 1.0
        }
        val speedKmh = (baseKmh * speedFactor).toInt().coerceIn(0, 140)
        val gear = when {
            speedKmh == 0 -> 0
            speedKmh <= 24 -> 1
            speedKmh <= 44 -> 2
            speedKmh <= 68 -> 3
            speedKmh <= 94 -> 4
            else -> 5
        }
        val gearMaxSpeed = when (gear) {
            1 -> 28.0
            2 -> 50.0
            3 -> 76.0
            4 -> 104.0
            else -> 140.0
        }
        val rpm = if (gear == 0) {
            1180 + (tick % 3) * 20
        } else {
            val loadRatio = (speedKmh / gearMaxSpeed).coerceIn(0.35, 0.95)
            (1800 + (loadRatio * 3700).toInt() + (tick % 4) * 45).coerceIn(1400, 6200)
        }
        val tempC = 89 + (tick.coerceAtMost(12) / 3) // 89..93 °C
        val throttlePct = if (speedFactor < 0.8) 8 else (18 + (speedKmh / 4)).coerceIn(12, 65)
        return SimObdPacket(
            active = true,
            rpm = rpm,
            speedKmh = speedKmh,
            gear = gear,
            engineTempC = tempC,
            batteryVolts = 14.1f,
            throttlePct = throttlePct
        )
    }

    private fun stopSimulationOnly() {
        val wasSimulating = simJob != null || _uiState.value.isSimulating
        simJob?.cancel()
        simJob = null
        if (_uiState.value.isSimulating) {
            _uiState.value = _uiState.value.copy(isSimulating = false)
        }
        if (wasSimulating) {
            EspMotoBleClient.sendSimObdPacket(SimObdPacket(active = false))
        }
    }

    private fun stopGpsNavigationOnly(
        stopService: Boolean = true,
        context: Context? = appContextRef
    ) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        rerouteJob?.cancel()
        rerouteJob = null
        offRouteCounter = 0
        if (_uiState.value.isNavigating) {
            _uiState.value = _uiState.value.copy(isNavigating = false)
        }
        if (stopService) {
            stopForegroundService(context)
        }
    }

    private fun stopForegroundService(context: Context?) {
        context?.let {
            try {
                it.stopService(Intent(it, OsmNavigationService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun hasBluetoothConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }
}

class OsmNavigationService : Service() {
    companion object {
        const val ACTION_START = "com.espmoto.nav.action.START"
        const val ACTION_START_SIM = "com.espmoto.nav.action.START_SIM"
        const val ACTION_STOP = "com.espmoto.nav.action.STOP"
        const val ACTION_CANCEL_ROUTE = "com.espmoto.nav.action.CANCEL_ROUTE"
        private const val CHANNEL_ID = "espmoto_osm_nav"
        private const val NOTIFICATION_ID = 1520

        fun updateNotification(context: Context, packet: NavPacket) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val notification = buildNotification(
                context = context,
                title = "${packet.maneuver.symbol} ${packet.distance} • ${packet.maneuver.label}",
                text = "${packet.street} (ETA ${packet.eta})"
            )
            nm.notify(NOTIFICATION_ID, notification)
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "OSM Turn-by-Turn Navigation",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps GPS navigation streaming to espMoto over BLE"
                }
                nm.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            ensureChannel(context)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cancelIntent = Intent(context, OsmNavigationService::class.java).apply {
                action = ACTION_CANCEL_ROUTE
            }
            val cancelPendingIntent = PendingIntent.getService(
                context,
                1,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val cancelAction = Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                "Cancel Route",
                cancelPendingIntent
            ).build()

            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(launchPendingIntent)
                .addAction(cancelAction)
                .build()
        }
    }

    private var locationManager: LocationManager? = null
    private var locationRegistered = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            NavigationController.onGpsLocationUpdate(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        HelmetAudioAlerts.load(this)
        when (intent?.action) {
            ACTION_CANCEL_ROUTE -> {
                NavigationController.cancelRoute(this)
                stopLocationUpdates()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopLocationUpdates()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val packet = NavigationController.uiState.value.currentPacket
        val title = packet?.let { "${it.maneuver.symbol} ${it.distance} • ${it.maneuver.label}" }
            ?: "espMoto OSM Navigation"
        val text = packet?.let { "${it.street} (ETA ${it.eta})" }
            ?: "Streaming turn-by-turn directions over BLE"

        val notification = buildNotification(this, title, text)
        if (!startForegroundCompat(notification)) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        val isSim = intent?.action == ACTION_START_SIM ||
            (intent?.action == null && NavigationController.uiState.value.isSimulating)
        if (isSim) {
            stopLocationUpdates()
        } else {
            startLocationUpdates()
        }

        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgType = 0
                if (NavigationController.hasLocationPermission(this)) {
                    fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
                if (NavigationController.hasBluetoothConnectPermission(this)) {
                    fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                }
                if (fgType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgType)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        return false
                    }
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "espMoto::NavWakeLock").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationRegistered || !NavigationController.hasLocationPermission(this)) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        locationManager = lm

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(LocationManager.FUSED_PROVIDER)
            }
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }

        var anyStarted = false
        for (provider in providers) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 1f, locationListener, Looper.getMainLooper())
                    anyStarted = true
                }
            } catch (_: Exception) {
            }
        }
        locationRegistered = anyStarted
    }

    private fun stopLocationUpdates() {
        if (!locationRegistered) return
        try {
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
        locationRegistered = false
    }

    override fun onDestroy() {
        stopLocationUpdates()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
