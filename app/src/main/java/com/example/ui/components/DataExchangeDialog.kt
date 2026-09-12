package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.map.OfflineMapFormat
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@Composable
fun DataExchangeDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val activeMbtiles by viewModel.activeMbtiles.collectAsState()
    val hasOfflineOrntpack by viewModel.hasOfflineOrntpack.collectAsState()
    val activeTileSource by viewModel.activeTileSource.collectAsState()

    // Universal map file picker (.mbtiles or .orntpack / .zip) with automatic detection
    val importMapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val fileName = getFileName(context, uri)
                    val isMbtiles = fileName.lowercase().endsWith(".mbtiles")
                    val targetDir = if (isMbtiles) {
                        File(context.filesDir, "maps").apply { mkdirs() }
                    } else {
                        File(context.filesDir, "packages").apply { mkdirs() }
                    }
                    val targetFile = File(targetDir, fileName.ifBlank { if (isMbtiles) "map.mbtiles" else "offline.orntpack" })

                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val (format, success) = viewModel.importOfflineMapFile(targetFile)
                    if (success) {
                        when (format) {
                            OfflineMapFormat.MBTILES -> {
                                val name = viewModel.activeMbtiles.value?.name ?: targetFile.nameWithoutExtension
                                Toast.makeText(context, "Карта MBTiles '$name' подключена (SQLite)!", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                            OfflineMapFormat.ORNTPACK -> {
                                Toast.makeText(context, "Офлайн-пакет .orntpack подключен в кэш!", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                            OfflineMapFormat.UNKNOWN -> {
                                Toast.makeText(context, "Офлайн-карта подключена!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }
                    } else {
                        Toast.makeText(context, "Не удалось открыть файл карты (.mbtiles или .orntpack)", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка импорта карты: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    // File picker launcher for importing GPX/KML
    val importFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val contentResolver = context.contentResolver
                    val fileName = getFileName(context, uri).lowercase()
                    val isKml = fileName.endsWith(".kml")
                    val inputStream = contentResolver.openInputStream(uri)

                    if (inputStream != null) {
                        val (wps, rtes) = viewModel.importNavigationData(inputStream, isKml)
                        statusMessage = "Успешно импортировано: $wps точек, $rtes маршрутов"
                        Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    statusMessage = "Ошибка импорта: ${e.localizedMessage}"
                    Toast.makeText(context, statusMessage, Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E2632),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ImportExport, contentDescription = null, tint = Color(0xFF81C784))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Обмен данными и картами", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Импорт/экспорт навигационных данных (GPX, KML) и подключение офлайн-карт (.mbtiles, .orntpack).",
                    color = Color(0xFFB0BEC5),
                    fontSize = 12.sp
                )

                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF81C784))
                }

                HorizontalDivider(color = Color(0xFF37474F))

                Text(
                    text = "НАВИГАЦИОННЫЕ ДАННЫЕ (GPX / KML)",
                    color = Color(0xFF81C784),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                // 1. Export to GPX
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isProcessing = true
                            try {
                                val gpxFile = File(context.cacheDir, "tactical_navigation_export.gpx")
                                viewModel.exportToGpx(gpxFile)
                                shareFile(context, gpxFile, "application/gpx+xml", "Экспорт путевых точек и маршрутов в GPX")
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка экспорта GPX: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("export_gpx_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF81C784))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Экспорт точек и маршрутов (GPX)")
                }

                // 2. Export to KML
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isProcessing = true
                            try {
                                val kmlFile = File(context.cacheDir, "tactical_navigation_export.kml")
                                viewModel.exportToKml(kmlFile)
                                shareFile(context, kmlFile, "application/vnd.google-earth.kml+xml", "Экспорт путевых точек и маршрутов в KML")
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка экспорта KML: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("export_kml_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF4FC3F7))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Экспорт точек и маршрутов (KML)")
                }

                // 3. Import GPX / KML
                Button(
                    onClick = {
                        importFileLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("import_gpx_kml_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Импорт из файла (GPX / KML)", fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(color = Color(0xFF37474F))

                Text(
                    text = "ОФЛАЙН-КАРТЫ (.MBTILES И .ORNTPACK)",
                    color = Color(0xFFFFB74D),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "• MBTiles: база SQLite для сторонних растровых карт (QGIS, SAS).\n• .orntpack: архивы тайлов для обмена между приложениями.",
                    color = Color(0xFF90A4AE),
                    fontSize = 11.sp
                )

                // Status indicator for active offline maps
                if (activeMbtiles != null) {
                    Surface(
                        color = Color(0x2281C784),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF81C784)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = Color(0xFF81C784), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("MBTiles: ${activeMbtiles?.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Зум: ${activeMbtiles?.minZoom}..${activeMbtiles?.maxZoom} • Прямой SQLite доступ", color = Color(0xFFB0BEC5), fontSize = 10.sp)
                            }
                            if (activeTileSource.id == activeMbtiles?.id) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Активна", tint = Color(0xFF81C784), modifier = Modifier.size(18.dp))
                            } else {
                                TextButton(
                                    onClick = { activeMbtiles?.let { viewModel.setTileSource(it) } },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("Включить", color = Color(0xFF81C784), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                if (hasOfflineOrntpack) {
                    Surface(
                        color = Color(0x22FFB74D),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Пакет .orntpack подключен в кэш тайлов", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }

                // 4. Import Offline Map with auto-detection (.mbtiles or .orntpack)
                Button(
                    onClick = {
                        importMapLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth().testTag("import_map_auto_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Импортировать карту (.mbtiles / .orntpack)", fontWeight = FontWeight.Bold)
                }

                // Format quick selection buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { importMapLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF81C784))
                    ) {
                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(".mbtiles (SQLite)", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { importMapLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFB74D))
                    ) {
                        Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(".orntpack (ZIP)", fontSize = 11.sp)
                    }
                }

                // 5. Pack current tile cache to .orntpack
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            isProcessing = true
                            try {
                                val packFile = File(context.cacheDir, "tactical_map_region.orntpack")
                                val count = viewModel.packCurrentCache(packFile)
                                if (count > 0) {
                                    Toast.makeText(context, "Упаковано $count тайлов в .orntpack", Toast.LENGTH_SHORT).show()
                                    shareFile(context, packFile, "application/zip", "Поделиться пакетом карт .orntpack")
                                } else {
                                    Toast.makeText(context, "Кэш тайлов пуст. Просмотрите нужный регион перед упаковкой.", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка упаковки: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("pack_orntpack_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFFFFB74D))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Собрать кэш в .orntpack")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Закрыть", color = Color(0xFF81C784))
            }
        }
    )
}

private fun getFileName(context: Context, uri: Uri): String {
    var name: String? = null
    if (uri.scheme == "content") {
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        name = cursor.getString(idx)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    if (name == null) {
        name = uri.lastPathSegment
    }
    return name ?: "imported_map"
}

private fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
    val authority = "${context.packageName}.fileprovider"
    val contentUri = FileProvider.getUriForFile(context, authority, file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
}
