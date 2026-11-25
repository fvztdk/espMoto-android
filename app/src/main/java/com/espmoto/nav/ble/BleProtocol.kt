package com.espmoto.nav.ble

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

object BleProtocol {
    const val DEVICE_NAME = "espMoto"
    val NAV_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    val NAV_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    val OBD_CONFIG_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
    val PAIR_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26aa")
    val SIM_OBD_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26ab")
}

enum class ManeuverType(val id: Int, val label: String, val symbol: String) {
    NONE(0, "Ready", "•"),
    STRAIGHT(1, "Continue straight", "↑"),
    TURN_RIGHT(2, "Turn right", "↱"),
    TURN_LEFT(3, "Turn left", "↰"),
    SLIGHT_RIGHT(4, "Keep right", "↗"),
    SLIGHT_LEFT(5, "Keep left", "↖"),
    SHARP_RIGHT(6, "Sharp right", "↳"),
    SHARP_LEFT(7, "Sharp left", "↲"),
    UTURN_LEFT(8, "U-turn left", "↶"),
    UTURN_RIGHT(9, "U-turn right", "↷"),
    ROUNDABOUT(10, "Roundabout", "⟳"),
    MERGE(11, "Merge", "⤤"),
    DESTINATION(12, "Arrive at destination", "◎")
}

data class NavPacket(
    val active: Boolean,
    val maneuver: ManeuverType,
    val distance: String,
    val street: String,
    val eta: String
) {
    fun encodeString(): String {
        val cleanStreet = street.replace("|", " ").take(32)
        val cleanDist = distance.replace("|", " ").take(12)
        val cleanEta = eta.replace("|", " ").take(10)
        return "${if (active) 1 else 0}|${maneuver.id}|$cleanDist|$cleanStreet|$cleanEta"
    }

    fun encode(): ByteArray = encodeString().toByteArray(Charsets.UTF_8)
}

data class SimObdPacket(
    val active: Boolean,
    val rpm: Int = 0,
    val speedKmh: Int = 0,
    val gear: Int = 0,
    val engineTempC: Int = 0,
    val batteryVolts: Float = 0f,
    val throttlePct: Int = 0
) {
    fun encode(): ByteArray {
        val voltsStr = String.format(Locale.US, "%.1f", batteryVolts)
        return "${if (active) 1 else 0}|$rpm|$speedKmh|$gear|$engineTempC|$voltsStr|$throttlePct"
            .toByteArray(Charsets.UTF_8)
    }
}

object NavFormatter {
    private val etaFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun formatDistance(meters: Double): String {
        if (!meters.isFinite() || meters < 0.0) return "--"
        return when {
            meters < 100.0 -> {
                val rounded = ((meters / 5.0).roundToInt() * 5).coerceAtLeast(5)
                "$rounded m"
            }
            meters < 1000.0 -> {
                val rounded = ((meters / 10.0).roundToInt() * 10).coerceAtLeast(10)
                if (rounded >= 1000) "1.0 km" else "$rounded m"
            }
            else -> String.format(Locale.US, "%.1f km", meters / 1000.0)
        }
    }

    fun formatDuration(seconds: Double): String {
        if (!seconds.isFinite() || seconds < 0.0) return "--"
        val totalMin = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val hrs = totalMin / 60
        val mins = totalMin % 60
        return if (hrs > 0) "${hrs}h ${mins}m" else "${mins} min"
    }

    fun formatEta(remainingSeconds: Double): String {
        if (!remainingSeconds.isFinite() || remainingSeconds < 0.0) return "--:--"
        val arrival = LocalTime.now().plusSeconds(remainingSeconds.toLong().coerceAtLeast(0L))
        return arrival.format(etaFormatter)
    }
}
