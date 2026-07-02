package com.example.steptwin.data.repository

import com.example.steptwin.data.remote.CoordinateDto
import com.example.steptwin.data.remote.PlaceDto
import com.example.steptwin.data.remote.RouteApi
import com.example.steptwin.data.remote.RoutePreviewRequest
import com.example.steptwin.data.remote.RoutingPreferencesDto
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.repository.RoutePreviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RoutePreviewRepositoryImpl @Inject constructor(
    private val api: RouteApi,
) : RoutePreviewRepository {

    override suspend fun checkHealth(): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.health() }
            .map { it.status?.equals("ok", ignoreCase = true) != false }
            .getOrDefault(false)
    }

    override suspend fun loadPreview(weights: TugWeights?): RoutePreview =
        withContext(Dispatchers.IO) {
            api.routePreview(
                RoutePreviewRequest(
                    origin = DemoOrigin,
                    destination = DemoDestination,
                    preferences = weights?.toPreferences(),
                )
            ).toDomain()
        }

    private companion object {
        // 데모 구간: 청량리역 -> 경희의료원
        val DemoOrigin = PlaceDto(
            name = "청량리역",
            coordinate = CoordinateDto(latitude = 37.5804, longitude = 127.0468),
        )
        val DemoDestination = PlaceDto(
            name = "경희의료원",
            coordinate = CoordinateDto(latitude = 37.5936, longitude = 127.0516),
        )
    }
}

/**
 * 보행 취약도 벡터를 서버 라우팅 선호도로 변환한다.
 * 취약할수록(값이 클수록) 계단/경사/회전 회피 가중치를 키우고 보행 속도를 낮춘다.
 * 값은 서버 허용 범위로 clamp 한다.
 */
private fun TugWeights.toPreferences(): RoutingPreferencesDto = RoutingPreferencesDto(
    avoid_stairs = strengthWeight >= 0.4f,
    stair_weight = (1.0 + strengthWeight * 2.0).coerceIn(0.0, 3.0),
    slope_weight = (0.7 + strengthWeight * 1.5).coerceIn(0.0, 3.0),
    corner_weight = (0.4 + turnWeight * 2.0).coerceIn(0.0, 3.0),
    walking_speed_mps = (1.15 - speedWeight * 0.4).coerceIn(0.3, 2.5),
)
