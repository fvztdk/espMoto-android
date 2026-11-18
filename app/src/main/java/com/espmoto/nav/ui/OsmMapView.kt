package com.espmoto.nav.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.espmoto.nav.osm.GeoCoord
import com.espmoto.nav.osm.OsmRoute
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun OsmMapView(
    currentLocation: GeoCoord,
    hasGpsFix: Boolean,
    route: OsmRoute?,
    activeStepIndex: Int,
    followRider: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember(context) {
        Configuration.getInstance().load(
            context.applicationContext,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "espMoto/0.2.0"
        true
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(currentLocation.lat, currentLocation.lon))
        }
    }

    var framedRoute by remember { mutableStateOf<OsmRoute?>(null) }
    var centeredInitialGps by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = { mv ->
            mv.overlays.clear()

            if (route != null && route.geometry.isNotEmpty()) {
                val routeLine = Polyline(mv).apply {
                    outlinePaint.color = 0xFFFF5500.toInt()
                    outlinePaint.strokeWidth = 12f
                    setPoints(route.geometry.map { GeoPoint(it.lat, it.lon) })
                }
                mv.overlays.add(routeLine)

                val upcoming = route.upcomingStepForSegment(activeStepIndex)
                if (upcoming != null && upcoming.maneuverLocation != route.destination) {
                    val turnMarker = Marker(mv).apply {
                        position = GeoPoint(upcoming.maneuverLocation.lat, upcoming.maneuverLocation.lon)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = createCircleDrawable(
                            fillColor = Color.rgb(255, 85, 0),
                            strokeColor = Color.WHITE,
                            sizePx = 28
                        )
                        title = "${upcoming.maneuver.symbol} ${upcoming.streetName}"
                    }
                    mv.overlays.add(turnMarker)
                }

                val destMarker = Marker(mv).apply {
                    position = GeoPoint(route.destination.lat, route.destination.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = route.destinationName
                }
                mv.overlays.add(destMarker)
            }

            val riderPoint = GeoPoint(currentLocation.lat, currentLocation.lon)
            val riderMarker = Marker(mv).apply {
                position = riderPoint
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createCircleDrawable(
                    fillColor = Color.rgb(0, 176, 255),
                    strokeColor = Color.WHITE,
                    sizePx = 32
                )
                title = if (hasGpsFix) "Current GPS" else "Start Location"
            }
            mv.overlays.add(riderMarker)

            if (route != null && route !== framedRoute && route.geometry.size >= 2) {
                framedRoute = route
                val geoPoints = route.geometry.map { GeoPoint(it.lat, it.lon) }
                mv.post {
                    runCatching {
                        val bbox = BoundingBox.fromGeoPoints(geoPoints)
                        mv.zoomToBoundingBox(bbox, true, 72)
                    }
                }
            } else if (followRider) {
                mv.controller.animateTo(riderPoint)
            } else if (hasGpsFix && !centeredInitialGps && route == null) {
                centeredInitialGps = true
                mv.controller.animateTo(riderPoint)
            }

            mv.invalidate()
        }
    )
}

private fun createCircleDrawable(fillColor: Int, strokeColor: Int, sizePx: Int): GradientDrawable {
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fillColor)
        setStroke(4, strokeColor)
        setSize(sizePx, sizePx)
    }
}
