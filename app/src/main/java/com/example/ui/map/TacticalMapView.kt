package com.example.ui.map

import android.graphics.Bitmap
import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.example.data.elevation.LineOfSightResult
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteLeg
import com.example.data.entity.TrackPointEntity
import com.example.data.entity.WaypointEntity
import com.example.geodesy.GeodesyEngine
import com.example.map.MapProjection
import com.example.map.RegionBounds
import com.example.map.TileCoordinate
import com.example.map.TileManager
import com.example.map.TileSource
import com.example.model.*
import com.example.sensor.OrientationData
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
fun TacticalMapView(
    center: GeoPoint,
    zoom: Double,
    onCenterChanged: (GeoPoint) -> Unit,
    onZoomChanged: (Double) -> Unit,
    userLocation: GeoPoint?,
    orientationData: OrientationData,
    tileSource: TileSource,
    tileManager: TileManager,
    waypoints: List<WaypointEntity>,
    selectedWaypoint: WaypointEntity?,
    onWaypointSelected: (WaypointEntity?) -> Unit,
    candidatePoint: GeoPoint? = null,
    activeMapTool: ActiveMapTool = ActiveMapTool.NONE,
    onMapTapped: (GeoPoint) -> Unit,
    rulerState: RulerState,
    onRulerPointChanged: (GeoPoint, GeoPoint) -> Unit,
    routeBuilderState: RouteBuilderState,
    savedRoutes: List<RouteEntity> = emptyList(),
    triangulationState: TriangulationState = TriangulationState(),
    savedTrackPoints: Map<Long, List<TrackPointEntity>> = emptyMap(),
    isSelectingRegion: Boolean = false,
    regionSelection: RegionBounds? = null,
    onRegionSelected: (RegionBounds) -> Unit = {},
    losResult: LineOfSightResult? = null,
    losObserver: GeoPoint? = null,
    losTarget: GeoPoint? = null,
    activeTrackPoints: List<TrackPointEntity>,
    angleUnit: AngleUnit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var canvasSize by remember { mutableStateOf(Pair(1080f, 1920f)) }

    // Redraw trigger when tiles finish loading asynchronously
    var tileRefreshTrigger by remember { mutableStateOf(0) }

    // Tracks tiles currently being fetched so we don't spawn duplicate requests
    // for the same tile on every recomposition/frame.
    val inFlightTiles = remember { mutableSetOf<String>() }

    // Requests an async load of a tile that isn't in cache yet. Called from the draw phase,
    // so it must not block: it launches into the composition scope and bumps a refresh
    // trigger when the bitmap lands, which re-runs the Canvas draw.
    val requestTile: (TileSource, TileCoordinate) -> Unit = remember(tileManager) {
        { source, tile ->
            val key = "${source.id}/${tile.key}"
            if (inFlightTiles.add(key)) {
                coroutineScope.launch {
                    try {
                        val bmp = tileManager.getTileBitmap(source, tile)
                        if (bmp != null) {
                            tileRefreshTrigger++
                        }
                    } catch (_: Exception) {
                    } finally {
                        inFlightTiles.remove(key)
                    }
                }
            }
        }
    }

    // IMPORTANT (gesture stability):
    // pointerInput() restarts its block whenever a key changes. Using `center`/`zoom` as keys
    // restarted the handler on every frame of a drag (pan changes center -> key changes ->
    // gesture aborted mid-drag), which made the map "stick" after moving a few pixels.
    // Keys are now stable (Unit) and the latest values are read through rememberUpdatedState,
    // so the lambda always sees fresh state without being torn down.
    val currentCenter by rememberUpdatedState(center)
    val currentZoom by rememberUpdatedState(zoom)
    val currentTool by rememberUpdatedState(activeMapTool)
    val currentRulerState by rememberUpdatedState(rulerState)
    val currentWaypoints by rememberUpdatedState(waypoints)
    val currentTriangulationState by rememberUpdatedState(triangulationState)
    val onCenterChangedState by rememberUpdatedState(onCenterChanged)
    val onZoomChangedState by rememberUpdatedState(onZoomChanged)
    val onMapTappedState by rememberUpdatedState(onMapTapped)
    val onWaypointSelectedState by rememberUpdatedState(onWaypointSelected)
    val onRulerPointChangedState by rememberUpdatedState(onRulerPointChanged)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("tactical_map_canvas")
            .pointerInput(isSelectingRegion) {
                // While selecting a region the tap handler must stand down: it runs first in the
                // modifier chain and was consuming the touch to drop a candidate waypoint, so the
                // drag never reached the selection handler below.
                if (isSelectingRegion) return@pointerInput

                detectTapGestures { offset ->
                    val tappedGeo = MapProjection.screenToGeo(
                        screenX = offset.x,
                        screenY = offset.y,
                        centerLat = currentCenter.latitude,
                        centerLon = currentCenter.longitude,
                        zoom = currentZoom,
                        screenWidth = size.width.toFloat(),
                        screenHeight = size.height.toFloat()
                    )

                    if (currentTool == ActiveMapTool.RULER || currentRulerState.isActive) {
                        val rs = currentRulerState
                        if (rs.startPoint == null || rs.startPoint == rs.endPoint) {
                            onRulerPointChangedState(rs.startPoint ?: tappedGeo, tappedGeo)
                        } else {
                            onRulerPointChangedState(tappedGeo, tappedGeo)
                        }
                        return@detectTapGestures
                    }

                    // Check if a waypoint was tapped
                    val clickedWp = currentWaypoints.firstOrNull { wp ->
                        val (sx, sy) = MapProjection.geoToScreen(
                            wp.toGeoPoint(),
                            currentCenter.latitude, currentCenter.longitude,
                            currentZoom, size.width.toFloat(), size.height.toFloat()
                        )
                        val dx = sx - offset.x
                        val dy = sy - offset.y
                        dx * dx + dy * dy < 1600f // 40px radius
                    }

                    if (clickedWp != null) {
                        onWaypointSelectedState(clickedWp)
                    } else {
                        // Check if intersection point of triangulation was tapped
                        val intersectionPt = currentTriangulationState.intersectionPoint()
                        if (intersectionPt != null) {
                            val (ix, iy) = MapProjection.geoToScreen(
                                intersectionPt,
                                currentCenter.latitude, currentCenter.longitude,
                                currentZoom, size.width.toFloat(), size.height.toFloat()
                            )
                            val dx = ix - offset.x
                            val dy = iy - offset.y
                            if (dx * dx + dy * dy < 2500f) { // 50px radius around intersection crosshair
                                onMapTappedState(intersectionPt)
                                return@detectTapGestures
                            }
                        }
                        onMapTappedState(tappedGeo)
                    }
                }
            }
            .pointerInput(isSelectingRegion) {
                // Only active in selection mode, so normal panning is untouched otherwise.
                if (!isSelectingRegion) return@pointerInput

                var startGeo: GeoPoint? = null
                detectDragGestures(
                    onDragStart = { offset ->
                        startGeo = MapProjection.screenToGeo(
                            offset.x, offset.y,
                            currentCenter.latitude, currentCenter.longitude, currentZoom,
                            size.width.toFloat(), size.height.toFloat()
                        )
                    },
                    onDrag = { change, _ ->
                        val start = startGeo ?: return@detectDragGestures
                        val now = MapProjection.screenToGeo(
                            change.position.x, change.position.y,
                            currentCenter.latitude, currentCenter.longitude, currentZoom,
                            size.width.toFloat(), size.height.toFloat()
                        )
                        onRegionSelected(
                            RegionBounds(
                                minLat = minOf(start.latitude, now.latitude),
                                maxLat = maxOf(start.latitude, now.latitude),
                                minLon = minOf(start.longitude, now.longitude),
                                maxLon = maxOf(start.longitude, now.longitude)
                            )
                        )
                    }
                )
            }
            .pointerInput(isSelectingRegion) {
                // Panning would fight the selection drag for the same gesture.
                if (isSelectingRegion) return@pointerInput

                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1.0f) {
                        val newZoom = (currentZoom + ln(gestureZoom.toDouble()) / ln(1.5))
                            .coerceIn(2.0, 19.0)
                        onZoomChangedState(newZoom)
                    }

                    if (pan.x != 0f || pan.y != 0f) {
                        val z = currentZoom
                        val currentWorldX = MapProjection.lonToWorldX(currentCenter.longitude, z)
                        val currentWorldY = MapProjection.latToWorldY(currentCenter.latitude, z)

                        val newWorldX = currentWorldX - pan.x
                        val newWorldY = currentWorldY - pan.y

                        val newLon = MapProjection.worldXToLon(newWorldX, z)
                        val newLat = MapProjection.worldYToLat(newWorldY, z)
                        onCenterChangedState(GeoPoint(newLat, newLon))
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        canvasSize = Pair(width, height)

        // Reading the trigger inside the draw scope makes this Canvas redraw
        // whenever an async tile finishes loading.
        @Suppress("UNUSED_EXPRESSION")
        tileRefreshTrigger

        // 1. Draw Map Tiles
        drawTiles(
            center = center,
            zoom = zoom,
            width = width,
            height = height,
            tileSource = tileSource,
            tileManager = tileManager,
            onRequestTile = requestTile
        )

        // 2. Draw Military Grid Overlay
        drawMilitaryGrid(center, zoom, width, height)

        // 3. Draw Recorded Tracks
        // Previously recorded tracks the user chose to display, drawn under the active one.
        savedTrackPoints.values.forEach { pts ->
            drawTrackPoints(pts, center, zoom, width, height)
        }

        drawTrackPoints(activeTrackPoints, center, zoom, width, height)

        // 4a. Draw all saved routes (persisted polylines)
        drawSavedRoutes(savedRoutes, waypoints, center, zoom, width, height)

        // 4b. Draw Route Builder Polylines & Legs (currently being edited)
        drawRouteBuilder(routeBuilderState, center, zoom, width, height, angleUnit)

        // 5. Draw triangulation rays and their crossing point
        if (triangulationState.rays.isNotEmpty()) {
            drawTriangulation(triangulationState, center, zoom, width, height)
        }

        // 5b. Draw the region selection rectangle
        if (regionSelection != null) {
            drawRegionSelection(regionSelection, center, zoom, width, height)
        }

        // 5c. Sight line. With terrain data it is a green/red ray showing where the view is
        // blocked; without it, a plain bearing line so the azimuth and distance are still usable
        // in the field rather than showing nothing at all.
        if (losResult != null) {
            drawLineOfSight(losResult, center, zoom, width, height)
        } else if (losObserver != null && losTarget != null) {
            drawPlainSightLine(losObserver, losTarget, center, zoom, width, height, angleUnit)
        }

        // 6. Draw Ruler
        if (rulerState.isActive && rulerState.startPoint != null && rulerState.endPoint != null) {
            drawRuler(rulerState, center, zoom, width, height, angleUnit)
        }

        // 7. Draw Waypoints
        drawWaypoints(waypoints, selectedWaypoint, center, zoom, width, height, userLocation, angleUnit)

        // 7b. Draw Candidate Point & Targeting Vector
        val activeTarget = candidatePoint ?: selectedWaypoint?.toGeoPoint()
        if (activeTarget != null && userLocation != null) {
            drawTargetBearingLine(userLocation, activeTarget, center, zoom, width, height)
        }
        if (candidatePoint != null) {
            drawCandidatePoint(candidatePoint, center, zoom, width, height)
        }

        // 8. Draw User Location Puck and Heading
        if (userLocation != null) {
            drawUserLocation(userLocation, orientationData, center, zoom, width, height)
        }

        // 9. Draw Center Crosshair
        drawCrosshair(width, height)
    }
}

