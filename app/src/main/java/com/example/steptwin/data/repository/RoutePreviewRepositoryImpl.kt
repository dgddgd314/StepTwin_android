package com.example.steptwin.data.repository

import com.example.steptwin.BuildConfig
import com.example.steptwin.data.remote.CoordinateDto
import com.example.steptwin.data.remote.KakaoLocalApi
import com.example.steptwin.data.remote.PlaceDto
import com.example.steptwin.data.remote.RouteApi
import com.example.steptwin.data.remote.RoutePreviewRequest
import com.example.steptwin.data.remote.RoutingPreferencesDto
import com.example.steptwin.data.remote.WalkRouteRequest
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.preview.NamedPlace
import com.example.steptwin.domain.preview.PlaceSuggestion
import com.example.steptwin.domain.preview.RoutePreview
import com.example.steptwin.domain.preview.WalkRouteResult
import com.example.steptwin.domain.repository.RoutePreviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import javax.inject.Inject

class RoutePreviewRepositoryImpl @Inject constructor(
    private val api: RouteApi,
    private val kakaoLocalApi: KakaoLocalApi,
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
                    origin = PlaceDto("출발", CoordinateDto(37.58945, 127.05775)),
                    destination = PlaceDto("도착", CoordinateDto(37.59375, 127.05158)),
                    preferences = weights?.toPreferences(),
                )
            ).toDomain()
        }

    override suspend fun geocode(query: String): NamedPlace? = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext null
        runCatching {
            kakaoLocalApi.searchKeyword(
                authorization = "KakaoAK ${BuildConfig.KAKAO_REST_API_KEY}",
                query = trimmed,
            )
        }.getOrNull()?.documents?.firstOrNull()?.let { doc ->
            val point = doc.toPoint() ?: return@let null
            NamedPlace(name = doc.place_name ?: trimmed, point = point)
        }
    }

    override suspend fun suggest(query: String): List<PlaceSuggestion> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        runCatching {
            kakaoLocalApi.searchKeyword(
                authorization = "KakaoAK ${BuildConfig.KAKAO_REST_API_KEY}",
                query = trimmed,
                size = 8,
            )
        }.getOrNull()?.documents?.mapNotNull { it.toSuggestion() }.orEmpty()
    }

    override suspend fun loadWalkRoute(
        start: NamedPlace,
        end: NamedPlace,
        weights: TugWeights?,
    ): WalkRouteResult = withContext(Dispatchers.IO) {
        try {
            val response = api.optimizeWalkRoute(
                WalkRouteRequest(
                    start = PlaceDto(
                        name = start.name,
                        coordinate = CoordinateDto(start.point.latitude, start.point.longitude),
                    ),
                    end = PlaceDto(
                        name = end.name,
                        coordinate = CoordinateDto(end.point.latitude, end.point.longitude),
                    ),
                    preferences = weights?.toPreferences(),
                )
            )
            WalkRouteResult.Success(response.toDomain())
        } catch (e: HttpException) {
            when (e.code()) {
                404 -> WalkRouteResult.NoRoute
                422 -> WalkRouteResult.InvalidRequest
                500, 503 -> WalkRouteResult.BackendError
                else -> WalkRouteResult.Failure("HTTP ${e.code()}")
            }
        } catch (e: Exception) {
            WalkRouteResult.Failure(e.message)
        }
    }
}

/** 보행 취약도 벡터를 서버 라우팅 선호도로 변환한다(값은 허용 범위로 clamp). */
private fun TugWeights.toPreferences(): RoutingPreferencesDto = RoutingPreferencesDto(
    avoid_stairs = strengthWeight >= 0.4f,
    stair_weight = (1.0 + strengthWeight * 2.0).coerceIn(0.0, 3.0),
    slope_weight = (0.7 + strengthWeight * 1.5).coerceIn(0.0, 3.0),
    corner_weight = (0.4 + turnWeight * 2.0).coerceIn(0.0, 3.0),
    walking_speed_mps = (1.15 - speedWeight * 0.4).coerceIn(0.3, 2.5),
)
