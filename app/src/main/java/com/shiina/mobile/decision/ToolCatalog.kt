package com.shiina.mobile.decision

/**
 * On-demand toolset catalog.
 * Instead of dumping all tool specifications into the base prompt on every turn,
 * tools are grouped into domain toolsets that the agent queries on-demand via GET_TOOLSET.
 */
object ToolCatalog {

    const val TOOLSET_MEDIA = "media"
    const val TOOLSET_APPS = "apps"
    const val TOOLSET_DEVICE = "device"
    const val TOOLSET_WEB = "web"
    const val TOOLSET_PLANNER = "planner"

    val AVAILABLE_TOOLSETS = listOf(
        TOOLSET_MEDIA,
        TOOLSET_APPS,
        TOOLSET_DEVICE,
        TOOLSET_WEB,
        TOOLSET_PLANNER,
    )

    /**
     * Concise overview of available toolsets shown in the base prompt.
     * Keeps the default prompt clean and lightweight without dumping specific tool calls.
     */
    val TOOLSET_OVERVIEW = """
        AVAILABLE TOOLSETS (Use GET_TOOLSET with {"toolset":"<name>"}):
        - "apps": Launch/close, foreground, URLs, intents, INSPECT_SCREEN, READ_SCREEN_TEXT, TAKE_SCREENSHOT, tap/swipe/keys, WAIT.
        - "device": Torch, settings, clipboard, senses, volume, ringer, vibrate, notifications.
        - "media": Audio search, playback, GET_CURRENT_PLAYING, volume.
        - "web": Web search and reader.
        - "planner": Reminders, goals, alarms, timers, REMEMBER, macros (EXECUTE_PROCEDURE, LEARN_PROCEDURE, GET_PROCEDURE, OPTIMIZE_PROCEDURE, REMOVE_PROCEDURE_STEP).
        - "NONE": Direct reply when no device task needed.
    """.trimIndent()

    /**
     * Returns detailed tool definitions, parameter schemas, and instructions for a requested toolset.
     */
    fun getToolset(name: String): String {
        val target = name.lowercase().trim()
        return when (target) {
            TOOLSET_MEDIA, "music", "audio" -> MEDIA_TOOLSET
            TOOLSET_APPS, "app" -> APPS_TOOLSET
            TOOLSET_DEVICE, "system", "hardware" -> DEVICE_TOOLSET
            TOOLSET_WEB, "search", "internet" -> WEB_TOOLSET
            TOOLSET_PLANNER, "goals", "reminders", "memory" -> PLANNER_TOOLSET
            "all" -> listOf(MEDIA_TOOLSET, APPS_TOOLSET, DEVICE_TOOLSET, WEB_TOOLSET, PLANNER_TOOLSET).joinToString("\n\n")
            else -> "Unknown toolset '$name'. Available toolsets: ${AVAILABLE_TOOLSETS.joinToString(", ")}"
        }
    }

    private val MEDIA_TOOLSET = """
        === TOOLSET: MEDIA ===
        Tools:
        1. SEARCH_MUSIC: {"tool":"SEARCH_MUSIC","tool_args":{"query":"<song or artist>"}} - Searches local storage audio files.
        2. PLAY_MUSIC: {"tool":"PLAY_MUSIC","tool_args":{"query":"<audio query or title>","player":"<optional player or package>"}} - Plays track locally or via installed media provider.
        3. MEDIA_CONTROL: {"tool":"MEDIA_CONTROL","tool_args":{"action":"<pause|resume|next|prev|stop>"}} - Manages active playback.
        4. GET_CURRENT_PLAYING: {"tool":"GET_CURRENT_PLAYING"} - Returns current track title, artist, album, player app, and playback status.
        5. VOLUME_CONTROL: {"tool":"VOLUME_CONTROL","tool_args":{"action":"<restore|up|down|mute|unmute|set>","level":<0-100>}} - Adjusts audio volume.
    """.trimIndent()

