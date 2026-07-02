package com.example.steptwin.domain.repository

import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.preview.NamedPlace
import com.example.steptwin.domain.preview.PlaceSuggestion
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.WalkRouteResult

interface RoutePreviewRepository {
    /** 서버 health 확인. 성공하면 true. */
    suspend fun checkHealth(): Boolean

    /** (구) 서버에서 지도 미리보기(경로 + 마커)를 받아온다. 실패 시 예외를 던진다. */
    suspend fun loadPreview(weights: TugWeights?): RoutePreview

    /** 장소 이름을 좌표로 변환(카카오 로컬 검색). 실패 시 null. */
    suspend fun geocode(query: String): NamedPlace?

    /** 자동완성 후보 목록(카카오 로컬 검색). 서버 불필요. */
    suspend fun suggest(query: String): List<PlaceSuggestion>

    /** walk-routes/optimize 로 커스텀 도보 경로를 받아온다. HTTP 상태를 결과로 반영한다. */
    suspend fun loadWalkRoute(
        start: NamedPlace,
        end: NamedPlace,
        weights: TugWeights?,
    ): WalkRouteResult
}
