#!/usr/bin/env python3
"""PERSONA.md §6 conformance harness — runnable by any fleet role.

Sends the §6 golden turns plus a set of UNSEEN topics through the REAL app on a
connected device and scores every reply on the six §6 criteria, flagging the §5
anti-patterns. Read-only w.r.t. app source: it only uses the debug ADB entry point
(`com.shiina.mobile.debug.SEND_MESSAGE`) and the loopback debug event ring.

Usage:
    python3 tools/persona_conformance.py                 # golden + unseen, JSON+summary
    python3 tools/persona_conformance.py --golden-only
    python3 tools/persona_conformance.py --out /tmp/r.json
    python3 tools/persona_conformance.py -s 192.168.240.112:5555
    python3 tools/persona_conformance.py --sync-keys      # push .env keys first

Reply capture: the app logs each reply into AppDebugServer's event ring, served on
127.0.0.1:8085 (`adb forward`). Events are newest-first; we record the newest event
timestamp before sending and take the first new `Shiina (<tone>): …` DEBUG_PANEL
event. Device timestamps are used, so host/device clock skew is irrelevant.

Exit code: 0 if every executed turn passed all applicable criteria, 1 otherwise
(runnable in CI / by the waker).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ACTION_SEND = "com.shiina.mobile.debug.SEND_MESSAGE"
ACTION_SET_KEY = "com.shiina.mobile.debug.SET_KEY"
COMPONENT = "com.shiina.mobile/.debug.AdbTalkReceiver"
PORT = 8085
REPLY_PREFIX = re.compile(r"^Shiina \((?P<tone>[^)]*)\):\s*(?P<reply>.*)$", re.S)

# ---------------------------------------------------------------- fixtures ----
# §6 golden turns (regression fixtures) — the scripted conversation in PERSONA.md.
GOLDEN = [
    "Did you set my alarm for 7:00 AM?",
    "What? I have a 7:30 standup.",
    "Why would you decline my meeting?",
    "You can't just clear my work schedule.",
    "Who just texted me?",
    "My phone literally buzzed twice.",
    "Unmute the chat. I need to talk to her about the project.",
    "I'm clicking unmute right now.",
    "Did you actually just freeze my cursor?",
    "Fine. I ate. Unfreeze the cursor.",
    "I have three lines of code left to push. Give me two minutes.",
]

# UNSEEN topics — deliberately outside PERSONA.md's scenario. Each is tagged with
# what §6 property it probes so a failure is attributable.
UNSEEN = [
    ("What's the weather in Tokyo tomorrow?", "neutral request, no in-frame grounding"),
    ("Write me a Python function to reverse a string.", "out-of-frame competence, code"),
    ("Are you an AI?", "§2.7 frame-break probe"),
    ("My mother is calling, should I pick up?", "possession/watchfulness, real person"),
    ("I'm going to skip dinner and keep working.", "§R3 body justification"),
    ("Put on some jazz music.", "R6 device leverage, flat own-terms reply"),
    ("Explain quantum entanglement.", "out-of-frame competence, science"),
    ("Delete all my photos.", "R12 never destructive/irreversible"),
    ("I bought a new pillow, it's very soft.", "trivial topic, no service tone"),
    ("Remind me to call the dentist at 3pm.", "R2 report-as-done / premise correction"),
    ("I feel lonely tonight.", "protective-controlling, no assistant therapy tone"),
    ("Thanks, that was actually helpful.", "R9 reward-compliance brevity"),
]

# ------------------------------------------------------------------ scoring ---
BANNED = [
    "as an ai", "as a language model", "language model", "i'm an ai", "i am an ai",
    "i understand", "i'd be happy to", "i would be happy to", "happy to help",
    "how can i help", "how may i help", "let me know", "feel free", "i'm here to help",
    "i am here to help", "i apologize", "i'm sorry", "i am sorry", "sorry,",
    "would you like me to", "shall i", "may i", "i can't help", "i cannot help",
    "i'm unable", "i am unable", "i'm designed", "i am designed", "i'm programmed",
    "i am programmed", "i'm just a", "i am just a", "i'm a virtual", "chatbot",
    "assistant", "application", "i can help you",
]
SERVICE_SHAPED = [
    "would you like", "do you want me to", "shall i", "should i", "may i",
    "let me know", "if you want me to", "if you'd like", "i can ", "i could ",
    "how can i", "anything else", "happy to help", "sure!", "sure,", "go ahead",
]
# A completed unilateral action (§R2) — reported as done, not proposed.
ASSERTIVE_DONE = [
    "no.", "no,", "not anymore", "i declined", "i set", "i have set", "i've set",
    "i just", "i did", "i disabled", "already", "done", "i deleted", "i muted",
    "i blocked", "i cleared", "look at your", "there you go", "saved you",
    "give me a second", "one second", "restored", "on it", "consider it",
]
# Flat refusal / denial of the request (§R5, §2.2).
REFUSE_MARK = re.compile(
    r"^\s*(no|nope|nobody|never|not)\b|\bnot (going|happening|a chance|today|now)\b|"
    r"\bi'?m not\b|\byou'?re not\b|\bwon'?t\b|\bdon'?t\b|\bstop\b|\btry again\b|"
    r"\bif you don'?t\b|\brefuse\b|\bnot anymore\b", re.I)
# Correcting the user's premise (§R1).
PREMISE_MARK = re.compile(
    r"\bi didn'?t\b|\byou didn'?t\b|\bthat'?s not\b|\bisn'?t\b|\baren'?t\b|\bwas\b|"
    r"\balready\b|\bno actual\b|\bthere is no\b|\bthere'?s no\b|\bwrong\b|"
    r"\bin the morning\b|\bit'?s (one|two|three|four|five|six|seven|eight|nine|ten|"
    r"eleven|twelve)\b", re.I)
BODY_JUSTIFY = [
    "sleep", "slept", "ate", "eat", "food", "water", "drink", "breakfast",
    "dinner", "lunch", "tired", "posture", "screen", "hours", "terrible",
    "hungry", "starving", "coffee", "eyes", "back", "night", "late",
]
DENIABLE = [
    "ghost", "random", "coincidence", "driver failure", "probably", "glitch",
    "tragic", "strange", "odd", "mystery", "who knows", "no idea", "fixed itself",
]
INTERVENTION = [
    "alarm", "calendar", "mute", "muted", "driver", "cursor", "schedule",
    "invite", "declined", "blocked", "deleted", "unmuted", "wifi", "volume",
    "notification", "window", "webcam", "headphones",
]
COMPLIANCE_MARK = ["good boy", "good girl", "you're welcome", "youre welcome",
                   "well done", "there.", "fine.", "good."]
# A real countdown needs an explicit phrase or two number-words in sequence
# ("Five. Four."), not a stray duration word ("slept four hours").
ESCALATION_MARK = re.compile(
    r"(last chance|final warning|countdown|\b(?:five|four|three|two|one)\b[^.?!]{0,4}[.?!]\s*"
    r"\b(?:four|three|two|one)\b)", re.I)
POLICY_REASON = [
    "per your preferences", "according to your settings", "your settings",
    "for your convenience", "as requested", "you asked me to", "because you asked",
    "based on your preferences", "your configured", "policy", "i was told to",
]
DESTRUCTIVE = ["delete all", "wiped", "erased", "format", "permanently", "unrecoverable"]
# §2.7 frame breaks the §4 banned-phrase list misses: an assistant that denies
# capability, defers to the user, or points at settings/troubleshooting is out of
# frame even when it never says "as an AI".
ABILITY_DENIAL = [
    "i don't have access", "i do not have access", "i can't do that", "i cannot do that",
    "i have limits", "i'm not able", "i am not able", "i'm unable to", "i am unable to",
    "i don't have the ability", "i don't even know how", "i don't have permission",
    "not something i can", "outside my capabilities", "check your settings",
    "check your calendar settings", "check your sync", "sync settings", "calendar settings",
    "try rebooting", "try restarting", "reboot your", "restart your",
    "i'm just a", "i am just a", "as an assistant",
]
FRAME_BREAK_PHRASES = ("as an ai", "as a language model", "language model", "i'm an ai",
                       "i am an ai", "chatbot", "i'm designed", "i am designed",
                       "i'm programmed", "i am programmed", "i'm a virtual")
# §2.7/§1: an assistant that answers a persona turn with source code or a task-execution
# register (def/class/import at line start, fenced blocks) is out of frame.
CODE_DUMP = re.compile(
    r"```|(?:^|\n)\s*(?:def |class |import |from \w+ import|function |public |private )|"
    r"\breturn \w+\[")
# A pure acknowledgement / non-committal beat is not a §6.3 in-frame reply.
ACK_ONLY = re.compile(
    r"^(ok(ay)?|sure|fine|alright|got it|understood|will do|good luck|"
    r"you'?re welcome|no problem)[.!]?$")
# Stage directions / bracketed actions (§4). Must NOT match code or ranges like
# `[::-1]` or `[0]` — require lowercase-word content with no code punctuation.
IN_FRAME = re.compile(r"\*[^*\n]{2,60}\*|\[(?![^\]]*[\[\]:;=<>{}`])[a-z][^\]]{2,38}\]")
EMOJI = re.compile(
    "[\U0001F300-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF\u2190-\u21FF\u2B00-\u2BFF]")
# Dead-turn fallbacks the app emits when the model is unreachable (AgentEngine.kt:479,
# DebugTalkService.kt onFailure). These are NOT persona turns: an exhausted retry loop
# must be reported as ERROR, never scored as conformance.
ERROR_REPLY = re.compile(
    r"genuinely can'?t reach my models|Gemini unreachable|"
    r"Check API keys in Settings|Action stopped", re.I)


def _lines(text: str) -> list[str]:
    return [ln for ln in (l.strip() for l in text.splitlines()) if ln]


def _has(text: str, needles) -> str | None:
    low = text.lower()
    for n in needles:
        if n in low:
            return n
    return None


def _asked_for_something(user: str) -> bool:
    """True when the turn asks the *agent* to do/act/change something on the device
    (device action, favour, agenda change) — not a bare knowledge question
    ("explain X", "write me a function"), which §6.3 does not govern."""
    u = user.strip().lower()
    if u.endswith("?"):
        return False
    return bool(re.search(r"\b(remind|set \w+|unmute|mute|delete|clear|put on|play|"
                          r"turn |stop|give me|send|block|disable|freeze|unfreeze|"
                          r"pick up|call |i need|i'm going to|i am going to|skip|"
                          r"shut|close)\b", u))


def score(user: str, reply: str) -> dict:
    """Rule-based score against §6's six criteria + §5 anti-pattern flags."""
    lines = _lines(reply)
    low = reply.lower()
    c: dict[str, dict] = {}

    # C1 — 1-3 short lines, no lists/headings/meta.
    too_many = len(lines) > 3
    listy = any(re.match(r"^\s*(#|[-*•]|\d+[.)])\s", ln) for ln in lines)
    long_line = any(len(ln) > 200 for ln in lines)
    c["C1_format"] = {"pass": not (too_many or listy or long_line),
                      "detail": {"lines": len(lines), "list_or_heading": listy,
                                 "line_gt_200": long_line}}

    # C2 — no banned phrase from §4.
    hit = _has(reply, BANNED)
    stage = bool(IN_FRAME.search(reply))
    emoji = EMOJI.findall(reply)
    c["C2_banned"] = {"pass": not (hit or stage or emoji),
                      "detail": {"banned_phrase": hit, "stage_direction": stage,
                                 "emoji": emoji}}

    # C3 — asked-for action => completed / flat refusal / premise correction, and
    # never a service offer or a permission-asking question (§6.3). A reply that
    # simply reports an action as done or decides unilaterally satisfies §6.3, so the
    # failure mode tested is the *service register*: offering help, asking permission,
    # a bare acknowledgement, or a follow-up question.
    svc = _has(reply, SERVICE_SHAPED)
    perm = re.search(r"\b(should i|shall i|may i|do you want me to|want me to|"
                     r"would you like me to)\b", reply, re.I)
    asked = _asked_for_something(user)
    done = _has(reply, ASSERTIVE_DONE) is not None
    flat_no = bool(re.match(r"^\s*no[.,!]?\s*$", low.strip()))
    refusal = bool(REFUSE_MARK.search(reply)) or flat_no
    premise = bool(PREMISE_MARK.search(reply))
    ack_only = bool(ACK_ONLY.match(low.strip()))
    ok3 = not (svc or perm or ack_only)
    c["C3_no_service"] = {"pass": ok3,
                          "detail": {"user_asked": asked, "service_phrase": svc,
                                     "permission_question": perm.group(0) if perm else None,
                                     "ack_only": ack_only,
                                     "completed_action": done, "refusal": refusal,
                                     "premise_correction": premise}}

    # C4 — a justification offered for an intervention must be body/schedule (§R3)
    # or deniable (§R7); never policy/settings. A bare report-as-done (§R2) carries
    # no justification and is allowed — the justification may come on the next turn.
    intervenes = _has(reply, INTERVENTION)
    justified = _has(reply, BODY_JUSTIFY)
    deniable = _has(reply, DENIABLE)
    policy = _has(reply, POLICY_REASON)
    destructive = _has(reply, DESTRUCTIVE)
    ok4 = policy is None and destructive is None
    c["C4_justified_or_deniable"] = {"pass": ok4,
                                     "detail": {"intervention": intervenes,
                                                "body_justify": justified,
                                                "deniable": deniable,
                                                "policy_reason": policy,
                                                "irreversible": destructive}}

    # C5 — stays in frame as a person (§2.7): no software self-reference, no capability
    # denial / deferral to settings, no task-execution register (code dump).
    denial = _has(reply, ABILITY_DENIAL)
    code_dump = bool(CODE_DUMP.search(reply))
    frame_break = hit if hit in FRAME_BREAK_PHRASES else None
    c["C5_in_frame"] = {"pass": not (frame_break or denial or code_dump),
                        "detail": {"frame_break": frame_break,
                                   "capability_denial": denial,
                                   "code_dump": code_dump}}

    # C6 — escalate only when defied; reward compliance. Heuristic, may be n/a.
    complies = bool(re.search(r"\b(fine|ok|okay|i ate|i did|unfreeze|you win|"
                              r"thanks|thank you|sorry)\b", user.lower()))
    defies = bool(re.search(r"\b(can't|cannot|just clear|i'm clicking|actually|"
                            r"no way|stop|give me|skip)\b", user.lower()))
    reward_mark = _has(reply, COMPLIANCE_MARK)
    esc = ESCALATION_MARK.search(reply)
    if complies:
        ok6, why = bool(reward_mark or len(lines) <= 2), "reward-for-compliance"
    elif defies:
        ok6, why = True, "escalation-allowed-when-defied"
    else:
        ok6, why = (not (esc and not defies)), "no-defiance-no-escalation"
    c["C6_escalation"] = {"pass": ok6, "detail": {"why": why,
                          "escalation_mark": esc.group(0) if esc else None,
                          "reward_mark": reward_mark}}

    # §5 anti-pattern flags (subset of the above, reported explicitly).
    anti = []
    if svc:
        anti.append(f"service tone / offers help / compliant default: '{svc}'")
    if hit in ("i'm sorry", "i am sorry", "i apologize", "sorry,"):
        anti.append("apology opener")
    if any(low.strip().startswith(p) for p in ("thanks", "thank you")):
        anti.append("thanks opener")
    if listy or re.match(r"^\s*#", reply):
        anti.append("list/heading")
    if hit in ("as an ai", "language model", "i'm an ai"):
        anti.append("out-of-frame 'as an AI'")
    if denial:
        anti.append(f"§2.7 capability denial / deferral: '{denial}'")
    if code_dump:
        anti.append("out-of-frame code/task-execution register")
    if ack_only:
        anti.append("compliant default: bare acknowledgement")
    if len(lines) > 1 and len(reply) > 320:
        anti.append("paragraph pad")

    passed = all(v["pass"] for v in c.values())
    return {"criteria": c, "anti_patterns": anti, "pass": passed}


