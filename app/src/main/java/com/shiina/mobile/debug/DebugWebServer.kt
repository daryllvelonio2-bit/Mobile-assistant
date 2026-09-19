package com.shiina.mobile.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
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

    fun log(category: String, message: String) {
        val event = AppEvent(category = category, message = message)
        events.add(0, event)
        if (events.size > 200) {
            events.removeAt(events.size - 1)
        }
        Log.d("AppDebugServer", "[$category] $message")
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log("CRASH", "Uncaught exception in thread ${thread.name}: ${throwable.stackTraceToString()}")
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
            val line = reader.readLine() ?: return
            val output = socket.getOutputStream()

            val html = buildHtmlDashboard()
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${html.length}\r\n\r\n$html"
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
        sb.append("table{width:100%;border-collapse:collapse;}th,td{padding:8px;border-bottom:1px solid #333;text-align:left;font-size:14px;}")
        sb.append("th{background:#1f1f1f;color:#bb86fc;}.cat{font-weight:bold;color:#03dac6;}</style></head>")
        sb.append("<body><h1>Shiina Mobile Real-Time Debugger</h1>")
        sb.append("<p>Status: Running on port $PORT | Real-time event log (auto-refreshing every 3s)</p>")
        sb.append("<table><tr><th>Time</th><th>Category</th><th>Message</th></tr>")
        for (e in events) {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(e.timestamp))
            sb.append("<tr><td>$time</td><td class=\"cat\">${e.category}</td><td>${e.message.replace("<", "&lt;").replace(">", "&gt;")}</td></tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }
}
