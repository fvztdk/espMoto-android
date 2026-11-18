package com.espmoto.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.espmoto.nav.ble.EspMotoBleClient
import com.espmoto.nav.ble.ManeuverType
import com.espmoto.nav.ble.NavFormatter
import com.espmoto.nav.ble.PairedDevice
import com.espmoto.nav.obd.ObdConfigStore
import com.espmoto.nav.service.HelmetAudioAlerts
import com.espmoto.nav.service.NavigationController
import com.espmoto.nav.ui.ObdConfigScreen
import com.espmoto.nav.ui.OsmMapView
import com.espmoto.nav.ui.PreferencesScreen
import com.espmoto.nav.ui.QrScannerDialog

private val BgDark = Color(0xFF0D0F14)
private val SurfaceDark = Color(0xFF161A22)
private val SurfaceBorder = Color(0xFF262C38)
private val EspMotoOrange = Color(0xFFFF5500)
private val TextSilver = Color(0xFFD0D4DC)
private val TextMuted = Color(0xFF7A8396)
private val StatusGreen = Color(0xFF00E676)
private val StatusAmber = Color(0xFFFFB300)
private val StatusRed = Color(0xFFFF1744)

private val EspMotoColorScheme = darkColorScheme(
    primary = EspMotoOrange,
    onPrimary = Color.White,
    background = BgDark,
    surface = SurfaceDark,
    onBackground = Color.White,
    onSurface = Color.White,
    outline = SurfaceBorder
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NavigationController.refreshLastKnownLocation(this)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(colorScheme = EspMotoColorScheme) {
                EspMotoNavScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (text.isNotBlank()) {
                    NavigationController.routeFromSharedIntent(text, subject)
                }
            }
            Intent.ACTION_VIEW -> {
                val dataStr = intent.dataString.orEmpty()
                if (dataStr.startsWith("geo:", ignoreCase = true)) {
                    NavigationController.routeFromSharedIntent(dataStr)
                }
            }
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun EspMotoNavScreen() {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    val connState by EspMotoBleClient.state.collectAsState()
    val bleStatusMsg by EspMotoBleClient.statusMessage.collectAsState()
    val simMirrorEnabled by EspMotoBleClient.simulatorMirrorEnabled.collectAsState()
    val simHost by EspMotoBleClient.simulatorHost.collectAsState()
    val pairedDevice by EspMotoBleClient.pairedDevice.collectAsState()
    val navState by NavigationController.uiState.collectAsState()
    val obdProfile by ObdConfigStore.profile.collectAsState()
    val soundWarningsEnabled by HelmetAudioAlerts.soundEnabled.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(0) } // 0 = Nav, 1 = OBD Config, 2 = Preferences
    var addressQuery by rememberSaveable { mutableStateOf("") }
    var showPlacesList by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ObdConfigStore.load(context)
        HelmetAudioAlerts.load(context)
        EspMotoBleClient.refreshPairing(context)
    }

    LaunchedEffect(navState.route) {
        val destName = navState.route?.destinationName
        if (!destName.isNullOrBlank()) {
            addressQuery = destName
            showPlacesList = false
        }
    }
    var isIgnoringBatteryOpt by rememberSaveable {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOpt =
                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            EspMotoBleClient.connect(context)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        }
    }

    val navPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        NavigationController.refreshLastKnownLocation(context)
        if (NavigationController.hasLocationPermission(context) && navState.route != null) {
            NavigationController.startGpsNavigation(context)
        }
    }

    val startupLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        NavigationController.refreshLastKnownLocation(context)
    }

    LaunchedEffect(Unit) {
        if (!NavigationController.hasLocationPermission(context)) {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            startupLocationLauncher.launch(perms)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = BgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Section 1: Header, BLE Status & Simulator Mirror
            HeaderConnectionCard(
                connState = connState,
                bleStatusMsg = bleStatusMsg,
                pairedDevice = pairedDevice,
                simMirrorEnabled = simMirrorEnabled,
                simHost = simHost,
                onConnectClick = {
                    if (connState == EspMotoBleClient.ConnectionState.CONNECTED ||
                        connState == EspMotoBleClient.ConnectionState.SCANNING ||
                        connState == EspMotoBleClient.ConnectionState.CONNECTING
                    ) {
                        EspMotoBleClient.disconnect()
                    } else {
                        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        } else {
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        blePermissionLauncher.launch(perms)
                    }
                },
                onScanQrClick = {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onToggleSimMirror = { enabled ->
                    EspMotoBleClient.setSimulatorMirrorEnabled(enabled)
                }
            )

            if (showQrScanner) {
                QrScannerDialog(
                    onQrScanned = { rawQr ->
                        showQrScanner = false
                        val res = EspMotoBleClient.pairWithQrPayload(context, rawQr)
                        if (res.isSuccess) {
                            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                arrayOf(
                                    Manifest.permission.BLUETOOTH_SCAN,
                                    Manifest.permission.BLUETOOTH_CONNECT
                                )
                            } else {
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                            blePermissionLauncher.launch(perms)
                        }
                    },
                    onDismiss = { showQrScanner = false }
                )
            }

            if (!isIgnoringBatteryOpt) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                ) {
                    Text(
                        text = "Allow unrestricted background battery (keeps screen-off updates running)",
                        color = StatusAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }

            // Mode Switcher Tabs: Navigation vs OBD Config vs Preferences
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Navigation", "OBD Config", "Preferences").forEachIndexed { idx, title ->
                    val active = selectedTab == idx
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (active) EspMotoOrange.copy(alpha = 0.2f) else SurfaceDark,
                        border = BorderStroke(1.dp, if (active) EspMotoOrange else SurfaceBorder),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedTab = idx }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = title,
                                color = if (active) EspMotoOrange else TextSilver,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (selectedTab == 1) {
                ObdConfigScreen(
                    profile = obdProfile,
                    bleConnected = connState == EspMotoBleClient.ConnectionState.CONNECTED,
                    simMirrorEnabled = simMirrorEnabled,
                    onProfileChange = { /* StateFlow updated in ObdConfigStore */ }
                )
            } else if (selectedTab == 2) {
                PreferencesScreen(
                    soundWarningsEnabled = soundWarningsEnabled,
                    onToggleSoundWarnings = { enabled ->
                        HelmetAudioAlerts.setEnabled(context, enabled, playPreview = enabled)
                    },
                    onTestSound = {
                        HelmetAudioAlerts.playTestSound(context)
                    }
                )
            } else {
                // Section 2: Address Input & Route Search
                AddressSearchBar(
                query = addressQuery,
                isSearching = navState.isSearching,
                onQueryChange = { addressQuery = it },
                onClear = {
                    addressQuery = ""
                    showPlacesList = false
                },
                onSearchSubmit = {
                    focusManager.clearFocus()
                    showPlacesList = true
                    NavigationController.searchAndRoute(addressQuery)
                }
            )

            if (navState.errorMessage != null) {
                Text(
                    text = navState.errorMessage!!,
                    color = StatusRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            if (showPlacesList && navState.searchResults.size > 1) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                ) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(navState.searchResults) { place ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showPlacesList = false
                                        addressQuery = place.shortName
                                        NavigationController.selectDestination(place)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = place.shortName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = place.displayName,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            HorizontalDivider(color = SurfaceBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }

            // Section 3: Interactive OpenStreetMap Preview
            Surface(
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SurfaceBorder),
                color = SurfaceDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    OsmMapView(
                        currentLocation = navState.currentLocation,
                        hasGpsFix = navState.hasGpsFix,
                        route = navState.route,
                        activeStepIndex = navState.activeStepIndex,
                        followRider = navState.isNavigating || navState.isSimulating,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Compact route summary pill + Cancel Route pill on map
                    val route = navState.route
                    if (route != null) {
                        Surface(
                            color = BgDark.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, SurfaceBorder),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "${NavFormatter.formatDistance(route.totalDistanceMeters)} • ${
                                    NavFormatter.formatDuration(route.totalDurationSeconds)
                                }",
                                color = TextSilver,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            color = BgDark.copy(alpha = 0.90f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clickable {
                                    addressQuery = ""
                                    showPlacesList = false
                                    NavigationController.cancelRoute(context)
                                }
                        ) {
                            Text(
                                text = "✕ Cancel Route",
                                color = StatusRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // Section 4: Turn-by-Turn Controller & ESP32 Live Preview Card
            TurnByTurnCard(
                navState = navState,
                onStartGpsNav = {
                    showPlacesList = false
                    if (NavigationController.hasLocationPermission(context)) {
                        NavigationController.startGpsNavigation(context)
                    } else {
                        val perms = buildList {
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }.toTypedArray()
                        navPermissionLauncher.launch(perms)
                    }
                },
                onSimulate = {
                    showPlacesList = false
                    NavigationController.startSimulation(context)
                },
                onPrevStep = { NavigationController.prevStep() },
                onNextStep = { NavigationController.nextStep() },
                onPause = { NavigationController.stopNavigation(context) },
                onCancelRoute = {
                    addressQuery = ""
                    showPlacesList = false
                    NavigationController.cancelRoute(context)
                }
            )
            }
        }
    }
}

@Composable
private fun HeaderConnectionCard(
    connState: EspMotoBleClient.ConnectionState,
    bleStatusMsg: String,
    pairedDevice: PairedDevice?,
    simMirrorEnabled: Boolean,
    simHost: String,
    onConnectClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onToggleSimMirror: (Boolean) -> Unit
) {
    val badgeColor = when (connState) {
        EspMotoBleClient.ConnectionState.CONNECTED -> StatusGreen
        EspMotoBleClient.ConnectionState.SCANNING,
        EspMotoBleClient.ConnectionState.CONNECTING -> StatusAmber
        EspMotoBleClient.ConnectionState.DISCONNECTED -> TextMuted
    }
    val buttonLabel = when (connState) {
        EspMotoBleClient.ConnectionState.CONNECTED -> "Disconnect"
        EspMotoBleClient.ConnectionState.SCANNING -> "Scanning..."
        EspMotoBleClient.ConnectionState.CONNECTING -> "Connecting..."
        EspMotoBleClient.ConnectionState.DISCONNECTED -> "Connect ESP32"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ESPMOTO • OSM NAV",
                        color = EspMotoOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                        Text(
                            text = "${connState.name} • $bleStatusMsg",
                            color = TextSilver,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = if (pairedDevice != null) {
                            "Paired: ${pairedDevice.mac}"
                        } else {
                            "Not paired • Scan QR on StopWatch"
                        },
                        color = TextMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedButton(
                    onClick = onScanQrClick,
                    border = BorderStroke(1.dp, EspMotoOrange),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EspMotoOrange),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(text = "Scan QR", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onConnectClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (connState == EspMotoBleClient.ConnectionState.CONNECTED) {
                            SurfaceBorder
                        } else {
                            EspMotoOrange
                        }
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(text = buttonLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Mirror to Web Sim ($simHost)",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Switch(
                    checked = simMirrorEnabled,
                    onCheckedChange = onToggleSimMirror,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = EspMotoOrange,
                        uncheckedTrackColor = SurfaceBorder
                    )
                )
            }
        }
    }
}

@Composable
private fun AddressSearchBar(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearchSubmit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Enter destination address...", color = TextMuted, fontSize = 14.sp) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = onClear) {
                        Text("✕", color = TextMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = EspMotoOrange,
                unfocusedBorderColor = SurfaceBorder,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        )

        Button(
            onClick = onSearchSubmit,
            enabled = !isSearching && query.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.height(52.dp)
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text("Route", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TurnByTurnCard(
    navState: com.espmoto.nav.service.NavUiState,
    onStartGpsNav: () -> Unit,
    onSimulate: () -> Unit,
    onPrevStep: () -> Unit,
    onNextStep: () -> Unit,
    onPause: () -> Unit,
    onCancelRoute: () -> Unit
) {
    val route = navState.route
    val packet = navState.currentPacket
    val maneuver = packet?.maneuver ?: ManeuverType.NONE
    val totalSteps = route?.segmentCount() ?: 0
    val currentStepNum = if (totalSteps > 0) (navState.activeStepIndex + 1).coerceAtMost(totalSteps) else 0

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
        border = BorderStroke(1.dp, SurfaceBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Maneuver icon tile
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgDark)
                ) {
                    Text(
                        text = maneuver.symbol,
                        color = if (packet?.active == true) Color.White else TextMuted,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = packet?.distance ?: "--",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ETA ${packet?.eta ?: "--:--"}",
                            color = TextSilver,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = packet?.street ?: "Enter an address to build an OSM route",
                        color = EspMotoOrange,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(maneuver.label)
                            if (totalSteps > 0) append(" • Step $currentStepNum of $totalSteps")
                            if (navState.isNavigating) append(" • GPS LIVE")
                            if (navState.isSimulating) append(" • SIMULATING")
                        },
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Primary action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (navState.isNavigating) {
                    OutlinedButton(
                        onClick = onPause,
                        border = BorderStroke(1.dp, EspMotoOrange),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Pause GPS", color = EspMotoOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onStartGpsNav,
                        enabled = route != null,
                        colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Start GPS Nav", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (navState.isSimulating) {
                    OutlinedButton(
                        onClick = onPause,
                        border = BorderStroke(1.dp, EspMotoOrange),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Pause Sim", color = EspMotoOrange, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onSimulate,
                        enabled = route != null,
                        border = BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Simulate", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // Step navigation & Cancel Route row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPrevStep,
                    enabled = route != null && navState.activeStepIndex > 0,
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("◀ Prev", color = TextSilver, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onNextStep,
                    enabled = route != null && totalSteps > 0 && navState.activeStepIndex < totalSteps - 1,
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Next ▶", color = TextSilver, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onCancelRoute,
                    enabled = route != null || navState.isNavigating || navState.isSimulating || packet?.active == true,
                    border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("Cancel Route", color = StatusRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
