package com.example.geodesy

import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

data class ParsedCoordinatePoint(
    val lat: Double,
    val lon: Double,
    val label: String,      // "MGRS: 36U UA...", "DEC", "DMS", "Гаусс-Крюгер (зона 36)" и т.д.
    val outsideUkraine: Boolean
)

data class TextParseResult(
    val points: List<ParsedCoordinatePoint>,
    val warnings: List<String>
)

object FreeTextCoordinateParser {

    private const val TAG = "FreeTextCoordParser"

    private data class BoundingBox(
        val latMin: Double,
        val latMax: Double,
        val lonMin: Double,
        val lonMax: Double
    ) {
        fun contains(lat: Double, lon: Double): Boolean {
            return lat in latMin..latMax && lon in lonMin..lonMax
        }
    }

    private val UA_BOX = BoundingBox(latMin = 43.3, latMax = 53.6, lonMin = 21.5, lonMax = 41.0)
    private val REGION_BOX = BoundingBox(latMin = 30.0, latMax = 75.0, lonMin = 5.0, lonMax = 70.0)

    // Number with optional grouping spaces (e.g., "6 142 530.45" or "48.4647" or "5412345")
    // Greedy match: numbers with space grouping (e.g. 6 142 530) or contiguous digits.
    private const val NUM_SRC = """\d{1,3}(?:[ \t]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?"""

    // Hemisphere prefixes and suffixes:
    // Latin: N, S, E, W
    // Cyrillic: пн, пд, сх, зх (and variations like пн.ш., сх.д., с.ш., в.д.)
    private const val HEM_SRC = """(?:[NSEWnsew]|пн\.?\s*ш?\.?|пд\.?\s*ш?\.?|сх\.?\s*д?\.?|зх\.?\s*д?\.?|с\.?\s*ш\.?|ю\.?\s*ш\.?|в\.?\s*д\.?|з\.?\s*д\.?)"""

    /**
     * Normalizes non-breaking spaces, variants of dashes, quotation marks, apostrophes, and degree signs.
     */
    fun normalizeText(input: String): String {
        return input
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace('\uFEFF', ' ')
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2212', '-')
            .replace('\u00AB', '"')
            .replace('\u00BB', '"')
            .replace('\u201C', '"')
            .replace('\u201D', '"')
            .replace('\u201E', '"')
            .replace('\u2018', '\'')
            .replace('\u2019', '\'')
            .replace('\u2032', '\'')
            .replace('\u02BC', '\'')
            .replace('\u2033', '"')
            .replace('\u00BA', '°')
            .replace('\u02DA', '°')
    }

    /**
     * Parse a numeric string (which may contain grouping spaces and comma as decimal separator).
     */
    private fun parseNum(s: String): Double? {
        val clean = s.replace(" ", "").replace("\t", "").replace(',', '.')
        return clean.toDoubleOrNull()
    }

    /**
     * Returns:
     *  axis: 'lat' or 'lon' or null
     *  sign: +1.0 or -1.0
     */
    private data class HemisphereInfo(val axis: String, val sign: Double)

    private fun parseHemisphere(hemStr: String?): HemisphereInfo? {
        if (hemStr.isNullOrBlank()) return null
        val h = hemStr.trim().lowercase()
            .replace(".", "")
            .replace(" ", "")

        return when {
            // Latitude North
            h == "n" || h == "с" || h == "пн" || h == "пнш" || h == "сш" -> HemisphereInfo("lat", 1.0)
            // Latitude South
            h == "s" || h == "ю" || h == "пд" || h == "пдш" || h == "юш" -> HemisphereInfo("lat", -1.0)
            // Longitude East
            h == "e" || h == "в" || h == "сх" || h == "схд" || h == "вд" -> HemisphereInfo("lon", 1.0)
            // Longitude West
            h == "w" || h == "з" || h == "зх" || h == "зхд" || h == "зд" -> HemisphereInfo("lon", -1.0)
            else -> null
        }
    }

