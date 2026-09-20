package com.shiina.mobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * ADB entry point for PC-side chat (wifi-friendly).
 * Send with: adb shell am broadcast -a com.shiina.mobile.debug.SEND_MESSAGE \
 *   --es adb_text "hello"
 * Forwards into DebugTalkService.ACTION_TALK, same pipeline as the
 * notification RemoteInput reply. Exported=true so the shell UID can
 * deliver; text is capped at 500 chars like RemoteInput.
 */
class AdbTalkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND) return
        val text = intent.getStringExtra(DebugTalkService.EXTRA_ADB_TEXT)
            ?.trim()?.take(500).orEmpty()
        if (text.isEmpty()) return
        val svc = Intent(context, DebugTalkService::class.java).apply {
            action = DebugTalkService.ACTION_TALK
            putExtra(DebugTalkService.EXTRA_ADB_TEXT, text)
        }
        runCatching { context.startForegroundService(svc) }
    }

    companion object {
        const val ACTION_SEND = "com.shiina.mobile.debug.SEND_MESSAGE"
    }
}
