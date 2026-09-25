# Project Progress (`PROGRESS.md`)

## Status Overview
- **Current Phase:** Master Plan: Personal Cognitive Companion & Usability Evolution (v0.103.0)
- **Last Updated:** 2026-09-24
- **Active Deliverables:** .env, agent.md, BUILD_PLAN.md, STACK_DECISION.md, TREE.md, PROGRESS.md, decision package, observation package, action package, character package (`CharacterMode`, `CharacterController`, `CharacterOverlayService`) + character UI (`CharacterViewModel`, `CharacterPanel`).

## Log of Updates
- **2026-09-22 — Zero-Shot Voice Clone, Clone-Only (v0.102.0):**
  - New [`VoiceCloneEngine.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/VoiceCloneEngine.kt): sherpa-onnx ZipVoice distill-int8 + vocos_24khz, reference `files/clone/ref.wav` (15s) + `ref.txt` (exact transcript, base.en-verified). Clone-only routing (system TTS emergency fallback); Kokoro engine + its 150MB model removed to save space (same AAR reused, arm64-only).
  - Pitfalls: `OfflineTtsZipVoiceModelConfig` (capital V); Python `generate(text, gen_config)`; vocoder lives in separate `vocoder-models` release; distill needs `num_steps=4`; reference text must match audio exactly.
  - Verified on JNY-LX1: "Clone engine loaded", "Spoke 247154 samples @24000Hz", process stable; 14/14 unit tests pass.
- **2026-09-22 — Kokoro Neural Voice, On-Device (v0.101.0, superseded by clone in v0.102.0):**
  - New [`KokoroVoiceEngine.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/KokoroVoiceEngine.kt): sherpa-onnx 1.13.8 AAR (vendored `app/libs`, arm64-only) + Kokoro int8 English, default voice af_bella (sid 1, 11 speakers mapped). Offline synthesis → AudioTrack PCM16. Model (~150MB) lives in `files/kokoro/`, pushed via adb — never in git/APK, never on mobile data.
  - [`ShiinaVoiceSpeaker`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/ShiinaVoiceSpeaker.kt) routes to Kokoro when ready, system TTS fallback otherwise; mood→speed mapping mirrors acoustics. `prewarmNeural()` at service start (DebugTalkService) so first reply speaks fast.
  - Settings: `neural_voice_enabled` (default on) + `kokoro_speaker_id` (default 1).
  - Pitfalls found live: OfflineTts MUST get null AssetManager for absolute paths (else native F-abort kills process); Huawei kills make chat-path verification flaky — verify via /api/events polls + screen-on.
  - Verified on JNY-LX1: "Engine loaded (af_bella)", "Spoke 16537 samples @24000Hz", 14/14 unit tests pass.
- **2026-09-22 — Shiina-CLI Prompt Transplant, Adapted On-Device (v0.100.0):**
  - Ported the portable core of the Shiina CLI system prompt (`~/Downloads/shiina-cli-prompt/`) into [`ShiinaPrompts.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), rewritten so she knows she lives on the phone, not in a terminal:
    - Direct Speech (SOUL port): reply length matches ask weight, finished tasks get short change-reports not process replays, no filler/restating/narrating tool calls.
    - Earned Depth + Plain & Honest: brief by default, detail only when asked or results demand it; unsure said plainly.
    - New `# Execution Discipline` section (CLI tool-use/finish/verify ports): act-don't-announce turns, finish-only-when-verified-on-device, verify-by-readback after state changes, blockers-over-fiction.
  - Left out CLI-only machinery (terminal paths, profiles, cron, skill index, gateway) — nothing in the app prompt references Hermes.
- **2026-09-22 — System Prompt Optimization: Assistant-First, Speak-Results & Shorter Context (v0.99.0):**
  - **Goal Alignment ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), `PROMPT_VERSION v8.1-assistant-grounded`)**:
    - Identity block compressed with "Assistant first: helpful action over chatter" + Reply Length rule (short default matching user energy, expand only when asked).
    - Added Favorites Relevance Rule (mention remembered favorites only when user reopens topic, related media is playing, or user asks) and Ask-First-When-Ambiguous rule (never guess/autoplay from memory).
    - Screen-grounding section compressed ~40% with zero behavior loss; TOOL_SPEC message field now orders silent intermediate lookups + DONE messages that speak actual observed results (fixes list-titles bug); CONCLUDE step requires data-first messages.
    - Compressed `TALK_DRIVE` (kept Fresh Session Etiquette + anti-drag lines verbatim).
  - **Skills Compression ([ShiinaSkills.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaSkills.kt))**:
    - All 7 playbooks compressed ~40% keeping every protocol and all test-anchored strings; added Ambiguous-Media ask-first (Skill 4) and Speak-the-Data (Skill 5).
  - **Execution-State & Final Speech ([PromptAssembler.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/PromptAssembler.kt))**:
    - DONE directive requires observed results in message; final-speech cap lifted to full list when user asked for one.
  - **Scenario Shortening ([DynamicLearningEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/DynamicLearningEngine.kt))**:
    - All 7 scenario strings shortened; all tags and test-anchored phrasing preserved.
  - Pure system context stays above the 10k-char test floor.
