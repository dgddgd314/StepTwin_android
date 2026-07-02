package com.example.steptwin.data.repository

import com.example.steptwin.data.remote.WeightRemoteDataSource
import com.example.steptwin.domain.gait.SensorSample
import com.example.steptwin.domain.gait.TugAnalysisResult
import com.example.steptwin.domain.gait.TugBaseline
import com.example.steptwin.domain.gait.TugCalculator
import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.repository.TugRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TugRepositoryImpl @Inject constructor(
    private val calculator: TugCalculator,
    private val remoteDataSource: WeightRemoteDataSource,
) : TugRepository {
    // 검사를 한 번도 하지 않은 사용자는 세 취약도가 모두 0.
    private val _latestWeights = MutableStateFlow<TugWeights?>(TugWeights.zero())
    override val latestWeights: StateFlow<TugWeights?> = _latestWeights.asStateFlow()

    override suspend fun analyzeAndSync(
        samples: List<SensorSample>,
        baseline: TugBaseline,
    ): TugAnalysisResult {
        val analysis = calculator.calculate(samples, baseline)
        _latestWeights.value = analysis.weights

        val syncResult = runCatching {
            remoteDataSource.upload(analysis.weights, samples.size)
        }

        return TugAnalysisResult(
            weights = analysis.weights,
            metrics = analysis.metrics,
            syncedToServer = syncResult.isSuccess,
            syncMessage = syncResult.exceptionOrNull()?.message,
        )
    }

    override suspend fun submitManual(
        clinicalTugSec: Float,
        gaitSpeedMps: Float?,
        turn180Sec: Float?,
    ): TugAnalysisResult {
        val analysis = calculator.fromManual(clinicalTugSec, gaitSpeedMps, turn180Sec)
        _latestWeights.value = analysis.weights

        val syncResult = runCatching {
            remoteDataSource.upload(analysis.weights, 0)
        }

        return TugAnalysisResult(
            weights = analysis.weights,
            metrics = analysis.metrics,
            syncedToServer = syncResult.isSuccess,
            syncMessage = syncResult.exceptionOrNull()?.message,
        )
    }

    override fun clearLocal() {
        // 삭제 후에는 다시 '검사 전' 상태 = 세 취약도 0.
        _latestWeights.value = TugWeights.zero()
    }
}
