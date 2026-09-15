package com.example.data.track

import com.example.data.entity.TrackPointEntity
import com.example.geodesy.GeodesyEngine
import com.example.model.GeoPoint

/**
 * Removes GPS outliers from a recorded track.
 *
 * Nothing is ever deleted from the database: raw points stay on disk and this filter is applied
 * when drawing, measuring and exporting. If the filter is ever wrong, the original walk is still
 * recoverable — which matters when the track is what gets someone out of a forest.
 *
 * Dead-reckoning points are never filtered: step-based positions drift smoothly and never jump,
 * so the outlier logic simply does not apply to them.
 */
object TrackFilter {

    /** Fixes reported less precise than this are untrustworthy for path drawing. */
    const val MAX_ACCURACY_METERS = 30.0f

    /** Anything above this is not physically plausible on foot or in a vehicle on tracks. */
    const val MAX_SPEED_MPS = 35.0

    /** Below this, consecutive fixes are GPS drift while standing still rather than movement. */
    const val MIN_MOVEMENT_METERS = 8.0

    /**
     * Upper bound on the gap between two consecutive dead-reckoning points.
     *
     * PDR flushes every ~18 m, on a 25° turn, or after 60 s, so neighbouring PDR points are
     * always close together. A far larger gap is not a walk — it means the heading was stale or
     * the anchor was wrong, and the leg was projected as one long straight line. Such segments
     * were previously passed through untouched because "dead reckoning never jumps"; it does
     * when its inputs are broken.
     */
    const val MAX_PDR_STEP_METERS = 60.0

    /** Walking speed ceiling used to sanity-check dead-reckoning legs. */
    const val MAX_WALKING_SPEED_MPS = 3.0

    /**
     * Bounds on the rubber-band scale factor. A correction far outside this range means the
     * inputs were wrong rather than the step length, and stretching the leg to match would do
     * more harm than leaving it.
     */
    const val MIN_RUBBER_SCALE = 0.4
    const val MAX_RUBBER_SCALE = 2.5

    /**
     * How many later fixes must agree with a suspicious jump before it is accepted.
     *
     * This is the "quarantine": a single wild fix that snaps back is discarded, but a genuine
     * fast displacement (stepping out of tree cover, a lift in a vehicle) is confirmed by the
     * points that follow it and kept.
     */
    const val CONFIRMATION_POINTS = 2

    /** Jumps are only confirmable if the follow-up fixes stay within this radius of the jump. */
    const val CONFIRMATION_RADIUS_METERS = 60.0

    data class Result(
        val points: List<TrackPointEntity>,
        val distanceMeters: Double,
        val rejectedCount: Int
    )

    /**
     * Returns the cleaned path plus its length.
     *
     * Passing [enabled] = false gives the raw track untouched, for the "show raw track" toggle.
     */
    fun filter(points: List<TrackPointEntity>, enabled: Boolean = true): Result {
        if (!enabled || points.size < 2) {
            return Result(points, rawDistance(points), 0)
        }

        val kept = ArrayList<TrackPointEntity>(points.size)
        var rejected = 0

        for ((index, candidate) in points.withIndex()) {
            if (candidate.source == TrackPointEntity.SOURCE_DEAD_RECKONING) {
                val previous = kept.lastOrNull()
                if (previous == null) {
                    kept.add(candidate)
                    continue
                }

                // Only compare against another dead-reckoning point. The FIRST point of a blind
                // leg is measured from a GPS fix that may itself have been off, and judging the
                // leg by that distance threw away the entire (correctly shaped) walk. Keeping
                // the leg and letting its own internal consistency speak is the better trade:
                // a displaced path still shows where the user went relative to themselves.
                if (previous.source != TrackPointEntity.SOURCE_DEAD_RECKONING) {
                    kept.add(candidate)
                    continue
                }

                val d = GeodesyEngine.distanceMeters(
                    previous.latitude, previous.longitude,
                    candidate.latitude, candidate.longitude
                )
                val dtSec = (candidate.timestamp - previous.timestamp) / 1000.0
                val pace = if (dtSec > 0.0) d / dtSec else Double.MAX_VALUE

                // Within a leg, neighbouring points are flushed every few metres, so a big gap
                // means the heading or anchor broke mid-leg.
                if (d > MAX_PDR_STEP_METERS || pace > MAX_WALKING_SPEED_MPS) {
                    rejected++
                    continue
                }

                kept.add(candidate)
                continue
            }

            val acc = candidate.accuracyMeters
            if (acc != null && acc > MAX_ACCURACY_METERS) {
                rejected++
                continue
            }

            val previous = kept.lastOrNull()
            if (previous == null) {
                kept.add(candidate)
                continue
            }

            val distance = GeodesyEngine.distanceMeters(
                previous.latitude, previous.longitude,
                candidate.latitude, candidate.longitude
            )

            // Drift while stationary: same place, new fix. Skip without counting as an error.
            if (distance < MIN_MOVEMENT_METERS) {
                continue
            }

            val elapsedSec = (candidate.timestamp - previous.timestamp) / 1000.0
            val speed = if (elapsedSec > 0.0) distance / elapsedSec else Double.MAX_VALUE

            if (speed <= MAX_SPEED_MPS) {
                kept.add(candidate)
                continue
            }

            // Implausible jump: accept only if the following fixes stay near it.
            if (isConfirmedByFollowing(points, index)) {
                kept.add(candidate)
            } else {
                rejected++
            }
        }

        val corrected = closeDeadReckoningGaps(kept)
        return Result(corrected, rawDistance(corrected), rejected)
    }

