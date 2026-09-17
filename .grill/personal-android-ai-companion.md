# Grill: personal android ai companion

Date: 2026-09-16

## Intent

A personal Android AI companion app that observes your behavior, learns your patterns over weeks, and has a character that can draw over other apps, move around the screen, and interrupt based on what it sees. Designed for the user's own device, not for store publication.

## Constraints

- Android-first. iOS not in scope for v1.
- Personal use, not published — no Play Store viability requirement.
- Screenshot capture is optional, opt-in, with the understanding that sensitive content may not be reliably blanked on all devices/Android versions.
- Personality is derived from the user's activity patterns relative to their own baseline, not from tracking the user's preference for grumpy vs. cute.
- Character UI (draw-over, movement, animation) is v2 — v1 builds the observation/action backend first.
- Free-tier external APIs acceptable for vision + reasoning in v1.

## Key decisions

- Decision: three personality/observation dimensions. Reason: map directly to the triggers described (movie binge, late sleep, unfinished goals), observable on Android without a museum of integrations. Dimensions: entertainment/screen drift, sleep timing/regularity, goal fulfillment.
- Decision: screen time read uses both an explicit user cap AND a learned 7-day baseline on the same axis. Reason: covers both "this is over my stated limit" and "I'm drifting above my own norm even without a cap."
- Decision: backend/observation/action layer built first; character UI built second. Reason: the character depends on the backend's decisions, and the character is the slower piece to develop.
- Decision: personality learning is a rolling-baseline snapshot ("where am I relative to my own norm?"), not a reaction-preference learner. Reason: explainable, avoids engagement trap, simpler v1. If the user wants tone preference learning later, that's a separate feature requiring reaction data.
- Decision: app executes local actions itself (set alarm, toggle capture, log goal); external API is used for reasoning/vision only. Reason: keeps the app dependent on external APIs only for decisions, not for device control, and lets the user act even if the API is down.
- Decision: screenshot frames stored locally in v1; guardrails (blacklist, auto-delete, view/delete history) deferred. Reason: personal use, user explicitly opted in and accepted the tradeoff.

## Surfaced assumptions

- `MediaProjection` / `VirtualDisplay` capture will blank sensitive/FLAG_SECURE windows sufficiently for the user's devices. Confirmed as an assumption, not a tested guarantee — a real risk on some OEM/Android versions.
- Free-tier API rate limits and monthly caps are acceptable for a 20-minute capture-and-process cadence, based on the user's own test.
- UsageStatsManager + `PACKAGE_USAGE_STATS` special permission is sufficient to distinguish "watching movies" from other app use once a local category map is added.
- Health Connect (or Google Fit) provides sleep timing data usable for the sleep axis.
- The user is comfortable with a personal device app that continuously captures behavior and may process screenshots on free external APIs.

## Open questions

- What is the exact local category map v1 uses to split "entertainment" from other app use?
- Are frames processed live by the API on each capture, or batched/summarized locally first and only sent when there's a decision to make?
- For the alarm action: is it a simple in-app alarm using `AlarmManager`/`setExact`, or does it need to integrate with the system clock/alarm app behavior (repeat, sound, dismiss)?
- Goal input mechanism in v1: free text to the character, in-app list, or something else?
- Long-term memory summary shape: what exactly gets persisted locally and what gets sent to the API each decision round.
- Whether the app needs a foreground service / persistent notification to keep observation and character alive across screen-off / background states.

## Out of scope

- iOS version.
- Public/store release.
- Reaction-based personality preference learning (v1 uses pattern-based baseline).
- Guardrails beyond the basic opt-in toggle in v1 (blacklist, history UI, auto-delete are deferred).
- Bug-hunting or polish-focused iteration before the core loop works.
