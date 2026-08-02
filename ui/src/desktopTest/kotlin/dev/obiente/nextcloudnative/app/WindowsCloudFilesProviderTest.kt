package dev.obiente.nextcloudnative.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsCloudFilesProviderTest {
    @Test
    fun `native provider creates a readable directory placeholder on Windows`() {
        if (!isWindowsDesktop()) return
        val root = createTempDirectory("windows-cloud-native-")
        val directory = WindowsCloudFileIdentity(
            accountId = "account-01",
            path = "Apps",
            remoteRevision = "\"directory-etag\"",
            size = 0L,
            directory = true,
        )
        val childDirectory = directory.copy(
            path = "Apps/Calendar",
            remoteRevision = "\"child-directory-etag\"",
        )
        val childFile = directory.copy(
            path = "Apps/readme.txt",
            remoteRevision = "\"child-file-etag\"",
            size = 5L,
            directory = false,
        )
        val backend = FakeBackend(
            ByteArray(5),
            listed = listOf(directory, childDirectory, childFile),
        )
        val api = JnaWindowsCloudFilesApi()
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
        )

        try {
            provider.start()
            val expectedChildren = setOf("Calendar", "readme.txt")
            val names = awaitExternalDirectoryEntries(root.resolve("Apps"), expectedChildren)
            assertTrue(
                names.containsAll(expectedChildren),
                "Expected Cloud Files children in directory entries: $names; " +
                    "backend listings=${backend.listedPaths}; ${api.diagnostics()}",
            )
            val childNames = runWindowsCommand("dir", "/b", root.resolve("Apps/Calendar").toString())
            assertEquals(0, childNames.exitCode, childNames.output.toString(Charsets.UTF_8))
            val hydrated = runWindowsCommand("type", root.resolve("Apps/readme.txt").toString())
            val hydrationDiagnostics = buildString {
                append(hydrated.output.toString(Charsets.UTF_8))
                append("; backend listings=")
                append(backend.listedPaths)
                append("; ")
                append(api.diagnostics())
            }
            assertEquals(0, hydrated.exitCode, hydrationDiagnostics)
            assertContentEquals(ByteArray(5), hydrated.output, hydrationDiagnostics)
        } finally {
            runCatching { provider.removeSyncRoot() }
            root.toFile().deleteRecursively()
        }
    }

    private fun awaitExternalDirectoryEntries(directory: Path, expected: Set<String>): Set<String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var names: Set<String>
        do {
            val result = runWindowsCommand("dir", "/b", directory.toString())
            names = if (result.exitCode == 0) {
                result.output.toString(Charsets.UTF_8).lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toCollection(linkedSetOf())
            } else {
                emptySet()
            }
            if (names.containsAll(expected)) return names
            Thread.sleep(50)
        } while (System.nanoTime() < deadline)
        return names
    }

    private fun runWindowsCommand(vararg arguments: String): WindowsCommandResult {
        val process = ProcessBuilder(listOf("cmd.exe", "/d", "/c") + arguments)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "The Windows Cloud Files probe did not stop." }
            error("The Windows Cloud Files probe timed out.")
        }
        return WindowsCommandResult(process.exitValue(), process.inputStream.readBytes())
    }

    private data class WindowsCommandResult(
        val exitCode: Int,
        val output: ByteArray,
    )

    @Test
    fun accountRemovalDisconnectsAndUnregistersTheSyncRoot() {
        val root = createTempDirectory("windows-cloud-remove")
        val api = FakeApi()
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0)),
            api = api,
        )

        provider.start()
        provider.removeSyncRoot()

        assertEquals(root, api.unregisteredRoot)
        assertTrue(api.closed)
    }

    @Test
    fun callbackChannelStartsBeforeInitialPlaceholderPopulation() {
        val root = createTempDirectory("windows-cloud-start-order")
        val api = FakeApi()
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(
                source = ByteArray(0),
                listed = listOf(
                    WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true),
                ),
            ),
            api = api,
        )

        try {
            provider.start()
            assertEquals(listOf("register", "connect", "create"), api.lifecycleEvents.take(3))
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedSyncRootRemovalKeepsTheNativeApiAvailableForRetry() {
        val root = createTempDirectory("windows-cloud-remove-retry")
        val api = FakeApi().apply { unregisterFailure = IllegalStateException("in use") }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0)),
            api = api,
        )
        provider.start()

        assertFailsWith<IllegalStateException> { provider.removeSyncRoot() }
        assertFalse(api.closed)

        api.unregisterFailure = null
        provider.removeSyncRoot()
        assertEquals(root, api.unregisteredRoot)
        assertTrue(api.closed)
    }

    @Test
    fun failedDisconnectKeepsTheConnectionAvailableForRemovalRetry() {
        val root = createTempDirectory("windows-cloud-disconnect-retry")
        val api = FakeApi().apply { disconnectFailure = IllegalStateException("in use") }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0)),
            api = api,
        )
        provider.start()

        assertFailsWith<IllegalStateException> { provider.removeSyncRoot() }
        assertEquals(listOf(1L), api.disconnectAttempts)
        assertEquals(null, api.unregisteredRoot)
        assertFalse(api.closed)

        api.disconnectFailure = null
        provider.removeSyncRoot()
        assertEquals(listOf(1L, 1L), api.disconnectAttempts)
        assertEquals(root, api.unregisteredRoot)
        assertTrue(api.closed)
    }

    @Test
    fun `native layouts match 64 bit cfapi structures`() {
        assertEquals(
            WindowsCloudNativeLayoutSizes(
                registration = 72,
                policies = 24,
                fileSystemMetadata = 48,
                placeholder = 88,
                callbackInfo = 152,
            ),
            windowsCloudNativeLayoutSizes(),
        )
    }

    @Test
    fun `new placeholders carry complete Windows filesystem metadata`() {
        val directory = WindowsCloudPlaceholder(
            name = "Apps",
            identity = byteArrayOf(1),
            size = 0L,
            directory = true,
            lastModifiedEpochMillis = 0L,
        ).windowsMetadata()
        val file = WindowsCloudPlaceholder(
            name = "readme.txt",
            identity = byteArrayOf(2),
            size = 5L,
            directory = false,
            lastModifiedEpochMillis = 0L,
        ).windowsMetadata()

        assertEquals(116_444_736_000_000_000L, windowsFileTime(0L))
        assertEquals(directory.creationTime, directory.lastAccessTime)
        assertEquals(directory.creationTime, directory.lastWriteTime)
        assertEquals(directory.creationTime, directory.changeTime)
        assertTrue(directory.creationTime > 0L)
        assertEquals(0x10, directory.fileAttributes)
        assertEquals(0L, directory.fileSize)
        assertEquals(0x20, file.fileAttributes)
        assertEquals(5L, file.fileSize)
    }

    @Test
    fun `placeholder identities round trip and reject tampering`() {
        val identity = fixtureIdentity(size = 9_217L).copy(lastModifiedEpochMillis = 1_785_587_696_000L)
        val encoded = WindowsCloudFileIdentityCodec.encode(identity)

        assertEquals(identity, WindowsCloudFileIdentityCodec.decode(encoded))
        assertTrue(encoded.size <= 4_096)

        val tampered = encoded.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertFailsWith<IllegalArgumentException> { WindowsCloudFileIdentityCodec.decode(tampered) }
    }

    @Test
    fun `legacy placeholder identities remain readable during migration`() {
        val decoded = WindowsCloudFileIdentityCodec.decode(legacyWindowsCloudIdentity())

        assertEquals("account-01", decoded.accountId)
        assertEquals("Apps/readme.txt", decoded.path)
        assertEquals(null, decoded.lastModifiedEpochMillis)
    }

    @Test
    fun `callback paths must stay in the sync root and match their identity`() {
        val root = createTempDirectory("windows-cloud-callback-path-")
        val expected = root.resolve("Photos/example.raf")

        requireWindowsCloudCallbackPath(root, expected.toString(), "Photos/example.raf")
        assertFailsWith<IllegalArgumentException> {
            requireWindowsCloudCallbackPath(root, root.resolve("Photos/other.raf").toString(), "Photos/example.raf")
        }
        assertFailsWith<IllegalArgumentException> {
            val outside = requireNotNull(root.parent).resolve("outside.raf")
            requireWindowsCloudCallbackPath(root, outside.toString(), "Photos/example.raf")
        }
    }

    @Test
    fun `callback paths are rooted on the reported Windows volume`() {
        assertEquals(
            "D:\\Users\\runner\\Nextcloud Native\\Apps",
            windowsCloudAbsoluteCallbackPath("D:", "\\Users\\runner\\Nextcloud Native\\Apps"),
        )
        assertEquals(
            "C:\\Users\\runner\\Nextcloud Native\\Apps",
            windowsCloudAbsoluteCallbackPath("D:", "C:\\Users\\runner\\Nextcloud Native\\Apps"),
        )
        assertFailsWith<IllegalArgumentException> {
            windowsCloudAbsoluteCallbackPath("", "\\Users\\runner\\Nextcloud Native\\Apps")
        }
    }

    @Test
    fun `patterned population still transfers the complete directory`() {
        val root = createTempDirectory("windows-cloud-pattern-")
        val directory = WindowsCloudFileIdentity("account-01", "Apps", "\"directory\"", 0L, true)
        val text = WindowsCloudFileIdentity("account-01", "Apps/readme.txt", "\"text\"", 5L, false)
        val image = WindowsCloudFileIdentity("account-01", "Apps/photo.jpg", "\"image\"", 9L, false)
        val api = FakeApi(expectedPlaceholderFetches = 1)
        val provider = WindowsCloudFilesProvider(
            root,
            FakeBackend(ByteArray(0), listed = listOf(text, image)),
            api,
        )

        provider.fetchPlaceholders(callbackInfo(root, directory), "*.txt")

        assertTrue(api.awaitPlaceholderFetches())
        assertEquals(setOf("readme.txt", "photo.jpg"), api.completedPlaceholders.map { it.name }.toSet())
        provider.close()
    }

    @Test
    fun `hydration planning aligns random reads and ends exactly at eof`() {
        val ranges = planWindowsCloudHydration(
            requiredOffset = 4_321L,
            requiredLength = 20_000L,
            fileSize = 19_111L,
            maximumChunkBytes = 8_192,
        )

        assertEquals(4_096L, ranges.first().offset)
        assertEquals(19_111L, ranges.last().offset + ranges.last().length)
        assertTrue(ranges.dropLast(1).all { it.length % 4_096 == 0 })
        assertTrue(ranges.all { it.offset % 4_096L == 0L })
        val interior = planWindowsCloudHydration(5_001L, 1L, 30_000L)
        assertEquals(8_192L, interior.single().offset + interior.single().length)
    }

    @Test
    fun `fetch callback transfers exact generation in aligned chunks`() {
        val root = createTempDirectory("windows-cloud-provider-")
        val bytes = ByteArray(12_345) { index -> (index % 251).toByte() }
        val backend = FakeBackend(bytes)
        val api = FakeApi(expectedTransfers = 1)
        val provider = WindowsCloudFilesProvider(root, backend, api)
        val identity = fixtureIdentity(size = bytes.size.toLong())
        val info = callbackInfo(root, identity)

        provider.fetchData(info, requiredOffset = 4_500L, requiredLength = 7_845L)

        assertTrue(api.awaitTransfers())
        assertEquals(listOf(4_096L), api.transfers.map { it.first })
        assertContentEquals(bytes.copyOfRange(4_096, bytes.size), api.transfers.flatMap { it.second.asIterable() }.toByteArray())
        provider.close()
    }

    @Test
    fun `new ordinary local file uploads before conversion to placeholder`() {
        val root = createTempDirectory("windows-cloud-local-")
        val local = root.resolve("Notes/new.txt")
        local.parent.toFile().mkdirs()
        local.writeBytes("offline edit".encodeToByteArray())
        val backend = FakeBackend("remote".encodeToByteArray())
        val api = FakeApi(expectedConversions = 1)
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.localEntryChanged(local)

        assertTrue(api.awaitConversions())
        assertEquals("Notes/new.txt", backend.lastUploadedPath)
        assertEquals(WindowsCloudPlaceholderState.InSync, api.placeholderState(local))
        provider.close()
    }

    @Test
    fun `new populated local directory uploads every descendant parent first`() {
        val root = createTempDirectory("windows-cloud-local-tree-")
        val directory = root.resolve("Projects")
        val nested = directory.resolve("Launch")
        nested.toFile().mkdirs()
        nested.resolve("brief.txt").writeBytes("ready".encodeToByteArray())
        val backend = FakeBackend("remote".encodeToByteArray())
        val api = FakeApi(expectedConversions = 3)
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.localEntryChanged(directory)

        assertTrue(api.awaitConversions())
        assertEquals(
            listOf("mkdir:Projects", "mkdir:Projects/Launch", "upload:Projects/Launch/brief.txt"),
            backend.operations,
        )
        assertEquals(WindowsCloudPlaceholderState.InSync, api.placeholderState(nested.resolve("brief.txt")))
        provider.close()
    }

    @Test
    fun `ambiguous local create reconciles exact remote bytes before placeholder conversion`() {
        val root = createTempDirectory("windows-cloud-ambiguous-create-")
        val local = root.resolve("Notes/recovered.txt")
        local.parent.toFile().mkdirs()
        local.writeBytes("saved once".encodeToByteArray())
        val backend = FakeBackend(
            source = "remote".encodeToByteArray(),
            expectedUploads = 1,
            failAfterUpload = true,
        )
        val api = FakeApi(expectedConversions = 1)
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.localEntryChanged(local)

        assertTrue(backend.awaitUploads())
        assertTrue(api.awaitConversions())
        assertEquals(WindowsCloudPlaceholderState.InSync, api.placeholderState(local))
        assertEquals("Notes/recovered.txt", backend.resolve("Notes/recovered.txt")?.path)
        provider.close()
    }

    @Test
    fun `startup invalidates hydrated bytes when the remote generation changed`() {
        val root = createTempDirectory("windows-cloud-refresh-")
        val local = root.resolve("example.raf")
        local.writeBytes("old bytes".encodeToByteArray())
        val old = fixtureIdentity(size = local.toFile().length()).copy(path = "example.raf")
        val fresh = old.copy(remoteRevision = "\"etag-02\"")
        val backend = FakeBackend("fresh".encodeToByteArray(), listed = listOf(fresh))
        val api = FakeApi().apply { seed(local, WindowsCloudPlaceholderState.InSync, old) }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.start()

        assertEquals(listOf(local), api.invalidatedUpdates)
        provider.close()
    }

    @Test
    fun `recovery uploads against the dirty placeholder revision`() {
        val root = createTempDirectory("windows-cloud-recovery-")
        val local = root.resolve("edit.txt")
        local.writeBytes("local edit".encodeToByteArray())
        val old = WindowsCloudFileIdentity("account-01", "edit.txt", "\"etag-01\"", local.toFile().length(), false)
        val backend = FakeBackend(
            "fresh".encodeToByteArray(),
            listed = listOf(old),
            expectedUploads = 1,
            blockFirstUpload = true,
        )
        val api = FakeApi(expectedIdentityReads = 4).apply {
            seed(local, WindowsCloudPlaceholderState.Dirty, old)
        }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.start()
        assertTrue(backend.awaitFirstUploadStarted())

        val migrationFailure = AtomicReference<Throwable?>()
        val migration = Thread {
            runCatching { provider.recoverBeforeRootMigration(timeoutSeconds = 5L) }
                .onFailure(migrationFailure::set)
        }
        migration.start()

        try {
            assertTrue(api.awaitIdentityReads())
        } finally {
            backend.releaseFirstUpload()
        }
        migration.join(TimeUnit.SECONDS.toMillis(5L))
        assertFalse(migration.isAlive)
        migrationFailure.get()?.let { throw it }
        assertTrue(backend.awaitUploads())
        assertEquals("\"etag-01\"", backend.lastExpectedRemoteRevision)
        assertEquals(listOf<String?>("\"etag-01\""), backend.uploadExpectedRevisions)
        assertEquals(0, provider.summary().pendingWritebackCount)
        provider.close()
    }

    @Test
    fun `local placeholder inventory includes hydrated files absent from bounded remote traversal`() {
        val root = createTempDirectory("windows-cloud-local-inventory-")
        val local = root.resolve("Archive/cached.raf")
        local.parent.toFile().mkdirs()
        local.writeBytes("hydrated bytes".encodeToByteArray())
        val identity = WindowsCloudFileIdentity(
            "account-01",
            "Archive/cached.raf",
            "\"etag-01\"",
            local.toFile().length(),
            false,
        )
        val backend = FakeBackend("remote".encodeToByteArray())
        val api = FakeApi(expectedIdentityReads = 1).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
        }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.start()

        assertTrue(api.awaitIdentityReads())
        val inventoryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (provider.summary().cachedBytes != local.toFile().length() && System.nanoTime() < inventoryDeadline) {
            Thread.yield()
        }
        assertEquals(local.toFile().length(), provider.summary().cachedBytes)
        assertEquals(1, provider.summary().hydratedFileCount)
        provider.close()
    }

    @Test
    fun `folder rename rebinds every clean descendant identity`() {
        val root = createTempDirectory("windows-cloud-rename-")
        val destination = root.resolve("Projects/New")
        destination.toFile().mkdirs()
        val child = destination.resolve("brief.txt")
        child.writeBytes("local edit".encodeToByteArray())
        val directoryIdentity = WindowsCloudFileIdentity("account-01", "Projects/Old", "\"dir-v1\"", 0L, true)
        val childIdentity = WindowsCloudFileIdentity(
            "account-01",
            "Projects/Old/brief.txt",
            "\"file-v1\"",
            child.toFile().length(),
            false,
        )
        val backend = FakeBackend("remote".encodeToByteArray())
        val api = FakeApi(expectedRenames = 1).apply {
            seed(destination, WindowsCloudPlaceholderState.InSync, directoryIdentity)
            seed(child, WindowsCloudPlaceholderState.InSync, childIdentity)
        }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.renameRequested(callbackInfo(root, directoryIdentity), destination.toString())

        assertTrue(api.awaitRenames())
        assertTrue(api.lastRenameAccepted)
        assertEquals("Projects/New/brief.txt", api.decodedIdentity(child)?.path)
        assertEquals(WindowsCloudPlaceholderState.InSync, api.placeholderState(child))
        provider.close()
    }

    @Test
    fun `rename rejects a dirty placeholder until writeback completes`() {
        val root = createTempDirectory("windows-cloud-dirty-rename-")
        val destination = root.resolve("renamed.txt")
        destination.writeBytes("local edit".encodeToByteArray())
        val identity = WindowsCloudFileIdentity(
            "account-01",
            "original.txt",
            "\"file-v1\"",
            destination.toFile().length(),
            false,
        )
        val backend = FakeBackend("remote".encodeToByteArray())
        val api = FakeApi(expectedRenames = 1).apply {
            seed(destination, WindowsCloudPlaceholderState.Dirty, identity)
        }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.renameRequested(callbackInfo(root, identity), destination.toString())

        assertTrue(api.awaitRenames())
        assertFalse(api.lastRenameAccepted)
        assertEquals("original.txt", api.decodedIdentity(destination)?.path)
        assertEquals(WindowsCloudPlaceholderState.Dirty, api.placeholderState(destination))
        provider.close()
    }

    @Test
    fun `a newer close event is coalesced and uploads after the active writeback`() {
        val root = createTempDirectory("windows-cloud-coalesce-")
        val local = root.resolve("edit.txt")
        local.writeBytes("first edit".encodeToByteArray())
        val identity = WindowsCloudFileIdentity(
            "account-01",
            "edit.txt",
            "\"etag-01\"",
            local.toFile().length(),
            false,
        )
        val backend = FakeBackend(
            "remote".encodeToByteArray(),
            expectedUploads = 2,
            blockFirstUpload = true,
        )
        val api = FakeApi().apply { seed(local, WindowsCloudPlaceholderState.Dirty, identity) }
        val provider = WindowsCloudFilesProvider(root, backend, api)
        val info = callbackInfo(root, identity).copy(
            normalizedPath = local.toString(),
            fileSize = local.toFile().length(),
        )

        provider.closed(info, deleted = false)
        assertTrue(backend.awaitFirstUploadStarted())
        local.writeBytes("later edit".encodeToByteArray())
        provider.closed(info.copy(fileSize = local.toFile().length()), deleted = false)
        backend.releaseFirstUpload()

        assertTrue(backend.awaitUploads())
        assertEquals(
            listOf("first edit", "later edit"),
            backend.uploadedBytes.map { it.decodeToString() },
        )
        assertEquals(listOf<String?>("\"etag-01\"", "\"uploaded-1\""), backend.uploadExpectedRevisions)
        provider.close()
    }

    @Test
    fun `failed dirty writeback remains visible after bounded retries and a later close can recover it`() {
        val root = createTempDirectory("windows-cloud-writeback-retry-")
        val local = root.resolve("edit.txt")
        local.writeBytes("retained edit".encodeToByteArray())
        val identity = WindowsCloudFileIdentity(
            "account-01",
            "edit.txt",
            "\"etag-01\"",
            local.toFile().length(),
            false,
        )
        val backend = FakeBackend(
            source = "remote".encodeToByteArray(),
            uploadFailuresRemaining = Int.MAX_VALUE,
        )
        val api = FakeApi(expectedConversions = 1).apply {
            seed(local, WindowsCloudPlaceholderState.Dirty, identity)
        }
        val provider = WindowsCloudFilesProvider(
            root,
            backend,
            api,
            writebackRetryDelayMillis = { 0L },
        )
        val info = callbackInfo(root, identity).copy(normalizedPath = local.toString())

        provider.closed(info, deleted = false)

        val failureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (provider.summary().failedWritebackCount == 0 && System.nanoTime() < failureDeadline) {
            Thread.yield()
        }
        assertEquals(1, provider.summary().pendingWritebackCount)
        assertEquals(1, provider.summary().failedWritebackCount)

        backend.uploadFailuresRemaining = 0
        provider.closed(info, deleted = false)

        assertTrue(api.awaitConversions())
        val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (provider.summary().pendingWritebackCount != 0 && System.nanoTime() < recoveryDeadline) {
            Thread.yield()
        }
        assertEquals(0, provider.summary().pendingWritebackCount)
        assertEquals(0, provider.summary().failedWritebackCount)
        provider.close()
    }

    private fun fixtureIdentity(size: Long) = WindowsCloudFileIdentity(
        accountId = "account-01",
        path = "Photos/example.raf",
        remoteRevision = "\"etag-01\"",
        size = size,
        directory = false,
    )

    private fun callbackInfo(root: Path, identity: WindowsCloudFileIdentity) = WindowsCloudCallbackInfo(
        connectionKey = 10L,
        transferKey = 20L,
        requestKey = 30L,
        normalizedPath = root.resolve(identity.path.replace('/', File.separatorChar)).toString(),
        fileIdentity = WindowsCloudFileIdentityCodec.encode(identity),
        fileSize = identity.size,
        priorityHint = 12,
    )

    private fun legacyWindowsCloudIdentity(): ByteArray {
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(0x4E434656)
                output.writeShort(1)
                output.writeBoolean(false)
                output.writeLong(5L)
                listOf("account-01", "Apps/readme.txt", "\"etag-01\"").forEach { value ->
                    val encoded = value.encodeToByteArray()
                    output.writeShort(encoded.size)
                    output.write(encoded)
                }
            }
            bytes.toByteArray()
        }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private class FakeBackend(
        private val source: ByteArray,
        private val listed: List<WindowsCloudFileIdentity> = emptyList(),
        expectedUploads: Int = 0,
        private val blockFirstUpload: Boolean = false,
        private val failAfterUpload: Boolean = false,
        @Volatile var uploadFailuresRemaining: Int = 0,
    ) : WindowsCloudFilesBackend {
        override val accountId: String = "account-01"
        override val displayName: String = "Nextcloud Native - account@example.test"
        private val uploadLatch = CountDownLatch(expectedUploads)
        private val firstUploadStarted = CountDownLatch(if (blockFirstUpload) 1 else 0)
        private val firstUploadRelease = CountDownLatch(if (blockFirstUpload) 1 else 0)
        var lastUploadedPath: String? = null
        var lastExpectedRemoteRevision: String? = null
        val uploadedBytes = mutableListOf<ByteArray>()
        val uploadExpectedRevisions = mutableListOf<String?>()
        val operations = mutableListOf<String>()
        val listedPaths = CopyOnWriteArrayList<String>()
        private val remoteIdentities = mutableMapOf<String, WindowsCloudFileIdentity>()
        private val remoteContents = mutableMapOf<String, ByteArray>()

        override fun resolve(path: String): WindowsCloudFileIdentity? = synchronized(this) {
            remoteIdentities[path]
        }
        override fun list(path: String): List<WindowsCloudFileIdentity> {
            listedPaths += path
            return listed.filter { it.path.substringBeforeLast('/', "") == path }
        }

        override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle {
            val bytes = synchronized(this) { remoteContents[identity.path]?.copyOf() } ?: source
            return object : WindowsCloudFileReadHandle {
                override val size: Long = bytes.size.toLong()
                override fun read(offset: Long, length: Int): ByteArray =
                    bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
                override fun close() = Unit
            }
        }

        override fun upload(
            path: String,
            localFile: File,
            expectedRemoteRevision: String?,
        ): WindowsCloudFileIdentity {
            synchronized(this) {
                if (uploadFailuresRemaining > 0) {
                    uploadFailuresRemaining -= 1
                    error("Simulated transient upload failure")
                }
            }
            lastUploadedPath = path
            lastExpectedRemoteRevision = expectedRemoteRevision
            val bytes = localFile.readBytes()
            val uploadNumber = synchronized(uploadedBytes) {
                uploadedBytes += bytes
                uploadExpectedRevisions += expectedRemoteRevision
                uploadedBytes.size
            }
            val uploaded = WindowsCloudFileIdentity(accountId, path, "\"uploaded-$uploadNumber\"", bytes.size.toLong(), false)
            synchronized(this) {
                operations += "upload:$path"
                remoteIdentities[path] = uploaded
                remoteContents[path] = bytes.copyOf()
            }
            if (blockFirstUpload && uploadNumber == 1) {
                firstUploadStarted.countDown()
                check(firstUploadRelease.await(5, TimeUnit.SECONDS))
            }
            uploadLatch.countDown()
            if (failAfterUpload && uploadNumber == 1) error("Simulated lost create response")
            return uploaded
        }

        override fun createDirectory(path: String): WindowsCloudFileIdentity = synchronized(this) {
            WindowsCloudFileIdentity(accountId, path, "\"directory\"", 0L, true).also { created ->
                operations += "mkdir:$path"
                remoteIdentities[path] = created
            }
        }

        override fun delete(identity: WindowsCloudFileIdentity) = Unit
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            identity.copy(path = destinationPath)

        fun awaitUploads(): Boolean = uploadLatch.await(5, TimeUnit.SECONDS)
        fun awaitFirstUploadStarted(): Boolean = firstUploadStarted.await(5, TimeUnit.SECONDS)
        fun releaseFirstUpload() = firstUploadRelease.countDown()
    }

    private class FakeApi(
        expectedTransfers: Int = 0,
        expectedConversions: Int = 0,
        expectedRenames: Int = 0,
        expectedIdentityReads: Int = 0,
        expectedPlaceholderFetches: Int = 0,
    ) : WindowsCloudFilesApi {
        private val transferLatch = CountDownLatch(expectedTransfers)
        private val conversionLatch = CountDownLatch(expectedConversions)
        private val renameLatch = CountDownLatch(expectedRenames)
        private val identityReadLatch = CountDownLatch(expectedIdentityReads)
        private val placeholderFetchLatch = CountDownLatch(expectedPlaceholderFetches)
        private val states = HashMap<Path, WindowsCloudPlaceholderState>()
        private val identities = HashMap<Path, ByteArray>()
        val transfers = mutableListOf<Pair<Long, ByteArray>>()
        val invalidatedUpdates = mutableListOf<Path>()
        var completedPlaceholders = emptyList<WindowsCloudPlaceholder>()
        var lastRenameAccepted = false
        var unregisteredRoot: Path? = null
        var unregisterFailure: RuntimeException? = null
        val disconnectAttempts = mutableListOf<Long>()
        var disconnectFailure: RuntimeException? = null
        var closed = false
        val lifecycleEvents = mutableListOf<String>()

        override fun registerSyncRoot(root: Path, displayName: String, syncRootIdentity: ByteArray) {
            lifecycleEvents += "register"
        }
        override fun unregisterSyncRoot(root: Path) {
            unregisterFailure?.let { throw it }
            unregisteredRoot = root
        }
        override fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long {
            lifecycleEvents += "connect"
            return 1L
        }
        override fun disconnect(connectionKey: Long) {
            disconnectAttempts += connectionKey
            disconnectFailure?.let { throw it }
        }
        override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) {
            lifecycleEvents += "create"
        }
        override fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray) {
            synchronized(transfers) { transfers += offset to bytes.copyOf() }
            transferLatch.countDown()
        }
        override fun failData(info: WindowsCloudCallbackInfo, offset: Long, length: Long, message: String) = Unit
        override fun completePlaceholderFetch(
            info: WindowsCloudCallbackInfo,
            placeholders: List<WindowsCloudPlaceholder>,
        ) {
            completedPlaceholders = placeholders
            placeholderFetchLatch.countDown()
        }
        override fun failPlaceholderFetch(info: WindowsCloudCallbackInfo) = Unit
        override fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean) = Unit
        override fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean) {
            lastRenameAccepted = accepted
            renameLatch.countDown()
        }
        override fun placeholderState(path: Path): WindowsCloudPlaceholderState =
            states[path] ?: WindowsCloudPlaceholderState.Absent
        override fun allocatedBytes(path: Path): Long = if (states[path] == WindowsCloudPlaceholderState.InSync) {
            path.toFile().length()
        } else {
            0L
        }
        override fun lastAccessedAtEpochMillis(path: Path): Long = 1L
        override fun isPinned(path: Path): Boolean = false
        override fun placeholderIdentity(path: Path): ByteArray? = identities[path]?.copyOf().also {
            identityReadLatch.countDown()
        }
        override fun updatePlaceholder(
            path: Path,
            placeholder: WindowsCloudPlaceholder,
            invalidateContent: Boolean,
            preserveSyncState: Boolean,
        ) {
            if (!preserveSyncState) states[path] = WindowsCloudPlaceholderState.InSync
            identities[path] = placeholder.identity.copyOf()
            if (invalidateContent) invalidatedUpdates.add(path)
        }
        override fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder) {
            states[path] = WindowsCloudPlaceholderState.Dirty
        }
        override fun markInSync(path: Path) {
            states[path] = WindowsCloudPlaceholderState.InSync
            conversionLatch.countDown()
        }
        override fun dehydrate(path: Path): Long = 0L
        override fun close() {
            closed = true
        }

        fun awaitTransfers(): Boolean = transferLatch.await(5, TimeUnit.SECONDS)
        fun awaitConversions(): Boolean = conversionLatch.await(5, TimeUnit.SECONDS)
        fun awaitRenames(): Boolean = renameLatch.await(5, TimeUnit.SECONDS)
        fun awaitIdentityReads(): Boolean = identityReadLatch.await(5, TimeUnit.SECONDS)
        fun awaitPlaceholderFetches(): Boolean = placeholderFetchLatch.await(5, TimeUnit.SECONDS)

        fun decodedIdentity(path: Path): WindowsCloudFileIdentity? =
            placeholderIdentity(path)?.let(WindowsCloudFileIdentityCodec::decode)

        fun seed(path: Path, state: WindowsCloudPlaceholderState, identity: WindowsCloudFileIdentity) {
            states[path] = state
            identities[path] = WindowsCloudFileIdentityCodec.encode(identity)
        }
    }
}
