package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Opt-in tests of the packaged registrar and real CFAPI, using only synthetic content. */
class WindowsCloudFilesBrandedIntegrationTest {
    @Test
    fun brandedRootPopulatesHydratesAndRestarts() = exerciseRoot(seedLegacy = false)

    @Test
    fun legacyShortIdentitiesRecoverWithoutLosingLocalFiles() = exerciseRoot(seedLegacy = true)

    private fun exerciseRoot(seedLegacy: Boolean) {
        val launcher = System.getenv("NATIVE_WINDOWS_TEST_LAUNCHER")
        assumeTrue(isWindowsDesktop() && !launcher.isNullOrBlank())
        val account = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val fixtureBase = System.getenv("NATIVE_WINDOWS_TEST_PARENT")?.let(Path::of) ?: Path.of("build")
        val parent = Files.createTempDirectory(Files.createDirectories(fixtureBase.toAbsolutePath()), "cloud-fixture-")
        val root = Files.createDirectory(parent.resolve("$account-v2"))
        val backend = FixtureBackend(account)
        val registrar = PackagedWindowsCloudShellRegistrar(
                launcherPath = launcher,
                recoveryRootsProvider = { emptyMap() },
            ).also { assertTrue(it.available) }
        val verifiedRegistrar = object : WindowsCloudShellRegistrar by registrar {
            override fun register(root: Path, accountId: String, displayName: String, syncRootIdentity: ByteArray): WindowsShellRegistrationResult =
                registrar.register(root, accountId, displayName, syncRootIdentity).also {
                    assertEquals(WindowsShellRegistrationResult.Registered, it)
                }
        }
        fun api() = JnaWindowsCloudFilesApi(
            shellRegistrar = verifiedRegistrar,
        )
        var provider = WindowsCloudFilesProvider(root, backend, api())
        try {
            if (seedLegacy) {
                root.resolve("local-edit.txt").toFile().writeText("preserved original")
                val nativeSeedApi = api()
                val seedApi = object : WindowsCloudFilesApi by nativeSeedApi {
                    override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) {
                        nativeSeedApi.createPlaceholders(baseDirectory, placeholders.map {
                            it.copy(identity = legacyIdentity(it.identity))
                        })
                    }
                }
                provider.close()
                provider = WindowsCloudFilesProvider(root, backend, seedApi)
                val failure = runCatching { provider.start() }.exceptionOrNull()
                provider.close()
                // Windows versions that no longer reproduce this kernel failure do not need the
                // migration probe; the normal native lifecycle test still applies there.
                assumeTrue(failure != null)
                assertEquals(0x8007016b.toInt(), assertIs<WindowsCloudFilesOperationException>(failure).hResult)
                provider = WindowsCloudFilesProvider(root, backend, api())
            }
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 10L)
            if (seedLegacy) {
                val preserved = requireNotNull(provider.preservedRecoveryRoot)
                assertEquals("preserved original", preserved.resolve("local-edit.txt").toFile().readText())
            }
            readFixture(root)
            provider.close()
            val nativeApi = api()
            var injectCorruption = true
            val recoveryApi = object : WindowsCloudFilesApi by nativeApi {
                override fun updatePlaceholder(path: Path, placeholder: WindowsCloudPlaceholder, invalidateContent: Boolean, preserveSyncState: Boolean) {
                    if (injectCorruption) {
                        injectCorruption = false
                        throw WindowsCloudFilesOperationException("open a synthetic corrupt placeholder", 0x8007016b.toInt())
                    }
                    nativeApi.updatePlaceholder(path, placeholder, invalidateContent, preserveSyncState)
                }
            }
            provider = WindowsCloudFilesProvider(root, backend, recoveryApi)
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 10L)
            assertTrue(provider.preservedRecoveryRoot != null)
            readFixture(root)
            provider.close()
            provider = WindowsCloudFilesProvider(root, backend, api())
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 10L)
            readFixture(root)
        } finally {
            provider.removeSyncRoot()
            // Both paths were created exclusively by this test under its private fixture parent.
            assertEquals(parent, root.parent)
            parent.toFile().deleteRecursively()
        }
    }

    private fun legacyIdentity(encoded: ByteArray): ByteArray {
        val identity = WindowsCloudFileIdentityCodec.decode(encoded)
        val length = 29 + identity.accountId.encodeToByteArray().size + identity.path.encodeToByteArray().size +
            identity.remoteRevision.encodeToByteArray().size
        val payload = encoded.copyOfRange(0, length).apply { this[5] = 2 }
        return payload + MessageDigest.getInstance("SHA-256").digest(payload)
    }

    private fun readFixture(root: Path) {
        val listing = ProcessBuilder("cmd.exe", "/d", "/c", "dir", "/b", root.toString())
            .redirectErrorStream(true).start()
        if (!listing.waitFor(15L, TimeUnit.SECONDS)) {
            listing.destroyForcibly()
            error("Synthetic root enumeration timed out")
        }
        val names = listing.inputStream.bufferedReader().readLines()
        assertEquals(0, listing.exitValue(), "Synthetic root enumeration failed: $names")
        assertTrue("Documents" in names)

        val observer = ProcessBuilder("cmd.exe", "/d", "/c", "dir", "/s", "/b", root.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val process = ProcessBuilder("cmd.exe", "/d", "/c", "type", root.resolve("Documents/readme.txt").toString())
            .redirectErrorStream(true).start()
        if (!process.waitFor(15L, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Synthetic Cloud Files hydration timed out")
        }
        assertEquals(0, process.exitValue())
        assertContentEquals("hello".encodeToByteArray(), process.inputStream.readBytes())
        if (!observer.waitFor(15L, TimeUnit.SECONDS)) {
            observer.destroyForcibly()
            error("Concurrent synthetic folder browsing timed out")
        }
        assertEquals(0, observer.exitValue(), "Concurrent synthetic folder browsing failed")
    }

    private class FixtureBackend(override val accountId: String) : WindowsCloudFilesBackend {
        override val displayName = "nati.ve disposable fixture"
        private val entries = listOf(
            WindowsCloudFileIdentity(accountId, "Documents", "\"0123456789abc\"", 0L, true, 1_700_000_000_000L),
            WindowsCloudFileIdentity(accountId, "Documents/readme.txt", "file-v1", 5L, false, 1_700_000_000_000L),
        ) + (1..40).map { index ->
            WindowsCloudFileIdentity(accountId, "F$index", "\"0123456789abc\"", 0L, true, 1_700_000_000_000L)
        } + listOf(".hidden", ".folder", "Folder with spaces", "Album (2026)").map { name ->
            WindowsCloudFileIdentity(accountId, name, "\"0123456789abcdef0123456789abcdef\"", 0L, true, 1_700_000_000_000L)
        } + listOf(0L, 5L, 4096L, 5_000_000_000L, 100_000_000_000L).mapIndexed { index, size ->
            WindowsCloudFileIdentity(accountId, "File-$index.bin", "\"0123456789abcdef0123456789abcdef\"", size, false, 1_700_000_000_000L)
        }
        override fun resolve(path: String) = entries.firstOrNull { it.path == path }
        override fun list(path: String) = entries.filter { it.path.substringBeforeLast('/', "") == path }
        override fun open(identity: WindowsCloudFileIdentity) = object : WindowsCloudFileReadHandle {
            override val size = 5L
            override fun read(offset: Long, length: Int) = "hello".encodeToByteArray()
                .copyOfRange(offset.toInt(), minOf(5, offset.toInt() + length))
            override fun close() = Unit
        }
        override fun upload(path: String, localFile: File, expectedRemoteRevision: String?): WindowsCloudFileIdentity =
            error("This read fixture must not upload")
        override fun createDirectory(path: String): WindowsCloudFileIdentity = error("Unexpected directory mutation")
        override fun delete(identity: WindowsCloudFileIdentity) = error("Unexpected deletion")
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            error("Unexpected move")
    }
}
