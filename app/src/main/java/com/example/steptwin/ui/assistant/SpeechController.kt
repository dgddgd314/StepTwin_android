package com.example.steptwin.ui.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/** 한국어 음성 인식(STT) 래퍼. 마이크 권한 필요. */
class SpeechController(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val available = SpeechRecognizer.isRecognitionAvailable(appContext)
    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = available

    fun startListening() {
        if (!available) return
        // 매 호출마다 새 인스턴스(안정성)
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onListening(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onListening(false)
            override fun onError(error: Int) = onListening(false)
            override fun onResults(results: Bundle?) {
                onListening(false)
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                list?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onResult)
            }
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.startListening(intent)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}

@Composable
fun rememberSpeechController(
    onResult: (String) -> Unit,
    onListeningChange: (Boolean) -> Unit,
): SpeechController {
    val context = LocalContext.current
    val latestResult by rememberUpdatedState(onResult)
    val latestListening by rememberUpdatedState(onListeningChange)
    val controller = remember {
        SpeechController(
            context = context,
            onResult = { latestResult(it) },
            onListening = { latestListening(it) },
        )
    }
    DisposableEffect(controller) { onDispose { controller.destroy() } }
    return controller
}
