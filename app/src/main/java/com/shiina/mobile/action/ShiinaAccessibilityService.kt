package com.shiina.mobile.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.data.settings.BedtimeStore
import com.shiina.mobile.memory.ClaimStore
import com.shiina.mobile.decision.AgentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class UiElement(
    val id: Int,
    val text: String,
    val desc: String,
    val type: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
)

/**
 * Accessibility Service for Shiina Mobile.
 * Enables autonomous screen interactions (tapping coordinates, clicking elements by text or ID,
 * scrolling/swiping, entering text, and global navigation) without requiring root or external cables.
 */
class ShiinaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: ShiinaAccessibilityService? = null
        val isEnabled: Boolean get() = instance != null

        // Warn-first ladder (PLAN_binge_block_v2): warn at WARN_MINUTES of continuous
        // sitting, GRACE_MINUTES of silence -> cut, EXTREME_MINUTES cuts on sight.
        // TEST FUSE: WARN 2 / GRACE 1 — ship values WARN 120 / GRACE 10 / EXTREME 180.
        const val WARN_MINUTES = 2L
        const val GRACE_MINUTES = 1L
        const val EXTREME_MINUTES = 180L
        // Claims ("I'm doing something important") expire after this long.
        const val CLAIM_TTL_HOURS = 24L
        // After a block, the app stays locked this long — reopening gets yanked instantly.
        // NOTE: on CUT the lock length comes from HER verdict; this is the fallback.
        const val APP_LOCKOUT_MINUTES = 30L
        // Safety: a block auto-releases after this long even if you never reply.
        const val BLOCK_AUTO_RELEASE_MINUTES = 10L
        // Her verdict stands this long per app before she'll reconsider.
        const val VERDICT_COOLDOWN_MINUTES = 30L
        const val ACTION_GRACE_EXPIRED = "com.shiina.mobile.action.GRACE_EXPIRED"
        const val EXTRA_GRACE_PKG = "grace_pkg"
        private const val GRACE_ALARM_CODE = 7742
        // Safety rails, not judgment: never cut calls, settings, or the camera.
        private val NEVER_BLOCK = setOf("dialer", "telecom", "android.settings", "camera")
    }

    // Binge-block session state (in-memory; resets if the process dies).
    private var blockPkg: String? = null
    private var blockAccumMs: Long = 0L
    private var blockLastStampMs: Long = 0L
    private var blockCount: Int = 0
    // Active lockouts: package -> release timestamp. Reopening during lockout = instant yank.
    private val blockLockouts = mutableMapOf<String, Long>()
    private var blockLastEnforceMs: Long = 0L
    // Consult-her state: which app she already ruled on + her verdict timestamps.
    private var consultedPkg: String? = null
    private val verdictAt = mutableMapOf<String, Long>()
    // Warn-first ladder (PLAN_binge_block_v2): warned pkg + grace start. Any chat from
    // him during grace = pass; silence past GRACE_MINUTES = consult #2 -> CUT.
    private var warnedPkg: String? = null
    private var warnAtMs: Long = 0L
    // Post-claim watch: pkg whose sitting minutes get attributed to his last claim.
    private var claimWatchPkg: String? = null
    private val claimStore: ClaimStore by lazy { ClaimStore(applicationContext) }
    private val consultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Heartbeat: fullscreen video rarely fires window-state events, so content changes
    // refresh the session clock too (throttled — wall-clock accuracy, minimal overhead).
    private var blockLastContentMs: Long = 0L
    private val BLOCK_CONTENT_THROTTLE_MS = 30_000L

    private val cachedElements = java.util.concurrent.ConcurrentHashMap<Int, UiElement>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        serviceInfo = info
        AppDebugServer.log("ACCESSIBILITY", "ShiinaAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                runCatching { trackBingeSession(pkg) }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                // Locked app stirring: yank immediately, no throttle — spam taps get no frames.
                val lockedUntil = blockLockouts[pkg] ?: 0L
                if (pkg != null && now < lockedUntil) {
                    runCatching { trackBingeSession(pkg) }
                } else if (now - blockLastContentMs >= BLOCK_CONTENT_THROTTLE_MS) {
                    blockLastContentMs = now
                    runCatching { trackBingeSession(pkg) }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_GRACE_EXPIRED) {
            val pkg = intent.getStringExtra(EXTRA_GRACE_PKG)
            if (pkg != null && warnedPkg == pkg) {
                AppDebugServer.log("ACCESSIBILITY", "Grace expired on $pkg with no reply — consult #2.")
                warnedPkg = null
                consultGraceCut(pkg)
            }
        }
        return START_STICKY
    }

    /**
     * Her cut-off: accumulates continuous foreground time inside any real app.
     * Past [WARN_MINUTES] in one sitting, SHE is asked to rule — rotting gets her
     * own lock line and a 30-min app lockout; fine gets ALLOW. Talking to her or Home
     * pauses the clock; other apps start a new session. System chrome is ignored.
     */
    private fun trackBingeSession(pkg: String?) {
        val now = System.currentTimeMillis()
        // System chrome (status bar, notifications, lock screens, Huawei AppLock) fires
        // window events constantly — it must neither count as watching nor reset the timer.
        if (pkg != null && isSystemChrome(pkg)) return
        val eligible = pkg != null && NEVER_BLOCK.none { pkg.contains(it, ignoreCase = true) }
        if (!eligible) {
            blockPkg = null
            blockAccumMs = 0L
            blockLastStampMs = 0L
            return
        }
        // Active lockout: instant yank, re-armed every second while he fights it.
        val until = blockLockouts[pkg] ?: 0L
        if (now < until) {
            if (now - blockLastEnforceMs >= 1_000L) {
                blockLastEnforceMs = now
                val leftMin = ((until - now) / 60_000L).coerceAtLeast(1L)
                blockPkg = null
                blockAccumMs = 0L
                blockLastStampMs = 0L
                enforceBingeBlock(pkg, leftMin)
            }
            return
        } else if (until > 0L) {
            blockLockouts.remove(pkg)
        }
        if (pkg == blockPkg && blockLastStampMs > 0L) {
            // Cap single-event deltas: breaks (Home, chatting) pause the clock instead
            // of inflating it with away time.
            blockAccumMs += (now - blockLastStampMs).coerceIn(0L, 5 * 60_000L)
        } else {
            // New sitting: flush the old session's minutes into the claim watch, if any.
            flushClaimWatch()
            blockPkg = pkg
            blockAccumMs = 0L
            consultedPkg = null
            warnedPkg = null
            AppDebugServer.log("ACCESSIBILITY", "Watching session started: $pkg")
        }
        blockLastStampMs = now
        if (blockAccumMs >= WARN_MINUTES * 60_000L && consultedPkg != pkg && warnedPkg != pkg) {
            val lastVerdict = verdictAt[pkg] ?: 0L
            if (now - lastVerdict >= VERDICT_COOLDOWN_MINUTES * 60_000L) {
                consultedPkg = pkg
                consultVerdict1(pkg, blockAccumMs / 60_000L)
            } else {
                // She already ruled on this app recently — don't nag her again.
                consultedPkg = pkg
            }
        }
    }

    /**
     * Consult #1 (PLAN_binge_block_v2 Step 4): SHE rules WARN | CUT on this sitting.
     * Bedtime short-circuits to a CUT-biased ask. His last claim + what he did after it
     * ride along so hollow excuses get CUT on sight. Fail-open: errors mean ALLOW.
     */
    private fun consultVerdict1(pkg: String, sessionMin: Long) {
        consultScope.launch {
            try {
                val app = applicationContext as? CompanionApp ?: return@launch
                val c = app.container
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0),
                    ).toString()
                }.getOrDefault(pkg)
                val cal = java.util.Calendar.getInstance()
                val clock = "%02d:%02d".format(
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE),
                )
                val bedtime = runCatching { BedtimeStore.isBedtimeNow() }.getOrDefault(false)
                val claimLine = runCatching {
                    claimStore.clearExpired()
                    val claim = claimStore.getActiveClaim()
                    if (claim == null) "no prior claim"
                    else "last claim '${claim.claimText}' on '${claim.appLabel}' then ${claim.postClaimMin} more min"
                }.getOrDefault("claim log unreadable")
                val extreme = sessionMin >= EXTREME_MINUTES
                AppDebugServer.log("ACCESSIBILITY", "Consult #1 on $label (${sessionMin}min, $clock, bedtime=$bedtime, $claimLine).")
                val agent = AgentEngine(this@ShiinaAccessibilityService, c)
                val bedtimeNote = if (bedtime) " It is past his bedtime — lean CUT." else ""
                val extremeNote = if (extreme) " This sitting is extreme — lean CUT on sight." else ""
                val reply = agent.runAgentLoop(
                    userText = "[BINGE_BLOCK_CONSULT] Daryll has been inside '$label' for ${sessionMin}min straight (now $clock). History: $claimLine.$bedtimeNote$extremeNote You hold the cut-off. Answer with EXACTLY one of: WARN followed by your short deadpan warning line — if he should get a 10-minute chance to justify himself. CUT followed by lock minutes (a number) followed by your short deadpan lock line — if he is mindless-rotting, past bedtime, abusing a hollow claim, or the sitting is extreme. ALLOW — if he is reading, creating, talking, or handling business. Speech only, no asterisks.",
                    initialTone = c.moodEngine.currentMood(),
                    initialMode = com.shiina.mobile.character.CharacterMode.WANDER.name,
                    isSystemTrigger = true,
                ).trim()
                verdictAt[pkg] = System.currentTimeMillis()
                when {
                    isAllow(reply) -> {
                        AppDebugServer.log("ACCESSIBILITY", "Shiina verdict on $label: ALLOW.")
                    }
                    reply.startsWith("CUT", ignoreCase = true) -> {
                        val (minutes, line) = parseCut(reply)
                        AppDebugServer.log("ACCESSIBILITY", "Shiina verdict on $label: CUT ${minutes}min. \"$line\"")
                        warnedPkg = null
                        enforceVerdictBlock(pkg, line, minutes)
                    }
                    else -> {
                        val line = reply.removePrefix("WARN").removePrefix("warn").trim().ifEmpty { reply }
                        AppDebugServer.log("ACCESSIBILITY", "Shiina verdict on $label: WARN. \"$line\"")
                        issueWarning(pkg, label, line)
                    }
                }
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "Binge consult #1 failed (fail-open ALLOW): ${e.message}")
                verdictAt[pkg] = System.currentTimeMillis() - 20 * 60_000L // retry in ~10min
            }
        }
    }

    /** CUT <minutes> <line> — minutes fall back to APP_LOCKOUT_MINUTES. */
    private fun parseCut(reply: String): Pair<Long, String> {
        val rest = reply.removePrefix("CUT").removePrefix("cut").trim()
        val parts = rest.split(Regex("\\s+"), limit = 2)
        val minutes = parts.firstOrNull()?.toLongOrNull()
            ?.coerceIn(1L, 180L) ?: APP_LOCKOUT_MINUTES
        val line = if (parts.size > 1) parts[1].trim().ifEmpty { rest } else rest
        return minutes to line
    }

    /** Warn path (Step 5): speak her warning, start the grace clock. Silence = consult #2. */
    private fun issueWarning(pkg: String, label: String, line: String) {
        warnedPkg = pkg
        warnAtMs = System.currentTimeMillis()
        AppDebugServer.log("ACCESSIBILITY", "Warning issued on $label, ${GRACE_MINUTES}min grace.")
        runCatching { com.shiina.mobile.debug.ChatBus.speak(line, "pouty", false) }
        runCatching {
            val app = applicationContext as? CompanionApp ?: return@runCatching
            consultScope.launch {
                runCatching { app.container.chatHistory.addShiina(line) }
            }
        }
        runCatching {
            val grace = Intent(this, ShiinaAccessibilityService::class.java).apply {
                action = ACTION_GRACE_EXPIRED
                putExtra(EXTRA_GRACE_PKG, pkg)
            }
            val pi = android.app.PendingIntent.getService(
                this, GRACE_ALARM_CODE, grace,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.set(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + GRACE_MINUTES * 60_000L, pi,
            )
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Grace alarm schedule failed: ${e.message}")
        }
    }

    private fun cancelGrace() {
        runCatching {
            val grace = Intent(this, ShiinaAccessibilityService::class.java).apply {
                action = ACTION_GRACE_EXPIRED
            }
            val pi = android.app.PendingIntent.getService(
                this, GRACE_ALARM_CODE, grace,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(android.app.AlarmManager::class.java)?.cancel(pi)
        }
    }

    /**
     * Consult #2 (Step 5): he stayed silent past grace. Short ask — she already knows
     * the case. CUT with her minutes + line, or ALLOW as a last mercy.
     */
    private fun consultGraceCut(pkg: String) {
        consultScope.launch {
            try {
                val app = applicationContext as? CompanionApp ?: return@launch
                val c = app.container
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0),
                    ).toString()
                }.getOrDefault(pkg)
                val totalMin = blockAccumMs / 60_000L
                AppDebugServer.log("ACCESSIBILITY", "Consult #2 on $label (silent ${GRACE_MINUTES}min, ${totalMin}min total).")
                val agent = AgentEngine(this@ShiinaAccessibilityService, c)
                val reply = agent.runAgentLoop(
                    userText = "[BINGE_BLOCK_GRACE] Daryll stayed silent ${GRACE_MINUTES}min after your warning on '$label' (${totalMin}min straight total). His silence means cut him off. Answer CUT followed by lock minutes (a number) followed by your short deadpan lock line. Only answer ALLOW as a last mercy if the hour makes rotting harmless. Speech only, no asterisks.",
                    initialTone = c.moodEngine.currentMood(),
                    initialMode = com.shiina.mobile.character.CharacterMode.WANDER.name,
                    isSystemTrigger = true,
                ).trim()
                verdictAt[pkg] = System.currentTimeMillis()
                if (isAllow(reply)) {
                    AppDebugServer.log("ACCESSIBILITY", "Shiina grace verdict on $label: ALLOW (mercy).")
                } else {
                    val (minutes, line) = parseCut(reply.removePrefix("WARN").removePrefix("warn").trim())
                    AppDebugServer.log("ACCESSIBILITY", "Shiina grace verdict on $label: CUT ${minutes}min. \"$line\"")
                    enforceVerdictBlock(pkg, line, minutes)
                }
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "Binge consult #2 failed (fail-open ALLOW): ${e.message}")
                verdictAt[pkg] = System.currentTimeMillis() - 20 * 60_000L
            }
        }
    }

    /**
     * Every chat reply from him lands here (hooked from DebugTalkService.handleTalk).
     * In-grace reply = his word wins: pass granted, claim logged, clock reset.
     * Otherwise, appeal-shaped text ("let me back in", "need in", "unlock") lifts
     * active lockouts and logs the appeal as a claim so abuse shows next consult.
     */
    fun onUserChat(text: String) {
        val now = System.currentTimeMillis()
        val gracePkg = warnedPkg
        if (gracePkg != null && now - warnAtMs <= (GRACE_MINUTES + 2) * 60_000L) {
            warnedPkg = null
            cancelGrace()
            runCatching {
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(gracePkg, 0),
                    ).toString()
                }.getOrDefault(gracePkg)
                claimStore.clearExpired()
                claimStore.saveClaim(label, text.take(200), now)
                claimWatchPkg = gracePkg
            }
            blockPkg = null
            blockAccumMs = 0L
            blockLastStampMs = 0L
            consultedPkg = null
            AppDebugServer.log("ACCESSIBILITY", "Grace pass granted on $gracePkg — claim logged, clock reset.")
            return
        }
        val lower = text.lowercase()
        val isAppeal = lower.contains("back in") || lower.contains("let me in") ||
            lower.contains("need in") || lower.contains("unlock") ||
            (lower.contains("need") && lower.contains("app"))
        if (isAppeal && blockLockouts.isNotEmpty()) {
            val lifted = blockLockouts.keys.toList()
            blockLockouts.clear()
            runCatching {
                claimStore.clearExpired()
                claimStore.saveClaim("appeal", text.take(200), now)
            }
            runCatching {
                val unlock = Intent(this, com.shiina.mobile.character.CharacterOverlayService::class.java).apply {
                    action = com.shiina.mobile.character.CharacterOverlayService.ACTION_UNLOCK
                }
                startService(unlock)
            }
            AppDebugServer.log("ACCESSIBILITY", "Appeal granted (\"$text\") — lifted ${lifted.size} lockout(s), logged.")
        }
    }

    /** Attribute the finished session's minutes to the watched claim, if any. */
    private fun flushClaimWatch() {
        val watch = claimWatchPkg ?: return
        claimWatchPkg = null
        val min = (blockAccumMs / 60_000L).toInt()
        if (min > 0) {
            runCatching {
                claimStore.clearExpired()
                claimStore.addPostClaimMinutes(min)
            }
            AppDebugServer.log("ACCESSIBILITY", "Flushed ${min}min post-claim on $watch.")
        }
    }

    /**
     * Ask Shiina herself to rule on this sitting. Rotting -> she answers with her own
     * lock line and the app gets cut off. Fine (reading, creating, business) -> ALLOW
     * and he keeps the app. Fail-open: any error means ALLOW, never trap him.
     */
    private fun consultShiina(pkg: String, sessionMin: Long) {
        consultScope.launch {
            try {
                val app = applicationContext as? CompanionApp ?: return@launch
                val c = app.container
                val label = runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0),
                    ).toString()
                }.getOrDefault(pkg)
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val minute = java.util.Calendar.getInstance().get(java.util.Calendar.MINUTE)
                val todayMin = runCatching { c.usageReader.getTodayEntertainmentMinutes() }.getOrDefault(0)
                val baseline = runCatching { c.baselineUpdater.getEntertainmentBaseline() }.getOrDefault(0)
                val clock = "%02d:%02d".format(hour, minute)
                AppDebugServer.log("ACCESSIBILITY", "Consulting Shiina on $label (${sessionMin}min straight, ${todayMin}min today).")
                val agent = AgentEngine(this@ShiinaAccessibilityService, c)
                val reply = agent.runAgentLoop(
                    userText = "[BINGE_BLOCK_CONSULT] Daryll has been inside '$label' for ${sessionMin}min straight (screen entertainment today ${todayMin}min, usual ~${baseline}min; now $clock). You hold the cut-off: if this is mindless rotting, answer with ONLY your short deadpan lock line, speech only. If he is reading, creating, talking, or handling business, answer exactly ALLOW.",
                    initialTone = c.moodEngine.currentMood(),
                    initialMode = com.shiina.mobile.character.CharacterMode.WANDER.name,
                    isSystemTrigger = true,
                ).trim()
                verdictAt[pkg] = System.currentTimeMillis()
                if (isAllow(reply)) {
                    AppDebugServer.log("ACCESSIBILITY", "Shiina verdict on $label: ALLOW.")
                } else {
                    AppDebugServer.log("ACCESSIBILITY", "Shiina verdict on $label: CUT. \"$reply\"")
                    enforceVerdictBlock(pkg, reply)
                }
            } catch (e: Exception) {
                AppDebugServer.log("ERROR", "Binge consult failed (fail-open ALLOW): ${e.message}")
                verdictAt[pkg] = System.currentTimeMillis() - 20 * 60_000L // retry in ~10min
            }
        }
    }

    private fun isAllow(reply: String): Boolean {
        if (reply.isBlank() || reply == "(empty reply)") return true
        if (reply.equals("ALLOW", ignoreCase = true) || reply.startsWith("ALLOW")) return true
        if (reply.equals("DONE", ignoreCase = true) || reply.startsWith("{")) return true
        return reply.contains("\"thought\":")
    }

    /** Packages that overlay every app but are never "watching" themselves. */
    private fun isSystemChrome(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p == "android" ||
            p.startsWith("com.android.systemui") ||
            p.startsWith("com.android.keyguard") ||
            p.startsWith("com.huawei.systemmanager") || // Huawei AppLock gate
            p.startsWith("com.huawei.android.launcher") ||
            p.startsWith("com.google.android.gms") ||
            p.startsWith("com.google.android.gsf") ||
            p == "com.shiina.mobile" // talking to her pauses the clock, doesn't reset it
    }

    /** Her verdict was CUT: lock the app for HER minutes with her own words. */
    private fun enforceVerdictBlock(pkg: String, line: String, lockMinutes: Long = APP_LOCKOUT_MINUTES) {
        blockLockouts[pkg] = System.currentTimeMillis() + lockMinutes * 60_000L
        blockCount++
        AppDebugServer.log("ACCESSIBILITY", "Binge-block enforced on $pkg. \"$line\"")
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        showLockedOverlay(line)
        runCatching { com.shiina.mobile.debug.ChatBus.speak(line, "pouty", false) }
        runCatching {
            val app = applicationContext as? CompanionApp ?: return@runCatching
            consultScope.launch {
                runCatching { app.container.chatHistory.addShiina(line) }
            }
        }
        scheduleBlockAutoRelease()
    }

    private fun showLockedOverlay(line: String) {
        runCatching {
            val show = android.content.Intent(this, com.shiina.mobile.character.CharacterOverlayService::class.java).apply {
                action = com.shiina.mobile.character.CharacterOverlayService.ACTION_SHOW
                putExtra(com.shiina.mobile.character.CharacterOverlayService.EXTRA_TONE, "pouty")
                putExtra(com.shiina.mobile.character.CharacterOverlayService.EXTRA_MESSAGE, line)
                putExtra(com.shiina.mobile.character.CharacterOverlayService.EXTRA_MODE, com.shiina.mobile.character.CharacterMode.WANDER.name)
                putExtra(com.shiina.mobile.character.CharacterOverlayService.EXTRA_INTERRUPT, true)
                putExtra(com.shiina.mobile.character.CharacterOverlayService.EXTRA_LOCKED, true)
            }
            startForegroundService(show)
        }
    }

    private fun scheduleBlockAutoRelease() {
        runCatching {
            val unlock = android.content.Intent(this, com.shiina.mobile.character.CharacterOverlayService::class.java).apply {
                action = com.shiina.mobile.character.CharacterOverlayService.ACTION_UNLOCK
            }
            val pi = android.app.PendingIntent.getForegroundService(
                this, 7741, unlock,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(android.app.AlarmManager::class.java)
            am?.set(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + BLOCK_AUTO_RELEASE_MINUTES * 60_000L, pi,
            )
        }.onFailure { e ->
            AppDebugServer.log("ERROR", "Binge-block auto-release schedule failed: ${e.message}")
        }
    }

    /** Yank to Home + LOCKED pouty countdown overlay + voice. Talk unlocks; safety timer releases. */
    private fun enforceBingeBlock(pkg: String, lockLeftMin: Long?) {
        val leftMin = lockLeftMin ?: 0L
        blockCount++
        blockLastEnforceMs = System.currentTimeMillis()
        runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
        
        consultScope.launch {
            val pm = packageManager
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            
            val prompt = "[BINGE_BLOCK_REJECT] Daryll just tried to reopen '$label', but it is still locked for $leftMin more minutes. Say exactly one short, deadpan sentence rejecting him, telling him how many minutes are left. Do not give him an out. No asterisks, speech only."
            
            val engine = com.shiina.mobile.decision.AgentEngine(applicationContext, (applicationContext as CompanionApp).container)
            val rawReply = runCatching { engine.rawQuery(prompt) }.getOrNull()?.trim()?.removePrefix("\"")?.removeSuffix("\"")
            val reply = if (!rawReply.isNullOrBlank()) rawReply else "It is still locked. $leftMin more minutes."
                
            AppDebugServer.log("ACCESSIBILITY", "Binge-block enforced on $pkg. \"$reply\"")
            showLockedOverlay(reply)
            runCatching { com.shiina.mobile.debug.ChatBus.speak(reply, "pouty", false) }
            runCatching {
                val app = applicationContext as? CompanionApp ?: return@launch
                runCatching { app.container.chatHistory.addShiina(reply) }
            }
        }
        scheduleBlockAutoRelease()
    }

    override fun onInterrupt() {
        AppDebugServer.log("ACCESSIBILITY", "ShiinaAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { consultScope.cancel() }
        if (instance === this) {
            instance = null
        }
        AppDebugServer.log("ACCESSIBILITY", "ShiinaAccessibilityService destroyed")
    }

    /**
     * Traverses child nodes of a container to extract text or content descriptions from its descendants.
     * Useful for compound views, bottom navigation tabs, buttons with nested text/icons, and cards.
     */
    private fun extractDescendantLabel(node: AccessibilityNodeInfo): Pair<String, String> {
        val texts = mutableListOf<String>()
        val descs = mutableListOf<String>()

        fun collect(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString()?.trim().orEmpty()
            if (t.isNotBlank() && !texts.contains(t)) texts.add(t)
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (d.isNotBlank() && !descs.contains(d) && !texts.contains(d)) descs.add(d)

            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                if (child != null) {
                    try {
                        collect(child)
                    } finally {
                        runCatching { child.recycle() }
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                try {
                    collect(child)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }
        return Pair(texts.joinToString(" "), descs.joinToString(" "))
    }

    /**
     * Dumps all interactive and labeled UI elements from the active window hierarchy.
     * Assigns sequential element IDs (1..N) and caches their exact screen bounds and centers.
     */
    fun dumpInteractiveElements(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UiElement>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val hasValidBounds = rect.width() >= 12 && rect.height() >= 12

                var text = node.text?.toString()?.trim().orEmpty()
                var desc = node.contentDescription?.toString()?.trim().orEmpty()
                val isClickable = node.isClickable
                val isEditable = node.isEditable
                val isCheckable = node.isCheckable
                val isFocusable = node.isFocusable
                val isInteractive = isClickable || isEditable || isCheckable || isFocusable

                // If this interactive container lacks direct text or desc, extract labels from descendants
                if (isInteractive && (text.isBlank() || desc.isBlank())) {
                    val (dText, dDesc) = extractDescendantLabel(node)
                    if (text.isBlank() && dText.isNotBlank()) text = dText
                    if (desc.isBlank() && dDesc.isNotBlank() && dDesc != text) desc = dDesc
                }

                val label = if (text.isNotBlank()) text else desc
                val hasLabel = label.isNotBlank()

                if (hasValidBounds && (hasLabel || isInteractive)) {
                    val type = node.className?.toString()?.substringAfterLast(".") ?: "View"
                    val elem = UiElement(
                        id = 0,
                        text = text,
                        desc = desc,
                        type = type,
                        centerX = rect.centerX(),
                        centerY = rect.centerY(),
                        bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
                        isClickable = isClickable,
                        isEditable = isEditable,
                    )
                    elements.add(elem)
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        try {
                            traverse(child)
                        } finally {
                            runCatching { child.recycle() }
                        }
                    }
                }
            } finally {
                // handled by caller
            }
        }

        try {
            traverse(root)
        } finally {
            runCatching { root.recycle() }
        }

        // Deduplicate elements with identical bounds and labels
        val seen = HashSet<String>()
        val deduplicated = mutableListOf<UiElement>()
        var nextId = 1
        for (elem in elements) {
            val key = "${elem.bounds}:${elem.text}:${elem.desc}"
            if (seen.add(key)) {
                deduplicated.add(elem.copy(id = nextId++))
            }
        }

        cachedElements.clear()
        deduplicated.forEach { cachedElements[it.id] = it }
        return deduplicated
    }

    /**
     * Clicks a cached element by its ID.
     * Prioritizes physical tap gesture at element center coordinates for maximum reliability
     * across custom views, cards, and video players. Falls back to text click if gesture fails.
     */
    suspend fun clickElementById(id: Int): Boolean {
        val elem = cachedElements[id] ?: return false
        val tapped = tap(elem.centerX.toFloat(), elem.centerY.toFloat())
        if (tapped) return true
        if (elem.text.isNotBlank()) {
            return clickNodeByText(elem.text)
        }
        return false
    }

    /**
     * Dispatches a tap gesture at the specified screen coordinates (x, y).
     * Automatically retries once after a short delay (150ms) if the gesture is cancelled
     * due to a momentary window animation or activity transition.
     */
    suspend fun tap(x: Float, y: Float, retryOnCancel: Boolean = true): Boolean {
        val success = performTapGesture(x, y)
        if (!success && retryOnCancel) {
            com.shiina.mobile.debug.AppDebugServer.log("GESTURE", "Tap was cancelled or failed at ($x, $y), retrying once after 150ms...")
            kotlinx.coroutines.delay(150L)
            return performTapGesture(x, y)
        }
        return success
    }

    private suspend fun performTapGesture(x: Float, y: Float): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                com.shiina.mobile.debug.AppDebugServer.log("GESTURE", "Tap completed at ($x, $y)")
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                com.shiina.mobile.debug.AppDebugServer.log("GESTURE", "Tap cancelled at ($x, $y)")
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched) {
            com.shiina.mobile.debug.AppDebugServer.log("GESTURE", "Tap dispatch failed at ($x, $y)")
            if (cont.isActive) cont.resume(false)
        }
    }

    /**
     * Dispatches a swipe gesture from (startX, startY) to (endX, endY).
     */
    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                cont.resume(false)
            }
        }, null)
        if (!dispatched) {
            cont.resume(false)
        }
    }

    /**
     * Searches the active window hierarchy for an element matching the given text and clicks it.
     */
    fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var curr: AccessibilityNodeInfo? = node
            while (curr != null) {
                if (curr.isClickable) {
                    val clicked = curr.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) return true
                }
                curr = curr.parent
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        return false
    }

    /**
     * Clicks an element by text, with multi-stage fallback (cached element coordinates,
     * accessibility action click, and physical tap with ancestor climbing for collapsed nodes).
     */
    suspend fun clickText(text: String): Boolean {
        val query = text.trim()
        if (query.isBlank()) return false

        // 1. Try cached element match first (contains resolved descendant labels and coordinates)
        val cached = cachedElements.values.firstOrNull {
            it.text.equals(query, ignoreCase = true) || it.desc.equals(query, ignoreCase = true)
        } ?: cachedElements.values.firstOrNull {
            it.text.contains(query, ignoreCase = true) || it.desc.contains(query, ignoreCase = true)
        }
        if (cached != null) {
            val tapped = tap(cached.centerX.toFloat(), cached.centerY.toFloat())
            if (tapped) return true
        }

        // 2. Try accessibility action click
        if (clickNodeByText(query)) return true

        // 3. Fallback to physical tap via node coordinates (with ancestor climbing if bounds are 0)
        val root = rootInActiveWindow ?: return false
        try {
            val nodes = root.findAccessibilityNodeInfosByText(query)
            for (node in nodes) {
                try {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val tapped = tap(rect.centerX().toFloat(), rect.centerY().toFloat())
                        if (tapped) return true
                    } else {
                        // Ancestor climbing for collapsed nodes
                        var ancestor = node.parent
                        while (ancestor != null) {
                            val aRect = android.graphics.Rect()
                            ancestor.getBoundsInScreen(aRect)
                            val parentNode = ancestor.parent
                            if (aRect.width() > 0 && aRect.height() > 0) {
                                val tapped = tap(aRect.centerX().toFloat(), aRect.centerY().toFloat())
                                runCatching { ancestor.recycle() }
                                if (tapped) return true
                                break
                            }
                            runCatching { ancestor.recycle() }
                            ancestor = parentNode
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
        return false
    }

    /**
     * Inputs text into the currently focused input field.
     */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Returns the package name of the active foreground window.
     */
    fun getCurrentPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Clears text from the currently focused input field.
     */
    fun clearInputText(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        return false
    }

    /**
     * Performs a long-press gesture at the specified screen coordinates.
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 600): Boolean = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!dispatched) {
            if (cont.isActive) cont.resume(false)
        }
    }

    /**
     * Traverses the active window hierarchy and returns all visible, readable text chunks.
     * Provides instantaneous screen reading without OCR latency.
     */
    fun getVisibleScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val texts = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val candidate = when {
                    !text.isNullOrBlank() && !desc.isNullOrBlank() && text != desc -> "$text ($desc)"
                    !text.isNullOrBlank() -> text
                    !desc.isNullOrBlank() -> desc
                    else -> null
                }
                if (candidate != null && candidate.length > 1 && seen.add(candidate)) {
                    texts.add(candidate)
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        try {
                            traverse(child)
                        } finally {
                            runCatching { child.recycle() }
                        }
                    }
                }
            } finally {
                // handled by caller
            }
        }

        try {
            traverse(root)
        } finally {
            runCatching { root.recycle() }
        }
        return texts.joinToString("\n")
    }

    /**
     * Direct node scroll forward or backward on scrollable container.
     */
    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = when (direction.lowercase().trim()) {
            "up", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            "down", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val found = findScrollable(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        val scrollable = findScrollable(root)
        return scrollable?.performAction(action) ?: false
    }

    /**
     * Performs a global navigation action (back, home, recents, notifications).
     */
    fun pressGlobal(action: String): Boolean {
        return when (action.lowercase().trim()) {
            "back", "dismiss_keyboard", "hide_keyboard" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents", "app_switch" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications", "notification_shade" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) else performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "lock_screen", "lock" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            else -> false
        }
    }

    /**
     * Captures a screenshot of the active display directly via AccessibilityService (Android 11+).
     * Bypasses MediaProjection consent requirements.
     */
    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                executor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val hwBuffer = result.hardwareBuffer
                            val colorSpace = result.colorSpace
                            val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                            val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBuffer.close()
                            cont.resume(copy)
                        } catch (e: Exception) {
                            cont.resume(null)
                        } finally {
                            executor.shutdown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        AppDebugServer.log("ACCESSIBILITY", "takeScreenshot failed: code=$errorCode")
                        executor.shutdown()
                        cont.resume(null)
                    }
                }
            )
        }
    }
}
