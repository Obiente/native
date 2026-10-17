package dev.obiente.nextcloudnative.app

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Startup writeback of placeholder files edited while nati.ve was not running.
 *
 * While the provider is connected, every file placeholder on a real sync root is a cloud reparse
 * point, which Java NIO reports as "other" rather than a regular file. These tests enumerate
 * placeholders the way the native scan reports them so recovery cannot depend on NIO file
 * classification.
 */
class WindowsCloudFilesPlaceholderRecoveryTest {
    @Test
    fun `startup writes back a dirty placeholder edited while the app was not running`() = withRoot { root ->
        val edited = root.resolve("edit.txt").apply { writeBytes(LOCAL_EDIT) }
        val untouched = root.resolve("untouched.txt").apply { writeBytes(REMOTE_ORIGINAL) }
        val backend = RevisionedBackend(
            "edit.txt" to REMOTE_ORIGINAL,
            "untouched.txt" to REMOTE_ORIGINAL,
        )
        val fake = WindowsCloudFilesProviderTest.FakeApi(expectedConversions = 1).apply {
            seed(edited, WindowsCloudPlaceholderState.Dirty, backend.identity("edit.txt"))
            seed(untouched, WindowsCloudPlaceholderState.InSync, backend.identity("untouched.txt"))
        }

        WindowsCloudFilesProvider(root, backend, fake.withPlaceholderEnumeration(edited, untouched)).use { provider ->
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            assertTrue(fake.awaitConversions())
            awaitWritebacksSettled(provider)

            assertEquals(listOf<String?>(ORIGINAL_REVISION), backend.uploadRevisions)
            assertContentEquals(LOCAL_EDIT, backend.content("edit.txt"))
            assertEquals(WindowsCloudPlaceholderState.InSync, fake.placeholderState(edited))
            assertEquals(backend.identity("edit.txt"), fake.decodedIdentity(edited))
            assertEquals(0, provider.summary().failedWritebackCount)
        }
    }

    @Test
    fun `startup keeps a dirty placeholder when the server copy changed while the app was not running`() =
        withRoot { root ->
            val edited = root.resolve("edit.txt").apply { writeBytes(LOCAL_EDIT) }
            val backend = RevisionedBackend("edit.txt" to REMOTE_ORIGINAL)
            val stale = backend.identity("edit.txt")
            backend.replaceRemotely("edit.txt", REMOTE_EDIT)
            val fake = WindowsCloudFilesProviderTest.FakeApi().apply {
                seed(edited, WindowsCloudPlaceholderState.Dirty, stale)
            }

            WindowsCloudFilesProvider(
                root,
                backend,
                fake.withPlaceholderEnumeration(edited),
                writebackRetryDelayMillis = { 0L },
            ).use { provider ->
                provider.start()
                provider.recoverAfterStartup(timeoutSeconds = 5)
                awaitBoundedRetriesExhausted(provider, backend)

                // Every attempt keeps the revision the edit was based on and is rejected.
                assertTrue(backend.uploadRevisions.all { it == ORIGINAL_REVISION })
                assertContentEquals(REMOTE_EDIT, backend.content("edit.txt"))
                assertContentEquals(LOCAL_EDIT, edited.readBytes())
                assertEquals(WindowsCloudPlaceholderState.Dirty, fake.placeholderState(edited))
                assertEquals(stale, fake.decodedIdentity(edited))
                assertFalse(edited in fake.updatedPaths)
                assertEquals(1, provider.summary().failedWritebackCount)
            }
        }

