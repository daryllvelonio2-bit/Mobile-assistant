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
        1. SEARCH_MUSIC: {"tool":"SEARCH_MUSIC","tool_args":{"query":"<optional: song name, artist, or empty to browse library>"}}
           - Inspects on-device music files (Downloads, Music) and returns matching tracks or available library tracks.
           - Use with status 'CONTINUE' before playing to discover real tracks on the phone.
        2. PLAY_MUSIC: {"tool":"PLAY_MUSIC","tool_args":{"query":"<song name or artist>","player":"<optional: default|youtube|spotify>"}}
           - Plays a specific song. Queries local storage first, then YouTube/browser streaming.
           - Parameter 'query' is required.
        3. MEDIA_CONTROL: {"tool":"MEDIA_CONTROL","tool_args":{"action":"<pause|play|stop|next|prev>"}}
           - Controls ongoing media playback (pause/resume/skip).
           - Do not use for specific songs; use PLAY_MUSIC for specific songs.
        4. VOLUME_CONTROL: {"tool":"VOLUME_CONTROL","tool_args":{"action":"<restore|up|down|mute|unmute|set>","level":<0-100>}}
           - Controls audio volume levels.

        Guidelines:
        - Call tools with status 'CONTINUE' so you receive the observation receipt.
        - Inspect the observation receipt before confirming playback or volume changes.
        - Never claim a track is playing if the receipt shows it failed or was not found.
    """.trimIndent()

    private val APPS_TOOLSET = """
        === TOOLSET: APPS ===
        Tools:
        1. OPEN_APP: {"tool":"OPEN_APP","tool_args":{"app":"<app name or package>"}}
           - Finds and launches an installed application by label or package name.
        2. SEARCH_APP: {"tool":"SEARCH_APP","tool_args":{"app":"<youtube|spotify|browser|maps|playstore>","query":"<search term>"}}
           - Searches directly inside a specific application.
        3. LIST_APPS: {"tool":"LIST_APPS","tool_args":{"filter":"<optional: music|browser|media|all>"}}
           - Discovers installed applications on the device.
        4. TAKE_SCREENSHOT: {"tool":"TAKE_SCREENSHOT"}
           - Captures the current device screen and attaches it as vision input.
           - Note: Screenshots and Screen Grounding Hierarchies are automatically attached after OPEN_APP, TAP_SCREEN, and INPUT_TEXT!
        5. TAP_SCREEN: {"tool":"TAP_SCREEN","tool_args":{"element_id":<optional: id from screen elements>,"text":"<optional: button/tab label>","x":<0-1000>,"y":<0-1000>}}
           - Taps a UI element on the screen.
           - Grounding Priority:
             1. By element_id (e.g. {"element_id": 1}): Target exact element from # Screen Grounding Hierarchy! 100% accurate.
             2. By text (e.g. {"text": "Search"} or {"text": "Genres"}): Matches text or content description.
             3. By coordinates (e.g. {"x": 750, "y": 960}): Normalized 0..1000 scale (or physical display pixels).
        6. SWIPE_SCREEN: {"tool":"SWIPE_SCREEN","tool_args":{"startX":<0-1000>,"startY":<0-1000>,"endX":<0-1000>,"endY":<0-1000>,"direction":"<optional: up|down|left|right>"}}
           - Swipes or scrolls the screen to reveal more content.
        7. INPUT_TEXT: {"tool":"INPUT_TEXT","tool_args":{"text":"<text to type>"}}
           - Types text into the currently focused input field.
        8. PRESS_KEY: {"tool":"PRESS_KEY","tool_args":{"action":"<back|home>"}}
           - Navigates back or returns to the home screen.
        9. INSPECT_SCREEN: {"tool":"INSPECT_SCREEN"}
           - Inspects the current screen hierarchy and returns all visible interactive elements with element IDs, labels, and exact coordinates.

        Guidelines:
        - Screenshots and screen element hierarchies are automatically attached after OPEN_APP, TAP_SCREEN, and INPUT_TEXT! You can see the updated screen on each turn.
        - SCREEN AUTOMATION: Use TAP_SCREEN (preferably by element_id or text, or normalized 0..1000 coordinates) and INPUT_TEXT to interact with the app.
        - NEVER stop at OPEN_APP and claim the task is done. The user asked you to do something in the app—open it, tap the tab/search, type the query, and play the content.
    """.trimIndent()

    private val DEVICE_TOOLSET = """
        === TOOLSET: DEVICE ===
        Tools:
        1. GET_DEVICE_STATE: {"tool":"GET_DEVICE_STATE"}
           - Inspects live audio volume, ringer mode, active music track, and whether music is actively playing.
        2. VOLUME_CONTROL: {"tool":"VOLUME_CONTROL","tool_args":{"action":"<restore|up|down|mute|unmute|set>","level":<0-100>}}
           - Adjusts or restores device audio volume.
        3. TAKE_SCREENSHOT: {"tool":"TAKE_SCREENSHOT"}
           - Captures the current device screen and attaches it as vision input.
        4. TAP_SCREEN: {"tool":"TAP_SCREEN","tool_args":{"element_id":<optional: id from screen elements>,"text":"<optional: label>","x":<0-1000>,"y":<0-1000>}}
           - Taps the screen at (x, y) coordinates, clicks by element_id from screen hierarchy, or clicks a UI element matching 'text'.
        5. SWIPE_SCREEN: {"tool":"SWIPE_SCREEN","tool_args":{"startX":<x1>,"startY":<y1>,"endX":<x2>,"endY":<y2>,"direction":"<optional: up|down|left|right>"}}
           - Swipes or scrolls the screen.
        6. INPUT_TEXT: {"tool":"INPUT_TEXT","tool_args":{"text":"<text to type>"}}
           - Types text into the currently focused input field.
        7. PRESS_KEY: {"tool":"PRESS_KEY","tool_args":{"action":"<back|home>"}}
           - Navigates back or returns to the home screen.
        8. INSPECT_SCREEN: {"tool":"INSPECT_SCREEN"}
           - Inspects the current screen hierarchy and returns all visible interactive elements with element IDs, labels, and exact coordinates.
        9. SET_MODE: {"tool":"SET_MODE","tool_args":{"mode":"<WANDER|STAY|VANISH>"}}
           - Controls companion avatar overlay presence on screen.

        Guidelines:
        - Call tools with status 'CONTINUE'.
        - Inspect observations to report accurate device status and verify UI state.
    """.trimIndent()

    private val WEB_TOOLSET = """
        === TOOLSET: WEB ===
        Tools:
        1. SEARCH_WEB: {"tool":"SEARCH_WEB","tool_args":{"query":"<search query>"}}
           - Searches the web for real-time or external information.
        2. READ_URL: {"tool":"READ_URL","tool_args":{"url":"<full url>"}}
           - Fetches and reads text content from a web page.

        Guidelines:
        - Call tools with status 'CONTINUE'.
        - Read search results in observation receipt before formulating your final answer.
    """.trimIndent()

    private val PLANNER_TOOLSET = """
        === TOOLSET: PLANNER ===
        Tools:
        1. SET_REMINDER: {"tool":"SET_REMINDER","tool_args":{"query":"<reminder text with time>"}}
           - Schedules a reminder or alarm.
        2. LOG_GOAL: {"tool":"LOG_GOAL","tool_args":{"title":"<goal title>"}}
           - Records a new personal goal for the user.
        3. CHECK_GOALS: {"tool":"CHECK_GOALS"}
           - Lists active and pending goals.
        4. COMPLETE_GOAL: {"tool":"COMPLETE_GOAL","tool_args":{"title":"<goal title>"}}
           - Marks an active goal as completed.

        Guidelines:
        - Call tools with status 'CONTINUE'.
        - Verify goal or reminder creation from observation receipt before confirming to the user.
    """.trimIndent()
}
