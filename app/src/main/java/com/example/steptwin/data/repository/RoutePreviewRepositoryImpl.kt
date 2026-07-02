package com.example.steptwin.data.repository

import com.example.steptwin.data.remote.RouteApi
import com.example.steptwin.data.remote.RoutePreviewRequest
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
                    speedWeight = weights?.speedWeight,
                    turnWeight = weights?.turnWeight,
                    strengthWeight = weights?.strengthWeight,
                )
            ).toDomain()
        }
}
