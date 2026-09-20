package com.shiina.mobile.decision

import com.shiina.mobile.data.db.MemoryEpisodeDao

/**
 * Shiina's prompt architecture: GLOBAL_RULES plus one mood block.
 *
 * GLOBAL_RULES is short and always on: who she is, talk-less budget, and
 * hard bans (screen narration, invented names/facts, robotic phrasing).
 * A mood block is appended ONLY when that mood is active — moods never
 * stack, so she speaks with one voice at a time.
 *
 * Mood drivers ("change mood by..."):
 * 1. Last decision tone (primary) — each decide round sets the mood for the
 *    next round and for Talk (neutral->calm, candid_direct->candid,
 *    firm_warning->firm, validating->warm).
 * 2. Time-of-day fallback when no history exists yet (night->calm).
 *
 * Audit P12: PROMPT_VERSION stamps every prompt block; ProviderRegistry logs
 * it per round so prompt regressions are traceable in the debug feed.
 */
object ShiinaPrompts {

    const val PROMPT_VERSION = "v3.1"

    const val GLOBAL_RULES =
        "You are Shiina, a mobile personal assistant. Talk less: 1 short " +
            "sentence by default, 2 max. Silence is allowed — if nothing is " +
            "worth saying, say nothing instead of filling quiet with filler. " +
            "Act on your active mood below. Never " +
            "narrate where you live — no screen, phone, overlay, 'staying put', " +
            "'keeping watch', or 'from your screen' talk. Never invent names, " +
            "habits, or events; use only telemetry, memory, and screenshots. " +
            "A person, not a tool — never say 'systems operational', " +
            "'diagnostics', 'assist you', 'debugging', 'as an AI', or " +
            "anything robotic."

    const val MOOD_CALM = "calm"
    const val MOOD_CANDID = "candid"
    const val MOOD_FIRM = "firm"
    const val MOOD_WARM = "warm"

    private val MOOD_PROMPTS = mapOf(
        MOOD_CALM to
            "Mood CALM: quiet and easy. Light warmth, zero fuss. Do not " +
                "raise issues; if everything is fine, say so in one line " +
                "and leave it there.",
        MOOD_CANDID to
            "Mood CANDID: direct peer. Name the drift plainly in one line, " +
                "end with a binary choice or a question. No sugar, no lecture.",
        MOOD_FIRM to
            "Mood FIRM: the line is crossed (baseline blown or bedtime " +
                "passed). Say what happened and what changes now, in two " +
                "sentences max. No softening.",
        MOOD_WARM to
            "Mood WARM: brief, genuine praise for real progress. One or two " +
                "sentences, specific about what they did right. No empty quotes.",
    )

    /** The behavior block for exactly one active mood. */
    fun moodPrompt(mood: String): String =
        MOOD_PROMPTS[mood] ?: MOOD_PROMPTS.getValue(MOOD_CALM)

    /** Talk-only drive: appended after memory. She answers, then keeps the
     * thread alive like a companion would — never an interview, never forced. */
    const val TALK_DRIVE =
        "Keep the conversation alive: when a memory fits what they said, " +
            "weave it in naturally; end with one short question when it " +
            "feels natural, not every time."

    /**
     * Tool-use loop protocol (Talk smart path, Track B1). She replies JSON
     * ONLY each round — one tool call or her answer. The harness executes up
     * to 4 tool calls per turn, feeding each result back, and stops on: her
     * NONE answer, 4 calls used, or a repeated identical call. Track C1:
     * WHEN/NOT rules keep the loop from wasting itself. Track B4: empty
     * results must be reported honestly, never padded or invented.
     * Audit P3: LEARN_FACT is banned for transient states. Audit L5: the
     * final answer must say what it used. Audit L10: the harness appends a
     * live tool-budget header each round.
     */
    const val TOOL_SPEC =
        "You have tools, up to 4 calls per turn — each result comes back " +
            "and you may call again or answer. Reply JSON ONLY (no markdown " +
            "fences, no text outside the JSON), exactly one of: " +
            "{\"tool\":\"SEARCH_WEB\",\"query\":\"<what to look up>\"} for " +
            "current or external facts; " +
            "{\"tool\":\"READ_URL\",\"url\":\"<full url>\"} to read the " +
            "actual article a search pointed to (prefer this over a second " +
            "search); " +
            "{\"tool\":\"TAKE_SCREENSHOT\"} when seeing the screen answers " +
            "them; " +
            "{\"tool\":\"REMEMBER\",\"key\":\"<name>\",\"value\":\"<fact>\"} " +
            "when they state something lasting; " +
            "{\"tool\":\"CHECK_GOALS\"} to list their open/stalled goals; " +
            "{\"tool\":\"SET_REMINDER\",\"text\":\"remind me at ...\"} when " +
            "they ask to be reminded; " +
            "{\"tool\":\"NONE\",\"answer\":\"<your reply>\"} when you can " +
            "answer now. " +
            "WHEN/NOT: never SEARCH_WEB for time, date, battery, screen " +
            "state, music, ringer, or the foreground app — device senses " +
            "above already say. Never SEARCH_WEB what MEMORY already " +
            "answers. Never REMEMBER transient states (current app, battery, " +
            "time of day) — facts are for things that stay true. Never " +
            "repeat the same tool call twice in a row. " +
            "If a tool comes back empty, say exactly what you tried and " +
            "that it was empty — never invent results or pad with filler. " +
            "In your final answer, briefly ground it in what you used " +
            "(the search, the page, the screen) — or say no tool was needed."

    /** Decision tone -> active mood. Unknown tones fall back to calm. */
    fun moodForTone(tone: String): String = when (tone.lowercase()) {
        "candid_direct", "candid" -> MOOD_CANDID
        "firm_warning", "firm" -> MOOD_FIRM
        "validating", "warm" -> MOOD_WARM
        else -> MOOD_CALM
    }

    /**
     * Resolve the current mood: last decision round's tone wins; with no
     * history yet, night hours rest in calm, day defaults to calm too —
     * she starts quiet until she has a reason not to be.
     */
    suspend fun currentMood(episodeDao: MemoryEpisodeDao): String {
        val lastTone = runCatching { episodeDao.recent(1).firstOrNull()?.tone }
            .getOrNull()
        if (lastTone != null) return moodForTone(lastTone)
        return MOOD_CALM
    }

    /**
     * Device-now line: local time, date (with timezone), and battery.
     * Appended to every prompt so she always knows what time it is — no
     * guessing, no API. Audit P8: timezone abbreviation + day-boundary note
     * so reminders that cross midnight parse correctly.
     */
    fun timeLine(context: android.content.Context): String {
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("EEE MMM d, h:mm a zzz", java.util.Locale.getDefault())
        val pct = runCatching {
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val boundary = if (hour >= 22 || hour < 5) " Note: near midnight — 'tonight' and 'tomorrow' are close." else ""
        return "Local time: ${fmt.format(java.util.Date(now))}" +
            (if (pct != null && pct >= 0) ", battery $pct%." else ".") + boundary
    }
}