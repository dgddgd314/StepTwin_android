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

/**
 * TUG 프로토콜 수행에서 추출한 구간 지표.
 * 시간은 3종으로 분리한다(서로 다른 조건의 임상 컷오프를 오적용하지 않기 위함):
 *  - clinicalTugSec: 시작 신호 → 착석 (임상 TUG 시간, 13.5초 등 임상 컷오프는 여기에만 적용)
 *  - movementSec: 첫 움직임 → 착석 (순수 동작시간)
 *  - reactionSec: 시작 신호 → 첫 움직임 (반응시간)
 */
data class TugMetrics(
    val clinicalTugSec: Float,
    val movementSec: Float,
    val reactionSec: Float,
    /** 기립(sit-to-stand) 추정 시간. */
    val standSec: Float,
    /** 보행(왕복) 총 시간. */
    val walkSec: Float,
    /** 180° 회전시간(중간 반환점). 2.45초 탐색 기준 대상. */
    val turn180Sec: Float,
    /** 의자 앞 회전(착석 전) 시간. */
    val turnToSitSec: Float,
    /** 착석(stand-to-sit) 추정 시간. */
    val sitSec: Float,
    /** 추정 보행 속도(m/s): 왕복 6m / 보행시간. 정확 거리 아님 → "추정". */
    val gaitSpeedMps: Float,
    /** 기준신호에서 폰 고정이 불안정으로 판정되면 true(결과 신뢰도 낮음). */
    val unstableMount: Boolean,
    val assessment: TugAssessment,
) {
    companion object {
        fun empty() = TugMetrics(
            clinicalTugSec = 0f,
            movementSec = 0f,
            reactionSec = 0f,
            standSec = 0f,
            walkSec = 0f,
            turn180Sec = 0f,
            turnToSitSec = 0f,
            sitSec = 0f,
            gaitSpeedMps = 0f,
            unstableMount = false,
            assessment = TugAssessment.empty(),
        )
    }
}

/** 직선보행 속도 밴드(별도 4m/6m 평상속도 검사 값 기준을 참고한 분류명). */
enum class GaitSpeedBand(val label: String) {
    Preserved("보행속도 보존(≥1.0)"),
    Slow("느린 보행형(0.8–1.0)"),
    VerySlow("고도 느린 보행형(<0.8)"),
    Unknown("-");

    companion object {
        fun of(mps: Float): GaitSpeedBand = when {
            mps <= 0f -> Unknown
            mps >= 1.0f -> Preserved
            mps >= 0.8f -> Slow
            else -> VerySlow
        }
    }
}

/**
 * 복수 태그 방식 해석. 하나의 진단·확정이 아니라 "추가평가 권장" 신호로 표현한다.
 * 서로 다른 연구 조건의 컷오프(13.5초/0.8·1.0m/s/2.45초)를 각각 탐색적으로만 사용한다.
 */
data class TugAssessment(
    /** 임상 TUG ≥ 13.5초 → 이동기능/낙상 추가평가 권장(진단 아님). */
    val needsFollowUp: Boolean,
    val gaitBand: GaitSpeedBand,
    /** 180° 회전 > 2.45초 → 탐색적 '회전 지연' 신호(허약 확정 아님). */
    val turnDelay: Boolean,
    /** 위 신호 2개 이상 동반 → 복합 이동취약 패턴. */
    val complexPattern: Boolean,
    /** 화면 표시용 태그 목록. */
    val tags: List<String>,
) {
    companion object {
        fun empty() = TugAssessment(
            needsFollowUp = false,
            gaitBand = GaitSpeedBand.Unknown,
            turnDelay = false,
            complexPattern = false,
            tags = emptyList(),
        )
    }
}

/** TugCalculator 의 분석 결과(라우팅용 취약도 + TUG 구간 지표/해석). */
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
