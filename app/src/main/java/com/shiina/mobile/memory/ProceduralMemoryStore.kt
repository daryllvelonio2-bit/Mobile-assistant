package com.shiina.mobile.memory

import android.content.Context
import com.shiina.mobile.debug.AppDebugServer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a single atomic action within an app navigation procedure or macro.
 */
data class ProcedureStep(
    val stepNumber: Int,
    val action: String,
    val target: String,
    val details: String = "",
) {
    fun toJsonObject(): JSONObject = JSONObject()
        .put("step_number", stepNumber)
        .put("action", action)
        .put("target", target)
        .put("details", details)

    companion object {
        fun fromJsonObject(json: JSONObject): ProcedureStep {
            return ProcedureStep(
                stepNumber = json.optInt("step_number", 1),
                action = json.optString("action", "TAP_SCREEN"),
                target = json.optString("target", ""),
                details = json.optString("details", ""),
            )
        }
    }
}

/**
 * Represents an end-to-end learned app navigation workflow or macro procedure.
 */
data class ProcedureEntry(
    val id: String,
    var title: String,
    var appPackage: String,
    var appLabel: String,
    var intentGoal: String,
    val triggers: MutableList<String>,
    val steps: MutableList<ProcedureStep>,
    var shortcutTip: String = "",
    var usageCount: Int = 1,
    var lastUsed: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJsonObject(): JSONObject {
        val stepsArray = JSONArray()
        steps.forEach { stepsArray.put(it.toJsonObject()) }
        val triggersArray = JSONArray()
        triggers.forEach { triggersArray.put(it) }

        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("app_package", appPackage)
            .put("app_label", appLabel)
            .put("intent_goal", intentGoal)
            .put("triggers", triggersArray)
            .put("steps", stepsArray)
            .put("shortcut_tip", shortcutTip)
            .put("usage_count", usageCount)
            .put("last_used", lastUsed)
            .put("created_at", createdAt)
            .put("updated_at", updatedAt)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): ProcedureEntry {
            val stepsList = mutableListOf<ProcedureStep>()
            val stepsArray = json.optJSONArray("steps")
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    val sObj = stepsArray.optJSONObject(i)
                    if (sObj != null) stepsList.add(ProcedureStep.fromJsonObject(sObj))
                }
            }
            val triggerList = mutableListOf<String>()
            val trigArray = json.optJSONArray("triggers")
            if (trigArray != null) {
                for (i in 0 until trigArray.length()) {
                    val t = trigArray.optString(i)
                    if (t.isNotBlank()) triggerList.add(t)
                }
            }

            return ProcedureEntry(
                id = json.optString("id", "proc_" + System.currentTimeMillis()),
                title = json.optString("title", "Learned Procedure"),
                appPackage = json.optString("app_package", ""),
                appLabel = json.optString("app_label", ""),
                intentGoal = json.optString("intent_goal", ""),
                triggers = triggerList,
                steps = stepsList,
                shortcutTip = json.optString("shortcut_tip", ""),
                usageCount = json.optInt("usage_count", 1),
                lastUsed = json.optLong("last_used", System.currentTimeMillis()),
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
            )
        }
    }
}

/**
 * Persistent Procedural Memory Store.
 *
 * Enables Shiina to:
 * 1. Learn app navigation workflows and save ordered steps in durable JSON storage.
 * 2. Surface known procedure titles into every system prompt so Shiina knows what procedures exist.
 * 3. Self-invoke learned procedures to perform multi-screen tasks in seconds.
 * 4. Self-optimize: remove redundant or outdated steps, modify steps, or replace entire procedures
 *    when discovering a faster, more direct route.
 */
