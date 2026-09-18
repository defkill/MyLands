package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
    orientationData: State<OrientationData>,
    gpsStatus: GpsStatus,
    pdrState: PdrState,
    /** True terrain height from local SRTM data, when tiles are available. */
    terrainElevation: Double? = null,
    isCompact: Boolean = false,
    onToggleCompact: (() -> Unit)? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mainCoordText = bundle.getFormatted(selectedCoordSystem)
    val alt = terrainElevation ?: crosshairPoint.altitude

    if (isCompact) {
        // Compact collapsed state: docked in bottom-left, semi-transparent, minimal obstruction
        Surface(
            modifier = modifier
                .padding(start = 12.dp, bottom = 12.dp)
                .clickable(onClick = onClick)
                .testTag("coordinate_bottom_bar_compact"),
            color = Color(0xCC121822), // Semi-transparent tactical dark
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = selectedCoordSystem.shortName,
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (alt != null) {
                            Text(
                                text = String.format(Locale.US, "H:%.0fm", alt),
                                color = if (terrainElevation != null) Color(0xFF81C784) else Color(0xFF90CAF9),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Text(
                        text = mainCoordText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                if (onToggleCompact != null) {
                    IconButton(
                        onClick = onToggleCompact,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("expand_coordinate_bar_button")
                    ) {
                        Icon(
                            Icons.Default.ExpandLess,
                            contentDescription = "Развернуть панель координат",
                            tint = Color(0xFF90A4AE),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Expanded full-width state
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
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Drag indicator handle and collapse button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(32.dp))
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFF455A64))
                    )
                    if (onToggleCompact != null) {
                        IconButton(
                            onClick = onToggleCompact,
                            modifier = Modifier
                                .size(24.dp)
                                .testTag("collapse_coordinate_bar_button")
                        ) {
                            Icon(
                                Icons.Default.ExpandMore,
                                contentDescription = "Свернуть панель координат",
                                tint = Color(0xFF90A4AE),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(32.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Primary Coordinate in selected system
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
                        if (alt != null) {
                            Text(
                                text = String.format(Locale.US, "H: %.0f м", alt),
                                color = if (terrainElevation != null) Color(0xFF81C784) else Color(0xFF90CAF9),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = "H: ---",
                                color = Color(0xFF546E7A),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
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
                    HeadingTelemetryText(orientationData, angleUnit)

                    if (terrainElevation != null) {
                        Text(
                            text = "SRTM рельеф",
                            color = Color(0xFF81C784),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
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
}

@Composable
private fun HeadingTelemetryText(
    orientationData: State<OrientationData>,
    angleUnit: AngleUnit
) {
    val currentOrientation = orientationData.value
    val trueAzStr = AngleUnit.format(currentOrientation.trueHeadingDeg.toDouble(), angleUnit)
    val declStr = String.format(Locale.US, "%+.1f°", currentOrientation.magneticDeclinationDeg)
    Text(
        text = "АЗ: $trueAzStr (Скл: $declStr)",
        color = Color(0xFFFFD54F), // Amber
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace
    )
}
