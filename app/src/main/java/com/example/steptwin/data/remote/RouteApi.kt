package com.example.steptwin.data.remote

import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.MarkerKind
import com.example.steptwin.domain.preview.PreviewMarker
import com.example.steptwin.domain.preview.PreviewSegment
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.RouteViewport
import com.example.steptwin.domain.preview.SegmentKind
import com.example.steptwin.domain.preview.SegmentStyle
import com.example.steptwin.domain.preview.WalkMetrics
import com.example.steptwin.domain.preview.WalkRoute
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface RouteApi {
    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    @POST("api/v1/routes/preview")
    suspend fun routePreview(@Body request: RoutePreviewRequest): RoutePreviewResponse

    /** 커스텀 도보 경로 최적화 (메인 엔드포인트). */
    @POST("api/v1/walk-routes/optimize")
    suspend fun optimizeWalkRoute(@Body request: WalkRouteRequest): WalkRouteResponse
}

// ---- walk-routes/optimize 요청 ----

data class WalkRouteRequest(
    val start: PlaceDto,
    val end: PlaceDto,
    val preferences: RoutingPreferencesDto? = null,
)

// ---- walk-routes/optimize 응답 ----

data class WalkRouteResponse(
    val route_kind: String? = null,
    val start: SnappedPointDto? = null,
    val end: SnappedPointDto? = null,
    val geometry: List<CoordinateDto> = emptyList(),
    val metrics: WalkMetricsDto? = null,
    // steps 는 무시(미사용)
) {
    fun toDomain(): WalkRoute = WalkRoute(
        routeKind = route_kind,
        geometry = geometry.mapNotNull { it.toDomain() },
        start = start?.coordinate?.toDomain(),
        end = end?.coordinate?.toDomain(),
        metrics = metrics?.toDomain(),
    )
}

data class SnappedPointDto(
    val vertex_id: Long? = null,
    val coordinate: CoordinateDto? = null,
    val snap_distance_meters: Double? = null,
)

data class WalkMetricsDto(
    val total_cost_seconds: Double? = null,
    val total_distance_meters: Int? = null,
    val duration_seconds: Int? = null,
    val stairs_count: Int? = null,
    val shade_shelters: Int? = null,
) {
    fun toDomain(): WalkMetrics = WalkMetrics(
        distanceMeters = total_distance_meters ?: 0,
        durationSeconds = duration_seconds ?: 0,
        stairsCount = stairs_count ?: 0,
        shadeShelters = shade_shelters ?: 0,
    )
}

// ---- 요청 (서버 RoutePreviewRequest 계약) ----

data class RoutePreviewRequest(
    val origin: PlaceDto,
    val destination: PlaceDto,
    val preferences: RoutingPreferencesDto? = null,
)

data class PlaceDto(
    val name: String,
    val coordinate: CoordinateDto,
)

/** 모든 필드 선택. null 이면 서버 기본값이 적용된다. */
data class RoutingPreferencesDto(
    val avoid_stairs: Boolean? = null,
    val shade_weight: Double? = null,
    val stair_weight: Double? = null,
    val slope_weight: Double? = null,
    val corner_weight: Double? = null,
    val walking_speed_mps: Double? = null,
    val max_extra_walk_ratio: Double? = null,
)

// ---- 응답 ----

data class HealthResponse(
    val status: String? = null,
)

/** 서버 RoutePreviewResponse. 지도 렌더링에 필요한 segments/markers/viewport 만 파싱한다. */
data class RoutePreviewResponse(
    val segments: List<SegmentDto> = emptyList(),
    val markers: List<MarkerDto> = emptyList(),
    val viewport: ViewportDto? = null,
) {
    fun toDomain(): RoutePreview = RoutePreview(
        segments = segments.mapNotNull { it.toDomain() },
        markers = markers.mapNotNull { it.toDomain() },
        viewport = viewport?.toDomain(),
    )
}

data class ViewportDto(
    val southwest: CoordinateDto? = null,
    val northeast: CoordinateDto? = null,
) {
    fun toDomain(): RouteViewport? {
        val sw = southwest?.toDomain() ?: return null
        val ne = northeast?.toDomain() ?: return null
        return RouteViewport(southwest = sw, northeast = ne)
    }
}

data class SegmentDto(
    val kind: String? = null,
    val geometry: List<CoordinateDto> = emptyList(),
    val render: RenderDto? = null,
) {
    fun toDomain(): PreviewSegment? {
        val points = geometry.mapNotNull { it.toDomain() }
        if (points.size < 2) return null
        return PreviewSegment(
            kind = SegmentKind.fromRaw(kind),
            geometry = points,
            style = SegmentStyle(
                colorHex = render?.color,
                dashed = render?.pattern?.equals("dashed", ignoreCase = true) == true,
                width = render?.width,
            ),
        )
    }
}

data class MarkerDto(
    val kind: String? = null,
    val coordinate: CoordinateDto? = null,
    val icon: String? = null,
) {
    fun toDomain(): PreviewMarker? {
        val point = coordinate?.toDomain() ?: return null
        return PreviewMarker(
            kind = MarkerKind.fromRaw(kind),
            coordinate = point,
            icon = icon,
        )
    }
}

data class RenderDto(
    val color: String? = null,
    val pattern: String? = null,
    val width: Int? = null,
)

data class CoordinateDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    fun toDomain(): GeoPoint? {
        val lat = latitude ?: return null
        val lng = longitude ?: return null
        return GeoPoint(lat, lng)
    }
}
