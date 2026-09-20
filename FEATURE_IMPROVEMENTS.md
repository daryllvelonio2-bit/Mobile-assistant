# Shiina Mobile — Feature Improvement Scan
Generated 2026-09-20 by a 4-way parallel codebase audit (72 .kt files, ~10.2k LOC).
Every item is grounded in current code; ids are stable for tracking.
Effort: S = < half day, M = ~1 day, L = multi-day.

## CHARACTER / MOOD / UI (14)

Current state: overlay is a static 64dp circle showing `tone.take(4)` + message,
drag-only, WANDER teleports randomly every 4s; MoodEngine is a V-A vector with
keyword-only sentiment and a single 6h decay, invisible to the user;
CharacterPanel is status-display only; no avatar, animation, tap, TTS, or
personality controls.

CHAR-1  Tappable overlay bubble with quick actions. Bubble is drag-only today;
        add tap -> mini-card with Talk / Hide / mood-nudge buttons.
        Files: CharacterOverlayService.kt. M.

CHAR-2  Live avatar face replacing the 4-letter badge. Drawn expressive face
        (eyes/mouth per mood + blink) instead of Text(tone.take(4)).
        Files: CharacterOverlayService.kt, Theme.kt. M.

CHAR-3  Mood-transition animations. toneColor() snaps instantly; animate border
        crossfade + subtle scale/pop on mood change.
        Files: CharacterOverlayService.kt. S.

CHAR-4  Typing/thinking indicator on overlay. Animated dots while the agent
        loop runs CONTINUE turns. Files: CharacterOverlayService.kt. S.

CHAR-5  Smooth wander path. startWander() jumps to Random positions every 4s;
        animate eased drift with edge avoidance.
        Files: CharacterOverlayService.kt, CharacterController.kt. S.

CHAR-6  FOLLOW mode. Bubble gently gravitates toward recent tap region; only
        STAY/WANDER/VANISH exist today.
        Files: CharacterMode.kt, CharacterController.kt, CharacterOverlayService.kt. M.

CHAR-7  Proactive check-in mode. Opt-in time-aware nudges (morning greeting,
        long-idle) driven by MoodEngine + time of day; today the overlay only
        shows on decision interrupt.
        Files: CharacterOverlayService.kt, MoodEngine.kt, SettingsScreen.kt. M.

CHAR-8  Mood history timeline. 24h/7d sparkline from persisted MoodState;
        today only current tone string is shown.
        Files: CharacterPanel.kt, CharacterViewModel.kt, MoodStateDao. M.

CHAR-9  Manual mood comfort controls. "Cheer her up / Give space" counter-events;
        today a COMPLAINT -0.25 sticks for hours with no user repair path.
        Files: CharacterPanel.kt, CharacterViewModel.kt, MoodEngine.kt. S.

CHAR-10 Personality sliders (playfulness/warmth/chattiness). Persisted sliders
        scaling promptBlock() expression and message length.
        Files: SettingsScreen.kt, SettingsViewModel.kt, MoodEngine.kt. M.

CHAR-11 Richer sentiment: negation + intensity modifiers. applyUserText is
        substring match ("thanks for nothing" counts as praise today).
        Files: MoodEngine.kt. S.

CHAR-12 Bond/affection meter. Slow-moving trust score from cumulative
        praise/talked-back vs complaint/dismissed, surfaced in panel + prompt;
        today only the fast 6h-half-life V-A vector exists.
        Files: MoodEngine.kt, CharacterPanel.kt, MoodState. L.

CHAR-13 Mood-reactive app theming. Tint panel accents/background by current
        mood; today mood colors live only on the bubble border.
        Files: Theme.kt, CharacterPanel.kt, CharacterViewModel.kt. M.

CHAR-14 Spoken replies (optional TTS). Opt-in TTS for final DONE messages with
        per-mood pitch/rate; no audio path exists today.
        Files: CharacterOverlayService.kt, SettingsScreen.kt. L.

## DECISION LOOP / PROMPTS / GEMINI (14)

LOOP-1  Rich DecisionSummary. renderLatest() hardcodes sleepBedMillis=0 and
        goals*=0, so the bedtime/goals prompt branches never fire with real
        data. Feed real sleep/foreground-app/music into summary + prompt.
        Files: DecisionModels.kt, GeminiProvider.kt, ProviderRegistry.kt,
        DebugTalkService.kt. M.

