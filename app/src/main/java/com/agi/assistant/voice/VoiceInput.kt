package com.agi.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Voice input based on the system SpeechRecognizer (on-device / Google speech services).
 * Emits partial results (preview only) while the user speaks and one final transcript, chosen by
 * [TranscriptProcessor] from the engine's alternatives. Recognition language comes from Settings
 * (Auto = phone locale, বাংলা, English) – see [TranscriptProcessor.recognitionLanguage].
 * Trace: `adb logcat -s VoiceInput` shows start / partial / FINAL lines (no secrets involved).
 */
class VoiceInput(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onReady()
        /** Live preview only – never submit this. */
        fun onPartial(text: String)
        /** Final recognizer result (processed text; [VoiceInput.lastFinal] keeps the raw transcript). */
        fun onResult(text: String)
        fun onLevel(rms: Float)
        fun onError(message: String)
        fun onEnd()
    }

    private var recognizer: SpeechRecognizer? = null
    var isListening = false
        private set
    /** Last final transcript (raw + processed + confidence) for debugging; null until a result arrived. */
    @Volatile var lastFinal: TranscriptProcessor.Transcript? = null
        private set
    private var requestedLanguage: String = ""
    private var sessionId = 0

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: String? = null) {
        if (!isAvailable) { listener.onError("Speech recognition is not available on this device."); return }
        stop()
        val session = ++sessionId
        val lang = TranscriptProcessor.recognitionLanguage(language, Locale.getDefault().toLanguageTag())
        requestedLanguage = lang
        lastFinal = null
        var delivered = false   // guards against a late duplicate onResults/onError for the same session
        Log.d(TAG, "stt#$session start lang=$lang")
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { isListening = true; Log.d(TAG, "stt#$session ready"); listener.onReady() }
                override fun onBeginningOfSpeech() { Log.d(TAG, "stt#$session speech began") }
                override fun onRmsChanged(rmsdB: Float) { listener.onLevel(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { isListening = false; Log.d(TAG, "stt#$session speech ended") }
                override fun onError(error: Int) {
                    isListening = false
                    if (delivered) return
                    delivered = true
                    Log.d(TAG, "stt#$session error $error (${describe(error)})")
                    listener.onError(describe(error))
                    listener.onEnd()
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    if (delivered) return
                    delivered = true
                    val alts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val t = TranscriptProcessor.final(alts, conf, lang)
                    lastFinal = t
                    if (t != null) {
                        Log.d(TAG, "stt#$session ${t.trace()} script=${TranscriptProcessor.script(t.processed)}")
                        listener.onResult(t.processed)
                    } else {
                        Log.d(TAG, "stt#$session FINAL empty")
                        listener.onError("I didn't hear anything.")
                    }
                    listener.onEnd()
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val raw = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    val t = TranscriptProcessor.partial(raw, lang)
                    if (t.isEmpty) return
                    Log.v(TAG, "stt#$session ${t.trace()}")
                    listener.onPartial(t.processed)   // preview only; never submitted
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        recognizer?.startListening(intent)
    }

    fun stop() {
        recognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        recognizer = null
        isListening = false
    }

    companion object { private const val TAG = "VoiceInput" }

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
