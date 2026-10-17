package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The commit protocol that follows a dirty placeholder upload. */
class WindowsCloudFilesWritebackTest {
    @Test
    fun `an unchanged upload records the new revision before it marks the file in sync`() = withFile { local ->
        val fixture = Fixture(local)
        fixture.writeback.writeBack(local, fixture.original)

        assertEquals(listOf<String?>(ORIGINAL), fixture.backend.revisions)
        assertEquals(listOf("revision:\"server-1\"", "in-sync"), fixture.events)
    }

    @Test
    fun `an edit during the upload stays dirty and uploads again against the new revision`() = withFile { local ->
        val fixture = Fixture(local)
        var edits = 1
        fixture.backend.duringUpload = { if (edits-- > 0) fixture.backend.editLocal(local) }

        fixture.writeback.writeBack(local, fixture.original)

        assertEquals(listOf<String?>(ORIGINAL, "\"server-1\""), fixture.backend.revisions)
        assertContentEquals(NEWER_EDIT + ByteArray(1), fixture.backend.content)
        assertEquals(listOf("revision:\"server-1\"", "revision:\"server-2\"", "in-sync"), fixture.events)
    }

    @Test
    fun `a file that keeps changing stays dirty after bounded uploads`() = withFile { local ->
        val fixture = Fixture(local)
        fixture.backend.duringUpload = { fixture.backend.editLocal(local) }

        assertFailsWith<WindowsCloudLocalChangedDuringWritebackException> {
            fixture.writeback.writeBack(local, fixture.original)
        }

        assertEquals(3, fixture.backend.revisions.size)
        assertTrue("in-sync" !in fixture.events)
        assertEquals(WindowsCloudPlaceholderState.Dirty, fixture.state())
    }

    @Test
    fun `a queued writeback of an already committed file uploads nothing`() = withFile { local ->
        val fixture = Fixture(local)
        fixture.writeback.writeBack(local, fixture.original)
        fixture.writeback.writeBack(local, fixture.placeholderIdentity())

        assertEquals(1, fixture.backend.revisions.size)
    }

    @Test
    fun `a commit that cannot open the file is retried without uploading again`() = withFile { local ->
        val fixture = Fixture(local)
        fixture.commitFailures = 1

        assertFailsWith<WindowsCloudFilesOperationException> { fixture.writeback.writeBack(local, fixture.original) }
        assertEquals(listOf("revision:\"server-1\""), fixture.events)

        fixture.writeback.writeBack(local, fixture.placeholderIdentity())

        assertEquals(1, fixture.backend.revisions.size)
        assertEquals(listOf("revision:\"server-1\"", "in-sync"), fixture.events)
    }

    @Test
    fun `a pending commit is dropped once the placeholder holds another revision`() = withFile { local ->
        val fixture = Fixture(local)
        fixture.commitFailures = 1
        assertFailsWith<WindowsCloudFilesOperationException> { fixture.writeback.writeBack(local, fixture.original) }

        // Another writer moved the placeholder on; the stale pending commit must not mark it in sync.
        val other = fixture.original.copy(remoteRevision = "\"elsewhere\"")
        fixture.backend.serverRevision = "\"elsewhere\""
        fixture.writeback.writeBack(local, other)

        assertEquals(listOf<String?>(ORIGINAL, "\"elsewhere\""), fixture.backend.revisions)
        assertEquals("in-sync", fixture.events.last())
    }

    @Test
    fun `a rejected upload changes neither the placeholder revision nor its sync state`() = withFile { local ->
        val fixture = Fixture(local)
        fixture.backend.serverRevision = "\"changed-remotely\""

        assertFailsWith<IOException> { fixture.writeback.writeBack(local, fixture.original) }

        assertEquals(emptyList(), fixture.events)
        assertContentEquals(LOCAL_EDIT, local.readBytes())
    }

    @Test
    fun `a corrupt placeholder update leaves the commit to root recovery`() = withFile { local ->
        val fixture = Fixture(local, recordRevision = false)

        fixture.writeback.writeBack(local, fixture.original)

        assertEquals(listOf("revision:\"server-1\""), fixture.events)
    }

