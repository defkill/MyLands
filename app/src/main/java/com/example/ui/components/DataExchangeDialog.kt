package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var selectedTab by remember { mutableIntStateOf(0) }

    val activeMbtiles by viewModel.activeMbtiles.collectAsState()
    val hasOfflineOrntpack by viewModel.hasOfflineOrntpack.collectAsState()
    val activeTileSource by viewModel.activeTileSource.collectAsState()

    // Universal map file picker (.mbtiles or .orntpack / .zip) with automatic detection
    // Holds the packed file until the user has picked a destination folder.
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }

    // Lets the user save the package anywhere on the device (Downloads, SD card, a folder of
    // their choice) instead of only handing it to another app through the share sheet.
    val saveMapLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        val source = pendingSaveFile
        pendingSaveFile = null
        if (uri != null && source != null) {
            coroutineScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            source.inputStream().use { input -> input.copyTo(output) }
                        }
                    }
                    Toast.makeText(context, "Файл карты сохранён", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Не удалось сохранить: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Elevation tiles (.hgt or the .zip they ship in).
    val importElevationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val displayName = queryDisplayName(context, uri)
                    val temp = File(context.cacheDir, displayName.ifBlank { "elevation.hgt" })
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    val added = viewModel.importElevationFile(temp, displayName)
                    temp.delete()
                    Toast.makeText(
                        context,
                        if (added > 0) "Добавлено квадратов рельефа: $added"
                        else "Новых квадратов нет (уже загружены или формат не .hgt)",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка импорта рельефа: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

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

                    val format = com.example.map.OfflineMapDetector.detectFromFile(targetFile)

                    if (format == OfflineMapFormat.MBTILES) {
                        val source = viewModel.attachMbtilesFile(targetFile)
                        if (source != null) {
                            Toast.makeText(
                                context,
                                "Карта MBTiles '${source.name}' подключена (SQLite)!",
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Не удалось открыть .mbtiles", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // MERGE, do not just attach: an attached package stays separate from the
                        // downloaded tiles, so "pack everything" silently produced a file without
                        // it. Merging is what makes an exported map a superset of everything the
                        // device has.
                        val added = viewModel.importOrntpackMerging(targetFile)
                        if (added != null) {
                            Toast.makeText(
                                context,
                                if (added > 0) "Карта добавлена: $added новых тайлов"
                                else "Все тайлы из файла уже есть на устройстве",
                                Toast.LENGTH_LONG
                            ).show()
                            targetFile.delete()
                            onDismiss()
                        } else {
                            Toast.makeText(
                                context,
                                "Не удалось открыть файл карты (.mbtiles или .orntpack)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
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
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isProcessing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF81C784))
                }

                // 3 Section Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        label = { Text("Навигация", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2E7D32),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        label = { Text("Карты", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00695C),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        label = { Text("Рельеф", fontSize = 11.sp) },
                        leadingIcon = { Icon(Icons.Default.Terrain, contentDescription = null, modifier = Modifier.size(14.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00838F),
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                HorizontalDivider(color = Color(0xFF37474F))

                when (selectedTab) {
                    0 -> {
                        // --- НАВИГАЦИОННЫЕ ДАННЫЕ (GPX / KML) ---
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
                    }

                    1 -> {
                        // --- ОФЛАЙН КАРТЫ ---
                        Text(
                            text = "ОФЛАЙН-КАРТЫ (.MBTILES И .ORNTPACK)",
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Поддерживаются .mbtiles (QGIS, SAS.Planet) и .orntpack. Импортированный .orntpack сливается с уже сохранёнными картами.",
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

                        // Import Offline Map
                        Button(
                            onClick = { importMapLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().testTag("import_map_auto_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C))
                        ) {
                            Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Импортировать карту", fontWeight = FontWeight.Bold)
                        }

                        val cacheStats = remember(isProcessing) { viewModel.tileManager.getCacheStats() }
                        Text(
                            text = if (cacheStats.first == 0) {
                                "Карт пока нет — они сохраняются автоматически при просмотре онлайн."
                            } else {
                                String.format(
                                    java.util.Locale.US,
                                    "Сохранено %d тайлов (%.1f МБ).",
                                    cacheStats.first,
                                    cacheStats.second / 1024.0 / 1024.0
                                )
                            },
                            color = Color(0xFF78909C),
                            fontSize = 11.sp
                        )

                        // Save / Share maps
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isProcessing = true
                                    try {
                                        val packFile = File(context.cacheDir, "tactical_map_region.orntpack")
                                        val count = viewModel.packCurrentCache(packFile)
                                        if (count > 0) {
                                            pendingSaveFile = packFile
                                            val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                                                .format(java.util.Date())
                                            saveMapLauncher.launch("maps_$stamp.orntpack")
                                        } else {
                                            Toast.makeText(context, "Карт пока нет. Просмотрите нужный регион онлайн перед упаковкой.", Toast.LENGTH_LONG).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Ошибка упаковки: ${e.message}", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("save_orntpack_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF81C784))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Сохранить все карты в файл")
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isProcessing = true
                                    try {
                                        val packFile = File(context.cacheDir, "tactical_map_region.orntpack")
                                        val count = viewModel.packCurrentCache(packFile)
                                        if (count > 0) {
                                            Toast.makeText(context, "Упаковано $count тайлов", Toast.LENGTH_SHORT).show()
                                            shareFile(context, packFile, "application/zip", "Поделиться пакетом карт .orntpack")
                                        } else {
                                            Toast.makeText(context, "Карт пока нет. Просмотрите нужный регион онлайн перед упаковкой.", Toast.LENGTH_LONG).show()
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
                            Text("Отправить все карты файлом")
                        }
                    }

                    2 -> {
                        // --- РЕЛЬЕФ (SRTM / .HGT) ---
                        Text(
                            "РЕЛЬЕФ (SRTM / .HGT)",
                            color = Color(0xFF80DEEA),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        val (needed, missing) = remember(isProcessing) { viewModel.elevationTilesForCurrentView() }
                        Surface(
                            color = if (missing.isEmpty()) Color(0x2281C784) else Color(0x22FFB74D),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, if (missing.isEmpty()) Color(0xFF81C784) else Color(0xFFFFB74D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = if (missing.isEmpty()) "ДАННЫЕ ДЛЯ ТЕКУЩЕГО ЭКРАНА ЕСТЬ" else "ТРЕБУЮТСЯ ФАЙЛЫ РЕЛЬЕФА",
                                    color = if (missing.isEmpty()) Color(0xFF81C784) else Color(0xFFFFB74D),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (missing.isEmpty()) {
                                        "Квадраты: ${needed.joinToString(", ")}"
                                    } else {
                                        "Нужны: ${needed.joinToString(", ")}\nНе найдены: ${missing.joinToString(", ")}"
                                    },
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        // Big prominent button for importing elevation
                        Button(
                            onClick = { importElevationLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth().testTag("import_elevation_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00838F))
                        ) {
                            Icon(Icons.Default.Terrain, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Импортировать рельеф (.hgt / .zip)", fontWeight = FontWeight.Bold)
                        }

                        // List of available tiles
                        val available = remember(isProcessing) { viewModel.elevationEngine.availableTiles() }
                        if (available.isNotEmpty()) {
                            Text(
                                "Загружено на устройство (${available.size}): ${available.joinToString(", ")}",
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                "На устройстве пока нет ни одного файла высот.",
                                color = Color(0xFF90A4AE),
                                fontSize = 11.sp
                            )
                        }

                        Text(
                            text = "Файлы SRTM (.hgt) позволяют рассчитывать прямую видимость, высоту точек и строить профиль высот без интернета. Поддерживаются квадраты SRTM-1 (30м) и SRTM-3 (90м).",
                            color = Color(0xFF78909C),
                            fontSize = 10.sp
                        )
                    }
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

/** Shared by the data-exchange and track screens to hand a generated file to another app. */
internal fun shareFile(context: Context, file: File, mimeType: String, chooserTitle: String) {
    val authority = "${context.packageName}.fileprovider"
    val contentUri = FileProvider.getUriForFile(context, authority, file)
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
}


/**
 * File name behind a SAF uri. Local copy because the helper in the screen file is private
 * there, and this dialog must not depend on it.
 */
private fun queryDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
    } catch (_: Exception) {
    }
    return name ?: uri.lastPathSegment ?: ""
}
