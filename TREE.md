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
            └── java/com/shiina/mobile/
                ├── CompanionApp.kt # Application container initialization
                ├── MainActivity.kt # Main entry point (Permissions & Settings UI)
                ├── data/
                │   ├── db/         # Room Database, DAOs, and Entities (Usage, Sleep, Goal, Baseline)
                │   ├── security/   # EncryptedSharedPreferences Keystore key management
                │   └── settings/   # DataStore settings repository
                ├── action/         # Local action executor (alarms, screenshot toggle, goal logs)
                ├── character/      # Overlay character (mode, controller, overlay service)
                ├── decision/       # Decision models, provider interface, registry, and Gemini round-robin provider
                ├── observation/    # Foreground observation service stub
                ├── theme/          # Material 3 Theme, Colors, and Typography
                ├── ui/
                │   ├── character/  # Character ViewModel and M3 control panel
                │   ├── permissions/# Permission check helpers and settings-directed grant screen
                │   └── settings/   # Settings ViewModel and Compose UI
                └── work/           # WorkManager baseline worker stub
```
*(Note: Update this tree immediately whenever files, modules, or directories are added, removed, or restructured.)*
