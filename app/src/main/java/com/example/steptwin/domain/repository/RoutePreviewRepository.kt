package com.example.steptwin.domain.repository

import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.preview.RoutePreview

interface RoutePreviewRepository {
    /** 서버 health 확인. 성공하면 true. */
    suspend fun checkHealth(): Boolean

    /** 서버에서 지도 미리보기(경로 + 마커)를 받아온다. 실패 시 예외를 던진다. */
    suspend fun loadPreview(weights: TugWeights?): RoutePreview
}
