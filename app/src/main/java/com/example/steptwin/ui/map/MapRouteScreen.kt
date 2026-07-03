package com.example.steptwin.ui.map

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.steptwin.R
import com.example.steptwin.domain.nav.NavMode
import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.MarkerKind
import com.example.steptwin.domain.preview.PlaceSuggestion
import com.example.steptwin.domain.preview.PreviewMarker
import com.example.steptwin.domain.preview.PreviewSegment
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.SegmentKind
import com.example.steptwin.ui.assistant.rememberSpeechController
import com.example.steptwin.ui.common.rememberAssistantVoice
import kotlinx.coroutines.delay
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelLayer
import com.kakao.vectormap.label.LabelManager
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLinePattern
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet

private val DemoCenter = LatLng.from(37.5722, 127.0146)
private const val DemoZoom = 12

/** 경로 화살표 반복 간격(픽셀). 클수록 화살표가 드물게 찍힌다. */
private const val ArrowSpacing = 64f

/** 길안내 중 카메라 줌 레벨. */
private const val NavZoom = 16

@Composable
fun MapRouteScreen(
    viewModel: MapRouteViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    if (!MapSupport.available) {
        MapUnsupportedNotice(modifier = modifier)
        return
    }

    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val kakaoMapState = remember { mutableStateOf<KakaoMap?>(null) }
    val mapView = remember {
        MapView(context).also { view ->
            view.start(
                object : MapLifeCycleCallback() {
                    override fun onMapDestroy() {
                        kakaoMapState.value = null
                    }

                    override fun onMapError(error: Exception) = Unit
                },
                object : KakaoMapReadyCallback() {
                    override fun onMapReady(map: KakaoMap) {
                        kakaoMapState.value = map
                        map.moveCamera(CameraUpdateFactory.newCenterPosition(DemoCenter, DemoZoom))
                    }

                    override fun getPosition(): LatLng = DemoCenter

                    override fun getZoomLevel(): Int = DemoZoom
                },
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose { mapView.finish() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.resume()
                Lifecycle.Event.ON_PAUSE -> mapView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.finish()
        }
    }

    // 지도 준비 + 경로/마커 변경 시 다시 그린다(패널 닫혀도 경로 유지).
    LaunchedEffect(kakaoMapState.value, uiState.preview, uiState.resolvedStart, uiState.resolvedEnd) {
        val map = kakaoMapState.value ?: return@LaunchedEffect
        drawPreview(map, uiState.preview, uiState.resolvedStart, uiState.resolvedEnd, context)
    }

    // 음성 발화: 내비 안내는 기기 TTS, 말벗 답변은 ElevenLabs 자연 음성(폴백=기기 TTS).
    val voice = rememberAssistantVoice()
    LaunchedEffect(Unit) {
        viewModel.ttsEvents.collect { voice.speak(it.text, natural = it.natural) }
    }

    // 내 위치 마커 + 카메라 추적(내비 중 userLocation 갱신마다).
    val userLabel = remember { mutableStateOf<Label?>(null) }
    LaunchedEffect(kakaoMapState.value, uiState.userLocation) {
        val map = kakaoMapState.value ?: return@LaunchedEffect
        val layer = map.labelManager?.layer
        runCatching { userLabel.value?.let { layer?.remove(it) } }
        userLabel.value = null
        val loc = uiState.userLocation ?: return@LaunchedEffect
        val mgr = map.labelManager ?: return@LaunchedEffect
        val styles = mgr.addLabelStyles(
            LabelStyles.from(LabelStyle.from(context.rasterize(R.drawable.ic_user_dot))),
        )
        val position = LatLng.from(loc.latitude, loc.longitude)
        userLabel.value = layer?.addLabel(LabelOptions.from(position).setStyles(styles))
        map.moveCamera(CameraUpdateFactory.newCenterPosition(position, NavZoom))
    }

    // 실제 GPS 브리지: 내비 중 + RealGps 모드일 때만 위치 수신(서버 전송 없음).
    RealGpsBridge(
        active = uiState.navState == NavigationState.NavigatingPlaceholder &&
            uiState.navMode == NavMode.RealGps,
        onLocation = viewModel::onRealLocation,
    )

    // 음성 목적지 입력 중: 출발지로 쓸 현재 위치를 확보(서버 전송 없음).
    CurrentLocationBridge(
        active = uiState.awaitingDestination,
        onLocation = viewModel::updateCurrentLocation,
    )

    // 말벗(음성 대화): STT + 마이크 권한.
    val speechController = rememberSpeechController(
        onResult = viewModel::onUserUtterance,
        onListeningChange = viewModel::setAssistantListening,
    )
    val micGranted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        micGranted.value = ok
        if (ok) speechController.startListening()
    }
    val onMic: () -> Unit = {
        if (micGranted.value) speechController.startListening()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 홈 '전화 걸기'로 음성 목적지 입력에 진입하면, 안내 음성이 끝날 즈음 자동으로 듣기 시작
    // (터치가 어려운 분 배려). 권한이 없으면 권한 요청을 띄운다.
    LaunchedEffect(uiState.awaitingDestination) {
        if (uiState.awaitingDestination) {
            delay(2500)
            onMic()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 상단: 패널 열림이면 검색 패널, 닫힘이면 '길찾기' 열기 버튼
        val topModifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(12.dp)
        when (uiState.panelState) {
            RoutePanelState.Open -> SearchPanel(
                uiState = uiState,
                onStartChange = viewModel::updateStartQuery,
                onEndChange = viewModel::updateEndQuery,
                onSelectStart = viewModel::selectStart,
                onSelectEnd = viewModel::selectEnd,
                onSearch = viewModel::search,
                onClose = viewModel::closePanel,
                modifier = topModifier,
            )
            RoutePanelState.Closed ->
                if (uiState.navState != NavigationState.NavigatingPlaceholder) {
                    Button(
                        onClick = viewModel::openPanel,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                    ) {
                        Text(text = "길찾기")
                    }
                }
        }

        // 하단: 경로 미리보기 바 / 길안내 모드 바
        val bottomModifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(12.dp)
        when {
            uiState.awaitingDestination &&
                uiState.navState != NavigationState.NavigatingPlaceholder ->
                VoiceDestinationPanel(
                    uiState = uiState,
                    onMic = onMic,
                    onCancel = viewModel::cancelVoiceDestination,
                    modifier = bottomModifier,
                )
            uiState.navState == NavigationState.RoutePreviewShown -> RoutePreviewBar(
                uiState = uiState,
                onStartNavigation = viewModel::startNavigation,
                onEditRoute = viewModel::openPanel,
                onSaveFavorite = viewModel::saveFavorite,
                modifier = bottomModifier,
            )
            else -> Unit
        }

        // 길안내 중: 말벗(위) + 길안내 바(아래)를 분리 배치해 가운데로 지도를 노출.
        // '지도 크게' 토글로 두 박스를 위/아래로 슬라이드해 접었다 다시 펼친다.
        if (uiState.navState == NavigationState.NavigatingPlaceholder) {
            var mapExpanded by remember { mutableStateOf(false) }
            val panelModifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)

            AnimatedVisibility(
                visible = !mapExpanded,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                AssistantPanel(
                    uiState = uiState,
                    onToggle = viewModel::toggleAssistant,
                    onMic = onMic,
                    modifier = panelModifier,
                )
            }

            AnimatedVisibility(
                visible = !mapExpanded,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NavigatingBar(
                    uiState = uiState,
                    onToggleMode = viewModel::setNavMode,
                    onProgress = viewModel::updateSimulatedProgress,
                    onStop = viewModel::stopNavigation,
                    modifier = panelModifier,
                )
            }

            // 접기/펼치기 토글(항상 보임) — 지도를 크게 보거나 안내를 다시 펼친다.
            FilledTonalButton(
                onClick = { mapExpanded = !mapExpanded },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(text = if (mapExpanded) "▲ 안내 보기" else "🗺 지도 크게")
            }
        }
    }
}

@Composable
private fun MapUnsupportedNotice(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "이 기기에서는 지도를 표시할 수 없어요",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "카카오 지도 SDK 는 ARM(arm64) 기기용 라이브러리만 제공합니다. " +
                        "지금은 x86/x86_64 에뮬레이터로 보입니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "실제 안드로이드 폰(대부분 arm64)에서 실행하면 지도가 정상적으로 표시됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ---------------- 상단 검색 패널 ----------------

@Composable
private fun SearchPanel(
    uiState: MapRouteUiState,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onSelectStart: (PlaceSuggestion) -> Unit,
    onSelectEnd: (PlaceSuggestion) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "길찾기", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onClose) { Text(text = "닫기") }
            }

            OutlinedTextField(
                value = uiState.startQuery,
                onValueChange = onStartChange,
                label = { Text("출발지") },
                singleLine = true,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.activeField == ActiveField.START) {
                SuggestionList(uiState.startSuggestions, onSelectStart)
            }
            OutlinedTextField(
                value = uiState.endQuery,
                onValueChange = onEndChange,
                label = { Text("도착지") },
                singleLine = true,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            if (uiState.activeField == ActiveField.END) {
                SuggestionList(uiState.endSuggestions, onSelectEnd)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSearch, enabled = !uiState.isLoading) {
                    Text(text = "길찾기")
                }
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
            }

            uiState.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun SuggestionList(
    suggestions: List<PlaceSuggestion>,
    onSelect: (PlaceSuggestion) -> Unit,
) {
    if (suggestions.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        suggestions.take(5).forEach { suggestion ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(suggestion) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = suggestion.name, style = MaterialTheme.typography.bodyMedium)
                if (suggestion.address.isNotBlank()) {
                    Text(
                        text = suggestion.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------- 하단 바 ----------------

@Composable
private fun RoutePreviewBar(
    uiState: MapRouteUiState,
    onStartNavigation: () -> Unit,
    onEditRoute: () -> Unit,
    onSaveFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = uiState.preview ?: return
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val summary = preview.summary
            if (summary != null) {
                Text(
                    text = "총 ${summary.totalDistanceMeters}m · ${summary.totalDurationSeconds / 60}분 " +
                        "${summary.totalDurationSeconds % 60}초 · 도보 ${summary.walkingDistanceMeters}m · " +
                        "대중교통 ${summary.transitDistanceMeters}m",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = "경로 준비 완료 · ${preview.segments.size}개 구간, ${preview.markers.size}개 지점",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // 내 보행지수 맞춤 근거(효과성): 계단 회피/그늘막 수를 노출.
            val stairs = preview.markers.count { it.kind == MarkerKind.STAIRS_AVOIDED }
            val shades = preview.markers.count { it.kind == MarkerKind.SHADE_SHELTER }
            if (stairs > 0 || shades > 0) {
                val parts = buildList {
                    if (stairs > 0) add("계단 ${stairs}곳 회피")
                    if (shades > 0) add("그늘막 ${shades}곳")
                }
                Text(
                    text = "🧭 내 보행지수 맞춤 · ${parts.joinToString(" · ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            preview.segments.mapNotNull { it.transit }.forEach { transit ->
                val icon = if (transit.isBus) "🚌" else "🚇" // 🚌 / 🚇
                val line = transit.lineLabel?.let { if (transit.isBus) "${it}번" else it } ?: "대중교통"
                val stops = if (!transit.boardingStop.isNullOrBlank() &&
                    !transit.alightingStop.isNullOrBlank()
                ) {
                    " · ${transit.boardingStop} → ${transit.alightingStop}"
                } else {
                    ""
                }
                Text(
                    text = "$icon $line$stops",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onStartNavigation, enabled = uiState.canNavigate) {
                    Text(text = "길안내 시작")
                }
                TextButton(onClick = onSaveFavorite, enabled = !uiState.favoriteSaved) {
                    Text(text = if (uiState.favoriteSaved) "★ 저장됨" else "☆ 즐겨찾기")
                }
                TextButton(onClick = onEditRoute) { Text(text = "경로 수정") }
            }
        }
    }
}

/** 음성 목적지 입력 패널 — 홈 '전화 걸기'로 진입, 도착지를 말하면 길찾기가 실행된다. */
@Composable
private fun VoiceDestinationPanel(
    uiState: MapRouteUiState,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "🗣 어디로 갈까요?", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCancel) { Text(text = "취소") }
            }
            Text(
                text = "출발: 현재 위치(GPS) · ‘○○에서’라고 말하면 출발지도 바꿔요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = uiState.assistantCaption.ifBlank { "도착지 이름만 말씀해 주세요." },
                style = MaterialTheme.typography.bodyLarge,
            )
            MicButton(
                listening = uiState.assistantListening,
                onMic = onMic,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "예: “경희의료원”  또는  “회기역에서 경희의료원까지”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 말벗(음성 양방향 대화) 패널 — 길안내와 동시에 챗봇처럼 묻고 답한다. */
@Composable
private fun AssistantPanel(
    uiState: MapRouteUiState,
    onToggle: () -> Unit,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "🗣 말벗 대화", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onToggle) {
                    Text(text = if (uiState.assistantActive) "끄기" else "켜기")
                }
            }

            if (!uiState.assistantActive) {
                Text(
                    text = "전화하듯 목소리로 물어보며 길을 안내받을 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }

            val status = when {
                uiState.assistantListening -> "듣고 있어요..."
                uiState.assistantThinking -> "생각 중..."
                else -> null
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = uiState.assistantCaption.ifBlank { "궁금한 걸 말하거나 아래 버튼을 눌러보세요." },
                style = MaterialTheme.typography.bodyLarge,
            )

            MicButton(
                listening = uiState.assistantListening,
                onMic = onMic,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!uiState.assistantHasKey) {
                Text(
                    text = "※ 자유 대화 키가 없어 정해진 질문만 알아들어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 큰 원형 "말하기" 버튼 + "누르고 말해주세요" 넛지.
 * 대기 중에는 은은하게 커졌다 작아지며(맥동) 누름을 유도하고, 듣는 중에는 멈춘다.
 */
@Composable
private fun MicButton(
    listening: Boolean,
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mic-pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mic-scale",
    )
    val scale = if (listening) 1f else pulse
    val circleColor = if (listening) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(circleColor)
                .clickable(enabled = !listening, onClick = onMic),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🎤", fontSize = 40.sp)
        }
        Text(
            text = if (listening) "듣고 있어요…" else "누르고 말해주세요",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NavigatingBar(
    uiState: MapRouteUiState,
    onToggleMode: (NavMode) -> Unit,
    onProgress: (Float) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "길안내 모드", style = MaterialTheme.typography.titleMedium)
            // 현재 안내(음성과 동일 문구) — 크게
            Text(
                text = uiState.navInstruction.ifBlank { "경로를 따라 이동하세요." },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            // 실제 GPS / 모의(슬라이더) 전환
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = uiState.navMode == NavMode.Simulated,
                    onClick = { onToggleMode(NavMode.Simulated) },
                    label = { Text(text = "모의(슬라이더)") },
                )
                FilterChip(
                    selected = uiState.navMode == NavMode.RealGps,
                    onClick = { onToggleMode(NavMode.RealGps) },
                    label = { Text(text = "실제 GPS") },
                )
            }

            if (uiState.navMode == NavMode.Simulated) {
                Text(
                    text = "슬라이더로 경로를 따라 이동해 보세요.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = uiState.navProgress,
                    onValueChange = onProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "실제 GPS 위치를 따라갑니다. 위치는 기기에서만 사용하며 서버로 전송하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(text = "길안내 종료")
            }
        }
    }
}

/** 실제 GPS 위치 수신 브리지(LocationManager). 좌표는 서버로 전송하지 않는다. */
@Composable
private fun RealGpsBridge(
    active: Boolean,
    onLocation: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val granted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted.value = ok }

    LaunchedEffect(active) {
        if (active && !granted.value) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    DisposableEffect(active, granted.value) {
        if (!active || !granted.value) return@DisposableEffect onDispose {}
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location.latitude, location.longitude)
            }

            @Deprecated("deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
            if (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
            }
        } catch (_: SecurityException) {
        }
        onDispose { lm?.removeUpdates(listener) }
    }
}

/**
 * 현재 위치 확보 브리지(음성 목적지 입력 시 출발지용). 마지막 위치를 즉시 쓰고, 갱신도 구독.
 * 좌표는 서버로 전송하지 않는다.
 */
@Composable
private fun CurrentLocationBridge(
    active: Boolean,
    onLocation: (Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val granted = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok -> granted.value = ok }

    LaunchedEffect(active) {
        if (active && !granted.value) launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    DisposableEffect(active, granted.value) {
        if (!active || !granted.value) return@DisposableEffect onDispose {}
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        // 즉시: 마지막으로 알려진 위치(있으면 바로 출발지로 사용).
        try {
            val last = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) onLocation(last.latitude, last.longitude)
        } catch (_: SecurityException) {
        }
        // 갱신: 더 최신/정확한 위치.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location.latitude, location.longitude)
            }

            @Deprecated("deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener)
            if (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, listener)
            }
        } catch (_: SecurityException) {
        }
        onDispose { lm?.removeUpdates(listener) }
    }
}

// ---------------- 렌더링 ----------------

/** segments 를 각각 별도 polyline(색/굵기/패턴)으로, markers 를 icon 별로 그린다. */
private fun drawPreview(
    map: KakaoMap,
    preview: RoutePreview?,
    resolvedStart: GeoPoint?,
    resolvedEnd: GeoPoint?,
    context: Context,
) {
    val routeLayer = map.routeLineManager?.layer
    val labelManager = map.labelManager
    val labelLayer = labelManager?.layer

    routeLayer?.removeAll()
    labelLayer?.removeAll()

    // 마커: 응답 markers 우선, 없으면 출발/도착 좌표
    if (labelManager != null && labelLayer != null) {
        if (preview != null && preview.markers.isNotEmpty()) {
            preview.markers.forEach { marker ->
                addMarker(labelManager, labelLayer, marker.coordinate, context.rasterize(marker.iconRes()))
            }
        } else {
            resolvedStart?.let {
                addMarker(labelManager, labelLayer, it, context.rasterize(R.drawable.ic_marker_origin))
            }
            resolvedEnd?.let {
                addMarker(labelManager, labelLayer, it, context.rasterize(R.drawable.ic_marker_destination))
            }
        }
    }

    // 세그먼트: 각각 개별 polyline
    if (routeLayer != null && preview != null) {
        // 진행 방향 화살표 패턴(선 방향으로 회전, 간격 ArrowSpacing). 비트맵 래스터화 필수.
        val arrowPattern = RouteLinePattern.from(context.rasterize(R.drawable.route_arrow), ArrowSpacing)
        preview.segments.forEach { segment ->
            val points = segment.geometry.map { LatLng.from(it.latitude, it.longitude) }
            if (points.size < 2) return@forEach
            val style = RouteLineStyle.from(segment.lineWidth(), segment.lineColor())
                .setPattern(arrowPattern)
            val stylesSet = RouteLineStylesSet.from(RouteLineStyles.from(style))
            val lineSegment = RouteLineSegment.from(points, stylesSet.getStyles(0))
            routeLayer.addRouteLine(RouteLineOptions.from(lineSegment).setStylesSet(stylesSet))
        }
    }

    moveCamera(map, preview, resolvedStart, resolvedEnd)
}

private fun moveCamera(
    map: KakaoMap,
    preview: RoutePreview?,
    resolvedStart: GeoPoint?,
    resolvedEnd: GeoPoint?,
) {
    // 1) 응답 viewport 우선
    preview?.viewport?.let { vp ->
        val pts = arrayOf(
            LatLng.from(vp.southwest.latitude, vp.southwest.longitude),
            LatLng.from(vp.northeast.latitude, vp.northeast.longitude),
        )
        map.moveCamera(CameraUpdateFactory.fitMapPoints(pts, 120))
        return
    }
    // 2) 모든 세그먼트 좌표 bounds
    val segPoints = preview?.segments?.flatMap { it.geometry }
        ?.map { LatLng.from(it.latitude, it.longitude) }
        .orEmpty()
    if (segPoints.size >= 2) {
        map.moveCamera(CameraUpdateFactory.fitMapPoints(segPoints.toTypedArray(), 120))
        return
    }
    // 3) 출발/도착만
    val ends = listOfNotNull(resolvedStart, resolvedEnd).map { LatLng.from(it.latitude, it.longitude) }
    if (ends.size >= 2) {
        map.moveCamera(CameraUpdateFactory.fitMapPoints(ends.toTypedArray(), 120))
    }
}

private fun addMarker(
    labelManager: LabelManager,
    labelLayer: LabelLayer,
    point: GeoPoint,
    icon: Bitmap,
) {
    val styles = labelManager.addLabelStyles(LabelStyles.from(LabelStyle.from(icon)))
    labelLayer.addLabel(
        LabelOptions.from(LatLng.from(point.latitude, point.longitude)).setStyles(styles),
    )
}

private fun PreviewSegment.lineColor(): Int {
    style.colorHex?.let { hex ->
        runCatching { AndroidColor.parseColor(hex) }.getOrNull()?.let { return it }
    }
    return when (kind) {
        SegmentKind.TRANSIT -> AndroidColor.parseColor("#0052A4")
        SegmentKind.CUSTOM_WALK -> AndroidColor.parseColor("#16A34A")
        SegmentKind.UNKNOWN -> AndroidColor.parseColor("#6B7280")
    }
}

private fun PreviewSegment.lineWidth(): Float {
    // 서버 두께를 기준으로 더 두껍게(가독성). 없으면 kind 기본값.
    val base = style.width?.toFloat() ?: if (kind == SegmentKind.TRANSIT) 8f else 6f
    return (base * 2.2f).coerceIn(14f, 26f)
}

private fun PreviewMarker.iconRes(): Int {
    when (icon?.lowercase()) {
        "origin", "start" -> return R.drawable.ic_marker_origin
        "destination", "goal" -> return R.drawable.ic_marker_destination
        "transit-stop", "stop", "bus", "subway", "bus-stop", "subway-stop" ->
            return R.drawable.ic_marker_stop
        "parasol", "shade" -> return R.drawable.ic_marker_parasol
        "tree" -> return R.drawable.ic_marker_parasol
        "stairs-off", "stairs", "stairs_avoided" -> return R.drawable.ic_marker_stairs
    }
    return R.drawable.ic_marker_default
}

/** 드로어블(벡터 포함)을 비트맵으로 래스터화(카카오 마커/패턴은 비트맵 필요). */
private fun Context.rasterize(@DrawableRes resId: Int): Bitmap {
    val drawable = ContextCompat.getDrawable(this, resId)
        ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return bitmap
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
