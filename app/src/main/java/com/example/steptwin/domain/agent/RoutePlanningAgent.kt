package com.example.steptwin.domain.agent

import com.example.steptwin.domain.gait.TugWeights
import com.example.steptwin.domain.preview.NamedPlace
import com.example.steptwin.domain.preview.WalkRouteResult
import com.example.steptwin.domain.repository.RoutePreviewRepository
import com.example.steptwin.domain.repository.TugRepository
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * 도보 경로 계획 에이전트.
 * ReAct 스타일로 도구를 순차 오케스트레이션한다:
 *   observe_gait_profile → derive_preferences → fetch_walk_route → generate_explanation
 * 마지막 설명은 LLM([RouteAdvisor])이 있으면 그것을, 없으면 규칙기반으로 생성한다.
 */
class RoutePlanningAgent @Inject constructor(
    private val tugRepository: TugRepository,
    private val routeRepository: RoutePreviewRepository,
    private val advisor: RouteAdvisor,
) {
    suspend fun plan(start: NamedPlace, end: NamedPlace): AgentReport {
        val steps = mutableListOf<AgentStep>()

        // 1) 보행 프로필 관찰
        val weights = tugRepository.latestWeights.value ?: TugWeights.neutral()
        steps += AgentStep(
            tool = "observe_gait_profile",
            observation = "최신 취약도 — 속도 ${pct(weights.speedWeight)}, " +
                "회전 ${pct(weights.turnWeight)}, 근력 ${pct(weights.strengthWeight)}",
        )

        // 2) 라우팅 선호도 도출 (취약도 → SDOH 가중치)
        val prefText = derivePreferenceText(weights)
        steps += AgentStep(tool = "derive_preferences", observation = prefText)

        // 3) 경로 탐색 도구 호출
        val result = routeRepository.loadWalkRoute(start, end, weights)
        steps += AgentStep(
            tool = "fetch_walk_route",
            observation = describeResult(result, start, end),
        )

        // 4) 설명 생성 (LLM 우선, 실패 시 규칙기반)
        val prompt = buildPrompt(start, end, weights, prefText, result)
        val llm = advisor.advise(prompt)
        val advice = llm ?: templateAdvice(weights, result)
        steps += AgentStep(
            tool = "generate_explanation",
            observation = if (llm != null) "LLM 자연어 설명 생성 완료" else "규칙기반 설명 생성(오프라인)",
        )

        return AgentReport(steps = steps, advice = advice, llmBacked = llm != null)
    }

    private fun dominantAxis(w: TugWeights): String = when {
        w.strengthWeight >= w.turnWeight && w.strengthWeight >= w.speedWeight -> "근력"
        w.turnWeight >= w.speedWeight -> "회전"
        else -> "속도"
    }

    private fun derivePreferenceText(w: TugWeights): String {
        val parts = mutableListOf<String>()
        if (w.strengthWeight >= 0.4f) parts += "계단 회피 강화"
        if (w.strengthWeight >= 0.5f) parts += "경사 회피"
        if (w.turnWeight >= 0.5f) parts += "복잡한 회전 최소화"
        if (w.speedWeight >= 0.5f) parts += "보행속도 하향(여유 시간)"
        if (parts.isEmpty()) parts += "표준 보행 선호도"
        return "${dominantAxis(w)} 취약 우선 → " + parts.joinToString(", ")
    }

    private fun describeResult(result: WalkRouteResult, start: NamedPlace, end: NamedPlace): String =
        when (result) {
            is WalkRouteResult.Success -> {
                val m = result.route.metrics
                if (m != null) {
                    "${start.name}→${end.name} 경로 발견: ${m.distanceMeters}m, " +
                        "${m.durationSeconds / 60}분 ${m.durationSeconds % 60}초, " +
                        "계단 ${m.stairsCount}, 그늘막 ${m.shadeShelters}"
                } else {
                    "${start.name}→${end.name} 경로 발견"
                }
            }
            WalkRouteResult.NoRoute -> "경로 없음(보행 네트워크 미완 구간)"
            WalkRouteResult.InvalidRequest -> "요청 형식 오류(422)"
            WalkRouteResult.BackendError -> "백엔드 오류(5xx)"
            is WalkRouteResult.Failure -> "서버 연결 실패"
        }

    private fun buildPrompt(
        start: NamedPlace,
        end: NamedPlace,
        w: TugWeights,
        prefText: String,
        result: WalkRouteResult,
    ): String = buildString {
        appendLine("당신은 고령자 보행 내비게이션 도우미입니다. 아래 정보를 바탕으로,")
        appendLine("어르신이 이해하기 쉬운 존댓말 2~3문장으로 이 경로 추천 이유를 설명하세요.")
        appendLine("의료 진단이 아니라 이동 보조 안내임을 전제로 합니다.")
        appendLine("- 출발: ${start.name} / 도착: ${end.name}")
        appendLine("- 보행 취약도(0~1): 속도 ${f(w.speedWeight)}, 회전 ${f(w.turnWeight)}, 근력 ${f(w.strengthWeight)}")
        appendLine("- 도출한 경로 선호도: $prefText")
        appendLine("- 경로 탐색 결과: ${describeResult(result, start, end)}")
    }

    private fun templateAdvice(w: TugWeights, result: WalkRouteResult): String {
        val axis = dominantAxis(w)
        val reason = when (axis) {
            "근력" -> "근력 취약도가 높아 계단과 경사를 피하는 경로를 우선했어요."
            "회전" -> "회전 취약도가 높아 방향 전환이 적은 단순한 경로를 우선했어요."
            else -> "보행 속도 취약도를 고려해 무리 없는 거리와 시간을 우선했어요."
        }
        val outcome = when (result) {
            is WalkRouteResult.Success -> {
                val m = result.route.metrics
                if (m != null) {
                    " 추천 경로는 약 ${m.distanceMeters}m, ${m.durationSeconds / 60}분 거리이고 " +
                        "계단은 ${m.stairsCount}개예요. 천천히 이동하셔도 좋아요."
                } else {
                    " 추천 경로를 찾았어요. 천천히 이동하셔도 좋아요."
                }
            }
            WalkRouteResult.NoRoute ->
                " 지금은 이 구간의 보행 길 정보가 아직 준비 중이라 경로를 찾지 못했어요. " +
                    "가까운 큰길로 우회하시길 권합니다."
            else -> " 지금은 서버 상태로 경로를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."
        }
        return reason + outcome
    }

    private fun pct(v: Float): String = "${(v.coerceIn(0f, 1f) * 100).roundToInt()}%"
    private fun f(v: Float): String = "%.2f".format(v)
}
