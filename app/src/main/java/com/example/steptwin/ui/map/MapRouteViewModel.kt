package com.example.steptwin.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.data.favorites.FavoritesStore
import com.example.steptwin.domain.favorites.FavoriteRoute
import com.example.steptwin.domain.nav.NavMode
import com.example.steptwin.domain.nav.NavPath
import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.NamedPlace
import com.example.steptwin.domain.preview.PlaceSuggestion
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.RoutePreviewResult
import com.example.steptwin.domain.repository.RoutePreviewRepository
import com.example.steptwin.domain.repository.TugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RoutePanelState { Closed, Open }

enum class NavigationState { Idle, RoutePreviewShown, NavigatingPlaceholder }

@HiltViewModel
class MapRouteViewModel @Inject constructor(
    private val previewRepository: RoutePreviewRepository,
    private val tugRepository: TugRepository,
    private val favoritesStore: FavoritesStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapRouteUiState())
    val uiState: StateFlow<MapRouteUiState> = _uiState.asStateFlow()

    private var selectedStart: NamedPlace? = null
    private var selectedEnd: NamedPlace? = null
    private var lastOrigin: NamedPlace? = null
    private var lastDestination: NamedPlace? = null
    private var startSuggestJob: Job? = null
    private var endSuggestJob: Job? = null

    // ---- 내비게이션 세션 ----
    private var navPath: NavPath? = null
    private val announcedCues = mutableSetOf<String>()
    private val _ttsEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val ttsEvents: SharedFlow<String> = _ttsEvents.asSharedFlow()

    init {
        // "내 보행정보"에서 고른 즐겨찾기를 이어받아 자동으로 길찾기 실행.
        viewModelScope.launch {
            favoritesStore.pendingRoute.collect { pending ->
                if (pending != null) {
                    applyFavorite(pending)
                    favoritesStore.consumePending()
                }
            }
        }
    }

    // ---- 패널 열기/닫기 ----
    fun openPanel() = _uiState.update { it.copy(panelState = RoutePanelState.Open) }
    fun closePanel() = _uiState.update {
        it.copy(panelState = RoutePanelState.Closed, activeField = ActiveField.NONE)
    }

    // ---- 자동완성 ----
    fun updateStartQuery(text: String) {
        selectedStart = null
        _uiState.update { it.copy(startQuery = text, activeField = ActiveField.START) }
        startSuggestJob?.cancel()
        if (text.isBlank()) {
            _uiState.update { it.copy(startSuggestions = emptyList()) }
            return
        }
        startSuggestJob = viewModelScope.launch {
            delay(SuggestDebounceMillis)
            _uiState.update { it.copy(startSuggestions = previewRepository.suggest(text)) }
        }
    }

    fun updateEndQuery(text: String) {
        selectedEnd = null
        _uiState.update { it.copy(endQuery = text, activeField = ActiveField.END) }
        endSuggestJob?.cancel()
        if (text.isBlank()) {
            _uiState.update { it.copy(endSuggestions = emptyList()) }
            return
        }
        endSuggestJob = viewModelScope.launch {
            delay(SuggestDebounceMillis)
            _uiState.update { it.copy(endSuggestions = previewRepository.suggest(text)) }
        }
    }

    fun selectStart(suggestion: PlaceSuggestion) {
        selectedStart = suggestion.toNamedPlace()
        startSuggestJob?.cancel()
        _uiState.update {
            it.copy(
                startQuery = suggestion.name,
                startSuggestions = emptyList(),
                resolvedStart = suggestion.point,
                activeField = ActiveField.NONE,
            )
        }
    }

    fun selectEnd(suggestion: PlaceSuggestion) {
        selectedEnd = suggestion.toNamedPlace()
        endSuggestJob?.cancel()
        _uiState.update {
            it.copy(
                endQuery = suggestion.name,
                endSuggestions = emptyList(),
                resolvedEnd = suggestion.point,
                activeField = ActiveField.NONE,
            )
        }
    }

    // ---- 길찾기 (routes/preview) ----
    fun search() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = null,
                    isError = false,
                    favoriteSaved = false,
                    activeField = ActiveField.NONE,
                    startSuggestions = emptyList(),
                    endSuggestions = emptyList(),
                )
            }

            val healthy = previewRepository.checkHealth()
            val origin = selectedStart ?: previewRepository.geocode(state.startQuery)
            val destination = selectedEnd ?: previewRepository.geocode(state.endQuery)
            lastOrigin = origin
            lastDestination = destination

            if (origin == null || destination == null) {
                val missing = when {
                    origin == null && destination == null -> "출발지와 도착지"
                    origin == null -> "출발지"
                    else -> "도착지"
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverHealthy = healthy,
                        preview = null,
                        navState = NavigationState.Idle,
                        resolvedStart = origin?.point ?: it.resolvedStart,
                        resolvedEnd = destination?.point ?: it.resolvedEnd,
                        statusMessage = "$missing 장소를 찾지 못했습니다. 검색어를 확인하세요.",
                        isError = true,
                    )
                }
                return@launch
            }

            val result = previewRepository.loadPreview(origin, destination, tugRepository.latestWeights.value)
            _uiState.update { s ->
                val base = s.copy(
                    isLoading = false,
                    serverHealthy = healthy,
                    resolvedStart = origin.point,
                    resolvedEnd = destination.point,
                )
                when (result) {
                    is RoutePreviewResult.Success -> {
                        val hasSegments = result.preview.segments.isNotEmpty()
                        base.copy(
                            preview = result.preview,
                            // 성공 시 패널은 접고 경로/길안내 바를 보여준다(지도·경로 유지).
                            panelState = RoutePanelState.Closed,
                            navState = if (hasSegments) {
                                NavigationState.RoutePreviewShown
                            } else {
                                NavigationState.Idle
                            },
                            statusMessage = if (hasSegments) null else "표시할 경로 구간이 없습니다.",
                            isError = false,
                        )
                    }
                    RoutePreviewResult.NoRoute -> base.copy(
                        preview = null,
                        navState = NavigationState.Idle,
                        statusMessage = "연결 가능한 경로를 찾지 못했습니다(404). 다른 출발/도착지를 시도하세요.",
                        isError = false,
                    )
                    RoutePreviewResult.InvalidRequest -> base.copy(
                        preview = null,
                        navState = NavigationState.Idle,
                        statusMessage = "요청 형식/좌표 오류(422).",
                        isError = true,
                    )
                    RoutePreviewResult.BackendError -> base.copy(
                        preview = null,
                        navState = NavigationState.Idle,
                        statusMessage = "서버 오류(500/503)입니다. 잠시 후 다시 시도하세요.",
                        isError = true,
                    )
                    is RoutePreviewResult.Failure -> base.copy(
                        preview = null,
                        navState = NavigationState.Idle,
                        statusMessage = "서버에 연결할 수 없습니다. 서버 주소/네트워크를 확인하세요.",
                        isError = true,
                    )
                }
            }
        }
    }

    // ---- 즐겨찾기 ----
    fun saveFavorite() {
        val origin = lastOrigin
        val destination = lastDestination
        if (origin == null || destination == null) return
        favoritesStore.add(FavoriteRoute.of(origin, destination))
        _uiState.update { it.copy(favoriteSaved = true) }
    }

    /** 즐겨찾기에서 선택한 경로를 채우고 바로 길찾기를 실행한다. */
    private fun applyFavorite(favorite: FavoriteRoute) {
        selectedStart = favorite.originPlace()
        selectedEnd = favorite.destPlace()
        _uiState.update {
            it.copy(
                startQuery = favorite.originName,
                endQuery = favorite.destName,
                startSuggestions = emptyList(),
                endSuggestions = emptyList(),
                resolvedStart = favorite.originPlace().point,
                resolvedEnd = favorite.destPlace().point,
                panelState = RoutePanelState.Closed,
                activeField = ActiveField.NONE,
            )
        }
        search()
    }

    // ---- 길안내(GPS/모의) ----
    fun startNavigation() {
        val preview = _uiState.value.preview ?: return
        val path = NavPath.from(preview)
        navPath = path
        announcedCues.clear()
        _uiState.update {
            it.copy(
                navState = NavigationState.NavigatingPlaceholder,
                navMode = NavMode.Simulated,
                navProgress = 0f,
                navInstruction = "경로를 따라 이동하세요.",
                userLocation = path?.pointAt(0.0),
            )
        }
        if (path != null) advance(0.0)
    }

    fun stopNavigation() {
        navPath = null
        announcedCues.clear()
        _uiState.update {
            if (it.navState == NavigationState.NavigatingPlaceholder) {
                it.copy(
                    navState = NavigationState.RoutePreviewShown,
                    userLocation = null,
                    navProgress = 0f,
                )
            } else {
                it
            }
        }
    }

    fun setNavMode(mode: NavMode) = _uiState.update { it.copy(navMode = mode) }

    /** 모의 GPS: 슬라이더(0~1) 진행. */
    fun updateSimulatedProgress(fraction: Float) {
        val path = navPath ?: return
        val f = fraction.coerceIn(0f, 1f)
        _uiState.update { it.copy(navProgress = f) }
        advance(f * path.totalDistance)
    }

    /** 실제 GPS: 위치를 경로에 투영해 진행. 좌표는 서버로 전송하지 않는다. */
    fun onRealLocation(latitude: Double, longitude: Double) {
        if (_uiState.value.navMode != NavMode.RealGps) return
        val path = navPath ?: return
        val dist = path.project(GeoPoint(latitude, longitude))
        val f = if (path.totalDistance > 0) (dist / path.totalDistance).toFloat().coerceIn(0f, 1f) else 0f
        _uiState.update { it.copy(navProgress = f) }
        advance(dist)
    }

    /** 진행거리(m)에서 아직 발화하지 않은 안내 큐를 실행하고 위치/배너를 갱신. */
    private fun advance(distance: Double) {
        val path = navPath ?: return
        val fired = path.cues
            .filter { it.triggerDistance <= distance && it.id !in announcedCues }
            .sortedBy { it.triggerDistance }
        for (cue in fired) {
            announcedCues += cue.id
            _ttsEvents.tryEmit(cue.speak)
        }
        val banner = fired.lastOrNull()?.banner ?: _uiState.value.navInstruction
        _uiState.update { it.copy(userLocation = path.pointAt(distance), navInstruction = banner) }
    }

    private companion object {
        const val SuggestDebounceMillis = 250L
    }
}

data class MapRouteUiState(
    val panelState: RoutePanelState = RoutePanelState.Closed,
    val navState: NavigationState = NavigationState.Idle,
    val startQuery: String = "서울역",
    val endQuery: String = "회기역",
    val startSuggestions: List<PlaceSuggestion> = emptyList(),
    val endSuggestions: List<PlaceSuggestion> = emptyList(),
    val activeField: ActiveField = ActiveField.NONE,
    val isLoading: Boolean = false,
    val serverHealthy: Boolean? = null,
    val preview: RoutePreview? = null,
    val resolvedStart: GeoPoint? = null,
    val resolvedEnd: GeoPoint? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val favoriteSaved: Boolean = false,
    val navMode: NavMode = NavMode.Simulated,
    val navProgress: Float = 0f,
    val navInstruction: String = "",
    val userLocation: GeoPoint? = null,
) {
    val canNavigate: Boolean = navState == NavigationState.RoutePreviewShown &&
        (preview?.segments?.isNotEmpty() == true)
}

enum class ActiveField { NONE, START, END }
