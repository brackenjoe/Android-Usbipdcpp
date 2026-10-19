package com.yunsmall.usbipdcpp

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The Kotlin `tag` against the tags the Python reference produced ([AUTH_VECTORS]). */
class AuthVectorsTest {

    private fun h(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun kotlinTagsMatchThePythonReference() {
        assertTrue(AUTH_VECTORS.size >= 3)
        for ((i, v) in AUTH_VECTORS.withIndex()) {
            val mac = softwareMac(hex(v.key))
            val nb = hex(v.nonceB)
            val np = hex(v.nonceP)
            assertEquals("phone tag $i", v.phoneTag, h(AuthV1.tag(mac, AuthV1.LABEL_PHONE, nb, np)))
            // the same Mac instance, as a handshake uses it: doFinal resets it
            assertEquals("box tag $i", v.boxTag, h(AuthV1.tag(mac, AuthV1.LABEL_BOX, nb, np)))
            assertTrue(v.phoneTag != v.boxTag)
        }
    }

    @Test
    fun constantsMatchTheReference() {
        assertEquals("UDIALIN1", String(AuthV1.MAGIC))
        assertEquals("usbip-dialin v1 phone", String(AuthV1.LABEL_PHONE))
        assertEquals("usbip-dialin v1 box", String(AuthV1.LABEL_BOX))
        assertEquals(32, AuthV1.NONCE_LEN)
        assertEquals(32, AuthV1.TAG_LEN)
    }

    /** The default reference is in the repository, so a wrong path cannot turn the tests below into silent skips. */
    @Test
    fun defaultReferenceIsInTheRepository() {
        val ref = File(TestPaths.repoRoot(), "linux/usbip-dialin/usbip_dialin.py")
        assertTrue("missing: $ref", ref.isFile)
        assertTrue("$ref has no auth v1 (_tag): it is stale, refresh linux/usbip-dialin from the box's copy", ref.readText().contains("def _tag"))
    }

    /** The committed constants are what the script prints now (skipped without python3 or the reference). */
    @Test
    fun committedVectorsAreWhatTheReferencePrintsNow() {
        val ref = PythonReference.path()
        assumeTrue("reference not found: ${PythonReference.REF_ENV}", ref != null)
        val script = PythonReference.resource("gen_auth_vectors.py")
        val p = try {
            ProcessBuilder("python3", "-I", script.path, ref!!.path).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            assumeTrue("python3 not runnable", false)
            return
        }
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue())
        val printed = Regex("\"([0-9a-f]{64})\"").findAll(out).map { it.groupValues[1] }.toList()
        val committed = AUTH_VECTORS.flatMap { listOf(it.key, it.nonceB, it.nonceP, it.phoneTag, it.boxTag) }
        assertEquals(committed, printed)
    }
}

/** Where the Python reference and the test resources are. */
object PythonReference {
    const val REF_ENV = "USBIP_DIALIN_REF"

    /** The fork's own copy of the reference, found from the repository root, not from the working directory. */
    private fun defaultRef(): File = File(TestPaths.repoRoot(), "linux/usbip-dialin/usbip_dialin.py")

    fun path(): File? = (System.getenv(REF_ENV)?.let { File(it) } ?: defaultRef()).takeIf { it.isFile }

    fun resource(name: String): File =
        File(PythonReference::class.java.getResource("/$name")!!.toURI())
}