class ProceduralMemoryStore(
    private val context: Context? = null,
    baseDir: File? = null,
) {
    private val mutex = Mutex()
    private val storageFile by lazy { File(baseDir ?: context?.filesDir ?: File("."), "learned_procedures.json") }
    private val procedures = LinkedHashMap<String, ProcedureEntry>()

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        procedures.clear()
        if (!storageFile.exists()) {
            seedDefaultProcedures()
            saveToDiskLocked()
            return
        }
        runCatching {
            val content = storageFile.readText().trim()
            if (content.isNotBlank()) {
                val array = JSONArray(content)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        val entry = ProcedureEntry.fromJsonObject(obj)
                        procedures[entry.id] = entry
                    }
                }
            }
        }.onFailure { e ->
            AppDebugServer.log("PROCEDURAL_MEMORY", "Failed to load procedures: ${e.message}")
            seedDefaultProcedures()
        }
    }

    /**
     * Forget-everything: deletes all user-learned procedures from disk, then reloads
     * so only the seeded defaults remain.
     */
    suspend fun clearUserProcedures(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { if (storageFile.exists()) storageFile.delete() }
            loadFromDisk()
        }
        AppDebugServer.log("PROCEDURAL_MEMORY", "clearUserProcedures: wiped learned procedures, defaults reseeded")
    }

    private fun saveToDiskLocked() {
        runCatching {
            val array = JSONArray()
            procedures.values.forEach { array.put(it.toJsonObject()) }
            storageFile.writeText(array.toString(2))
        }.onFailure { e ->
            AppDebugServer.log("PROCEDURAL_MEMORY", "Failed to save procedures: ${e.message}")
        }
    }

    private fun seedDefaultProcedures() {
        val clock = ProcedureEntry(
            id = "proc_clock_alarm",
            title = "Clock: Set Alarm",
            appPackage = "com.android.deskclock",
            appLabel = "Clock",
            intentGoal = "set an alarm or wake up call",
            triggers = mutableListOf("alarm", "wake up", "wake me up", "wake", "set alarm", "clock"),
            steps = mutableListOf(
                ProcedureStep(1, "OPEN_APP", "clock", "Opens the system Clock app"),
                ProcedureStep(2, "TAP_SCREEN", "Alarm", "Selects the leftmost 'Alarm' bottom navigation tab"),
                ProcedureStep(3, "TAP_SCREEN", "Add alarm", "Taps the add alarm (+) action button"),
                ProcedureStep(4, "INPUT_TEXT", "time", "Inputs or selects the requested alarm hour and minute"),
                ProcedureStep(5, "TAP_SCREEN", "Save", "Taps Save to confirm and activate the alarm"),
            ),
            shortcutTip = "Direct fast-path: Call SET_ALARM tool with {'hour': H, 'minute': M} to set immediately without UI tapping.",
        )

        val youtube = ProcedureEntry(
            id = "proc_youtube_search",
            title = "YouTube: Search and Play Video",
            appPackage = "com.google.android.youtube",
            appLabel = "YouTube",
            intentGoal = "search and play videos or songs",
            triggers = mutableListOf("youtube", "play video", "watch video", "search youtube", "video"),
            steps = mutableListOf(
                ProcedureStep(1, "OPEN_APP", "youtube", "Launches YouTube app"),
                ProcedureStep(2, "WAIT", "2", "Waits for splash screen and home feed to settle"),
                ProcedureStep(3, "TAP_SCREEN", "Search", "Taps search magnifying glass icon in top bar"),
                ProcedureStep(4, "INPUT_TEXT", "query", "Types search terms into the query input field"),
                ProcedureStep(5, "PRESS_KEY", "enter", "Presses Enter on keyboard to execute search and dismiss keyboard"),
                ProcedureStep(6, "TAP_SCREEN", "First Result Card", "Taps the first matching video card in search results"),
            ),
            shortcutTip = "Ensure virtual software keyboard is dismissed with PRESS_KEY enter so results are visible.",
        )

        val wifi = ProcedureEntry(
            id = "proc_settings_wifi",
            title = "Settings: Wi-Fi Controls",
            appPackage = "com.android.settings",
            appLabel = "Settings",
            intentGoal = "view or toggle Wi-Fi connections",
            triggers = mutableListOf("wifi", "wi-fi", "internet", "network"),
            steps = mutableListOf(
                ProcedureStep(1, "OPEN_SETTINGS", "wifi", "Opens the native Wi-Fi settings panel directly"),
                ProcedureStep(2, "TAP_SCREEN", "Wi-Fi Switch", "Toggles the main Wi-Fi switch or selects a known SSID network"),
            ),
            shortcutTip = "Use OPEN_SETTINGS with action 'wifi' instead of manually navigating through Settings menus.",
        )

        val bluetooth = ProcedureEntry(
            id = "proc_settings_bluetooth",
            title = "Settings: Bluetooth Controls",
            appPackage = "com.android.settings",
            appLabel = "Settings",
            intentGoal = "view or toggle Bluetooth devices",
            triggers = mutableListOf("bluetooth", "pair device", "connect headphones", "headset"),
            steps = mutableListOf(
                ProcedureStep(1, "OPEN_SETTINGS", "bluetooth", "Opens native Bluetooth settings panel directly"),
                ProcedureStep(2, "TAP_SCREEN", "Bluetooth Switch", "Toggles Bluetooth or taps paired audio accessory"),
            ),
            shortcutTip = "Use OPEN_SETTINGS with action 'bluetooth' for instant deep navigation.",
        )

        procedures[clock.id] = clock
        procedures[youtube.id] = youtube
        procedures[wifi.id] = wifi
        procedures[bluetooth.id] = bluetooth
    }

    /**
     * Finds a matching procedure by ID, title, or fuzzy keyword match.
     */
    @Synchronized
    fun getProcedure(key: String): ProcedureEntry? {
        val q = key.trim().lowercase()
        return procedures[q]
            ?: procedures.values.firstOrNull { it.title.equals(q, ignoreCase = true) }
            ?: procedures.values.firstOrNull { it.id.contains(q, ignoreCase = true) || it.title.contains(q, ignoreCase = true) }
            ?: procedures.values.firstOrNull { it.triggers.any { t -> q.contains(t, ignoreCase = true) } }
    }

    /**
     * Finds the best matching procedure for an active goal or user request.
     */
    @Synchronized
    fun findBestMatch(goal: String, currentApp: String = ""): ProcedureEntry? {
        val q = goal.trim().lowercase()
        val app = currentApp.trim().lowercase()
        val qTokens = q.split(Regex("""[\s\-_,.]+""")).filter { it.length > 2 }

        // 1. Direct trigger match in goal
        val byTrigger = procedures.values.firstOrNull { proc ->
            proc.triggers.any { t ->
                q.contains(t, ignoreCase = true) ||
                (t.contains("wake", ignoreCase = true) && q.contains("wake", ignoreCase = true)) ||
                (t.contains("alarm", ignoreCase = true) && q.contains("alarm", ignoreCase = true))
            }
        }
        if (byTrigger != null) return byTrigger

        // 2. Token overlap match
        val byToken = procedures.values.maxByOrNull { proc ->
            val allTrigTokens = (proc.triggers + proc.intentGoal.split(" ") + proc.title.split(" "))
                .map { it.lowercase().trim() }
                .filter { it.length > 2 }
            qTokens.count { token -> allTrigTokens.any { tt -> tt.contains(token) || token.contains(tt) } }
        }
        if (byToken != null) {
            val score = qTokens.count { token ->
                (byToken.triggers + byToken.intentGoal.split(" ") + byToken.title.split(" "))
                    .any { it.contains(token, ignoreCase = true) }
            }
            if (score >= 1) return byToken
        }

        // 3. App package or label match
        if (app.isNotBlank()) {
            val byApp = procedures.values.firstOrNull { proc ->
                proc.appPackage.contains(app, ignoreCase = true) || proc.appLabel.contains(app, ignoreCase = true)
            }
            if (byApp != null) return byApp
        }

        // 4. Substring match on intent goal
        return procedures.values.firstOrNull { proc ->
            q.contains(proc.intentGoal.lowercase()) || proc.intentGoal.lowercase().contains(q)
        }
    }

    /**
     * Saves or updates a learned procedure.
     */
    suspend fun addProcedure(
        title: String,
        appLabel: String,
        appPackage: String,
        intentGoal: String,
        steps: List<ProcedureStep>,
        shortcutTip: String = "",
    ): String = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext "Failed: Procedure requires a title."
        if (steps.isEmpty()) return@withContext "Failed: Procedure requires at least one step."

        val id = "proc_" + title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        mutex.withLock {
            val existing = procedures[id]
            val triggers = existing?.triggers ?: mutableListOf()
            title.split(" ").filter { it.length > 3 }.forEach { if (!triggers.contains(it.lowercase())) triggers.add(it.lowercase()) }
            if (intentGoal.isNotBlank() && !triggers.contains(intentGoal.lowercase())) triggers.add(intentGoal.lowercase())

            val reindexed = steps.mapIndexed { idx, s -> s.copy(stepNumber = idx + 1) }.toMutableList()
            val entry = ProcedureEntry(
                id = id,
                title = title.trim(),
                appPackage = appPackage.trim(),
                appLabel = appLabel.trim(),
                intentGoal = intentGoal.trim(),
                triggers = triggers,
                steps = reindexed,
                shortcutTip = shortcutTip.trim(),
                usageCount = (existing?.usageCount ?: 0) + 1,
                lastUsed = System.currentTimeMillis(),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            procedures[id] = entry
            saveToDiskLocked()
        }
        AppDebugServer.log("PROCEDURAL_MEMORY", "Learned procedure '$title' with ${steps.size} steps (id: $id)")
        "Learned procedure '$title' (id: $id) with ${steps.size} steps. Saved to procedural memory."
    }

    /**
     * Removes a specific step from a procedure when Shiina finds a faster way or an obsolete step.
     */
    suspend fun removeStep(key: String, stepNumber: Int): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = getProcedure(key) ?: return@withContext "Procedure '$key' not found in memory."
            val removed = entry.steps.removeIf { it.stepNumber == stepNumber }
            if (!removed) return@withContext "Step #$stepNumber not found in procedure '${entry.title}'."

            // Re-index remaining steps
            val reindexed = entry.steps.mapIndexed { idx, s -> s.copy(stepNumber = idx + 1) }.toMutableList()
            entry.steps.clear()
            entry.steps.addAll(reindexed)
            entry.updatedAt = System.currentTimeMillis()
            saveToDiskLocked()
            AppDebugServer.log("PROCEDURAL_MEMORY", "Removed step #$stepNumber from '${entry.title}'. Remaining steps: ${entry.steps.size}")
            "Successfully removed step #$stepNumber from '${entry.title}'. Procedure now has ${entry.steps.size} steps."
        }
    }

    /**
     * Updates an existing step in a procedure (e.g. changing coordinates or action).
     */
    suspend fun updateStep(
        key: String,
        stepNumber: Int,
        newAction: String,
        newTarget: String,
        newDetails: String = "",
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = getProcedure(key) ?: return@withContext "Procedure '$key' not found in memory."
            val idx = entry.steps.indexOfFirst { it.stepNumber == stepNumber }
            if (idx < 0) return@withContext "Step #$stepNumber not found in procedure '${entry.title}'."

            val old = entry.steps[idx]
            val updated = old.copy(
                action = newAction.ifBlank { old.action },
                target = newTarget.ifBlank { old.target },
                details = newDetails.ifBlank { old.details },
            )
            entry.steps[idx] = updated
            entry.updatedAt = System.currentTimeMillis()
            saveToDiskLocked()
            AppDebugServer.log("PROCEDURAL_MEMORY", "Updated step #$stepNumber in '${entry.title}': ${updated.action} -> ${updated.target}")
            "Successfully updated step #$stepNumber in '${entry.title}' to: ${updated.action} target='${updated.target}'."
        }
    }

    /**
     * Inserts a new step into a procedure at a specified position.
     */
    suspend fun insertStep(
        key: String,
        afterStepNumber: Int,
        action: String,
        target: String,
        details: String = "",
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = getProcedure(key) ?: return@withContext "Procedure '$key' not found in memory."
            val insertIdx = if (afterStepNumber <= 0) 0 else (afterStepNumber).coerceAtMost(entry.steps.size)
            val newStep = ProcedureStep(
                stepNumber = insertIdx + 1,
                action = action.ifBlank { "TAP_SCREEN" },
                target = target,
                details = details,
            )
            entry.steps.add(insertIdx, newStep)

            // Re-index
            val reindexed = entry.steps.mapIndexed { idx, s -> s.copy(stepNumber = idx + 1) }.toMutableList()
            entry.steps.clear()
            entry.steps.addAll(reindexed)
            entry.updatedAt = System.currentTimeMillis()
            saveToDiskLocked()
            "Successfully inserted step at #${insertIdx + 1} in '${entry.title}'. Procedure now has ${entry.steps.size} steps."
        }
    }

    /**
     * Replaces an entire procedure with an optimized sequence of steps.
     */
    suspend fun optimizeProcedure(
        key: String,
        newSteps: List<ProcedureStep>,
        reason: String = "",
    ): String = withContext(Dispatchers.IO) {
        if (newSteps.isEmpty()) return@withContext "Failed: Optimized procedure requires at least one step."
        mutex.withLock {
            val entry = getProcedure(key) ?: return@withContext "Procedure '$key' not found in memory."
            val oldCount = entry.steps.size
            val reindexed = newSteps.mapIndexed { idx, s -> s.copy(stepNumber = idx + 1) }.toMutableList()
            entry.steps.clear()
            entry.steps.addAll(reindexed)
            entry.updatedAt = System.currentTimeMillis()
            if (reason.isNotBlank()) entry.shortcutTip = reason
            saveToDiskLocked()
            AppDebugServer.log("PROCEDURAL_MEMORY", "Optimized procedure '${entry.title}': was $oldCount steps, now ${entry.steps.size} steps ($reason)")
            "Optimized procedure '${entry.title}': updated from $oldCount steps to ${entry.steps.size} steps. ${if (reason.isNotBlank()) "Reason: $reason" else ""}"
        }
    }

    /**
     * Deletes a procedure from procedural memory.
     */
    suspend fun deleteProcedure(key: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entry = getProcedure(key) ?: return@withContext "Procedure '$key' not found in memory."
            procedures.remove(entry.id)
            saveToDiskLocked()
            AppDebugServer.log("PROCEDURAL_MEMORY", "Deleted procedure '${entry.title}' (id: ${entry.id})")
            "Deleted procedure '${entry.title}' from memory."
        }
    }

    /**
     * Returns a detailed execution guide for a single procedure.
     */
    @Synchronized
    fun getProcedureDetails(key: String): String {
        val entry = getProcedure(key) ?: return "Procedure '$key' not found in procedural memory."
        entry.usageCount++
        entry.lastUsed = System.currentTimeMillis()

        val sb = StringBuilder()
        sb.append("### Procedure: \"${entry.title}\" (id: ${entry.id})\n")
        sb.append("- App: ${entry.appLabel} (${entry.appPackage})\n")
        sb.append("- Goal: ${entry.intentGoal}\n")
        if (entry.shortcutTip.isNotBlank()) sb.append("- Optimization Note: ${entry.shortcutTip}\n")
        sb.append("- Steps (${entry.steps.size}):\n")
        entry.steps.forEach { s ->
            val dStr = if (s.details.isNotBlank()) " — ${s.details}" else ""
            sb.append("  ${s.stepNumber}. [${s.action}] target=\"${s.target}\"$dStr\n")
        }
        return sb.toString().trim()
    }

    /**
     * Builds the concise titles list for prompt injection so Shiina is aware of every procedure.
     */
    @Synchronized
    fun getProceduresSummaryForPrompt(currentApp: String = "", currentGoal: String = ""): String {
        if (procedures.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# Learned App Navigation Procedures & Macro Memory\n")
        sb.append("Shiina has learned the following app navigation procedures and saved them in memory:\n")
        procedures.values.forEach { proc ->
            val appInfo = if (proc.appLabel.isNotBlank()) "[${proc.appLabel}] " else ""
            sb.append("- $appInfo\"${proc.title}\" (id: ${proc.id}) — ${proc.steps.size} steps\n")
        }

        // If current goal or active app matches a learned procedure, surface its exact steps immediately!
        val match = if (currentGoal.isNotBlank()) findBestMatch(currentGoal, currentApp) else null
        if (match != null) {
            sb.append("\n[MATCHED WORKFLOW FOR CURRENT GOAL]: \"${match.title}\" (id: ${match.id})\n")
            if (match.shortcutTip.isNotBlank()) sb.append("Tip: ${match.shortcutTip}\n")
            match.steps.forEach { s ->
                val d = if (s.details.isNotBlank()) " (${s.details})" else ""
                sb.append("  Step ${s.stepNumber}: ${s.action} target=\"${s.target}\"$d\n")
            }
            sb.append("* Direct Execution: Invoke EXECUTE_PROCEDURE with {\"key\": \"${match.id}\"} to replay these verified steps at device speed, or follow them directly!\n")
            sb.append("* Self-Optimization: If you find an unnecessary step or an app update changes the screen, call REMOVE_PROCEDURE_STEP or OPTIMIZE_PROCEDURE to keep memory optimal!\n")
        } else {
            sb.append("* Shiina Invocation: Call EXECUTE_PROCEDURE with {\"key\": \"<id or title>\"} to run any procedure instantly, or GET_PROCEDURE to inspect steps.\n")
            sb.append("* Fast Learning: When you successfully navigate a new app flow, save it via LEARN_PROCEDURE so you can do it faster next time!\n")
            sb.append("* Self-Optimization: Remove redundant steps via REMOVE_PROCEDURE_STEP, update steps via UPDATE_PROCEDURE_STEP, or optimize via OPTIMIZE_PROCEDURE!\n")
        }

        return sb.toString().trim()
    }

    /**
     * Autonomously synthesizes a new procedural macro from a successful multi-step agent interaction.
     * When Shiina navigates an app and succeeds, this captures the navigation path so future runs take seconds.
     */
    suspend fun synthesizeFromCompletedRun(
        userGoal: String,
        appPackage: String,
        appLabel: String,
        actions: List<ProcedureStep>,
    ): String = withContext(Dispatchers.IO) {
        if (actions.size < 2 || userGoal.isBlank()) return@withContext "Skipped: Not enough navigation steps to synthesize procedure."

        // Clean actions: remove trailing WAIT steps or empty targets
        val cleanActions = actions.filter { s ->
            s.target.isNotBlank() || s.action == "PRESS_KEY" || s.action == "WAIT"
        }
        if (cleanActions.size < 2) return@withContext "Skipped: Insufficient clean actions."

        val cleanGoal = userGoal.trim().replace(Regex("""[^\w\s\-]"""), "")
        val title = "${appLabel.ifBlank { "App" }}: ${cleanGoal.take(30).trim()}"
        val existing = findBestMatch(userGoal, appPackage)

        if (existing != null) {
            // If the new run achieved the same goal in fewer steps, optimize it!
            if (cleanActions.size < existing.steps.size) {
                return@withContext optimizeProcedure(
                    key = existing.id,
                    newSteps = cleanActions,
                    reason = "Shiina found a shorter route (${cleanActions.size} steps vs ${existing.steps.size} steps)",
                )
            }
            return@withContext "Procedure already known for '$userGoal': ${existing.title}"
        }

        addProcedure(
            title = title,
            appLabel = appLabel.ifBlank { "Mobile App" },
            appPackage = appPackage,
            intentGoal = userGoal.trim(),
            steps = cleanActions,
            shortcutTip = "Autonomously learned by Shiina during live task execution.",
        )
    }

    /**
     * Parses human-readable multi-step strings like "1. TAP text='Alarm' | 2. TAP text='Add'" into structured steps.
     */
    fun parseStepsFromString(raw: String): List<ProcedureStep> {
        val result = mutableListOf<ProcedureStep>()
        val parts = if (raw.contains("|")) {
            raw.split("|")
        } else if (raw.contains("\n")) {
            raw.split("\n")
        } else {
            listOf(raw)
        }

        var idx = 1
        for (p in parts) {
            val line = p.trim().removePrefix("-").trim()
            if (line.isBlank()) continue
            val clean = line.replace(Regex("""^\d+[\.\):\-]\s*"""), "").trim()
            val action = when {
                clean.startsWith("OPEN_APP", ignoreCase = true) -> "OPEN_APP"
                clean.startsWith("TAP_SCREEN", ignoreCase = true) || clean.startsWith("TAP", ignoreCase = true) -> "TAP_SCREEN"
                clean.startsWith("INPUT_TEXT", ignoreCase = true) || clean.startsWith("TYPE", ignoreCase = true) -> "INPUT_TEXT"
                clean.startsWith("PRESS_KEY", ignoreCase = true) || clean.startsWith("KEY", ignoreCase = true) -> "PRESS_KEY"
                clean.startsWith("WAIT", ignoreCase = true) -> "WAIT"
                clean.startsWith("SWIPE", ignoreCase = true) -> "SWIPE_SCREEN"
                clean.startsWith("SCROLL", ignoreCase = true) -> "SCROLL"
                clean.startsWith("OPEN_SETTINGS", ignoreCase = true) -> "OPEN_SETTINGS"
                clean.startsWith("SET_ALARM", ignoreCase = true) -> "SET_ALARM"
                clean.startsWith("SET_TIMER", ignoreCase = true) -> "SET_TIMER"
                else -> "TAP_SCREEN"
            }
            val target = clean.substringAfter(action).replace(Regex("""^[=\s:\("']+"""), "").replace(Regex("""["'\)]+$"""), "").trim()
            result.add(
                ProcedureStep(
                    stepNumber = idx++,
                    action = action,
                    target = target.ifBlank { clean },
                    details = clean,
                )
            )
        }
        return result
    }
}
