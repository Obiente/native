package dev.obiente.nextcloudnative.app

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Native entries must classify exactly like Java NIO with NOFOLLOW_LINKS did before. */
class WindowsCloudLocalEntryTest {
    private val inspection = WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.Local)
    private val path = Path.of("synthetic-entry")

    private fun classify(attributes: Int, tag: Int = 0): Triple<Boolean, Boolean, Boolean> =
        windowsCloudLocalEntry(path, attributes, tag, inspection).let { Triple(it.directory, it.regularFile, it.symbolicLink) }

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
    fun `other reparse points keep Java NIO classification`() {
        // Cloud placeholder directories and junctions remain traversable directories.
        assertEquals(Triple(true, false, false), classify(FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_CLOUD))
        assertEquals(Triple(true, false, false), classify(FILE_ATTRIBUTE_DIRECTORY or FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_MOUNT_POINT))
        // A file reparse point is "other" in Java NIO, not a regular file.
        assertEquals(Triple(false, false, false), classify(FILE_ATTRIBUTE_REPARSE_POINT, IO_REPARSE_TAG_CLOUD))
        // A stale symlink tag without the reparse attribute never makes an entry a link.
        assertEquals(Triple(false, true, false), classify(FILE_ATTRIBUTE_ARCHIVE, IO_REPARSE_TAG_SYMLINK))
    }

    @Test
    fun `devices are not regular files`() {
        assertEquals(Triple(false, false, false), classify(FILE_ATTRIBUTE_DEVICE))
    }

    private companion object {
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10
        const val FILE_ATTRIBUTE_ARCHIVE = 0x20
        const val FILE_ATTRIBUTE_DEVICE = 0x40
        const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
        const val IO_REPARSE_TAG_SYMLINK = 0xA000000C.toInt()
        const val IO_REPARSE_TAG_MOUNT_POINT = 0xA0000003.toInt()
        const val IO_REPARSE_TAG_CLOUD = 0x9000001A.toInt()
    }
}
