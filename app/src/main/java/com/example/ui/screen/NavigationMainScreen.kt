package com.example.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.geodesy.GeodesyEngine
import com.example.map.MapTileType
import com.example.map.OfflineMapFormat
import com.example.map.TileSource
import com.example.model.*
import com.example.ui.components.*
import kotlinx.coroutines.launch
import com.example.ui.map.TacticalMapView
import com.example.viewmodel.MainViewModel
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationMainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val mapCenter by viewModel.mapCenter.collectAsStateWithLifecycle()
    val mapZoom by viewModel.mapZoom.collectAsStateWithLifecycle()
    val isFollowingLocation by viewModel.isFollowingLocation.collectAsStateWithLifecycle()
    val gpsLocation by viewModel.gpsLocation.collectAsStateWithLifecycle()
    val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
    val orientationData by viewModel.orientationData.collectAsStateWithLifecycle()
    val pdrState by viewModel.pdrState.collectAsStateWithLifecycle()
    val activeTileSource by viewModel.activeTileSource.collectAsStateWithLifecycle()
    val availableTileSources by viewModel.availableTileSources.collectAsStateWithLifecycle()
    val userPreferences by viewModel.userPreferences.collectAsStateWithLifecycle()

    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    val triangulationState by viewModel.triangulationState.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val visibleTrackIds by viewModel.visibleTrackIds.collectAsStateWithLifecycle()
    val savedTrackPoints by viewModel.visibleTrackPoints.collectAsStateWithLifecycle()
    val showRawTracks by viewModel.showRawTracks.collectAsStateWithLifecycle()
    val isPositionEstimated by viewModel.isPositionEstimated.collectAsStateWithLifecycle()
    val packImportProgress by viewModel.packImportProgress.collectAsStateWithLifecycle()
    val isSelectingRegion by viewModel.isSelectingRegion.collectAsStateWithLifecycle()
    val regionSelection by viewModel.regionSelection.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val terrainElevation by viewModel.terrainElevation.collectAsStateWithLifecycle()
    val losResult by viewModel.losResult.collectAsStateWithLifecycle()
    val isCalculatingLos by viewModel.isCalculatingLos.collectAsStateWithLifecycle()
    val isPickingLosTarget by viewModel.isPickingLosTarget.collectAsStateWithLifecycle()
    val losObserver by viewModel.losObserver.collectAsStateWithLifecycle()
    val observerHeight by viewModel.observerHeight.collectAsStateWithLifecycle()
    val targetHeight by viewModel.targetHeight.collectAsStateWithLifecycle()
    val sightMode by viewModel.sightMode.collectAsStateWithLifecycle()
    val routeProfile by viewModel.routeProfile.collectAsStateWithLifecycle()
    val isCalculatingProfile by viewModel.isCalculatingProfile.collectAsStateWithLifecycle()

    // Best available position: a live fix when there is one, otherwise the step-counted
    // estimate. Bearings and distances to waypoints stay useful with GPS switched off,
    // which is exactly the situation this app exists for.
    val effectiveLocation = gpsLocation ?: pdrState.lastEstimatedPosition
    val blindDistanceMeters by viewModel.blindDistanceMeters.collectAsStateWithLifecycle()
    val selectedWaypoint by viewModel.selectedWaypoint.collectAsStateWithLifecycle()
    val candidatePoint by viewModel.candidatePoint.collectAsStateWithLifecycle()
    val activeMapTool by viewModel.activeMapTool.collectAsStateWithLifecycle()
    val activeTrack by viewModel.activeTrack.collectAsStateWithLifecycle()
    val currentTrackPoints by viewModel.currentTrackPoints.collectAsStateWithLifecycle()

    val rulerState by viewModel.rulerState.collectAsStateWithLifecycle()
    val routeBuilderState by viewModel.routeBuilderState.collectAsStateWithLifecycle()
    val isCoordinateModalOpen by viewModel.isCoordinateModalOpen.collectAsStateWithLifecycle()

    var showAddWaypointDialog by remember { mutableStateOf(false) }
    var showCompassScreen by remember { mutableStateOf(false) }
    var showTriangulationDialog by remember { mutableStateOf(false) }
    var showTracksSheet by remember { mutableStateOf(false) }
    var showClearTilesConfirm by remember { mutableStateOf(false) }
    var showRegionDownloadDialog by remember { mutableStateOf(false) }
    var showQrDialogFor by remember { mutableStateOf<Pair<String, GeoPoint>?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }
    var scannedPoint by remember { mutableStateOf<com.example.data.qr.QrPayload.DecodedPoint?>(null) }
    var showLosDialog by remember { mutableStateOf(false) }
    var showRouteProfileFor by remember { mutableStateOf<com.example.data.entity.RouteEntity?>(null) }
    // Progress can be collapsed so the map stays usable during multi-hour downloads.
    var isDownloadMinimized by remember { mutableStateOf(false) }
    var editingWaypoint by remember { mutableStateOf<com.example.data.entity.WaypointEntity?>(null) }
    var showSavedWaypointsSheet by remember { mutableStateOf(false) }
    var showSettlementSearchSheet by remember { mutableStateOf(false) }
    var showMapSourceMenu by remember { mutableStateOf(false) }
    var showDataExchangeDialog by remember { mutableStateOf(false) }
    var showBatteryOptimizationDialog by remember { mutableStateOf(false) }
    var hasDismissedBatteryOptPrompt by remember { mutableStateOf(false) }

    // Location permission can be missing even when the system location toggle is on:
    // the OS switch and the per-app grant are separate things. Allow re-requesting it
    // straight from the GPS button instead of dead-ending on "Требуется разрешение GPS".
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.locationTracker.startListening()
            Toast.makeText(context, "Разрешение получено, поиск спутников…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                context,
                "Без разрешения на геолокацию ваша позиция не отображается",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Без уведомлений статус записи не виден в шторке", Toast.LENGTH_SHORT).show()
        }
    }

    val importScope = rememberCoroutineScope()

    // File picker launcher for offline maps (.orntpack / .zip or .mbtiles)
    val offlinePackageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val displayName = getFileNameFromUri(context, uri)
                val isMbtiles = displayName.lowercase().endsWith(".mbtiles")
                val targetDir = if (isMbtiles) File(context.filesDir, "maps").apply { mkdirs() } else context.cacheDir
                val tempFile = File(targetDir, displayName.ifBlank { if (isMbtiles) "offline_map.mbtiles" else "imported_offline.orntpack" })
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val format = com.example.map.OfflineMapDetector.detectFromFile(tempFile)

                if (format == OfflineMapFormat.MBTILES) {
                    val source = viewModel.attachMbtilesFile(tempFile)
                    if (source != null) {
                        Toast.makeText(
                            context,
                            "Карта MBTiles '${source.name}' подключена!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(context, "Не удалось открыть .mbtiles", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // .orntpack is MERGED into local storage rather than attached read-only, so
                    // its tiles join everything already downloaded and future exports include
                    // both. That is what lets a shared map file keep growing as it is passed on.
                    importScope.launch {
                        val added = viewModel.importOrntpackMerging(tempFile)
                        if (added != null) {
                            Toast.makeText(
                                context,
                                if (added > 0) "Карта добавлена: $added новых тайлов"
                                else "Все тайлы из файла уже есть в памяти",
                                Toast.LENGTH_LONG
                            ).show()
                            tempFile.delete()
                        } else {
                            Toast.makeText(
                                context,
                                "Не удалось открыть файл карты (.mbtiles или .orntpack)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Dynamic coordinate bundle at the center crosshair
    val crosshairBundle = remember(mapCenter.latitude, mapCenter.longitude, mapCenter.altitude) {
        viewModel.getCoordinateBundle(mapCenter.latitude, mapCenter.longitude)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF121820)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. Full-Screen Interactive Tactical Canvas Map
            TacticalMapView(
                center = mapCenter,
                zoom = mapZoom,
                onCenterChanged = { viewModel.setMapCenter(it) },
                onZoomChanged = { viewModel.setMapZoom(it) },
                userLocation = effectiveLocation,
                orientationData = orientationData,
                tileSource = activeTileSource,
                tileManager = viewModel.tileManager,
                waypoints = waypoints,
                selectedWaypoint = selectedWaypoint,
                onWaypointSelected = { wp ->
                    // The callback is nullable: a tap on empty space clears the selection.
                    if (isPickingLosTarget && wp != null) {
                        // While sighting, tapping a waypoint chooses target B instead of
                        // opening its editor.
                        viewModel.pickLosTarget(wp.toGeoPoint())
                    } else {
                        viewModel.selectWaypoint(wp)
                        editingWaypoint = wp
                    }
                },
                candidatePoint = candidatePoint,
                activeMapTool = activeMapTool,
                onMapTapped = { pt ->
                    if (isPickingLosTarget) viewModel.pickLosTarget(pt)
                    else viewModel.onMapTapped(pt)
                },
                rulerState = rulerState,
                onRulerPointChanged = { p1, p2 -> viewModel.updateRulerPoints(p1, p2) },
                routeBuilderState = routeBuilderState,
                savedRoutes = routes,
                triangulationState = triangulationState,
                savedTrackPoints = savedTrackPoints,
                losResult = losResult,
                isSelectingRegion = isSelectingRegion,
                regionSelection = regionSelection,
                onRegionSelected = { viewModel.setRegionSelection(it) },
                activeTrackPoints = currentTrackPoints,
                angleUnit = userPreferences.defaultAngleUnit
            )

            // 2. Top Tactical Header Overlay
            //
            // Scrolls horizontally instead of distributing chips across the full width: with
            // SpaceBetween on a narrow phone every added chip squeezed the others and pushed
            // the last one (НП) off screen entirely.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Compass Rose & Heading Pill
                Surface(
                    onClick = { showCompassScreen = true },
                    color = Color(0xDD161C24),
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 4.dp,
                    modifier = Modifier.testTag("compass_chip_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Navigation,
                            contentDescription = "Компас",
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = AngleUnit.format(orientationData.trueHeadingDeg.toDouble(), userPreferences.defaultAngleUnit),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Top Actions: Angle Unit Toggle & Map Layer Button
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Angle Unit Toggle (Degrees 360° <-> Mils 60-00)
                    Surface(
                        onClick = { viewModel.toggleAngleUnit() },
                        color = Color(0xDD161C24),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("toggle_angle_unit_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (userPreferences.defaultAngleUnit == AngleUnit.DEGREES_360) "360°" else "60-00",
                                color = Color(0xFFFFD54F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Map Layer Selector
                    Box {
                        Surface(
                            onClick = { showMapSourceMenu = true },
                            color = Color(0xDD161C24),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.testTag("map_layer_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Layers, contentDescription = "Слои", tint = Color.White, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(activeTileSource.name, color = Color.White, fontSize = 12.sp, maxLines = 1)
                            }
                        }

                        DropdownMenu(
                            expanded = showMapSourceMenu,
                            onDismissRequest = { showMapSourceMenu = false },
                            containerColor = Color(0xFF1E2632)
                        ) {
                            availableTileSources.forEach { src ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val icon = when (src.type) {
                                                MapTileType.MBTILES -> Icons.Default.Storage
                                                MapTileType.SATELLITE -> Icons.Default.Public
                                                MapTileType.TOPO -> Icons.Default.Terrain
                                                else -> Icons.Default.Map
                                            }
                                            Icon(
                                                icon,
                                                contentDescription = null,
                                                tint = if (src == activeTileSource) Color(0xFF81C784) else Color(0xFF90A4AE),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (src.type == MapTileType.MBTILES) "[MBTiles] ${src.name}" else src.name,
                                                color = if (src == activeTileSource) Color(0xFF81C784) else Color.White,
                                                fontWeight = if (src == activeTileSource) FontWeight.Bold else FontWeight.Normal
                                            )
                                            if (src == activeTileSource) {
                                                Spacer(modifier = Modifier.weight(1f))
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = "Выбрано",
                                                    tint = Color(0xFF81C784),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.setTileSource(src)
                                        showMapSourceMenu = false
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFF37474F))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            tint = Color(0xFF81C784),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Загрузить область", color = Color(0xFF81C784))
                                    }
                                },
                                onClick = {
                                    viewModel.startRegionSelection()
                                    showMapSourceMenu = false
                                    Toast.makeText(
                                        context,
                                        "Выделите область на карте: проведите пальцем по диагонали",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            )
                            HorizontalDivider(color = Color(0xFF37474F))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            tint = Color(0xFFFFB74D),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Удалить тайлы слоя", color = Color(0xFFFFB74D))
                                    }
                                },
                                onClick = {
                                    showMapSourceMenu = false
                                    showClearTilesConfirm = true
                                }
                            )
                            HorizontalDivider(color = Color(0xFF37474F))
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.FileOpen,
                                            contentDescription = null,
                                            tint = Color(0xFF81C784),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Импорт карты (.mbtiles / .orntpack)", color = Color(0xFF81C784))
                                    }
                                },
                                onClick = {
                                    showMapSourceMenu = false
                                    offlinePackageLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }

                    // Data Exchange (GPX / KML / .orntpack).
                    // While a region download runs this chip doubles as its progress indicator
                    // and as the way back into the (minimised) progress dialog, so downloading
                    // no longer blocks the map for hours.
                    val activeDownload = downloadProgress
                    val downloadFraction = activeDownload
                        ?.takeIf { it.total > 0 }
                        ?.let { it.done.toFloat() / it.total.toFloat() }
                        ?: 0f

                    Surface(
                        onClick = {
                            if (activeDownload != null) {
                                isDownloadMinimized = false
                            } else {
                                showDataExchangeDialog = true
                            }
                        },
                        color = Color(0xDD161C24),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("data_exchange_button")
                    ) {
                        Box {
                            // Green fill showing how far the download has got.
                            if (activeDownload != null) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(RoundedCornerShape(20.dp))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(downloadFraction)
                                            .background(Color(0x662E7D32))
                                    )
                                }
                            }

                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (activeDownload != null) Icons.Default.Download else Icons.Default.ImportExport,
                                contentDescription = "Файлы",
                                tint = Color(0xFF81C784),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (activeDownload != null) {
                                    "${(downloadFraction * 100).toInt()}%"
                                } else "Файлы",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                        }
                    }

                    // Saved Waypoints Sheet button
                    Surface(
                        onClick = { showSavedWaypointsSheet = true },
                        color = Color(0xDD161C24),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("open_waypoints_list_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Точки", tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("Точки", color = Color.White, fontSize = 11.sp)
                        }
                    }

                    // Settlement Search button
                    Surface(
                        onClick = { showSettlementSearchSheet = true },
                        color = Color(0xDD161C24),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("open_settlements_search_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.LocationCity, contentDescription = "НП", tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("НП", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }

            // 2b. Top-Left Zoom Controls
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallFloatingActionButton(
                    onClick = { viewModel.zoomIn() },
                    containerColor = Color(0xFF263238),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("zoom_in_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Приблизить")
                }

                SmallFloatingActionButton(
                    onClick = { viewModel.zoomOut() },
                    containerColor = Color(0xFF263238),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("zoom_out_button")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Отдалить")
                }
            }

            // 3. Right Floating Action Toolbar
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Center on GPS / Follow Location
                FloatingActionButton(
                    onClick = {
                        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                        if (!hasPermission) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            viewModel.toggleFollowLocation()
                            if (gpsLocation == null) {
                                Toast.makeText(
                                    context,
                                    "Поиск спутников… под открытым небом это занимает до минуты",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp).testTag("gps_follow_button"),
                    containerColor = if (isFollowingLocation) Color(0xFF2E7D32) else Color(0xFF263238),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Мое местоположение", modifier = Modifier.size(22.dp))
                }

                // Add Waypoint
                FloatingActionButton(
                    onClick = { showAddWaypointDialog = true },
                    modifier = Modifier.size(48.dp).testTag("add_waypoint_button"),
                    containerColor = Color(0xFF00897B),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.AddLocation, contentDescription = "Добавить точку", modifier = Modifier.size(22.dp))
                }

                // Triangulation (rays by azimuth/distance)
                FloatingActionButton(
                    onClick = {
                        viewModel.startTriangulation()
                        showTriangulationDialog = true
                    },
                    modifier = Modifier.size(48.dp).testTag("triangulation_button"),
                    containerColor = Color(0xFF263238),
                    contentColor = Color(0xFFFF7043)
                ) {
                    Icon(Icons.Default.ChangeHistory, contentDescription = "Триангуляция", modifier = Modifier.size(22.dp))
                }

                // Ruler Tool Toggle
                FloatingActionButton(
                    onClick = { viewModel.toggleRuler() },
                    modifier = Modifier.size(48.dp).testTag("ruler_tool_button"),
                    containerColor = if (rulerState.isActive) Color(0xFFF57F17) else Color(0xFF263238),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.Straighten, contentDescription = "Линейка", modifier = Modifier.size(22.dp))
                }

                // Route Builder Tool Toggle ("Построение маршрута по точкам")
                FloatingActionButton(
                    onClick = {
                        if (routeBuilderState.isActive) {
                            viewModel.cancelRouteBuilder()
                        } else {
                            viewModel.startRouteBuilder()
                        }
                    },
                    modifier = Modifier.size(48.dp).testTag("route_builder_button"),
                    containerColor = if (routeBuilderState.isActive) Color(0xFF00ACC1) else Color(0xFF263238),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Default.AltRoute, contentDescription = "Построение маршрута", modifier = Modifier.size(22.dp))
                }

                // Track Recording (Start / Stop Foreground Service)
                val isRecording = (activeTrack != null && activeTrack!!.isActive) || viewModel.isTrackingServiceRunning.collectAsStateWithLifecycle().value
                FloatingActionButton(
                    onClick = { showTracksSheet = true },
                    modifier = Modifier.size(48.dp).testTag("track_recording_button"),
                    containerColor = if (isRecording) Color(0xFFD32F2F) else Color(0xFF263238),
                    contentColor = Color.White
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = "Запись трека",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 4. Bottom Controls / Panels
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                // Active Ruler Overlay or Selected Target (Candidate / Waypoint) Overlay
                if (rulerState.isActive) {
                    RulerOverlay(
                        rulerState = rulerState,
                        angleUnit = userPreferences.defaultAngleUnit,
                        onClose = { viewModel.toggleRuler() }
                    )
                } else if (candidatePoint != null || selectedWaypoint != null) {
                    val targetPt: GeoPoint = candidatePoint ?: selectedWaypoint!!.toGeoPoint()
                    val userPt: GeoPoint = gpsLocation ?: mapCenter
                    val targetRuler = remember(userPt, targetPt) {
                        RulerState.calculate(userPt, targetPt)
                    }
                    // Terrain height for the selected/candidate point, fetched once per point.
                    var pointElevation by remember(targetPt) { mutableStateOf<Double?>(null) }
                    LaunchedEffect(targetPt) {
                        viewModel.elevationForPoint(targetPt) { pointElevation = it }
                    }
                    val elevationSuffix = pointElevation?.let { " • H: ${it.toInt()} м" } ?: ""

                    val title = if (candidatePoint != null) {
                        "ТОЧКА-КАНДИДАТ$elevationSuffix"
                    } else {
                        "ТОЧКА: ${selectedWaypoint?.name}$elevationSuffix"
                    }
                    val icon = if (candidatePoint != null) Icons.Default.Adjust else Icons.Default.Place
                    val iconTint = if (candidatePoint != null) Color(0xFFFF9800) else Color(0xFF00E5FF)

                    RulerOverlay(
                        rulerState = targetRuler,
                        angleUnit = userPreferences.defaultAngleUnit,
                        title = title,
                        icon = icon,
                        iconTint = iconTint,
                        onReverseAzimuthClick = {
                            val revStr = targetRuler.formatReverseAzimuth(userPreferences.defaultAngleUnit)
                            Toast.makeText(context, "Обратный азимут: $revStr", Toast.LENGTH_SHORT).show()
                        },
                        onClose = {
                            if (candidatePoint != null) {
                                viewModel.clearCandidatePoint()
                            } else {
                                viewModel.clearSelectedWaypoint()
                            }
                        }
                    )
                }

                // Visibility check overlay: stays on the map so the ray remains visible.
                if (isPickingLosTarget || losResult != null) {
                    VisibilityCheckOverlay(
                        result = losResult,
                        isPickingTarget = isPickingLosTarget,
                        isCalculating = isCalculatingLos,
                        onCreateObstacleWaypoint = {
                            viewModel.createObstacleWaypoint()
                            Toast.makeText(context, "Точка на препятствии создана", Toast.LENGTH_SHORT).show()
                        },
                        onOpenSettings = { showLosDialog = true },
                        onClose = { viewModel.cancelVisibilityCheck() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // Active Route Builder Panel
                if (routeBuilderState.isActive) {
                    RouteBuilderPanel(
                        routeState = routeBuilderState,
                        allWaypoints = waypoints,
                        savedRoutes = routes,
                        angleUnit = userPreferences.defaultAngleUnit,
                        onToggleWaypoint = { viewModel.toggleWaypointInRoute(it) },
                        onRouteNameChanged = { viewModel.setRouteName(it) },
                        onSaveRoute = {
                            viewModel.saveCurrentRoute()
                            Toast.makeText(context, "Маршрут сохранен!", Toast.LENGTH_SHORT).show()
                        },
                        onLoadRoute = { viewModel.loadRouteIntoBuilder(it) },
                        onShowProfile = { route ->
                            showRouteProfileFor = route
                            viewModel.calculateRouteProfile(route)
                        },
                        onDeleteRoute = {
                            viewModel.deleteRoute(it)
                            Toast.makeText(context, "Маршрут удалён", Toast.LENGTH_SHORT).show()
                        },
                        onCancel = { viewModel.cancelRouteBuilder() }
                    )
                }

                // Main Tactical Coordinate Bottom Bar
                CoordinateBottomBar(
                    crosshairPoint = mapCenter,
                    bundle = crosshairBundle,
                    selectedCoordSystem = userPreferences.defaultCoordinateSystem,
                    angleUnit = userPreferences.defaultAngleUnit,
                    orientationData = orientationData,
                    gpsStatus = gpsStatus,
                    pdrState = pdrState,
                    terrainElevation = terrainElevation,
                    onClick = { viewModel.openCoordinateModal() }
                )
            }
        }
    }

    // Full Coordinate Modal Card
    if (isCoordinateModalOpen) {
        CoordinateModalSheet(
            bundle = crosshairBundle,
            selectedSystem = userPreferences.defaultCoordinateSystem,
            onSystemSelected = { sys -> viewModel.setCoordinateSystem(sys) },
            onJumpToPoint = { pt -> viewModel.setMapCenter(pt) },
            onShowQr = {
                showQrDialogFor = "Точка на карте" to mapCenter
                viewModel.closeCoordinateModal()
            },
            onScanQr = {
                viewModel.closeCoordinateModal()
                showQrScanner = true
            },
            onDismiss = { viewModel.closeCoordinateModal() }
        )
    }

    // Saved Waypoints Bottom Sheet
    if (showSavedWaypointsSheet) {
        SavedWaypointsSheet(
            waypoints = waypoints,
            userLocation = effectiveLocation,
            angleUnit = userPreferences.defaultAngleUnit,
            coordinateSystem = userPreferences.defaultCoordinateSystem,
            onSelectWaypoint = { wp ->
                viewModel.setMapCenter(wp.toGeoPoint())
                viewModel.selectWaypoint(wp)
            },
            onDeleteWaypoint = { id ->
                viewModel.deleteWaypoint(id)
            },
            onDismiss = { showSavedWaypointsSheet = false }
        )
    }

    // Recorded Tracks Bottom Sheet
    if (showTracksSheet) {
        val isRecordingNow = (activeTrack != null && activeTrack!!.isActive) ||
            viewModel.isTrackingServiceRunning.collectAsStateWithLifecycle().value
        val exportScope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = { showTracksSheet = false },
            containerColor = Color(0xFF10151C)
        ) {
            TracksSheet(
                tracks = tracks,
                visibleTrackIds = visibleTrackIds,
                isRecording = isRecordingNow,
                showRawTracks = showRawTracks,
                serviceRunning = viewModel.isTrackingServiceRunning.collectAsStateWithLifecycle().value,
                servicePointCount = viewModel.serviceRecordedPointsCount.collectAsStateWithLifecycle().value,
                hasWakeUpStepSensor = viewModel.stepDetectorManager.hasWakeUpStepSensor,
                hasActivityPermission = viewModel.stepDetectorManager.hasActivityRecognitionPermission(),
                onToggleRawTracks = { viewModel.toggleRawTracks() },
                onStartRecording = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (!hasDismissedBatteryOptPrompt) {
                        showTracksSheet = false
                        showBatteryOptimizationDialog = true
                    } else {
                        viewModel.startTrackRecording()
                        Toast.makeText(context, "Фоновая запись трека запущена", Toast.LENGTH_SHORT).show()
                    }
                },
                onStopRecording = {
                    viewModel.stopTrackRecording()
                    Toast.makeText(context, "Запись трека остановлена и сохранена", Toast.LENGTH_SHORT).show()
                },
                onToggleVisibility = { viewModel.toggleTrackVisibility(it) },
                onCenterOnTrack = {
                    viewModel.centerOnTrack(it)
                    showTracksSheet = false
                },
                onRename = { track, newName -> viewModel.renameTrack(track, newName) },
                onDelete = {
                    viewModel.deleteTrack(it)
                    Toast.makeText(context, "Трек удалён", Toast.LENGTH_SHORT).show()
                },
                onShare = { track ->
                    exportScope.launch {
                        val file = viewModel.exportTrackToGpxFile(track)
                        if (file == null) {
                            Toast.makeText(context, "В треке нет записанных точек", Toast.LENGTH_SHORT).show()
                        } else {
                            shareFile(context, file, "application/gpx+xml", "Отправить трек")
                        }
                    }
                },
                onDismiss = { showTracksSheet = false }
            )
        }
    }

    // Settlement Search Bottom Sheet
    if (showSettlementSearchSheet) {
        SettlementSearchSheet(
            repository = viewModel.settlementRepository,
            userLocation = effectiveLocation,
            onSelectSettlement = { settlement ->
                val pt = GeoPoint(settlement.latitude, settlement.longitude)
                viewModel.setMapCenter(pt)
                viewModel.setMapZoom(14.0)
                viewModel.onMapTapped(pt)
            },
            onDismiss = { showSettlementSearchSheet = false }
        )
    }

    // Add Waypoint Dialog
    editingWaypoint?.let { wp ->
        EditWaypointDialog(
            waypoint = wp,
            onSave = { name, desc ->
                viewModel.updateWaypointDetails(wp, name, desc)
                editingWaypoint = null
                Toast.makeText(context, "Точка обновлена", Toast.LENGTH_SHORT).show()
            },
            onStartTriangulation = {
                viewModel.startTriangulation()
                editingWaypoint = null
                showTriangulationDialog = true
            },
            onShowQr = {
                showQrDialogFor = wp.name to wp.toGeoPoint()
                editingWaypoint = null
            },
            onCheckVisibility = {
                // The tapped waypoint is the OBSERVER; the target is chosen next.
                viewModel.beginVisibilityCheck(wp.toGeoPoint())
                editingWaypoint = null
                Toast.makeText(context, "Выберите цель на карте", Toast.LENGTH_SHORT).show()
            },
            onDelete = {
                viewModel.deleteWaypoint(wp.id)
                editingWaypoint = null
                Toast.makeText(context, "Точка удалена", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { editingWaypoint = null }
        )
    }

    if (showTriangulationDialog) {
        TriangulationDialog(
            state = triangulationState,
            allWaypoints = waypoints,
            preselected = selectedWaypoint,
            onAddRay = { wp, az, dist -> viewModel.addTriangulationRay(wp, az, dist) },
            onRemoveRay = { viewModel.removeTriangulationRay(it) },
            onSaveIntersection = { name ->
                viewModel.saveTriangulationPoint(name)
                showTriangulationDialog = false
                Toast.makeText(context, "Точка пересечения создана", Toast.LENGTH_SHORT).show()
            },
            onSaveRayEnd = { ray, name ->
                viewModel.saveRayEndpoint(ray, name)
                Toast.makeText(context, "Точка на конце отрезка создана", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showTriangulationDialog = false
                viewModel.cancelTriangulation()
            }
        )
    }

    // Import progress: merging a large package takes a while and must not look frozen.
    packImportProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = { },
            containerColor = Color(0xFF161E28),
            title = { Text("Импорт карты", color = Color.White, fontSize = 15.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (progress.total > 0) {
                            "Добавлено ${progress.done} из ${progress.total} тайлов"
                        } else {
                            "Чтение файла…"
                        },
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF00E5FF)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF00E5FF)
                        )
                    }
                }
            },
            confirmButton = {}
        )
    }

    // Deleting tiles is now permanent: they live in app storage, not a disposable cache.
    // Losing a downloaded region to a stray tap would only be discovered offline in the field.
    // Bar shown while an area is being selected on the map.
    if (isSelectingRegion) {
        Box(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
                color = Color(0xEE10151C),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = if (regionSelection == null) {
                            "Проведите пальцем по диагонали, чтобы выделить область"
                        } else {
                            val b = regionSelection!!
                            val widthKm = GeodesyEngine.distanceMeters(
                                b.minLat, b.minLon, b.minLat, b.maxLon
                            ) / 1000.0
                            val heightKm = GeodesyEngine.distanceMeters(
                                b.minLat, b.minLon, b.maxLat, b.minLon
                            ) / 1000.0
                            String.format(java.util.Locale.US, "Область %.1f × %.1f км", widthKm, heightKm)
                        },
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showRegionDownloadDialog = true },
                            enabled = regionSelection != null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) { Text("Далее") }
                        OutlinedButton(
                            onClick = { viewModel.cancelRegionSelection() },
                            modifier = Modifier.weight(1f)
                        ) { Text("Отмена", color = Color.Gray) }
                    }
                }
            }
        }
    }

    if (showRegionDownloadDialog) {
        RegionDownloadDialog(
            availableSources = availableTileSources.filter { it.urlTemplate.isNotBlank() },
            estimateFor = { minZ, maxZ, layers -> viewModel.estimateRegion(minZ, maxZ, layers) },
            onStart = { minZ, maxZ, sources ->
                showRegionDownloadDialog = false
                isDownloadMinimized = false
                viewModel.startRegionDownload(minZ, maxZ, sources)
                viewModel.cancelRegionSelection()
            },
            onDismiss = { showRegionDownloadDialog = false }
        )
    }

    // Report the outcome once, from a composable (main thread). Calling Toast straight from the
    // download coroutine crashed the app: Toast may only be shown on a thread with a Looper.
    val downloadResult by viewModel.downloadResult.collectAsStateWithLifecycle()
    LaunchedEffect(downloadResult) {
        val result = downloadResult ?: return@LaunchedEffect
        val blocked = if (result.blockedSources.isNotEmpty()) {
            " Не отвечали: ${result.blockedSources.joinToString(", ")}."
        } else ""

        val msg = when {
            result.cancelled ->
                "Загрузка остановлена. Загружено ${result.downloaded} тайлов.$blocked"
            result.abortedByProvider ->
                "Серверы карт не отвечают — загрузить не удалось.$blocked Повторите позже."
            else ->
                "Готово: ${result.downloaded} новых, ${result.skipped} уже были.$blocked"
        }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        viewModel.consumeDownloadResult()
    }

    downloadProgress?.let { progress ->
        if (!isDownloadMinimized) {
            RegionDownloadProgressDialog(
                progress = progress,
                onMinimize = { isDownloadMinimized = true },
                onOpenFiles = {
                    isDownloadMinimized = true
                    showDataExchangeDialog = true
                },
                onCancel = {
                    viewModel.cancelRegionDownload()
                    isDownloadMinimized = false
                }
            )
        }
    }

    // Reset the collapsed flag when a download ends, so the next one opens normally.
    LaunchedEffect(downloadProgress == null) {
        if (downloadProgress == null) isDownloadMinimized = false
    }

    showQrDialogFor?.let { (name, point) ->
        QrCodeDialog(
            name = name,
            point = point,
            coordinateSystem = userPreferences.defaultCoordinateSystem,
            onDismiss = { showQrDialogFor = null }
        )
    }

    if (showQrScanner) {
        QrScannerDialog(
            onPointScanned = { decoded ->
                showQrScanner = false
                scannedPoint = decoded
            },
            onDismiss = { showQrScanner = false }
        )
    }

    scannedPoint?.let { decoded ->
        ScannedPointDialog(
            decoded = decoded,
            onSave = {
                viewModel.addWaypointAt(
                    name = decoded.name,
                    latitude = decoded.latitude,
                    longitude = decoded.longitude,
                    altitude = decoded.altitudeMeters,
                    description = "Получена по QR",
                    colorArgb = 0xFF00BCD4.toInt()
                )
                scannedPoint = null
                Toast.makeText(context, "Точка сохранена", Toast.LENGTH_SHORT).show()
            },
            onShowOnMap = {
                viewModel.setMapCenter(decoded.toGeoPoint())
                viewModel.setMapZoom(15.0)
                scannedPoint = null
            },
            onDismiss = { scannedPoint = null }
        )
    }

    if (showLosDialog) {
        LineOfSightDialog(
            result = losResult,
            isCalculating = isCalculatingLos,
            observerHeight = observerHeight,
            targetHeight = targetHeight,
            sightMode = sightMode,
            onObserverHeightChange = { viewModel.setObserverHeight(it) },
            onTargetHeightChange = { viewModel.setTargetHeight(it) },
            onSightModeChange = { viewModel.setSightMode(it) },
            onRecalculate = { viewModel.calculateLineOfSight() },
            onGoToObstacle = {
                viewModel.goToObstacle()
                showLosDialog = false
            },
            onCreateObstacleWaypoint = {
                viewModel.createObstacleWaypoint()
                Toast.makeText(context, "Ориентир на вершине создан", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                showLosDialog = false
                viewModel.clearLineOfSight()
            }
        )
    }

    showRouteProfileFor?.let { route ->
        RouteProfileDialog(
            profile = routeProfile,
            routeName = route.name,
            isCalculating = isCalculatingProfile,
            onDismiss = {
                showRouteProfileFor = null
                viewModel.clearRouteProfile()
            }
        )
    }

    if (showClearTilesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearTilesConfirm = false },
            containerColor = Color(0xFF161E28),
            title = { Text("Удалить загруженные тайлы?", color = Color.White, fontSize = 15.sp) },
            text = {
                Text(
                    "Карты текущего слоя будут удалены с устройства. Без интернета восстановить " +
                        "их не получится. Импортированные из файла тайлы тоже удалятся.",
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearTileCacheForActiveSource()
                        showClearTilesConfirm = false
                        Toast.makeText(context, "Тайлы слоя удалены", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                ) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearTilesConfirm = false }) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }

    if (showCompassScreen) {
        CompassFullScreenDialog(
            orientationData = orientationData,
            position = effectiveLocation,
            coordinateSystem = userPreferences.defaultCoordinateSystem,
            angleUnit = userPreferences.defaultAngleUnit,
            isEstimated = isPositionEstimated,
            blindDistanceMeters = blindDistanceMeters,
            onCreatePoint = { pt ->
                viewModel.addWaypointAt(
                    name = "Точка ${System.currentTimeMillis() % 10000}",
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    altitude = pt.altitude,
                    description = "Создана с экрана компаса",
                    colorArgb = 0xFF00E5FF.toInt()
                )
                Toast.makeText(context, "Точка сохранена", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showCompassScreen = false }
        )
    }

    if (showAddWaypointDialog) {
        val targetPoint = candidatePoint ?: (gpsLocation ?: mapCenter)
        AddWaypointDialog(
            initialPoint = targetPoint,
            onSave = { name, desc, color ->
                if (candidatePoint != null) {
                    viewModel.saveCandidatePoint(name, desc, color)
                } else {
                    viewModel.addWaypointAt(
                        name = name,
                        latitude = targetPoint.latitude,
                        longitude = targetPoint.longitude,
                        altitude = targetPoint.altitude,
                        description = desc,
                        colorArgb = color
                    )
                }
                showAddWaypointDialog = false
                Toast.makeText(context, "Точка '$name' сохранена", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAddWaypointDialog = false }
        )
    }

    // Data Exchange Dialog (GPX / KML / .orntpack)
    if (showDataExchangeDialog) {
        DataExchangeDialog(
            viewModel = viewModel,
            onDismiss = { showDataExchangeDialog = false }
        )
    }

    // Battery Optimization & OEM Restrictions Dialog
    if (showBatteryOptimizationDialog) {
        val isAospIgnored = viewModel.isBatteryOptimizationIgnored()
        val mfr = Build.MANUFACTURER.uppercase()
        val model = Build.MODEL

        AlertDialog(
            onDismissRequest = { showBatteryOptimizationDialog = false },
            icon = {
                Icon(
                    Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    "Фоновая запись трека и батарея",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Для непрерывной записи трека и работы шагомера (PDR) при заблокированном телефоне требуется защита фоновой службы от остановки операционной системой.",
                        color = Color(0xFFCFD8DC),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    // 1. AOSP System Status Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E272C)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (isAospIgnored) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isAospIgnored) Color(0xFF81C784) else Color(0xFFFFB74D),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isAospIgnored) "1. Системное исключение Android: Включено" else "1. Системное исключение Android: Требуется",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                            if (!isAospIgnored) {
                                Text(
                                    "Стандартный режим энергосбережения Google Doze усыпляет GPS через пару минут после выключения экрана.",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            } catch (_: Exception) {}
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) {
                                    Text("Запросить исключение Doze", fontSize = 12.sp, color = Color(0xFF80CBC4))
                                }
                            }
                        }
                    }

                    // 2. OEM Vendor Warning Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C1E1E)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFF7043),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "2. ⚠️ Ограничения производителя (OEM)",
                                    color = Color(0xFFFFCCBC),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Text(
                                "Системный запрос Google Doze НЕ покрывает фирменные оболочки (MIUI, EMUI, One UI, ColorOS). Их встроенные 'убийцы процессов' принудительно гасят фоновую запись через 10–15 минут сна. Настройте вручную:",
                                color = Color(0xFFFFEBEE),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )

                            // Manufacturer detected badge
                            Surface(
                                color = Color(0xFF3E2723),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "Ваше устройство: $mfr $model",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color(0xFFFFAB91),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Vendor-specific guidance
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "• Xiaomi / Redmi / POCO (MIUI / HyperOS):\n" +
                                    "  1. Включить «Автозапуск» и «Автозапуск в фоновом режиме»\n" +
                                    "  2. Контроль активности -> «Нет ограничений»\n" +
                                    "  3. В меню запущенных приложений повесить замочек 🔒 на карточку Orientir",
                                    color = Color(0xFFECEFF1),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                                Text(
                                    "• Samsung (One UI):\n" +
                                    "  1. Приложения -> Orientir -> Батарея -> «Не ограничено» (Unrestricted)\n" +
                                    "  2. Батарея -> Ограничения в фоне -> убрать из «Спящих приложений»",
                                    color = Color(0xFFECEFF1),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                                Text(
                                    "• Huawei / Honor (EMUI / MagicOS):\n" +
                                    "  1. Настройки батареи -> Запуск приложений -> Orientir -> Ручное управление\n" +
                                    "  2. Включить: Автозапуск, Косвенный запуск, Работа в фоне",
                                    color = Color(0xFFECEFF1),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                                Text(
                                    "• Realme / OPPO / OnePlus (ColorOS / OxygenOS):\n" +
                                    "  1. Использование батареи -> Разрешить работу в фоновом режиме\n" +
                                    "  2. Разрешить автозапуск приложения",
                                    color = Color(0xFFECEFF1),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }

                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD84315)),
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Открыть настройки приложения (автозапуск/батарея)", fontSize = 11.sp, color = Color.White)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        hasDismissedBatteryOptPrompt = true
                        showBatteryOptimizationDialog = false
                        viewModel.startTrackRecording()
                        Toast.makeText(context, "Фоновая запись трека запущена", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Начать запись трека", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showBatteryOptimizationDialog = false
                    }
                ) {
                    Text("Отмена", color = Color(0xFF90A4AE))
                }
            },
            containerColor = Color(0xFF263238),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

private fun getFileNameFromUri(context: android.content.Context, uri: android.net.Uri): String {
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
    return name ?: "map_file"
}
