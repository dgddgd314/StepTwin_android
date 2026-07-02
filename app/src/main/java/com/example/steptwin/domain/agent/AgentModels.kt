package com.example.steptwin.domain.agent

/** 에이전트가 수행한 한 단계(도구 호출 + 관찰 결과). */
data class AgentStep(
    val tool: String,
    val observation: String,
)

/** 에이전트 워크플로우 실행 결과. */
data class AgentReport(
    val steps: List<AgentStep>,
    val advice: String,
    /** true 면 LLM 이 설명을 생성, false 면 규칙기반 폴백. */
    val llmBacked: Boolean,
)

/** LLM 자연어 설명 생성기. 키 없음/실패 시 null 을 반환해 규칙기반으로 폴백한다. */
interface RouteAdvisor {
    suspend fun advise(prompt: String): String?
}
