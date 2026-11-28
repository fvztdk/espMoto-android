package com.espmoto.nav.ui

import android.os.SystemClock
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espmoto.nav.ble.EspMotoBleClient
import com.espmoto.nav.ble.PairedDevice

private val BgDark = Color(0xFF0D0F14)
private val SurfaceDark = Color(0xFF161A22)
private val SurfaceBorder = Color(0xFF262C38)
private val EspMotoOrange = Color(0xFFFF5500)
private val TextSilver = Color(0xFFD0D4DC)
private val TextMuted = Color(0xFF7A8396)
private val StatusGreen = Color(0xFF00E676)
private val StatusAmber = Color(0xFFFFB300)
private val StatusRed = Color(0xFFFF1744)

enum class ConfigSubScreen { ROOT, ESP32_CONNECTION }

@Composable
fun ConfigurationScreen(
    subScreen: ConfigSubScreen,
    onOpenSubScreen: (ConfigSubScreen) -> Unit,
    connState: EspMotoBleClient.ConnectionState,
    bleStatusMsg: String,
    pairedDevice: PairedDevice?,
    autoReconnectEnabled: Boolean,
    isIgnoringBatteryOpt: Boolean,
    soundWarningsEnabled: Boolean,
    devModeUnlocked: Boolean,
    gpsSimulationEnabled: Boolean,
    appVersion: String,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onUnpairClick: () -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onRequestBatteryOpt: () -> Unit,
    onToggleSoundWarnings: (Boolean) -> Unit,
    onTestSound: () -> Unit,
    onUnlockDeveloperMode: () -> Unit,
    onToggleGpsSimulation: (Boolean) -> Unit,
    onHideDeveloperMode: () -> Unit
) {
    when (subScreen) {
        ConfigSubScreen.ROOT -> ConfigurationRootContent(
            connState = connState,
            pairedDevice = pairedDevice,
            autoReconnectEnabled = autoReconnectEnabled,
            soundWarningsEnabled = soundWarningsEnabled,
            devModeUnlocked = devModeUnlocked,
            gpsSimulationEnabled = gpsSimulationEnabled,
            appVersion = appVersion,
            onOpenEsp32Connection = { onOpenSubScreen(ConfigSubScreen.ESP32_CONNECTION) },
            onToggleSoundWarnings = onToggleSoundWarnings,
            onTestSound = onTestSound,
            onUnlockDeveloperMode = onUnlockDeveloperMode,
            onToggleGpsSimulation = onToggleGpsSimulation,
            onHideDeveloperMode = onHideDeveloperMode
        )

        ConfigSubScreen.ESP32_CONNECTION -> Esp32ConnectionSubScreen(
            connState = connState,
            bleStatusMsg = bleStatusMsg,
            pairedDevice = pairedDevice,
            autoReconnectEnabled = autoReconnectEnabled,
            isIgnoringBatteryOpt = isIgnoringBatteryOpt,
            onBack = { onOpenSubScreen(ConfigSubScreen.ROOT) },
            onConnectClick = onConnectClick,
            onDisconnectClick = onDisconnectClick,
            onScanQrClick = onScanQrClick,
            onUnpairClick = onUnpairClick,
            onToggleAutoReconnect = onToggleAutoReconnect,
            onRequestBatteryOpt = onRequestBatteryOpt
        )
    }
}

