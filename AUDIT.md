# AUDIT.md — Shiina Mobile codebase audit

Auditor: `auditor` profile · Date: 2026-10-01 · Branch: `agents/autodev` · Commit audited: `9504fb6`
Scope: `app/src/main/java/com/shiina/mobile/` (19,929 LOC across 70+ Kotlin files),
`AndroidManifest.xml`, `app/build.gradle.kts`, plus repo-level hygiene (`agent.md` rules).

Build gate verified during this audit: `./gradlew :app:compileDebugKotlin --offline` → **EXIT=0**
(compiles clean; findings below are runtime/security/architecture issues a compiler will not catch).

Rule references are to `agent.md` (rule 5 = 1500-line limit, rule 10 = Material 3 tokens only,
rule 12 = stability/perf/no leaks, rule 15 = TREE.md upkeep, rule 17 = on-demand toolset).

---

## Severity summary

| # | Severity | Area | Finding | File:line |
|---|----------|------|---------|-----------|
| 1 | HIGH | Security | ADB debug receiver exported with no permission — any app can inject chat + overwrite API keys | `AdbTalkReceiver.kt:21`, `AndroidManifest.xml:96-97` |
| 2 | HIGH | Security/Cost | Proactive-loop receiver exported — any app can force LLM heartbeat ticks | `AndroidManifest.xml:118-119` |
| 3 | MEDIUM | Correctness | MediaProjection used without a registered `MediaProjection.Callback` (breaks capture on Android 14+, targetSdk 35) | `ScreenshotTaker.kt:41-45,88` |
| 4 | MEDIUM | Correctness | Procedural-memory usage counters mutated but never persisted — reset every restart | `ProceduralMemoryStore.kt:479-480`, `ToolDispatcher.kt:577-578` |
| 5 | MEDIUM | Privacy | Full prompts / full model+API responses logged to logcat in all builds | `AgentEngine.kt:360-367,444`, `GeminiProvider.kt:71-75,94` |
| 6 | MEDIUM | Hygiene/Secrets | `.env` is tracked in git; `.gitignore` does not ignore it | `.env`, `.gitignore` |
| 7 | MEDIUM | Rule 10 | Hardcoded `Color` values in Compose UI and notifications | `ShiinaVisuals.kt:115-146,350-357`, `DebugTalkService.kt:331`, `MainActivity.kt:414-415` |
| 8 | LOW | Dead code | `KnowledgeNote` entity + DAO never registered in `AppDatabase`, never referenced | `KnowledgeNote.kt:1-48` |
| 9 | LOW | Dead code | `structuredSchema` param is a no-op; `rawQuery(tone=)` ignored | `AgentEngine.kt:73,372-380` |
| 10 | LOW | Perf/Lifecycle | `collectAsState()` used instead of `collectAsStateWithLifecycle()` (7 call sites) | see §10 |
| 11 | LOW | Security | `MusicTracker` registers its receiver `RECEIVER_EXPORTED` for app-internal broadcasts | `MusicTracker.kt:91` |
| 12 | LOW | Stability | Unparented coroutine scopes never cancelled | `DeviceActionController.kt:41`, `SettingsRepository.kt:22` |
| 13 | LOW | Perf | `Thread.sleep(100)` busy-wait up to 2s while holding capture mutex | `ScreenshotTaker.kt:95-100` |
| 14 | LOW | Robustness | `!!` on nullable SharedPreferences read | `UserActivityTracker.kt:29` |
| 15 | LOW | Observability | Provider loop swallows all exceptions silently | `ProviderRegistry.kt:50` |
| 16 | LOW | Rule 5 planning | Files at 847–1269 lines, approaching the 1500 limit | `DeviceActionController.kt`, `ShiinaAccessibilityService.kt`, `OnboardingScreen.kt` |
| 17 | LOW | Cost | Agent loop has no per-turn cost guard (up to 25 sequential API calls) | `AgentEngine.kt:30,125` |

---

## HIGH

