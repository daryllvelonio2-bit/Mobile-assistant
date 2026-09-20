# Project Work-Tree (`TREE.md`)

Current file structure and layout of the Mobile-assistant project.

```text
Mobile-assistant/
├── .env                        # Local environment variables & API keys (ignored by git)
├── agent.md                    # AI agent operating guidelines and rules
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
            ├── res/xml/            # Accessibility service configuration (gestures & window content retrieval)
            └── java/com/shiina/mobile/
                ├── CompanionApp.kt # Application container initialization
                ├── MainActivity.kt # Main entry point (Permissions & Settings UI)
                ├── data/
                │   ├── db/         # Room Database, DAOs, Entities (Usage, Sleep, Goal, Baseline,
                │   │               #   MemoryEpisode, MemoryFact, ChatTurn, MemorySummary) + migrations v1..v6
                │   ├── security/   # EncryptedSharedPreferences Keystore key management
                │   └── settings/   # DataStore settings repository
                ├── action/         # Executor, DeviceActionController, ShiinaAccessibilityService (tap/swipe/type) + AlarmReceiver
                ├── character/      # Overlay character (mode, controller, overlay service)
                ├── decision/       # Decision models, provider interface, registry (episode log + cooldown),
                │   │               #   Gemini round-robin provider, MemoryContext (ranked recall + digests),
                │   │               #   ShiinaPrompts (global rules + mood prompts), ToolCatalog (on-demand toolsets)
                ├── debug/          # AppDebugServer, JSON API dashboard + DebugTalkService notification panel
                │                   #   + AdbTalkReceiver (PC chat over adb wifi via send.py)
                ├── memory/         # MemoryStore (facts CRUD/contradict/forget), MemoryCompactor
                ├── observation/    # Foreground observation service + ScreenMetrics (resolution learner) +
                │                   #   MusicTracker (MediaSession, broadcasts) + MusicNotificationListener + MemoryOutcomes (episode signals)
                ├── theme/          # Material 3 Theme, Colors, and Typography
                ├── ui/
                │   ├── character/  # Character ViewModel ("remembers N days") and M3 control panel
                │   ├── permissions/# Permission check helpers and settings-directed grant screen
                │   └── settings/   # Settings ViewModel/UI + MemoryPanel (status, facts viewer, digests, forget all)
                └── work/           # BaselineWorker (runs NightlyReflection, charger-idle periodic)
```
*(Note: Update this tree immediately whenever files, modules, or directories are added, removed, or restructured.)*
