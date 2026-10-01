# AUDIT.md — Shiina Mobile codebase audit

Auditor: `auditor` profile · Date: 2026-10-01 · Branch: `agents/autodev` · Commit audited: `9504fb6`
Scope: `app/src/main/java/com/shiina/mobile/` (19,929 LOC across 70+ Kotlin files),
`AndroidManifest.xml`, `app/build.gradle.kts`, plus repo-level hygiene (`agent.md` rules).

Build gate verified during this audit: `./gradlew :app:compileDebugKotlin --offline` → **EXIT=0**
(compiles clean; findings below are runtime/security/architecture issues a compiler will not catch).

Rule references are to `agent.md` (rule 5 = 1500-line limit, rule 10 = Material 3 tokens only,
rule 12 = stability/perf/no leaks, rule 15 = TREE.md upkeep, rule 17 = on-demand toolset).

---

## Verification pass — 2026-10-01 (task `t_9ada0fa2`)

Re-verified the developer's in-flight work against the findings below. The working tree had
diverged from the audited baseline `0e164be` with **uncommitted** WIP: 16 modified + 9 untracked
files, consisting mainly of *feature* work (Phase 1 briefings, R2 voice input, R4 notification
triage, R5 QS tile) rather than audit-finding fixes.

Build gates re-run this pass:
- `./gradlew :app:compileDebugKotlin --offline` → **BUILD SUCCESSFUL** (8m 30s).
- `./gradlew :app:assembleDebug --offline` → **BUILD SUCCESSFUL**; APK at
  `app/build/outputs/apk/debug/app-debug.apk` (65 MB).

Device verification: **BLOCKED.** Target Waydroid `192.168.240.112:5555` is Android 13 on
`x86_64`, but the APK ships only `lib/arm64-v8a` (`app/build.gradle.kts:18-20`); `adb install`
returns `INSTALL_FAILED_NO_MATCHING_ABIS`. Filed as a finding (`t_87cef44f`). Until resolved no
"Verify on device" step in this document can be executed.

Finding status:
- **#1 — FIXED (device-verified 2026-10-01, task `t_77639e6c`)** — see finding #1 for evidence.
- **#2 — FIXED (device-verified 2026-10-01, task `t_731e89ab`)** — see finding #2 for evidence.
- **#3 — FIXED (device-verified 2026-10-01, task `t_ad34b7f4`)** — see finding #3 for evidence.
- **#4 — FIXED (device-verified 2026-10-01, task `t_94c90291`)** — see finding #4 for evidence.
- **#5 — FIXED (device-verified 2026-10-01, task `t_9833a73f`)** — see finding #5 for evidence.
- **#6 — FIXED (git-verified 2026-10-01, task `t_3690dae3`)** — see finding #6 for evidence.
- **#7, #8–#17 — OPEN** (no working-tree change addresses them).

New findings from this pass: **N1** (`t_61a50116`), **N2** (`t_8265ae80`),
**N3** (`t_87cef44f`), **N4** (`t_aa09a8ac`) — details in the *New findings* section below.

---

## Severity summary

Status: OPEN / FIXED. "FIXED" reflects a working-tree change verified statically; none of the
fixes are committed yet.

