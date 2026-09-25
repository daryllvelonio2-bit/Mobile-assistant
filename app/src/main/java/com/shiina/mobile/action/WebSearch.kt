package com.shiina.mobile.action

import com.shiina.mobile.debug.AppDebugServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Keyless web search. No API key, no account, no new permissions (INTERNET
 * already granted). Strategy: DuckDuckGo Instant Answers first (clean,
 * direct answers for factual queries), then DuckDuckGo Lite HTML scrape
 * (real top results for everything else). Returns a short digest
 * (700 chars max) or "" when nothing useful comes back.
 * Audit A9: result URLs are returned alongside snippets so the Talk loop can
 * chain READ_URL in the same turn. Audit A10: empty/overlong query guards.
 */
class WebSearch(client: OkHttpClient? = null) {

    private val http = (client?.newBuilder() ?: OkHttpClient.Builder())
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val q = query.trim().take(200)
        if (q.isEmpty()) return@withContext ""
        val instant = runCatching { searchInstant(q) }.getOrDefault("")
        if (instant.isNotEmpty()) return@withContext instant
        runCatching { searchLite(q) }.getOrElse { e ->
            AppDebugServer.log("ERROR", "WebSearch failed: ${e.message}")
            ""
        }
    }

    private fun searchInstant(q: String): String {
        val url = "https://api.duckduckgo.com/?q=${URLEncoder.encode(q, "UTF-8")}" +
            "&format=json&no_html=1&skip_disambig=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ShiinaMobile/1.0")
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return ""
            return digest(JSONObject(res.body?.string().orEmpty()))
        }
    }

    /** Scrape DDG Lite result titles + snippets + urls (top 3). */
    private fun searchLite(q: String): String {
        val url = "https://lite.duckduckgo.com/lite/?q=${URLEncoder.encode(q, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            .get()
            .build()
        val html = http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return ""
            res.body?.string().orEmpty()
        }
        if (html.isEmpty()) return ""
        val titles = Regex("result-link'[^>]*>([^<]{3,150})")
            .findAll(html).map { clean(it.groupValues[1]) }.toList()
        val urls = Regex("<a[^>]*href=\"(http[^\"]+)\"[^>]*class='result-link'")
            .findAll(html).map { it.groupValues[1] }.toList()
        val snippets = Regex("result-snippet'[^>]*>\\s*([\\s\\S]{10,400}?)\\s*</td>")
            .findAll(html).map { clean(it.groupValues[1]) }.toList()
        val sb = StringBuilder()
        var i = 0
        while (i < 3 && i < titles.size) {
            sb.append(titles[i])
            if (i < snippets.size && snippets[i].isNotEmpty()) {
                sb.append(": ").append(snippets[i].take(220))
            }
            if (i < urls.size && urls[i].isNotEmpty()) {
                sb.append(" [").append(urls[i].take(120)).append("]")
            }
            sb.append(" ")
            i++
        }
        AppDebugServer.log("ACTION", "WebSearch lite: ${titles.size} results for \"$q\"")
        return sb.toString().trim().take(700)
    }

    private fun clean(s: String): String {
        var t = s.replace(Regex("<[^>]+>"), "")
        for ((e, r) in arrayOf("&amp;" to "&", "&quot;" to "\"", "&#x27;" to "'", "&lt;" to "<", "&gt;" to ">")) {
            t = t.replace(e, r)
        }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun digest(json: JSONObject): String {
        val sb = StringBuilder()
        val answer = json.optString("Answer").trim()
        if (answer.isNotEmpty()) sb.append("Answer: $answer. ")
        val abs = json.optString("AbstractText").trim()
        if (abs.isNotEmpty()) sb.append(abs.take(300)).append(" ")
        val absUrl = json.optString("AbstractURL").trim()
        if (abs.isNotEmpty() && absUrl.isNotEmpty()) sb.append("[").append(absUrl.take(120)).append("] ")
        val related = json.optJSONArray("RelatedTopics")
        if (related != null) {
            var added = 0
            var i = 0
            while (added < 2 && i < related.length()) {
                val t = related.optJSONObject(i)?.optString("Text").orEmpty().trim()
                if (t.isNotEmpty()) {
                    sb.append("Related: ").append(t.take(160)).append(" ")
                    added++
                }
                i++
            }
        }
        return sb.toString().trim().take(700)
    }
}