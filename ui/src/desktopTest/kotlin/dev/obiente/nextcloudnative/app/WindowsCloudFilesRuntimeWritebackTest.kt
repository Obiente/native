package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * Native writeback of placeholder edits made by another process while the provider is connected.
 * These run only on Windows, against a disposable sync root and a synthetic server.
 */
class WindowsCloudFilesRuntimeWritebackTest {
    @Test
    fun `native Windows writes back an edit that changes the file size`() = withNativeRoot { root, backend, api ->
        val draft = root.resolve("draft.txt")
        assertContentEquals(ORIGINAL_BYTES, Files.readAllBytes(draft))

        editInPlace(draft, LONGER_EDIT)

        awaitInSync(api, draft, backend, commits = 1)
        assertEquals(listOf<String?>(ORIGINAL_REVISION), backend.uploadRevisions)
        assertContentEquals(LONGER_EDIT, backend.content("draft.txt"))
        assertEquals(backend.identity("draft.txt"), api.placeholderIdentity(draft)?.let(WindowsCloudFileIdentityCodec::decode))
    }

    @Test
    fun `native Windows uploads an edit made during a writeback before marking the file in sync`() =
        withNativeRoot { root, backend, api ->
            val draft = root.resolve("draft.txt")
            assertContentEquals(ORIGINAL_BYTES, Files.readAllBytes(draft))
            var edits = 1
            backend.duringUpload = { if (edits-- > 0) editInPlace(draft, NEWER_EDIT) }

            editInPlace(draft, LONGER_EDIT)

            awaitInSync(api, draft, backend, commits = 2)
            assertEquals(listOf<String?>(ORIGINAL_REVISION, "\"server-1\""), backend.uploadRevisions.distinct())
            assertContentEquals(NEWER_EDIT, backend.content("draft.txt"))
            assertContentEquals(NEWER_EDIT, Files.readAllBytes(draft))
        }

    private fun withNativeRoot(
        block: (Path, WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend, JnaWindowsCloudFilesApi) -> Unit,
    ) {
        assumeTrue(isWindowsDesktop())
        val root = createTempDirectory("cloud-native-runtime-writeback-")
        val backend = WindowsCloudFilesPlaceholderRecoveryTest.RevisionedBackend("draft.txt" to ORIGINAL_BYTES)
        val api = JnaWindowsCloudFilesApi(shellRegistrar = PackagedWindowsCloudShellRegistrar(launcherPath = null))
        val provider = WindowsCloudFilesProvider(root, backend, api, writebackRetryDelayMillis = { 0L })
        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            block(root, backend, api)
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

    /**
     * Overwrites from offset zero without truncating, like an editor saving a longer file. Cloud
     * Files sends no close notification for I/O by the connected provider's own process, so the
     * edit runs in a separate process as a real editor would.
     */
    private fun editInPlace(path: Path, bytes: ByteArray) {
        val script = "\$s = [IO.File]::Open(\$env:NATIVE_EDIT_PATH, 'Open', 'Write'); " +
            "\$b = [Convert]::FromBase64String(\$env:NATIVE_EDIT_BYTES); \$s.Write(\$b, 0, \$b.Length); \$s.Close()"
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .apply {
                environment()["NATIVE_EDIT_PATH"] = path.toAbsolutePath().toString()
                environment()["NATIVE_EDIT_BYTES"] = Base64.getEncoder().encodeToString(bytes)
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "External edit failed: $output" }
    }

    private companion object {
        const val ORIGINAL_REVISION = "\"etag-01\""
        val ORIGINAL_BYTES = "synthetic original".encodeToByteArray()
        val LONGER_EDIT = "synthetic longer local edit".encodeToByteArray()
        val NEWER_EDIT = "synthetic newer local edit made during upload".encodeToByteArray()
    }
}