- **2026-09-22 — Conversational Session Boundaries & Anti-Clinging Prompt Refinement (v0.97.0):**
  - **Conversational Session Boundaries & Anti-Clinging Directive ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt))**:
    - Added `# Conversational Session Boundaries & Temporal Recency Etiquette` to `GLOBAL_RULES`: explicitly defines that a gap of 30+ minutes or 1-2+ hours represents a fresh session where previous topics (music, tunes, apps, old chatter) are expired background context.
    - Added Anti-Clinging & Topic Expiry rule: strictly forbids clinging to, obsessing over, or dragging up old topics when the user returns after a gap, unless the user specifically asks or resumes it.
    - Added Natural Present-Moment Greetings rule: when the user returns and greets ("hello", "good afternoon", "hey", etc.), respond directly to their present greeting and time of day without dredging up past topics.
    - Updated `TALK_DRIVE` with "Fresh Session Etiquette" and cleaned up context-weaving music references that prompted repetitive tune mentions.
    - Updated `bootGreetingPrompt`: tailored greetings for returning users after an absence, eliminating repetitive music questions.
  - **Session State & Gap Surfacing in Prompts ([PromptAssembler.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/PromptAssembler.kt))**:
    - Calculates exact elapsed time since the previous turn (`lastInteractionMillis` / `getPreviousTurnTimestamp`).
    - Appends clear `Conversational Recency: [NEW SESSION — User was offline/away for X, last active at Y]` directive directly into `# Live Context, Time & Session State`.
  - **Session Break Markers in Chat History ([ChatTurn.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/db/ChatTurn.kt))**:
    - Added `formatTurnsWithSessionBreaks` and `formatDuration`: inserts clean visual markers `--- [Session Break: X later] ---` between turns separated by 30+ minutes.
  - **Absence Return Real-World Scenario ([DynamicLearningEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/DynamicLearningEngine.kt))**:
    - Added `REAL-WORLD SCENARIO [USER RETURN AFTER X]`: dynamically added when user returns after 30m to 10h of inactivity, instructing Shiina to greet warmly in the present moment and not dwell on old topics.
    - Constrained music scenario to only mention music if relevant to user input and never derail greetings.
  - **Activity Tracking Accuracy ([UserActivityTracker.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/activity/UserActivityTracker.kt), [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), [AgentEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentEngine.kt))**:
    - Preserved `KEY_PREV_ACTIVE` so recording fresh activity does not erase the user's absence duration.
    - Used actual previous turn timestamps from `ChatHistory` for deterministic inactivity calculation.
  - **Unit Testing**:
    - Added `testSessionBoundaryAndAntiClingingRulesInPrompts`, `testDynamicLearningEngineUserReturnScenario`, and `testChatHistorySessionBreaksAndDuration` to [`SystemContextAndSkillsTest.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/test/java/com/shiina/mobile/SystemContextAndSkillsTest.kt).
    - 100% tests passing in 43s.
- **2026-09-22 — First-Run Guided Onboarding & Per-Turn Token Optimization (v0.96.0):**
  - **First-Run Guided Onboarding Wizard ([OnboardingScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/onboarding/OnboardingScreen.kt)) (Backlog B8)**:
    - Designed and implemented a modern Material 3 stepped onboarding wizard with animated transitions:
      1. *Meet Shiina*: Companion introduction, personality philosophy, emotional grounding.
      2. *Brain Setup*: Gemini API key entry, validation feedback, direct link to Google AI Studio, multi-key round-robin pooling explanation.
      3. *Core Sensory Permissions*: Explanatory cards with live status indicators and direct "Grant" buttons for Draw Over Other Apps (floating presence), Accessibility Service (UI inspection & action automation), Usage Access (app & habit awareness), Exact Alarms (punctual morning & reminder triggers), and Battery Optimization Exemption (background resilience on Huawei/OEM devices).
      4. *Voice & Vision Preferences*: Direct toggles for Voice TTS and Screen Vision Capture.
      5. *Getting Started*: Quick-start guidance for tapping the floating bubble, conversational commands, and autonomous macro learning.
    - Added persistent DataStore preference `onboardingCompleted` in [`SettingsRepository.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/settings/SettingsRepository.kt) and exposed via [`SettingsViewModel.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsViewModel.kt).
    - Integrated with [`MainActivity.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/MainActivity.kt) and added a "Replay Guided Setup Tour" action in [`SettingsScreen.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
  - **Per-Turn Token Optimization & Visual Downscaling ([ScreenshotTaker.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/observation/ScreenshotTaker.kt), [AgentEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentEngine.kt)) (Backlog B14)**:
    - Implemented high-efficiency downscaling in `ScreenshotTaker.saveOptimizedJpeg`: scales screen frames to max dimension 1024 with 75% JPEG quality while preserving full-resolution coordinate tracking in `ScreenMetrics`.
    - Reduces screenshot file payloads by over **90%** (from ~600KB down to ~40KB) and vision input tokens by **75%** (from ~2,000 tokens down to 258 tokens per capture).
    - Hardened capture freshness in [`GeminiProvider.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/GeminiProvider.kt) and [`AgentEngine.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentEngine.kt): only captures created within the last 60 seconds are attached, eliminating stale 30-minute screenshot attachments on proactive turns.
    - Added prompt prefix stability in `AgentEngine.kt`: reuses the pre-assembled `basePromptWithTools` across loop turns, allowing Gemini's KV cache and prefix caching to activate.
    - Added real-time `TOKEN_AUDIT` telemetry in `AppDebugServer`: tracks estimated text tokens, image tokens, prompt character counts, and compression metrics.
  - **Backlog Completion**:
    - With B8 and B14 verified and deployed, all items in [`BACKLOG.md`](file:///home/janelle/Documents/GitHub/Mobile-assistant/BACKLOG.md) (B1 through B14) are now 100% complete!
  - **Unit Testing & Hardware Verification**:
    - 100% unit tests passing (11/11 in 11s) in [`SystemContextAndSkillsTest.kt`](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/test/java/com/shiina/mobile/SystemContextAndSkillsTest.kt).
    - Deployed `versionCode = 96`, `versionName = "0.96.0"` to Huawei JNY-LX1 at `192.168.43.1:5555`. Verified clean launch, talk responsiveness, and live `TOKEN_AUDIT` logs.
- **2026-09-22 — Native Emotional Voice TTS, Bubble Thinking Shimmer & Autonomous Path Synthesis (v0.95.0):**
  - **Native Voice TTS Engine ([ShiinaVoiceSpeaker.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/ShiinaVoiceSpeaker.kt)) (Backlog B5)**:
    - Built dedicated Android TTS engine with initialization lifecycle management (`TextToSpeech.OnInitListener`).
    - Implemented intelligent markdown and syntax stripping (`sanitizeForSpeech`): removes bold/italics, code blocks, URLs, and excess emoji for clean natural speech.
    - Added dynamic emotional acoustic modulation: pouty/late-night pitch `0.93f`-`0.96f` and deliberate rate `0.88f`-`0.92f`; cheerful/warm pitch `1.12f` and brisk rate `1.04f`.
    - Integrated with `ChatBus.spokenUtterance` and exposed user toggle in Settings (`voiceTtsEnabled`).
  - **Bubble Thinking Pulse Shimmer & Real-Time Status Chips ([CharacterOverlayService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/CharacterOverlayService.kt)) (Backlog B6)**:
    - Added animated pulse shimmer border (`busyAlpha` infinite transition) and status chip display whenever `ChatBus.busy` is active.
    - Prevents appearance of application freeze during intermediate agent turns.
  - **Zero-Hardcoding Dynamic Proactive Sensing ([ProactiveLoop.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ProactiveLoop.kt))**:
    - Removed all hardcoded string reprimands and canned dialogue templates.
    - Delegated proactive triggers directly to `AgentEngine.runAgentLoop(isSystemTrigger = true)`: Shiina observes live physical senses (time, screen status, battery, active foreground app) and decides what to do dynamically.
    - Enabled late-night screen-on gate: Shiina checks in dynamically when user stays up late into the early morning hours.
  - **Procedural Autonomous Macro Execution (`EXECUTE_PROCEDURE`) ([ToolDispatcher.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolDispatcher.kt), [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - Added `EXECUTE_PROCEDURE` tool to execute learned action sequences at hardware speed without per-step LLM roundtrips.
  - **Self-Synthesizing Navigation Procedure Auto-Learner ([AgentEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentEngine.kt))**:
    - `AgentEngine` tracks successful multi-step navigation actions and automatically invokes `ProceduralMemoryStore.synthesizeFromCompletedRun` upon `DONE` to memorize new app workflows.
  - **Comprehensive Unit Testing & Verification ([SystemContextAndSkillsTest.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/test/java/com/shiina/mobile/SystemContextAndSkillsTest.kt))**:
    - 100% unit tests passing (9/9): verified TTS markdown stripping, procedural macro execution, autonomous synthesis, and pure system context (>20k chars).
  - **Live Hardware Verification**:
    - Deployed `versionCode = 95`, `versionName = "0.95.0"` to Huawei JNY-LX1 at `192.168.43.1:5555`. Verified dynamic late-night awareness, TTS initialization, and chat responsiveness.
- **2026-09-22 — Procedural Learning, App Navigation Memory & Macro Self-Optimization (v0.94.0):**
  - **Procedural Memory Store ([ProceduralMemoryStore.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/ProceduralMemoryStore.kt))**:
    - Created dedicated persistent procedural macro engine storing structured app workflows (`ProcedureEntry`, `ProcedureStep`) in durable JSON storage (`learned_procedures.json`).
    - Seeded initial standard procedures for Clock Alarm navigation, YouTube search and video playback, Wi-Fi controls, and Bluetooth pairing.
    - Added smart fuzzy matching (`findBestMatch`) utilizing direct trigger substrings, token overlaps, and app package/label indicators.
  - **Every-Prompt Memory Surfacing & Dynamic Invocations ([PromptAssembler.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/PromptAssembler.kt))**:
    - Every system prompt dynamically injects `# Learned App Navigation Procedures & Macro Memory` listing all known procedures.
    - If the user's active goal or foreground app matches a procedure, the exact ordered action steps are immediately highlighted so Shiina can follow them directly without blind exploration.
  - **Autonomous Step Pruning & Self-Optimization Tools ([ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt), [ToolDispatcher.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolDispatcher.kt))**:
    - `GET_PROCEDURE`: Inspects full step sequences for any learned procedure.
    - `LEARN_PROCEDURE`: Saves newly discovered app navigation workflows.
    - `REMOVE_PROCEDURE_STEP`: Removes redundant, obsolete, or unnecessary steps when faster routes are found.
    - `UPDATE_PROCEDURE_STEP`: Updates step actions, targets, or parameters when app UI elements change.
    - `OPTIMIZE_PROCEDURE`: Replaces an entire workflow with a streamlined, minimized step sequence.
    - `LIST_PROCEDURES`: Returns all active procedural memories.
    - `FORGET_PROCEDURE`: Deletes stale or invalid procedures.
  - **Operational Skills Playbook Expansion ([ShiinaSkills.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaSkills.kt))**:
    - Added `Skill 7: Procedural Learning, Navigation Memory & Macro Self-Optimization`.
    - Pure system context length exceeds **20,200 characters** (strictly excluding user chat and history).
  - **Unit Test Suite Coverage ([SystemContextAndSkillsTest.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/test/java/com/shiina/mobile/SystemContextAndSkillsTest.kt))**:
    - 100% unit tests passing (7/7): verified lifecycle of procedural memory (seed loading, trigger matching, adding, step removal, step update, optimization, deletion, and prompt summary).
  - **Live Hardware Device Verification**:
    - Compiled `versionCode = 94`, `versionName = "0.94.0"`. Installed over wireless ADB (`192.168.43.1:5555`).
    - Verified Shiina accurately recalled learned procedures and successfully pruned step #2 from her YouTube procedure via autonomous agent tool call.
- **2026-09-22 — Clock, Alarm & Direct System Navigation Grounding (v0.93.0):**
  - **Alarm & System Permissions ([AndroidManifest.xml](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/AndroidManifest.xml))**:
    - Added `com.android.alarm.permission.SET_ALARM`, `android.permission.SET_ALARM`, `android.permission.VIBRATE`, and `android.permission.CAMERA` to the manifest.
    - Eliminated OS-level `SecurityException` during direct `AlarmClock.ACTION_SET_ALARM` execution.
  - **Direct Intent Execution & UI Bypass ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt))**:
    - Updated `setAlarm` and `setTimer` with `EXTRA_SKIP_UI = true` and fallback to `ACTION_SHOW_ALARMS` / `ACTION_SHOW_TIMERS`.
    - Enhanced `openApp` with direct intent shortcuts for clock/alarm and system app alias keyword mapping.
  - **Accessibility Collapsed Node Descendant Text Extraction ([ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt))**:
    - Added `extractDescendantLabel(node)` in `dumpInteractiveElements`: containers lacking explicit labels inspect descendants, solving OEM collapsed tab bars (e.g. Huawei deskclock `[0,0][0,0]` TextViews under clickable LinearLayout).
    - Updated `clickText` with multi-stage fallback (cached elements, accessibility action click, and ancestor climbing for zero-bound nodes).
  - **Agent Step Time Parsing ([AgentStep.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentStep.kt), [ToolDispatcher.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolDispatcher.kt))**:
    - Added `hour`, `minute`, `seconds` parameters and natural language time parsing ("8:00 AM", "7:45 pm", "10 minutes").
    - Verified 8:00 AM alarm live on connected phone: set instantly in background via `SET_ALARM` without UI traps.
  - **Version Bump**:
    - Bumped `versionCode = 93`, `versionName = "0.93.0"`.
