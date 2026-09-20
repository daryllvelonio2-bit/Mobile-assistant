# Project Progress (`PROGRESS.md`)

## Status Overview
- **Current Phase:** Memory upgrade — Hermes-style learning + safe auto-compact (v0.24.0)
- **Last Updated:** 2026-09-20
- **Active Deliverables:** .env, agent.md, BUILD_PLAN.md, STACK_DECISION.md, TREE.md, PROGRESS.md, decision package, observation package, action package, character package (`CharacterMode`, `CharacterController`, `CharacterOverlayService`) + character UI (`CharacterViewModel`, `CharacterPanel`).

## Log of Updates
- **2026-09-20 — Conversation Date/Time Timestamps & 10-Hour Inactivity Pouty Mood (v0.53.0):**
  Implemented explicit date and time timestamps on every conversation turn and added a prolonged-absence pouty mood feature.
  - In [ChatTurn.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/db/ChatTurn.kt), every conversation turn in history (and active conversation) is now formatted with a date and time prefix `[yyyy-MM-dd h:mm a] Role: text`, ensuring full temporal awareness across conversations.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), current user messages and event triggers are formatted with real-time timestamps `[yyyy-MM-dd h:mm a]`.
  - Created [UserActivityTracker.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/activity/UserActivityTracker.kt) wired through [AppContainer.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/di/AppContainer.kt) to track user interaction timestamps and compute absence durations (`hoursInactive`).
  - Added `MOOD_POUTY` (`"pouty"`) to [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.4), `MOOD_PROMPTS`, and `TOOL_SPEC`. When the user hasn't opened the app or interacted for 10+ hours, Shiina automatically triggers the `pouty` bad mood—sulkily pouting, giving tsundere-like side-eye, and complaining about being left alone for hours, while secretly glad they are back.
  - In [CharacterOverlayService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/CharacterOverlayService.kt), mapped `pouty` tone to a vibrant moody magenta/violet accent (`Color(0xFFE879F9)`) with a `POUT` badge on the floating overlay bubble.
- **2026-09-20 — Autonomous Boot Greeting & Mood-Grounded Startup Prompt (v0.52.0):**
  Implemented automatic boot greeting system so Shiina proactively greets the user upon app boot/startup based on her current mood and full context.
  - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.3), added `bootGreetingPrompt(mood)` system prompt defining an in-character startup greeting tailored to active mood, time of day, active music playback, goals, and learned memory facts.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), added `triggerBootGreeting()` with 10-minute debounce to avoid duplicate greetings. Updated `askGemini` with `isSystemTrigger` support to format `# Event Trigger` without polluting SQLite `chat_turns` with synthetic user turns. Automatically pushes the generated greeting to the floating overlay ([CharacterOverlayService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/CharacterOverlayService.kt)), records it in episodic memory (`source = "boot_greeting"`), and logs it to `chatHistory` for continuous dialogue.
  - In [AlarmReceiver.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/AlarmReceiver.kt) and [MainActivity.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/MainActivity.kt), ensured `DebugTalkService` starts reliably on `BOOT_COMPLETED` and cold activity launch.
- **2026-09-20 — Conversational Banter Recovery & Pout/Playful Interaction (v0.51.0):**
  Fixed issue where Shiina was overly restricted by brevity rules, causing passive stonewalling and dry dead-end responses (such as replying *"Fair enough."* and *"Still here."* when the user said *"Hmp"*). Updated [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.2):
  - In `GLOBAL_RULES` and `TALK_DRIVE`, removed rigid brevity constraints (*"Match the user's exact brevity"*) and added explicit guidance to react to playful remarks, pouts, or emotional expressions (like *"Hmp"*, *"ugh"*, *"sigh"*) like a real friend with playful curiosity, gentle teasing, or empathy—never with cold dismissive dead-ends.
  - In `MOOD_PROMPTS` and `TOOL_SPEC`, updated `candid` mood instructions to trigger witty banter, humorous retorts, and playful teasing when the user is moody, sarcastic, or playful (e.g. *"Hmp"*, banter).
  - In [ChatTurn.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/db/ChatTurn.kt), added sanitization in `buildConversationContext()` and `addShiina()` to filter out corrupted JSON / `MALFORMED_RESPONSE` turns from polluting SQLite conversation history.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), refined `identityRule` to instruct speaking naturally like a close friend who knows them well.
