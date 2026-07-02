package com.example.steptwin.ui.map

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.example.steptwin.R
import com.example.steptwin.domain.preview.MarkerKind
import com.example.steptwin.domain.preview.PreviewMarker
import com.example.steptwin.domain.preview.PreviewSegment
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.SegmentKind
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
import com.kakao.vectormap.route.RouteLinePattern
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle
import com.kakao.vectormap.route.RouteLineStyles
import com.kakao.vectormap.route.RouteLineStylesSet

// 데모 구간 중심(청량리역 부근). 데이터가 없을 때 기본 카메라 위치로 사용한다.
private val DemoCenter = LatLng.from(37.5804, 127.0468)
private const val DemoZoom = 15

@Composable
fun MapRouteScreen(
    viewModel: MapRouteViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 카카오 지도 네이티브 라이브러리가 없는 기기(x86_64 에뮬레이터 등)에서는
    // MapView 를 만들면 크래시하므로 안내만 보여준다.
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

                    override fun onMapError(error: Exception) {
                        // 인증 실패(앱 키 미설정) 등은 여기로 온다. 데모에서는 조용히 무시.
                    }
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

    // MapView 는 액티비티 생명주기에 맞춰 resume/pause/finish 되어야 한다.
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

    // 지도 준비 + 서버 데이터가 모두 준비되면 그린다.
    LaunchedEffect(kakaoMapState.value, uiState.preview) {
        val map = kakaoMapState.value ?: return@LaunchedEffect
        val preview = uiState.preview ?: return@LaunchedEffect
        drawPreview(map, preview, context)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
        )

        StatusBanner(
            uiState = uiState,
            onRefresh = viewModel::refresh,
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
private fun StatusBanner(
    uiState: MapRouteUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "맞춤 길찾기",
                style = MaterialTheme.typography.titleMedium,
            )

            val serverText = when (uiState.serverHealthy) {
                true -> "서버 연결됨"
                false -> "서버 응답 없음"
                null -> "서버 확인 중"
            }
            val segmentCount = uiState.preview?.segments?.size ?: 0
            val markerCount = uiState.preview?.markers?.size ?: 0

            Text(
                text = "$serverText · 경로 ${segmentCount}구간 · 마커 ${markerCount}개",
                style = MaterialTheme.typography.bodySmall,
            )

            uiState.error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onRefresh, enabled = !uiState.isLoading) {
                    Text(text = "경로 새로고침")
                }
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}

/** 서버가 준 segments/markers 를 지도 위에 그린다. 매 호출 시 이전 렌더링을 지운다. */
private fun drawPreview(map: KakaoMap, preview: RoutePreview, context: Context) {
    val routeLayer = map.routeLineManager?.layer
    val labelManager = map.labelManager
    val labelLayer = labelManager?.layer

    routeLayer?.removeAll()
    labelLayer?.removeAll()

    if (routeLayer != null) {
        preview.segments.forEach { segment ->
            val points = segment.geometry.map { LatLng.from(it.latitude, it.longitude) }
            if (points.size < 2) return@forEach

            val style = RouteLineStyle.from(segment.lineWidth(), segment.lineColor()).let { base ->
                if (segment.style.dashed) {
                    base.setPattern(RouteLinePattern.from(R.drawable.route_dash_pattern, 24f))
                } else {
                    base
                }
            }
            val stylesSet = RouteLineStylesSet.from(RouteLineStyles.from(style))
            val lineSegment = RouteLineSegment.from(points, stylesSet.getStyles(0))
            routeLayer.addRouteLine(
                RouteLineOptions.from(lineSegment).setStylesSet(stylesSet),
            )
        }
    }

    if (labelManager != null && labelLayer != null) {
        preview.markers.forEach { marker ->
            val styles = labelManager.addLabelStyles(
                LabelStyles.from(LabelStyle.from(marker.iconRes())),
            )
            labelLayer.addLabel(
                LabelOptions
                    .from(LatLng.from(marker.coordinate.latitude, marker.coordinate.longitude))
                    .setStyles(styles),
            )
        }
    }

    // 첫 좌표로 카메라 이동해 데모 구간이 화면에 들어오게 한다.
    preview.firstPoint()?.let { first ->
        map.moveCamera(CameraUpdateFactory.newCenterPosition(first, DemoZoom))
    }
}

private fun PreviewSegment.lineColor(): Int {
    style.colorHex?.let { hex ->
        runCatching { AndroidColor.parseColor(hex) }.getOrNull()?.let { return it }
    }
    return when (kind) {
        SegmentKind.TRANSIT -> AndroidColor.parseColor("#2563EB")
        SegmentKind.CUSTOM_WALK -> AndroidColor.parseColor("#16A34A")
        SegmentKind.UNKNOWN -> AndroidColor.parseColor("#6B7280")
    }
}

private fun PreviewSegment.lineWidth(): Float = when (kind) {
    SegmentKind.TRANSIT -> 16f
    else -> 12f
}

private fun PreviewMarker.iconRes(): Int {
    val byKind = when (kind) {
        MarkerKind.SHADE_SHELTER -> R.drawable.ic_marker_parasol
        MarkerKind.STAIRS_AVOIDED -> R.drawable.ic_marker_stairs
        MarkerKind.UNKNOWN -> null
    }
    if (byKind != null) return byKind

    return when (icon?.lowercase()) {
        "parasol", "tree", "shade" -> R.drawable.ic_marker_parasol
        "stairs" -> R.drawable.ic_marker_stairs
        else -> R.drawable.ic_marker_default
    }
}

private fun RoutePreview.firstPoint(): LatLng? {
    segments.firstOrNull()?.geometry?.firstOrNull()?.let {
        return LatLng.from(it.latitude, it.longitude)
    }
    markers.firstOrNull()?.coordinate?.let {
        return LatLng.from(it.latitude, it.longitude)
    }
    return null
}

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
