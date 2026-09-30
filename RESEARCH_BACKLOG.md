# RESEARCH_BACKLOG.md

Ranked research backlog for the Shiina Mobile companion app.
Maintained by the **researcher** role. Developer-ready specs are handed off as kanban
feature tasks (one per feature, idempotency key `res-<slug>`).

- Repo: `/home/janelle/Documents/GitHub/Mobile-assistant` (branch `agents/autodev`)
- Basis: full survey of `agent.md`, `BUILD_PLAN.md`, `BACKLOG.md`, `PROGRESS.md`, `TREE.md`,
  `AUDIT.md` and `app/src/main/java/com/shiina/mobile/` at `versionCode 108` / `versionName 0.104.0`,
  plus external state-of-the-art research (citations inline).
- Ordering: impact × readiness. New findings are inserted at the top of the *ranked list*;
  history is never deleted — entries are re-ranked and marked.
- **Refresh 2026-10-01 (research task `t_63547052`):** R1–R7 are already on the board (see the
  *previously ranked* section); this refresh adds **R8–R11**, grounded in the in-flight work now
  visible in the tree (`CalendarReader.kt`, `NotificationTriageEngine.kt`, `ShiinaTileService.kt`,
  `VoiceInputEngine.kt`, `KnowledgeNote.kt`). Nothing from R1–R7 was deleted or renumbered.

---

## Current app state (what already exists)

- Closed-loop agent: `decision/AgentEngine.kt`, `ToolDispatcher.kt`, `ToolCatalog.kt` (on-demand
  toolsets: media/apps/device/web/planner), `PromptAssembler.kt`, Gemini round-robin provider.
- Proactive loop: `action/ProactiveLoop.kt` (7 hard gates, late-night + binge guardians, dynamic
  reschedule). Phase 1 (contextual briefings) currently being implemented under task `t_89a8061d`.
- Memory: Room v11 (`data/db/`), `MemoryStore`, `DynamicLearningEngine`, `ProceduralMemoryStore`,
  `MemoryCompactor`, `NightlyReflection`, `MemoryPanel` UI.
- Character/voice: `CharacterOverlayService`, `AnimeCharacter3D`, `MoodEngine`,
  `ShiinaVoiceSpeaker` (system TTS) and `VoiceCloneEngine` (on-device sherpa-onnx ZipVoice clone).
- Observation: `ObservationService`, `ScreenshotTaker` (downscaled), `UsageReader`, `SleepReader`,
  `MusicTracker` + `MusicNotificationListener`.
- **Gaps confirmed by reading source:**
  - **No microphone / speech input anywhere** (`SpeechRecognizer`, `RECORD_AUDIO`, `AudioRecord`
    have zero matches). Chat is text-only (`ui/chat/ChatScreen.kt`).
  - `data/db/KnowledgeNote.kt` exists as an **untracked, unwired** file — entity + DAO are declared
    but not registered in `AppDatabase` and referenced by no other code (leftover from an interrupted
    Phase 3 attempt).
  - `MusicNotificationListener` is the app's **only** `NotificationListenerService` and only reads
    media metadata — general notification triage does not exist. Android forbids a second listener
    service, so triage must live in that single service.
  - No Quick Settings tile, no share-sheet receiver.

---

## Ranked features — new in this refresh (R8–R11)

> Ordered by impact × readiness against the state at `versionCode 108`. R8 (`t_b8d11611`) and R9
> (`t_9db034ad`) are READY; R10 (`t_aaaf0aa2`, parent R1 `t_d2173776`) and R11 (`t_858c1a5c`,
> parents R2 `t_b409f3bc` + R4 `t_0f8f3c7e`) are dependency-gated `todo` cards that the dispatcher
> promotes automatically.

### R8 — Calendar Write: "schedule this" event creation — READY
**Impact: high (closes the temporal loop started by Phase 1 briefings). Readiness: high.**

**Rationale.** Phase 1 (in-flight, `CalendarReader.kt`) makes Shiina *aware* of upcoming events via a
read-only `CalendarContract.Instances` query. But the most common calendar ask is a *write* — "put
lunch with Sam on my calendar tomorrow at 1", "block 90 minutes for the thesis at 3pm". Today there
is **no CALENDAR tool** in `ToolCatalog.kt` and the manifest holds only `READ_CALENDAR`. Adding a
write path means the next briefing can reference the event she just created: awareness + agency.

