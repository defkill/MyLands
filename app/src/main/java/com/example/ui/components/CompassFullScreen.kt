package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Warning
import android.hardware.SensorManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.entity.WaypointEntity
import com.example.model.AngleUnit
import com.example.model.CoordinateSystem
import com.example.model.GeoPoint
import com.example.geodesy.GeodesyEngine
import com.example.sensor.OrientationData
import android.graphics.Paint as AndroidPaint
import kotlin.math.cos
import kotlin.math.sin

data class CompassPointMarker(
    val index: Int,
    val name: String,
    val distanceMeters: Double,
    val azimuthDeg: Double,
    val colorArgb: Int
)

/**
 * Full-screen compass. Deliberately minimal: a rotating dial, the heading, the cardinal
 * direction, and the current position in the coordinate system selected on the main screen.
 * Opened by tapping the small azimuth chip in the top-left of the map.
 */
@Composable
fun CompassFullScreenDialog(
    orientationData: OrientationData,
    position: GeoPoint?,
    coordinateSystem: CoordinateSystem,
    angleUnit: AngleUnit,
    waypoints: List<WaypointEntity> = emptyList(),
    isEstimated: Boolean = false,
    blindDistanceMeters: Double = 0.0,
    onCreatePoint: (GeoPoint) -> Unit,
    onDismiss: () -> Unit
) {
    val nearestPoints: List<CompassPointMarker> = remember(waypoints, position) {
        if (position == null) emptyList()
        else {
            waypoints
                .map { wp ->
                    val dist = GeodesyEngine.distanceMeters(position, wp.toGeoPoint())
                    val az = GeodesyEngine.azimuthDegrees(position, wp.toGeoPoint())
                    Triple(wp, dist, az)
                }
                .sortedBy { it.second }
                .take(5)
                .mapIndexed { idx, item ->
                    CompassPointMarker(
                        index = idx + 1,
                        name = item.first.name,
                        distanceMeters = item.second,
                        azimuthDeg = item.third,
                        colorArgb = item.first.colorArgb
                    )
                }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            color = Color(0xFF0B0F14)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {

                // Close
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .testTag("compass_close_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color(0xFF90A4AE))
                }

                // Top Header and Nearest Points Row
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(top = 18.dp, end = 52.dp)
                ) {
                    Text(
                        text = "КОМПАС",
                        color = Color(0xFF90A4AE),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 20.dp)
                    )

                    if (nearestPoints.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val numberSymbols = listOf("①", "②", "③", "④", "⑤")
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("compass_nearest_points_row"),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(nearestPoints) { item ->
                                val numIcon = numberSymbols.getOrElse(item.index - 1) { "${item.index}" }
                                Surface(
                                    color = Color(item.colorArgb).copy(alpha = 0.25f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(item.colorArgb).copy(alpha = 0.7f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$numIcon ${item.name} — ${"%.0f".format(item.distanceMeters)} м",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val heading = orientationData.trueHeadingDeg.toDouble()

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCompassDial(heading, nearestPoints)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = AngleUnit.format(heading, angleUnit),
                                color = Color.White,
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Light,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = cardinalName(heading),
                                color = Color(0xFFB0BEC5),
                                fontSize = 20.sp
                            )

                            if (position == null) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Координаты недоступны",
                                    color = Color(0xFFFFB74D),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Нет данных GPS — разрешите геолокацию\nи дождитесь спутников",
                                    color = Color(0xFF607D8B),
                                    fontSize = 11.sp
                                )
                            }

                            if (position != null) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (isEstimated) {
                                        "${coordinateSystem.shortName} · СЧИСЛЕНИЕ"
                                    } else {
                                        coordinateSystem.shortName
                                    },
                                    color = if (isEstimated) Color(0xFFFFB74D) else Color(0xFF607D8B),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = GeodesyEngine
                                            .getCoordinateBundle(position.latitude, position.longitude, position.altitude)
                                            .getFormatted(coordinateSystem),
                                        color = if (isEstimated) Color(0xFFFFCC80) else Color(0xFF81D4FA),
                                        fontSize = 15.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        onClick = { onCreatePoint(position) },
                                        shape = MaterialTheme.shapes.small,
                                        color = Color(0xFF00897B),
                                        modifier = Modifier.testTag("compass_add_point_button")
                                    ) {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "Создать точку",
                                            tint = Color.White,
                                            modifier = Modifier.padding(4.dp).size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Low compass accuracy warning banner
                    if (isCompassAccuracyLow(orientationData.accuracy)) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = Color(0x33FF9800),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFFB74D),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Точность компаса низкая · Опишите «восьмёрку» телефоном для калибровки",
                                    color = Color(0xFFFFCC80),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = when {
                            position == null -> "Ожидание местоположения…"
                            isEstimated -> "Без GPS: пройдено по счислению ${"%.0f".format(blindDistanceMeters)} м · " +
                                "погрешность растёт примерно на 5-10% пути"
                            else -> "Магнитное склонение: ${"%+.1f".format(orientationData.magneticDeclinationDeg)}°"
                        },
                        color = if (isEstimated) Color(0xFFFFB74D) else Color(0xFF607D8B),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 24.dp)
                            .testTag("compass_declination_text")
                    )
                }
            }
        }
    }
}

