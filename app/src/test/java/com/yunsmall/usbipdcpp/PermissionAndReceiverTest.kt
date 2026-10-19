package com.yunsmall.usbipdcpp

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A permission broadcast is a claim, and every runtime receiver is not exported. */
class PermissionAndReceiverTest {

    @Test
    fun aBroadcastClaimingGrantedForADeviceWithoutPermissionIsNotGranted() {
        val d = decidePermissionResult(claimedGranted = true) { false }
        assertFalse("must not bind", d.granted)
        assertTrue(d.claimMismatch)
    }

    @Test
    fun aGrantTheSystemConfirmsIsGranted() {
        val d = decidePermissionResult(claimedGranted = true) { true }
        assertTrue(d.granted)
        assertFalse(d.claimMismatch)
    }

    @Test
    fun theSystemsRecordWinsOverADenialClaim() {
        // "false" in the extra, permission held: what is held is what counts
        val d = decidePermissionResult(claimedGranted = false) { true }
        assertTrue(d.granted)
        assertTrue(d.claimMismatch)
        val denied = decidePermissionResult(claimedGranted = false) { false }
        assertFalse(denied.granted)
        assertFalse(denied.claimMismatch)
    }

    @Test
    fun aCheckThatThrowsIsNotAGrant() {
        val d = decidePermissionResult(claimedGranted = true) { throw SecurityException("test") }
        assertFalse(d.granted)
        assertTrue(d.claimMismatch)
    }

    @Test
    fun noMainSourceRegistersAReceiverOtherThanThroughContextCompat() {
        val sources = File(TestPaths.moduleDir(), "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val offenders = mutableListOf<String>()
        var viaCompat = 0
        for (f in sources) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (code.contains("ContextCompat.registerReceiver(") &&
                    code.contains("ContextCompat.RECEIVER_NOT_EXPORTED")
                ) viaCompat++
                else if (Regex("""(?<![A-Za-z])registerReceiver\(""").containsMatchIn(code) &&
                    !code.contains("fun registerReceiver(") && !code.contains("permissionManager.registerReceiver(")
                ) offenders.add("${f.name}:${i + 1}: ${line.trim()}")
            }
        }
        assertEquals(emptyList<String>(), offenders)
        assertEquals("the permission manager's and the service's receivers", 2, viaCompat)
    }
}
