package com.example.steptwin.data.remote

import com.example.steptwin.domain.preview.GeoPoint
import com.example.steptwin.domain.preview.MarkerKind
import com.example.steptwin.domain.preview.PreviewMarker
import com.example.steptwin.domain.preview.PreviewSegment
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.SegmentKind
import com.example.steptwin.domain.preview.SegmentStyle
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface RouteApi {
    @GET("api/v1/health")
    suspend fun health(): HealthResponse

    @POST("api/v1/routes/preview")
    suspend fun routePreview(@Body request: RoutePreviewRequest): RoutePreviewResponse
}

/**
 * preview 요청 바디. 서버 계약이 확정되면 필드를 맞추면 된다.
 * 현재는 최신 보행 취약도를 함께 보내 개인화 경로를 요청한다.
 */
data class RoutePreviewRequest(
    val speedWeight: Float? = null,
    val turnWeight: Float? = null,
    val strengthWeight: Float? = null,
)

data class HealthResponse(
    val status: String? = null,
)

data class RoutePreviewResponse(
    val segments: List<SegmentDto> = emptyList(),
    val markers: List<MarkerDto> = emptyList(),
) {
    fun toDomain(): RoutePreview = RoutePreview(
        segments = segments.mapNotNull { it.toDomain() },
        markers = markers.mapNotNull { it.toDomain() },
    )
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