### 1. `AdbTalkReceiver` is exported and unauthenticated — any installed app can talk to Shiina and rewrite API keys
**Evidence:** `app/src/main/AndroidManifest.xml:96-97` declares the receiver `android:exported="true"` with
actions `com.shiina.mobile.debug.SEND_MESSAGE` and `com.shiina.mobile.debug.SET_KEY`.
`AdbTalkReceiver.onReceive` (`app/src/main/java/com/shiina/mobile/debug/AdbTalkReceiver.kt:22-44`) performs **no
caller check**: `ACTION_SET_KEY` writes an arbitrary key into the encrypted Keystore
(`AdbTalkReceiver.kt:24-31`), and `ACTION_SEND` starts `DebugTalkService` with attacker-controlled text
(`AdbTalkReceiver.kt:33-42`), which calls the LLM and TTS.

**Impact:** any third-party app installed on the device can (a) exfiltrate/redirect API usage by pointing the
provider at a key it controls, or (b) drive the paid Gemini API and the voice engine with arbitrary prompts —
no `adb` and no permission required. Because the receiver is exported, the adb workflow in `send.py` does not
even require it to stay exported: `am broadcast -n <explicit component>` from the shell UID is the legitimate path.

**Required fix:**
- Add an explicit caller check at the top of `onReceive` and return early otherwise:
  ```kotlin
  val uid = android.os.Binder.getCallingUid()
  val self = android.os.Process.myUid()
  if (uid != self && uid != android.os.Process.SHELL_UID) return
  ```
  (`SHELL_UID` covers `adb shell am broadcast`; `self` covers in-app sends.)
- Keep `android:exported="true"` only if the shell check is in place; otherwise move the receiver behind a
  `debug`-only source set / `BuildConfig.DEBUG` gate.
- Never allow `SET_KEY` outside a debug build.

**Verify on device/waydroid:**
1. `adb shell am broadcast -n com.shiina.mobile/.debug.AdbTalkReceiver -a com.shiina.mobile.debug.SET_KEY --es provider gemini --es key TESTKEY` → succeeds (shell allowed).
2. From a second normal (non-shell) app/UID, broadcast the same action → rejected, and no `SECURITY` log line appears in the debug server.

---

### 2. `ProactiveLoop` receiver is exported — any app can force paid proactive heartbeat ticks
**Evidence:** `app/src/main/AndroidManifest.xml:118-119` declares `.action.ProactiveLoop`
`android:exported="true"` with `com.shiina.mobile.action.PROACTIVE_TICK` and `BOOT_COMPLETED`.
`ProactiveLoop.onReceive` (`app/src/main/java/com/shiina/mobile/action/ProactiveLoop.kt:48-70`) treats *any*
unknown action as a tick (`else -> runTick(context)`, line 61), and `runTick` →
`runDecisionPipeline` → `agent.runAgentLoopInternal` (line 381-388) issues a Gemini request.

**Impact:** a third-party app can loop `am broadcast -a com.shiina.mobile.action.PROACTIVE_TICK -n
com.shiina.mobile/.action.ProactiveLoop`, forcing repeated LLM calls (API cost, battery, thermal load) and
overlay interruptions. `BOOT_COMPLETED` never requires `exported="true"` — the alarm `PendingIntent` in
`scheduleNextTick` is explicit and keeps working with `exported="false"`.

**Required fix:** set `android:exported="false"` on the `.action.ProactiveLoop` receiver. If external triggering
is ever wanted, gate with a signature-level permission.

**Verify:** `adb shell am broadcast -a com.shiina.mobile.action.PROACTIVE_TICK -n com.shiina.mobile/.action.ProactiveLoop` → `SecurityException`/`not exported`; reboot → boot restore still logs `BOOT_COMPLETED received; restoring proactive heartbeat loop`.

---

## MEDIUM

### 3. MediaProjection has no callback registered — screen capture breaks on Android 14+
**Evidence:** `ScreenshotTaker.setProjection` (`app/src/main/java/com/shiina/mobile/observation/ScreenshotTaker.kt:41-45`)
stores the projection and `release()` at line 47-50 calls `projection?.stop()`, but **no
`MediaProjection.registerCallback(...)` is ever called**; `grabFrame` then calls
`mp.createVirtualDisplay(...)` (line 88). With `targetSdk = 35` (`app/build.gradle.kts:13`), Android 14+
requires a callback to be registered before `createVirtualDisplay`; without one the system throws/refuses
and screenshots silently degrade to the accessibility fallback (`ScreenshotTaker.kt:59-72`) or nothing.