private fun DrawScope.drawTiles(
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
    tileSource: TileSource,
    tileManager: TileManager,
    onRequestTile: (TileSource, TileCoordinate) -> Unit
) {
    val intZoom = zoom.toInt().coerceIn(tileSource.minZoom, tileSource.maxZoom)
    val scale = 2.0.pow(zoom - intZoom).toFloat()

    val centerWorldX = MapProjection.lonToWorldX(center.longitude, intZoom.toDouble())
    val centerWorldY = MapProjection.latToWorldY(center.latitude, intZoom.toDouble())

    val halfW = (width / 2f) / scale
    val halfH = (height / 2f) / scale

    val minWorldX = centerWorldX - halfW
    val maxWorldX = centerWorldX + halfW
    val minWorldY = centerWorldY - halfH
    val maxWorldY = centerWorldY + halfH

    val minTileX = (minWorldX / 256.0).toInt().coerceAtLeast(0)
    val maxTileX = (maxWorldX / 256.0).toInt().coerceAtMost((2.0.pow(intZoom) - 1).toInt())
    val minTileY = (minWorldY / 256.0).toInt().coerceAtLeast(0)
    val maxTileY = (maxWorldY / 256.0).toInt().coerceAtMost((2.0.pow(intZoom) - 1).toInt())

    val maxTiles = 2.0.pow(intZoom).toInt()

    for (tx in minTileX..maxTileX) {
        val wrappedTx = (tx % maxTiles + maxTiles) % maxTiles
        for (ty in minTileY..maxTileY) {
            val tile = TileCoordinate(wrappedTx, ty, intZoom)

            val screenTileX = width / 2f + (tx * 256.0 - centerWorldX).toFloat() * scale
            val screenTileY = height / 2f + (ty * 256.0 - centerWorldY).toFloat() * scale
            val tileDisplaySize = 256f * scale

            val cachedBmp = tileManager.getCachedBitmap(tileSource.id, tile)
            if (cachedBmp != null) {
                drawContext.canvas.nativeCanvas.drawBitmap(
                    cachedBmp,
                    null,
                    android.graphics.RectF(
                        screenTileX,
                        screenTileY,
                        screenTileX + tileDisplaySize,
                        screenTileY + tileDisplaySize
                    ),
                    null
                )
            } else {
                // Draw fallback dark grid tile and request an async load of the real one
                drawContext.canvas.nativeCanvas.drawBitmap(
                    tileManager.createGridFallbackTile(tile),
                    null,
                    android.graphics.RectF(
                        screenTileX,
                        screenTileY,
                        screenTileX + tileDisplaySize,
                        screenTileY + tileDisplaySize
                    ),
                    null
                )
                onRequestTile(tileSource, tile)
            }
        }
    }
}

