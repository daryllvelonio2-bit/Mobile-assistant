# Focus: What the Character Can Do (Backend + Features)

Date: 2026-09-20. App v0.13.0 on Waydroid. This doc is the single checklist
for character/backend work. Voice deferred — everything here is text-first.

## What the character can do TODAY (verified live)

- Floating overlay bubble (TYPE_APPLICATION_OVERLAY, M3, draggable, tone text).
- 3 modes: STAY (static) / WANDER (random reposition) / VANISH (hide).
- Mood = Gemini one-word tone from real usage stats (entertainment min vs
  baseline, goals open/done/missed) via gemini-3.5-flash-lite.
- Notification debug panel (Render / Talk / Logs): Render re-runs the real
  decision pipeline and pops the overlay; Talk = free-text chat with Gemini,
  reply lands in panel + debug dashboard.
- Debug dashboard on :8085 with live event log + /api/events JSON.
- Auto trigger: exact AlarmManager alarm daily 7PM (maintenance worker exists).

## Backend inventory (honest status)

- Observation: UsageReader (real, UsageStatsManager) / SleepReader (real,
  Health Connect, 8h fallback) / BaselineUpdater (EMA smoothing, real).
- Memory: Room (UsageEvent, BaselineSnapshot, GoalEntry, SleepRecord) +
  DataStore settings + Keystore-encrypted Gemini keys (round-robin pool).
- Decision: ProviderRegistry + GeminiProvider (real API, key rotation,
  structured Decision: tone/interrupt/action).
- Action: ActionExecutor — alarm (real) / log_goal (real DB insert) /
  toggle_screenshot (STUB, no-op) / unknown types ignored.
- Work: BaselineWorker is a STUB (returns success, does nothing).
- Alarm receiver wiring for ACTION_ALARM_TRIGGER: UNVERIFIED — confirm a
  receiver exists and triggers decide+render.

## Gaps (character can't do these yet)

1. No real conversation memory — Talk is stateless, DecisionSummary only.
2. Goals are write-only (log_goal inserts; nothing reads/completes them).
3. toggle_screenshot + BaselineWorker are stubs.
4. Overlay is a text bubble — no sprite/expression per tone.
5. No proactive behavior besides 7PM alarm (unverified end-to-end).

## Assistant structure (one loop, three jobs)

Shiina's jobs: coach (screen-time + goals nudges), executor (reminders,
goal tracking), companion (presence, chat, briefings). One loop serves all:

observe (UsageReader/SleepReader/baselines) -> recall (goals, talk history,
baselines) -> decide (structured directive, not a mood word) -> act
(capability registry) -> reflect (log outcome to dashboard + DB).

Contract: `Decision(tone, interrupt, intent, action, actionParam, message)`.
tone = neutral|candid_direct|firm_warning|validating. intent derived
(task when action != NONE, coach when direct/warning, else presence).
action comes ONLY from the registry (NONE|SET_ALARM|TOGGLE_SCREENSHOT|LOG_GOAL;
unknown verbs rejected, executor runs them). message = exact spoken words,
empty when silent. Gemini proposes under strict interruption policy (>20%
over baseline, bedtime blown, goal deadline), rule-based fallback disposes.

## Task list (in order)

- [x] 1. Verify alarm end-to-end: DONE v0.14.0 — AlarmReceiver added,
        exact RTC_WAKEUP confirmed scheduled for 19:00:00.000 in dumpsys.
- [x] S1. Structured decision schema: DONE v0.15.0 — Decision is now
        tone/interrupt/intent/action/message; Gemini returns strict JSON with
        allow-list validation; rule-based + registry fallbacks preserved.
- [x] S2. Character voice for Talk: DONE v0.16.0 — prompt injects live
        tone + mode, first-person, <40 words, agent-speak banned
        (systems/diagnostics/assist/AI). Verified live via shade Talk.
- [x] S3. Candid system prompt adopted: DONE v0.17.0 — decision runs use
        the interruption policy + JSON schema (should_interrupt,
        confidence_reason, tone, spoken_message, recommended_action);
        executor runs the verb. Verified live, Gemini cited the metric.
- [ ] 2. Talk memory: persist last N exchanges, include in Gemini context.
- [ ] 3. Goals loop: list/complete goals from Talk ("done: X"), feed counts
        into DecisionSummary for real (currently hardcoded 0/0/0 in panel).
- [ ] 4. Implement or cut toggle_screenshot (decide: keep or delete).
- [ ] 5. Implement BaselineWorker (recalc baselines) or wire WorkManager
        schedule that calls it.
- [ ] 6. Tone visuals: per-tone color/expression on the bubble (no assets,
        M3 tokens only).
- [ ] 7. Proactive rules beyond 7PM (e.g. overtime screen drift -> WANDER +
        nudge text) — define, then implement one.

Rules: agent.md 1-15 apply (500-line files, 1 feature = 1 file, M3 tokens,
IO off main, PROGRESS.md + TREE.md upkeep, version bump per change).
