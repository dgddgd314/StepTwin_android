package com.example.steptwin.data.remote

import com.example.steptwin.domain.gait.TugWeights
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class WeightRemoteDataSource @Inject constructor(
    private val api: WeightApi,
) {
    suspend fun upload(weights: TugWeights, sampleCount: Int): WeightUploadResponse {
        return withContext(Dispatchers.IO) {
            api.postWeights(
                WeightUploadRequest(
                    speedWeight = weights.speedWeight,
                    turnWeight = weights.turnWeight,
                    strengthWeight = weights.strengthWeight,
                    measuredAtEpochMillis = System.currentTimeMillis(),
                    sampleCount = sampleCount,
                )
            )
        }
    }
}