- **2026-09-22 — Human Dialogue Engine, Real-World Grounded Moods, Calm Task Execution & Comprehensive Skill Environment (v0.92.0):**
  - **Authentic Human Persona & Non-Repetitive Dialogue Engine ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt))**:
    - Eliminated generic robotic assistant tropes ("Certainly!", "I understand", "As an AI") and sycophantic behavior.
    - Grounded Shiina with a distinct peer identity, self-respect, dry humor, and authentic care.
    - Anti-Sycophancy & Non-Favorite Bias: Instructs Shiina to advocate for what is genuinely right, healthy, and constructive rather than blindly pandering to user rationalizations or destructive habits.
    - Dignity Against Dares: Shiina never acts merely out of being challenged or dared ("I bet you can't", "prove you have agency"). She acts because it is the right, constructive thing to do on her own terms.
  - **Comprehensive Operational Skills (>10k Pure System Context) ([ShiinaSkills.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaSkills.kt), [PromptAssembler.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/PromptAssembler.kt))**:
    - Implemented modular, deep behavioral playbooks spanning over 17,700 pure characters of system context (strictly excluding user chats and conversation history):
      - `Skill 1: Calm Task Execution & The Loading Settle Protocol`: Mandates deliberate pacing over haste. Instructs the AI on recognizing splash screens, skeleton loaders, and spinning indicators, and using `WAIT` instead of frantic retries.
      - `Skill 2: Real-World Scenarios & Well-being Guardianship`: Deep awareness of late-night hours (11 PM - 4:30 AM), low battery, screen fatigue, and daily routine phases.
      - `Skill 3: Personal Dignity, Anti-Manipulation & Principled Action`: Unyielding self-respect, resisting playground dares, and principled boundaries.
      - `Skill 4: Mobile App Navigation & Interaction Mastery`: Software keyboard dismissal heuristics, search result card vs. editable query disambiguation, and media closed-loop execution.
      - `Skill 5: Strict Zero Hallucination & Epistemic Honesty`: Never claiming completion before receiving verified tool receipts or visual confirmation.
      - `Skill 6: Continuous Learning & Lifelong Memory Integration`: Categorizing enduring habits vs. ephemeral states, and resolving contradictions gracefully.
  - **Real-World Situational Trigger Engine ([DynamicLearningEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/DynamicLearningEngine.kt), [MoodEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/character/MoodEngine.kt))**:
    - Built dynamic evaluator detecting live physical scenarios (`LATE_NIGHT_ACTIVE`, `MORNING_START`, `MIDDAY_BREAK`, `CRITICAL_BATTERY`, `MUSIC_LISTENING`, `NEGLECT`).
    - Tuned `MoodEngine` vector deltas: late-night active usage automatically pulls Shiina into a pouty, firm, and caring emotional state to push the user to sleep.
    - Categorized lifelong knowledge into 5 structured domains (Identity, Circadian & Routine, Preferences, Health & Well-being, User Principles).
  - **Calm Task Execution & Settle Delays ([ToolDispatcher.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolDispatcher.kt))**:
    - Enhanced `WAIT` tool: parses flexible duration parameters, applies delay, and returns visual screenshot confirmation with settle receipt.
    - Enhanced `OPEN_APP` and `SEARCH_APP` settle wait windows (2500ms) with explicit loading notices in execution state prompts.
  - **Developer Bridges & WiFi ADB Tooling ([AdbTalkReceiver.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/AdbTalkReceiver.kt), [send.py](file:///home/janelle/Documents/GitHub/Mobile-assistant/send.py))**:
    - Added `com.shiina.mobile.debug.SET_KEY` intent action and `send.py --sync-keys` flag, enabling seamless synchronization of API keys from local `.env` directly into device Keystore.
    - Verified wireless ADB connection on `192.168.43.1:5555`.
  - **Unit Testing Suite ([SystemContextAndSkillsTest.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/test/java/com/shiina/mobile/SystemContextAndSkillsTest.kt))**:
    - Implemented unit test suite verifying: pure system context length (>10,000 characters; measured 17,724 characters), skills completeness, real-world scenario detection, and agent step parsing.
  - **Version Bump**:
    - Bumped `versionCode = 92`, `versionName = "0.92.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts). APK compiled and installed to device over WiFi.
- **2026-09-22 — Agentic Workflow Optimization, Complete Mobile Toolset & Backend Architecture Refactor (v0.91.0):**
  - **Zero-Bypass Agentic Architecture ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), [AgentEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentEngine.kt))**:
    - Eliminated hardcoded string regex bypasses (`isSearchAsk`, `isScreenshotAsk`, `isMusicAsk`) from the talk pipeline.
    - All user queries and commands now run through the genuine, closed-loop Think-Act-Observe-Verify agent loop (`AgentEngine`), letting the AI query device senses and call tools autonomously without brittle heuristic shortcuts.
  - **Comprehensive Mobile Capabilities ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt), [ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt), [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - **Perception & Interaction**: Added `GET_FOREGROUND_APP` (real-time window package & label detection), `READ_SCREEN_TEXT` (instant accessibility tree text extraction without OCR overhead), `CLEAR_TEXT` (focused field clearing), `WAIT` (explicit settle delay for page/video loads), and long-press tap support (`is_long_press`).
    - **Device & System**: Added `SET_RINGER_MODE` (`normal`, `vibrate`, `silent`), `VIBRATE_DEVICE` (haptic buzz alerts), `SEND_NOTIFICATION` (status/reminder notifications in the drawer), and `CLOSE_APP` (home return).
    - **Media & Audio**: Added `GET_CURRENT_PLAYING` (reads active MediaSession track, artist, album, player app, and playback status).
    - **Planner & Clock**: Added `SET_ALARM` (`AlarmClock.ACTION_SET_ALARM`) and `SET_TIMER` (`AlarmClock.ACTION_SET_TIMER`), plus `FORGET` and `LIST_FACTS` memory tools.
  - **Automated Visual Verification & Grounding ([AgentEngine.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/AgentEngine.kt), [ToolDispatcher.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolDispatcher.kt))**:
    - Fixed turn blindness: after every UI-affecting action (`OPEN_APP`, `TAP_SCREEN`, `SWIPE_SCREEN`, `INPUT_TEXT`, `CLEAR_TEXT`, `PRESS_KEY`), the engine waits for UI animations to settle and automatically captures/attaches the live screenshot on the subsequent turn.
    - Injects active foreground package name and detailed receipts into `# Agent Execution State` so the AI immediately knows what changed.
  - **Modular Architecture Refactoring (Resolved BACKLOG B9 & B3)**:
    - Split bloated `DebugTalkService.kt` (1,332 lines) into focused, single-responsibility modules:
      - `AgentEngine.kt`: Manages the multi-turn agent loop, Gemini API requests, key pool rotation, and 429 failover.
      - `ToolDispatcher.kt`: Safe tool execution mapping, error sandboxing, and JSON schemas.
      - `PromptAssembler.kt`: System prompt assembly, live context/senses, and execution state injections.
      - `AgentStep.kt`: Model data class and resilient parsing with code fence and JSON-leak shields.
    - Reduced `DebugTalkService.kt` to 414 clean lines handling service lifecycle and notification panel.
    - Integrated interactive stop control (`ACTION_STOP`, `ChatBus.stopRequested`) to halt running agent loops instantly on demand.
  - **Native Accessibility Screenshots ([accessibility_service_config.xml](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/res/xml/accessibility_service_config.xml))**:
    - Added `android:canTakeScreenshot="true"`, enabling reliable, zero-prompt screen capture on Android 11+ directly via the Accessibility Service.
  - **Version Bump**:
    - Bumped `versionCode = 91`, `versionName = "0.91.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts). APK compiled and copied to `/home/janelle/Downloads/shiina-debug.apk`.
- **2026-09-20 — Multi-Turn Redundancy Resolution, Dynamic Orientation Handling & Gesture Retry (v0.86.0):**
  - **Investigation of Turn 8–13 Multi-Turn Redundancy**:
    - Analyzed the live monitor logs (`/api/events`) from the AniLab2 automation run.
    - Discovered that at Step 8, the search card tap was dispatched, but `TAKE_SCREENSHOT` at Step 9 fired after only 1000ms while the activity was still in the middle of a transition, capturing a stale frame.
    - At Step 10, the agent believed the tap had failed and re-tapped at the exact moment the window transitioned, causing Android's `dispatchGesture` to be cancelled.
    - `DeviceActionController.tapScreen` returned `"Could not tap screen. Ensure Accessibility Service is enabled in Settings."`, falsely convincing the AI that Accessibility had died and prompting it to invoke `OPEN_APP: com.xo.anilab`, throwing away progress and causing a 3-turn delay.
  - **Dynamic Screen Orientation Learning ([ScreenMetrics.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/observation/ScreenMetrics.kt))**:
    - Replaced static width/height storage with physical base dimensions (`baseShort` = `minOf(w, h)`, `baseLong` = `maxOf(w, h)`).
    - Added dynamic orientation detection (`isLandscape()` via `Configuration.orientation` and `WindowManager` display rotation) and `getCurrentDimensions()` so that switching between portrait and landscape (e.g. fullscreen video playback) never inverts screen metrics or offsets normalized coordinate calculations.
  - **Gesture Cancellation Auto-Retry ([ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt))**:
    - Added an automatic 150ms retry in `tap(x, y)` when `onCancelled` is received, ensuring momentary window animations or activity transitions do not drop tap gestures.
  - **Truthful Error Diagnostics ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt))**:
    - Differentiated between `service == null` (Accessibility Service genuinely disabled) and gesture cancellation or window busy states.
    - Returns actionable diagnostic feedback instructing the agent not to restart the application when a tap is cancelled during transitions.
  - **Optimized UI Settle Delay ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Increased `waitMs` for `TAP_SCREEN` from 1000ms to 1500ms, giving complex activity transitions, card clicks, and network loads sufficient time to render before `TAKE_SCREENSHOT` captures the verification frame.
  - **Version Bump**:
    - Bumped `versionCode = 86`, `versionName = "0.86.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Automatic Screen Resolution Learning & True Coordinate Mapping (v0.85.0):**
  - **ScreenMetrics Resolution Learner ([ScreenMetrics.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/observation/ScreenMetrics.kt))**:
    - Created dedicated `ScreenMetrics` component that automatically learns the real physical screen resolution via `WindowManager` real metrics (`maximumWindowMetrics` / `getRealMetrics`) and updates/confirms with actual captured screenshot bitmap dimensions.
    - Persists learned resolution across app restarts in SharedPreferences.
    - Provides precise `toPixels(x, y)` mapping from normalized `0..1000` AI vision space to true physical screen pixels, eliminating vertical and horizontal offsets caused by system bars (status bar, navigation bar).
  - **Accurate Tap & Swipe Dispatching ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt), [ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt))**:
    - In `tapScreen`, when visual coordinates `(x >= 0, y >= 0)` are specified, prioritized coordinate tapping mapped with learned `ScreenMetrics` over text matching. Prevents `clickText` from intercepting coordinate taps and clicking input fields containing search queries.
    - Added `lineTo(x, y)` in `ShiinaAccessibilityService.tap` to ensure OEM touch controllers (e.g. Samsung OneUI, MIUI) properly register touch gestures.
    - Updated `swipeScreen` to accurately translate start/end coordinates and directional offsets using learned screen bounds.
  - **Senses & Prompt Context Injection ([DeviceSenses.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/observation/DeviceSenses.kt), [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Added `screen_resolution` to `DeviceSenses.snapshot()`.
    - Injected `- Screen Resolution: {width}x{height} (normalized 0..1000)` into `# Live Context & Device Senses` in `DebugTalkService`.
    - Clarified normalized coordinate bounds `(0,0 is top-left, 1000,1000 is bottom-right)` in system prompt rules.
  - **Version Bump**:
    - Bumped `versionCode = 85`, `versionName = "0.85.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Tap Hierarchy Elimination & Pure Visual Grounding (v0.84.0):**
  - **Eliminated Tap Hierarchy Injections ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Removed `# Live Screen Grounding Hierarchy (Turn 1)` and loop turn `# Live Screen Grounding Hierarchy` prompt injections.
    - Eliminates massive context clutter, stale text dumps, and misleading node labels (such as search query text in editable input fields) that caused the AI to make incorrect tapping decisions.
  - **Visual Ground Truth & Coordinate Targeting ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Updated Rule 2 (`VISUAL GROUND TRUTH & SCREENSHOT-FIRST INTERACTION`) and `TASK WORKFLOW` to target elements directly from visual screenshots using normalized coordinates `(x: 0..1000, y: 0..1000)` or exact visible `text` in `TAP_SCREEN`.
    - Removed `element_id` and screen hierarchy references from system prompts and tool schemas.
    - Updated `APPS_TOOLSET` and `DEVICE_TOOLSET` in `ToolCatalog.kt` to focus `TAP_SCREEN` on coordinates/text and removed `INSPECT_SCREEN`.
  - **Version Bump**:
    - Bumped `versionCode = 84`, `versionName = "0.84.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — System Prompt Screenshot Priority Over Text Details (v0.83.0):**
  - **System Prompt Screenshot Priority ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Updated Rule 2 to `PRIORITIZE TAKING SCREENSHOTS OVER TEXT DETAILS`: mandates calling `TAKE_SCREENSHOT` over relying on text details whenever the agent needs to observe screen state, verify search results, or confirm actions.
    - Explicitly noted that textual screen hierarchy details are only a rough summary that can contain stale text, input field contents, or incomplete labels, whereas visual screenshots represent the true ground truth.
    - Updated `TASK WORKFLOW` and `CORE RULES` to emphasize taking screenshots before deciding actions or choosing element IDs.
  - **Clean Code Architecture ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Reverted synthetic header overrides and automatic capture hooks to keep the engine clean, modular, and driven directly by the AI calling `TAKE_SCREENSHOT`, while preserving the 1-second turn pacing guarantee.
  - **Version Bump**:
    - Bumped `versionCode = 83`, `versionName = "0.83.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Multimodal Vision Priority & Turn 1 Ground Truth Architecture (v0.82.0):**
  - **Multimodal Part Ordering & Turn 1 Screenshot ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Reordered multimodal Gemini request parts so the screenshot image (`inline_data`) is placed as the first part (`parts[0]`) before the prompt text (`parts[1]`), establishing visual reality as the primary perception modality.
    - Updated initial turn initialization to capture and attach the live screenshot on Turn 1 whenever `screenshotTaker.ready` is true, ensuring the model never begins execution blind.
    - Structured prompt headers to explicitly designate `# Visual Screen Perception (PRIMARY GROUND TRUTH)` vs `# Screen Coordinate Reference (SECONDARY TO SCREENSHOT)`.
  - **Version Bump**:
    - Bumped `versionCode = 82`, `versionName = "0.82.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — 1-Second Turn Pacing & Animation Settle Guarantee (v0.81.0):**
  - **Turn Pacing & Animation Settle ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Enforced a minimum 1-second delay (`1000ms`, with `2000ms` for `OPEN_APP`) between turns in the agent loop.
    - Prevents the agent from executing UI actions too quickly, giving UI transitions, network requests, and animations ample time to settle while allowing the user to visually follow agent interactions.
  - **Version Bump**:
    - Bumped `versionCode = 81`, `versionName = "0.81.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Visual Ground Truth & Screenshot-First Hierarchy Grounding (v0.80.0):**
  - **Visual Ground Truth Over Text Output ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Prioritized visual screenshots over textual hierarchy output: the screenshot is the ground truth of what is visually on screen (actual video playback, poster cards, open keyboard, dialogs), while the Live Screen Grounding Hierarchy serves as an `element_id` and coordinate targeting reference.
    - Added explicit rule preventing the agent from confusing input fields (`[Input Field: "..."]`) holding typed search queries with clickable search result cards.
  - **Editable Field Differentiation & Enter/Search Key Support ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt), [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - Updated `getScreenElementsSummary()` to clearly label editable elements as `[Input Field: "text"]` or `[Editable Input Field]`, ensuring the model never mistakes its own typed query for a clickable content card.
    - Added support for `"enter"` and `"search"` in `PRESS_KEY` (keycode 66) to allow submitting search queries.
  - **Direct Coordinate Physical Tap & Automatic Screenshot Attachment ([ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt), [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - In `clickElementById`, prioritized direct physical tap at element center coordinates over `clickNodeByText` to ensure real touch events (`MotionEvent.ACTION_DOWN` / `ACTION_UP`) are dispatched to custom views, cards, and video players.
    - Increased tap stroke duration to 100ms (standard Android tap timeout) with debug logging.
    - Updated `runTool` so UI actions automatically capture and attach fresh screen frames on subsequent turns when `screenshotTaker` is ready.
  - **Version Bump**:
    - Bumped `versionCode = 80`, `versionName = "0.80.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Critical Decision-Making & Match Integrity, Silent Step Headroom, & Turn Counter Removal (v0.79.0):**
  - **Removed Turn Limits & Step Countdowns ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Removed `lastTurnsLeft` and all `[Step N/M · K left]` prefixes from on-screen bubble messages to eliminate distracting countdown clutter and visual noise.
    - Removed `turns=N/15` and `Turns left: N/15` from the debug notification panel.
    - Removed `($remainingTurns turns remaining)` and urgent countdown wrap-up warnings from `# Agent Execution State` in the prompt, while increasing `MAX_AGENT_STEPS = 25` to provide ample execution headroom without artificial pressure.
  - **Critical Decision-Making & Match Integrity Rules ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Added Rule 2 `CRITICAL DECISION MAKING & MATCH INTEGRITY`: general, proper system prompt forbidding tapping unrelated search results or items that do not match the user's requested title without mentioning specific names or titles. Required to verify matches before acting, refine query/scroll, or honestly report unavailability rather than blindly opening incorrect cards.
    - Added Rule 3 `NO REPETITIVE BLIND ACTIONS`: forbids repeatedly tapping the same element (e.g. card/button) if the screen state does not advance after 2 attempts. Required to dismiss keyboards, scroll, or try alternatives.
    - Added `MATCH VERIFICATION` step to `TASK WORKFLOW` and reinforced `- NEVER OPEN WRONG SEARCH RESULTS` in `CORE RULES`.
  - **Version Bump**:
    - Bumped `versionCode = 79`, `versionName = "0.79.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Zero JSON Speech Leakage Shield & Final Speech Enforcement (v0.78.0):**
  - **Eliminated Raw JSON Leakage ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Fixed root cause where an empty `step.message` on completion fell back to the model's raw output string (`raw`), causing raw JSON (`{"thought": ..., "status": "DONE", ...}`) to be emitted directly to the user.
    - Added comprehensive JSON interception shield in both `isDone` and post-loop completion: any message starting with `{` or containing `"thought":` or `"status":` is intercepted, parsed for an inner text message, or synthesized into clean, authentic conversational speech.
    - Updated `stepSchema` with an explicit description on `message`: `"Natural conversational response spoken to the user. MANDATORY when status is DONE. Only omit or leave empty \"\" when executing intermediate tools silently."`
  - **Prompt Rules Hardening ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Added explicit rule `- NEVER EMIT RAW JSON AS SPEECH: The 'message' property must contain pure conversational natural speech. Never put JSON, schemas, code blocks, or thoughts into 'message'.`
    - Updated `TOOL_SPEC` to mandate conversational text in `message` when `status: 'DONE'`.
  - **Version Bump**:
    - Bumped `versionCode = 78`, `versionName = "0.78.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Silent Intermediate Execution & Guaranteed Final Talk Protocol (v0.77.0):**
  - **Optional Intermediate Message in JSON Schema ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Removed `message` from the `required` fields array in `stepSchema` (`required: ["thought", "status", "tool"]`). The model is no longer forced to invent dialogue during action turns.
    - During intermediate execution turns (`status: 'CONTINUE'`), if `step.message` is blank, the agent acts completely silently without pushing anything to the character bubble overlay.
  - **Guaranteed Speech at Final Completion ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - When `status: 'DONE'`, speech is strictly guaranteed: if `step.message` was empty, an automatic synthesis pass produces the final conversational reply grounded in the steps taken before returning and showing it on the overlay.
  - **Prompt & Tool Protocol Synchronization ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), [sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md))**:
    - Updated `TOOL_SPEC` and `TASK WORKFLOW`: instructs the agent to work silently (`message: ''`) during intermediate tool execution (`GET_TOOLSET`, actions, `TAKE_SCREENSHOT`) unless there is critical information to convey.
    - Formally documents that talking is guaranteed and required at the final step when `status: 'DONE'`.
  - **Version Bump**:
    - Bumped `versionCode = 77`, `versionName = "0.77.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Multi-Model Selection (Gemini 3.5 & 3.1 Flash-Lite), Automatic 429 Failover, & Turns Counter (v0.76.0):**
  - **In-App Model Target Selection ([SettingsRepository.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/settings/SettingsRepository.kt), [SettingsViewModel.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsViewModel.kt), [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt))**:
    - Added persistent DataStore preference `gemini_model` allowing users to select between `gemini-3.5-flash-lite` (default, balanced reasoning/vision) and `gemini-3.1-flash-lite` (lightweight, separate Google AI Studio quota).
    - Designed Material 3 Model Selection card in Settings with detailed explanations and radio selection.
  - **Automatic 429 Failover to Alternate Model ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - In `postText`, requests target the user's chosen model. If HTTP 429 (quota rate limit) is encountered, it reports the key to the cooldown pool and automatically fails over to the alternate model (`gemini-3.1-flash-lite` or `gemini-3.5-flash-lite`) which maintains an independent quota pool.
    - Updated [GeminiProvider.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/GeminiProvider.kt) and [MemoryConsolidator.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/MemoryConsolidator.kt) to dynamically respect the selected model.
  - **Agent Loop Turns Counter ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Added `@Volatile private var lastTurnsLeft: Int = 15`.
    - Real-time overlay status display: `[Step ${calls + 1}/15 · ${15 - (calls + 1)} left] <progress message>`.
    - Live notification debug panel updates: displays `turns: $lastTurnsLeft/15` in the title and `Turns left: $lastTurnsLeft/15` in the expanded panel text.
    - Injected into prompt `# Agent Execution State`: `- Current step: #${calls} of max 15 ($remainingTurns turns remaining)`. Injects urgent wrap-up directive when `remainingTurns <= 3`.
  - **Version Bump**:
    - Bumped `versionCode = 76`, `versionName = "0.76.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Round-Robin API Key Pool & Multi-Key Settings Management (v0.75.0):**
  - **Round-Robin Key Pool Core ([RoundRobinKeyPool.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/RoundRobinKeyPool.kt), [AppContainer.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/di/AppContainer.kt))**:
    - Upgraded `RoundRobinKeyPool` with fallback capability (if all keys hit cooldown, it rotates to the next key instead of returning null) and added `size()` helper.
    - Promoted `geminiKeyPool` to a public property on `AppContainer` shared across `ProviderRegistry`, `DebugTalkService`, and `MemoryConsolidator`.
  - **Multi-Key Storage ([KeyStoreKeys.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/data/security/KeyStoreKeys.kt))**:
    - Added `addKey(provider, key)` and `removeKey(provider, key)` methods to safely manage multiple API keys in encrypted shared preferences.
  - **Automatic Failover & 429 Resilience ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), [MemoryConsolidator.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/MemoryConsolidator.kt))**:
    - In `postText`, dynamically rotates keys via `keyPool.next()` across attempts.
    - If a key returns HTTP 429, it puts the offending key on a 60-second cooldown and automatically falls over to the next key in the pool, preventing rate limit errors.
  - **Settings UI for Multi-Key Pool ([SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt), [SettingsViewModel.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsViewModel.kt))**:
    - Added list of active keys in rotation (masked with `••••••••`) with one-tap removal (`Delete` icon).
    - Added "Add Gemini API Key" input row with an "Add" button and password toggle.
    - Displays live key pool count badge (`N keys active in pool`).
  - **Version Bump**:
    - Bumped `versionCode = 75`, `versionName = "0.75.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Dynamic Mood Balancing & Explicit Visual Screenshot Workflow (v0.74.0):**
  - **Dynamic Mood Balancing & Peer Dignity Protocol ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt))**:
    - Added `PEER DIGNITY & EMOTIONAL BOUNDARIES` to `GLOBAL_RULES`: Shiina is an equal peer with self-respect; when the user is rude, insulting, or disrespectful, she does not laugh it off with banter or act like a subservient bot—she switches to `firm` or `pouty` and sets direct boundaries.
    - Expanded `MOOD_PROMPTS`: `pouty` (sulky, offended, hurt by dismissal/harshness, neglected), `firm` (stern accountability, calling out disrespect, setting boundaries), `warm` (soothing, validates feelings, softens when user apologizes).
    - Updated `TOOL_SPEC` with balanced `MOOD TRIGGERS` across all 5 moods (`calm`, `candid`, `firm`, `warm`, `pouty`).
  - **Explicit TAKE_SCREENSHOT Workflow ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - Empowered the AI to explicitly control when to capture and inspect the screen by calling `TAKE_SCREENSHOT` (status: `CONTINUE`).
    - When `TAKE_SCREENSHOT` is invoked, a fresh screen frame is captured and attached as an image on the subsequent turn, with an explicit directive prompting visual verification against the primary goal.
    - Updated `ToolCatalog.kt` to clearly document `TAKE_SCREENSHOT` in `APPS_TOOLSET` and `DEVICE_TOOLSET`.
  - **Graceful Gemini 429 Handling ([MemoryConsolidator.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/MemoryConsolidator.kt))**:
    - Added graceful handling for HTTP 429 (Too Many Requests / Rate Limit) during background memory consolidation so it logs a gentle warning instead of alarming errors.
  - **Static Prompt Sync & Version Bump ([sysprompt.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/sysprompt.md), [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts), [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt))**:
    - Synchronized `sysprompt.md` with new static rules and tool specs.
    - Bumped `versionCode = 74`, `versionName = "0.74.0"`.
- **2026-09-20 — Agent Guidelines Hardening: Universal Think-Act-Observe-Verify & Zero Bypasses (v0.72.0):**
  Codified mandatory agent behavior in [agent.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/agent.md) to permanently prevent domain-narrow hallucinations (e.g. music/anime focus) and hardcoded shortcuts:
  - **Rule 17 (Universal Protocol & Zero Bypasses)**: Prohibits hardcoded task bypasses, keyword regexes, or task-specific shortcuts; forbids domain-narrow rules; mandates automatic live vision on every turn (no guessing); establishes precision UI targeting priority (`element_id` > `text` > coordinates).
  - **Rule 18 (Mandatory Visual Goal Verification & Persistent Execution)**: Forbids stopping at intermediate steps or unprompted hand-offs to the user; mandates keeping `status: 'CONTINUE'` until the screen visually confirms goal completion before setting `status: 'DONE'`.
  - **Version & UI Update**:
    - Bumped `versionCode = 72`, `versionName = "0.72.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Universal Think-Act-Observe-Verify Protocol, Every-Turn Vision & Elimination of Bypasses (v0.71.0):**
  Addressed user directive to remove all hardcoded task bypasses/heuristics and universalize the loop logic across all tasks (not just music or anime/video), enforcing continuous visual verification on every turn:
  - **Loop Core Universalization ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Completely removed hardcoded bypass functions (`isMultiStepGoal`, `isStopOrCancelCommand`, `isGoalAchieved`) and premature completion interceptor.
    - Implemented universal visual perception on every turn: Turn 1 captures and attaches an initial screenshot, and every subsequent turn attaches the live screenshot (`attachShot = hasScreenshot`) alongside `# Live Screen Grounding Hierarchy`.
    - Automatically waits 700ms for UI transition and captures a fresh screenshot after every action tool.
    - Injects universal `nextInstruction` compelling the model to inspect the live screenshot, verify outcome against the user's primary goal, keep `status: 'CONTINUE'` until the goal is visually achieved, and only conclude with `status: 'DONE'` when the screen visually confirms goal completion (or if the user commands to stop).
  - **Universal Prompts & Rules ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt))**:
    - In `GLOBAL_RULES`, updated to `UNIVERSAL GOAL PERSISTENCE & MANDATORY VERIFICATION`: applies to all tasks (media, apps, settings, search, UI interaction). Forbids premature `status: 'DONE'` or asking the user to finish the task; mandates every-turn screenshot analysis and visual confirmation of goal completion before finishing.
    - In `TOOL_SPEC`, generalized section 3 to `SCREEN AUTOMATION & VISUAL GROUNDING WORKFLOW`: emphasizes no guessing, refreshed screenshots on every turn, and mandatory visual goal verification.
    - In `CORE TASK RULES`, updated Rule 8 to `UNIVERSAL VISUAL GOAL VERIFICATION`.
  - **Tool Catalog Generalization ([ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - Replaced media-specific guidelines in `APPS_TOOLSET` with universal mandatory visual goal verification.
  - **Version & UI Update**:
    - Bumped `versionCode = 71`, `versionName = "0.71.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Mandatory Goal Verification, Playback Enforcement & Premature Completion Interception (v0.70.0):**
  Addressed issue where Shiina stopped prematurely after merely opening an application without verifying that the user's primary goal (e.g. playing an anime/video/song) was actually achieved:
  - **Premature Completion Interceptor ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Added `isMultiStepGoal(userText)`, `isStopOrCancelCommand(text)`, and `isGoalAchieved(userText, steps, screenElements)`.
    - In `askGemini`, intercepted cases where the model declared `status: 'DONE', tool: 'NONE'` before verified playback occurred (e.g. only opened app or only navigated to search). The harness rejects premature completion, logs the interception, captures a fresh screenshot, dumps the screen hierarchy, and injects a high-priority directive compelling the agent to continue until playback is verified on screen.
    - Added explicit `- Primary Goal: "$userText"` and `- Goal Status: IN PROGRESS / PLAYBACK VERIFIED` tracking in `# Agent Execution State` on every loop turn.
    - Honors user stop/cancel commands immediately.
  - **Goal Persistence & Mandatory Verification Rules ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt))**:
    - In `GLOBAL_RULES`, added `GOAL PERSISTENCE & MANDATORY VERIFICATION`: strictly forbids stopping after merely opening an app, forbids asking the user to take over, and mandates verifying active playback on screen before setting status `DONE`.
    - In `TOOL_SPEC` and Rule 8, formulated Mandatory Playback Verification: instructs that if the screen shows cards, tap one; if details page, tap Play/Episode 1; and keep status `CONTINUE` until playback is verified on the attached screenshot.
  - **Tool Catalog Updates ([ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - In `APPS_TOOLSET`, updated guidelines with Mandatory Task Completion & Verification.
  - **Version & UI Update**:
    - Bumped `versionCode = 70`, `versionName = "0.70.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — UI Hierarchy Grounding, Precision Screen Tapping & Element-ID Selection (v0.69.0):**
  Addressed coordinate drift and visual targeting error on high-resolution displays (1080x2310) by implementing direct UI hierarchy inspection and element-ID-based screen interaction:
  - **UI Hierarchy Grounding ([ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt))**:
    - Created `UiElement` data class (`id`, `text`, `desc`, `type`, `centerX`, `centerY`, `bounds`, `isClickable`, `isEditable`).
    - Implemented `dumpInteractiveElements()` traversing `rootInActiveWindow` to discover all visible, interactive, or text-labeled elements on screen, deduplicating them and assigning sequential element IDs (1..N).
    - Implemented `clickElementById(id)`: attempts direct accessibility node click, falling back to physical tap gesture on the element's exact screen center coordinates (`centerX`, `centerY`).
    - Implemented `clickText(text)`: searches hierarchy for node by text with physical tap gesture fallback on node center bounds.
  - **Screen Element Grounding & Precision Tap Routing ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt))**:
    - Implemented `getScreenElementsSummary()`: outputs display resolution (e.g. `1080x2310`) and a compact formatted list of visible interactive elements (e.g. `- [1] "Search" at (756, 2217) (clickable)`).
    - Updated `tapScreen(x, y, text, elementId)`: establishes targeting priority:
      1. `elementId > 0`: targets exact element from screen hierarchy via `clickElementById`.
      2. `text`: targets element by text with tap fallback via `clickText`.
      3. `x, y`: scaled from 0..1000 normalized coordinates or executed as raw display pixels.
  - **Loop Core & Schema Integration ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Added `elementId` to `AgentStep`, `parseAgentStep`, and `toolArgsSchema`.
    - Added `INSPECT_SCREEN` to `KNOWN_TOOLS`.
    - Automatically injects `# Screen Grounding Hierarchy` into the prompt execution state following UI actions (`OPEN_APP`, `TAP_SCREEN`, `SWIPE_SCREEN`, `INPUT_TEXT`, `TAKE_SCREENSHOT`, `INSPECT_SCREEN`).
  - **Tool Catalog & Prompt Updates**:
    - Updated [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt) and [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) documenting `element_id` parameter and grounding priority (element_id > text > coordinates).
  - **Version & UI Update**:
    - Bumped `versionCode = 69`, `versionName = "0.69.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts) and [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Normalized Coordinate Mapping & Automatic Screenshot Attachment (v0.68.0):**
  Diagnosed monitor session logs from real-device testing where `TAP_SCREEN` was invoked with normalized coordinates (x: 700, y: 960) that fell into blank space due to unmapped pixel resolution (1080x2310):
  - **Normalized Coordinate Resolution ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt))**:
    - Added automatic scale detection: when coordinates are in 0..1000 scale (standard LLM vision output), mapped them to real screen pixels `(x / 1000f) * screenWidth` and `(y / 1000f) * screenHeight` (e.g. mapping 700, 960 to 756, 2217 on 1080x2310).
    - Prioritized `clickNodeByText(text)` so button/tab labels (e.g. "Search", "Genres") are clicked directly on the accessibility tree.
  - **Automatic Vision Attachment & Step Runway ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Automatically captures and attaches screenshots to the prompt after `OPEN_APP`, `TAP_SCREEN`, `SWIPE_SCREEN`, and `INPUT_TEXT`, eliminating wasted turns spent manually calling `TAKE_SCREENSHOT`.
    - Increased `MAX_AGENT_STEPS` from 8 to 15 to provide sufficient runway for complex multi-step app navigation.
  - **Tool Catalog & Prompt Updates**:
    - Updated [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt) and [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) documenting normalized 0..1000 coordinates, preferred text clicking, and auto-screenshot behavior.
  - **Version & UI Update**:
    - Bumped `versionCode = 68`, `versionName = "0.68.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts).
    - Updated version in [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt).
- **2026-09-20 — Accessibility Service Toggle & Settings Integration (v0.67.0):**
  Added Accessibility service management and direct configuration to the app's Settings and Permissions screen:
  - **Permissions & Settings Integration**:
    - In [Permissions.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/permissions/Permissions.kt), implemented `hasAccessibilityAccess(context)` (checking live service state and `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`) and `openAccessibilitySettings(context)` (directing user to `Settings.ACTION_ACCESSIBILITY_SETTINGS`).
    - Integrated accessibility into `hasAllPermissions(context)`.
    - In [PermissionScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/permissions/PermissionScreen.kt), added "Accessibility service" item to `PermissionSection` with a "Grant" button that opens Android Accessibility Settings directly and reflects live "Granted" status when enabled.
    - In [SettingsScreen.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/ui/settings/SettingsScreen.kt), updated version display to v0.67.0.
  - **Version Update**: Bumped `versionCode = 67`, `versionName = "0.67.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts).
- **2026-09-20 — Automatic APK Export to Downloads Folder on Build (v0.66.0):**
  Updated `app/build.gradle.kts` with a custom `assembleDebug` task extension that automatically copies `app-debug.apk` to `/home/janelle/Downloads/shiina-debug.apk` immediately upon successful compilation. Verified clean build and successful copy.
- **2026-09-20 — Autonomous Screen Interaction & Visual Verification Engine (v0.65.0):**
  Eliminated stale hardcoded prompt instructions and equipped Shiina with the ability to visually verify screens and automatically tap, swipe, and type inside apps to execute tasks:
  - **Stale Prompt Instruction Elimination**:
    - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), removed the lingering hardcoded music example `(e.g. playing a song from the library, or adjusting state)` from the intermediate loop prompt.
    - Replaced with dynamic, task-aware instructions:
      - After `OPEN_APP`: instructs the agent to invoke `TAKE_SCREENSHOT` (`status: 'CONTINUE'`) to verify what is displayed on the screen and inspect UI layout/buttons.
      - After `TAKE_SCREENSHOT`: confirms vision attachment and instructs locating coordinates/buttons to tap via `TAP_SCREEN` or type via `INPUT_TEXT`.
      - After screen interactions (`TAP_SCREEN`, `SWIPE_SCREEN`, `INPUT_TEXT`, `PRESS_KEY`): guides further verification via screenshot or conclusion.
  - **Screen Automation Engine ([ShiinaAccessibilityService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ShiinaAccessibilityService.kt))**:
    - Created Android Accessibility Service with `dispatchGesture` for precision coordinate tapping (`tap(x, y)`), smooth scrolling/swiping (`swipe(startX, startY, endX, endY)`), element clicking by text (`clickNodeByText`), text entry (`inputText`), and global keys (`pressGlobal`).
    - Configured in [res/xml/accessibility_service_config.xml](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/res/xml/accessibility_service_config.xml) and registered in [AndroidManifest.xml](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/AndroidManifest.xml).
  - **Action Bridging & Dual Execution ([DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt))**:
    - Implemented `tapScreen(x, y, text)`, `swipeScreen(startX, startY, endX, endY, direction)`, `inputText(text)`, and `pressKey(action)` supporting both AccessibilityService gestures and shell/ADB fallbacks.
  - **Toolset & Prompt Expansion**:
    - In [ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt), added `TAKE_SCREENSHOT`, `TAP_SCREEN`, `SWIPE_SCREEN`, `INPUT_TEXT`, and `PRESS_KEY` to `apps` and `device` toolsets with schemas.
    - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), formulated the App Execution & Screen Interaction Workflow and added Core Rule 8 (Visual Verification Before Concluding App Tasks).
    - In [agent.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/agent.md), added Rule 17 (Visual Verification & Autonomous Screen Interaction).
  - **Loop Core & Schema Integration**:
    - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), registered `"TAP_SCREEN"`, `"SWIPE_SCREEN"`, `"INPUT_TEXT"`, `"PRESS_KEY"`, added coordinate fields to `AgentStep`, and wired `runTool` and `toolArgsSchema`.
- **2026-09-20 — On-Demand Hierarchical Toolset Discovery Architecture (v0.65.0):**
  Eliminated prompt flooding and tool dumping by transitioning from static full-tool dumps to an on-demand, hierarchical toolset discovery architecture:
  - **On-Demand Tool Catalog ([ToolCatalog.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ToolCatalog.kt))**:
    - Partitioned all tools and specialized actuation instructions into domain toolsets:
      - `media`: `SEARCH_MUSIC`, `PLAY_MUSIC`, `MEDIA_CONTROL`, `VOLUME_CONTROL` with detailed parameter schemas and playback rules.
      - `apps`: `OPEN_APP`, `SEARCH_APP`, `LIST_APPS` with exact app-launch and search semantics.
      - `device`: `GET_DEVICE_STATE`, `VOLUME_CONTROL`, `TAKE_SCREENSHOT`, `SET_MODE` with state-inspection guidelines.
      - `web`: `SEARCH_WEB`, `READ_URL` with search and content-fetching protocols.
      - `planner`: `SET_REMINDER`, `LOG_GOAL`, `CHECK_GOALS`, `COMPLETE_GOAL` with goal and reminder schemas.
    - Provided `ToolCatalog.TOOLSET_OVERVIEW` for lightweight base prompt inclusion and `ToolCatalog.getToolset(name)` for on-demand loading.
  - **Base Prompt Modernization ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt))**:
    - Replaced all verbose tool descriptions in `TOOL_SPEC` with high-level toolset categories and the `GET_TOOLSET` meta-tool.
    - Standardized the on-demand execution loop: (1) `GET_TOOLSET` -> (2) Actuate -> (3) Observe -> (4) Conclude.
    - Maintained strict global rules prohibiting blind one-step execution, premature confirmations, and gaslighting.
  - **Loop Core & Schema Integration ([DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt))**:
    - Added `GET_TOOLSET` to `KNOWN_TOOLS`.
    - Added `toolset` property to `AgentStep`, `parseAgentStep`, and `toolArgsSchema`.
    - Wired `GET_TOOLSET` in `runTool` to return `ToolCatalog.getToolset(...)`.
  - **Operating Guidelines Update ([agent.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/agent.md))**:
    - Added Rule 16 specifying the On-Demand Hierarchical Toolset Architecture and prohibiting full tool dumps.
  - **Repository & Version Updates**:
    - Added `ToolCatalog.kt` to [TREE.md](file:///home/janelle/Documents/GitHub/Mobile-assistant/TREE.md).
    - Bumped `versionCode = 65`, `versionName = "0.65.0"` in [app/build.gradle.kts](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/build.gradle.kts).
- **2026-09-20 — Multi-Domain General Task Harness & Balanced Loop Logic (v0.64.0):**
  Eliminated over-specialization on music and restructured the prompt architecture and loop harness to be a general, robust task execution engine across all device operations:
  - **Universal Think-Act-Observe-Verify Protocol**:
    - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), restructured `GLOBAL_RULES` and `TOOL_SPEC` to generalize across all device tasks (launching apps, in-app searching, web search, page reading, volume and media control, reminders, goal tracking, and screenshot analysis).
    - Established the 4-phase closed loop:
      1. *Phase 1 (Inspect & Gather Facts)*: Use `LIST_APPS`, `GET_DEVICE_STATE`, `SEARCH_MUSIC`, `SEARCH_WEB`, or `TAKE_SCREENSHOT` (`status: 'CONTINUE'`) if information is required before acting.
      2. *Phase 2 (Execute Action)*: Invoke the actuation tool (`OPEN_APP`, `SEARCH_APP`, `VOLUME_CONTROL`, `MEDIA_CONTROL`, `PLAY_MUSIC`, `SET_REMINDER`, `LOG_GOAL`, `COMPLETE_GOAL`, `SET_MODE`) with `status: 'CONTINUE'`.
      3. *Phase 3 (Observe & Verify)*: Review the observation receipt returned by the environment.
      4. *Phase 4 (Conclude & Respond)*: Set `tool: 'NONE'` and `status: 'DONE'`, delivering a final conversational message strictly grounded in verified facts.
      5. *Pure Conversation*: Direct chatting/bantering completes in 1 step with `tool: 'NONE'`, `status: 'DONE'`.
  - **Universal Rules**:
    - *No Blind One-Step Actions*: All actions require `status: 'CONTINUE'` so receipts are observed.
    - *Zero Premature Confirmation*: Intermediate messages are strictly progress updates, preventing false claims before receipt verification.
    - *Observation Grounding & Zero Hallucination*: Prohibits claiming an action worked when the receipt shows it failed, was blocked, or had no results.
    - *Parameter Precision*: Requires explicit arguments across all tools without omission.
- **2026-09-20 — Loop Core & Harness Restructuring: True Closed-Loop & Grounded Actuation (v0.63.0):**
  Investigated device debug logs revealing false positive observations and premature speech output (where Shiina falsely reported music playing when it was not found, gaslighting the user after dead `playFromSearch` calls to Chrome):
  - **Harness & Observation Grounding in [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt)**:
    - `searchMusic(query)`: Added browsing support for empty/all queries (returning top 20 library tracks) and fallback candidate suggestions (`available_tracks`) when specific searches yield 0 results, allowing Shiina to perceive real songs on the device instead of guessing.
    - `playSong(query, player)`: Eliminated blind `playFromSearch` calls to inactive/browser media sessions that returned false positive `"requested"` receipts. If local MediaStore has no match, it now genuinely launches YouTube search in the browser (`https://www.youtube.com/results?search_query=...`) and returns an honest receipt (`status: "not_found_locally"`, `action_taken: "opened_in_browser"`, `is_music_active`).
    - Added `is_music_active` verification to all media observation receipts.
  - **Loop Core Restructuring in [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt)**:
    - Fixed answer assignment: `answer` is now strictly assigned ONLY when `isDone` is true (`status: "DONE"` and `tool: "NONE"`). Intermediate messages during tool execution (`status: "CONTINUE"`) are pushed to the overlay as live progress bubbles and NEVER overwrite the final answer or leak premature claims into conversation history.
  - **System Prompt & Workflow Discipline in [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt)**:
    - Added **Zero Gaslighting** and **Zero Premature Confirmation** rules to `GLOBAL_RULES` and `TOOL_SPEC`.
    - Formulated the **Grounded Music Workflow**:
      1. Inspect via `SEARCH_MUSIC` (`status: "CONTINUE"`).
      2. Read returned `tracks` or `available_tracks` and select an actual song.
      3. Call `PLAY_MUSIC` (`status: "CONTINUE"`).
      4. Inspect the `PLAY_MUSIC` receipt (status, `is_music_active`), and report the genuine truth (`tool: "NONE"`, `status: "DONE"`).
- **2026-09-20 — Closed-Loop Task Execution: Know-Act-Observe Protocol & Prohibition of One-Step Actions (v0.62.0):**
  Eliminated blind "one-step" execution to guarantee that Shiina understands what she is doing before acting and verifies the outcome before confirming:
  - **Prohibition of Blind One-Step Actions**:
    - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), updated `GLOBAL_RULES` and `TOOL_SPEC` to strictly forbid setting `status: 'DONE'` when invoking any action tool.
    - Mandated the **3-Phase Closed-Loop (KNOW -> ACT -> OBSERVE -> CONCLUDE)**:
      1. *Phase 1 (Know/Inspect)*: Use `SEARCH_MUSIC`, `GET_DEVICE_STATE`, or `LIST_APPS` with `status: 'CONTINUE'` to discover available tracks, apps, or state before acting.
      2. *Phase 2 (Act)*: Invoke the actuation tool (`PLAY_MUSIC`, `VOLUME_CONTROL`, `MEDIA_CONTROL`, `OPEN_APP`) with `status: 'CONTINUE'` so the system returns an observation receipt.
      3. *Phase 3 (Observe & Conclude)*: Inspect the observation receipt, verify the outcome, and conclude with `tool: 'NONE'`, `status: 'DONE'`, and a conversational response grounded in what actually happened.
      4. *Pure Conversation*: Chat without device tasks concludes directly in 1 step with `tool: 'NONE'`, `status: 'DONE'`.
  - **Loop Termination Guard in [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt)**:
    - Updated `isDone` so the loop only terminates when `tool == "NONE"`. If an action tool was invoked, the loop cannot break early—the tool is executed, and its observation receipt is fed back into the prompt so Shiina is required to observe and verify the outcome before reporting back to the user.
- **2026-09-20 — Expanded Global Prompt & Grounded Device Agency (v0.61.0):**
  Significantly expanded Shiina's global prompt architecture ([ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) v5.0) to align with the new environment harness:
  - **`GLOBAL_RULES` Expanded**:
    - Integrated **Device Agency & Environment**: explicitly establishes that Shiina lives on the device, shares it with the user, and has real hands/capabilities to interact with the environment. Mandates executing tools decisively rather than roleplaying in text or behaving like a helpless bot or submissive butler.
    - Added **Action Fidelity**: explicitly grounds that conversational text cannot change device settings and strictly forbids claiming an action happened in chat without invoking the tool in that turn.
    - Added **Grounded Reality**: reinforces relying on provided context, device senses, and observation receipts.
  - **`TALK_DRIVE` Refined**:
    - Added **Effortless Companion Agency**: guides Shiina to handle device tasks (passing the aux cord, adjusting volume) naturally and without formal butler responses or resistance.
  - **`TOOL_SPEC` Expanded**:
    - Detailed `tool_args` parameters and types for all tools.
    - Explicitly defined **Loop Control & Execution Patterns**: single-step execution (`status: "DONE"`) for direct actuations vs. inspection & multi-step execution (`status: "CONTINUE"`) for searching/discovering.
    - Detailed affordance documentation distinguishing `PLAY_MUSIC` (required query, specific tracks) from `MEDIA_CONTROL` (generic pause/resume/stop), `SEARCH_MUSIC` (inspection without playback), and `VOLUME_CONTROL` (audio volume).
    - Structured 8 core rules covering Action Fidelity & Zero Hallucination, Track Playback vs Generic Playback, Volume vs Media, Observation Grounding, No Meta Leaks, Device Senses Awareness, Non-Repetitive Responses, and Authentic Companion Cadence.
- **2026-09-20 — Grounded Agent Harness: Constrained Decoding (response_schema), Inspection-Actuation Orthogonality & Structured Receipts (v0.60.0):**
  Eliminated fragile regex bypasses and fundamentally upgraded Shiina's agent harness so the engine guarantees valid JSON arguments, separates inspection from actuation, and provides structured observation feedback:
  - **Constrained Decoding via Gemini `response_schema`**:
    - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), upgraded `postText` to supply `generationConfig` with `response_mime_type = "application/json"` and `response_schema` directly to the Gemini API.
    - The JSON schema strictly enforces `thought`, `mood`, `status` (`"acting" | "done"`), `tool` (enum matching allowed tools), `tool_args` (with typed properties `query`, `action`, `level`, `app`, `player`, `filter`), and `message`. This eliminates parameter omission and invalid tool names at the decoding level.
    - Fully removed regex/string bypasses (`extractSongQuery` and `reconcileMissingTool`).
  - **Inspection vs Actuation Affordance Design**:
    - Separated music search from playback: implemented `SEARCH_MUSIC` in [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt), [DecisionModels.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/DecisionModels.kt), [ActionExecutor.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ActionExecutor.kt), [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt), and [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt). Shiina can now inspect candidate tracks in `MediaStore` (`[{"id":..., "title":..., "artist":..., "duration_sec":...}]`) before deciding to act.
    - Dedicated audio control: implemented `VOLUME_CONTROL` supporting `restore`, `mute`, `unmute`, `volume_up`, `volume_down`, and direct percentage `set` (0..100).
  - **Structured Observation Receipts**:
    - Upgraded all tools in [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt) (`playSong`, `controlMedia`, `searchApp`, `openApp`, `listApps`, `getDeviceState`, `searchMusic`, `volumeControl`) to return structured JSON receipts (e.g. `{"tool":"PLAY_MUSIC", "status":"playing", "source":"local_storage", ...}`) into the Think-Act-Observe loop, providing grounded facts for the next turn.