    @Test
    fun `startup never repeats an ambiguous writeback without its original revision`() = withRoot { root ->
        val edited = root.resolve("edit.txt").apply { writeBytes(LOCAL_EDIT) }
        val backend = RevisionedBackend("edit.txt" to REMOTE_ORIGINAL)
        val fake = WindowsCloudFilesProviderTest.FakeApi().apply {
            seed(edited, WindowsCloudPlaceholderState.Dirty, backend.identity("edit.txt"))
        }
        backend.loseNextUploadResponse = true

        WindowsCloudFilesProvider(
            root,
            backend,
            fake.withPlaceholderEnumeration(edited),
            writebackRetryDelayMillis = { 0L },
        ).use { provider ->
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            awaitBoundedRetriesExhausted(provider, backend)

            // The server committed once and lost the response. Retries stay guarded by the old
            // revision, so they cannot overwrite the committed copy or a later concurrent edit.
            assertEquals(1, backend.commits)
            assertTrue(backend.uploadRevisions.all { it == ORIGINAL_REVISION })
            assertContentEquals(LOCAL_EDIT, edited.readBytes())
            assertEquals(WindowsCloudPlaceholderState.Dirty, fake.placeholderState(edited))
            assertEquals(1, provider.summary().failedWritebackCount)
        }
    }

    @Test
    fun `a dirty placeholder whose writeback failed is written back on the next start`() = withRoot { root ->
        val edited = root.resolve("edit.txt").apply { writeBytes(LOCAL_EDIT) }
        val backend = RevisionedBackend("edit.txt" to REMOTE_ORIGINAL)
        val fake = WindowsCloudFilesProviderTest.FakeApi(expectedConversions = 1).apply {
            seed(edited, WindowsCloudPlaceholderState.Dirty, backend.identity("edit.txt"))
        }
        val api = fake.withPlaceholderEnumeration(edited)
        backend.unavailableUploads = Int.MAX_VALUE

        WindowsCloudFilesProvider(root, backend, api, writebackRetryDelayMillis = { 0L }).use { provider ->
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            awaitBoundedRetriesExhausted(provider, backend)
            assertEquals(WindowsCloudPlaceholderState.Dirty, fake.placeholderState(edited))
        }

        backend.unavailableUploads = 0
        WindowsCloudFilesProvider(root, backend, api).use { restarted ->
            restarted.start()
            restarted.recoverAfterStartup(timeoutSeconds = 5)
            assertTrue(fake.awaitConversions())
            awaitWritebacksSettled(restarted)

            assertEquals(1, backend.commits)
            assertTrue(backend.uploadRevisions.all { it == ORIGINAL_REVISION })
            assertContentEquals(LOCAL_EDIT, backend.content("edit.txt"))
            assertEquals(WindowsCloudPlaceholderState.InSync, fake.placeholderState(edited))
            assertEquals(0, restarted.summary().failedWritebackCount)
        }
    }

    @Test
    fun `startup does not write back a dirty entry behind a foreign reparse point`() = withRoot { root ->
        val placeholder = root.resolve("edit.txt").apply { writeBytes(LOCAL_EDIT) }
        val foreign = root.resolve("foreign.bin").apply { writeBytes(LOCAL_EDIT) }
        val backend = RevisionedBackend("edit.txt" to REMOTE_ORIGINAL, "foreign.bin" to REMOTE_ORIGINAL)
        val fake = WindowsCloudFilesProviderTest.FakeApi(expectedConversions = 1).apply {
            seed(placeholder, WindowsCloudPlaceholderState.Dirty, backend.identity("edit.txt"))
            seed(foreign, WindowsCloudPlaceholderState.Dirty, backend.identity("foreign.bin"))
        }

        WindowsCloudFilesProvider(
            root,
            backend,
            fake.withPlaceholderEnumeration(placeholder, foreign = setOf(foreign)),
        ).use { provider ->
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            assertTrue(fake.awaitConversions())
            awaitWritebacksSettled(provider)

            assertEquals(listOf("edit.txt"), backend.uploadedPaths)
            assertContentEquals(REMOTE_ORIGINAL, backend.content("foreign.bin"))
        }
    }

