package com.example.data.elevation

import com.example.geodesy.GeodesyEngine
import com.example.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * How the sight line bends, which decides how far it reaches.
 *
 * Radio waves refract in the atmosphere and follow the surface further than light does; the
 * standard way to account for it is to pretend Earth's radius is 4/3 of the real one. Using the
 * visual setting for a radio link under-reports range, and the radio setting for the naked eye
 * over-reports it — which, when picking an observation post, is the dangerous direction.
 */
enum class SightMode(val label: String, val radiusFactor: Double) {
    VISUAL("Глазом", 1.0),
    RADIO("Радиосвязь", 4.0 / 3.0)
}

/** Common target heights, so the number is chosen rather than guessed. */
enum class TargetPreset(val label: String, val heightMeters: Double) {
    PERSON("Человек", 1.7),
    VEHICLE("Машина", 2.0),
    BUILDING("Строение", 10.0),
    TOWER("Вышка", 50.0)
}

data class ProfileSample(
    /** Distance from the observer along the ground, metres. */
    val distanceMeters: Double,
    val point: GeoPoint,
    /** Terrain height above sea level, metres. */
    val terrainMeters: Double,
    /** Height of the straight sight line at this distance, metres above sea level. */
    val sightLineMeters: Double,
    /** Terrain height with the Earth-curvature drop already applied. */
    val effectiveTerrainMeters: Double
) {
    val isBlocked: Boolean get() = effectiveTerrainMeters > sightLineMeters

    /** How much the terrain sticks up through the sight line, metres. */
    val obstructionMeters: Double get() = effectiveTerrainMeters - sightLineMeters
}

data class LineOfSightResult(
    val samples: List<ProfileSample>,
    val isVisible: Boolean,
    /** Worst obstruction, present only when the line is blocked. */
    val worstObstacle: ProfileSample?,
    /** Extra height the observer needs for the line to clear, metres. */
    val requiredExtraHeightMeters: Double,
    val totalDistanceMeters: Double,
    val observerElevation: Double,
    val targetElevation: Double,
    val hasGaps: Boolean
)

/**
 * Terrain line-of-sight between two points, sampled from local SRTM data.
 *
 * Important limitation to keep in mind when reading the result: SRTM is a radar surface model,
 * so over woodland it measures roughly the canopy rather than the ground — helpful here, since
 * trees really do block the view. But isolated masts, poles and buildings are not in it at all.
 * The answer is therefore "terrain and vegetation do not block this", not "you will definitely
 * see it".
 */
object LineOfSight {

    /** Mean Earth radius, metres. */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Sampling step along the ground; finer than SRTM-1 spacing would add nothing. */
    const val SAMPLE_STEP_METERS = 25.0

    const val MAX_SAMPLES = 2000

    const val DEFAULT_OBSERVER_HEIGHT = 1.7
    const val DEFAULT_TARGET_HEIGHT = 1.7

    /**
     * Drop of the Earth's surface below a straight line, at [distance] from one end of a chord
     * of length [total].
     */
    private fun curvatureDrop(distance: Double, total: Double, mode: SightMode): Double {
        val r = EARTH_RADIUS_M * mode.radiusFactor
        return distance * (total - distance) / (2.0 * r)
    }

