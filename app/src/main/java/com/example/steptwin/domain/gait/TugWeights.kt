package com.example.steptwin.domain.gait

data class TugWeights(
    val speedWeight: Float,
    val turnWeight: Float,
    val strengthWeight: Float,
) {
    companion object {
        fun neutral() = TugWeights(
            speedWeight = 0.35f,
            turnWeight = 0.35f,
            strengthWeight = 0.35f,
        )
    }
}

/** TUG 프로토콜 수행에서 추출한 구간 지표. */
data class TugMetrics(
    /** TUG 총 소요 시간(초): 움직임 시작 ~ 종료. */
    val tugTimeSec: Float,
    /** 일어서기(sit-to-stand) 구간 추정 시간(초). */
    val standSec: Float,
    /** 보행(왕복) 구간 총 시간(초). */
    val walkSec: Float,
    /** 회전 구간 총 시간(초). */
    val turnSec: Float,
    /** 추정 보행 속도(m/s): 왕복 6m / 보행시간. */
    val gaitSpeedMps: Float,
    /** TUG 총시간 기반 낙상 위험 등급. */
    val fallRisk: FallRisk,
) {
    companion object {
        fun empty() = TugMetrics(0f, 0f, 0f, 0f, 0f, FallRisk.Unknown)
    }
}

/** TUG 총시간 기준 낙상 위험(임상 관례: <10 정상, 10~13.5 주의, ≥13.5 위험). */
enum class FallRisk(val label: String) {
    Low("낮음"),
    Moderate("주의"),
    High("높음"),
    Unknown("-");

    companion object {
        fun fromTugTime(sec: Float): FallRisk = when {
            sec <= 0f -> Unknown
            sec < 10f -> Low
            sec < 13.5f -> Moderate
            else -> High
        }
    }
}

/** TugCalculator 의 분석 결과(취약도 + 구간 지표). */
data class TugAnalysis(
    val weights: TugWeights,
    val metrics: TugMetrics,
) {
    companion object {
        fun neutral() = TugAnalysis(TugWeights.neutral(), TugMetrics.empty())
    }
}

data class TugAnalysisResult(
    val weights: TugWeights,
    val metrics: TugMetrics,
    val syncedToServer: Boolean,
    val syncMessage: String?,
)
