 # Shiina Abilities + Loop + Logic — Full Plan (2026-09-20)

 Goal: turn her from "chatbot with two tools" into an agent that can do
 multi-step tasks reliably. Three tracks: A) abilities, B) the loop,
 C) the logic (decision quality). Ordered by dependency — each phase is
 shippable alone, 1 feature = 1 file, bump per phase.

 ## Current state (ground truth)

 Talk loop: keyword fast-path -> round 1 JSON tool protocol -> harness runs
 ONE tool -> retry only for empty SEARCH_WEB (max 2) -> final answer.
 Tools: SEARCH_WEB (instant+lite), TAKE_SCREENSHOT, memory R/W, time.
 Render loop: decide (JSON: tone/action/message) -> executeDecision ->
 overlay. Actions: NONE/SET_ALARM/TOGGLE_SCREENSHOT/LOG_GOAL/LEARN_FACT/
 SEARCH_WEB/TAKE_SCREENSHOT. Trigger: notification Render, 7PM alarm,
 observation sampling. No multi-step: one decision = one action, no
 follow-through, no task state, no success check.

 ## Track A — Abilities (new tools, on-device first, no new permissions)

 A1. Device senses (read-only, all free APIs) — one file `action/Senses.kt`:
      exact time/date, battery level+charging, current foreground app
      (UsageStats, already granted), music playing (AudioManager), screen
      on/off, orientation. Expose as compact JSON block appended to every
      prompt ("SENSES: ..."). Why first: every later tool decision needs it,
      zero risk.
 A2. Page reader — `action/PageReader.kt`: SEARCH_WEB returns snippets only;
      add READ_URL that fetches a result link and extracts article text
      (strip tags, 2000 chars). Search -> read -> answer with sources.
 A3. Reminders that fire — `action/Reminders.kt`: Room table
      (id, text, triggerAtMillis, fired). SET_REMINDER schedules exact alarm;
      firing posts overlay + notification. Her first action that reaches into
      the future. Needs one new Room entity + alarm path reuse.
 A4. Goal follow-through — extend `action/ActionExecutor.kt`: CHECK_GOALS
      reads goal table + memory outcomes, reports open/stalled goals. Turns
      LOG_GOAL (write-only today) into something she can review with you.
 A5. On-device routine checks — `action/RoutineChecks.kt`: missed-call /
      unread-SMS counts via content providers (needs READ_* runtime consent
      in MainActivity — first runtime permission ask, keep it optional and
      off by default). Defer if permission UX feels heavy; A1-A4 need none.
 OUT OF SCOPE: voice, messaging/calling out, smart home, arbitrary browsing.

 ## Track B — The Loop (from one-shot to task loop)

 B1. Multi-step Talk loop (the core fix): replace single-shot harness with
      `debug/TalkLoop.kt`: while-loop, max 4 tool calls per turn:
      model JSON -> harness executes -> result appended to transcript ->
      model decides next (another tool or NONE+answer). Stops on NONE,
      on 4 calls, or on repeated identical calls (break + answer with what
      exists). Every tool result logged to AppDebugServer so the monitor
      shows the chain. This is what "cant do tasks" means today — she gets
      one move; tasks need 2-4 (search -> read -> remember -> answer).
 B2. Same loop for Render: `decision/RenderLoop.kt` — decisions return a
      LIST of 1-3 actions executed in order (e.g. TAKE_SCREENSHOT then
      SEARCH_WEB then SPEAK), instead of one verb. Reuse TalkLoop's
      executor; keep overlay render at the end.
 B3. Task state: `decision/TaskRecord.kt` (in-memory + episode row): goal,
      steps taken, tool results, done/failed. Survives across the 4 calls
      of one turn; summarized into the episode at the end so tomorrow she
      remembers what she did, not just what she said.
 B4. Failure honesty: when tools return empty twice, she says what she
      tried ("searched X and Y, both empty") instead of generic filler.
      Already half-done in v0.31 retry text — extend to the full chain.

 ## Track C — Logic (decision quality, fewer wasted loops)

 C1. Tool-choice grounding in TOOL_SPEC: add WHEN/NOT rules per tool
      (SEARCH only for non-on-device info; SCREENSHOT only when the answer
      needs eyes; REMINDER only with explicit time; max 1 screenshot/turn).
      Stops the "search for what time is it" class of waste.
 C2. Confidence + silence: decision JSON gains "confidence" 0-1; Render
      below threshold -> STAY silent instead of low-value chatter. Pairs
      with the v0.26 silence permission — now with a number behind it.
 C3. Self-check round: after the final answer is composed (long tasks only,
      3+ tool calls), one short model call: "does this answer the owner's
      ask using the tool results? yes/no + fix". Cheap, catches the
      hallucinated-answer-off-empty-results failure mode.
 C4. Learned thresholds feed the loop: NightlyReflection already learns a
      candid threshold — extend to per-tool success rates (search empty
      rate, screenshot unavailable rate) stored as facts; TOOL_SPEC
      includes "search has been failing lately, prefer memory" style hints.
      The loop gets smarter weekly without code changes.

 ## Build order (each = build + install + monitor verify + PROGRESS line)

  1. A1 Senses (small, unlocks everything)
  2. B1 TalkLoop multi-step (the headline fix)
  3. A2 PageReader (makes search genuinely useful)
  4. C1 tool-choice rules (stops waste from B1's extra freedom)
  5. B2 Render action lists + B3 task state (Render catches up to Talk)
  6. A3 Reminders (first future-reaching action)
  7. C2 confidence/silence + C3 self-check (quality gates)
  8. A4 goal review + C4 learned tool rates (compounding)
  9. A5 routine checks (only if runtime-permission UX approved)

 ## Test script (run on device after each phase)

  - "what time is it" -> answered from SENSES, no tool call.
  - "peso to dollar" -> SEARCH -> answer with numbers, no retry needed.
  - "find X and remember it" -> SEARCH -> LEARN_FACT -> confirms both.
  - "remind me at 8pm to ..." -> scheduled; fires overlay at 8pm.
  - nonsense ask with tools failing -> honest "tried X, empty" reply.
  - monitor shows tool chains, never raw JSON to user, zero FATALs.
