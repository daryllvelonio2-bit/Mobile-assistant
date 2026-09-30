package com.shiina.mobile.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.shiina.mobile.BuildConfig
import com.shiina.mobile.CompanionApp

/**
 * ADB entry point for PC-side chat & developer tooling (wifi-friendly).
 *
 * Access control (AUDIT finding #1): the manifest guards this receiver with
 * `android:permission="android.permission.DUMP"`. Only the adb shell (uid 2000)
 * and system/privileged callers hold DUMP, so AMS refuses to deliver a
 * third-party broadcast before `onReceive` runs, on every supported API level.
 * [isTrustedCaller] adds an in-process allowlist on API 34+, where the framework
 * exposes the real sender uid ([BroadcastReceiver.getSentFromUid]).
 *
 * Do NOT gate on `Binder.getCallingUid()` here: during a broadcast callback the
 * binder identity is already unwound, so on-device it returns this app's own uid
 * (verified on API 33) — not the sender's — which would let every caller through.
 *
 * Send chat with:
 *   adb shell am broadcast -a com.shiina.mobile.debug.SEND_MESSAGE \
 *     -n com.shiina.mobile/.debug.AdbTalkReceiver \
 *     --es adb_text "hello"
 *
 * Sync API key with (debug builds only):
 *   adb shell am broadcast -a com.shiina.mobile.debug.SET_KEY \
 *     -n com.shiina.mobile/.debug.AdbTalkReceiver \
 *     --es provider "gemini" --es key "AIzaSy..."
 */
class AdbTalkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isTrustedCaller()) return
        when (intent.action) {
            ACTION_SET_KEY -> {
                // Never allow key sync outside a debug build.
                if (!BuildConfig.DEBUG) return
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

    /**
     * The manifest `android:permission` guard already restricts delivery to callers
     * holding DUMP (the adb shell / system). This adds an explicit sender allowlist
     * where the framework can report it (API 34+); below that the guard is the gate.
     */
    private fun isTrustedCaller(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val sender = sentFromUid
        return sender == Process.SHELL_UID || sender == Process.myUid()
    }

    companion object {
        const val ACTION_SEND = "com.shiina.mobile.debug.SEND_MESSAGE"
        const val ACTION_SET_KEY = "com.shiina.mobile.debug.SET_KEY"
    }
}
