package com.yunsmall.usbipdcpp

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugging a device in must not open a prompt: the fork registers no
 * USB_DEVICE_ATTACHED activity filter, and automatic re-binding of remembered
 * devices is off unless the user turns it on.
 */
class NoPlugInPromptTest {

    private fun moduleDir() = TestPaths.moduleDir()

    @Test
    fun manifestHasNoUsbAttachedFilter() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.intent.action.MAIN")) // the file was really read
        assertFalse(Regex("<action[^>]+USB_DEVICE_ATTACHED").containsMatchIn(manifest))
        assertFalse(manifest.contains("@xml/device_filter"))
    }

    @Test
    fun autoBindIsOffByDefault() {
        val settings = File(moduleDir(), "src/main/java/com/yunsmall/usbipdcpp/AppSettings.kt").readText()
        assertTrue(settings.contains("getBoolean(KEY_AUTO_BIND_REMEMBERED_DEVICES, false)"))
    }
}
