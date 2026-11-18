package com.espmoto.nav.osm

import com.espmoto.nav.ble.ManeuverType
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoCoord(
    val lat: Double,
    val lon: Double
) {
    fun distanceToMeters(other: GeoCoord): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = (other.lat - lat) * PI / 180.0
        val dLon = (other.lon - lon) * PI / 180.0
        val lat1Rad = lat * PI / 180.0
        val lat2Rad = other.lat * PI / 180.0

        val a = sin(dLat / 2.0).pow(2) +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2.0).pow(2)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return earthRadiusMeters * c
    }

    /**
     * Shortest planar-projected distance in meters from this coordinate to segment [a, b],
     * used for off-route detection along OSRM step polylines.
     */
    fun distanceToSegmentMeters(a: GeoCoord, b: GeoCoord): Double {
        val metersPerDegLat = 111_320.0
        val refLatRad = ((a.lat + b.lat) * 0.5) * PI / 180.0
        val metersPerDegLon = (111_320.0 * cos(refLatRad)).coerceAtLeast(1.0)

        val ax = (a.lon - lon) * metersPerDegLon
        val ay = (a.lat - lat) * metersPerDegLat
        val bx = (b.lon - lon) * metersPerDegLon
        val by = (b.lat - lat) * metersPerDegLat

        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-6) {
            return sqrt(ax * ax + ay * ay)
        }

        val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return sqrt(projX * projX + projY * projY)
    }
}

data class NominatimPlace(
    val displayName: String,
    val shortName: String,
    val coord: GeoCoord
)

data class RouteStep(
    val index: Int,
    val maneuver: ManeuverType,
    val maneuverTypeRaw: String,
    val maneuverModifierRaw: String,
    val streetName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverLocation: GeoCoord,
    val geometry: List<GeoCoord>
) {
    fun distanceToGeometryMeters(point: GeoCoord): Double {
        if (geometry.isEmpty()) return point.distanceToMeters(maneuverLocation)
        if (geometry.size == 1) return point.distanceToMeters(geometry[0])
        var minMeters = Double.MAX_VALUE
        for (i in 0 until geometry.lastIndex) {
            val d = point.distanceToSegmentMeters(geometry[i], geometry[i + 1])
            if (d < minMeters) minMeters = d
        }
        return minMeters
    }
}

data class OsmRoute(
    val destinationName: String,
    val origin: GeoCoord,
    val destination: GeoCoord,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val geometry: List<GeoCoord>,
    val steps: List<RouteStep>
) {
    /**
     * In OSRM, step `i` describes traveling along `steps[i].streetName` for `steps[i].distanceMeters`
     * until reaching the maneuver at `steps[i + 1]`.
     */
    fun upcomingStepForSegment(segmentIndex: Int): RouteStep? {
        if (steps.isEmpty()) return null
        if (steps.size == 1) return steps[0]
        val nextIndex = (segmentIndex + 1).coerceIn(1, steps.lastIndex)
        return steps[nextIndex]
    }

    fun currentSegment(segmentIndex: Int): RouteStep? {
        if (steps.isEmpty()) return null
        return steps[segmentIndex.coerceIn(0, steps.lastIndex)]
    }

    fun segmentCount(): Int = (steps.size - 1).coerceAtLeast(1)

    fun minDistanceToRouteMeters(point: GeoCoord): Double {
        if (geometry.isEmpty()) return point.distanceToMeters(destination)
        if (geometry.size == 1) return point.distanceToMeters(geometry[0])
        var minMeters = Double.MAX_VALUE
        for (i in 0 until geometry.lastIndex) {
            val d = point.distanceToSegmentMeters(geometry[i], geometry[i + 1])
            if (d < minMeters) minMeters = d
        }
        return minMeters
    }

    fun remainingDurationSeconds(segmentIndex: Int, distToTurnMeters: Double): Double {
        if (steps.isEmpty()) return 0.0
        val clamped = segmentIndex.coerceIn(0, steps.lastIndex)
        val current = steps[clamped]
        val currentFraction = if (current.distanceMeters > 1.0) {
            (distToTurnMeters / current.distanceMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        var total = current.durationSeconds * currentFraction
        for (i in (clamped + 1) until steps.size) {
            total += steps[i].durationSeconds
        }
        return total
    }
}
