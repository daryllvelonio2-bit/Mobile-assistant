# AI Agent Guidelines (`agent.md`)

**MANDATORY:** Read this file first before making any changes or generating code in this repository.

## Project Context
|- **Project:** Personal Android AI Companion (`Mobile-assistant`)
|- **Tech Stack:** Kotlin, Jetpack Compose, Room, DataStore, Foreground Service, WorkManager.
|- **Target:** Personal single-device Android application (v1 backend + v2 character overlay).

## Core Rules & Philosophy

1. **Zero Bloatware:** Avoid unnecessary UI elements, overly complex components, verbose explanations, or fluff text. Keep interfaces clean, minimal, and direct.
2. **Simplicity First:** Prioritize straightforward implementations and intuitive workflows over over-engineered solutions.
3. **Scalable Architecture:** Design systems with clean separation of concerns (Observation, Memory, Decision, Action, UI/Character layers), robust folder structures, and easy extensibility.
4. **Modular Code:** Break down features into small, reusable classes, Composables, ViewModels, and services.
5. **File Size Limit:** **No code file may exceed 1500 lines.** If a file approaches or exceeds 1500 lines, refactor and split it into smaller modules.
6. **Strict 1 Feature = 1 File Modularity:** Different feature scopes must reside in separate files to guarantee fault isolation.
7. **Strict User Instruction Adherence & Zero Hallucination:** Follow the user's explicit step-by-step instructions precisely. Never invent unrequested functionality or jump ahead without user guidance.
8. **Progress Tracking & Creation (`PROGRESS.md`):** Always ensure `PROGRESS.md` exists in the repository root. After every phase completion, significant update, refactor, or feature addition, immediately update `PROGRESS.md` with a summary of changes, current status, and date.
9. **Debug Build & ADB Deployment:** Always build and verify the application in **Debug mode** (`./gradlew assembleDebug`, installing via `adb install` or Android Studio) to enable live logcat inspection, debugging, and incremental compilation.
10. **Material 3 Theming:** All Compose UI components must strictly consume Material 3 theme tokens (`MaterialTheme.colorScheme`, typography, shapes). Never use hardcoded static colors that violate the active theme selection.
11. **Phase-by-Phase Execution, Never Rush:** Execute multi-phase work (following `BUILD_PLAN.md`) strictly one phase at a time. Think thoroughly before every phase, complete it fully, verify its exit gate (compilation, typecheck, line limits, behavior), report back, and wait for the user's go before starting the next phase. Never jump ahead, batch phases, or skip verification.
12. **Stability & Performance First:** All code implemented or refactored must strictly prioritize application stability and peak execution speed on Android:
    - **Rock-Solid Stability:** Guarantee zero unhandled coroutine exceptions, proper supervisor scopes, leak-free BroadcastReceivers/services/listeners with mandatory cleanup/unregistration, and safe thread-safe database/storage operations (Room/DataStore).
    - **Maximized Speed & Smoothness:** Prevent main-thread blockage; run all heavy I/O, database queries, and network calls asynchronously off the main thread using Kotlin Coroutines (`Dispatchers.IO`); avoid heavy synchronous computations in Composable render paths; virtualize large lazy lists (`LazyColumn`).
13. **Code Simplicity:** Always code less with the same functionality, never over-complicate. Strive for minimal, clear implementations that solve the problem without unnecessary abstractions, boilerplate, or architectural overhead.
14. **Specialized Subagent Delegation:**
    - **Debugging Work:** Always delegate debugging tasks to the `debugger` subagent profile.
    - **UI/UX Work:** Always delegate UI/UX design and review tasks to the `ui/ux expert` subagent profile.
    - **Context Provision:** Always feed subagents the full info, precise task description, and all necessary context upfront so they do not need to re-read or explore the project repository themselves.
    - **Main Agent Responsibilities:** The main agent (Shiina) handles repository reading, code auditing, end-to-end verification, and general feature implementation not handled by the specialized expert subagents.
15. **Project Work-Tree Maintenance (`TREE.md`):**
    - Always maintain and update `TREE.md` whenever new files, directories, modules, or components are added, removed, or restructured in the repository.
    - Ensure `TREE.md` accurately reflects the project structure so main and subagents have immediate visibility into code layout without redundant file-tree scanning.
16. **Version Increment:** Always increment the application version by `0.1.0` (and version code by `+1`) on every update or change.
17. **On-Demand Hierarchical Toolset Architecture:**
    - **Never Dump All Tool Calls or Domain Instructions:** Never include exhaustive tool definitions, schema parameters, or domain-specific actuation rules (e.g. music playback instructions) in the base system prompt.
    - **Hierarchical Discovery:** The base prompt must only provide high-level toolset categories (`media`, `apps`, `device`, `web`, `planner`) and the `GET_TOOLSET` meta-tool.
    - **On-Demand Loading:** The agent must explicitly invoke `GET_TOOLSET` for the relevant domain to receive tool specifications, exact parameter schemas, usage guidance, and actuation rules for that domain before acting.
    - **Closed-Loop Execution:** Tasks must follow a strict closed-loop flow: (1) Discover domain toolset via `GET_TOOLSET`, (2) Actuate within the domain, (3) Observe execution receipts, and (4) Conclude with grounded conversational response. Never guess parameters or claim outcomes without observing receipts.
18. **Visual Verification & Autonomous Screen Interaction:**
    - **Never Stop at App Launch:** When the user asks to perform an in-app task (e.g. watch anime, search video, play a game), launching the app via `OPEN_APP` is only step 1. Never conclude or claim completion upon merely opening an app.
    - **Mandatory Visual Verification:** After `OPEN_APP`, the agent must invoke `TAKE_SCREENSHOT` with status `'CONTINUE'` to verify that the app opened and inspect the actual screen layout, buttons, search bars, and categories.
    - **Automated Screen Execution:** After observing the screenshot, the agent must use `TAP_SCREEN` (with `x, y` coordinates or element `text`), `INPUT_TEXT`, or `SWIPE_SCREEN` with status `'CONTINUE'` to execute the requested task (selecting genre, clicking search, tapping play) until completion is verified.
