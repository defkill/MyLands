package com.example.sensor

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.example.model.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GpsStatus(val label: String) {
    NO_PERMISSION("Требуется разрешение GPS"),
    SEARCHING("Поиск спутников GPS..."),
    ACTIVE("GPS активен"),
    DISABLED("Служба GPS отключена")
}

class LocationTracker(private val context: Context) : LocationListener {

    private companion object {
        const val TAG = "LocationTracker"
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    private val _gpsStatus = MutableStateFlow(GpsStatus.SEARCHING)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private var isListening = false

    /** True once location updates have been successfully registered. */
    fun isActive(): Boolean = isListening

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isListening) return

        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!isGpsEnabled) {
            _gpsStatus.value = GpsStatus.DISABLED
        } else {
            _gpsStatus.value = GpsStatus.SEARCHING
        }

        try {
            // Check last known location
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = lastGps ?: lastNetwork
            if (best != null) {
                updateFromLocation(best)
            }

            // Register GPS updates: 1 second interval, 1 meter minimum distance.
            //
            // The explicit Looper is essential: the overload without one uses the CALLING
            // thread's Looper and throws when there is none. TrackingService starts this from
            // a background coroutine, so without this the registration failed silently in the
            // catch below and the service never received a single fix (track stayed empty
            // while the UI happily reported "recording").
            val looper = Looper.getMainLooper()

            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    1.0f,
                    this,
                    looper
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    5.0f,
                    this,
                    looper
                )
            }
            isListening = true
        } catch (e: SecurityException) {
            _gpsStatus.value = GpsStatus.NO_PERMISSION
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            _gpsStatus.value = GpsStatus.SEARCHING
            // Never swallow this silently again: a failure here means no track is recorded.
            Log.e(TAG, "Failed to register location updates", e)
        }
    }

    fun stopListening() {
        if (!isListening) return
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        isListening = false
    }

    override fun onLocationChanged(location: Location) {
        updateFromLocation(location)
    }

    private fun updateFromLocation(loc: Location) {
        _gpsStatus.value = GpsStatus.ACTIVE
        _currentLocation.value = GeoPoint(
            latitude = loc.latitude,
            longitude = loc.longitude,
            altitude = if (loc.hasAltitude()) loc.altitude else null,
            accuracy = if (loc.hasAccuracy()) loc.accuracy else null,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing else null,
            timestamp = loc.time
        )
    }

    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _gpsStatus.value = GpsStatus.SEARCHING
        }
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _gpsStatus.value = GpsStatus.DISABLED
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