    @Test
    fun `a directory identity is never written back`() = withFile { local ->
        val fixture = Fixture(local)
        assertFailsWith<IllegalArgumentException> {
            fixture.writeback.writeBack(local, fixture.original.copy(directory = true))
        }
        assertEquals(emptyList(), fixture.backend.revisions)
    }

    private fun withFile(block: (Path) -> Unit) {
        val root = createTempDirectory("windows-cloud-writeback-")
        try {
            block(root.resolve("edit.txt").apply { writeBytes(LOCAL_EDIT) })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(local: Path, recordRevision: Boolean = true) {
        val original = WindowsCloudFileIdentity("account-01", "edit.txt", ORIGINAL, LOCAL_EDIT.size.toLong(), false)
        val backend = RevisionedServer()
        val events = mutableListOf<String>()
        var commitFailures = 0
        private var placeholder = original
        private val fake = WindowsCloudFilesProviderTest.FakeApi().apply {
            seed(local, WindowsCloudPlaceholderState.Dirty, original)
        }
        private val api = object : WindowsCloudFilesApi by fake {
            override fun markInSync(path: Path) {
                events += "in-sync"
                fake.markInSync(path)
            }
            override fun markInSyncIfUnchanged(path: Path, expected: WindowsCloudLocalContentStamp): WindowsCloudInSyncCommit {
                if (commitFailures > 0) {
                    commitFailures -= 1
                    throw WindowsCloudFilesOperationException("open for commit", SHARING_VIOLATION)
                }
                return markInSyncWhenStampMatches(path, expected)
            }
        }
        val writeback = WindowsCloudFilesWriteback(api, backend, {}) { path, uploaded ->
            check(path == local)
            events += "revision:${uploaded.remoteRevision}"
            placeholder = uploaded
            recordRevision
        }

        fun placeholderIdentity() = placeholder
        fun state() = fake.placeholderState(placeholderPath)
        private val placeholderPath = local
    }

    private class RevisionedServer : WindowsCloudFilesBackend {
        override val accountId = "account-01"
        override val displayName = "Synthetic account"
        var serverRevision: String = ORIGINAL
        var content = ByteArray(0)
        val revisions = mutableListOf<String?>()
        var duringUpload: () -> Unit = {}
        private var generation = 0
        private var localEdits = 0

        /** Changes size and modification time, as a real editor saving the file would. */
        fun editLocal(local: Path) {
            localEdits += 1
            local.writeBytes(NEWER_EDIT + ByteArray(localEdits))
            Files.setLastModifiedTime(local, FileTime.fromMillis(Files.getLastModifiedTime(local).toMillis() + 2_000L * localEdits))
        }

        override fun upload(path: String, localFile: File, expectedRemoteRevision: String?): WindowsCloudFileIdentity {
            revisions += expectedRemoteRevision
            if (expectedRemoteRevision != serverRevision) throw IOException("Synthetic precondition failed")
            content = localFile.readBytes()
            duringUpload()
            generation += 1
            serverRevision = "\"server-$generation\""
            return WindowsCloudFileIdentity(accountId, path, serverRevision, content.size.toLong(), false)
        }

        override fun resolve(path: String): WindowsCloudFileIdentity? = error("Unexpected resolve")
        override fun list(path: String): List<WindowsCloudFileIdentity> = error("Unexpected list")
        override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle = error("Unexpected read")
        override fun createDirectory(path: String): WindowsCloudFileIdentity = error("Unexpected create")
        override fun delete(identity: WindowsCloudFileIdentity) = error("Unexpected delete")
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            error("Unexpected move")
    }

    private companion object {
        const val ORIGINAL = "\"etag-01\""
        const val SHARING_VIOLATION = 0x80070020.toInt()
        val LOCAL_EDIT = "synthetic local edit".encodeToByteArray()
        val NEWER_EDIT = "synthetic newer local edit".encodeToByteArray()
    }
}
