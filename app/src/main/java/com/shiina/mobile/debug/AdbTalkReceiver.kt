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
 * That manifest guard is the *only* enforcement — [isTrustedCaller] is a
 * rejection filter, not a security boundary (AUDIT finding N5).
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
     * Thin wrapper over the pure [isTrustedSender] decision so the platform input
     * (this receiver's own uid, and the framework-reported sender uid) is read here
     * and the *logic* stays unit-testable off-device.
     *
     * [BroadcastReceiver.getSentFromUid] only exists on API 34+ — reading the
     * `sentFromUid` accessor on an older device throws `NoSuchMethodError` and crashes
     * the receiver before the predicate can short-circuit (device-verified on API 33).
     * [callerUidToCheck] therefore takes the getter as a lambda and only invokes it once
     * the level check has passed.
     */
    private fun isTrustedCaller(): Boolean {
        val sdk = Build.VERSION.SDK_INT
        return isTrustedSender(sdk, callerUidToCheck(sdk) { sentFromUid }, Process.myUid())
    }

    companion object {
        const val ACTION_SEND = "com.shiina.mobile.debug.SEND_MESSAGE"
        const val ACTION_SET_KEY = "com.shiina.mobile.debug.SET_KEY"

        /**
         * The manifest `android:permission="android.permission.DUMP"` guard is the real
         * enforcement: AMS rejects non-DUMP callers before `onReceive` runs. This predicate
         * is only a belt-and-braces filter and must never reject a caller the manifest
         * guard admitted.
         *
         * On API 34+ [BroadcastReceiver.getSentFromUid] returns [Process.INVALID_UID]
         * whenever the *sender* did not opt in to identity sharing
         * (`BroadcastOptions.setShareIdentityEnabled(true)`); the adb shell never opts in
         * (`ActivityManagerShellCommand` has no such call). A legitimate `am broadcast` —
         * including `send.py` / `send.py --sync-keys` — therefore reports INVALID_UID.
         * Treating that as untrusted silently drops every adb broadcast (AUDIT finding N5),
         * so INVALID_UID must be accepted. Below API 34 the framework exposes no sender uid
         * at all ([BroadcastReceiver.getSentFromUid] does not exist there), so the manifest
         * guard alone gates delivery and this predicate is a no-op.
         */
        internal fun isTrustedSender(sdkInt: Int, senderUid: Int, selfUid: Int): Boolean {
            if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            return senderUid == Process.INVALID_UID ||
                senderUid == Process.SHELL_UID ||
                senderUid == selfUid
        }

        /**
         * Reads the framework sender uid only where the accessor exists. On API < 34
         * [BroadcastReceiver.getSentFromUid] is absent, so [readSentFromUid] must not be
         * invoked — an eager read throws `NoSuchMethodError` and crashes the receiver
         * (device-verified on the API 33 Waydroid). Kept pure so the guard is asserted
         * by unit tests rather than only by the platform.
         */
        internal fun callerUidToCheck(sdkInt: Int, readSentFromUid: () -> Int): Int =
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) readSentFromUid()
            else Process.INVALID_UID
    }
}
