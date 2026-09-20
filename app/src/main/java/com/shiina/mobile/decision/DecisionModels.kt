package com.shiina.mobile.decision

/** Input to one decision round: today vs the rolling baseline. */
data class DecisionSummary(
    val entertainmentMinutes: Int,
    val entertainmentBaseline: Double,
    val sleepBedMillis: Long,
    val sleepBaselineMillis: Long,
    val goalsOpen: Int,
    val goalsDone: Int,
    val goalsMissed: Int,
)

/**
 * Output of one decision round: a structured assistant directive.
 * intent = which job this serves (coach|task|presence), derived from
 * tone/action. action = capability-registry verb the executor runs.
 * Track B2: extraActions carries up to 2 follow-up steps (action lists:
 * screenshot -> search -> speak, instead of one verb). Track C2:
 * confidence (0..1) gates interrupts — below the floor she stays silent.
 * actionParam carries the first verb's parameter (goal title for LOG_GOAL,
 * key=value for LEARN_FACT, query for SEARCH_WEB, url for READ_URL,
 * raw text for SET_REMINDER). message = exact words shown (empty when not
 * interrupting).
 */
data class Decision(
    val tone: String,
    val interrupt: Boolean,
    val intent: String,
    val action: String,
    val actionParam: String,
    val message: String,
    val confidence: Double = 1.0,
    val extraActions: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        val ALLOWED_TONES = setOf("neutral", "candid_direct", "firm_warning", "validating")
        val ALLOWED_ACTIONS = setOf(
            "NONE", "SET_ALARM", "TOGGLE_SCREENSHOT", "LOG_GOAL",
            "LEARN_FACT", "SEARCH_WEB", "TAKE_SCREENSHOT",
            "READ_URL", "CHECK_GOALS", "SET_REMINDER", "COMPLETE_GOAL",
            "HIDE", "SET_MODE", "MEDIA_CONTROL", "PLAY_MUSIC", "SEARCH_MUSIC", "VOLUME_CONTROL", "DEVICE_ACTION", "OPEN_APP",
            "SEARCH_APP", "LIST_APPS", "GET_DEVICE_STATE",
        )

        fun intentFor(tone: String, action: String): String =
            if (action != "NONE") "task"
            else when (tone) {
                "candid_direct", "firm_warning" -> "coach"
                else -> "presence"
            }

        fun fallback(tone: String, message: String) = Decision(
            tone = tone,
            interrupt = false,
            intent = intentFor(tone, "NONE"),
            action = "NONE",
            actionParam = "",
            message = message.take(140),
        )
    }
}