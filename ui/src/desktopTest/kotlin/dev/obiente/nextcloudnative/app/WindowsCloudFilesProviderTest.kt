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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectory
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
    fun cloudFileUpdateHandlesRequestOnlyRequiredAccess() {
        assertEquals(0, windowsCloudOpenFileFlags(write = false, exclusive = false))
        assertEquals(2, windowsCloudOpenFileFlags(write = true, exclusive = false))
        assertEquals(3, windowsCloudOpenFileFlags(write = true, exclusive = true))
    }

    @Test
    fun nativeInspectionDistinguishesMissingCorruptAndUnreadableEntries() {
        assertEquals(
            WindowsCloudPlaceholderEntryState.Missing,
            windowsCloudPlaceholderInspection(findSucceeded = false, win32Error = 2).state,
        )
        assertEquals(
            WindowsCloudPlaceholderEntryState.Missing,
            windowsCloudPlaceholderInspection(findSucceeded = false, win32Error = 3).state,
        )
        assertEquals(
            WindowsCloudPlaceholderEntryState.Corrupt,
            windowsCloudPlaceholderInspection(findSucceeded = false, win32Error = 363).state,
        )
        assertEquals(
            WindowsCloudPlaceholderEntryState.Unreadable,
            windowsCloudPlaceholderInspection(findSucceeded = false, win32Error = 5).state,
        )
        assertEquals(
            WindowsCloudPlaceholderEntryState.Corrupt,
            windowsCloudPlaceholderInspection(
                findSucceeded = true,
                win32Error = 363,
                fileAttributes = 0x410,
                reparseTag = 0x9000001A.toInt(),
                placeholderStateBits = -1,
            ).state,
        )
        val unreadableState = windowsCloudPlaceholderInspection(
            findSucceeded = true,
            win32Error = 5,
            fileAttributes = 0x410,
            reparseTag = 0x9000001A.toInt(),
            placeholderStateBits = -1,
        )
        assertEquals(WindowsCloudPlaceholderEntryState.Unreadable, unreadableState.state)
        assertEquals(5, unreadableState.win32Error)
    }

    @Test
    fun nativeInspectionSeparatesLocalAndValidPlaceholderEntries() {
        assertEquals(
            WindowsCloudPlaceholderEntryState.Local,
            windowsCloudPlaceholderInspection(
                findSucceeded = true,
                fileAttributes = 0x20,
                reparseTag = 0,
                placeholderStateBits = 0,
            ).state,
        )
        assertEquals(
            WindowsCloudPlaceholderEntryState.InSync,
            windowsCloudPlaceholderInspection(
                findSucceeded = true,
                fileAttributes = 0x410,
                reparseTag = 0x9000001A.toInt(),
                placeholderStateBits = 0x9,
            ).state,
        )
        assertEquals(
            WindowsCloudPlaceholderEntryState.Dirty,
            windowsCloudPlaceholderInspection(
                findSucceeded = true,
                fileAttributes = 0x410,
                reparseTag = 0x9000001A.toInt(),
                placeholderStateBits = 0x11,
            ).state,
        )
    }

    @Test
    fun recoveryRootNameIsBoundedAndDoesNotReplaceTheOriginalPath() {
        val root = Path.of("synthetic-volume", "Nextcloud Native", "account-v2")
        val recovery = windowsCloudFilesRecoveryRoot(root, "12345678-abcd")

        assertEquals(root.toAbsolutePath().normalize().parent, recovery.parent)
        assertEquals("account-v2.recovery-12345678-abcd", recovery.fileName.toString())
    }

    @Test
    fun failedPlaceholderIndexIncludesTheFirstRejectedUnprocessedEntry() {
        assertEquals(
            0,
            windowsCloudFailedPlaceholderIndex(
                firstFailedEntryIndex = 0,
                processedCount = 0,
                placeholderCount = 2,
            ),
        )
        assertEquals(
            1,
            windowsCloudFailedPlaceholderIndex(
                firstFailedEntryIndex = null,
                processedCount = 1,
                placeholderCount = 2,
            ),
        )
    }

    @Test
    fun placeholderDiagnosticResultSamplesStayBounded() {
        assertEquals(0, windowsCloudPlaceholderDiagnosticSampleSize(0))
        assertEquals(4, windowsCloudPlaceholderDiagnosticSampleSize(4))
        assertEquals(
            MAX_WINDOWS_CLOUD_PLACEHOLDER_DIAGNOSTIC_RESULTS,
            windowsCloudPlaceholderDiagnosticSampleSize(100_000),
        )
    }

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
    fun concurrentCallbackPlaceholderCreationDoesNotFailStartup() {
        val root = createTempDirectory("windows-cloud-placeholder-race")
        val identity = WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true)
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                placeholders.forEach { created ->
                    seed(
                        baseDirectory.resolve(created.name),
                        WindowsCloudPlaceholderState.InSync,
                        WindowsCloudFileIdentityCodec.decode(created.identity),
                    )
                }
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0), listOf(identity)), api)

        try {
            provider.start()

            assertEquals(identity, api.decodedIdentity(root.resolve("Apps")))
            assertEquals(listOf(root.resolve("Apps")), api.updatedPaths)
            assertTrue(api.disconnectAttempts.isEmpty())
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun placeholderFetchWaitsForInitialPopulation() {
        val root = createTempDirectory("windows-cloud-initial-population")
        val child = WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true)
        val rootIdentity = child.copy(path = "", remoteRevision = "root")
        val createStarted = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        val api = FakeApi(expectedPlaceholderFetches = 1).apply {
            createPlaceholdersHook = { _, _ ->
                createStarted.countDown()
                check(releaseCreate.await(5, TimeUnit.SECONDS))
            }
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0), listOf(child)), api)
        val startupFailure = AtomicReference<Throwable?>()
        val startup = Thread {
            runCatching(provider::start).exceptionOrNull()?.let(startupFailure::set)
        }

        try {
            startup.start()
            assertTrue(createStarted.await(5, TimeUnit.SECONDS))

            provider.fetchPlaceholders(callbackInfo(root, rootIdentity), pattern = null)

            assertFalse(api.awaitPlaceholderFetches(100))
            releaseCreate.countDown()
            startup.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(startup.isAlive)
            startupFailure.get()?.let { throw it }
            assertTrue(api.awaitPlaceholderFetches())
            assertEquals(listOf("Apps"), api.completedPlaceholders.map(WindowsCloudPlaceholder::name))
        } finally {
            releaseCreate.countDown()
            startup.join(TimeUnit.SECONDS.toMillis(5))
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun collisionRecoveryPreservesTheAuthoritativeNewerGeneration() {
        val root = createTempDirectory("windows-cloud-placeholder-generation")
        val old = WindowsCloudFileIdentity("account-01", "report.txt", "old", 5L, false)
        val current = old.copy(remoteRevision = "current")
        val backend = FakeBackend("fresh".encodeToByteArray(), listOf(old)).apply { seedRemote(current) }
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                seed(
                    baseDirectory.resolve(placeholders.single().name),
                    WindowsCloudPlaceholderState.InSync,
                    current,
                )
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            recordDiagnostic = diagnostics::add,
        )

        try {
            provider.start()

            assertEquals(current, api.decodedIdentity(root.resolve("report.txt")))
            assertTrue(api.invalidatedUpdates.isEmpty())
            assertEquals(listOf("", ""), backend.listedPaths.take(2))
            assertEquals(
                listOf("collision-detected", "collision-reconciled"),
                diagnostics.map(SupportDiagnosticEventDraft::outcome),
            )
            val reconciled = diagnostics.last()
            assertEquals(SupportDiagnosticComponent.VirtualFiles, reconciled.component)
            assertEquals(
                SupportDiagnosticValuePrivacy.LocalPath,
                reconciled.fields.single { it.name == "local_directory" }.privacy,
            )
            assertEquals(
                SupportDiagnosticValuePrivacy.RemotePath,
                reconciled.fields.single { it.name == "remote_path" }.privacy,
            )
            assertEquals(
                SupportDiagnosticValuePrivacy.Identifier,
                reconciled.fields.single { it.name == "account" }.privacy,
            )
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun partialBatchRetryUsesTheRefreshedRemoteGeneration() {
        val root = createTempDirectory("windows-cloud-placeholder-partial-batch")
        val first = WindowsCloudFileIdentity("account-01", "Apps", "apps", 0L, true)
        val oldSecond = WindowsCloudFileIdentity("account-01", "report.txt", "old", 5L, false)
        val currentSecond = oldSecond.copy(remoteRevision = "current")
        val backend = FakeBackend("fresh".encodeToByteArray(), listOf(first, oldSecond)).apply {
            seedRemote(currentSecond)
        }
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                val created = placeholders.first()
                seed(
                    baseDirectory.resolve(created.name),
                    WindowsCloudPlaceholderState.InSync,
                    WindowsCloudFileIdentityCodec.decode(created.identity),
                )
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        try {
            provider.start()

            val retried = api.createdPlaceholderBatches.last().single()
            assertEquals(currentSecond, WindowsCloudFileIdentityCodec.decode(retried.identity))
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun collisionRevalidatesAPlaceholderGenerationThatAdvancedAfterRefresh() {
        val root = createTempDirectory("windows-cloud-placeholder-revalidation")
        val old = WindowsCloudFileIdentity("account-01", "report.txt", "old", 5L, false)
        val intermediate = old.copy(remoteRevision = "intermediate")
        val current = old.copy(remoteRevision = "current")
        val backend = FakeBackend("fresh".encodeToByteArray(), listOf(old)).apply {
            queueList(listOf(old), listOf(intermediate))
            seedRemote(current)
        }
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                seed(
                    baseDirectory.resolve(placeholders.single().name),
                    WindowsCloudPlaceholderState.InSync,
                    current,
                )
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        try {
            provider.start()

            assertEquals(current, api.decodedIdentity(root.resolve("report.txt")))
            assertEquals(listOf("report.txt"), backend.resolvedPaths)
            assertTrue(api.invalidatedUpdates.isEmpty())
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun collisionWithAnotherRemotePathFailsClosed() {
        val root = createTempDirectory("windows-cloud-placeholder-name-conflict")
        val listed = WindowsCloudFileIdentity("account-01", "Report", "listed", 0L, true)
        val conflicting = listed.copy(path = "report", remoteRevision = "conflicting")
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                seed(
                    baseDirectory.resolve(placeholders.single().name),
                    WindowsCloudPlaceholderState.InSync,
                    conflicting,
                )
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0), listOf(listed)), api)

        try {
            val failure = assertFailsWith<IllegalStateException> { provider.start() }
            assertTrue(failure.message.orEmpty().contains("same Windows path"))
            assertEquals(listOf(1L), api.disconnectAttempts)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dirtyCollisionWithoutAReadableIdentityFailsClosed() {
        val root = createTempDirectory("windows-cloud-placeholder-dirty-identity")
        val identity = WindowsCloudFileIdentity("account-01", "edit.txt", "revision", 5L, false)
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                seedState(
                    baseDirectory.resolve(placeholders.single().name),
                    WindowsCloudPlaceholderState.Dirty,
                )
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend("local".encodeToByteArray(), listOf(identity)), api)

        try {
            val failure = assertFailsWith<IllegalStateException> { provider.start() }
            assertTrue(failure.message.orEmpty().contains("Could not verify the existing"))
            assertEquals(listOf(1L), api.disconnectAttempts)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ordinaryLocalEntryCreatedDuringPlaceholderRaceIsPreserved() {
        val root = createTempDirectory("windows-cloud-local-placeholder-race")
        val localBytes = "local recovery data".encodeToByteArray()
        val identity = WindowsCloudFileIdentity(
            "account-01",
            "draft.txt",
            "revision",
            localBytes.size.toLong(),
            false,
        )
        val api = FakeApi().apply {
            createPlaceholdersHook = { baseDirectory, placeholders ->
                baseDirectory.resolve(placeholders.single().name).writeBytes(localBytes)
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val backend = FakeBackend("different remote bytes".encodeToByteArray(), listOf(identity))
        val provider = WindowsCloudFilesProvider(root, backend, api)

        try {
            provider.start()

            assertContentEquals(localBytes, root.resolve("draft.txt").toFile().readBytes())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (provider.summary().failedWritebackCount == 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(1, provider.summary().failedWritebackCount)
            assertEquals(1, provider.summary().pendingWritebackCount)
            assertTrue(api.disconnectAttempts.isEmpty())
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptPlaceholderPreservesTheWholeRootAndReconnectsOnce() {
        val root = createTempDirectory("windows-cloud-corrupt-root")
        val localBytes = "local-only recovery data".encodeToByteArray()
        root.resolve("local-note.txt").writeBytes(localBytes)
        val identity = WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true)
        val corruptPath = root.resolve("Apps")
        val api = FakeApi().apply {
            createPlaceholdersHook = { _, _ ->
                seedInspection(
                    corruptPath,
                    WindowsCloudPlaceholderInspection(
                        state = WindowsCloudPlaceholderEntryState.Corrupt,
                        win32Error = 363,
                    ),
                )
                throw WindowsCloudFilesOperationException(
                    "create Windows Cloud Files placeholders",
                    0x800700B7.toInt(),
                )
            }
        }
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val preserved = root.resolveSibling("preserved-corrupt-root")
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listOf(identity)),
            api = api,
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.seedInspection(
                    corruptPath,
                    WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.Missing),
                )
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()

            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertContentEquals(localBytes, preserved.resolve("local-note.txt").toFile().readBytes())
            assertTrue(Files.isDirectory(root))
            assertEquals(2, api.createdPlaceholderBatches.size)
            assertEquals(listOf("Apps"), api.createdPlaceholderBatches.last().map(WindowsCloudPlaceholder::name))
            assertEquals(
                listOf("register", "connect", "create", "unregister", "register", "connect", "create"),
                api.lifecycleEvents.take(7),
            )
            assertEquals(listOf(1L), api.disconnectAttempts)
            assertEquals(
                listOf(
                    "collision-detected",
                    "corrupt-entry-detected",
                    "corrupt-root-preserved",
                    "corrupt-root-recovered",
                ),
                diagnostics.map(SupportDiagnosticEventDraft::outcome),
            )
            val preservedField = diagnostics[2].fields.single { it.name == "preserved_root" }
            assertEquals(SupportDiagnosticValuePrivacy.LocalPath, preservedField.privacy)
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptRootWaitsForQueuedWritebackBeforeMovingLocalData() {
        val root = createTempDirectory("windows-cloud-corrupt-root-writeback")
        val localBytes = "local edit queued before corruption recovery".encodeToByteArray()
        val localFile = root.resolve("draft.txt")
        localFile.writeBytes(localBytes)
        val draft = WindowsCloudFileIdentity(
            "account-01",
            "draft.txt",
            "draft-revision",
            localBytes.size.toLong(),
            false,
        )
        val directory = WindowsCloudFileIdentity("account-01", "Apps", "directory-revision", 0L, true)
        val corruptPath = root.resolve("Apps")
        val backend = FakeBackend(
            source = ByteArray(0),
            listed = listOf(draft, directory),
            expectedUploads = 1,
            blockFirstUpload = true,
        )
        val api = FakeApi()
        val preserved = root.resolveSibling("preserved-after-writeback")
        lateinit var provider: WindowsCloudFilesProvider
        api.createPlaceholdersHook = { _, _ ->
            api.seed(localFile, WindowsCloudPlaceholderState.Dirty, draft)
            provider.closed(callbackInfo(root, draft).copy(normalizedPath = localFile.toString()), deleted = false)
            api.seedInspection(
                corruptPath,
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Corrupt,
                    win32Error = 363,
                ),
            )
            throw WindowsCloudFilesOperationException(
                "create Windows Cloud Files placeholders",
                0x800700B7.toInt(),
            )
        }
        provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            preserveCorruptRoot = { current ->
                api.clearInspection(corruptPath)
                Files.move(current, preserved)
            },
        )
        val startFailure = AtomicReference<Throwable?>()
        val startThread = Thread {
            runCatching(provider::start).exceptionOrNull()?.let(startFailure::set)
        }

        try {
            startThread.start()
            assertTrue(backend.awaitFirstUploadStarted())
            assertFalse(Files.exists(preserved))
            assertContentEquals(localBytes, localFile.toFile().readBytes())

            backend.releaseFirstUpload()
            startThread.join(5_000L)

            assertFalse(startThread.isAlive)
            assertEquals(null, startFailure.get())
            assertContentEquals(localBytes, preserved.resolve("draft.txt").toFile().readBytes())
            assertEquals(listOf(localBytes.toList()), backend.uploadedBytes.map(ByteArray::toList))
        } finally {
            backend.releaseFirstUpload()
            startThread.join(5_000L)
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptRootWaitsForQueuedDeleteBeforeRebuildingRemoteState() {
        val root = createTempDirectory("windows-cloud-corrupt-root-delete")
        val localBytes = "local data retained while delete completes".encodeToByteArray()
        root.resolve("local-note.txt").writeBytes(localBytes)
        val obsolete = WindowsCloudFileIdentity("account-01", "obsolete.txt", "obsolete-revision", 4L, false)
        val directory = WindowsCloudFileIdentity("account-01", "Apps", "directory-revision", 0L, true)
        val corruptPath = root.resolve("Apps")
        val backend = FakeBackend(
            source = ByteArray(0),
            listed = listOf(obsolete, directory),
            blockFirstDelete = true,
        )
        val api = FakeApi()
        val preserved = root.resolveSibling("preserved-after-delete")
        lateinit var provider: WindowsCloudFilesProvider
        api.createPlaceholdersHook = { _, _ ->
            provider.deleteRequested(callbackInfo(root, obsolete))
            api.seedInspection(
                corruptPath,
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Corrupt,
                    win32Error = 363,
                ),
            )
            throw WindowsCloudFilesOperationException(
                "create Windows Cloud Files placeholders",
                0x800700B7.toInt(),
            )
        }
        provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            preserveCorruptRoot = { current ->
                api.clearInspection(corruptPath)
                Files.move(current, preserved)
            },
        )
        val startFailure = AtomicReference<Throwable?>()
        val startThread = Thread {
            runCatching(provider::start).exceptionOrNull()?.let(startFailure::set)
        }

        try {
            startThread.start()
            assertTrue(backend.awaitFirstDeleteStarted())
            assertFalse(Files.exists(preserved))

            backend.releaseFirstDelete()
            startThread.join(5_000L)

            assertFalse(startThread.isAlive)
            assertEquals(null, startFailure.get())
            assertContentEquals(localBytes, preserved.resolve("local-note.txt").toFile().readBytes())
            assertFalse(backend.listedPathsAfterDelete().contains("obsolete.txt"))
        } finally {
            backend.releaseFirstDelete()
            startThread.join(5_000L)
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun preservedRootIsRecordedBeforeARebuildFailureEscapes() {
        val root = createTempDirectory("windows-cloud-corrupt-root-rebuild-failure")
        val localBytes = "local-only recovery data".encodeToByteArray()
        root.resolve("local-note.txt").writeBytes(localBytes)
        val identity = WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true)
        val corruptPath = root.resolve("Apps")
        val api = FakeApi().apply {
            seedInspection(
                corruptPath,
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Corrupt,
                    win32Error = 363,
                ),
            )
        }
        val preserved = root.resolveSibling("preserved-before-rebuild-failure")
        val recorded = mutableListOf<Path>()
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listOf(identity)),
            api = api,
            preserveCorruptRoot = { current ->
                api.clearInspection(corruptPath)
                api.connectFailures += WindowsCloudFilesOperationException(
                    "connect Windows Cloud Files root",
                    0x80070005.toInt(),
                )
                Files.move(current, preserved)
            },
            recordPreservedCorruptRoot = recorded::add,
        )

        try {
            assertFailsWith<WindowsCloudFilesOperationException> { provider.start() }

            assertEquals(listOf(preserved), recorded)
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertContentEquals(localBytes, preserved.resolve("local-note.txt").toFile().readBytes())
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun nestedCorruptPlaceholderIsPreservedDuringLegacyMigration() {
        val root = createTempDirectory("windows-cloud-nested-corrupt-root")
        val album = root.resolve("Photos").createDirectory()
        val localBytes = "local-only photo edit".encodeToByteArray()
        val localPhoto = album.resolve("edited.jpg")
        localPhoto.writeBytes(localBytes)
        val directory = WindowsCloudFileIdentity("account-01", "Photos", "directory-revision", 0L, true)
        val photo = WindowsCloudFileIdentity(
            "account-01",
            "Photos/edited.jpg",
            "photo-revision",
            localBytes.size.toLong(),
            false,
        )
        val api = FakeApi().apply {
            seed(album, WindowsCloudPlaceholderState.InSync, directory)
            seed(localPhoto, WindowsCloudPlaceholderState.InSync, photo)
            seedInspection(
                localPhoto,
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Corrupt,
                    win32Error = 363,
                ),
            )
        }
        val preserved = root.resolveSibling("preserved-nested-corrupt-root")
        val recorded = mutableListOf<Path>()
        val backend = FakeBackend(ByteArray(0), listOf(directory, photo))
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            preserveCorruptRoot = { current ->
                api.clearInspection(localPhoto)
                Files.move(current, preserved)
            },
            recordPreservedCorruptRoot = recorded::add,
        )

        try {
            provider.start()
            provider.recoverBeforeRootMigration(timeoutSeconds = 5L)

            assertEquals(listOf(preserved), recorded)
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertContentEquals(localBytes, preserved.resolve("Photos/edited.jpg").toFile().readBytes())
            assertEquals(listOf(1L), api.disconnectAttempts)
            assertTrue(backend.uploadedBytes.isEmpty())
            assertEquals(0, provider.summary().failedWritebackCount)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun nestedCorruptPlaceholderIsPreservedBeforeNormalStartupCompletes() {
        val root = createTempDirectory("windows-cloud-current-nested-corrupt-root")
        val album = root.resolve("Photos").createDirectory()
        val localBytes = "current local-only photo edit".encodeToByteArray()
        val localPhoto = album.resolve("edited.jpg")
        localPhoto.writeBytes(localBytes)
        val directory = WindowsCloudFileIdentity("account-01", "Photos", "directory-revision", 0L, true)
        val photo = WindowsCloudFileIdentity(
            "account-01",
            "Photos/edited.jpg",
            "photo-revision",
            localBytes.size.toLong(),
            false,
        )
        val api = FakeApi().apply {
            seed(album, WindowsCloudPlaceholderState.InSync, directory)
            seed(localPhoto, WindowsCloudPlaceholderState.InSync, photo)
            seedInspection(
                localPhoto,
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Corrupt,
                    win32Error = 363,
                ),
            )
        }
        val preserved = root.resolveSibling("preserved-current-nested-corrupt-root")
        val recorded = mutableListOf<Path>()
        val backend = FakeBackend(ByteArray(0), listOf(directory, photo))
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            preserveCorruptRoot = { current ->
                api.clearInspection(localPhoto)
                Files.move(current, preserved)
            },
            recordPreservedCorruptRoot = recorded::add,
        )

        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5L)

            assertEquals(listOf(preserved), recorded)
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertContentEquals(localBytes, preserved.resolve("Photos/edited.jpg").toFile().readBytes())
            assertTrue(backend.uploadedBytes.isEmpty())
            assertEquals(0, provider.summary().failedWritebackCount)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun unreadablePlaceholderStopsNormalStartupActivation() {
        val root = createTempDirectory("windows-cloud-unreadable-startup-root")
        val localFile = root.resolve("unreadable.txt")
        localFile.writeBytes("local data".encodeToByteArray())
        val api = FakeApi().apply {
            seedInspection(
                localFile,
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Unreadable,
                    win32Error = 5,
                ),
            )
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), emptyList()),
            api = api,
        )

        try {
            provider.start()

            val failure = assertFailsWith<WindowsCloudFilesUnreadableEntryException> {
                provider.recoverAfterStartup(timeoutSeconds = 5L)
            }
            assertEquals(5, failure.inspection.win32Error)
            assertContentEquals("local data".encodeToByteArray(), localFile.toFile().readBytes())
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unreadableUnmanagedEntryStopsNormalStartupActivation() {
        val root = createTempDirectory("windows-cloud-unreadable-unmanaged-root")
        val localFile = root.resolve("unmanaged.txt")
        localFile.writeBytes("unmanaged local data".encodeToByteArray())
        val api = FakeApi().apply {
            queueInspections(
                localFile,
                WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.Local),
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Unreadable,
                    win32Error = 5,
                ),
            )
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), emptyList()),
            api = api,
        )

        try {
            provider.start()

            val failure = assertFailsWith<WindowsCloudFilesUnreadableEntryException> {
                provider.recoverAfterStartup(timeoutSeconds = 5L)
            }
            assertEquals(5, failure.inspection.win32Error)
            assertContentEquals("unmanaged local data".encodeToByteArray(), localFile.toFile().readBytes())
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptRootMoveFailureLeavesLocalDataUntouched() {
        val root = createTempDirectory("windows-cloud-corrupt-root-move-failure")
        val localBytes = "must remain local".encodeToByteArray()
        root.resolve("local-note.txt").writeBytes(localBytes)
        val identity = WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true)
        val api = FakeApi().apply {
            seedInspection(
                root.resolve("Apps"),
                WindowsCloudPlaceholderInspection(
                    state = WindowsCloudPlaceholderEntryState.Corrupt,
                    win32Error = 363,
                ),
            )
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listOf(identity)),
            api = api,
            preserveCorruptRoot = { throw IllegalStateException("simulated move refusal") },
        )

        try {
            val failure = assertFailsWith<IllegalStateException> { provider.start() }

            assertTrue(failure.message.orEmpty().contains("Could not preserve"))
            assertContentEquals(localBytes, root.resolve("local-note.txt").toFile().readBytes())
            assertEquals(listOf("register", "connect", "unregister", "register"), api.lifecycleEvents)
            assertEquals(listOf(1L), api.disconnectAttempts)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unrelatedPlaceholderCreationFailureStillStopsStartup() {
        val root = createTempDirectory("windows-cloud-placeholder-failure")
        val identity = WindowsCloudFileIdentity("account-01", "Apps", "revision", 0L, true)
        val failure = WindowsCloudFilesOperationException(
            "create Windows Cloud Files placeholders",
            0x80070005.toInt(),
        )
        val api = FakeApi().apply {
            createPlaceholdersHook = { _, _ -> throw failure }
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0), listOf(identity)), api)

        try {
            assertEquals(failure, assertFailsWith<WindowsCloudFilesOperationException> { provider.start() })
            assertEquals(listOf(1L), api.disconnectAttempts)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun missingRegistrationDuringConnectIsRepairedAndRetriedOnce() {
        val root = createTempDirectory("windows-cloud-connect-repair")
        val api = FakeApi().apply {
            connectFailures += WindowsCloudFilesOperationException(
                "connect the Windows Cloud Files provider",
                0x80070186.toInt(),
            )
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0)), api)

        try {
            provider.start()

            assertEquals(
                listOf("register", "connect", "unregister", "register", "connect"),
                api.lifecycleEvents.take(5),
            )
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsafeConnectFailureDoesNotReplaceTheRegistration() {
        val root = createTempDirectory("windows-cloud-connect-rejected")
        val failure = WindowsCloudFilesOperationException(
            "connect the Windows Cloud Files provider",
            0x80070005.toInt(),
        )
        val api = FakeApi().apply { connectFailures += failure }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0)), api)

        try {
            assertEquals(failure, assertFailsWith<WindowsCloudFilesOperationException> { provider.start() })
            assertEquals(listOf("register", "connect"), api.lifecycleEvents)
            assertEquals(null, api.unregisteredRoot)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun registrationRepairStopsAfterOneFailedRetry() {
        val root = createTempDirectory("windows-cloud-connect-retry-bounded")
        val firstFailure = WindowsCloudFilesOperationException(
            "connect the Windows Cloud Files provider",
            0x80070003.toInt(),
        )
        val retryFailure = WindowsCloudFilesOperationException(
            "connect the Windows Cloud Files provider",
            0x80070186.toInt(),
        )
        val api = FakeApi().apply {
            connectFailures += firstFailure
            connectFailures += retryFailure
        }
        val provider = WindowsCloudFilesProvider(root, FakeBackend(ByteArray(0)), api)

        try {
            assertEquals(retryFailure, assertFailsWith<WindowsCloudFilesOperationException> { provider.start() })
            assertEquals(listOf(firstFailure), retryFailure.suppressed.toList())
            assertEquals(
                listOf("register", "connect", "unregister", "register", "connect"),
                api.lifecycleEvents,
            )
        } finally {
            provider.close()
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
    fun `startup retains hydrated bytes for metadata-only placeholder updates`() {
        val root = createTempDirectory("windows-cloud-metadata-refresh-")
        val local = root.resolve("example.raf")
        local.writeBytes("cached bytes".encodeToByteArray())
        val old = fixtureIdentity(size = local.toFile().length()).copy(path = "example.raf")
        val fresh = old.copy(lastModifiedEpochMillis = 1_000L)
        val backend = FakeBackend("cached bytes".encodeToByteArray(), listed = listOf(fresh))
        val api = FakeApi().apply { seed(local, WindowsCloudPlaceholderState.InSync, old) }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.start()

        assertTrue(api.invalidatedUpdates.isEmpty())
        assertEquals(fresh, api.decodedIdentity(local))
        provider.close()
    }

    @Test
    fun `startup does not rewrite an unchanged file placeholder`() {
        val root = createTempDirectory("windows-cloud-unchanged-file-")
        val local = root.resolve("example.raf")
        local.writeBytes("cached bytes".encodeToByteArray())
        val identity = fixtureIdentity(size = local.toFile().length()).copy(path = "example.raf")
        val backend = FakeBackend("cached bytes".encodeToByteArray(), listed = listOf(identity))
        val api = FakeApi().apply { seed(local, WindowsCloudPlaceholderState.InSync, identity) }
        val provider = WindowsCloudFilesProvider(root, backend, api)

        provider.start()

        assertTrue(api.updatedPaths.isEmpty())
        assertEquals(identity, api.decodedIdentity(local))
        provider.close()
    }

    @Test
    fun `startup keeps an unchanged directory active when its optional refresh is rejected`() {
        val root = createTempDirectory("windows-cloud-unchanged-directory-")
        val local = root.resolve("Photos")
        local.toFile().mkdirs()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val failure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val backend = FakeBackend(ByteArray(0), listed = listOf(identity))
        val api = FakeApi().apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            updatePlaceholderFailure = failure
        }
        val provider = WindowsCloudFilesProvider(root, backend, api, recordDiagnostic = diagnostics::add)

        provider.start()

        assertEquals(listOf(local), api.updatedPaths)
        assertEquals("unchanged-refresh-skipped", diagnostics.single().outcome)
        assertEquals("HRESULT:0x80070179", diagnostics.single().code)
        assertEquals(identity, api.decodedIdentity(local))
        provider.close()
    }

    @Test
    fun `startup preserves and rebuilds a root when placeholder update reports corrupt metadata`() {
        val root = createTempDirectory("windows-cloud-update-corrupt-root-")
        val localBytes = "local-only recovery data".encodeToByteArray()
        root.resolve("local-note.txt").writeBytes(localBytes)
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val failure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val preserved = root.resolveSibling("preserved-update-corrupt-root")
        val api = FakeApi().apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            updatePlaceholderFailure = failure
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.updatePlaceholderFailure = null
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()

            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertContentEquals(localBytes, preserved.resolve("local-note.txt").toFile().readBytes())
            assertTrue(Files.isDirectory(root))
            assertEquals(listOf(local), api.updatedPaths)
            assertEquals(listOf("Photos"), api.createdPlaceholderBatches.single().map(WindowsCloudPlaceholder::name))
            assertEquals(listOf(1L), api.disconnectAttempts)
            assertEquals(
                listOf(
                    "corrupt-metadata-detected",
                    "corrupt-entry-detected",
                    "corrupt-root-preserved",
                    "corrupt-root-recovered",
                ),
                diagnostics.map(SupportDiagnosticEventDraft::outcome),
            )
            assertEquals("HRESULT:0x8007016b", diagnostics.first().code)
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup retries a rejected unchanged directory refresh`() {
        val root = createTempDirectory("windows-cloud-unchanged-directory-retry-")
        val local = root.resolve("Photos")
        local.toFile().mkdirs()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val failure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val backend = FakeBackend(ByteArray(0), listed = listOf(identity))
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            updatePlaceholderFailure = failure
            updatePlaceholderFailuresRemaining = 1
        }
        val provider = WindowsCloudFilesProvider(
            root,
            backend,
            api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
        )

        provider.start()

        assertTrue(api.awaitPlaceholderUpdates())
        val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (diagnostics.none { it.outcome == "unchanged-refresh-recovered" } && System.nanoTime() < recoveryDeadline) {
            Thread.yield()
        }
        assertEquals(2, api.updatedPaths.size)
        assertTrue(diagnostics.any { it.outcome == "unchanged-refresh-skipped" })
        assertTrue(diagnostics.any { it.outcome == "unchanged-refresh-recovered" })
        assertEquals(identity, api.decodedIdentity(local))
        provider.close()
    }

    @Test
    fun `delayed directory refresh preserves the root when a retry reveals corrupt metadata`() {
        val root = createTempDirectory("windows-cloud-retry-corrupt-root-")
        val localBytes = "local data preserved after delayed corruption".encodeToByteArray()
        root.resolve("local-note.txt").writeBytes(localBytes)
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val preserved = root.resolveSibling("preserved-retry-corrupt-root")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            updatePlaceholderFailure = ordinaryFailure
            onUpdatePlaceholderFailure = { updatePlaceholderFailure = corruptFailure }
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.updatePlaceholderFailure = null
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()

            assertTrue(api.awaitPlaceholderUpdates())
            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertContentEquals(localBytes, preserved.resolve("local-note.txt").toFile().readBytes())
            assertTrue(Files.isDirectory(root))
            assertEquals(2, api.updatedPaths.size)
            assertEquals(listOf("Photos"), api.createdPlaceholderBatches.single().map(WindowsCloudPlaceholder::name))
            assertEquals(listOf(1L), api.disconnectAttempts)
            assertTrue(diagnostics.any { it.outcome == "unchanged-refresh-skipped" })
            assertTrue(diagnostics.any { it.outcome == "corrupt-metadata-detected" })
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovered" })
            assertFalse(diagnostics.any { it.outcome == "unchanged-refresh-stale" })
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delayed directory refresh preserves the root when retry inspection reveals corruption`() {
        val root = createTempDirectory("windows-cloud-retry-inspection-corrupt-root-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val inSync = WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.InSync)
        val corrupt = WindowsCloudPlaceholderInspection(
            WindowsCloudPlaceholderEntryState.Corrupt,
            win32Error = WINDOWS_ERROR_CLOUD_FILE_METADATA_CORRUPT,
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val preserved = root.resolveSibling("preserved-retry-inspection-corrupt-root")
        val api = FakeApi(expectedPlaceholderUpdates = 1).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            queueInspections(local, inSync, corrupt)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()
            assertTrue(api.awaitPlaceholderUpdates())
            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertTrue(diagnostics.any { it.outcome == "corrupt-entry-detected" })
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovered" })
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertEquals(1, api.updatedPaths.size)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `activation waits for queued delayed corruption recovery to finish`() {
        val root = createTempDirectory("windows-cloud-activation-waits-recovery-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val preservationStarted = CountDownLatch(1)
        val releasePreservation = CountDownLatch(1)
        val activationFinished = CountDownLatch(1)
        val activationFailure = AtomicReference<Throwable?>()
        val preserved = root.resolveSibling("preserved-activation-waits-recovery")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            preserveCorruptRoot = { current ->
                preservationStarted.countDown()
                check(releasePreservation.await(5, TimeUnit.SECONDS))
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )
        val activation = Thread {
            runCatching { provider.recoverAfterStartup(timeoutSeconds = 5L) }
                .onFailure(activationFailure::set)
            activationFinished.countDown()
        }

        try {
            provider.start()
            activation.start()
            assertTrue(preservationStarted.await(5, TimeUnit.SECONDS))
            assertFalse(activationFinished.await(100, TimeUnit.MILLISECONDS))

            releasePreservation.countDown()
            assertTrue(activationFinished.await(5, TimeUnit.SECONDS))
            activationFailure.get()?.let { throw it }
            assertEquals(preserved, provider.preservedRecoveryRoot)
        } finally {
            releasePreservation.countDown()
            activation.join(TimeUnit.SECONDS.toMillis(5))
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt placeholder update after writeback rebuilds without replaying the upload`() {
        val root = createTempDirectory("windows-cloud-writeback-update-corrupt-")
        val localBytes = "locally edited once".encodeToByteArray()
        val local = root.resolve("edit.txt")
        local.writeBytes(localBytes)
        val identity = WindowsCloudFileIdentity(
            "account-01",
            "edit.txt",
            "\"etag-01\"",
            localBytes.size.toLong(),
            false,
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val preserved = root.resolveSibling("preserved-writeback-update-corrupt")
        val backend = FakeBackend(
            source = "remote".encodeToByteArray(),
            listed = listOf(identity),
            expectedUploads = 1,
        )
        val api = FakeApi(expectedPlaceholderUpdates = 1).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.updatePlaceholderFailure = null
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5L)
            api.seed(local, WindowsCloudPlaceholderState.Dirty, identity)
            api.updatePlaceholderFailure = corruptFailure
            provider.closed(
                callbackInfo(root, identity).copy(normalizedPath = local.toString()),
                deleted = false,
            )

            assertTrue(backend.awaitUploads())
            assertTrue(api.awaitPlaceholderUpdates())
            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertTrue(diagnostics.any { event ->
                event.outcome == "corrupt-metadata-detected" &&
                    event.fields.any { it.name == "remote_mutation_completed" && it.value == "true" }
            })
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovered" })
            assertEquals(listOf<String?>("\"etag-01\""), backend.uploadExpectedRevisions)
            assertEquals(1, backend.uploadedBytes.size)
            assertContentEquals(localBytes, preserved.resolve("edit.txt").toFile().readBytes())
            assertEquals(preserved, provider.preservedRecoveryRoot)
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `concurrent corrupt directory retries preserve the root only once`() {
        val root = createTempDirectory("windows-cloud-concurrent-corrupt-root-")
        root.resolve("local-note.txt").writeBytes("keep me".encodeToByteArray())
        val photos = root.resolve("Photos").createDirectory()
        val videos = root.resolve("Videos").createDirectory()
        val identities = listOf(
            fixtureIdentity(size = 0L).copy(path = "Photos", directory = true),
            fixtureIdentity(size = 0L).copy(path = "Videos", directory = true),
        )
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val preserveCount = AtomicInteger()
        val rejectedUpdates = AtomicInteger()
        val preserved = root.resolveSibling("preserved-concurrent-corrupt-root")
        val api = FakeApi(expectedPlaceholderUpdates = 4).apply {
            seed(photos, WindowsCloudPlaceholderState.InSync, identities[0])
            seed(videos, WindowsCloudPlaceholderState.InSync, identities[1])
            updatePlaceholderFailure = ordinaryFailure
            onUpdatePlaceholderFailure = {
                if (rejectedUpdates.incrementAndGet() == 2) updatePlaceholderFailure = corruptFailure
            }
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = identities),
            api = api,
            directoryRefreshRetryDelayMillis = { 25L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                preserveCount.incrementAndGet()
                api.updatePlaceholderFailure = null
                api.seedState(photos, WindowsCloudPlaceholderState.Absent)
                api.seedState(videos, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()

            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertEquals(1, preserveCount.get())
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertTrue(Files.exists(preserved.resolve("local-note.txt")))
            assertEquals(1, diagnostics.count { it.outcome == "corrupt-root-preserved" })
            assertEquals(1, diagnostics.count { it.outcome == "corrupt-root-recovered" })
        } finally {
            provider.removeSyncRoot()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `corrupt retries wait for startup without starving the initial recovery worker`() {
        val root = createTempDirectory("windows-cloud-startup-retry-recovery-")
        val identities = (1..5).map { index ->
            fixtureIdentity(size = 0L).copy(path = "Folder-$index", directory = true)
        }
        val localPaths = identities.map { identity -> root.resolve(identity.path).createDirectory() }
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val fifthInitialUpdateEntered = CountDownLatch(1)
        val finishInitialPopulation = CountDownLatch(1)
        val delayedInitialUpdate = AtomicBoolean(false)
        val startFailure = AtomicReference<Throwable?>()
        val preserved = root.resolveSibling("preserved-startup-retry-recovery")
        val api = FakeApi().apply {
            localPaths.zip(identities).forEach { (path, identity) ->
                seed(path, WindowsCloudPlaceholderState.InSync, identity)
                scriptUpdatePlaceholderFailures(path, ordinaryFailure, corruptFailure)
            }
            beforeUpdatePlaceholder = { path ->
                if (path == localPaths.last() && delayedInitialUpdate.compareAndSet(false, true)) {
                    fifthInitialUpdateEntered.countDown()
                    check(finishInitialPopulation.await(5, TimeUnit.SECONDS))
                }
            }
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = identities),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                localPaths.forEach { api.seedState(it, WindowsCloudPlaceholderState.Absent) }
                Files.move(current, preserved)
            },
        )
        val starter = Thread {
            runCatching(provider::start).exceptionOrNull()?.let(startFailure::set)
        }

        try {
            starter.start()
            assertTrue(fifthInitialUpdateEntered.await(5, TimeUnit.SECONDS))
            Thread.sleep(100L)
            finishInitialPopulation.countDown()
            starter.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse(starter.isAlive)
            assertEquals(null, startFailure.get())

            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertEquals(1, diagnostics.count { it.outcome == "corrupt-root-recovered" })
        } finally {
            finishInitialPopulation.countDown()
            starter.join(TimeUnit.SECONDS.toMillis(5))
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shutdown waits for delayed corrupt root recovery before disconnecting the rebuilt provider`() {
        val root = createTempDirectory("windows-cloud-shutdown-during-recovery-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val recoveryEntered = CountDownLatch(1)
        val finishRecovery = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val preserved = root.resolveSibling("preserved-shutdown-recovery")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            preserveCorruptRoot = { current ->
                recoveryEntered.countDown()
                check(finishRecovery.await(5, TimeUnit.SECONDS))
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        val closer = Thread {
            provider.close()
            closeFinished.countDown()
        }
        try {
            provider.start()
            assertTrue(recoveryEntered.await(5, TimeUnit.SECONDS))

            closer.start()
            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
            finishRecovery.countDown()
            assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1L, 1L), api.disconnectAttempts)
            assertTrue(api.closed)
        } finally {
            finishRecovery.countDown()
            closer.join(TimeUnit.SECONDS.toMillis(5))
            if (!api.closed) provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `shutdown keeps writeback retries alive until corrupt root recovery quiesces`() {
        val root = createTempDirectory("windows-cloud-shutdown-writeback-retry-")
        val localBytes = "retry before preserving".encodeToByteArray()
        val localFile = root.resolve("draft.txt")
        localFile.writeBytes(localBytes)
        val directoryPath = root.resolve("Photos").createDirectory()
        val draft = fixtureIdentity(size = localBytes.size.toLong()).copy(path = "draft.txt")
        val directory = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val backend = FakeBackend(
            source = ByteArray(0),
            listed = listOf(draft, directory),
            expectedUploads = 1,
            uploadFailuresRemaining = 1,
        )
        val preserved = root.resolveSibling("preserved-shutdown-writeback-retry")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(localFile, WindowsCloudPlaceholderState.Dirty, draft)
            seed(directoryPath, WindowsCloudPlaceholderState.InSync, directory)
            scriptUpdatePlaceholderFailures(directoryPath, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            writebackRetryDelayMillis = { 100L },
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.seedState(localFile, WindowsCloudPlaceholderState.Absent)
                api.seedState(directoryPath, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )
        val closeFinished = CountDownLatch(1)
        val closer = Thread {
            provider.close()
            closeFinished.countDown()
        }

        try {
            provider.start()
            val corruptionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-metadata-detected" } &&
                System.nanoTime() < corruptionDeadline
            ) {
                Thread.yield()
            }
            while (
                (!provider.isCorruptRootRecoveryInProgress() || backend.uploadFailuresRemaining > 0) &&
                System.nanoTime() < corruptionDeadline
            ) {
                Thread.yield()
            }
            assertTrue(provider.isCorruptRootRecoveryInProgress())
            assertEquals(0, backend.uploadFailuresRemaining)

            closer.start()
            assertTrue(closeFinished.await(5, TimeUnit.SECONDS))
            assertTrue(backend.awaitUploads())
            assertContentEquals(localBytes, preserved.resolve("draft.txt").toFile().readBytes())
            assertEquals(listOf(localBytes.toList()), backend.uploadedBytes.map(ByteArray::toList))
        } finally {
            closer.join(TimeUnit.SECONDS.toMillis(5))
            if (!api.closed) provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `successful delayed recovery invalidates stale unreadable startup failure`() {
        val root = createTempDirectory("windows-cloud-stale-startup-unreadable-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val inSync = WindowsCloudPlaceholderInspection(WindowsCloudPlaceholderEntryState.InSync)
        val unreadable = WindowsCloudPlaceholderInspection(
            WindowsCloudPlaceholderEntryState.Unreadable,
            win32Error = 5,
        )
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val preserveCount = AtomicInteger()
        val preserved = root.resolveSibling("preserved-stale-startup-unreadable")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            queueInspections(local, inSync, unreadable, inSync)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 100L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                preserveCount.incrementAndGet()
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5L)
            assertEquals(1, preserveCount.get())
            assertEquals(preserved, provider.preservedRecoveryRoot)
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovered" })
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `legacy migration keeps stable root access ahead of delayed corruption recovery`() {
        val root = createTempDirectory("windows-cloud-migration-delayed-corruption-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val migrationEntered = CountDownLatch(1)
        val releaseMigration = CountDownLatch(1)
        val migrationFinished = CountDownLatch(1)
        val preservationStarted = CountDownLatch(1)
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val preserved = root.resolveSibling("preserved-migration-delayed-corruption")
        val backend = FakeBackend(ByteArray(0), listed = listOf(identity))
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            directoryRefreshRetryDelayMillis = { 500L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                preservationStarted.countDown()
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )
        val migrationFailure = AtomicReference<Throwable?>()
        lateinit var migration: Thread
        migration = Thread {
            runCatching { provider.recoverBeforeRootMigration(timeoutSeconds = 15L) }
                .onFailure(migrationFailure::set)
            migrationFinished.countDown()
        }.apply { name = "legacy-root-migration-test" }

        try {
            provider.start()
            val initialRecoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (backend.listedPaths.size < 2 && System.nanoTime() < initialRecoveryDeadline) {
                Thread.yield()
            }
            assertTrue(backend.listedPaths.size >= 2)
            backend.beforeList = {
                if (Thread.currentThread() === migration) {
                    migrationEntered.countDown()
                    check(releaseMigration.await(15, TimeUnit.SECONDS))
                }
            }
            migration.start()
            assertTrue(migrationEntered.await(5, TimeUnit.SECONDS))
            assertTrue(api.awaitPlaceholderUpdates())

            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!provider.isCorruptRootRecoveryInProgress() && System.nanoTime() < recoveryDeadline) {
                Thread.yield()
            }
            assertTrue(provider.isCorruptRootRecoveryInProgress())
            assertFalse(preservationStarted.await(100, TimeUnit.MILLISECONDS))

            releaseMigration.countDown()
            assertTrue(migrationFinished.await(5, TimeUnit.SECONDS))
            migrationFailure.get()?.let { throw it }
            assertTrue(preservationStarted.await(5, TimeUnit.SECONDS))
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovered" })
            assertEquals(preserved, provider.preservedRecoveryRoot)
        } finally {
            releaseMigration.countDown()
            migration.join(TimeUnit.SECONDS.toMillis(5))
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `delayed recovery scans files created during rebuilt root population`() {
        val root = createTempDirectory("windows-cloud-rebuild-local-file-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val localBytes = "created while rebuilding".encodeToByteArray()
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val backend = FakeBackend(ByteArray(0), listed = listOf(identity), expectedUploads = 1)
        val preserved = root.resolveSibling("preserved-rebuild-local-file")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = backend,
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { current ->
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                api.createPlaceholdersHook = { baseDirectory, _ ->
                    baseDirectory.resolve("captured.txt").writeBytes(localBytes)
                }
                Files.move(current, preserved)
            },
        )

        try {
            provider.start()
            assertTrue(backend.awaitUploads())
            assertEquals("captured.txt", backend.lastUploadedPath)
            assertEquals(listOf(localBytes.toList()), backend.uploadedBytes.map(ByteArray::toList))
            val recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (
                diagnostics.none { it.outcome == "corrupt-root-recovered" } &&
                System.nanoTime() < recoveryDeadline
            ) {
                Thread.yield()
            }
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovered" })
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `cache cleanup waits until corrupt root preservation finishes`() {
        val root = createTempDirectory("windows-cloud-cache-during-recovery-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val preservationStarted = CountDownLatch(1)
        val finishPreservation = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        val preserved = root.resolveSibling("preserved-cache-during-recovery")
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            scriptUpdatePlaceholderFailures(local, ordinaryFailure, corruptFailure)
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            preserveCorruptRoot = { current ->
                preservationStarted.countDown()
                check(finishPreservation.await(5, TimeUnit.SECONDS))
                api.seedState(local, WindowsCloudPlaceholderState.Absent)
                Files.move(current, preserved)
            },
        )
        val cleanup = Thread {
            provider.freeUpSpace(1L)
            cleanupFinished.countDown()
        }

        try {
            provider.start()
            assertTrue(preservationStarted.await(5, TimeUnit.SECONDS))
            cleanup.start()
            assertFalse(cleanupFinished.await(100, TimeUnit.MILLISECONDS))
            finishPreservation.countDown()
            assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS))
            assertTrue(Files.isDirectory(preserved.resolve("Photos")))
        } finally {
            finishPreservation.countDown()
            cleanup.join(TimeUnit.SECONDS.toMillis(5))
            provider.close()
            root.toFile().deleteRecursively()
            preserved.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed delayed corrupt root recovery is retained and reported`() {
        val root = createTempDirectory("windows-cloud-delayed-recovery-failure-")
        val local = root.resolve("Photos").createDirectory()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val ordinaryFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val corruptFailure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x8007016B.toInt(),
        )
        val preservationFailure = IllegalStateException("recovery drive unavailable")
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val reportedFailure = AtomicReference<Throwable?>()
        val api = FakeApi(expectedPlaceholderUpdates = 2).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            updatePlaceholderFailure = ordinaryFailure
            onUpdatePlaceholderFailure = { updatePlaceholderFailure = corruptFailure }
        }
        val provider = WindowsCloudFilesProvider(
            root = root,
            backend = FakeBackend(ByteArray(0), listed = listOf(identity)),
            api = api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
            preserveCorruptRoot = { throw preservationFailure },
            onRuntimeFailure = reportedFailure::set,
        )

        try {
            provider.start()

            val failureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (reportedFailure.get() == null && System.nanoTime() < failureDeadline) {
                Thread.yield()
            }
            val retained = provider.runtimeRecoveryFailure()
            assertTrue(retained is IllegalStateException)
            assertTrue(retained.message.orEmpty().contains("Could not preserve"))
            assertEquals(retained, reportedFailure.get())
            assertTrue(diagnostics.any { it.outcome == "corrupt-root-recovery-failed" })
            assertFailsWith<IllegalStateException> { provider.recoverAfterStartup(timeoutSeconds = 5L) }
        } finally {
            provider.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup reports an unchanged directory as stale after bounded refresh retries`() {
        val root = createTempDirectory("windows-cloud-unchanged-directory-stale-")
        val local = root.resolve("Photos")
        local.toFile().mkdirs()
        val identity = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val failure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val backend = FakeBackend(ByteArray(0), listed = listOf(identity))
        val api = FakeApi(expectedPlaceholderUpdates = 5).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, identity)
            updatePlaceholderFailure = failure
        }
        val provider = WindowsCloudFilesProvider(
            root,
            backend,
            api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
        )

        provider.start()

        assertTrue(api.awaitPlaceholderUpdates())
        val staleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (diagnostics.none { it.outcome == "unchanged-refresh-stale" } && System.nanoTime() < staleDeadline) {
            Thread.yield()
        }
        assertEquals(5, api.updatedPaths.size)
        assertEquals("unchanged-refresh-stale", diagnostics.last().outcome)
        assertEquals(
            "4",
            diagnostics.last().fields.single { it.name == "attempt" }.value,
        )
        provider.close()
    }

    @Test
    fun `startup abandons a delayed directory refresh when the path identity changed`() {
        val root = createTempDirectory("windows-cloud-unchanged-directory-replaced-")
        val local = root.resolve("Photos")
        local.toFile().mkdirs()
        val original = fixtureIdentity(size = 0L).copy(path = "Photos", directory = true)
        val replacement = original.copy(remoteRevision = "replacement-revision")
        val failure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val diagnostics = CopyOnWriteArrayList<SupportDiagnosticEventDraft>()
        val backend = FakeBackend(ByteArray(0), listed = listOf(original))
        val api = FakeApi(expectedPlaceholderUpdates = 1).apply {
            seed(local, WindowsCloudPlaceholderState.InSync, original)
            updatePlaceholderFailure = failure
            updatePlaceholderFailuresRemaining = 1
            onUpdatePlaceholderFailure = {
                seed(local, WindowsCloudPlaceholderState.InSync, replacement)
            }
        }
        val provider = WindowsCloudFilesProvider(
            root,
            backend,
            api,
            directoryRefreshRetryDelayMillis = { 0L },
            recordDiagnostic = diagnostics::add,
        )

        provider.start()

        assertTrue(api.awaitPlaceholderUpdates())
        val abandonedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (diagnostics.none { it.outcome == "unchanged-refresh-abandoned" } && System.nanoTime() < abandonedDeadline) {
            Thread.yield()
        }
        assertEquals(1, api.updatedPaths.size)
        assertEquals(replacement, api.decodedIdentity(local))
        val abandoned = diagnostics.single { it.outcome == "unchanged-refresh-abandoned" }
        assertEquals("false", abandoned.fields.single { it.name == "identity_matches" }.value)
        provider.close()
    }

    @Test
    fun `startup fails closed and records the HRESULT when a changed placeholder update is rejected`() {
        val root = createTempDirectory("windows-cloud-changed-update-failure-")
        val local = root.resolve("example.raf")
        local.writeBytes("cached bytes".encodeToByteArray())
        val previous = fixtureIdentity(size = local.toFile().length()).copy(path = "example.raf")
        val current = previous.copy(remoteRevision = "new-remote-revision")
        val failure = WindowsCloudFilesOperationException(
            "update a Windows Cloud Files placeholder",
            0x80070179.toInt(),
        )
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        val backend = FakeBackend("new bytes".encodeToByteArray(), listed = listOf(current))
        val api = FakeApi().apply {
            seed(local, WindowsCloudPlaceholderState.InSync, previous)
            updatePlaceholderFailure = failure
        }
        val provider = WindowsCloudFilesProvider(root, backend, api, recordDiagnostic = diagnostics::add)

        try {
            assertEquals(
                failure,
                assertFailsWith<WindowsCloudFilesOperationException> { provider.start() },
            )
            assertEquals("failed", diagnostics.single().outcome)
            assertEquals("HRESULT:0x80070179", diagnostics.single().code)
            assertEquals(
                "true",
                diagnostics.single().fields.single { it.name == "content_changed" }.value,
            )
        } finally {
            provider.close()
        }
    }

    @Test
    fun `Windows Cloud Files diagnostic code follows a bounded cause chain`() {
        val failure = IllegalStateException(
            "activation failed",
            WindowsCloudFilesOperationException("update a placeholder", 0x80070179.toInt()),
        )

        assertEquals("HRESULT:0x80070179", windowsCloudFilesDiagnosticCode(failure))
        assertEquals(null, windowsCloudFilesDiagnosticCode(IllegalStateException("unrelated")))
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

        assertTrue(api.awaitConversions(TimeUnit.SECONDS.toMillis(15)))
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
        private val blockFirstDelete: Boolean = false,
    ) : WindowsCloudFilesBackend {
        override val accountId: String = "account-01"
        override val displayName: String = "Nextcloud Native - account@example.test"
        private val uploadLatch = CountDownLatch(expectedUploads)
        private val firstUploadStarted = CountDownLatch(if (blockFirstUpload) 1 else 0)
        private val firstUploadRelease = CountDownLatch(if (blockFirstUpload) 1 else 0)
        private val firstDeleteStarted = CountDownLatch(if (blockFirstDelete) 1 else 0)
        private val firstDeleteRelease = CountDownLatch(if (blockFirstDelete) 1 else 0)
        var lastUploadedPath: String? = null
        var lastExpectedRemoteRevision: String? = null
        val uploadedBytes = mutableListOf<ByteArray>()
        val uploadExpectedRevisions = mutableListOf<String?>()
        val operations = mutableListOf<String>()
        val listedPaths = CopyOnWriteArrayList<String>()
        val resolvedPaths = CopyOnWriteArrayList<String>()
        @Volatile var beforeList: ((String) -> Unit)? = null
        private val remoteIdentities = listed.associateBy { identity -> identity.path }.toMutableMap()
        private val remoteContents = mutableMapOf<String, ByteArray>()
        private val scriptedLists = ArrayDeque<List<WindowsCloudFileIdentity>>()

        override fun resolve(path: String): WindowsCloudFileIdentity? = synchronized(this) {
            resolvedPaths += path
            remoteIdentities[path]
        }
        override fun list(path: String): List<WindowsCloudFileIdentity> = synchronized(this) {
            beforeList?.invoke(path)
            listedPaths += path
            if (scriptedLists.isNotEmpty()) return@synchronized scriptedLists.removeFirst()
            listed.mapNotNull { identity -> remoteIdentities[identity.path] }
                .filter { identity -> identity.path.substringBeforeLast('/', "") == path }
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
                check(expectedRemoteRevision != null || remoteIdentities[path] == null) {
                    "The server file appeared after the sync scan."
                }
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
            check(remoteIdentities[path] == null) { "The server folder appeared after the sync scan." }
            WindowsCloudFileIdentity(accountId, path, "\"directory\"", 0L, true).also { created ->
                operations += "mkdir:$path"
                remoteIdentities[path] = created
            }
        }

        override fun delete(identity: WindowsCloudFileIdentity) {
            if (blockFirstDelete) {
                firstDeleteStarted.countDown()
                check(firstDeleteRelease.await(5, TimeUnit.SECONDS))
            }
            synchronized(this) {
                operations += "delete:${identity.path}"
                remoteIdentities.remove(identity.path)
                remoteContents.remove(identity.path)
            }
        }
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            identity.copy(path = destinationPath)

        fun awaitUploads(): Boolean = uploadLatch.await(5, TimeUnit.SECONDS)
        fun awaitFirstUploadStarted(): Boolean = firstUploadStarted.await(5, TimeUnit.SECONDS)
        fun releaseFirstUpload() = firstUploadRelease.countDown()
        fun awaitFirstDeleteStarted(): Boolean = firstDeleteStarted.await(5, TimeUnit.SECONDS)
        fun releaseFirstDelete() = firstDeleteRelease.countDown()
        fun listedPathsAfterDelete(): Set<String> = synchronized(this) { remoteIdentities.keys.toSet() }

        fun seedRemote(identity: WindowsCloudFileIdentity) = synchronized(this) {
            remoteIdentities[identity.path] = identity
        }

        fun queueList(vararg responses: List<WindowsCloudFileIdentity>) = synchronized(this) {
            scriptedLists.addAll(responses)
        }
    }

    private class FakeApi(
        expectedTransfers: Int = 0,
        expectedConversions: Int = 0,
        expectedRenames: Int = 0,
        expectedIdentityReads: Int = 0,
        expectedPlaceholderFetches: Int = 0,
        expectedPlaceholderUpdates: Int = 0,
    ) : WindowsCloudFilesApi {
        private val transferLatch = CountDownLatch(expectedTransfers)
        private val conversionLatch = CountDownLatch(expectedConversions)
        private val renameLatch = CountDownLatch(expectedRenames)
        private val identityReadLatch = CountDownLatch(expectedIdentityReads)
        private val placeholderFetchLatch = CountDownLatch(expectedPlaceholderFetches)
        private val placeholderUpdateLatch = CountDownLatch(expectedPlaceholderUpdates)
        private val states = HashMap<Path, WindowsCloudPlaceholderState>()
        private val inspections = HashMap<Path, WindowsCloudPlaceholderInspection>()
        private val inspectionScripts = HashMap<Path, ArrayDeque<WindowsCloudPlaceholderInspection>>()
        private val identities = HashMap<Path, ByteArray>()
        val transfers = mutableListOf<Pair<Long, ByteArray>>()
        val createdPlaceholderBatches = mutableListOf<List<WindowsCloudPlaceholder>>()
        val invalidatedUpdates = mutableListOf<Path>()
        val updatedPaths = CopyOnWriteArrayList<Path>()
        var completedPlaceholders = emptyList<WindowsCloudPlaceholder>()
        var lastRenameAccepted = false
        var unregisteredRoot: Path? = null
        var unregisterFailure: RuntimeException? = null
        val connectFailures = mutableListOf<WindowsCloudFilesOperationException>()
        val disconnectAttempts = mutableListOf<Long>()
        var disconnectFailure: RuntimeException? = null
        var createPlaceholdersHook: ((Path, List<WindowsCloudPlaceholder>) -> Unit)? = null
        var updatePlaceholderFailure: WindowsCloudFilesOperationException? = null
        var updatePlaceholderFailuresRemaining: Int = Int.MAX_VALUE
        var onUpdatePlaceholderFailure: (() -> Unit)? = null
        var beforeUpdatePlaceholder: ((Path) -> Unit)? = null
        private val updatePlaceholderFailureScripts = HashMap<Path, ArrayDeque<WindowsCloudFilesOperationException>>()
        var closed = false
        val lifecycleEvents = mutableListOf<String>()

        override fun registerSyncRoot(root: Path, displayName: String, syncRootIdentity: ByteArray) {
            lifecycleEvents += "register"
        }
        override fun unregisterSyncRoot(root: Path) {
            unregisterFailure?.let { throw it }
            lifecycleEvents += "unregister"
            unregisteredRoot = root
        }
        override fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long {
            lifecycleEvents += "connect"
            if (connectFailures.isNotEmpty()) throw connectFailures.removeAt(0)
            return 1L
        }
        override fun disconnect(connectionKey: Long) {
            disconnectAttempts += connectionKey
            disconnectFailure?.let { throw it }
        }
        override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) {
            lifecycleEvents += "create"
            createdPlaceholderBatches.add(placeholders)
            createPlaceholdersHook?.also { hook ->
                createPlaceholdersHook = null
                hook(baseDirectory, placeholders)
            }
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
        override fun inspectPlaceholder(path: Path): WindowsCloudPlaceholderInspection {
            inspectionScripts[path]?.let { script ->
                if (script.isNotEmpty()) return script.removeFirst()
            }
            return inspections[path] ?: super.inspectPlaceholder(path)
        }
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
            beforeUpdatePlaceholder?.invoke(path)
            updatedPaths.add(path)
            try {
                val scriptedFailure = synchronized(updatePlaceholderFailureScripts) {
                    updatePlaceholderFailureScripts[path]?.removeFirstOrNull()
                }
                if (scriptedFailure != null) throw scriptedFailure
                updatePlaceholderFailure?.let { failure ->
                    if (updatePlaceholderFailuresRemaining > 0) {
                        updatePlaceholderFailuresRemaining -= 1
                        onUpdatePlaceholderFailure?.invoke()
                        throw failure
                    }
                }
                if (!preserveSyncState) states[path] = WindowsCloudPlaceholderState.InSync
                identities[path] = placeholder.identity.copyOf()
                if (invalidateContent) invalidatedUpdates.add(path)
            } finally {
                placeholderUpdateLatch.countDown()
            }
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
        fun awaitConversions(timeoutMillis: Long = TimeUnit.SECONDS.toMillis(5)): Boolean =
            conversionLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        fun awaitRenames(): Boolean = renameLatch.await(5, TimeUnit.SECONDS)
        fun awaitIdentityReads(): Boolean = identityReadLatch.await(5, TimeUnit.SECONDS)
        fun awaitPlaceholderFetches(timeoutMillis: Long = TimeUnit.SECONDS.toMillis(5)): Boolean =
            placeholderFetchLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        fun awaitPlaceholderUpdates(timeoutMillis: Long = TimeUnit.SECONDS.toMillis(5)): Boolean =
            placeholderUpdateLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)

        fun scriptUpdatePlaceholderFailures(
            path: Path,
            vararg failures: WindowsCloudFilesOperationException,
        ) {
            synchronized(updatePlaceholderFailureScripts) {
                updatePlaceholderFailureScripts[path] = ArrayDeque(failures.asList())
            }
        }

        fun decodedIdentity(path: Path): WindowsCloudFileIdentity? =
            placeholderIdentity(path)?.let(WindowsCloudFileIdentityCodec::decode)

        fun seed(path: Path, state: WindowsCloudPlaceholderState, identity: WindowsCloudFileIdentity) {
            states[path] = state
            identities[path] = WindowsCloudFileIdentityCodec.encode(identity)
        }

        fun seedState(path: Path, state: WindowsCloudPlaceholderState) {
            states[path] = state
            identities.remove(path)
        }

        fun seedInspection(path: Path, inspection: WindowsCloudPlaceholderInspection) {
            inspections[path] = inspection
        }

        fun queueInspections(path: Path, vararg queued: WindowsCloudPlaceholderInspection) {
            inspectionScripts[path] = ArrayDeque(queued.asList())
        }

        fun clearInspection(path: Path) {
            inspections.remove(path)
        }
    }
}
