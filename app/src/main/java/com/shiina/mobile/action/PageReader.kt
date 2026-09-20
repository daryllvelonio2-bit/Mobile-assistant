package com.shiina.mobile.action

import com.shiina.mobile.debug.AppDebugServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Track A2 — page reader: search gives snippets; READ_URL fetches the actual
 * article text so she can answer from the source, then cite it. Plain HTTP +
 * tag stripping, no new dependencies, no new permissions.
 * Audit A7: output starts with a Source: line so answers can cite the page.
 * Audit A8: content-type guard (skip binaries/PDFs) + body capped at ~1MB so
 * a huge page can't blow the heap.
 * Returns clean text (2500 chars max) or "" on any failure.
 */
class PageReader {

    private val http = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun read(url: String): String = withContext(Dispatchers.IO) {
        val u = url.trim().take(500)
        if (!u.startsWith("http://") && !u.startsWith("https://")) return@withContext ""
        runCatching {
            val req = Request.Builder().url(u)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) ShiinaMobile/1.0")
                .get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    AppDebugServer.log("ACTION", "READ_URL ${res.code} for $u")
                    return@withContext ""
                }
                val type = res.header("Content-Type").orEmpty().lowercase()
                if (type.isNotEmpty() && !type.contains("text/") && !type.contains("html")) {
                    AppDebugServer.log("ACTION", "READ_URL skipped non-text type: $type")
                    return@withContext ""
                }
                // Cap the body at ~1MB; article text is always in the head.
                val html = res.peekBody(1_000_000).string()
                val text = extract(html)
                if (text.isEmpty()) "" else "Source: $u\n$text"
            }
        }.getOrElse { e ->
            AppDebugServer.log("ERROR", "PageReader failed: ${e.message}")
            ""
        }
    }

    private fun extract(html: String): String {
        var t = html
            .replace(Regex("(?is)<(script|style|nav|header|footer|form|svg|noscript)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<br[^>]*>"), "\n")
            .replace(Regex("(?is)</(p|div|h[1-6]|li|tr|blockquote)>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        for ((e, r) in arrayOf(
            "&amp;" to "&", "&quot;" to "\"", "&#x27;" to "'", "&#39;" to "'",
            "&lt;" to "<", "&gt;" to ">", "&nbsp;" to " ",
        )) {
            t = t.replace(e, r)
        }
        t = t.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
        return t.trim().take(2500)
    }
}