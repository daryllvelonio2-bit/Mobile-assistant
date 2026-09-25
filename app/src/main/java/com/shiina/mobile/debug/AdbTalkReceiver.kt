package com.shiina.mobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shiina.mobile.CompanionApp

/**
 * ADB entry point for PC-side chat & developer tooling (wifi-friendly).
 *
 * Send chat with:
 *   adb shell am broadcast -a com.shiina.mobile.debug.SEND_MESSAGE \
 *     -n com.shiina.mobile/.debug.AdbTalkReceiver \
 *     --es adb_text "hello"
 *
 * Sync API key with:
 *   adb shell am broadcast -a com.shiina.mobile.debug.SET_KEY \
 *     -n com.shiina.mobile/.debug.AdbTalkReceiver \
 *     --es provider "gemini" --es key "AIzaSy..."
 */
class AdbTalkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET_KEY -> {
                val key = intent.getStringExtra("key")?.trim().orEmpty()
                val provider = intent.getStringExtra("provider")?.trim()?.ifEmpty { "gemini" } ?: "gemini"
                if (key.isNotEmpty()) {
                    val container = (context.applicationContext as? CompanionApp)?.container
                    container?.keyStore?.addKey(provider, key)
                    AppDebugServer.log("SECURITY", "API key added via ADB for provider: $provider")
                }
            }
            ACTION_SEND -> {
                val text = intent.getStringExtra(DebugTalkService.EXTRA_ADB_TEXT)
                    ?.trim()?.take(500).orEmpty()
                if (text.isEmpty()) return
                val svc = Intent(context, DebugTalkService::class.java).apply {
                    action = DebugTalkService.ACTION_TALK
                    putExtra(DebugTalkService.EXTRA_ADB_TEXT, text)
                }
                runCatching { context.startForegroundService(svc) }
            }
        }
    }

    companion object {
        const val ACTION_SEND = "com.shiina.mobile.debug.SEND_MESSAGE"
        const val ACTION_SET_KEY = "com.shiina.mobile.debug.SET_KEY"
    }
}
