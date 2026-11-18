package com.espmoto.nav.obd

import org.json.JSONArray
import org.json.JSONObject

enum class ObdProtocol(val atspCode: Int, val displayName: String, val hint: String) {
    AUTO(0, "Auto (ATSP0)", "Euro 4/5+ CAN auto-detect"),
    ISO_15765_11_500(6, "CAN 11-bit 500k (ATSP6)", "Standard Euro 4/5+ Honda, Yamaha, HDLAN"),
    ISO_15765_29_500(7, "CAN 29-bit 500k (ATSP7)", "29-bit CAN / UDS ECUs"),
    J1850_VPW(2, "J1850 VPW (ATSP2)", "Pre-2014 Harley-Davidson (Sportster/Dyna)"),
    ISO_14230_KWP_FAST(5, "KWP2000 Fast (ATSP5)", "Older Honda / Yamaha K-Line"),
    ISO_9141_2(3, "ISO 9141-2 (ATSP3)", "Legacy K-Line");

    companion object {
        fun fromCode(code: Int): ObdProtocol =
            entries.firstOrNull { it.atspCode == code } ?: AUTO
    }
}

enum class MetricId(
    val id: Int,
    val displayName: String,
    val shortLabel: String,
    val defaultUnit: String,
    val isStandardObd2: Boolean
) {
    NONE(0, "None (Hidden)", "--", "", true),
    RPM(1, "Engine RPM", "RPM", "", true),
    SPEED(2, "Vehicle Speed", "SPD", "km/h", true),
    COOLANT_TEMP(3, "Coolant / Engine Temp", "TEMP", "°C", true),
    VOLTAGE(4, "Control Module Voltage", "VOLT", "V", true),
    THROTTLE(5, "Throttle Position", "TPS", "%", true),
    INTAKE_TEMP(6, "Intake Air Temp", "IAT", "°C", true),
    GEAR(7, "Gear Position", "GEAR", "", false),
    OIL_PRESSURE(8, "Oil Pressure", "OIL", "bar", false),
    LEAN_ANGLE(9, "Lean Angle (IMU)", "LEAN", "°", false),
    CLUTCH_KICKSTAND(10, "Clutch / Kickstand", "CLCH", "", false),
    ABS_TC(11, "ABS / Traction Ctrl", "ABS", "", false);

    companion object {
        fun fromId(id: Int): MetricId = entries.firstOrNull { it.id == id } ?: NONE
        val selectableDisplayMetrics: List<MetricId> = entries.filter { it != RPM }
        val centerSlotMetrics: List<MetricId> = listOf(GEAR, SPEED, THROTTLE, NONE)
    }
}

