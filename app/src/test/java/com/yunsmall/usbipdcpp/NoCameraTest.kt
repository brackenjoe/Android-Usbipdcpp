package com.yunsmall.usbipdcpp

import com.yunsmall.usbipdcpp.util.isVideoClass
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fork has neither the camera nor the microphone permission, and the code
 * that asked for one is gone. These tests fail if either comes back.
 */
class NoCameraTest {

    private fun moduleDir() = TestPaths.moduleDir()

    private val forbidden = Regex("CAMERA|RECORD_AUDIO")

    @Test
    fun manifestAsksForNeitherCameraNorMicrophone() {
        val manifest = File(moduleDir(), "src/main/AndroidManifest.xml").readText()
        assertFalse(forbidden.containsMatchIn(manifest))
        assertTrue(manifest.contains("android.permission.INTERNET")) // the file was really read
    }

    @Test
    fun noMainSourceNamesTheCameraOrMicrophonePermission() {
        val main = File(moduleDir(), "src/main")
        val sources = listOf("java", "res").map { File(main, it) }.filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList() }
        assertTrue("sources found", sources.size > 20)
        val hits = sources.filter { forbidden.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun videoClassIsRecognisedAtTheDeviceAndAtTheInterface() {
        assertTrue(isVideoClass(0x0E, emptyList()))            // device-level declaration
        assertTrue(isVideoClass(0x00, listOf(0x02, 0x0E)))     // composite: one video interface
        assertTrue(isVideoClass(0xEF, listOf(0x0E, 0x0E)))     // IAD composite
        assertFalse(isVideoClass(0x00, listOf(0x02, 0x0A)))    // a serial FC
        assertFalse(isVideoClass(0x02, emptyList()))
    }
}
