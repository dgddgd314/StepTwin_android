package com.example.steptwin.data.repository

import com.example.steptwin.BuildConfig
import com.example.steptwin.domain.assistant.ChatTurn
import com.example.steptwin.domain.repository.VoiceAssistantRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Messages API(Claude Haiku)로 말벗 답변을 생성한다.
 * 키(BuildConfig.ANTHROPIC_API_KEY)가 없으면 hasApiKey=false 이고, 호출 시 예외를 던진다
 * (ViewModel 이 규칙기반 폴백으로 처리).
 */
@Singleton
class VoiceAssistantRepositoryImpl @Inject constructor() : VoiceAssistantRepository {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY

    override val hasApiKey: Boolean get() = apiKey.isNotBlank()

    override suspend fun reply(systemPrompt: String, history: List<ChatTurn>): String =
        withContext(Dispatchers.IO) {
            require(hasApiKey) { "no api key" }

            val payload = mapOf(
                "model" to MODEL,
                "max_tokens" to 200,
                "system" to systemPrompt,
                "messages" to history.takeLast(8).map {
                    mapOf("role" to it.role, "content" to it.content)
                },
            )
            val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
                val parsed = gson.fromJson(text, AnthropicResponse::class.java)
                parsed?.content
                    ?.filter { it.type == "text" }
                    ?.mapNotNull { it.text }
                    ?.joinToString(" ")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "네, 알겠어요."
            }
        }

    private data class AnthropicResponse(val content: List<Block>?)
    private data class Block(val type: String?, val text: String?)

    private companion object {
        const val MODEL = "claude-haiku-4-5-20251001"
    }
}