data class ObdParamConfig(
    val metric: MetricId,
    val enabled: Boolean,
    val requestHex: String,
    val header: String = "",
    val byteOffset: Int = 0,
    val byteLength: Int = 1,
    val mul: Int = 1,
    val div: Int = 1,
    val add: Int = 0,
    val bitMask: Int = 0,
    val bitShift: Int = 0,
    val isSigned: Boolean = false
) {
    fun formulaPreview(): String {
        val raw = if (byteLength == 2) "((A*256)+B)" else "A"
        val masked = if (bitMask != 0) "($raw & 0x${bitMask.toString(16).uppercase()}) >> $bitShift" else raw
        val scaled = when {
            mul == 1 && div == 1 -> masked
            mul == 1 -> "$masked / $div"
            div == 1 -> "$masked * $mul"
            else -> "($masked * $mul) / $div"
        }
        return when {
            add > 0 -> "$scaled + $add"
            add < 0 -> "$scaled - ${-add}"
            else -> scaled
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", metric.id)
        .put("en", if (enabled) 1 else 0)
        .put("req", requestHex.trim().uppercase())
        .put("hdr", header.trim().uppercase())
        .put("lbl", metric.shortLabel)
        .put("unit", metric.defaultUnit)
        .put("off", byteOffset)
        .put("len", byteLength)
        .put("mul", mul)
        .put("div", if (div == 0) 1 else div)
        .put("add", add)
        .put("mask", bitMask)
        .put("shr", bitShift)
        .put("sgn", if (isSigned) 1 else 0)

    companion object {
        fun fromJson(obj: JSONObject): ObdParamConfig? {
            val metric = MetricId.fromId(obj.optInt("id", 0))
            if (metric == MetricId.NONE) return null
            return ObdParamConfig(
                metric = metric,
                enabled = obj.optInt("en", 0) != 0,
                requestHex = obj.optString("req", ""),
                header = obj.optString("hdr", ""),
                byteOffset = obj.optInt("off", 0),
                byteLength = obj.optInt("len", 1).coerceIn(1, 2),
                mul = obj.optInt("mul", 1),
                div = obj.optInt("div", 1).let { if (it == 0) 1 else it },
                add = obj.optInt("add", 0),
                bitMask = obj.optInt("mask", 0),
                bitShift = obj.optInt("shr", 0),
                isSigned = obj.optInt("sgn", 0) != 0
            )
        }
    }
}

data class ObdDisplayConfig(
    val maxRpm: Int = 7000,
    val redlineRpm: Int = 5500,
    val centerSlot: MetricId = MetricId.GEAR,
    val bottomLeftSlot: MetricId = MetricId.COOLANT_TEMP,
    val bottomRightSlot: MetricId = MetricId.VOLTAGE,
    val bottomCenterSlot: MetricId = MetricId.NONE
)

enum class BikePresetId(val title: String, val subtitle: String) {
    EURO4_5_GENERIC("Euro 4/5+ OBD-II", "Standard Service 01 (Honda, Yamaha, H-D CAN)"),
    HARLEY_J1850("Harley J1850 VPW", "Pre-2014 Sportster/Dyna (ATSP2)"),
    HARLEY_HDLAN("Harley HDLAN CAN", "2014+ H-D CAN / UDS Service 22"),
    HONDA_EURO5("Honda Euro 5+", "Red 6-pin CAN + UDS Service 22"),
    YAMAHA_EURO5("Yamaha Euro 5+", "Red 6-pin CAN + UDS Service 22"),
    CUSTOM("Custom Bike", "Manual protocol & PID table")
}

data class BikeProfile(
    val presetId: BikePresetId,
    val protocol: ObdProtocol,
    val elmDeviceFilter: String = "",
    val display: ObdDisplayConfig,
    val params: List<ObdParamConfig>
) {
    fun toWireJson(): String {
        val pidsArr = JSONArray()
        params.forEach { pidsArr.put(it.toJson()) }
        val slotsArr = JSONArray()
            .put(display.centerSlot.id)
            .put(display.bottomLeftSlot.id)
            .put(display.bottomRightSlot.id)
            .put(display.bottomCenterSlot.id)

        return JSONObject()
            .put("v", 1)
            .put("proto", protocol.atspCode)
            .put("elmName", elmDeviceFilter.trim())
            .put("maxRpm", display.maxRpm)
            .put("redline", display.redlineRpm)
            .put("slots", slotsArr)
            .put("pids", pidsArr)
            .toString()
    }

    companion object {
        fun fromJsonString(json: String): BikeProfile? = runCatching {
            val obj = JSONObject(json)
            val presetName = obj.optString("presetId", BikePresetId.EURO4_5_GENERIC.name)
            val preset = runCatching { BikePresetId.valueOf(presetName) }
                .getOrDefault(BikePresetId.EURO4_5_GENERIC)
            val proto = ObdProtocol.fromCode(obj.optInt("proto", 0))
            val elmName = obj.optString("elmName", "")
            val slots = obj.optJSONArray("slots")
            val display = ObdDisplayConfig(
                maxRpm = obj.optInt("maxRpm", 7000).coerceIn(2000, 20000),
                redlineRpm = obj.optInt("redline", 5500).coerceIn(1000, 20000),
                centerSlot = MetricId.fromId(slots?.optInt(0, MetricId.SPEED.id) ?: MetricId.SPEED.id),
                bottomLeftSlot = MetricId.fromId(slots?.optInt(1, MetricId.COOLANT_TEMP.id) ?: MetricId.COOLANT_TEMP.id),
                bottomRightSlot = MetricId.fromId(slots?.optInt(2, MetricId.VOLTAGE.id) ?: MetricId.VOLTAGE.id),
                bottomCenterSlot = MetricId.fromId(slots?.optInt(3, MetricId.NONE.id) ?: MetricId.NONE.id)
            )
            val pidsArr = obj.optJSONArray("pids")
            val params = buildList {
                if (pidsArr != null) {
                    for (i in 0 until pidsArr.length()) {
                        pidsArr.optJSONObject(i)?.let { ObdParamConfig.fromJson(it) }?.let(::add)
                    }
                }
            }
            BikeProfile(
                presetId = preset,
                protocol = proto,
                elmDeviceFilter = elmName,
                display = display,
                params = if (params.isEmpty()) ObdPresets.create(preset).params else params
            )
        }.getOrNull()
    }

    fun toStorageJson(): String {
        val obj = JSONObject(toWireJson())
        obj.put("presetId", presetId.name)
        return obj.toString()
    }
}

object ObdPresets {
    private fun standardService01Params(
        enableSpeed: Boolean = true,
        enableThrottle: Boolean = true,
        enableIntake: Boolean = false
    ): List<ObdParamConfig> = listOf(
        ObdParamConfig(MetricId.RPM, true, "010C", byteLength = 2, mul = 1, div = 4),
        ObdParamConfig(MetricId.SPEED, enableSpeed, "010D", byteLength = 1, mul = 1, div = 1),
        ObdParamConfig(MetricId.COOLANT_TEMP, true, "0105", byteLength = 1, mul = 1, div = 1, add = -40),
        ObdParamConfig(MetricId.THROTTLE, enableThrottle, "0111", byteLength = 1, mul = 100, div = 255),
        ObdParamConfig(MetricId.INTAKE_TEMP, enableIntake, "010F", byteLength = 1, mul = 1, div = 1, add = -40),
        ObdParamConfig(MetricId.VOLTAGE, true, "0142", byteLength = 2, mul = 1, div = 1000)
    )

    private fun manufacturerExtras(defaultHdr: String = ""): List<ObdParamConfig> = listOf(
        ObdParamConfig(MetricId.GEAR, false, "221000", header = defaultHdr, byteLength = 1),
        ObdParamConfig(MetricId.OIL_PRESSURE, false, "221001", header = defaultHdr, byteLength = 1, mul = 1, div = 10),
        ObdParamConfig(MetricId.LEAN_ANGLE, false, "221002", header = defaultHdr, byteLength = 1, isSigned = true),
        ObdParamConfig(MetricId.CLUTCH_KICKSTAND, false, "221003", header = defaultHdr, byteLength = 1),
        ObdParamConfig(MetricId.ABS_TC, false, "221004", header = defaultHdr, byteLength = 1)
    )

    fun create(preset: BikePresetId): BikeProfile = when (preset) {
        BikePresetId.EURO4_5_GENERIC -> BikeProfile(
            presetId = preset,
            protocol = ObdProtocol.AUTO,
            display = ObdDisplayConfig(
                maxRpm = 10000,
                redlineRpm = 8500,
                centerSlot = MetricId.SPEED,
                bottomLeftSlot = MetricId.COOLANT_TEMP,
                bottomRightSlot = MetricId.VOLTAGE,
                bottomCenterSlot = MetricId.THROTTLE
            ),
            params = standardService01Params() + manufacturerExtras()
        )

        BikePresetId.HARLEY_J1850 -> BikeProfile(
            presetId = preset,
            protocol = ObdProtocol.J1850_VPW,
            display = ObdDisplayConfig(
                maxRpm = 7000,
                redlineRpm = 5500,
                centerSlot = MetricId.GEAR,
                bottomLeftSlot = MetricId.COOLANT_TEMP,
                bottomRightSlot = MetricId.VOLTAGE,
                bottomCenterSlot = MetricId.NONE
            ),
            params = listOf(
                ObdParamConfig(MetricId.RPM, true, "010C", byteLength = 2, mul = 1, div = 4),
                ObdParamConfig(MetricId.SPEED, true, "010D", byteLength = 1, mul = 1, div = 1),
                ObdParamConfig(MetricId.COOLANT_TEMP, true, "0105", byteLength = 1, mul = 1, div = 1, add = -40),
                ObdParamConfig(MetricId.VOLTAGE, true, "0142", byteLength = 2, mul = 1, div = 1000),
                ObdParamConfig(MetricId.THROTTLE, false, "0111", byteLength = 1, mul = 100, div = 255),
                ObdParamConfig(MetricId.INTAKE_TEMP, false, "010F", byteLength = 1, mul = 1, div = 1, add = -40),
                ObdParamConfig(MetricId.GEAR, false, "2100", byteLength = 1),
                ObdParamConfig(MetricId.OIL_PRESSURE, false, "2101", byteLength = 1)
            )
        )

        BikePresetId.HARLEY_HDLAN -> BikeProfile(
            presetId = preset,
            protocol = ObdProtocol.ISO_15765_11_500,
            display = ObdDisplayConfig(
                maxRpm = 7000,
                redlineRpm = 5500,
                centerSlot = MetricId.GEAR,
                bottomLeftSlot = MetricId.COOLANT_TEMP,
                bottomRightSlot = MetricId.VOLTAGE,
                bottomCenterSlot = MetricId.OIL_PRESSURE
            ),
            params = standardService01Params() + manufacturerExtras("7E0")
        )

        BikePresetId.HONDA_EURO5 -> BikeProfile(
            presetId = preset,
            protocol = ObdProtocol.ISO_15765_11_500,
            display = ObdDisplayConfig(
                maxRpm = 12000,
                redlineRpm = 10500,
                centerSlot = MetricId.SPEED,
                bottomLeftSlot = MetricId.COOLANT_TEMP,
                bottomRightSlot = MetricId.VOLTAGE,
                bottomCenterSlot = MetricId.THROTTLE
            ),
            params = standardService01Params(enableIntake = true) + manufacturerExtras("7E0")
        )

        BikePresetId.YAMAHA_EURO5 -> BikeProfile(
            presetId = preset,
            protocol = ObdProtocol.ISO_15765_11_500,
            display = ObdDisplayConfig(
                maxRpm = 11000,
                redlineRpm = 9500,
                centerSlot = MetricId.SPEED,
                bottomLeftSlot = MetricId.COOLANT_TEMP,
                bottomRightSlot = MetricId.VOLTAGE,
                bottomCenterSlot = MetricId.THROTTLE
            ),
            params = standardService01Params(enableIntake = true) + manufacturerExtras("7E0")
        )

        BikePresetId.CUSTOM -> BikeProfile(
            presetId = preset,
            protocol = ObdProtocol.AUTO,
            display = ObdDisplayConfig(
                maxRpm = 9000,
                redlineRpm = 7500,
                centerSlot = MetricId.SPEED,
                bottomLeftSlot = MetricId.COOLANT_TEMP,
                bottomRightSlot = MetricId.VOLTAGE,
                bottomCenterSlot = MetricId.NONE
            ),
            params = standardService01Params() + manufacturerExtras()
        )
    }
}
