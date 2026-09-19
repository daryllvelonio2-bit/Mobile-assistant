package com.shiina.mobile.decision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Gemini reasoning backend. Full prompt/response parsing lands in Phase 3. */
class GeminiProvider(
    private val keys: RoundRobinKeyPool,
    private val http: OkHttpClient,
    private val model: String = "gemini-3.5-flash-lite",
) : DecisionProvider {

    override val name = "gemini"
    override val supportsVision = true

    override suspend fun generate(summary: DecisionSummary): Decision = withContext(Dispatchers.IO) {
        val key = keys.next() ?: run {
            com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "No Gemini API keys found in KeyStore")
            throw IllegalStateException("no gemini keys")
        }
        com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "Calling Gemini API (model=$model)...")
        try {
            val body = JSONObject()
                .put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", prompt(summary))
                    ))
                ))
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API error response: code=$code")
                    throw IllegalStateException("gemini $code")
                }
                val respStr = response.body?.string().orEmpty()
                com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API success response received")
                parse(respStr, summary)
            }
        } catch (e: Exception) {
            keys.reportFailure(key)
            com.shiina.mobile.debug.AppDebugServer.log("GEMINI", "API call failed: ${e.message}")
            throw e
        }
    }

    private fun prompt(summary: DecisionSummary): String =
        "Tone for screen drift ${summary.entertainmentMinutes}min vs baseline " +
            "${summary.entertainmentBaseline}, goals open=${summary.goalsOpen} " +
            "done=${summary.goalsDone} missed=${summary.goalsMissed}. " +
            "Reply with one word tone only."

    private fun parse(body: String, summary: DecisionSummary): Decision {
        val drifted = summary.entertainmentMinutes > summary.entertainmentBaseline
        val tone = try {
            JSONObject(body)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim().lowercase().take(32)
        } catch (_: Exception) {
            if (drifted) "steady" else "calm"
        }
        return Decision(tone = tone, interrupt = drifted, action = "none")
    }
}
