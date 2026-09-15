package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.map.DownloadProgress
import com.example.map.RegionBounds
import com.example.map.RegionDownloader
import com.example.map.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads a map region in a foreground service.
 *
 * A plain coroutine in the ViewModel stalled as soon as the screen went off: Android throttles
 * background work and network access for an app the user is not looking at. Since a throttled
 * region download can legitimately run for hours, it has to be a foreground service with an
 * ongoing notification — that is the only arrangement the system keeps running.
 */
class MapDownloadService : Service() {

    companion object {
        private const val TAG = "MapDownloadService"
        private const val CHANNEL_ID = "map_download_channel"
        private const val NOTIFICATION_ID = 4823

        const val ACTION_START = "com.example.action.START_MAP_DOWNLOAD"
        const val ACTION_CANCEL = "com.example.action.CANCEL_MAP_DOWNLOAD"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _progress = MutableStateFlow<DownloadProgress?>(null)
        val progress: StateFlow<DownloadProgress?> = _progress.asStateFlow()

        private val _lastResult = MutableStateFlow<RegionDownloader.Result?>(null)
        val lastResult: StateFlow<RegionDownloader.Result?> = _lastResult.asStateFlow()

        /** Parameters are handed over in memory: they are bulky and only valid for this run. */
        @Volatile private var pendingBounds: RegionBounds? = null
        @Volatile private var pendingMinZoom: Int = 12
        @Volatile private var pendingMaxZoom: Int = 16
        @Volatile private var pendingSources: List<TileSource> = emptyList()
        @Volatile private var pendingBaseDir: File? = null

        fun start(
            context: Context,
            baseDir: File,
            bounds: RegionBounds,
            minZoom: Int,
            maxZoom: Int,
            sources: List<TileSource>
        ) {
            pendingBaseDir = baseDir
            pendingBounds = bounds
            pendingMinZoom = minZoom
            pendingMaxZoom = maxZoom
            pendingSources = sources
            _lastResult.value = null

            val intent = Intent(context, MapDownloadService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, MapDownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun consumeResult() {
            _lastResult.value = null
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var cancelRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelRequested = true
                return START_NOT_STICKY
            }
            ACTION_START -> startDownload()
        }
        return START_NOT_STICKY
    }

    private fun startDownload() {
        val bounds = pendingBounds
        val baseDir = pendingBaseDir
        val sources = pendingSources

        if (bounds == null || baseDir == null || sources.isEmpty()) {
            stopSelf()
            return
        }

        cancelRequested = false
        _isRunning.value = true
        startForeground(NOTIFICATION_ID, buildNotification("Подготовка…", 0, 0))
        acquireWakeLock()

        serviceScope.launch {
            try {
                val result = RegionDownloader.download(
                    baseDir = baseDir,
                    bounds = bounds,
                    minZoom = pendingMinZoom,
                    maxZoom = pendingMaxZoom,
                    sources = sources,
                    isCancelled = { cancelRequested },
                    onProgress = { progress ->
                        _progress.value = progress
                        updateNotification(progress)
                    }
                )
                _lastResult.value = result
                Log.d(TAG, "Download finished: $result")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _lastResult.value = RegionDownloader.Result(0, 0, 0, false, true)
            } finally {
                _progress.value = null
                _isRunning.value = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Held for the whole job: the download is a long chain of network requests with pauses in
     * between, and without the lock the CPU sleeps through those pauses and the job stalls.
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OrientirNav:MapDownloadWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Загрузка карт",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Фоновая загрузка выбранной области карты" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MapDownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Загрузка карты")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", cancelIntent)
            .apply {
                if (total > 0) setProgress(total, done, false)
            }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(progress: DownloadProgress) {
        val text = "${progress.done} из ${progress.total} • ${progress.currentLayer}"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progress.done, progress.total))
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        _isRunning.value = false
        _progress.value = null
        super.onDestroy()
    }
}
