package com.example.model

import java.util.Locale

/**
 * Supported angle measurement units.
 * Default is [DEGREES_360].
 */
enum class AngleUnit(val displayName: String, val shortSymbol: String) {
    DEGREES_360("Градусы (360°)", "°"),
    MILS_60_00("Деления угломера (60-00)", "ду");

    companion object {
        val DEFAULT = DEGREES_360

        /**
         * Normalizes any degree value into [0.0, 360.0).
         */
        fun normalizeDegrees(deg: Double): Double {
            var d = deg % 360.0
            if (d < 0.0) d += 360.0
            return d
        }

        /**
         * Converts degrees [0..360] to 60-00 mils [0..6000].
         * 1 circle = 6000 mils.
         */
        fun degreesToMils60(deg: Double): Double {
            val normalized = normalizeDegrees(deg)
            return (normalized / 360.0) * 6000.0
        }

        /**
         * Converts 60-00 mils to degrees [0..360].
         */
        fun mils60ToDegrees(mils: Double): Double {
            var m = mils % 6000.0
            if (m < 0.0) m += 6000.0
            return (m / 6000.0) * 360.0
        }

        /**
         * Formats an angle in degrees into the selected [AngleUnit].
         * For DEGREES_360: e.g. "145.2°" or optional DMS.
         * For MILS_60_00: e.g. "24-20" (24 hundreds, 20 units).
         */
        fun format(degrees: Double, unit: AngleUnit): String {
            val norm = normalizeDegrees(degrees)
            return when (unit) {
                DEGREES_360 -> String.format(Locale.US, "%.1f°", norm)
                MILS_60_00 -> {
                    val mils = (norm / 360.0) * 6000.0
                    val totalMils = Math.round(mils).toInt() % 6000
                    val hundreds = totalMils / 100
                    val units = totalMils % 100
                    String.format(Locale.US, "%02d-%02d", hundreds, units)
                }
            }
        }

        /**
         * Formats an angle in degrees with higher precision.
         */
        fun formatDetailed(degrees: Double, unit: AngleUnit): String {
            val norm = normalizeDegrees(degrees)
            return when (unit) {
                DEGREES_360 -> {
                    val d = norm.toInt()
                    val minutesTotal = (norm - d) * 60.0
                    val m = minutesTotal.toInt()
                    val s = (minutesTotal - m) * 60.0
                    String.format(Locale.US, "%d°%02d'%04.1f\" (%.3f°)", d, m, s, norm)
                }
                MILS_60_00 -> {
                    val mils = (norm / 360.0) * 6000.0
                    val totalMils = Math.round(mils).toInt() % 6000
                    val hundreds = totalMils / 100
                    val units = totalMils % 100
                    String.format(Locale.US, "%02d-%02d (%.1f ду)", hundreds, units, mils)
                }
            }
        }

        /**
         * Calculates reverse azimuth (azimuth + 180° / + 30-00 mils).
         */
        fun reverseAzimuth(degrees: Double): Double {
            return normalizeDegrees(degrees + 180.0)
        }
    }
}
