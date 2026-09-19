package com.example.map.vector

import android.graphics.Color
import android.graphics.Paint

/**
 * Styling and Level-Of-Detail (LOD) zoom visibility rules for raw GeoPackage OSM feature layers.
 */
object GeoPackageStyle {

    // Render sequence: bottom to top
    val RENDER_LAYER_ORDER = listOf(
        "gis_osm_landuse_a_free",
        "gis_osm_natural_a_free",
        "gis_osm_water_a_free",
        "gis_osm_waterways_free",
        "gis_osm_buildings_a_free",
        "gis_osm_railways_free",
        "gis_osm_roads_free",
        "gis_osm_pois_free",
        "gis_osm_places_free"
    )

    sealed class ZoomRule(val maxZoomExclusive: Int) {
        class IncludeOnly(maxZoom: Int, val classes: List<String>) : ZoomRule(maxZoom)
        class Exclude(maxZoom: Int, val classes: List<String>, val prefixExclude: String? = null) : ZoomRule(maxZoom)
    }

    private val LAYER_MIN_ZOOMS = mapOf(
        "gis_osm_water_a_free" to 6,
        "gis_osm_railways_free" to 10,
        "gis_osm_buildings_a_free" to 14
    )

    private val LAYER_ZOOM_RULES = mapOf(
        "gis_osm_roads_free" to listOf(
            ZoomRule.IncludeOnly(8, listOf("motorway", "trunk", "primary", "motorway_link", "trunk_link", "primary_link")),
            ZoomRule.IncludeOnly(11, listOf("motorway", "trunk", "primary", "secondary", "tertiary", "motorway_link", "trunk_link", "primary_link", "secondary_link")),
            ZoomRule.Exclude(14, listOf("path", "footway", "steps", "cycleway", "pedestrian", "service"), prefixExclude = "track_grade")
        ),
        "gis_osm_landuse_a_free" to listOf(
            ZoomRule.IncludeOnly(9, listOf("forest", "residential", "military")),
            ZoomRule.IncludeOnly(12, listOf("forest", "residential", "military", "farmland", "meadow", "commercial", "industrial"))
        ),
        "gis_osm_natural_a_free" to listOf(
            ZoomRule.IncludeOnly(9, listOf("water", "wood", "glacier")),
            ZoomRule.IncludeOnly(12, listOf("water", "wood", "glacier", "scrub", "heath", "grassland", "wetland"))
        ),
        "gis_osm_waterways_free" to listOf(
            ZoomRule.IncludeOnly(10, listOf("river")),
            ZoomRule.IncludeOnly(13, listOf("river", "canal", "stream"))
        ),
        "gis_osm_places_free" to listOf(
            ZoomRule.IncludeOnly(6, listOf("country")),
            ZoomRule.IncludeOnly(9, listOf("country", "state", "city")),
            ZoomRule.IncludeOnly(12, listOf("country", "state", "city", "town")),
            ZoomRule.IncludeOnly(14, listOf("country", "state", "city", "town", "village"))
        ),
        "gis_osm_pois_free" to listOf(
            ZoomRule.IncludeOnly(13, listOf("hospital", "police", "fire_station", "airport", "helipad")),
            ZoomRule.IncludeOnly(15, listOf("hospital", "police", "fire_station", "airport", "helipad", "pharmacy", "fuel", "bank"))
        )
    )

    fun isLayerPossiblyVisibleAtZoom(layerName: String, zoom: Int): Boolean {
        val minZoom = LAYER_MIN_ZOOMS[layerName]
        return if (minZoom != null) zoom >= minZoom else (if (layerName in RENDER_LAYER_ORDER) true else zoom >= 13)
    }

    fun sqlVisibilityFilter(layerName: String, zoom: Int): Pair<String, List<String>>? {
        val rules = LAYER_ZOOM_RULES[layerName] ?: return null
        for (rule in rules) {
            if (zoom < rule.maxZoomExclusive) {
                return when (rule) {
                    is ZoomRule.IncludeOnly -> {
                        val placeholders = rule.classes.joinToString(",") { "?" }
                        "fclass IN ($placeholders)" to rule.classes
                    }
                    is ZoomRule.Exclude -> {
                        val placeholders = rule.classes.joinToString(",") { "'$it'" }
                        val sql = if (rule.prefixExclude != null) {
                            "fclass NOT IN ($placeholders) AND fclass NOT LIKE '${rule.prefixExclude}%'"
                        } else {
                            "fclass NOT IN ($placeholders)"
                        }
                        sql to emptyList()
                    }
                }
            }
        }
        return null
    }

    fun isFeatureVisibleAtZoom(layerName: String, attributes: Map<String, Any?>, zoom: Int): Boolean {
        val minZoom = LAYER_MIN_ZOOMS[layerName]
        if (minZoom != null && zoom < minZoom) return false
        if (layerName !in RENDER_LAYER_ORDER && zoom < 13) return false

        val rules = LAYER_ZOOM_RULES[layerName] ?: return true
        val fclass = (attributes["fclass"] as? String)?.lowercase() ?: ""
        for (rule in rules) {
            if (zoom < rule.maxZoomExclusive) {
                return when (rule) {
                    is ZoomRule.IncludeOnly -> fclass in rule.classes
                    is ZoomRule.Exclude -> fclass !in rule.classes && (rule.prefixExclude == null || !fclass.startsWith(rule.prefixExclude))
                }
            }
        }
        return true
    }