    private val APPS_TOOLSET = """
        === TOOLSET: APPS ===
        Tools:
        1. OPEN_APP: {"tool":"OPEN_APP","tool_args":{"app":"<app name or package>"}} - Launches installed app.
        2. CLOSE_APP: {"tool":"CLOSE_APP"} - Returns to home screen, closing foreground activity.
        3. GET_FOREGROUND_APP: {"tool":"GET_FOREGROUND_APP"} - Returns the exact app package and name currently open on screen.
        4. LIST_APPS: {"tool":"LIST_APPS","tool_args":{"filter":"<optional: music|browser|media|social|all|keyword>"}} - Discovers installed apps.
        5. SEARCH_APP: {"tool":"SEARCH_APP","tool_args":{"app":"<app name or package>","query":"<search term>"}} - In-app search query execution.
        6. OPEN_URL: {"tool":"OPEN_URL","tool_args":{"url":"<full url or domain>"}} - Opens website directly in user's default browser.
        7. SYSTEM_INTENT: {"tool":"SYSTEM_INTENT","tool_args":{"action":"<system action verb>","text":"<action payload or URI>"}} - Launches Android system intents.
        8. INSPECT_SCREEN: {"tool":"INSPECT_SCREEN"} - Dumps active window UI accessibility tree with element IDs, texts, types, coordinates, and bounds.
        9. READ_SCREEN_TEXT: {"tool":"READ_SCREEN_TEXT"} - Instantly extracts all readable text visible on the current screen (no OCR lag).
        10. TAKE_SCREENSHOT: {"tool":"TAKE_SCREENSHOT"} - Captures visual screenshot and attaches the image on your next turn for visual verification.
        11. TAP_SCREEN: {"tool":"TAP_SCREEN","tool_args":{"x":<0-1000>,"y":<0-1000>,"text":"<optional label>","element_id":<optional id>,"is_long_press":<optional true/false>}} - Taps UI via coordinates (0-1000 normalized), text, or element ID.
        12. SWIPE_SCREEN: {"tool":"SWIPE_SCREEN","tool_args":{"startX":<0-1000>,"startY":<0-1000>,"endX":<0-1000>,"endY":<0-1000>,"direction":"<optional: up|down|left|right>"}} - Scrolls or swipes screen.
        13. INPUT_TEXT: {"tool":"INPUT_TEXT","tool_args":{"text":"<text to type>"}} - Types into currently focused input field.
        14. CLEAR_TEXT: {"tool":"CLEAR_TEXT"} - Clears text from currently focused input field.
        15. PRESS_KEY: {"tool":"PRESS_KEY","tool_args":{"action":"<back|home|recents|notifications|quick_settings|enter|search|dismiss_keyboard|volume_up|volume_down|lock_screen>"}} - System navigation or key action.
        16. WAIT: {"tool":"WAIT","tool_args":{"level":<seconds 1-10>}} - Pauses execution calmly to allow UI loading, splash screens, spinner loaders, or animations to settle before acting.
    """.trimIndent()

    private val DEVICE_TOOLSET = """
        === TOOLSET: DEVICE ===
        Tools:
        1. TOGGLE_FLASHLIGHT: {"tool":"TOGGLE_FLASHLIGHT","tool_args":{"action":"<on|off|toggle>"}} - Turns camera flashlight / torch on or off.
        2. OPEN_SETTINGS: {"tool":"OPEN_SETTINGS","tool_args":{"action":"<wifi|bluetooth|display|sound|battery|apps|accessibility|date|privacy|storage|network|main>"}} - Opens Android settings panel directly.
        3. MANAGE_CLIPBOARD: {"tool":"MANAGE_CLIPBOARD","tool_args":{"action":"<read|copy>","text":"<text to copy if action is copy>"}} - Reads or sets clipboard text.
        4. GET_DEVICE_STATE: {"tool":"GET_DEVICE_STATE"} - Inspects battery, charging, network, audio routing, media/ringer volume, screen state, and DND.
        5. VOLUME_CONTROL: {"tool":"VOLUME_CONTROL","tool_args":{"action":"<restore|up|down|mute|unmute|set>","level":<0-100>}} - Manages volume.
        6. SET_RINGER_MODE: {"tool":"SET_RINGER_MODE","tool_args":{"action":"<normal|vibrate|silent>"}} - Sets system ringer mode.
        7. VIBRATE_DEVICE: {"tool":"VIBRATE_DEVICE","tool_args":{"level":<optional duration ms, default 200>}} - Vibrates the device for haptic alert.
        8. SEND_NOTIFICATION: {"tool":"SEND_NOTIFICATION","tool_args":{"title":"<notification title>","text":"<message>"}} - Posts a system notification.
        9. PRESS_KEY: {"tool":"PRESS_KEY","tool_args":{"action":"<back|home|recents|notifications|quick_settings|enter|search|dismiss_keyboard|volume_up|volume_down|lock_screen>"}} - System navigation.
        10. SET_MODE: {"tool":"SET_MODE","tool_args":{"mode":"<WANDER|STAY|VANISH>"}} - Controls companion avatar overlay.
    """.trimIndent()