private fun DrawScope.drawMilitaryGrid(center: GeoPoint, zoom: Double, width: Float, height: Float) {
    if (zoom < 10.0) return

    val gridStepDeg = if (zoom >= 15.0) 0.01 else if (zoom >= 13.0) 0.05 else 0.1
    val startLat = (center.latitude / gridStepDeg).toInt() * gridStepDeg - gridStepDeg * 4
    val endLat = startLat + gridStepDeg * 8

    val startLon = (center.longitude / gridStepDeg).toInt() * gridStepDeg - gridStepDeg * 4
    val endLon = startLon + gridStepDeg * 8

    val gridColor = Color(0x334CAF50) // Tactical translucent green
    val textColor = Color(0x8881C784)

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.argb(140, 129, 199, 132)
        textSize = 28f
        isAntiAlias = true
    }

    var lat = startLat
    while (lat <= endLat) {
        val (sx1, sy1) = MapProjection.geoToScreen(GeoPoint(lat, center.longitude - 1.0), center.latitude, center.longitude, zoom, width, height)
        val (sx2, sy2) = MapProjection.geoToScreen(GeoPoint(lat, center.longitude + 1.0), center.latitude, center.longitude, zoom, width, height)
        drawLine(gridColor, Offset(0f, sy1), Offset(width, sy1), strokeWidth = 1f)

        if (sy1 in 30f..(height - 30f)) {
            drawContext.canvas.nativeCanvas.drawText(
                String.format(java.util.Locale.US, "%.3f°", lat),
                16f,
                sy1 - 6f,
                paint
            )
        }
        lat += gridStepDeg
    }

    var lon = startLon
    while (lon <= endLon) {
        val (sx1, _) = MapProjection.geoToScreen(GeoPoint(center.latitude, lon), center.latitude, center.longitude, zoom, width, height)
        drawLine(gridColor, Offset(sx1, 0f), Offset(sx1, height), strokeWidth = 1f)

        if (sx1 in 60f..(width - 60f)) {
            drawContext.canvas.nativeCanvas.drawText(
                String.format(java.util.Locale.US, "%.3f°", lon),
                sx1 + 6f,
                height - 20f,
                paint
            )
        }
        lon += gridStepDeg
    }
}

