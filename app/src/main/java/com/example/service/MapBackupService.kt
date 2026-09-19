package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.map.MbtilesMerger
import com.example.map.MergeResult
import com.example.map.TileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service for packing and restoring full map backups.
 * Keeps running when the user switches apps or turns off the screen.
 */
class MapBackupService : Service() {

    sealed class OperationType {
        object Backup : OperationType()
        object Restore : OperationType()
        object Merge : OperationType()
    }

    data class Progress(
        val type: OperationType,
        val current: Long,
        val total: Long,
        val percentage: Float,
        val statusText: String
    )

    data class Result(
        val type: OperationType,
        val success: Boolean,
        val count: Int,
        val outputFile: File? = null,
        val error: String? = null
    )

    companion object {
        private const val TAG = "MapBackupService"
        private const val CHANNEL_ID = "map_backup_channel"
        private const val NOTIFICATION_ID = 4825

        const val ACTION_BACKUP = "com.example.action.BACKUP_MAPS"
        const val ACTION_RESTORE = "com.example.action.RESTORE_MAPS"
        const val ACTION_MERGE_MAPS = "com.example.action.MERGE_MAPS"
        const val ACTION_CANCEL = "com.example.action.CANCEL_BACKUP_MAPS"

        const val EXTRA_OUTPUT_PATH = "com.example.extra.OUTPUT_PATH"
        const val EXTRA_RESTORE_URI = "com.example.extra.RESTORE_URI"
        const val EXTRA_SOURCE_PATHS = "com.example.extra.SOURCE_PATHS"
        const val EXTRA_DELETE_SOURCES = "com.example.extra.DELETE_SOURCES"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _progress = MutableStateFlow<Progress?>(null)
        val progress: StateFlow<Progress?> = _progress.asStateFlow()

        private val _lastResult = MutableStateFlow<Result?>(null)
        val lastResult: StateFlow<Result?> = _lastResult.asStateFlow()

        fun startBackup(context: Context, outputFile: File) {
            _lastResult.value = null

            val intent = Intent(context, MapBackupService::class.java).apply {
                action = ACTION_BACKUP
                putExtra(EXTRA_OUTPUT_PATH, outputFile.absolutePath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startRestore(context: Context, archiveUri: Uri) {
            _lastResult.value = null

            val intent = Intent(context, MapBackupService::class.java).apply {
                action = ACTION_RESTORE
                putExtra(EXTRA_RESTORE_URI, archiveUri.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startMerge(
            context: Context,
            sourceFiles: List<File>,
            outputFile: File,
            deleteSourcesOnSuccess: Boolean = false
        ) {
            _lastResult.value = null

            val intent = Intent(context, MapBackupService::class.java).apply {
                action = ACTION_MERGE_MAPS
                putStringArrayListExtra(EXTRA_SOURCE_PATHS, ArrayList(sourceFiles.map { it.absolutePath }))
                putExtra(EXTRA_OUTPUT_PATH, outputFile.absolutePath)
                putExtra(EXTRA_DELETE_SOURCES, deleteSourcesOnSuccess)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, MapBackupService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }

        fun consumeResult(): Result? {
            val res = _lastResult.value
            _lastResult.value = null
            return res
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
            ACTION_BACKUP -> {
                val path = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                if (path == null) {
                    Log.e(TAG, "Restarted without EXTRA_OUTPUT_PATH, cannot resume backup")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startBackupOperation(File(path))
            }
            ACTION_RESTORE -> {
                val uriStr = intent.getStringExtra(EXTRA_RESTORE_URI)
                if (uriStr == null) {
                    Log.e(TAG, "Restarted without EXTRA_RESTORE_URI, cannot resume restore")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRestoreOperation(Uri.parse(uriStr))
            }
            ACTION_MERGE_MAPS -> {
                val paths = intent.getStringArrayListExtra(EXTRA_SOURCE_PATHS)
                val outputPath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
                val deleteSources = intent.getBooleanExtra(EXTRA_DELETE_SOURCES, false)
                if (paths.isNullOrEmpty() || outputPath == null) {
                    Log.e(TAG, "Missing arguments for ACTION_MERGE_MAPS")
                    stopSelf()
                    return START_NOT_STICKY
                }
                val sourceFiles = paths.map { File(it) }
                startMergeOperation(sourceFiles, File(outputPath), deleteSources)
            }
        }
        return START_NOT_STICKY
    }

    private fun startMergeOperation(
        sourceFiles: List<File>,
        outputFile: File,
        deleteSourcesOnSuccess: Boolean
    ) {
        cancelRequested = false
        _isRunning.value = true
        _lastResult.value = null

        val notification = buildNotification("Слияние баз данных карт…", 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()

        serviceScope.launch {
            try {
                val totalCount = sourceFiles.size
                val mergeResult = MbtilesMerger.merge(sourceFiles, outputFile) { current, total ->
                    if (cancelRequested) throw java.util.concurrent.CancellationException("Отменено пользователем")
                    val p = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val text = "Слияние: $current из $total файлов (${(p * 100).toInt()}%)"
                    _progress.value = Progress(OperationType.Merge, current.toLong(), total.toLong(), p, text)
                    updateNotification("Слияние карт", text, p)
                }

                when (mergeResult) {
                    is MergeResult.Success -> {
                        if (deleteSourcesOnSuccess) {
                            sourceFiles.forEach { src ->
                                if (src.absolutePath != outputFile.absolutePath) {
                                    runCatching { src.delete() }
                                }
                            }
                        }
                        _lastResult.value = Result(
                            OperationType.Merge,
                            success = true,
                            count = mergeResult.tileSourceCount,
                            outputFile = mergeResult.file
                        )
                        notifyFinished("Карты объединены", "Объединено источников: ${mergeResult.tileSourceCount}")
                    }
                    is MergeResult.Error -> {
                        _lastResult.value = Result(
                            OperationType.Merge,
                            success = false,
                            count = 0,
                            outputFile = null,
                            error = mergeResult.message
                        )
                        notifyFinished("Ошибка слияния карт", mergeResult.message)
                    }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                Log.i(TAG, "Merge cancelled by user")
                runCatching { outputFile.delete() }
                _lastResult.value = Result(OperationType.Merge, false, 0, null, "Отменено")
            } catch (e: Exception) {
                Log.e(TAG, "Merge failed", e)
                runCatching { outputFile.delete() }
                _lastResult.value = Result(OperationType.Merge, false, 0, null, e.localizedMessage ?: e.message)
                notifyFinished("Ошибка слияния карт", e.localizedMessage ?: "Сбой слияния")
            } finally {
                _progress.value = null
                _isRunning.value = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startBackupOperation(outputFile: File) {
        cancelRequested = false
        _isRunning.value = true
        _lastResult.value = null

        val notification = buildNotification("Подготовка резервной копии карт…", 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()

        serviceScope.launch {
            try {
                val tileManager = TileManager(applicationContext)
                val count = tileManager.packFullBackup(outputFile) { written, total ->
                    if (cancelRequested) throw java.util.concurrent.CancellationException("Отменено пользователем")
                    val p = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
                    val writtenMb = written / (1024 * 1024)
                    val totalMb = total / (1024 * 1024)
                    val statusText = "Архивация: $writtenMb из $totalMb МБ (${(p * 100).toInt()}%)"
                    val prog = Progress(OperationType.Backup, written, total, p, statusText)
                    _progress.value = prog
                    updateNotification("Резервная копия карт", statusText, p)
                }

                if (count > 0) {
                    _lastResult.value = Result(OperationType.Backup, true, count, outputFile)
                    notifyFinished("Резервная копия создана", "Упаковано файлов: $count")
                } else {
                    _lastResult.value = Result(OperationType.Backup, false, 0, null, "Нет файлов карт для копирования")
                }
            } catch (e: java.util.concurrent.CancellationException) {
                Log.i(TAG, "Backup cancelled by user")
                _lastResult.value = Result(OperationType.Backup, false, 0, null, "Отменено")
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
                _lastResult.value = Result(OperationType.Backup, false, 0, null, e.localizedMessage ?: e.message)
                notifyFinished("Ошибка резервной копии", e.localizedMessage ?: "Сбой архивации")
            } finally {
                _progress.value = null
                _isRunning.value = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startRestoreOperation(uri: Uri) {
        cancelRequested = false
        _isRunning.value = true
        _lastResult.value = null

        val notification = buildNotification("Подготовка к восстановлению карт…", 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()

        serviceScope.launch {
            val tempZip = File(cacheDir, "temp_restore_backup.zip")
            try {
                // 1. Copy uri stream to tempZip with progress
                val sourceSize = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) c.getLong(idx) else -1L
                } ?: -1L

                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(1 shl 20) // 1MB buffer
                        var copied = 0L
                        while (true) {
                            if (cancelRequested) throw java.util.concurrent.CancellationException("Отменено пользователем")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (sourceSize > 0) {
                                val p = (copied.toFloat() / sourceSize).coerceIn(0f, 1f)
                                val copiedMb = copied / (1024 * 1024)
                                val totalMb = sourceSize / (1024 * 1024)
                                val text = "Загрузка архива: $copiedMb из $totalMb МБ (${(p * 100).toInt()}%)"
                                _progress.value = Progress(OperationType.Restore, copied, sourceSize, p, text)
                                updateNotification("Восстановление карт", text, p)
                            }
                        }
                    }
                } ?: throw java.io.IOException("Не удалось открыть выбранный архив")

                // 2. Unpack backup with progress
                val tileManager = TileManager(applicationContext)
                val count = tileManager.restoreFullBackup(tempZip) { cur, total ->
                    if (cancelRequested) throw java.util.concurrent.CancellationException("Отменено пользователем")
                    val p = if (total > 0) cur.toFloat() / total else 0f
                    val text = "Восстановление: $cur из $total файлов (${(p * 100).toInt()}%)"
                    _progress.value = Progress(OperationType.Restore, cur.toLong(), total.toLong(), p, text)
                    updateNotification("Восстановление карт", text, p)
                }

                if (count > 0) {
                    _lastResult.value = Result(OperationType.Restore, true, count)
                    notifyFinished("Карты восстановлены", "Успешно восстановлено файлов: $count")
                } else {
                    _lastResult.value = Result(OperationType.Restore, false, 0, null, "В архиве не найдено файлов карт")
                }
            } catch (e: java.util.concurrent.CancellationException) {
                Log.i(TAG, "Restore cancelled by user")
                _lastResult.value = Result(OperationType.Restore, false, 0, null, "Отменено")
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                _lastResult.value = Result(OperationType.Restore, false, 0, null, e.localizedMessage ?: e.message)
                notifyFinished("Ошибка восстановления карт", e.localizedMessage ?: "Сбой распаковки")
            } finally {
                runCatching { tempZip.delete() }
                _progress.value = null
                _isRunning.value = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OrientirNav:MapBackupWakeLock"
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
                "Резервная копия карт",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Фоновое создание и восстановление резервных копий карт" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progressFraction: Float): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MapBackupService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Резервная копия карт")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", cancelIntent)
            .apply {
                if (progressFraction > 0f) {
                    setProgress(100, (progressFraction * 100).toInt(), false)
                } else {
                    setProgress(100, 0, true)
                }
            }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String, progressFraction: Float) {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MapBackupService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", cancelIntent)
            .apply {
                if (progressFraction > 0f) {
                    setProgress(100, (progressFraction * 100).toInt(), false)
                } else {
                    setProgress(100, 0, true)
                }
            }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyFinished(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIFICATION_ID + 10, notif)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, foregroundServiceType: Int) {
        super.onTimeout(startId, foregroundServiceType)
        Log.w(TAG, "Map backup foreground service timed out by system (type=$foregroundServiceType, startId=$startId)")
        cancelRequested = true
        _lastResult.value = Result(
            OperationType.Backup,
            false,
            0,
            null,
            "Лимит времени фоновой работы исчерпан системой."
        )

        val timeoutNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Резервное копирование приостановлено")
            .setContentText("Лимит фоновой работы исчерпан системой Android.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + 1, timeoutNotification)

        _isRunning.value = false
        _progress.value = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        _isRunning.value = false
        _progress.value = null
        super.onDestroy()
    }
}
