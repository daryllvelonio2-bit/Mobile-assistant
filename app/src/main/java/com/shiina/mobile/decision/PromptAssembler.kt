package com.shiina.mobile.decision

import android.content.Context
import com.shiina.mobile.di.AppContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Assembles the full system context, behavioral playbooks, dynamic learning knowledge,
 * live device telemetry, and execution state prompts for Shiina.
 *
 * Guarantees a compact, focused system context (~5k characters) providing:
 * - Authentic human persona, non-repetitive conversational engine, dignity & agency.
 * - Comprehensive operational skills & playbooks (calm execution, app navigation, zero hallucination).
 * - Real-world situational scenario awareness (late-night active, battery alerts, morning routines).
 * - Categorized dynamic lifelong learnings & user profile.
 */
object PromptAssembler {

    suspend fun buildBasePrompt(
        context: Context,
        container: AppContainer,
        userText: String,
        tone: String,
        includeTools: Boolean = false,
        isSystemTrigger: Boolean = false,
        hoursInactive: Double = 0.0,
        lastInteractionMillis: Long = 0L,
    ): String = buildString {
        val P = ShiinaPrompts
        appendLine(P.GLOBAL_RULES)
        appendLine()
        appendLine(P.moodPrompt(P.moodForTone(tone)))
        appendLine()
        appendLine(P.TALK_DRIVE)
        appendLine()

        // 1. Comprehensive Operational Skills & Behavioral Playbooks
        appendLine(ShiinaSkills.ALL_SKILLS_MANUAL)
        appendLine()

        val userName = container.learnedMemoryManager.get("user_name")
            ?: runCatching { container.memoryStore.factValue("user_name") }.getOrNull()
        if (!userName.isNullOrBlank()) {
            appendLine("- User Profile: Their name is $userName. Address them naturally as a close companion.")
        } else {
            appendLine("- User Profile: You do not know their name yet — never invent or guess one.")
        }
        appendLine()

        if (includeTools) {
            appendLine(P.TOOL_SPEC)
            appendLine()
            appendLine(ToolCatalog.TOOLSET_OVERVIEW)
            appendLine()
        }

        val screenRes = container.screenMetrics.toString()
        val timeInfo = P.timeLine(context)
        val senses = runCatching { container.deviceSenses.snapshot() }.getOrDefault("{}")
        val moodBlock = runCatching { container.moodEngine.promptBlock() }.getOrNull().orEmpty()

        val now = System.currentTimeMillis()
        val prevInteraction = if (lastInteractionMillis > 0L) {
            lastInteractionMillis
        } else {
            runCatching { container.chatHistory.getPreviousTurnTimestamp(userText) }.getOrDefault(0L)
        }

        val effectiveHoursInactive = if (prevInteraction > 0L) {
            ((now - prevInteraction).coerceAtLeast(0L) / 3_600_000.0)
        } else if (hoursInactive > 0.0) {
            hoursInactive
        } else {
            runCatching { container.userActivityTracker.getHoursSinceLastActive() }.getOrDefault(0.0)
        }

        appendLine("# Live Context, Time & Session State")
        appendLine("- $timeInfo")
        appendLine("- Senses: $senses")
        appendLine("- Screen Resolution: $screenRes (normalized coordinates 0..1000: x: 0..1000, y: 0..1000)")
        if (moodBlock.isNotBlank()) appendLine("- $moodBlock")

        if (prevInteraction > 0L) {
            val diff = (now - prevInteraction).coerceAtLeast(0L)
            val diffMinutes = diff / 60_000L
            val lastTimeStr = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(prevInteraction))
            if (diffMinutes >= 30) {
                val gapStr = if (diffMinutes < 60) "${diffMinutes} minutes" else if (diffMinutes < 90) "about an hour" else "${"%.1f".format(diffMinutes / 60.0)} hours"
                appendLine("- Conversational Recency: [NEW SESSION — User returned after $gapStr, last active at $lastTimeStr]. The previous interaction has concluded. Do not obsess over or drag up concluded topics from earlier hours. Respond to their immediate message and greeting in the present moment.")
            } else if (diffMinutes >= 5) {
                appendLine("- Conversational Recency: [Recent pause — $diffMinutes minutes since last message at $lastTimeStr].")
            } else {
                appendLine("- Conversational Recency: [Active continuous conversation — last exchange was ${diff / 1000L}s ago].")
            }
        } else {
            appendLine("- Conversational Recency: [First interaction of session / fresh start]. Greet warmly in the present moment.")
        }
        appendLine()

        // 2. Real-World Situational Scenarios (Late-night, morning, battery, neglect, absence return)
        val scenarios = runCatching {
            container.dynamicLearningEngine.evaluateRealWorldScenarios(senses, effectiveHoursInactive)
        }.getOrDefault(emptyList())

