package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFileSyncStoreTest {
    @Test
    fun `desktop store preserves advanced policy and recovers running work`() {
        val directory = Files.createTempDirectory("desktop-sync-store-").toFile()
        try {
            val pair = FileSyncPair(
                id = "pair",
                accountId = "account",
                localRootId = "root",
                remoteRootPath = "Photos",
                configuration = FileSyncConfiguration(
                    deviceLabel = "Linux workstation",
                    selectedPaths = listOf("2026/July"),
                    ignoredPatterns = listOf("*.part"),
                    priorityRules = listOf(
                        FileSyncPriorityRule("**/*.raf"),
                        FileSyncPriorityRule("**/*.jpg"),
                    ),
                ),
            )
            var coordinator = scanFileSyncPair(
                FileSyncCoordinatorState(listOf(pair)),
                "pair",
                localEntries = listOf(LocalSyncEntry("2026", SyncEntryKind.Directory, "dir")),
                remoteEntries = emptyList(),
                nowEpochMillis = 10L,
            )
            coordinator = claimNextFileSyncOperation(coordinator, "pair", 20L).state
            val expected = DesktopFileSyncPersistedState(
                coordinator = coordinator,
                roots = listOf(DesktopFileSyncRootRecord("root", directory.absolutePath, "Photos")),
            )
            val store = DesktopFileSyncStore(File(directory, "state.json"))

            store.save(expected)
            val restored = store.load()

            assertEquals(
                FileSyncExecutionState.Ready,
                restored.coordinator.pairs.single().workItems.single().state,
            )
            assertEquals(pair.configuration, restored.coordinator.pairs.single().configuration)
            assertEquals(expected.roots, restored.roots)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sync mapping overlap detects local and remote ancestry`() {
        val root = Files.createTempDirectory("desktop-sync-overlap-")
        try {
            val child = Files.createDirectories(root.resolve("child"))
            val sibling = Files.createDirectories(root.resolveSibling(root.fileName.toString() + "-sibling"))
            assertTrue(desktopSyncRootsOverlap(root.toString(), child.toString()))
            assertFalse(desktopSyncRootsOverlap(root.toString(), sibling.toString()))
            assertTrue(desktopSyncRemoteRootsOverlap("", "Photos"))
            assertTrue(desktopSyncRemoteRootsOverlap("Photos", "Photos/RAW"))
            assertFalse(desktopSyncRemoteRootsOverlap("Photos", "Documents"))
            sibling.toFile().deleteRecursively()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
