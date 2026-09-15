package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AngleUnit
import com.example.model.CoordinateBundle
import com.example.model.CoordinateSystem
import com.example.model.GeoPoint
import com.example.sensor.GpsStatus
import com.example.sensor.OrientationData
import com.example.sensor.PdrState
import java.util.Locale

@Composable
fun CoordinateBottomBar(
    crosshairPoint: GeoPoint,
    bundle: CoordinateBundle,
    selectedCoordSystem: CoordinateSystem,
    angleUnit: AngleUnit,
    orientationData: OrientationData,
    gpsStatus: GpsStatus,
    pdrState: PdrState,
    /** True terrain height from local SRTM data, when tiles are available. */
    terrainElevation: Double? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("coordinate_bottom_bar"),
        color = Color(0xFF161C24), // Tactical deep slate
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Drag indicator handle
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF455A64))
                    .align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Coordinate in selected system
            val mainCoordText = bundle.getFormatted(selectedCoordSystem)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = selectedCoordSystem.title.uppercase(Locale.getDefault()),
                        color = Color(0xFF81C784), // Tactical green
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = mainCoordText,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                // Elevation & Crosshair Indicator
                Column(horizontalAlignment = Alignment.End) {
                    val alt = crosshairPoint.altitude
                    if (alt != null) {
                        Text(
                            text = String.format(Locale.US, "H: %.0f м", alt),
                            color = Color(0xFF90CAF9),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = "НАЖМИТЕ ДЛЯ ВСЕХ СК",
                        color = Color(0xFF78909C),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Navigation & Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // True & Magnetic Heading
                val trueAzStr = AngleUnit.format(orientationData.trueHeadingDeg.toDouble(), angleUnit)
                val declStr = String.format(Locale.US, "%+.1f°", orientationData.magneticDeclinationDeg)
                Text(
                    text = "АЗ: $trueAzStr (Скл: $declStr)",
                    color = Color(0xFFFFD54F), // Amber
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Terrain height from SRTM, which is the ground itself rather than the GPS
                // ellipsoid figure — the number that matters for reading the landscape.
                terrainElevation?.let { h ->
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "H: ${h.toInt()} м",
                        color = Color(0xFF81C784),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // GPS / PDR Telemetry
                val gpsColor = when (gpsStatus) {
                    GpsStatus.ACTIVE -> Color(0xFF4CAF50)
                    GpsStatus.SEARCHING -> Color(0xFFFFB74D)
                    else -> Color(0xFFE57373)
                }

                val pdrInfo = if (pdrState.isDeadReckoningActive && gpsStatus != GpsStatus.ACTIVE) {
                    "PDR: ${pdrState.totalSteps} ш (${pdrState.totalDistanceMeters.toInt()}м)"
                } else {
                    gpsStatus.label
                }

                Text(
                    text = pdrInfo,
                    color = gpsColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
