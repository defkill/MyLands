package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataExchangeSecurityTest {

    @Test
    fun `sanitizeFileName strips path traversal segments and dangerous characters`() {
        assertEquals("evil.txt", sanitizeFileName("../../evil.txt", "fallback.txt"))
        assertEquals("orientir_database.db", sanitizeFileName("../../databases/orientir_database.db", "fallback.db"))
        assertEquals("evil.mbtiles", sanitizeFileName("..\\..\\evil.mbtiles", "map.mbtiles"))
        assertEquals("map.mbtiles", sanitizeFileName("...", "map.mbtiles"))
        assertEquals("elevation.hgt", sanitizeFileName("", "elevation.hgt"))
        assertEquals("elevation.hgt", sanitizeFileName("   ", "elevation.hgt"))
        assertEquals("clean_name_.hgt", sanitizeFileName("clean:name?.hgt", "fallback.hgt"))
        assertEquals("valid-name_123.mbtiles", sanitizeFileName("valid-name_123.mbtiles", "fallback.mbtiles"))
    }

    @Test
    fun `file creation with sanitized name stays strictly inside target directory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File(context.filesDir, "maps").apply { mkdirs() }

        val maliciousName = "../../databases/orientir_database.db"
        val safeName = sanitizeFileName(maliciousName, "map.mbtiles")
        val targetFile = File(targetDir, safeName)

        // Defense in depth check
        val isSafe = targetFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)
        assertTrue("Sanitized file must remain strictly within targetDir", isSafe)
        assertEquals(File(targetDir, "orientir_database.db").canonicalPath, targetFile.canonicalPath)
    }

    @Test
    fun `canonical path check rejects raw un-sanitized traversal attempts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targetDir = File(context.filesDir, "maps").apply { mkdirs() }

        val rawMaliciousFile = File(targetDir, "../../evil.txt")
        val isSafe = rawMaliciousFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)
        assertFalse("Un-sanitized relative traversal file must be rejected", isSafe)
    }

    @Test
    fun `elevation cache import stays strictly inside cacheDir`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cacheDir = context.cacheDir

        val rawDisplayName = "../../../etc/passwd"
        val safeDisplayName = sanitizeFileName(rawDisplayName, "elevation.hgt")
        val temp = File(cacheDir, safeDisplayName)

        assertTrue(
            "Elevation temp file must stay within cacheDir",
            temp.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator)
        )
        assertEquals(File(cacheDir, "passwd").canonicalPath, temp.canonicalPath)
    }
}
