package com.shiina.mobile.debug

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
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
        val chunkSize = 3500
        if (message.length <= chunkSize) {
            Log.d("AppDebugServer", "[$category] $message")
        } else {
            val totalParts = (message.length + chunkSize - 1) / chunkSize
            for (i in 0 until totalParts) {
                val start = i * chunkSize
                val end = minOf(start + chunkSize, message.length)
                val partContent = message.substring(start, end)
                Log.d("AppDebugServer", "[$category] [part ${i + 1}/$totalParts] $partContent")
            }
        }
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
                // Audit B10: bind loopback ONLY — never expose telemetry on LAN/hotspot.
                // PC access: adb forward tcp:8085 tcp:8085 -> http://127.0.0.1:8085
                serverSocket = ServerSocket(PORT, 5, InetAddress.getLoopbackAddress())
                log("SYSTEM", "Debug server started on 127.0.0.1:$PORT (loopback only). PC: adb forward tcp:$PORT tcp:$PORT")
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
            socket.soTimeout = 5000
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
                    val safeMsg = org.json.JSONObject.quote(e.message)
                    val safeCat = org.json.JSONObject.quote(e.category)
                    sb.append("{\"timestamp\":${e.timestamp},\"category\":$safeCat,\"message\":$safeMsg}")
                }
                sb.append("]")
                responseBody = sb.toString()
            } else {
                contentType = "text/html; charset=UTF-8"
                responseBody = buildHtmlDashboard()
            }

            val responseBytes = responseBody.toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${responseBytes.size}\r\n\r\n"
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(responseBytes)
            output.flush()
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Debug dashboard, restyled onto the Shiina design language (dark-first indigo/amber
     * on OLED black) with live fetching instead of a full page refresh, so the search box
     * and category filter survive each update.
     */
    private fun buildHtmlDashboard(): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>Shiina · Debug Server</title><style>")
        sb.append(":root{--bg:#030712;--surface:#0B1220;--surface2:#1E293B;--line:#1E293B;")
        sb.append("--fg:#F8FAFC;--muted:#94A3B8;--primary:#818CF8;--amber:#FBBF24;--teal:#5EEAD4;--red:#F87171;}")
        sb.append("*{box-sizing:border-box}")
        sb.append("body{margin:0;background:var(--bg);color:var(--fg);")
        sb.append("font:14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif}")
        sb.append("header{position:sticky;top:0;z-index:10;background:linear-gradient(180deg,rgba(3,7,18,.98),rgba(3,7,18,.86));")
        sb.append("backdrop-filter:blur(12px);border-bottom:1px solid var(--line);padding:14px 20px;display:flex;align-items:center;gap:14px;flex-wrap:wrap}")
        sb.append(".orb{width:34px;height:34px;border-radius:50%;flex:0 0 auto;")
        sb.append("background:radial-gradient(circle at 32% 30%,#A5B4FC,#4F46E5 65%,#312E81);box-shadow:0 0 18px rgba(99,102,241,.45)}")
        sb.append("h1{margin:0;font-size:16px;font-weight:800;letter-spacing:-.2px}")
        sb.append(".sub{color:var(--muted);font-size:12px;margin-top:2px}")
        sb.append(".spacer{flex:1}")
        sb.append(".pill{display:inline-flex;align-items:center;gap:6px;padding:5px 11px;border-radius:999px;")
        sb.append("background:var(--surface);border:1px solid var(--line);font-size:12px;font-weight:600;color:var(--muted)}")
        sb.append(".dot{width:8px;height:8px;border-radius:50%;background:var(--teal);box-shadow:0 0 8px var(--teal);animation:pulse 2s ease-in-out infinite}")
        sb.append("@keyframes pulse{0%,100%{opacity:1}50%{opacity:.35}}")
        sb.append("a{color:var(--primary);text-decoration:none}a:hover{text-decoration:underline}")
        sb.append(".bar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:14px 20px;border-bottom:1px solid var(--line);position:sticky;top:63px;")
        sb.append("background:rgba(3,7,18,.94);backdrop-filter:blur(12px);z-index:9}")
        sb.append("input[type=search]{flex:1;min-width:180px;background:var(--surface);border:1px solid var(--line);color:var(--fg);")
        sb.append("padding:9px 13px;border-radius:10px;font-size:13px;outline:none}")
        sb.append("input[type=search]:focus{border-color:var(--primary)}")
        sb.append("button.chip{background:var(--surface);border:1px solid var(--line);color:var(--muted);padding:7px 12px;border-radius:999px;")
        sb.append("font-size:12px;font-weight:600;cursor:pointer;transition:all .15s}")
        sb.append("button.chip:hover{border-color:var(--primary);color:var(--fg)}")
        sb.append("button.chip.on{background:rgba(99,102,241,.18);border-color:var(--primary);color:#C7D2FE}")
        sb.append("main{padding:8px 20px 60px}")
        sb.append(".row{display:grid;grid-template-columns:96px 132px 1fr;gap:14px;padding:11px 0;border-bottom:1px solid rgba(30,41,59,.7);align-items:start}")
        sb.append(".row:hover{background:rgba(30,41,59,.35)}")
        sb.append(".t{color:var(--muted);font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;padding-top:1px}")
        sb.append(".c{font:700 11px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;letter-spacing:.3px;text-transform:uppercase}")
        sb.append("pre{margin:0;white-space:pre-wrap;word-break:break-word;font:12.5px/1.55 ui-monospace,SFMono-Regular,Menlo,monospace;color:#E2E8F0}")
        sb.append(".crash{color:#FDA4AF}.crash+.cell pre,.row.danger pre{color:#FECDD3}")
        sb.append(".err{color:var(--red)}.warn{color:var(--amber)}.gem{color:var(--primary)}")
        sb.append(".talk{color:var(--teal)}.agent{color:#C4B5FD}.muted{color:var(--muted)}")
        sb.append(".row.danger{background:rgba(127,29,29,.22)}")
        sb.append(".empty{color:var(--muted);text-align:center;padding:60px 0;font-size:13px}")
        sb.append("@media(max-width:640px){.row{grid-template-columns:1fr;gap:2px}.t,.c{display:inline-block;margin-right:8px}}")
        sb.append("</style></head><body>")
        sb.append("<header><div class=\"orb\"></div><div><h1>Shiina Debug Server</h1>")
        sb.append("<div class=\"sub\">127.0.0.1:$PORT · loopback only · <a href=\"/api/events\">/api/events</a> (JSON)</div></div>")
        sb.append("<div class=\"spacer\"></div><span class=\"pill\"><span class=\"dot\"></span><span id=\"count\">0</span> events</span></header>")
        sb.append("<div class=\"bar\">")
        sb.append("<input id=\"q\" type=\"search\" placeholder=\"Filter messages…\" autocomplete=\"off\">")
        sb.append("<button class=\"chip on\" data-f=\"ALL\">All</button>")
        sb.append("<button class=\"chip\" data-f=\"ERROR\">Errors</button>")
        sb.append("<button class=\"chip\" data-f=\"CRASH\">Crashes</button>")
        sb.append("<button class=\"chip\" data-f=\"GEMINI\">Gemini</button>")
        sb.append("<button class=\"chip\" data-f=\"TALK\">Talk</button>")
        sb.append("<button class=\"chip\" data-f=\"AGENT\">Agent</button>")
        sb.append("<button class=\"chip\" id=\"pause\">Pause</button>")
        sb.append("</div><main id=\"feed\"><div class=\"empty\">Connecting…</div></main><script>")
        sb.append("var all=[],filter='ALL',query='',paused=false;")
        sb.append("function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}")
        sb.append("function cls(c){c=String(c).toUpperCase();")
        sb.append("if(c.indexOf('CRASH')>=0)return 'crash';if(c.indexOf('ERROR')>=0||c.indexOf('FAIL')>=0)return 'err';")
        sb.append("if(c.indexOf('WARN')>=0)return 'warn';if(c.indexOf('GEMINI')>=0||c.indexOf('TOKEN')>=0)return 'gem';")
        sb.append("if(c.indexOf('TALK')>=0||c.indexOf('MOOD')>=0||c.indexOf('INTERACTION')>=0||c.indexOf('DECISION')>=0)return 'talk';")
        sb.append("if(c.indexOf('AGENT')>=0||c.indexOf('TOOL')>=0||c.indexOf('SERVICE')>=0||c.indexOf('LOOP')>=0)return 'agent';")
        sb.append("return 'muted';}")
        sb.append("function clock(ms){var d=new Date(ms);function p(n,l){n=String(n);while(n.length<l)n='0'+n;return n;}")
        sb.append("return p(d.getHours(),2)+':'+p(d.getMinutes(),2)+':'+p(d.getSeconds(),2)+'.'+p(d.getMilliseconds(),3);}")
        sb.append("function render(){var f=document.getElementById('feed');var q=query.toLowerCase();")
        sb.append("var rows=all.filter(function(e){if(filter!=='ALL'&&String(e.category).toUpperCase().indexOf(filter)<0)return false;")
        sb.append("if(q&&(String(e.message).toLowerCase().indexOf(q)<0&&String(e.category).toLowerCase().indexOf(q)<0))return false;return true;});")
        sb.append("document.getElementById('count').textContent=rows.length;")
        sb.append("if(!rows.length){f.innerHTML='<div class=\"empty\">No matching events.</div>';return;}")
        sb.append("var h='';for(var i=0;i<rows.length;i++){var e=rows[i];var k=cls(e.category);")
        sb.append("h+='<div class=\"row'+(k==='crash'?' danger':'')+'\"><div class=\"t\">'+clock(e.timestamp)+'</div>';")
        sb.append("h+='<div class=\"c '+k+'\">'+esc(e.category)+'</div><div class=\"cell\"><pre>'+esc(e.message)+'</pre></div></div>';}")
        sb.append("f.innerHTML=h;}")
        sb.append("var feed=document.getElementById('feed');")
        sb.append("var bar=document.querySelector('.bar');")
        sb.append("bar.addEventListener('click',function(ev){var b=ev.target.closest('button.chip');if(!b)return;")
        sb.append("if(b.id==='pause'){paused=!paused;document.getElementById('pause').classList.toggle('on',paused);")
        sb.append("document.getElementById('pause').textContent=paused?'Paused':'Pause';return;}")
        sb.append("filter=b.getAttribute('data-f');var cs=bar.querySelectorAll('button.chip');")
        sb.append("for(var i=0;i<cs.length;i++){if(cs[i].getAttribute('data-f'))cs[i].classList.toggle('on',cs[i]===b);}render();});")
        sb.append("document.getElementById('q').addEventListener('input',function(ev){query=ev.target.value;render();});")
        sb.append("function tick(){if(paused)return;fetch('/api/events').then(function(r){return r.json();})")
        sb.append(".then(function(d){all=d;render();}).catch(function(){});}")
        sb.append("setInterval(tick,2000);tick();")
        sb.append("</script></body></html>")
        return sb.toString()
    }
}