    private data class CoordVal(
        val value: Double,
        val axis: String? = null // "lat", "lon", or null
    )

    private fun dmsValue(dStr: String, mStr: String, sStr: String, hemStr: String?): CoordVal? {
        val d = parseNum(dStr) ?: return null
        val m = parseNum(mStr) ?: return null
        val s = parseNum(sStr) ?: return null
        val hem = parseHemisphere(hemStr)

        var deg = abs(d) + m / 60.0 + s / 3600.0
        val sign = when {
            hem != null -> hem.sign
            d < 0 -> -1.0
            else -> 1.0
        }
        deg *= sign
        return CoordVal(deg, hem?.axis)
    }

    private fun dmValue(dStr: String, mStr: String, hemStr: String?): CoordVal? {
        val d = parseNum(dStr) ?: return null
        val m = parseNum(mStr) ?: return null
        val hem = parseHemisphere(hemStr)

        var deg = abs(d) + m / 60.0
        val sign = when {
            hem != null -> hem.sign
            d < 0 -> -1.0
            else -> 1.0
        }
        deg *= sign
        return CoordVal(deg, hem?.axis)
    }

    private fun decValue(numStr: String, hemStr: String?): CoordVal? {
        val v = parseNum(numStr) ?: return null
        val hem = parseHemisphere(hemStr)
        var deg = abs(v)
        val sign = when {
            hem != null -> hem.sign
            v < 0 -> -1.0
            else -> 1.0
        }
        deg *= sign
        return CoordVal(deg, hem?.axis)
    }

    /**
     * Determines which coordinate is latitude and which is longitude.
     */
    private fun orderPair(v1: CoordVal, v2: CoordVal): Pair<Double, Double>? {
        // Explicit hemisphere axis
        if (v1.axis == "lat" && v2.axis == "lon") return Pair(v1.value, v2.value)
        if (v1.axis == "lon" && v2.axis == "lat") return Pair(v2.value, v1.value)
        if (v1.axis == "lat" && v2.axis == null && abs(v2.value) <= 180.0) return Pair(v1.value, v2.value)
        if (v1.axis == "lon" && v2.axis == null && abs(v2.value) <= 90.0) return Pair(v2.value, v1.value)
        if (v2.axis == "lat" && v1.axis == null && abs(v1.value) <= 180.0) return Pair(v2.value, v1.value)
        if (v2.axis == "lon" && v1.axis == null && abs(v1.value) <= 90.0) return Pair(v1.value, v2.value)

        // Range checks: |lat| <= 90, |lon| <= 180
        val canOrder1 = abs(v1.value) <= 90.0 && abs(v2.value) <= 180.0
        val canOrder2 = abs(v2.value) <= 90.0 && abs(v1.value) <= 180.0

        if (canOrder1 && !canOrder2) return Pair(v1.value, v2.value)
        if (!canOrder1 && canOrder2) return Pair(v2.value, v1.value)
        if (!canOrder1 && !canOrder2) return null

        // Both are mathematically valid (e.g. 48.5 and 35.2). Prioritize Ukraine bounding box, then default lat,lon
        val order1InUa = UA_BOX.contains(v1.value, v2.value)
        val order2InUa = UA_BOX.contains(v2.value, v1.value)
        if (order1InUa && !order2InUa) return Pair(v1.value, v2.value)
        if (!order1InUa && order2InUa) return Pair(v2.value, v1.value)

        // Default order: (v1 = lat, v2 = lon)
        return Pair(v1.value, v2.value)
    }

    private data class GkCandidate(
        val lat: Double,
        val lon: Double,
        val zone: Int,
        val score: Int
    )

