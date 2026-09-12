package com.example

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.example.data.AppDatabase
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.model.GeoPoint
import com.example.sensor.FlushedPdrPoint
import com.example.sensor.PdrFlushReason
import com.example.sensor.StepDetectorManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdrAdaptiveBatchingTest {

    @Test
    fun `test in-memory step vector accumulation does not flush prematurely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdrManager = StepDetectorManager(
            context = context,
            stepLengthMeters = 0.75f,
            displacementThresholdMeters = 18.0,
            headingDiffThresholdDeg = 25.0f,
            timeoutThresholdMs = 60_000L
        )

        val anchor = GeoPoint(50.4501, 30.5234, 150.0)
        pdrManager.updateGpsAnchor(anchor, timestamp = 1000L)

        val flushedPoints = mutableListOf<FlushedPdrPoint>()
        pdrManager.onStepFlushed = { flushedPoints.add(it) }

        // Walk 10 steps facing North (heading = 0.0°)
        // Total displacement = 10 * 0.75 = 7.5 meters (< 18.0m threshold)
        pdrManager.updateCurrentHeading(0.0f)
        for (i in 1..10) {
            pdrManager.registerStep(timestamp = 1000L + i * 500L)
        }

        assertEquals(10, pdrManager.inMemoryAccumulatedSteps)
        assertEquals(7.5, pdrManager.inMemoryAccumulatedDisplacement, 0.01)
        // No flush should have occurred yet
        assertEquals(0, flushedPoints.size)
        // PDR state for UI has updated
        assertEquals(10, pdrManager.pdrState.value.totalSteps)
        assertEquals(7.5, pdrManager.pdrState.value.totalDistanceMeters, 0.01)
        assertNotNull(pdrManager.pdrState.value.lastEstimatedPosition)
    }

    @Test
    fun `test Criterion 1 - displacement threshold 18m triggers flush`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdrManager = StepDetectorManager(
            context = context,
            stepLengthMeters = 0.75f,
            displacementThresholdMeters = 18.0,
            headingDiffThresholdDeg = 25.0f,
            timeoutThresholdMs = 60_000L
        )

        val anchor = GeoPoint(50.4501, 30.5234, 150.0)
        pdrManager.updateGpsAnchor(anchor, timestamp = 1000L, headingDeg = 90.0f)

        val flushedPoints = mutableListOf<FlushedPdrPoint>()
        pdrManager.onStepFlushed = { flushedPoints.add(it) }

        // 23 steps * 0.75m = 17.25m (< 18.0m)
        pdrManager.updateCurrentHeading(90.0f) // Walking East
        for (i in 1..23) {
            pdrManager.registerStep(timestamp = 1000L + i * 500L)
        }
        assertEquals(0, flushedPoints.size)
        assertEquals(23, pdrManager.inMemoryAccumulatedSteps)

        // 24th step * 0.75m = 18.0m (>= 18.0m threshold!) -> triggers flush
        pdrManager.registerStep(timestamp = 1000L + 24 * 500L)
        assertEquals(1, flushedPoints.size)

        val flushed = flushedPoints.first()
        assertEquals(PdrFlushReason.DISPLACEMENT, flushed.reason)
        assertEquals(24, flushed.stepCount)
        assertEquals(18.0, flushed.displacementMeters, 0.01)
        assertEquals(90.0f, flushed.headingDeg, 0.1f)

        // Latitude should be almost unchanged, Longitude should have increased (East movement)
        assertEquals(anchor.latitude, flushed.point.latitude, 0.0001)
        assertTrue("Longitude must increase going East", flushed.point.longitude > anchor.longitude)

        // Memory buffer must be reset after flush
        assertEquals(0, pdrManager.inMemoryAccumulatedSteps)
        assertEquals(0.0, pdrManager.inMemoryAccumulatedDisplacement, 0.01)
        assertEquals(flushed.point, pdrManager.currentAnchor)
    }

    @Test
    fun `test Criterion 2 - heading change over 20-30 deg triggers flush to preserve turns`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdrManager = StepDetectorManager(
            context = context,
            stepLengthMeters = 0.75f,
            displacementThresholdMeters = 18.0,
            headingDiffThresholdDeg = 25.0f,
            timeoutThresholdMs = 60_000L
        )

        val anchor = GeoPoint(50.4501, 30.5234, 150.0)
        pdrManager.updateGpsAnchor(anchor, timestamp = 1000L)

        val flushedPoints = mutableListOf<FlushedPdrPoint>()
        pdrManager.onStepFlushed = { flushedPoints.add(it) }

        // Walk 6 steps North (heading 0°) -> 4.5 meters
        pdrManager.updateCurrentHeading(0.0f)
        for (i in 1..6) {
            pdrManager.registerStep(timestamp = 1000L + i * 500L)
        }
        assertEquals(0, flushedPoints.size)
        assertEquals(6, pdrManager.inMemoryAccumulatedSteps)

        // Turn right around corner to East (heading 90°: difference = 90° >= 25°)
        pdrManager.updateCurrentHeading(90.0f)
        pdrManager.registerStep(timestamp = 1000L + 7 * 500L)

        // Must flush immediately to record the corner point!
        assertEquals(1, flushedPoints.size)
        val cornerFlush = flushedPoints.first()
        assertEquals(PdrFlushReason.HEADING_CHANGE, cornerFlush.reason)
        assertEquals(7, cornerFlush.stepCount)
        assertEquals(90.0f, cornerFlush.headingDeg, 0.1f)

        // After corner point flushed, memory buffer is cleared and anchor moved to corner
        assertEquals(0, pdrManager.inMemoryAccumulatedSteps)
        assertEquals(cornerFlush.point, pdrManager.currentAnchor)
    }

    @Test
    fun `test Criterion 3 - 60 seconds elapsed triggers checkpoint flush`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdrManager = StepDetectorManager(
            context = context,
            stepLengthMeters = 0.75f,
            displacementThresholdMeters = 18.0,
            headingDiffThresholdDeg = 25.0f,
            timeoutThresholdMs = 60_000L
        )

        val anchor = GeoPoint(50.4501, 30.5234, 150.0)
        pdrManager.updateGpsAnchor(anchor, timestamp = 10_000L, headingDeg = 180.0f)

        val flushedPoints = mutableListOf<FlushedPdrPoint>()
        pdrManager.onStepFlushed = { flushedPoints.add(it) }

        // Walk 5 steps slowly at t = 12_000L (displacement = 3.75m, heading 180° South)
        pdrManager.updateCurrentHeading(180.0f)
        for (i in 1..5) {
            pdrManager.registerStep(timestamp = 10_000L + i * 400L)
        }
        assertEquals(0, flushedPoints.size)

        // Check periodic ticker at t = 40_000L (30 seconds elapsed < 60 seconds)
        val flushAt30s = pdrManager.checkFlushTimeout(40_000L)
        assertNull(flushAt30s)
        assertEquals(0, flushedPoints.size)

        // User stops walking; periodic ticker runs at t = 71_000L (61 seconds elapsed >= 60s)
        val flushAt61s = pdrManager.checkFlushTimeout(71_000L)
        assertNotNull(flushAt61s)
        assertEquals(1, flushedPoints.size)

        val timeoutFlush = flushedPoints.first()
        assertEquals(PdrFlushReason.TIMEOUT, timeoutFlush.reason)
        assertEquals(5, timeoutFlush.stepCount)
        assertEquals(3.75, timeoutFlush.displacementMeters, 0.01)
        assertEquals(180.0f, timeoutFlush.headingDeg, 0.1f)
        assertTrue("Latitude should decrease going South", timeoutFlush.point.latitude < anchor.latitude)
    }

    @Test
    fun `test Room persistence batches PDR steps without writing individual steps to DB`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = AppDatabase.getInstance(context)
        val trackDao = db.trackDao()

        val trackId = trackDao.insertTrack(
            TrackEntity(
                name = "Адаптивный PDR батчинг трек",
                startTime = System.currentTimeMillis(),
                isActive = true
            )
        )

        val pdrManager = StepDetectorManager(
            context = context,
            stepLengthMeters = 0.75f,
            displacementThresholdMeters = 18.0,
            headingDiffThresholdDeg = 25.0f,
            timeoutThresholdMs = 60_000L
        )

        val anchor = GeoPoint(50.4501, 30.5234, 150.0)
        pdrManager.updateGpsAnchor(anchor, timestamp = 1000L, headingDeg = 0.0f)

        // Record flush events directly into Room
        pdrManager.onStepFlushed = { flushed ->
            runBlocking {
                trackDao.insertPoint(
                    TrackPointEntity(
                        trackId = trackId,
                        latitude = flushed.point.latitude,
                        longitude = flushed.point.longitude,
                        altitudeMeters = flushed.point.altitude,
                        source = TrackPointEntity.SOURCE_DEAD_RECKONING,
                        headingDegrees = flushed.headingDeg,
                        speedMps = 1.2f,
                        accuracyMeters = 15.0f,
                        timestamp = flushed.timestamp
                    )
                )
            }
        }

        // Simulate 50 steps:
        // Steps 1..24: North (0°) -> triggers Criterion 1 at step 24 (18m)
        pdrManager.updateCurrentHeading(0.0f)
        for (i in 1..24) {
            pdrManager.registerStep(timestamp = 1000L + i * 500L)
        }

        // Steps 25..30: Turn East (90°) -> triggers Criterion 2 on turn
        pdrManager.updateCurrentHeading(90.0f)
        for (i in 25..30) {
            pdrManager.registerStep(timestamp = 1000L + i * 500L)
        }

        // Check points written in Room: exactly 2 points written for 30 steps!
        // NOT 30 separate inserts!
        val points = trackDao.getTrackPointsSync(trackId)
        assertEquals("Room must contain only batched flush points, not 30 individual step inserts", 2, points.size)
        assertTrue(points.all { it.source == TrackPointEntity.SOURCE_DEAD_RECKONING })

        // Force flush on track stop writes the remaining 5 steps
        val stoppedFlush = pdrManager.forceFlush(timestamp = 1000L + 31 * 500L, reason = PdrFlushReason.MANUAL_STOP)
        assertNotNull(stoppedFlush)

        val finalPoints = trackDao.getTrackPointsSync(trackId)
        // 2 + 1 = 3 points in Room for all 30 steps
        assertEquals(3, finalPoints.size)
    }
}
