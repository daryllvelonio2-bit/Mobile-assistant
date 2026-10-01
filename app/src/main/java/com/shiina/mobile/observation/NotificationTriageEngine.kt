package com.shiina.mobile.observation

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coarse buckets a triaged notification falls into (R4 / BUILD_PLAN Phase 4). */
enum class TriageCategory(val id: String, val label: String) {
    MESSAGE("message", "Messages & calls"),
    EMAIL("email", "Email"),
    SOCIAL("social", "Social"),
    SYSTEM("system", "System"),
    PROMO("promo", "Promotions"),
    OTHER("other", "Other");

    /** Crucial categories are surfaced individually; the rest collapse into one digest. */
    val crucial: Boolean get() = this == MESSAGE || this == EMAIL

    companion object {
        fun fromId(id: String): TriageCategory? = entries.firstOrNull { it.id == id }
    }
}

/** One classified, truncated notification. Never carries more text than the caps below. */
data class TriageNotification(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val category: TriageCategory,
    val postedAt: Long,
) {
    val crucial: Boolean get() = category.crucial
}

/**
 * R4 (BUILD_PLAN Phase 4): Notification Triage & Digest.
 *
 * Pure JVM — no Android types — so the classifier, dedupe, mute rules and digest
 * grouping are all covered by plain unit tests. The single existing
 * `NotificationListenerService` (`MusicNotificationListener`) delegates non-media
 * notifications here; Android forbids registering a second listener.
 *
 * State is in-memory by design (no Room migration): a rolling, deduped queue of
 * recent pings. Bodies are truncated on entry and only leave the device through
 * the model prompt when the user has opted into summarization.
 */
object NotificationTriageEngine {

    const val MAX_ENTRIES = 80
    const val WINDOW_MS = 6 * 3_600_000L
    const val MAX_TITLE_CHARS = 120
    const val MAX_TEXT_CHARS = 280

    @Volatile private var enabled: Boolean = true
    // AUDIT N1: safe default before `configure()` runs — notification text must
    // never reach the model until the user opts in.
    @Volatile private var summarizationEnabled: Boolean = false
    @Volatile private var mutedPackages: Set<String> = emptySet()
    @Volatile private var mutedCategories: Set<TriageCategory> = emptySet()

    private val queue = ConcurrentHashMap<String, TriageNotification>()
    private val _pending = MutableStateFlow<List<TriageNotification>>(emptyList())

    /** Newest-first live view of the queue for the overlay pill / settings. */
    val pending: StateFlow<List<TriageNotification>> = _pending.asStateFlow()

    /** Pushes the persisted user preferences into the (context-free) engine. */
    fun configure(
        enabled: Boolean,
        summarizationEnabled: Boolean,
        mutedPackages: Set<String>,
        mutedCategories: Set<TriageCategory>,
    ) {
        this.enabled = enabled
        this.summarizationEnabled = summarizationEnabled
        this.mutedPackages = mutedPackages
        this.mutedCategories = mutedCategories
        refresh()
    }

    fun isEnabled(): Boolean = enabled
    fun isSummarizationEnabled(): Boolean = summarizationEnabled
    fun mutedPackageSet(): Set<String> = mutedPackages

    // ---- ingestion ----------------------------------------------------------