private fun DrawScope.drawTrackPoints(
    points: List<TrackPointEntity>,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    if (points.size < 2) return

    val gpsColor = Color(0xFF4CAF50)
    val drColor = Color(0xFFFF9800)
    val drPathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 15f), 0f)

    for (i in 0 until points.size - 1) {
        val p1 = points[i]
        val p2 = points[i + 1]

        val (s1x, s1y) = MapProjection.geoToScreen(p1.toGeoPoint(), center.latitude, center.longitude, zoom, width, height)
        val (s2x, s2y) = MapProjection.geoToScreen(p2.toGeoPoint(), center.latitude, center.longitude, zoom, width, height)

        val isDr = p2.source == TrackPointEntity.SOURCE_DEAD_RECKONING
        val color = if (isDr) drColor else gpsColor
        val effect = if (isDr) drPathEffect else null

        drawLine(
            color = color,
            start = Offset(s1x, s1y),
            end = Offset(s2x, s2y),
            strokeWidth = 6f,
            pathEffect = effect
        )
    }
}

private fun DrawScope.drawPlainSightLine(
    observer: GeoPoint,
    target: GeoPoint,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
    angleUnit: AngleUnit
) {
    val (ax, ay) = MapProjection.geoToScreen(
        observer, center.latitude, center.longitude, zoom, width, height
    )
    val (bx, by) = MapProjection.geoToScreen(
        target, center.latitude, center.longitude, zoom, width, height
    )

    // Dashed and yellow so it reads as "bearing only, terrain unknown" rather than an
    // analysed result.
    drawLine(
        color = Color(0xFFFFD54F),
        start = Offset(ax, ay),
        end = Offset(bx, by),
        strokeWidth = 5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
    )

    drawCircle(Color.White, radius = 7f, center = Offset(ax, ay))
    drawCircle(Color(0xFFFFD54F), radius = 9f, center = Offset(bx, by), style = Stroke(3f))

    val distance = GeodesyEngine.distanceMeters(observer, target)
    val azimuth = GeodesyEngine.azimuthDegrees(observer, target)
    val distStr = if (distance >= 1000.0) {
        String.format(java.util.Locale.US, "%.2f км", distance / 1000.0)
    } else {
        String.format(java.util.Locale.US, "%.0f м", distance)
    }

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(255, 213, 79)
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
        textAlign = AndroidPaint.Align.CENTER
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }
    drawContext.canvas.nativeCanvas.drawText(
        "${AngleUnit.format(azimuth, angleUnit)} · $distStr",
        (ax + bx) / 2f,
        (ay + by) / 2f - 14f,
        paint
    )
}