LOOP-2  Silent actions. parse() forces action=NONE whenever interrupt=false;
        allow a silent_action allowlist (LOG_GOAL, LEARN_FACT, SET_REMINDER,
        CHECK_GOALS, TAKE_SCREENSHOT) when interrupt=false, gated by confidence.
        Files: GeminiProvider.kt, DecisionModels.kt, ProviderRegistry.kt. S.

LOOP-3  Unify action verbs across loops. Decision.ALLOWED_ACTIONS lacks
        TAP_SCREEN/SWIPE_SCREEN/INPUT_TEXT/PRESS_KEY/REMEMBER that the talk
        loop already executes. Share one allowlist + prompt schema.
        Files: DecisionModels.kt, GeminiProvider.kt. S.

LOOP-4  Multi-step plans in decision rounds. Extras capped at 2 and discarded
        when silent; raise to 4 ordered steps with per-step receipts
        (CHECK_GOALS -> SEARCH_WEB -> SET_REMINDER).
        Files: DecisionModels.kt, GeminiProvider.kt, ProviderRegistry.kt. M.

LOOP-5  Per-source confidence floors + proper absence gate. One global 0.6
        floor and a brittle `senseJson.contains("screen":"off")` string match;
        parse senses JSON, stricter floor for proactive, charging+screen-off
        = absent. Files: ProviderRegistry.kt, DecisionModels.kt. S.

LOOP-6  Provider latency/quality scoreboard with smart failover. decide()
        tries providers in fixed order with no timing/outcome tracking; record
        per-provider latency + outcome, prefer fastest healthy backend,
        persist confidence/reason in episodes.
        Files: ProviderRegistry.kt, MemoryEpisode schema/DAO. M.

LOOP-7  Native Gemini function calling for decision rounds. Today hand-rolled
        JSON-in-text parsing; attach functionDeclarations mirroring ToolCatalog
        so Gemini returns typed functionCall.
        Files: GeminiProvider.kt, ToolCatalog.kt. M.

LOOP-8  generationConfig tuning. Neither Gemini call sets temperature/maxTokens/
        thinking; low-temp deterministic JSON for decisions, livelier sampling
        for chat, capped output tokens.
        Files: GeminiProvider.kt, DebugTalkService.kt, SettingsRepository. S.

LOOP-9  Vision upgrade. Decision vision attaches any <=30-min capture full-size
        with text part first; downscale <=1024px JPEG-70, inline_data first,
        freshness tiers (fresh <2min vs stale).
        Files: GeminiProvider.kt, DebugTalkService.kt, ScreenshotTaker.kt. M.

LOOP-10 Smarter key pool. Flat 60s cooldown on any failure including local
        parse errors; differentiate 429 (long cooldown + model failover) vs
        5xx (short retry) vs parse errors (no penalty); surface pool depth.
        Files: RoundRobinKeyPool.kt, GeminiProvider.kt, DebugTalkService.kt,
        ProviderRegistry.kt. M.

LOOP-11 Query-relevant memory recall. MemoryContext takes top-N by recency/
        confidence regardless of topic; boost facts/episodes matching current
        goal titles, active app, or music artist.
        Files: MemoryContext.kt, ProviderRegistry.kt, DebugTalkService.kt. M.

LOOP-12 Prompt versioning + A/B. PROMPT_VERSION is one constant stamped in
        logs; add versioned builders + settings flag assigning variant per
        round, compare interrupt-accept rates from episode outcomes.
        Files: ShiinaPrompts.kt, GeminiProvider.kt, ProviderRegistry.kt. M.

LOOP-13 Talk-loop context compression. askGemini rebuilds full history + raw
        step strings every turn so 25-step runs balloon; summarize old steps
        past a char budget. Files: DebugTalkService.kt, ShiinaPrompts.kt. L.

LOOP-14 Proactive follow-up questions. Decision prompt has no open loops;
        inject due-soon reminders + stalest-goal age with a FOLLOW_UP intent
        tone so she pings "still doing X?" unprompted.
        Files: GeminiProvider.kt, MemoryContext.kt, DecisionModels.kt,
        ShiinaPrompts.kt. M.

## MEMORY / DATA (14)

Current state: episodes one row/round with outcomes, message hashed, 90-day
prune, 30d->7d-window compaction into 560-char digests; facts key/value/
confidence with stated=1.0 inferred=0.5+0.1, cap 200, contradict x0.3;
nightly pass logs tone effectiveness but doesn't persist it; chat turns
uncapped verbatim with 100k-char auto-compact; GoalDao has no delete/status
queries; MemorySummary has no prune; no FTS/indexes.

