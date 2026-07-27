package dev.obiente.nextcloudnative.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MediaBackupLedgerStoreException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Indexed, transactional media transfer ledger shared by Android and desktop.
 *
 * A single connection is guarded by [mutex]. The bundled driver keeps SQLite behavior consistent
 * across supported targets. Pages use a stable `(updated_at, local_key)` cursor so the UI never
 * needs to materialize a complete upload history.
 */
class MediaBackupLedgerStore internal constructor(
    private val connection: SQLiteConnection,
    private val maxRecordsPerAccount: Int = MAX_MEDIA_BACKUP_LEDGER_RECORDS_PER_ACCOUNT,
    recoverInterruptedTransfers: Boolean = true,
) {
    private val mutex = Mutex()

    constructor(
        databasePath: String,
        recoverInterruptedTransfers: Boolean = true,
    ) : this(
        connection = BundledSQLiteDriver().open(databasePath),
        recoverInterruptedTransfers = recoverInterruptedTransfers,
    )

    init {
        require(maxRecordsPerAccount in 1..MAX_MEDIA_BACKUP_LEDGER_RECORDS_PER_ACCOUNT)
        try {
            withMediaBackupLedgerInitializationLock {
                initializeSchema(recoverInterruptedTransfers)
            }
        } catch (failure: Throwable) {
            connection.close()
            throw if (failure is MediaBackupLedgerStoreException) {
                failure
            } else {
                MediaBackupLedgerStoreException("Could not open the media backup ledger.", failure)
            }
        }
    }

    suspend fun upsert(record: MediaBackupLedgerRecord) = upsertAll(listOf(record))

    suspend fun upsertAll(records: Collection<MediaBackupLedgerRecord>) = mutex.withLock {
        require(records.size <= MAX_MEDIA_BACKUP_LEDGER_WRITE_BATCH)
        require(records.map { it.accountId to it.localKey }.distinct().size == records.size)
        if (records.isEmpty()) return@withLock
        transaction {
            records.forEach { record ->
                connection.prepare(UPSERT_RECORD).use { statement ->
                    statement.bindRecord(record)
                    check(!statement.step()) { "Media ledger upsert returned an unexpected row." }
                }
            }
            records.map(MediaBackupLedgerRecord::accountId).distinct().forEach { accountId ->
                pruneCompletedHistory(accountId)
                check(countRecords(accountId) <= maxRecordsPerAccount) {
                    "The media ledger contains too many unfinished transfers for this account."
                }
            }
        }
    }

    suspend fun load(
        accountId: String,
        localKey: String,
    ): MediaBackupLedgerRecord? = mutex.withLock {
        requireAccountId(accountId)
        requireLocalKey(localKey)
        connection.prepare("$SELECT_COLUMNS WHERE account_id = ? AND local_key = ?").use { statement ->
            statement.bindText(1, accountId)
            statement.bindText(2, localKey)
            if (statement.step()) statement.readRecord() else null
        }
    }

    suspend fun loadMany(
        accountId: String,
        localKeys: Collection<String>,
    ): Map<String, MediaBackupLedgerRecord> = mutex.withLock {
        requireAccountId(accountId)
        val keys = localKeys.distinct()
        require(keys.size <= MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS)
        keys.forEach(::requireLocalKey)
        if (keys.isEmpty()) return@withLock emptyMap()
        val placeholders = List(keys.size) { "?" }.joinToString()
        connection.prepare(
            "$SELECT_COLUMNS WHERE account_id = ? AND local_key IN ($placeholders)",
        ).use { statement ->
            statement.bindText(1, accountId)
            keys.forEachIndexed { index, key -> statement.bindText(index + 2, key) }
            buildMap {
                while (statement.step()) {
                    val record = statement.readRecord()
                    put(record.localKey, record)
                }
            }
        }
    }

    suspend fun migrateSourceLocalKeys(
        accountId: String,
        sourceId: String,
        migrations: Collection<MediaBackupLedgerKeyMigration>,
    ) = mutex.withLock {
        requireAccountId(accountId)
        requireSourceId(sourceId)
        require(migrations.size <= MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS)
        require(
            migrations.map(MediaBackupLedgerKeyMigration::legacyLocalKey).distinct().size ==
                migrations.size,
        )
        migrations.forEach { migration ->
            requireLocalKey(migration.legacyLocalKey)
            requireLocalKey(migration.currentLocalKey)
            requireValidSyncPath(migration.remotePath)
        }
        if (migrations.isEmpty()) return@withLock
        transaction {
            migrations.forEach { migration ->
                val currentExists = connection.prepare(
                    "SELECT 1 FROM media_backup_ledger WHERE account_id = ? AND local_key = ?",
                ).use { statement ->
                    statement.bindText(1, accountId)
                    statement.bindText(2, migration.currentLocalKey)
                    statement.step()
                }
                if (currentExists) {
                    connection.prepare(
                        "DELETE FROM media_backup_ledger " +
                            "WHERE account_id = ? AND local_key = ? AND " +
                            "(source_id = ? OR (source_id IS NULL AND " +
                            "(remote_path = ? OR " +
                            "(remote_path IS NULL AND transfer_state != 'Succeeded'))))",
                    ).use { statement ->
                        statement.bindText(1, accountId)
                        statement.bindText(2, migration.legacyLocalKey)
                        statement.bindText(3, sourceId)
                        statement.bindText(4, migration.remotePath)
                        check(!statement.step())
                    }
                } else {
                    connection.prepare(
                        """
                        UPDATE media_backup_ledger
                        SET local_key = ?, source_id = ?
                        WHERE account_id = ? AND local_key = ? AND
                            (source_id = ? OR (source_id IS NULL AND
                            (remote_path = ? OR
                            (remote_path IS NULL AND transfer_state != 'Succeeded'))))
                        """.trimIndent(),
                    ).use { statement ->
                        statement.bindText(1, migration.currentLocalKey)
                        statement.bindText(2, sourceId)
                        statement.bindText(3, accountId)
                        statement.bindText(4, migration.legacyLocalKey)
                        statement.bindText(5, sourceId)
                        statement.bindText(6, migration.remotePath)
                        check(!statement.step())
                    }
                }
            }
        }
    }

    suspend fun page(
        accountId: String,
        transferState: MediaBackupTransferState? = null,
        after: MediaBackupLedgerCursor? = null,
        limit: Int = 50,
        includeClearedCompleted: Boolean = true,
    ): MediaBackupLedgerPage = mutex.withLock {
        requireAccountId(accountId)
        require(limit in 1..MAX_MEDIA_BACKUP_LEDGER_PAGE_SIZE)
        queryPage(accountId, transferState, after, limit, includeClearedCompleted)
    }

    suspend fun snapshot(
        accountId: String,
        transferState: MediaBackupTransferState? = null,
        after: MediaBackupLedgerCursor? = null,
        limit: Int = 50,
        includeClearedCompleted: Boolean = true,
    ): MediaBackupLedgerSnapshot = mutex.withLock {
        requireAccountId(accountId)
        require(limit in 1..MAX_MEDIA_BACKUP_LEDGER_PAGE_SIZE)
        readTransaction {
            MediaBackupLedgerSnapshot(
                summary = querySummary(accountId, includeClearedCompleted),
                page = queryPage(accountId, transferState, after, limit, includeClearedCompleted),
            )
        }
    }

    private fun queryPage(
        accountId: String,
        transferState: MediaBackupTransferState?,
        after: MediaBackupLedgerCursor?,
        limit: Int,
        includeClearedCompleted: Boolean,
    ): MediaBackupLedgerPage {
        val predicates = buildList {
            add("account_id = ?")
            if (transferState != null) add("transfer_state = ?")
            if (!includeClearedCompleted) {
                add("(transfer_state != 'Succeeded' OR history_visible = 1)")
            }
            if (after != null) {
                add("(updated_at < ? OR (updated_at = ? AND local_key < ?))")
            }
        }
        val sql = buildString {
            append(SELECT_COLUMNS)
            append(" WHERE ")
            append(predicates.joinToString(" AND "))
            append(" ORDER BY updated_at DESC, local_key DESC LIMIT ?")
        }
        val rows = connection.prepare(sql).use { statement ->
            var index = 1
            statement.bindText(index++, accountId)
            transferState?.let { statement.bindText(index++, it.name) }
            after?.let { cursor ->
                statement.bindLong(index++, cursor.updatedAtEpochMillis)
                statement.bindLong(index++, cursor.updatedAtEpochMillis)
                statement.bindText(index++, cursor.localKey)
            }
            statement.bindLong(index, (limit + 1).toLong())
            buildList {
                while (statement.step()) add(statement.readRecord())
            }
        }
        val visible = rows.take(limit)
        return MediaBackupLedgerPage(
            records = visible,
            nextCursor = if (rows.size > limit) {
                visible.last().let { MediaBackupLedgerCursor(it.updatedAtEpochMillis, it.localKey) }
            } else {
                null
            },
        )
    }

    suspend fun summary(
        accountId: String,
        includeClearedCompleted: Boolean = true,
    ): MediaBackupLedgerSummary = mutex.withLock {
        requireAccountId(accountId)
        querySummary(accountId, includeClearedCompleted)
    }

    private fun querySummary(
        accountId: String,
        includeClearedCompleted: Boolean,
    ): MediaBackupLedgerSummary {
        val counts = mutableMapOf<MediaBackupTransferState, Int>()
        val visibleHistoryPredicate = if (includeClearedCompleted) {
            ""
        } else {
            " AND (transfer_state != 'Succeeded' OR history_visible = 1)"
        }
        connection.prepare(
            "SELECT transfer_state, COUNT(*) FROM media_backup_ledger " +
                "WHERE account_id = ?$visibleHistoryPredicate GROUP BY transfer_state",
        ).use { statement ->
            statement.bindText(1, accountId)
            while (statement.step()) {
                val state = enumValueOf<MediaBackupTransferState>(statement.getText(0))
                val count = statement.getLong(1)
                check(count in 0..Int.MAX_VALUE.toLong())
                counts[state] = count.toInt()
            }
        }
        return MediaBackupLedgerSummary(
            pending = counts[MediaBackupTransferState.Pending] ?: 0,
            uploading = counts[MediaBackupTransferState.Uploading] ?: 0,
            failed = counts[MediaBackupTransferState.Failed] ?: 0,
            succeeded = counts[MediaBackupTransferState.Succeeded] ?: 0,
        )
    }

    suspend fun retryFailed(
        accountId: String,
        localKey: String,
        updatedAtEpochMillis: Long,
    ): Boolean = mutex.withLock {
        requireAccountId(accountId)
        requireLocalKey(localKey)
        require(updatedAtEpochMillis >= 0L)
        connection.prepare(
            """
            UPDATE media_backup_ledger
            SET transfer_state = 'Pending', failure_message = NULL, updated_at = ?,
                history_visible = 1
            WHERE account_id = ? AND local_key = ? AND transfer_state = 'Failed'
            """.trimIndent(),
        ).use { statement ->
            statement.bindLong(1, updatedAtEpochMillis)
            statement.bindText(2, accountId)
            statement.bindText(3, localKey)
            check(!statement.step())
        }
        changedRowCount() == 1
    }

    /**
     * Removes a queued projection after its authoritative scheduler has accepted cancellation.
     *
     * Uploading rows are deliberately excluded: callers must stop their worker first and then
     * recover it to Pending before removing the projection.
     */
    suspend fun removePending(
        accountId: String,
        localKey: String,
    ): Boolean = mutex.withLock {
        requireAccountId(accountId)
        requireLocalKey(localKey)
        connection.prepare(
            "DELETE FROM media_backup_ledger " +
                "WHERE account_id = ? AND local_key = ? AND transfer_state = 'Pending' " +
                "AND receipt_local_revision IS NULL",
        ).use { statement ->
            statement.bindText(1, accountId)
            statement.bindText(2, localKey)
            check(!statement.step())
        }
        changedRowCount() == 1
    }

    suspend fun clearCompleted(accountId: String): Int = mutex.withLock {
        requireAccountId(accountId)
        connection.prepare(
            "UPDATE media_backup_ledger SET history_visible = 0 " +
                "WHERE account_id = ? AND transfer_state = 'Succeeded' AND history_visible = 1",
        ).use { statement ->
            statement.bindText(1, accountId)
            check(!statement.step())
        }
        changedRowCount()
    }

    suspend fun deleteUnfinishedSource(
        accountId: String,
        sourceId: String,
        legacyLocalKeys: Collection<String> = emptyList(),
    ): Int = mutex.withLock {
        requireAccountId(accountId)
        requireSourceId(sourceId)
        val legacyKeys = legacyLocalKeys.distinct()
        require(legacyKeys.size <= MAX_MEDIA_BACKUP_SOURCE_LEGACY_KEYS)
        legacyKeys.forEach(::requireLocalKey)
        transaction {
            var deleted = connection.prepare(
                "DELETE FROM media_backup_ledger WHERE account_id = ? AND source_id = ? " +
                    "AND transfer_state != 'Succeeded'",
            ).use { statement ->
                statement.bindText(1, accountId)
                statement.bindText(2, sourceId)
                check(!statement.step())
                changedRowCount()
            }
            legacyKeys.chunked(MAX_MEDIA_BACKUP_LEDGER_QUERY_KEYS).forEach { keys ->
                val placeholders = List(keys.size) { "?" }.joinToString()
                connection.prepare(
                    "DELETE FROM media_backup_ledger WHERE account_id = ? AND source_id IS NULL " +
                        "AND transfer_state != 'Succeeded' AND local_key IN ($placeholders)",
                ).use { statement ->
                    statement.bindText(1, accountId)
                    keys.forEachIndexed { index, key -> statement.bindText(index + 2, key) }
                    check(!statement.step())
                    deleted += changedRowCount()
                }
            }
            deleted
        }
    }

    suspend fun statusesForRemotePaths(
        accountId: String,
        remotePaths: Collection<String>,
    ): Map<String, MediaBackupStatus> = mutex.withLock {
        requireAccountId(accountId)
        val paths = remotePaths.distinct()
        require(paths.size <= MAX_MEDIA_BACKUP_STATUS_PATHS)
        paths.forEach(::requireValidSyncPath)
        if (paths.isEmpty()) return@withLock emptyMap()
        val placeholders = List(paths.size) { "?" }.joinToString()
        connection.prepare(
            "$SELECT_COLUMNS WHERE account_id = ? AND remote_path IN ($placeholders) " +
                "ORDER BY updated_at DESC, local_key DESC",
        ).use { statement ->
            statement.bindText(1, accountId)
            paths.forEachIndexed { index, path -> statement.bindText(index + 2, path) }
            buildMap {
                while (statement.step()) {
                    val record = statement.readRecord()
                    val path = requireNotNull(record.receipt).remotePath
                    putIfAbsent(path, record.resolveMediaBackupStatus())
                }
            }
        }
    }

    suspend fun deleteAccount(accountId: String) = mutex.withLock {
        requireAccountId(accountId)
        transaction {
            connection.prepare("DELETE FROM media_backup_ledger WHERE account_id = ?").use { statement ->
                statement.bindText(1, accountId)
                check(!statement.step())
            }
        }
    }

    suspend fun recoverInterruptedTransfers(accountId: String): Int = mutex.withLock {
        requireAccountId(accountId)
        transaction {
            connection.prepare(
                "UPDATE media_backup_ledger SET transfer_state = 'Pending', failure_message = NULL " +
                    "WHERE account_id = ? AND transfer_state = 'Uploading'",
            ).use { statement ->
                statement.bindText(1, accountId)
                check(!statement.step())
            }
            changedRowCount()
        }
    }

    suspend fun reconcileInterruptedTransfers(
        accountId: String,
        activeSourceIds: Set<String>,
    ): Int = mutex.withLock {
        requireAccountId(accountId)
        require(activeSourceIds.size <= MAX_MEDIA_BACKUP_TRANSFER_SOURCES)
        activeSourceIds.forEach(::requireSourceId)
        transaction {
            val predicate = if (activeSourceIds.isEmpty()) {
                ""
            } else {
                val placeholders = List(activeSourceIds.size) { "?" }.joinToString()
                " AND source_id IS NOT NULL AND source_id NOT IN ($placeholders)"
            }
            connection.prepare(
                "UPDATE media_backup_ledger SET transfer_state = 'Pending', failure_message = NULL " +
                    "WHERE account_id = ? AND transfer_state = 'Uploading'$predicate",
            ).use { statement ->
                statement.bindText(1, accountId)
                activeSourceIds.forEachIndexed { index, sourceId ->
                    statement.bindText(index + 2, sourceId)
                }
                check(!statement.step())
            }
            changedRowCount()
        }
    }

    suspend fun close() = mutex.withLock {
        connection.close()
    }

    private fun initializeSchema(recoverInterruptedTransfers: Boolean) {
        connection.execSQL("PRAGMA busy_timeout = $SCHEMA_MIGRATION_BUSY_TIMEOUT_MILLIS")
        connection.prepare("PRAGMA journal_mode = WAL").use(SQLiteStatement::step)
        connection.execSQL("PRAGMA synchronous = FULL")
        connection.execSQL("PRAGMA foreign_keys = ON")
        transaction {
            val version = connection.prepare("PRAGMA user_version").use { statement ->
                check(statement.step())
                statement.getLong(0).toInt()
            }
            when (version) {
                0 -> {
                    connection.execSQL(CREATE_TABLE)
                    connection.execSQL(CREATE_ACCOUNT_STATE_INDEX)
                    connection.execSQL(CREATE_ACCOUNT_UPDATED_INDEX)
                    connection.execSQL(CREATE_ACCOUNT_REMOTE_PATH_INDEX)
                    connection.execSQL(CREATE_ACCOUNT_SOURCE_INDEX)
                    connection.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                1 -> {
                    connection.execSQL(ADD_HISTORY_VISIBLE_COLUMN)
                    connection.execSQL(ADD_SOURCE_ID_COLUMN)
                    connection.execSQL(CREATE_ACCOUNT_REMOTE_PATH_INDEX)
                    connection.execSQL(CREATE_ACCOUNT_SOURCE_INDEX)
                    connection.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                2 -> {
                    connection.execSQL(ADD_HISTORY_VISIBLE_COLUMN)
                    connection.execSQL(ADD_SOURCE_ID_COLUMN)
                    connection.execSQL(CREATE_ACCOUNT_SOURCE_INDEX)
                    connection.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                3 -> {
                    connection.execSQL(ADD_SOURCE_ID_COLUMN)
                    connection.execSQL(CREATE_ACCOUNT_SOURCE_INDEX)
                    connection.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                SCHEMA_VERSION -> Unit
                else -> throw MediaBackupLedgerStoreException(
                    "Media backup ledger schema version $version is unsupported.",
                )
            }
        }
        if (recoverInterruptedTransfers) {
            transaction {
                connection.execSQL(
                    "UPDATE media_backup_ledger SET transfer_state = 'Pending', failure_message = NULL " +
                        "WHERE transfer_state = 'Uploading'",
                )
            }
        }
    }

    private fun pruneCompletedHistory(accountId: String) {
        val excess = countRecords(accountId) - maxRecordsPerAccount
        if (excess <= 0) return
        connection.prepare(
            """
            DELETE FROM media_backup_ledger
            WHERE account_id = ? AND local_key IN (
                SELECT local_key
                FROM media_backup_ledger
                WHERE account_id = ? AND transfer_state = 'Succeeded'
                ORDER BY updated_at ASC, local_key ASC
                LIMIT ?
            )
            """.trimIndent(),
        ).use { statement ->
            statement.bindText(1, accountId)
            statement.bindText(2, accountId)
            statement.bindLong(3, excess.toLong())
            check(!statement.step())
        }
    }

    private fun countRecords(accountId: String): Int =
        connection.prepare(
            "SELECT COUNT(*) FROM media_backup_ledger WHERE account_id = ?",
        ).use { statement ->
            statement.bindText(1, accountId)
            check(statement.step())
            val count = statement.getLong(0)
            check(count in 0..Int.MAX_VALUE.toLong())
            count.toInt()
        }

    private fun changedRowCount(): Int =
        connection.prepare("SELECT changes()").use { statement ->
            check(statement.step())
            val count = statement.getLong(0)
            check(count in 0..Int.MAX_VALUE.toLong())
            count.toInt()
        }

    private inline fun <T> transaction(block: () -> T): T {
        connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
        return try {
            block().also { connection.execSQL("COMMIT") }
        } catch (failure: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw failure
        }
    }

    private inline fun <T> readTransaction(block: () -> T): T {
        connection.execSQL("BEGIN TRANSACTION")
        return try {
            block().also { connection.execSQL("COMMIT") }
        } catch (failure: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw failure
        }
    }

    private fun SQLiteStatement.bindRecord(record: MediaBackupLedgerRecord) {
        bindText(1, record.accountId)
        bindNullableText(2, record.sourceId)
        bindText(3, record.localKey)
        bindNullableText(4, record.local?.displayName)
        bindNullableLong(5, record.local?.size)
        bindNullableText(6, record.local?.revision)
        bindNullableText(7, record.receipt?.localRevision)
        bindNullableLong(8, record.receipt?.localSize)
        bindNullableText(9, record.receipt?.remotePath)
        bindNullableText(10, record.receipt?.remoteEtag)
        bindNullableLong(11, record.receipt?.verifiedAtEpochMillis)
        bindText(12, record.transferState.name)
        bindLong(13, record.attemptCount.toLong())
        bindLong(14, record.updatedAtEpochMillis)
        bindNullableText(15, record.failureMessage)
    }

    private fun SQLiteStatement.readRecord(): MediaBackupLedgerRecord {
        val accountId = getText(0)
        val sourceId = nullableText(1)
        val localKey = getText(2)
        val local = if (isNull(3)) {
            check(isNull(4) && isNull(5))
            null
        } else {
            LocalMediaObject(
                key = localKey,
                displayName = getText(3),
                size = getLong(4),
                revision = getText(5),
            )
        }
        val receipt = if (isNull(8)) {
            check(isNull(6) && isNull(7) && isNull(9) && isNull(10))
            null
        } else {
            MediaBackupReceipt(
                localKey = localKey,
                localRevision = getText(6),
                localSize = getLong(7),
                remotePath = getText(8),
                remoteEtag = getText(9),
                verifiedAtEpochMillis = getLong(10),
            )
        }
        val attemptCount = getLong(12)
        check(attemptCount in 0..Int.MAX_VALUE.toLong())
        return MediaBackupLedgerRecord(
            accountId = accountId,
            sourceId = sourceId,
            local = local,
            receipt = receipt,
            transferState = enumValueOf(getText(11)),
            attemptCount = attemptCount.toInt(),
            updatedAtEpochMillis = getLong(13),
            failureMessage = nullableText(14),
        )
    }

    private fun SQLiteStatement.bindNullableText(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindText(index, value)
    }

    private fun SQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun SQLiteStatement.nullableText(index: Int): String? =
        if (isNull(index)) null else getText(index)

    private fun requireAccountId(accountId: String) {
        require(accountId.isNotBlank() && accountId.length <= 256 && accountId.none(Char::isISOControl))
    }

    private fun requireLocalKey(localKey: String) {
        require(localKey.isNotBlank() && localKey.length <= 2_048 && localKey.none(Char::isISOControl))
    }

    private fun requireSourceId(sourceId: String) {
        require(sourceId.isNotBlank() && sourceId.length <= 256 && sourceId.none(Char::isISOControl))
    }

    private companion object {
        const val SCHEMA_VERSION = 4
        const val SCHEMA_MIGRATION_BUSY_TIMEOUT_MILLIS = 5_000

        const val SELECT_COLUMNS =
            "SELECT account_id, source_id, local_key, local_display_name, local_size, local_revision, " +
                "receipt_local_revision, receipt_local_size, remote_path, remote_etag, verified_at, " +
                "transfer_state, attempt_count, updated_at, failure_message FROM media_backup_ledger"

        val CREATE_TABLE =
            """
            CREATE TABLE media_backup_ledger (
                account_id TEXT NOT NULL,
                source_id TEXT,
                local_key TEXT NOT NULL,
                local_display_name TEXT,
                local_size INTEGER,
                local_revision TEXT,
                receipt_local_revision TEXT,
                receipt_local_size INTEGER,
                remote_path TEXT,
                remote_etag TEXT,
                verified_at INTEGER,
                transfer_state TEXT NOT NULL CHECK (
                    transfer_state IN ('Pending', 'Uploading', 'Failed', 'Succeeded')
                ),
                attempt_count INTEGER NOT NULL CHECK (attempt_count >= 0),
                updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
                failure_message TEXT,
                history_visible INTEGER NOT NULL DEFAULT 1 CHECK (history_visible IN (0, 1)),
                PRIMARY KEY (account_id, local_key),
                CHECK (
                    (local_display_name IS NULL AND local_size IS NULL AND local_revision IS NULL)
                    OR
                    (local_display_name IS NOT NULL AND local_size IS NOT NULL AND local_revision IS NOT NULL)
                ),
                CHECK (
                    (remote_path IS NULL AND receipt_local_revision IS NULL AND receipt_local_size IS NULL
                        AND remote_etag IS NULL AND verified_at IS NULL)
                    OR
                    (remote_path IS NOT NULL AND receipt_local_revision IS NOT NULL
                        AND receipt_local_size IS NOT NULL AND remote_etag IS NOT NULL
                        AND verified_at IS NOT NULL)
                ),
                CHECK ((transfer_state = 'Failed') = (failure_message IS NOT NULL))
            )
            """.trimIndent()

        const val ADD_HISTORY_VISIBLE_COLUMN =
            "ALTER TABLE media_backup_ledger ADD COLUMN history_visible INTEGER NOT NULL " +
                "DEFAULT 1 CHECK (history_visible IN (0, 1))"

        const val ADD_SOURCE_ID_COLUMN =
            "ALTER TABLE media_backup_ledger ADD COLUMN source_id TEXT"

        const val CREATE_ACCOUNT_STATE_INDEX =
            "CREATE INDEX media_backup_account_state_updated " +
                "ON media_backup_ledger(account_id, transfer_state, updated_at DESC, local_key DESC)"

        const val CREATE_ACCOUNT_UPDATED_INDEX =
            "CREATE INDEX media_backup_account_updated " +
                "ON media_backup_ledger(account_id, updated_at DESC, local_key DESC)"

        const val CREATE_ACCOUNT_REMOTE_PATH_INDEX =
            "CREATE INDEX media_backup_account_remote_path_updated " +
                "ON media_backup_ledger(account_id, remote_path, updated_at DESC, local_key DESC)"

        const val CREATE_ACCOUNT_SOURCE_INDEX =
            "CREATE INDEX media_backup_account_source " +
                "ON media_backup_ledger(account_id, source_id)"

        val UPSERT_RECORD =
            """
            INSERT INTO media_backup_ledger (
                account_id, source_id, local_key, local_display_name, local_size, local_revision,
                receipt_local_revision, receipt_local_size, remote_path, remote_etag, verified_at,
                transfer_state, attempt_count, updated_at, failure_message, history_visible
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(account_id, local_key) DO UPDATE SET
                source_id = excluded.source_id,
                local_display_name = excluded.local_display_name,
                local_size = excluded.local_size,
                local_revision = excluded.local_revision,
                receipt_local_revision = excluded.receipt_local_revision,
                receipt_local_size = excluded.receipt_local_size,
                remote_path = excluded.remote_path,
                remote_etag = excluded.remote_etag,
                verified_at = excluded.verified_at,
                transfer_state = excluded.transfer_state,
                attempt_count = excluded.attempt_count,
                updated_at = excluded.updated_at,
                failure_message = excluded.failure_message,
                history_visible = CASE
                    WHEN media_backup_ledger.transfer_state = 'Succeeded'
                        AND excluded.transfer_state = 'Succeeded'
                        AND media_backup_ledger.receipt_local_revision = excluded.receipt_local_revision
                        AND media_backup_ledger.receipt_local_size = excluded.receipt_local_size
                        AND media_backup_ledger.remote_path = excluded.remote_path
                        AND media_backup_ledger.remote_etag = excluded.remote_etag
                    THEN media_backup_ledger.history_visible
                    ELSE 1
                END
            """.trimIndent()
    }
}

internal expect fun <T> withMediaBackupLedgerInitializationLock(block: () -> T): T
