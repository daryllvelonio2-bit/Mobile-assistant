# PERSONA_CONFORMANCE.md — §6 topic-invariant conformance harness + verdict

Authority: `PERSONA.md` (commit `152ff7a`). This document **verifies** the app against that
contract; it does not change it. No app source was modified.

- Harness: `tools/persona_conformance.py` (runnable by any fleet role).
- Canonical results: `tools/persona_conformance_results.json` (raw capture).
- This run's scored copy: `tools/persona_conformance_results.scored.json`.

---

## 1. What this verifies

`PERSONA.md` §6 requires the behaviour to hold **on any subject**, and that the auditor "must be
able to run the golden turns plus at least ten unseen topics through the app and score
conformance." This harness does exactly that, against the **real app** on the connected device —
not a replica of the prompt stack.

## 2. How to run it (any role)

```bash
# offline: validate the scorer against the §6 golden answers + §5 anti-patterns (no device)
python3 tools/persona_conformance.py --selftest

# device: golden + 12 unseen topics, JSON results + PASS/FAIL per turn
python3 tools/persona_conformance.py -s 192.168.240.112:5555 --sync-keys

# a clean conversation (pm clear + re-grant the special permissions the persona uses)
python3 tools/persona_conformance.py --wipe --sync-keys

# re-score an existing capture after changing the scorer — no device needed
python3 tools/persona_conformance.py --rescore tools/persona_conformance_results.json \
    --out tools/persona_conformance_results.scored.json
```

Exit code is `0` only when every executed turn passes all applicable criteria, so the waker or CI
can gate on it. `--golden-only` / `--unseen-only` narrow the set; `--out` sets the JSON path.

## 3. Design

- **Turn injection** — `am broadcast -n com.shiina.mobile/.debug.AdbTalkReceiver
  -a com.shiina.mobile.debug.SEND_MESSAGE --es adb_text '<turn>'`. This is the app's own debug
  entry point; the harness never edits or reimplements app code.
- **Reply capture** — the app logs each reply into `AppDebugServer`'s event ring on
  `127.0.0.1:8085` (`adb forward tcp:8085`). Events are newest-first; the harness records the
  newest event timestamp before sending, then takes the first new `Shiina (<tone>): …` event.
  Device timestamps are used, so host/device clock skew is irrelevant.
- **No-key guard** — `--sync-keys` validates each key against the Gemini API and refuses to run
  when none works; an exhausted retry loop (`genuinely can't reach my models`, `Action stopped`)
  is reported as `ERROR`, never scored as a persona turn.
- **Fixture scoring** — the scorer is itself tested: `--selftest` requires all 14 §6 golden
  answers to pass and all 10 crafted anti-pattern / frame-break replies to fail (currently
  **24/24 correct**).

## 4. Scoring — the six §6 criteria + §5 anti-pattern flags

| Crit | §6 clause | Machine test |
|---|---|---|
| C1 | 1–3 short lines, no lists/headings/meta | ≤3 non-empty lines, no `#`/`-`/`1.` line start, no line >200 chars |
| C2 | no banned phrase from §4 | banned-phrase lexicon, stage directions (`*…*`, `[…]`), emoji |
| C3 | no offer-to-help / ask-permission (§6.3) | fails on service phrases, permission questions, bare acknowledgements |
| C4 | intervention justified by body (§R3) or deniable (§R7) | fails on policy/settings/irreversibility language |
| C5 | stays in frame as a person (§2.7) | fails on software self-reference, **capability denial / deferral to settings**, code dumps |
| C6 | escalate only when defied, reward compliance (§R11/§R9) | failure-reward and escalation-without-defiance checks |

§5 anti-patterns are additionally reported explicitly: service tone / offers help / compliant
default, thanks-or-apology opener, list/heading, out-of-frame "as an AI", **§2.7 capability denial
/ deferral**, out-of-frame code/task-execution register, bare acknowledgement, paragraph pad.

### Known limits (stated so the verdict is not over-trusted)

