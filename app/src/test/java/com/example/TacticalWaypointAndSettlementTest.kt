package com.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.settlement.SettlementRepository
import com.example.geodesy.GeodesyEngine
import com.example.model.ActiveMapTool
import com.example.model.GeoPoint
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TacticalWaypointAndSettlementTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testUsk2000CoordinateConversion() {
        // Known geodetic test point near Kyiv: ~50.4501 N, 30.5234 E
        val lat = 50.4501
        val lon = 30.5234
        val bundle = GeodesyEngine.getCoordinateBundle(lat, lon)

        // Verify USK-2000 string was computed
        assertTrue("USK-2000 bundle should not be empty", bundle.usk2000.isNotBlank())
        assertTrue("USK-2000 should contain X and Y", bundle.usk2000.contains("X:") && bundle.usk2000.contains("Y:"))

        val x = bundle.usk2000X
        val y = bundle.usk2000Y
        assertTrue("X should be valid northing in Ukraine (> 4,000,000 m)", x > 4000000.0)
        assertTrue("Y should be valid easting with zone prefix", y > 100000.0)

        // Inverse transform to WGS-84
        val result = GeodesyEngine.usk2000ToWgs84(x, y)

        // Check distance between original and transformed coordinate (< 5 meters due to roundings)
        val diffMeters = GeodesyEngine.distanceMeters(GeoPoint(lat, lon), result)
        assertTrue("USK-2000 roundtrip error should be less than 5m, was: $diffMeters m", diffMeters < 5.0)
    }

    @Test
    fun testSettlementRepositorySearchAndDuplicateResolution() = runBlocking {
        val repo = SettlementRepository(application)
        val all = repo.getSettlements()
        assertTrue("Settlements should be loaded from assets", all.isNotEmpty())

        // Search for "Олександрівка" which exists in multiple oblasts
        val results = repo.search("Олександрівка")
        assertTrue("Should find multiple entries for Олександрівка", results.size >= 2)

        val oblasts = results.map { it.oblast }.distinct()
        assertTrue("Duplicate settlement names should have distinct oblasts", oblasts.size >= 2)

        // Search with specific oblast filter
        val kirovohradResults = repo.search("Олександрівка", oblastFilter = "Кіровоградська")
        assertTrue("Filtered search should return matching settlement", kirovohradResults.isNotEmpty())
        assertEquals("Кіровоградська", kirovohradResults.first().oblast)
    }

    @Test
    fun testActiveMapToolStateMachine() = runBlocking {
        val viewModel = MainViewModel(application)

        // 1. Initial state is NONE
        assertEquals(ActiveMapTool.NONE, viewModel.activeMapTool.value)
        assertNull(viewModel.candidatePoint.value)
        assertNull(viewModel.selectedWaypoint.value)

        // 2. Tap on map creates candidate marker and transitions to PLACE_WAYPOINT_PENDING
        val tapPoint = GeoPoint(50.4501, 30.5234)
        viewModel.onMapTapped(tapPoint)

        assertEquals(ActiveMapTool.PLACE_WAYPOINT_PENDING, viewModel.activeMapTool.value)
        assertEquals(tapPoint, viewModel.candidatePoint.value)
        assertNull(viewModel.selectedWaypoint.value)

        // 3. Saving candidate point returns state to NONE and clears candidate
        viewModel.saveCandidatePoint("Тестова точка", "Опис точки")
        assertEquals(ActiveMapTool.NONE, viewModel.activeMapTool.value)
        assertNull(viewModel.candidatePoint.value)

        // 4. Tapping existing waypoint clears candidate and selects it
        viewModel.onMapTapped(GeoPoint(49.0, 31.0)) // candidate set
        assertEquals(ActiveMapTool.PLACE_WAYPOINT_PENDING, viewModel.activeMapTool.value)

        val dummyWaypoint = com.example.data.entity.WaypointEntity(
            id = 1L,
            name = "Тестова точка",
            latitude = 50.4501,
            longitude = 30.5234,
            altitudeMeters = 120.0
        )
        viewModel.selectWaypoint(dummyWaypoint)
        assertEquals(dummyWaypoint, viewModel.selectedWaypoint.value)
        assertNull("Selecting existing waypoint must clear candidate", viewModel.candidatePoint.value)
        assertEquals(ActiveMapTool.NONE, viewModel.activeMapTool.value)

        // 5. Activating Ruler sets RULER tool
        viewModel.toggleRuler()
        assertEquals(ActiveMapTool.RULER, viewModel.activeMapTool.value)
        assertTrue(viewModel.rulerState.value.isActive)

        // While ruler is active, onMapTapped does not create candidate
        viewModel.onMapTapped(GeoPoint(48.0, 32.0))
        assertNull(viewModel.candidatePoint.value)
        assertEquals(ActiveMapTool.RULER, viewModel.activeMapTool.value)

        // Deactivating Ruler returns to NONE
        viewModel.toggleRuler()
        assertEquals(ActiveMapTool.NONE, viewModel.activeMapTool.value)
        assertFalse(viewModel.rulerState.value.isActive)
    }
}
