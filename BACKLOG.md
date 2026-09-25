# BACKLOG — usability & interactivity improvements

Source: full-project audit 2026-09-22 (v0.89.0). Ordered by impact. Check off items as done.

## P0 — Usability gaps (the core promise needs entry points)

- [x] **B1. In-app chat screen (Chat tab).** Chat lives in `ui/chat/ChatScreen.kt`
  (LazyColumn from `chat_turns` Room Flow + input row), sends via `DebugTalkService.ACTION_TALK`,
  real-time echoes and progress indicators via `ChatBus`.
- [x] **B2. Tap the overlay bubble.** Overlay bubble has tap detection (brings `MainActivity` forward on Chat tab)
  and long-press (hides overlay when unlocked).
- [x] **B3. Stop control for a running agent task.** MAX_AGENT_STEPS=25 x 1-2s pacing
  = up to a minute of her driving the phone with no UI abort (v0.71 removed the
  keyword interceptor).
  Plan: `ACTION_STOP` + `@Volatile stopRequested` checked at the top of every loop
  turn; Stop button on Chat screen while busy; conditional Stop notification action.
- [x] **B4. Chat history capture on fast paths.** remember/forget/correction replies
  never entered `chat_turns` — invisible to both the UI and the model's history.

## P1 — Interactivity / feel

- [x] **B5. Voice output (TTS) on final replies.** Dedicated native Android TTS engine
  (`ShiinaVoiceSpeaker.kt`) with dynamic mood pitch/rate modulation, markdown stripping,
  and Settings toggle. Wired via `ChatBus.spokenUtterance`.
- [x] **B6. Bubble "thinking" state.** Added animated border shimmer (`busyAlpha`)
  and dynamic progress chips to `CharacterOverlayService.kt` during `ChatBus.busy`.
- [x] **B7. Battery-exemption permission row.** Huawei battery-kills the app; triggers
  and boot greeting silently die. PermissionScreen doesn't surface it.
  (Row added; user still grants on the device manually — OEM whitelist is the real fix.)
- [x] **B8. First-run guided onboarding.** Step-by-step Material 3 onboarding wizard
  (`OnboardingScreen.kt`) walking through personality, Gemini brain key setup,
  core sensory permissions (overlay, accessibility, usage, alarms, battery),
  voice/vision toggles, and procedural navigation tips. Replayable from Settings.

## P2 — Engineering hygiene (iteration speed & trust)

- [x] **B9. Split DebugTalkService.kt (1,331 lines).** Extracted agent loop (`AgentEngine.kt`),
  tool execution (`ToolDispatcher.kt`), prompt assembly (`PromptAssembler.kt`), and step parsing (`AgentStep.kt`).
  DebugTalkService reduced to ~414 clean lines.
- [x] **B10. DebugWebServer binds 0.0.0.0:8085 with no auth** — full telemetry readable
  by anyone on the LAN/hotspot. Bind loopback; access via `adb forward tcp:8085 tcp:8085`.
- [x] **B11. Delete `shiina_character.glb.bak`** — 6.5 MB dead weight shipped in assets.
- [x] **B12. Unit tests for regression-prone pure logic.** Added unit test suite in `app/src/test/java/com/shiina/mobile/`
  (`SystemContextAndSkillsTest.kt` verifying >10,000 pure system context character length, playbooks, step parsing, scenario detection).
- [x] **B13. Doc/build drift:** `sysprompt.md` cited in PROGRESS but missing;
  SettingsScreen hardcodes a stale version string ("0.86.0") — switch to
  BuildConfig.VERSION_NAME (enable buildConfig).

## P3 — Cost / latency (measure before optimizing)

- [x] **B14. Per-turn token audit.** High-efficiency image downscaling in `ScreenshotTaker`
  (max dimension 1024, quality 75) reducing screenshot payloads by 90%+ (600KB down to ~40KB)
  and visual tokens by 75% (~258 tokens). Screen capture freshness cap (60s). Stable base prompt
  prefix reuse across loop turns for KV/prefix caching. Real-time `TOKEN_AUDIT` telemetry in logcat.

## Status log
- 2026-09-22 — v0.96.0: First-Run Guided Onboarding & Per-Turn Token Optimization. Added Material 3 OnboardingScreen wizard (Backlog B8) covering companion intro, Gemini key config, core sensory permissions, and voice/vision preferences; replayable from Settings. Implemented per-turn token optimization (Backlog B14) with max-1024 screenshot downscaling, 60s freshness gate, stable base prompt prefix caching, and real-time TOKEN_AUDIT metrics. 100% unit tests passing (11/11). Installed & verified on hardware. All Backlog items (B1-B14) COMPLETE.
- 2026-09-22 — v0.95.0: Emotional Voice TTS & Bubble Thinking Shimmer. Added dedicated ShiinaVoiceSpeaker with dynamic pitch/rate modulation matching Shiina's moods (pouty, calm, warm, sleepy) and markdown sanitization. Added bubble thinking pulse shimmer (busyAlpha) and real-time status chips in CharacterOverlayService. Added zero-hardcoding dynamic proactive trigger engine delegating to AgentEngine(isSystemTrigger=true). Added EXECUTE_PROCEDURE macro engine and autonomous post-task procedural path synthesis in AgentEngine. Unit test suite passing 9/9. Installed & verified on hardware. Remaining: B8, B14.
- 2026-09-22 — v0.94.0: Procedural Learning, App Navigation Memory & Macro Self-Optimization. Added ProceduralMemoryStore for persistent JSON macro storage. Surfaced learned procedures in every system prompt. Added procedural tools (GET_PROCEDURE, LEARN_PROCEDURE, REMOVE_PROCEDURE_STEP, UPDATE_PROCEDURE_STEP, OPTIMIZE_PROCEDURE, LIST_PROCEDURES, FORGET_PROCEDURE). Added Skill 7 in ShiinaSkills. Pure system context exceeds 20,200 characters. 100% unit tests passing (7/7). Live-tested on Huawei device over wireless ADB.
- 2026-09-22 — v0.93.0: Clock, Alarm & Accessibility Tab Grounding. Added com.android.alarm.permission.SET_ALARM, android.permission.SET_ALARM, VIBRATE, and CAMERA to AndroidManifest.xml. Enhanced DeviceActionController with direct SET_ALARM/SET_TIMER (EXTRA_SKIP_UI=true) and fallback to SHOW_ALARMS. Solved OEM collapsed node bounds in ShiinaAccessibilityService via descendant label extraction and ancestor climbing for zero-bound nodes. Added openApp system app alias mapping.
- 2026-09-22 — v0.92.0: Comprehensive AI Environment Upgrade. Added ShiinaSkills (calm execution & loading settle, real-world scenarios, dignity & anti-challenge, app navigation, zero hallucination playbooks). Added DynamicLearningEngine with real-world situation triggers (late-night active, morning routine, battery alerts) and categorized lifelong learning. Enriched ShiinaPrompts (non-repetitive conversational engine, anti-sycophancy, human dignity). Pure system context exceeds 17,000 characters without user chats/history. Added unit test suite (B12). WiFi ADB enabled on 192.168.43.1:5555 with automatic Keystore key syncing.
- 2026-09-22 — v0.91.0: B3, B9 done (split DebugTalkService into AgentEngine, ToolDispatcher, PromptAssembler, AgentStep; stop control active in loop, comprehensive mobile tools added without bypasses). Remaining: B5, B6, B8, B14.
