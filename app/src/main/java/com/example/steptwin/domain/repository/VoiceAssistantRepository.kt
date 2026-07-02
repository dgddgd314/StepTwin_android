package com.example.steptwin.domain.repository

import com.example.steptwin.domain.assistant.ChatTurn

/** 말벗(음성 대화) — Claude 로 경로 맥락 기반 답변을 생성한다. */
interface VoiceAssistantRepository {
    /** API 키가 설정돼 자유 대화가 가능한지. */
    val hasApiKey: Boolean

    /** 시스템 프롬프트 + 대화 이력으로 답변 텍스트를 생성. 실패 시 예외. */
    suspend fun reply(systemPrompt: String, history: List<ChatTurn>): String
}
