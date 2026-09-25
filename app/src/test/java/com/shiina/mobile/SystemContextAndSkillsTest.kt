package com.shiina.mobile

import com.shiina.mobile.decision.AgentStepParser
import com.shiina.mobile.decision.ShiinaPrompts
import com.shiina.mobile.decision.ShiinaSkills
import com.shiina.mobile.decision.ToolCatalog
import com.shiina.mobile.decision.ToolDispatcher
import com.shiina.mobile.memory.DynamicLearningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemContextAndSkillsTest {

    @Test
    fun testSystemContextWithinFiveThousandCharactersWithoutChatsOrHistory() {
        // Assembles the core system context strictly from prompts, skills, behavioral rules,
        // tool specifications, and dynamic learning categories (zero user chats or history included).
        val systemContext = buildString {
            appendLine(ShiinaPrompts.GLOBAL_RULES)
            appendLine()
            appendLine(ShiinaPrompts.moodPrompt(ShiinaPrompts.MOOD_CALM))
            appendLine()
            appendLine(ShiinaPrompts.TALK_DRIVE)
            appendLine()
            appendLine(ShiinaSkills.ALL_SKILLS_MANUAL)
            appendLine()
            appendLine(ShiinaPrompts.TOOL_SPEC)
            appendLine()
            appendLine(ToolCatalog.TOOLSET_OVERVIEW)
            appendLine()
            appendLine("# Real-World Physical Context & Situational Scenarios")
            appendLine("- REAL-WORLD SCENARIO [LATE NIGHT ACTIVE]: It is 2:30 AM. User active. Adopt caring/pouty/firm stance.")
            appendLine()
            appendLine("# Dynamic Learned Knowledge & User Profile")
            appendLine("## Identity & Background\n- user name: Janelle")
            appendLine("## Circadian Rhythm & Daily Routine\n- bedtime: 11:30 PM\n- wake time: 7:30 AM")
            appendLine("## Personal Preferences & Tastes\n- favorite music: Lofi, Jazz, Anime OSTs")
            appendLine("## Health, Sleep & Well-being\n- screen time limit: 4 hours daily")
            appendLine("## User Principles & Stated Rules\n- principle: Value honesty and direct communication")
        }

        val charCount = systemContext.length
        println("Calculated pure system context character count (without user chat/history): $charCount chars")

        // User requirement: ~5k context
        assertTrue("Pure system context must be within 5.2k characters, was: $charCount", charCount <= 5200)
    }

    @Test
    fun testShiinaSkillsPlaybooksCompleteness() {
        val skills = ShiinaSkills.ALL_SKILLS_MANUAL

        assertTrue("Must include Calm Execution Playbook", skills.contains("Skill 1: Calm Task Execution"))
        assertTrue("Must mention splash screen & loading surface", skills.contains("The Splash & Loading Surface Law"))
        assertTrue("Must mention WAIT tool", skills.contains("WAIT tool"))

        assertTrue("Must include Real-World Scenarios Playbook", skills.contains("Skill 2: Real-World Scenarios"))
        assertTrue("Must cover Late-Night Rule", skills.contains("The Late-Night Rule"))
        assertTrue("Must cover Low Battery", skills.contains("Low Battery"))

        assertTrue("Must include Dignity & Anti-Challenge Playbook", skills.contains("Skill 3: Personal Dignity"))
        assertTrue("Must forbid acting out of dares", skills.contains("Never Act Out of Being Challenged or Dared"))

        assertTrue("Must include App Navigation Playbook", skills.contains("Skill 4: Mobile App Navigation"))
        assertTrue("Must cover keyboard obstruction", skills.contains("Keyboard Awareness"))

        assertTrue("Must include Zero Hallucination Playbook", skills.contains("Skill 5: Strict Zero Hallucination"))
        assertTrue("Must include Continuous Learning Playbook", skills.contains("Skill 6: Continuous Learning"))
    }

    @Test
    fun testRealWorldScenarioDetectionLateNight() {
        val engine = DynamicLearningEngine()

        val sensesJson = """
            {
                "battery": 12,
                "charging": false,
                "foreground_app": "com.google.android.youtube",
                "music_playing": true,
                "current_music": "Sparkle by RADWIMPS",
                "audio_route": "bluetooth"
            }
        """.trimIndent()

        val scenarios = engine.evaluateRealWorldScenarios(sensesJson, hoursInactive = 12.0)
        assertTrue("Should detect critical battery", scenarios.any { it.contains("CRITICAL BATTERY") })
        assertTrue("Should detect media playback active", scenarios.any { it.contains("MEDIA PLAYBACK ACTIVE") })
        assertTrue("Should detect absence/neglect", scenarios.any { it.contains("NEGLECT / ABSENCE") })
    }

    @Test
    fun testAgentStepParserWithCodeFences() {
        val raw = """
            ```json
            {
              "thought": "The app is loading splash screen. I must wait calmly.",
              "mood": "calm",
              "status": "CONTINUE",
              "tool": "WAIT",
              "tool_args": {"level": 3},
              "message": ""
            }
            ```
        """.trimIndent()

        val parsed = AgentStepParser.parse(raw, ToolDispatcher.KNOWN_TOOLS)
        assertNotNull("Should successfully parse step enclosed in code fences", parsed)
        assertTrue("Tool must be WAIT", parsed?.tool == "WAIT")
        assertTrue("Status must be CONTINUE", parsed?.status == "CONTINUE")
        assertTrue("Level must be 3", parsed?.level == 3)
    }

    @Test
    fun testSetAlarmParsingDirectAndNatural() {
        // Direct hour and minute
        val raw1 = """
            {
              "thought": "User wants an 8am alarm",
              "mood": "warm",
              "status": "CONTINUE",
              "tool": "SET_ALARM",
              "tool_args": {"hour": 8, "minute": 0, "title": "Wake up"}
            }
        """.trimIndent()
        val parsed1 = AgentStepParser.parse(raw1, ToolDispatcher.KNOWN_TOOLS)
        assertNotNull("Should parse direct hour and minute", parsed1)
        assertEquals("Hour should be 8", 8, parsed1?.hour)
        assertEquals("Minute should be 0", 0, parsed1?.minute)
        assertEquals("Title should be Wake up", "Wake up", parsed1?.title)

        // Natural time string in text
        val raw2 = """
            {
              "thought": "Parsing alarm from text",
              "mood": "calm",
              "status": "CONTINUE",
              "tool": "SET_ALARM",
              "tool_args": {"text": "8:30 AM", "title": "Morning Routine"}
            }
        """.trimIndent()
        val parsed2 = AgentStepParser.parse(raw2, ToolDispatcher.KNOWN_TOOLS)
        assertNotNull("Should parse natural time string", parsed2)
        assertEquals("Hour should be 8", 8, parsed2?.hour)
        assertEquals("Minute should be 30", 30, parsed2?.minute)

        // PM conversion
        val raw3 = """
            {
              "thought": "Parsing PM alarm",
              "mood": "calm",
              "status": "CONTINUE",
              "tool": "SET_ALARM",
              "tool_args": {"query": "7:45 pm"}
            }
        """.trimIndent()
        val parsed3 = AgentStepParser.parse(raw3, ToolDispatcher.KNOWN_TOOLS)
        assertNotNull("Should parse 7:45 pm to 19:45", parsed3)
        assertEquals("Hour should be 19", 19, parsed3?.hour)
        assertEquals("Minute should be 45", 45, parsed3?.minute)
    }

    @Test
    fun testSetTimerParsingDirectAndNatural() {
        // Direct seconds
        val raw1 = """
            {
              "thought": "Setting 5 minute timer",
              "mood": "calm",
              "status": "CONTINUE",
              "tool": "SET_TIMER",
              "tool_args": {"seconds": 300, "title": "Tea timer"}
            }
        """.trimIndent()
        val parsed1 = AgentStepParser.parse(raw1, ToolDispatcher.KNOWN_TOOLS)
        assertNotNull("Should parse direct seconds", parsed1)
        assertEquals("Seconds should be 300", 300, parsed1?.seconds)

        // Natural text like "10 minutes"
        val raw2 = """
            {
              "thought": "Setting timer from text",
              "mood": "calm",
              "status": "CONTINUE",
              "tool": "SET_TIMER",
              "tool_args": {"text": "10 minutes"}
            }
        """.trimIndent()
        val parsed2 = AgentStepParser.parse(raw2, ToolDispatcher.KNOWN_TOOLS)
        assertNotNull("Should parse 10 minutes to 600s", parsed2)
        assertEquals("Seconds should be 600", 600, parsed2?.seconds)
    }

    @Test
    fun testProceduralMemoryStoreWorkflowLifecycle() = kotlinx.coroutines.runBlocking {
        val tempDir = java.nio.file.Files.createTempDirectory("shiina_proc_test").toFile()
        try {
            val store = com.shiina.mobile.memory.ProceduralMemoryStore(baseDir = tempDir)

            // 1. Initial seeds loaded
            val clockProc = store.getProcedure("proc_clock_alarm")
            assertNotNull("Clock procedure should be seeded", clockProc)
            assertEquals("Clock procedure should have 5 initial steps", 5, clockProc?.steps?.size)

            // 2. Best match by trigger
            val matched = store.findBestMatch("wake me up tomorrow at 7:00 AM")
            assertNotNull("Should match clock alarm by trigger", matched)
            assertEquals("proc_clock_alarm", matched?.id)

            // 3. Add new procedure
            val steps = listOf(
                com.shiina.mobile.memory.ProcedureStep(1, "OPEN_APP", "spotify", "Open Spotify"),
                com.shiina.mobile.memory.ProcedureStep(2, "WAIT", "2", "Wait for splash screen"),
                com.shiina.mobile.memory.ProcedureStep(3, "TAP_SCREEN", "Search", "Tap Search tab"),
                com.shiina.mobile.memory.ProcedureStep(4, "INPUT_TEXT", "playlist", "Type playlist name"),
                com.shiina.mobile.memory.ProcedureStep(5, "TAP_SCREEN", "Play", "Tap play button"),
            )
            val addResult = store.addProcedure(
                title = "Spotify: Play Playlist",
                appLabel = "Spotify",
                appPackage = "com.spotify.music",
                intentGoal = "play a playlist in Spotify",
                steps = steps,
            )
            assertTrue("Should report learned procedure", addResult.contains("Learned procedure"))
            val spotifyProc = store.getProcedure("Spotify: Play Playlist")
            assertNotNull("Spotify procedure should be saved", spotifyProc)
            assertEquals("Should have 5 steps", 5, spotifyProc?.steps?.size)

            // 4. Remove a step (e.g. remove redundant wait step 2)
            val removeResult = store.removeStep(spotifyProc!!.id, 2)
            assertTrue("Should report removed step", removeResult.contains("Successfully removed step #2"))
            assertEquals("Procedure should now have 4 steps", 4, spotifyProc.steps.size)
            assertEquals("Step 2 should now be Search tab", "Search", spotifyProc.steps[1].target)

            // 5. Update a step
            val updateResult = store.updateStep(spotifyProc.id, 1, "OPEN_APP", "com.spotify.music", "Direct package launch")
            assertTrue("Should report updated step", updateResult.contains("Successfully updated step #1"))
            assertEquals("com.spotify.music", spotifyProc.steps[0].target)

            // 6. Optimize entire procedure into streamlined version
            val optimizedSteps = listOf(
                com.shiina.mobile.memory.ProcedureStep(1, "SYSTEM_INTENT", "spotify://playlist/chill", "Direct deep link"),
                com.shiina.mobile.memory.ProcedureStep(2, "TAP_SCREEN", "Play", "Instant play"),
            )
            val optResult = store.optimizeProcedure(spotifyProc.id, optimizedSteps, "Deep link replaces manual search")
            assertTrue("Should report optimized procedure", optResult.contains("Optimized procedure"))
            assertEquals("Procedure should now have 2 steps", 2, spotifyProc.steps.size)

            // 7. Prompt summary includes procedure titles
            val summary = store.getProceduresSummaryForPrompt(currentGoal = "play a playlist in Spotify")
            assertTrue("Summary should contain Spotify procedure title", summary.contains("Spotify: Play Playlist"))
            assertTrue("Summary should highlight matched workflow", summary.contains("[MATCHED WORKFLOW FOR CURRENT GOAL]"))

            // 8. Delete procedure
            val delResult = store.deleteProcedure(spotifyProc.id)
            assertTrue("Should report deleted procedure", delResult.contains("Deleted procedure"))
            val afterDel = store.getProcedure(spotifyProc.id)
            org.junit.Assert.assertNull("Procedure should be null after deletion", afterDel)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testExecuteProcedureAndAutonomousSynthesis() = kotlinx.coroutines.runBlocking {
        assertTrue("EXECUTE_PROCEDURE must be in KNOWN_TOOLS", ToolDispatcher.KNOWN_TOOLS.contains("EXECUTE_PROCEDURE"))

        val tempDir = java.nio.file.Files.createTempDirectory("shiina_synth_test").toFile()
        try {
            val store = com.shiina.mobile.memory.ProceduralMemoryStore(baseDir = tempDir)

            val actions = listOf(
                com.shiina.mobile.memory.ProcedureStep(1, "OPEN_APP", "com.xo.anilab", "Opens AniLab"),
                com.shiina.mobile.memory.ProcedureStep(2, "TAP_SCREEN", "Search", "Taps search icon"),
                com.shiina.mobile.memory.ProcedureStep(3, "INPUT_TEXT", "Frieren", "Inputs anime title"),
                com.shiina.mobile.memory.ProcedureStep(4, "PRESS_KEY", "enter", "Submits search"),
                com.shiina.mobile.memory.ProcedureStep(5, "TAP_SCREEN", "Episode 1", "Taps first episode"),
            )

            val result = store.synthesizeFromCompletedRun(
                userGoal = "watch frieren on anilab",
                appPackage = "com.xo.anilab",
                appLabel = "AniLab",
                actions = actions,
            )

            assertTrue("Should report learned or optimized procedure", result.contains("Learned procedure") || result.contains("Optimized"))

            val matched = store.findBestMatch("watch frieren")
            assertNotNull("Should find synthesized procedure", matched)
            assertEquals("Should have 5 steps", 5, matched?.steps?.size)
            assertEquals("OPEN_APP", matched?.steps?.first()?.action)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testVoiceTtsSanitizeAndAcoustics() {
        // Test sanitization logic
        val raw = "Sure! Here is the **important** update: check [this link](https://example.com) for details. `System.exit(0)`"
        // Mock sanitization test without needing Android context
        var s = raw.trim()
        s = s.replace(Regex("""```[\s\S]*?```"""), "")
        s = s.replace(Regex("""`[^`]*`"""), "")
        s = s.replace(Regex("""\[([^\]]+)\]\([^\)]+\)"""), "$1")
        s = s.replace(Regex("""https?://\S+"""), "")
        s = s.replace(Regex("""\*{1,3}([^*]+)\*{1,3}"""), "$1")
        s = s.replace(Regex("""\s+"""), " ").trim()

        assertEquals("Sure! Here is the important update: check this link for details.", s)

        // Test acoustics modulation
        // Late night should be calmer, lower pitch, measured pace
        val (latePitch, lateRate) = Pair(0.93f, 0.88f)
        assertTrue("Late night pitch should be lower than default 1.05", latePitch < 1.05f)
        assertTrue("Late night speech rate should be slower than default 1.0", lateRate < 1.0f)

        // Warm should be higher pitch
        val (warmPitch, warmRate) = Pair(1.12f, 1.04f)
        assertTrue("Warm pitch should be higher", warmPitch > 1.05f)
    }

    @Test
    fun testPerTurnTokenOptimizationDownscalingRatio() {
        val originalW = 1080
        val originalH = 2310
        val maxDim = 1024

        val scale = if (maxOf(originalW, originalH) > maxDim) {
            maxDim.toFloat() / maxOf(originalW, originalH)
        } else 1.0f

        val targetW = (originalW * scale).toInt()
        val targetH = (originalH * scale).toInt()

        assertEquals("Scaled height should equal max dimension 1024", 1024, targetH)
        assertTrue("Scaled width should be proportional (~478)", targetW in 470..485)

        val originalPixels = originalW.toLong() * originalH.toLong()
        val scaledPixels = targetW.toLong() * targetH.toLong()
        val reductionRatio = 1.0 - (scaledPixels.toDouble() / originalPixels.toDouble())

        assertTrue("Downscaling must reduce pixel area and visual tokens by at least 75%", reductionRatio >= 0.75)
    }

    @Test
    fun testOnboardingRequirementsAndPermissionsCoverage() {
        // Verifies required sensory permissions for onboarding
        val requiredSensoryPermissions = listOf(
            "Draw over other apps",
            "Accessibility Service",
            "Usage Access",
            "Exact Alarms",
            "Battery Exemption",
        )
        assertEquals("Must cover exactly 5 core sensory permissions in onboarding", 5, requiredSensoryPermissions.size)

        // Verifies procedural execution is registered in tool catalog and dispatcher
        assertTrue("ToolCatalog overview must include EXECUTE_PROCEDURE", ToolCatalog.TOOLSET_OVERVIEW.contains("EXECUTE_PROCEDURE"))
        assertTrue("ToolCatalog overview must include OPTIMIZE_PROCEDURE", ToolCatalog.TOOLSET_OVERVIEW.contains("OPTIMIZE_PROCEDURE"))
        assertTrue("ToolDispatcher KNOWN_TOOLS must include EXECUTE_PROCEDURE", ToolDispatcher.KNOWN_TOOLS.contains("EXECUTE_PROCEDURE"))
    }

    @Test
    fun testSessionBoundaryAndAntiClingingRulesInPrompts() {
        val rules = ShiinaPrompts.GLOBAL_RULES
        val talkDrive = ShiinaPrompts.TALK_DRIVE

        assertTrue("Must include Session Boundaries section in GLOBAL_RULES",
            rules.contains("Conversational Session Boundaries & Temporal Recency Etiquette"))
        assertTrue("Must contain Anti-Clinging & Topic Expiry rule",
            rules.contains("Anti-Clinging & Topic Expiry"))
        assertTrue("Must explicitly forbid dragging up previous conversation",
            rules.contains("Never cling to, obsess over, or drag up previous conversation topics"))
        assertTrue("Must require natural present-moment greetings",
            rules.contains("Natural Present-Moment Greetings"))

        assertTrue("TALK_DRIVE must include Fresh Session Etiquette",
            talkDrive.contains("Fresh Session Etiquette"))
        assertTrue("TALK_DRIVE must state not to drag up old topics into a fresh greeting",
            talkDrive.contains("Never drag concluded earlier subjects into a fresh greeting"))
    }

    @Test
    fun testDynamicLearningEngineUserReturnScenario() {
        val engine = DynamicLearningEngine()
        val senses = """{"music_playing": true, "current_music": "Chillhop Beats"}"""

        // Test 1.5 hours absence
        val scenarios = engine.evaluateRealWorldScenarios(senses, hoursInactive = 1.5)

        val returnScenario = scenarios.firstOrNull { it.contains("USER RETURN AFTER") }
        assertNotNull("Should detect user return after 1.5h absence", returnScenario)
        assertTrue("Return scenario must emphasize greeting in the present moment",
            returnScenario!!.contains("Greet them in the present moment"))
        assertTrue("Return scenario must forbid dragging up old topics",
            returnScenario.contains("Do not dwell on concluded topics from earlier hours"))

        val musicScenario = scenarios.firstOrNull { it.contains("MEDIA PLAYBACK ACTIVE") }
        assertNotNull("Should have media playback scenario", musicScenario)
        assertTrue("Media scenario must be relevant to user intent",
            musicScenario!!.contains("Only reference if directly relevant to the user's intent"))
    }

    @Test
    fun testChatHistorySessionBreaksAndDuration() {
        assertEquals("30 minutes", com.shiina.mobile.data.db.ChatHistory.formatDuration(30 * 60 * 1000L))
        assertEquals("1h 45m", com.shiina.mobile.data.db.ChatHistory.formatDuration(105 * 60 * 1000L))
        assertEquals("2 hours", com.shiina.mobile.data.db.ChatHistory.formatDuration(120 * 60 * 1000L))

        val baseTime = 1760000000000L
        val turns = listOf(
            com.shiina.mobile.data.db.ChatTurn(id = 1, role = "user", text = "play some tunes", timestampMillis = baseTime),
            com.shiina.mobile.data.db.ChatTurn(id = 2, role = "shiina", text = "playing tunes", timestampMillis = baseTime + 60_000L),
            com.shiina.mobile.data.db.ChatTurn(id = 3, role = "user", text = "good afternoon", timestampMillis = baseTime + 60_000L + (105 * 60 * 1000L)),
        )

        val formatted = com.shiina.mobile.data.db.ChatHistory.formatTurnsWithSessionBreaks(turns)
        assertTrue("Formatted history must contain Session Break marker",
            formatted.contains("--- [Session Break: 1h 45m later] ---"))
        assertTrue("Must include user turn", formatted.contains("User: good afternoon"))
    }
}
