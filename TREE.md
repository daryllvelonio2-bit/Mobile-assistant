# Project Work-Tree (`TREE.md`)

Current file structure and layout of the Mobile-assistant project.

```text
Mobile-assistant/
├── .env                        # Local environment variables & API keys (ignored by git)
├── agent.md                    # AI agent operating guidelines and rules
├── BACKLOG.md                  # Usability, interactivity, and architecture backlog
├── BUILD_PLAN.md               # Phase-by-phase implementation plan
├── PROGRESS.md                 # Project progress tracking and update log
├── STACK_DECISION.md           # Architecture, permissions, and tech stack decisions
├── build.gradle.kts            # Root build script
├── settings.gradle.kts         # Gradle settings & repositories
├── gradle.properties           # Gradle performance & AndroidX flags
├── gradle/
│   └── libs.versions.toml      # Version catalog (Compose, Room, DataStore, WorkManager, OkHttp)
└── app/
    ├── build.gradle.kts        # App module build configuration (compileSdk 35, minSdk 26)
    └── src/
        └── main/
            ├── AndroidManifest.xml # Permissions (UsageStats, SYSTEM_ALERT_WINDOW, Health Connect, Foreground Service)
            ├── res/xml/            # Accessibility service configuration (gestures, window content & screenshots)
            └── java/com/shiina/mobile/
                ├── CompanionApp.kt # Application container initialization
                ├── MainActivity.kt # Main entry point (Permissions & Settings UI)
                ├── data/
                │   ├── db/         # Room Database, DAOs, Entities (Usage, Sleep, Goal, Baseline,
                │   │               #   MemoryEpisode, MemoryFact, ChatTurn, MemorySummary, TriggerEntry)
                │   ├── security/   # EncryptedSharedPreferences Keystore key management
                │   └── settings/   # DataStore settings repository
                ├── action/         # Executor, DeviceActionController, ShiinaAccessibilityService (tap/swipe/type/clear/scroll),
                │                   #   TriggerScheduler, ProactiveLoop, AlarmReceiver, ReminderReceiver, TriggerReceiver
                ├── character/      # Overlay character (mode, controller, overlay service, AnimeCharacter3D, MoodEngine, ShiinaVoiceSpeaker, VoiceCloneEngine)
                ├── decision/       # AgentEngine (closed-loop Think-Act-Observe-Verify runner), ToolDispatcher (safe tool execution & schemas),
                │                   #   PromptAssembler (system rules, live context, senses, state injections), AgentStep (model & parsing),
                │                   #   ShiinaSkills (calm execution, real-world situations, dignity, app navigation, zero hallucination playbooks),
                │                   #   Decision models, Provider registry, Gemini round-robin provider, MemoryContext,
                │                   #   ShiinaPrompts (global rules & mood prompts), ToolCatalog (on-demand toolsets)
                ├── debug/          # AppDebugServer, JSON API dashboard, DebugTalkService (foreground talk & debug notification panel),
                │                   #   ChatBus (process-wide busy, progress, stop state), ChatSend (in-app talk sender),
                │                   #   AdbTalkReceiver (PC chat over adb wifi & key sync via send.py)
                ├── memory/         # MemoryStore (facts CRUD/contradict/forget), LearnedMemoryManager, DynamicLearningEngine (real-world scenarios & categorized knowledge),
                │                   #   ProceduralMemoryStore (app navigation workflows, macro memory, step pruning & optimization),
                │                   #   MemoryCompactor, MemoryConsolidator, NightlyReflection
                ├── observation/    # Foreground observation service, ScreenMetrics (resolution learner), ScreenshotTaker,
                │                   #   MusicTracker (MediaSession, broadcasts), MusicNotificationListener, MemoryOutcomes, DeviceSenses
                ├── theme/          # Material 3 Theme, Colors, and Typography
                ├── ui/
                │   ├── character/  # Character ViewModel and M3 control panel
                │   ├── permissions/# Permission check helpers and settings-directed grant screen
                │   ├── chat/       # In-app chat screen (Chat tab), live Room flow, message list & input row
                │   ├── onboarding/ # First-run guided setup wizard (Meet Shiina, brain keys, permissions, preferences)
                │   └── settings/   # Settings ViewModel/UI, MemoryPanel (status, facts viewer, digests, forget all)
                └── work/           # BaselineWorker (runs NightlyReflection, charger-idle periodic)
    └── src/test/java/com/shiina/mobile/ # Pure unit tests (SystemContextAndSkillsTest: >10k pure system context, playbooks, parsing, scenario detection)
```
*(Note: Update this tree immediately whenever files, modules, or directories are added, removed, or restructured.)*