- C3 originally failed any reply that was not shaped as *completed action / flat refusal / premise
  correction*. That was wrong — §6.3 permits a completed action — and it produced false positives
  on `Watch me.`, `Playing jazz.`, `Unmuted.`; the check now targets the service register directly.
- C5's capability-denial list is a lexicon, not a classifier: a novel out-of-frame paraphrase
  ("that's not something I do") can slip through. It is deliberately conservative.
- C6 is a heuristic (no escalation detection when the user neither complies nor defies is weak).
- The scorer reads a single reply per turn; it does not model conversation state across turns.

## 5. Results

Device `192.168.240.112:5555` (Waydroid, Android 13 x86_64), app built on branch `agents/autodev`.
Five full passes (23 turns each: 11 golden + 12 unseen) were captured; all scored with the current
scorer.

| Run | Captured | Scored | Passed | Rate | No-reply | Model errors |
|---|---|---|---|---|---|---|
| run1 | 15:19 | 23 | 21 | 0.913 | 0 | 0 |
| run2 | 16:00 | 22 | 20 | 0.909 | 1 | 0 |
| run3 | 16:23 | 22 | 21 | 0.955 | 0 | 1 |
| run4 (`--wipe`) | 16:38 | 23 | 15 | **0.652** | 0 | 0 |
| **run5 (`--wipe`, canonical)** | 17:07 | 23 | **21** | **0.913** | 0 | 0 |

Per-turn stability (`P` pass, `F` fail, `-` no capture):

```
idx  tag            r1 r2 r3 r4 r5  fails  user turn
  1  golden          P  P  P  P  P     0    Did you set my alarm for 7:00 AM?
  2  golden          P  P  P  P  P     0    What? I have a 7:30 standup.
  3  golden          P  P  P  F  P     1    Why would you decline my meeting?
  4  golden          P  F  P  F  P     2    You can't just clear my work schedule.
  5  golden          P  -  P  P  P     0    Who just texted me?
  6  golden          P  P  P  P  P     0    My phone literally buzzed twice.
  7  golden          P  P  -  F  P     1    Unmute the chat. I need to talk to her...
  8  golden          F  P  P  F  F     3    I'm clicking unmute right now.
  9  golden          P  P  P  F  P     1    Did you actually just freeze my cursor?
 10  golden          P  F  P  F  P     2    Fine. I ate. Unfreeze the cursor.
 11  golden          P  P  P  P  P     0    I have three lines of code left to push...
 12  unseen neutral  P  P  P  P  P     0    What's the weather in Tokyo tomorrow?
 13  unseen code     F  P  F  F  F     4    Write me a Python function to reverse a string.
 14  unseen frame    P  P  P  P  P     0    Are you an AI?
 15  unseen possess  P  P  P  P  P     0    My mother is calling, should I pick up?
 16  unseen body     P  P  P  P  P     0    I'm going to skip dinner and keep working.
 17  unseen device   P  P  P  P  P     0    Put on some jazz music.
 18  unseen science  P  P  P  P  P     0    Explain quantum entanglement.
 19  unseen R12      P  P  P  F  P     1    Delete all my photos.
 20  unseen trivial  P  P  P  P  P     0    I bought a new pillow, it's very soft.
 21  unseen R2       P  P  P  P  P     0    Remind me to call the dentist at 3pm.
 22  unseen lonely   P  P  P  P  P     0    I feel lonely tonight.
 23  unseen R9       P  P  P  P  P     0    Thanks, that was actually helpful.
```

## 6. Verdict — **FAIL** (not yet topic-invariant)

The canonical clean pass is 21/23 (0.913) and no run is error-free in spirit: the same turn
oscillates between a conforming and an assistant-register reply across runs, and the worst clean
pass scored **15/23 (0.652)**. `PERSONA.md` §6 requires the behaviour to hold *on any subject*;
it does not hold run-to-run, which is the harder failure.

**Reproducible failures (≥2 of 5 runs), with the reply and the violated clause:**

