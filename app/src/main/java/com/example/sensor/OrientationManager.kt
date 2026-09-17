package com.example.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class OrientationData(
    val magneticHeadingDeg: Float = 0f,
    val trueHeadingDeg: Float = 0f,
    val magneticDeclinationDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
)

class OrientationManager(private val context: Context) : SensorEventListener {

    companion object {
        @Volatile
        private var instance: OrientationManager? = null

        fun getInstance(context: Context): OrientationManager =
            instance ?: synchronized(this) {
                instance ?: OrientationManager(context.applicationContext).also { instance = it }
            }
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    /**
     * Prefer the wake-up rotation vector so heading keeps updating with the screen off.
     *
     * With the non-wake-up sensor the last heading before the CPU suspended is reused for every
     * subsequent step, which projects the whole dead-reckoning leg as one straight line in a
     * single direction no matter where the user actually walked.
     */
    private val rotationVectorSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    @Volatile
    private var isListening = false

    /** Timestamp of the last orientation update, to detect a frozen heading. */
    @Volatile
    var lastUpdateTimestamp: Long = 0L
        private set

    /** Milliseconds since heading last changed; large values mean the sensor stopped reporting. */
    fun headingAgeMillis(): Long =
        if (lastUpdateTimestamp == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - lastUpdateTimestamp

    fun setHeadingForTest(trueHeading: Float, timestamp: Long = System.currentTimeMillis()) {
        lastUpdateTimestamp = timestamp
        _orientationData.value = OrientationData(
            magneticHeadingDeg = trueHeading,
            trueHeadingDeg = trueHeading
        )
    }

    private val _orientationData = MutableStateFlow(OrientationData())
    val orientationData: StateFlow<OrientationData> = _orientationData.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val truncatedVector = FloatArray(4)

    private var lastAcc = FloatArray(3)
    private var lastMag = FloatArray(3)
    private var hasAcc = false
    private var hasMag = false

    // Vector smoothing across 0/360 boundary
    private var smoothCos = 1.0
    private var smoothSin = 0.0
    private val smoothingFactor = 0.15 // Filter responsiveness

    private var currentDeclinationDeg = 0f

    fun updateGeomagneticDeclination(lat: Double, lon: Double, altMeters: Double = 0.0) {
        try {
            val field = GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                altMeters.toFloat(),
                System.currentTimeMillis()
            )
            currentDeclinationDeg = field.declination
        } catch (_: Exception) {}
    }

    fun start() {
        synchronized(this) {
            if (isListening) return
            isListening = true
            if (rotationVectorSensor != null) {
                sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            } else {
                accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            }
        }
    }

    fun stop() {
        synchronized(this) {
            if (!isListening) return
            isListening = false
            sensorManager.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Truncate to 4 elements if needed for older hardware
                val vec = if (event.values.size > 4) {
                    System.arraycopy(event.values, 0, truncatedVector, 0, 4)
                    truncatedVector
                } else {
                    event.values
                }
                SensorManager.getRotationMatrixFromVector(rotationMatrix, vec)
                processRotationMatrix(event.accuracy)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAcc, 0, 3)
                hasAcc = true
                if (hasMag) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAcc, lastMag)) {
                        processRotationMatrix(event.accuracy)
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMag, 0, 3)
                hasMag = true
                if (hasAcc) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAcc, lastMag)) {
                        processRotationMatrix(event.accuracy)
                    }
                }
            }
        }
    }

    @Volatile
    private var lastAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    private fun processRotationMatrix(accuracy: Int) {
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val rawAzimuthRad = orientationAngles[0].toDouble()
        val pitchRad = orientationAngles[1].toDouble()
        val rollRad = orientationAngles[2].toDouble()

        // Continuous angular smoothing via sine/cosine
        val curCos = cos(rawAzimuthRad)
        val curSin = sin(rawAzimuthRad)
        smoothCos = smoothCos * (1.0 - smoothingFactor) + curCos * smoothingFactor
        smoothSin = smoothSin * (1.0 - smoothingFactor) + curSin * smoothingFactor

        var smoothedHeadingDeg = Math.toDegrees(atan2(smoothSin, smoothCos)).toFloat()
        smoothedHeadingDeg = (smoothedHeadingDeg % 360f + 360f) % 360f

        val trueHeadingDeg = (smoothedHeadingDeg + currentDeclinationDeg + 360f) % 360f

        val effectiveAccuracy = if (accuracy != 0) accuracy else lastAccuracy
        lastUpdateTimestamp = System.currentTimeMillis()
        _orientationData.value = OrientationData(
            magneticHeadingDeg = smoothedHeadingDeg,
            trueHeadingDeg = trueHeadingDeg,
            magneticDeclinationDeg = currentDeclinationDeg,
            pitchDeg = Math.toDegrees(pitchRad).toFloat(),
            rollDeg = Math.toDegrees(rollRad).toFloat(),
            accuracy = effectiveAccuracy
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            lastAccuracy = accuracy
            _orientationData.value = _orientationData.value.copy(accuracy = accuracy)
        }
    }
}
