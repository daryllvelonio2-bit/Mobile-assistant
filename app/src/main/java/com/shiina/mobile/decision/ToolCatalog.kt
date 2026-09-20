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
        AVAILABLE TOOLSETS (Use GET_TOOLSET with {"toolset": "<name>"} to inspect tools and instructions):
        - "media": Music search, audio playback, playback controls (pause/resume/skip), and volume adjustments.
        - "apps": Application launcher, in-app search, screen capture verification, and automated screen taps/swipes/typing.
        - "device": Live audio/ringer state, screen capture, automated taps/gestures, volume control, and companion avatar mode.
        - "web": Google web search and webpage content reader.
        - "planner": Personal reminder scheduling and goal tracking/completion.
        - "NONE": Direct conversational response when no device task is needed.
    """.trimIndent()

    /**
     * Returns detailed tool definitions, parameter schemas, and instructions for a requested toolset.
     */
    fun getToolset(name: String): String {
        val target = name.lowercase().trim()
        return when (target) {
            TOOLSET_MEDIA, "music", "audio" -> MEDIA_TOOLSET
            TOOLSET_APPS, "app" -> APPS_TOOLSET
            TOOLSET_DEVICE, "system" -> DEVICE_TOOLSET
            TOOLSET_WEB, "search", "internet" -> WEB_TOOLSET
            TOOLSET_PLANNER, "goals", "reminders" -> PLANNER_TOOLSET
            "all" -> listOf(MEDIA_TOOLSET, APPS_TOOLSET, DEVICE_TOOLSET, WEB_TOOLSET, PLANNER_TOOLSET).joinToString("\n\n")
            else -> "Unknown toolset '$name'. Available toolsets: ${AVAILABLE_TOOLSETS.joinToString(", ")}"
        }
    }

    private val MEDIA_TOOLSET = """
        === TOOLSET: MEDIA ===
        Tools:
        1. SEARCH_MUSIC: {"tool":"SEARCH_MUSIC","tool_args":{"query":"<song or artist>"}} - Searches local storage.
        2. PLAY_MUSIC: {"tool":"PLAY_MUSIC","tool_args":{"query":"<song/artist>","player":"<optional: spotify|youtube|auto>"}} - Plays track locally or streams.
        3. MEDIA_CONTROL: {"tool":"MEDIA_CONTROL","tool_args":{"action":"<pause|resume|next|prev|stop>"}} - Manages active playback.
        4. VOLUME_CONTROL: {"tool":"VOLUME_CONTROL","tool_args":{"action":"<restore|up|down|mute|unmute|set>","level":<0-100>}} - Adjusts media volume.
    """.trimIndent()

    private val APPS_TOOLSET = """
        === TOOLSET: APPS ===
        Tools:
        1. OPEN_APP: {"tool":"OPEN_APP","tool_args":{"app":"<app name or package>"}} - Launches installed app.
        2. SEARCH_APP: {"tool":"SEARCH_APP","tool_args":{"app":"<youtube|spotify|browser|maps|playstore>","query":"<search term>"}} - In-app search.
        3. LIST_APPS: {"tool":"LIST_APPS","tool_args":{"filter":"<optional: music|browser|media|all>"}} - Discovers apps.
        4. TAKE_SCREENSHOT: {"tool":"TAKE_SCREENSHOT"} - Captures screen and attaches the image on your next turn for visual verification.
        5. TAP_SCREEN: {"tool":"TAP_SCREEN","tool_args":{"x":<0-1000>,"y":<0-1000>,"text":"<optional label>"}} - Taps UI via coordinates (0-1000 normalized) or text.
        6. SWIPE_SCREEN: {"tool":"SWIPE_SCREEN","tool_args":{"startX":<0-1000>,"startY":<0-1000>,"endX":<0-1000>,"endY":<0-1000>,"direction":"<optional: up|down|left|right>"}} - Scrolls screen.
        7. INPUT_TEXT: {"tool":"INPUT_TEXT","tool_args":{"text":"<text to type>"}} - Types into focused field.
        8. PRESS_KEY: {"tool":"PRESS_KEY","tool_args":{"action":"<back|home|enter|search>"}} - System navigation or submit search.
    """.trimIndent()

    private val DEVICE_TOOLSET = """
        === TOOLSET: DEVICE ===
        Tools:
        1. GET_DEVICE_STATE: {"tool":"GET_DEVICE_STATE"} - Inspects audio volume, ringer, and active audio.
        2. VOLUME_CONTROL: {"tool":"VOLUME_CONTROL","tool_args":{"action":"<restore|up|down|mute|unmute|set>","level":<0-100>}} - Manages volume.
        3. TAKE_SCREENSHOT: {"tool":"TAKE_SCREENSHOT"} - Captures screen and attaches the image on your next turn for visual verification.
        4. TAP_SCREEN: {"tool":"TAP_SCREEN","tool_args":{"x":<0-1000>,"y":<0-1000>,"text":"<optional label>"}} - Taps UI via coordinates (0-1000 normalized) or text.
        5. SWIPE_SCREEN: {"tool":"SWIPE_SCREEN","tool_args":{"startX":<x1>,"startY":<y1>,"endX":<x2>,"endY":<y2>,"direction":"<optional: up|down|left|right>"}} - Scrolls screen.
        6. INPUT_TEXT: {"tool":"INPUT_TEXT","tool_args":{"text":"<text to type>"}} - Types into focused field.
        7. PRESS_KEY: {"tool":"PRESS_KEY","tool_args":{"action":"<back|home|enter|search>"}} - System navigation or submit search.
        8. SET_MODE: {"tool":"SET_MODE","tool_args":{"mode":"<WANDER|STAY|VANISH>"}} - Controls companion avatar overlay.
    """.trimIndent()

    private val WEB_TOOLSET = """
        === TOOLSET: WEB ===
        Tools:
        1. SEARCH_WEB: {"tool":"SEARCH_WEB","tool_args":{"query":"<search query>"}} - Searches the web for external info.
        2. READ_URL: {"tool":"READ_URL","tool_args":{"url":"<full url>"}} - Fetches/reads webpage text content.
    """.trimIndent()

    private val PLANNER_TOOLSET = """
        === TOOLSET: PLANNER ===
        Tools:
        1. SET_REMINDER: {"tool":"SET_REMINDER","tool_args":{"text":"<description with date/time>"}} - Schedules notification.
        2. LOG_GOAL: {"tool":"LOG_GOAL","tool_args":{"title":"<goal title>"}} - Logs personal user goal.
        3. CHECK_GOALS: {"tool":"CHECK_GOALS"} - Reviews active goals/progress.
        4. COMPLETE_GOAL: {"tool":"COMPLETE_GOAL","tool_args":{"title":"<goal title>"}} - Marks goal complete.
        5. REMEMBER: {"tool":"REMEMBER","tool_args":{"key":"<concept>","value":"<fact>"}} - Saves persistent user fact.
    """.trimIndent()
}
