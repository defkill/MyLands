package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.map.DownloadEstimate
import com.example.map.DownloadProgress
import com.example.map.RegionDownloader
import com.example.map.TileSource
import java.util.Locale

/**
 * Configure and launch a download of the selected map region.
 *
 * Shows what the job will actually cost before it starts — tile count, size and rough duration —
 * because the throttling that keeps providers from blocking us makes large areas take hours, and
 * that should be a conscious decision rather than a surprise.
 */
@Composable
fun RegionDownloadDialog(
    availableSources: List<TileSource>,
    estimateFor: (minZoom: Int, maxZoom: Int, layerCount: Int) -> DownloadEstimate?,
    onStart: (minZoom: Int, maxZoom: Int, sources: List<TileSource>) -> Unit,
    onDismiss: () -> Unit
) {
    var minZoom by remember { mutableStateOf(12) }
    var maxZoom by remember { mutableStateOf(16) }
    val selected = remember { mutableStateListOf<TileSource>().apply { availableSources.firstOrNull()?.let { add(it) } } }

    val estimate = estimateFor(minZoom, maxZoom, selected.size.coerceAtLeast(1))

    // Each request is spaced out, plus a longer pause every few hundred tiles.
    val seconds = estimate?.let {
        (it.tileCount * RegionDownloader.DEFAULT_DELAY_MS / 1000.0) +
            (it.tileCount / RegionDownloader.LONG_PAUSE_EVERY) * (RegionDownloader.LONG_PAUSE_MS / 1000.0)
    } ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161E28),
        title = {
            Text("ЗАГРУЗКА ОБЛАСТИ", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {

                Text("Слои", color = Color(0xFF90A4AE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                availableSources.forEach { source ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = selected.contains(source),
                            onCheckedChange = { checked ->
                                if (checked) selected.add(source) else selected.remove(source)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00897B))
                        )
                        Text(source.name, color = Color.White, fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Масштабы: $minZoom — $maxZoom", color = Color(0xFF90A4AE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))

                Text("Минимальный (обзор)", color = Color(0xFF607D8B), fontSize = 11.sp)
                Slider(
                    value = minZoom.toFloat(),
                    onValueChange = {
                        minZoom = it.toInt()
                        if (minZoom > maxZoom) maxZoom = minZoom
                    },
                    valueRange = 6f..18f,
                    steps = 11,
                    modifier = Modifier.testTag("min_zoom_slider")
                )

                Text("Максимальный (детализация)", color = Color(0xFF607D8B), fontSize = 11.sp)
                Slider(
                    value = maxZoom.toFloat(),
                    onValueChange = {
                        maxZoom = it.toInt()
                        if (maxZoom < minZoom) minZoom = maxZoom
                    },
                    valueRange = 6f..19f,
                    steps = 12,
                    modifier = Modifier.testTag("max_zoom_slider")
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (estimate != null) {
                    Surface(color = Color(0xFF1B2733), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Тайлов: %d • примерно %.0f МБ",
                                    estimate.tileCount,
                                    estimate.approxMegabytes
                                ),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Время: примерно ${formatDuration(seconds)}",
                                color = Color(0xFFFFB74D),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Загрузка намеренно медленная, чтобы сервер карт не заблокировал доступ. " +
                                    "Уже загруженные тайлы пропускаются, поэтому прерванную загрузку можно продолжить.",
                                color = Color(0xFF78909C),
                                fontSize = 10.sp
                            )
                        }
                    }

                    if (estimate.tileCount > 50_000) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Очень большая область. Уменьшите максимальный масштаб или площадь — " +
                                "иначе загрузка растянется на сутки.",
                            color = Color(0xFFEF9A9A),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(minZoom, maxZoom, selected.toList()) },
                enabled = selected.isNotEmpty() && (estimate?.tileCount ?: 0) > 0,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier.testTag("start_region_download_button")
            ) {
                Text("Начать", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = Color.Gray) }
        }
    )
}

/**
 * Live progress of a running region download.
 */
@Composable
fun RegionDownloadProgressDialog(
    progress: DownloadProgress,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        containerColor = Color(0xFF161E28),
        title = { Text("Загрузка карты", color = Color.White, fontSize = 15.sp) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${progress.done} из ${progress.total} • ${progress.currentLayer}",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.total > 0) progress.done.toFloat() / progress.total.toFloat() else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF00E5FF)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Загружено ${progress.downloaded} • пропущено ${progress.skipped} • ошибок ${progress.failed}",
                    color = Color(0xFF78909C),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Сверните это окно — карта останется доступной, а прогресс будет " +
                        "виден на кнопке «Файлы». Можно выключить экран, загрузка продолжится.",
                    color = Color(0xFF607D8B),
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onMinimize,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B)),
                modifier = Modifier.testTag("minimize_download_button")
            ) {
                Text("Свернуть", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOpenFiles) {
                    Text("Файлы", color = Color(0xFF90CAF9))
                }
                TextButton(onClick = onCancel) {
                    Text("Остановить", color = Color(0xFFEF9A9A))
                }
            }
        }
    )
}

private fun formatDuration(seconds: Double): String {
    val h = (seconds / 3600).toInt()
    val m = ((seconds % 3600) / 60).toInt()
    return when {
        h > 0 -> "$h ч $m мин"
        m > 0 -> "$m мин"
        else -> "меньше минуты"
    }
}
