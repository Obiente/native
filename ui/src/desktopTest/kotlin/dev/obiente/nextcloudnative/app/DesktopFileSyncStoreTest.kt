package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
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

            assertEquals(pair, store.loadPair(pair.id).coordinator.pairs.single())
            store.deletePair(pair.id, pair.localRootId, deleteRoot = true)

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

            store.savePair(
                DesktopFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair)), listOf(root)),
                pair.id,
            )
            assertEquals(20_001, store.loadPair(pair.id).coordinator.pairs.single().baselines.size)

            val updated = pair.copy(
                baselines = baselines.dropLast(1) + baselines.last().copy(remoteEtag = "remote-updated"),
                lastScanEpochMillis = 42L,
            )
            store.savePair(
                DesktopFileSyncPersistedState(FileSyncCoordinatorState(listOf(updated)), listOf(root)),
                pair.id,
            )
            val restored = store.loadPair(pair.id).coordinator.pairs.single()
            assertEquals(42L, restored.lastScanEpochMillis)
            assertEquals("remote-updated", restored.baselines.last().remoteEtag)
            assertEquals(20_001, restored.baselines.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `pair and account reads do not decode unrelated baseline rows`() {
        val directory = Files.createTempDirectory("desktop-sync-scoped-read-").toFile()
        try {
            fun pair(id: String, rootId: String, path: String) = FileSyncPair(
                id = id,
                accountId = "account",
                localRootId = rootId,
                remoteRootPath = path,
                configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
                baselines = listOf(FileSyncBaseline("file.jpg", SyncEntryKind.File, "local", "remote")),
            )
            val primary = pair("primary-pair", "primary-root", "Primary")
            val secondary = pair("secondary-pair", "secondary-root", "Secondary")
            val roots = listOf(
                DesktopFileSyncRootRecord(primary.localRootId, directory.absolutePath, "Primary"),
                DesktopFileSyncRootRecord(secondary.localRootId, directory.absolutePath, "Secondary"),
            )
            val database = File(directory, "state.db")
            val store = DesktopFileSyncStore(database, legacyStateFile = null)
            val persisted = DesktopFileSyncPersistedState(
                FileSyncCoordinatorState(listOf(primary, secondary)),
                roots,
            )
            store.savePair(persisted, primary.id)
            store.savePair(persisted, secondary.id)

            BundledSQLiteDriver().open(database.absolutePath).use { connection ->
                connection.prepare("UPDATE sync_baselines SET record = ? WHERE pair_id = ?").use { statement ->
                    statement.bindBlob(1, byteArrayOf(0))
                    statement.bindText(2, secondary.id)
                    assertFalse(statement.step())
                }
            }

            assertEquals(primary, store.loadPair(primary.id).coordinator.pairs.single())
            val overview = store.load()
            assertEquals(2, overview.coordinator.pairs.size)
            assertTrue(overview.coordinator.pairs.all { it.baselines.isEmpty() && it.workItems.isEmpty() })
            assertEquals(1, store.loadAccount("account").completedCountsByPairId.getValue(secondary.id))
            assertFails { store.loadPair(secondary.id) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `account summaries and tray decode only their bounded work pages`() {
        val directory = Files.createTempDirectory("desktop-sync-work-page-").toFile()
        try {
            val pair = FileSyncPair(
                id = "paged-pair",
                accountId = "account",
                localRootId = "paged-root",
                remoteRootPath = "Pictures",
                configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
            )
            val conflicts = (0 until 6).map { index ->
                LocalSyncEntry("Attention/conflict-$index.jpg", SyncEntryKind.File, "local-conflict-$index")
            }
            val queued = (0 until 20).map { index ->
                LocalSyncEntry("Queue/file-${index.toString().padStart(2, '0')}.jpg", SyncEntryKind.File, "local-$index")
            }
            val coordinator = scanFileSyncPair(
                FileSyncCoordinatorState(listOf(pair)),
                pair.id,
                localEntries = conflicts + queued,
                remoteEntries = conflicts.mapIndexed { index, entry ->
                    RemoteSyncEntry(entry.relativePath, SyncEntryKind.File, "remote-conflict-$index")
                },
                nowEpochMillis = 10L,
            )
            val root = DesktopFileSyncRootRecord(pair.localRootId, directory.absolutePath, "Pictures")
            val database = File(directory, "state.db")
            val store = DesktopFileSyncStore(database, legacyStateFile = null)
            store.savePair(DesktopFileSyncPersistedState(coordinator, listOf(root)), pair.id)

            BundledSQLiteDriver().open(database.absolutePath).use { connection ->
                connection.prepare(
                    "UPDATE sync_work SET record = ? WHERE pair_id = ? AND relative_path = ?",
                ).use { statement ->
                    statement.bindBlob(1, byteArrayOf(0))
                    statement.bindText(2, pair.id)
                    statement.bindText(3, "Queue/file-19.jpg")
                    assertFalse(statement.step())
                }
            }

            val account = store.loadAccount("account", trayLimit = MAX_TRAY_ACTIVITY_ITEMS)
            val work = account.workByPairId.getValue(pair.id)
            assertEquals(20, work.readyCount)
            assertEquals(6, work.conflictCount)
            assertEquals(FILE_SYNC_CONFLICT_PAGE_SIZE, work.conflicts.size)
            assertEquals(MAX_TRAY_ACTIVITY_ITEMS, account.trayWorkItems.size)
            assertFails { store.loadPair(pair.id) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `version two databases gain bounded work index columns`() {
        val directory = Files.createTempDirectory("desktop-sync-work-index-migration-").toFile()
        try {
            val pair = FileSyncPair(
                id = "migration-pair",
                accountId = "account",
                localRootId = "migration-root",
                remoteRootPath = "Documents",
                configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
            )
            val coordinator = scanFileSyncPair(
                FileSyncCoordinatorState(listOf(pair)),
                pair.id,
                localEntries = listOf(LocalSyncEntry("report.pdf", SyncEntryKind.File, "local-report")),
                remoteEntries = emptyList(),
                nowEpochMillis = 10L,
            )
            val persistedPair = coordinator.pairs.single()
            val work = persistedPair.workItems.single()
            val database = File(directory, "state.db")
            BundledSQLiteDriver().open(database.absolutePath).use { connection ->
                connection.execSQL("PRAGMA foreign_keys = ON")
                connection.execSQL("CREATE TABLE sync_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
                connection.execSQL(
                    "CREATE TABLE sync_roots (" +
                        "id TEXT PRIMARY KEY NOT NULL, absolute_path TEXT NOT NULL, display_name TEXT NOT NULL)",
                )
                connection.execSQL("CREATE TABLE sync_pairs (id TEXT PRIMARY KEY NOT NULL, record BLOB NOT NULL)")
                connection.execSQL(
                    "CREATE TABLE sync_baselines (" +
                        "pair_id TEXT NOT NULL, relative_path TEXT NOT NULL, record BLOB NOT NULL, " +
                        "PRIMARY KEY(pair_id, relative_path), " +
                        "FOREIGN KEY(pair_id) REFERENCES sync_pairs(id) ON DELETE CASCADE)",
                )
                connection.execSQL(
                    "CREATE TABLE sync_work (" +
                        "pair_id TEXT NOT NULL, work_id INTEGER NOT NULL, record BLOB NOT NULL, " +
                        "PRIMARY KEY(pair_id, work_id), " +
                        "FOREIGN KEY(pair_id) REFERENCES sync_pairs(id) ON DELETE CASCADE)",
                )
                connection.prepare("INSERT INTO sync_metadata(key, value) VALUES ('schema_version', '2')")
                    .use { assertFalse(it.step()) }
                connection.prepare(
                    "INSERT INTO sync_roots(id, absolute_path, display_name) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.bindText(1, pair.localRootId)
                    statement.bindText(2, directory.absolutePath)
                    statement.bindText(3, "Documents")
                    assertFalse(statement.step())
                }
                connection.prepare("INSERT INTO sync_pairs(id, record) VALUES (?, ?)").use { statement ->
                    statement.bindText(1, pair.id)
                    statement.bindBlob(
                        2,
                        encodeFileSyncPairRecord(persistedPair.copy(baselines = emptyList(), workItems = emptyList())),
                    )
                    assertFalse(statement.step())
                }
                connection.prepare("INSERT INTO sync_work(pair_id, work_id, record) VALUES (?, ?, ?)")
                    .use { statement ->
                        statement.bindText(1, pair.id)
                        statement.bindLong(2, work.id)
                        statement.bindBlob(3, encodeFileSyncWorkRecord(work))
                        assertFalse(statement.step())
                    }
            }

            val store = DesktopFileSyncStore(database, legacyStateFile = null)
            val account = store.loadAccount("account", trayLimit = 1)
            assertEquals(1, account.workByPairId.getValue(pair.id).readyCount)
            assertEquals(work, account.trayWorkItems.single().workItem)
            assertEquals(work, store.loadPair(pair.id).coordinator.pairs.single().workItems.single())
            BundledSQLiteDriver().open(database.absolutePath).use { connection ->
                val version = connection.prepare(
                    "SELECT value FROM sync_metadata WHERE key = 'schema_version'",
                ).use { statement ->
                    assertTrue(statement.step())
                    statement.getText(0)
                }
                val columns = connection.prepare("PRAGMA table_info(sync_work)").use { statement ->
                    buildSet {
                        while (statement.step()) add(statement.getText(1))
                    }
                }
                assertEquals("4", version)
                assertTrue(setOf("state", "relative_path", "detail").all { it in columns })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `verification progress is stored in rows instead of the bounded pair record`() {
        val directory = Files.createTempDirectory("desktop-sync-progress-rows-").toFile()
        try {
            val progress = (0 until 5_000).map { index ->
                FileSyncContentVerificationProgress(
                    candidate = FileSyncContentVerificationCandidate(
                        relativePath = "Archive/$index-${"x".repeat(900)}.bin",
                        localRevision = "local-$index",
                        remoteEtag = "remote-$index",
                        expectedSizeBytes = 16L * 1024L * 1024L,
                    ),
                    verifiedBytes = 8L * 1024L * 1024L,
                    aggregateHash = EMPTY_FILE_SYNC_IDENTITY_AGGREGATE,
                )
            }
            val pair = FileSyncPair(
                id = "progress-pair",
                accountId = "account",
                localRootId = "progress-root",
                remoteRootPath = "Archive",
                configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
                contentVerificationProgress = progress,
            )
            val root = DesktopFileSyncRootRecord(pair.localRootId, directory.absolutePath, "Archive")
            val database = File(directory, "state.db")
            val store = DesktopFileSyncStore(database, legacyStateFile = null)

            store.savePair(
                DesktopFileSyncPersistedState(FileSyncCoordinatorState(listOf(pair)), listOf(root)),
                pair.id,
            )

            assertEquals(
                progress.sortedBy { it.candidate.relativePath },
                store.loadPair(pair.id).coordinator.pairs.single().contentVerificationProgress,
            )
            BundledSQLiteDriver().open(database.absolutePath).use { connection ->
                val pairRecordBytes = connection.prepare(
                    "SELECT length(record) FROM sync_pairs WHERE id = ?",
                ).use { statement ->
                    statement.bindText(1, pair.id)
                    assertTrue(statement.step())
                    statement.getLong(0)
                }
                val progressRows = connection.prepare(
                    "SELECT COUNT(*) FROM sync_content_verification WHERE pair_id = ?",
                ).use { statement ->
                    statement.bindText(1, pair.id)
                    assertTrue(statement.step())
                    statement.getLong(0)
                }
                assertTrue(pairRecordBytes < 64L * 1024L)
                assertEquals(progress.size.toLong(), progressRows)
            }
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
            store.savePair(persisted, pair.id)

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

            val restored = DesktopFileSyncStore(File(directory, "state.db"), legacyStateFile = null)
                .loadPair(pair.id)
            assertTrue(restored.coordinator.pairs.single().workItems.isEmpty())
            assertEquals(success.synchronizedBaselines, restored.coordinator.pairs.single().baselines)
            assertEquals(listOf(root), restored.roots)
            BundledSQLiteDriver().open(File(directory, "state.db").absolutePath).use { connection ->
                val baselineCount = connection.prepare(
                    "SELECT value FROM sync_metadata WHERE key = ?",
                ).use { statement ->
                    statement.bindText(1, "baseline_count:${pair.id}")
                    assertTrue(statement.step())
                    statement.getText(0)
                }
                assertEquals("1", baselineCount)
            }
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

            store.savePair(expected, pair.id)
            val restored = store.loadPair(pair.id)
            val account = store.loadAccount(pair.accountId, trayLimit = 1)

            assertEquals(
                FileSyncExecutionState.Failed,
                restored.coordinator.pairs.single().workItems.single().state,
            )
            assertEquals(
                INTERRUPTED_FILE_SYNC_FAILURE_MESSAGE,
                restored.coordinator.pairs.single().workItems.single().failureMessage,
            )
            assertEquals(0, account.workByPairId.getValue(pair.id).readyCount)
            assertEquals(1, account.workByPairId.getValue(pair.id).failedCount)
            assertEquals(FileSyncExecutionState.Failed, account.trayWorkItems.single().workItem.state)
            assertEquals(INTERRUPTED_FILE_SYNC_FAILURE_MESSAGE, account.trayWorkItems.single().workItem.failureMessage)
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
