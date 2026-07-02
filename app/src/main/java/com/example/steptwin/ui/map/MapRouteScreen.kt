package com.example.steptwin.ui.map

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.steptwin.R
import com.example.steptwin.domain.agent.AgentReport
import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.PlaceSuggestion
import com.example.steptwin.domain.preview.WalkRoute
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.label.LabelStyles
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet

private val DemoCenter = LatLng.from(37.5916, 127.0547)
private const val DemoZoom = 15
private const val RouteColorHex = "#16A34A"
private const val RouteWidth = 6f

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

    // 지도 준비 + 상태 변경 시 다시 그린다(경로 없음이어도 마커는 갱신).
    LaunchedEffect(kakaoMapState.value, uiState.route, uiState.resolvedStart, uiState.resolvedEnd) {
        val map = kakaoMapState.value ?: return@LaunchedEffect
        drawWalkRoute(map, uiState.route, uiState.resolvedStart, uiState.resolvedEnd, context)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        SearchPanel(
            uiState = uiState,
            onStartChange = viewModel::updateStartQuery,
            onEndChange = viewModel::updateEndQuery,
            onSelectStart = viewModel::selectStart,
            onSelectEnd = viewModel::selectEnd,
            onSearch = viewModel::search,
            onRunAgent = viewModel::runAgent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
        )
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

@Composable
private fun SearchPanel(
    uiState: MapRouteUiState,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onSelectStart: (PlaceSuggestion) -> Unit,
    onSelectEnd: (PlaceSuggestion) -> Unit,
    onSearch: () -> Unit,
    onRunAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "맞춤 길찾기", style = MaterialTheme.typography.titleMedium)

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
                Button(
                    onClick = onRunAgent,
                    enabled = !uiState.isLoading && !uiState.isAgentRunning,
                ) {
                    Text(text = "🤖 AI 에이전트")
                }
                if (uiState.isLoading || uiState.isAgentRunning) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
            }

            uiState.agentReport?.let { AgentReportCard(it) }

            val route = uiState.route
            if (route?.metrics != null) {
                val m = route.metrics
                Text(
                    text = "거리 ${m.distanceMeters}m · 시간 ${m.durationSeconds / 60}분 " +
                        "${m.durationSeconds % 60}초 · 계단 ${m.stairsCount} · 그늘막 ${m.shadeShelters}",
                    style = MaterialTheme.typography.bodySmall,
                )
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

@Composable
private fun AgentReportCard(report: AgentReport) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "🤖 AI 에이전트 워크플로우 · ${if (report.llmBacked) "LLM" else "규칙기반"}",
            style = MaterialTheme.typography.labelLarge,
        )
        report.steps.forEachIndexed { index, step ->
            Text(
                text = "${index + 1}. ${step.tool} → ${step.observation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = report.advice,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** WalkRoute geometry 를 초록 실선 polyline 으로, 출발/도착을 마커로 그린다. */
private fun drawWalkRoute(
    map: KakaoMap,
    route: WalkRoute?,
    resolvedStart: GeoPoint?,
    resolvedEnd: GeoPoint?,
    context: Context,
) {
    val routeLayer = map.routeLineManager?.layer
    val labelManager = map.labelManager
    val labelLayer = labelManager?.layer

    routeLayer?.removeAll()
    labelLayer?.removeAll()

    // 출발/도착 마커 (경로 없어도 표시). 스냅 좌표 우선, 없으면 지오코딩 좌표.
    val startPt = route?.start ?: resolvedStart
    val endPt = route?.end ?: resolvedEnd
    if (labelManager != null && labelLayer != null) {
        startPt?.let {
            addMarker(labelManager, labelLayer, it, context.rasterize(R.drawable.ic_marker_origin))
        }
        endPt?.let {
            addMarker(labelManager, labelLayer, it, context.rasterize(R.drawable.ic_marker_destination))
        }
    }

    // 경로 polyline (초록 실선, 폭 6)
    val geometry = route?.geometry.orEmpty()
    if (routeLayer != null && geometry.size >= 2) {
        val points = geometry.map { LatLng.from(it.latitude, it.longitude) }
        val style = RouteLineStyle.from(RouteWidth, AndroidColor.parseColor(RouteColorHex))
        val stylesSet = RouteLineStylesSet.from(RouteLineStyles.from(style))
        val segment = RouteLineSegment.from(points, stylesSet.getStyles(0))
        routeLayer.addRouteLine(RouteLineOptions.from(segment).setStylesSet(stylesSet))
    }

    // 카메라: 경로가 있으면 경로 전체, 없으면 출발/도착만 담는다.
    val camPoints = when {
        geometry.size >= 2 -> geometry.map { LatLng.from(it.latitude, it.longitude) }
        startPt != null && endPt != null ->
            listOf(startPt, endPt).map { LatLng.from(it.latitude, it.longitude) }
        else -> emptyList()
    }
    if (camPoints.size >= 2) {
        map.moveCamera(CameraUpdateFactory.fitMapPoints(camPoints.toTypedArray(), 120))
    }
}

private fun addMarker(
    labelManager: com.kakao.vectormap.label.LabelManager,
    labelLayer: com.kakao.vectormap.label.LabelLayer,
    point: GeoPoint,
    icon: Bitmap,
) {
    val styles = labelManager.addLabelStyles(LabelStyles.from(LabelStyle.from(icon)))
    labelLayer.addLabel(
        LabelOptions.from(LatLng.from(point.latitude, point.longitude)).setStyles(styles),
    )
}

/** 드로어블(벡터 포함)을 비트맵으로 래스터화(카카오 마커는 비트맵 필요). */
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