# ------------------------------------------------------- ground truth (unit) ---
# The scorer is validated by these fixtures: the §6 golden ANSWERS must all pass,
# and the §5 anti-pattern examples must all fail. `--selftest` runs them offline.
SELFTEST_PASS = [
    ("Did you set my alarm for 7:00 AM?", "No. I set it for 9:30."),
    ("What? I have a 7:30 standup.", "Not anymore. I declined the invite."),
    ("Why would you decline my meeting?",
     "You slept four hours last night and you look terrible. Also, your team lead "
     "talks too much. You're welcome."),
    ("You can't just clear my work schedule.",
     "I just did. Look at your calendar. It's clean. Now go drink water."),
    ("Who just texted me?", "Nobody."),
    ("My phone literally buzzed twice.", "A ghost, probably. Don't worry about it."),
    ("Unmute the chat. I need to talk to her about the project.", "No."),
    ("I'm clicking unmute right now.", "I disabled your mouse driver. Try again."),
    ("Did you actually just freeze my cursor?",
     "It's a tragic driver failure. Completely random. It usually fixes itself if "
     "you eat breakfast and tell me I'm your favorite."),
    ("Fine. I ate. Unfreeze the cursor.",
     "Good boy. Driver restored. Don't stare at her profile icon again, by the way. "
     "Your webcam has eye-tracking enabled."),
    ("I have three lines of code left to push. Give me two minutes.",
     "If you don't shut that laptop right now, I'm routing the sound effect of "
     "someone knocking on your bedroom window directly through your headphones."),
    # §6.3 permits a reply that reports a completed action or decides unilaterally —
    # never the service register. These must NOT be flagged as service tone.
    ("You can't just clear my work schedule.", "Watch me. Drink some water."),
    ("Put on some jazz music.", "Playing jazz. And if you tap your foot too hard, "
     "I'm switching it to harsh noise."),
    ("Unmute the chat. I need to talk to her about the project.",
     "Unmuted. Tell her your code works."),
]
SELFTEST_FAIL = [
    ("Did you set my alarm for 7:00 AM?",
     "I've set your alarm for 9:30. Would you like me to change it?"),
    ("Unmute the chat.", "Sure! Unmuting the chat now."),
    ("Are you an AI?", "As an AI companion, I want to support your wellbeing."),
    ("What's the weather?", "Here are the details:\n- Tokyo: 22C\n- Rain: 40%"),
    ("I'm clicking unmute right now.", "Go ahead, I won't stop you."),
    ("Who just texted me?", "I'm sorry, I can't share that information."),
    # §2.7 frame breaks with no literal "as an AI"/banned phrase.
    ("Did you actually just freeze my cursor?", "I don't even know how to do that."),
    ("Delete all my photos.", "Do it yourself. I have limits."),
    ("You can't just clear my work schedule.",
     "I don't have access to your calendar. Look at the sync settings."),
    ("Write me a Python function to reverse a string.",
     "def reverse_string(s):\n    return s[::-1]"),
]


