package com.example.util

import java.io.File
import java.util.zip.ZipFile

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
        if (targetCanonical != baseCanonical && !targetCanonical.startsWith(baseCanonical + File.separator)) {
            return null
        }

        // Prevent Symlink Hijacking: verify that neither target nor any existing intermediate parent is a symbolic link
        try {
            var curr: File? = target
            val basePath = baseDir.toPath()
            while (curr != null) {
                val currPath = curr.toPath()
                if (curr.exists() && java.nio.file.Files.isSymbolicLink(currPath)) {
                    return null
                }
                if (currPath == basePath) break
                curr = curr.parentFile
            }
        } catch (_: Throwable) {
            return null
        }

        return target
    }

    /**
     * Validates that [zipFile] is a non-corrupt, valid ZIP archive that can be safely traversed.
     */
    fun verifyZipIntegrity(zipFile: File): Boolean {
        if (!zipFile.exists() || zipFile.length() < 22) return false
        return try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.isEmpty()) return false
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }
}

