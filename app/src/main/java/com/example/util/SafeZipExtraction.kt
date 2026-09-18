package com.example.util

import java.io.File

object SafeZipExtraction {
    /**
     * Resolves an entry path within a base directory safely, preventing Path Traversal.
     * Returns the target File if and only if its canonical path is located within [baseDir].
     * Returns null if the target would escape [baseDir].
     */
    fun resolveSafely(baseDir: File, entryName: String): File? {
        val safeName = entryName.replace("\\", "/").trimStart('/')
        val target = File(baseDir, safeName)
        val baseCanonical = baseDir.canonicalPath
        val targetCanonical = target.canonicalPath
        return if (targetCanonical == baseCanonical || targetCanonical.startsWith(baseCanonical + File.separator)) {
            target
        } else {
            null
        }
    }
}
