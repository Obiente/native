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
            initializeSchema(recoverInterruptedTransfers)
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

    suspend fun page(
        accountId: String,
        transferState: MediaBackupTransferState? = null,
        after: MediaBackupLedgerCursor? = null,
        limit: Int = 50,
        includeClearedCompleted: Boolean = true,
    ): MediaBackupLedgerPage = mutex.withLock {
        requireAccountId(accountId)
        require(limit in 1..MAX_MEDIA_BACKUP_LEDGER_PAGE_SIZE)
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
        MediaBackupLedgerPage(
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
        MediaBackupLedgerSummary(
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
                    connection.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                1 -> {
                    connection.execSQL(ADD_HISTORY_VISIBLE_COLUMN)
                    connection.execSQL(CREATE_ACCOUNT_REMOTE_PATH_INDEX)
                    connection.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
                }
                2 -> {
                    connection.execSQL(ADD_HISTORY_VISIBLE_COLUMN)
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

    private fun SQLiteStatement.bindRecord(record: MediaBackupLedgerRecord) {
        bindText(1, record.accountId)
        bindText(2, record.localKey)
        bindNullableText(3, record.local?.displayName)
        bindNullableLong(4, record.local?.size)
        bindNullableText(5, record.local?.revision)
        bindNullableText(6, record.receipt?.localRevision)
        bindNullableLong(7, record.receipt?.localSize)
        bindNullableText(8, record.receipt?.remotePath)
        bindNullableText(9, record.receipt?.remoteEtag)
        bindNullableLong(10, record.receipt?.verifiedAtEpochMillis)
        bindText(11, record.transferState.name)
        bindLong(12, record.attemptCount.toLong())
        bindLong(13, record.updatedAtEpochMillis)
        bindNullableText(14, record.failureMessage)
    }

    private fun SQLiteStatement.readRecord(): MediaBackupLedgerRecord {
        val accountId = getText(0)
        val localKey = getText(1)
        val local = if (isNull(2)) {
            check(isNull(3) && isNull(4))
            null
        } else {
            LocalMediaObject(
                key = localKey,
                displayName = getText(2),
                size = getLong(3),
                revision = getText(4),
            )
        }
        val receipt = if (isNull(7)) {
            check(isNull(5) && isNull(6) && isNull(8) && isNull(9))
            null
        } else {
            MediaBackupReceipt(
                localKey = localKey,
                localRevision = getText(5),
                localSize = getLong(6),
                remotePath = getText(7),
                remoteEtag = getText(8),
                verifiedAtEpochMillis = getLong(9),
            )
        }
        val attemptCount = getLong(11)
        check(attemptCount in 0..Int.MAX_VALUE.toLong())
        return MediaBackupLedgerRecord(
            accountId = accountId,
            local = local,
            receipt = receipt,
            transferState = enumValueOf(getText(10)),
            attemptCount = attemptCount.toInt(),
            updatedAtEpochMillis = getLong(12),
            failureMessage = nullableText(13),
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

    private companion object {
        const val SCHEMA_VERSION = 3
        const val SCHEMA_MIGRATION_BUSY_TIMEOUT_MILLIS = 5_000

        const val SELECT_COLUMNS =
            "SELECT account_id, local_key, local_display_name, local_size, local_revision, " +
                "receipt_local_revision, receipt_local_size, remote_path, remote_etag, verified_at, " +
                "transfer_state, attempt_count, updated_at, failure_message FROM media_backup_ledger"

        val CREATE_TABLE =
            """
            CREATE TABLE media_backup_ledger (
                account_id TEXT NOT NULL,
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

        const val CREATE_ACCOUNT_STATE_INDEX =
            "CREATE INDEX media_backup_account_state_updated " +
                "ON media_backup_ledger(account_id, transfer_state, updated_at DESC, local_key DESC)"

        const val CREATE_ACCOUNT_UPDATED_INDEX =
            "CREATE INDEX media_backup_account_updated " +
                "ON media_backup_ledger(account_id, updated_at DESC, local_key DESC)"

        const val CREATE_ACCOUNT_REMOTE_PATH_INDEX =
            "CREATE INDEX media_backup_account_remote_path_updated " +
                "ON media_backup_ledger(account_id, remote_path, updated_at DESC, local_key DESC)"

        val UPSERT_RECORD =
            """
            INSERT INTO media_backup_ledger (
                account_id, local_key, local_display_name, local_size, local_revision,
                receipt_local_revision, receipt_local_size, remote_path, remote_etag, verified_at,
                transfer_state, attempt_count, updated_at, failure_message, history_visible
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(account_id, local_key) DO UPDATE SET
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
                history_visible = 1
            """.trimIndent()
    }
}
