package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFileSyncStoreTest {
    @Test
    fun `download capacity includes reserve and both same-store copies`() {
        assertEquals(250L, requiredDesktopDownloadFreeBytes(100L, 50L, sameStore = true))
        assertEquals(150L, requiredDesktopDownloadFreeBytes(100L, 50L, sameStore = false))
        assertEquals(Long.MAX_VALUE, requiredDesktopDownloadFreeBytes(Long.MAX_VALUE, 1L, sameStore = true))
    }

    @Test
    fun `exclusive store transaction serializes independent engine instances`() {
        val directory = Files.createTempDirectory("desktop-sync-lock-").toFile()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val stateFile = File(directory, "state.json")
            val first = DesktopFileSyncStore(stateFile)
            val second = DesktopFileSyncStore(stateFile)
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val firstFuture = executor.submit {
                first.withExclusiveAccess {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val secondFuture = executor.submit {
                second.withExclusiveAccess { secondEntered.countDown() }
            }

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            firstFuture.get(5, TimeUnit.SECONDS)
            secondFuture.get(5, TimeUnit.SECONDS)
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            directory.deleteRecursively()
        }
    }

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

    @Test
    fun `local overlap is global while remote overlap is scoped to one account`() {
        val root = Files.createTempDirectory("desktop-sync-account-overlap-")
        try {
            val child = Files.createDirectories(root.resolve("child"))
            val sibling = Files.createDirectories(root.resolveSibling(root.fileName.toString() + "-sibling"))

            assertTrue(
                desktopSyncMappingsOverlap(
                    "account-a", "account-b",
                    root.toString(), child.toString(),
                    "Photos", "Documents",
                ),
            )
            assertFalse(
                desktopSyncMappingsOverlap(
                    "account-a", "account-b",
                    root.toString(), sibling.toString(),
                    "Photos", "Photos/RAW",
                ),
            )
            assertTrue(
                desktopSyncMappingsOverlap(
                    "account-a", "account-a",
                    root.toString(), sibling.toString(),
                    "Photos", "Photos/RAW",
                ),
            )
            sibling.toFile().deleteRecursively()
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
