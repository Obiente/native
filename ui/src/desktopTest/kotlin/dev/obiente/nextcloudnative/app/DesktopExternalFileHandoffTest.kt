package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopExternalFileHandoffTest {
    @Test
    fun `desktop open stages an exact read-only generation outside the live DAV object`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-").toFile()
        var launched: File? = null
        try {
            val handoff = DesktopExternalFileHandoff(root, launchFile = { file ->
                launched = file
                true
            })

            val result = handoff.launch(
                file = file(),
                action = ExternalFileHandoffAction.OpenWith,
                capability = capability(),
                download = {
                    NextcloudFileContent(
                        bytes = "detached copy".encodeToByteArray(),
                        mimeType = "application/pdf",
                        etag = "\"v1\"",
                    )
                },
            )

            assertIs<ExternalFileHandoffResult.Launched>(result)
            val staged = requireNotNull(launched)
            assertEquals("report.pdf", staged.name)
            assertEquals("detached copy", staged.readText())
            assertEquals(root.canonicalFile, staged.parentFile?.parentFile?.canonicalFile)
            assertFalse(staged.canWrite())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop share exports the staged copy instead of opening it`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-share-").toFile()
        var exported = ""
        var openCalls = 0
        try {
            val result = DesktopExternalFileHandoff(
                root = root,
                launchFile = {
                    openCalls += 1
                    true
                },
                exportFile = { staged ->
                    exported = staged.readText()
                    DesktopStagedFileExport.Exported
                },
            ).launch(
                file = file(),
                action = ExternalFileHandoffAction.Share,
                capability = capability(ExternalFileHandoffAction.Share),
                download = {
                    NextcloudFileContent(
                        bytes = "detached copy".encodeToByteArray(),
                        mimeType = "application/pdf",
                        etag = "\"v1\"",
                    )
                },
            )

            assertIs<ExternalFileHandoffResult.Launched>(result)
            assertEquals("detached copy", exported)
            assertEquals(0, openCalls)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `changed generation is rejected before desktop launch or staging`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-").toFile()
        var launchCalls = 0
        try {
            val result = DesktopExternalFileHandoff(root, launchFile = {
                launchCalls += 1
                true
            }).launch(
                file = file(),
                action = ExternalFileHandoffAction.OpenWith,
                capability = capability(),
                download = {
                    NextcloudFileContent(
                        bytes = byteArrayOf(1),
                        mimeType = "application/pdf",
                        etag = "\"newer\"",
                    )
                },
            )

            assertEquals(
                ExternalFileHandoffRejection.VersionChanged,
                assertIs<ExternalFileHandoffResult.Rejected>(result).reason,
            )
            assertEquals(0, launchCalls)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `deck attachment streams to a detached read-only copy without DAV identity`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-attachment-").toFile()
        var launched: File? = null
        try {
            val result = DesktopExternalFileHandoff(root, launchFile = { file ->
                launched = file
                true
            }).launchDetached(
                attachment = attachment(byteCount = 13L),
                action = ExternalFileHandoffAction.OpenWith,
                capability = capability(),
                download = { output, _ ->
                    val content = "detached copy".encodeToByteArray()
                    output.write(content)
                    DesktopDetachedDownload(content.size.toLong())
                },
            )

            assertIs<ExternalFileHandoffResult.Launched>(result)
            val staged = requireNotNull(launched)
            assertEquals("report.pdf", staged.name)
            assertEquals("detached copy", staged.readText())
            assertFalse(staged.canWrite())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `streamed deck attachment is not constrained by the in-memory threshold`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-attachment-").toFile()
        var launchCalls = 0
        try {
            val result = DesktopExternalFileHandoff(root, launchFile = {
                launchCalls += 1
                true
            }).launchDetached(
                attachment = attachment(byteCount = null),
                action = ExternalFileHandoffAction.OpenWith,
                capability = ExternalFileHandoffCapability(
                    supportedActions = setOf(ExternalFileHandoffAction.OpenWith),
                    maximumInMemoryFileBytes = 4L,
                ),
                download = { output, _ ->
                    output.write(byteArrayOf(1, 2, 3, 4, 5))
                    DesktopDetachedDownload(5L)
                },
            )

            assertIs<ExternalFileHandoffResult.Launched>(result)
            assertEquals(1, launchCalls)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `short streamed deck attachment is cleaned before launch`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-attachment-").toFile()
        var launchCalls = 0
        try {
            assertFailsWith<IllegalStateException> {
                DesktopExternalFileHandoff(root, launchFile = {
                    launchCalls += 1
                    true
                }).launchDetached(
                    attachment = attachment(byteCount = 5L),
                    action = ExternalFileHandoffAction.OpenWith,
                    capability = capability(),
                    download = { output, _ ->
                        output.write(byteArrayOf(1, 2, 3))
                        DesktopDetachedDownload(3L)
                    },
                )
            }

            assertEquals(0, launchCalls)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop cache pruning removes expired detached copies`() {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-").toFile()
        try {
            val old = root.resolve("old").apply { mkdir() }
            old.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val recent = root.resolve("recent").apply { mkdir() }
            recent.resolve("payload.bin").writeBytes(byteArrayOf(4, 5, 6))
            val now = 2L * 24L * 60L * 60L * 1000L
            old.setLastModified(1L)
            recent.setLastModified(now)

            pruneDesktopExternalFileCache(root, requiredBytes = 1L, nowMillis = now)

            assertFalse(old.exists())
            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop cache pressure preserves newly handed off files`() {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-").toFile()
        try {
            val recent = root.resolve("recent").apply { mkdir() }
            recent.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val now = 10L * 60L * 60L * 1000L
            recent.setLastModified(now)

            pruneDesktopExternalFileCache(root, requiredBytes = Long.MAX_VALUE, nowMillis = now)

            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `same-filesystem export moves the staged copy without requiring duplicate capacity`() {
        val root = Files.createTempDirectory("nextcloud-desktop-export-").toFile()
        try {
            val staged = root.resolve("staged.bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
                assertTrue(setWritable(false, false) || !canWrite())
            }
            val destination = root.resolve("exported.bin")

            assertEquals(DesktopStagedFileExport.Exported, publishDesktopStagedFile(staged, destination))

            assertFalse(staged.exists())
            assertEquals(listOf<Byte>(1, 2, 3, 4), destination.readBytes().toList())
            assertTrue(destination.canWrite())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun capability(vararg actions: ExternalFileHandoffAction) = ExternalFileHandoffCapability(
        supportedActions = actions.toSet().ifEmpty { setOf(ExternalFileHandoffAction.OpenWith) },
        maximumInMemoryFileBytes = MAX_IN_MEMORY_EXTERNAL_FILE_HANDOFF_BYTES,
    )

    private fun file() = NextcloudFile(
        path = "Documents/report.pdf",
        name = "report.pdf",
        isDirectory = false,
        mimeType = "application/pdf",
        size = 13,
        lastModified = null,
        fileId = 42,
        hasPreview = true,
        etag = "\"v1\"",
        permissions = "RGDNVW",
    )

    private fun attachment(byteCount: Long?): DeckAttachment = DeckAttachment(
        id = 7L,
        cardId = 11L,
        type = DeckAttachmentType.DeckFile,
        name = "report.pdf",
        mimeType = "application/pdf",
        byteCount = byteCount,
        createdBy = "user",
        createdAt = null,
        lastModified = null,
    )
}