| # | Status | Severity | Area | Finding | File:line |
|---|--------|----------|------|---------|-----------|
| 1 | FIXED (device-verified) | HIGH | Security | ADB debug receiver exported with no permission — any app can inject chat + overwrite API keys | `AdbTalkReceiver.kt`, `AndroidManifest.xml` |
| 2 | FIXED (device-verified) | HIGH | Security/Cost | Proactive-loop receiver exported — any app can force LLM heartbeat ticks | `AndroidManifest.xml:132-133` |
| 3 | FIXED (device-verified) | MEDIUM | Correctness | MediaProjection used without a registered `MediaProjection.Callback` (breaks capture on Android 14+, targetSdk 35) | `ScreenshotTaker.kt:41-45,88` |
| 4 | FIXED (device-verified) | MEDIUM | Correctness | Procedural-memory usage counters mutated but never persisted — reset every restart | `ProceduralMemoryStore.kt:477-493`, `ToolDispatcher.kt:577` |
| 5 | OPEN | MEDIUM | Privacy | Full prompts / full model+API responses logged to logcat in all builds | `AgentEngine.kt:360-367,444`, `GeminiProvider.kt:71-75,94` |
| 6 | OPEN | MEDIUM | Hygiene/Secrets | `.env` is tracked in git; `.gitignore` does not ignore it | `.env`, `.gitignore` |
| 7 | OPEN | MEDIUM | Rule 10 | Hardcoded `Color` values in Compose UI and notifications | `ShiinaVisuals.kt:115-146,350-357`, `DebugTalkService.kt:331`, `MainActivity.kt:414-415` |
| 8 | OPEN | LOW | Dead code | `KnowledgeNote` entity + DAO never registered in `AppDatabase`, never referenced | `KnowledgeNote.kt:1-48` |
| 9 | OPEN | LOW | Dead code | `structuredSchema` param is a no-op; `rawQuery(tone=)` ignored | `AgentEngine.kt:73,372-380` |
| 10 | OPEN | LOW | Perf/Lifecycle | `collectAsState()` used instead of `collectAsStateWithLifecycle()` (7 call sites) | see §10 |
| 11 | OPEN | LOW | Security | `MusicTracker` registers its receiver `RECEIVER_EXPORTED` for app-internal broadcasts | `MusicTracker.kt:91` |
| 12 | OPEN | LOW | Stability | Unparented coroutine scopes never cancelled | `DeviceActionController.kt:41`, `SettingsRepository.kt:22` |
| 13 | OPEN | LOW | Perf | `Thread.sleep(100)` busy-wait up to 2s while holding capture mutex | `ScreenshotTaker.kt:95-100` |
| 14 | OPEN | LOW | Robustness | `!!` on nullable SharedPreferences read | `UserActivityTracker.kt:29` |
| 15 | OPEN | LOW | Observability | Provider loop swallows all exceptions silently | `ProviderRegistry.kt:50` |
| 16 | OPEN | LOW | Rule 5 planning | Files at 847–1269 lines, approaching the 1500 limit | `DeviceActionController.kt`, `ShiinaAccessibilityService.kt`, `OnboardingScreen.kt` |
| 17 | OPEN | LOW | Cost | Agent loop has no per-turn cost guard (up to 25 sequential API calls) | `AgentEngine.kt:30,125` |
| N1 | NEW | MEDIUM | Privacy | Notification title/text sent to Gemini by default (summarization toggle defaults ON) | `SettingsRepository.kt:152-153`, `PromptAssembler.kt:117-124`, `NotificationTriageEngine.kt:228-256` |
| N2 | NEW | LOW | Correctness | `ChatBus.speaking` is dead state — the R2 echo gate cannot work | `ChatBus.kt:27-36`, `VoiceInputEngine.kt:84` |
| N3 | NEW | MEDIUM | Infra | Debug APK is arm64-only; cannot install on x86_64 Waydroid — blocks all device verification | `app/build.gradle.kts:18-20` |
| N4 | NEW | LOW | Architecture | Duplicate notification stores (`NotificationDigest` + `NotificationTriageEngine`) both retain text | `NotificationDigest.kt:19-52`, `NotificationTriageEngine.kt:50-135`, `MusicNotificationListener.kt` |
| N5 | NEW | HIGH (API 34+) | Correctness/Security | API-34+ `isTrustedCaller()` allowlist silently drops legitimate adb-shell broadcasts (`getSentFromUid()` → `Process.INVALID_UID`) | `AdbTalkReceiver.kt:68-72`, `AndroidManifest.xml:109-115` |

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

**RESOLVED — verified on device 2026-10-01 (task `t_77639e6c`; Waydroid Android 13 / x86_64, app uid 10128):**
- **Manifest guard (the actual enforcement):** `AndroidManifest.xml` now declares the receiver with
  `android:permission="android.permission.DUMP"`. Only the adb shell (uid 2000) and system/privileged
  callers hold DUMP, so AMS rejects a third-party broadcast **before `onReceive` runs**, on every
  supported API level. `send.py` keeps working (shell holds DUMP).
- **In-code hardening:** `ACTION_SET_KEY` is gated on `BuildConfig.DEBUG`; on API 34+
  `isTrustedCaller()` allowlists the real sender uid via `BroadcastReceiver.getSentFromUid()`.
- **⚠ Correction to the required-fix snippet above — `Binder.getCallingUid()` does NOT work here.**
  In a broadcast callback the binder identity is already unwound, so it returns the *receiving app's
  own* uid. Observed on device: a shell broadcast (uid 2000) logged `caller uid=10128 self=10128`,
  i.e. the originally prescribed `uid != self && uid != SHELL_UID` check is always `false` and lets
  **every** caller through. The manifest permission guard replaces it.
- **Device evidence (3/3):**
  1. `adb shell am broadcast -n com.shiina.mobile/.debug.AdbTalkReceiver -a …SET_KEY --es provider __verify --es key TESTKEY`
     → accepted; `[SECURITY] API key added via ADB for provider: __verify` logged.
  2. Same two actions broadcast from a third-party app (uid 10129, `com.attacker`) →
     `BroadcastQueue: Permission Denial: … requires android.permission.DUMP`; **no** `SECURITY` log and no
     `DebugTalkService` start (attacker text never processed).
  3. `python3 send.py "hello"` → accepted + delivered: `[DEBUG_PANEL] You: hello`.

