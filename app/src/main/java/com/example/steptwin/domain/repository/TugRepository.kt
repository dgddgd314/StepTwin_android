package com.example.steptwin.domain.repository

import com.example.steptwin.domain.gait.SensorSample
import com.example.steptwin.domain.gait.TugAnalysisResult
import com.example.steptwin.domain.gait.TugWeights
import kotlinx.coroutines.flow.StateFlow

interface TugRepository {
    val latestWeights: StateFlow<TugWeights?>

    suspend fun analyzeAndSync(samples: List<SensorSample>): TugAnalysisResult
}
