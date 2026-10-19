package com.yunsmall.usbipdcpp

import java.io.File

/**
 * Where things are, found from the working directory. Gradle runs unit tests
 * with the app module's directory as the working directory; an IDE may use the
 * repository root. Walking up and looking for a marker works from either.
 */
object TestPaths {
    private fun start(): File = File(System.getProperty("user.dir")).absoluteFile

    /** The app module's directory (the one holding src/main/AndroidManifest.xml). */
    fun moduleDir(): File {
        var dir: File? = start()
        while (dir != null) {
            if (File(dir, "src/main/AndroidManifest.xml").isFile) return dir
            File(dir, "app").takeIf { File(it, "src/main/AndroidManifest.xml").isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("app module not found from ${start()}")
    }

    /** The repository root: the directory above the app module, which holds linux/usbip-dialin. */
    fun repoRoot(): File = moduleDir().parentFile
}