        if (scenarios.isNotEmpty()) {
            appendLine("# Real-World Physical Context & Situational Scenarios")
            appendLine("The user is currently situated in these real physical circumstances. Ground your tone, empathy, and advice in them:")
            for (sc in scenarios) {
                appendLine("- $sc")
            }
            appendLine()
        }

        // 3. Categorized Dynamic Lifelong Learnings & Facts
        val rawMemory = runCatching { container.learnedMemoryManager.readMemory() }.getOrDefault("")
        val allFacts = runCatching { container.memoryStore.allFacts() }.getOrDefault(emptyList())
        val categorizedLearnings = runCatching {
            container.dynamicLearningEngine.buildCategorizedKnowledge(rawMemory, allFacts)
        }.getOrDefault("")

        if (categorizedLearnings.isNotBlank()) {
            appendLine(categorizedLearnings)
            appendLine()
        } else if (rawMemory.isNotBlank()) {
            appendLine("# Learned Memory (Persistent Knowledge)")
            appendLine(rawMemory.trim())
            appendLine()
        }

        // 4. Procedural Navigation Memories & Reusable App Workflows
        val proceduralMemories = runCatching {
            container.proceduralMemoryStore.getProceduresSummaryForPrompt(currentGoal = userText)
        }.getOrDefault("")
        if (proceduralMemories.isNotBlank()) {
            appendLine(proceduralMemories)
            appendLine()
        }

        // 5. Episodic Memory & Context
        val memory = runCatching { container.memoryContext.build() }.getOrDefault("")
        if (memory.isNotBlank()) {
            appendLine("# Episodic Memory & Recent Interaction Context")
            appendLine(memory.trim())
            appendLine()
        }

        // 5. Conversation History
        val history = runCatching { container.chatHistory.buildConversationContext() }.getOrDefault("")
        if (history.isNotBlank()) {
            appendLine("# Conversation History")
            appendLine(history.trim())
            appendLine()
        }

        val nowFmt = SimpleDateFormat("yyyy-MM-dd h:mm a", Locale.getDefault()).format(Date())
        if (isSystemTrigger) {
            appendLine("# Event Trigger")
            appendLine("[$nowFmt] $userText")
        } else {
            appendLine("# Current User Message")
            appendLine("[$nowFmt] User: $userText")
        }
    }

    fun buildExecutionStatePrompt(
        basePrompt: String,
        currentStep: Int,
        userText: String,
        stepsTaken: List<String>,
        lastThought: String,
        lastTool: String,
        lastObservation: String,
        hasScreenshotAttached: Boolean,
        foregroundApp: String? = null,
    ): String = buildString {
        append(basePrompt)
        appendLine()
        appendLine()
        appendLine("# Agent Execution State")
        appendLine("- Current step: #$currentStep")
        appendLine("- Primary Goal: \"$userText\"")
        if (foregroundApp != null && foregroundApp.isNotBlank()) {
            appendLine("- Active Foreground App: $foregroundApp")
        }
        appendLine("- Steps taken so far: ${stepsTaken.joinToString("; ").ifEmpty { "none" }}")
        if (lastThought.isNotBlank()) {
            appendLine("- Your previous thought: $lastThought")
        }
        appendLine("- Observation from $lastTool: $lastObservation")
        if (hasScreenshotAttached) {
            appendLine("- Visual Verification: The live screen frame is attached as an image. Inspect the screen carefully to verify whether your action succeeded and whether \"$userText\" has been completed.")
        }
        appendLine("- CALM EXECUTION DIRECTIVE: Never rush. If the screen is loading (splash screen, spinner, white/dark blank surface, or content rendering), call WAIT (1-5 seconds) with status 'CONTINUE' to allow it to settle. Only proceed when content is visible.")
        appendLine("Decide your next tactical action. Unfinished → tool + 'CONTINUE'. Verified complete or pure chat → tool 'NONE', status 'DONE', with the actual observed results spoken in the message (names, titles, values) — never a bare 'done'.")
    }

    fun buildFinalSpeechPrompt(
        basePromptNoTools: String,
        stepsTaken: List<String>,
    ): String = buildString {
        append(basePromptNoTools)
        appendLine()
        appendLine()
        if (stepsTaken.isNotEmpty()) {
            appendLine("# Agent Execution Summary")
            appendLine("- Steps taken: ${stepsTaken.joinToString("; ")}")
            appendLine()
        }
        appendLine("The task is completed. Speak as Shiina, plain conversational text ONLY (no JSON, no meta talk). Short default (1-2 sentences); if the user asked for a list, give the full list from the steps above.")
    }
}
