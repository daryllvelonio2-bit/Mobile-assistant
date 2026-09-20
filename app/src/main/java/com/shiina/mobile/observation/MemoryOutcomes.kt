package com.shiina.mobile.observation

import com.shiina.mobile.data.db.MemoryEpisodeDao
import com.shiina.mobile.debug.AppDebugServer

/**
 * Phase 2: outcome tracking on logged episodes. Every signal is already
 * observable in-app — no new permissions.
 * - shown: overlay actually rendered for an interrupt decision.
 * - dismissed: overlay hidden within 60s of that show.
 * - talkedBack: Talk reply within 5 min of the interrupt.
 * - acted: the recommended action was executed.
 * Also owns the real cooldown gate: recent dismissals suppress the next
 * interrupt instead of the prompt's honor-system cooldown.
 */
class MemoryOutcomes(private val dao: MemoryEpisodeDao) {

    /** Overlay rendered for the latest interrupt decision. */
    suspend fun markShown() {
        runCatching {
            val ep = dao.lastInterrupt() ?: return
            dao.markShown(ep.id)
        }.onFailure { log(it) }
    }

    /** Overlay hidden: dismiss if it lands inside the 60s window. */
    suspend fun onOverlayHidden() {
        runCatching {
            val ep = dao.lastInterrupt() ?: return
            if (System.currentTimeMillis() - ep.timestampMillis <= DISMISS_WINDOW_MS) {
                dao.markDismissed(ep.id)
                AppDebugServer.log("MEMORY", "Episode ${ep.id} dismissed (hidden within 60s)")
            }
        }.onFailure { log(it) }
    }

    /** Talk reply received: talkedBack if inside the 5 min window. */
    suspend fun onTalkReply() {
        runCatching {
            val ep = dao.lastInterrupt() ?: return
            if (System.currentTimeMillis() - ep.timestampMillis <= TALK_WINDOW_MS) {
                dao.markTalkedBack(ep.id)
                AppDebugServer.log("MEMORY", "Episode ${ep.id} talkedBack")
            }
        }.onFailure { log(it) }
    }

    /** Recommended action executed by ActionExecutor. */
    suspend fun onActionExecuted() {
        runCatching {
            val ep = dao.lastInterrupt() ?: return
            if (ep.action != "NONE") {
                dao.markActed(ep.id)
                AppDebugServer.log("MEMORY", "Episode ${ep.id} acted (${ep.action})")
            }
        }.onFailure { log(it) }
    }

    /** True when recent dismissals warrant suppressing a new interrupt. */
    suspend fun cooldownActive(): Boolean = runCatching {
        val n = dao.dismissedCountSince(System.currentTimeMillis() - COOLDOWN_WINDOW_MS)
        if (n >= DISMISS_THRESHOLD) {
            AppDebugServer.log("MEMORY", "Cooldown: $n dismissals in the last hour")
        }
        n >= DISMISS_THRESHOLD
    }.getOrDefault(false)

    private fun log(e: Throwable) {
        AppDebugServer.log("ERROR", "MemoryOutcomes failed: ${e.message}")
    }

    companion object {
        private const val DISMISS_WINDOW_MS = 60_000L
        private const val TALK_WINDOW_MS = 5 * 60_000L
        private const val COOLDOWN_WINDOW_MS = 60 * 60_000L
        private const val DISMISS_THRESHOLD = 2
    }
}