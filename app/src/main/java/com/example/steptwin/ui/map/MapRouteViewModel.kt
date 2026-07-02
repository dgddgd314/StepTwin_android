package com.example.steptwin.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.domain.agent.AgentReport
import com.example.steptwin.domain.agent.RoutePlanningAgent
import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.NamedPlace
import com.example.steptwin.domain.preview.PlaceSuggestion
import com.example.steptwin.domain.preview.WalkRoute
import com.example.steptwin.domain.preview.WalkRouteResult
import com.example.steptwin.domain.repository.RoutePreviewRepository
import com.example.steptwin.domain.repository.TugRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapRouteViewModel @Inject constructor(
    private val previewRepository: RoutePreviewRepository,
    private val tugRepository: TugRepository,
    private val agent: RoutePlanningAgent,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapRouteUiState())
    val uiState: StateFlow<MapRouteUiState> = _uiState.asStateFlow()

    // 선택된 장소(자동완성에서 고른 좌표). 없으면 검색 시 텍스트를 지오코딩한다.
    private var selectedStart: NamedPlace? = null
    private var selectedEnd: NamedPlace? = null

    private var startSuggestJob: Job? = null
    private var endSuggestJob: Job? = null

    fun updateStartQuery(text: String) {
        selectedStart = null
        _uiState.update {
            it.copy(startQuery = text, activeField = ActiveField.START)
        }
        startSuggestJob?.cancel()
        if (text.isBlank()) {
            _uiState.update { it.copy(startSuggestions = emptyList()) }
            return
        }
        startSuggestJob = viewModelScope.launch {
            delay(SuggestDebounceMillis)
            val list = previewRepository.suggest(text)
            _uiState.update { it.copy(startSuggestions = list) }
        }
    }

    fun updateEndQuery(text: String) {
        selectedEnd = null
        _uiState.update {
            it.copy(endQuery = text, activeField = ActiveField.END)
        }
        endSuggestJob?.cancel()
        if (text.isBlank()) {
            _uiState.update { it.copy(endSuggestions = emptyList()) }
            return
        }
        endSuggestJob = viewModelScope.launch {
            delay(SuggestDebounceMillis)
            val list = previewRepository.suggest(text)
            _uiState.update { it.copy(endSuggestions = list) }
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

    /** 출발/도착(선택 또는 지오코딩)으로 경로를 요청한다. */
    fun search() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = null,
                    isError = false,
                    activeField = ActiveField.NONE,
                    startSuggestions = emptyList(),
                    endSuggestions = emptyList(),
                )
            }

            val healthy = previewRepository.checkHealth()
            val start = selectedStart ?: previewRepository.geocode(state.startQuery)
            val end = selectedEnd ?: previewRepository.geocode(state.endQuery)

            if (start == null || end == null) {
                val missing = when {
                    start == null && end == null -> "출발지와 도착지"
                    start == null -> "출발지"
                    else -> "도착지"
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        serverHealthy = healthy,
                        route = null,
                        resolvedStart = start?.point ?: it.resolvedStart,
                        resolvedEnd = end?.point ?: it.resolvedEnd,
                        statusMessage = "$missing 장소를 찾지 못했습니다. 검색어를 확인하세요.",
                        isError = true,
                    )
                }
                return@launch
            }

            val result = previewRepository.loadWalkRoute(start, end, tugRepository.latestWeights.value)
            _uiState.update { s ->
                val base = s.copy(
                    isLoading = false,
                    serverHealthy = healthy,
                    resolvedStart = start.point,
                    resolvedEnd = end.point,
                )
                when (result) {
                    is WalkRouteResult.Success -> base.copy(
                        route = result.route,
                        statusMessage = null,
                        isError = false,
                    )
                    WalkRouteResult.NoRoute -> base.copy(
                        route = null,
                        statusMessage = "경로를 찾지 못했습니다. 보행 네트워크가 아직 개선 중일 수 있습니다.",
                        isError = false,
                    )
                    WalkRouteResult.InvalidRequest -> base.copy(
                        route = null,
                        statusMessage = "요청 형식 오류(422). 좌표/선호값을 확인하세요.",
                        isError = true,
                    )
                    WalkRouteResult.BackendError -> base.copy(
                        route = null,
                        statusMessage = "서버 오류입니다. 잠시 후 다시 시도하세요.",
                        isError = true,
                    )
                    is WalkRouteResult.Failure -> base.copy(
                        route = null,
                        statusMessage = "서버에 연결할 수 없습니다. 서버 주소/네트워크를 확인하세요.",
                        isError = true,
                    )
                }
            }
        }
    }

    /** AI 에이전트 워크플로우 실행(프로필→선호도→경로→설명). */
    fun runAgent() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isAgentRunning = true) }
            val start = selectedStart ?: previewRepository.geocode(state.startQuery)
            val end = selectedEnd ?: previewRepository.geocode(state.endQuery)
            if (start == null || end == null) {
                _uiState.update {
                    it.copy(
                        isAgentRunning = false,
                        statusMessage = "출발지/도착지를 먼저 확인하세요.",
                        isError = true,
                    )
                }
                return@launch
            }
            val report = agent.plan(start, end)
            _uiState.update {
                it.copy(
                    isAgentRunning = false,
                    agentReport = report,
                    resolvedStart = start.point,
                    resolvedEnd = end.point,
                )
            }
        }
    }

    private companion object {
        const val SuggestDebounceMillis = 250L
    }
}

enum class ActiveField { NONE, START, END }

data class MapRouteUiState(
    val startQuery: String = "회기역",
    val endQuery: String = "경희대학교병원",
    val startSuggestions: List<PlaceSuggestion> = emptyList(),
    val endSuggestions: List<PlaceSuggestion> = emptyList(),
    val activeField: ActiveField = ActiveField.NONE,
    val isLoading: Boolean = false,
    /** null = 아직 확인 전, true/false = health 결과. */
    val serverHealthy: Boolean? = null,
    val route: WalkRoute? = null,
    /** 출발/도착 좌표(경로가 없어도 마커 표시용). */
    val resolvedStart: GeoPoint? = null,
    val resolvedEnd: GeoPoint? = null,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    val isAgentRunning: Boolean = false,
    val agentReport: AgentReport? = null,
)
