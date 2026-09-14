package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteLeg
import com.example.data.entity.TrackEntity
import com.example.data.entity.TrackPointEntity
import com.example.data.entity.WaypointEntity
import com.example.data.repository.NavigationRepository
import com.example.data.track.TrackFilter
import com.example.geodesy.GeodesyEngine
import com.example.map.MapProjection
import com.example.map.MbtilesTileSource
import com.example.map.OfflineMapDetector
import com.example.map.OfflineMapFormat
import com.example.map.TileCoordinate
import com.example.map.TileManager
import com.example.map.TileSource
import com.example.model.*
import com.example.sensor.GpsStatus
import com.example.sensor.LocationTracker
import com.example.sensor.OrientationData
import com.example.sensor.OrientationManager
import com.example.sensor.PdrState
import com.example.sensor.StepDetectorManager
import com.example.service.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val database = AppDatabase.getInstance(application)
    val repository = NavigationRepository(database)
    val tileManager = TileManager(application)
    val settlementRepository = com.example.data.settlement.SettlementRepository(application)

    val locationTracker = LocationTracker(application)
    val orientationManager = OrientationManager(application)
    val stepDetectorManager = StepDetectorManager(application)

    // UI & Navigation State
    val waypoints: StateFlow<List<WaypointEntity>> = repository.allWaypoints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routes: StateFlow<List<RouteEntity>> = repository.allRoutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tracks: StateFlow<List<TrackEntity>> = repository.allTracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTrack: StateFlow<TrackEntity?> = repository.activeTrack
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isTrackingServiceRunning: StateFlow<Boolean> = TrackingService.isServiceRunning
    val serviceRecordedPointsCount: StateFlow<Int> = TrackingService.recordedPointsCount

    val gpsLocation: StateFlow<GeoPoint?> = locationTracker.currentLocation
    val gpsStatus: StateFlow<GpsStatus> = locationTracker.gpsStatus
    val orientationData: StateFlow<OrientationData> = orientationManager.orientationData
    val pdrState: StateFlow<PdrState> = stepDetectorManager.pdrState

    // Map viewport state
    private val _mapCenter = MutableStateFlow(GeoPoint(50.4501, 30.5234)) // Kyiv center
    val mapCenter: StateFlow<GeoPoint> = _mapCenter.asStateFlow()

    private val _mapZoom = MutableStateFlow(14.0)
    val mapZoom: StateFlow<Double> = _mapZoom.asStateFlow()

    private val _isFollowingLocation = MutableStateFlow(true)
    val isFollowingLocation: StateFlow<Boolean> = _isFollowingLocation.asStateFlow()

    private val _availableTileSources = MutableStateFlow<List<TileSource>>(TileSource.ALL)
    val availableTileSources: StateFlow<List<TileSource>> = _availableTileSources.asStateFlow()

    // Default to satellite: OSM's volunteer tile servers block app traffic that looks like
    // bulk downloading, which is exactly this app's use case, so it is a poor default.
    private val _activeTileSource = MutableStateFlow(TileSource.SATELLITE)
    val activeTileSource: StateFlow<TileSource> = _activeTileSource.asStateFlow()

    private val _activeMbtiles = MutableStateFlow<MbtilesTileSource?>(null)
    val activeMbtiles: StateFlow<MbtilesTileSource?> = _activeMbtiles.asStateFlow()

    private val _hasOfflineOrntpack = MutableStateFlow(false)
    val hasOfflineOrntpack: StateFlow<Boolean> = _hasOfflineOrntpack.asStateFlow()

    // Settings
    private val _userPreferences = MutableStateFlow(UserPreferences())
    val userPreferences: StateFlow<UserPreferences> = _userPreferences.asStateFlow()

    // Active Map Tool state machine (NONE, PLACE_WAYPOINT_PENDING, RULER)
    private val _activeMapTool = MutableStateFlow(ActiveMapTool.NONE)
    val activeMapTool: StateFlow<ActiveMapTool> = _activeMapTool.asStateFlow()

    // Temporary Candidate Point (before saving to Room)
    private val _candidatePoint = MutableStateFlow<GeoPoint?>(null)
    val candidatePoint: StateFlow<GeoPoint?> = _candidatePoint.asStateFlow()

    // Active Tools
    private val _rulerState = MutableStateFlow(RulerState())
    val rulerState: StateFlow<RulerState> = _rulerState.asStateFlow()


    private val _routeBuilderState = MutableStateFlow(RouteBuilderState())
    val routeBuilderState: StateFlow<RouteBuilderState> = _routeBuilderState.asStateFlow()

    // Inspecting / selected waypoint
    private val _selectedWaypoint = MutableStateFlow<WaypointEntity?>(null)
    val selectedWaypoint: StateFlow<WaypointEntity?> = _selectedWaypoint.asStateFlow()

    // Bottom Coordinate Modal sheet
    private val _isCoordinateModalOpen = MutableStateFlow(false)
    val isCoordinateModalOpen: StateFlow<Boolean> = _isCoordinateModalOpen.asStateFlow()

    // Active track recording points
    private val _currentTrackPoints = MutableStateFlow<List<TrackPointEntity>>(emptyList())
    val currentTrackPoints: StateFlow<List<TrackPointEntity>> = _currentTrackPoints.asStateFlow()

    /**
     * When true the map shows the unfiltered recording, including GPS outliers.
     * Off by default; the filter never alters what is stored.
     *
     * Declared before init(): Kotlin initialises properties in declaration order, and the
     * coroutines started in init read this flag. Declaring it further down left it null at
     * that moment and crashed the app on launch.
     */
    /**
     * Metres walked by dead reckoning since the last real GPS fix. Surfaced in the UI so the
     * user can judge how much error has accumulated: PDR drifts roughly 5-10% of distance.
     */
    private val _blindDistanceMeters = MutableStateFlow(0.0)
    val blindDistanceMeters: StateFlow<Double> = _blindDistanceMeters.asStateFlow()

    /** True when the displayed position comes from step counting rather than a live fix. */
    private val _isPositionEstimated = MutableStateFlow(false)
    val isPositionEstimated: StateFlow<Boolean> = _isPositionEstimated.asStateFlow()

    private val _showRawTracks = MutableStateFlow(false)
    val showRawTracks: StateFlow<Boolean> = _showRawTracks.asStateFlow()

    init {
        orientationManager.start()
        locationTracker.startListening()
        stepDetectorManager.start()

        // Sync location with map center if following
        viewModelScope.launch {
            gpsLocation.collect { loc ->
                if (loc != null) {
                    orientationManager.updateGeomagneticDeclination(loc.latitude, loc.longitude, loc.altitude ?: 0.0)
                    stepDetectorManager.updateGpsAnchor(loc)
                    if (_isFollowingLocation.value) {
                        _mapCenter.value = loc
                    }
                    // If tracking service is not actively recording in background, save point from ViewModel as fallback
                    if (!TrackingService.isServiceRunning.value) {
                        val track = activeTrack.value
                        if (track != null && track.isActive) {
                            val pt = TrackPointEntity(
                                trackId = track.id,
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                altitudeMeters = loc.altitude,
                                source = TrackPointEntity.SOURCE_GPS,
                                headingDegrees = loc.bearingDeg ?: orientationData.value.trueHeadingDeg,
                                speedMps = loc.speedMps,
                                accuracyMeters = loc.accuracy
                            )
                            repository.recordTrackPoint(pt)
                        }
                    }
                }
            }
        }

        // Pass heading to PDR
        viewModelScope.launch {
            orientationData.collect { o ->
                stepDetectorManager.updateCurrentHeading(o.trueHeadingDeg)
            }
        }

        // When the fix disappears, hand the last known position to the step counter as its
        // anchor. Without this the estimator has nothing to count from and the compass and
        // waypoint bearings go blank the moment GPS is lost — the opposite of what is needed.
        viewModelScope.launch {
            gpsStatus.collect { status ->
                val live = status == GpsStatus.ACTIVE
                _isPositionEstimated.value = !live

                if (!live) {
                    val anchor = locationTracker.lastFixBeforeSignalLoss.value
                        ?: gpsLocation.value
                        ?: pdrState.value.lastEstimatedPosition
                    if (anchor != null) {
                        stepDetectorManager.updateGpsAnchor(anchor)
                        stepDetectorManager.setDeadReckoningActive(true)
                    }
                } else {
                    _blindDistanceMeters.value = 0.0
                }
            }
        }

        // Track how far we have walked blind, for the accuracy hint in the UI.
        viewModelScope.launch {
            pdrState.collect { pdr ->
                if (_isPositionEstimated.value) {
                    _blindDistanceMeters.value = pdr.totalDistanceMeters
                }
            }
        }

        // Persist dead-reckoning points. TrackingService does this in the background, but when
        // it is not the one recording, nothing was writing them at all: switching GPS off mid
        // track produced a straight line across the whole blind section instead of the path.
        stepDetectorManager.onStepFlushed = { flushed ->
            viewModelScope.launch(Dispatchers.IO) {
                if (isTrackingServiceRunning.value && serviceRecordedPointsCount.value > 0) {
                    return@launch
                }
                val track = activeTrack.value ?: return@launch
                if (!track.isActive) return@launch

                repository.recordTrackPoint(
                    TrackPointEntity(
                        trackId = track.id,
                        latitude = flushed.point.latitude,
                        longitude = flushed.point.longitude,
                        altitudeMeters = null,
                        source = TrackPointEntity.SOURCE_DEAD_RECKONING,
                        headingDegrees = flushed.headingDeg,
                        speedMps = null,
                        accuracyMeters = null,
                        timestamp = flushed.timestamp
                    )
                )
                val filtered = TrackFilter.filter(repository.getTrackPointsSync(track.id))
                repository.updateTrack(track.copy(totalDistanceMeters = filtered.distanceMeters))
            }
        }

        // Watch active track points.
        // flatMapLatest is required here: collecting the inner points flow directly inside
        // activeTrack.collect never returns, so the outer flow would stop seeing new tracks
        // after the first one (second recording showed no line at all).
        viewModelScope.launch {
            activeTrack
                .flatMapLatest { track ->
                    if (track != null) repository.getTrackPoints(track.id) else flowOf(emptyList())
                }
                .collect { pts ->
                    _currentTrackPoints.value = TrackFilter.filter(pts, !_showRawTracks.value).points
                }
        }

        // Foreground fallback recorder.
        //
        // Points are normally written by TrackingService. If that service fails to start
        // (OEM restrictions, denied notification permission, FGS launch restrictions), the
        // track row still exists and the UI says "recording" while nothing is ever saved.
        // While the app is in the foreground we have a working GPS stream right here, so
        // record from it too whenever the service is NOT running. The distance gate also
        // keeps the database from filling with near-identical points while standing still.
        viewModelScope.launch {
            gpsLocation.collect { loc ->
                if (loc == null) return@collect

                // Normally the service owns recording. But if it claims to be running while
                // never persisting anything (its own GPS registration failed, OEM killed its
                // callbacks, ...), take over rather than let the user walk with an empty track.
                val serviceIsRecording = isTrackingServiceRunning.value &&
                    serviceRecordedPointsCount.value > 0
                if (serviceIsRecording) return@collect

                val track = activeTrack.value ?: return@collect
                if (!track.isActive) return@collect

                // Hopeless fixes are not worth storing at all: they cannot be salvaged later
                // and only add noise. Everything milder is kept raw and cleaned at display time.
                val accuracy = loc.accuracy
                if (accuracy != null && accuracy > UNUSABLE_ACCURACY_METERS) return@collect

                val last = _currentTrackPoints.value.lastOrNull()
                val movedEnough = last == null || GeodesyEngine.distanceMeters(
                    last.latitude, last.longitude, loc.latitude, loc.longitude
                ) >= MIN_TRACK_POINT_DISTANCE_METERS

                if (movedEnough) {
                    withContext(Dispatchers.IO) {
                        repository.recordTrackPoint(
                            TrackPointEntity(
                                trackId = track.id,
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                altitudeMeters = loc.altitude,
                                source = TrackPointEntity.SOURCE_GPS,
                                headingDegrees = loc.bearingDeg
                                    ?: orientationManager.orientationData.value.trueHeadingDeg,
                                speedMps = loc.speedMps,
                                accuracyMeters = loc.accuracy,
                                timestamp = loc.timestamp
                            )
                        )

                        // Recompute over the filtered path rather than adding each leg as it
                        // arrives: a single GPS jump would otherwise permanently inflate the
                        // total by kilometres even once the outlier is filtered out of the map.
                        val filtered = TrackFilter.filter(repository.getTrackPointsSync(track.id))
                        repository.updateTrack(
                            track.copy(totalDistanceMeters = filtered.distanceMeters)
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** Minimum spacing between recorded GPS points, in metres. */
        const val MIN_TRACK_POINT_DISTANCE_METERS = 5.0

        /** Fixes worse than this carry no usable information and are dropped on the spot. */
        const val UNUSABLE_ACCURACY_METERS = 100.0f
    }

    override fun onCleared() {
        super.onCleared()
        orientationManager.stop()
        locationTracker.stopListening()
        stepDetectorManager.stop()
    }

    // --- Map Actions ---
    fun setMapCenter(point: GeoPoint) {
        _mapCenter.value = point
        _isFollowingLocation.value = false
    }

    fun setMapZoom(zoom: Double) {
        _mapZoom.value = zoom.coerceIn(2.0, 19.0)
    }

    fun zoomIn() {
        setMapZoom(_mapZoom.value + 1.0)
    }

    fun zoomOut() {
        setMapZoom(_mapZoom.value - 1.0)
    }

    fun toggleFollowLocation() {
        val next = !_isFollowingLocation.value
        _isFollowingLocation.value = next

        // Safety net: re-sync providers whenever the user asks to be located. Covers
        // permission granted later from system settings, GPS switched on after launch, or
        // only the network provider having been registered because GPS was off at start.
        if (next && (!locationTracker.isActive() || !locationTracker.isGpsRegistered())) {
            locationTracker.syncProviders()
        }

        if (next && gpsLocation.value != null) {
            _mapCenter.value = gpsLocation.value!!
        }
    }

    fun setTileSource(source: TileSource) {
        _activeTileSource.value = source
    }

    fun attachOfflineArchive(file: File): Boolean {
        val ok = tileManager.attachOfflinePackage(file)
        _hasOfflineOrntpack.value = ok
        return ok
    }

    fun attachMbtilesFile(file: File): MbtilesTileSource? {
        val source = tileManager.attachMbtiles(file)
        if (source != null) {
            _activeMbtiles.value = source
            val current = _availableTileSources.value.toMutableList()
            current.removeAll { it.id == source.id }
            current.add(source)
            _availableTileSources.value = current
            _activeTileSource.value = source

            // If center coordinates are stored in MBTiles metadata, center map
            source.getCenterPoint()?.let { (centerPt, optZoom) ->
                _mapCenter.value = centerPt
                if (optZoom != null) {
                    _mapZoom.value = optZoom.coerceIn(source.minZoom.toDouble(), source.maxZoom.toDouble())
                }
            }
        }
        return source
    }

    /**
     * Imports an offline map file (.orntpack / .zip or .mbtiles),
     * automatically detecting the format by extension and content header.
     */
    fun importOfflineMapFile(file: File): Pair<OfflineMapFormat, Boolean> {
        val format = OfflineMapDetector.detectFromFile(file)
        return when (format) {
            OfflineMapFormat.MBTILES -> {
                val source = attachMbtilesFile(file)
                Pair(OfflineMapFormat.MBTILES, source != null)
            }
            OfflineMapFormat.ORNTPACK -> {
                val ok = attachOfflineArchive(file)
                Pair(OfflineMapFormat.ORNTPACK, ok)
            }
            OfflineMapFormat.UNKNOWN -> {
                // Try MBTiles first, then Orntpack
                val mbtiles = attachMbtilesFile(file)
                if (mbtiles != null) {
                    Pair(OfflineMapFormat.MBTILES, true)
                } else {
                    val ok = attachOfflineArchive(file)
                    Pair(if (ok) OfflineMapFormat.ORNTPACK else OfflineMapFormat.UNKNOWN, ok)
                }
            }
        }
    }

    // --- Coordinate Display & Modal ---
    fun openCoordinateModal() {
        _isCoordinateModalOpen.value = true
    }

    fun closeCoordinateModal() {
        _isCoordinateModalOpen.value = false
    }

    fun getCoordinateBundle(lat: Double, lon: Double): CoordinateBundle {
        return GeodesyEngine.getCoordinateBundle(lat, lon)
    }

    // --- User Preferences ---
    fun toggleAngleUnit() {
        val cur = _userPreferences.value.defaultAngleUnit
        val next = if (cur == AngleUnit.DEGREES_360) AngleUnit.MILS_60_00 else AngleUnit.DEGREES_360
        _userPreferences.value = _userPreferences.value.copy(defaultAngleUnit = next)
    }

    fun setCoordinateSystem(system: CoordinateSystem) {
        _userPreferences.value = _userPreferences.value.copy(defaultCoordinateSystem = system)
    }

    fun setStepLength(meters: Float) {
        _userPreferences.value = _userPreferences.value.copy(pdrStepLengthMeters = meters)
        stepDetectorManager.stepLengthMeters = meters
    }

    // --- Waypoint Operations ---
    fun addWaypointAt(
        name: String,
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        description: String = "",
        colorArgb: Int = 0xFFFF5722.toInt()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val wp = WaypointEntity(
                name = name,
                latitude = latitude,
                longitude = longitude,
                altitudeMeters = altitude,
                description = description,
                colorArgb = colorArgb
            )
            repository.addWaypoint(wp)
        }
    }

    fun selectWaypoint(waypoint: WaypointEntity?) {
        _selectedWaypoint.value = waypoint
        if (waypoint != null) {
            // Tapping an existing waypoint clears temporary candidate point
            _candidatePoint.value = null
            if (_activeMapTool.value == ActiveMapTool.PLACE_WAYPOINT_PENDING) {
                _activeMapTool.value = ActiveMapTool.NONE
            }
        }
    }

    fun clearCandidatePoint() {
        _candidatePoint.value = null
        if (_activeMapTool.value == ActiveMapTool.PLACE_WAYPOINT_PENDING) {
            _activeMapTool.value = ActiveMapTool.NONE
        }
    }

    fun clearSelectedWaypoint() {
        _selectedWaypoint.value = null
    }

    /**
     * Map tap router respecting active tool hierarchy:
     * - If RULER is active, taps go to its handler.
     * - When active tool is NONE or PLACE_WAYPOINT_PENDING, tapping on empty map places/moves
     *   a temporary candidate marker (without Room write) and sets state to PLACE_WAYPOINT_PENDING.
     */
    fun onMapTapped(tappedGeo: GeoPoint) {
        if (_rulerState.value.isActive || _activeMapTool.value == ActiveMapTool.RULER) {
            return
        }

        _candidatePoint.value = tappedGeo
        _selectedWaypoint.value = null
        _activeMapTool.value = ActiveMapTool.PLACE_WAYPOINT_PENDING
    }

    fun saveCandidatePoint(
        name: String,
        description: String = "",
        colorArgb: Int = 0xFFFF5722.toInt()
    ) {
        val candidate = _candidatePoint.value ?: return
        addWaypointAt(
            name = name,
            latitude = candidate.latitude,
            longitude = candidate.longitude,
            altitude = candidate.altitude,
            description = description,
            colorArgb = colorArgb
        )
        clearCandidatePoint()
    }

    fun deleteWaypoint(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteWaypoint(id)
            if (_selectedWaypoint.value?.id == id) {
                _selectedWaypoint.value = null
            }
        }
    }

    // --- Navigation Tools: Ruler ---
    fun toggleRuler() {
        val cur = _rulerState.value
        if (cur.isActive) {
            _rulerState.value = RulerState(isActive = false)
            if (_activeMapTool.value == ActiveMapTool.RULER) {
                _activeMapTool.value = ActiveMapTool.NONE
            }
        } else {
            _candidatePoint.value = null
            _selectedWaypoint.value = null
            val start = gpsLocation.value ?: _mapCenter.value
            _rulerState.value = RulerState(isActive = true, startPoint = start, endPoint = start)
            _activeMapTool.value = ActiveMapTool.RULER
        }
    }

    fun updateRulerPoints(start: GeoPoint, end: GeoPoint) {
        _rulerState.value = RulerState.calculate(start, end)
    }

    /**
     * Purges cached tiles for the active layer. Use when a provider has been serving
     * "Access blocked" placeholder tiles, which otherwise persist in the disk cache.
     */
    fun clearTileCacheForActiveSource() {
        viewModelScope.launch(Dispatchers.IO) {
            tileManager.clearDiskCache(_activeTileSource.value.id)
        }
    }

    // --- Recorded tracks management ---

    fun toggleRawTracks() {
        _showRawTracks.value = !_showRawTracks.value
        // Re-apply filtering to everything currently displayed.
        viewModelScope.launch(Dispatchers.IO) {
            val refreshed = _visibleTrackIds.value.associateWith { id ->
                TrackFilter.filter(repository.getTrackPointsSync(id), !_showRawTracks.value).points
            }
            _visibleTrackPoints.value = refreshed
        }
    }

    /** Ids of finished tracks the user chose to display on the map. */
    private val _visibleTrackIds = MutableStateFlow<Set<Long>>(emptySet())
    val visibleTrackIds: StateFlow<Set<Long>> = _visibleTrackIds.asStateFlow()

    /** Points of every currently visible track, keyed by track id. */
    private val _visibleTrackPoints = MutableStateFlow<Map<Long, List<TrackPointEntity>>>(emptyMap())
    val visibleTrackPoints: StateFlow<Map<Long, List<TrackPointEntity>>> = _visibleTrackPoints.asStateFlow()

    fun toggleTrackVisibility(track: TrackEntity) {
        val current = _visibleTrackIds.value
        if (current.contains(track.id)) {
            _visibleTrackIds.value = current - track.id
            _visibleTrackPoints.value = _visibleTrackPoints.value - track.id
        } else {
            _visibleTrackIds.value = current + track.id
            viewModelScope.launch(Dispatchers.IO) {
                val pts = TrackFilter.filter(
                    repository.getTrackPointsSync(track.id),
                    !_showRawTracks.value
                ).points
                _visibleTrackPoints.value = _visibleTrackPoints.value + (track.id to pts)
            }
        }
    }

    /** Centres the map on the first recorded point of a track. */
    fun centerOnTrack(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val pts = TrackFilter.filter(
                repository.getTrackPointsSync(track.id),
                !_showRawTracks.value
            ).points
            val first = pts.firstOrNull() ?: return@launch
            _visibleTrackIds.value = _visibleTrackIds.value + track.id
            _visibleTrackPoints.value = _visibleTrackPoints.value + (track.id to pts)
            _mapCenter.value = GeoPoint(first.latitude, first.longitude, first.altitudeMeters)
        }
    }

    fun renameTrack(track: TrackEntity, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateTrack(track.copy(name = newName))
        }
    }

    fun deleteTrack(track: TrackEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTrack(track.id)
            _visibleTrackIds.value = _visibleTrackIds.value - track.id
            _visibleTrackPoints.value = _visibleTrackPoints.value - track.id
        }
    }

    /**
     * Writes a track to a GPX file in the app cache and returns it, so the UI can hand it to
     * a share intent. Returns null when the track has no recorded points.
     */
    suspend fun exportTrackToGpxFile(track: TrackEntity): java.io.File? = withContext(Dispatchers.IO) {
        val points = TrackFilter.filter(
            repository.getTrackPointsSync(track.id),
            !_showRawTracks.value
        ).points
        if (points.isEmpty()) return@withContext null

        val xml = com.example.data.io.GpxKmlService.exportTrackGpx(track, points)
        val safeName = track.name.replace(Regex("[^A-Za-z0-9А-Яа-яЇїІіЄєҐґ_\\-]"), "_")
        val dir = java.io.File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, "$safeName.gpx")
        file.writeText(xml)
        file
    }

    // --- Navigation Tools: Triangulation (rays by azimuth + optional distance) ---
    private val _triangulationState = MutableStateFlow(TriangulationState())
    val triangulationState: StateFlow<TriangulationState> = _triangulationState.asStateFlow()

    fun startTriangulation() {
        _triangulationState.value = TriangulationState(isActive = true)
    }

    fun cancelTriangulation() {
        _triangulationState.value = TriangulationState(isActive = false)
    }

    /**
     * Adds a ray from [origin] along [azimuthDeg]. A null/blank [lengthMeters] means an
     * open-ended ray; a value makes it a fixed-length segment. Keeps at most two rays and
     * recomputes their intersection as soon as two are present.
     */
    fun addTriangulationRay(origin: WaypointEntity, azimuthDeg: Double, lengthMeters: Double?) {
        val current = _triangulationState.value
        val rays = (current.rays + AzimuthRay(origin, azimuthDeg, lengthMeters)).takeLast(2)

        val result = if (rays.size == 2) {
            GeodesyEngine.intersectTwoAzimuths(
                rays[0].originPoint, rays[0].azimuthDeg,
                rays[1].originPoint, rays[1].azimuthDeg
            )
        } else null

        _triangulationState.value = current.copy(isActive = true, rays = rays, result = result)

        // Jump the map to the crossing point so the user immediately sees it.
        (result as? IntersectionResult.Success)?.let { setMapCenter(it.intersectionPoint) }
    }

    fun removeTriangulationRay(index: Int) {
        val current = _triangulationState.value
        if (index !in current.rays.indices) return
        val rays = current.rays.toMutableList().also { it.removeAt(index) }
        _triangulationState.value = current.copy(rays = rays, result = null)
    }

    /** Saves the computed crossing point as a real waypoint. */
    fun saveTriangulationPoint(name: String) {
        val pt = _triangulationState.value.intersectionPoint() ?: return
        addWaypointAt(
            name = name,
            latitude = pt.latitude,
            longitude = pt.longitude,
            altitude = pt.altitude,
            description = "Пересечение азимутов (триангуляция)",
            colorArgb = 0xFFD32F2F.toInt()
        )
        _triangulationState.value = TriangulationState(isActive = false)
    }

    /** Saves the far end of a fixed-length segment as a waypoint. */
    fun saveRayEndpoint(ray: AzimuthRay, name: String) {
        val len = ray.lengthMeters ?: return
        val end = GeodesyEngine.destinationPoint(ray.originPoint, len, ray.azimuthDeg)
        addWaypointAt(
            name = name,
            latitude = end.latitude,
            longitude = end.longitude,
            altitude = null,
            description = "Отложено от '${ray.origin.name}': Аз ${"%.1f".format(ray.azimuthDeg)}°, ${"%.0f".format(len)} м",
            colorArgb = 0xFFFFA726.toInt()
        )
    }

    // --- Waypoint editing ---
    fun updateWaypointDetails(waypoint: WaypointEntity, newName: String, newDescription: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateWaypoint(
                waypoint.copy(name = newName, description = newDescription)
            )
        }
    }

    fun deleteRoute(route: RouteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteRoute(route.id)
        }
    }

    fun setRouteName(name: String) {
        _routeBuilderState.value = _routeBuilderState.value.copy(routeName = name)
    }

    // --- Navigation Tools: Route Builder by Points ---
    fun startRouteBuilder(initialName: String = "Маршрут") {
        _routeBuilderState.value = RouteBuilderState(isActive = true, routeName = initialName)
    }

    fun toggleWaypointInRoute(wp: WaypointEntity) {
        val curList = _routeBuilderState.value.selectedWaypoints.toMutableList()
        if (curList.any { it.id == wp.id }) {
            curList.removeAll { it.id == wp.id }
        } else {
            curList.add(wp)
        }

        val legs = mutableListOf<RouteLeg>()
        var totalDist = 0.0
        for (i in 0 until curList.size - 1) {
            val leg = RouteLeg.create(i + 1, curList[i], curList[i + 1])
            legs.add(leg)
            totalDist += leg.distanceMeters
        }

        _routeBuilderState.value = _routeBuilderState.value.copy(
            selectedWaypoints = curList,
            legs = legs,
            totalDistanceMeters = totalDist
        )
    }

    fun saveCurrentRoute() {
        val state = _routeBuilderState.value
        if (state.selectedWaypoints.size >= 2) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.createRouteFromWaypoints(state.routeName, state.selectedWaypoints)
                // Keep the builder panel open and clear only the current selection, so the
                // freshly saved route shows up in the saved-routes list right below.
                _routeBuilderState.value = RouteBuilderState(
                    isActive = true,
                    routeName = "Маршрут ${System.currentTimeMillis() % 10000}"
                )
            }
        }
    }

    /**
     * Loads a previously saved route back into the builder (and therefore back onto the map)
     * by resolving its stored waypoint ids against the current waypoint list.
     */
    fun loadRouteIntoBuilder(route: RouteEntity) {
        viewModelScope.launch {
            val all = waypoints.value
            val ordered = route.parseWaypointIds().mapNotNull { id -> all.firstOrNull { it.id == id } }
            if (ordered.size < 2) return@launch

            val legs = mutableListOf<RouteLeg>()
            var totalDist = 0.0
            for (i in 0 until ordered.size - 1) {
                val leg = RouteLeg.create(i + 1, ordered[i], ordered[i + 1])
                legs.add(leg)
                totalDist += leg.distanceMeters
            }

            _routeBuilderState.value = RouteBuilderState(
                isActive = true,
                routeName = route.name,
                selectedWaypoints = ordered,
                legs = legs,
                totalDistanceMeters = totalDist
            )
        }
    }

    fun cancelRouteBuilder() {
        _routeBuilderState.value = RouteBuilderState(isActive = false)
    }

    // --- Track Recording (Foreground Service + PDR) ---
    fun startTrackRecording(name: String = "Трек ${System.currentTimeMillis() % 10000}") {
        viewModelScope.launch(Dispatchers.IO) {
            val trackId = repository.startNewTrack(name)
            TrackingService.startTracking(getApplication(), trackId, name)
        }
    }

    fun stopTrackRecording() {
        TrackingService.stopTracking(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            repository.stopCurrentTrack()
        }
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    // --- GPX & KML Import / Export ---
    suspend fun exportGpx(): String = withContext(Dispatchers.IO) {
        val wps = repository.allWaypoints.first()
        val routes = repository.allRoutes.first()
        val routesWithPoints = routes.map { r ->
            val pts = repository.getRoutePoints(r)
            Pair(r, pts)
        }
        com.example.data.io.GpxKmlService.exportGpx(wps, routesWithPoints)
    }

    suspend fun exportToGpx(file: File): Unit = withContext(Dispatchers.IO) {
        val xml = exportGpx()
        file.writeText(xml, Charsets.UTF_8)
    }

    suspend fun exportKml(): String = withContext(Dispatchers.IO) {
        val wps = repository.allWaypoints.first()
        val routes = repository.allRoutes.first()
        val routesWithPoints = routes.map { r ->
            val pts = repository.getRoutePoints(r)
            Pair(r, pts)
        }
        com.example.data.io.GpxKmlService.exportKml(wps, routesWithPoints)
    }

    suspend fun exportToKml(file: File): Unit = withContext(Dispatchers.IO) {
        val xml = exportKml()
        file.writeText(xml, Charsets.UTF_8)
    }

    suspend fun importNavigationData(inputStream: java.io.InputStream, isKml: Boolean): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val data = if (isKml) {
            com.example.data.io.GpxKmlService.importKml(inputStream)
        } else {
            com.example.data.io.GpxKmlService.importGpx(inputStream)
        }

        var wpCount = 0
        var rteCount = 0

        for (wp in data.waypoints) {
            repository.addWaypoint(wp)
            wpCount++
        }

        for ((name, pts) in data.routes) {
            val savedEntities = pts.mapIndexed { idx, p ->
                val entity = WaypointEntity(
                    name = "$name - T${idx + 1}",
                    latitude = p.latitude,
                    longitude = p.longitude,
                    altitudeMeters = p.altitude
                )
                val id = repository.addWaypoint(entity)
                entity.copy(id = id)
            }
            if (savedEntities.size >= 2) {
                repository.createRouteFromWaypoints(name, savedEntities)
                rteCount++
            }
        }

        Pair(wpCount, rteCount)
    }

    // --- Offline Pack (.orntpack) Packing ---
    suspend fun packCurrentCache(outputFile: java.io.File): Int = withContext(Dispatchers.IO) {
        tileManager.packCacheToOrntpack(outputFile)
    }
}
