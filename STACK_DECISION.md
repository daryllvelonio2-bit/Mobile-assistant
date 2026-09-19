# Stack Decision: Personal Android AI Companion (v1)

Date: 2026-09-16
Status: proposed — confirm before scaffolding.

## Language: Kotlin

Reasoning: modern Android default; cleaner async/background code; better fit for the observation + API call pattern this app uses. Java would work, but Kotlin reduces boilerplate for the parts that matter (coroutines for polling/capture, data classes for summaries, extension helpers for permissions). No reason to pick Java for a personal v1.

Open choice: none — Kotlin is the recommendation unless you have a strong Java preference.

## In-app UI: Jetpack Compose

Reasoning: faster to build the settings/goal/alarm screens than XML Views, and Compose scales fine for a personal app with a handful of screens. The draw-over character overlay (v2) is a separate floating window and is not bound to the in-app toolkit choice — it can be a Compose-based floating content view or a traditional View later.

Open choice: in-app screens could be XML Views if you prefer, but Compose is the recommendation.

## Character overlay (v2): floating window with SYSTEM_ALERT_WINDOW

Reasoning: the character needs to draw over other apps. The mechanism is SYSTEM_ALERT_WINDOW + a floating View/Compose surface. Toolkit for the overlay itself is deferred to v2 and can be Compose-in-floating-window or a plain View. Not deciding the overlay toolkit in v1.

## Architecture: simple layered, not over-architected

Layers:
- Observation: reads usage, sleep, goals, optional screenshots.
- Memory: persists events + rolling baseline locally.
- Decision: builds summary, calls API, returns tone + interrupt + action.
- Action: executes local actions (alarm, toggle capture, goal log).
- UI (v1): settings, goals, alarm, capture toggle.
- Character (v2): consumes decision output.

For a personal v1, use pragmatic separation with interfaces at the layer boundaries (observation behind an interface, decision behind an interface, action behind an interface) so the character can consume the decision later without refactoring. Avoid heavy DI frameworks unless you want them — a small manual DI or lightweight service locator is enough for one device. If you want a standard option, Hilt is available; if you want minimal, manual wiring is fine for v1.

Open choice: DI approach. Recommendation: keep it light; Hilt only if you want the standard plumbing.

## Persistence

- Structured event/history data (usage events, sleep readings, goal lifecycle, baseline snapshots): Room.
- Settings (screenshot toggle, alarm time, goal caps, API key pointer): DataStore (Preferences).
- Screenshot frames: files on disk, managed by the capture service; lifecycle handled in v1 with the deferred guardrails (no history UI yet, just storage + the toggle).

Reasoning: Room for queryable history and baselines; DataStore for simple settings; files for frames because they are blobs and not structured records.

## Background / observation model

Recommendation: a foreground service with a persistent notification for the observation + optional capture loop, because v1 wants the capture loop and usage polling to stay alive across Doze and screen-off time more than it wants battery minimalism. WorkManager can handle deferrable maintenance (baseline recalculation, cleanup) but is not the primary engine for continuous capture.

Reasoning: Doze and OEM battery policy will kill passive background work; if the screenshot loop and observation are core to v1, a foreground service is the honest choice for one user's device. The tradeoff is a visible notification and higher battery cost — acceptable for personal use.

Open choice: if you want lighter battery use and are OK with capture only while the app is active or via a scheduled job, we can reconsider. For the described behavior (periodic capture while you use the phone), foreground service is the recommendation.

## API layer (free-tier models, expandable multi-provider + round-robin)

- Provider: expandable registry of free-tier providers (Gemini, OpenAI, OpenRouter, Groq, etc.). v1 ships with one working provider, but the interface allows adding more without refactoring callers.
- Key rotation: round-robin across multiple API keys per provider, then across providers. On 429/quota/transient failure, rotate to next key/provider with backoff; track per-key failure counts and cooldown.
- Key storage: Android Keystore + EncryptedSharedPreferences (or equivalent) for all API keys; never plain shared prefs. Keys loaded from settings, not hardcoded. `.env` template documents variable names only.
- Call shape: `DecisionProvider` interface (name, generate(), supportsVision). `ProviderRegistry` holds ordered providers. `RoundRobinKeyPool` per provider. JSON over HTTP; timeout; retry with backoff; cache last good decision; rule-based fallback (baseline-only tone, skip interrupt) when all providers down.
- Vision: when a screenshot frame is relevant, send it via the current provider's vision call (or separate vision call per provider shape). Keep v1 simple: one decision round, one call.
- Adding a provider = new `DecisionProvider` implementation + register + keys. No changes to Decision service or Action layer.

## Permissions (v1)

Request in this kind of order, with Settings-directed flows where needed:

1. UsageStats: `PACKAGE_USAGE_STATS` — special, redirected to Settings.
2. Draw-over: `SYSTEM_ALERT_WINDOW` — special, redirected to Settings (useful to request early even though the character is v2, because it gates the overlay path).
3. Sleep: Health Connect read permission (or Google Fit) — runtime/grant per the provider shape.
4. Screenshot capture: `MediaProjection` consent flow — one-time session consent, tied to the foreground capture service when the toggle is on.
5. Alarms: `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` as needed for exact repeating alarms on newer Android, plus `POST_NOTIFICATIONS` if the alarm or foreground service uses notifications.

Keep v1 to the permissions above. Do not add location, contacts, microphone, or other sensors unless a later feature genuinely needs them.

## Tooling

- Android Studio (or CLI builds if you prefer) with the chosen Compose/Kotlin toolchain.
- Git for version control (personal repo, no need for hosted remote unless you want one).
- Logs: in-app debug logging for observation/decision/action; no external logging service for v1.

## What we are NOT deciding in Phase 0

- Character animation style, voice, or rendering detail — v2.
- On-device model vs. API-only beyond "API-only for v1 with rule-based fallback."
- Multi-device sync, cloud profile, or any server component beyond the free-tier API calls.
- Guardrails beyond the opt-in toggle — deferred.

## Decision summary

- Kotlin + Jetpack Compose for in-app UI.
- Layered architecture with interfaces at observation/decision/action boundaries; DI kept light (manual or Hilt if you want standard).
- Room for history/baselines, DataStore for settings, files for frames.
- Foreground service for observation + capture loop; WorkManager for deferrable maintenance.
- Expandable multi-provider API registry with round-robin key rotation, keys in Keystore/EncryptedSharedPreferences, retry + rule-based fallback when all unavailable.
- v1 permissions: UsageStats, SYSTEM_ALERT_WINDOW, Health Connect/sleep read, MediaProjection (when capture on), alarm + notification permissions as needed.

Confirm or override any of the above, then we scaffold.
