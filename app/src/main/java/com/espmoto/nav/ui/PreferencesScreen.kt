package com.espmoto.nav.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgDark = Color(0xFF0D0F14)
private val SurfaceDark = Color(0xFF161A22)
private val SurfaceBorder = Color(0xFF262C38)
private val EspMotoOrange = Color(0xFFFF5500)
private val TextSilver = Color(0xFFD0D4DC)
private val TextMuted = Color(0xFF7A8396)
private val StatusGreen = Color(0xFF00E676)

@Composable
fun PreferencesScreen(
    soundWarningsEnabled: Boolean,
    onToggleSoundWarnings: (Boolean) -> Unit,
    onTestSound: () -> Unit
) {
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
                        text = "USER PREFERENCES",
                        color = EspMotoOrange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp
                    )
                    Text(
                        text = "Saved locally on phone • Applied immediately",
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
    }
}