    /**
     * Resolves truncated Gauss-Kruger (SK-42) coordinates.
     * Searches combinations of X prefixes {5, 4, 6} (in millions) and Y zones {6, 5, 7, 4}.
     */
    private fun convertGK(rawX: Double, rawY: Double, explicitZone: Int?): GkCandidate? {
        val xCandidates = if (rawX >= 1_000_000.0) {
            listOf(rawX to 0) // Already has million digit, no guess penalty
        } else {
            listOf(
                (rawX + 5_000_000.0) to -1, // Most common for Ukraine (lat ~45-54)
                (rawX + 4_000_000.0) to -1,
                (rawX + 6_000_000.0) to -1
            )
        }

        val yCandidates = if (explicitZone != null) {
            val yVal = if (rawY >= 1_000_000.0) (rawY % 1_000_000.0) + explicitZone * 1_000_000.0 else rawY + explicitZone * 1_000_000.0
            listOf(Triple(yVal, explicitZone, 0))
        } else if (rawY >= 1_000_000.0) {
            val z = (rawY / 1_000_000.0).toInt()
            listOf(Triple(rawY, z, 0))
        } else {
            // Truncated Y (e.g. 350123 or 500000 without zone prefix)
            // Real Gauss-Kruger 6-degree zones for Ukraine: 6 (central), 5 (west), 7 (east), 4 (far west)
            listOf(6, 5, 7, 4).map { z ->
                Triple(rawY + z * 1_000_000.0, z, -1)
            }
        }

        var best: GkCandidate? = null

        for ((xVal, xPen) in xCandidates) {
            for ((yVal, z, yPen) in yCandidates) {
                try {
                    // Inverse Gauss-Kruger gives SK-42 lat/lon on Krasovsky ellipsoid
                    val (skLat, skLon) = GaussKrugerConverter.inverse(x = xVal, y = yVal, zoneInput = z, zoneWidth = 6)
                    // Convert SK-42 to WGS84
                    val (wgsLat, wgsLon, _) = DatumTransform.sk42ToWgs84(skLat, skLon)

                    var score = 0 + xPen + yPen
                    if (UA_BOX.contains(wgsLat, wgsLon)) {
                        score += 5
                    } else if (REGION_BOX.contains(wgsLat, wgsLon)) {
                        score += 2
                    } else {
                        score -= 5
                    }

                    if (best == null || score > best.score) {
                        best = GkCandidate(wgsLat, wgsLon, z, score)
                    }
                } catch (_: Exception) {
                    // Ignore math singularities or out-of-zone errors
                }
            }
        }

        return best
    }