    suspend fun analyze(
        engine: ElevationEngine,
        observer: GeoPoint,
        target: GeoPoint,
        observerHeightMeters: Double = DEFAULT_OBSERVER_HEIGHT,
        targetHeightMeters: Double = DEFAULT_TARGET_HEIGHT,
        mode: SightMode = SightMode.VISUAL
    ): LineOfSightResult? = withContext(Dispatchers.IO) {

        val totalDistance = GeodesyEngine.distanceMeters(observer, target)
        if (totalDistance < 1.0) return@withContext null

        val observerGround = engine.getElevation(observer.latitude, observer.longitude)
            ?: return@withContext null
        val targetGround = engine.getElevation(target.latitude, target.longitude)
            ?: return@withContext null

        val observerTop = observerGround + observerHeightMeters
        val targetTop = targetGround + targetHeightMeters

        val azimuth = GeodesyEngine.azimuthDegrees(observer, target)

        val stepCount = (totalDistance / SAMPLE_STEP_METERS).toInt().coerceIn(2, MAX_SAMPLES)
        val step = totalDistance / stepCount

        val samples = ArrayList<ProfileSample>(stepCount + 1)
        var hasGaps = false

        for (i in 0..stepCount) {
            val d = i * step
            val point = GeodesyEngine.destinationPoint(observer, d, azimuth)
            val terrain = engine.getElevation(point.latitude, point.longitude)

            if (terrain == null) {
                hasGaps = true
                continue
            }

            // Straight line from observer's eye to the top of the target.
            val sightLine = observerTop + (targetTop - observerTop) * (d / totalDistance)

            // Curvature lifts the terrain relative to a straight chord.
            val effective = terrain + curvatureDrop(d, totalDistance, mode)

            samples.add(
                ProfileSample(
                    distanceMeters = d,
                    point = point,
                    terrainMeters = terrain,
                    sightLineMeters = sightLine,
                    effectiveTerrainMeters = effective
                )
            )
        }

        if (samples.size < 2) return@withContext null

        // Ignore the samples right at either end: the observer and target stand on their own
        // ground, which would otherwise register as blocking them.
        val interior = samples.filter {
            it.distanceMeters > step && it.distanceMeters < totalDistance - step
        }

        val worst = interior.maxByOrNull { it.obstructionMeters }
        val blocked = worst != null && worst.isBlocked

        // How much higher the observer must stand for the line to clear everything. Raising the
        // observer lifts the line most near the observer and not at all at the target, so each
        // obstruction needs its own amount and we take the largest.
        var requiredExtra = 0.0
        if (blocked) {
            for (s in interior) {
                if (!s.isBlocked) continue
                val remainingFraction = 1.0 - (s.distanceMeters / totalDistance)
                if (remainingFraction <= 0.0001) continue
                requiredExtra = max(requiredExtra, s.obstructionMeters / remainingFraction)
            }
        }

        LineOfSightResult(
            samples = samples,
            isVisible = !blocked,
            worstObstacle = if (blocked) worst else null,
            requiredExtraHeightMeters = requiredExtra,
            totalDistanceMeters = totalDistance,
            observerElevation = observerGround,
            targetElevation = targetGround,
            hasGaps = hasGaps
        )
    }

    /**
     * Elevation profile along a route, with the climb/descent statistics.
     */
    suspend fun routeProfile(
        engine: ElevationEngine,
        points: List<GeoPoint>
    ): RouteProfile? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null

        val samples = ArrayList<Pair<Double, Double>>()
        var distance = 0.0
        var gaps = false

        for (i in points.indices) {
            if (i > 0) distance += GeodesyEngine.distanceMeters(points[i - 1], points[i])
            val h = engine.getElevation(points[i].latitude, points[i].longitude)
            if (h == null) {
                gaps = true
                continue
            }
            samples.add(distance to h)
        }

        if (samples.size < 2) return@withContext null

        var climb = 0.0
        var descent = 0.0
        for (i in 1 until samples.size) {
            val delta = samples[i].second - samples[i - 1].second
            if (delta > 0) climb += delta else descent -= delta
        }

        RouteProfile(
            samples = samples,
            minElevation = samples.minOf { it.second },
            maxElevation = samples.maxOf { it.second },
            totalClimb = climb,
            totalDescent = descent,
            totalDistanceMeters = distance,
            hasGaps = gaps
        )
    }
}

data class RouteProfile(
    /** (distance metres, elevation metres) pairs. */
    val samples: List<Pair<Double, Double>>,
    val minElevation: Double,
    val maxElevation: Double,
    val totalClimb: Double,
    val totalDescent: Double,
    val totalDistanceMeters: Double,
    val hasGaps: Boolean
) {
    /** Average gradient over the route, percent. */
    val averageGradientPercent: Double
        get() = if (totalDistanceMeters > 0) (totalClimb / totalDistanceMeters) * 100.0 else 0.0
}
