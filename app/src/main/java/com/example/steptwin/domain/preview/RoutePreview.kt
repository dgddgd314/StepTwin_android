package com.example.steptwin.domain.preview

/**
 * 서버가 내려주는 지도 미리보기 데이터의 도메인 모델.
 *
 * 안드로이드는 이 데이터를 그대로 카카오 지도 위에 Polyline / Marker 로 그리기만 한다.
 */
data class RoutePreview(
    val segments: List<PreviewSegment>,
    val markers: List<PreviewMarker>,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
)

/** 경로 한 구간. custom_walk = 사용자 맞춤 도보, transit = 대중교통 등. */
data class PreviewSegment(
    val kind: SegmentKind,
    val geometry: List<GeoPoint>,
    val style: SegmentStyle,
)

data class SegmentStyle(
    /** "#16A34A" 형태의 색상. 없으면 kind 기본색을 사용한다. */
    val colorHex: String?,
    /** true 면 점선(dashed), false 면 실선(solid). */
    val dashed: Boolean,
    /** 서버가 지정한 선 두께(1~16). 없으면 kind 기본값을 사용한다. */
    val width: Int?,
)

/** 지도 위 마커. shade_shelter = 그늘막/파라솔, stairs_avoided = 계단 회피 지점. */
data class PreviewMarker(
    val kind: MarkerKind,
    val coordinate: GeoPoint,
    /** 서버가 지정한 아이콘 힌트(예: "parasol"). 없으면 kind 로 판단한다. */
    val icon: String?,
)

enum class SegmentKind {
    CUSTOM_WALK,
    TRANSIT,
    UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): SegmentKind = when (raw?.lowercase()) {
            "custom_walk" -> CUSTOM_WALK
            "transit" -> TRANSIT
            else -> UNKNOWN
        }
    }
}

enum class MarkerKind {
    SHADE_SHELTER,
    STAIRS_AVOIDED,
    STOP,
    ORIGIN,
    DESTINATION,
    UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): MarkerKind = when (raw?.lowercase()) {
            "shade_shelter" -> SHADE_SHELTER
            "stairs_avoided" -> STAIRS_AVOIDED
            "stop" -> STOP
            "origin" -> ORIGIN
            "destination" -> DESTINATION
            else -> UNKNOWN
        }
    }
}