    /**
     * True when enough of the fixes after [index] sit close to points[index], meaning the device
     * really moved there instead of bouncing out and back.
     */
    private fun isConfirmedByFollowing(points: List<TrackPointEntity>, index: Int): Boolean {
        val jump = points[index]
        var agreeing = 0

        var i = index + 1
        while (i < points.size && i <= index + CONFIRMATION_POINTS * 2) {
            val next = points[i]
            if (next.source != TrackPointEntity.SOURCE_DEAD_RECKONING) {
                val d = GeodesyEngine.distanceMeters(
                    jump.latitude, jump.longitude, next.latitude, next.longitude
                )
                if (d <= CONFIRMATION_RADIUS_METERS) {
                    agreeing++
                    if (agreeing >= CONFIRMATION_POINTS) return true
                }
            }
            i++
        }
        return false
    }


    /**
     * Rubber-bands dead-reckoning legs onto the GPS fixes that bracket them.
     *
     * A blind leg accumulates two kinds of error: a rotation (the phone's carry angle and
     * magnetic disturbance) and a scale error (the assumed step length). When the signal comes
     * back we know where the leg truly started and truly ended, so the whole leg can be rotated
     * and scaled about its anchor to meet both. The shape of the walk — which is the part dead
     * reckoning gets right — is preserved.
     *
     * Only legs with a GPS fix on BOTH sides can be corrected; an open-ended leg (signal never
     * returned) is left exactly as recorded, because there is nothing to align it to.
     */
    private fun closeDeadReckoningGaps(points: List<TrackPointEntity>): List<TrackPointEntity> {
        if (points.size < 3) return points

        val result = points.toMutableList()
        var i = 0

        while (i < result.size) {
            if (result[i].source != TrackPointEntity.SOURCE_DEAD_RECKONING) {
                i++
                continue
            }

            val legStart = i
            var legEnd = i
            while (legEnd + 1 < result.size &&
                result[legEnd + 1].source == TrackPointEntity.SOURCE_DEAD_RECKONING
            ) {
                legEnd++
            }

            val anchor = result.getOrNull(legStart - 1)
            val closing = result.getOrNull(legEnd + 1)

            if (anchor != null && closing != null &&
                anchor.source != TrackPointEntity.SOURCE_DEAD_RECKONING &&
                closing.source != TrackPointEntity.SOURCE_DEAD_RECKONING
            ) {
                applyRubberBand(result, legStart, legEnd, anchor, closing)
            }

            i = legEnd + 1
        }

        return result
    }

    private fun applyRubberBand(
        points: MutableList<TrackPointEntity>,
        legStart: Int,
        legEnd: Int,
        anchor: TrackPointEntity,
        closing: TrackPointEntity
    ) {
        val last = points[legEnd]

        val estimatedDistance = GeodesyEngine.distanceMeters(
            anchor.latitude, anchor.longitude, last.latitude, last.longitude
        )
        val trueDistance = GeodesyEngine.distanceMeters(
            anchor.latitude, anchor.longitude, closing.latitude, closing.longitude
        )

        // Too short to infer anything reliable; a tiny estimated vector makes the rotation
        // meaningless and the scale explode.
        if (estimatedDistance < 5.0) return

        val estimatedBearing = GeodesyEngine.azimuthDegrees(
            anchor.latitude, anchor.longitude, last.latitude, last.longitude
        )
        val trueBearing = GeodesyEngine.azimuthDegrees(
            anchor.latitude, anchor.longitude, closing.latitude, closing.longitude
        )

        val rotation = trueBearing - estimatedBearing
        val scale = (trueDistance / estimatedDistance).coerceIn(MIN_RUBBER_SCALE, MAX_RUBBER_SCALE)

        for (index in legStart..legEnd) {
            val p = points[index]
            val d = GeodesyEngine.distanceMeters(
                anchor.latitude, anchor.longitude, p.latitude, p.longitude
            )
            val b = GeodesyEngine.azimuthDegrees(
                anchor.latitude, anchor.longitude, p.latitude, p.longitude
            )
            val moved = GeodesyEngine.destinationPoint(
                GeoPoint(anchor.latitude, anchor.longitude),
                d * scale,
                b + rotation
            )
            points[index] = p.copy(latitude = moved.latitude, longitude = moved.longitude)
        }
    }

    private fun rawDistance(points: List<TrackPointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += GeodesyEngine.distanceMeters(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude
            )
        }
        return total
    }
}
