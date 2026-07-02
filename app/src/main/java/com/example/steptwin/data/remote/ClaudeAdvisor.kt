package com.example.steptwin.data.remote

import com.example.steptwin.BuildConfig
import com.example.steptwin.domain.agent.RouteAdvisor
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

/**
 * Anthropic Claude Messages API 로 자연어 설명을 생성한다.
 * local.properties 의 ANTHROPIC_API_KEY 가 비어있거나 호출이 실패하면 null 을 반환한다(규칙기반 폴백).
 */
class ClaudeAdvisor @Inject constructor(
    private val client: OkHttpClient,
) : RouteAdvisor {

    override suspend fun advise(prompt: String): String? = withContext(Dispatchers.IO) {
        val key = BuildConfig.ANTHROPIC_API_KEY
        if (key.isBlank()) return@withContext null

        try {
            val body = JsonObject().apply {
                addProperty("model", "claude-haiku-4-5-20251001")
                addProperty("max_tokens", 400)
                add(
                    "messages",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", prompt)
                            },
                        )
                    },
                )
            }.toString()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.body?.string() ?: return@use null
                JsonParser.parseString(text).asJsonObject
                    .getAsJsonArray("content")
                    ?.firstOrNull()?.asJsonObject
                    ?.get("text")?.asString
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
