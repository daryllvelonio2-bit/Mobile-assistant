package com.shiina.mobile.observation

data class DigestNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

/**
 * Phase 1 (Smart Contextual Briefings): lightweight, in-memory digest of the
 * notifications currently sitting in the shade. Fed by the app's
 * NotificationListenerService; pruned by age so it only ever reflects
 * "recent, still-relevant" pings. Pure JVM (no Android types) so the count,
 * sender grouping and pruning rules are unit-testable.
 */
object NotificationDigest {

    private const val MAX_ENTRIES = 60
    private const val WINDOW_MS = 12 * 3_600_000L

    private val entries = java.util.concurrent.ConcurrentHashMap<String, DigestNotification>()

    /** Replace the whole digest (used when the listener (re)connects and seeds active notifications). */
    fun seed(items: List<DigestNotification>) {
        entries.clear()
        items.forEach { entries[it.key] = it }
        prune()
        // enforce the newest-first cap
        snapshot()
    }

    fun onPosted(item: DigestNotification) {
        entries[item.key] = item
        snapshot()
    }

    fun onRemoved(key: String) {
        entries.remove(key)
    }

    fun snapshot(): List<DigestNotification> {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        entries.entries.removeIf { it.value.postedAt < cutoff }
        val sorted = entries.values.sortedByDescending { it.postedAt }
        if (sorted.size > MAX_ENTRIES) {
            sorted.drop(MAX_ENTRIES).forEach { entries.remove(it.key) }
        }
        return entries.values.sortedByDescending { it.postedAt }
    }

    fun unreadCount(): Int = snapshot().size

    /** e.g. ["WhatsApp (3)", "Gmail (2)"] — the noisiest senders first. */
    fun topSenders(limit: Int = 4): List<String> = snapshot()
        .groupingBy { it.appLabel.ifBlank { it.packageName } }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { "${it.key} (${it.value})" }

    /** Test/teardown helper. */
    fun clear() = entries.clear()

    private fun prune(): Int {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        val before = entries.size
        entries.entries.removeIf { it.value.postedAt < cutoff }
        return before - entries.size
    }
}