MEM-1   Fact expiry dates + refresh. expiresMillis on MemoryFact; nightly pass
        re-prompts/stale-flags expiring facts instead of silent decay.
        Files: MemoryFact.kt, MemoryStore.kt, NightlyReflection.kt,
        AppDatabase.kt (v10). M.

MEM-2   Episode importance scoring + selective retention. importance REAL from
        acted/talkedBack/interrupt/dismissed; keep high-importance rows past
        30d, quote top-2 in digests.
        Files: MemoryEpisode.kt, MemoryCompactor.kt, AppDatabase.kt. M.

MEM-3   Persisted per-tone effectiveness table. tone_stats entity; today
        toneEffectiveness() only logs. Recall ranks tones that worked.
        Files: new ToneStat.kt, NightlyReflection.kt, AppDatabase.kt,
        MemoryContext. S.

MEM-4   Goal-aware memory links. GoalDao gets delete/byStatus/streakDays;
        link episodes -> goalIds so nightly reports per-goal acted rates and
        streaks ("3-day Duolingo streak").
        Files: GoalDao.kt, GoalEntry.kt, MemoryEpisode.kt,
        NightlyReflection.kt. M.

MEM-5   Digest retention + digest-aware recall. keepLatest(N)/12-month cap +
        recall last 3 digests into prompt block.
        Files: MemorySummary.kt, MemoryCompactor.kt, MemoryContext. S.

MEM-6   Consolidator contradiction + correction pipeline. Route extracted
        facts through contradict()/alias-normalized upsert, cap ~5/run with
        dedupe; today it only ever learn()s.
        Files: MemoryConsolidator.kt, MemoryStore.kt. M.

MEM-7   Offline rule-based consolidator fallback. Keyword/regex extractor
        (name/birthday/bedtime/likes) writing 0.3-confidence inferred facts
        when no Gemini key/quota.
        Files: MemoryConsolidator.kt, MemoryStore.kt. M.

MEM-8   Fact-conflict surfacing. Track conflictOf and ask which is right when
        a new value contradicts a >=0.7 fact instead of silently overwriting.
        Files: MemoryFact.kt, MemoryStore.kt. M.

MEM-9   Chat-turn FTS + semantic recall. FTS5/keyword index +
        searchSince(keyword, limit) so "what did we decide about Tokyo?" works.
        Files: ChatTurn.kt, AppDatabase.kt (v10). L.

MEM-10  Activity-rhythm facts. Daily active-window histogram -> inferred facts
        (active_window=22:00-23:30, quiet_mornings) feeding best-nudge-hour;
        today only one last_active timestamp.
        Files: UserActivityTracker.kt, NightlyReflection.kt, MemoryStore.kt. M.

MEM-11  Mood-memory correlation. mood_samples table (valence/arousal per
        episode/night) + nightly correlation of tone/action -> next-day
        valence; MoodState is a single overwrite row today.
        Files: MoodState.kt, new MoodSample.kt, NightlyReflection.kt,
        AppDatabase.kt. L.

MEM-12  Scoped forget. forgetPrefix("work_") / forgetSource("inferred") wired
        to Talk + Settings; today only forgetKey/forgetAll.
        Files: MemoryFact DAO, MemoryStore.kt, LearnedMemoryManager.kt. S.

MEM-13  LearnedMemoryManager cap + single-source-of-truth sync. 200-entry cap,
        startup reconciliation (Room wins), clear() clears Room facts too.
        Files: LearnedMemoryManager.kt, MemoryStore.kt. S.

MEM-14  Memory health dashboard data source. memoryHealth() (counts, digest
        coverage gaps, consolidation watermark lag, low-confidence ratio, DB
        size) feeding Settings viewer.
        Files: MemoryStore.kt, MemoryCompactor.kt, MemoryConsolidator.kt. S.

## SENSES / ACTIONS (13)

Current state: DeviceSenses snapshot (time/battery/screen/ringer/foreground
app/music/top-3 apps); UsageReader entertainment-only with 7 hardcoded pkgs;
SleepReader 24h total with fake 480 fallback; CaptureWatcher app-change/
rotation/music triggers, 30% roll, 15-min cooldown; ActionExecutor verbs
SET_ALARM/LOG_GOAL/COMPLETE_GOAL/CHECK_GOALS/SEARCH_WEB/READ_URL/
TAKE_SCREENSHOT/SET_REMINDER/MEDIA_CONTROL/PLAY_MUSIC/SEARCH_MUSIC/VOLUME/
OPEN_APP/SEARCH_APP/LIST_APPS/GET_DEVICE_STATE/HIDE/SET_MODE;
ShiinaAccessibilityService has clickText/tapScreen not wired into the
executor; ReminderScheduler one-shot only.