fun cardinalName(deg: Double): String {
    val d = ((deg % 360) + 360) % 360
    return when {
        d < 22.5 -> "Север"
        d < 67.5 -> "Северо-восток"
        d < 112.5 -> "Восток"
        d < 157.5 -> "Юго-восток"
        d < 202.5 -> "Юг"
        d < 247.5 -> "Юго-запад"
        d < 292.5 -> "Запад"
        d < 337.5 -> "Северо-запад"
        else -> "Север"
    }
}

/**
 * Compact Tactical Compass HUD widget that floats on the map with a semi-transparent background.
 * Shows a mini rotating dial, azimuth, and cardinal direction.
 * Tapping expands to the full-screen compass; includes a close button to dismiss.
 */
@Composable
fun MiniCompassHudWidget(
    orientationData: State<OrientationData>,
    angleUnit: AngleUnit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .testTag("mini_compass_hud")
            .clickable { onExpand() },
        color = Color(0xCC10151E), // Semi-transparent tactical dark
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .width(108.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: title & close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "КОМПАС",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(20.dp)
                        .testTag("mini_compass_close_button")
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Закрыть компас",
                        tint = Color(0xFF90A4AE),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            val pNorth = remember {
                AndroidPaint().apply {
                    color = android.graphics.Color.rgb(255, 100, 100)
                    textSize = 18f
                    isAntiAlias = true
                    isFakeBoldText = true
                    textAlign = AndroidPaint.Align.CENTER
                }
            }
            val pOther = remember {
                AndroidPaint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 15f
                    isAntiAlias = true
                    textAlign = AndroidPaint.Align.CENTER
                }
            }

            // Mini rotating dial
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val currentOrientation = orientationData.value
                    val heading = currentOrientation.trueHeadingDeg.toDouble()
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.minDimension / 2f * 0.88f

                    // Fixed indicator top arrow
                    val arrowColor = Color(0xFF00E5FF)
                    val arrowTop = cy - r - 2f
                    drawLine(
                        color = arrowColor,
                        start = Offset(cx, arrowTop),
                        end = Offset(cx, cy - r + 8f),
                        strokeWidth = 4f
                    )

                    // Rotated dial
                    rotate(degrees = -heading.toFloat(), pivot = Offset(cx, cy)) {
                        // Outer ring
                        drawCircle(
                            color = Color(0x66FFFFFF),
                            radius = r,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f)
                        )

                        // 8 main ticks (every 45 degrees)
                        for (d in 0 until 360 step 45) {
                            val rad = Math.toRadians(d.toDouble() - 90.0)
                            val inner = if (d % 90 == 0) r - 10f else r - 6f
                            val col = if (d == 0) Color(0xFFFF5252) else Color.White
                            drawLine(
                                color = col,
                                start = Offset(cx + (cos(rad) * inner).toFloat(), cy + (sin(rad) * inner).toFloat()),
                                end = Offset(cx + (cos(rad) * r).toFloat(), cy + (sin(rad) * r).toFloat()),
                                strokeWidth = if (d % 90 == 0) 3f else 1.5f
                            )
                        }

                        // Cardinal letters
                        val cardinals = listOf(0 to "С", 90 to "В", 180 to "Ю", 270 to "З")
                        cardinals.forEach { (d, label) ->
                            val rad = Math.toRadians(d.toDouble() - 90.0)
                            val lr = r - 16f
                            val lx = cx + (cos(rad) * lr).toFloat()
                            val ly = cy + (sin(rad) * lr).toFloat() + 6f
                            drawContext.canvas.nativeCanvas.drawText(
                                label, lx, ly,
                                if (d == 0) pNorth else pOther
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            MiniCompassBottomText(orientationData, angleUnit)
        }
    }
}

