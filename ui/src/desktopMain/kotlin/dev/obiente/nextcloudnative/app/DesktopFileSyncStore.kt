package dev.obiente.nextcloudnative.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal data class DesktopFileSyncRootRecord(
    val id: String,
    val absolutePath: String,
    val displayName: String,
)

internal data class DesktopFileSyncPersistedState(
    val coordinator: FileSyncCoordinatorState = FileSyncCoordinatorState(),
    val roots: List<DesktopFileSyncRootRecord> = emptyList(),
) {
    init {
        require(roots.size <= 64)
        require(roots.map(DesktopFileSyncRootRecord::id).distinct().size == roots.size)
        require(roots.all {
            it.id.isNotBlank() && it.id.length <= 256 &&
                it.absolutePath.isNotBlank() && it.absolutePath.length <= 8_192 &&
                it.displayName.isNotBlank() && it.displayName.length <= 256
        })
        require(coordinator.pairs.all { pair -> roots.any { it.id == pair.localRootId } })
    }
}

internal data class DesktopFileSyncAccountView(
    val state: DesktopFileSyncPersistedState,
    val completedCountsByPairId: Map<String, Int>,
    val workByPairId: Map<String, DesktopFileSyncWorkOverview>,
    val trayWorkItems: List<DesktopFileSyncScopedWorkItem>,
)

internal data class DesktopFileSyncWorkOverview(
    val readyCount: Int,
    val runningCount: Int,
    val conflictCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val conflicts: List<FileSyncWorkItem>,
    val skippedReasons: List<String>,
)

internal data class DesktopFileSyncScopedWorkItem(
    val pairId: String,
    val workItem: FileSyncWorkItem,
)

