package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Writeback of placeholder edits made while the provider is connected. Native cases run only on
 * Windows, against a disposable sync root and a synthetic server, with edits from another process.
 */
class WindowsCloudFilesRuntimeWritebackTest {
    @Test
    fun `a close callback reporting the last in-sync size still writes back the edit`() {
        val root = createTempDirectory("windows-cloud-runtime-size-")
        try {
            val backend = WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend("edit.txt" to ORIGINAL_BYTES)
            val local = root.resolve("edit.txt").apply { writeBytes(NEWER_EDIT) }
            // After a blocked commit the identity carries the uploaded size, not the last in-sync size.
            val recorded = backend.identity("edit.txt").copy(size = LONGER_EDIT.size.toLong())
            val api = WindowsCloudFilesProviderTest.FakeApi(expectedConversions = 1).apply {
                seed(local, WindowsCloudPlaceholderState.Dirty, recorded)
            }
            WindowsCloudFilesProvider(root, backend, api).use { provider ->
                provider.closed(closeInfo(local, recorded, fileSize = ORIGINAL_BYTES.size.toLong()), deleted = false)

                assertTrue(api.awaitConversions())
                assertEquals(listOf<String?>(ORIGINAL_REVISION), backend.uploadRevisions)
                assertContentEquals(NEWER_EDIT, backend.content("edit.txt"))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `native Windows writes back an edit that changes the file size`() = withNativeRoot { root, backend, api, _ ->
        val draft = root.resolve("draft.txt")
        assertContentEquals(ORIGINAL_BYTES, Files.readAllBytes(draft))

        editInPlaceFromAnotherProcess(draft, LONGER_EDIT)

        awaitInSync(api, draft, backend, commits = 1)
        assertEquals(listOf<String?>(ORIGINAL_REVISION), backend.uploadRevisions)
        assertContentEquals(LONGER_EDIT, backend.content("draft.txt"))
        assertEquals(backend.identity("draft.txt"), api.placeholderIdentity(draft)?.let(WindowsCloudFileIdentityCodec::decode))
    }

    @Test
    fun `native Windows uploads an edit made during a writeback before marking the file in sync`() =
        withNativeRoot { root, backend, api, _ ->
            val draft = root.resolve("draft.txt")
            assertContentEquals(ORIGINAL_BYTES, Files.readAllBytes(draft))
            var edits = 1
            backend.duringUpload = { if (edits-- > 0) editInPlaceFromAnotherProcess(draft, NEWER_EDIT) }

            editInPlaceFromAnotherProcess(draft, LONGER_EDIT)

            awaitInSync(api, draft, backend, commits = 2)
            assertEquals(listOf<String?>(ORIGINAL_REVISION, "\"server-1\""), backend.uploadRevisions.distinct())
            assertContentEquals(NEWER_EDIT, backend.content("draft.txt"))
            assertContentEquals(NEWER_EDIT, Files.readAllBytes(draft))
        }

    @Test
    fun `native Windows completes a commit blocked by another application once it closes the file`() =
        withNativeRoot { root, backend, api, provider ->
            val draft = root.resolve("draft.txt")
            assertContentEquals(ORIGINAL_BYTES, Files.readAllBytes(draft))

            // Another application keeps the file open, so the exclusive in-sync commit cannot run.
            ExternalOpenHandle(draft).use {
                editInPlaceFromAnotherProcess(draft, LONGER_EDIT)
                awaitCondition("blocked commit") {
                    backend.commits == 1 && provider.summary().failedWritebackCount == 1
                }
                assertEquals(WindowsCloudPlaceholderState.Dirty, api.placeholderState(draft))
            }

            // Its close reports the size from the last in-sync point, not the uploaded size, and
            // completes the pending commit without uploading again.
            awaitInSync(api, draft, backend, commits = 1)
            assertEquals(listOf<String?>(ORIGINAL_REVISION), backend.uploadRevisions)
            assertContentEquals(LONGER_EDIT, backend.content("draft.txt"))

            editInPlaceFromAnotherProcess(draft, NEWER_EDIT)

            awaitInSync(api, draft, backend, commits = 2)
            assertEquals(listOf<String?>(ORIGINAL_REVISION, "\"server-1\""), backend.uploadRevisions)
            assertContentEquals(NEWER_EDIT, backend.content("draft.txt"))
        }

    private fun withNativeRoot(
        block: (
            Path,
            WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend,
            JnaWindowsCloudFilesApi,
            WindowsCloudFilesProvider,
        ) -> Unit,
    ) {
        assumeTrue(isWindowsDesktop())
        val root = createTempDirectory("cloud-native-runtime-writeback-")
        val backend = WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend("draft.txt" to ORIGINAL_BYTES)
        val api = JnaWindowsCloudFilesApi(shellRegistrar = PackagedWindowsCloudShellRegistrar(launcherPath = null))
        val provider = WindowsCloudFilesProvider(root, backend, api, writebackRetryDelayMillis = { 0L })
        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            block(root, backend, api, provider)
            assertEquals(0, provider.summary().failedWritebackCount)
        } finally {
            try {
                provider.removeSyncRoot()
            } finally {
                provider.close()
                root.toFile().deleteRecursively()
            }
        }
    }

    private fun awaitCondition(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out waiting for $label." }
            Thread.sleep(10)
        }
    }

    private fun awaitInSync(
        api: WindowsCloudFilesApi,
        path: Path,
        backend: WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend,
        commits: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (backend.commits < commits || api.placeholderState(path) != WindowsCloudPlaceholderState.InSync) {
            check(System.nanoTime() < deadline) {
                "Timed out: commits=${backend.commits}, state=${api.placeholderState(path)}, " +
                    "callbacks=${(api as? JnaWindowsCloudFilesApi)?.diagnostics()}"
            }
            Thread.sleep(10)
        }
    }

    private fun closeInfo(local: Path, identity: WindowsCloudFileIdentity, fileSize: Long) = WindowsCloudCallbackInfo(
        connectionKey = 1L,
        transferKey = 2L,
        requestKey = 3L,
        normalizedPath = local.toString(),
        fileIdentity = WindowsCloudFileIdentityCodec.encode(identity),
        fileSize = fileSize,
        priorityHint = 0,
    )

    private companion object {
        const val ORIGINAL_REVISION = "\"etag-01\""
        val ORIGINAL_BYTES = "synthetic original".encodeToByteArray()
        val LONGER_EDIT = "synthetic longer local edit".encodeToByteArray()
        val NEWER_EDIT = "synthetic newer local edit made during upload".encodeToByteArray()
    }
}