@Composable
private fun MiniCompassBottomText(
    orientationData: State<OrientationData>,
    angleUnit: AngleUnit
) {
    val currentOrientation = orientationData.value
    val heading = currentOrientation.trueHeadingDeg.toDouble()
    // Azimuth value & Cardinal text
    Text(
        text = AngleUnit.format(heading, angleUnit),
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )
    if (isCompassAccuracyLow(currentOrientation.accuracy)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Низкая точность компаса",
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "Калибр.",
                color = Color(0xFFFFB74D),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Text(
            text = cardinalName(heading),
            color = Color(0xFFB0BEC5),
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}


internal fun isCompassAccuracyLow(accuracy: Int): Boolean {
    return accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
}

/**
 * Draws the dial rotated against the heading, so the ring turns while the fixed
 * top arrow marks the direction the device is pointing.
 */
private fun DrawScope.drawCompassDial(
    headingDeg: Double,
    pointMarkers: List<CompassPointMarker> = emptyList()
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = size.minDimension / 2f * 0.82f

    // Fixed indicator arrow at the top
    val arrowColor = Color(0xFF9FA8FF)
    val arrowTop = cy - radius - 34f
    drawLine(
        color = arrowColor,
        start = Offset(cx, arrowTop),
        end = Offset(cx, cy - radius + 6f),
        strokeWidth = 8f
    )
    drawLine(arrowColor, Offset(cx, arrowTop), Offset(cx - 14f, arrowTop + 22f), strokeWidth = 8f)
    drawLine(arrowColor, Offset(cx, arrowTop), Offset(cx + 14f, arrowTop + 22f), strokeWidth = 8f)

    val labelPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
        textAlign = AndroidPaint.Align.CENTER
    }
    val northPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(255, 154, 154)
        textSize = 42f
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = AndroidPaint.Align.CENTER
    }
    val degPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(200, 200, 200)
        textSize = 26f
        isAntiAlias = true
        textAlign = AndroidPaint.Align.CENTER
    }
    val numberPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 22f
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = AndroidPaint.Align.CENTER
        setShadowLayer(2f, 0f, 1f, android.graphics.Color.BLACK)
    }

    rotate(degrees = -headingDeg.toFloat(), pivot = Offset(cx, cy)) {
        // Tick marks every 2 degrees, long ticks every 30
        for (deg in 0 until 360 step 2) {
            val isMajor = deg % 30 == 0
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            val outer = radius
            val inner = if (isMajor) radius - 26f else radius - 12f
            val color = if (deg == 0) Color(0xFFFF9A9A) else Color.White.copy(alpha = 0.85f)

            drawLine(
                color = color,
                start = Offset(cx + (cos(rad) * inner).toFloat(), cy + (sin(rad) * inner).toFloat()),
                end = Offset(cx + (cos(rad) * outer).toFloat(), cy + (sin(rad) * outer).toFloat()),
                strokeWidth = if (isMajor) 6f else 2.5f
            )

            if (isMajor) {
                val tr = radius + 28f
                val tx = cx + (cos(rad) * tr).toFloat()
                val ty = cy + (sin(rad) * tr).toFloat() + 9f
                drawContext.canvas.nativeCanvas.drawText(deg.toString(), tx, ty, degPaint)
            }
        }

        // Cardinal letters inside the ring
        val cardinals = listOf(0 to "С", 90 to "В", 180 to "Ю", 270 to "З")
        cardinals.forEach { (deg, label) ->
            val rad = Math.toRadians(deg.toDouble() - 90.0)
            val lr = radius - 72f
            val lx = cx + (cos(rad) * lr).toFloat()
            val ly = cy + (sin(rad) * lr).toFloat() + 12f
            drawContext.canvas.nativeCanvas.drawText(
                label, lx, ly,
                if (deg == 0) northPaint else labelPaint
            )
        }

        // Nearest waypoints markers on the dial
        pointMarkers.forEach { marker ->
            val rad = Math.toRadians(marker.azimuthDeg - 90.0)
            val markerRadius = radius - 6f
            val mx = cx + (cos(rad) * markerRadius).toFloat()
            val my = cy + (sin(rad) * markerRadius).toFloat()
            drawCircle(color = Color(marker.colorArgb), radius = 14f, center = Offset(mx, my))
            drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 14f, center = Offset(mx, my), style = Stroke(1.5f))
            drawContext.canvas.nativeCanvas.drawText(marker.index.toString(), mx, my + 8f, numberPaint)
        }
    }
}
