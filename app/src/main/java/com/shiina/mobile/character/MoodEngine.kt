package com.shiina.mobile.character

import com.shiina.mobile.data.db.MoodState
import com.shiina.mobile.data.db.MoodStateDao
import com.shiina.mobile.debug.AppDebugServer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Dynamic Mood Balancing steps 2-4: a persistent valence-arousal vector that
 * real events nudge and time decays — mood is no longer "whatever the last
 * model round said".
 *
 * - Events (talk, dismissal, praise, neglect...) apply fixed deltas.
 * - Every update decays exponentially toward baseline (6h half-life), so
 *   emotions fade unless reinforced.
 * - Discrete moods are derived from the nearest anchor; intensity scales how
 *   strongly the mood prompt tells her to express it.
 * - Model expression (Gemini picking a mood) nudges the vector 35% toward
 *   that anchor instead of overwriting state.
 *
 * Fully local, zero API cost. All failures are swallowed — mood must never
 * break a decision or Talk round.
 */
class MoodEngine(private val dao: MoodStateDao) {

    enum class Event {
        USER_TALK, DISMISSED, TALKED_BACK, ACTED, NEGLECT,
        PRAISE, COMPLAINT, MUSIC_PLAYING, LATE_NIGHT, LOW_BATTERY, BOOT,
    }

    private data class Anchor(val mood: String, val v: Double, val a: Double)

    private val anchors = listOf(
        Anchor("calm", 0.10, 0.20),
        Anchor("warm", 0.50, 0.30),
        Anchor("candid", 0.30, 0.60),
        Anchor("firm", -0.10, 0.60),
        Anchor("pouty", -0.40, 0.50),
        Anchor("excited", 0.70, 0.80),
        Anchor("melancholy", -0.30, 0.15),
    )

    @Volatile private var cache: MoodState? = null

    suspend fun current(): MoodState {
        cache?.let { return it }
        val loaded = runCatching { dao.get() }.getOrNull()
        val s = loaded ?: MoodState(
            id = 1, valence = BASE_V, arousal = BASE_A, mood = "calm",
            intensity = 0.0, reason = "baseline",
            updatedMillis = System.currentTimeMillis(),
        )
        cache = s
        return s
    }

    suspend fun currentMood(): String = current().mood

    /** User spoke: attention boost + light keyword sentiment scan. */
    suspend fun applyUserText(text: String) {
        val t = text.lowercase()
        when {
            PRAISE_WORDS.any { it in t } -> applyEvent(Event.PRAISE, "praise")
            COMPLAINT_WORDS.any { it in t } -> applyEvent(Event.COMPLAINT, "complaint")
            else -> applyEvent(Event.USER_TALK, "talk")
        }
    }

    /** Step 4: model chose a mood -> nudge toward that anchor, never overwrite. */
    suspend fun applyModelExpression(mood: String) {
        val anchor = anchors.firstOrNull { it.mood == mood.lowercase() } ?: return
        shift(anchor.v * MODEL_PULL, anchor.a * MODEL_PULL, "expressed ${anchor.mood}")
    }

    suspend fun applyEvent(event: Event, note: String = "") {
        val (dv, da) = when (event) {
            Event.USER_TALK -> 0.12 to 0.08
            Event.DISMISSED -> -0.20 to -0.05
            Event.TALKED_BACK -> 0.10 to 0.05
            Event.ACTED -> 0.18 to 0.02
            Event.NEGLECT -> -0.30 to 0.10
            Event.PRAISE -> 0.25 to 0.10
            Event.COMPLAINT -> -0.25 to 0.12
            Event.MUSIC_PLAYING -> 0.05 to 0.03
            Event.LATE_NIGHT -> -0.08 to 0.06
            Event.LOW_BATTERY -> -0.05 to 0.10
            Event.BOOT -> 0.05 to 0.05
        }
        shift(dv, da, note.ifEmpty { event.name.lowercase() })
    }

    /** Decay toward baseline, apply delta, derive mood, persist. */
    private suspend fun shift(dv: Double, da: Double, reason: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val prev = current()
            val hours = (now - prev.updatedMillis) / 3_600_000.0
            val decay = 1.0 - exp(-hours / HALF_LIFE_HOURS)
            val v = (prev.valence + (BASE_V - prev.valence) * decay + dv).coerceIn(-1.0, 1.0)
            val a = (prev.arousal + (BASE_A - prev.arousal) * decay + da).coerceIn(0.0, 1.0)
            val (mood, intensity) = derive(v, a)
            val next = MoodState(1, v, a, mood, intensity, reason.take(60), now)
            cache = next
            runCatching { dao.upsert(next) }
            AppDebugServer.log(
                "MOOD",
                "mood=$mood intensity=${"%.2f".format(intensity)} " +
                    "v=${"%.2f".format(v)} a=${"%.2f".format(a)} reason=$reason",
            )
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "MoodEngine shift failed: ${e.message}")
        }
    }

    /** Step 3: nearest anchor wins; below the calm floor she is just calm. */
    private fun derive(v: Double, a: Double): Pair<String, Double> {
        val intensity = ((abs(v) + a) / 2.0).coerceIn(0.0, 1.0)
        if (intensity < CALM_FLOOR) return "calm" to intensity
        val nearest = anchors.minByOrNull {
            sqrt((it.v - v) * (it.v - v) + (it.a - a) * (it.a - a))
        }
        return (nearest?.mood ?: "calm") to intensity
    }

    /** Step 5: injected into every prompt — state + expression strength. */
    suspend fun promptBlock(): String {
        val s = current()
        val pct = (s.intensity * 100).toInt().coerceIn(0, 100)
        return "EMOTIONAL STATE (persistent; fades with time unless reinforced): " +
            "mood=${s.mood}, intensity=$pct% (cause: ${s.reason.ifBlank { "ambient" }}). " +
            "Express this mood at roughly $pct% strength — let it color phrasing and " +
            "word choice, but never state the mood explicitly. Below 25% intensity " +
            "stay essentially neutral."
    }

    companion object {
        private const val BASE_V = 0.10
        private const val BASE_A = 0.25
        private const val HALF_LIFE_HOURS = 6.0
        private const val CALM_FLOOR = 0.18
        private const val MODEL_PULL = 0.35

        private val PRAISE_WORDS = setOf(
            "thank", "thanks", "salamat", "love it", "good job", "nice one",
            "awesome", "ganda", "galing", "perfect",
        )
        private val COMPLAINT_WORDS = setOf(
            "annoying", "shut up", "stop it", "hmp", "hmph", "whatever",
            "useless", "angry", "galit", "ayoko",
        )
    }
}