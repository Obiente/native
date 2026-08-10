package dev.obiente.nextcloudnative.app

import java.io.File
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
    fun preservedRootRecordSurvivesReloadUntilAcknowledged() {
        val nodeName = "windows-preserved-root-test-${UUID.randomUUID()}"
        val preferences = Preferences.userRoot().node(nodeName)
        val accountId = "c".repeat(64)
        val preservedRoot = Files.createTempDirectory("windows-preserved-root")
        try {
            persistWindowsCloudFilesPreservedRoot(preferences, accountId, preservedRoot)

            val reloadedPreferences = Preferences.userRoot().node(nodeName)
            assertEquals(
                preservedRoot.toAbsolutePath().normalize(),
                persistedWindowsCloudFilesPreservedRoot(reloadedPreferences, accountId),
            )

            acknowledgeWindowsCloudFilesPreservedRoot(reloadedPreferences, accountId)

            assertEquals(null, persistedWindowsCloudFilesPreservedRoot(preferences, accountId))
            assertTrue(Files.isDirectory(preservedRoot))
        } finally {
            preferences.removeNode()
            preservedRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun temporarilyMissingPreservedRootDoesNotClearItsRecoveryRecord() {
        val preferences = Preferences.userRoot().node("windows-missing-preserved-root-test-${UUID.randomUUID()}")
        val accountId = "d".repeat(64)
        val preservedRoot = Files.createTempDirectory("windows-missing-preserved-root")
        try {
            persistWindowsCloudFilesPreservedRoot(preferences, accountId, preservedRoot)
            preservedRoot.toFile().deleteRecursively()

            assertEquals(
                preservedRoot.toAbsolutePath().normalize(),
                persistedWindowsCloudFilesPreservedRoot(preferences, accountId),
            )
        } finally {
            preferences.removeNode()
            preservedRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun aSecondRecoveryCannotReplaceAnUnacknowledgedRecord() {
        val preferences = Preferences.userRoot().node("windows-second-preserved-root-test-${UUID.randomUUID()}")
        val accountId = "e".repeat(64)
        val firstRoot = Files.createTempDirectory("windows-first-preserved-root")
        val secondRoot = Files.createTempDirectory("windows-second-preserved-root")
        try {
            persistWindowsCloudFilesPreservedRoot(preferences, accountId, firstRoot)

            kotlin.test.assertFailsWith<IllegalArgumentException> {
                persistWindowsCloudFilesPreservedRoot(preferences, accountId, secondRoot)
            }

            assertEquals(
                firstRoot.toAbsolutePath().normalize(),
                persistedWindowsCloudFilesPreservedRoot(preferences, accountId),
            )
        } finally {
            preferences.removeNode()
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun recoveryNoticesRemainScopedToTheirAccounts() {
        val preferences = Preferences.userRoot().node("windows-account-recovery-notice-test-${UUID.randomUUID()}")
        val firstAccountId = "1".repeat(64)
        val secondAccountId = "2".repeat(64)
        val firstRoot = Files.createTempDirectory("windows-first-recovery-notice")
        val secondRoot = Files.createTempDirectory("windows-second-recovery-notice")
        try {
            persistWindowsCloudFilesPreservedRoot(preferences, firstAccountId, firstRoot)
            persistWindowsCloudFilesPreservedRoot(preferences, secondAccountId, secondRoot)

            val firstNotice = requireNotNull(persistedWindowsCloudFilesRecoveryNotice(preferences, firstAccountId))
            val secondNotice = requireNotNull(persistedWindowsCloudFilesRecoveryNotice(preferences, secondAccountId))
            assertTrue(firstNotice.contains(firstRoot.toAbsolutePath().normalize().toString()))
            assertFalse(firstNotice.contains(secondRoot.toAbsolutePath().normalize().toString()))
            assertTrue(secondNotice.contains(secondRoot.toAbsolutePath().normalize().toString()))
            assertFalse(secondNotice.contains(firstRoot.toAbsolutePath().normalize().toString()))

            acknowledgeWindowsCloudFilesPreservedRoot(preferences, firstAccountId)

            assertEquals(null, persistedWindowsCloudFilesRecoveryNotice(preferences, firstAccountId))
            assertEquals(secondNotice, persistedWindowsCloudFilesRecoveryNotice(preferences, secondAccountId))
        } finally {
            preferences.removeNode()
            firstRoot.toFile().deleteRecursively()
            secondRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun recoveryNoticeReportsPreservationWithoutClaimingRepair() {
        val preservedRoot = Files.createTempDirectory("windows-preservation-notice")
        try {
            val notice = windowsCloudFilesRecoveryNoticeMessage(preservedRoot)

            assertTrue(notice.contains("preserved existing local data"))
            assertFalse(notice.contains("repaired", ignoreCase = true))
        } finally {
            preservedRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun loadsOnlyLiveAccountScopedRecoveryRoots() {
        val preferences = Preferences.userRoot().node("windows-recovery-root-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-recovery-root-home").toFile()
        val liveAccountId = "a".repeat(64)
        val missingAccountId = "b".repeat(64)
        val liveRoot = home.resolve("live").apply { assertTrue(mkdirs()) }
        try {
            preferences.put(windowsCloudFilesRootPreferenceKey(liveAccountId), liveRoot.absolutePath)
            preferences.put(
                windowsCloudFilesRootPreferenceKey(missingAccountId),
                home.resolve("missing").absolutePath,
            )
            preferences.put("wcfr.future-format", liveRoot.absolutePath)

            assertEquals(
                mapOf(liveAccountId to liveRoot.toPath()),
                persistedWindowsCloudFilesRecoveryRoots(preferences),
            )
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    @Test
    fun pagesAcrossAllRecoveryRootsInsteadOfRepeatingTheFirstPage() {
        val roots = (0 until 18).associate { index ->
            index.toString(16).padStart(64, '0') to File("C:/recovery/$index").toPath()
        }

        val first = pageWindowsCloudFilesRecoveryRoots(roots, startAfterAccountId = null)
        val second = pageWindowsCloudFilesRecoveryRoots(
            roots,
            startAfterAccountId = first.keys.last(),
        )

        assertEquals(16, first.size)
        assertEquals(16, second.size)
        assertTrue(roots.keys.drop(16).all(second::containsKey))
        assertFalse(first.keys == second.keys)
    }

    @Test
    fun cloudFilesCleanupTreatsOnlyMissingRootsAsAlreadyAbsent() {
        assertTrue(isWindowsCloudFilesRootAbsentResult(0xC000CF13.toInt(), rootMissing = false))
        assertTrue(isWindowsCloudFilesRootAbsentResult(0xD000CF13.toInt(), rootMissing = false))
        assertTrue(isWindowsCloudFilesRootAbsentResult(0x80070186.toInt(), rootMissing = false))
        assertTrue(isWindowsCloudFilesRootAbsentResult(0x80070002.toInt(), rootMissing = true))
        assertTrue(isWindowsCloudFilesRootAbsentResult(0x80070003.toInt(), rootMissing = true))
        assertFalse(isWindowsCloudFilesRootAbsentResult(0x80070003.toInt(), rootMissing = false))
        assertFalse(isWindowsCloudFilesRootAbsentResult(0x80070005.toInt(), rootMissing = true))
        assertFalse(isWindowsCloudFilesRootAbsentResult(0x8007017C.toInt(), rootMissing = true))
    }

    @Test
    fun cloudFilesDisconnectTreatsOnlyAnAlreadyMissingConnectionAsAbsent() {
        assertTrue(isWindowsCloudFilesConnectionAbsentResult(0x80070057.toInt()))
        assertFalse(isWindowsCloudFilesConnectionAbsentResult(0x80070005.toInt()))
        assertFalse(isWindowsCloudFilesConnectionAbsentResult(0x8007017C.toInt()))
    }

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

    @Test
    fun uninstallCleansTheCurrentRegistrationBeforeAStaleLegacyPreference() {
        val preferences = Preferences.userRoot().node("windows-stale-legacy-pointer-test-${UUID.randomUUID()}")
        val home = Files.createTempDirectory("windows-stale-legacy-pointer-home").toFile()
        val session = NextcloudSession("https://cloud.invalid", "alice", "unused")
        val accountId = desktopFileCacheAccountId(session)
        val currentRoot = desktopWindowsCloudFilesRoot(accountId, home).toPath()
        val legacyRoot = home.resolve("Nextcloud Native").resolve(accountId).toPath()
        val api = RecordingWindowsCloudFilesApi().apply {
            prerequisiteRoot = currentRoot
            dependentRoot = legacyRoot
        }
        try {
            preferences.put("server", session.serverUrl)
            preferences.put("login", session.loginName)
            preferences.put("windows-cloud-files-root", legacyRoot.toString())

            unregisterWindowsCloudFilesRootForUninstall(preferences, home) { api }

            assertEquals(listOf(currentRoot, legacyRoot), api.unregisteredRoots)
            assertEquals(null, preferences.get("windows-cloud-files-root", null))
            assertTrue(api.closed)
        } finally {
            preferences.removeNode()
            home.deleteRecursively()
        }
    }

    private class RecordingWindowsCloudFilesApi : WindowsCloudFilesApi {
        val unregisteredRoots = mutableListOf<Path>()
        val unregisteredRoot: Path? get() = unregisteredRoots.lastOrNull()
        var prerequisiteRoot: Path? = null
        var dependentRoot: Path? = null
        var closed = false

        override fun unregisterSyncRoot(root: Path) {
            if (root == dependentRoot && prerequisiteRoot !in unregisteredRoots) {
                error("The stable registration still points at another candidate root.")
            }
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
