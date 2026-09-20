# Dynamic Mood Balancing Plan

Note: this file was missing from the repo; authored 2026-09-20 as the working
design, including a review of the pre-existing mood setup and the improvements
that fix it.

## Review of the old setup (what was wrong)

1. Mood = last decision tone. One Gemini round overwrote her entire emotional
   state; no continuity between rounds, no memory of how she felt.
2. Discrete labels only (calm/candid/firm/warm/pouty). No intensity, no
   blending, no decay — she flips 0-to-100 and never cools down.
3. Only one real-world driver besides tone: 10h neglect -> pouty. Dismissals,
   replies, praise, complaints, late nights, music — none of it moved her.
4. Model expression was absolute: when Gemini said "mood: pouty" it replaced
   state instead of nudging it, so one flaky parse could hijack her voice.
5. Nothing persisted across process death.

## Design (implemented below, one step per section)

### Step 1 — MoodState: persistent valence-arousal state (Room v9)
Single-row `mood_state` table: valence (-1..1), arousal (0..1), derived mood,
intensity (0..1), cause, timestamp. Emotions become a continuous coordinate
that survives restarts — the psychology-standard V-A model instead of labels.

### Step 2 — MoodEngine: events + decay, zero API cost
Pure local math. Real events nudge the vector:
  USER_TALK (+v), DISMISSED (-v), TALKED_BACK (+v), ACTED (+v),
  NEGLECT (-v,+a), PRAISE (+v), COMPLAINT (-v), MUSIC (+v),
  LATE_NIGHT (-v,+a), LOW_BATTERY (+a).
Every update first decays toward baseline (0.10, 0.25) exponentially with a
6-hour half-life — real emotions fade unless reinforced.

### Step 3 — Derived discrete moods (2 new: excited, melancholy)
Nearest-anchor mapping over the V-A plane; intensity = (|v| + a)/2. Below the
calm floor (0.18) she is simply calm. Anchors: calm, warm, candid, firm,
pouty, excited, melancholy.

### Step 4 — Balanced model expression
Gemini choosing a mood no longer overwrites state; it nudges the vector 35%
toward that mood's anchor (applyModelExpression). Her voice is a blend of
what the model wants to express and what actually happened to her.

### Step 5 — Prompt integration with intensity scaling
Every decision and Talk prompt gets an EMOTIONAL STATE block: mood, intensity
percent, cause, and the instruction to express at that strength ("never state
the mood explicitly; below 25% stay essentially neutral"). PROMPT_VERSION v6.0.

### Step 6 — Wiring + feedback loop
MemoryOutcomes feeds DISMISSED/TALKED_BACK/ACTED into the engine; the debug
Talk loop scans user text for praise/complaint keywords; decision rounds apply
model expression after each round; DECISION_LOG carries the live mood; overlay
gains colors for excited/melancholy.

## Acceptance criteria
- Build passes; Room 8->9 migration clean on device.
- logcat MOOD lines show vector updates on talk/dismiss/action events.
- Repeated dismissals visibly drag valence negative (pouty/melancholy derive).
- Ignored for hours -> decay back toward calm, not stuck.