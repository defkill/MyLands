package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.elevation.LineOfSightResult
import com.example.data.elevation.RouteProfile
import com.example.data.elevation.SightMode
import com.example.data.elevation.TargetPreset
import android.graphics.Paint as AndroidPaint
import java.util.Locale

/**
 * Line-of-sight setup and result.
 *
 * Target height is a first-class control rather than a buried setting: a 50 m mast clears a hill
 * that hides a person standing in the same spot, so getting this number wrong changes the answer
 * completely.
 */
@Composable
fun LineOfSightDialog(
    result: LineOfSightResult?,
    isCalculating: Boolean,
    observerHeight: Double,
    targetHeight: Double,
    sightMode: SightMode,
    onObserverHeightChange: (Double) -> Unit,
    onTargetHeightChange: (Double) -> Unit,
    onSightModeChange: (SightMode) -> Unit,
    onRecalculate: () -> Unit,
    onGoToObstacle: () -> Unit,
    onCreateObstacleWaypoint: () -> Unit,
    onDismiss: () -> Unit
) {
    var targetHeightText by remember(targetHeight) {
        mutableStateOf(targetHeight.toInt().toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161E28),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ПРЯМАЯ ВИДИМОСТЬ", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {

                // Sight mode: light and radio do not reach equally far.
                Text("Режим", color = Color(0xFF90A4AE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SightMode.entries.forEach { mode ->
                        FilterChip(
                            selected = sightMode == mode,
                            onClick = { onSightModeChange(mode) },
                            label = { Text(mode.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00695C),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text("Высота цели", color = Color(0xFF90A4AE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TargetPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = targetHeight == preset.heightMeters,
                            onClick = {
                                onTargetHeightChange(preset.heightMeters)
                                targetHeightText = preset.heightMeters.toInt().toString()
                            },
                            label = { Text(preset.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2E7D32),
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = targetHeightText,
                        onValueChange = {
                            targetHeightText = it
                            it.toDoubleOrNull()?.let(onTargetHeightChange)
                        },
                        label = { Text("Цель, м") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("target_height_field")
                    )
                    OutlinedTextField(
                        value = observerHeight.toInt().toString(),
                        onValueChange = { it.toDoubleOrNull()?.let(onObserverHeightChange) },
                        label = { Text("Наблюдатель, м") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onRecalculate,
                    enabled = !isCalculating,
                    modifier = Modifier.fillMaxWidth().testTag("calculate_los_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) {
                    Text(if (isCalculating) "Расчёт…" else "Рассчитать", fontWeight = FontWeight.Bold)
                }

                if (result != null) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        color = if (result.isVisible) Color(0xFF12301F) else Color(0xFF3A1A1A),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                text = if (result.isVisible) "ВИДИМОСТЬ ЕСТЬ" else "ВИДИМОСТЬ ЗАКРЫТА",
                                color = if (result.isVisible) Color(0xFF69F0AE) else Color(0xFFEF9A9A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Дистанция %.2f км • наблюдатель %.0f м • цель %.0f м над уровнем моря",
                                    result.totalDistanceMeters / 1000.0,
                                    result.observerElevation,
                                    result.targetElevation
                                ),
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp
                            )

                            result.worstObstacle?.let { obstacle ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Препятствие: H=%.0f м на удалении %.2f км",
                                        obstacle.terrainMeters,
                                        obstacle.distanceMeters / 1000.0
                                    ),
                                    color = Color(0xFFFFB74D),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Перекрывает на %.0f м",
                                        obstacle.obstructionMeters
                                    ),
                                    color = Color(0xFFFFB74D),
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Подняться на %.0f м, чтобы открылось",
                                        result.requiredExtraHeightMeters
                                    ),
                                    color = Color(0xFF81C784),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (result.hasGaps) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "Часть профиля без данных рельефа — результат приблизителен",
                                    color = Color(0xFFFFB74D),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    LineOfSightChart(
                        result = result,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .testTag("los_chart")
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "SRTM — радарная модель: над лесом это высота крон, а отдельные мачты " +
                            "и постройки в ней отсутствуют.",
                        color = Color(0xFF546E7A),
                        fontSize = 10.sp
                    )
                }
            }
        },
        confirmButton = {
            if (result?.worstObstacle != null) {
                Row {
                    TextButton(onClick = onGoToObstacle) {
                        Text("К вершине", color = Color(0xFF90CAF9), fontSize = 12.sp)
                    }
                    TextButton(onClick = onCreateObstacleWaypoint) {
                        Text("Ориентир", color = Color(0xFF81C784), fontSize = 12.sp)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Color.Gray) }
        }
    )
}

/**
 * Terrain cross-section with the sight line drawn over it: green where the line clears the
 * ground, red where the ground cuts through it.
 */
@Composable
fun LineOfSightChart(result: LineOfSightResult, modifier: Modifier = Modifier) {
    val samples = result.samples
    if (samples.isEmpty()) return

    val minH = minOf(
        samples.minOf { it.effectiveTerrainMeters },
        samples.minOf { it.sightLineMeters }
    )
    val maxH = maxOf(
        samples.maxOf { it.effectiveTerrainMeters },
        samples.maxOf { it.sightLineMeters }
    )
    val span = (maxH - minH).coerceAtLeast(1.0)
    val totalD = result.totalDistanceMeters.coerceAtLeast(1.0)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun xOf(d: Double) = (d / totalD * w).toFloat()
        fun yOf(v: Double) = (h - ((v - minH) / span * h)).toFloat()

        // Terrain as a filled silhouette
        val terrainPath = Path().apply {
            moveTo(0f, h)
            samples.forEach { lineTo(xOf(it.distanceMeters), yOf(it.effectiveTerrainMeters)) }
            lineTo(w, h)
            close()
        }
        drawPath(terrainPath, Color(0xFF37474F))

        // Terrain outline
        for (i in 0 until samples.size - 1) {
            drawLine(
                color = Color(0xFF90A4AE),
                start = Offset(xOf(samples[i].distanceMeters), yOf(samples[i].effectiveTerrainMeters)),
                end = Offset(xOf(samples[i + 1].distanceMeters), yOf(samples[i + 1].effectiveTerrainMeters)),
                strokeWidth = 2f
            )
        }

        // Sight line, coloured by whether the terrain blocks it there
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            drawLine(
                color = if (a.isBlocked || b.isBlocked) Color(0xFFE53935) else Color(0xFF66BB6A),
                start = Offset(xOf(a.distanceMeters), yOf(a.sightLineMeters)),
                end = Offset(xOf(b.distanceMeters), yOf(b.sightLineMeters)),
                strokeWidth = 4f
            )
        }

        // Mark the worst obstruction
        result.worstObstacle?.let { o ->
            val x = xOf(o.distanceMeters)
            drawLine(
                color = Color(0xFFFFB74D),
                start = Offset(x, 0f),
                end = Offset(x, h),
                strokeWidth = 2f
            )
        }

        val paint = AndroidPaint().apply {
            color = android.graphics.Color.rgb(176, 190, 197)
            textSize = 24f
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText("${maxH.toInt()} м", 6f, 24f, paint)
        drawContext.canvas.nativeCanvas.drawText("${minH.toInt()} м", 6f, h - 6f, paint)
    }
}

/**
 * Elevation profile of a saved route, with climb and descent totals.
 */
@Composable
fun RouteProfileDialog(
    profile: RouteProfile?,
    routeName: String,
    isCalculating: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161E28),
        title = {
            Text("ПРОФИЛЬ: $routeName", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    isCalculating -> Text("Расчёт по рельефу…", color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    profile == null -> Text(
                        "Нет данных рельефа для этого маршрута. Импортируйте файлы .hgt через меню «Файлы».",
                        color = Color(0xFFFFB74D),
                        fontSize = 13.sp
                    )
                    else -> {
                        Text(
                            text = String.format(
                                Locale.US,
                                "Длина %.2f км • высоты %.0f…%.0f м",
                                profile.totalDistanceMeters / 1000.0,
                                profile.minElevation,
                                profile.maxElevation
                            ),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(
                                Locale.US,
                                "Подъём %.0f м • спуск %.0f м • средний уклон %.1f%%",
                                profile.totalClimb,
                                profile.totalDescent,
                                profile.averageGradientPercent
                            ),
                            color = Color(0xFF81C784),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        RouteProfileChart(
                            profile = profile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .testTag("route_profile_chart")
                        )

                        if (profile.hasGaps) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Часть маршрута без данных рельефа",
                                color = Color(0xFFFFB74D),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть", color = Color.Gray) }
        }
    )
}

@Composable
fun RouteProfileChart(profile: RouteProfile, modifier: Modifier = Modifier) {
    val samples = profile.samples
    if (samples.size < 2) return

    val minH = profile.minElevation
    val span = (profile.maxElevation - minH).coerceAtLeast(1.0)
    val totalD = profile.totalDistanceMeters.coerceAtLeast(1.0)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        fun xOf(d: Double) = (d / totalD * w).toFloat()
        fun yOf(v: Double) = (h - ((v - minH) / span * h)).toFloat()

        val path = Path().apply {
            moveTo(0f, h)
            samples.forEach { lineTo(xOf(it.first), yOf(it.second)) }
            lineTo(w, h)
            close()
        }
        drawPath(path, Color(0xFF1B3A2A))

        for (i in 0 until samples.size - 1) {
            drawLine(
                color = Color(0xFF69F0AE),
                start = Offset(xOf(samples[i].first), yOf(samples[i].second)),
                end = Offset(xOf(samples[i + 1].first), yOf(samples[i + 1].second)),
                strokeWidth = 3f
            )
        }

        val paint = AndroidPaint().apply {
            color = android.graphics.Color.rgb(176, 190, 197)
            textSize = 24f
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText("${profile.maxElevation.toInt()} м", 6f, 24f, paint)
        drawContext.canvas.nativeCanvas.drawText("${profile.minElevation.toInt()} м", 6f, h - 6f, paint)
    }
}

/**
 * On-map overlay for the visibility check: prompts for the target while one is being chosen,
 * then reports the verdict and the blocking summit.
 *
 * Kept as an overlay rather than a dialog so the map — and the ray drawn on it — stays visible
 * while the user reads the result.
 */
@Composable
fun VisibilityCheckOverlay(
    result: LineOfSightResult?,
    isPickingTarget: Boolean,
    isCalculating: Boolean,
    /** True once a target has been chosen, so a null result means missing data, not "not started". */
    hasTarget: Boolean,
    onCreateObstacleWaypoint: () -> Unit,
    onImportElevation: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("visibility_overlay"),
        color = Color(0xEE10151C),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ПРЯМАЯ ВИДИМОСТЬ",
                    color = Color(0xFF80DEEA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onOpenSettings) {
                        Text("Параметры", color = Color(0xFF90CAF9), fontSize = 12.sp)
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.Gray)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            when {
                isPickingTarget -> Text(
                    "Выберите цель: нажмите точку на карте или сохранённую точку",
                    color = Color(0xFFFFB74D),
                    fontSize = 13.sp
                )

                isCalculating -> Text("Расчёт по рельефу…", color = Color(0xFFB0BEC5), fontSize = 13.sp)

                // Target chosen but nothing came back: the area has no elevation tile. Say so
                // plainly and offer the fix, instead of leaving the user with an empty panel.
                result == null && hasTarget -> Column {
                    Text(
                        "НЕТ ФАЙЛОВ РЕЛЬЕФА (.HGT)",
                        color = Color(0xFFEF5350),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Для расчёта линии видимости импортируйте файл высот для этой зоны " +
                            "(например, N50E036.hgt) через меню «Файлы».",
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onImportElevation,
                        modifier = Modifier.fillMaxWidth().testTag("import_elevation_from_los_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                    ) {
                        Text("Импортировать рельеф", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Линия визирования на карте показана жёлтым — азимут и дистанцию " +
                            "видно и без данных рельефа.",
                        color = Color(0xFF78909C),
                        fontSize = 10.sp
                    )
                }

                result == null -> Text(
                    "Выберите цель, чтобы рассчитать видимость.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )

                else -> {
                    Text(
                        text = if (result.isVisible) "✓ ПРЯМАЯ ВИДИМОСТЬ ОТКРЫТА"
                        else "✕ ГОРИЗОНТ ЗАКРЫТ ПРЕПЯТСТВИЕМ",
                        color = if (result.isVisible) Color(0xFF69F0AE) else Color(0xFFEF5350),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format(
                            Locale.US,
                            "Дистанция %.2f км",
                            result.totalDistanceMeters / 1000.0
                        ),
                        color = Color(0xFFB0BEC5),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    result.worstObstacle?.let { o ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "Препятствие на удалении %.2f км • Высота пика: %.0f м (выше луча на %.0f м)",
                                o.distanceMeters / 1000.0,
                                o.terrainMeters,
                                o.obstructionMeters
                            ),
                            color = Color(0xFFFFB74D),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = String.format(
                                Locale.US,
                                "Подняться на %.0f м, чтобы открылось",
                                result.requiredExtraHeightMeters
                            ),
                            color = Color(0xFF81C784),
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onCreateObstacleWaypoint,
                            modifier = Modifier.fillMaxWidth().testTag("create_obstacle_waypoint_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE64A19))
                        ) {
                            Text("ПОСТАВИТЬ ТОЧКУ НА ПРЕПЯТСТВИИ", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    LineOfSightChart(
                        result = result,
                        modifier = Modifier.fillMaxWidth().height(110.dp)
                    )
                }
            }
        }
    }
}
