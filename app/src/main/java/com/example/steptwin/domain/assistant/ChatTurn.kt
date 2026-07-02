package com.example.steptwin.domain.assistant

/** 말벗 대화 한 턴. role 은 "user" 또는 "assistant". */
data class ChatTurn(
    val role: String,
    val content: String,
)
