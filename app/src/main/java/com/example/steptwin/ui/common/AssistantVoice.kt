package com.example.steptwin.ui.common

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.steptwin.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** 발화 이벤트. natural=true 면 말벗(챗봇) 답변으로 ElevenLabs 목소리, false 면 내비 안내로 기기 TTS. */
data class Utterance(val text: String, val natural: Boolean = false)

/**
 * 음성 재생기.
 * - 내비 안내(natural=false): 즉시성·오프라인·비용 때문에 기기 내장 TTS.
 * - 말벗 답변(natural=true): ElevenLabs 로 자연스러운 목소리(키 없거나 실패하면 기기 TTS 폴백).
 */
class AssistantVoice(context: Context) {
    private val appContext = context.applicationContext
    private val device = KoreanTts(appContext)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey: String = BuildConfig.ELEVENLABS_API_KEY
    private val voiceId: String = BuildConfig.ELEVENLABS_VOICE_ID
    private val naturalEnabled: Boolean = apiKey.isNotBlank() && voiceId.isNotBlank()

    private var player: MediaPlayer? = null
    private var job: Job? = null

    /** 새 발화는 이전 발화를 끊고 시작(내비/말벗 음성이 겹치지 않도록). */
    fun speak(text: String, natural: Boolean) {
        val t = text.trim()
        if (t.isBlank()) return
        job?.cancel()
        stopPlayer()
        device.stop()

        if (!natural || !naturalEnabled) {
            device.speak(t, flush = true)
            return
        }

        job = scope.launch {
            val file = runCatching { withContext(Dispatchers.IO) { synthesize(t) } }.getOrNull()
            if (file == null || !isActive) {
                if (isActive) device.speak(t, flush = true) // 합성 실패 → 기기 TTS 폴백
                return@launch
            }
            playFile(file)
        }
    }

    private fun synthesize(text: String): File {
        val payload = mapOf(
            "text" to text,
            "model_id" to MODEL,
            "voice_settings" to mapOf(
                "stability" to 0.5,
                "similarity_boost" to 0.8,
            ),
        )
        val body = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .header("accept", "audio/mpeg")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("elevenlabs ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("empty tts body")
            val f = File(appContext.cacheDir, "assistant_tts.mp3")
            f.writeBytes(bytes)
            return f
        }
    }

    private fun playFile(file: File) {
        stopPlayer()
        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { p ->
                p.release()
                if (player === p) player = null
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) player = null
                true
            }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        }.onFailure {
            stopPlayer()
        }
    }

    private fun stopPlayer() {
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
    }

    fun shutdown() {
        scope.cancel()
        stopPlayer()
        device.shutdown()
    }

    private companion object {
        // 한국어 지원 다국어 모델.
        const val MODEL = "eleven_multilingual_v2"
    }
}

@Composable
fun rememberAssistantVoice(): AssistantVoice {
    val context = LocalContext.current
    val voice = remember { AssistantVoice(context) }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    return voice
}
