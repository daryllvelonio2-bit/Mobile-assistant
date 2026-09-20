package com.shiina.mobile.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class AppEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val category: String,
    val message: String
)

object AppDebugServer {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val events = CopyOnWriteArrayList<AppEvent>()
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private const val PORT = 8085

    val errorHandler = CoroutineExceptionHandler { _, throwable ->
        log("COROUTINE_ERROR", "${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString()}")
    }

    fun log(category: String, message: String) {
        val event = AppEvent(category = category, message = message)
        events.add(0, event)
        if (events.size > 300) {
            events.removeAt(events.size - 1)
        }
        Log.d("AppDebugServer", "[$category] $message")
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH", "Uncaught exception in thread ${thread.name}: ${throwable.javaClass.simpleName} - ${throwable.message}\n${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }

        scope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                log("SYSTEM", "Debug server started on port $PORT. Access via http://<waydroid-ip>:$PORT")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    scope.launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                log("ERROR", "Debug server error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val output = socket.getOutputStream()

            val responseBody: String
            val contentType: String

            if (requestLine.contains("GET /api/events")) {
                contentType = "application/json; charset=UTF-8"
                val sb = StringBuilder("[")
                for ((index, e) in events.withIndex()) {
                    if (index > 0) sb.append(",")
                    val safeMsg = e.message.replace("\"", "\\\"").replace("\n", "\\n")
                    sb.append("{\"timestamp\":${e.timestamp},\"category\":\"${e.category}\",\"message\":\"$safeMsg\"}")
                }
                sb.append("]")
                responseBody = sb.toString()
            } else {
                contentType = "text/html; charset=UTF-8"
                responseBody = buildHtmlDashboard()
            }

            val response = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${responseBody.length}\r\n\r\n$responseBody"
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
            socket.close()
        } catch (_: Exception) {}
    }

    private fun buildHtmlDashboard(): String {
        val sb = StringBuilder()
        sb.append("<html><head><title>Shiina Mobile Debug Server</title>")
        sb.append("<meta http-equiv=\"refresh\" content=\"3\">")
        sb.append("<style>body{font-family:sans-serif;background:#121212;color:#e0e0e0;padding:20px;}")
        sb.append("table{width:100%;border-collapse:collapse;}th,td{padding:8px;border-bottom:1px solid #333;text-align:left;font-size:13px;word-break:break-all;}")
        sb.append("th{background:#1f1f1f;color:#bb86fc;}.cat{font-weight:bold;color:#03dac6;}")
        sb.append(".err{color:#ff5252;font-weight:bold;}.crash{color:#ff1744;font-weight:bold;background:#311b92;}</style></head>")
        sb.append("<body><h1>Shiina Mobile Real-Time Debugger & Error Inspector</h1>")
        sb.append("<p>Status: Running on port $PORT | <a href=\"/api/events\" style=\"color:#bb86fc;\">JSON API (/api/events)</a> | Auto-refreshing every 3s</p>")
        sb.append("<table><tr><th>Time</th><th>Category</th><th>Message</th></tr>")
        for (e in events) {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.timestamp))
            val cssClass = when {
                e.category.contains("CRASH", ignoreCase = true) -> "crash"
                e.category.contains("ERROR", ignoreCase = true) -> "err"
                else -> "cat"
            }
            sb.append("<tr><td>$time</td><td class=\"$cssClass\">${e.category}</td><td><pre style=\"margin:0;white-space:pre-wrap;\">${e.message.replace("<", "&lt;").replace(">", "&gt;")}</pre></td></tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }
}
