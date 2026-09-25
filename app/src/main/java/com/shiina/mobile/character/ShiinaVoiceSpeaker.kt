package com.shiina.mobile.character

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shiina.mobile.debug.AppDebugServer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android Text-To-Speech (TTS) engine for Shiina (Backlog B5).
 *
 * Provides:
 * - Natural pitch and rate modulation adapting dynamically to Shiina's emotional state
 *   and circadian scenario (e.g. hushed, calm, slightly pouty tone late at night; warm and lively during daytime).
 * - Markdown & symbol sanitization to prevent TTS from reading formatting syntax aloud.
 * - Queue flush capability so new conversational replies immediately take precedence.
 * - Thread-safe, non-blocking lifecycle management.
 */
class ShiinaVoiceSpeaker(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private var isMuted: Boolean = false
    private val clone: VoiceCloneEngine by lazy { VoiceCloneEngine(context.applicationContext) }
    @Volatile var cloneVoiceEnabled: Boolean = true

    init {
        runCatching {
            tts = TextToSpeech(context.applicationContext, this)
        }.onFailure { e ->
            AppDebugServer.log("VOICE_TTS", "Failed to construct TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            // Prefer US English or device default locale
            val result = engine.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    AppDebugServer.log("VOICE_TTS", "Speech started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    AppDebugServer.log("VOICE_TTS", "Speech finished: $utteranceId")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    AppDebugServer.log("VOICE_TTS", "Speech error on: $utteranceId")
                }
            })
            isInitialized.set(true)
            AppDebugServer.log("VOICE_TTS", "Shiina TextToSpeech initialized successfully (locale: ${engine.voice?.locale ?: "default"})")
        } else {
            AppDebugServer.log("VOICE_TTS", "TextToSpeech init failed with status: $status")
            isInitialized.set(false)
        }
    }

    /**
     * Speaks the conversational response aloud with emotional voice inflection.
     *
     * @param text The conversational reply text.
     * @param mood Current persona mood ('pouty', 'calm', 'candid', 'firm', 'warm', 'excited').
     * @param isLateNight Whether late-night circadian hours (11 PM - 5 AM) are active.
     */
    fun speak(text: String, mood: String = "calm", isLateNight: Boolean = false) {
        if (isMuted || text.isBlank()) return

        val cleanSpeech = sanitizeForSpeech(text)
        if (cleanSpeech.isBlank()) return

        // Cloned voice first, system TTS only as emergency fallback.
        if (cloneVoiceEnabled && VoiceCloneEngine.isClonePresent(context.applicationContext)) {
            clone.speak(cleanSpeech, VoiceCloneEngine.speedFor(mood, isLateNight))
            AppDebugServer.log("VOICE_TTS", "Routed to clone (mood=$mood, lateNight=$isLateNight)")
            return
        }
        if (!isInitialized.get()) return
        val engine = tts ?: return

        // Dynamic pitch and speech-rate modulation based on real-world situation & mood
        val (pitch, speechRate) = calculateVoiceAcoustics(mood, isLateNight)

        runCatching {
            engine.setPitch(pitch)
            engine.setSpeechRate(speechRate)
            val utteranceId = "shiina_" + System.currentTimeMillis()
            engine.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            AppDebugServer.log("VOICE_TTS", "Speaking (pitch=$pitch, rate=$speechRate, mood=$mood, lateNight=$isLateNight): \"${cleanSpeech.take(60)}...\"")
        }.onFailure { e ->
            AppDebugServer.log("VOICE_TTS", "Error during speak: ${e.message}")
        }
    }

    /**
     * Calculates the voice pitch and speech-rate multipliers.
     */
    fun calculateVoiceAcoustics(mood: String, isLateNight: Boolean): Pair<Float, Float> {
        return when {
            // Late night: gentler, calm, hushed, slightly pouty and deliberate pace
            isLateNight -> Pair(0.93f, 0.88f)
            mood.equals("pouty", ignoreCase = true) || mood.equals("sulky", ignoreCase = true) -> Pair(0.96f, 0.92f)
            mood.equals("firm", ignoreCase = true) || mood.equals("firm_warning", ignoreCase = true) -> Pair(1.0f, 0.98f)
            mood.equals("warm", ignoreCase = true) || mood.equals("excited", ignoreCase = true) -> Pair(1.12f, 1.04f)
            mood.equals("candid", ignoreCase = true) -> Pair(1.06f, 1.0f)
            mood.equals("melancholy", ignoreCase = true) -> Pair(0.92f, 0.86f)
            else -> Pair(1.05f, 1.0f) // Standard calm default
        }
    }

    /**
     * Strips markdown formatting, links, URLs, and code blocks so speech sounds completely natural.
     */
    fun sanitizeForSpeech(raw: String): String {
        var s = raw.trim()
        // Skip JSON leaks or debug strings if any slipped through
        if (s.startsWith("{") || s.contains("\"thought\":") || s.contains("\"status\":")) {
            return ""
        }
        // Remove code blocks
        s = s.replace(Regex("""```[\s\S]*?```"""), "")
        // Remove inline code
        s = s.replace(Regex("""`[^`]*`"""), "")
        // Convert markdown links [text](url) -> text
        s = s.replace(Regex("""\[([^\]]+)\]\([^\)]+\)"""), "$1")
        // Remove URLs
        s = s.replace(Regex("""https?://\S+"""), "")
        // Remove stage directions (*rolls eyes*, *sighs*) and [actions] entirely — never spoken
        s = s.replace(Regex("""\*[^\n*]+\*"""), "")
        s = s.replace(Regex("""\[[^\]\n]{1,60}\]"""), "")
        // Remove bold/italics
        s = s.replace(Regex("""\*{1,3}([^*]+)\*{1,3}"""), "$1")
        s = s.replace(Regex("""_{1,3}([^_]+)_{1,3}"""), "$1")
        // Remove headers
        s = s.replace(Regex("""^#+\s+""", RegexOption.MULTILINE), "")
        // Remove bullet markers
        s = s.replace(Regex("""^[-*+]\s+""", RegexOption.MULTILINE), "")
        // Remove excess emojis that read awkwardly
        s = s.replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
        // Clean whitespace
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    fun stop() {
        runCatching { tts?.stop() }
        runCatching { clone.stop() }
    }

    /** True once the clone model is on-device and the native engine loads. */
    fun isNeuralReady(): Boolean = VoiceCloneEngine.isClonePresent(context.applicationContext) && clone.isReady()

    /** Background-preloads the clone engine so first speech has no model-load pause. */
    fun prewarmNeural() {
        if (cloneVoiceEnabled) runCatching { clone.prewarm() }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) stop()
    }

    fun shutdown() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized.set(false)
            clone.release()
        }
    }
}