**Exact files / areas to touch.**
- New `observation/CalendarWriter.kt` (1 feature = 1 file).
  `suspend fun createEvent(title, beginMillis, endMillis, location?, description?, allDay=false): CalendarWriteResult`.
  Select a **writable** calendar by querying `CalendarContract.Calendars.CONTENT_URI`
  (projection `_ID, CALENDAR_DISPLAY_NAME, ACCOUNT_NAME, ACCOUNT_TYPE, CALENDAR_ACCESS_LEVEL,
  VISIBLE, SYNC_EVENTS, IS_PRIMARY`): require `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR`
  and `VISIBLE=1`; **read-only calendars such as the holidays calendar have `CAL_ACCESS_READ` and
  must be excluded**. Prefer `IS_PRIMARY=1`, else `ACCOUNT_TYPE_LOCAL`, else any writable.
  No writable calendar → return `NoWritableCalendar` (never write to holidays).
  Insert via `contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)` with the
  **required** columns for a non-recurring event: `DTSTART`, `DTEND`, `TITLE`, `CALENDAR_ID`,
  `EVENT_TIMEZONE` (`TimeZone.getDefault().id`) — see the Calendar Provider rules
  (developer.android.com/identity/providers/calendar-provider).
- **Dedupe (fixes the duplicate-on-retry failure seen live):** before insert, query `CalendarContract.Events`
  for the same `TITLE` and a `DTSTART` within ±60 s; if present return `Duplicate(existingId)` and do
  not insert. A retried/duplicated tool call must not create a second event.
- `AndroidManifest.xml` — add `<uses-permission android:name="android.permission.WRITE_CALENDAR"/>`.
- `ui/permissions/Permissions.kt` + `PermissionScreen.kt` — a Calendar row that requests both
  READ and WRITE.
- `decision/ToolCatalog.kt` + `ToolDispatcher.kt` — planner tool
  `CREATE_CALENDAR_EVENT {title, date, time, duration_minutes, location?, description?, all_day?}`.
  Parse natural dates (`today`, `tomorrow`, `<weekday>`) and times (`3pm`, `15:30`, `for 90 minutes`),
  reusing/extending the `AgentStep` time parsing already used by `SET_ALARM`.
- Fallback with no `WRITE_CALENDAR`: return `PermissionMissing` and offer the no-permission
  `Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)` pre-filled with
  `EXTRA_EVENT_BEGIN_TIME`/`END_TIME`/`TITLE`, which opens the calendar app for the user to confirm.
- `decision/ShiinaPrompts.kt` — short rule: create events **only** when the user explicitly asks;
  never invent attendees; state the resolved date/time back in one deadpan line (no double-confirm).

**Design decision (fixed, do not re-decide):** v1 is a **direct provider insert** into the primary
writable calendar when `WRITE_CALENDAR` is granted, with the `ACTION_INSERT` intent as fallback.
**Recurring events (RRULE) are out of scope** for v1; all-day events are optional stretch.

**Acceptance criteria.**
1. "add lunch with Sam tomorrow 1pm for an hour" creates exactly one event in a writable calendar,
   start = tomorrow 13:00 local, end = 14:00, correct `EVENT_TIMEZONE`.
2. Read-only/holidays calendars are never chosen; with only read-only calendars present the tool
   returns a clear "no writable calendar" result and falls back to the system insert intent — no crash.
3. Re-issuing the same utterance (or the model calling the tool twice) does not create a duplicate
   (dedupe by title + DTSTART ±60 s).
4. Unit tests: time/date parsing (today/tomorrow/weekday, `3pm`, `15:30`, `for 90 minutes`, all-day);
   writable-calendar selection (read-only excluded, primary preferred, local fallback);
   dedupe window; `NoWritableCalendar` / `PermissionMissing` result mapping.
5. READ granted but WRITE denied → `PermissionMissing`; the permissions row routes to system settings.
6. `./gradlew assembleDebug` clean; version bumped +0.1.0/+1; `PROGRESS.md` and `TREE.md` updated.

**Risks.** Provider absent or account-less on some builds/Waydroid (must degrade, not throw);
DST/timezone (always store epoch millis + `EVENT_TIMEZONE`); duplicate storms (dedupe above);
sync-adapter behaviour when `CALENDAR_ID` is chosen wrongly; permission UX wording.

**Waydroid test procedure.**
1. `adb shell pm grant com.shiina.mobile android.permission.READ_CALENDAR` and
   `... android.permission.WRITE_CALENDAR`.
2. On a build with no writable calendar, send the AC1 utterance → expect the "no writable calendar"
   result + the system insert sheet, and **no** row created.
3. On a device/emulator with a local calendar:
   `adb shell content query --uri content://com.android.calendar/events --projection title,dtstart,dtend`
   before and after → exactly one new row at the right epoch; repeat the utterance → still one row.

---

### R9 — Home-screen status widget (Jetpack Glance) — READY
**Impact: medium-high (persistent presence + one-tap talk). Readiness: high.**

**Rationale.** Presence today is a floating overlay bubble the user can hide, plus a Quick Settings
tile (R5). A home-screen widget is the canonical always-visible companion surface: a mood orb, a
one-line status (next event / pending digest count / days remembered) and a tap target that summons
chat. Glance (1.1.x stable) gives a Compose-style widget API and the project already enables Compose.

