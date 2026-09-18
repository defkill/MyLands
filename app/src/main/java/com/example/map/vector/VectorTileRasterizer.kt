package com.example.map.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface

/**
 * High-performance tactical rasterizer for Mapbox Vector Tiles / Shortbread Schema.
 *
 * Renders vector geometries into 512x512 raster tiles with a high-contrast tactical
 * dark palette, level-of-detail zooming, collision-free text labels, and sub-pixel overzoom.
 */
class VectorTileRasterizer(
    private val tileSizePx: Int = 512
) {

    // --- Tactical Dark Color Palette (ARGB) ---
    private val colorBackground = Color.rgb(0x1A, 0x1F, 0x26)
    private val colorLandCoverWood = Color.rgb(0x1E, 0x2D, 0x24)
    private val colorLandCoverUrban = Color.rgb(0x22, 0x27, 0x2E)
    private val colorWater = Color.rgb(0x16, 0x32, 0x3F)
    private val colorWaterLine = Color.rgb(0x20, 0x4B, 0x5E)
    private val colorBuilding = Color.rgb(0x2A, 0x31, 0x3B)
    private val colorBuildingStroke = Color.rgb(0x38, 0x41, 0x4E)
    private val colorRoadMinor = Color.rgb(0x45, 0x4F, 0x5A)
    private val colorRoadSecondary = Color.rgb(0x60, 0x6C, 0x7A)
    private val colorRoadPrimary = Color.rgb(0x7D, 0x8B, 0x9A)
    private val colorRoadMotorway = Color.rgb(0x9E, 0x8E, 0x75)
    private val colorRoadMotorwayCasing = Color.rgb(0x42, 0x38, 0x2A)
    private val colorRailway = Color.rgb(0x54, 0x5F, 0x6C)
    private val colorBoundary = Color.rgb(0x7E, 0x57, 0xC2)
    private val colorTextCity = Color.rgb(0xEE, 0xF2, 0xF6)
    private val colorTextTown = Color.rgb(0xCF, 0xD8, 0xDC)
    private val colorTextStreet = Color.rgb(0x90, 0xA4, 0xAE)
    private val colorTextPoi = Color.rgb(0x80, 0xCB, 0xC4)
    private val colorTextHalo = Color.rgb(0x0E, 0x12, 0x17)

    // --- Pre-allocated Paint Objects ---
    private val paintBg = Paint().apply {
        color = colorBackground
        style = Paint.Style.FILL
    }

    private val paintLandWood = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorLandCoverWood
        style = Paint.Style.FILL
    }

    private val paintLandUrban = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorLandCoverUrban
        style = Paint.Style.FILL
    }

    private val paintWater = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorWater
        style = Paint.Style.FILL
    }

    private val paintWaterLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorWaterLine
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintBuilding = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBuilding
        style = Paint.Style.FILL
    }

    private val paintBuildingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBuildingStroke
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val paintRoadMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRoadMinor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintRoadSecondary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRoadSecondary
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintRoadPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRoadPrimary
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintRoadMotorwayCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRoadMotorwayCasing
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintRoadMotorway = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRoadMotorway
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val paintRailway = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorRailway
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val paintBoundary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorBoundary
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
        strokeWidth = 2f
    }

    // --- Text and Label Paints ---
    private val paintLabelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
    }

    private val paintLabelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorTextHalo
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.DEFAULT_BOLD
    }

    // Reusable Path to reduce heap allocations
    private val reusablePath = Path()
    private val textBounds = Rect()

    /**
     * Rasterizes a [VectorTile] into an Android ARGB_8888 [Bitmap].
     *
     * @param tile Parsed vector tile
     * @param zoom Target zoom level requested by the map view
     * @param parentZoom Source zoom of the tile data (used for overzoom)
     * @param offsetX Sub-tile X offset in units of source tile
     * @param offsetY Sub-tile Y offset in units of source tile
     */
    fun rasterize(
        tile: VectorTile,
        zoom: Int,
        parentZoom: Int = zoom,
        offsetX: Int = 0,
        offsetY: Int = 0
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(tileSizePx, tileSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Clear background
        canvas.drawRect(0f, 0f, tileSizePx.toFloat(), tileSizePx.toFloat(), paintBg)

        if (tile.layers.isEmpty()) {
            return bitmap
        }

        val overzoomScale = if (zoom > parentZoom) (1 shl (zoom - parentZoom)) else 1
        val layerMap = tile.layers.associateBy { it.name.lowercase() }

        // 2. Render Land Cover / Land Use
        renderPolygons(canvas, layerMap["land"] ?: layerMap["landcover"] ?: layerMap["landuse"], overzoomScale, offsetX, offsetY) { feature ->
            val kind = (feature.attributes["kind"] as? String)?.lowercase() ?: ""
            if (kind.contains("wood") || kind.contains("forest") || kind.contains("park") || kind.contains("grass")) {
                paintLandWood
            } else {
                paintLandUrban
            }
        }

        // 3. Render Water Polygons & Oceans
        renderPolygons(canvas, layerMap["water_polygons"] ?: layerMap["water"] ?: layerMap["ocean"] ?: layerMap["water_polygon"], overzoomScale, offsetX, offsetY) {
            paintWater
        }

        // 4. Render Water Lines (rivers, canals)
        renderLines(canvas, layerMap["water_lines"] ?: layerMap["waterway"], overzoomScale, offsetX, offsetY) {
            paintWaterLine.apply { strokeWidth = (if (zoom >= 13) 3f else 1.5f) * (tileSizePx / 512f) }
        }

        // 5. Render Boundaries (Admin / National)
        renderLines(canvas, layerMap["boundaries"] ?: layerMap["boundary"] ?: layerMap["admin"], overzoomScale, offsetX, offsetY) {
            paintBoundary
        }

        // 6. Render Buildings (z >= 13)
        if (zoom >= 13) {
            val buildingsLayer = layerMap["buildings"] ?: layerMap["building"]
            if (buildingsLayer != null) {
                renderPolygons(canvas, buildingsLayer, overzoomScale, offsetX, offsetY, strokePaint = paintBuildingStroke) {
                    paintBuilding
                }
            }
        }

        // 7. Render Railways
        renderLines(canvas, layerMap["railway_lines"] ?: layerMap["railways"] ?: layerMap["railway"], overzoomScale, offsetX, offsetY) {
            paintRailway.apply { strokeWidth = 2f * (tileSizePx / 512f) }
        }

        // 8. Render Roads / Streets (low zoom -> high zoom roads)
        val streetLines = layerMap["street_lines"] ?: layerMap["streets"] ?: layerMap["roads"] ?: layerMap["transportation"]
        val streetPolys = layerMap["street_polygons"] ?: layerMap["streets_polygons"]
        if (streetPolys != null) {
            renderPolygons(canvas, streetPolys, overzoomScale, offsetX, offsetY) {
                paintRoadMinor
            }
        }
        if (streetLines != null) {
            renderRoads(canvas, streetLines, zoom, overzoomScale, offsetX, offsetY)
        }

        // 9. Render Labels (Place names, Street names, POIs) with collision detection
        renderLabels(canvas, layerMap, zoom, overzoomScale, offsetX, offsetY)

        return bitmap
    }

    private fun renderPolygons(
        canvas: Canvas,
        layer: VectorLayer?,
        scale: Int,
        offsetX: Int,
        offsetY: Int,
        strokePaint: Paint? = null,
        paintProvider: (VectorFeature) -> Paint
    ) {
        if (layer == null) return
        val extent = layer.extent.toFloat()

        for (feature in layer.features) {
            if (feature.geometryType != GeometryType.POLYGON) continue
            val paint = paintProvider(feature)

            for (ring in feature.geometry) {
                if (ring.size < 3) continue
                reusablePath.rewind()
                var first = true
                for (pt in ring) {
                    val px = ((pt.x.toFloat() * scale - offsetX * extent) * tileSizePx) / extent
                    val py = ((pt.y.toFloat() * scale - offsetY * extent) * tileSizePx) / extent
                    if (first) {
                        reusablePath.moveTo(px, py)
                        first = false
                    } else {
                        reusablePath.lineTo(px, py)
                    }
                }
                reusablePath.close()
                canvas.drawPath(reusablePath, paint)
                if (strokePaint != null) {
                    canvas.drawPath(reusablePath, strokePaint)
                }
            }
        }
    }

    private fun renderLines(
        canvas: Canvas,
        layer: VectorLayer?,
        scale: Int,
        offsetX: Int,
        offsetY: Int,
        paintProvider: (VectorFeature) -> Paint
    ) {
        if (layer == null) return
        val extent = layer.extent.toFloat()

        for (feature in layer.features) {
            if (feature.geometryType != GeometryType.LINESTRING) continue
            val paint = paintProvider(feature)

            for (line in feature.geometry) {
                if (line.size < 2) continue
                reusablePath.rewind()
                var first = true
                for (pt in line) {
                    val px = ((pt.x.toFloat() * scale - offsetX * extent) * tileSizePx) / extent
                    val py = ((pt.y.toFloat() * scale - offsetY * extent) * tileSizePx) / extent
                    if (first) {
                        reusablePath.moveTo(px, py)
                        first = false
                    } else {
                        reusablePath.lineTo(px, py)
                    }
                }
                canvas.drawPath(reusablePath, paint)
            }
        }
    }

    private fun renderRoads(
        canvas: Canvas,
        layer: VectorLayer,
        zoom: Int,
        scale: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        val extent = layer.extent.toFloat()
        val baseMultiplier = tileSizePx / 512f

        // Group features by road class
        val motorways = mutableListOf<VectorFeature>()
        val primaries = mutableListOf<VectorFeature>()
        val secondaries = mutableListOf<VectorFeature>()
        val minors = mutableListOf<VectorFeature>()

        for (feature in layer.features) {
            if (feature.geometryType != GeometryType.LINESTRING) continue
            val kind = ((feature.attributes["kind"] ?: feature.attributes["class"] ?: feature.attributes["type"]) as? String)?.lowercase() ?: ""

            when {
                kind.contains("motorway") || kind.contains("trunk") || kind == "highway" -> motorways.add(feature)
                kind.contains("primary") || kind.contains("secondary") || kind.contains("major") -> primaries.add(feature)
                kind.contains("tertiary") || kind.contains("residential") -> secondaries.add(feature)
                else -> minors.add(feature)
            }
        }

        // Draw minor roads (z >= 12)
        if (zoom >= 12) {
            paintRoadMinor.strokeWidth = (if (zoom >= 15) 3.5f else 1.8f) * baseMultiplier
            drawFeatureLines(canvas, minors, paintRoadMinor, extent, scale, offsetX, offsetY)
        }

        // Draw secondary roads (z >= 9)
        if (zoom >= 9) {
            paintRoadSecondary.strokeWidth = (if (zoom >= 14) 5.0f else 2.5f) * baseMultiplier
            drawFeatureLines(canvas, secondaries, paintRoadSecondary, extent, scale, offsetX, offsetY)
        }

        // Draw primary roads
        paintRoadPrimary.strokeWidth = (if (zoom >= 14) 6.5f else if (zoom >= 10) 4.0f else 2.2f) * baseMultiplier
        drawFeatureLines(canvas, primaries, paintRoadPrimary, extent, scale, offsetX, offsetY)

        // Draw motorways with casing
        val mwWidth = (if (zoom >= 14) 8.5f else if (zoom >= 10) 5.5f else 3.2f) * baseMultiplier
        paintRoadMotorwayCasing.strokeWidth = mwWidth + 2.5f * baseMultiplier
        drawFeatureLines(canvas, motorways, paintRoadMotorwayCasing, extent, scale, offsetX, offsetY)
        paintRoadMotorway.strokeWidth = mwWidth
        drawFeatureLines(canvas, motorways, paintRoadMotorway, extent, scale, offsetX, offsetY)
    }

    private fun drawFeatureLines(
        canvas: Canvas,
        features: List<VectorFeature>,
        paint: Paint,
        extent: Float,
        scale: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        for (f in features) {
            for (line in f.geometry) {
                if (line.size < 2) continue
                reusablePath.rewind()
                var first = true
                for (pt in line) {
                    val px = ((pt.x.toFloat() * scale - offsetX * extent) * tileSizePx) / extent
                    val py = ((pt.y.toFloat() * scale - offsetY * extent) * tileSizePx) / extent
                    if (first) {
                        reusablePath.moveTo(px, py)
                        first = false
                    } else {
                        reusablePath.lineTo(px, py)
                    }
                }
                canvas.drawPath(reusablePath, paint)
            }
        }
    }

    private fun renderLabels(
        canvas: Canvas,
        layerMap: Map<String, VectorLayer>,
        zoom: Int,
        scale: Int,
        offsetX: Int,
        offsetY: Int
    ) {
        val occupiedBoxes = mutableListOf<RectF>()

        // 1. Place Labels (cities, towns, villages)
        val placeLayer = layerMap["place_labels"] ?: layerMap["places"] ?: layerMap["place_label"] ?: layerMap["place"]
        if (placeLayer != null) {
            val extent = placeLayer.extent.toFloat()
            for (f in placeLayer.features) {
                val name = (f.attributes["name"] ?: f.attributes["name_en"] ?: f.attributes["name:ru"] ?: f.attributes["name:uk"]) as? String ?: continue
                if (name.isBlank()) continue
                val kind = ((f.attributes["kind"] ?: f.attributes["type"]) as? String)?.lowercase() ?: ""

                val (textSize, textColor) = when {
                    kind.contains("capital") || kind.contains("city") -> Pair(16f, colorTextCity)
                    kind.contains("town") -> Pair(13f, colorTextTown)
                    zoom >= 12 && (kind.contains("village") || kind.contains("hamlet")) -> Pair(11f, colorTextTown)
                    else -> if (zoom >= 14) Pair(10f, colorTextTown) else continue
                }

                // Place labels are points
                for (ring in f.geometry) {
                    for (pt in ring) {
                        val px = ((pt.x.toFloat() * scale - offsetX * extent) * tileSizePx) / extent
                        val py = ((pt.y.toFloat() * scale - offsetY * extent) * tileSizePx) / extent
                        drawTextWithHalo(canvas, name, px, py, textSize, textColor, occupiedBoxes)
                    }
                }
            }
        }

        // 2. Street Labels (z >= 14)
        if (zoom >= 14) {
            val streetLabelLayer = layerMap["street_labels"] ?: layerMap["streets"]
            if (streetLabelLayer != null) {
                val extent = streetLabelLayer.extent.toFloat()
                for (f in streetLabelLayer.features) {
                    val name = (f.attributes["name"] ?: f.attributes["name:uk"] ?: f.attributes["name:ru"]) as? String ?: continue
                    if (name.isBlank() || name.length < 2) continue

                    // Midpoint of first line
                    val firstLine = f.geometry.firstOrNull() ?: continue
                    if (firstLine.size >= 2) {
                        val midIdx = firstLine.size / 2
                        val p1 = firstLine[midIdx - 1]
                        val p2 = firstLine[midIdx]
                        val midX = (p1.x + p2.x) / 2f
                        val midY = (p1.y + p2.y) / 2f
                        val px = ((midX * scale - offsetX * extent) * tileSizePx) / extent
                        val py = ((midY * scale - offsetY * extent) * tileSizePx) / extent
                        drawTextWithHalo(canvas, name, px, py, 10f, colorTextStreet, occupiedBoxes)
                    }
                }
            }
        }

        // 3. POIs (z >= 15)
        if (zoom >= 15) {
            val poiLayer = layerMap["pois"] ?: layerMap["poi"]
            if (poiLayer != null) {
                val extent = poiLayer.extent.toFloat()
                for (f in poiLayer.features) {
                    val name = (f.attributes["name"] ?: f.attributes["name:uk"]) as? String ?: continue
                    if (name.isBlank()) continue
                    for (ring in f.geometry) {
                        for (pt in ring) {
                            val px = ((pt.x.toFloat() * scale - offsetX * extent) * tileSizePx) / extent
                            val py = ((pt.y.toFloat() * scale - offsetY * extent) * tileSizePx) / extent
                            drawTextWithHalo(canvas, name, px, py, 9.5f, colorTextPoi, occupiedBoxes)
                        }
                    }
                }
            }
        }
    }

    private fun drawTextWithHalo(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        spSize: Float,
        textColor: Int,
        occupiedBoxes: MutableList<RectF>
    ) {
        val pxSize = spSize * (tileSizePx / 512f) * 1.3f
        paintLabelFill.textSize = pxSize
        paintLabelFill.color = textColor
        paintLabelHalo.textSize = pxSize
        paintLabelHalo.strokeWidth = pxSize * 0.28f

        paintLabelFill.getTextBounds(text, 0, text.length, textBounds)
        val textWidth = paintLabelFill.measureText(text)
        val textHeight = textBounds.height().toFloat()

        val left = x - textWidth / 2f
        val top = y - textHeight / 2f
        val right = left + textWidth
        val bottom = top + textHeight

        // Check bounds within tile (with 4px margin)
        if (left < 4 || right > tileSizePx - 4 || top < 4 || bottom > tileSizePx - 4) {
            return
        }

        val box = RectF(left - 4, top - 4, right + 4, bottom + 4)
        for (occupied in occupiedBoxes) {
            if (RectF.intersects(box, occupied)) {
                return // Collision, skip label
            }
        }

        occupiedBoxes.add(box)
        val baselineY = y + textHeight / 3f
        canvas.drawText(text, left, baselineY, paintLabelHalo)
        canvas.drawText(text, left, baselineY, paintLabelFill)
    }
}
