package com.example.sensor

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
        const val GPS_MIN_INTERVAL_MS = 1000L
        const val GPS_MIN_DISTANCE_M = 1.0f
        const val NETWORK_MIN_INTERVAL_MS = 2000L
        const val NETWORK_MIN_DISTANCE_M = 5.0f
    }

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()

    /**
     * Position of the last real fix before the signal was lost, used as the dead-reckoning
     * anchor. Separate from [currentLocation] so consumers can tell "we are here" from
     * "this is where we last were".
     */
    private val _lastFixBeforeSignalLoss = MutableStateFlow<GeoPoint?>(null)
    val lastFixBeforeSignalLoss: StateFlow<GeoPoint?> = _lastFixBeforeSignalLoss.asStateFlow()

    private val _gpsStatus = MutableStateFlow(GpsStatus.SEARCHING)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    /**
     * Which providers we currently hold a registration for.
     *
     * A single "isListening" flag was wrong: when GPS was off at startup the flag still got
     * set (only the network provider, or nothing at all, had actually been registered), and
     * every later call — including the user pressing the locate button after switching GPS on
     * — returned immediately without subscribing. The only way out was restarting the app.
     * Tracking registrations per provider lets a repeat call top up whatever is missing.
     */
    private val registeredProviders = mutableSetOf<String>()

    /**
     * System-wide provider toggle broadcast.
     *
     * onProviderEnabled() only reaches listeners already registered for that provider, so when
     * GPS is off at startup we never hear about it being switched on. This receiver fires for
     * any provider change (including from the notification shade) and lets us subscribe to the
     * provider that just became available, without the user having to restart the app.
     */
    private val providersChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                Log.d(TAG, "Providers changed broadcast")
                syncProviders()
            }
        }
    }

    private var receiverRegistered = false

    /**
     * Drops registrations for providers that were turned off and adds any that appeared.
     */
    fun syncProviders() {
        val gpsEnabled = safeIsProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = safeIsProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled) registeredProviders.remove(LocationManager.GPS_PROVIDER)
        if (!networkEnabled) registeredProviders.remove(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !networkEnabled) {
            _gpsStatus.value = GpsStatus.DISABLED
            return
        }
        startListening()
    }

    /** True once updates from at least one provider have been registered. */
    fun isActive(): Boolean = registeredProviders.isNotEmpty()

    /** True when the real GPS provider (not just network) is registered. */
    fun isGpsRegistered(): Boolean = registeredProviders.contains(LocationManager.GPS_PROVIDER)

    /**
     * Registers for location updates. Safe to call repeatedly: providers that are already
     * registered are skipped, and any provider that became available since the last call
     * (for example the user enabling GPS from the notification shade) is added now.
     */
    @SuppressLint("MissingPermission")
    fun startListening() {
        // Register the provider-change receiver FIRST. Previously this sat after the early
        // return below, so launching the app with location switched off meant we never
        // subscribed to the broadcast — and turning GPS on from the shade did nothing until
        // the user pressed the locate button or restarted the app.
        registerProvidersReceiver()

        val gpsEnabled = safeIsProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = safeIsProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!gpsEnabled && !networkEnabled) {
            _gpsStatus.value = GpsStatus.DISABLED
            return
        }

        try {
            // Seed with the last known position so the map has something before the first fix.
            if (_currentLocation.value == null) {
                val last = (if (gpsEnabled) {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } else null) ?: (if (networkEnabled) {
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } else null)
                if (last != null) updateFromLocation(last)
            }

            // The explicit Looper is essential: the overload without one uses the CALLING
            // thread's Looper and throws when there is none. TrackingService starts this from
            // a background coroutine, so without this the registration failed silently and the
            // service never received a single fix.
            val looper = Looper.getMainLooper()

            if (gpsEnabled) {
                register(LocationManager.GPS_PROVIDER, GPS_MIN_INTERVAL_MS, GPS_MIN_DISTANCE_M, looper)
            }
            if (networkEnabled) {
                register(
                    LocationManager.NETWORK_PROVIDER,
                    NETWORK_MIN_INTERVAL_MS,
                    NETWORK_MIN_DISTANCE_M,
                    looper
                )
            }

            if (registeredProviders.isEmpty()) {
                _gpsStatus.value = GpsStatus.DISABLED
            } else if (_gpsStatus.value != GpsStatus.ACTIVE) {
                _gpsStatus.value = GpsStatus.SEARCHING
            }
        } catch (e: SecurityException) {
            _gpsStatus.value = GpsStatus.NO_PERMISSION
            Log.e(TAG, "Location permission missing", e)
        } catch (e: Exception) {
            _gpsStatus.value = GpsStatus.SEARCHING
            // Never swallow this silently: a failure here means no position and no track.
            Log.e(TAG, "Failed to register location updates", e)
        }
    }

    private fun registerProvidersReceiver() {
        if (receiverRegistered) return
        try {
            // targetSdk 34+ requires the exported flag explicitly; ContextCompat picks the
            // right call for the running OS version. NOT_EXPORTED: this is a system broadcast
            // we only listen to, nothing else should be able to send it to us.
            ContextCompat.registerReceiver(
                context,
                providersChangedReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register providers-changed receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun register(provider: String, intervalMs: Long, minDistanceM: Float, looper: Looper) {
        if (registeredProviders.contains(provider)) return
        locationManager.requestLocationUpdates(provider, intervalMs, minDistanceM, this, looper)
        registeredProviders.add(provider)
        Log.d(TAG, "Registered location updates for $provider")
    }

    private fun safeIsProviderEnabled(provider: String): Boolean = try {
        locationManager.isProviderEnabled(provider)
    } catch (_: Exception) {
        false
    }

    fun stopListening() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(providersChangedReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }

        if (registeredProviders.isEmpty()) return
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        registeredProviders.clear()
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

    /**
     * Fired when the user switches a provider on (for example GPS from the notification
     * shade). Previously this only changed the status text, so the app kept saying
     * "searching" forever without ever subscribing to the provider that just appeared.
     */
    override fun onProviderEnabled(provider: String) {
        Log.d(TAG, "Provider enabled: $provider")
        if (_gpsStatus.value == GpsStatus.DISABLED) {
            _gpsStatus.value = GpsStatus.SEARCHING
        }
        startListening()
    }

    override fun onProviderDisabled(provider: String) {
        Log.d(TAG, "Provider disabled: $provider")
        registeredProviders.remove(provider)
        if (registeredProviders.isEmpty()) {
            _gpsStatus.value = GpsStatus.DISABLED
            // The last fix is deliberately KEPT: dead reckoning needs it as the anchor to
            // count steps from. Clearing it here left the compass and the waypoint bearings
            // blank exactly when the user is off-grid and needs them most. Consumers tell
            // live fixes from stale ones via gpsStatus, not by this value being null.
            _lastFixBeforeSignalLoss.value = _currentLocation.value
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
