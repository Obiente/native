package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsUninstallCleanupTest {
    @Test
    fun cloudFilesRootUsesTheCurrentProviderMetadataGeneration() {
        val home = Files.createTempDirectory("windows-root-generation-home").toFile()
        try {
            assertEquals(
                "${"a".repeat(64)}-v2",
                desktopWindowsCloudFilesRoot("a".repeat(64), home).name,
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun uninstallUnregistersThePersistedAccountsCloudFilesRoot() {
        val preferences = Preferences.userRoot().node("windows-uninstall-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-uninstall-home").toFile()
        val session = NextcloudSession("https://cloud.invalid", "alice", "unused")
        val expectedRoot = desktopWindowsCloudFilesRoot(desktopFileCacheAccountId(session), home)
        assertTrue(expectedRoot.mkdirs())
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("server", session.serverUrl)
            preferences.put("login", session.loginName)

            unregisterWindowsCloudFilesRootForUninstall(preferences, home) { api }

            assertEquals(
                setOf(
                    expectedRoot.toPath(),
                    home.resolve("Nextcloud Native").resolve(desktopFileCacheAccountId(session)).toPath(),
                ),
                api.unregisteredRoots.toSet(),
            )
            assertTrue(api.closed)
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun uninstallWithoutAPersistedAccountDoesNotLoadCloudFiles() {
        val preferences = Preferences.userRoot().node("windows-uninstall-empty-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-uninstall-empty-home").toFile()
        var apiCreated = false
        try {
            unregisterWindowsCloudFilesRootForUninstall(preferences, home) {
                apiCreated = true
                RecordingWindowsCloudFilesApi()
            }
            assertFalse(apiCreated)
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun uninstallCanUseThePersistedRootAfterSessionMetadataIsGone() {
        val preferences = Preferences.userRoot().node("windows-uninstall-root-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-uninstall-root-home").toFile()
        val expectedRoot = desktopWindowsCloudFilesRoot("a".repeat(64), home)
        assertTrue(expectedRoot.mkdirs())
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("windows-cloud-files-root", expectedRoot.absolutePath)

            unregisterWindowsCloudFilesRootForUninstall(preferences, home) { api }

            assertEquals(expectedRoot.toPath(), api.unregisteredRoot)
            assertTrue(api.closed)
            assertEquals(null, preferences.get("windows-cloud-files-root", null))
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun uninstallStillUnregistersAValidatedRootAfterItsDirectoryDisappears() {
        val preferences = Preferences.userRoot().node("windows-uninstall-missing-root-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-uninstall-missing-root-home").toFile()
        val expectedRoot = desktopWindowsCloudFilesRoot("b".repeat(64), home)
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("windows-cloud-files-root", expectedRoot.absolutePath)

            unregisterWindowsCloudFilesRootForUninstall(preferences, home) { api }

            assertEquals(expectedRoot.toPath(), api.unregisteredRoot)
            assertTrue(api.closed)
            assertEquals(null, preferences.get("windows-cloud-files-root", null))
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun activationUnregistersTheSupersededProviderRootWithoutDeletingIt() {
        val preferences = Preferences.userRoot().node("windows-root-migration-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-root-migration-home").toFile()
        val legacyRoot = home.resolve("Nextcloud Native").resolve("c".repeat(64))
        assertTrue(legacyRoot.mkdirs())
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("windows-cloud-files-root", legacyRoot.absolutePath)

            unregisterSupersededWindowsCloudFilesRoot(
                preferences = preferences,
                accountId = "c".repeat(64),
                userHome = home,
                api = api,
            )

            assertEquals(legacyRoot.toPath(), api.unregisteredRoot)
            assertTrue(legacyRoot.isDirectory)
            assertEquals(null, preferences.get("windows-cloud-files-root", null))
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun activationDoesNotUnregisterAnotherAccountsProviderRoot() {
        val preferences = Preferences.userRoot().node("windows-root-account-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-root-account-home").toFile()
        val otherRoot = desktopWindowsCloudFilesRoot("a".repeat(64), home)
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("windows-cloud-files-root", otherRoot.absolutePath)

            unregisterSupersededWindowsCloudFilesRoot(
                preferences = preferences,
                accountId = "b".repeat(64),
                userHome = home,
                api = api,
            )

            assertEquals(
                home.resolve("Nextcloud Native").resolve("b".repeat(64)).toPath(),
                api.unregisteredRoot,
            )
            assertEquals(otherRoot.absolutePath, preferences.get("windows-cloud-files-root", null))
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun activationFindsItsLegacyRootAfterAnotherAccountReplacesTheSavedPointer() {
        val preferences = Preferences.userRoot().node("windows-root-derived-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-root-derived-home").toFile()
        val otherRoot = desktopWindowsCloudFilesRoot("a".repeat(64), home)
        val legacyRoot = home.resolve("Nextcloud Native").resolve("b".repeat(64))
        assertTrue(legacyRoot.mkdirs())
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("windows-cloud-files-root", otherRoot.absolutePath)

            unregisterSupersededWindowsCloudFilesRoot(
                preferences = preferences,
                accountId = "b".repeat(64),
                userHome = home,
                api = api,
            )

            assertEquals(legacyRoot.toPath(), api.unregisteredRoot)
            assertEquals(otherRoot.absolutePath, preferences.get("windows-cloud-files-root", null))
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun uninstallAcceptsALegacyProviderRoot() {
        val preferences = Preferences.userRoot().node("windows-legacy-uninstall-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-legacy-uninstall-home").toFile()
        val legacyRoot = home.resolve("Nextcloud Native").resolve("d".repeat(64))
        assertTrue(legacyRoot.mkdirs())
        val api = RecordingWindowsCloudFilesApi()
        try {
            preferences.put("windows-cloud-files-root", legacyRoot.absolutePath)

            unregisterWindowsCloudFilesRootForUninstall(preferences, home) { api }

            assertEquals(legacyRoot.toPath(), api.unregisteredRoot)
            assertTrue(api.closed)
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    private class RecordingWindowsCloudFilesApi : WindowsCloudFilesApi {
        val unregisteredRoots = mutableListOf<Path>()
        val unregisteredRoot: Path? get() = unregisteredRoots.lastOrNull()
        var closed = false

        override fun unregisterSyncRoot(root: Path) {
            unregisteredRoots.add(root)
        }

        override fun close() {
            closed = true
        }

        override fun registerSyncRoot(root: Path, displayName: String, syncRootIdentity: ByteArray) = unsupported()
        override fun connect(root: Path, callbacks: WindowsCloudFilesCallbacks): Long = unsupported()
        override fun disconnect(connectionKey: Long) = unsupported()
        override fun createPlaceholders(baseDirectory: Path, placeholders: List<WindowsCloudPlaceholder>) = unsupported()
        override fun transferData(info: WindowsCloudCallbackInfo, offset: Long, bytes: ByteArray) = unsupported()
        override fun failData(info: WindowsCloudCallbackInfo, offset: Long, length: Long, message: String) = unsupported()
        override fun completePlaceholderFetch(info: WindowsCloudCallbackInfo, placeholders: List<WindowsCloudPlaceholder>) = unsupported()
        override fun failPlaceholderFetch(info: WindowsCloudCallbackInfo) = unsupported()
        override fun acknowledgeDelete(info: WindowsCloudCallbackInfo, accepted: Boolean) = unsupported()
        override fun acknowledgeRename(info: WindowsCloudCallbackInfo, accepted: Boolean) = unsupported()
        override fun placeholderState(path: Path): WindowsCloudPlaceholderState = unsupported()
        override fun allocatedBytes(path: Path): Long = unsupported()
        override fun lastAccessedAtEpochMillis(path: Path): Long = unsupported()
        override fun isPinned(path: Path): Boolean = unsupported()
        override fun placeholderIdentity(path: Path): ByteArray? = unsupported()
        override fun updatePlaceholder(path: Path, placeholder: WindowsCloudPlaceholder, invalidateContent: Boolean, preserveSyncState: Boolean) = unsupported()
        override fun convertToPlaceholder(path: Path, placeholder: WindowsCloudPlaceholder) = unsupported()
        override fun markInSync(path: Path) = unsupported()
        override fun dehydrate(path: Path): Long = unsupported()

        private fun unsupported(): Nothing = error("Unexpected Cloud Files operation")
    }
}
