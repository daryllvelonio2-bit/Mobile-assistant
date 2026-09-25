package com.shiina.mobile.decision

import android.content.Context
import android.util.Base64
import com.shiina.mobile.debug.AppDebugServer
import com.shiina.mobile.debug.ChatBus
import com.shiina.mobile.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Core agentic execution engine.
 * Coordinates the autonomous Think-Act-Observe-Verify loop for Shiina.
 * Operates without hardcoded task bypasses, grounding every step on device senses,
 * live screen perception, and real Android hardware/OS receipts.
 */
class AgentEngine(
    private val context: Context,
    private val container: AppContainer,
) {
    private val http get() = container.httpClient

    companion object {
        const val MAX_AGENT_STEPS = 25
    }

data class AgentRunResult(
    val message: String,
    val nextCheckInMinutes: Int,
)

    /**
     * Executes an autonomous Think-Act-Observe-Verify agent loop for a user message or system trigger.
     */
    suspend fun runAgentLoop(
        userText: String,
        initialTone: String,
        initialMode: String,
        isSystemTrigger: Boolean = false,
        hoursInactive: Double = 0.0,
        onProgress: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val result = runAgentLoopInternal(userText, initialTone, initialMode, isSystemTrigger, hoursInactive, onProgress)
        result.message
    }

    suspend fun rawQuery(prompt: String, tone: String = "calm", attachShot: Boolean = false): String {
        return postText(prompt, attachShot, false)
    }

    suspend fun runAgentLoopInternal(
        userText: String,
        initialTone: String,
        initialMode: String,
        isSystemTrigger: Boolean = false,
        hoursInactive: Double = 0.0,
        onProgress: (String) -> Unit = {},
    ): AgentRunResult = withContext(Dispatchers.IO) {
        ChatBus.beginRun()
        var currentTone = initialTone
        var shouldAttachShot = false
        var chosenNextInterval = -1

        try {
            val previousInteractionTime = runCatching {
                container.chatHistory.getPreviousTurnTimestamp(userText)
            }.getOrNull() ?: 0L

            val effectiveHoursInactive = if (previousInteractionTime > 0L) {
                ((System.currentTimeMillis() - previousInteractionTime).coerceAtLeast(0L) / 3_600_000.0)
            } else if (hoursInactive > 0.0) {
                hoursInactive
            } else {
                runCatching { container.userActivityTracker.getHoursSinceLastActive() }.getOrDefault(0.0)
            }

            if (!isSystemTrigger) {
                runCatching { container.chatHistory.addUser(userText) }
            }

            val stepsTaken = mutableListOf<String>()
            val executedNavSteps = mutableListOf<com.shiina.mobile.memory.ProcedureStep>()
            var answer = ""
            var calls = 0

            val basePromptWithTools = PromptAssembler.buildBasePrompt(
                context = context,
                container = container,
                userText = userText,
                tone = currentTone,
                includeTools = true,
                isSystemTrigger = isSystemTrigger,
                hoursInactive = effectiveHoursInactive,
                lastInteractionMillis = previousInteractionTime,
            )

            var currentPrompt = basePromptWithTools

            while (calls < MAX_AGENT_STEPS) {
                // Check if user requested to stop execution
                if (ChatBus.stopRequested) {
                    AppDebugServer.log("AGENT_LOOP", "Agent loop halted: stop requested by user.")
                    return@withContext AgentRunResult("Action stopped.", -1)
                }

                val raw = postText(currentPrompt, attachShot = shouldAttachShot, structuredSchema = true)
                shouldAttachShot = false
                val step = AgentStepParser.parse(raw, ToolDispatcher.KNOWN_TOOLS)

                if (step != null) {
                    if (step.mood.isNotBlank()) {
                        runCatching { container.moodEngine.applyModelExpression(step.mood) }
                        currentTone = runCatching { container.moodEngine.currentMood() }.getOrDefault(step.mood)
                        AppDebugServer.log("TALK_MOOD", "Model expressed ${step.mood} -> mood now $currentTone")
                    }
                    if (step.nextCheckInMinutes in 1..60) {
                        chosenNextInterval = step.nextCheckInMinutes
                        AppDebugServer.log("LOOP", "Shiina dynamically chose next check-in interval: ${chosenNextInterval}m")
                    }
                    AppDebugServer.log(
                        "GEMINI_TALK_PARSED",
                        "Agent step #${calls + 1}: mood=${step.mood}, status=${step.status}, tool=${step.tool}, nextCheckIn=${step.nextCheckInMinutes}, thought=\"${step.thought.take(80)}\", message=\"${step.message.take(80)}\"",
                    )
                }

                if (step == null) {
                    // Plaintext response fallback
                    answer = raw.trim().take(512)
                    break
                }

                val isDone = (step.tool == "NONE" || step.tool.isBlank()) &&
                    (step.status.equals("DONE", ignoreCase = true) || !step.status.equals("CONTINUE", ignoreCase = true))

                if (isDone) {
                    var finalMsg = step.message.trim()
                    if (finalMsg.isBlank() || finalMsg.startsWith("{") || finalMsg.startsWith("```") || finalMsg.contains("\"thought\":")) {
                        val extracted = AgentStepParser.parse(raw, ToolDispatcher.KNOWN_TOOLS)?.message?.trim().orEmpty()
                        if (extracted.isNotBlank() && !extracted.startsWith("{") && !extracted.contains("\"thought\":")) {
                            finalMsg = extracted
                        } else {
                            AppDebugServer.log("TALK_AGENT", "Final step had no conversational message; synthesizing natural speech...")
                            val basePromptNoTools = PromptAssembler.buildBasePrompt(
                                context = context,
                                container = container,
                                userText = userText,
                                tone = currentTone,
                                includeTools = false,
                                isSystemTrigger = isSystemTrigger,
                                hoursInactive = effectiveHoursInactive,
                                lastInteractionMillis = previousInteractionTime,
                            )
                            val finalPrompt = PromptAssembler.buildFinalSpeechPrompt(basePromptNoTools, stepsTaken)
                            finalMsg = postText(finalPrompt, attachShot = false).trim()
                        }
                    }
                    answer = finalMsg.trim().take(512)
                    AppDebugServer.log("TALK_AGENT", "Agent invoked completion (DONE) at step #${calls + 1}")
                    break
                }

                // Intermediate step
                if (step.message.isNotBlank()) {
                    val progressMsg = step.message.trim()
                    ChatBus.setProgress(progressMsg)
                    onProgress(progressMsg)
                    AppDebugServer.log("TALK_INTERACTION", "Agent progress status: $progressMsg")
                } else {
                    AppDebugServer.log("TALK_INTERACTION", "Agent executing step #${calls + 1} silently (${step.tool})")
                }

                calls++

                val toolResult = ToolDispatcher.dispatch(step, container, context)
                delay(toolResult.waitMs)

                if (toolResult.shouldAttachScreenshot) {
                    shouldAttachShot = true
                }

                runCatching { container.toolTracker.record(step.tool.lowercase(), toolResult.receipt.isNotEmpty()) }
                stepsTaken += "${step.tool}: ${toolResult.receipt.take(150).ifEmpty { "ok" }}"
                AppDebugServer.log("AGENT_TOOL", "Tool ${step.tool} -> ${toolResult.receipt.take(120).ifEmpty { "(ok)" }}")

                // Record navigation step for procedural learning
                when (step.tool) {
                    "OPEN_APP" -> {
                        val app = step.app.ifBlank { step.query.ifBlank { step.text } }
                        executedNavSteps.add(com.shiina.mobile.memory.ProcedureStep(executedNavSteps.size + 1, "OPEN_APP", app, "Opens $app"))
                    }
                    "OPEN_SETTINGS" -> {
                        executedNavSteps.add(com.shiina.mobile.memory.ProcedureStep(executedNavSteps.size + 1, "OPEN_SETTINGS", step.action.ifBlank { step.query }, "Opens settings"))
                    }
                    "TAP_SCREEN" -> {
                        val target = if (step.text.isNotBlank()) step.text else if (step.x >= 0) "(${step.x.toInt()}, ${step.y.toInt()})" else ""
                        if (target.isNotBlank()) {
                            executedNavSteps.add(com.shiina.mobile.memory.ProcedureStep(executedNavSteps.size + 1, "TAP_SCREEN", target, "Taps $target"))
                        }
                    }
                    "INPUT_TEXT" -> {
                        val target = step.text.ifBlank { step.query }
                        if (target.isNotBlank()) {
                            executedNavSteps.add(com.shiina.mobile.memory.ProcedureStep(executedNavSteps.size + 1, "INPUT_TEXT", target, "Inputs text"))
                        }
                    }
                    "PRESS_KEY" -> {
                        val k = step.action.ifBlank { step.query }
                        executedNavSteps.add(com.shiina.mobile.memory.ProcedureStep(executedNavSteps.size + 1, "PRESS_KEY", k, "Presses $k"))
                    }
                    "WAIT" -> {
                        executedNavSteps.add(com.shiina.mobile.memory.ProcedureStep(executedNavSteps.size + 1, "WAIT", step.level.toString(), "Waits"))
                    }
                }

                val fgApp = runCatching {
                    container.deviceActionController.getForegroundApp()
                }.getOrNull()

                val basePrompt = if (currentTone != initialTone) {
                    PromptAssembler.buildBasePrompt(
                        context = context,
                        container = container,
                        userText = userText,
                        tone = currentTone,
                        includeTools = true,
                        isSystemTrigger = isSystemTrigger,
                        hoursInactive = effectiveHoursInactive,
                        lastInteractionMillis = previousInteractionTime,
                    )
                } else {
                    basePromptWithTools
                }

                currentPrompt = PromptAssembler.buildExecutionStatePrompt(
                    basePrompt = basePrompt,
                    currentStep = calls,
                    userText = userText,
                    stepsTaken = stepsTaken,
                    lastThought = step.thought,
                    lastTool = step.tool,
                    lastObservation = toolResult.receipt.ifEmpty { "done" },
                    hasScreenshotAttached = toolResult.shouldAttachScreenshot,
                    foregroundApp = fgApp,
                )
            }

            if (answer.isBlank() || answer.startsWith("{") || answer.startsWith("```") || answer.contains("\"thought\":")) {
                val extracted = AgentStepParser.parse(answer, ToolDispatcher.KNOWN_TOOLS)?.message?.trim().orEmpty()
                if (extracted.isNotBlank() && !extracted.startsWith("{") && !extracted.contains("\"thought\":")) {
                    answer = extracted
                } else {
                    val basePromptNoTools = PromptAssembler.buildBasePrompt(
                        context = context,
                        container = container,
                        userText = userText,
                        tone = currentTone,
                        includeTools = false,
                        isSystemTrigger = isSystemTrigger,
                        hoursInactive = effectiveHoursInactive,
                        lastInteractionMillis = previousInteractionTime,
                    )
                    val finalPrompt = PromptAssembler.buildFinalSpeechPrompt(basePromptNoTools, stepsTaken)
                    answer = postText(finalPrompt, attachShot = false).trim()
                    if (answer.startsWith("{") || answer.startsWith("```") || answer.contains("\"thought\":")) {
                        answer = AgentStepParser.parse(answer, ToolDispatcher.KNOWN_TOOLS)?.message?.trim().orEmpty()
                    }
                }
            }

            if (answer.isBlank() || answer.startsWith("{") || answer.startsWith("```") || answer.contains("\"thought\":")) {
                answer = "Hmm, I lost my train of thought for a second. What's on your mind?"
            }

            // Autonomous procedural macro learning: if Shiina successfully navigated an app flow, save it to memory!
            if (executedNavSteps.size >= 2) {
                val fgApp = runCatching { container.deviceActionController.getForegroundApp() }.getOrNull() ?: ""
                val pkg = if (fgApp.contains("(")) fgApp.substringAfter("(").substringBefore(")") else fgApp
                val label = if (fgApp.contains("(")) fgApp.substringBefore("(").trim() else fgApp
                runCatching {
                    val learnReceipt = container.proceduralMemoryStore.synthesizeFromCompletedRun(
                        userGoal = userText,
                        appPackage = pkg,
                        appLabel = label,
                        actions = executedNavSteps,
                    )
                    AppDebugServer.log("AUTONOMOUS_LEARNING", "Procedural synthesis: $learnReceipt")
                }
            }

            AgentRunResult(
                message = answer.trim().take(512).ifEmpty { "(empty reply)" },
                nextCheckInMinutes = chosenNextInterval,
            )
        } finally {
            ChatBus.endRun()
        }
    }

    /**
     * Single Gemini request with optional vision attach and JSON schema enforcement.
     */
    private suspend fun postText(prompt: String, attachShot: Boolean, structuredSchema: Boolean = false): String {
        val keyPool = container.geminiKeyPool
        val totalKeys = container.keyStore.getKeys("gemini").size.coerceAtLeast(1)
        val maxAttempts = (totalKeys * 2).coerceAtMost(4)

        val parts = JSONArray().put(
            JSONObject().put("text", prompt),
        )
        var imageBytesCount = 0
        if (attachShot) {
            val shot = runCatching { container.screenshotTaker.latestCapture(maxAgeMs = 60_000L) }.getOrNull()
            if (shot != null) {
                val bytes = runCatching { shot.readBytes() }.getOrNull()
                if (bytes != null) {
                    imageBytesCount = bytes.size
                    parts.put(
                        JSONObject().put(
                            "inline_data",
                            JSONObject()
                                .put("mime_type", "image/jpeg")
                                .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                        ),
                    )
                }
            }
        }

        val textTokensEst = prompt.length / 4
        val imageTokensEst = if (imageBytesCount > 0) 258 else 0
        val totalTokensEst = textTokensEst + imageTokensEst
        AppDebugServer.log(
            "TOKEN_AUDIT",
            "Turn request payload: ~$totalTokensEst tokens (text: ~$textTokensEst, image: ${if (imageBytesCount > 0) "~258 tokens (${imageBytesCount / 1024}KB)" else "0 tokens"}), promptLength=${prompt.length}",
        )
        AppDebugServer.log(
            "GEMINI_TALK_REQUEST",
            "Talk prompt:\n$prompt" + if (imageBytesCount > 0) " [screenshot attached: ${imageBytesCount / 1024}KB]" else "",
        )

        val requestBody = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))

        if (structuredSchema) {
            // gemini-3.5-flash-lite does not support response_schema object directly; omit to prevent 400 bad request
            // requestBody.put(
            //     "generationConfig",
            //     JSONObject()
            //         .put("response_mime_type", "application/json")
            //         .put("response_schema", ToolDispatcher.buildStepSchema()),
            // )
        }

        val configuredModel = runCatching { container.settingsRepository.getGeminiModel() }
            .getOrDefault("gemini-3.5-flash-lite")
        val alternateModel = "gemini-3.5-flash-lite"

        var currentModel = configuredModel
        var fallbackTriggered = false

        for (attempt in 1..maxAttempts) {
            val key = keyPool.next()
                ?: container.keyStore.getKeys("gemini").firstOrNull()
                ?: throw IllegalStateException("no gemini keys configured")

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$currentModel:generateContent?key=$key")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val raw = runCatching {
                http.newCall(request).execute().use { response ->
                    val code = response.code
                    val respBody = response.body?.string().orEmpty()
                    AppDebugServer.log("GEMINI_NET", "API response code=$code bodyLen=${respBody.length}: ${respBody.take(400)}")
                    if (!response.isSuccessful) {
                        if (code == 429) {
                            keyPool.reportFailure(key, 60_000L)
                            AppDebugServer.log("WARN", "Gemini key ...${key.takeLast(4)} hit 429 rate limit on $currentModel, rotating key in pool")
                            if (!fallbackTriggered) {
                                currentModel = alternateModel
                                fallbackTriggered = true
                                AppDebugServer.log("WARN", "Failing over to alternate model: $currentModel (separate quota pool)")
                            }
                        }
                        throw IllegalStateException("gemini $code")
                    }
                    respBody
                }
            }.getOrDefault("")

            if (raw.isNotBlank()) {
                AppDebugServer.log("GEMINI_RAW", "Raw API response length=${raw.length}: ${raw}")
                val json = runCatching { JSONObject(raw) }.getOrNull()
                val candidate = json?.optJSONArray("candidates")?.optJSONObject(0)
                val finishReason = candidate?.optString("finishReason", "")
                val candidateParts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                val text = (0 until (candidateParts?.length() ?: 0))
                    .mapNotNull { candidateParts?.optJSONObject(it)?.optString("text") }
                    .firstOrNull { it.isNotBlank() }
                    ?.trim()

                if (!text.isNullOrBlank()) {
                    return text.take(1024)
                }
                AppDebugServer.log("WARN", "Gemini returned empty text or finishReason=$finishReason on attempt $attempt ($currentModel)")
            }
            if (attempt < maxAttempts) delay(300L)
        }

        return "{\"thought\": \"Response generation failed\", \"status\": \"DONE\", \"tool\": \"NONE\", \"message\": \"Hmm, I lost my train of thought for a second. What's on your mind?\"}"
    }
}
