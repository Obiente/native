package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Native entries classify links and directories like Java NIO with NOFOLLOW_LINKS, and also treat
 * Cloud Files file placeholders as files, which NIO reports as "other".
 */
class WindowsCloudLocalEntryTest {
    private val inspection = WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.Local)
    private val path = Path.of("synthetic-entry")

    private fun classify(attributes: Int, tag: Int = 0): Triple<Boolean, Boolean, Boolean> =
        windowsCloudLocalEntry(path, attributes, tag, inspection).let { Triple(it.directory, it.file, it.symbolicLink) }

    @Test
    fun `plain files and directories`() {
        assertEquals(Triple(false, true, false), classify(FILE_ATTRIBUTE_ARCHIVE))
        assertEquals(Triple(true, false, false), classify(FILE_ATTRIBUTE_DIRECTORY))
    }

    @Test
    fun `symbolic links are neither directories nor regular files`() {
        assertEquals(Triple(false, false, true), classify(FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_SYMLINK))
        assertEquals(
            Triple(false, false, true),
            classify(FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_SYMLINK),
        )
    }

    @Test
    fun `directory reparse points keep Java NIO classification`() {
        // Cloud placeholder directories and junctions remain traversable directories.
        assertEquals(Triple(true, false, false), classify(FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_CLOUD))
        assertEquals(Triple(true, false, false), classify(FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_MOUNT_POINT))
        // A stale symlink tag without the reparse attribute never makes an entry a link.
        assertEquals(Triple(false, true, false), classify(FILE_ATTRIBUTE_ARCHIVE, IO_REPARSE_TAG_SYMLINK))
    }

    @Test
    fun `Cloud Files file placeholders are files for every cloud tag variant`() {
        // Java NIO calls these "other"; a real sync root reports every file placeholder this way.
        (0x0..0xF).forEach { variant ->
            val tag = IO_REPARSE_TAG_CLOUD or (variant shl 12)
            assertTrue(isWindowsCloudFilesReparseTag(tag))
            assertEquals(Triple(false, true, false), classify(FILE_ATTRIBUTE_ARCHIVE or FILE_ATTRIBUTE_REPARSE_POINT, tag))
        }
    }

    @Test
    fun `foreign file reparse points are not files`() {
        listOf(IO_REPARSE_TAG_MOUNT_POINT, IO_REPARSE_TAG_DEDUP, IO_REPARSE_TAG_APPEXECLINK, IO_REPARSE_TAG_CLOUD or 0x1).forEach { tag ->
            assertFalse(isWindowsCloudFilesReparseTag(tag))
            assertEquals(Triple(false, false, false), classify(FILE_ATTRIBUTE_ARCHIVE or FILE_ATTRIBUTE_REPARSE_POINT, tag))
        }
    }

    @Test
    fun `devices are not regular files`() {
        assertEquals(Triple(false, false, false), classify(FILE_ATTRIBUTE_DEVICE))
        assertEquals(Triple(false, false, false), classify(FILE_ATTRIBUTE_DEVICE or FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_CLOUD))
    }

    private companion object {
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10
        const val FILE_ATTRIBUTE_ARCHIVE = 0x20
        const val FILE_ATTRIBUTE_DEVICE = 0x40
        const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
        const val IO_REPARSE_TAG_SYMLINK = 0xA000000C.toInt()
        const val IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003.toInt()
        const val IO_REPARSE_TAG_CLOUD = 0x9000001A.toInt()
        const val IO_REPARSE_TAG_DEDUP = 0x80000013.toInt()
        const val IO_REPARSE_TAG_APPEXECLINK = 0x8000001B.toInt()
    }
}