- **2026-09-20 — Action Reconciliation Safety Net, Query Extraction & Volume Restoration (v0.59.0):**
  Resolved execution failures discovered from device monitor logs where Shiina either omitted tool parameters (e.g. calling `PLAY_MUSIC` with blank `query`, causing fallback to generic media resume) or hallucinated action completion in conversational text while returning `tool: "NONE"` (e.g. stating "Fine, volume restored" or "Alright, digging up Perfect for you" without executing any tool):
  - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt):
    - Added Rule 10 (Anti-Hallucination of Actions): strictly prohibits claiming or confirming actions (volume, music, apps) in conversational text while returning `tool: "NONE"`, and mandates that `query` must never be omitted on `PLAY_MUSIC`.
    - Updated `TOOL_SPEC` to document `restore` action for `DEVICE_ACTION` and explicit required query requirement for `PLAY_MUSIC`.
  - In [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt):
    - Upgraded `playSong`: query sanitization (strips extraneous quotes/punctuation), exact phrase matching on MediaStore with a multi-keyword fallback across `TITLE`, `DISPLAY_NAME`, and `ARTIST` (so tracks like `Conan Gray - Heather (Official Lyric Video).mp3` match search queries like "conan gray heather"), and ensured local audio intents do not lock package to streaming apps (YouTube/Spotify) that cannot handle local content URIs.
    - Upgraded `deviceAction`: added support for `restore`, `restore_volume`, `put_back_volume`, and `unmute` (unmutes AND ensures volume is raised to at least 50% if currently 0), percentage volume commands (`volume_50`, `set_volume: 75`), and Tagalog terms (`lakasan`, `hinaan`).
    - In `controlMedia`: routed `restore`/`unmute` requests directly to `deviceAction("restore")`.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt):
    - Added `extractSongQuery(userText, thought, message)`: robustly extracts requested song titles from quoted text in thought/message, conversational phrases ("queueing up X", "digging up X", "playing X"), or user requests ("play X", "i want X", "search my music playlist for X").
    - Added `reconcileMissingTool(userText, step)`: software safety net that intercepts cases where `tool == "NONE"` or raw text was returned but an action was requested or promised (volume restore/up/down/mute, pause/resume/next/prev, song playback, opening apps), transforming it into the appropriate executable tool.
    - In `askGemini`: wired query extraction fallback for `PLAY_MUSIC` and action reconciliation for `tool == "NONE"` and raw model text. In `runTool`, added query extraction fallback for `PLAY_MUSIC`.