private fun DrawScope.drawLineOfSight(
    result: LineOfSightResult,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    val samples = result.samples
    if (samples.size < 2) return

    // Colour by position relative to the blocking summit, not per-segment: everything up to the
    // obstacle is genuinely visible, everything past it is dead ground. Segment-by-segment
    // colouring produced a striped line that told the user nothing useful.
    val cutoff = result.worstObstacle?.distanceMeters

    for (i in 0 until samples.size - 1) {
        val a = samples[i]
        val b = samples[i + 1]
        val (ax, ay) = MapProjection.geoToScreen(
            a.point, center.latitude, center.longitude, zoom, width, height
        )
        val (bx, by) = MapProjection.geoToScreen(
            b.point, center.latitude, center.longitude, zoom, width, height
        )
        val beyondObstacle = cutoff != null && a.distanceMeters >= cutoff
        drawLine(
            color = if (beyondObstacle) Color(0xFFE53935) else Color(0xFF66BB6A),
            start = Offset(ax, ay),
            end = Offset(bx, by),
            strokeWidth = 6f
        )
    }

    // Endpoints
    val first = samples.first()
    val last = samples.last()
    listOf(first, last).forEach { p ->
        val (px, py) = MapProjection.geoToScreen(
            p.point, center.latitude, center.longitude, zoom, width, height
        )
        drawCircle(Color.White, radius = 7f, center = Offset(px, py))
    }

    // Obstacle marker: a triangle with its height, right on the ray where it is blocked.
    result.worstObstacle?.let { o ->
        val (ox, oy) = MapProjection.geoToScreen(
            o.point, center.latitude, center.longitude, zoom, width, height
        )

        val size = 20f
        val path = Path().apply {
            moveTo(ox, oy - size)
            lineTo(ox - size * 0.9f, oy + size * 0.7f)
            lineTo(ox + size * 0.9f, oy + size * 0.7f)
            close()
        }
        drawPath(path, Color(0xFFFF5722))
        drawPath(path, Color.White, style = Stroke(3f))

        val paint = AndroidPaint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = AndroidPaint.Align.CENTER
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
        drawContext.canvas.nativeCanvas.drawText(
            "${o.terrainMeters.toInt()} м",
            ox,
            oy - size - 12f,
            paint
        )
    }
}

