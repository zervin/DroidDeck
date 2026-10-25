package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddonPackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, "addons/sample")

    @Before fun clean() {
        root.deleteRecursively()
    }

    private fun installed(version: String, complete: Boolean = true, modified: Long = 1_000L): File =
        File(root, version).apply {
            mkdirs()
            File(this, "tool").writeText("x")
            if (complete) File(this, AddonPack.COMPLETE).writeText(version)
            setLastModified(modified)
        }

    @Test fun unpinnedLocalBuildReusesTheNewestCompletePack() {
        installed("a".repeat(64), modified = 1_000L)
        installed("b".repeat(64), modified = 2_000L)
        installed("c".repeat(64), complete = false, modified = 3_000L)
        val pack = AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = true)

        assertTrue(pack.installed(context))
        assertEquals("b".repeat(64), pack.dir(context).name)
    }

    @Test fun unpinnedLocalBuildWithNothingInstalledHasNoPack() {
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = true).installed(context))
    }

    @Test fun pinnedBuildIgnoresOtherInstalledVersions() {
        installed("b".repeat(64))
        val pack = AddonPack("sample", "a".repeat(64), 1L, listOf("tool"))

        assertFalse(pack.installed(context))
        assertEquals("a".repeat(64), pack.dir(context).name)
    }

    @Test fun ciBuildWithoutAPinNeverReuses() {
        installed("b".repeat(64))
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = false).installed(context))
    }

    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun bundledArchiveMustMatchTheBuildPinAndSize() {
        val archive = File(context.cacheDir, "sample-pack.tar.zst")
        archive.writeText("old contents")
        val pack = AddonPack("sample", abcSha256, 3L, listOf("tool"))

        assertTrue(pack.copyBundledArchive(ByteArrayInputStream("abc".toByteArray()), archive))
        assertEquals("abc", archive.readText())
    }

    @Test fun corruptedBundledArchiveIsDeletedBeforeExtraction() {
        val archive = File(context.cacheDir, "sample-pack.tar.zst")
        val pack = AddonPack("sample", abcSha256, 3L, listOf("tool"))

        assertFalse(pack.copyBundledArchive(ByteArrayInputStream("bad".toByteArray()), archive))
        assertFalse(archive.exists())
    }

    @Test fun bundledArchiveWithWrongSizeIsRejectedEvenWhenTheDigestMatches() {
        val archive = File(context.cacheDir, "sample-pack.tar.zst")
        val pack = AddonPack("sample", abcSha256, 4L, listOf("tool"))

        assertFalse(pack.copyBundledArchive(ByteArrayInputStream("abc".toByteArray()), archive))
        assertFalse(archive.exists())
    }

    @Test fun interruptedBundledCopyClosesTheInputAndDeletesPartialBytes() {
        val archive = File(context.cacheDir, "sample-pack.tar.zst")
        val pack = AddonPack("sample", abcSha256, 3L, listOf("tool"))
        var closed = false
        val source = object : InputStream() {
            private var read = false
            override fun read(): Int {
                if (read) throw IOException("interrupted")
                read = true
                return 'a'.code
            }
            override fun close() { closed = true }
        }

        assertThrows(IOException::class.java) { pack.copyBundledArchive(source, archive) }
        assertTrue(closed)
        assertFalse(archive.exists())
    }
}