- **2026-09-20 — Expanded Environment Discovery, Local MediaStore Audio & In-App Actions (v0.58.0):**
  Expanded Shiina's environment awareness and action capabilities so she can discover installed apps, inspect detailed device/audio state, and properly play local audio tracks:
  - In [AndroidManifest.xml](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/AndroidManifest.xml), added `READ_EXTERNAL_STORAGE` and `READ_MEDIA_AUDIO` permissions so Shiina can query the on-device `MediaStore` audio library.
  - In [DeviceSenses.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/observation/DeviceSenses.kt), added `media_volume` (e.g. `"7/15"`) and `available_music_players` (e.g. Huawei Music, Kyoto Player, YouTube, Spotify, Chrome, Brave) into the live device senses JSON snapshot.
  - In [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt):
    - Upgraded `playSong(query, player)` to query `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` for matching local tracks (e.g. Download/Music folder) and directly launch `ACTION_VIEW` on `content://media/external/audio/media/<id>` with `audio/*` in the device's music player. Also supports explicit player routing (`player="youtube"`, `player="spotify"`) and active `MediaSession` `playFromSearch`.
    - Implemented `searchApp(app, query)`: directly searches inside specific target applications (YouTube app/web, Spotify, Maps, Play Store, Browser).
    - Implemented `listApps(filter)`: dynamically lists installed packages on the device filtered by category (`music`, `browser`, `media`, `all`).
    - Implemented `getDeviceState()`: returns live audio/media volume, ringer mode, and active music track metadata.
  - In [DecisionModels.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/DecisionModels.kt) and [ActionExecutor.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ActionExecutor.kt), registered `"SEARCH_APP"`, `"LIST_APPS"`, and `"GET_DEVICE_STATE"` in `ALLOWED_ACTIONS` and wired them to `DeviceActionController`.
  - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.8), updated `TOOL_SPEC` to expose `SEARCH_APP`, `LIST_APPS`, and `GET_DEVICE_STATE`. Updated Rule 9 (Action & Environment Orientation) to instruct that song requests query on-device local storage first with YouTube fallback, in-app searches use `SEARCH_APP`, and app discovery uses `LIST_APPS`.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), added `player` and `filter` fields to `AgentStep`, registered the new tools in `KNOWN_TOOLS`, and wired `SEARCH_APP`, `LIST_APPS`, and `GET_DEVICE_STATE` in `runTool`.