private fun DrawScope.drawTriangulation(
    state: TriangulationState,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    val rayColor = Color(0xFFFF7043)
    val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(255, 138, 101)
        textSize = 26f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    state.rays.forEach { ray ->
        val start = ray.originPoint
        val end = GeodesyEngine.destinationPoint(start, ray.effectiveLengthMeters(), ray.azimuthDeg)

        val (sx, sy) = MapProjection.geoToScreen(start, center.latitude, center.longitude, zoom, width, height)
        val (ex, ey) = MapProjection.geoToScreen(end, center.latitude, center.longitude, zoom, width, height)

        // Bounded segments are solid, open-ended rays are dashed.
        drawLine(
            color = rayColor,
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = 4f,
            pathEffect = if (ray.lengthMeters == null) dash else null
        )

        val label = if (ray.lengthMeters != null) {
            String.format(java.util.Locale.US, "%.0f°/%.0f м", ray.azimuthDeg, ray.lengthMeters)
        } else {
            String.format(java.util.Locale.US, "%.0f°", ray.azimuthDeg)
        }
        drawContext.canvas.nativeCanvas.drawText(label, (sx + ex) / 2f, (sy + ey) / 2f - 10f, paint)

        if (ray.lengthMeters != null) {
            drawCircle(rayColor, radius = 9f, center = Offset(ex, ey))
            drawCircle(Color.White, radius = 9f, center = Offset(ex, ey), style = Stroke(2f))
        }
    }

    // Crossing point marker
    state.intersectionPoint()?.let { pt ->
        val (ix, iy) = MapProjection.geoToScreen(pt, center.latitude, center.longitude, zoom, width, height)
        val hit = Color(0xFFD32F2F)
        drawCircle(hit, radius = 16f, center = Offset(ix, iy), style = Stroke(4f))
        drawLine(hit, Offset(ix - 26f, iy), Offset(ix + 26f, iy), strokeWidth = 3f)
        drawLine(hit, Offset(ix, iy - 26f), Offset(ix, iy + 26f), strokeWidth = 3f)
    }
}

private fun DrawScope.drawRegionSelection(
    bounds: RegionBounds,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    val (x1, y1) = MapProjection.geoToScreen(
        GeoPoint(bounds.maxLat, bounds.minLon),
        center.latitude, center.longitude, zoom, width, height
    )
    val (x2, y2) = MapProjection.geoToScreen(
        GeoPoint(bounds.minLat, bounds.maxLon),
        center.latitude, center.longitude, zoom, width, height
    )

    val left = minOf(x1, x2)
    val top = minOf(y1, y2)
    val w = kotlin.math.abs(x2 - x1)
    val h = kotlin.math.abs(y2 - y1)

    drawRect(
        color = Color(0x3300E5FF),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(w, h)
    )
    drawRect(
        color = Color(0xFF00E5FF),
        topLeft = Offset(left, top),
        size = androidx.compose.ui.geometry.Size(w, h),
        style = Stroke(3f)
    )
}

private fun DrawScope.drawSavedRoutes(
    savedRoutes: List<RouteEntity>,
    waypoints: List<WaypointEntity>,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    if (savedRoutes.isEmpty() || waypoints.isEmpty()) return

    val byId = waypoints.associateBy { it.id }
    val routeColor = Color(0xFF26C6DA)

    savedRoutes.forEach { route ->
        val pts = route.parseWaypointIds().mapNotNull { byId[it] }
        if (pts.size < 2) return@forEach

        for (i in 0 until pts.size - 1) {
            val (ax, ay) = MapProjection.geoToScreen(
                pts[i].toGeoPoint(), center.latitude, center.longitude, zoom, width, height
            )
            val (bx, by) = MapProjection.geoToScreen(
                pts[i + 1].toGeoPoint(), center.latitude, center.longitude, zoom, width, height
            )
            drawLine(
                color = routeColor,
                start = Offset(ax, ay),
                end = Offset(bx, by),
                strokeWidth = 5f
            )
        }
    }
}

