package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.model.GeoPoint
import com.example.sensor.GpsStatus
import com.example.data.track.TrackFilter
import com.example.sensor.LocationTracker
import com.example.sensor.OrientationManager
import com.example.sensor.StepDetectorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground Service dedicated to continuous, uninterrupted track recording
 * and dead-reckoning (PDR) that survives screen-off, device lock, and Activity destruction.
 *
 * Runs with FOREGROUND_SERVICE_TYPE_LOCATION. To preserve battery life on multi-hour outdoor
 * hikes, PARTIAL_WAKE_LOCK is NOT held continuously; instead, brief WakeLocks (acquire/release)
 * are taken strictly around the processing of incoming GPS fixes, PDR step flushes, and Room DB writes.
 */
class TrackingService : Service() {

    companion object {
        private const val TAG = "TrackingService"
        const val ACTION_START_TRACKING = "com.example.service.action.START_TRACKING"
        const val ACTION_STOP_TRACKING = "com.example.service.action.STOP_TRACKING"
        const val ACTION_CHECKPOINT_ALARM = "com.example.service.action.CHECKPOINT_ALARM"
        const val EXTRA_TRACK_ID = "extra_track_id"
        const val EXTRA_TRACK_NAME = "extra_track_name"

        const val CHECKPOINT_INTERVAL_MS = 60_000L
        private const val ALARM_REQUEST_CODE = 4040

        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "orientir_track_recording_channel"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        private val _activeTrackId = MutableStateFlow<Long?>(null)
        val activeTrackId: StateFlow<Long?> = _activeTrackId.asStateFlow()

        private val _recordedPointsCount = MutableStateFlow(0)
        val recordedPointsCount: StateFlow<Int> = _recordedPointsCount.asStateFlow()

        private val _lastRecordedPoint = MutableStateFlow<TrackPointEntity?>(null)
        val lastRecordedPoint: StateFlow<TrackPointEntity?> = _lastRecordedPoint.asStateFlow()

        fun startTracking(context: Context, trackId: Long, trackName: String) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_TRACK_ID, trackId)
                putExtra(EXTRA_TRACK_NAME, trackName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopTracking(context: Context) {
            val intent = Intent(context, TrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val database by lazy { AppDatabase.getInstance(applicationContext) }
    private val trackDao by lazy { database.trackDao() }

    private lateinit var locationTracker: LocationTracker
    private lateinit var orientationManager: OrientationManager
    private lateinit var stepDetectorManager: StepDetectorManager

    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockLock = Any()
    private var currentTrackId: Long = 0L
    private var currentTrackName: String = "Трек"
    private var lastGpsTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        locationTracker = LocationTracker(applicationContext)
        orientationManager = OrientationManager(applicationContext)
        stepDetectorManager = StepDetectorManager(applicationContext)

        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_TRACKING

        when (action) {
            ACTION_STOP_TRACKING -> {
                handleStopTracking()
            }
            ACTION_START_TRACKING -> {
                val trackId = intent?.getLongExtra(EXTRA_TRACK_ID, 0L) ?: 0L
                val trackName = intent?.getStringExtra(EXTRA_TRACK_NAME) ?: "Трек"
                handleStartTracking(trackId, trackName)
            }
            ACTION_CHECKPOINT_ALARM -> {
                handleCheckpointAlarm()
            }
        }

        return START_STICKY
    }

    private fun handleStartTracking(trackId: Long, trackName: String) {
        currentTrackName = trackName

        // NOTE: We do NOT hold WakeLock continuously across the entire session to prevent
        // battery drain on long outdoor hikes. Instead, brief WakeLocks (acquire/release)
        // are acquired strictly around each incoming GPS / PDR event and Room DB write.

        // Put service in foreground immediately
        val notification = buildNotification(trackName, _recordedPointsCount.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isServiceRunning.value = true

        serviceScope.launch {
            // Resolve track ID in Room
            if (trackId > 0L) {
                currentTrackId = trackId
            } else {
                val active = trackDao.getActiveTrackSync()
                currentTrackId = active?.id ?: trackDao.insertTrack(
                    TrackEntity(name = trackName, isActive = true)
                )
            }
            _activeTrackId.value = currentTrackId

            // Count existing points if any
            val existing = trackDao.getTrackPointsSync(currentTrackId)
            _recordedPointsCount.value = existing.size
            if (existing.isNotEmpty()) {
                _lastRecordedPoint.value = existing.last()
                val lastPt = existing.last()
                stepDetectorManager.updateGpsAnchor(
                    GeoPoint(
                        latitude = lastPt.latitude,
                        longitude = lastPt.longitude,
                        altitude = lastPt.altitudeMeters
                    )
                )
            }

            // Start hardware subscriptions
            startSensorsAndGps()
        }
    }

    private fun startSensorsAndGps() {
        locationTracker.startListening()
        orientationManager.start()
        stepDetectorManager.start()

        // Brief per-event wake locks only work if something wakes the CPU in the first place.
        // With a wake-up step detector the sensor itself does that. Without one, step events
        // stop entirely during deep sleep and the dead-reckoning track dies a minute after the
        // screen goes off — so on those devices we hold the lock for the whole session and
        // accept the battery cost, because a track that stops is worthless in the field.
        if (!stepDetectorManager.hasWakeUpStepSensor) {
            acquireSustainedWakeLock()
        }

        // 1. Subscribe to orientation changes -> feeds current azimuth to PDR
        serviceScope.launch {
            orientationManager.orientationData.collect { orientation ->
                stepDetectorManager.updateCurrentHeading(orientation.trueHeadingDeg)
            }
        }

        // 2. Subscribe to GPS fixes -> persists directly to Room & updates anchors
        serviceScope.launch {
            locationTracker.currentLocation.collect { loc ->
                if (loc != null && currentTrackId > 0L) {
                    acquireBriefWakeLock(3000L)
                    try {
                        val now = loc.timestamp ?: System.currentTimeMillis()
                        val timeSinceGps = now - lastGpsTimestamp
                        val isGpsActive = locationTracker.gpsStatus.value == GpsStatus.ACTIVE

                        // If GPS was lost or stale (> 3.5s) and we were accumulating PDR steps,
                        // flush them to Room first so the dead-reckoning path up to GPS reacquisition is saved
                        if (!isGpsActive || timeSinceGps > 3500L) {
                            val pending = stepDetectorManager.flush(now, com.example.sensor.PdrFlushReason.GPS_REACQUIRED)
                            if (pending != null) {
                                withContext(Dispatchers.IO) {
                                    val pdrPt = TrackPointEntity(
                                        trackId = currentTrackId,
                                        latitude = pending.point.latitude,
                                        longitude = pending.point.longitude,
                                        altitudeMeters = pending.point.altitude,
                                        source = TrackPointEntity.SOURCE_DEAD_RECKONING,
                                        headingDegrees = pending.headingDeg,
                                        speedMps = 1.2f,
                                        accuracyMeters = 15.0f,
                                        timestamp = now - 500L
                                    )
                                    trackDao.insertPoint(pdrPt)
                                }
                                _recordedPointsCount.value += 1
                            }
                        }

                        orientationManager.updateGeomagneticDeclination(
                            loc.latitude,
                            loc.longitude,
                            loc.altitude ?: 0.0
                        )
                        val heading = loc.bearingDeg ?: orientationManager.orientationData.value.trueHeadingDeg
                        stepDetectorManager.updateGpsAnchor(loc, now, heading)
                        lastGpsTimestamp = now
                        val pt = TrackPointEntity(
                            trackId = currentTrackId,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            altitudeMeters = loc.altitude,
                            source = TrackPointEntity.SOURCE_GPS,
                            headingDegrees = heading,
                            speedMps = loc.speedMps,
                            accuracyMeters = loc.accuracy,
                            timestamp = now
                        )

                        withContext(Dispatchers.IO) {
                            trackDao.insertPoint(pt)
                        }

                        val count = _recordedPointsCount.value + 1
                        _recordedPointsCount.value = count
                        _lastRecordedPoint.value = pt

                        if (count % 5 == 0) {
                            updateNotification(currentTrackName, count)
                        }
                    } finally {
                        releaseBriefWakeLock()
                    }
                }
            }
        }

        // 3. Sensor-based Dead Reckoning (PDR) adaptive flush callback
        // Steps accumulate purely in-memory (lightweight dx, dy vector addition without GeodesyEngine/DB).
        // Flushes to Room only when adaptive criteria are satisfied:
        // 1. Accumulated displacement > 15-20 m (default 18.0 m)
        // 2. Course changed > 20-30° (default 25.0°) relative to heading at last recording
        // 3. 60 seconds elapsed without criteria 1 or 2 triggering
        stepDetectorManager.onStepFlushed = { flushed ->
            if (currentTrackId > 0L) {
                val now = flushed.timestamp
                val timeSinceGps = now - lastGpsTimestamp
                val isGpsActive = locationTracker.gpsStatus.value == GpsStatus.ACTIVE

                // Only record PDR points if GPS is unavailable or stale (> 3.5s)
                if (!isGpsActive || timeSinceGps > 3500L) {
                    serviceScope.launch(Dispatchers.IO) {
                        acquireBriefWakeLock(3000L)
                        try {
                            val pt = TrackPointEntity(
                                trackId = currentTrackId,
                                latitude = flushed.point.latitude,
                                longitude = flushed.point.longitude,
                                altitudeMeters = flushed.point.altitude,
                                source = TrackPointEntity.SOURCE_DEAD_RECKONING,
                                headingDegrees = flushed.headingDeg,
                                speedMps = 1.2f, // standard pedestrian pace
                                accuracyMeters = 15.0f,
                                timestamp = now
                            )
                            trackDao.insertPoint(pt)

                            val count = _recordedPointsCount.value + 1
                            _recordedPointsCount.value = count
                            _lastRecordedPoint.value = pt

                            if (count % 5 == 0) {
                                updateNotification(currentTrackName, count)
                            }
                        } finally {
                            releaseBriefWakeLock()
                        }
                    }
                }
            }
        }

        // 4. Hardware RTC AlarmManager checkpoint for Criterion 3 (60s timeout).
        // Uses AlarmManager.setExactAndAllowWhileIdle so the device is guaranteed to wake
        // up even during deep sleep (Doze) without GPS fixes or step sensor interrupts.
        scheduleNextCheckpointAlarm()
    }

    private fun handleStopTracking() {
        cancelCheckpointAlarm()
        serviceScope.launch(Dispatchers.IO) {
            acquireBriefWakeLock(5000L)
            try {
                // Force flush any pending in-memory accumulated steps before finalizing track
                val pending = stepDetectorManager.flush(System.currentTimeMillis(), com.example.sensor.PdrFlushReason.MANUAL_STOP)
                if (pending != null && currentTrackId > 0L) {
                    val pt = TrackPointEntity(
                        trackId = currentTrackId,
                        latitude = pending.point.latitude,
                        longitude = pending.point.longitude,
                        altitudeMeters = pending.point.altitude,
                        source = TrackPointEntity.SOURCE_DEAD_RECKONING,
                        headingDegrees = pending.headingDeg,
                        speedMps = 1.2f,
                        accuracyMeters = 15.0f,
                        timestamp = pending.timestamp
                    )
                    trackDao.insertPoint(pt)
                    _recordedPointsCount.value += 1
                }

                val track = if (currentTrackId > 0L) {
                    trackDao.getTrackById(currentTrackId)
                } else {
                    trackDao.getActiveTrackSync()
                }

                if (track != null) {
                    val points = trackDao.getTrackPointsSync(track.id)

                    // Report the cleaned length: one GPS jump must not add kilometres that
                    // were never walked. Raw points remain stored as recorded.
                    val totalDist = TrackFilter.filter(points).distanceMeters
                    val hasDr = points.any { it.source == TrackPointEntity.SOURCE_DEAD_RECKONING }

                    trackDao.updateTrack(
                        track.copy(
                            isActive = false,
                            endTime = System.currentTimeMillis(),
                            totalDistanceMeters = totalDist,
                            hasDeadReckoningSegments = hasDr
                        )
                    )
                }
            } catch (_: Exception) {
            } finally {
                releaseBriefWakeLock()
                withContext(Dispatchers.Main) {
                    cleanup()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * Hardware AlarmManager trigger for Criterion 3: 60s elapsed checkpoint.
     * Guarantees that even if the device is in deep sleep and no steps/GPS fixes arrive,
     * the system wakes up, checks if accumulated steps should be flushed, and immediately sleeps again.
     */
    private fun handleCheckpointAlarm() {
        if (!_isServiceRunning.value || currentTrackId <= 0L) {
            cancelCheckpointAlarm()
            return
        }

        // Brief WakeLock specifically for the duration of checkpoint verification and flush
        acquireBriefWakeLock(3000L)
        try {
            val now = System.currentTimeMillis()
            val timeSinceGps = now - lastGpsTimestamp
            val isGpsActive = locationTracker.gpsStatus.value == GpsStatus.ACTIVE

            if (!isGpsActive || timeSinceGps > 3500L) {
                // If there are accumulated steps waiting >= 60s without displacement/turn triggers,
                // this flushes them into an exact geodetic point and triggers onStepFlushed -> Room insert
                stepDetectorManager.checkFlushTimeout(now)
            }

            // Refresh the notification on every checkpoint, not only every 5th point: while
            // dead reckoning the point count barely moves, so the diagnostics would otherwise
            // stay frozen exactly during the phase we need to observe.
            updateNotification(currentTrackName, _recordedPointsCount.value)
        } finally {
            scheduleNextCheckpointAlarm()
            releaseBriefWakeLock()
        }
    }

    private fun scheduleNextCheckpointAlarm() {
        if (!_isServiceRunning.value || currentTrackId <= 0L) return

        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_CHECKPOINT_ALARM
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, ALARM_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(this, ALARM_REQUEST_CODE, intent, flags)
            }

            val triggerAtMillis = SystemClock.elapsedRealtime() + CHECKPOINT_INTERVAL_MS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (_: Exception) {}
    }

    private fun cancelCheckpointAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_CHECKPOINT_ALARM
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, ALARM_REQUEST_CODE, intent, flags)
            } else {
                PendingIntent.getService(this, ALARM_REQUEST_CODE, intent, flags)
            }
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        } catch (_: Exception) {}
    }

    /**
     * Acquires a brief PARTIAL_WAKE_LOCK to prevent the CPU from entering deep sleep
     * during the execution of critical work (processing a GPS/sensor event and Room DB write).
     * The lock is released immediately once processing finishes, or automatically released by the OS after [timeoutMs].
     */
    private var sustainedWakeLock: PowerManager.WakeLock? = null

    /**
     * Held for the full recording session on devices without a wake-up step sensor.
     */
    private fun acquireSustainedWakeLock() {
        synchronized(wakeLockLock) {
            try {
                if (sustainedWakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    sustainedWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "OrientirNav:TrackingSustainedWakeLock"
                    )
                }
                if (sustainedWakeLock?.isHeld == false) {
                    sustainedWakeLock?.acquire()
                    Log.d(TAG, "Sustained wake lock acquired (no wake-up step sensor)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire sustained wake lock", e)
            }
        }
    }

    private fun releaseSustainedWakeLock() {
        synchronized(wakeLockLock) {
            try {
                if (sustainedWakeLock?.isHeld == true) {
                    sustainedWakeLock?.release()
                }
            } catch (_: Exception) {}
            sustainedWakeLock = null
        }
    }

    private fun acquireBriefWakeLock(timeoutMs: Long = 3000L) {
        synchronized(wakeLockLock) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OrientirNav:TrackingBriefWakeLock").apply {
                        setReferenceCounted(true)
                    }
                }
                wakeLock?.acquire(timeoutMs)
            } catch (_: Exception) {}
        }
    }

    private fun releaseBriefWakeLock() {
        synchronized(wakeLockLock) {
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}
        }
    }

    private fun cleanup() {
        cancelCheckpointAlarm()
        releaseBriefWakeLock()
        releaseSustainedWakeLock()
        try {
            locationTracker.stopListening()
            orientationManager.stop()
            stepDetectorManager.stop()
        } catch (_: Exception) {}

        _isServiceRunning.value = false
        _activeTrackId.value = null
        currentTrackId = 0L
    }

    override fun onDestroy() {
        cleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Запись трека",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянное уведомление активной фоновой записи трека и PDR"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(trackName: String, pointsCount: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrackingService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Diagnostics live in the notification because the failure we are chasing happens with
        // the screen off and the phone in a pocket: by the time the user can look at the app,
        // whatever went wrong is over. Step count answers the key question directly — are step
        // events still arriving while the CPU sleeps?
        val steps = stepDetectorManager.pdrState.value.totalSteps
        val pending = stepDetectorManager.inMemoryAccumulatedSteps
        val sensorKind = if (stepDetectorManager.hasWakeUpStepSensor) "wake" else "nowake"
        val gpsLive = locationTracker.gpsStatus.value == GpsStatus.ACTIVE

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Запись трека активна")
            .setContentText("Точек: $pointsCount • $trackName")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Точек: $pointsCount • $trackName\n" +
                        "Шагов: $steps (в буфере $pending) • датчик: $sensorKind\n" +
                        "GPS: ${if (gpsLive) "есть" else "нет — счисление"}"
                )
            )
            .setSubText("Фоновая запись")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Остановить",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(trackName: String, pointsCount: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification(trackName, pointsCount))
    }
}