    // Colors
    val COLOR_BACKGROUND = Color.rgb(242, 239, 233)
    val COLOR_WATER = Color.rgb(170, 211, 223)
    val COLOR_FOREST = Color.rgb(209, 236, 196)
    val COLOR_FARMLAND = Color.rgb(238, 240, 213)
    val COLOR_RESIDENTIAL = Color.rgb(224, 223, 223)
    val COLOR_INDUSTRIAL = Color.rgb(235, 219, 228)
    val COLOR_BUILDING_FILL = Color.rgb(217, 208, 201)
    val COLOR_BUILDING_STROKE = Color.rgb(199, 188, 180)

    val COLOR_ROAD_MOTORWAY = Color.rgb(233, 144, 160)
    val COLOR_ROAD_PRIMARY = Color.rgb(253, 215, 161)
    val COLOR_ROAD_SECONDARY = Color.rgb(253, 237, 206)
    val COLOR_ROAD_RESIDENTIAL = Color.rgb(255, 255, 255)
    val COLOR_ROAD_TRACK = Color.rgb(180, 140, 100)
    val COLOR_ROAD_PATH = Color.rgb(150, 100, 80)
    val COLOR_ROAD_CASING = Color.rgb(200, 195, 185)

    val COLOR_POI_DEFAULT = Color.rgb(80, 120, 180)
    val COLOR_POI_MEDICAL = Color.rgb(210, 40, 40)
    val COLOR_TEXT = Color.rgb(45, 50, 55)
    val COLOR_TEXT_HALO = Color.rgb(255, 255, 255)

    private val polygonPaintCache = mutableMapOf<String, Paint>()
    private val linePaintCache = mutableMapOf<String, Paint>()
    private val pointPaintCache = mutableMapOf<String, Paint>()
    private val textPaintCache = mutableMapOf<String, Paint>()

    fun getPointPaint(layerName: String, fclass: String): Paint {
        val key = "$layerName:$fclass"
        return pointPaintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = when (fclass) {
                    "hospital", "pharmacy" -> COLOR_POI_MEDICAL
                    "police", "fire_station" -> Color.rgb(220, 100, 30)
                    else -> COLOR_POI_DEFAULT
                }
            }
        }
    }

    fun getTextPaint(fontSize: Float, isHalo: Boolean = false): Paint {
        val key = "$fontSize:$isHalo"
        return textPaintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = fontSize
                textAlign = Paint.Align.CENTER
                if (isHalo) {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = COLOR_TEXT_HALO
                } else {
                    style = Paint.Style.FILL
                    color = COLOR_TEXT
                }
            }
        }
    }

    private fun resolvePolygonColor(layerName: String, fclass: String): Int {
        return when (layerName) {
            "gis_osm_water_a_free" -> COLOR_WATER
            "gis_osm_buildings_a_free" -> COLOR_BUILDING_FILL
            "gis_osm_landuse_a_free" -> {
                when (fclass) {
                    "forest" -> COLOR_FOREST
                    "farmland", "meadow", "grass" -> COLOR_FARMLAND
                    "residential" -> COLOR_RESIDENTIAL
                    "industrial", "commercial" -> COLOR_INDUSTRIAL
                    else -> Color.rgb(230, 230, 225)
                }
            }
            "gis_osm_natural_a_free" -> {
                when (fclass) {
                    "water" -> COLOR_WATER
                    "wood" -> COLOR_FOREST
                    "scrub", "heath", "grassland" -> COLOR_FARMLAND
                    else -> Color.rgb(230, 235, 225)
                }
            }
            else -> Color.rgb(230, 230, 230)
        }
    }

    fun getPolygonPaint(layerName: String, fclass: String): Paint {
        val key = "$layerName:$fclass"
        return polygonPaintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = resolvePolygonColor(layerName, fclass)
            }
        }
    }

    fun getLinePaint(layerName: String, fclass: String, zoom: Int, isCasing: Boolean = false): Paint {
        val key = "$layerName:$fclass:$zoom:$isCasing"
        return linePaintCache.getOrPut(key) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND

                if (layerName == "gis_osm_waterways_free") {
                    color = COLOR_WATER
                    strokeWidth = if (fclass == "river") 2.5f else 1.2f
                } else if (layerName == "gis_osm_railways_free") {
                    color = Color.rgb(120, 120, 120)
                    strokeWidth = 1.5f
                } else {
                    val baseWidth: Float = when {
                        fclass.startsWith("motorway") || fclass.startsWith("trunk") -> if (zoom >= 14) 7f else if (zoom >= 11) 4.5f else 2.5f
                        fclass.startsWith("primary") -> if (zoom >= 14) 5.5f else if (zoom >= 11) 3.5f else 2f
                        fclass.startsWith("secondary") -> if (zoom >= 14) 4.5f else if (zoom >= 11) 2.5f else 1.5f
                        fclass.startsWith("tertiary") -> if (zoom >= 14) 3.5f else 2f
                        fclass in setOf("track", "path", "footway", "steps", "cycleway") -> 1.5f
                        else -> if (zoom >= 14) 3f else 1.5f
                    }

                    if (isCasing) {
                        color = COLOR_ROAD_CASING
                        strokeWidth = baseWidth + 2f
                    } else {
                        strokeWidth = baseWidth
                        color = when {
                            fclass.startsWith("motorway") || fclass.startsWith("trunk") -> COLOR_ROAD_MOTORWAY
                            fclass.startsWith("primary") -> COLOR_ROAD_PRIMARY
                            fclass.startsWith("secondary") || fclass.startsWith("tertiary") -> COLOR_ROAD_SECONDARY
                            fclass == "track" -> COLOR_ROAD_TRACK
                            fclass in setOf("path", "footway", "steps", "cycleway") -> COLOR_ROAD_PATH
                            else -> COLOR_ROAD_RESIDENTIAL
                        }
                    }
                }
            }
        }
    }
}
