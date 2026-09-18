package com.example

import com.example.map.vector.GeometryType
import com.example.map.vector.IntPoint
import com.example.map.vector.VectorFeature
import com.example.map.vector.VectorLayer
import com.example.map.vector.VectorTile
import com.example.map.vector.VectorTileRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VectorTileRasterizerTest {

    @Test
    fun testRasterizeEmptyTile() {
        val rasterizer = VectorTileRasterizer(512)
        val emptyTile = VectorTile(emptyList())
        val bmp = rasterizer.rasterize(emptyTile, zoom = 14)
        assertNotNull(bmp)
        assertEquals(512, bmp.width)
        assertEquals(512, bmp.height)
    }

    @Test
    fun testRasterizeSyntheticLayersWithOverzoom() {
        val rasterizer = VectorTileRasterizer(512)

        // Water polygon
        val waterFeature = VectorFeature(
            geometryType = GeometryType.POLYGON,
            attributes = mapOf("kind" to "lake"),
            geometry = listOf(
                listOf(IntPoint(100, 100), IntPoint(500, 100), IntPoint(500, 500), IntPoint(100, 500), IntPoint(100, 100))
            )
        )
        val waterLayer = VectorLayer(name = "water_polygons", extent = 4096, features = listOf(waterFeature))

        // Roads
        val roadFeature = VectorFeature(
            geometryType = GeometryType.LINESTRING,
            attributes = mapOf("kind" to "motorway", "name" to "M-06"),
            geometry = listOf(
                listOf(IntPoint(0, 2048), IntPoint(4096, 2048))
            )
        )
        val roadsLayer = VectorLayer(name = "street_lines", extent = 4096, features = listOf(roadFeature))

        // Buildings
        val buildingFeature = VectorFeature(
            geometryType = GeometryType.POLYGON,
            attributes = mapOf("building" to "yes"),
            geometry = listOf(
                listOf(IntPoint(2000, 2000), IntPoint(2200, 2000), IntPoint(2200, 2200), IntPoint(2000, 2200), IntPoint(2000, 2000))
            )
        )
        val buildingsLayer = VectorLayer(name = "buildings", extent = 4096, features = listOf(buildingFeature))

        // Place label
        val placeFeature = VectorFeature(
            geometryType = GeometryType.POINT,
            attributes = mapOf("name" to "Kyiv", "kind" to "capital"),
            geometry = listOf(
                listOf(IntPoint(2048, 2048))
            )
        )
        val placesLayer = VectorLayer(name = "place_labels", extent = 4096, features = listOf(placeFeature))

        val tile = VectorTile(listOf(waterLayer, roadsLayer, buildingsLayer, placesLayer))

        // Test normal rasterize at z=14
        val bmp14 = rasterizer.rasterize(tile, zoom = 14, parentZoom = 14)
        assertNotNull(bmp14)

        // Test overzoom at z=16 (parent z=14, offset (1, 1))
        val bmp16 = rasterizer.rasterize(tile, zoom = 16, parentZoom = 14, offsetX = 1, offsetY = 1)
        assertNotNull(bmp16)
        assertEquals(512, bmp16.width)
        assertEquals(512, bmp16.height)
    }
}
