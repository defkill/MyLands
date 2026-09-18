package com.example.data.settlement

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

class SettlementRepository(private val context: Context) {

    private val tag = "SettlementRepository"
    private var cachedSettlements: List<Settlement>? = null
    private var cachedOblasts: List<String>? = null

    suspend fun getSettlements(): List<Settlement> = withContext(Dispatchers.IO) {
        cachedSettlements?.let { return@withContext it }

        val list = loadFromSources()
        cachedSettlements = list
        cachedOblasts = list.map { it.oblast }.filter { it.isNotBlank() }.distinct().sorted()
        Log.i(tag, "Loaded ${list.size} settlements into search cache")
        list
    }

    suspend fun getOblasts(): List<String> = withContext(Dispatchers.IO) {
        cachedOblasts?.let { return@withContext it }
        getSettlements()
        cachedOblasts ?: emptyList()
    }

    suspend fun search(
        query: String,
        oblastFilter: String? = null,
        limit: Int = 100
    ): List<Settlement> = withContext(Dispatchers.Default) {
        val all = getSettlements()
        val q = query.trim().lowercase()

        val filteredByOblast = if (oblastFilter.isNullOrBlank()) {
            all
        } else {
            val filterLower = oblastFilter.trim().lowercase()
            all.filter { it.oblastLower == filterLower }
        }

        if (q.isEmpty()) {
            return@withContext filteredByOblast.take(limit)
        }

        // Rank results: exact name match > startsWith > contains in name > contains in oblast
        val exactMatches = mutableListOf<Settlement>()
        val startsWithMatches = mutableListOf<Settlement>()
        val containsMatches = mutableListOf<Settlement>()

        for (item in filteredByOblast) {
            val nameLower = item.nameLower
            val oblastLower = item.oblastLower

            if (nameLower == q) {
                exactMatches.add(item)
            } else if (nameLower.startsWith(q)) {
                startsWithMatches.add(item)
            } else if (nameLower.contains(q) || oblastLower.contains(q)) {
                containsMatches.add(item)
            }
        }

        (exactMatches + startsWithMatches + containsMatches).take(limit)
    }

    private fun loadFromSources(): List<Settlement> {
        // Priority 1: Check internal filesDir for user-dropped updates
        val externalFile = File(context.filesDir, "settlements_ua.csv")
        if (externalFile.exists() && externalFile.length() > 0) {
            try {
                externalFile.inputStream().use { stream ->
                    return parseCsv(stream)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse filesDir/settlements_ua.csv", e)
            }
        }

        // Priority 2: Check assets for settlements_ua.csv
        try {
            context.assets.open("settlements_ua.csv").use { stream ->
                val list = parseCsv(stream)
                if (list.isNotEmpty()) return list
            }
        } catch (e: Exception) {
            Log.d(tag, "settlements_ua.csv not found in assets or failed: ${e.message}")
        }

        // Priority 3: Check assets for settlements_ua.json
        try {
            context.assets.open("settlements_ua.json").use { stream ->
                val list = parseJson(stream)
                if (list.isNotEmpty()) return list
            }
        } catch (e: Exception) {
            Log.d(tag, "settlements_ua.json not found in assets: ${e.message}")
        }

        // Priority 4: Check assets for settlements.geojson
        try {
            context.assets.open("settlements.geojson").use { stream ->
                val list = parseGeoJson(stream)
                if (list.isNotEmpty()) return list
            }
        } catch (e: Exception) {
            Log.d(tag, "settlements.geojson not found in assets: ${e.message}")
        }

        return emptyList()
    }

    private fun parseCsv(stream: InputStream): List<Settlement> {
        val results = ArrayList<Settlement>(30000)
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 32768).use { reader ->
            val firstLine = reader.readLine() ?: return emptyList()

            val delimiter = when {
                firstLine.contains(";") -> ";"
                firstLine.contains("\t") -> "\t"
                else -> ","
            }

            val headers = firstLine.split(delimiter).map { it.trim().lowercase().replace("\"", "") }
            var nameIdx = headers.indexOfFirst { it == "name" || it == "назва" || it == "settlement" || it == "city" }
            var oblastIdx = headers.indexOfFirst { it == "oblast" || it == "область" || it == "region" }
            var latIdx = headers.indexOfFirst { it == "latitude" || it == "lat" || it == "широта" }
            var lonIdx = headers.indexOfFirst { it == "longitude" || it == "lon" || it == "lng" || it == "довгота" }

            // Fallback column positions if header is missing or non-standard
            if (nameIdx == -1 && headers.isNotEmpty()) nameIdx = 0
            if (oblastIdx == -1 && headers.size > 1) oblastIdx = 1
            if (latIdx == -1 && headers.size > 2) latIdx = 2
            if (lonIdx == -1 && headers.size > 3) lonIdx = 3

            var line: String? = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    val cols = line.split(delimiter)
                    if (cols.size > maxOf(nameIdx, oblastIdx, latIdx, lonIdx)) {
                        val name = cols[nameIdx].trim().replace("\"", "")
                        val oblast = cols[oblastIdx].trim().replace("\"", "")
                        val lat = cols[latIdx].trim().replace("\"", "").toDoubleOrNull()
                        val lon = cols[lonIdx].trim().replace("\"", "").toDoubleOrNull()

                        if (name.isNotEmpty() && lat != null && lon != null) {
                            results.add(Settlement(name, oblast, lat, lon))
                        }
                    }
                }
                line = reader.readLine()
            }
        }
        return results
    }

    private fun parseJson(stream: InputStream): List<Settlement> {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(text)
        val results = ArrayList<Settlement>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.optString("name").ifEmpty { obj.optString("назва") }
            val oblast = obj.optString("oblast").ifEmpty { obj.optString("область") }
            val lat = if (obj.has("latitude")) obj.optDouble("latitude") else obj.optDouble("lat")
            val lon = if (obj.has("longitude")) obj.optDouble("longitude") else obj.optDouble("lon", obj.optDouble("lng"))

            if (name.isNotEmpty() && !lat.isNaN() && !lon.isNaN()) {
                results.add(Settlement(name, oblast, lat, lon))
            }
        }
        return results
    }

    private fun parseGeoJson(stream: InputStream): List<Settlement> {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        val features = root.optJSONArray("features") ?: return emptyList()
        val results = ArrayList<Settlement>(features.length())

        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: JSONObject()
            val geom = f.optJSONObject("geometry") ?: JSONObject()
            val coords = geom.optJSONArray("coordinates")

            val name = props.optString("name").ifEmpty { props.optString("назва") }
            val oblast = props.optString("oblast").ifEmpty { props.optString("область") }

            if (coords != null && coords.length() >= 2) {
                val lon = coords.getDouble(0)
                val lat = coords.getDouble(1)
                if (name.isNotEmpty()) {
                    results.add(Settlement(name, oblast, lat, lon))
                }
            }
        }
        return results
    }
}