- **2026-09-20 — Cheesy Companion Filler & Tack-On Sentence Ban (v0.50.0):**
  Addressed issue where Shiina was tacking on unprompted companion clichés (e.g. *"Hey Daryll. Just hanging out with you."*) to simple greetings. Updated `GLOBAL_RULES`, `TALK_DRIVE`, and `TOOL_SPEC` (Rule 9) in [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.1) to default strictly to a single concise sentence, explicitly forbidding unnecessary second sentences and unprompted activity commentary (such as *"just hanging out with you"*, *"just chilling"*, *"here for you"*). When the user sends a simple greeting (e.g. *"Hi"*, *"Hey"*), Shiina is instructed to respond with just a simple greeting back (e.g. *"Hey Daryll!"*, *"Hi!"*) matching the user's exact brevity. Updated `identityRule` in [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt) to ban tacking on companion filler to greetings.
- **2026-09-20 — Dynamic Autonomous Mood System & Visual State Reflection (v0.49.0):**
  Upgraded Shiina's mood architecture from a static, frozen `calm` state into a dynamic autonomous system.
  Added `"mood": "<calm | candid | warm | firm>"` to the agent output schema in [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.0),
  allowing Gemini to shift mood based on the conversation (e.g., `candid` for witty banter, `warm` for comfort/validation,
  `firm` for accountability/procrastination, and `calm` for unhurried downtime). Sharpened `MOOD_PROMPTS` with distinct,
  vibrant behavioral instructions. In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt),
  parsed `step.mood` dynamically updates `lastTone`, restores tone on startup from `MemoryEpisodeDao`, and persists
  conversation moods into episodic memory for background decisions. In [CharacterOverlayService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/CharacterOverlayService.kt),
  mapped `toneColor` to vibrant theme accents (amber for `candid`, coral for `firm`, mint for `warm`, sky blue for `calm`)
  and added a live mood-colored border and badge to the floating bubble overlay.
- **2026-09-20 — MALFORMED_RESPONSE Error Handling & Small Talk Repetition Ban (v0.48.0):**
  Fixed critical issue where Gemini API responses with `finishReason: "MALFORMED_RESPONSE"` (empty candidate parts) leaked raw JSON error payloads as Shiina's speech on the overlay and debug panel. In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), `postText` now performs automatic retries on malformed or empty responses and falls back gracefully to a natural conversational phrase instead of raw JSON. Sanitized `parseAgentStep` and `askGemini` to strictly filter out candidate/finishReason/error JSON strings from ever becoming user messages. Updated `GLOBAL_RULES`, `TALK_DRIVE`, and `TOOL_SPEC` in [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v3.9) to explicitly ban repetitive small talk (such as repeatedly asking "how is your Sunday afternoon going?") and forbid repeating questions or topics already asked in recent conversation history.
- **2026-09-20 — Single-Step Action Execution & Repetition Prevention (v0.47.0):**
  Fixed bug where saving a fact or executing an action triggered an intermediate status message followed by a repetitive final message. In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), tools requested on the final step (`status: "DONE"`) now execute immediately before completing, allowing single-step actions without an unnecessary second turn. Intermediate overlay status updates are now restricted to long-running tools (`SEARCH_WEB`, `READ_URL`, `TAKE_SCREENSHOT`), preventing duplicate/repetitive messages for fast local tools (`LEARN`, `SET_MODE`). Connected `userName` resolution in [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt) to [LearnedMemoryManager.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/LearnedMemoryManager.kt) (`get(key)`), resolving prompt contradictions where the AI greeted known users as strangers. Added name extraction fallback in `runTool` and updated `TOOL_SPEC` in [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v3.8) to enforce non-repetitive responses.
- **2026-09-20 — Situation-Grounded Suggestions & Natural Conversational Flow (v0.46.0):**
  Refined Shiina's conversational behavior in [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v3.7)
  to stop appending unprompted, artificial multiple-choice questions or unsolicited conversational filler
  (e.g., asking "Need a cozy nap or just hanging out?" after a casual "Hmpp"). Updated `GLOBAL_RULES`,
  `TALK_DRIVE`, and `TOOL_SPEC` to instruct the model to match the user's energy, avoid forcing conversations
  with trailing questions on brief banter, and ensure suggestions and follow-ups are strictly grounded
  in the current situation (real-time device senses like battery/time/music, active goals, or explicit context).
