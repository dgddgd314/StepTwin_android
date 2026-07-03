package com.example.steptwin.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.steptwin.data.favorites.FavoritesStore
import com.example.steptwin.domain.assistant.ChatTurn
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
import com.example.steptwin.domain.repository.VoiceAssistantRepository
import com.example.steptwin.ui.common.Utterance
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
    private val assistantRepository: VoiceAssistantRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapRouteUiState())
    val uiState: StateFlow<MapRouteUiState> = _uiState.asStateFlow()

    private var selectedStart: NamedPlace? = null
    private var selectedEnd: NamedPlace? = null
    private var lastOrigin: NamedPlace? = null
    private var lastDestination: NamedPlace? = null
    private var startSuggestJob: Job? = null
    private var endSuggestJob: Job? = null

    // 기기 GPS 현재 위치(서버 전송 안 함). 음성 목적지 입력 시 출발지로 사용.
    private var currentGps: GeoPoint? = null

    // ---- 내비게이션 세션 ----
    private var navPath: NavPath? = null
    private val announcedCues = mutableSetOf<String>()
    private val _ttsEvents = MutableSharedFlow<Utterance>(extraBufferCapacity = 16)
    val ttsEvents: SharedFlow<Utterance> = _ttsEvents.asSharedFlow()

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
                val wasVoice = _uiState.value.awaitingDestination
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
                        // 음성 입력이 실패하면 모드를 끄고 검색 패널을 열어 직접 수정하게 한다.
                        awaitingDestination = false,
                        panelState = if (wasVoice) RoutePanelState.Open else it.panelState,
                    )
                }
                if (wasVoice) {
                    _ttsEvents.tryEmit(
                        Utterance("$missing 을(를) 찾지 못했어요. 검색창에서 다시 확인해 주세요.", natural = true),
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
                    // 음성 목적지 입력이 결과에 도달하면 해당 모드를 종료.
                    awaitingDestination = false,
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
            // 내비 안내는 즉시성·오프라인·비용 때문에 기기 TTS.
            _ttsEvents.tryEmit(Utterance(cue.speak, natural = false))
        }
        val banner = fired.lastOrNull()?.banner ?: _uiState.value.navInstruction
        _uiState.update { it.copy(userLocation = path.pointAt(distance), navInstruction = banner) }
    }

    // ---- 음성 목적지 입력(홈 '전화 걸기'에서 진입) ----
    /** 화면에서 받은 기기 GPS 위치를 저장(출발지로 사용, 서버 전송 없음). */
    fun updateCurrentLocation(latitude: Double, longitude: Double) {
        currentGps = GeoPoint(latitude, longitude)
    }

    /** 목적지를 말로 받도록 대기 상태로 전환하고 안내 음성을 낸다. */
    fun startVoiceDestination() {
        chatHistory.clear()
        val prompt = "어디로 갈까요? 도착지 이름만 말씀해 주세요."
        _uiState.update {
            it.copy(
                awaitingDestination = true,
                assistantActive = true,
                assistantListening = false,
                assistantThinking = false,
                assistantHasKey = assistantRepository.hasApiKey,
                assistantCaption = prompt,
            )
        }
        _ttsEvents.tryEmit(Utterance(prompt, natural = true))
    }

    fun cancelVoiceDestination() = _uiState.update {
        it.copy(awaitingDestination = false, assistantActive = false, assistantListening = false)
    }

    /**
     * 음성 발화에서 출발지/도착지를 뽑아 길찾기를 실행한다.
     * "A부터/에서 B까지"처럼 출발지를 말하면 그대로, 없으면 GPS 현위치를 출발지로 쓴다.
     */
    private fun handleDestinationSpeech(text: String) {
        val (origin, dest) = parseRoute(text)
        selectedEnd = null

        if (origin != null) {
            // 출발지도 말로 지정 → 지오코딩으로 찾도록 검색어만 세팅.
            selectedStart = null
            _uiState.update {
                it.copy(
                    startQuery = origin,
                    endQuery = dest,
                    resolvedStart = null,
                    assistantCaption = "‘$origin’에서 ‘$dest’까지 길을 찾아볼게요.",
                    assistantListening = false,
                )
            }
            _ttsEvents.tryEmit(
                Utterance("$origin 에서 $dest 까지 길을 찾아볼게요. 잠시만요.", natural = true),
            )
        } else {
            // 출발지 표현 없음 → GPS 현재 위치(있으면)로.
            val gps = currentGps
            if (gps != null) {
                selectedStart = NamedPlace("현재 위치", gps)
                _uiState.update { it.copy(startQuery = "현재 위치", resolvedStart = gps) }
            }
            _uiState.update {
                it.copy(
                    endQuery = dest,
                    assistantCaption = "‘$dest’(으)로 길을 찾아볼게요.",
                    assistantListening = false,
                )
            }
            _ttsEvents.tryEmit(
                Utterance("$dest. 지금 위치에서 길을 찾아볼게요. 잠시만요.", natural = true),
            )
        }
        search()
    }

    /**
     * 발화에서 (출발지, 도착지)를 추출. 출발지 표현(부터/에서)이 없으면 출발지는 null.
     * 뒤에 붙는 이동 의도 표현과 조사만 제거하고, 종로·을지로처럼 '로'로 끝나는 지명은 지킨다.
     */
    private fun parseRoute(raw: String): Pair<String?, String> {
        val core = stripIntentTail(raw)
        // '여기/현위치'류를 뜻하는 출발지 → GPS 로(지오코딩하지 않음).
        val hereWords = setOf(
            "여기", "여기서", "여기에서", "현재위치", "현재 위치", "현위치",
            "지금위치", "지금 위치", "내위치", "내 위치", "이곳", "이곳에서",
        )
        // "여기서 ○○까지"처럼 lone '서' 접미 케이스 먼저 처리.
        for (h in listOf("여기서부터", "여기에서부터", "여기서", "여기에서", "이곳에서")) {
            if (core.startsWith(h)) {
                val dest = stripDestTail(core.removePrefix(h).trim())
                if (dest.isNotBlank()) return null to dest
            }
        }
        // 출발지 마커: "에서부터" > "부터" > "에서" 순으로 탐색.
        for (marker in listOf("에서부터", "부터", "에서")) {
            val idx = core.indexOf(marker)
            if (idx > 0) {
                val origin = core.substring(0, idx).trim()
                val dest = stripDestTail(core.substring(idx + marker.length).trim())
                if (dest.isBlank()) continue
                return if (origin.isBlank() || origin in hereWords) null to dest
                else origin to dest
            }
        }
        return null to stripDestTail(core).ifBlank { core }
    }

    /** 뒤쪽 이동 의도 표현(선행 조사 포함)만 제거. */
    private fun stripIntentTail(raw: String): String {
        var s = raw.trim()
        val tailPatterns = listOf(
            Regex("""(으로|로|에|까지|를|을)?\s*가고\s*싶어요?$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*가고\s*싶다$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*가고\s*파요?$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*갈래요?$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*가\s*주세요$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*가\s*줘요?$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*가자$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*가야\s*(돼요?|해요?|되)$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*데려다\s*줘요?$"""),
            Regex("""(으로|로|에|까지)?\s*안내\s*(해)?\s*(줘요?|주세요)$"""),
            Regex("""(으로|로|에|까지)?\s*안내$"""),
            Regex("""(으로|로|에|까지|를|을)?\s*찾아\s*줘요?$"""),
            Regex("""(으로|로|에|까지)?\s*가는\s*(길|방법)$"""),
            Regex("""\s*좀$"""),
        )
        var changed = true
        var guard = 0
        while (changed && guard++ < 8) {
            changed = false
            for (p in tailPatterns) {
                val next = p.replace(s, "").trim()
                if (next != s) {
                    s = next
                    changed = true
                }
            }
        }
        return s.ifBlank { raw.trim() }
    }

    /** 도착지 꼬리 조사 제거: '까지'만 안전하게 떼고, '로'로 끝나는 지명은 건드리지 않는다. */
    private fun stripDestTail(raw: String): String {
        var s = raw.trim()
        if (s.endsWith("까지")) s = s.removeSuffix("까지").trim()
        return s.ifBlank { raw.trim() }
    }

    // ---- 말벗(음성 양방향 대화) ----
    private val chatHistory = mutableListOf<ChatTurn>()

    fun toggleAssistant() {
        if (_uiState.value.assistantActive) {
            _uiState.update {
                it.copy(assistantActive = false, assistantListening = false, assistantThinking = false)
            }
            return
        }
        chatHistory.clear()
        val dest = lastDestination?.name ?: "목적지"
        val greeting = "안녕하세요. ${dest}까지 제가 말벗이 되어 안내해 드릴게요. 궁금하면 언제든 물어보세요."
        _uiState.update {
            it.copy(
                assistantActive = true,
                assistantThinking = false,
                assistantHasKey = assistantRepository.hasApiKey,
                assistantCaption = greeting,
            )
        }
        _ttsEvents.tryEmit(Utterance(greeting, natural = true))
    }

    fun setAssistantListening(listening: Boolean) =
        _uiState.update { it.copy(assistantListening = listening) }

    /** 마이크로 받은 사용자 발화 처리(안전어 → SOS, 그 외 → Claude/폴백). */
    fun onUserUtterance(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (SosWords.any { t.contains(it) }) { triggerSos(); return }
        if (_uiState.value.awaitingDestination) { handleDestinationSpeech(t); return }

        if (!assistantRepository.hasApiKey) {
            respond(keywordAnswer(t))
            return
        }
        chatHistory.add(ChatTurn("user", t))
        _uiState.update { it.copy(assistantCaption = "“$t”", assistantThinking = true) }
        viewModelScope.launch {
            val reply = runCatching {
                assistantRepository.reply(buildSystemPrompt(), chatHistory)
            }.getOrElse { keywordAnswer(t) }
            chatHistory.add(ChatTurn("assistant", reply))
            _uiState.update { it.copy(assistantThinking = false, assistantCaption = reply) }
            _ttsEvents.tryEmit(Utterance(reply, natural = true))
        }
    }

    private fun respond(text: String) {
        _uiState.update { it.copy(assistantThinking = false, assistantCaption = text) }
        _ttsEvents.tryEmit(Utterance(text, natural = true))
    }

    private fun triggerSos() {
        val msg = "긴급 도움 요청을 보냈어요. 보호자에게 곧 연락이 갈 거예요. 그 자리에 잠시 계세요."
        _uiState.update { it.copy(assistantThinking = false, assistantCaption = "🚨 $msg") }
        // 긴급 안내는 지연 없이 확실히 나가도록 기기 TTS.
        _ttsEvents.tryEmit(Utterance(msg, natural = false))
    }

    private fun keywordAnswer(text: String): String = when {
        text.contains("어디") -> "지금 '${currentInstr()}' 안내를 따라 가고 계세요."
        text.contains("남았") || text.contains("얼마") -> "목적지까지 약 ${remainingMeters()}미터 남았어요."
        text.contains("잘") || text.contains("맞") -> "네, 잘 가고 계세요. 걱정 마세요."
        text.contains("다시") -> currentInstr()
        text.contains("그만") || text.contains("종료") -> "네, 필요하면 다시 불러주세요."
        else -> "잘 못 들었어요. 아래 버튼으로 물어봐 주세요."
    }

    private fun currentInstr(): String =
        _uiState.value.navInstruction.ifBlank { "경로를 따라 이동" }

    private fun remainingMeters(): Int {
        val path = navPath ?: return 0
        return (path.totalDistance * (1f - _uiState.value.navProgress)).toInt().coerceAtLeast(0)
    }

    private fun buildSystemPrompt(): String {
        val origin = lastOrigin?.name ?: "현재 위치"
        val dest = lastDestination?.name ?: "목적지"
        val transit = _uiState.value.preview?.segments
            ?.mapNotNull { it.transit?.lineLabel }?.distinct()?.joinToString(", ").orEmpty()
        return "당신은 어르신과 대화하며 ${dest}까지 도보 길안내를 돕는 다정한 AI 말벗입니다. " +
            "존댓말로, 한 번에 한두 문장만, 짧고 쉽게 말하세요. " +
            "[경로] 출발 '$origin' → 도착 '$dest'. 현재 안내: '${currentInstr()}'. " +
            "목적지까지 약 ${remainingMeters()}미터 남음. " +
            (if (transit.isNotBlank()) "이용 대중교통: $transit. " else "") +
            "[사용자] ${userProfileLine()} " +
            "지도에 없는 실제 지형(가게 이름, 신호등 개수 등)은 지어내지 말고, 모르면 다정하게 모른다고 하세요. " +
            "다치거나 위험하다고 하면 침착히 안심시키고 보호자에게 연락하겠다고 하세요."
    }

    /** 챗봇에 전달할 사용자 보행지수 요약(0~100, 높을수록 양호). 앱이 아는 유일한 개인 특성. */
    private fun userProfileLine(): String {
        val w = tugRepository.latestWeights.value
        val untested = w == null ||
            (w.speedWeight <= 0f && w.turnWeight <= 0f && w.strengthWeight <= 0f)
        if (untested) {
            return "아직 보행검사를 하지 않아 보행지수 정보가 없는 어르신입니다. 일반적인 주의로 안내하세요."
        }
        fun idx(v: Float) = 100 - (v.coerceIn(0f, 1f) * 100).toInt()
        return "이 어르신의 보행지수(높을수록 양호) — 속도지수 ${idx(w.speedWeight)}, " +
            "회전지수 ${idx(w.turnWeight)}, 근력지수 ${idx(w.strengthWeight)}. " +
            "지수가 낮은 항목은 특히 천천히·자주 안심시키며 배려해 안내하세요."
    }

    private companion object {
        const val SuggestDebounceMillis = 250L
        val SosWords = listOf("도와", "살려", "아파", "넘어", "다쳐", "다쳤", "응급")
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
    val assistantActive: Boolean = false,
    val assistantListening: Boolean = false,
    val assistantThinking: Boolean = false,
    val assistantHasKey: Boolean = false,
    val assistantCaption: String = "",
    /** 홈 '전화 걸기'로 진입한 음성 목적지 입력 모드. */
    val awaitingDestination: Boolean = false,
) {
    val canNavigate: Boolean = navState == NavigationState.RoutePreviewShown &&
        (preview?.segments?.isNotEmpty() == true)
}

enum class ActiveField { NONE, START, END }
