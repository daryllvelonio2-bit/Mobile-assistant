# Build Plan: Personal Android AI Companion

Status: v1 design grilled and converged. Tech stack to be decided in Phase 0.
Owner: personal use, single device, not for store publication.

## Vision (one paragraph)

An Android app for one person that observes behavior through usage stats, sleep, and goals; accumulates a weekly baseline; and runs a character that can draw over other apps, move around the screen, and interrupt when screen-drift, sleep, or goal patterns cross the user's own norms. Optional screenshot capture gives the character a deeper view, processed through free-tier vision/reasoning APIs. The character's tone is a snapshot of the user's state relative to their own norms, not a learner of the user's preferred tone.

## What v1 delivers

- Observation layer: app usage categories/time, sleep timing, goal set/done/miss.
- Local memory: rolling 7-day baseline per dimension, persisted on device.
- Decision layer: each decision round produces a personality tone + whether to interrupt + what action to take.
- Local actions: set a recurring alarm (e.g. 7pm), toggle screenshot capture on/off, log a goal.
- Optional screenshot capture with an opt-in toggle; frames stored locally in v1.
- Character UI deferred to v2 — v1 backend works without it.

## Phase 0 — Decide the stack (short, before coding)

Deliverables:
- Choose Android stack: language (Kotlin or Java), UI toolkit for the in-app screens (Views, Jetpack Compose, or 혼합), architecture pattern (MVVM/MVI), DI approach.
- Choose persistence: Room, DataStore, plain files, or mix.
- Choose background/observation model: foreground service, WorkManager, scheduled jobs, or mix for usage polling, health reads, and optional screenshot loop.
- Choose API layer for free-tier model calls: which provider(s), how auth/keys are stored, how retries/fallbacks work, how the app behaves when the API is unavailable.
- Choose vision/OCR approach if screenshots are processed: send frames to API, on-device preprocessing, or both.
- Freeze the list of v1 permissions the app will request and in what order.

Output: a one-page stack decision doc plus a permission list.

## Phase 1 — Project scaffold + permissions

Deliverables:
- New Android project scaffolded with the chosen stack.
- Minimal in-app settings screen (later: settings for screenshot toggle, goals, alarm).
- Permission request flow for: UsageStats (special, Settings-directed), draw-over-other-apps (special, Settings-directed), optional Health Connect / sleep read, optional MediaProjection-based capture consent flow.
- Empty local memory schema ready for usage, sleep, goal, and reaction-less trend records.

Acceptance:
- App can request and receive the special permissions via Settings.
- App can open a basic settings screen and persist a setting.

## Phase 2 — Observation backend

Deliverables:
- Usage reader: app usage grouped into at least "entertainment" vs "other" via a local category map; daily rolling totals; 7-day baseline.
- Sleep reader: bed/wake timing from Health Connect or Google Fit, stored locally; baseline.
- Goal store: create, complete, miss, list; daily and recent history.
- Baseline updater: rolling average per dimension, with a simple size and decay policy.
- Screenshot capture loop (when enabled): periodic capture with the chosen capture mechanism; frames written to local storage.

Acceptance:
- App can produce, for a decision round, a summary containing today's entertainment hours, sleep timing, and goal status, plus the baseline for each.

## Phase 3 — Decision + action layer

Deliverables:
- Decision service: takes the summary + baseline, calls the free-tier API for reasoning, returns a tone + interrupt decision + action.
- Local action executor:
  - Set a recurring alarm (7pm example) via the chosen Android alarm mechanism.
  - Toggle screenshot capture on/off.
  - Log / complete / miss a goal.
- Offline behavior: when the API is unavailable or rate-limited, define v1 fallback (e.g., simple rule-based tone from baseline alone, or skip interrupt).

Acceptance:
- From a real summary the app can schedule a 7pm alarm and store it.
- From a real summary the app can decide to mute or interrupt and execute the chosen action.

## Phase 4 — Character skin v2 (after backend works)

Deliverables:
- Draw-over view layered over other apps with SYSTEM_ALERT_WINDOW.
- Movement/animation modes: vanish, stay, move around.
- Character consumes the decision service output (tone + interrupt + action) and renders accordingly.

Acceptance:
- Character appears over other apps according to the decision service output.
- Movement modes behave as designed.

## Big technical risks (call out now, not later)

- Screenshot blanking is not guaranteed across OEM/Android versions for FLAG_SECURE content. Personal use reduces external exposure but not the on-device reality; the screen-capture path should be verified on the target device before trusting it as a "sensitive stays blank" assumption.
- Special permissions (UsageStats, draw-over, MediaProjection) are friction — the app will need a Settings-directed grant flow and a clear user explanation for each.
- Background observation can be constrained by Doze / app standby / OEM battery policy; the persistence model (foreground service vs periodic jobs) affects whether observation stays alive when the user is not actively in the app.
- Free-tier API limits and model behavior should be validated against the intended cadence and context length, especially once memory summaries grow.
- Local categorization of "entertainment apps" is manual to start and will need maintenance as apps change.

## Build order rationale

Backend first because the character is a consumer of the decision output; building the character before the decision layer produces a pretty thing that has nothing to react to. Observation before decision because decision depends on what is observed. Action layer alongside decision because the app must do something with a decision (alarm, toggle, goal) to be useful. Character last because it is the slowest and least essential piece for v1.

## Definition of done for v1

- App can read usage/sleep/goals and maintain a rolling baseline.
- App can decide, from a summary, whether to interrupt and with what tone.
- App can execute at least one local action (set a recurring alarm) from a decision.
- Screenshot capture toggle exists and works when enabled, with frames stored locally.
- Character UI may be stubbed or absent — v1 backend is the deliverable, character is v2.

## Out of scope for v1

- Character UI polish.
- Reaction-based tone preference learning.
- History UI for screenshots.
- App blacklist / auto-delete / privacy guardrails beyond the opt-in toggle.
- Multi-device, sync, or cloud profile portability.
- Store publication, review, or public-facing privacy posture.

## Next step after this plan

Decide the stack in Phase 0, then scaffold the project. The plan above is stack-agnostic by design; the stack decision unlocks which concrete libraries, services, and permission flows get wired in.
