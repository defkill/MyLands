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

    // Standard continuous rotation vector for responsive UI
    private val rotationVectorSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR, true)

    // Geomagnetic rotation vector (accel + mag only, no gyroscope needed)
    private val geomagVectorSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    @Volatile
    private var isListening = false

    private val activeRequesters = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @Volatile
    private var isFirstReading = true

    @Volatile
    private var lastRotVectorTime = 0L

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

    fun start(tag: String = "default") {
        synchronized(this) {
            activeRequesters.add(tag)
            if (!isListening) {
                isListening = true
                isFirstReading = true
                lastRotVectorTime = 0L
                val rot = rotationVectorSensor ?: geomagVectorSensor
                if (rot != null) {
                    sensorManager.registerListener(this, rot, SensorManager.SENSOR_DELAY_UI)
                }
                // Always register accelerometer and magnetometer as fallback
                accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            }
        }
    }

    fun stop(tag: String = "default") {
        synchronized(this) {
            activeRequesters.remove(tag)
            if (activeRequesters.isEmpty() && isListening) {
                isListening = false
                sensorManager.unregisterListener(this)
            }
        }
    }

    fun forceStop() {
        synchronized(this) {
            activeRequesters.clear()
            if (isListening) {
                isListening = false
                sensorManager.unregisterListener(this)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                // Truncate to 4 elements if needed for older hardware
                val vec = if (event.values.size > 4) {
                    System.arraycopy(event.values, 0, truncatedVector, 0, 4)
                    truncatedVector
                } else {
                    event.values
                }
                SensorManager.getRotationMatrixFromVector(rotationMatrix, vec)
                processRotationMatrix(event.accuracy)
                lastRotVectorTime = System.currentTimeMillis()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAcc, 0, 3)
                hasAcc = true
                if (hasMag && (System.currentTimeMillis() - lastRotVectorTime > 1000L)) {
                    if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAcc, lastMag)) {
                        processRotationMatrix(event.accuracy)
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, lastMag, 0, 3)
                hasMag = true
                if (hasAcc && (System.currentTimeMillis() - lastRotVectorTime > 1000L)) {
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
        if (isFirstReading) {
            smoothCos = curCos
            smoothSin = curSin
            isFirstReading = false
        } else {
            smoothCos = smoothCos * (1.0 - smoothingFactor) + curCos * smoothingFactor
            smoothSin = smoothSin * (1.0 - smoothingFactor) + curSin * smoothingFactor
        }

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
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR ||
            sensor?.type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR ||
            sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            lastAccuracy = accuracy
            _orientationData.value = _orientationData.value.copy(accuracy = accuracy)
        }
    }
}