**Required fix in `setProjection`:**
```kotlin
mp.registerCallback(object : android.media.projection.MediaProjection.Callback() {
    override fun onStop() { release() }
}, android.os.Handler(android.os.Looper.getMainLooper()))
```
and unregister/stop cleanly in `release()`.

**Verify:** on API 34/35 device, enable the screenshot toggle, grant projection, then call `TAKE_SCREENSHOT`;
confirm a `cap_*.jpg` lands in `filesDir/captures` and `CAPTURE Saved …` is logged (not the
`Capture skipped … no consent or accessibility screenshot available` fallback).

---

### 4. Procedural-memory usage counters are mutated in memory but never written to disk
**Evidence:** `ProceduralMemoryStore.getProcedureDetails` (`app/.../memory/ProceduralMemoryStore.kt:477-493`)
does `entry.usageCount++` / `entry.lastUsed = …` at lines 479-480 and returns — **no `saveToDiskLocked()`**.
`ToolDispatcher.executeProcedureSteps` (`app/.../decision/ToolDispatcher.kt:577-578`) does the same after a
successful replay, again without persisting. Every other mutator (`addProcedure:351`, `removeStep:371`,
`updateStep:399`, `deleteProcedure:467`) correctly calls `saveToDiskLocked()`.

**Impact:** `usage_count` / `last_used` are silently reset to the last persisted values on every process
restart, so any future usage-based ranking/optimization of procedures (`getProceduresSummaryForPrompt`,
`findBestMatch`) operates on wrong data; the "macro self-optimization" feature cannot actually converge.

**Required fix:** make the counter bump persist. Either convert `getProcedureDetails` to a `suspend fun` that
takes `mutex.withLock { … ; saveToDiskLocked() }` (and update callers), or have `executeProcedureSteps` call a
new `suspend fun recordUsage(entry)` that persists. Do not mutate shared entries from a `@Synchronized`
non-suspend reader while writers hold the coroutine `mutex` — that is also a lock-discipline inconsistency.

**Verify:** `GET_PROCEDURE` twice, force-stop the app, relaunch and `GET_PROCEDURE` again → `usageCount`
reflects the accumulated count rather than resetting.

---

### 5. Private content (prompts, model replies, raw API bodies) is logged to logcat in every build
**Evidence:**
- `AgentEngine.postText` logs the **entire prompt** (`AgentEngine.kt:364-367`), the **entire raw model JSON**
  (`AgentEngine.kt:444`), and API response bodies up to 400 chars (`AgentEngine.kt:415`).
- `GeminiProvider` logs the full prompt including memory facts and senses (`GeminiProvider.kt:71-75`) and the
  full raw response (`GeminiProvider.kt:94`).
- `AppDebugServer.log` mirrors every message to `Log.d` (`debug/DebugWebServer.kt:40-49`).
Because `AppDebugServer.start()` and `DebugTalkService` run unconditionally (`CompanionApp.kt:25,30`) and the
release build has `isMinifyEnabled = false`, this chat/memory content is present in logcat for any build,
accessible to any process with log access and to anything that captures bug reports.

**Required fix:** gate payload logging on `BuildConfig.DEBUG` (already `buildConfig = true`). Keep category/
severity/size lines; drop the raw `prompt`, `respBody`, and `GEMINI_RAW` bodies in release. Consider a
redaction pass for `TOKEN_AUDIT`/`GEMINI_TALK_REQUEST`.

**Verify:** assemble a release APK, send a chat turn, `adb logcat -d | grep GEMINI` → no prompt or response text present.

---

### 6. `.env` is committed and not ignored
**Evidence:** `git ls-files` returns `.env`; `git log -- .env` shows it added in commit `8ce3d40`.
`content/.gitignore` has **no `.env` rule** (verified: `grep -n env .gitignore` → no match). The file's own
header says "Never commit this file. Template only — real keys stay on device Keystore", and the values are
currently placeholders (`[REDACTED]`), so no live key is exposed **yet** — but the moment a real key is synced
locally it will be committed.

**Required fix:** add `.env` to `.gitignore`, `git rm --cached .env`, and commit `.env.example` containing the
redacted template instead. (Also note `monitor.py` is gitignored but `send.py:16` imports it — document the
dependency or un-ignore `monitor.py`.)