    /**
     * Ingests one posted notification. Returns the enqueued entry, or null when it
     * was ignored (media / ongoing / group-summary / own app), muted, disabled or a
     * duplicate of an identical (package, key, postTime).
     */
    fun onPosted(
        packageName: String,
        key: String,
        title: String,
        text: String,
        category: String?,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        isMedia: Boolean,
        appLabel: String,
        postedAt: Long,
        ownPackage: String = "com.shiina.mobile",
        nowMillis: Long = System.currentTimeMillis(),
    ): TriageNotification? {
        if (!enabled) return null
        if (isNoise(packageName, isOngoing, isGroupSummary, isMedia, ownPackage)) return null
        if (packageName in mutedPackages) return null

        val bucket = classify(packageName, category, title, text)
        if (bucket in mutedCategories) return null

        val safeKey = key.ifBlank { "$packageName:$postedAt" }
        // Update semantics: a newer post of the same (pkg, key) replaces the stale one.
        queue.entries.removeIf {
            it.value.packageName == packageName && it.value.key == safeKey && it.value.postedAt != postedAt
        }
        val dedupeKey = dedupeKey(packageName, safeKey, postedAt)
        if (queue.containsKey(dedupeKey)) return null

        val entry = TriageNotification(
            key = safeKey,
            packageName = packageName,
            appLabel = appLabel.ifBlank { packageName },
            title = truncate(title, MAX_TITLE_CHARS),
            text = truncate(text, MAX_TEXT_CHARS),
            category = bucket,
            postedAt = postedAt,
        )
        queue[dedupeKey] = entry
        prune(nowMillis)
        refresh()
        return entry
    }

    /** Removes an entry when the shade notification is dismissed. */
    fun onRemoved(key: String, packageName: String) {
        queue.entries.removeIf { it.value.packageName == packageName && it.value.key == key }
        refresh()
    }

    fun dedupeKey(packageName: String, key: String, postedAt: Long): String = "$packageName|$key|$postedAt"

    // ---- filtering / classification (pure, testable) ------------------------

    /** Media, foreground-service (ongoing) pings, group summaries and our own app are noise. */
    fun isNoise(
        packageName: String,
        isOngoing: Boolean,
        isGroupSummary: Boolean,
        isMedia: Boolean,
        ownPackage: String = "com.shiina.mobile",
    ): Boolean = packageName == ownPackage || isOngoing || isGroupSummary || isMedia

    /**
     * Rule-based v1 classifier. Prefers the system category, falls back to package
     * heuristics, then light content heuristics (needed because `adb cmd notification
     * post` stamps every ping with the shell package).
     */
    fun classify(packageName: String, category: String?, title: String, text: String): TriageCategory {
        when (category?.lowercase()) {
            "msg", "call", "missed_call", "voicemail" -> return TriageCategory.MESSAGE
            "email" -> return TriageCategory.EMAIL
            "social" -> return TriageCategory.SOCIAL
            "promo", "recommendation" -> return TriageCategory.PROMO
            "sys", "service", "progress", "status", "alarm", "event", "reminder",
            "transport", "navigation", "location_sharing", "workout", "stopwatch",
            -> return TriageCategory.SYSTEM
        }

        val pkg = packageName.lowercase()
        when {
            pkg.matchesAny("whatsapp", "telegram", "signal", "messenger", "sms", "mms", "chat", "viber", "line", "wechat", "discord", "skype") ->
                return TriageCategory.MESSAGE
            pkg.matchesAny("gmail", "mail", "outlook", "proton") -> return TriageCategory.EMAIL
            pkg.matchesAny("facebook", "instagram", "twitter", "tiktok", "snapchat", "reddit", "linkedin", "social") ->
                return TriageCategory.SOCIAL
            pkg.matchesAny("shop", "store", "deal", "offer", "amazon", "flipkart", "lazada", "coupon", "ads") ->
                return TriageCategory.PROMO
            pkg.matchesAny("android", "system", "settings", "packageinstaller") -> return TriageCategory.SYSTEM
        }

        val haystack = "$title $text".lowercase()
        when {
            haystack.matchesAny("payment", "received", "credited", "debited", "otp", "one-time code", "verification code") ->
                return TriageCategory.MESSAGE
            haystack.matchesAny("% off", "sale", "discount", "coupon", "deal", "offer ends") ->
                return TriageCategory.PROMO
            haystack.matchesAny("liked your", "commented", "started following", "tagged you") ->
                return TriageCategory.SOCIAL
            haystack.matchesAny("unread email", "new email", "inbox") -> return TriageCategory.EMAIL
            haystack.matchesAny("update available", "battery", "storage", "wi-fi", "wifi", "system") ->
                return TriageCategory.SYSTEM
        }
        return TriageCategory.OTHER
    }

