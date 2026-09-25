package com.shiina.mobile.debug

import android.content.Context
import android.content.Intent

/**
 * One-stop sender for the in-app Chat UI into DebugTalkService's talk pipeline.
 * Same process, so ChatBus (progress/busy/echo) updates take effect immediately;
 * the service persists the real turns in Room and the UI streams from there.
 */
object ChatSend {

    /** Send a chat message from the app UI. Echoes it into ChatBus + sets busy. */
    fun text(context: Context, msg: String) {
        val trimmed = msg.trim().take(500)
        if (trimmed.isEmpty()) return
        ChatBus.echoUser(trimmed)
        ChatBus.beginRun()
        val i = Intent(context, DebugTalkService::class.java).apply {
            action = DebugTalkService.ACTION_TALK
            putExtra(DebugTalkService.EXTRA_ADB_TEXT, trimmed)
            putExtra(DebugTalkService.EXTRA_ECHO, true)
        }
        runCatching { context.startForegroundService(i) }
            .onFailure { ChatBus.endRun() }
    }

    /** Ask the running agent loop to halt at the next turn boundary. */
    fun stop(context: Context) {
        ChatBus.requestStop()
        val i = Intent(context, DebugTalkService::class.java).apply {
            action = DebugTalkService.ACTION_STOP
        }
        runCatching { context.startForegroundService(i) }
    }
}
