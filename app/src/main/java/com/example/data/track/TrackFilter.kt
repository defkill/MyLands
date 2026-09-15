package com.example.data.track

import com.example.data.entity.TrackPointEntity
import com.example.geodesy.GeodesyEngine

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
                val prevPdr = kept.lastOrNull()
                if (prevPdr == null) {
                    kept.add(candidate)
                    continue
                }

                val d = GeodesyEngine.distanceMeters(
                    prevPdr.latitude, prevPdr.longitude,
                    candidate.latitude, candidate.longitude
                )
                val dtSec = (candidate.timestamp - prevPdr.timestamp) / 1000.0
                val pace = if (dtSec > 0.0) d / dtSec else Double.MAX_VALUE

                // Reject legs that no walker could have produced between two flushes.
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

        return Result(kept, rawDistance(kept), rejected)
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
