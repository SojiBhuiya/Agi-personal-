package com.agi.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Voice input foundation based on the on-device/system SpeechRecognizer.
 * Emits partial results while the user speaks and a final transcript.
 */
class VoiceInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onLevel(rms: Float)
        fun onError(message: String)
        fun onEnd()
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String? = null) {
        if (!isAvailable) { listener.onError("Speech recognition is not available on this device."); return }
        stop()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; listener.onReady() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) { listener.onLevel(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false }
                override fun onError(error: Int) {
                    isListening = false
                    listener.onError(describe(error))
                    listener.onEnd()
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) listener.onResult(text) else listener.onError("I didn't hear anything.")
                    listener.onEnd()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { listener.onPartial(it) }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language ?: Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        recognizer = null
        isListening = false
    }

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "Recognizer stopped."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error during speech recognition."
        SpeechRecognizer.ERROR_NO_MATCH -> "I couldn't understand that."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy, try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        else -> "Speech recognition error ($code)."
    }
}
