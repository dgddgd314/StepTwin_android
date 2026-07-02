package com.example.steptwin.domain.preview

/**
 * walk-routes/optimize 가 반환하는 단일 도보 경로.
 * 안드로이드는 geometry 를 하나의 polyline 으로 그리고 metrics 를 표시한다.
 */
data class WalkRoute(
    val routeKind: String?,
    val geometry: List<GeoPoint>,
    /** 스냅된 출발 좌표(선택). */
    val start: GeoPoint?,
    /** 스냅된 도착 좌표(선택). */
    val end: GeoPoint?,
    val metrics: WalkMetrics?,
)

/** 지오코딩된 장소(이름 + 좌표). */
data class NamedPlace(
    val name: String,
    val point: GeoPoint,
)

/** 자동완성 후보(이름 + 주소 + 좌표). */
data class PlaceSuggestion(
    val name: String,
    val address: String,
    val point: GeoPoint,
) {
    fun toNamedPlace(): NamedPlace = NamedPlace(name, point)
}

data class WalkMetrics(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val stairsCount: Int,
    val shadeShelters: Int,
)

/** walk-routes/optimize 호출 결과(HTTP 상태 반영). */
sealed interface WalkRouteResult {
    data class Success(val route: WalkRoute) : WalkRouteResult
    /** 404: 경로 없음(보행 네트워크가 아직 개선 중일 수 있음). */
    data object NoRoute : WalkRouteResult
    /** 422: 잘못된 요청(필드 누락/좌표 오류/선호값 범위 초과). */
    data object InvalidRequest : WalkRouteResult
    /** 500/503: 백엔드 오류(재시도 가능). */
    data object BackendError : WalkRouteResult
    /** 네트워크/기타 오류. */
    data class Failure(val message: String?) : WalkRouteResult
}
