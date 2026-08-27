package dev.obiente.nextcloudnative.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal fun ensureDesktopFileSyncUploadCleanupTable(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS sync_upload_cleanups (" +
            "pair_id TEXT NOT NULL, upload_id TEXT NOT NULL, record BLOB NOT NULL, " +
            "PRIMARY KEY(pair_id, upload_id), " +
            "FOREIGN KEY(pair_id) REFERENCES sync_pairs(id) ON DELETE CASCADE)",
    )
}

internal fun readDesktopFileSyncUploadCleanups(
    connection: SQLiteConnection,
    pairId: String,
): List<FileSyncPendingUploadCleanup> = buildList {
    connection.prepare(
        "SELECT upload_id, record FROM sync_upload_cleanups WHERE pair_id = ? " +
            "ORDER BY upload_id LIMIT ${MAX_FILE_SYNC_WORK_ITEMS + 1}",
    ).use { statement ->
        statement.bindText(1, pairId)
        while (statement.step()) {
            val cleanup = decodeFileSyncPendingUploadCleanupRecord(statement.getBlob(1))
            require(cleanup.uploadId == statement.getText(0))
            add(cleanup)
        }
    }
    require(size <= MAX_FILE_SYNC_WORK_ITEMS) {
        "The desktop folder sync database contains too many upload cleanups."
    }
}

internal fun persistDesktopFileSyncUploadCleanups(
    connection: SQLiteConnection,
    pairId: String,
    before: List<FileSyncPendingUploadCleanup>,
    after: List<FileSyncPendingUploadCleanup>,
) {
    val old = before.associateBy(FileSyncPendingUploadCleanup::uploadId)
    val current = after.associateBy(FileSyncPendingUploadCleanup::uploadId)
    (old.keys - current.keys).forEach { uploadId ->
        connection.prepare(
            "DELETE FROM sync_upload_cleanups WHERE pair_id = ? AND upload_id = ?",
        ).use { statement ->
            statement.bindText(1, pairId)
            statement.bindText(2, uploadId)
            check(!statement.step())
        }
    }
    current.forEach { (uploadId, cleanup) ->
        if (old[uploadId] != cleanup) {
            connection.prepare(
                "INSERT OR REPLACE INTO sync_upload_cleanups(pair_id, upload_id, record) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.bindText(1, pairId)
                statement.bindText(2, uploadId)
                statement.bindBlob(3, encodeFileSyncPendingUploadCleanupRecord(cleanup))
                check(!statement.step())
            }
        }
    }
}

internal fun persistDesktopFileSyncPairUploadCleanups(
    connection: SQLiteConnection,
    pairId: String,
    before: FileSyncPair?,
    after: FileSyncPair,
) = persistDesktopFileSyncUploadCleanups(
    connection,
    pairId,
    before?.pendingUploadCleanups.orEmpty(),
    after.pendingUploadCleanups,
)

internal sealed interface DesktopFileSyncUploadCleanupChange {
    data class Retain(val cleanup: FileSyncPendingUploadCleanup) : DesktopFileSyncUploadCleanupChange
    data class Complete(val uploadId: String) : DesktopFileSyncUploadCleanupChange
}

internal fun persistDesktopFileSyncExecutionUploadCleanups(
    connection: SQLiteConnection,
    pairId: String,
    stored: List<FileSyncPendingUploadCleanup>,
    change: DesktopFileSyncUploadCleanupChange?,
) {
    val after = when (change) {
        null -> stored
        is DesktopFileSyncUploadCleanupChange.Retain ->
            stored.filterNot { it.uploadId == change.cleanup.uploadId } + change.cleanup
        is DesktopFileSyncUploadCleanupChange.Complete ->
            stored.filterNot { it.uploadId == change.uploadId }
    }
    persistDesktopFileSyncUploadCleanups(connection, pairId, stored, after)
}

internal fun migrateInlineDesktopFileSyncUploadCleanups(
    connection: SQLiteConnection,
    upsertPairRecord: (FileSyncPair) -> Unit,
) {
    val inline = buildList {
        connection.prepare("SELECT id, record FROM sync_pairs ORDER BY id").use { statement ->
            while (statement.step()) {
                val pair = decodeFileSyncPairRecord(statement.getBlob(1))
                require(pair.id == statement.getText(0))
                if (pair.pendingUploadCleanups.isNotEmpty()) add(pair)
            }
        }
    }
    if (inline.isEmpty()) return
    connection.execSQL("BEGIN IMMEDIATE TRANSACTION")
    try {
        inline.forEach { pair ->
            val stored = readDesktopFileSyncUploadCleanups(connection, pair.id)
            val combined = (stored + pair.pendingUploadCleanups)
                .distinctBy(FileSyncPendingUploadCleanup::uploadId)
            persistDesktopFileSyncUploadCleanups(connection, pair.id, stored, combined)
            upsertPairRecord(pair)
        }
        connection.execSQL("COMMIT")
    } catch (failure: Throwable) {
        runCatching { connection.execSQL("ROLLBACK") }
        throw failure
    }
}
