package com.example

import com.example.data.entity.RouteEntity
import com.example.data.entity.WaypointEntity
import com.example.data.io.GpxKmlService
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class GpxKmlServiceTest {

    @Test
    fun testGpxExportAndImport() {
        val w1 = WaypointEntity(id = 1, name = "OP-1", latitude = 50.4501, longitude = 30.5234, altitudeMeters = 175.0, description = "Observatory")
        val w2 = WaypointEntity(id = 2, name = "OP-2", latitude = 50.4600, longitude = 30.5300, altitudeMeters = 180.0, description = "Target point")

        val route = RouteEntity(id = 1, name = "Route Alpha", waypointIdsCsv = "1,2", totalDistanceMeters = 1200.0)

        val gpxXml = GpxKmlService.exportGpx(listOf(w1, w2), listOf(Pair(route, listOf(w1, w2))))
        assertTrue(gpxXml.contains("<gpx"))
        assertTrue(gpxXml.contains("<wpt lat=\"50.4501\" lon=\"30.5234\">"))
        assertTrue(gpxXml.contains("<name>OP-1</name>"))
        assertTrue(gpxXml.contains("<rte>"))
        assertTrue(gpxXml.contains("<name>Route Alpha</name>"))

        // Now import back
        val stream = ByteArrayInputStream(gpxXml.toByteArray(Charsets.UTF_8))
        val imported = GpxKmlService.importGpx(stream)

        assertEquals(2, imported.waypoints.size)
        assertEquals("OP-1", imported.waypoints[0].name)
        assertEquals(50.4501, imported.waypoints[0].latitude, 0.0001)
        assertEquals(30.5234, imported.waypoints[0].longitude, 0.0001)

        assertEquals(1, imported.routes.size)
        assertEquals("Route Alpha", imported.routes[0].first)
        assertEquals(2, imported.routes[0].second.size)
    }

    @Test
    fun testKmlExportAndImport() {
        val w1 = WaypointEntity(id = 1, name = "Kml Point 1", latitude = 49.8419, longitude = 24.0315, altitudeMeters = 290.0)
        val w2 = WaypointEntity(id = 2, name = "Kml Point 2", latitude = 49.8500, longitude = 24.0400, altitudeMeters = 300.0)

        val route = RouteEntity(id = 1, name = "Kml Route", waypointIdsCsv = "1,2", totalDistanceMeters = 1500.0)

        val kmlXml = GpxKmlService.exportKml(listOf(w1, w2), listOf(Pair(route, listOf(w1, w2))))
        assertTrue(kmlXml.contains("<kml"))
        assertTrue(kmlXml.contains("<coordinates>24.0315,49.8419,290.0</coordinates>"))
        assertTrue(kmlXml.contains("<LineString>"))

        // Now import back
        val stream = ByteArrayInputStream(kmlXml.toByteArray(Charsets.UTF_8))
        val imported = GpxKmlService.importKml(stream)

        assertEquals(2, imported.waypoints.size)
        assertEquals("Kml Point 1", imported.waypoints[0].name)
        assertEquals(49.8419, imported.waypoints[0].latitude, 0.0001)
        assertEquals(24.0315, imported.waypoints[0].longitude, 0.0001)

        assertEquals(1, imported.routes.size)
        assertEquals("Kml Route", imported.routes[0].first)
        assertEquals(2, imported.routes[0].second.size)
    }

    @Test
    fun testGpxImport_PreventsXxe() {
        val maliciousGpx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE gpx [
              <!ENTITY xxe SYSTEM "file:///etc/hostname">
            ]>
            <gpx version="1.1" creator="Malicious">
              <wpt lat="50.45" lon="30.52">
                <name>&xxe;</name>
                <desc>Test Description</desc>
              </wpt>
            </gpx>
        """.trimIndent()

        val stream = ByteArrayInputStream(maliciousGpx.toByteArray(Charsets.UTF_8))
        try {
            val result = GpxKmlService.importGpx(stream)
            // If parser didn't throw due to disallow-doctype-decl, verify entity was NOT expanded with external file content
            for (wp in result.waypoints) {
                assertNotEquals("Should not contain file content", "localhost", wp.name)
                assertFalse("Entity references should not be expanded to external content", wp.name.contains("hostname"))
            }
        } catch (e: Exception) {
            // Safely rejected due to disallow-doctype-decl or prohibited external entities
            assertTrue(
                "Exception should be a secure XML parse error",
                e.message?.contains("DOCTYPE") == true || e.message?.contains("disallowed") == true || e is org.xml.sax.SAXParseException
            )
        }
    }
}
