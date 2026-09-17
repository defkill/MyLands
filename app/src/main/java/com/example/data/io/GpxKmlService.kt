package com.example.data.io

import com.example.data.entity.RouteEntity
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.data.entity.WaypointEntity
import com.example.model.GeoPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedNavigationData(
    val waypoints: List<WaypointEntity>,
    val routes: List<Pair<String, List<GeoPoint>>>
)

object GpxKmlService {

    private val isoDateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    // ==========================================
    // EXPORT GPX
    // ==========================================
    fun exportGpx(
        waypoints: List<WaypointEntity>,
        routesWithPoints: List<Pair<RouteEntity, List<WaypointEntity>>>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Orientir-Android\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>Orientir Navigation Export</name>\n")
        sb.append("    <time>${isoDateFmt.format(Date())}</time>\n")
        sb.append("  </metadata>\n")

        // Waypoints
        for (wp in waypoints) {
            sb.append("  <wpt lat=\"${wp.latitude}\" lon=\"${wp.longitude}\">\n")
            if (wp.altitudeMeters != null) {
                sb.append("    <ele>${wp.altitudeMeters}</ele>\n")
            }
            sb.append("    <name>${escapeXml(wp.name)}</name>\n")
            if (wp.description.isNotEmpty()) {
                sb.append("    <desc>${escapeXml(wp.description)}</desc>\n")
            }
            sb.append("  </wpt>\n")
        }

        // Routes
        for ((route, points) in routesWithPoints) {
            sb.append("  <rte>\n")
            sb.append("    <name>${escapeXml(route.name)}</name>\n")
            for (pt in points) {
                sb.append("    <rtept lat=\"${pt.latitude}\" lon=\"${pt.longitude}\">\n")
                if (pt.altitudeMeters != null) {
                    sb.append("      <ele>${pt.altitudeMeters}</ele>\n")
                }
                sb.append("      <name>${escapeXml(pt.name)}</name>\n")
                sb.append("    </rtept>\n")
            }
            sb.append("  </rte>\n")
        }

        sb.append("</gpx>\n")
        return sb.toString()
    }

