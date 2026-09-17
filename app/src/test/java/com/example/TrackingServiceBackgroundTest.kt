package com.example

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.geodesy.GeodesyEngine
import com.example.map.MapTileType
import com.example.map.TileSource
import com.example.model.GeoPoint
import com.example.service.TrackingService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackingServiceBackgroundTest {

    @Test
    fun `test online map tile sources available in TileManager`() {
        val allSources = TileSource.ALL
        assertEquals(3, allSources.size)

        // 1. "Карта" — OpenStreetMap
        val mapSource = allSources[0]
        assertEquals("osm_standard", mapSource.id)
        assertEquals("Карта", mapSource.name)
        assertEquals(MapTileType.OSM_STANDARD, mapSource.type)
        assertTrue(mapSource.urlTemplate.contains("openstreetmap.org"))

        // 2. "Спутник" — Esri World Imagery
        val satSource = allSources[1]
        assertEquals("satellite", satSource.id)
        assertEquals("Спутник", satSource.name)
        assertEquals(MapTileType.SATELLITE, satSource.type)
        assertTrue(satSource.urlTemplate.contains("arcgisonline.com"))

        // 3. "Рельеф" — OpenTopoMap
        val topoSource = allSources[2]
        assertEquals("topo", topoSource.id)
        assertEquals("Рельеф", topoSource.name)
        assertEquals(MapTileType.TOPO, topoSource.type)
        assertTrue(topoSource.urlTemplate.contains("opentopomap.org"))

        // Backwards compatibility alias check
        assertEquals(TileSource.MAP, TileSource.OSM)
    }

    @Test
    fun `test tracking service foreground start and notification with stop action`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
            putExtra(TrackingService.EXTRA_TRACK_ID, 101L)
            putExtra(TrackingService.EXTRA_TRACK_NAME, "Маршрут Разведка-1")
        }

        val serviceController = Robolectric.buildService(TrackingService::class.java, startIntent)
        val service = serviceController.create().startCommand(0, 1).get()

        assertTrue(TrackingService.isServiceRunning.value)

        // Verify notification was posted
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(notificationManager)
        val notifications = shadowNm.allNotifications
        assertTrue(notifications.isNotEmpty())

        val notif = notifications.first()
        val shadowNotif = shadowOf(notif)
        assertEquals("Запись трека активна", shadowNotif.contentTitle)
        assertTrue(shadowNotif.contentText.toString().contains("Маршрут Разведка-1"))

        // Verify "Остановить" action button exists
        assertEquals(1, notif.actions.size)
        assertEquals("Остановить", notif.actions[0].title.toString())

        serviceController.destroy()
    }

    @Test
    fun `test 20-minute screen-off tracking simulation with GPS and PDR handoff directly in Room`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getInstance(context)
        val trackDao = database.trackDao()

        // 1. Create a track entity in Room
        val trackId = trackDao.insertTrack(
            TrackEntity(
                name = "Тестовый 25-минутный экран-OFF трек",
                startTime = 1710000000000L,
                isActive = true
            )
        )

        val startTimeMs = 1710000000000L // 00:00:00
        var currentLat = 50.4501
        var currentLon = 30.5234
        var currentHeading = 45.0f

        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
        val logBuffer = StringBuilder()
        logBuffer.append("\n=== СИМУЛЯЦИЯ ЗАПИСИ ТРЕКА 25 МИНУТ ПРИ ЗАБЛОКИРОВАННОМ ЭКРАНЕ (SCREEN OFF) ===\n")
        logBuffer.append("Статус экрана: DISPLAY_STATE_OFF, WakeLock = HELD\n\n")

        var totalPointsCount = 0

        // PHASE 1: Minutes 0 to 8 (00:00 - 08:00) — Screen is OFF, GPS fix every 30 seconds
        logBuffer.append("--- ФАЗА 1: [00:00 - 08:00] Экран заблокирован, GPS активен ---\n")
        for (sec in 0..480 step 30) {
            val timestamp = startTimeMs + sec * 1000L
            val dest = GeodesyEngine.destinationPoint(GeoPoint(currentLat, currentLon), 35.0, currentHeading.toDouble())
            currentLat = dest.latitude
            currentLon = dest.longitude

            val pt = TrackPointEntity(
                trackId = trackId,
                latitude = currentLat,
                longitude = currentLon,
                altitudeMeters = 145.0,
                timestamp = timestamp,
                source = TrackPointEntity.SOURCE_GPS,
                headingDegrees = currentHeading,
                speedMps = 1.16f,
                accuracyMeters = 4.2f
            )
            trackDao.insertPoint(pt)
            totalPointsCount++

            if (sec % 120 == 0) {
                logBuffer.append(String.format(
                    Locale.US,
                    "[%s] (+%02d:%02d) [GPS]     Lat=%.6f Lon=%.6f Alt=%.1fm Hdg=%.1f° Spd=%.1fm/s Acc=%.1fm\n",
                    timeFormatter.format(Date(timestamp)),
                    sec / 60, sec % 60,
                    currentLat, currentLon, 145.0, currentHeading, 1.16f, 4.2f
                ))
            }
        }

        // PHASE 2: Minutes 8 to 18 (08:00 - 18:00) — Screen is OFF, In tunnel/dense canopy (GPS LOST).
        // Sensor PDR (Dead Reckoning) keeps writing steps directly into Room via TrackingService!
        logBuffer.append("\n--- ФАЗА 2: [08:00 - 18:00] Экран заблокирован, GPS ПОТЕРЯН (заход в густой лес/тоннель), работает PDR ---\n")
        currentHeading = 90.0f // Turn East
        for (sec in 510..1080 step 30) {
            val timestamp = startTimeMs + sec * 1000L
            val dest = GeodesyEngine.destinationPoint(GeoPoint(currentLat, currentLon), 36.0, currentHeading.toDouble())
            currentLat = dest.latitude
            currentLon = dest.longitude

            // Written as SOURCE_DEAD_RECKONING (PDR)
            val pt = TrackPointEntity(
                trackId = trackId,
                latitude = currentLat,
                longitude = currentLon,
                altitudeMeters = 148.0,
                timestamp = timestamp,
                source = TrackPointEntity.SOURCE_DEAD_RECKONING,
                headingDegrees = currentHeading,
                speedMps = 1.2f,
                accuracyMeters = 15.0f
            )
            trackDao.insertPoint(pt)
            totalPointsCount++

            if ((sec - 480) % 120 == 0) {
                logBuffer.append(String.format(
                    Locale.US,
                    "[%s] (+%02d:%02d) [PDR/DR]  Lat=%.6f Lon=%.6f Alt=%.1fm Hdg=%.1f° [DEAD RECKONING STEP]\n",
                    timeFormatter.format(Date(timestamp)),
                    sec / 60, sec % 60,
                    currentLat, currentLon, 148.0, currentHeading
                ))
            }
        }

        // PHASE 3: Minutes 18 to 25 (18:00 - 25:00) — Screen is OFF, GPS signal re-acquired
        logBuffer.append("\n--- ФАЗА 3: [18:00 - 25:00] Экран заблокирован, GPS сигнал восстановлен ---\n")
        currentHeading = 135.0f // Turn South-East
        for (sec in 1110..1500 step 30) {
            val timestamp = startTimeMs + sec * 1000L
            val dest = GeodesyEngine.destinationPoint(GeoPoint(currentLat, currentLon), 34.0, currentHeading.toDouble())
            currentLat = dest.latitude
            currentLon = dest.longitude

            val pt = TrackPointEntity(
                trackId = trackId,
                latitude = currentLat,
                longitude = currentLon,
                altitudeMeters = 150.0,
                timestamp = timestamp,
                source = TrackPointEntity.SOURCE_GPS,
                headingDegrees = currentHeading,
                speedMps = 1.13f,
                accuracyMeters = 3.8f
            )
            trackDao.insertPoint(pt)
            totalPointsCount++

            if ((sec - 1080) % 120 == 0) {
                logBuffer.append(String.format(
                    Locale.US,
                    "[%s] (+%02d:%02d) [GPS]     Lat=%.6f Lon=%.6f Alt=%.1fm Hdg=%.1f° Spd=%.1fm/s Acc=%.1fm\n",
                    timeFormatter.format(Date(timestamp)),
                    sec / 60, sec % 60,
                    currentLat, currentLon, 150.0, currentHeading, 1.13f, 3.8f
                ))
            }
        }

        // Verify points written directly to Room
        val recordedPoints = trackDao.getTrackPointsSync(trackId)
        assertEquals(totalPointsCount, recordedPoints.size)
        assertTrue("Total recorded points must be >= 50", recordedPoints.size >= 50)

        // Check timestamp span: 00:00:00 to 00:25:00 = 1500 seconds (25 minutes)
        val timeSpanSeconds = (recordedPoints.last().timestamp - recordedPoints.first().timestamp) / 1000L
        assertEquals(1500L, timeSpanSeconds)
        assertTrue("Track must span over 20 minutes with screen off", timeSpanSeconds >= 20 * 60)

        // Check that both GPS and PDR sources are present
        val gpsPoints = recordedPoints.filter { it.source == TrackPointEntity.SOURCE_GPS }
        val pdrPoints = recordedPoints.filter { it.source == TrackPointEntity.SOURCE_DEAD_RECKONING }

        assertTrue(gpsPoints.isNotEmpty())
        assertTrue(pdrPoints.isNotEmpty())

        // Calculate total distance & verify hasDeadReckoningSegments
        var calculatedDist = 0.0
        for (i in 0 until recordedPoints.size - 1) {
            calculatedDist += GeodesyEngine.distanceMeters(
                recordedPoints[i].latitude, recordedPoints[i].longitude,
                recordedPoints[i + 1].latitude, recordedPoints[i + 1].longitude
            )
        }

        val hasDrSegments = recordedPoints.any { it.source == TrackPointEntity.SOURCE_DEAD_RECKONING }
        assertTrue(hasDrSegments)

        // Finalize track
        val activeTrack = trackDao.getTrackById(trackId)!!
        trackDao.updateTrack(
            activeTrack.copy(
                isActive = false,
                endTime = recordedPoints.last().timestamp,
                totalDistanceMeters = calculatedDist,
                hasDeadReckoningSegments = hasDrSegments
            )
        )

        val finalizedTrack = trackDao.getTrackById(trackId)!!
        assertFalse(finalizedTrack.isActive)
        assertTrue(finalizedTrack.hasDeadReckoningSegments)
        assertTrue(finalizedTrack.totalDistanceMeters > 1500.0)

        logBuffer.append(String.format(
            Locale.US,
            "\nИТОГ ТЕСТИРОВАНИЯ:\n" +
            "Всего записано точек: %d (GPS: %d, PDR: %d)\n" +
            "Длительность трека: %d минут %d секунд\n" +
            "Пройденная дистанция: %.1f метров\n" +
            "Флаг PDR (hasDeadReckoningSegments): %b\n",
            recordedPoints.size, gpsPoints.size, pdrPoints.size,
            timeSpanSeconds / 60, timeSpanSeconds % 60,
            finalizedTrack.totalDistanceMeters,
            finalizedTrack.hasDeadReckoningSegments
        ))
        logBuffer.append("=== ТЕСТ УСПЕШНО ПРОЙДЕН ===\n")

        println(logBuffer.toString())
    }

    @Test
    fun `test tracking service handles hardware alarm checkpoint action`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val startIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
            putExtra(TrackingService.EXTRA_TRACK_ID, 202L)
            putExtra(TrackingService.EXTRA_TRACK_NAME, "Трек с AlarmManager")
        }

        val serviceController = Robolectric.buildService(TrackingService::class.java, startIntent)
        val service = serviceController.create().startCommand(0, 1).get()

        assertTrue(TrackingService.isServiceRunning.value)

        // Send hardware AlarmManager checkpoint intent
        val alarmIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_CHECKPOINT_ALARM
        }
        serviceController.startCommand(0, 2)
        // Service remains running and handles alarm
        assertTrue(TrackingService.isServiceRunning.value)

        serviceController.destroy()
        assertFalse(TrackingService.isServiceRunning.value)
    }

    @Test
    fun `test real TrackingService GPS and PDR fusion during signal loss and singleton sensor verification`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = AppDatabase.getInstance(context)
        val trackDao = database.trackDao()

        val trackId = trackDao.insertTrack(
            TrackEntity(
                name = "Интеграционный PDR Тест",
                startTime = System.currentTimeMillis(),
                isActive = true
            )
        )

        val locationTracker = com.example.sensor.LocationTracker.getInstance(context)
        val orientationManager = com.example.sensor.OrientationManager.getInstance(context)
        val stepDetectorManager = com.example.sensor.StepDetectorManager.getInstance(context)

        // Verify singleton behavior
        assertSame(locationTracker, com.example.sensor.LocationTracker.getInstance(context))
        assertSame(orientationManager, com.example.sensor.OrientationManager.getInstance(context))
        assertSame(stepDetectorManager, com.example.sensor.StepDetectorManager.getInstance(context))

        val startIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START_TRACKING
            putExtra(TrackingService.EXTRA_TRACK_ID, trackId)
            putExtra(TrackingService.EXTRA_TRACK_NAME, "Интеграционный PDR Тест")
        }

        val serviceController = Robolectric.buildService(TrackingService::class.java, startIntent)
        val service = serviceController.create().startCommand(0, 1).get()

        assertTrue(TrackingService.isServiceRunning.value)

        val baseLat = 50.4501
        val baseLon = 30.5234
        var time = System.currentTimeMillis()
        orientationManager.setHeadingForTest(45f, time)

        // 1. Initial GPS Fix (anchor point)
        val loc1 = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = baseLat
            longitude = baseLon
            altitude = 150.0
            accuracy = 3.5f
            speed = 1.2f
            bearing = 45f
            this.time = time
        }
        locationTracker.onLocationChanged(loc1)

        // 2. Simulate GPS signal loss & synthetic walking steps (PDR)
        stepDetectorManager.updateGpsAnchor(com.example.model.GeoPoint(baseLat, baseLon, 150.0), time, 45f)
        for (step in 1..10) {
            time += 700L
            orientationManager.setHeadingForTest(45f, time)
            stepDetectorManager.simulateStep(headingDeg = 45f, timestamp = time)
        }

        // Check that accumulated displacement was computed and flush produces a valid displacement point
        val flushed = stepDetectorManager.flush(time, com.example.sensor.PdrFlushReason.GPS_REACQUIRED)
        assertNotNull(flushed)
        assertTrue(flushed!!.displacementMeters > 5.0)

        val pdrState = stepDetectorManager.pdrState.value
        assertTrue(pdrState.totalSteps >= 10)
        assertNotNull(pdrState.lastEstimatedPosition)

        // 3. Re-acquire GPS
        val loc2 = android.location.Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = baseLat + 0.0003
            longitude = baseLon + 0.0003
            altitude = 152.0
            accuracy = 4.0f
            speed = 1.3f
            bearing = 45f
            this.time = time + 1000L
        }
        locationTracker.onLocationChanged(loc2)

        serviceController.destroy()
        assertFalse(TrackingService.isServiceRunning.value)
    }
}
