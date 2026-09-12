package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.geodesy.GeodesyEngine
import com.example.model.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class PdrState(
    val totalSteps: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val lastEstimatedPosition: GeoPoint? = null,
    val isDeadReckoningActive: Boolean = false
)

enum class PdrFlushReason {
    DISPLACEMENT,      // > 15-20 meters displacement
    HEADING_CHANGE,    // > 20-30 degrees course change
    TIMEOUT,           // 60 seconds elapsed without criteria 1 or 2
    MANUAL_STOP,       // Track recording manually stopped
    GPS_REACQUIRED     // GPS fix reacquired
}

data class FlushedPdrPoint(
    val point: GeoPoint,
    val headingDeg: Float,
    val timestamp: Long,
    val stepCount: Int,
    val displacementMeters: Double,
    val reason: PdrFlushReason
)

/**
 * Pedestrian Dead Reckoning (PDR) step detector and in-memory vector accumulator.
 *
 * Lightweight per-step accumulation:
 * On each step, only lightweight in-memory vector addition (dx, dy) is performed without
 * invoking GeodesyEngine or Room DB operations.
 *
 * Adaptive flush criteria to write points to Room:
 * 1. Accumulated displacement > 15-20 m (default 18.0 m)
 * 2. Course changed > 20-30° (default 25.0°) relative to heading at last recording
 * 3. 60 seconds elapsed without criteria 1 or 2 triggering (checkpoint for straight walk or pause)
 */
