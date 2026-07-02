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
import androidx.compose.material3.TextButton
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
import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.PlaceSuggestion
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
        when (uiState.navState) {
            NavigationState.RoutePreviewShown -> RoutePreviewBar(
                uiState = uiState,
                onStartNavigation = viewModel::startNavigation,
                onEditRoute = viewModel::openPanel,
                modifier = bottomModifier,
            )
            NavigationState.NavigatingPlaceholder -> NavigatingBar(
                onStop = viewModel::stopNavigation,
                modifier = bottomModifier,
            )
            NavigationState.Idle -> Unit
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onStartNavigation, enabled = uiState.canNavigate) {
                    Text(text = "길안내 시작")
                }
                TextButton(onClick = onEditRoute) { Text(text = "경로 수정") }
            }
        }
    }
}

@Composable
private fun NavigatingBar(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = "길안내 모드", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "경로를 따라 이동하세요. 상세 턴바이턴 안내는 준비 중입니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onStop) { Text(text = "길안내 종료") }
        }
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
        preview.segments.forEach { segment ->
            val points = segment.geometry.map { LatLng.from(it.latitude, it.longitude) }
            if (points.size < 2) return@forEach
            val style = RouteLineStyle.from(segment.lineWidth(), segment.lineColor()).let { base ->
                if (segment.style.dashed) {
                    base.setPattern(
                        RouteLinePattern.from(context.rasterize(R.drawable.route_dash_pattern), 24f),
                    )
                } else {
                    base
                }
            }
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
    style.width?.let { return it.toFloat() }
    return when (kind) {
        SegmentKind.TRANSIT -> 8f
        else -> 6f
    }
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