- **2026-09-20 — Persistent Learned Memory File & LEARN Tool (v0.45.0):**
  Added persistent file-backed learning (`LearnedMemoryManager`) writing to `learned_memory.md` on
  disk and syncing with `MemoryStore`. Equipped Shiina with the `LEARN` tool, enabling the autonomous
  agent to detect and record lasting facts, user habits, rules, and preferences into the persistent
  knowledge base. The contents of `learned_memory.md` are dynamically read and injected into the prompt
  under a dedicated `# Learned Memory (Persistent Knowledge File)` section, ensuring the AI carries its
  authored memory across sessions. Identity corrections (`I am <name>`, `call me <name>`) also sync
  directly into the persistent memory file.
- **2026-09-20 — Autonomous Agent Loop & Interactive CONTINUE/DONE Control (v0.44.0):**
  Upgraded the Talk engine from a standard turn-based chatbot into an autonomous Think-Act-Observe
  agent loop (inspired by ReAct, smolagents, and Hermes Agent architectures). Shiina now loops by
  default to execute multi-step tasks, investigate observations, and interact. Each step outputs
  structured JSON with `thought`, `status` (`CONTINUE` | `DONE`), `tool`, and `message`. Only the
  AI invokes loop completion by declaring `status: "DONE"`. When continuing (`status: "CONTINUE"`),
  intermediate progress messages are pushed live to the floating overlay so the user sees real-time
  interaction. An 8-step safety ceiling and repetition detection prevent runaway loops.
- **2026-09-20 — Uncapped Conversation Memory & 100k Context Auto-Compaction (v0.43.0):**
  Removed all artificial turn limits and message truncations from `ChatTurnDao` and `ChatHistory`.
  Chat turns are now stored permanently in SQLite without `keepLatest(30)` deletions or `.take(512)`
  character caps. Below the 100k context threshold (`MAX_CONTEXT_CHARS = 100_000`), 100% of
  conversation turns are passed verbatim in chronological order to Gemini, granting Shiina full,
  unbroken memory across large conversational sessions. Once conversation context reaches 100k
  context, `buildConversationContext()` automatically compacts the older conversation turns into
  a durable digest (`# Earlier Conversation (Compacted)`) persisted in `MemoryFact`, while keeping
  recent turns in full verbatim fidelity without deleting any raw rows from the database.
- **2026-09-20 — Expanded Conversation Window & Identity Learning (v0.42.0):** Fixed issue
  where conversation history in Talk mode was prematurely cut off after only 3 exchanges.
  Increased `recentTurns` limit from 6 to 20 across `ChatHistory` and `DebugTalkService`,
  maintaining up to 10 full conversational back-and-forth turns in context. Expanded
  identity fast-path in `DebugTalkService` to recognize `"I am <name>"`, `"I'm <name>"`,
  `"im <name>"`, and `"call me <name>"` (filtering state words like "bored", "fine"),
  persisting the user's name directly to `memoryStore` (`user_name`) so it is never lost.
- **2026-09-20 — Clean Prompt Hierarchy & Section Separation (v0.41.0):** Restructured prompt
  generation in `DebugTalkService`, `ShiinaPrompts` (v3.4), and `GeminiProvider`. Fixed
  critical structural issue where `TOOL_SPEC` ("You have tools...") was concatenated directly
  onto the end of the user's input (`"They say: $userText You have tools..."`) and chat
  history was mashed together with the current query. Prompts now feature clean, distinct
  markdown sections: `# Persona & Instructions`, `# Available Tools & Protocol`, `# Live Context & Device Senses`,
  `# Memory`, `# Recent Conversation History`, and an isolated `# Current User Message`.
- **2026-09-20 — Friendly global companion persona (v0.40.0):** Overhauled `GLOBAL_RULES`
  and moods in `ShiinaPrompts` (v3.3). Replaced terse "silence/brevity over filler"
  and robotic appliance framing ("Owner says") with a warm, caring, and lighthearted
  friend persona ("They say", natural conversational flow) while preserving conciseness
  and zero hallucination.
- **2026-09-20 — Exact music query & tracking (v0.39.0):** NEW `observation/MusicTracker.kt`
  + `MusicNotificationListener.kt`. Reads exact song title, artist, and album from
  Android `MediaSession`, `NotificationListenerService`, and music broadcast intents
  (Spotify, Huawei Music, Apple Music, YouTube Music, etc.). Injected into `DeviceSenses`
  (`"current_music"` field) and wired to a Talk fast-path ("what song is playing",
  "anong kanta to") so Shiina immediately and accurately answers with the real playing
  track without guessing.
