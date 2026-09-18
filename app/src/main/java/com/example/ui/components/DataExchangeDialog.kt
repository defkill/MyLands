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

    // Backup / Restore foreground service states
    val isBackupServiceRunning by MapBackupService.isRunning.collectAsState()
    val backupServiceProgress by MapBackupService.progress.collectAsState()
    val backupServiceResult by MapBackupService.lastResult.collectAsState()

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
                    onDismiss()
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
                    val safeFileName = sanitizeFileName(
                        rawFileName,
                        if (isMbtiles) "map.mbtiles" else "offline.orntpack"
                    )
                    val targetDir = if (isMbtiles) {
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
                                        val isVec = activeMbtiles?.metadata?.isVector == true
                                        val typeLabel = if (isVec) "Векторная (MVT/Shortbread)" else "Растровая"
                                        Text("MBTiles: ${activeMbtiles?.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Text("Зум: ${activeMbtiles?.minZoom}..${activeMbtiles?.maxZoom} • $typeLabel", color = Color(0xFFB0BEC5), fontSize = 10.sp)
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