    /**
     * Exports a recorded track as a GPX <trk>. Dead-reckoning points are kept in the same
     * segment as GPS points so the walked path stays continuous; their origin is preserved
     * in the point <type> so it is not lost on re-import.
     */
    fun exportTrackGpx(track: TrackEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MyLands-Android\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>${escapeXml(track.name)}</name>\n")
        sb.append("    <time>${isoDateFmt.format(Date(track.startTime))}</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>${escapeXml(track.name)}</name>\n")
        sb.append("    <trkseg>\n")
        for (pt in points) {
            sb.append("      <trkpt lat=\"${pt.latitude}\" lon=\"${pt.longitude}\">\n")
            if (pt.altitudeMeters != null) {
                sb.append("        <ele>${pt.altitudeMeters}</ele>\n")
            }
            sb.append("        <time>${isoDateFmt.format(Date(pt.timestamp))}</time>\n")
            sb.append("        <type>${if (pt.source == 1) "dead_reckoning" else "gps"}</type>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")
        return sb.toString()
    }

    // ==========================================
    // EXPORT KML
    // ==========================================
    fun exportKml(
        waypoints: List<WaypointEntity>,
        routesWithPoints: List<Pair<RouteEntity, List<WaypointEntity>>>
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>Ориентирование - Экспорт</name>\n")
        sb.append("    <open>1</open>\n")

        // Waypoints Folder
        if (waypoints.isNotEmpty()) {
            sb.append("    <Folder>\n")
            sb.append("      <name>Путевые точки</name>\n")
            for (wp in waypoints) {
                sb.append("      <Placemark>\n")
                sb.append("        <name>${escapeXml(wp.name)}</name>\n")
                if (wp.description.isNotEmpty()) {
                    sb.append("        <description>${escapeXml(wp.description)}</description>\n")
                }
                val alt = wp.altitudeMeters ?: 0.0
                sb.append("        <Point>\n")
                sb.append("          <coordinates>${wp.longitude},${wp.latitude},$alt</coordinates>\n")
                sb.append("        </Point>\n")
                sb.append("      </Placemark>\n")
            }
            sb.append("    </Folder>\n")
        }

        // Routes Folder
        if (routesWithPoints.isNotEmpty()) {
            sb.append("    <Folder>\n")
            sb.append("      <name>Маршруты</name>\n")
            for ((route, points) in routesWithPoints) {
                sb.append("      <Placemark>\n")
                sb.append("        <name>${escapeXml(route.name)}</name>\n")
                sb.append("        <LineString>\n")
                sb.append("          <tessellate>1</tessellate>\n")
                sb.append("          <coordinates>\n")
                val coordStr = points.joinToString(" ") { pt ->
                    "${pt.longitude},${pt.latitude},${pt.altitudeMeters ?: 0.0}"
                }
                sb.append("            $coordStr\n")
                sb.append("          </coordinates>\n")
                sb.append("        </LineString>\n")
                sb.append("      </Placemark>\n")
            }
            sb.append("    </Folder>\n")
        }

        sb.append("  </Document>\n")
        sb.append("</kml>\n")
        return sb.toString()
    }

    private fun createSafeDocumentBuilderFactory(): DocumentBuilderFactory {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
        try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) } catch (_: Exception) {}
        return factory
    }

    // ==========================================
    // IMPORT GPX
    // ==========================================
    fun importGpx(inputStream: InputStream): ImportedNavigationData {
        val factory = createSafeDocumentBuilderFactory()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)

        val waypoints = mutableListOf<WaypointEntity>()
        val routes = mutableListOf<Pair<String, List<GeoPoint>>>()

        // 1. Waypoints (<wpt>)
        val wptNodes = doc.getElementsByTagName("wpt")
        for (i in 0 until wptNodes.length) {
            val node = wptNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                val lat = elem.getAttribute("lat").toDoubleOrNull() ?: continue
                val lon = elem.getAttribute("lon").toDoubleOrNull() ?: continue
                val name = getChildTagText(elem, "name")
                val desc = getChildTagText(elem, "desc")
                val ele = getChildTagText(elem, "ele")?.toDoubleOrNull()

                waypoints.add(
                    WaypointEntity(
                        name = if (name.isNullOrBlank()) "Точка ${waypoints.size + 1}" else name,
                        latitude = lat,
                        longitude = lon,
                        altitudeMeters = ele,
                        description = desc ?: ""
                    )
                )
            }
        }

        // 2. Routes (<rte>)
        val rteNodes = doc.getElementsByTagName("rte")
        for (i in 0 until rteNodes.length) {
            val node = rteNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val elem = node as Element
                val rName = getChildTagText(elem, "name") ?: "Маршрут ${routes.size + 1}"
                val rtepts = elem.getElementsByTagName("rtept")
                val pts = mutableListOf<GeoPoint>()
                for (j in 0 until rtepts.length) {
                    val ptNode = rtepts.item(j) as? Element ?: continue
                    val lat = ptNode.getAttribute("lat").toDoubleOrNull() ?: continue
                    val lon = ptNode.getAttribute("lon").toDoubleOrNull() ?: continue
                    val ele = getChildTagText(ptNode, "ele")?.toDoubleOrNull()
                    pts.add(GeoPoint(lat, lon, ele))
                }
                if (pts.size >= 2) {
                    routes.add(Pair(rName, pts))
                }
            }
        }

        return ImportedNavigationData(waypoints, routes)
    }

    // ==========================================
    // IMPORT KML
    // ==========================================
    fun importKml(inputStream: InputStream): ImportedNavigationData {
        val factory = createSafeDocumentBuilderFactory()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(inputStream)

        val waypoints = mutableListOf<WaypointEntity>()
        val routes = mutableListOf<Pair<String, List<GeoPoint>>>()

        val placemarks = doc.getElementsByTagName("Placemark")
        for (i in 0 until placemarks.length) {
            val node = placemarks.item(i) as? Element ?: continue
            val name = getChildTagText(node, "name") ?: "Точка ${waypoints.size + 1}"
            val desc = getChildTagText(node, "description") ?: ""

            // Check if Placemark has Point
            val pointList = node.getElementsByTagName("Point")
            if (pointList.length > 0) {
                val ptElem = pointList.item(0) as Element
                val coordsText = getChildTagText(ptElem, "coordinates")
                if (!coordsText.isNullOrBlank()) {
                    val parts = coordsText.trim().split(",")
                    if (parts.size >= 2) {
                        val lon = parts[0].trim().toDoubleOrNull()
                        val lat = parts[1].trim().toDoubleOrNull()
                        val alt = if (parts.size >= 3) parts[2].trim().toDoubleOrNull() else null
                        if (lat != null && lon != null) {
                            waypoints.add(
                                WaypointEntity(
                                    name = name,
                                    latitude = lat,
                                    longitude = lon,
                                    altitudeMeters = alt,
                                    description = desc
                                )
                            )
                        }
                    }
                }
            }

            // Check if Placemark has LineString
            val lineList = node.getElementsByTagName("LineString")
            if (lineList.length > 0) {
                val lineElem = lineList.item(0) as Element
                val coordsText = getChildTagText(lineElem, "coordinates")
                if (!coordsText.isNullOrBlank()) {
                    val pts = mutableListOf<GeoPoint>()
                    val tokens = coordsText.trim().split("\\s+".toRegex())
                    for (t in tokens) {
                        val parts = t.split(",")
                        if (parts.size >= 2) {
                            val lon = parts[0].trim().toDoubleOrNull() ?: continue
                            val lat = parts[1].trim().toDoubleOrNull() ?: continue
                            val alt = if (parts.size >= 3) parts[2].trim().toDoubleOrNull() else null
                            pts.add(GeoPoint(lat, lon, alt))
                        }
                    }
                    if (pts.size >= 2) {
                        routes.add(Pair(name, pts))
                    }
                }
            }
        }

        return ImportedNavigationData(waypoints, routes)
    }

    private fun getChildTagText(parent: Element, tagName: String): String? {
        val list = parent.getElementsByTagName(tagName)
        return if (list.length > 0) list.item(0).textContent?.trim() else null
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