internal class DesktopFileSyncStore(
    private val stateFile: File = desktopFileSyncDatabaseFile(),
    private val legacyStateFile: File? = stateFile.parentFile
        ?.resolve("file-sync-state.json")
        ?.takeIf { stateFile.name == "file-sync-state-v2.db" },
) {
    private val transactionKey = runCatching(stateFile::getCanonicalPath).getOrElse {
        stateFile.toPath().toAbsolutePath().normalize().toString()
    }
    /** Serializes one complete load-mutate-save transaction across app processes. */
    fun <T> withExclusiveAccess(block: () -> T): T = processLocks
        .computeIfAbsent(transactionKey) { ReentrantLock() }
        .withLock {
            val parent = requireNotNull(stateFile.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Could not create desktop folder sync storage." }
            val lockFile = File(parent, "${stateFile.name}.lock")
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use { block() }
            }
        }

    @Synchronized
    fun load(): DesktopFileSyncPersistedState {
        val connection = openDatabase()
        return try {
            initializeSchema(connection)
            migrateLegacyState(connection)
            readOverview(connection)
        } finally {
            connection.close()
        }
    }

    @Synchronized
    fun loadPair(pairId: String): DesktopFileSyncPersistedState {
        require(pairId.isNotBlank() && pairId.length <= 256)
        val connection = openDatabase()
        return try {
            initializeSchema(connection)
            migrateLegacyState(connection)
            readPairState(connection, pairId)
        } finally {
            connection.close()
        }
    }

    @Synchronized
    fun loadAccount(accountId: String, trayLimit: Int = 0): DesktopFileSyncAccountView {
        require(accountId.isNotBlank() && accountId.length <= 256)
        require(trayLimit in 0..MAX_TRAY_ACTIVITY_ITEMS)
        val connection = openDatabase()
        return try {
            initializeSchema(connection)
            migrateLegacyState(connection)
            val pairs = readPairRecords(connection).filter { it.accountId == accountId }
            val rootIds = pairs.mapTo(mutableSetOf(), FileSyncPair::localRootId)
            val roots = readRoots(connection).filter { it.id in rootIds }
            val completedCounts = pairs.associate { pair ->
                pair.id to countPairRows(connection, "sync_baselines", pair.id, MAX_FILE_SYNC_ENTRIES)
            }
            val work = pairs.associate { pair -> pair.id to readWorkOverview(connection, pair.id) }
            DesktopFileSyncAccountView(
                state = DesktopFileSyncPersistedState(
                    coordinator = FileSyncCoordinatorState(pairs),
                    roots = roots,
                ),
                completedCountsByPairId = completedCounts,
                workByPairId = work,
                trayWorkItems = if (trayLimit == 0) {
                    emptyList()
                } else {
                    readTrayWork(connection, pairs.map(FileSyncPair::id), trayLimit)
                },
            )
        } finally {
            connection.close()
        }
    }

    @Synchronized
    fun savePair(state: DesktopFileSyncPersistedState, pairId: String) {
        DesktopFileSyncPersistedState(state.coordinator, state.roots)
        val pair = state.coordinator.pairs.singleOrNull { it.id == pairId }
            ?: error("The desktop folder sync pair is not present in the scoped state.")
        val root = state.roots.singleOrNull { it.id == pair.localRootId }
            ?: error("The desktop folder sync root is not present in the scoped state.")
        val connection = openDatabase()
        try {
            initializeSchema(connection)
            migrateLegacyState(connection)
            val before = readPairState(connection, pairId).coordinator.pairs.singleOrNull()
            transaction(connection) {
                upsertRootRecord(connection, root)
                upsertPairRecord(connection, pair)
                persistBaselines(connection, pairId, before?.baselines.orEmpty(), pair.baselines)
                persistWork(connection, pairId, before?.workItems.orEmpty(), pair.workItems)
                putMetadata(connection, baselineCountKey(pairId), pair.baselines.size.toString())
            }
        } finally {
            connection.close()
        }
    }

    @Synchronized
    fun deletePair(pairId: String, rootId: String, deleteRoot: Boolean) {
        require(pairId.isNotBlank() && pairId.length <= 256)
        require(rootId.isNotBlank() && rootId.length <= 256)
        val connection = openDatabase()
        try {
            initializeSchema(connection)
            migrateLegacyState(connection)
            transaction(connection) {
                delete(connection, "DELETE FROM sync_pairs WHERE id = ?", pairId)
                delete(connection, "DELETE FROM sync_metadata WHERE key = ?", baselineCountKey(pairId))
                if (deleteRoot) delete(connection, "DELETE FROM sync_roots WHERE id = ?", rootId)
            }
        } finally {
            connection.close()
        }
    }

    /** Persists one claimed, completed, or failed transfer without diffing the entire large-tree snapshot. */
    @Synchronized
    fun saveExecutionTransition(
        state: DesktopFileSyncPersistedState,
        pairId: String,
        workId: Long,
        workItem: FileSyncWorkItem?,
        synchronizedBaselines: List<FileSyncBaseline> = emptyList(),
        removedBaselinePaths: Set<String> = emptySet(),
    ) {
        DesktopFileSyncPersistedState(state.coordinator, state.roots)
        val pair = state.coordinator.pairs.firstOrNull { it.id == pairId }
            ?: error("The desktop folder sync pair no longer exists.")
        require(workItem == null || workItem.id == workId)
        require(synchronizedBaselines.map(FileSyncBaseline::relativePath).distinct().size == synchronizedBaselines.size)
        require(synchronizedBaselines.none { it.relativePath in removedBaselinePaths })
        val connection = openDatabase()
        try {
            initializeSchema(connection)
            migrateLegacyState(connection)
            transaction(connection) {
                val synchronizedPaths = synchronizedBaselines.map(FileSyncBaseline::relativePath).toSet()
                val resultingBaselineCount = if (synchronizedPaths.isEmpty() && removedBaselinePaths.isEmpty()) {
                    null
                } else {
                    requireBaselineCapacity(connection, pairId, synchronizedPaths, removedBaselinePaths)
                }
                upsertPairRecord(connection, pair)
                persistWorkRecord(connection, pairId, workId, workItem)
                synchronizedBaselines.forEach { baseline -> upsertBaselineRecord(connection, pairId, baseline) }
                removedBaselinePaths.forEach { path ->
                    delete(connection, "DELETE FROM sync_baselines WHERE pair_id = ? AND relative_path = ?", pairId, path)
                }
                resultingBaselineCount?.let { count ->
                    putMetadata(connection, baselineCountKey(pairId), count.toString())
                }
            }
        } finally {
            connection.close()
        }
    }

    private fun requireBaselineCapacity(
        connection: SQLiteConnection,
        pairId: String,
        synchronizedPaths: Set<String>,
        removedPaths: Set<String>,
    ): Int {
        val affectedPaths = synchronizedPaths + removedPaths
        val currentCount = metadataValue(connection, baselineCountKey(pairId))?.toIntOrNull()
            ?: countPairRows(connection, "sync_baselines", pairId, MAX_FILE_SYNC_ENTRIES)
        require(currentCount in 0..MAX_FILE_SYNC_ENTRIES)
        val replacedCount = affectedPaths.count { path ->
            connection.prepare(
                "SELECT 1 FROM sync_baselines WHERE pair_id = ? AND relative_path = ? LIMIT 1",
            ).use { statement ->
                statement.bindText(1, pairId)
                statement.bindText(2, path)
                statement.step()
            }
        }
        val resultingCount = currentCount - replacedCount + synchronizedPaths.size
        require(resultingCount <= MAX_FILE_SYNC_ENTRIES) {
            "The synchronized result would exceed the folder baseline limit."
        }
        return resultingCount
    }

    private fun openDatabase(): SQLiteConnection {
        val parent = requireNotNull(stateFile.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not create desktop folder sync storage." }
        return BundledSQLiteDriver().open(stateFile.absolutePath)
    }

    private fun initializeSchema(connection: SQLiteConnection) {
        connection.execSQL("PRAGMA foreign_keys = ON")
        connection.execSQL("PRAGMA journal_mode = WAL")
        connection.execSQL("PRAGMA synchronous = FULL")
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_roots (" +
                "id TEXT PRIMARY KEY NOT NULL, absolute_path TEXT NOT NULL, display_name TEXT NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_pairs (id TEXT PRIMARY KEY NOT NULL, record BLOB NOT NULL)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_baselines (" +
                "pair_id TEXT NOT NULL, relative_path TEXT NOT NULL, record BLOB NOT NULL, " +
                "PRIMARY KEY(pair_id, relative_path), " +
                "FOREIGN KEY(pair_id) REFERENCES sync_pairs(id) ON DELETE CASCADE)",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS sync_work (" +
                "pair_id TEXT NOT NULL, work_id INTEGER NOT NULL, state TEXT NOT NULL, " +
                "relative_path TEXT NOT NULL, detail TEXT, record BLOB NOT NULL, " +
                "PRIMARY KEY(pair_id, work_id), " +
                "FOREIGN KEY(pair_id) REFERENCES sync_pairs(id) ON DELETE CASCADE)",
        )
        val schemaVersion = metadataValue(connection, SCHEMA_VERSION_KEY)
        when (schemaVersion) {
            null -> putMetadata(connection, SCHEMA_VERSION_KEY, DATABASE_SCHEMA_VERSION)
            PREVIOUS_DATABASE_SCHEMA_VERSION -> migrateWorkIndexColumns(connection)
            DATABASE_SCHEMA_VERSION -> Unit
            else -> require(false) {
                "The desktop folder sync database version is unsupported."
            }
        }
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS sync_work_pair_state_path " +
                "ON sync_work(pair_id, state, relative_path, work_id)",
        )
    }

    private fun migrateWorkIndexColumns(connection: SQLiteConnection) {
        transaction(connection) {
            connection.execSQL("ALTER TABLE sync_work ADD COLUMN state TEXT")
            connection.execSQL("ALTER TABLE sync_work ADD COLUMN relative_path TEXT")
            connection.execSQL("ALTER TABLE sync_work ADD COLUMN detail TEXT")
            connection.prepare("SELECT pair_id, work_id, record FROM sync_work ORDER BY pair_id, work_id")
                .use { source ->
                    connection.prepare(
                        "UPDATE sync_work SET state = ?, relative_path = ?, detail = ? " +
                            "WHERE pair_id = ? AND work_id = ?",
                    ).use { update ->
                        while (source.step()) {
                            val pairId = source.getText(0)
                            val workId = source.getLong(1)
                            val work = decodeFileSyncWorkRecord(source.getBlob(2))
                            require(work.id == workId)
                            update.bindText(1, work.state.name)
                            update.bindText(2, work.relativePath)
                            work.indexDetail()?.let { update.bindText(3, it) } ?: update.bindNull(3)
                            update.bindText(4, pairId)
                            update.bindLong(5, workId)
                            check(!update.step())
                            update.reset()
                            update.clearBindings()
                        }
                    }
                }
            val missingIndexValues = connection.prepare(
                "SELECT COUNT(*) FROM sync_work WHERE state IS NULL OR relative_path IS NULL",
            ).use { statement ->
                check(statement.step())
                statement.getLong(0)
            }
            check(missingIndexValues == 0L) { "Could not index the existing desktop folder sync work." }
            putMetadata(connection, SCHEMA_VERSION_KEY, DATABASE_SCHEMA_VERSION)
        }
    }

    private fun migrateLegacyState(connection: SQLiteConnection) {
        if (metadataValue(connection, LEGACY_IMPORT_KEY) != null) return
        val legacy = legacyStateFile?.takeIf(File::isFile)?.let(::decodeLegacyState)
        transaction(connection) {
            if (legacy != null) persistDifference(connection, DesktopFileSyncPersistedState(), legacy)
            putMetadata(connection, LEGACY_IMPORT_KEY, if (legacy == null) "absent" else "imported")
        }
    }

    private fun readOverview(connection: SQLiteConnection): DesktopFileSyncPersistedState =
        DesktopFileSyncPersistedState(
            coordinator = FileSyncCoordinatorState(readPairRecords(connection)),
            roots = readRoots(connection),
        )

    private fun readPairState(connection: SQLiteConnection, pairId: String): DesktopFileSyncPersistedState {
        val pair = readPairRecords(connection, pairId).singleOrNull()
        val roots = pair?.let { selected ->
            readRoots(connection).filter { it.id == selected.localRootId }
        }.orEmpty()
        return DesktopFileSyncPersistedState(
            coordinator = recoverInterruptedFileSyncWork(
                FileSyncCoordinatorState(
                    pair?.let {
                        listOf(
                            it.copy(
                                baselines = readBaselines(connection, pairId),
                                workItems = readWork(connection, pairId),
                            ),
                        )
                    }.orEmpty(),
                ),
            ),
            roots = roots,
        )
    }

    private fun readRoots(connection: SQLiteConnection): List<DesktopFileSyncRootRecord> {
        val roots = connection.prepare(
            "SELECT id, absolute_path, display_name FROM sync_roots ORDER BY id LIMIT ${MAX_FILE_SYNC_PAIRS + 1}",
        ).use { statement ->
            buildList {
                while (statement.step()) {
                    add(DesktopFileSyncRootRecord(statement.getText(0), statement.getText(1), statement.getText(2)))
                }
            }
        }
        require(roots.size <= MAX_FILE_SYNC_PAIRS) { "The desktop folder sync database contains too many roots." }
        return roots
    }

    private fun readPairRecords(connection: SQLiteConnection, pairId: String? = null): List<FileSyncPair> {
        val sql = if (pairId == null) {
            "SELECT id, record FROM sync_pairs ORDER BY id LIMIT ${MAX_FILE_SYNC_PAIRS + 1}"
        } else {
            "SELECT id, record FROM sync_pairs WHERE id = ? LIMIT 2"
        }
        val pairs = connection.prepare(sql).use { statement ->
            if (pairId != null) statement.bindText(1, pairId)
            buildList {
                while (statement.step()) {
                    val pair = decodeFileSyncPairRecord(statement.getBlob(1))
                    require(pair.id == statement.getText(0))
                    add(pair)
                }
            }
        }
        require(pairs.size <= if (pairId == null) MAX_FILE_SYNC_PAIRS else 1) {
            "The desktop folder sync database contains too many pair records."
        }
        return pairs
    }

    private fun readBaselines(connection: SQLiteConnection, pairId: String): List<FileSyncBaseline> {
        val baselines = buildList {
            connection.prepare(
                "SELECT relative_path, record FROM sync_baselines WHERE pair_id = ? " +
                    "ORDER BY relative_path LIMIT ${MAX_FILE_SYNC_ENTRIES + 1}",
            ).use { statement ->
                statement.bindText(1, pairId)
                while (statement.step()) {
                    val baseline = decodeFileSyncBaselineRecord(statement.getBlob(1))
                    require(baseline.relativePath == statement.getText(0))
                    add(baseline)
                }
            }
        }
        require(baselines.size <= MAX_FILE_SYNC_ENTRIES) {
            "The desktop folder sync database contains too many baselines for one pair."
        }
        return baselines
    }

    private fun readWork(connection: SQLiteConnection, pairId: String): List<FileSyncWorkItem> {
        val work = buildList {
            connection.prepare(
                "SELECT work_id, record FROM sync_work WHERE pair_id = ? " +
                    "ORDER BY work_id LIMIT ${MAX_FILE_SYNC_WORK_ITEMS + 1}",
            ).use { statement ->
                statement.bindText(1, pairId)
                while (statement.step()) {
                    val item = decodeFileSyncWorkRecord(statement.getBlob(1))
                    require(item.id == statement.getLong(0))
                    add(item)
                }
            }
        }
        require(work.size <= MAX_FILE_SYNC_WORK_ITEMS) {
            "The desktop folder sync database contains too much work for one pair."
        }
        return work
    }

    private fun readWorkOverview(connection: SQLiteConnection, pairId: String): DesktopFileSyncWorkOverview {
        val counts = mutableMapOf<FileSyncExecutionState, Int>()
        connection.prepare(
            "SELECT state, COUNT(*) FROM sync_work WHERE pair_id = ? GROUP BY state",
        ).use { statement ->
            statement.bindText(1, pairId)
            while (statement.step()) {
                val state = enumValueOf<FileSyncExecutionState>(statement.getText(0))
                val count = statement.getLong(1)
                require(count in 1..MAX_FILE_SYNC_WORK_ITEMS.toLong())
                counts[state] = count.toInt()
            }
        }
        require(counts.values.sum() <= MAX_FILE_SYNC_WORK_ITEMS) {
            "The desktop folder sync database contains too much work for one pair."
        }
        val conflictCount = counts[FileSyncExecutionState.AwaitingDecision] ?: 0
        val skippedCount = counts[FileSyncExecutionState.Skipped] ?: 0
        return DesktopFileSyncWorkOverview(
            readyCount = (counts[FileSyncExecutionState.Ready] ?: 0) +
                (counts[FileSyncExecutionState.Running] ?: 0),
            runningCount = 0,
            conflictCount = conflictCount,
            failedCount = counts[FileSyncExecutionState.Failed] ?: 0,
            skippedCount = skippedCount,
            conflicts = if (conflictCount == 0) {
                emptyList()
            } else {
                readWorkPage(
                    connection,
                    pairId,
                    FileSyncExecutionState.AwaitingDecision,
                    limit = FILE_SYNC_CONFLICT_PAGE_SIZE,
                )
            },
            skippedReasons = if (skippedCount == 0) emptyList() else readSkippedReasons(connection, pairId),
        )
    }

    private fun readWorkPage(
        connection: SQLiteConnection,
        pairId: String,
        state: FileSyncExecutionState,
        limit: Int,
    ): List<FileSyncWorkItem> {
        require(limit in 1..MAX_FILE_SYNC_WORK_ITEMS)
        return connection.prepare(
            "SELECT work_id, relative_path, record FROM sync_work " +
                "WHERE pair_id = ? AND state = ? ORDER BY relative_path, work_id LIMIT $limit",
        ).use { statement ->
            statement.bindText(1, pairId)
            statement.bindText(2, state.name)
            buildList {
                while (statement.step()) {
                    val work = decodeFileSyncWorkRecord(statement.getBlob(2))
                    require(work.id == statement.getLong(0))
                    require(work.relativePath == statement.getText(1) && work.state == state)
                    add(work)
                }
            }
        }
    }

    private fun readSkippedReasons(connection: SQLiteConnection, pairId: String): List<String> =
        connection.prepare(
            "SELECT DISTINCT detail FROM sync_work WHERE pair_id = ? AND state = ? AND detail IS NOT NULL " +
                "ORDER BY detail LIMIT $MAX_FILE_SYNC_SKIPPED_REASONS",
        ).use { statement ->
            statement.bindText(1, pairId)
            statement.bindText(2, FileSyncExecutionState.Skipped.name)
            buildList {
                while (statement.step()) {
                    val reason = statement.getText(0)
                    require(
                        reason.isNotBlank() && reason.length <= MAX_FILE_SYNC_FAILURE_LENGTH &&
                            reason.none(Char::isISOControl),
                    )
                    add(reason)
                }
            }
        }

    private fun readTrayWork(
        connection: SQLiteConnection,
        pairIds: List<String>,
        limit: Int,
    ): List<DesktopFileSyncScopedWorkItem> {
        if (pairIds.isEmpty()) return emptyList()
        require(pairIds.size <= MAX_FILE_SYNC_PAIRS)
        require(limit in 1..MAX_TRAY_ACTIVITY_ITEMS)
        val placeholders = List(pairIds.size) { "?" }.joinToString(",")
        return connection.prepare(
            "SELECT pair_id, work_id, state, relative_path, record FROM sync_work " +
                "WHERE pair_id IN ($placeholders) AND state != ? " +
                "ORDER BY CASE " +
                "WHEN state IN ('AwaitingDecision', 'Failed') THEN 1 " +
                "WHEN state IN ('Ready', 'Running') THEN 2 ELSE 3 END, relative_path, work_id LIMIT $limit",
        ).use { statement ->
            pairIds.forEachIndexed { index, pairId -> statement.bindText(index + 1, pairId) }
            statement.bindText(pairIds.size + 1, FileSyncExecutionState.Skipped.name)
            buildList {
                while (statement.step()) {
                    val pairId = statement.getText(0)
                    val workId = statement.getLong(1)
                    val indexedState = enumValueOf<FileSyncExecutionState>(statement.getText(2))
                    val indexedPath = statement.getText(3)
                    val decoded = decodeFileSyncWorkRecord(statement.getBlob(4))
                    require(decoded.id == workId && decoded.state == indexedState && decoded.relativePath == indexedPath)
                    add(
                        DesktopFileSyncScopedWorkItem(
                            pairId,
                            if (decoded.state == FileSyncExecutionState.Running) {
                                decoded.copy(state = FileSyncExecutionState.Ready)
                            } else {
                                decoded
                            },
                        ),
                    )
                }
            }
        }
    }

    private fun countPairRows(connection: SQLiteConnection, table: String, pairId: String, maximum: Int): Int {
        require(table == "sync_baselines" || table == "sync_work")
        val count = connection.prepare("SELECT COUNT(*) FROM $table WHERE pair_id = ?").use { statement ->
            statement.bindText(1, pairId)
            check(statement.step())
            statement.getLong(0)
        }
        require(count in 0..maximum.toLong()) { "The desktop folder sync database exceeds its row limit." }
        return count.toInt()
    }

    private fun persistDifference(
        connection: SQLiteConnection,
        before: DesktopFileSyncPersistedState,
        after: DesktopFileSyncPersistedState,
    ) {
        val oldRoots = before.roots.associateBy(DesktopFileSyncRootRecord::id)
        val newRoots = after.roots.associateBy(DesktopFileSyncRootRecord::id)
        (oldRoots.keys - newRoots.keys).forEach { id -> delete(connection, "DELETE FROM sync_roots WHERE id = ?", id) }
        newRoots.forEach { (id, root) ->
            if (oldRoots[id] != root) {
                upsertRootRecord(connection, root)
            }
        }

        val oldPairs = before.coordinator.pairs.associateBy(FileSyncPair::id)
        val newPairs = after.coordinator.pairs.associateBy(FileSyncPair::id)
        (oldPairs.keys - newPairs.keys).forEach { id ->
            delete(connection, "DELETE FROM sync_pairs WHERE id = ?", id)
            delete(connection, "DELETE FROM sync_metadata WHERE key = ?", baselineCountKey(id))
        }
        newPairs.forEach { (pairId, pair) ->
            val oldPair = oldPairs[pairId]
            val pairRecord = pair.copy(baselines = emptyList(), workItems = emptyList())
            val oldPairRecord = oldPair?.copy(baselines = emptyList(), workItems = emptyList())
            if (pairRecord != oldPairRecord) {
                upsertPairRecord(connection, pair)
            }
            persistBaselines(connection, pairId, oldPair?.baselines.orEmpty(), pair.baselines)
            persistWork(connection, pairId, oldPair?.workItems.orEmpty(), pair.workItems)
            putMetadata(connection, baselineCountKey(pairId), pair.baselines.size.toString())
        }
    }

    private fun persistBaselines(
        connection: SQLiteConnection,
        pairId: String,
        before: List<FileSyncBaseline>,
        after: List<FileSyncBaseline>,
    ) {
        val old = before.associateBy(FileSyncBaseline::relativePath)
        val current = after.associateBy(FileSyncBaseline::relativePath)
        (old.keys - current.keys).forEach { path ->
            delete(connection, "DELETE FROM sync_baselines WHERE pair_id = ? AND relative_path = ?", pairId, path)
        }
        current.forEach { (path, baseline) ->
            if (old[path] != baseline) {
                upsertBaselineRecord(connection, pairId, baseline)
            }
        }
    }

    private fun persistWork(
        connection: SQLiteConnection,
        pairId: String,
        before: List<FileSyncWorkItem>,
        after: List<FileSyncWorkItem>,
    ) {
        val old = before.associateBy(FileSyncWorkItem::id)
        val current = after.associateBy(FileSyncWorkItem::id)
        (old.keys - current.keys).forEach { id ->
            connection.prepare("DELETE FROM sync_work WHERE pair_id = ? AND work_id = ?").use { statement ->
                statement.bindText(1, pairId)
                statement.bindLong(2, id)
                check(!statement.step())
            }
        }
        current.forEach { (id, item) ->
            if (old[id] != item) {
                persistWorkRecord(connection, pairId, id, item)
            }
        }
    }

    private fun upsertPairRecord(connection: SQLiteConnection, pair: FileSyncPair) {
        connection.prepare(
            "INSERT INTO sync_pairs(id, record) VALUES (?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET record = excluded.record",
        ).use { statement ->
            statement.bindText(1, pair.id)
            statement.bindBlob(2, encodeFileSyncPairRecord(pair.copy(baselines = emptyList(), workItems = emptyList())))
            check(!statement.step())
        }
    }

    private fun upsertRootRecord(connection: SQLiteConnection, root: DesktopFileSyncRootRecord) {
        connection.prepare(
            "INSERT OR REPLACE INTO sync_roots(id, absolute_path, display_name) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, root.id)
            statement.bindText(2, root.absolutePath)
            statement.bindText(3, root.displayName)
            check(!statement.step())
        }
    }

    private fun upsertBaselineRecord(
        connection: SQLiteConnection,
        pairId: String,
        baseline: FileSyncBaseline,
    ) {
        connection.prepare(
            "INSERT OR REPLACE INTO sync_baselines(pair_id, relative_path, record) VALUES (?, ?, ?)",
        ).use { statement ->
            statement.bindText(1, pairId)
            statement.bindText(2, baseline.relativePath)
            statement.bindBlob(3, encodeFileSyncBaselineRecord(baseline))
            check(!statement.step())
        }
    }

    private fun persistWorkRecord(
        connection: SQLiteConnection,
        pairId: String,
        workId: Long,
        workItem: FileSyncWorkItem?,
    ) {
        if (workItem == null) {
            connection.prepare("DELETE FROM sync_work WHERE pair_id = ? AND work_id = ?").use { statement ->
                statement.bindText(1, pairId)
                statement.bindLong(2, workId)
                check(!statement.step())
            }
            return
        }
        connection.prepare(
            "INSERT OR REPLACE INTO sync_work(" +
                "pair_id, work_id, state, relative_path, detail, record) VALUES (?, ?, ?, ?, ?, ?)",
        )
            .use { statement ->
                statement.bindText(1, pairId)
                statement.bindLong(2, workId)
                statement.bindText(3, workItem.state.name)
                statement.bindText(4, workItem.relativePath)
                workItem.indexDetail()?.let { statement.bindText(5, it) } ?: statement.bindNull(5)
                statement.bindBlob(6, encodeFileSyncWorkRecord(workItem))
                check(!statement.step())
            }
    }

    private fun delete(connection: SQLiteConnection, sql: String, vararg values: String) {
        connection.prepare(sql).use { statement ->
            values.forEachIndexed { index, value -> statement.bindText(index + 1, value) }
            check(!statement.step())
        }
    }

    private fun metadataValue(connection: SQLiteConnection, key: String): String? =
        connection.prepare("SELECT value FROM sync_metadata WHERE key = ?").use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }

    private fun putMetadata(connection: SQLiteConnection, key: String, value: String) {
        connection.prepare("INSERT OR REPLACE INTO sync_metadata(key, value) VALUES (?, ?)").use { statement ->
            statement.bindText(1, key)
            statement.bindText(2, value)
            check(!statement.step())
        }
    }

    private inline fun <T> transaction(connection: SQLiteConnection, block: () -> T): T {
        connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
        return try {
            block().also { connection.execSQL("COMMIT") }
        } catch (failure: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw failure
        }
    }

    private companion object {
        val processLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

@Serializable
private data class DesktopFileSyncSnapshotV1(
    val schemaVersion: Int = FORMAT_VERSION,
    val coordinatorBase64: String,
    val roots: List<DesktopFileSyncRootSnapshotV1>,
)

@Serializable
private data class DesktopFileSyncRootSnapshotV1(
    val id: String,
    val absolutePath: String,
    val displayName: String,
)

private fun desktopFileSyncDatabaseFile(): File {
    val xdgState = System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)
    val root = xdgState?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/state")
    return File(root, "nextcloud-native/file-sync-state-v2.db")
}

private fun decodeLegacyState(stateFile: File): DesktopFileSyncPersistedState {
    require(stateFile.isFile && stateFile.length() in 1..MAX_LEGACY_STATE_BYTES) {
        "Desktop folder sync state exceeds its safe storage limit."
    }
    val snapshot = stateJson.decodeFromString<DesktopFileSyncSnapshotV1>(stateFile.readText())
    require(snapshot.schemaVersion == FORMAT_VERSION) { "Desktop folder sync state version is unsupported." }
    val coordinator = decodeFileSyncCoordinatorSnapshot(Base64.getDecoder().decode(snapshot.coordinatorBase64))
    val roots = snapshot.roots.map { DesktopFileSyncRootRecord(it.id, it.absolutePath, it.displayName) }
    return DesktopFileSyncPersistedState(coordinator, roots)
}

private val stateJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

private fun FileSyncWorkItem.indexDetail(): String? =
    (operation as? FileSyncOperation.Skipped)?.reason ?: failureMessage

private const val FORMAT_VERSION = 1
private const val MAX_LEGACY_STATE_BYTES = 17L * 1024L * 1024L
private const val LEGACY_IMPORT_KEY = "legacy_v1_import"
private const val SCHEMA_VERSION_KEY = "schema_version"
private const val BASELINE_COUNT_KEY_PREFIX = "baseline_count:"
private const val MAX_FILE_SYNC_SKIPPED_REASONS = 20
private const val PREVIOUS_DATABASE_SCHEMA_VERSION = "2"
private const val DATABASE_SCHEMA_VERSION = "3"

private fun baselineCountKey(pairId: String): String = BASELINE_COUNT_KEY_PREFIX + pairId