@Composable
private fun ConfigurationRootContent(
    connState: EspMotoBleClient.ConnectionState,
    pairedDevice: PairedDevice?,
    autoReconnectEnabled: Boolean,
    soundWarningsEnabled: Boolean,
    devModeUnlocked: Boolean,
    gpsSimulationEnabled: Boolean,
    appVersion: String,
    onOpenEsp32Connection: () -> Unit,
    onToggleSoundWarnings: (Boolean) -> Unit,
    onTestSound: () -> Unit,
    onUnlockDeveloperMode: () -> Unit,
    onToggleGpsSimulation: (Boolean) -> Unit,
    onHideDeveloperMode: () -> Unit
) {
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastTapMs by remember { mutableLongStateOf(0L) }

    val statusDotColor = when (connState) {
        EspMotoBleClient.ConnectionState.CONNECTED -> StatusGreen
        EspMotoBleClient.ConnectionState.SCANNING,
        EspMotoBleClient.ConnectionState.CONNECTING,
        EspMotoBleClient.ConnectionState.WAITING_IN_RANGE -> StatusAmber
        EspMotoBleClient.ConnectionState.DISCONNECTED -> TextMuted
    }
    val conciseStatus = when (connState) {
        EspMotoBleClient.ConnectionState.CONNECTED ->
            if (pairedDevice != null) "Connected • ${pairedDevice.mac}" else "Connected"
        EspMotoBleClient.ConnectionState.WAITING_IN_RANGE ->
            if (pairedDevice != null) "Waiting in range • ${pairedDevice.mac}" else "Waiting in range"
        EspMotoBleClient.ConnectionState.CONNECTING -> "Connecting..."
        EspMotoBleClient.ConnectionState.SCANNING -> "Scanning..."
        EspMotoBleClient.ConnectionState.DISCONNECTED ->
            if (pairedDevice != null) "Disconnected • ${pairedDevice.mac}" else "Not paired • Tap to scan QR"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CONFIGURATION",
                        color = EspMotoOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp
                    )
                    Text(
                        text = "ESP32 handlebar display & app preferences",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenEsp32Connection)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ESP32 Pairing & Connection",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Text(
                                text = conciseStatus,
                                color = TextSilver,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = if (autoReconnectEnabled) {
                                "Auto-reconnect: ON"
                            } else {
                                "Auto-reconnect: OFF"
                            },
                            color = if (autoReconnectEnabled) StatusGreen else TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = "›",
                        color = TextMuted,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Helmet Sound Warnings",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (soundWarningsEnabled) {
                                    "ACTIVE • Plays through Bluetooth helmet speaker & ducks music"
                                } else {
                                    "MUTED • All audio warnings disabled"
                                },
                                color = if (soundWarningsEnabled) StatusGreen else TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Switch(
                            checked = soundWarningsEnabled,
                            onCheckedChange = onToggleSoundWarnings,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = EspMotoOrange,
                                uncheckedTrackColor = SurfaceBorder
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgDark,
                        border = BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Included Audio Warnings",
                                color = EspMotoOrange,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "• Approaching Turn / Exit (2-Stage Speed-Adaptive):\n" +
                                    "  - City (< 50 km/h): Early chime at 250 m, final alert at 70 m\n" +
                                    "  - Road (50–85 km/h): Early chime at 450 m, final alert at 110 m\n" +
                                    "  - Highway (> 85 km/h): Early chime at 700 m, final alert at 160 m",
                                color = TextSilver,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Text(
                                text = "• Off-Route & Rerouting: Descending alert when OSRM recalculates a missed turn.",
                                color = TextSilver,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Text(
                                text = "• ESP32 BLE Link Status: Warning tone on unexpected handlebar display disconnect and confirmation chirp on connect.",
                                color = TextSilver,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onTestSound,
                        border = BorderStroke(1.dp, EspMotoOrange),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Test Helmet Chime",
                            color = EspMotoOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (devModeUnlocked) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, EspMotoOrange.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "DEVELOPER OPTIONS",
                            color = EspMotoOrange,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "GPS Route Simulation & Step Controls",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (gpsSimulationEnabled) {
                                        "ACTIVE • Shows Simulate and ◀ Prev / Next ▶ on Navigation"
                                    } else {
                                        "OFF • Shows Simulate and ◀ Prev / Next ▶ controls on Navigation for offline testing"
                                    },
                                    color = if (gpsSimulationEnabled) StatusGreen else TextMuted,
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Switch(
                                checked = gpsSimulationEnabled,
                                onCheckedChange = onToggleGpsSimulation,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = EspMotoOrange,
                                    uncheckedTrackColor = SurfaceBorder
                                )
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                versionTapCount = 0
                                onHideDeveloperMode()
                            },
                            border = BorderStroke(1.dp, SurfaceBorder),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Hide Developer Options",
                                color = TextSilver,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!devModeUnlocked) {
                            val now = SystemClock.elapsedRealtime()
                            val next = if (now - lastTapMs <= 3000L) versionTapCount + 1 else 1
                            lastTapMs = now
                            if (next >= 7) {
                                versionTapCount = 0
                                onUnlockDeveloperMode()
                            } else {
                                versionTapCount = next
                            }
                        }
                    }
                    .padding(vertical = 10.dp)
            ) {
                val footerText = when {
                    devModeUnlocked -> "espMoto v$appVersion • Developer mode active"
                    versionTapCount in 4..6 ->
                        "espMoto v$appVersion\nTap ${7 - versionTapCount} more times to enable Developer Options"
                    else -> "espMoto v$appVersion"
                }
                Text(
                    text = footerText,
                    color = TextMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun Esp32ConnectionSubScreen(
    connState: EspMotoBleClient.ConnectionState,
    bleStatusMsg: String,
    pairedDevice: PairedDevice?,
    autoReconnectEnabled: Boolean,
    isIgnoringBatteryOpt: Boolean,
    onBack: () -> Unit,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onUnpairClick: () -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onRequestBatteryOpt: () -> Unit
) {
    var showUnpairDialog by remember { mutableStateOf(false) }

    val statusDotColor = when (connState) {
        EspMotoBleClient.ConnectionState.CONNECTED -> StatusGreen
        EspMotoBleClient.ConnectionState.SCANNING,
        EspMotoBleClient.ConnectionState.CONNECTING,
        EspMotoBleClient.ConnectionState.WAITING_IN_RANGE -> StatusAmber
        EspMotoBleClient.ConnectionState.DISCONNECTED -> TextMuted
    }
    val stateTitle = when (connState) {
        EspMotoBleClient.ConnectionState.CONNECTED -> "Connected"
        EspMotoBleClient.ConnectionState.WAITING_IN_RANGE -> "Waiting for ESP32 in range"
        EspMotoBleClient.ConnectionState.CONNECTING -> "Connecting..."
        EspMotoBleClient.ConnectionState.SCANNING -> "Scanning..."
        EspMotoBleClient.ConnectionState.DISCONNECTED -> "Disconnected"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceDark,
                border = BorderStroke(1.dp, SurfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgDark,
                        border = BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier.clickable(onClick = onBack)
                    ) {
                        Text(
                            text = "‹ Configuration",
                            color = TextSilver,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Text(
                        text = "ESP32 PAIRING & CONNECTION",
                        color = EspMotoOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        item {
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Text(
                            text = stateTitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = bleStatusMsg,
                        color = TextSilver,
                        fontSize = 12.sp
                    )

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgDark,
                        border = BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (pairedDevice != null) {
                                Text(
                                    text = "Paired Device",
                                    color = TextMuted,
                                    fontSize = 10.sp
                                )
                                Text(
                                    text = "${pairedDevice.name} • ${pairedDevice.mac}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    text = "No espMoto paired yet. Open the QR pairing screen on your StopWatch and scan it below.",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    if (pairedDevice == null) {
                        Button(
                            onClick = onScanQrClick,
                            colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                        ) {
                            Text(
                                text = "Scan QR Code to Pair",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        when (connState) {
                            EspMotoBleClient.ConnectionState.CONNECTED -> {
                                Button(
                                    onClick = onDisconnectClick,
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceBorder),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                ) {
                                    Text(
                                        text = "Disconnect",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            EspMotoBleClient.ConnectionState.SCANNING,
                            EspMotoBleClient.ConnectionState.CONNECTING -> {
                                OutlinedButton(
                                    onClick = onDisconnectClick,
                                    border = BorderStroke(1.dp, SurfaceBorder),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                ) {
                                    Text(
                                        text = "Cancel",
                                        color = TextSilver,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            EspMotoBleClient.ConnectionState.WAITING_IN_RANGE -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = onConnectClick,
                                        colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                    ) {
                                        Text(
                                            text = "Connect Now",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onDisconnectClick,
                                        border = BorderStroke(1.dp, SurfaceBorder),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(42.dp)
                                    ) {
                                        Text(
                                            text = "Stop Waiting",
                                            color = TextSilver,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }

                            EspMotoBleClient.ConnectionState.DISCONNECTED -> {
                                Button(
                                    onClick = onConnectClick,
                                    colors = ButtonDefaults.buttonColors(containerColor = EspMotoOrange),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                ) {
                                    Text(
                                        text = "Connect ESP32",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onScanQrClick,
                                border = BorderStroke(1.dp, EspMotoOrange),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                            ) {
                                Text(
                                    text = "Re-pair (Scan QR)",
                                    color = EspMotoOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            OutlinedButton(
                                onClick = { showUnpairDialog = true },
                                border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.7f)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                            ) {
                                Text(
                                    text = "Unpair Device",
                                    color = StatusRed,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Reconnect When in Range",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (autoReconnectEnabled) {
                                    "ACTIVE • Connects automatically when espMoto is in range"
                                } else {
                                    "OFF • Manual connection only"
                                },
                                color = if (autoReconnectEnabled) StatusGreen else TextMuted,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Switch(
                            checked = autoReconnectEnabled,
                            onCheckedChange = onToggleAutoReconnect,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = EspMotoOrange,
                                uncheckedTrackColor = SurfaceBorder
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BgDark,
                        border = BorderStroke(1.dp, SurfaceBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "• Automatically connects on app launch, when Bluetooth turns ON, and whenever the StopWatch returns in range while the app is open, backgrounded, or navigating.",
                                color = TextSilver,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            Text(
                                text = "• Tapping Disconnect pauses auto-reconnect for the current session until you tap Connect or reopen the app.",
                                color = TextSilver,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        if (!isIgnoringBatteryOpt) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceDark,
                    border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRequestBatteryOpt)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Unrestricted Battery Recommended",
                            color = StatusAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Tap to disable battery optimization so background auto-reconnect and screen-off navigation updates stay reliable.",
                            color = TextSilver,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }

    if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            containerColor = SurfaceDark,
            title = {
                Text(
                    text = "Unpair espMoto?",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You will need to scan the pairing QR code on your StopWatch again to reconnect.",
                    color = TextSilver,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnpairDialog = false
                        onUnpairClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusRed)
                ) {
                    Text("Unpair", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text("Cancel", color = TextSilver)
                }
            }
        )
    }
}
