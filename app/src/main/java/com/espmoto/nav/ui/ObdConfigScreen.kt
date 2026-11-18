package com.espmoto.nav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espmoto.nav.ble.EspMotoBleClient
import com.espmoto.nav.obd.BikePresetId
import com.espmoto.nav.obd.BikeProfile
import com.espmoto.nav.obd.MetricId
import com.espmoto.nav.obd.ObdConfigStore
import com.espmoto.nav.obd.ObdParamConfig
import com.espmoto.nav.obd.ObdPresets
import com.espmoto.nav.obd.ObdProtocol

private val BgDark = Color(0xFF0D0F14)
private val SurfaceDark = Color(0xFF161A22)
private val SurfaceBorder = Color(0xFF262C38)
private val EspMotoOrange = Color(0xFFFF5500)
private val TextSilver = Color(0xFFD0D4DC)
private val TextMuted = Color(0xFF7A8396)
private val StatusGreen = Color(0xFF00E676)
private val StatusAmber = Color(0xFFFFB300)

@Composable
fun ObdConfigScreen(
    profile: BikeProfile,
    bleConnected: Boolean,
    simMirrorEnabled: Boolean,
    onProfileChange: (BikeProfile) -> Unit
) {
    val context = LocalContext.current
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    fun updateAndSave(next: BikeProfile) {
        ObdConfigStore.save(context, next)
        onProfileChange(next)
        syncStatus = null
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Sync Action Bar
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "OBD / ELM327 CONFIGURATION",
                                color = EspMotoOrange,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            )
                            Text(
                                text = syncStatus ?: if (bleConnected) {
                                    "Saved locally • Ready to sync to ESP32"
                                } else {
                                    "Saved locally • Will auto-sync when ESP32 connects"
                                },
                                color = if (syncStatus != null) StatusGreen else TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                ObdConfigStore.save(context, profile)
                                val sentBle = EspMotoBleClient.sendObdConfig(profile)
                                syncStatus = when {
                                    sentBle && simMirrorEnabled -> "Synced to ESP32 & Simulator"
                                    sentBle -> "Synced to ESP32"
                                    simMirrorEnabled -> "Saved & Mirrored to Simulator"
                                    else -> "Saved (connect ESP32 to sync)"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Sync to ESP32", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 2. Bike Presets & Protocol
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Bike Preset",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BikePresetId.entries.forEach { preset ->
                            val selected = profile.presetId == preset
                            FilterChip(
                                selected = selected,
                                onClick = { updateAndSave(ObdPresets.create(preset)) },
                                label = { Text(preset.title, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EspMotoOrange.copy(alpha = 0.22f),
                                    selectedLabelColor = EspMotoOrange,
                                    containerColor = BgDark,
                                    labelColor = TextSilver
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = SurfaceBorder,
                                    selectedBorderColor = EspMotoOrange
                                )
                            )
                        }
                    }

                    Text(
                        text = profile.presetId.subtitle,
                        color = TextMuted,
                        fontSize = 11.sp
                    )

                    ProtocolDropdown(
                        selected = profile.protocol,
                        onSelected = { proto -> updateAndSave(profile.copy(protocol = proto)) }
                    )

                    OutlinedTextField(
                        value = profile.elmDeviceFilter,
                        onValueChange = { filter -> updateAndSave(profile.copy(elmDeviceFilter = filter)) },
                        label = { Text("ELM327 BLE Name Filter (optional)", fontSize = 11.sp) },
                        placeholder = { Text("e.g. OBDLink, Vgate, Veepeak (blank = auto)", fontSize = 12.sp) },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgDark,
                        border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Euro 4/5+ bikes share standard Service 01 PIDs (RPM, Speed, Temp, TPS, IAT, Voltage). " +
                                "Gear, Lean Angle, Oil Pressure & pre-Euro 4 (J1850/K-Line) require bike-specific UDS Service 22 / Mode 21 PIDs. " +
                                "Unsupported PIDs returning 7F are skipped automatically.",
                            color = TextSilver,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // 3. ESP32 Display Slots
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ESP32 Telemetry Layout",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        var maxRpmText by remember(profile.display.maxRpm) {
                            mutableStateOf(profile.display.maxRpm.toString())
                        }
                        var redlineText by remember(profile.display.redlineRpm) {
                            mutableStateOf(profile.display.redlineRpm.toString())
                        }

                        OutlinedTextField(
                            value = maxRpmText,
                            onValueChange = { txt ->
                                maxRpmText = txt.filter { it.isDigit() }
                                val v = maxRpmText.toIntOrNull()
                                if (v != null && v in 2000..20000) {
                                    updateAndSave(profile.copy(display = profile.display.copy(maxRpm = v)))
                                }
                            },
                            label = { Text("Max RPM", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = darkFieldColors(),
                            modifier = Modifier.weight(1f)
                        )

                        OutlinedTextField(
                            value = redlineText,
                            onValueChange = { txt ->
                                redlineText = txt.filter { it.isDigit() }
                                val v = redlineText.toIntOrNull()
                                if (v != null && v in 1000..20000) {
                                    updateAndSave(profile.copy(display = profile.display.copy(redlineRpm = v)))
                                }
                            },
                            label = { Text("Redline RPM", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            colors = darkFieldColors(),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricSlotDropdown(
                            label = "Center Badge",
                            selected = profile.display.centerSlot,
                            options = MetricId.centerSlotMetrics,
                            onSelected = { m -> updateAndSave(profile.copy(display = profile.display.copy(centerSlot = m))) },
                            modifier = Modifier.weight(1f)
                        )
                        MetricSlotDropdown(
                            label = "Bottom Center",
                            selected = profile.display.bottomCenterSlot,
                            options = MetricId.selectableDisplayMetrics,
                            onSelected = { m -> updateAndSave(profile.copy(display = profile.display.copy(bottomCenterSlot = m))) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricSlotDropdown(
                            label = "Bottom Left",
                            selected = profile.display.bottomLeftSlot,
                            options = MetricId.selectableDisplayMetrics,
                            onSelected = { m -> updateAndSave(profile.copy(display = profile.display.copy(bottomLeftSlot = m))) },
                            modifier = Modifier.weight(1f)
                        )
                        MetricSlotDropdown(
                            label = "Bottom Right",
                            selected = profile.display.bottomRightSlot,
                            options = MetricId.selectableDisplayMetrics,
                            onSelected = { m -> updateAndSave(profile.copy(display = profile.display.copy(bottomRightSlot = m))) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 4. OBD Parameter Checklist & Custom PID Editor
        item {
            Text(
                text = "OBD Parameters (${profile.params.count { it.enabled }} active)",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        itemsIndexed(profile.params) { index, param ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, if (param.enabled) EspMotoOrange.copy(alpha = 0.5f) else SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = param.metric.displayName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = if (param.metric.isStandardObd2) StatusGreen.copy(alpha = 0.15f) else StatusAmber.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = if (param.metric.isStandardObd2) "OBD-II STD" else "UDS / CUSTOM",
                                    color = if (param.metric.isStandardObd2) StatusGreen else StatusAmber,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val hdrPrefix = if (param.header.isNotBlank()) "HDR ${param.header} • " else ""
                        Text(
                            text = "${hdrPrefix}${param.requestHex} • ${param.formulaPreview()}",
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = { editingIndex = index },
                        border = BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Edit", color = TextSilver, fontSize = 11.sp)
                    }

                    Switch(
                        checked = param.enabled,
                        onCheckedChange = { enabled ->
                            val updated = profile.params.toMutableList()
                            updated[index] = param.copy(enabled = enabled)
                            updateAndSave(profile.copy(params = updated))
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = EspMotoOrange,
                            uncheckedTrackColor = SurfaceBorder
                        )
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }

    val editIdx = editingIndex
    if (editIdx != null && editIdx in profile.params.indices) {
        PidEditorDialog(
            param = profile.params[editIdx],
            onDismiss = { editingIndex = null },
            onSave = { edited ->
                val updated = profile.params.toMutableList()
                updated[editIdx] = edited
                updateAndSave(profile.copy(presetId = BikePresetId.CUSTOM, params = updated))
                editingIndex = null
            }
        )
    }
}

@Composable
private fun ProtocolDropdown(
    selected: ObdProtocol,
    onSelected: (ObdProtocol) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = BgDark,
            border = BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ELM327 Protocol", color = TextMuted, fontSize = 10.sp)
                    Text(selected.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(selected.hint, color = TextMuted, fontSize = 11.sp)
                }
                Text("▼", color = TextMuted, fontSize = 12.sp)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ObdProtocol.entries.forEach { proto ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(proto.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(proto.hint, fontSize = 11.sp, color = TextMuted)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(proto)
                    }
                )
            }
        }
    }
}

@Composable
private fun MetricSlotDropdown(
    label: String,
    selected: MetricId,
    options: List<MetricId>,
    onSelected: (MetricId) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = BgDark,
            border = BorderStroke(1.dp, SurfaceBorder),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, color = TextMuted, fontSize = 10.sp)
                    Text(
                        text = selected.displayName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text("▼", color = TextMuted, fontSize = 11.sp)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { metric ->
                DropdownMenuItem(
                    text = { Text(metric.displayName, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onSelected(metric)
                    }
                )
            }
        }
    }
}

@Composable
private fun PidEditorDialog(
    param: ObdParamConfig,
    onDismiss: () -> Unit,
    onSave: (ObdParamConfig) -> Unit
) {
    var reqHex by remember { mutableStateOf(param.requestHex) }
    var hdr by remember { mutableStateOf(param.header) }
    var offset by remember { mutableStateOf(param.byteOffset.toString()) }
    var length by remember { mutableStateOf(param.byteLength.toString()) }
    var mul by remember { mutableStateOf(param.mul.toString()) }
    var div by remember { mutableStateOf(param.div.toString()) }
    var add by remember { mutableStateOf(param.add.toString()) }
    var maskHex by remember { mutableStateOf(if (param.bitMask == 0) "" else param.bitMask.toString(16).uppercase()) }
    var shift by remember { mutableStateOf(param.bitShift.toString()) }
    var signed by remember { mutableStateOf(param.isSigned) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "Edit ${param.metric.displayName}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reqHex,
                        onValueChange = { reqHex = it.uppercase().filter { c -> c in '0'..'9' || c in 'A'..'F' }.take(10) },
                        label = { Text("Request Hex", fontSize = 11.sp) },
                        placeholder = { Text("010C / 221000", fontSize = 11.sp) },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = hdr,
                        onValueChange = { hdr = it.uppercase().filter { c -> c in '0'..'9' || c in 'A'..'F' }.take(6) },
                        label = { Text("Header (ATSH)", fontSize = 11.sp) },
                        placeholder = { Text("7E0", fontSize = 11.sp) },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = offset,
                        onValueChange = { offset = it.filter { c -> c.isDigit() } },
                        label = { Text("Byte Offset", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = length,
                        onValueChange = { length = it.filter { c -> c == '1' || c == '2' }.take(1) },
                        label = { Text("Bytes (1 or 2)", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mul,
                        onValueChange = { mul = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Mul", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = div,
                        onValueChange = { div = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Div", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = add,
                        onValueChange = { add = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Offset", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maskHex,
                        onValueChange = { maskHex = it.uppercase().filter { c -> c in '0'..'9' || c in 'A'..'F' }.take(4) },
                        label = { Text("Bitmask (Hex)", fontSize = 11.sp) },
                        placeholder = { Text("0 = none", fontSize = 11.sp) },
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = shift,
                        onValueChange = { shift = it.filter { c -> c.isDigit() } },
                        label = { Text("Bit Shift", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        colors = darkFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Signed Value", color = TextSilver, fontSize = 12.sp)
                    Switch(
                        checked = signed,
                        onCheckedChange = { signed = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = EspMotoOrange,
                            uncheckedTrackColor = SurfaceBorder
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeDiv = (div.toIntOrNull() ?: 1).let { if (it == 0) 1 else it }
                    onSave(
                        param.copy(
                            requestHex = reqHex.ifBlank { param.requestHex },
                            header = hdr,
                            byteOffset = (offset.toIntOrNull() ?: 0).coerceIn(0, 16),
                            byteLength = (length.toIntOrNull() ?: 1).coerceIn(1, 2),
                            mul = mul.toIntOrNull() ?: 1,
                            div = safeDiv,
                            add = add.toIntOrNull() ?: 0,
                            bitMask = maskHex.toIntOrNull(16) ?: 0,
                            bitShift = (shift.toIntOrNull() ?: 0).coerceIn(0, 15),
                            isSigned = signed
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSilver)
            }
        }
    )
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = EspMotoOrange,
    unfocusedBorderColor = SurfaceBorder,
    focusedContainerColor = BgDark,
    unfocusedContainerColor = BgDark,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = EspMotoOrange,
    unfocusedLabelColor = TextMuted
)
