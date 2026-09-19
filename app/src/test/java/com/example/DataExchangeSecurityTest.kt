package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DataExchangeSecurityTest {

    @Before
    fun setUp() {
        // Clear FileProvider's static strategy cache across Robolectric tests
        try {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
        } catch (_: Exception) {}
    }

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

    @Test
    fun `restoreFullBackup rejects path traversal zip entries and unknown sections`() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tileManager = com.example.map.TileManager(context)

            val zipFile = File(context.cacheDir, "malicious_backup.zip")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
                // Valid map entry
                zos.putNextEntry(java.util.zip.ZipEntry("maps/valid.mbtiles"))
                zos.write("safe content".toByteArray())
                zos.closeEntry()

                // Path traversal attempt targeting root filesDir or escape
                zos.putNextEntry(java.util.zip.ZipEntry("maps/../../evil.txt"))
                zos.write("malicious payload".toByteArray())
                zos.closeEntry()

                // Path traversal attempt in cache prefix
                zos.putNextEntry(java.util.zip.ZipEntry("cache/../../cache_escape.png"))
                zos.write("malicious payload 2".toByteArray())
                zos.closeEntry()

                // Unknown prefix
                zos.putNextEntry(java.util.zip.ZipEntry("unknown/forbidden.txt"))
                zos.write("unknown section".toByteArray())
                zos.closeEntry()
            }

            val restoredCount = tileManager.restoreFullBackup(zipFile)
            assertEquals("Only the safe valid entry should be restored", 1, restoredCount)

            val evilFile = File(context.filesDir, "evil.txt")
            assertFalse("evil.txt must not exist outside target dir", evilFile.exists())

            val cacheEscapeFile = File(context.filesDir, "cache_escape.png")
            assertFalse("cache_escape.png must not exist outside target dir", cacheEscapeFile.exists())

            val validFile = File(File(context.filesDir, "maps"), "valid.mbtiles")
            assertTrue("Valid map file should exist", validFile.exists())

            zipFile.delete()
            validFile.delete()
        }
    }

    @Test
    fun `SafeZipExtraction correctly resolves valid entries and rejects traversals`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val baseDir = File(context.cacheDir, "test_base").apply { mkdirs() }

        // Valid relative paths
        val valid1 = com.example.util.SafeZipExtraction.resolveSafely(baseDir, "tiles/10/500/300.png")
        assertNotNull(valid1)
        assertTrue(valid1!!.canonicalPath.startsWith(baseDir.canonicalPath + File.separator))

        val valid2 = com.example.util.SafeZipExtraction.resolveSafely(baseDir, "simple.txt")
        assertNotNull(valid2)
        assertEquals(File(baseDir, "simple.txt").canonicalPath, valid2!!.canonicalPath)

        // Path traversal attempts
        assertNull(com.example.util.SafeZipExtraction.resolveSafely(baseDir, "../../etc/passwd"))
        assertNull(com.example.util.SafeZipExtraction.resolveSafely(baseDir, "..\\..\\evil.txt"))
        assertNull(com.example.util.SafeZipExtraction.resolveSafely(baseDir, "sub/../../escape.bin"))
        assertNull(com.example.util.SafeZipExtraction.resolveSafely(baseDir, "/../../escape.bin"))
    }

    @Test
    fun `importOfflinePackage rejects path traversal zip entries`() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val tileManager = com.example.map.TileManager(context)

            val packageFile = File(context.cacheDir, "malicious_package.orntpack")
            java.util.zip.ZipOutputStream(java.io.FileOutputStream(packageFile)).use { zos ->
                // Valid tile entry
                zos.putNextEntry(java.util.zip.ZipEntry("osm/1/1/1.png"))
                zos.write("png_bytes".toByteArray())
                zos.closeEntry()

                // Malicious traversal tile entry
                zos.putNextEntry(java.util.zip.ZipEntry("../../evil_tile.png"))
                zos.write("evil_bytes".toByteArray())
                zos.closeEntry()
            }

            val added = tileManager.importOfflinePackage(packageFile)
            assertEquals(1, added)

            val evilFile = File(context.cacheDir, "evil_tile.png")
            assertFalse("evil_tile.png must not be extracted outside baseCacheDir", evilFile.exists())

            packageFile.delete()
        }
    }

    @Test
    fun testShareFile_backupPackage_resolvesFileProviderUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val testFile = File(exportsDir, "test.orntpack").apply { writeText("test") }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                testFile
            )
            assertNotNull(uri)
        } finally {
            testFile.delete()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testShareFile_outsideExports_throwsIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outsideFile = File(context.cacheDir, "outside_test.orntpack").apply { writeText("outside") }
        try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outsideFile
            )
        } finally {
            outsideFile.delete()
        }
    }
}
