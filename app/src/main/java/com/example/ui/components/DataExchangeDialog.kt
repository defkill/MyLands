package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import com.example.service.MapBackupService
import com.example.util.SafeZipExtraction
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
    var copyProgress by remember { mutableFloatStateOf(0f) }
    var copyStatusText by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val activeMbtiles by viewModel.activeMbtiles.collectAsState()
    val hasOfflineOrntpack by viewModel.hasOfflineOrntpack.collectAsState()
    val activeTileSource by viewModel.activeTileSource.collectAsState()
    val availableTileSources by viewModel.availableTileSources.collectAsState()

    // Backup / Restore / Merge foreground service states
    val isBackupServiceRunning by MapBackupService.isRunning.collectAsState()
    val backupServiceProgress by MapBackupService.progress.collectAsState()
    val backupServiceResult by MapBackupService.lastResult.collectAsState()

    // Universal map file picker (.mbtiles or .orntpack / .zip) with automatic detection
    // Holds the packed file until the user has picked a destination folder.
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }

    // Multi-selection & merge states for MBTiles maps
    var selectedMbtilesPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var orderedMbtilesList by remember { mutableStateOf<List<com.example.map.MbtilesTileSource>>(emptyList()) }
    var showMergeConfirmDialog by remember { mutableStateOf(false) }
    var mergeOutputFileName by remember { mutableStateOf("merged_map.mbtiles") }
    var deleteSourcesAfterMerge by remember { mutableStateOf(false) }
    var pendingSourcesToOfferDelete by remember { mutableStateOf<List<File>?>(null) }
    var showPostMergeDeleteDialog by remember { mutableStateOf(false) }

    // Sync ordered list when availableTileSources changes
    LaunchedEffect(availableTileSources) {
        val currentMbtiles = availableTileSources.filterIsInstance<com.example.map.MbtilesTileSource>()
        val existingPaths = currentMbtiles.map { it.file.absolutePath }.toSet()
        val kept = orderedMbtilesList.filter { it.file.absolutePath in existingPaths }
        val added = currentMbtiles.filter { m -> kept.none { it.file.absolutePath == m.file.absolutePath } }
        orderedMbtilesList = kept + added
        selectedMbtilesPaths = selectedMbtilesPaths.filter { it in existingPaths }.toSet()
    }

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

    // React to service completion or error
    LaunchedEffect(backupServiceResult) {
        backupServiceResult?.let { result ->
            MapBackupService.consumeResult()
            if (result.success) {
                if (result.type == MapBackupService.OperationType.Backup && result.outputFile != null) {
                    pendingSaveFile = result.outputFile
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
                    saveMapLauncher.launch("maps_backup_$stamp.zip")
                } else if (result.type == MapBackupService.OperationType.Restore) {
                    Toast.makeText(context, "Успешно восстановлено файлов карт: ${result.count}", Toast.LENGTH_LONG).show()
                    viewModel.restoreSavedOfflineMaps()
                    onDismiss()
                } else if (result.type == MapBackupService.OperationType.Merge) {
                    viewModel.restoreSavedOfflineMaps()
                    if (result.outputFile != null) {
                        val attached = viewModel.attachMbtilesFile(result.outputFile)
                        if (attached != null) {
                            viewModel.setTileSource(attached)
                        }
                    }
                    Toast.makeText(
                        context,
                        "Карты успешно объединены в файл '${result.outputFile?.name}'!",
                        Toast.LENGTH_LONG
                    ).show()
                    val sourcesToDelete = pendingSourcesToOfferDelete
                    if (!sourcesToDelete.isNullOrEmpty()) {
                        showPostMergeDeleteDialog = true
                    }
                }
            } else {
                val msg = result.error ?: "Операция не выполнена"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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
                    val rawDisplayName = queryDisplayName(context, uri)
                    val safeDisplayName = sanitizeFileName(rawDisplayName, "elevation.hgt")
                    val cacheDir = context.cacheDir
                    val temp = SafeZipExtraction.resolveSafely(cacheDir, safeDisplayName)
                        ?: throw SecurityException("Небезопасный путь к файлу: $rawDisplayName")

                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    val added = viewModel.importElevationFile(temp, safeDisplayName)
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
                copyProgress = 0f
                copyStatusText = "Подготовка к импорту..."
                var targetFileRef: File? = null
                try {
                    val rawFileName = getFileName(context, uri)
                    val isMbtiles = rawFileName.lowercase().endsWith(".mbtiles")
                    val isGpkg = rawFileName.lowercase().endsWith(".gpkg")
                    val isDirectMap = isMbtiles || isGpkg
                    val safeFileName = sanitizeFileName(
                        rawFileName,
                        if (isMbtiles) "map.mbtiles" else if (isGpkg) "map.gpkg" else "offline.orntpack"
                    )
                    val targetDir = if (isDirectMap) {
                        File(context.filesDir, "maps").apply { mkdirs() }
                    } else {
                        File(context.filesDir, "packages").apply { mkdirs() }
                    }
                    val targetFile = File(targetDir, safeFileName)
                    targetFileRef = targetFile

                    if (!targetFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        throw SecurityException("Небезопасный путь к файлу: $rawFileName")
                    }

                    // 1. Check source size and available disk space
                    val sourceSize = withContext(Dispatchers.IO) {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(OpenableColumns.SIZE)
                            if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                        } ?: -1L
                    }

                    val freeBytes = targetDir.usableSpace
                    if (sourceSize > 0 && freeBytes < sourceSize + (100L * 1024 * 1024)) {
                        val needMb = sourceSize / (1024 * 1024)
                        val freeMb = freeBytes / (1024 * 1024)
                        Toast.makeText(
                            context,
                            "Недостаточно места: нужно ~$needMb МБ, свободно $freeMb МБ",
                            Toast.LENGTH_LONG
                        ).show()
                        isProcessing = false
                        copyProgress = 0f
                        copyStatusText = null
                        return@launch
                    }

                    // 2. Stream copy on IO thread with progress reporting
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(targetFile).use { output ->
                                val buffer = ByteArray(1 shl 20) // 1 MB buffer
                                var copied = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    copied += read
                                    if (sourceSize > 0) {
                                        val p = (copied.toFloat() / sourceSize).coerceIn(0f, 1f)
                                        val copiedMb = copied / (1024 * 1024)
                                        val totalMb = sourceSize / (1024 * 1024)
                                        withContext(Dispatchers.Main) {
                                            copyProgress = p
                                            copyStatusText = "Копирование: $copiedMb из $totalMb МБ (${(p * 100).toInt()}%)"
                                        }
                                    }
                                }
                            }
                        } ?: throw java.io.IOException("Не удалось открыть выбранный файл")
                    }

                    copyStatusText = "Анализ формата карты..."
                    val format = withContext(Dispatchers.IO) {
                        com.example.map.OfflineMapDetector.detectFromFile(targetFile)
                    }

                    if (format == OfflineMapFormat.MBTILES) {
                        copyStatusText = "Подключение MBTiles базы данных..."
                        val source = withContext(Dispatchers.IO) {
                            viewModel.attachMbtilesFile(targetFile)
                        }
                        if (source != null) {
                            val typeLabel = if (source.metadata.isVector) "векторная" else "растровая"
                            Toast.makeText(
                                context,
                                "Карта MBTiles '${source.name}' ($typeLabel) подключена!",
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Не удалось открыть MBTiles базу данных", Toast.LENGTH_LONG).show()
                        }
                    } else if (format == OfflineMapFormat.GEOPACKAGE) {
                        copyStatusText = "Построение пространственного индекса (R-Tree)..."
                        val source: com.example.map.GeoPackageTileSource? = withContext(Dispatchers.IO) {
                            try {
                                com.example.map.vector.GeoPackageIndexer.buildSpatialIndex(targetFile) { layerName: String, done: Int, total: Int ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        if (total > 0) {
                                            copyProgress = done.toFloat() / total
                                            copyStatusText = "Индексация: $layerName ($done/$total)"
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("DataExchangeDialog", "Spatial index build issue: ${e.message}")
                            }
                            viewModel.attachGpkgFile(targetFile)
                        }
                        if (source != null) {
                            val indexMode = if (source.isBtreeFallback) " (B-Tree fallback - медленный режим)" else " (R-Tree)"
                            Toast.makeText(
                                context,
                                "Векторная карта GeoPackage '${source.name}' подключена$indexMode",
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Не удалось открыть файл GeoPackage", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        copyStatusText = "Интеграция пакета тайлов..."
                        val added = withContext(Dispatchers.IO) {
                            viewModel.importOrntpackMerging(targetFile)
                        }
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
                    targetFileRef?.let { file ->
                        runCatching { if (file.exists()) file.delete() }
                    }
                    Toast.makeText(context, "Ошибка импорта карты: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                    copyProgress = 0f
                    copyStatusText = null
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val sourceSize = withContext(Dispatchers.IO) {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                            val idx = c.getColumnIndex(OpenableColumns.SIZE)
                            if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                        } ?: -1L
                    }
                    val freeBytes = context.filesDir.usableSpace
                    if (sourceSize > 0 && freeBytes < sourceSize + (50L * 1024 * 1024)) {
                        val needMb = sourceSize / (1024 * 1024)
                        val freeMb = freeBytes / (1024 * 1024)
                        Toast.makeText(
                            context,
                            "Недостаточно места: нужно ~$needMb МБ, свободно $freeMb МБ",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    // Start background foreground service for restore
                    MapBackupService.startRestore(context, uri)
                    Toast.makeText(context, "Восстановление карт запущено в фоне", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка запуска восстановления: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
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
                val showProgress = isProcessing || isBackupServiceRunning
                if (showProgress) {
                    val effectiveProgress = if (isBackupServiceRunning) {
                        backupServiceProgress?.percentage ?: 0f
                    } else copyProgress

                    val effectiveStatusText = if (isBackupServiceRunning) {
                        backupServiceProgress?.statusText ?: "Операция с картами в фоне..."
                    } else copyStatusText

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (effectiveProgress > 0f) {
                            LinearProgressIndicator(
                                progress = { effectiveProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF81C784),
                                trackColor = Color(0xFF2E3B4E)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFF81C784),
                                trackColor = Color(0xFF2E3B4E)
                            )
                        }
                        effectiveStatusText?.let { status ->
                            Text(
                                text = status,
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (isBackupServiceRunning) {
                            TextButton(
                                onClick = { MapBackupService.cancel(context) },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Отменить операцию", color = Color(0xFFFF8A80), fontSize = 11.sp)
                            }
                        }
                    }
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
                                        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val gpxFile = File(exportsDir, "tactical_navigation_export.gpx")
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
                                        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val kmlFile = File(exportsDir, "tactical_navigation_export.kml")
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
                            text = "ОФЛАЙН-КАРТЫ (.MBTILES, .GPKG И .ORNTPACK)",
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Поддерживаются .mbtiles, векторные .gpkg (OGC GeoPackage с R-Tree) и архивы .orntpack.",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )

                        // GeoPackage layers section
                        val gpkgSources = availableTileSources.filterIsInstance<com.example.map.GeoPackageTileSource>()
                        if (gpkgSources.isNotEmpty()) {
                            Text(
                                text = "ИМПОРТИРОВАННЫЕ GEOPACKAGE (.GPKG) (${gpkgSources.size})",
                                color = Color(0xFF64B5F6),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )

                            gpkgSources.forEach { gpkg ->
                                val isActive = activeTileSource.id == gpkg.id
                                val sizeMb = (gpkg.file.length() / (1024.0 * 1024.0))

                                Surface(
                                    color = Color(0x15FFFFFF),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isActive) Color(0xFF81C784) else Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = gpkg.name,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (isActive) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Surface(
                                                            color = Color(0xFF2E7D32),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                "АКТИВНА",
                                                                color = Color.White,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                val indexBadge = if (gpkg.isBtreeFallback) "⚠ B-Tree (медленный)" else "R-Tree"
                                                Text(
                                                    text = "Векторные слои OSM • $indexBadge • ${String.format(java.util.Locale.US, "%.1f МБ", sizeMb)}",
                                                    color = if (gpkg.isBtreeFallback) Color(0xFFFFB74D) else Color(0xFF90A4AE),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (!isActive) {
                                                TextButton(
                                                    onClick = {
                                                        viewModel.attachGpkgFile(gpkg.file)
                                                        viewModel.setTileSource(gpkg)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("Сделать активной", color = Color(0xFF81C784), fontSize = 10.sp)
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    viewModel.deleteOfflineMapFiles(listOf(gpkg.file))
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("Удалить", color = Color(0xFFE57373), fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Status indicator for active offline maps & imported MBTiles list
                        Text(
                            text = "ИМПОРТИРОВАННЫЕ СЛОИ MBTILES (${orderedMbtilesList.size})",
                            color = Color(0xFF81C784),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        if (orderedMbtilesList.isEmpty()) {
                            Surface(
                                color = Color(0x11FFFFFF),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Нет импортированных карт MBTiles. Нажмите кнопку ниже для импорта.",
                                    color = Color(0xFF90A4AE),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        } else {
                            Text(
                                text = "Отметьте 2+ карты для объединения в один файл. Кнопками ↑ / ↓ настройте порядок приоритета (карта снизу перекрывает верхние при наложении).",
                                color = Color(0xFFB0BEC5),
                                fontSize = 10.sp
                            )

                            orderedMbtilesList.forEachIndexed { index, mbtiles ->
                                val isSelected = selectedMbtilesPaths.contains(mbtiles.file.absolutePath)
                                val isActive = activeTileSource.id == mbtiles.id
                                val isVec = mbtiles.metadata.isVector
                                val typeLabel = if (isVec) "Вектор" else "Растр"
                                val sizeMb = (mbtiles.file.length() / (1024.0 * 1024.0))

                                Surface(
                                    color = if (isSelected) Color(0x2200897B) else Color(0x15FFFFFF),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isActive) Color(0xFF81C784) else if (isSelected) Color(0xFF00897B) else Color(0x33FFFFFF)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    selectedMbtilesPaths = if (checked) {
                                                        selectedMbtilesPaths + mbtiles.file.absolutePath
                                                    } else {
                                                        selectedMbtilesPaths - mbtiles.file.absolutePath
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = mbtiles.name,
                                                        color = Color.White,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (isActive) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Surface(
                                                            color = Color(0xFF2E7D32),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                "АКТИВНА",
                                                                color = Color.White,
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "Зум: ${mbtiles.minZoom}..${mbtiles.maxZoom} • $typeLabel • ${String.format(java.util.Locale.US, "%.1f МБ", sizeMb)}",
                                                    color = Color(0xFF90A4AE),
                                                    fontSize = 10.sp
                                                )
                                            }

                                            // Reorder buttons for priority
                                            IconButton(
                                                onClick = {
                                                    if (index > 0) {
                                                        val mutable = orderedMbtilesList.toMutableList()
                                                        val item = mutable.removeAt(index)
                                                        mutable.add(index - 1, item)
                                                        orderedMbtilesList = mutable
                                                    }
                                                },
                                                enabled = index > 0,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ArrowUpward,
                                                    contentDescription = "Выше в списке",
                                                    tint = if (index > 0) Color.White else Color(0x33FFFFFF),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    if (index < orderedMbtilesList.size - 1) {
                                                        val mutable = orderedMbtilesList.toMutableList()
                                                        val item = mutable.removeAt(index)
                                                        mutable.add(index + 1, item)
                                                        orderedMbtilesList = mutable
                                                    }
                                                },
                                                enabled = index < orderedMbtilesList.size - 1,
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ArrowDownward,
                                                    contentDescription = "Ниже в списке (выше приоритет)",
                                                    tint = if (index < orderedMbtilesList.size - 1) Color.White else Color(0x33FFFFFF),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (!isActive) {
                                                TextButton(
                                                    onClick = {
                                                        viewModel.attachMbtilesFile(mbtiles.file)
                                                        viewModel.setTileSource(mbtiles)
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("Сделать активной", color = Color(0xFF81C784), fontSize = 10.sp)
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    viewModel.deleteOfflineMapFiles(listOf(mbtiles.file))
                                                },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text("Удалить", color = Color(0xFFE57373), fontSize = 10.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // Selection & Merge section
                            val selectedSources = orderedMbtilesList.filter { selectedMbtilesPaths.contains(it.file.absolutePath) }
                            val hasVector = selectedSources.any { it.metadata.isVector }
                            val hasRaster = selectedSources.any { !it.metadata.isVector }
                            val isFormatCompatible = selectedSources.size >= 2 && !(hasVector && hasRaster)

                            if (selectedSources.isNotEmpty()) {
                                if (selectedSources.size < 2) {
                                    Surface(
                                        color = Color(0x22FFB74D),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Выберите ещё как минимум одну карту для слияния (выбрано: 1)",
                                            color = Color(0xFFFFB74D),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                } else if (hasVector && hasRaster) {
                                    Surface(
                                        color = Color(0x22E57373),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, Color(0xFFE57373)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Нельзя слить растровую и векторную карты вместе. Выберите файлы только одного типа.",
                                            color = Color(0xFFFF8A80),
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        color = Color(0x2200897B),
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(1.dp, Color(0xFF00897B)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = "Готово к слиянию: ${selectedSources.size} карт (${if (hasVector) "Вектор" else "Растр"}).",
                                                color = Color(0xFF80CBC4),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "При совпадении тайлов карта снизу списка перекроет предыдущие.",
                                                color = Color(0xFFB0BEC5),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        showMergeConfirmDialog = true
                                    },
                                    enabled = isFormatCompatible && !isProcessing && !isBackupServiceRunning,
                                    modifier = Modifier.fillMaxWidth().testTag("merge_maps_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                                ) {
                                    Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Объединить выбранные в один файл (${selectedSources.size})", fontWeight = FontWeight.Bold)
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
                                    try {
                                        val totalBytes = viewModel.calculateFullMapBackupSize()
                                        if (totalBytes <= 0L) {
                                            Toast.makeText(context, "Нет карт для резервной копии.", Toast.LENGTH_LONG).show()
                                            return@launch
                                        }

                                        val freeBytes = context.cacheDir.usableSpace
                                        if (freeBytes < totalBytes + (50L * 1024 * 1024)) {
                                            val needMb = totalBytes / (1024 * 1024)
                                            val freeMb = freeBytes / (1024 * 1024)
                                            Toast.makeText(
                                                context,
                                                "Недостаточно места: нужно ~$needMb МБ, свободно $freeMb МБ",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            return@launch
                                        }

                                        val backupFile = File(context.cacheDir, "maps_full_backup.zip")
                                        MapBackupService.startBackup(context, backupFile)
                                        Toast.makeText(context, "Резервное копирование запущено в фоне", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Ошибка резервного копирования: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isProcessing && !isBackupServiceRunning,
                            modifier = Modifier.fillMaxWidth().testTag("full_backup_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF4FC3F7))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Полная резервная копия карт")
                        }

                        OutlinedButton(
                            onClick = {
                                restoreBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            },
                            enabled = !isProcessing && !isBackupServiceRunning,
                            modifier = Modifier.fillMaxWidth().testTag("restore_backup_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF4FC3F7))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Восстановить из резервной копии")
                        }

                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    isProcessing = true
                                    try {
                                        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val packFile = File(exportsDir, "tactical_map_region.orntpack")
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
                                        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
                                        val packFile = File(exportsDir, "tactical_map_region.orntpack")
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

    if (showMergeConfirmDialog) {
        val selectedSources = orderedMbtilesList.filter { selectedMbtilesPaths.contains(it.file.absolutePath) }
        AlertDialog(
            onDismissRequest = { showMergeConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MergeType, contentDescription = null, tint = Color(0xFF81C784))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Объединение карт", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Будет создана единая база MBTiles из выбранных источников (${selectedSources.size} шт.).",
                        color = Color(0xFFCFD8DC),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Порядок наложения (снизу перекрывает верх):",
                        color = Color(0xFFFFB74D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    selectedSources.forEachIndexed { i, src ->
                        val isTopPriority = i == selectedSources.size - 1
                        Text(
                            text = "${i + 1}. ${src.name} ${if (isTopPriority) "★ (высший приоритет)" else ""}",
                            color = if (isTopPriority) Color(0xFF81C784) else Color(0xFFB0BEC5),
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = mergeOutputFileName,
                        onValueChange = { mergeOutputFileName = it },
                        label = { Text("Имя нового файла (.mbtiles)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = deleteSourcesAfterMerge,
                            onCheckedChange = { deleteSourcesAfterMerge = it }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Удалить исходные файлы после объединения",
                            color = Color(0xFFCFD8DC),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanBase = sanitizeFileName(mergeOutputFileName.trim(), "merged_map")
                        val finalName = if (cleanBase.lowercase().endsWith(".mbtiles")) cleanBase else "$cleanBase.mbtiles"
                        val mapsDir = File(context.filesDir, "maps").apply { mkdirs() }
                        val targetFile = File(mapsDir, finalName)

                        val sourceFiles = selectedSources.map { it.file }
                        if (!deleteSourcesAfterMerge) {
                            pendingSourcesToOfferDelete = sourceFiles
                        } else {
                            pendingSourcesToOfferDelete = null
                        }

                        MapBackupService.startMerge(
                            context = context,
                            sourceFiles = sourceFiles,
                            outputFile = targetFile,
                            deleteSourcesOnSuccess = deleteSourcesAfterMerge
                        )
                        showMergeConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) {
                    Text("Объединить", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirmDialog = false }) {
                    Text("Отмена", color = Color(0xFF90A4AE))
                }
            }
        )
    }

    if (showPostMergeDeleteDialog) {
        val count = pendingSourcesToOfferDelete?.size ?: 0
        AlertDialog(
            onDismissRequest = {
                pendingSourcesToOfferDelete = null
                showPostMergeDeleteDialog = false
            },
            title = {
                Text("Очистка исходных файлов", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    text = "Слияние успешно завершено! Удалить исходные $count файлов карт, чтобы освободить место на устройстве?",
                    color = Color(0xFFCFD8DC),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingSourcesToOfferDelete?.let { files ->
                            viewModel.deleteOfflineMapFiles(files)
                        }
                        pendingSourcesToOfferDelete = null
                        showPostMergeDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Удалить исходные ($count)", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingSourcesToOfferDelete = null
                        showPostMergeDeleteDialog = false
                    }
                ) {
                    Text("Оставить как есть", color = Color(0xFF90A4AE))
                }
            }
        )
    }
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

internal fun sanitizeFileName(raw: String, fallback: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val trimmed = cleaned.trim('.', '_')
    return trimmed.ifBlank { fallback }
}
