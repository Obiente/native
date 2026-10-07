package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class WindowsCloudFilesStartupTest {
    @Test
    fun `native Windows startup does not populate an unopened remote directory`() {
        assumeTrue(isWindowsDesktop())
        val root = createTempDirectory("cloud-native-startup-")
        val backend = RootBackend()
        val api = JnaWindowsCloudFilesApi(shellRegistrar = PackagedWindowsCloudShellRegistrar(launcherPath = null))
        val provider = WindowsCloudFilesProvider(root, backend, api)
        try {
            provider.start()
            provider.recoverAfterStartup(timeoutSeconds = 5)
            assertEquals(setOf(root, root.resolve("Remote")), api.localTree(root).toSet())
            assertEquals(listOf(""), backend.listed)
        } finally {
            try { provider.removeSyncRoot() } finally { provider.close() }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `startup lists the remote root without crawling remote descendants`() {
        val root = createTempDirectory("cloud-startup-")
        val backend = RootBackend()
        val api = LocalApi()
        try {
            WindowsCloudFilesProvider(root, backend, api).use { provider ->
                provider.start()
                provider.recoverAfterStartup(timeoutSeconds = 5)
                assertEquals(listOf(""), backend.listed)
                assertTrue(api.scanned.isNotEmpty())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resident traversal includes nested entries and rejects paths outside the directory`() {
        val root = createTempDirectory("cloud-local-tree-")
        val directory = Files.createDirectory(root.resolve("Notes"))
        Files.writeString(directory.resolve("draft.txt"), "synthetic draft")
        val api = LocalApi()
        try {
            assertEquals(
                setOf(root, directory, directory.resolve("draft.txt")),
                api.localTree(root).toSet(),
            )
            assertEquals(listOf(root, directory), api.scanned)
            // A child supplied outside the enumerated directory must never enter the traversal.
            api.unsafeChild = root.resolveSibling("outside")
            assertFailsWith<IllegalArgumentException> { api.localTree(root).toList() }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Windows native local enumeration returns resident children and distinguishes missing directories`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = createTempDirectory("cloud-native-list-")
        try {
            assertEquals(emptyList(), windowsCloudLocalChildren(root))
            val folder = Files.createDirectory(root.resolve("Notes"))
            val file = Files.writeString(root.resolve("draft.txt"), "synthetic draft")
            assertEquals(setOf(folder, file), windowsCloudLocalChildren(root).toSet())
            assertFailsWith<WindowsCloudLocalScanException> { windowsCloudLocalChildren(root.resolve("missing")) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class RootBackend : WindowsCloudFilesBackend {
        override val accountId = "synthetic-account"
        override val displayName = "Synthetic account"
        val listed = CopyOnWriteArrayList<String>()
        override fun list(path: String): List<WindowsCloudFileIdentity> {
            listed += path
            check(path.isEmpty()) { "Startup must not browse remote descendants." }
            return listOf(WindowsCloudFileIdentity(accountId, "Remote", "revision", 0, true))
        }
        override fun resolve(path: String): WindowsCloudFileIdentity? = null
        override fun open(identity: WindowsCloudFileIdentity): WindowsCloudFileReadHandle = error("Unexpected read")
        override fun upload(path: String, localFile: File, expectedRemoteRevision: String?): WindowsCloudFileIdentity =
            error("Unexpected write")
        override fun createDirectory(path: String): WindowsCloudFileIdentity = error("Unexpected create")
        override fun delete(identity: WindowsCloudFileIdentity) = error("Unexpected delete")
        override fun move(identity: WindowsCloudFileIdentity, destinationPath: String): WindowsCloudFileIdentity =
            error("Unexpected move")
    }

    private class LocalApi : WindowsCloudFilesApi {
        val scanned = CopyOnWriteArrayList<Path>()
        var unsafeChild: Path? = null
        override fun localChildren(directory: Path): List<Path> {
            scanned.add(directory)
            return unsafeChild?.let(::listOf) ?: super.localChildren(directory)
        }
        override fun registerSyncRoot(root: Path, displayName: String, syncRootIdentity: ByteArray) = Unit
        override fun unregisterSyncRoot(root: Path) = Unit
        override fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks) = 1L
        override fun disconnect(connectionKey: Long) = Unit
        override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) = Unit
        override fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray) = Unit
        override fun failData(info: WindowsCloudCallbackInfo, offset: Long, length: Long, message: String) = Unit
        override fun completePlaceholderFetch(info: WindowsCloudCallbackInfo, placeholders: List<WindowsCloudPlaceholder>) = Unit
        override fun failPlaceholderFetch(info: WindowsCloudCallbackInfo) = Unit
        override fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean) = Unit
        override fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean) = Unit
        override fun placeholderState(path: Path) = WindowsCloudPlaceholderState.Absent
        override fun allocatedBytes(path: Path) = 0L
        override fun lastAccessedAtEpochMillis(path: Path) = 0L
        override fun isPinned(path: Path) = false
        override fun placeholderIdentity(path: Path): ByteArray? = null
        override fun updatePlaceholder(path: Path, placeholder: WindowsCloudPlaceholder, invalidateContent: Boolean, preserveSyncState: Boolean) = Unit
        override fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder) = Unit
        override fun markInSync(path: Path) = Unit
        override fun dehydrate(path: Path) = 0L
        override fun close() = Unit
    }
}
