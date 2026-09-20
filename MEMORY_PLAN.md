# Shiina Mobile — Memory & Learning Plan

Status: PLAN ONLY. Nothing below is implemented yet.
Goal: give her episodic memory (what happened), semantic memory (facts about
you), and adaptive behavior (what actually works on you) — all on-device in
Room, no server, no new permissions.

What exists today (verified 2026-09-20):
- Room v1: UsageEvent, SleepRecord, GoalEntry, BaselineSnapshot.
- BaselineUpdater: single EMA average for entertainment_minutes. This is the
  ONLY learning in the app — a running average, not memory.
- DecisionSummary/Gemini prompt: point-in-time telemetry only. Every decision
  round and every Talk reply is stateless; she cannot reference yesterday,
  notice streaks, or know what worked before.

Design principles:
- 1 feature = 1 file, 500 lines max per file, version bump per phase.
- Room migrations v1 -> v2 -> ... (never destructive).
- Retention caps on everything (memory must forget, too).
- All memory on-device, app-private. Add a "forget everything" path.

## Phase 1 — Episodic log (she remembers what she did)

New: data/db/MemoryEpisode.kt (entity + DAO in one file, small).
Log every decision round: timestamp, telemetry snapshot (minutes vs baseline,
goals open/done/missed), decision out (tone, interrupt, action, message hash —
not full text), trigger source (alarm render / talk / watcher).
Wire: ProviderRegistry.decide() writes the episode after generate(); fallback
writes too (source=fallback).
Accept: after Render, one row exists; PROGRESS updated; version bump.

## Phase 2 — Outcomes + real cooldown (she learns what landed)

New: outcome columns on MemoryEpisode (shown: bool, dismissed: bool,
acted: bool, talkedBack: bool) + observation/MemoryOutcomes.kt updater.
Signals, all already observable, no new permissions:
- dismissed: overlay HIDE within 60s of an interrupt show.
- talkedBack: Talk reply within 5 min of an interrupt.
- acted: recommended action executed (alarm set, goal logged, app closed).
Replace the prompt's honor-system cooldown with a real one: query last
interrupt time + dismissal before deciding.
Accept: dismiss her twice, third decision round logs cooldown suppression.

## Phase 3 — Semantic facts (she knows things about you)

New: data/db/MemoryFact.kt (entity + DAO): key, value, confidence 0..1,
source (stated / inferred / goal), updatedMillis. Cap ~200 rows, lowest
confidence evicted first.
Writes from two paths:
- Explicit: Talk "remember ..." command parses key=value directly (no API).
- Inferred: nightly Gemini pass over the day's episodes extracts at most 3
  facts (bedtime drift, problem apps, stated goals). Confidence starts 0.5,
  +0.1 per corroboration, cap 1.0.
New action verb LEARN_FACT so decisions can store facts (extends
ALLOWED_ACTIONS; executor writes to MemoryFact).
Accept: tell her "remember my bedtime is 11pm", next decision prompt contains
it; "forget everything" wipes both tables.

## Phase 4 — Recall into the prompt (memory becomes visible)

New: decision/MemoryContext.kt: builds a compact context block under a token
budget (~300 words): last 5 episodes (tone/interrupt/outcome), top 10 facts
by confidence, streak counters (consecutive over-baseline days, dismiss
streak). Query on Dispatchers.IO, never on main.
Wire: GeminiProvider.prompt() appends the block; Talk askGemini() gets last
6 chat turns (Phase 6 store) so conversations have continuity.
Accept: with 3+ days of episodes, her message references a real past event
("third night in a row past baseline") — verified in monitor log.

## Phase 5 — Nightly reflection (she adapts, rule-based first)

Wire the existing BaselineWorker stub (currently unimplemented) to run
memory/NightlyReflection.kt once daily:
- Per-tone effectiveness: acted+talkedBack rate per tone over 14 days.
- Adaptive threshold: personal interrupt bar drifts from 20% toward the level
  where you actually respond (bounded 10..40%).
- Best-nudge window: hour-of-day with highest acted rate.
- Bedtime learn: median screen-off time -> MemoryFact (bedtime, inferred).
- Prune: episodes older than 90 days, facts under 0.2 confidence unused 30d.
No ML library — ratios and medians only. On-device, charger-idle worker.
Accept: after simulated history, threshold differs from default 20% and prompt
shows the learned value.

## Phase 6 — Chat continuity + polish

- Talk history table (last 20 turns, pruned) so Talk remembers the thread.
- Settings UI: memory viewer (facts list, edit/delete), "forget everything".
- CharacterPanel: subtle "remembers N days" indicator (presence, not a dump).

## Order of build (1 by 1)

1, 2, 4 (episodes -> outcomes -> recall: first visible memory),
then 3 (facts), 5 (adaptation), 6 (polish).
Phase 4 needs Phase 1 data to matter; Phase 5 needs Phase 2 outcomes.
Each phase: spec -> implement -> build -> install on AUDUT20616012479 ->
verify in Shiina-only monitor -> PROGRESS.md + version bump.

## Explicitly out of scope

- Cloud sync / backup of memory (on-device only).
- Embeddings / vector search (overkill at this scale; key + recency queries
  suffice under ~500 rows).
- Cross-app content learning (only package names + times, never screen text;
  screenshots stay ephemeral vision input, never stored with memory rows).