private fun DrawScope.drawRouteBuilder(
    routeState: RouteBuilderState,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
    angleUnit: AngleUnit
) {
    if (!routeState.isActive) return

    val routeColor = Color(0xFF00E5FF) // Cyan
    val legs = routeState.legs

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 30f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    legs.forEach { leg ->
        val (s1x, s1y) = MapProjection.geoToScreen(leg.fromPoint.toGeoPoint(), center.latitude, center.longitude, zoom, width, height)
        val (s2x, s2y) = MapProjection.geoToScreen(leg.toPoint.toGeoPoint(), center.latitude, center.longitude, zoom, width, height)

        drawLine(
            color = routeColor,
            start = Offset(s1x, s1y),
            end = Offset(s2x, s2y),
            strokeWidth = 5f
        )

        // Midpoint label for distance and azimuth
        val midX = (s1x + s2x) / 2f
        val midY = (s1y + s2y) / 2f

        val distStr = leg.formatDistance()
        val azStr = AngleUnit.format(leg.forwardAzimuthDeg, angleUnit)
        val label = "#${leg.index}: $distStr | $azStr"

        drawContext.canvas.nativeCanvas.drawText(label, midX - 60f, midY - 12f, paint)
    }
}

private fun DrawScope.drawRuler(
    ruler: RulerState,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
    angleUnit: AngleUnit
) {
    val p1 = ruler.startPoint ?: return
    val p2 = ruler.endPoint ?: return

    val (s1x, s1y) = MapProjection.geoToScreen(p1, center.latitude, center.longitude, zoom, width, height)
    val (s2x, s2y) = MapProjection.geoToScreen(p2, center.latitude, center.longitude, zoom, width, height)

    val rulerColor = Color(0xFFFFD600)

    drawLine(
        color = rulerColor,
        start = Offset(s1x, s1y),
        end = Offset(s2x, s2y),
        strokeWidth = 6f
    )
    drawCircle(rulerColor, radius = 10f, center = Offset(s1x, s1y))
    drawCircle(rulerColor, radius = 10f, center = Offset(s2x, s2y))

    // Badge in the center
    val midX = (s1x + s2x) / 2f
    val midY = (s1y + s2y) / 2f

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 34f
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }

    val text = "${ruler.formatDistance()} | ${ruler.formatAzimuth(angleUnit)}"
    drawContext.canvas.nativeCanvas.drawText(text, midX - 80f, midY - 20f, paint)
}

private fun DrawScope.drawWaypoints(
    waypoints: List<WaypointEntity>,
    selected: WaypointEntity?,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float,
    userLocation: GeoPoint?,
    angleUnit: AngleUnit
) {
    val textPaint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    // Azimuth / distance readout shown under every waypoint, measured from the user's
    // current position, so multiple points can be compared at a glance while moving.
    val navPaint = AndroidPaint().apply {
        color = android.graphics.Color.rgb(129, 212, 250)
        textSize = 24f
        isAntiAlias = true
        setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    waypoints.forEach { wp ->
        val (sx, sy) = MapProjection.geoToScreen(wp.toGeoPoint(), center.latitude, center.longitude, zoom, width, height)
        val isSelected = selected?.id == wp.id
        val color = Color(wp.colorArgb)

        // Draw pin
        val radius = if (isSelected) 18f else 12f
        drawCircle(color, radius = radius, center = Offset(sx, sy))
        drawCircle(Color.White, radius = radius, center = Offset(sx, sy), style = Stroke(3f))

        if (isSelected) {
            drawCircle(Color.Cyan, radius = radius + 6f, center = Offset(sx, sy), style = Stroke(2f))
        }

        drawContext.canvas.nativeCanvas.drawText(wp.name, sx + 16f, sy + 10f, textPaint)

        if (userLocation != null) {
            val target = wp.toGeoPoint()
            val distM = GeodesyEngine.distanceMeters(userLocation, target)
            val azDeg = GeodesyEngine.azimuthDegrees(userLocation, target)

            val distStr = if (distM >= 1000.0) {
                String.format(java.util.Locale.US, "%.2f км", distM / 1000.0)
            } else {
                String.format(java.util.Locale.US, "%.0f м", distM)
            }
            val label = "${AngleUnit.format(azDeg, angleUnit)} · $distStr"
            drawContext.canvas.nativeCanvas.drawText(label, sx + 16f, sy + 38f, navPaint)
        }
    }
}

private fun DrawScope.drawCandidatePoint(
    candidate: GeoPoint,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    val (sx, sy) = MapProjection.geoToScreen(candidate, center.latitude, center.longitude, zoom, width, height)
    val amber = Color(0xFFFF9800)
    val radius = 16f

    // Concentric crosshair rings
    drawCircle(amber, radius = radius, center = Offset(sx, sy))
    drawCircle(Color.White, radius = radius, center = Offset(sx, sy), style = Stroke(3f))
    drawCircle(
        Color(0xFFFFB74D),
        radius = radius + 8f,
        center = Offset(sx, sy),
        style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f))
    )

    // Crosshairs
    drawLine(Color.White, Offset(sx - radius - 8f, sy), Offset(sx + radius + 8f, sy), strokeWidth = 2f)
    drawLine(Color.White, Offset(sx, sy - radius - 8f), Offset(sx, sy + radius + 8f), strokeWidth = 2f)

    val paint = AndroidPaint().apply {
        color = android.graphics.Color.WHITE
        textSize = 28f
        isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
    }
    drawContext.canvas.nativeCanvas.drawText("Точка-кандидат", sx + 22f, sy + 10f, paint)
}

