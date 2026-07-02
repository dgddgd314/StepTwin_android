package com.example.steptwin.ui.common

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** 한국어 TTS 래퍼(안드로이드 내장 엔진, 추가 의존성/권한 없음). */
class KoreanTts(context: Context) {
    private var ready = false
    private var engine: TextToSpeech? = null

    init {
        val app = context.applicationContext
        engine = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = engine?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ready = result >= TextToSpeech.LANG_AVAILABLE
            }
        }
    }

    /** flush=true 면 진행 중 발화를 끊고 즉시, false 면 뒤에 이어서 발화. */
    fun speak(text: String, flush: Boolean = true) {
        val e = engine ?: return
        if (!ready || text.isBlank()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        e.speak(text, mode, null, text.hashCode().toString())
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}

@Composable
fun rememberKoreanTts(): KoreanTts {
    val context = LocalContext.current
    val tts = remember { KoreanTts(context) }
    DisposableEffect(tts) { onDispose { tts.shutdown() } }
    return tts
}
