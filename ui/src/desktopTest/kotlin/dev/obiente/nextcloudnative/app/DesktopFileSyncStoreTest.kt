package dev.obiente.nextcloudnative.app

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DesktopFileSyncStoreTest {
    @Test
    fun `legacy json state imports once into the transactional database`() {
        val directory = Files.createTempDirectory("desktop-sync-legacy-import-").toFile()
        try {
            val pair = FileSyncPair(
                id = "legacy-pair",
                accountId = "account",
                localRootId = "legacy-root",
                remoteRootPath = "Documents",
                configuration = FileSyncConfiguration(deviceLabel = "Linux workstation"),
                baselines = listOf(
                    FileSyncBaseline("Notes/readme.md", SyncEntryKind.File, "local-1", "remote-1"),
                ),
            )
            val legacy = File(directory, "file-sync-state.json")
            val coordinator = Base64.getEncoder().encodeToString(
                encodeFileSyncCoordinatorSnapshot(FileSyncCoordinatorState(listOf(pair))),
            )
            legacy.writeText(Json.encodeToString(buildJsonObject {
                put("schemaVersion", 1)
                put("coordinatorBase64", coordinator)
                put("roots", buildJsonArray {
                    add(buildJsonObject {
                        put("id", "legacy-root")
                        put("absolutePath", directory.absolutePath)
                        put("displayName", "Documents")
                    })
                })
            }))
            val database = File(directory, "file-sync-state-v2.db")
            val store = DesktopFileSyncStore(database, legacy)

            assertEquals(pair, store.load().coordinator.pairs.single())
            store.save(DesktopFileSyncPersistedState())

            assertTrue(legacy.isFile)
            assertTrue(DesktopFileSyncStore(database, legacy).load().coordinator.pairs.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sqlite store preserves more than the legacy folder entry ceiling`() {
        val directory = Files.createTempDirectory("desktop-sync-large-store-").toFile()
        try {
            val baselines = (0..20_000).map { index ->
                val path = "Archive/file-${index.toString().padStart(5, '0')}.jpg"
                FileSyncBaseline(path, SyncEntryKind.File, "local-$index", "remote-$index")
            }
            val pair = FileSyncPair(
                id = "large-pair",
                accountId = "account",
                localRootId = "large-root",
                remoteRootPath = "Archive",
                configuration = FileSyncConfiguration(deviceLabel = "Linux workstation"),
                baselines = baselines,
            )
            val root = DesktopFileSyncRootRecord("large-root", directory.absolutePath, "Archive")
            val store = DesktopFileSyncStore(File(directory, "state.db"), legacyStateFile = null)

            store.save(DesktopFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair)), listOf(root)))
            assertEquals(20_001, store.load().coordinator.pairs.single().baselines.size)

            val updated = pair.copy(
                baselines = baselines.dropLast(1) + baselines.last().copy(remoteEtag = "remote-updated"),
                lastScanEpochMillis = 42L,
            )
            store.save(DesktopFileSyncPersistedState(FileSyncCoordinatorState(listOf(updated)), listOf(root)))
            val restored = store.load().coordinator.pairs.single()
            assertEquals(42L, restored.lastScanEpochMillis)
            assertEquals("remote-updated", restored.baselines.last().remoteEtag)
            assertEquals(20_001, restored.baselines.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `execution transitions update only their durable work and baseline rows`() {
        val directory = Files.createTempDirectory("desktop-sync-transition-").toFile()
        try {
            val pair = FileSyncPair(
                id = "transition-pair",
                accountId = "account",
                localRootId = "transition-root",
                remoteRootPath = "Documents",
                configuration = FileSyncConfiguration(deviceLabel = "Linux workstation"),
            )
            val scanned = scanFileSyncPair(
                FileSyncCoordinatorState(listOf(pair)),
                pair.id,
                localEntries = listOf(LocalSyncEntry("Notes/readme.md", SyncEntryKind.File, "local-1")),
                remoteEntries = emptyList(),
                nowEpochMillis = 10L,
            )
            val claim = claimNextFileSyncOperation(scanned, pair.id, nowEpochMillis = 20L)
            val running = claim.state.pairs.single().workItems.single()
            val root = DesktopFileSyncRootRecord(pair.localRootId, directory.absolutePath, "Documents")
            val store = DesktopFileSyncStore(File(directory, "state.db"), legacyStateFile = null)
            var persisted = DesktopFileSyncPersistedState(claim.state, listOf(root))
            store.save(persisted)

            val success = FileSyncExecutionSuccess(
                synchronizedBaselines = listOf(
                    FileSyncBaseline("Notes/readme.md", SyncEntryKind.File, "local-1", "remote-1"),
                ),
            )
            persisted = persisted.copy(
                coordinator = completeFileSyncOperation(
                    persisted.coordinator,
                    pair.id,
                    running.id,
                    success,
                ),
            )
            store.saveExecutionTransition(
                state = persisted,
                pairId = pair.id,
                workId = running.id,
                workItem = null,
                synchronizedBaselines = success.synchronizedBaselines,
            )

            val restored = DesktopFileSyncStore(File(directory, "state.db"), legacyStateFile = null).load()
            assertTrue(restored.coordinator.pairs.single().workItems.isEmpty())
            assertEquals(success.synchronizedBaselines, restored.coordinator.pairs.single().baselines)
            assertEquals(listOf(root), restored.roots)
        } finally {
            directory.deleteRecursively()
        }
    }

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