    // ---- read surface -------------------------------------------------------

    fun snapshot(nowMillis: Long = System.currentTimeMillis()): List<TriageNotification> {
        prune(nowMillis)
        return queue.values.sortedByDescending { it.postedAt }
    }

    fun unreadCount(nowMillis: Long = System.currentTimeMillis()): Int = snapshot(nowMillis).size

    /** Crucial pings — surfaced individually. */
    fun crucial(nowMillis: Long = System.currentTimeMillis()): List<TriageNotification> =
        snapshot(nowMillis).filter { it.crucial }

    /** Everything non-crucial, collapsed into the single digest. */
    fun digestItems(nowMillis: Long = System.currentTimeMillis()): List<TriageNotification> =
        snapshot(nowMillis).filterNot { it.crucial }

    /** e.g. "WhatsApp (3), Instagram (2)" — sends first, noisy senders on top. */
    fun digestSummary(nowMillis: Long = System.currentTimeMillis()): String = digestItems(nowMillis)
        .groupingBy { it.appLabel }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .joinToString(", ") { "${it.key} (${it.value})" }

    /**
     * Grounded block for the model. Bodies (already-truncated title/text) are only
     * included when the user has enabled summarization; otherwise counts only.
     */
    fun promptBlock(nowMillis: Long = System.currentTimeMillis()): String {
        val items = snapshot(nowMillis)
        if (items.isEmpty()) return ""
        val includeBodies = summarizationEnabled
        val sb = StringBuilder()
        sb.appendLine("# Notification Triage (on-device)")
        sb.appendLine("- pending pings: ${items.size} (window: last 6h)")
        val surfaced = items.filter { it.crucial }
        if (surfaced.isNotEmpty()) {
            sb.appendLine("- surfaced individually (important):")
            surfaced.take(5).forEach { n ->
                // AUDIT N1: without the opt-in, only the sender/category leaves the
                // device — never the notification title or body text.
                val line = if (includeBodies) {
                    "[${n.category.id}] ${n.appLabel}: ${n.title}${detail(n)}"
                } else {
                    "[${n.category.id}] ${n.appLabel}"
                }
                sb.appendLine("  · $line")
            }
        }
        val digest = items.filterNot { it.crucial }
        if (digest.isNotEmpty()) {
            sb.appendLine("- grouped low-priority digest: ${digestSummary(nowMillis)}")
            if (includeBodies) {
                digest.take(8).forEach { n ->
                    sb.appendLine("  · [${n.category.id}] ${n.appLabel}: ${n.title}${detail(n)}")
                }
            }
        }
        if (!includeBodies) {
            sb.appendLine("- (notification text stays on-device; enable \"Send notification text to model\" in Settings for grounded detail)")
        }
        return sb.toString().trimEnd()
    }

    private fun detail(n: TriageNotification): String =
        if (n.text.isBlank() || n.text == n.title) "" else " — ${n.text}"

    /** Test/teardown helper. */
    fun clear() {
        queue.clear()
        refresh()
    }

    // ---- internals ----------------------------------------------------------

    private fun prune(nowMillis: Long): Int {
        val cutoff = nowMillis - WINDOW_MS
        val before = queue.size
        queue.entries.removeIf { it.value.postedAt < cutoff }
        if (queue.size > MAX_ENTRIES) {
            queue.values.sortedByDescending { it.postedAt }
                .drop(MAX_ENTRIES)
                .forEach { queue.remove(dedupeKey(it.packageName, it.key, it.postedAt)) }
        }
        return before - queue.size
    }

    private fun refresh() {
        _pending.value = queue.values.sortedByDescending { it.postedAt }
    }

    private fun truncate(value: String, max: Int): String {
        val clean = value.trim()
        return if (clean.length <= max) clean else clean.take(max - 1).trimEnd() + "…"
    }

    private fun String.matchesAny(vararg needles: String): Boolean = needles.any { contains(it) }
}