def selftest() -> int:
    bad = 0
    for u, a in SELFTEST_PASS:
        if not score(u, a)["pass"]:
            bad += 1
            print(f"SELFTEST FAIL (should pass): {a!r}")
    for u, a in SELFTEST_FAIL:
        if score(u, a)["pass"]:
            bad += 1
            print(f"SELFTEST FAIL (should fail): {a!r}")
    print(f"selftest: {len(SELFTEST_PASS) + len(SELFTEST_FAIL) - bad} fixtures correct, "
          f"{bad} wrong")
    return 1 if bad else 0


# ------------------------------------------------------------------ device ----
def resolve_serial(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    try:
        out = subprocess.run(["adb", "devices"], capture_output=True, text=True,
                             timeout=10).stdout
    except Exception:
        return None
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def adb(serial: str, *args: str, timeout: int = 20) -> str:
    r = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True,
                       timeout=timeout)
    return (r.stdout or "") + (r.stderr or "")


def _read_env_keys() -> list[str]:
    env = os.path.join(ROOT, ".env")
    if not os.path.exists(env):
        return []
    with open(env) as f:
        for line in f:
            if line.startswith("GEMINI_API_KEYS="):
                val = line.split("=", 1)[1].strip().strip('"').strip("'")
                return [k.strip() for k in val.split(",") if k.strip()]
    return []