**Exact files / areas to touch.**
- `gradle/libs.versions.toml` + `app/build.gradle.kts` — add
  `androidx.glance:glance-appwidget:1.1.1` (and `glance-material3` if using the M3 wrapper).
- New `ui/widget/ShiinaWidget.kt` — a `GlanceAppWidget` + `GlanceAppWidgetReceiver`. In
  `provideGlance` (off the main thread) read: current mood (`MoodEngine`/`MoodState`), days-remembered
  (`MemoryStore`/baseline), next event (`CalendarReader.upcomingEvents(24, 1)`) and pending triage
  count (`NotificationTriageEngine.unreadCount()`). Render the existing orb drawable + one status line
  + a button launching `MainActivity` with `EXTRA_SUMMON` (same flag R5 uses).
- New `res/xml/shiina_widget_info.xml` (`<appwidget-provider>` with `initialLayout="@layout/glance_default_loading_layout"`).
- `AndroidManifest.xml` — `<receiver android:exported="true">` with `APPWIDGET_UPDATE` +
  `android.appwidget.provider` meta-data.
- A tiny `ui/widget/ShiinaWidgetUpdater.kt` (or a call from `CharacterOverlayService` /
  `NotificationTriageEngine` change hook) that pushes updates when mood or the triage count changes.
  **Never poll every minute** — widgets live in a separate process and frequent updates drain battery
  (developer.android.com/develop/ui/compose/glance).

**Design decision (fixed):** the widget is **read-only + one summon button** in v1 — no interactive
toggles. Glance composables must not be mixed with regular Compose UI; keep this file self-contained.

**Acceptance criteria.**
1. Widget is addable from the launcher widget picker; renders within a few seconds.
2. Shows the mood and one grounded status line; tap opens chat with the overlay summoned.
3. Updates when a triaged notification arrives and when the mood changes; does not poll per-minute.
4. With calendar/notification permissions missing it falls back to a static greeting + mood — no crash.
5. `assembleDebug` clean; version bumped; `TREE.md` updated (new `ui/widget/` package).

**Risks.** Glance is a separate process/RemoteViews model (no shared composables); Room/DataStore reads
inside `provideGlance` must be off the main thread; `CompanionApp`/DI access from the widget receiver;
widget host quirks on Waydroid/Huawei.

**Waydroid test procedure.** Launcher long-press → Widgets → add "Shiina"; verify via
`adb shell dumpsys appwidget | grep -i shiina` that the provider is bound and updated; tap the widget →
app opens on Chat with the overlay.

---

