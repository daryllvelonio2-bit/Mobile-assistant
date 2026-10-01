package com.shiina.mobile.debug

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUDIT finding N5 — the API-34+ sender-identity filter in [AdbTalkReceiver].
 *
 * `BroadcastReceiver.getSentFromUid()` returns [Process.INVALID_UID] unless the *sender*
 * opted in via `BroadcastOptions.setShareIdentityEnabled(true)`. The adb shell never opts
 * in, so a legitimate `am broadcast` (send.py / send.py --sync-keys) reports INVALID_UID.
 * The predicate must therefore accept INVALID_UID, and must keep accepting the shell and
 * self uids, while still rejecting a foreign app uid when the framework does report one.
 *
 * Below API 34 the framework exposes no sender uid at all; the manifest DUMP guard is the
 * only gate, so the predicate is a no-op there.
 */
class AdbTalkReceiverTrustTest {

    private val selfUid = 10_128
    private val foreignAppUid = 10_133
    private val api33 = 33
    private val api34 = 34

    // --- the N5 regression: INVALID_UID must not be rejected on API 34+ ---

    @Test
    fun `api 34 accepts INVALID_UID (adb shell never shares identity)`() {
        assertTrue(
            "INVALID_UID means the sender did not opt in to identity sharing; the manifest " +
                "DUMP guard already gated delivery, so it must be accepted",
            AdbTalkReceiver.isTrustedSender(api34, Process.INVALID_UID, selfUid)
        )
    }

    @Test
    fun `api 34 still accepts shell uid and own uid`() {
        assertTrue(AdbTalkReceiver.isTrustedSender(api34, Process.SHELL_UID, selfUid))
        assertTrue(AdbTalkReceiver.isTrustedSender(api34, selfUid, selfUid))
    }

    @Test
    fun `api 34 rejects a foreign app uid when identity is shared`() {
        assertFalse(AdbTalkReceiver.isTrustedSender(api34, foreignAppUid, selfUid))
    }

    // --- below API 34 there is no sender identity; the manifest guard is the gate ---

    @Test
    fun `api 33 is a no-op for every sender uid`() {
        assertTrue(AdbTalkReceiver.isTrustedSender(api33, Process.INVALID_UID, selfUid))
        assertTrue(AdbTalkReceiver.isTrustedSender(api33, Process.SHELL_UID, selfUid))
        assertTrue(AdbTalkReceiver.isTrustedSender(api33, foreignAppUid, selfUid))
    }

    /**
     * Documents the exact pre-fix behaviour so this test cannot pass vacuously: the old
     * predicate (`shell || self`) returned false for INVALID_UID, which is what silently
     * dropped the adb-shell broadcast on Android 14+.
     */
    @Test
    fun `pre-fix predicate would have dropped INVALID_UID (non-vacuous)`() {
        fun preFix(senderUid: Int, ownUid: Int): Boolean =
            senderUid == Process.SHELL_UID || senderUid == ownUid

        assertFalse(preFix(Process.INVALID_UID, selfUid))
        assertTrue(AdbTalkReceiver.isTrustedSender(api34, Process.INVALID_UID, selfUid))
    }

    // --- regression: the getter must not be read below API 34 ---

    @Test
    fun `below api 34 the sent-from-uid getter is never invoked`() {
        var invoked = false
        val uid = AdbTalkReceiver.callerUidToCheck(api33) {
            invoked = true
            throw NoSuchMethodError("getSentFromUid") // what the platform would do
        }
        assertEquals(Process.INVALID_UID, uid)
        assertFalse("getSentFromUid must not be read on API < 34", invoked)
    }

    @Test
    fun `api 34 reads the sent-from-uid getter`() {
        val uid = AdbTalkReceiver.callerUidToCheck(api34) { foreignAppUid }
        assertEquals(foreignAppUid, uid)
    }
}