**Verify:** `git ls-files | grep -qx .env && echo TRACKED || echo untracked` → `untracked`;
`git check-ignore -v .env` → matches the new rule.

---

### 7. Hardcoded colors violate `agent.md` rule 10 (Material 3 tokens only)
**Evidence:**
- `ui/components/ShiinaVisuals.kt:115` (`Color.White.copy(...)`), `:126`, `:146` (ink),
  `:350-357` — a 7-entry mood→gradient map built from literal `Color(0xFF…)` ARGB values.
- `debug/DebugTalkService.kt:331` — `.setColor(0xFF6366F1.toInt())`.
- `MainActivity.kt:414-415` — ambient/spot shadow `Color.Black.copy(alpha = …)`.

Rule 10 requires all Compose UI to consume `MaterialTheme.colorScheme` / typography / shapes and never use
hardcoded static colors. The mood palette is semantic data and should live in `theme/Color.kt` (which already
holds the design tokens) and be exposed through the theme extension, not inline in the component.

**Required fix:** move the mood palette to `theme/Color.kt` as named tokens; expose via
`CompanionTheme`/a `MaterialTheme` extension; replace `DebugTalkService.kt:331` with
`MaterialTheme.colorScheme.primary.toArgb()` sourced from a shared constant; keep alpha modulations but derive
the base from theme tokens.

**Verify:** `grep -rn "Color(0x\|Color\.White\|Color\.Black" app/src/main/java/com/shiina/mobile/ui` →
only `theme/Color.kt` and intentional overlay scrim tokens remain.

---

## LOW

### 8. Dead code — `KnowledgeNote` is never wired up
`data/db/KnowledgeNote.kt` defines an `@Entity` + `@Dao` (48 lines) that is **not** listed in
`AppDatabase.entities` (`data/db/AppDatabase.kt:9-22`) and is not referenced anywhere (`grep -rn KnowledgeNote`
returns only the file itself). It is also currently **untracked** in git. This is the unimplemented BUILD_PLAN
Phase 3 (knowledge capture). Either wire it into `AppDatabase` with a migration (version 12) and a consumer, or
remove the file until Phase 3 begins.

### 9. Dead code — no-op `structuredSchema` and ignored `rawQuery` parameter
`AgentEngine.postText(prompt, attachShot, structuredSchema)` declares `structuredSchema` and then has an empty
`if (structuredSchema) { /* commented-out response_schema */ }` block (`AgentEngine.kt:372-380`); the parameter
has no effect. `rawQuery(prompt, tone, attachShot)` never uses `tone` (`AgentEngine.kt:73-75`). Remove the
parameters or implement them; the commented-out schema should be deleted so it does not read as working code.

### 10. `collectAsState()` instead of `collectAsStateWithLifecycle()`
Call sites: `ui/chat/ChatScreen.kt:95-98`, `ui/settings/SettingsScreen.kt:86-95`,
`ui/settings/MemoryPanel.kt:83-86`, `ui/character/CharacterPanel.kt:75-78`, `MainActivity.kt:162`,
`ui/onboarding/OnboardingScreen.kt:356,681-682`, and `character/CharacterOverlayService.kt:275-276`.
`lifecycle-runtime-compose` is already a dependency (`app/build.gradle.kts:69`), so the migration is
mechanical. Plain `collectAsState` keeps collecting Room/DataStore flows while the host is stopped
(rule 12: no wasted work while off-screen).

### 11. `MusicTracker` receiver registered as exported
`observation/MusicTracker.kt:91` uses `Context.RECEIVER_EXPORTED` for app-internal music broadcasts.
These are not system broadcasts the app must receive from others; use `Context.RECEIVER_NOT_EXPORTED`
(hardening; also silences the Android 13+ exported flag lint warning).

### 12. Unparented coroutine scopes
`action/DeviceActionController.kt:41` — `CoroutineScope(Dispatchers.Main)` (no `SupervisorJob`, never
cancelled) and `data/settings/SettingsRepository.kt:22` — `CoroutineScope(Dispatchers.IO).launch { store.data.collect { … } }`
inside `init` (fire-and-forget collector that never completes and is never cancelled). Use
`SupervisorJob()` + an explicit `close()`/`cancel()` lifecycle, or a lifecycle-scoped collector.