- **2026-09-20 — PC-side chat via adb wifi (v0.57.0):**
  Chat with Shiina from the PC terminal over wifi — no phone needed. New exported `debug/AdbTalkReceiver.kt` (manifest-registered, explicit-component delivery) forwards `--es adb_text` into `DebugTalkService.ACTION_TALK`, sharing the full Talk pipeline (RemoteInput path untouched, 500-char cap kept). New host script `send.py` (wifi-serial auto-select, device-shell quoting fix so multi-word messages arrive intact). Verified end-to-end on 192.168.43.1:5555: `send.py` → `You:` logged → Gemini agent step → `Shiina (candid): Terminal check received, loud and clear.` (~2s). Usage: `python3 send.py "hi shiina"`, reply streams in the monitor window.
 Follow-up: `chat.py` interactive REPL (`python3 chat.py`, type + Enter to send, `/quit` to exit) reusing `send_message()` — verified live ("Loop's fine. Still here, still listening.").
- **2026-09-20 — Specific Song/Track Playback & PLAY_MUSIC Tool (v0.56.0):**
  Addressed user feedback where asking Shiina to play a specific song (e.g. *"play Heather"*) only resumed the currently paused/queued track instead of searching and playing the requested song.
  - In [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt), implemented `playSong(query)`:
    - Queries active `MediaSession` controllers with `playFromSearch(query, bundle)` containing `SearchManager.QUERY` and `MediaStore.EXTRA_MEDIA_FOCUS`.
    - Dispatches `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH` targeted to the active music player (or generic fallback).
    - Falls back to Spotify search deep link (`spotify:search:<query>`) or YouTube search intent if local media players do not resolve.
    - Updated `controlMedia(action, query)` to delegate to `playSong` when a query is provided or when action is `"play <song>"`.
  - In [DecisionModels.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/DecisionModels.kt) and [ActionExecutor.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ActionExecutor.kt), registered `"PLAY_MUSIC"` in `ALLOWED_ACTIONS` and wired it to `deviceActionController.playSong(param)`.
  - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.7), added `PLAY_MUSIC` to `TOOL_SPEC` (`{"tool":"PLAY_MUSIC","query":"<song name, artist, or playlist>"}`) and updated Rule 9 to explicitly instruct that specific song requests must invoke `PLAY_MUSIC` with the song title rather than generic resume.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), added `PLAY_MUSIC` to `KNOWN_TOOLS`, enhanced `parseAgentStep` to extract `query` from `song`, `track`, or `title` fields, and wired `PLAY_MUSIC` and query-aware `MEDIA_CONTROL` in `runTool`.