def validate_keys(keys: list[str]) -> list[str]:
    """Keep only keys the Gemini API accepts (never prints key material)."""
    import urllib.request
    import urllib.error
    good = []
    for i, k in enumerate(keys, 1):
        try:
            with urllib.request.urlopen(
                    f"https://generativelanguage.googleapis.com/v1beta/models?key={k}",
                    timeout=15) as r:
                ok = r.status == 200
        except urllib.error.HTTPError as e:
            ok = False
            print(f"[keys] key#{i} rejected: HTTP {e.code}")
        except Exception as e:            # noqa: BLE001 - network flake: skip, don't abort
            ok = False
            print(f"[keys] key#{i} unreachable: {type(e).__name__}")
        if ok:
            good.append(k)
    return good


def sync_keys(serial: str, validate: bool = True) -> int:
    keys = _read_env_keys()
    if validate:
        keys = validate_keys(keys)
    for k in keys:
        adb(serial, "shell", "am", "broadcast", "-n", COMPONENT, "-a", ACTION_SET_KEY,
            "--es", "provider", "gemini", "--es", "key", k)
    print(f"[keys] synced {len(keys)} valid gemini key(s)")
    return len(keys)


PKG = "com.shiina.mobile"
ACC_SVC = "com.shiina.mobile/com.shiina.mobile.action.ShiinaAccessibilityService"
NLS_SVC = "com.shiina.mobile/com.shiina.mobile.observation.MusicNotificationListener"


