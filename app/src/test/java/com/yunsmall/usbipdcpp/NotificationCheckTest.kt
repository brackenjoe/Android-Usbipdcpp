package com.yunsmall.usbipdcpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Fingerprint needed" notification is swallowed when any of four things is off. */
class NotificationCheckTest {

    @Test
    fun allFineIsNotBlocked() {
        assertFalse(notificationsBlocked(33, permissionGranted = true, appNotificationsEnabled = true, authChannelBlocked = false))
        assertFalse(notificationsBlocked(36, permissionGranted = true, appNotificationsEnabled = true, authChannelBlocked = false))
    }

    @Test
    fun belowApi33ThePermissionDoesNotExistSoItsAbsenceIsNotBlocked() {
        assertFalse(notificationsBlocked(32, permissionGranted = false, appNotificationsEnabled = true, authChannelBlocked = false))
        assertFalse(notificationsBlocked(28, permissionGranted = false, appNotificationsEnabled = true, authChannelBlocked = false))
    }

    @Test
    fun fromApi33AMissingPermissionIsBlocked() {
        assertTrue(notificationsBlocked(33, permissionGranted = false, appNotificationsEnabled = true, authChannelBlocked = false))
        assertTrue(notificationsBlocked(37, permissionGranted = false, appNotificationsEnabled = true, authChannelBlocked = false))
    }

    @Test
    fun appNotificationsOffIsBlockedOnEveryApi() {
        for (sdk in listOf(28, 32, 33, 36)) {
            assertTrue("sdk $sdk", notificationsBlocked(sdk, permissionGranted = true, appNotificationsEnabled = false, authChannelBlocked = false))
        }
    }

    @Test
    fun aBlockedAuthChannelIsBlockedOnEveryApi() {
        for (sdk in listOf(28, 32, 33, 36)) {
            assertTrue("sdk $sdk", notificationsBlocked(sdk, permissionGranted = true, appNotificationsEnabled = true, authChannelBlocked = true))
        }
    }

    @Test
    fun exhaustiveTruthTable() {
        for (sdk in listOf(32, 33)) for (p in listOf(false, true)) for (e in listOf(false, true)) for (c in listOf(false, true)) {
            val expected = (sdk >= 33 && !p) || !e || c
            assertEquals("sdk=$sdk p=$p e=$e c=$c", expected, notificationsBlocked(sdk, p, e, c))
        }
    }

    @Test
    fun theAuthChannelIdIsUnchanged() {
        assertEquals("usbipd_auth", AUTH_CHANNEL_ID)
    }
}
