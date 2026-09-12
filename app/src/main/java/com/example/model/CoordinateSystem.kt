package com.example.model

/**
 * Coordinate systems supported for display, compact bar, and conversion.
 */
enum class CoordinateSystem(val id: String, val displayName: String, val shortName: String) {
    WGS84_DECIMAL("wgs84_dec", "WGS84 (десятичные градусы)", "WGS84 DD"),
    WGS84_DMS("wgs84_dms", "WGS84 (градусы, минуты, секунды)", "WGS84 DMS"),
    MGRS("mgrs", "MGRS (военная координатная сетка)", "MGRS"),
    GAUSS_KRUGER("gauss_kruger", "СК-42 / Гаусс-Крюгер (6°)", "СК-42 / Гаусс-Крюгер"),
    USK_2000("usk_2000", "УСК-2000 (Гаусс-Крюгер 3°)", "УСК-2000");

    val title: String get() = shortName

    companion object {
        val GAUSS_KRUGER_SK42 = GAUSS_KRUGER
        val DEFAULT = MGRS // Common military / navigation standard or configurable

        fun fromId(id: String): CoordinateSystem {
            return entries.find { it.id == id } ?: DEFAULT
        }
    }
}

/**
 * Encapsulates the coordinates represented in all supported systems.
 */
data class CoordinateBundle(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val wgs84Decimal: String,
    val wgs84Dms: String,
    val mgrs: String,
    val gaussKruger: String,
    val gaussKrugerZone: Int,
    val gaussKrugerX: Double,
    val gaussKrugerY: Double,
    val usk2000: String,
    val usk2000Zone: Int,
    val usk2000X: Double,
    val usk2000Y: Double
) {
    fun getFormatted(system: CoordinateSystem): String {
        return when (system) {
            CoordinateSystem.WGS84_DECIMAL -> wgs84Decimal
            CoordinateSystem.WGS84_DMS -> wgs84Dms
            CoordinateSystem.MGRS -> mgrs
            CoordinateSystem.GAUSS_KRUGER -> gaussKruger
            CoordinateSystem.USK_2000 -> usk2000
        }
    }
}
