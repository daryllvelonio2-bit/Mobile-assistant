package com.shiina.mobile.observation

import com.shiina.mobile.data.settings.SettingsRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AUDIT N1 (privacy): notification title/text must only reach the model prompt
 * when the user has explicitly opted in. Pure JVM — the triage engine has no
 * Android types, so the prompt block can be asserted directly.
 */
class NotificationTriageEngineTest {

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() = NotificationTriageEngine.clear()

    @After
    fun tearDown() = NotificationTriageEngine.clear()

    private fun postMessage() {
        NotificationTriageEngine.onPosted(
            packageName = "com.whatsapp",
            key = "k1",
            title = "Alice",
            text = "dinner at 8?",
            category = "msg",
            isOngoing = false,
            isGroupSummary = false,
            isMedia = false,
            appLabel = "WhatsApp",
            postedAt = now,
            nowMillis = now,
        )
    }

    private fun postPromo() {
        NotificationTriageEngine.onPosted(
            packageName = "com.amazon.shop",
            key = "k2",
            title = "Flash sale",
            text = "50% off everything today",
            category = "promo",
            isOngoing = false,
            isGroupSummary = false,
            isMedia = false,
            appLabel = "Amazon",
            postedAt = now,
            nowMillis = now,
        )
    }

    @Test
    fun defaultSummarizationIsOff() {
        assertFalse(
            "notification text must be opt-in (AUDIT N1)",
            SettingsRepository.DEFAULT_NOTIFICATION_SUMMARIZATION,
        )
        assertTrue(SettingsRepository.DEFAULT_NOTIFICATION_TRIAGE)
    }

    @Test
    fun summarizationOff_leaksNeitherTitleNorBody() {
        NotificationTriageEngine.configure(
            enabled = true,
            summarizationEnabled = false,
            mutedPackages = emptySet(),
            mutedCategories = emptySet(),
        )
        postMessage()
        postPromo()

        val block = NotificationTriageEngine.promptBlock(now)

        // Senders / categories / counts are allowed.
        assertTrue(block.contains("WhatsApp"))
        assertTrue(block.contains("message"))
        assertTrue(block.contains("Amazon (1)"))
        // Titles and bodies must not appear anywhere in the block.
        assertFalse("title leaked", block.contains("Alice"))
        assertFalse("body leaked", block.contains("dinner at 8"))
        assertFalse("promo title leaked", block.contains("Flash sale"))
        assertFalse("promo body leaked", block.contains("50% off"))
        assertTrue("must tell the model text stayed on-device", block.contains("stays on-device"))
    }

    @Test
    fun summarizationOn_includesTitleAndBody() {
        NotificationTriageEngine.configure(
            enabled = true,
            summarizationEnabled = true,
            mutedPackages = emptySet(),
            mutedCategories = emptySet(),
        )
        postMessage()

        val block = NotificationTriageEngine.promptBlock(now)

        assertTrue(block.contains("Alice"))
        assertTrue(block.contains("dinner at 8?"))
        assertFalse(block.contains("stays on-device"))
    }

    @Test
    fun configureReportsSummarizationState() {
        NotificationTriageEngine.configure(true, false, emptySet(), emptySet())
        assertFalse(NotificationTriageEngine.isSummarizationEnabled())
        NotificationTriageEngine.configure(true, true, emptySet(), emptySet())
        assertTrue(NotificationTriageEngine.isSummarizationEnabled())
    }
}