### R10 — Weekly Reflection Digest  *(gated on R1 — parents=[`t_d2173776`])* — GATED
**Impact: medium-high. Readiness: medium (needs R1's `KnowledgeNote` store; goals and memory already exist).**

**Rationale.** `NightlyReflection` and `MemoryCompactor` already distill memory in the background, but
nothing user-facing closes the week. A Sunday-evening digest that writes one structured
`KnowledgeNote(kind="reflection", topic="weekly")` — the week's captured notes, completed goals,
habit/mood patterns and one focus for next week — turns raw capture into reflection. That is the
"external brain" payoff the app promises.

**Exact files / areas to touch.**
- New `memory/WeeklyReflectionWorker.kt` (`CoroutineWorker`, existing `work-runtime`): reads
  `KnowledgeNoteDao` (last 7 days), `GoalDao` (completed), `MemorySummary`; asks the existing Gemini
  provider for a short structured summary; **deterministic template fallback** if the model is
  unreachable (offline/dev). Writes exactly one note per ISO week.
- `action/ProactiveLoop.kt` — surface the new reflection once (overlay/notification) on the chosen day.
- `data/settings/SettingsRepository.kt` + `SettingsScreen.kt` — `weeklyReflectionEnabled`, day + hour.
- `di/AppContainer.kt` — schedule the periodic worker (7-day interval, existing `BaselineWorker` pattern).

**Design decision (fixed):** one note per ISO week, keyed idempotently by `topic="weekly:<year>-W<week>"`
so re-runs do not duplicate; the model is optional (template fallback), never a hard dependency.

**Acceptance criteria.** Worker writes exactly one reflection note per week even if run repeatedly;
deterministic fallback when the provider fails; note appears in the R1 Journal; surfaced once; unit
tests for the week-key + note assembly from a fixed input set.

**Risks.** Depends on R1's schema/migration landing first (hence the parent gate); WorkManager periodic
minimum is 15 min but 7-day cadence is fine; duplicate weeks if the key is computed inconsistently.

---

### R11 — Inline notification quick-reply via RemoteInput  *(gated on R2 + R4)* — GATED
**Impact: medium-high (the Phase 4 "one-tap voice reply" goal is still uncovered). Readiness: medium (needs R2 + R4).**

**Rationale.** R4 triages notification noise and R2 adds voice input, but Phase 4 also promises
*replying* without opening the app. Android's platform mechanism is **Direct Reply** (`RemoteInput`):
attach a reply action to Shiina's own digest/important notification so the user can type — or speak
(R2) — a reply that is routed back into the source messaging app's own RemoteInput action, falling
back to Shiina's chat when the source app does not expose one.

**Exact files / areas to touch.**
- `observation/NotificationTriageEngine.kt` — for `MESSAGE`-category posts, capture the source
  `Notification.Action`s that carry a `RemoteInput` (key + `PendingIntent`) so a reply can be routed.
- New `action/ReplyActionReceiver.kt` — a `BroadcastReceiver` for the reply `PendingIntent`; extract
  text with `RemoteInput.getResultsFromIntent(intent)` and forward it to the source app's action, or
  to Shiina's chat when absent; update the notification afterward (do not just dismiss it).
- `character/CharacterOverlayService.kt` — the digest pill gains a reply affordance; R2's mic fills it.
- `AndroidManifest.xml` — register the receiver (`exported="false"`, PendingIntent is explicit).
- `data/settings/SettingsRepository.kt` — `inlineReplyEnabled` toggle.

**Design decision (fixed):** v1 replies go to the **source app's own RemoteInput** when it exists;
otherwise the text opens Shiina's chat pre-filled — never silently drop the reply. The reply action is
only added to MESSAGE/EMAIL-category notifications, never to promos/system.

**Acceptance criteria.** A messaging notification surfaces a Shiina "Reply" action; a typed reply is
delivered to the source app and the notification updates to show it; a voice reply (R2) can fill the
same field; an app with no RemoteInput action falls back to opening Shiina chat pre-filled; unit tests
for RemoteInput extraction + fallback selection.

**Risks.** Depends on R2 (voice) and R4 (triage) landing; grabbing another app's RemoteInput action is
best-effort and OEM-dependent; PendingIntent mutability flags (API 31+ `FLAG_MUTABLE` is required for
RemoteInput); privacy — never persist reply bodies.

---

## Ranked features (previously ranked — R1–R7, unchanged order)

> R1–R7 below were ranked in the first research pass; their developer tasks already exist on the
> board (R1 `t_d2173776`, R2 `t_b409f3bc`, R3 `t_1f30bc77`, R4 `t_0f8f3c7e`, R5 `t_a26db032`,
> R6 `t_4a613f94`). Kept verbatim for history; re-ranked only relative to the new R8–R11 above.

### R1 — Knowledge Capture & Structured Journaling  *(BUILD_PLAN Phase 3)* — READY
**Impact: very high. Readiness: high (scaffolding already on disk).**

**Rationale.** The app's stated promise is an "external brain" that remembers ideas, decisions and
reflections shared in chat. Today only *facts* (key/value `MemoryFact`) are stored; there is no
journal, no topic structure, no retrieval. `KnowledgeNote.kt` already defines the table + DAO but is
dead code. Wiring it turns the companion into a searchable personal knowledge base and is the
single cheapest large qualitative win.

**Exact files / areas to touch.**
- `data/db/KnowledgeNote.kt` (exists, untracked — adopt as-is after review; `kind`, `topic`, `text`,
  `source`, `createdMillis`).
- `data/db/AppDatabase.kt` — add `KnowledgeNote::class` to `entities`, add `knowledgeNoteDao()`,
  **bump version 11 → 12**, add non-destructive `MIGRATION_11_12` creating `knowledge_notes`
  (`CREATE TABLE IF NOT EXISTS` + indexes on `createdMillis`, `kind`, `topic`).
- `di/AppContainer.kt` — expose the DAO.
- `decision/ToolCatalog.kt` + `decision/ToolDispatcher.kt` — add **planner** tools:
  `CAPTURE_NOTE {kind, topic, text, source}`, `LIST_NOTES {topic?, kind?, limit?}`,
  `SEARCH_NOTES {query}`, `LIST_TOPICS`, `DELETE_NOTE {id}`.
- `decision/PromptAssembler.kt` (or `decision/MemoryContext.kt`) — inject the most recent N notes
  (e.g. 8) and topic list into live context so recall works without a tool call.
- `decision/ShiinaPrompts.kt` — short capture etiquette: capture silently when the user shares an
  idea/task/decision/reflection ("note this", "I should…", "remind me we decided…"); never spam
  the chat with "saved!". Keep `REMEMBER` for durable preferences; `CAPTURE_NOTE` for rich notes.
- `ui/settings/MemoryPanel.kt` — a "Journal" section: notes grouped by topic, kind chip, delete,
  total count; reuse existing M3 card components.

**Design decision (fixed, do not re-decide):** v1 capture is **explicit-tool driven** (the model
calls `CAPTURE_NOTE`), not a background NLP extractor — deterministic, testable, no surprise rows.
Automated extraction is a later feature (see R7).

**Acceptance criteria.**
1. On a fresh install, chat "note this: I want to refactor the memory module next week" produces
   exactly one `knowledge_notes` row with a sensible `kind` and `topic`, and she does **not** print
   a "saved" confirmation.
2. `Migration 11→12` upgrades a populated v11 database with zero data loss (existing facts, chat
   turns and episodes intact).
3. "What ideas did I mention this week?" returns the captured note from prompt context / tool.
4. `MemoryPanel` lists notes by topic, delete works, count is correct.
5. Unit tests: note classification/kind mapping, DAO query shapes (topics/recent/byKind), and that
   recent notes are present in the assembled prompt. `./gradlew assembleDebug` clean; version bumped.

**Risks.** Room migration errors if the table DDL drifts from the entity (keep them byte-identical);
over-capture clutter (mitigated by explicit-tool design + a "don't capture trivial chatter" prompt
rule); file ownership collision with `MemoryPanel.kt` (see hotspot note at bottom).

**Waydroid test procedure.**
1. `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`.
2. Launch, complete/skip onboarding, open Chat, send the capture utterance from AC1.
3. `adb shell run-as com.shiina.mobile sqlite3 databases/shiina.db "select kind,topic,text from knowledge_notes;"` — expect exactly one row.
4. Open Settings → Memory → Journal; confirm the note renders under its topic; delete it and confirm.
5. Reboot Waydroid and confirm the migration applies on an existing (populated) DB without crash.

---

### R2 — Voice Input: Push-to-Talk Speech Recognition  *(BUILD_PLAN Phase 2a)* — READY
**Impact: very high (removes the text-only interaction gap). Readiness: medium.**

**Rationale.** A companion you must type to is not a companion. There is currently no audio input
path at all. Adding push-to-talk STT makes the product feel alive and is the prerequisite for
barge-in (R3). Two viable engines; **decision fixed for v1: Gemini audio input**
(`inline_data` `audio/wav`), because the app already has a multi-key Gemini pool, retry/failover,
and the required REST client — no new model download, no Play Services dependency (Waydroid-safe).
On-device sherpa-onnx streaming ASR (the AAR is already vendored) is the follow-up for offline use.

**Exact files / areas to touch.**
- New `character/VoiceInputEngine.kt` (1 feature = 1 file): `AudioRecord` at 16 kHz mono PCM16,
  encode to WAV via a pure helper (`encodeWavPcm16`), expose `start()/stop(): File?`, release on
  lifecycle end. Use `MediaRecorder.AudioSource.VOICE_RECOGNITION`.
- `AndroidManifest.xml` — add `<uses-permission android:name="android.permission.RECORD_AUDIO"/>`.
- `ui/permissions/PermissionScreen.kt` + `ui/permissions/Permissions.kt` — a Microphone row.
- `data/settings/SettingsRepository.kt` — `voiceInputEnabled` toggle (+ Settings UI toggle).
- `decision/GeminiProvider.kt` — accept an optional audio `File` and attach as `inline_data`
  `mime_type=audio/wav` alongside the text part (it already attaches screenshots this way).
- `decision/AgentEngine.kt` — a `runAgentLoop(userText, audioFile)` entry that forwards the audio.
- `ui/chat/ChatScreen.kt` — a mic button in the input row: tap to start, tap to stop → transcribed
  text lands in the input field → send. Show recording state (reuse `ChatBus`/`TypingDots`).

**Design decision (fixed):** v1 is **push-to-talk**, not always-listening. Always-listening +
wake word is explicitly out of scope (battery + privacy) and deferred.

**Acceptance criteria.**
1. Granting mic permission and holding/tapping the mic records ≤30 s and sends the clip; the agent
   transcribes intent and responds correctly.
2. Permission denied → the mic button routes to the permission screen, no crash, no silent failure.
3. WAV helper unit test: header fields (RIFF/WAVE/fmt/data), channels=1, sampleRate=16000, correct
   data length for a known PCM buffer.
4. Gemini request builder unit test: audio part present with `audio/wav` when a file is supplied,
   absent otherwise.
5. Cloned/system TTS speaks the reply as usual. `assembleDebug` clean; version bumped.

**Risks.** PCM16 @16 kHz is ~1.9 MB/min → base64 inflates ~33 %; cap the clip at 30 s and reuse the
existing key pool/retry. Recording must not run while the assistant is speaking (echo) — that is R3's
job, but v1 should hard-gate "stop recording before TTS starts". Background mic capture from a
foreground service must respect Android 14 FGS-type rules (use the existing specialUse service).

**Waydroid test procedure.**
1. Push a known 16 kHz mono WAV to the device and drive `VoiceInputEngine`'s encode/attach path
   directly (or via the debug web API) — Waydroid often has no host mic.
2. `adb install -r`, grant `RECORD_AUDIO` via `adb shell pm grant com.shiina.mobile android.permission.RECORD_AUDIO`.
3. Chat screen → mic button → confirm UI state toggles and the debug server logs `GEMINI` with an
   audio part; verify a transcript-driven reply appears.
4. On a real device (or Waydroid with a virtual mic) confirm a spoken phrase round-trips.

---

### R3 — Conversational Barge-In  *(BUILD_PLAN Phase 2b)* — BLOCKED on R2
**Impact: high. Readiness: low until R2 lands.** *(Kanban task created with `parents=[R2 task]`.)*

**Rationale.** "Talking to Shiina should feel like a phone call" requires that she *stops talking*
the moment the user speaks. Today playback (`VoiceCloneEngine.playPcm16` / system TTS) and any future
mic path are independent, so her own voice would be re-heard and self-interrupt if R2 and TTS ran
together. This is the acoustic-engineering feature that makes voice feel natural.

**Exact files / areas to touch.**
- `character/VoiceCloneEngine.kt` — expose `isSpeaking` + a synchronous `stopNow()` (it already has
  a `stopFlag`/`stopPlayback`); `character/ShiinaVoiceSpeaker.kt` — surface `isSpeaking`.
- New `character/VoiceActivityDetector.kt` (1 feature = 1 file): `AudioRecord` on
  `VOICE_COMMUNICATION`, enable `AcousticEchoCanceler`/`NoiseSuppressor` when
  `isAvailable()`, compute short-window RMS/energy, emit "user speech started/stopped".
- Wiring in `debug/DebugTalkService.kt` / `CharacterOverlayService.kt`: when VAD reports speech
  while `isSpeaking`, call `stopNow()` then begin capture (hands off to R2's engine).

**Acceptance criteria.**
1. While a reply is being spoken, sustained user speech above threshold stops playback within
   ~300 ms and starts a new capture.
2. `AcousticEchoCanceler` is enabled when available and cleanly skipped when not; TTS leaking into
   the mic does **not** trigger barge-in (no self-interruption) on a device with AEC.
3. Energy/VAD logic is unit-tested (silence → no event; above-threshold frame → event; hysteresis
   prevents flap).
4. No audio-route regression: media playback by other apps keeps working.

**Risks.** AEC availability varies by OEM (Huawei JNY-LX1 must be probed); threshold calibration is
device-specific (make it a tunable constant + settings value); battery drain from continuous
`AudioRecord` (stop the recorder when not speaking); interaction with `USAGE_ASSISTANT` audio focus.

**References.**
- Android `AcousticEchoCanceler` / `AudioRecord` with `VOICE_COMMUNICATION` for hardware AEC
  (developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler).
- Gemini **Live API** (native bidirectional audio, real-time barge-in) is the eventual low-latency
  path — developer.android.com/ai/gemini/live and ai.google.dev/gemini-api/docs/live-api. Not used
  for v1 (WebSocket session management); revisit if R2/R3 latency disappoints.

**Waydroid test procedure.** Waydroid has limited audio routing, so: (a) unit-test VAD/energy logic
and the "stop playback" state machine on the host; (b) on hardware, play a reply and speak over it,
confirming `VOICE_CLONE`/`VOICE_TTS` log a `stop` and a new capture starts within ~300 ms; (c) log
`AEC available=<bool>` and re-test without AEC to document fallback behaviour.

---

### R4 — Notification Triage & Digest  *(BUILD_PLAN Phase 4)* — READY
**Impact: high. Readiness: medium.**

**Rationale.** Notification noise is the core daily-friction problem; the app already holds the
notification-listener binding but uses it only for media. Grouping low-priority pings into a single
spoken/overlay digest, while surfacing genuinely important ones, is exactly the "filter the noise"
promise and pairs with proactive briefings (Phase 1).

**Exact files / areas to touch.**
- **Critical constraint:** Android permits **one** `NotificationListenerService` per app. Extend the
  existing `observation/MusicNotificationListener.kt` binding — add triage logic to it (or extract a
  shared `observation/NotificationTriageEngine.kt` that the same service delegates to; do **not**
  register a second listener).
- New `observation/NotificationTriageEngine.kt` (1 feature = 1 file): filter out media/ongoing/
  group-summary noise, classify by package + `Notification.category` into
  `message | email | social | system | promo | other`, keep a rolling in-memory (and optional
  persisted) queue with dedupe by `(pkg, key, postTime)`.
- `data/db/` — new `NotificationDigestEntry` entity + DAO + `MIGRATION_12_13` (if persisting), else
  prefer in-memory to avoid another migration.
- `CharacterOverlayService` — an "N new notifications" glassy pill that expands to a digest.
- `decision/PromptAssembler.kt` — expose the pending-queue summary to the model so "what did I miss?"
  works conversationally.
- `ui/settings/SettingsScreen.kt` + `SettingsRepository.kt` — per-category mute + a
  `notificationTriageEnabled` toggle, and a clear "notification content is processed on-device;
  summarizing sends text to your model only if enabled" note.

**Design decision (fixed):** classification is **rule-based** (package/category heuristics) for v1;
LLM summarization is opt-in and only ever receives already-truncated title/text. Never store full
message bodies unless the user opts in.

**Acceptance criteria.**
1. Posting ≥5 mixed notifications (e.g. `adb shell cmd notification post`) yields correct categories;
   media/ongoing notifications are ignored.
2. Low-priority notifications group into one digest; a "crucial" category is surfaced individually.
3. "What did I miss?" returns a grounded summary of the current queue.
4. Muting a package suppresses its notifications from the digest.
5. No second listener service is registered (manifest still has exactly one
   `NotificationListenerService`); unit tests for the classifier + dedupe.

**Risks.** Privacy (notification bodies are sensitive — truncate, keep on device, opt-in send);
classification false positives; battery from a high-frequency callback; OEMs killing the listener
(already a known Huawei issue).

**Waydroid test procedure.**
1. `adb shell cmd notification post -t "Bank" tag1 "Payment of 20 received"` and a few others,
   plus a media notification; enable the listener in Settings → Notification access.
2. Confirm via the debug web server that only non-media notifications enter the queue with correct
   categories; check the overlay digest appears and expands.
3. Chat "what did I miss?" and verify a grounded answer.
4. Mute one package and re-post; confirm it is dropped.

---

### R5 — Quick Settings Tile: "Talk to Shiina" — READY (small)
**Impact: medium-high (one-tap access). Readiness: high.**

**Rationale.** The overlay bubble is the main entry point, but it is hideable and easy to lose. A
Quick Settings tile gives a guaranteed one-tap summon from anywhere — the standard Android pattern
for always-available assistants.

**Exact files / areas to touch.**
- New `action/ShiinaTileService.kt` (extends `android.service.quicksettings.TileService`).
- `AndroidManifest.xml` — `<service>` with `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"`
  and `<intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>`.
- New `res/drawable/ic_tile_shiina.xml` (monochrome, reuses the existing even-odd orb path so flat
  tinting renders eyes/mouth as holes).
- `CharacterOverlayService` / `MainActivity` — action to bring the overlay/chat forward.

**Acceptance criteria.** Tile is addable from the QS edit UI; tap summons the overlay/chat; long-press
opens app settings; `TileService` handles `onTileAdded/onStartListening/onStopListening` without
leaking; no crash if overlay permission is missing (routes to the permission screen). Version bumped.

**Risks.** Tile icon tinting; `startActivity` from a tile requires `FLAG_ACTIVITY_NEW_TASK`; do work
off the main thread.

**Reference.** developer.android.com/develop/ui/views/quicksettings-tiles.

**Waydroid test procedure.** `adb shell cmd statusbar` / pull down QS → Edit tiles → add "Shiina" →
tap and confirm the overlay/chat opens; long-press opens Settings.

---

### R6 — Share-to-Shiina Capture (share sheet) — READY (small, synergizes R1)
**Impact: medium. Readiness: high (needs R1's store to land or stubs against it).**

**Rationale.** The fastest way a user captures an idea is to share it the moment they see it. An
`ACTION_SEND` target writes straight into the journal (R1), making the knowledge base actually fill
up from real life instead of only chat.

**Exact files / areas to touch.**
- New `action/ShareCaptureActivity.kt` (transparent theme) handling `ACTION_SEND` for `text/plain`
  (and optionally `image/*` later).
- `AndroidManifest.xml` — activity with intent-filter `ACTION_SEND`, `mimeType="text/plain"`,
  label "Save to Shiina".
- Writes a `KnowledgeNote(kind="note", source="share", text=<EXTRA_TEXT>, topic=<auto>)` via R1's DAO,
  then finishes with a brief confirmation overlay/toast.

**Acceptance criteria.** Sharing text from another app lists "Shiina" and saves a note with
`source="share"`; the note appears in the Journal (R1); activity is exported and handles the intent
without ANR; auto-topic is derived from the text (simple keyword/length rule) rather than blank.

**Risks.** Depends on R1's schema — implement after/with R1 (Kanban task parent = R1); large shared
text should be truncated to a sane cap.

**Reference.** developer.android.com/develop/ui/compose/sharing/send.

**Waydroid test procedure.** Open Browser/Notes → share some text → pick "Shiina" → confirm the note
in Settings → Memory → Journal; verify `source="share"`.

---

### R7 — (Deferred) Automatic Knowledge Extraction & Semantic Recall
**Impact: medium-high. Readiness: low — deferred until R1 has real data.**

**Rationale.** R1 captures what the user explicitly flags; the next step is passive extraction (mine
each finished conversation for ideas/tasks/decisions) and semantic retrieval (embeddings) so recall
works by meaning, not keywords. Deferred because it needs R1's schema + a corpus, and on-device
embeddings are a separate model/dependency decision. Revisit once R1 is verified on device.

---

## Cross-cutting notes for the developer

- **Hotspot — one file, many features:** `decision/PromptAssembler.kt`, `decision/ToolCatalog.kt`,
  `decision/ToolDispatcher.kt` and `ui/settings/MemoryPanel.kt` are touched by several cards (R1, R3,
  R4, R8). Land R1 first and keep those edits small/append-only to avoid merge collisions; flag if a
  file shows up in multiple in-flight diffs.
- **Hotspot — notification surface:** `observation/NotificationTriageEngine.kt` is now touched by R4
  (triage), R9 (widget reads `unreadCount`) and R11 (RemoteInput capture). Land R4 first; R9 should
  only *read* it and R11 should extend it additively.
- **Hotspot — calendar files:** `observation/CalendarReader.kt` (Phase 1, read) and the new
  `observation/CalendarWriter.kt` (R8, write) must agree on the `CalendarEvent` shape; keep the write
  path in its own file and reuse the reader's timezone handling.
- **Hotspot — the single NotificationListenerService:** R4 must extend, never duplicate, the existing
  binding in `AndroidManifest.xml`.
- **Room migrations are additive-only** (matches existing style). Every new entity = version bump +
  `CREATE TABLE IF NOT EXISTS` migration; never destructive.
- **agent.md rules still apply:** ≤1500 lines/file (largest today is `DeviceActionController.kt` at
  1269 — watch it), 1 feature = 1 file, Material 3 tokens only, off-main-thread I/O, bump version by
  0.1.0 per update, update `PROGRESS.md` + `TREE.md`.

## Sources
- Android Calendar Provider (read/write events, required columns, `WRITE_CALENDAR`,
  `ACTION_INSERT` fallback, read-only holidays calendar): developer.android.com/identity/providers/calendar-provider;
  `CalendarContract.Events` — developer.android.com/reference/android/provider/CalendarContract.Events.
- Notification Direct Reply / `RemoteInput` (inline reply, `getResultsFromIntent`, PendingIntent flags):
  developer.android.com/develop/ui/views/notifications; blog.michaelsam94.com/android-notification-inline-replies.
- Jetpack Glance app widgets (`GlanceAppWidget`, `provideGlance`, update scheduling / battery guidance):
  developer.android.com/develop/ui/compose/glance.
- UsageStats API (existing wellbeing/binge gates): developer.android.com/reference/android/app/usage/UsageStatsManager.
- Acoustic echo cancellation / barge-in on Android: `AcousticEchoCanceler`, `AudioRecord`
  `VOICE_COMMUNICATION` — developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler.
- Gemini Live API (real-time bidirectional audio): developer.android.com/ai/gemini/live;
  ai.google.dev/gemini-api/docs/live-api.
- Android `SpeechRecognizer` / ML Kit GenAI Speech Recognition:
  developer.android.com/reference/android/speech/SpeechRecognizer;
  developers.google.com/ml-kit/genai/speech-recognition/android.
- sherpa-onnx on-device streaming ASR (AAR already vendored): k2-fsa.github.io/sherpa/onnx/android/apk.html.
- Quick Settings tiles: developer.android.com/develop/ui/views/quicksettings-tiles.
- Android Sharesheet / `ACTION_SEND`: developer.android.com/develop/ui/compose/sharing/send.
- Notification inline replies / `RemoteInput`:
  blog.michaelsam94.com/android-notification-inline-replies.

## History
- 2026-10-01 — Initial `RESEARCH_BACKLOG.md`. Survey at v0.103.4. Ranked R1–R7; created developer
  feature tasks for R1, R2, R4, R5, R6 and a dependency-gated task for R3 (parents = R2).
- 2026-10-01 — Refresh (task `t_63547052`, survey at v0.104.0). Added **R8** (calendar write /
  "schedule this"), **R9** (Glance home-screen status widget), **R10** (weekly reflection digest,
  gated on R1), **R11** (inline notification quick-reply via RemoteInput, gated on R2+R4). Created
  developer tasks with idempotency keys `res-calendar-write`, `res-glance-widget`,
  `res-weekly-reflection`, `res-inline-reply`. R1–R7 kept verbatim in the *previously ranked* section;
  no entries deleted or renumbered.
