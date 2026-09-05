package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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
                accountId = accountId(),
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
            assertEquals(accountId(), staged.parentFile?.parentFile?.name)
            assertEquals(root.canonicalFile, staged.parentFile?.parentFile?.parentFile?.canonicalFile)
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
                accountId = accountId(),
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
            assertTrue(root.resolve(accountId()).listFiles().orEmpty().isEmpty())
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
                accountId = accountId(),
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
                accountId = accountId(),
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
                accountId = accountId(),
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
                    accountId = accountId(),
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
            assertTrue(root.resolve(accountId()).listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop cache pruning removes expired detached copies`() {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-").toFile()
        try {
            val accountRoot = root.resolve(accountId()).apply { mkdir() }
            val old = accountRoot.resolve("old").apply { mkdir() }
            old.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val recent = accountRoot.resolve("recent").apply { mkdir() }
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
            val accountRoot = root.resolve(accountId()).apply { mkdir() }
            val recent = accountRoot.resolve("recent").apply { mkdir() }
            recent.resolve("payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            val now = 10L * 60L * 60L * 1000L
            recent.setLastModified(now)

            assertFailsWith<IllegalStateException> {
                pruneDesktopExternalFileCache(root, requiredBytes = 2L, nowMillis = now, maximumBytes = 4L)
            }

            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop cache pressure prunes operation copies across accounts to one global limit`() {
        val root = Files.createTempDirectory("nextcloud-desktop-global-handoff-").toFile()
        try {
            val older = root.resolve("a".repeat(64)).resolve("older").apply { mkdirs() }
            val newer = root.resolve("b".repeat(64)).resolve("newer").apply { mkdirs() }
            older.resolve("payload.bin").writeBytes(ByteArray(6))
            newer.resolve("payload.bin").writeBytes(ByteArray(6))
            older.setLastModified(1L)
            newer.setLastModified(2L)

            val available = pruneDesktopExternalFileCache(
                root = root,
                requiredBytes = 4L,
                nowMillis = 2L * 60L * 60L * 1000L,
                maximumBytes = 10L,
            )

            assertFalse(older.exists())
            assertTrue(newer.exists())
            assertEquals(4L, available)
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

    @Test
    fun `account cleanup removes only that accounts detached copies`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-account-handoff-").toFile()
        val removed = "a".repeat(64)
        val retained = "b".repeat(64)
        val freshLegacy = root.resolve("123e4567-e89b-12d3-a456-426614174000")
        try {
            val handoff = DesktopExternalFileHandoff(root, launchFile = { true })
            handoff.launch(removed, file(), ExternalFileHandoffAction.OpenWith, capability()) {
                NextcloudFileContent("removed".encodeToByteArray(), "application/pdf", "\"v1\"")
            }
            handoff.launch(retained, file(), ExternalFileHandoffAction.OpenWith, capability()) {
                NextcloudFileContent("retained".encodeToByteArray(), "application/pdf", "\"v1\"")
            }
            freshLegacy.mkdir()
            freshLegacy.resolve("payload.bin").writeText("unknown-account-legacy-copy")

            repeat(2) { handoff.removeAccount(removed) }

            assertFalse(root.resolve(removed).exists())
            assertFalse(freshLegacy.exists())
            assertEquals("retained", root.resolve(retained).walkTopDown().first(File::isFile).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `account and legacy cleanup unlink nested symlinks without deleting their targets`() {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-safe-delete-").toFile()
        val scopedTarget = Files.createTempDirectory("nextcloud-desktop-handoff-scoped-target-").toFile()
        val legacyTarget = Files.createTempDirectory("nextcloud-desktop-handoff-legacy-target-").toFile()
        try {
            val scoped = root.resolve(accountId()).apply { mkdir() }
            val legacy = root.resolve("123e4567-e89b-12d3-a456-426614174000").apply { mkdir() }
            scopedTarget.resolve("keep.txt").writeText("scoped-target")
            legacyTarget.resolve("keep.txt").writeText("legacy-target")
            val linksCreated = runCatching {
                Files.createSymbolicLink(scoped.resolve("linked").toPath(), scopedTarget.toPath())
                Files.createSymbolicLink(legacy.resolve("linked").toPath(), legacyTarget.toPath())
            }.isSuccess
            if (!linksCreated) return

            DesktopExternalFileHandoff(root).removeAccount(accountId())

            assertFalse(scoped.exists())
            assertFalse(legacy.exists())
            assertEquals("scoped-target", scopedTarget.resolve("keep.txt").readText())
            assertEquals("legacy-target", legacyTarget.resolve("keep.txt").readText())
        } finally {
            deleteDesktopExternalFileTree(root.toPath())
            scopedTarget.deleteRecursively()
            legacyTarget.deleteRecursively()
        }
    }

    @Test
    fun `staging rejects an account cache directory replaced by a symlink`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-account-link-").toFile()
        val target = Files.createTempDirectory("nextcloud-desktop-handoff-account-target-").toFile()
        try {
            target.resolve("keep.txt").writeText("outside")
            val linked = runCatching {
                Files.createSymbolicLink(root.resolve(accountId()).toPath(), target.toPath())
            }.isSuccess
            if (!linked) return@runBlocking

            assertFailsWith<IllegalStateException> {
                DesktopExternalFileHandoff(root).launch(
                    accountId(), file(), ExternalFileHandoffAction.OpenWith, capability(),
                ) { error("download must not start") }
            }

            assertEquals("outside", target.resolve("keep.txt").readText())
        } finally {
            deleteDesktopExternalFileTree(root.toPath())
            target.deleteRecursively()
        }
    }

    @Test
    fun `legacy cleanup expires old unscoped copies without deleting account directories`() {
        val root = Files.createTempDirectory("nextcloud-desktop-legacy-handoff-").toFile()
        val expired = root.resolve("123e4567-e89b-12d3-a456-426614174000").apply { mkdir() }
        val recent = root.resolve("123e4567-e89b-12d3-a456-426614174001").apply { mkdir() }
        val scoped = root.resolve(accountId()).apply { mkdir() }
        val now = 2L * DESKTOP_EXTERNAL_FILE_TEST_DAY_MILLIS
        try {
            expired.resolve("payload.bin").writeText("expired")
            recent.resolve("payload.bin").writeText("recent")
            scoped.resolve("payload.bin").writeText("scoped")
            expired.setLastModified(1L)
            recent.setLastModified(now)
            scoped.setLastModified(1L)

            pruneLegacyDesktopExternalFileCache(root, now)

            assertFalse(expired.exists())
            assertTrue(recent.isDirectory)
            assertTrue(scoped.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `account removal waits for in flight handoff then deletes its copy`() = runBlocking {
        val root = Files.createTempDirectory("nextcloud-desktop-handoff-removal-").toFile()
        val guard = DesktopAccountOperationGuard()
        val session = session()
        val scopedAccountId = desktopFileCacheAccountId(session)
        val downloadStarted = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        var removalFinished = false
        try {
            val handoff = DesktopExternalFileHandoff(root, launchFile = { true })
            val launch = async {
                guard.withExternalFileHandoffSession(session, { session }) {
                    handoff.launch(scopedAccountId, file(), ExternalFileHandoffAction.OpenWith, capability()) {
                        downloadStarted.complete(Unit)
                        finishDownload.await()
                        NextcloudFileContent("detached".encodeToByteArray(), "application/pdf", "\"v1\"")
                    }
                }
            }
            downloadStarted.await()
            val removal = async(start = CoroutineStart.UNDISPATCHED) {
                guard.serialize {
                    handoff.removeAccount(scopedAccountId)
                    removalFinished = true
                }
            }

            assertFalse(removalFinished)
            finishDownload.complete(Unit)
            assertIs<ExternalFileHandoffResult.Launched>(launch.await())
            removal.await()
            assertFalse(root.resolve(scopedAccountId).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun capability(vararg actions: ExternalFileHandoffAction) = ExternalFileHandoffCapability(
        supportedActions = actions.toSet().ifEmpty { setOf(ExternalFileHandoffAction.OpenWith) },
        maximumInMemoryFileBytes = MAX_IN_MEMORY_EXTERNAL_FILE_HANDOFF_BYTES,
    )

    private fun accountId() = "0123456789abcdef".repeat(4)

    private fun session() = NextcloudSession(
        serverUrl = "https://cloud.invalid",
        loginName = "ada",
        appPassword = "synthetic-secret",
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

private const val DESKTOP_EXTERNAL_FILE_TEST_DAY_MILLIS = 24L * 60L * 60L * 1000L
