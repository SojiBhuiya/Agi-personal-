package com.agi.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Thin wrapper around Android TextToSpeech with lazy init and a queue. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    @Volatile var enabled = true

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                pending.forEach { speakNow(it) }
                pending.clear()
            }
        }
    }

    fun speak(context: Context, text: String) {
        if (!enabled || text.isBlank()) return
        init(context)
        if (ready) speakNow(text) else pending += text
    }

    private fun speakNow(text: String) {
        tts?.speak(text.take(600), TextToSpeech.QUEUE_ADD, null, "agi_" + System.nanoTime())
    }

    fun stop() { tts?.stop() }

    fun shutdown() { tts?.shutdown(); tts = null; ready = false }
}
