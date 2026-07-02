package com.example.steptwin.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

interface WeightApi {
    @POST("api/v1/weights")
    suspend fun postWeights(@Body request: WeightUploadRequest): WeightUploadResponse
}

data class WeightUploadRequest(
    val speedWeight: Float,
    val turnWeight: Float,
    val strengthWeight: Float,
    val measuredAtEpochMillis: Long,
    val sampleCount: Int,
)

data class WeightUploadResponse(
    val id: String? = null,
    val status: String? = null,
)