### 13. Blocking busy-wait in screen capture
`observation/ScreenshotTaker.kt:95-100` loops `repeat(20) { … Thread.sleep(100) }` — up to 2 s of blocked IO
time **while holding `lock`** (`:53`), stalling concurrent captures. Use an `ImageReader.OnImageAvailableListener`
or a bounded `delay()`-based wait inside the coroutine.

### 14. Non-null assertion on a nullable prefs value
`data/activity/UserActivityTracker.kt:29` — `prefs.getStringSet(KEY_HOURS, emptySet())!!`. `getStringSet`
returns `StringSet?`; the `!!` will NPE if a malformed/legacy value is read. Use
`.orEmpty().toMutableSet()`.

### 15. Silent provider-failure swallow
`decision/ProviderRegistry.kt:50` — `catch (_: Exception) { continue }` hides every provider failure with no
log, so a permanently broken provider looks identical to a healthy one until the fallback path fires. Log the
provider name + exception before continuing.

### 16. Large files approaching the rule-5 limit
- `action/DeviceActionController.kt` — **1269 lines**
- `action/ShiinaAccessibilityService.kt` — **1054 lines**
- `ui/onboarding/OnboardingScreen.kt` — **847 lines**
Rule 5 forbids >1500 and asks that files approaching it be split. Suggested seams: DeviceActionController →
`MediaController` / `AppLauncher` / `SystemToggles` / `ScreenReader`; ShiinaAccessibilityService →
`GestureRunner` / `ScreenReader` / `ChatConsult`; OnboardingScreen → one file per step composable.

### 17. No per-turn cost guard in the agent loop
`AgentEngine.kt:30` sets `MAX_AGENT_STEPS = 25`; `AgentEngine.kt:125-271` will issue up to 25 sequential
Gemini calls if the model never returns `DONE`. Add a cumulative token/time budget (or lower the cap) so a
looping model cannot burn quota silently.

---

## Developer tasks spawned from this audit

Every actionable finding has one developer task (idempotency key `audit-<slug>`),
assigned to the `developer` profile on tenant `mobile-assistant`:

| Finding | Task id | Title |
|---------|---------|-------|
| #1 | `t_77639e6c` | Harden exported AdbTalkReceiver (caller UID check, debug-only key sync) |
| #2 | `t_731e89ab` | Unexport ProactiveLoop receiver (block forced LLM heartbeat ticks) |
| #3 | `t_ad34b7f4` | Register MediaProjection.Callback before createVirtualDisplay |
| #4 | `t_94c90291` | Persist procedural-memory usage counters |
| #5 | `t_9833a73f` | Gate verbose prompt/response logging behind BuildConfig.DEBUG |
| #6 | `t_3690dae3` | Untrack .env, add to .gitignore, add .env.example |
| #7 | `t_ac953910` | Move hardcoded colors into Material 3 theme tokens |
| #10 | `t_bcddc21d` | Use collectAsStateWithLifecycle |
| #8, #9 | `t_f6321785` | Remove/wire dead code (KnowledgeNote, no-op structuredSchema, rawQuery tone) |
| #11–#15 | `t_b1f98933` | Low-severity hardening (receiver flags, scopes, busy-wait, NPE, silent swallow) |
| #16, #17 | `t_29262fd2` | Split large files (rule 5) and add agent-loop cost guard |

Re-audit trigger: verify each task against the "Verify" section of its body when the developer
completes it, then mark the corresponding AUDIT.md row resolved.

## Non-findings (verified good)

- No file exceeds 1500 lines (rule 5 currently satisfied).
- No hardcoded API keys/secrets found in source (`grep -rnE "AIza…|sk-…"` → none); keys are in
  `EncryptedSharedPreferences` with a documented fallback (`data/security/KeyStoreKeys.kt`).
- Room DB is on version 11 with explicit migrations 1→11; no destructive fallback.
- Debug HTTP server binds loopback only (`debug/DebugWebServer.kt:67`), prior audit item B10 respected.
- Services cancel their scopes and unregister receivers in `onDestroy`
  (`CharacterOverlayService.kt:525-528`, `ShiinaAccessibilityService.kt:564-566`, `MusicTracker.stop()`).
- No `runBlocking`, `GlobalScope`, or `allowMainThreadQueries` anywhere.
