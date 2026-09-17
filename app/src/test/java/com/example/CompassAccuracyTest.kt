package com.example
 
import android.hardware.SensorManager
import com.example.ui.components.isCompassAccuracyLow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
 
class CompassAccuracyTest {
 
    @Test
    fun `isCompassAccuracyLow returns true for UNRELIABLE and LOW accuracy`() {
        // SENSOR_STATUS_UNRELIABLE is 0 - the worst case where compass is not reliable
        assertTrue(
            "UNRELIABLE (0) must trigger accuracy warning",
            isCompassAccuracyLow(SensorManager.SENSOR_STATUS_UNRELIABLE)
        )
        // SENSOR_STATUS_ACCURACY_LOW is 1
        assertTrue(
            "ACCURACY_LOW (1) must trigger accuracy warning",
            isCompassAccuracyLow(SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        )
    }
 
    @Test
    fun `isCompassAccuracyLow returns false for MEDIUM and HIGH accuracy`() {
        // SENSOR_STATUS_ACCURACY_MEDIUM is 2
        assertFalse(
            "ACCURACY_MEDIUM (2) should not trigger accuracy warning",
            isCompassAccuracyLow(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
        )
        // SENSOR_STATUS_ACCURACY_HIGH is 3
        assertFalse(
            "ACCURACY_HIGH (3) should not trigger accuracy warning",
            isCompassAccuracyLow(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        )
    }
}