    @Test
    fun `native Windows startup writes back a placeholder edited while disconnected`() {
        assumeTrue(isWindowsDesktop())
        val root = createTempDirectory("cloud-native-dirty-recovery-")
        val backend = RevisionedBackend("draft.txt" to REMOTE_ORIGINAL)
        val draft = root.resolve("draft.txt")
        try {
            WindowsCloudFilesProvider(root, backend, nativeApi()).use { provider ->
                provider.start()
                provider.recoverAfterStartup(timeoutSeconds = 5)
                // Hydrate while connected, so the later edit needs no provider callback.
                assertContentEquals(REMOTE_ORIGINAL, Files.readAllBytes(draft))
            }
            // While disconnected this process sees the placeholder disguised as a plain file.
            FileChannel.open(draft, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(LOCAL_EDIT), 0L)
            }

            val api = nativeApi()
            val provider = WindowsCloudFilesProvider(root, backend, api, writebackRetryDelayMillis = { 0L })
            try {
                provider.start()
                provider.recoverAfterStartup(timeoutSeconds = 5)
                // The connected provider sees the cloud reparse point that Java NIO calls "other".
                val entry = api.localEntries(root).single { it.path == draft }
                assertTrue(entry.file)
                assertTrue(isWindowsCloudFilesReparseTag(requireNotNull(entry.inspection.reparseTag)))
                awaitCondition { api.placeholderState(draft) == WindowsCloudPlaceholderState.InSync }
                awaitWritebacksSettled(provider)

                assertEquals(listOf<String?>(ORIGINAL_REVISION), backend.uploadRevisions)
                assertContentEquals(LOCAL_EDIT, backend.content("draft.txt"))
                assertContentEquals(LOCAL_EDIT, Files.readAllBytes(draft))
                assertEquals(backend.identity("draft.txt"), api.placeholderIdentity(draft)?.let(WindowsCloudFileIdentityCodec::decode))
                assertEquals(0, provider.summary().failedWritebackCount)
            } finally {
                try { provider.removeSyncRoot() } finally { provider.close() }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun nativeApi() =
        JnaWindowsCloudFilesApi(shellRegistrar = PackagedWindowsCloudShellRegistrar(launcherPath = null))

    private fun withRoot(block: (Path) -> Unit) {
        val root = createTempDirectory("windows-cloud-placeholder-recovery-")
        try {
            block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitWritebacksSettled(provider: WindowsCloudFilesProvider) {
        awaitCondition { provider.summary().pendingWritebackCount == 0 }
    }

    private fun awaitBoundedRetriesExhausted(provider: WindowsCloudFilesProvider, backend: RevisionedBackend) {
        awaitCondition {
            backend.uploadRevisions.size >= MAX_WRITEBACK_ATTEMPTS && provider.summary().failedWritebackCount == 1
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for Windows Cloud Files writeback." }
            Thread.sleep(5)
        }
    }

    /** Reports seeded file placeholders with a cloud reparse tag, as the native enumeration does. */
    private fun WindowsCloudFilesProviderTest.FakeApi.withPlaceholderEnumeration(
        vararg placeholders: Path,
        foreign: Set<Path> = emptySet(),
    ): WindowsCloudFilesApi {
        val fake = this
        return object : WindowsCloudFilesApi by fake {
            override fun localEntries(directory: Path): List<WindowsCloudLocalEntry> =
                Files.list(directory).use { it.toList() }.map { child ->
                    val inspection = fake.inspectPlaceholder(child)
                    when {
                        Files.isDirectory(child) ->
                            windowsCloudLocalEntry(child, FILE_ATTRIBUTE_DIRECTORY, 0, inspection)
                        child in foreign -> windowsCloudLocalEntry(
                            child,
                            FILE_ATTRIBUTE_ARCHIVE or FILE_ATTRIBUTE_REPARSE_POINT,
                            IO_REPARSE_TAG_DEDUP,
                            inspection,
                        )
                        child in placeholders -> windowsCloudLocalEntry(
                            child,
                            FILE_ATTRIBUTE_ARCHIVE or FILE_ATTRIBUTE_REPARSE_POINT,
                            IO_REPARSE_TAG_CLOUD_3,
                            inspection,
                        )
                        else -> windowsCloudLocalEntry(child, FILE_ATTRIBUTE_ARCHIVE, 0, inspection)
                    }
                }.also { entries ->
                    // Guard the fixture itself: NIO would have skipped every seeded placeholder.
                    entries.filter { it.path in placeholders }.forEach { check(it.file) }
                }
        }
    }

    /** A synthetic server that enforces If-Match revisions like WebDAV PUT. */
    internal class RevisionedBackend(vararg files: Pair<String, ByteArray>) : WindowsCloudFilesBackend {
        override val accountId = "account-01"
        override val displayName = "Synthetic account"
        private val remote = files.associate { (path, bytes) -> path to (ORIGINAL_REVISION to bytes) }.toMutableMap()
        val uploadRevisions = CopyOnWriteArrayList<String?>()
        val uploadedPaths = CopyOnWriteArrayList<String>()
        @Volatile var unavailableUploads = 0
        @Volatile var loseNextUploadResponse = false
        @Volatile var duringUpload: () -> Unit = {}
        @Volatile var commits = 0
            private set
        private var generation = 0

        fun identity(path: String): WindowsCloudFileIdentity = synchronized(this) {
            val (revision, bytes) = remote.getValue(path)
            WindowsCloudFileIdentity(accountId, path, revision, bytes.size.toLong(), false)
        }

        fun content(path: String): ByteArray = synchronized(this) { remote.getValue(path).second.copyOf() }

        fun replaceRemotely(path: String, bytes: ByteArray) = synchronized(this) {
            generation += 1
            remote[path] = "\"remote-$generation\"" to bytes.copyOf()
        }

        override fun resolve(path: String): WindowsCloudFileIdentity? =
            synchronized(this) { if (path in remote) identity(path) else null }

        override fun list(path: String): List<WindowsCloudFileIdentity> = synchronized(this) {
            remote.keys.filter { it.substringBeforeLast('/', "") == path }.map(::identity)
        }

        override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle {
            val bytes = synchronized(this) {
                val (revision, bytes) = remote.getValue(identity.path)
                check(revision == identity.remoteRevision) { "Synthetic read of a stale revision." }
                bytes.copyOf()
            }
            return object : WindowsCloudFileReadHandle {
                override val size = bytes.size.toLong()
                override fun read(offset: Long, length: Int): ByteArray =
                    bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                override fun close() = Unit
            }
        }

        override fun upload(
            path: String,
            localFile: File,
            expectedRemoteRevision: String?,
        ): WindowsCloudFileIdentity = synchronized(this) {
            uploadRevisions += expectedRemoteRevision
            if (unavailableUploads > 0) {
                unavailableUploads -= 1
                throw IOException("Synthetic server unavailable")
            }
            if (remote[path]?.first != expectedRemoteRevision) {
                throw IOException("Synthetic precondition failed")
            }
            generation += 1
            commits += 1
            uploadedPaths += path
            remote[path] = "\"server-$generation\"" to localFile.readBytes()
            duringUpload()
            if (loseNextUploadResponse) {
                loseNextUploadResponse = false
                throw IOException("Synthetic lost upload response")
            }
            identity(path)
        }

        override fun createDirectory(path: String): WindowsCloudFileIdentity = error("Unexpected create")
        override fun delete(identity: WindowsCloudFileIdentity) = error("Unexpected delete")
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            error("Unexpected move")
    }

    private companion object {
        const val ORIGINAL_REVISION = "\"etag-01\""
        const val MAX_WRITEBACK_ATTEMPTS = 5
        const val FILE_ATTRIBUTE_DIRECTORY = 0x10
        const val FILE_ATTRIBUTE_ARCHIVE = 0x20
        const val FILE_ATTRIBUTE_REPARSE_POINT = 0x400
        const val IO_REPARSE_TAG_CLOUD_3 = 0x9000301A.toInt()
        const val IO_REPARSE_TAG_DEDUP = 0x80000013.toInt()
        val LOCAL_EDIT = "synthetic local edit".encodeToByteArray()
        val REMOTE_ORIGINAL = "synthetic original".encodeToByteArray()
        val REMOTE_EDIT = "synthetic remote edit".encodeToByteArray()
    }
}