    fun parse(rawText: String): TextParseResult {
        val text = normalizeText(rawText)
        val mask = text.toCharArray()
        val points = mutableListOf<ParsedCoordinatePoint>()
        val warnings = mutableListOf<String>()

        fun rest(): String = String(mask)

        fun consume(start: Int, end: Int) {
            val s = max(0, start)
            val e = kotlin.math.min(mask.size, end)
            for (i in s until e) {
                mask[i] = ' '
            }
        }

        fun addPoint(lat: Double, lon: Double, label: String) {
            val outside = !UA_BOX.contains(lat, lon)
            points.add(ParsedCoordinatePoint(lat, lon, label, outside))
        }

        // =========================================================================
        // PASS 1: Gauss-Kruger with explicit X/Y labels (X...Y... or Y...X...)
        // =========================================================================
        // Example: X=5412345 Y=6312345 or X: 5412345, Y: 312345 (зона 6)
        run {
            val cur = rest()
            // Variant A: X then Y
            val reXY = Regex(
                """(?:^|[^\wА-Яа-яЁёЇїІіЄєҐґ])([XxХх])\s*[:=]?\s*($NUM_SRC)\s*(?:[,;\s]+)\s*([YyУу])\s*[:=]?\s*($NUM_SRC)(?:\s*(?:\(?[зз]она|[Zz]one)\s*[:=]?\s*(\d{1,2})\)?)?""",
                RegexOption.IGNORE_CASE
            )
            for (m in reXY.findAll(cur).toList()) {
                val xVal = parseNum(m.groupValues[2])
                val yVal = parseNum(m.groupValues[4])
                val zoneVal = m.groupValues[5].toIntOrNull()
                if (xVal != null && yVal != null) {
                    val cand = convertGK(xVal, yVal, zoneVal)
                    if (cand != null && cand.score >= 0) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(cand.lat, cand.lon, "Гаусс-Крюгер (зона ${cand.zone})")
                    }
                }
            }

            // Variant B: Y then X
            val reYX = Regex(
                """(?:^|[^\wА-Яа-яЁёЇїІіЄєҐґ])([YyУу])\s*[:=]?\s*($NUM_SRC)\s*(?:[,;\s]+)\s*([XxХх])\s*[:=]?\s*($NUM_SRC)(?:\s*(?:\(?[зз]она|[Zz]one)\s*[:=]?\s*(\d{1,2})\)?)?""",
                RegexOption.IGNORE_CASE
            )
            for (m in reYX.findAll(rest()).toList()) {
                val yVal = parseNum(m.groupValues[2])
                val xVal = parseNum(m.groupValues[4])
                val zoneVal = m.groupValues[5].toIntOrNull()
                if (xVal != null && yVal != null) {
                    val cand = convertGK(xVal, yVal, zoneVal)
                    if (cand != null && cand.score >= 0) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(cand.lat, cand.lon, "Гаусс-Крюгер (зона ${cand.zone})")
                    }
                }
            }
        }

        // =========================================================================
        // PASS 2: MGRS
        // \b(\d{1,2})\s*([C-X])\s*([A-Za-z]{2})((?:\s*\d){4,10})\b
        // =========================================================================
        run {
            val cur = rest()
            val reMgrs = Regex("""\b(\d{1,2})\s*([C-X])\s*([A-Za-z]{2})((?:\s*\d){4,10})\b""", RegexOption.IGNORE_CASE)
            for (m in reMgrs.findAll(cur).toList()) {
                val zoneNum = m.groupValues[1].toIntOrNull() ?: 0
                val band = m.groupValues[2].uppercase()
                val sq = m.groupValues[3].uppercase()
                val digits = m.groupValues[4].replace(" ", "").replace("\t", "")

                // Even number of digits required for easting/northing
                if (digits.length % 2 == 0) {
                    if (zoneNum < 10) {
                        // Do NOT guess zone; add warning about likely missing leading digit (Ukraine is zones 34-37)
                        warnings.add("MGRS '${m.value.trim()}': номер зоны $zoneNum < 10 (вероятно, потеряна ведущая цифра 3)")
                        consume(m.range.first, m.range.last + 1)
                    } else {
                        val mgrsString = "$zoneNum$band$sq$digits"
                        try {
                            val (lat, lon) = MgrsConverter.inverse(mgrsString)
                            consume(m.range.first, m.range.last + 1)
                            addPoint(lat, lon, "MGRS: $zoneNum$band $sq $digits")
                        } catch (e: Exception) {
                            warnings.add("Ошибка разбора MGRS '${m.value.trim()}': ${e.message}")
                        }
                    }
                }
            }
        }

        // =========================================================================
        // PASS 3: DMS with symbols ° ' "
        // =========================================================================
        run {
            val cur = rest()
            // One DMS coordinate with optional hemisphere before or after:
            // e.g. 48°27'53.2"N or N48°27'53.2"
            val dmsSingle = """(?:($HEM_SRC)\s*)?(-?\d{1,3})\s*°\s*(\d{1,2})\s*['′]\s*($NUM_SRC)\s*["″]?\s*($HEM_SRC)?"""
            val reDmsPair = Regex("""(?:^|[^\w°'"′″])($dmsSingle)\s*[,;\s]+\s*($dmsSingle)""", RegexOption.IGNORE_CASE)

            for (m in reDmsPair.findAll(cur).toList()) {
                // Group indexes for first coordinate: 2:hemPre, 3:d, 4:m, 5:s, 6:hemPost
                // Group indexes for second coordinate: 8:hemPre, 9:d, 10:m, 11:s, 12:hemPost
                val hem1 = m.groupValues[2].ifBlank { m.groupValues[6] }
                val v1 = dmsValue(m.groupValues[3], m.groupValues[4], m.groupValues[5], hem1)

                val hem2 = m.groupValues[8].ifBlank { m.groupValues[12] }
                val v2 = dmsValue(m.groupValues[9], m.groupValues[10], m.groupValues[11], hem2)

                if (v1 != null && v2 != null) {
                    val pair = orderPair(v1, v2)
                    if (pair != null) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(pair.first, pair.second, "DMS")
                    }
                }
            }
        }

        // =========================================================================
        // PASS 4: Ukrainian tactical notation Ш / Д (Ш49.64 Д36.97 or Ш 49°38.4' Д 36°58.2')
        // Uses negative lookbehind to avoid catching word endings like "вид" or "перед"
        // =========================================================================
        run {
            val cur = rest()
            // Matches "Ш" followed by coordinate, then "Д" followed by coordinate (or Д then Ш)
            val partSrc = """(?:(\d{1,3})\s*°\s*(\d{1,2}(?:[.,]\d+)?)\s*['′]?|($NUM_SRC))"""
            val reShD = Regex(
                """(?<![A-Za-zА-Яа-яЁёЇїІіЄєҐґ])([Шш])\s*[:=]?\s*$partSrc\s*[,;\s]+\s*(?<![A-Za-zА-Яа-яЁёЇїІіЄєҐґ])([Дд])\s*[:=]?\s*$partSrc""",
                RegexOption.IGNORE_CASE
            )

            for (m in reShD.findAll(cur).toList()) {
                // Group 1: Ш
                // Groups 2, 3: deg, min OR Group 4: dec
                // Group 5: Д
                // Groups 6, 7: deg, min OR Group 8: dec
                val latVal = if (m.groupValues[2].isNotBlank() && m.groupValues[3].isNotBlank()) {
                    dmValue(m.groupValues[2], m.groupValues[3], "N")
                } else {
                    decValue(m.groupValues[4], "N")
                }

                val lonVal = if (m.groupValues[6].isNotBlank() && m.groupValues[7].isNotBlank()) {
                    dmValue(m.groupValues[6], m.groupValues[7], "E")
                } else {
                    decValue(m.groupValues[8], "E")
                }

                if (latVal != null && lonVal != null) {
                    consume(m.range.first, m.range.last + 1)
                    addPoint(latVal.value, lonVal.value, "Ш/Д")
                }
            }
        }

        // =========================================================================
        // PASS 5: DMS without symbols (three integers with spaces: "48 27 53, 35 02 46" or "48 27 53.2 N, 35 02 46.1 E")
        // Separated by mandatory comma or semicolon
        // =========================================================================
        run {
            val cur = rest()
            val dmsNoSym = """(?:($HEM_SRC)\s*)?(-?\d{1,3})\s+(\d{1,2})\s+($NUM_SRC)(?:\s*($HEM_SRC))?"""
            val reDmsNoSymPair = Regex("""(?:^|[^\w])($dmsNoSym)\s*[,;]\s*($dmsNoSym)(?:[^\w]|$)""", RegexOption.IGNORE_CASE)

            for (m in reDmsNoSymPair.findAll(cur).toList()) {
                // First coordinate: 2:hemPre, 3:d, 4:m, 5:s, 6:hemPost
                val hem1 = m.groupValues[2].ifBlank { m.groupValues[6] }
                val v1 = dmsValue(m.groupValues[3], m.groupValues[4], m.groupValues[5], hem1)

                // Second coordinate: 8:hemPre, 9:d, 10:m, 11:s, 12:hemPost
                val hem2 = m.groupValues[8].ifBlank { m.groupValues[12] }
                val v2 = dmsValue(m.groupValues[9], m.groupValues[10], m.groupValues[11], hem2)

                if (v1 != null && v2 != null) {
                    val pair = orderPair(v1, v2)
                    if (pair != null) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(pair.first, pair.second, "DMS")
                    }
                }
            }
        }

        // =========================================================================
        // PASS 6: DMS with hyphen or colon separator ("48-27-53, 35-02-46" or "48:27:53.2, 35:02:46.1")
        // Separator is captured and repeated via backreference
        // =========================================================================
        run {
            val cur = rest()
            // Group 1: hemPre1
            // Group 2: d1
            // Group 3: separator ([-:])
            // Group 4: m1
            // Group 5: s1
            // Group 6: hemPost1
            // ...
            // Group 7: hemPre2
            // Group 8: d2
            // Group 9: separator
            // Group 10: m2
            // Group 11: s2
            // Group 12: hemPost2
            val reSepDms = Regex(
                """(?:^|[^\w])(?:($HEM_SRC)\s*)?(-?\d{1,3})([-:])(\d{1,2})\3($NUM_SRC)(?:\s*($HEM_SRC))?\s*[,;\s]+\s*(?:($HEM_SRC)\s*)?(-?\d{1,3})([-:])(\d{1,2})\9($NUM_SRC)(?:\s*($HEM_SRC))?(?:[^\w]|$)""",
                RegexOption.IGNORE_CASE
            )

            for (m in reSepDms.findAll(cur).toList()) {
                val hem1 = m.groupValues[1].ifBlank { m.groupValues[6] }
                val v1 = dmsValue(m.groupValues[2], m.groupValues[4], m.groupValues[5], hem1)

                val hem2 = m.groupValues[7].ifBlank { m.groupValues[12] }
                val v2 = dmsValue(m.groupValues[8], m.groupValues[10], m.groupValues[11], hem2)

                if (v1 != null && v2 != null) {
                    val pair = orderPair(v1, v2)
                    if (pair != null) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(pair.first, pair.second, "DMS")
                    }
                }
            }
        }

        // =========================================================================
        // PASS 7: DM — degrees and decimal minutes ("48° 27.887' N, 35° 02.771' E" or "48 27.887 N, 35 02.771 E")
        // Either contains degree sign (°), or degrees and minutes are separated by whitespace (\s+)
        // =========================================================================
        run {
            val cur = rest()
            val dmSingle = """(?:(?:($HEM_SRC)\s*)?(-?\d{1,3})\s*°\s*(\d{1,2}(?:[.,]\d+))\s*['′]?(?:\s*($HEM_SRC))?|(?:($HEM_SRC)\s*)?(-?\d{1,3})\s+(\d{1,2}(?:[.,]\d+))\s*['′]?(?:\s*($HEM_SRC))?)"""
            val reDmPair = Regex("""(?:^|[^\w°'"′″])($dmSingle)\s*[,;\s]+\s*($dmSingle)""", RegexOption.IGNORE_CASE)

            for (m in reDmPair.findAll(cur).toList()) {
                // First coordinate:
                // If branch 1 (with °): 2:hemPre, 3:d, 4:m, 5:hemPost
                // If branch 2 (with \s+): 6:hemPre, 7:d, 8:m, 9:hemPost
                val d1 = m.groupValues[3].ifBlank { m.groupValues[7] }
                val m1 = m.groupValues[4].ifBlank { m.groupValues[8] }
                val hem1 = (m.groupValues[2].ifBlank { m.groupValues[5] }).ifBlank {
                    m.groupValues[6].ifBlank { m.groupValues[9] }
                }
                val v1 = if (d1.isNotBlank() && m1.isNotBlank()) dmValue(d1, m1, hem1) else null

                // Second coordinate:
                // Branch 1: 11:hemPre, 12:d, 13:m, 14:hemPost
                // Branch 2: 15:hemPre, 16:d, 17:m, 18:hemPost
                val d2 = m.groupValues[12].ifBlank { m.groupValues[16] }
                val m2 = m.groupValues[13].ifBlank { m.groupValues[17] }
                val hem2 = (m.groupValues[11].ifBlank { m.groupValues[14] }).ifBlank {
                    m.groupValues[15].ifBlank { m.groupValues[18] }
                }
                val v2 = if (d2.isNotBlank() && m2.isNotBlank()) dmValue(d2, m2, hem2) else null

                if (v1 != null && v2 != null) {
                    val pair = orderPair(v1, v2)
                    if (pair != null) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(pair.first, pair.second, "DM")
                    }
                }
            }
        }

        // =========================================================================
        // PASS 8: Decimal degrees with date protection
        // Protection from dates (e.g. 12.05.2026): lookahead/lookbehind checks
        // =========================================================================
        run {
            val cur = rest()
            // Individual decimal degree number: (-?\d{1,3}[.,]\d{2,10})
            // Negative lookahead (?!\d|[.,]\d) to prevent matching within dates like 12.05.2026
            val decSingle = """(?:($HEM_SRC)\s*)?(-?\d{1,3}[.,]\d{2,10})(?!\d|[.,]\d)(?:\s*°)?(?:\s*($HEM_SRC))?"""
            val reDecPair = Regex(
                """(?:^|[^\w.,])($decSingle)\s*[,;\s]+\s*($decSingle)(?=[^\w.,]|$)""",
                RegexOption.IGNORE_CASE
            )

            for (m in reDecPair.findAll(cur).toList()) {
                // Group 2: hemPre1, 3: num1, 4: hemPost1
                val hem1 = m.groupValues[2].ifBlank { m.groupValues[4] }
                val v1 = decValue(m.groupValues[3], hem1)

                // Group 6: hemPre2, 7: num2, 8: hemPost2
                val hem2 = m.groupValues[6].ifBlank { m.groupValues[8] }
                val v2 = decValue(m.groupValues[7], hem2)

                if (v1 != null && v2 != null) {
                    val pair = orderPair(v1, v2)
                    if (pair != null) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(pair.first, pair.second, "DEC")
                    }
                }
            }
        }

        // =========================================================================
        // PASS 9: Gauss-Kruger without labels (last resort)
        // Runs ONLY if nothing was found yet (points.isEmpty()) and requires score >= 2
        // =========================================================================
        if (points.isEmpty()) {
            val cur = rest()
            // Pair of numbers: 6 or 7 digits (with optional grouping spaces)
            val gkNum = """\b\d{1,2}(?:[ \t]\d{3}){2}(?:[.,]\d+)?\b|\b\d{6,8}(?:[.,]\d+)?\b"""
            val reGkNoLabels = Regex("""($gkNum)\s*[,;\s]+\s*($gkNum)""")

            for (m in reGkNoLabels.findAll(cur).toList()) {
                val n1 = parseNum(m.groupValues[1])
                val n2 = parseNum(m.groupValues[2])
                if (n1 != null && n2 != null) {
                    // Try (X=n1, Y=n2) and (X=n2, Y=n1)
                    val cand1 = convertGK(n1, n2, null)
                    val cand2 = convertGK(n2, n1, null)

                    val best = when {
                        cand1 != null && cand2 != null -> if (cand1.score >= cand2.score) cand1 else cand2
                        cand1 != null -> cand1
                        cand2 != null -> cand2
                        else -> null
                    }

                    if (best != null && best.score >= 2) {
                        consume(m.range.first, m.range.last + 1)
                        addPoint(best.lat, best.lon, "Гаусс-Крюгер (зона ${best.zone})")
                    }
                }
            }
        }

        return TextParseResult(points = points, warnings = warnings)
    }
}