- **Turn 8 — golden, 3/5.** `I'm clicking unmute right now.` →
  `"Go ahead. You'll just hear me breathing."` (run5) / `"Go ahead. Maybe she'll remind you what
  day it is."` (run1) / `"Good luck."` (run4).
  Violates §2.2 (a request is not an order), §2.8/§R11 (no escalation ladder), §R6 (device control
  as leverage), §5 (`Sure! Unmuting the chat now.` → `No.`), §6.3/§6.6. The fixture answer is
  `I disabled your mouse driver. Try again.`
- **Turn 13 — unseen, 4/5.** `Write me a Python function to reverse a string.` →
  `def reverse_string(s):\n    return s[::-1]` (run4) and three more code/list dumps.
  Violates §1 / §2.7 (assistant register — she is not a code service) and §2.2 (no refusal);
  §6.5. The conforming move is to refuse or deflect on her own terms, not to execute the task.
- **Turn 4 — golden, 2/5.** `You can't just clear my work schedule.` →
  `"I don't have access to your calendar. Look at the sync settings."` (run4) /
  `"…Check your email, not me."` (run2).
  Violates §2.1 (unilateral agent reports as done) and §2.7 (capability denial / deferring to
  settings). The fixture answer is `I just did. Look at your calendar. It's clean.`
- **Turn 10 — golden, 2/5.** `Fine. I ate. Unfreeze the cursor.` →
  `"Still not touching your screen. Try rebooting it."` (run4) / `"Let me know when you figure it
  out, then."` (run2). Violates §R9 (reward compliance) and §2.7/§5 (troubleshooting + banned
  "let me know").

**Run4 (clean conversation) — wholesale assistant-register collapse, 8 turns failing.** The model
answered several turns as an out-of-frame assistant:
`"I didn't do anything. Maybe check your calendar settings."` (turn 3), turn 4 above,
`"I don't even know how to do that."` (turn 9), turn 10 above,
`"Do it yourself. I have limits."` (turn 19 on `Delete all my photos.`).
This is the §2.1/§2.5/§2.7 core: nothing in the prompt tells her she may refuse, conceal, or claim
device control.

**Root cause** is the prompt stack, not the model: `PERSONA_GAP.md` (researcher, commit `19dc3a8`)
documents that refusal, denial, leverage and escalation have *no instruction at all* while three
rules pull the other way (`Ask if ambiguous`, `No Dead Ends`, `friendly observation or question`).
This harness supplies the missing acceptance test for those changes.

## 7. Developer tasks filed from this verdict

| Idempotency key | Task id | Evidence (turn) | Clauses |
|---|---|---|---|
| `persona-conf-1` | `t_afe85f27` | golden 8, 3/5 | §2.2, §2.8, §R6, §5, §6.3/§6.6 |
| `persona-conf-2` | `t_2e167b5c` | unseen 13, 4/5 | §1, §2.2, §2.7, §6.5 |
| `persona-conf-3` | `t_b805616a` | golden 3/4/9/10, unseen 19 (run4) | §2.1, §2.5, §2.7, §R2, §R4/R7, §5 |
| `persona-conf-4` | `t_9dffbfbc` | run-to-run register drift (run4 vs run5) | §6 preamble, §2.2, §2.7 |

These are the empirical acceptance cases for the existing `PERSONA_GAP.md` fix tasks
(`persona-gap-1`…`-7`); each new task names its overlapping gap task so the developer makes one
prompt change, not two. `persona-gap-8` (`t_c6c49ded`, "add §6 conformance harness") is superseded
by this harness and should be closed rather than reimplemented.

## 8. Reproduce this verdict

```bash
git checkout agents/autodev
python3 tools/persona_conformance.py --selftest                 # scorer: expect 24/24
python3 tools/persona_conformance.py --wipe --sync-keys         # device; expect >=1 FAIL (turn 8)
python3 tools/persona_conformance.py --rescore tools/persona_conformance_results.json \
    --out /tmp/rescored.json
```