private fun DrawScope.drawTargetBearingLine(
    userLocation: GeoPoint,
    target: GeoPoint,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    val (s1x, s1y) = MapProjection.geoToScreen(userLocation, center.latitude, center.longitude, zoom, width, height)
    val (s2x, s2y) = MapProjection.geoToScreen(target, center.latitude, center.longitude, zoom, width, height)

    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
    drawLine(
        color = Color(0xFFFFB74D),
        start = Offset(s1x, s1y),
        end = Offset(s2x, s2y),
        strokeWidth = 3f,
        pathEffect = dash
    )
}

private fun DrawScope.drawUserLocation(
    userLocation: GeoPoint,
    orientationData: OrientationData,
    center: GeoPoint,
    zoom: Double,
    width: Float,
    height: Float
) {
    val (sx, sy) = MapProjection.geoToScreen(userLocation, center.latitude, center.longitude, zoom, width, height)

    // Accuracy circle
    userLocation.accuracy?.let { acc ->
        val metersPerPixel = 156543.03392 * cos(Math.toRadians(center.latitude)) / 2.0.pow(zoom)
        val radiusPixels = (acc / metersPerPixel).toFloat().coerceIn(16f, 300f)

        drawCircle(
            color = Color(0x222196F3),
            radius = radiusPixels,
            center = Offset(sx, sy)
        )
        drawCircle(
            color = Color(0x662196F3),
            radius = radiusPixels,
            center = Offset(sx, sy),
            style = Stroke(2f)
        )
    }

    // Directional Cone pointing in heading direction
    val heading = orientationData.trueHeadingDeg
    rotate(heading, pivot = Offset(sx, sy)) {
        val conePath = Path().apply {
            moveTo(sx, sy - 42f)
            lineTo(sx - 18f, sy + 10f)
            lineTo(sx, sy)
            lineTo(sx + 18f, sy + 10f)
            close()
        }
        drawPath(conePath, color = Color(0xFF00E5FF))
    }

    // Center puck
    drawCircle(Color.White, radius = 10f, center = Offset(sx, sy))
    drawCircle(Color(0xFF0288D1), radius = 7f, center = Offset(sx, sy))
}

private fun DrawScope.drawCrosshair(width: Float, height: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val color = Color(0xCCFFFFFF)
    val size = 20f

    drawLine(color, Offset(cx - size, cy), Offset(cx - 6f, cy), strokeWidth = 2f)
    drawLine(color, Offset(cx + 6f, cy), Offset(cx + size, cy), strokeWidth = 2f)
    drawLine(color, Offset(cx, cy - size), Offset(cx, cy - 6f), strokeWidth = 2f)
    drawLine(color, Offset(cx, cy + 6f), Offset(cx, cy + size), strokeWidth = 2f)
}