SENSE-1 Offline-aware network sense. Connectivity type (wifi/cellular/offline +
        metered) in snapshot via ConnectivityManager (permission already
        granted); talk loop skips SEARCH_WEB/READ_URL when offline.
        Files: DeviceSenses.kt, decision TOOL_SPEC. S. None.

SENSE-2 App-session depth. Per-app open counts (UsageEvents MOVE_TO_FOREGROUND)
        + session minutes today, top-5 — "you opened TikTok 14 times / 90 min".
        Files: UsageReader.kt, DeviceSenses.kt. M. None (UsageStats granted).

SENSE-3 Audio-output routing sense. Speaker/wired/BT via
        AudioManager.getDevices — she ducks volume or asks before playing
        aloud on speaker. Files: DeviceSenses.kt, DeviceActionController.kt.
        S. None.

SENSE-4 Entertainment category upgrade. 7 hardcoded pkgs miss browsers/
        YouTube-in-Brave/local players; use AppStandbyBucket + user-curated
        fact list + separate video bucket.
        Files: UsageReader.kt, BaselineUpdater.kt. M. None.

SENSE-5 Bedtime/wake + streak sense. Last session bedtime/wake/duration + 7d
        average/streak with honest "no data" instead of 480 fallback; feeds
        evening decision. Files: SleepReader.kt, AlarmReceiver.kt,
        DeviceSenses.kt. M. None (Health Connect READ_SLEEP declared).

SENSE-6 Missed-pings sense — the ONE optional ask. Missed calls + unread SMS
        counts via CallLog/Telephony providers, off by default behind runtime
        consent (ABILITIES_PLAN A5 slot). Files: new PingSenses.kt,
        DeviceSenses.kt, MainActivity. M. READ_CALL_LOG + READ_SMS, optional.

SENSE-7 Smarter capture triggers. Add ACTION_USER_PRESENT unlock + notification
        burst (3+ non-music in 2 min) under existing cooldown + settings
        toggle. Files: CaptureWatcher.kt, MusicNotificationListener.kt. S. None.

SENSE-8 Orientation + posture in senses. ScreenMetrics.isLandscape() exists but
        snapshot never reports it; add orientation + WxH so taps stop guessing.
        Files: ScreenMetrics.kt, DeviceSenses.kt. S. None.

ACT-1   Reminder list/cancel/snooze. LIST_REMINDERS, CANCEL_REMINDER <id>,
        SNOOZE 10m on top of existing one-shots.
        Files: ReminderScheduler.kt, ActionExecutor.kt. M. None.

ACT-2   Repeating reminders. daily/weekly/weekday repeat; reschedule on fire
        instead of delete. Files: ReminderScheduler.kt (+ Room column),
        ActionExecutor.kt. M. None.

ACT-3   Real media queue control. NEXT/PREV/PAUSE/RESUME with receipts from
        MusicTracker.currentTrack; pause-then-duck on reminder fire.
        Files: DeviceActionController.kt, ActionExecutor.kt,
        ReminderScheduler.kt. S. None.

ACT-4   Open-and-do: guided in-app tap. DO_IN_APP <app: text to tap> =
        openApp + dumpInteractiveElements + clickText fallback; wires the
        existing a11y clickText/tapScreen into the executor.
        Files: DeviceActionController.kt, ShiinaAccessibilityService.kt,
        ActionExecutor.kt. L. None (a11y declared).

ACT-5   Screenshot with eyes receipt. READ_LAST_CAPTURE returning newest
        capture's reason/timestamp/size + which path produced it; tag
        on-demand captures with the user query.
        Files: ScreenshotTaker.kt, ActionExecutor.kt. S. None.

ACT-6   Sleep-defer quiet hours. Suppress non-reminder overlays 11pm-7am (or
        Health Connect bedtime +/-1h) while letting reminders through, logged
        as deferred. Files: ActionExecutor.kt, MemoryOutcomes.kt,
        SleepReader.kt. S. None.

## Suggested top batch (highest alive-per-effort, no new permissions)

1. LOOP-2 + LOOP-3  — silent background actions + unified verbs (S+S)
2. CHAR-2 + CHAR-3  — real avatar face + mood transitions (M+S)
3. LOOP-1           — real sleep/goal data in decisions (M)
4. ACT-1 + ACT-2    — reminder list/cancel + repeats (M+M)
5. MEM-3 + MEM-10   — persisted tone stats + activity-rhythm nudges (S+M)
6. SENSE-2 + SENSE-3 — session depth + audio routing (M+S)