    private val WEB_TOOLSET = """
        === TOOLSET: WEB ===
        Tools:
        1. SEARCH_WEB: {"tool":"SEARCH_WEB","tool_args":{"query":"<search query>"}} - Searches the web for external info.
        2. READ_URL: {"tool":"READ_URL","tool_args":{"url":"<full url>"}} - Fetches/reads webpage article text content.
    """.trimIndent()

    private val PLANNER_TOOLSET = """
        === TOOLSET: PLANNER ===
        Tools:
        1. REMEMBER: {"tool":"REMEMBER","tool_args":{"key":"<concept>","value":"<fact>"}} - Saves persistent user fact or preference.
        2. FORGET: {"tool":"FORGET","tool_args":{"key":"<concept to forget>"}} - Deletes remembered fact.
        3. LIST_FACTS: {"tool":"LIST_FACTS"} - Lists all remembered facts and preferences.
        4. LOG_GOAL: {"tool":"LOG_GOAL","tool_args":{"title":"<goal title>"}} - Logs personal user goal.
        5. CHECK_GOALS: {"tool":"CHECK_GOALS"} - Reviews active goals, progress, and stalled items.
        6. COMPLETE_GOAL: {"tool":"COMPLETE_GOAL","tool_args":{"title":"<goal title>"}} - Marks goal complete.
        7. SET_REMINDER: {"tool":"SET_REMINDER","tool_args":{"text":"<description with date/time>"}} - Schedules notification reminder.
        8. LIST_REMINDERS: {"tool":"LIST_REMINDERS"} - Lists active reminders.
        9. CANCEL_REMINDER: {"tool":"CANCEL_REMINDER","tool_args":{"text":"<reminder text or id>"}} - Cancels a reminder.
        10. SET_TRIGGER: {"tool":"SET_TRIGGER","tool_args":{"text":"<commitment description with target time>"}} - Schedules a persistent overlay that cannot be dismissed until the user replies. Use when the user requests mandatory scheduled accountability.
        11. LIST_TRIGGERS: {"tool":"LIST_TRIGGERS"} - Lists pending locked triggers.
        12. CANCEL_TRIGGER: {"tool":"CANCEL_TRIGGER","tool_args":{"text":"<trigger id or text>"}} - Cancels a pending trigger.
        13. SET_ALARM: {"tool":"SET_ALARM","tool_args":{"hour":<0-23>,"minute":<0-59>,"title":"<optional label>"}} - Sets standard Android alarm.
        14. SET_TIMER: {"tool":"SET_TIMER","tool_args":{"seconds":<seconds>,"title":"<optional label>"}} - Sets countdown timer.
        15. GET_PROCEDURE: {"tool":"GET_PROCEDURE","tool_args":{"key":"<procedure id or title>"}} - Inspects exact steps of a learned app navigation workflow.
        16. LEARN_PROCEDURE: {"tool":"LEARN_PROCEDURE","tool_args":{"title":"<procedure title>","app":"<app label/pkg>","query":"<goal>","text":"<step1 | step2 | step3>"}} - Saves a learned app navigation procedure.
        17. REMOVE_PROCEDURE_STEP: {"tool":"REMOVE_PROCEDURE_STEP","tool_args":{"key":"<procedure id>","level":<step_number_to_delete>}} - Removes an obsolete or redundant step when a faster way is found.
        18. UPDATE_PROCEDURE_STEP: {"tool":"UPDATE_PROCEDURE_STEP","tool_args":{"key":"<procedure id>","level":<step_number>,"action":"<new action>","query":"<new target>","text":"<note>"}} - Updates an existing step in a procedure.
        19. OPTIMIZE_PROCEDURE: {"tool":"OPTIMIZE_PROCEDURE","tool_args":{"key":"<procedure id>","text":"<streamlined steps>","title":"<optimization rationale>"}} - Replaces procedure with an optimized sequence.
        20. LIST_PROCEDURES: {"tool":"LIST_PROCEDURES","tool_args":{"query":"<optional filter>"}} - Lists all learned app navigation procedures.
        21. FORGET_PROCEDURE: {"tool":"FORGET_PROCEDURE","tool_args":{"key":"<procedure id or title>"}} - Deletes a procedure from memory.
        22. EXECUTE_PROCEDURE: {"tool":"EXECUTE_PROCEDURE","tool_args":{"key":"<procedure id or title>","query":"<optional target parameter like search terms or time>"}} - Executes learned workflow steps directly on the device in fast-path sequence.
    """.trimIndent()
}