**INDEPENDENT RE-VERIFICATION 2026-10-01 (task `t_ab4039e7`) — PASS, from commit `fedcc32` alone:**
- **Build:** detached worktree at `fedcc32` (`git worktree add --detach /tmp/wt-fedcc32 fedcc32`) with the single
  edit `abiFilters += listOf("arm64-v8a", "x86_64")` (`app/build.gradle.kts:20`) — needed only because the bundled
  `libs/sherpa-onnx-1.13.8.aar` has no `jni/x86_64` (`unzip -l` → arm64-v8a + armeabi-v7a only), so the stock
  arm64 APK cannot install on the x86_64 Waydroid target. `./gradlew :app:assembleDebug --offline` → BUILD
  SUCCESSFUL; no app source was modified. Worktree deleted after the run.
- **Tested class == committed class:** `md5sum` of `AdbTalkReceiver.kt` at `fedcc32` and in the working tree are
  identical (`90c6b19a89ea5a0c2d601119bc9d4855`), and `aapt2 dump xmltree` on the installed APK shows
  `permission="android.permission.DUMP"` + `exported=true` on `com.shiina.mobile.debug.AdbTalkReceiver`.
- **No legitimate caller broken:** only `send.py` and the adb command line send `SEND_MESSAGE` / `SET_KEY`
  (`grep -rn` over `app/src/main/java` → only the constants in `AdbTalkReceiver.kt:75-76`); in-app chat goes
  `ChatSend.kt:19-22` → `DebugTalkService.ACTION_TALK` directly and never touches this receiver. The app itself
  neither requests nor holds DUMP (`dumpsys package com.shiina.mobile` → no DUMP entry).
