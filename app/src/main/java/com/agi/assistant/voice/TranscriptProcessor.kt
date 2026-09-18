package com.agi.assistant.voice

import java.util.Locale

/**
 * Pure (Android-free) handling of speech-recognition output.
 *
 * Keeps three things apart and never confuses them:
 *  - [Transcript.raw]       – exactly what the recognizer returned (kept for debugging / trace)
 *  - [Transcript.processed] – what the assistant receives: whitespace-tidied only; words are NOT rewritten
 *  - [Transcript.isFinal]   – partial (live preview only) vs final (may be submitted)
 *
 * Deliberately no spelling/grammar "correction": the assistant must understand what the user
 * actually said (Bengali, English or Banglish), not what the app guesses they meant.
 */
object TranscriptProcessor {

    data class Transcript(
        val raw: String,
        val processed: String,
        val isFinal: Boolean,
        /** Recognizer confidence of the chosen alternative (0..1), -1 when the engine gave none. */
        val confidence: Float = -1f,
        /** All alternatives the engine offered (raw), first = chosen. */
        val alternatives: List<String> = listOf(raw),
        /** BCP-47 tag requested from the recognizer. */
        val language: String = "",
    ) {
        val isEmpty: Boolean get() = processed.isEmpty()
        /** One safe line for logs: no secrets are ever in a transcript source, but keep it short. */
        fun trace(): String = "${if (isFinal) "FINAL" else "partial"} lang=$language conf=${"%.2f".format(Locale.US, confidence)} " +
            "raw=\"${raw.take(120)}\" processed=\"${processed.take(120)}\" alts=${alternatives.size}"
    }

    /**
     * Minimal, reversible-in-spirit normalisation: trims, collapses runs of whitespace, removes
     * zero-width / control characters some engines emit. Case, digits (Bengali or ASCII), script
     * and punctuation are preserved exactly.
     */
    fun normalize(raw: String): String =
        raw.replace(Regex("[\\u200B-\\u200D\\uFEFF\\p{Cntrl}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Builds a partial (live preview) transcript. Never submitted. */
    fun partial(raw: String, language: String = ""): Transcript =
        Transcript(raw, normalize(raw), isFinal = false, language = language)

    /**
     * Picks the final transcript from the engine's alternatives.
     * Android returns alternatives best-first; confidence scores (when present) are honoured so a
     * higher-scored later alternative is not ignored. Empty alternatives are skipped.
     */
    fun final(alternatives: List<String>?, confidences: FloatArray? = null, language: String = ""): Transcript? {
        val alts = alternatives.orEmpty()
        if (alts.isEmpty()) return null
        var bestIdx = -1; var bestScore = -1f
        for (i in alts.indices) {
            if (normalize(alts[i]).isEmpty()) continue
            val score = confidences?.getOrNull(i)?.takeIf { it >= 0f } ?: -1f
            val better = when {
                bestIdx < 0 -> true
                score >= 0f && bestScore >= 0f -> score > bestScore
                else -> false  // no scores: keep engine order
            }
            if (better) { bestIdx = i; bestScore = score }
        }
        if (bestIdx < 0) return null
        val raw = alts[bestIdx]
        return Transcript(raw, normalize(raw), isFinal = true, confidence = bestScore,
            alternatives = listOf(raw) + alts.filterIndexed { i, a -> i != bestIdx && normalize(a).isNotEmpty() }, language = language)
    }

    // ---- language -------------------------------------------------------------------

    const val LANG_AUTO = "auto"
    const val LANG_BENGALI = "bn-BD"
    const val LANG_ENGLISH = "en-US"

    /** Recognition-language choices shown in Settings (value → label). */
    val LANGUAGE_CHOICES: List<Pair<String, String>> = listOf(
        LANG_AUTO to "Auto (phone language)",
        LANG_BENGALI to "বাংলা (Bengali)",
        LANG_ENGLISH to "English",
    )

    /**
     * Resolves the tag handed to the recognizer. "auto" follows the phone locale (the previous
     * behaviour). Root cause on many devices: the phone is set to English, so Bengali speech was
     * decoded by an English model – an explicit choice fixes that without any text rewriting.
     */
    fun recognitionLanguage(setting: String?, deviceTag: String): String = when (setting.orEmpty().ifBlank { LANG_AUTO }) {
        LANG_AUTO -> deviceTag.ifBlank { LANG_ENGLISH }
        else -> setting!!
    }

    /** Which script the transcript is mostly in – used for trace only, never for rewriting. */
    fun script(text: String): String {
        val bn = text.count { it in '\u0980'..'\u09FF' }
        val latin = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when {
            bn > 0 && latin > 0 -> "mixed"
            bn > 0 -> "bengali"
            latin > 0 -> "latin"
            else -> "other"
        }
    }
}