class StepDetectorManager(
    private val context: Context,
    var stepLengthMeters: Float = 0.75f,
    var displacementThresholdMeters: Double = 18.0,
    var headingDiffThresholdDeg: Float = 25.0f,
    var timeoutThresholdMs: Long = 60_000L
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _pdrState = MutableStateFlow(PdrState())
    val pdrState: StateFlow<PdrState> = _pdrState.asStateFlow()

    // Anchor point for dead reckoning (last GPS fix or last flushed PDR point)
    private var anchorPosition: GeoPoint? = null

    // In-memory vector displacement accumulation relative to anchorPosition
    private var accumulatedDx: Double = 0.0
    private var accumulatedDy: Double = 0.0
    private var accumulatedSteps: Int = 0
    private var lastRecordedHeadingDeg: Float = 0f
    private var lastFlushTimestamp: Long = 0L

    private var currentHeadingDeg: Float = 0f

    var onStepDetected: ((point: GeoPoint?, headingDeg: Float) -> Unit)? = null
    var onStepFlushed: ((FlushedPdrPoint) -> Unit)? = null

    // Fallback step detector using accelerometer peak detection
    private var lastAccMagnitude = 9.8f
    private var isPeak = false
    private var lastStepTime = 0L

    val inMemoryAccumulatedSteps: Int get() = accumulatedSteps
    val inMemoryAccumulatedDisplacement: Double get() = sqrt(accumulatedDx * accumulatedDx + accumulatedDy * accumulatedDy)
    val currentAnchor: GeoPoint? get() = anchorPosition

    fun start(initialPosition: GeoPoint? = null) {
        anchorPosition = initialPosition
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_FASTEST)
        } else {
            // Fallback to accelerometer
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun updateCurrentHeading(headingDeg: Float) {
        currentHeadingDeg = headingDeg
    }

    fun updateGpsAnchor(
        gpsPoint: GeoPoint,
        timestamp: Long = System.currentTimeMillis(),
        headingDeg: Float? = null
    ) {
        anchorPosition = gpsPoint
        if (headingDeg != null) {
            currentHeadingDeg = headingDeg
        }
        accumulatedDx = 0.0
        accumulatedDy = 0.0
        accumulatedSteps = 0
        lastRecordedHeadingDeg = currentHeadingDeg
        lastFlushTimestamp = timestamp

        _pdrState.value = _pdrState.value.copy(
            lastEstimatedPosition = gpsPoint,
            isDeadReckoningActive = false
        )
    }

    fun resetSteps() {
        accumulatedDx = 0.0
        accumulatedDy = 0.0
        accumulatedSteps = 0
        lastFlushTimestamp = System.currentTimeMillis()
        _pdrState.value = PdrState(lastEstimatedPosition = anchorPosition)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
            registerStep()
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // Accelerometer peak detection fallback
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = sqrt(x * x + y * y + z * z)

            val now = System.currentTimeMillis()
            if (mag > 12.0f && !isPeak && (now - lastStepTime > 300)) {
                isPeak = true
                lastStepTime = now
                registerStep(now)
            } else if (mag < 10.0f) {
                isPeak = false
            }
            lastAccMagnitude = mag
        }
    }

    /**
     * Registers a single step.
     * Performs lightweight vector displacement accumulation in memory:
     * dx = L * sin(azimuth), dy = L * cos(azimuth).
     * No GeodesyEngine calculation or DB operation on individual steps.
     */
    fun registerStep(timestamp: Long = System.currentTimeMillis()) {
        val rad = Math.toRadians(currentHeadingDeg.toDouble())
        val dx = stepLengthMeters * sin(rad)
        val dy = stepLengthMeters * cos(rad)

        accumulatedDx += dx
        accumulatedDy += dy
        accumulatedSteps++

        val prev = _pdrState.value
        val newSteps = prev.totalSteps + 1
        val newDistance = prev.totalDistanceMeters + stepLengthMeters

        // Fast flat-earth tangent estimate for real-time UI without GeodesyEngine overhead
        val estimatedPoint = estimateCurrentPosition()

        _pdrState.value = PdrState(
            totalSteps = newSteps,
            totalDistanceMeters = newDistance,
            lastEstimatedPosition = estimatedPoint,
            isDeadReckoningActive = true
        )

        onStepDetected?.invoke(estimatedPoint, currentHeadingDeg)

        // Check adaptive criteria to flush accumulated steps
        val displacement = sqrt(accumulatedDx * accumulatedDx + accumulatedDy * accumulatedDy)
        val headingDiff = angleDifferenceDeg(currentHeadingDeg, lastRecordedHeadingDeg)
        val timeSinceFlush = if (lastFlushTimestamp > 0L) timestamp - lastFlushTimestamp else 0L

        val reason = when {
            // Criterion 1: Accumulated displacement > 15-20m
            displacement >= displacementThresholdMeters -> PdrFlushReason.DISPLACEMENT
            // Criterion 2: Course changed > 20-30° relative to last recorded heading
            accumulatedSteps > 0 && headingDiff >= headingDiffThresholdDeg -> PdrFlushReason.HEADING_CHANGE
            // Criterion 3: 60s elapsed without criteria 1 or 2
            accumulatedSteps > 0 && lastFlushTimestamp > 0L && timeSinceFlush >= timeoutThresholdMs -> PdrFlushReason.TIMEOUT
            else -> null
        }

        if (reason != null) {
            flush(timestamp, reason)
        }
    }

    /**
     * Flushes accumulated displacement in memory into an exact geodetic point (using GeodesyEngine),
     * notifies listeners (TrackingService -> Room), and resets in-memory accumulation buffers.
     */
    fun flush(
        timestamp: Long = System.currentTimeMillis(),
        reason: PdrFlushReason = PdrFlushReason.MANUAL_STOP
    ): FlushedPdrPoint? {
        if (accumulatedSteps == 0 && reason != PdrFlushReason.MANUAL_STOP) {
            return null
        }
        val currentAnchorPos = anchorPosition ?: return null

        val displacement = sqrt(accumulatedDx * accumulatedDx + accumulatedDy * accumulatedDy)
        val flushedPoint = if (displacement > 0.0) {
            val azimuthDeg = (Math.toDegrees(atan2(accumulatedDx, accumulatedDy)) + 360.0) % 360.0
            // GeodesyEngine is invoked ONLY on flush, not per step
            GeodesyEngine.destinationPoint(currentAnchorPos, displacement, azimuthDeg)
        } else {
            currentAnchorPos
        }

        val headingAtFlush = currentHeadingDeg
        val stepsCount = accumulatedSteps

        // Update anchor to the new flushed position and clear accumulation buffers
        anchorPosition = flushedPoint
        accumulatedDx = 0.0
        accumulatedDy = 0.0
        accumulatedSteps = 0
        lastRecordedHeadingDeg = headingAtFlush
        lastFlushTimestamp = timestamp

        val result = FlushedPdrPoint(
            point = flushedPoint,
            headingDeg = headingAtFlush,
            timestamp = timestamp,
            stepCount = stepsCount,
            displacementMeters = displacement,
            reason = reason
        )

        onStepFlushed?.invoke(result)
        return result
    }

    /**
     * Periodic check to satisfy Criterion 3 (60s elapsed checkpoint)
     * even if walking stopped or steps are infrequent.
     */
    fun checkFlushTimeout(now: Long = System.currentTimeMillis()): FlushedPdrPoint? {
        if (accumulatedSteps > 0 && lastFlushTimestamp > 0L && (now - lastFlushTimestamp) >= timeoutThresholdMs) {
            return flush(now, PdrFlushReason.TIMEOUT)
        }
        return null
    }

    /**
     * Forces immediate flush of any pending accumulated steps (e.g. on track stop or GPS reacquisition).
     */
    fun forceFlush(timestamp: Long = System.currentTimeMillis(), reason: PdrFlushReason = PdrFlushReason.MANUAL_STOP): FlushedPdrPoint? {
        return flush(timestamp, reason)
    }

    /**
     * Fast flat-earth tangent projection for instantaneous UI rendering, avoiding geodesic sphere calculations.
     */
    private fun estimateCurrentPosition(): GeoPoint? {
        val anchor = anchorPosition ?: return null
        if (accumulatedDx == 0.0 && accumulatedDy == 0.0) return anchor
        val latRad = Math.toRadians(anchor.latitude)
        val metersPerLat = 111132.954
        val metersPerLon = 111132.954 * cos(latRad)
        return GeoPoint(
            latitude = anchor.latitude + (accumulatedDy / metersPerLat),
            longitude = anchor.longitude + (accumulatedDx / metersPerLon),
            altitude = anchor.altitude
        )
    }

    /**
     * Simulates a step detection, useful for unit tests and testing PDR screen-off behavior.
     */
    fun simulateStep(headingDeg: Float? = null, timestamp: Long = System.currentTimeMillis()) {
        if (headingDeg != null) {
            currentHeadingDeg = headingDeg
        }
        registerStep(timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /**
         * Calculates angular difference [0..180] degrees between two azimuths.
         */
        fun angleDifferenceDeg(a: Float, b: Float): Float {
            val diff = (a - b + 180f) % 360f
            val normalized = if (diff < 0f) diff + 360f else diff
            return kotlin.math.abs(normalized - 180f)
        }
    }
}