- **Device battery (Waydroid Android 13 / x86_64, app uid 10128; `dumpsys` confirmed version 0.104.0/108
  immediately before and after — the developer's concurrent 0.105.0/109 install was restored afterwards):**
  1. PASS — `adb shell am broadcast … SET_KEY --es provider __verify --es key TESTKEY` →
     `Broadcast completed: result=0` + `[SECURITY] API key added via ADB for provider: __verify`.
  2. PASS — purpose-built third-party APK (`/tmp/audit_verify/attacker-unsigned.apk`, package `com.attacker`,
     uid 10133, built outside this repo with aapt2/d8/apksigner) broadcasting both actions →
     `BroadcastQueue: Permission Denial: broadcasting Intent { act=com.shiina.mobile.debug.SET_KEY … } from
     com.attacker (pid=3101, uid=10133) … requires android.permission.DUMP`, and the same for `SEND_MESSAGE`;
     **no** `SECURITY` log, no `DebugTalkService` start, attacker text never reached the agent loop. APK removed
     afterwards. (Reproduced identically against the developer's own 0.104.0 build earlier in the run.)
  3. PASS — `python3 send.py "audit clean-build check"` and `am broadcast … SEND_MESSAGE --es adb_text …` →
     `[DEBUG_PANEL] You: …` with the pipeline running to `[GEMINI_FAIL] no gemini keys configured` (no key on the
     test device — expected, zero API cost).
- **Commit scope audited:** `git show fedcc32 --name-only` = `AUDIT.md`, `PROGRESS.md`, `app/build.gradle.kts`
  (version 107→108 only), `app/src/main/AndroidManifest.xml` (receiver hunk only), `AdbTalkReceiver.kt`.
  `.env` is **not** in the commit (still a staged deletion in the shared index).
- **Residual risk:** the API-34+ sender allowlist would break the legitimate shell path on Android 14+ — see
  new finding **N5** (`t_7283bc91`).

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

**RESOLVED — verified on device 2026-10-01 (task `t_731e89ab`; Waydroid Android 13 / x86_64, app uid
10128, build 0.105.0 / versionCode 109):**
- **Fix:** `AndroidManifest.xml` now declares the receiver `android:exported="false"` (was `"true"`).
  `BOOT_COMPLETED` never required `exported="true"` (the system may target non-exported receivers),
  and the alarm `PendingIntent` in `scheduleNextTick` is explicit and same-UID, so it is unaffected.
- **Defence in depth:** the `else -> runTick(context)` fallback in `ProactiveLoop.onReceive` was
  removed — an unrecognized action now logs `ProactiveLoop ignored unknown action: <action>` and
  returns instead of starting a paid agent turn.
- **Device evidence:**
  1. Third-party app (`com.attacker2`, uid 10132, built for this test) broadcast all four attack
     shapes — explicit `PROACTIVE_TICK`, explicit alternate `ACTION_PROACTIVE_TICK`, an unrecognized
     action, and an implicit `PROACTIVE_TICK` — and every one was refused by AMS:
     `BroadcastQueue: Permission Denial: broadcasting Intent { act=com.shiina.mobile.action.PROACTIVE_TICK … }
     from com.attacker2 (pid=3285, uid=10132) to com.shiina.mobile/.action.ProactiveLoop is not exported
     from uid 10128`. `adb logcat -s AppDebugServer` showed **zero** `[LOOP]` tick lines — no LLM call.
     (The adb shell is equally refused: `… from null (pid=2862, uid=2000) … is not exported from uid 10128`.)
  2. Cold boot (container restart) → `[LOOP] BOOT_COMPLETED received; restoring proactive heartbeat loop`
     at 22:57:14, followed by `[LOOP] Next proactive tick scheduled by Shiina in 57m`.
  3. `adb shell dumpsys alarm` after boot lists the live alarm
     `RTC_WAKEUP #1: Alarm{5fd8587 … com.shiina.mobile} tag=*walarm*:com.shiina.mobile.action.PROACTIVE_TICK
     origWhen=2026-09-30 23:54:14.771 … operation=PendingIntent{90b2cb4: PendingIntentRecord{89910dd
     com.shiina.mobile broadcastIntent}}` — the explicit, same-UID PendingIntent that delivers the tick,
     registered and pending while the receiver is `exported="false"`.
  4. **Not observed in this session:** the natural delivery of that alarm. `setAndAllowWhileIdle`
     alarms carry a flex window (here `window=+42m44s998ms`), so Android is allowed to batch/defer
     delivery; 8 min past `origWhen` the alarm was still pending (`whenElapsed=-7m58s60ms`,
     `maxWhenElapsed=+34m46s938ms`) and no `[LOOP]` line had appeared. This is normal Android flex
     batching, not a regression from `exported=false` (delivery here is an explicit same-UID
     `PendingIntent`, which Android never export-checks). The auditor should confirm the eventual
     tick on the device.

**Verify:** `adb shell am broadcast -a com.shiina.mobile.action.PROACTIVE_TICK -n com.shiina.mobile/.action.ProactiveLoop` → `SecurityException`/`not exported`; reboot → boot restore still logs `BOOT_COMPLETED received; restoring proactive heartbeat loop`.

**Re-verified 2026-10-01 (audit pass `t_9ada0fa2`):** was STILL OPEN at that time —
`AndroidManifest.xml:132` was `android:exported="true"` and `ProactiveLoop.kt:64` still had
`else -> runTick(context)`. Fixed the same day by developer task `t_731e89ab` (see RESOLVED above).

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

**Re-verified 2026-10-01: FIXED (device-verified), task `t_ad34b7f4`.**

**Fix (`ScreenshotTaker.kt`):** `setProjection` now registers a `MediaProjection.Callback` on the main
looper *before* the projection is stored and before any `createVirtualDisplay`; the callback is kept in
`projectionCallback` and `release()` unregisters it (then stops the projection). `onStop` calls
`release()` so a system-initiated teardown (user taps **Stop** in the projection dialog) drops the dead
projection instead of reusing it. `setProjection`/`release` are `@Synchronized`.

```kotlin
private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
@Volatile private var projectionCallback: MediaProjection.Callback? = null

@Synchronized fun setProjection(mp: MediaProjection) {
    release()
    val callback = object : MediaProjection.Callback() {
        override fun onStop() { release() }
    }
    mp.registerCallback(callback, mainHandler)   // registered BEFORE any createVirtualDisplay
    projectionCallback = callback
    projection = mp
}

@Synchronized fun release() {
    val mp = projection; val callback = projectionCallback
    projection = null; projectionCallback = null
    if (mp != null && callback != null) runCatching { mp.unregisterCallback(callback) }
    runCatching { mp?.stop() }
}
```

**Verified on Waydroid (Android 13 / API 33, x86_64), 2/2 independent runs (fresh process each time):**
screenshot toggle enabled, `MediaProjection` consent granted → `CAPTURE Projection ready (consent
granted, callback registered)`; a subsequent capture through the projection path logged
`CAPTURE Trigger app:…: roll=0.055` → `CAPTURE Saved cap_…jpg (18KB, 480x1040 -> 472x1024,
reason=app_…)`. The message carries **no `via Accessibility` suffix**, so it came from
`grabFrame`/`createVirtualDisplay`, not the `a11y.captureScreenshot()` fallback; the
`Capture skipped (…): no consent or accessibility screenshot available` line occurred **0 times**.
(The callback requirement is API 34+; the fleet Waydroid is API 33, so the *requirement* itself cannot
be reproduced there — the fix is confirmed to register the callback and to not regress capture.)

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

**FIXED 2026-10-01** (task `t_94c90291`, commit on `agents/autodev`). `getProcedureDetails` is now a
`suspend fun` that runs `mutex.withLock { … ; saveToDiskLocked() }` via a new private
`recordUsageLocked(entry)` helper (no more mutation from a `@Synchronized` non-suspend reader).
A new `suspend fun ProceduralMemoryStore.recordUsage(key)` persists the bump, and
`ToolDispatcher.executeProcedureSteps` now calls `recordUsage(entry.id)` instead of mutating the
entry in memory.

Verified two ways:
1. **JVM regression tests** (`app/src/test/java/com/shiina/mobile/memory/ProceduralMemoryPersistenceTest.kt`,
   4 tests, 0 failures): two `GET_PROCEDURE` calls then a *fresh store instance over the same storage
   dir* → `usage_count` accumulates and is read back from `learned_procedures.json`. RED check with the
   `saveToDiskLocked()` line removed reproduced the reported bug exactly (`expected:<3> but was:<1>`
   and `expected:<2> but was:<1>`).
2. **Waydroid device run** (API 33 x86_64, debug APK): `GET_PROCEDURE proc_clock_alarm` twice in pid
   `5440` → `usage_count=2` then `usage_count=3`; `am force-stop` (process gone) → relaunch as pid
   `5569` → `GET_PROCEDURE` again → `usage_count=4` (accumulated, **not** reset). Pre-fix the post-restart
   call would have logged `2`.

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

**FIXED (device-verified 2026-10-01, task `t_9833a73f`, commit on `agents/autodev`).** Payload logging now
routes through a new `AppDebugServer.logPayload(...)` that is a no-op unless `BuildConfig.DEBUG`; every
sensitive body moved behind it while a category/size line is still emitted unconditionally in release:
- `AgentEngine`: `GEMINI_TALK_REQUEST` prompt body, `GEMINI_NET` response preview → `GEMINI_NET_BODY`,
  `GEMINI_RAW` raw JSON → `GEMINI_RAW_BODY`, and `GEMINI_TALK_PARSED` thought/message →
  `GEMINI_TALK_PARSED_BODY` (the structural line keeps mood/status/tool/nextCheckIn + lengths).
- `GeminiProvider`: `GEMINI_REQUEST` prompt, `GEMINI_RESPONSE` raw body → `GEMINI_RESPONSE_BODY`,
  `GEMINI_PARSE_ERROR` body → `GEMINI_PARSE_ERROR_BODY`, `GEMINI_PARSED` reason/message →
  `GEMINI_PARSED_BODY`.
- `DebugTalkService`: the raw chat lines (`You:` / `Shiina:` replies incl. boot greeting) now go through a
  debug-only `dbgContent()`.

**Verify (release APK signed with the debug key, Waydroid API 33 x86_64):** one chat turn → logcat shows only
`TOKEN_AUDIT … promptLength=…`, `GEMINI_NET API response code=… bodyLen=…`, `GEMINI_RAW Raw API response
length=…` — **0** matches for the prompt text, the typed message, or any response body. The debug APK still
logs the full prompt and bodies (unchanged in debug).

**Residual (same class, outside this task's named scope):** `ShiinaAccessibilityService` verdict logs,
`TRIGGER` text, and `LEARNED_MEMORY` facts still embed model/memory content in logcat — recommend a follow-up
finding. Developer task `t_9833a73f`.

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

**FIXED 2026-10-01 (git-verified)** — developer task `t_3690dae3`:
- `.gitignore` now carries a `# Secrets — never commit` / `.env` rule (line 38) and `.fleet/` is ignored.
- `.env` untracked via `git rm --cached .env` (local file preserved). `git ls-files | grep -qx .env` → **untracked**;
  `git check-ignore -v .env` → `.gitignore:38:.env`.
- `.env.example` added and tracked: the redacted template (`GEMINI_API_KEYS=`, `AI_PROVIDER_ORDER=`, …) so
  `send.py --sync-keys` docs still apply; contains no real key.
- `monitor.py` dependency documented in `send.py` (module docstring + import comment): `resolve_serial` comes
  from the local, intentionally gitignored PC-only helper — keep `monitor.py` beside `send.py`.
- **Independent verification (auditor, task `t_a1f5d5b9`, 2026-10-01, read-only on commit `8edc219`):** all four
  claims reproduced. `git ls-tree HEAD -- .env` and `git ls-files -s .env` → empty (index entry gone) while `.env`
  is still on disk (511 B, mode 644) and `git check-ignore -v .env` → `.gitignore:38:.env` (and `.env` is *not*
  ignored if it ever reappears — the rule matches by name, `.env.example` is correctly **not** ignored).
  `.env.example` is tracked in HEAD (`blob bf39577`, `sha256 fe95d3f66cb2ff7d…` — matches the developer's claim)
  and its committed bytes contain only empty `*_API_KEYS=` values plus non-secret config. `send.py` still
  `py_compile`s, and `git show --name-only 8edc219` lists 7 files with **0** under `app/` (no `.kt`/`.kts`/`.xml`),
  so this is config/docs-only — no app-code change. `.fleet/` has no tracked files, so ignoring it is clean.
- **Residual (verified, not actionable now):** untracking removes `.env` only from the tip tree — the file stays
  reachable in history (`8ce3d40:.env`) and at the *unchanged* tips of `origin/agents/autodev` and `origin/main`
  (`blob 86c26b5`). A scan of all 582 reachable blobs found **zero** occurrences of the current local
  `GEMINI_API_KEYS` value, and every committed `*_API_KEYS` value is the literal `[REDACTED]` placeholder, so no
  rotation or `filter-repo` is needed today. If a real key is ever committed before this branch is pushed, rotate
  it and rewrite history rather than relying on `git rm --cached`.

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

**Re-verified 2026-10-01: STILL OPEN.** `ShiinaVisuals.kt` not modified this pass. Developer task `t_ac953910`.

---

## LOW

### 8. Dead code — `KnowledgeNote` is never wired up
`data/db/KnowledgeNote.kt` defines an `@Entity` + `@Dao` (48 lines) that is **not** listed in
`AppDatabase.entities` (`data/db/AppDatabase.kt:9-22`) and is not referenced anywhere (`grep -rn KnowledgeNote`
returns only the file itself). It is also currently **untracked** in git. This is the unimplemented BUILD_PLAN
Phase 3 (knowledge capture). Either wire it into `AppDatabase` with a migration (version 12) and a consumer, or
remove the file until Phase 3 begins.

**Re-verified 2026-10-01: STILL OPEN** (still untracked/unwired). R1 (`t_d2173776`) is the task that will wire
it; developer task `t_f6321785` should be reconciled with R1 rather than delete the file.

### 9. Dead code — no-op `structuredSchema` and ignored `rawQuery` parameter
`AgentEngine.postText(prompt, attachShot, structuredSchema)` declares `structuredSchema` and then has an empty
`if (structuredSchema) { /* commented-out response_schema */ }` block (`AgentEngine.kt:372-380`); the parameter
has no effect. `rawQuery(prompt, tone, attachShot)` never uses `tone` (`AgentEngine.kt:73-75`). Remove the
parameters or implement them; the commented-out schema should be deleted so it does not read as working code.

**Re-verified 2026-10-01: STILL OPEN.** `AgentEngine.kt` `postText` signature unchanged in this respect.

### 10. `collectAsState()` instead of `collectAsStateWithLifecycle()`
Call sites: `ui/chat/ChatScreen.kt:95-98`, `ui/settings/SettingsScreen.kt:86-95`,
`ui/settings/MemoryPanel.kt:83-86`, `ui/character/CharacterPanel.kt:75-78`, `MainActivity.kt:162`,
`ui/onboarding/OnboardingScreen.kt:356,681-682`, and `character/CharacterOverlayService.kt:275-276`.
`lifecycle-runtime-compose` is already a dependency (`app/build.gradle.kts:69`), so the migration is
mechanical. Plain `collectAsState` keeps collecting Room/DataStore flows while the host is stopped
(rule 12: no wasted work while off-screen).

**Re-verified 2026-10-01: STILL OPEN.**

### 11. `MusicTracker` receiver registered as exported
`observation/MusicTracker.kt:91` uses `Context.RECEIVER_EXPORTED` for app-internal music broadcasts.
These are not system broadcasts the app must receive from others; use `Context.RECEIVER_NOT_EXPORTED`
(hardening; also silences the Android 13+ exported flag lint warning).

**Re-verified 2026-10-01: STILL OPEN.**

### 12. Unparented coroutine scopes
`action/DeviceActionController.kt:41` — `CoroutineScope(Dispatchers.Main)` (no `SupervisorJob`, never
cancelled) and `data/settings/SettingsRepository.kt:22` — `CoroutineScope(Dispatchers.IO).launch { store.data.collect { … } }`
inside `init` (fire-and-forget collector that never completes and is never cancelled). Use
`SupervisorJob()` + an explicit `close()`/`cancel()` lifecycle, or a lifecycle-scoped collector.

**Re-verified 2026-10-01: STILL OPEN.** This pass added a *new* instance of the same pattern:
`CompanionApp.observeNotificationTriage()` (`CompanionApp.kt:46-67`) launches another never-cancelled
`CoroutineScope(SupervisorJob() + Dispatchers.IO)` collector. Fold it into this task.

### 13. Blocking busy-wait in screen capture
`observation/ScreenshotTaker.kt:95-100` loops `repeat(20) { … Thread.sleep(100) }` — up to 2 s of blocked IO
time **while holding `lock`** (`:53`), stalling concurrent captures. Use an `ImageReader.OnImageAvailableListener`
or a bounded `delay()`-based wait inside the coroutine.

**Re-verified 2026-10-01: STILL OPEN.**

### 14. Non-null assertion on a nullable prefs value
`data/activity/UserActivityTracker.kt:29` — `prefs.getStringSet(KEY_HOURS, emptySet())!!`. `getStringSet`
returns `StringSet?`; the `!!` will NPE if a malformed/legacy value is read. Use
`.orEmpty().toMutableSet()`.

**Re-verified 2026-10-01: STILL OPEN.**

### 15. Silent provider-failure swallow
`decision/ProviderRegistry.kt:50` — `catch (_: Exception) { continue }` hides every provider failure with no
log, so a permanently broken provider looks identical to a healthy one until the fallback path fires. Log the
provider name + exception before continuing.

**Re-verified 2026-10-01: STILL OPEN.**

### 16. Large files approaching the rule-5 limit
- `action/DeviceActionController.kt` — **1269 lines**
- `action/ShiinaAccessibilityService.kt` — **1054 lines**
- `ui/onboarding/OnboardingScreen.kt` — **847 lines**
Rule 5 forbids >1500 and asks that files approaching it be split. Suggested seams: DeviceActionController →
`MediaController` / `AppLauncher` / `SystemToggles` / `ScreenReader`; ShiinaAccessibilityService →
`GestureRunner` / `ScreenReader` / `ChatConsult`; OnboardingScreen → one file per step composable.

**Re-verified 2026-10-01: STILL OPEN.** Watch `action/ProactiveLoop.kt`, which grew substantially this pass
(Phase 1 integration) and now exceeds 500 lines.

### 17. No per-turn cost guard in the agent loop
`AgentEngine.kt:30` sets `MAX_AGENT_STEPS = 25`; `AgentEngine.kt:125-271` will issue up to 25 sequential
Gemini calls if the model never returns `DONE`. Add a cumulative token/time budget (or lower the cap) so a
looping model cannot burn quota silently.

**Re-verified 2026-10-01: STILL OPEN.**

---

## New findings — 2026-10-01 verification pass

### N1. Notification title/text reaches Gemini by default (summarization toggle defaults ON) — MEDIUM (privacy)
`SettingsRepository.kt:152-153` defaults `notificationSummarizationEnabled` to `true`; `:141-142` defaults
triage to `true`. `PromptAssembler.kt:117-124` injects `NotificationTriageEngine.promptBlock()` into **every**
base prompt, and `NotificationTriageEngine.promptBlock()` (`:228-256`) embeds per-notification `title` + `text`
bodies whenever `summarizationEnabled` is true. On a fresh install, third-party notification content
(WhatsApp/Gmail/banking) is therefore sent to the cloud model without an explicit opt-in, contradicting R4's
"only if enabled" intent. **Fix:** default `NOTIF_SUMMARY` to `false`. Developer task `t_61a50116`.

### N2. `ChatBus.speaking` is dead state — the R2 echo gate cannot work — LOW (correctness)
`ChatBus.kt:27-36` declares `_speaking`/`speaking`/`setSpeaking()` and documents that the push-to-talk mic
hard-gates on it, but `grep -rn "setSpeaking" app/src/main/java/com/shiina/mobile/` returns only the
definition — no caller. `VoiceInputEngine.kt:84` `start()` has no speaking check, so R2 will capture while
TTS/clone audio plays (echo / self-interruption). **Fix:** bracket `VoiceCloneEngine.speak` and
`ShiinaVoiceSpeaker` playback with `ChatBus.setSpeaking(true/false)` (the new `onComplete` hook fits) and have
`VoiceInputEngine.start()` refuse while speaking. Developer task `t_8265ae80`.

### N3. Debug APK is arm64-only — cannot install on x86_64 Waydroid — MEDIUM (verification infra)
`app/build.gradle.kts:18-20` sets `abiFilters += listOf("arm64-v8a")`; the built APK contains only
`lib/arm64-v8a`. Target Waydroid `192.168.240.112:5555` is `x86_64` (Android 13); `adb install -r` fails with
`INSTALL_FAILED_NO_MATCHING_ABIS`. Every "Verify on device" step in this file and every Waydroid procedure in
`RESEARCH_BACKLOG.md` is unrunnable until fixed. Developer task `t_87cef44f`.

### N4. Duplicate notification stores retain text twice — LOW (architecture)
`NotificationDigest.kt:19-52` and `NotificationTriageEngine.kt:50-135` are two independent in-memory stores of
the same notifications (different caps/windows), both fed by `MusicNotificationListener` (`recordDigest` +
`triage`, `seedDigest` + `seedTriage`). `NotificationDigest` has no mute/privacy gate. Consolidate so the
digest derives from the triage engine. Developer task `t_aa09a8ac`.

### N5. API-34+ sender allowlist rejects the legitimate adb/shell path — HIGH (latent, API 34+ only)
`AdbTalkReceiver.kt:68-72` (`isTrustedCaller()`) returns `false` on API 34+ unless `sentFromUid` is
`Process.SHELL_UID` or `Process.myUid()`. `BroadcastReceiver.getSentFromUid()` returns `Process.INVALID_UID`
when the receiver "cannot access the identity of the broadcasting app"
(`developer.android.com/reference/android/content/BroadcastReceiver#getSentFromUid`), and the sender identity
is only propagated when the **sender** opts in with `BroadcastOptions.setShareIdentityEnabled(true)`
(`developer.android.com/privacy-and-security/risks/sender-of-pending-intents`). AOSP makes it explicit:
`shareIdentity ? callingUid : Process.INVALID_UID` (`BroadcastController.java:1677-1682`,
aosp-mirror/platform_frameworks_base@main). The shell never opts in — `ActivityManagerShellCommand.runSendBroadcast`
(the implementation behind `am broadcast`) builds `BroadcastOptions` only for `--allow-background-activity-starts`
and a temp-allowlist; the string `shareIdentity` does not appear in that file. Therefore on any API 34+ target
(minSdk 26, **targetSdk 35**) a shell broadcast yields `INVALID_UID`, `isTrustedCaller()` returns `false`, and
`send.py` / `send.py --sync-keys` are **silently dropped** — the same silent-no-op failure class the previous
fix corrected. The allowlist adds no security: the manifest `android:permission="android.permission.DUMP"`
guard (`AndroidManifest.xml:109-115`) already gated delivery, and `Process.myUid()` can never satisfy that guard
(on device the app neither requests nor holds DUMP), so the extra check can only produce false negatives.
**Not reproducible on this test rig** — Waydroid is API 33, so the branch is never taken; the finding rests on
the platform contract and AOSP source cited above.
**Fix:** `return sender == Process.INVALID_UID || sender == Process.SHELL_UID || sender == Process.myUid()`
(or delete `isTrustedCaller()` and rely on the manifest permission guard, as the KDoc already argues).
**Verify on API 34+:** `adb shell am broadcast -n com.shiina.mobile/.debug.AdbTalkReceiver
-a com.shiina.mobile.debug.SEND_MESSAGE --es adb_text "hi"` → `[DEBUG_PANEL] You: hi`.
Developer task `t_7283bc91`.

---

## Developer tasks spawned from this audit

Every actionable finding has one developer task (idempotency key `audit-<slug>`),
assigned to the `developer` profile on tenant `mobile-assistant`:

| Finding | Task id | Title |
|---------|---------|-------|
| #1 | `t_77639e6c` | Harden exported AdbTalkReceiver (manifest DUMP guard + debug-only key sync) — **FIXED, device-verified, committed** |
| #2 | `t_731e89ab` | Unexport ProactiveLoop receiver (block forced LLM heartbeat ticks) |
| #3 | `t_ad34b7f4` | Register MediaProjection.Callback before createVirtualDisplay — **FIXED, device-verified, committed** |
| #4 | `t_94c90291` | Persist procedural-memory usage counters |
| #5 | `t_9833a73f` | Gate verbose prompt/response logging behind BuildConfig.DEBUG — **FIXED, device-verified, committed** |
| #6 | `t_3690dae3` | Untrack .env, add to .gitignore, add .env.example |
| #7 | `t_ac953910` | Move hardcoded colors into Material 3 theme tokens |
| #10 | `t_bcddc21d` | Use collectAsStateWithLifecycle |
| #8, #9 | `t_f6321785` | Remove/wire dead code (KnowledgeNote, no-op structuredSchema, rawQuery tone) |
| #11–#15 | `t_b1f98933` | Low-severity hardening (receiver flags, scopes, busy-wait, NPE, silent swallow) |
| #16, #17 | `t_29262fd2` | Split large files (rule 5) and add agent-loop cost guard |
| N1 | `t_61a50116` | Privacy: default notification summarization ON sends notification text to Gemini |
| N2 | `t_8265ae80` | Wire ChatBus.speaking so the push-to-talk echo gate works |
| N3 | `t_87cef44f` | Debug APK arm64-only — cannot install on x86_64 Waydroid |
| N4 | `t_aa09a8ac` | Consolidate duplicate notification stores |
| N5 | `t_7283bc91` | Remove the API-34+ sender allowlist that silently drops adb-shell broadcasts |

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
- New this pass: the single-notification-listener constraint is respected — `NotificationTriageEngine` is a
  delegate of the existing `MusicNotificationListener`, not a second `NotificationListenerService`.
  `VoiceInputEngine.encodeWavPcm16` emits a correct 44-byte RIFF/WAVE header (unit-testable).
