# RESEARCH_BACKLOG.md

Ranked research backlog for the Shiina Mobile companion app.
Maintained by the **researcher** role. Developer-ready specs are handed off as kanban
feature tasks (one per feature, idempotency key `res-<slug>`).

- Repo: `/home/janelle/Documents/GitHub/Mobile-assistant` (branch `agents/autodev`)
- Basis: full survey of `agent.md`, `BUILD_PLAN.md`, `BACKLOG.md`, `PROGRESS.md`, `TREE.md`
  and `app/src/main/java/com/shiina/mobile/` at `versionCode 107` / `versionName 0.103.4`,
  plus external state-of-the-art research (citations inline).
- Ordering: impact × readiness. New findings are inserted at the top of the *ranked list*;
  history is never deleted — entries are re-ranked and marked.

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

## Ranked features

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
  R4). Land R1 first and keep those edits small/append-only to avoid merge collisions; flag if a
  file shows up in multiple in-flight diffs.
- **Hotspot — the single NotificationListenerService:** R4 must extend, never duplicate, the existing
  binding in `AndroidManifest.xml`.
- **Room migrations are additive-only** (matches existing style). Every new entity = version bump +
  `CREATE TABLE IF NOT EXISTS` migration; never destructive.
- **agent.md rules still apply:** ≤1500 lines/file (largest today is `DeviceActionController.kt` at
  1269 — watch it), 1 feature = 1 file, Material 3 tokens only, off-main-thread I/O, bump version by
  0.1.0 per update, update `PROGRESS.md` + `TREE.md`.

## Sources
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