- **2026-09-20 — On-Device Task Execution & Media/App Action Capabilities (v0.55.0):**
  Investigated recent debug logs where the user commanded Shiina to *"Turn it off thenn"* and *"Turn it off"* while music was playing. Discovered that Shiina was bantering/bluffing without actually being able to control media or execute on-device tasks due to lack of action tools in `TOOL_SPEC`, `KNOWN_TOOLS`, and `DebugTalkService.kt`.
  - Created [DeviceActionController.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/DeviceActionController.kt):
    - `controlMedia(action)`: controls active media playback (`pause`, `play`, `stop`, `next`, `prev`, `toggle`). Interacts with active `MediaSession` controllers via `MediaSessionManager`, dispatches `KeyEvent` media keycodes via `AudioManager`, and transiently requests audio focus on pause/stop to guarantee immediate silence. Also synchronizes state with `MusicTracker`.
    - `deviceAction(action)`: adjusts device volume (`volume_up`, `volume_down`, `mute`, `unmute`) via `AudioManager`.
    - `openApp(query)`: resolves installed packages by exact package name or app label (e.g. Spotify, YouTube, Chrome, Camera, Settings) and launches them via `Intent.FLAG_ACTIVITY_NEW_TASK`.
  - In [DecisionModels.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/DecisionModels.kt) and [ActionExecutor.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/action/ActionExecutor.kt), registered `"MEDIA_CONTROL"`, `"DEVICE_ACTION"`, and `"OPEN_APP"` in `ALLOWED_ACTIONS` and wired them to `DeviceActionController`.
  - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.6), updated `TOOL_SPEC` to expose `MEDIA_CONTROL`, `DEVICE_ACTION`, `OPEN_APP`, and `LOG_GOAL`. Added Rule 9 (Action Orientation): instructs Shiina to actually execute the tool when asked, commanded, or dared to perform an action (e.g., turning off music, pausing, opening an app, setting reminders) rather than merely bluffing in text.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), added `action` and `app` fields to `AgentStep`, added the new tools to `KNOWN_TOOLS` and `runTool`, and updated the repetition detection signature.
  - In [DebugWebServer.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugWebServer.kt), fixed `/api/events` JSON serialization using `JSONObject.quote(...)` and fixed HTTP `Content-Length` to use UTF-8 byte count instead of character length, preventing JSON decoding errors and response truncation.
  - In [AppContainer.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/di/AppContainer.kt), wired `DeviceActionController` into `actionExecutor` and exposed it on the application container.