- **2026-09-20 — Prompt overhaul & SET_MODE tool (v0.38.0):** Overhauled all system
  and conversation prompts across `ShiinaPrompts` (v3.2), `GeminiProvider`, and
  `DebugTalkService`. Removed robotic meta-commentary triggers (such as "no tool was
  needed", rigid binary choice mandates, and draft self-critique loops) that caused
  weird conversational outputs. Added explicit `SET_MODE` tool (`WANDER`, `STAY`,
  `VANISH`) and `HIDE` action support across `ToolCall`, the Talk tool harness,
  `CharacterController`, and `ActionExecutor`.
- **2026-09-20 — Overlay persistence fix (v0.37.0):** Removed the 60s auto-dismiss
  timer (`autoDismissJob`) from `CharacterOverlayService`. The overlay now
  stays visible continuously and only hides when the character mode explicitly
  becomes `VANISH` (or `ACTION_HIDE` is called), preventing unintentional
  disappearances and false dismissal cooldown suppressions.
- **2026-09-20 — Total context viewer (v0.33.0):** Settings → Memory now has
  a "Total context" section showing the exact block she carries into every
  reply (MemoryContext.build output with char count) plus a refresh button.
  Same source, same ranking as the prompt — what you see is what she knows.
  Wired MemoryContext into SettingsViewModel (nullable param, no breakage).
- **2026-09-20 — Fenced JSON fix (v0.32.0):** the monitor caught it — she DID
  call SEARCH_WEB, but wrapped it in ```json fences, so parseToolCall failed
  and the raw JSON leaked to you as her reply while no search ever ran.
  Parser now strips fences and extracts the {...} block. Ask her again.
- **2026-09-20 — Search that actually searches (v0.31.0):** the peso failure
  was real — DDG Instant Answers returns empty for most queries (rates, news,
  general questions), and the harness gave up after one shot. WebSearch now
  falls back to scraping DDG Lite top-3 titles+snippets (verified from here:
  10 results for USD→PHP). Harness retries once with a rephrased query when
  the first search is empty (max 2/turn), then answers with whatever it has.
  Ask her the peso question again.
- **2026-09-20 — Manual memory clear, complete (v0.30.0):** the Settings
  Memory "Forget everything" button now asks for a confirming second tap,
  then wipes DB (facts, episodes, chat, digests) AND resets the live session
  (tone back to calm, mode STAY) via a new RESET_SESSION service action —
  previously the wipe left stale tone/mode in memory. Talk "forget everything"
  resets session too. Also fixed a real crash found during verification:
  MemoryPanel's LazyColumn inside an unbounded Column killed the app on open
  (FATAL, PID 20345) — replaced with a scrollable Column. Clean launch on
  PID 20829, zero fatals.
- **2026-09-20 — Launch crash fix (v0.30.0, same build):** the scrollable
  Column nested inside MainActivity's already-scrollable root re-triggered
  the same infinite-constraints FATAL at launch (PID 21613, MainActivity
  force-finished, back to launcher while the service kept running).
  MemoryPanel is now a plain Column — the outer screen scrolls. Verified:
  MainActivity resumed on top, zero fatals since install.
- **2026-09-20 — Tool-use loop / harness (v0.29.0):** Talk is now agentic.
  ShiinaPrompts.TOOL_SPEC gives her the JSON protocol (SEARCH_WEB with her
  own query, TAKE_SCREENSHOT, or NONE with direct answer). askGemini runs the
  loop: round 1 parses her tool call, harness executes at most one tool, round
  2 answers off the result; unparseable round 1 is honored as a direct answer.
  Keyword fast-paths kept. She decides when she needs live info — the ability,
  the harness, and the loop.
- **2026-09-20 — Real abilities (v0.28.0):** NEW action/WebSearch.kt (keyless
  DuckDuckGo answers, no new permissions). New decision verbs SEARCH_WEB
  (answer stored as memory fact) + TAKE_SCREENSHOT (vision pipeline picks it
  up); JSON schema + validation updated. Time awareness: ShiinaPrompts.timeLine
  (local time, date, battery) in every decision + Talk prompt. Talk triggers:
  "search .../look up .../google ..." answers from live results, "take a
  screenshot / what do you see" captures now and answers with vision attached.
  Build SUCCESS (one missing-import fix), installed on AUDUT20616012479,
  zero fatals.
- **2026-09-20 — Talk with memory + drive (v0.27.0):** Talk prompt now gets
  the full memory block (facts, notable rounds, digests, learned threshold)
  plus TALK_DRIVE — answer, weave in a memory when it fits, end with one
  short question when natural. She finally converses with sources, not just
  thread history.
- **2026-09-20 — Silence permission (v0.26.0):** GLOBAL_RULES now grants her
  the right to say nothing — quiet over filler. Decision JSON already allowed
  null spoken_message, so this aligns the rule with the schema.
- **2026-09-20 — Prompt architecture: global rules + gated moods (v0.25.0):**
  NEW decision/ShiinaPrompts.kt — short GLOBAL_RULES (Shiina mobile personal
  assistant, 1-2 sentences, no screen/phone/overlay narration, no invented
  names/facts, no robotic phrasing) + one mood block appended ONLY when active
  (calm/candid/firm/warm mapped from decision tones; mood follows last round,
  calm default). GeminiProvider.prompt() + Talk prompt rebuilt on it; the old
  "living on your phone / screen warm" framing that taught her the "staying
  put on my screen" filler is gone. Build SUCCESS, installed on
  AUDUT20616012479, zero fatals.
- **2026-09-20 — Memory upgrade: learning + safe auto-compact (v0.24.0, Room v6):**
  NEW MemorySummary.kt (weekly digest entity + DAO) + MemoryCompactor.kt
  (extract-then-delete: episodes >30d distilled into 7-day digests BEFORE raw
  delete, offline rule-based, 4 windows/run, hooked into NightlyReflection).
  MemoryStore: contradict() crushes confidence to 30%, stated facts always
  override inference, forgetKey(), forgetAll wipes digests, status/digest
  readers. MemoryContext: importance-ranked recall (acted > talked > dismissed
  + recency decay), user_name pinned, last 2 digests injected (2000 chars).
  Talk: deterministic corrections — "my name is X", "don't call me X",
  "forget <key>", vague corrections get honest guidance; identity rule in both
  Talk + decision prompts (never invent a name). Chat keep 20→30. Settings
  MemoryPanel shows status line + digests. DB MIGRATION_5_6. Build SUCCESS,
  installed on AUDUT20616012479, zero fatals.
- **2026-09-20 — Memory & Learning Plan Phases 1-6 (v0.23.0, Room v5):**
  P1 episodic log: MemoryEpisode entity + DAO; ProviderRegistry.decide() logs
  every round (source=render/alarm/debug/fallback). P2 outcomes: shown/
  dismissed/acted/talkedBack columns + MemoryOutcomes (60s dismiss window,
  5min talk window) + real cooldown (2 dismissals/hr suppress interrupts).
  P3 facts: MemoryFact entity (cap 200, evict lowest), MemoryStore with
  remember-command parsing and LEARN_FACT action verb. P4 recall:
  MemoryContext block (last 5 rounds + outcomes, top 10 facts, streaks,
  learned threshold/nudge hour) appended to the Gemini decision prompt.
  P5 NightlyReflection via BaselineWorker (charger-idle, 24h): per-tone
  effectiveness, adaptive threshold 10..40%, best nudge hour, bedtime from
  median sleep records, pruning (episodes >90d, facts <0.2 unused 30d).
  P6 chat continuity (last 20 turns, 6 in prompt), MemoryPanel settings UI
  (facts edit/delete + forget everything), "remembers N days" panel line.
  Talk handles "remember key=value" and "forget everything" locally.
  Migrations 1→2→3→4→5 all non-destructive. assembleDebug BUILD SUCCESSFUL,
  APK at app/build/outputs/apk/debug/app-debug.apk. On-device behavioral
  verification (episode rows, cooldown suppression, recall in prompt) still
  pending install on AUDUT20616012479.
- **2026-09-20 — S3 DONE, candid system prompt (v0.17.0):** decision runs
  now use the user's interruption policy + JSON schema verbatim (adapted with
  live telemetry + % over baseline). Action verbs are NONE|SET_ALARM|
  TOGGLE_SCREENSHOT|LOG_GOAL, executed by ActionExecutor.executeDecision;
  intent derived from tone/action. Verified live: Gemini returned
  should_interrupt=false with reason citing 0min vs 120min baseline.
- **2026-09-20 — S2 DONE, character voice (v0.16.0):** Talk prompt now
  injects live tone + mode, first-person, under 40 words, with explicit bans
  on agent-speak. Verified live through the shade: she answered in a calm,
  teasing voice with zero robotic phrasing.
- **2026-09-20 — S1 DONE, structured decisions (v0.15.0):** Decision is now
  a real directive (tone/interrupt/intent/action/message). Gemini prompt
  demands JSON-only with intent/action allow-lists; parser extracts {..},
  clamps message to 140 chars, forces action=none when not drifted; any
  failure falls back to rule-based coach/nudge or presence/calm. Consumers
  updated (overlay service, panel Render, alarm log). Verified live: tapped
  in-app Render latest via uiautomator, Gemini JSON parsed, overlay rendered,
  zero crashes.
- **2026-09-20 — Task 1 DONE, alarm end-to-end (v0.14.0):** found the 7PM
  alarm fully dead (no receiver, ActionExecutor never instantiated, nothing
  scheduled). Added `action/AlarmReceiver.kt`: BOOT_COMPLETED reschedule +
  trigger runs real pipeline (usage vs baseline -> Gemini -> overlay) then
  rolls to tomorrow. Wired ActionExecutor into AppContainer, schedule on app
  start, manifest receiver + RECEIVE_BOOT_COMPLETED. Verified: BUILD
  SUCCESSFUL, dumpsys shows exact RTC_WAKEUP 19:00:00.000 ACTION_ALARM_TRIGGER.
- **2026-09-20 — FOCUS_TASK.md:** new character/backend focus doc (verified
  capabilities, honest backend inventory, stubs flagged, 7 ordered tasks).
- **2026-09-20 — Persistent notification debug panel with text talk (v0.11.0/v0.12.0):**
  - New `debug/DebugTalkService.kt` (1 feature = 1 file, <500 lines): ongoing foreground notification showing current tone + last reply, with Render / Hide / Logs-toggle actions and RemoteInput Talk reply (text-based debugging; voice deferred).
  - Render reuses UsageReader + BaselineUpdater + ProviderRegistry off-main (Dispatchers.IO, supervisor scope), updates overlay + AppDebugServer logs; Talk sends free text to gemini-3.5-flash-lite via Keystore key and posts the reply to the panel + debug server.
  - Registered unexported specialUse FGS in manifest, auto-started from CompanionApp, version 0.10.0 → 0.11.0 (code 11). Verified `assembleDebug` BUILD SUCCESSFUL, APK 13.1M, reinstalled on Waydroid.
  - Live verification caught `CannotPostForegroundServiceNotificationException` at launch: RemoteInput action's PendingIntent was IMMUTABLE, system requires MUTABLE — fixed with dedicated mutable intent for Talk, bumped to v0.12.0 (code 12), rebuilt, reinstalled, relaunched: process alive, `shiina_debug` FGS notification posted, zero new crashes.
  - Shade showed only Render/Hide/Logs (Talk cut as 4th action) — removed extra Hide button so actions are Render/Talk/Logs, v0.13.0 (code 13), rebuilt, reinstalled, verified `actions=3` on posted notification.
- **2026-09-19:** 
  - Initialized project configuration files (`agent.md`, `BUILD_PLAN.md`, `STACK_DECISION.md`, `TREE.md`, `PROGRESS.md`). Tailored guidelines for Kotlin + Jetpack Compose Android development, specialized subagent delegation (`debugger`, `ui/ux expert`), work-tree maintenance, and progress tracking.
  - Added `.env` configuration template for API keys and environment settings (ignored by git).
  - Revised `STACK_DECISION.md` API layer: expandable `DecisionProvider` registry + `RoundRobinKeyPool` per provider, provider order config, Keystore storage, rule-based fallback.
  - Installed CLI toolchain (no Studio): JDK 17, Android cmdline-tools latest, platform-tools, android-34/35 platform, build-tools 34.0.0/35.0.0.
  - **Completed Phase 1 (Scaffold + Permissions):**
    - Scaffolded Gradle root & app build scripts, configured AndroidManifest, Room DB, DataStore, Keystore crypto, Decision registry & Gemini provider, M3 theme, Permissions UI, and Settings UI. Verified clean build (`BUILD SUCCESSFUL`).
  - **Completed Phase 2 (Observation backend):**
    - Implemented `UsageReader`, `SleepReader`, `BaselineUpdater`, and `ObservationService`. Verified clean compilation (`BUILD SUCCESSFUL`).
  - **Completed Phase 3 (Decision + Action layer):**
    - Implemented `ActionExecutor`: handles scheduling 7pm recurring alarms via AlarmManager, toggling screenshot capture state, and logging goals in Room `GoalDao`.
    - Verified clean compilation and successful debug APK build (`BUILD SUCCESSFUL`).
  - **Completed Phase 4 (Character skin v2):**
    - Implemented `CharacterMode` (VANISH/STAY/WANDER), `CharacterController` (pure Decision-to-mode mapping + wander offsets), and `CharacterOverlayService` (TYPE_APPLICATION_OVERLAY bubble, drag-to-move, 4s wander drift, supervisor scope, leak-free remove on hide/destroy).
    - Implemented `CharacterViewModel` (tone/mode state, render-latest via ProviderRegistry + UsageReader + BaselineUpdater) and `CharacterPanel` (M3 Show/Hide/Render-latest, overlay-permission guard).
    - Registered overlay service in manifest (specialUse FGS + subtype property), wired `UsageReader`/`BaselineUpdater` into `AppContainer`, added panel to `MainActivity`.
    - Verified clean `assembleDebug` with zero warnings; APK at `app/build/outputs/apk/debug/app-debug.apk` (12.4M).
  - **2026-09-19 — Render-latest crash fix (static analysis, no logcat available):**
    - Root cause: `CharacterViewModel.renderLatest()` launched an unguarded `viewModelScope` coroutine — any throw from `UsageReader` (`SecurityException` without usage-stats grant) or `show()`/`startForegroundService` (FGS start / overlay denial, common on Waydroid) escaped and crashed the app; `CharacterOverlayService.onStartCommand`/`ensureBubble` likewise let `startForeground` and `WindowManager.addView` (`BadToken`/`SecurityException` on Waydroid) propagate and kill the service/process.
    - Hardened `CharacterViewModel`: `renderLatest` body wrapped in try/catch with new `error: StateFlow<String?>`, usage-stat read moved to `Dispatchers.IO`, `show()` guarded by `canOverlay()` + `runCatching`, `hide()` start wrapped in `runCatching`.
    - Hardened `CharacterOverlayService`: `onStartCommand` `startForeground` + dispatch body wrapped in `runCatching` with `stopSelf()` (no throw), `ensureBubble` `addView` wrapped in `runCatching` with bubble cleanup + `stopSelf()` on failure.
    - Surfaced `error` in `CharacterPanel` as `bodySmall` text in `MaterialTheme.colorScheme.error`.
    - All touched files under 500 lines (VM 99, Panel 64, Service 229), M3 tokens only. Verified `assembleDebug` BUILD SUCCESSFUL, zero Kotlin warnings; APK at `app/build/outputs/apk/debug/app-debug.apk` (12.4M). No reinstall (parent handles).
  - **2026-09-19 — Enhanced Error Inspector & JSON API Added:**
    - Upgraded `AppDebugServer` with advanced error categorization, color-coded red/purple highlights for errors and crashes, and a new JSON event endpoint (`GET /api/events`).
    - Added global `CoroutineExceptionHandler` for catching and logging coroutine exceptions in detail.
    - Version bumped to `0.10.0` (`versionCode` 10) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Reordered `savedStateController.performRestore(null)` before `ON_CREATE` event in `OverlayLifecycleOwner.attach()` within `CharacterOverlayService.kt` (resolving `You can 'consumeRestoredStateForKey' only after the corresponding component has moved to the 'CREATED' state`).
    - Version bumped to `0.9.0` (`versionCode` 9) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Updated model in `GeminiProvider.kt` to `"gemini-3.5-flash-lite"`.
    - Version bumped to `0.8.0` (`versionCode` 8) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Updated Gemini model from deprecated `"gemini-2.0-flash"` to valid `"gemini-1.5-flash"` in `GeminiProvider.kt` (resolving HTTP 404).
    - Added `savedStateController.performRestore(null)` in `OverlayLifecycleOwner.attach()` within `CharacterOverlayService.kt` (resolving `You can 'consumeRestoredStateForKey' only after the corresponding component has moved to the 'CREATED' state`).
    - Version bumped to `0.7.0` (`versionCode` 7) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Added `<uses-permission android:name="android.permission.INTERNET" />` and `ACCESS_NETWORK_STATE` to `AndroidManifest.xml` (resolving `EPERM` on debug socket server and network calls).
    - Added comprehensive debug logging across `CharacterOverlayService` (`onStartCommand`, `ensureBubble`, `addView` success/failure).
    - Version bumped to `0.6.0` (`versionCode` 6) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Implemented a zero-dependency local HTTP debug server (`AppDebugServer` running on port 8085) capturing all app events, UI clicks, decision calls, API requests, and uncaught crashes in real-time.
    - Provides a web dashboard at `http://<waydroid-ip>:8085` with auto-refreshing live log stream.
    - Version bumped to `0.5.0` (`versionCode` 5) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Added secure Gemini API key input field in `SettingsScreen`, backed by encrypted `KeyStoreKeys` in `SettingsViewModel`.
    - Version bumped to `0.4.0` (`versionCode` 4) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`.
    - Root cause: `CharacterOverlayService` added a ComposeView via WindowManager without a LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner attached, causing Compose `WindowRecomposer` to throw `IllegalStateException`.
    - Fix: Added retained `OverlayLifecycleOwner` in `CharacterOverlayService`, setting tags directly on `ComposeView` using lifecycle/viewmodel/savedstate R IDs before `addView`, advancing to RESUMED on add success and DESTROYED on remove/destroy/failure.
    - Updated AGP to `8.6.1` to align with AndroidX compose metadata requirements.
    - Version bumped to `0.3.0` (`versionCode` 3) per agent.md rule 15.
    - Rebuilt `assembleDebug` BUILD SUCCESSFUL, reinstalled on Waydroid via `waydroid app install`, confirmed zero crashes/FATAL exceptions in logcat.
  - **2026-09-19 — Overlay ComposeView ViewTreeLifecycleOwner crash fix (code fix only, parent builds/verifies):**
    - Root cause: `CharacterOverlayService.ensureBubble()` created a bare `ComposeView` and added it via `WindowManager.addView` with no `ViewTreeLifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner`, so composition threw `IllegalStateException: ViewTreeLifecycleOwner not found from ComposeView`.
    - Fix: new retained `OverlayLifecycleOwner` (LifecycleRegistry + ViewModelStore + SavedStateRegistryController implementing LifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner); all three `ViewTree*.set(view, owner)` calls happen BEFORE `addView`, lifecycle moves to RESUMED on add success and DESTROYED (store cleared) on `removeBubble`/`onDestroy`/add failure. Existing `runCatching` hardening kept.
    - Deps: added explicit `lifecycle-runtime`, `lifecycle-viewmodel` (2.8.5) + `savedstate` (1.2.1, corrected from nonexistent 2.8.0) to version catalog + `app/build.gradle.kts`. Each `ViewTree*` import verified present in its declared AAR (classes.jar inspection).
  - **2026-09-20 — UI Modernization & Material 3 Refactor (v0.36.0):**
    - Delegated UI/UX redesign to specialized `ui_ux_expert` subagent following `agent.md` Rule 13.
    - Modernized layout from single raw vertical column to Material 3 `Scaffold` with edge-to-edge support, sleek `TopAppBar`, and a 3-tab `NavigationBar` (Companion, Memory, Settings).
    - Eliminated bloatware, excessive cards, and nested containers in favor of clean typography-led hierarchy, generous whitespace, flush rows, and subtle low-opacity `HorizontalDivider` elements.
    - Modernized `Color.kt` and `Theme.kt` with a sophisticated indigo-slate light theme and obsidian/iris dark theme with complete Material 3 semantic color mapping.
    - Integrated permissions status & grant triggers seamlessly into Settings with a non-intrusive header badge indicator when permissions are missing.
    - Version bumped to `0.36.0` (`versionCode` 36) per `agent.md` Rule 15.
    - Verified with `./gradlew assembleDebug` — BUILD SUCCESSFUL in 19s.
  - **2026-09-20 — Full Gemini API Observability & Monitor Log Stream (v0.37.0):**
    - Delegated debugging and monitoring enhancements to specialized `debugger` subagent following `agent.md` Rule 13.
    - Added safe logcat chunking in `AppDebugServer.log` (splits >3500 char messages into `[part X/Y]` to prevent `logd`'s 4KB truncation).
    - Implemented comprehensive Gemini logging in `GeminiProvider.kt`:
      - `GEMINI_REQUEST`: full prompt (rules, senses, baselines, goals, memory) and vision screenshot attachment status.
      - `GEMINI_RESPONSE`: complete raw response string from Gemini.
      - `GEMINI_PARSED`: extracted decision fields (interrupt, confidence, reason, tone, action, param, message, extras).
      - `GEMINI_PARSE_ERROR`: logs parse errors and raw response before falling back to rule-based decision.
    - Enhanced talk logging in `DebugTalkService.kt`:
      - `GEMINI_TALK_REQUEST`: full talk prompt and screenshot attachment status.
      - `GEMINI_TALK_RESPONSE`: raw response text from Gemini.
      - `GEMINI_TALK_PARSED`: parsed tool calls (tool, query, url, key, value, text, answer).
    - Enhanced `monitor.py`:
      - Chunk reassembly for multi-part logcat messages.
      - ANSI color-coded visual boxes for requests (cyan), responses (yellow with pretty-printed JSON and token counts), parsed outputs (green), and errors (red).
    - Version bumped to `0.37.0` (`versionCode` 37) per `agent.md` Rule 15.
    - Verified with `assembleDebug` — BUILD SUCCESSFUL in 7s.