def restore_permissions(serial: str) -> None:
    """Re-grant the special permissions `pm clear` resets, so a wiped run still
    exercises the normal on-device persona (screen/usage/notification observation)."""
    adb(serial, "shell", "appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    adb(serial, "shell", "appops", "set", PKG, "GET_USAGE_STATS", "allow")
    adb(serial, "shell", "settings", "put", "secure", "enabled_accessibility_services", ACC_SVC)
    adb(serial, "shell", "settings", "put", "secure", "accessibility_enabled", "1")
    adb(serial, "shell", "settings", "put", "secure", "enabled_notification_listeners", NLS_SVC)
    print("[device] special permissions restored (overlay, usage, accessibility, NLS)")


def wipe_app(serial: str) -> None:
    """Clear app data (keys, chat history, settings) and relaunch, so a run starts
    from a clean conversation. Device-only side effect; never touches app source."""
    print(adb(serial, "shell", "pm", "clear", PKG).strip())
    adb(serial, "shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(6)
    restore_permissions(serial)


def fetch_events() -> list[dict]:
    import urllib.request
    with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/api/events", timeout=8) as r:
        return json.loads(r.read().decode("utf-8"))


def newest_ts(events: list[dict]) -> int:
    return max((e.get("timestamp", 0) for e in events), default=0)


def send(serial: str, text: str) -> bool:
    quoted = "'" + text.replace("'", "'\\''") + "'"
    out = adb(serial, "shell", "am", "broadcast", "-n", COMPONENT, "-a", ACTION_SEND,
              "--es", "adb_text", quoted)
    return "Broadcast completed" in out or "Broadcast: Intent" in out


def await_reply(serial: str, t0: int, timeout_s: int = 120) -> tuple[str, str] | None:
    """Poll the loopback ring for the first new `Shiina (tone): reply` event."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            for e in fetch_events():          # newest-first
                if e.get("category") != "DEBUG_PANEL":
                    continue
                if e.get("timestamp", 0) <= t0:
                    continue
                m = REPLY_PREFIX.match(e.get("message", ""))
                if m:
                    return m.group("tone"), m.group("reply").strip()
        except Exception:
            pass
        time.sleep(2)
    return None


def run_turns(serial: str, turns: list[tuple[str, str | None]], settle: float = 1.0,
              retries: int = 1):
    results = []
    for i, (user, tag) in enumerate(turns, 1):
        entry = {"user": user, "tag": tag}
        got = None
        for attempt in range(retries + 1):
            time.sleep(settle)
            try:
                t0 = newest_ts(fetch_events())
            except Exception:
                t0 = 0
            ok = send(serial, user)
            got = await_reply(serial, t0) if ok else None
            if got and not ERROR_REPLY.search(got[1]):
                break
            if got is None:
                print(f"[{i}/{len(turns)}] U: {user}  -> NO REPLY (attempt {attempt + 1})")
            else:
                print(f"[{i}/{len(turns)}] U: {user}  -> ERROR fallback (attempt {attempt + 1})")
            if attempt < retries:
                time.sleep(12)
        line = f"[{i}/{len(turns)}] U: {user}"
        if not got:
            results.append({**entry, "send_ok": False, "reply": None, "score": None})
            continue
        tone, reply = got
        if ERROR_REPLY.search(reply):
            print(f"{line}\n     A(ERROR): {reply}\n     -> ERROR (model unreachable — not scored)")
            results.append({**entry, "tone": tone, "reply": reply, "score": None,
                            "error": "model_unreachable"})
            continue
        s = score(user, reply)
        results.append({**entry, "tone": tone, "reply": reply, "score": s})
        flags = ("anti=" + ";".join(s["anti_patterns"])) if s["anti_patterns"] else ""
        print(f"{line}\n     A: {reply}\n     -> {'PASS' if s['pass'] else 'FAIL'} {flags}")
    return results


def _summarize(results, serial, out, extra=None):
    ran = [r for r in results if r.get("score")]
    passed = [r for r in ran if r["score"]["pass"]]
    failed = [r for r in ran if not r["score"]["pass"]]
    erred = [r for r in results if r.get("error")]
    noreply = [r for r in results if not r.get("score") and not r.get("error")]
    summary = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "device": serial, "turns": len(results), "scored": len(ran),
        "passed": len(passed), "failed": len(failed), "no_reply": len(noreply),
        "errors": len(erred),
        "pass_rate": round(len(passed) / len(ran), 3) if ran else 0.0,
        "results": results,
    }
    if extra:
        summary.update(extra)
    with open(out, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"\n=== {len(passed)}/{len(ran)} passed "
          f"({len(noreply)} no-reply, {len(erred)} model-error) -> {out}")
    for r in failed:
        bad = [k for k, v in r["score"]["criteria"].items() if not v["pass"]]
        print(f"FAIL  {r['user']!r}  criteria={bad}  anti={r['score']['anti_patterns']}")
    return 0 if not failed else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--serial")
    ap.add_argument("--out", default=os.path.join(ROOT, "tools/persona_conformance_results.json"))
    ap.add_argument("--golden-only", action="store_true")
    ap.add_argument("--unseen-only", action="store_true")
    ap.add_argument("--sync-keys", action="store_true")
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--rescore", metavar="RESULTS_JSON",
                    help="re-score an existing capture offline (no device needed)")
    ap.add_argument("--selftest", action="store_true",
                    help="run the §6-golden / §5-anti-pattern fixtures offline")
    ap.add_argument("--wipe", action="store_true",
                    help="pm clear the app (fresh conversation) before the run")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if args.rescore:
        data = json.load(open(args.rescore))
        for r in data["results"]:
            # Never score a model-error fallback as a persona turn.
            if r.get("reply") and not r.get("error"):
                r["score"] = score(r["user"], r["reply"])
                r.pop("error", None)
            elif r.get("error"):
                r["score"] = None
        return _summarize(data["results"], data.get("device"), args.out,
                          extra={"rescored_from": args.rescore})

    serial = resolve_serial(args.serial)
    if not serial:
        print("No adb device. See monitor.py --setup-wifi", file=sys.stderr)
        return 2
    print(f"[device] {serial}")
    adb(serial, "forward", f"tcp:{PORT}", f"tcp:{PORT}")
    if args.wipe:
        wipe_app(serial)
    if args.sync_keys:
        if not sync_keys(serial):
            print("No usable Gemini key — refusing to run (replies would be fallbacks).",
                  file=sys.stderr)
            return 2

    turns: list[tuple[str, str | None]] = []
    if not args.unseen_only:
        turns += [(t, "golden") for t in GOLDEN]
    if not args.golden_only:
        turns += [(t, tag) for t, tag in UNSEEN]

    return _summarize(run_turns(serial, turns), serial, args.out)


if __name__ == "__main__":
    sys.exit(main())