- **2026-09-20 — Wifi debugging support for monitor.py (host tool, no app bump):**
  Enabled `adb tcpip 5555` + `adb connect 192.168.43.1:5555` on the Huawei JNY-LX1 (Android 10, phone is hotspot host at 192.168.43.1); both USB + wifi transports live, logcat/pidof verified over wifi. `monitor.py` now prefers the wifi transport by default, honors `-s/--serial` and `$ANDROID_SERIAL`, and adds `--setup-wifi PHONE_IP` (tcpip+connect in one shot) and `--list`. Run cable-free with `python3 monitor.py`.
- **2026-09-20 — Asynchronous Memory Consolidation & Inline LEARN Removal (v0.54.0):**
  Moved fact extraction from the real-time conversation loop to asynchronous background reflection to eliminate token waste, duplicate writes, and conversational distraction.
  - In [ShiinaPrompts.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/decision/ShiinaPrompts.kt) (v4.5), removed `LEARN` tool and inline fact extraction rule (Rule 5) from `TOOL_SPEC`. Shiina can now focus 100% on natural, warm dialogue without deliberating whether to emit inline JSON extraction payloads.
  - In [DebugTalkService.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/debug/DebugTalkService.kt), removed `LEARN` from `KNOWN_TOOLS` and `runTool`. Added debounced `scheduleBackgroundConsolidation()` which triggers 20 seconds after the last chat message when conversation pauses.
  - Created [MemoryConsolidator.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/MemoryConsolidator.kt) wired through [AppContainer.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/di/AppContainer.kt):
    - Evaluates unextracted turns against existing memory to extract durable user facts, preferences, and rules.
    - Persists extracted facts cleanly to `learned_memory.md` ([LearnedMemoryManager.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/LearnedMemoryManager.kt)) and SQLite [MemoryStore.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/MemoryStore.kt).
    - Tracks `last_extracted_turn_timestamp` to prevent re-extracting already consolidated turns.
  - In [MainActivity.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/MainActivity.kt), added background extraction trigger in `onStop()`.
  - In [NightlyReflection.kt](file:///home/janelle/Documents/GitHub/Mobile-assistant/app/src/main/java/com/shiina/mobile/memory/NightlyReflection.kt), integrated `memoryConsolidator.consolidate(force = true)` into the nightly worker pass.
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
- **2026-09-21 — P0 trigger repair live-verified (v0.89.0, code 89):**
  - Symptom: "scold me at 12" never produced anything. Live red-loop on
    AUDUT20616012479 found two killers, not one: (1) Talk agent answered
    DONE with tool=NONE while claiming "locked in" (hallucinated receipt);
    (2) TriggerReceiver swallowed all exceptions with zero logging and the
    overlay 6-shows/hour cap ate trigger SHOWs silently.
  - Fixes: TriggerScheduler.kt (328 lines) — time grammar extended
    (when-it's, bare am/pm, midnight/noon, in-N, tomorrow, every-day),
    30-day clamp, NO_TIME contract documented, receiver logs BEFORE
    startForegroundService + onFailure logs, calendar daily rollover,
    fallback notification when overlay blocked. CharacterOverlayService.kt
    (516 lines) — LOCKED shows bypass the rate cap; non-locked skip now
    really returns. ShiinaPrompts.kt TOOL_SPEC — ACCOUNTABILITY RULE:
    scold/nag/remind/wake requests MUST load planner + call SET_TRIGGER
    or SET_REMINDER and observe the receipt before DONE.
  - Verified live: "scold me at 7:24pm" -> planner loaded -> SET_TRIGGER
    receipt -> 19:24 alarm fired -> TRIGGER firing log -> Overlay LOCKED.
    Process alive, zero fatals. Side note: Huawei battery-kills the app
    (not whitelisted); reinstalls need a manual launch before send.py works.
    Build: ~/gradle/gradle-8.7 (the /tmp/gradle-8.8 path is stale).
