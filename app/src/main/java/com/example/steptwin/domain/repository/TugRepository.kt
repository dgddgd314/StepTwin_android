package com.example.steptwin.domain.repository

import com.example.steptwin.domain.gait.SensorSample
import com.example.steptwin.domain.gait.TugAnalysisResult
import com.example.steptwin.domain.gait.TugBaseline
import com.example.steptwin.domain.gait.TugWeights
import kotlinx.coroutines.flow.StateFlow

interface TugRepository {
    val latestWeights: StateFlow<TugWeights?>

    suspend fun analyzeAndSync(
        samples: List<SensorSample>,
        baseline: TugBaseline = TugBaseline.UNKNOWN,
    ): TugAnalysisResult

    /** 의료진이 직접 입력한 TUG 값으로 결과를 만든다(측정 대체). */
    suspend fun submitManual(
        clinicalTugSec: Float,
        gaitSpeedMps: Float?,
        turn180Sec: Float?,
    ): TugAnalysisResult

    /** 기기에 남은 보행 프로필(취약도)을 삭제한다. */
    fun clearLocal()
}
