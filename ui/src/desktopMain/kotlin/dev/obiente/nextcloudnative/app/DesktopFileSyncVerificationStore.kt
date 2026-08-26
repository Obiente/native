package dev.obiente.nextcloudnative.app

import androidx.sqlite.SQLiteConnection

internal fun readDesktopFileSyncContentVerificationProgress(
    connection: SQLiteConnection,
    pairId: String,
): List<FileSyncContentVerificationProgress> {
    val progress = buildList {
        connection.prepare(
            "SELECT relative_path, record FROM sync_content_verification WHERE pair_id = ? " +
                "ORDER BY relative_path LIMIT ${MAX_FILE_SYNC_ENTRIES + 1}",
        ).use { statement ->
            statement.bindText(1, pairId)
            while (statement.step()) {
                val item = decodeFileSyncContentVerificationProgressRecord(statement.getBlob(1))
                require(item.candidate.relativePath == statement.getText(0))
                add(item)
            }
        }
    }
    require(progress.size <= MAX_FILE_SYNC_ENTRIES) {
        "The desktop folder sync database contains too much verification progress for one pair."
    }
    return progress
}

internal fun persistDesktopFileSyncContentVerificationProgress(
    connection: SQLiteConnection,
    pairId: String,
    before: List<FileSyncContentVerificationProgress>,
    after: List<FileSyncContentVerificationProgress>,
) {
    val old = before.associateBy { it.candidate.relativePath }
    val current = after.associateBy { it.candidate.relativePath }
    (old.keys - current.keys).forEach { path ->
        connection.prepare(
            "DELETE FROM sync_content_verification WHERE pair_id = ? AND relative_path = ?",
        ).use { statement ->
            statement.bindText(1, pairId)
            statement.bindText(2, path)
            check(!statement.step())
        }
    }
    current.forEach { (path, progress) ->
        if (old[path] != progress) {
            connection.prepare(
                "INSERT OR REPLACE INTO sync_content_verification(pair_id, relative_path, record) " +
                    "VALUES (?, ?, ?)",
            ).use { statement ->
                statement.bindText(1, pairId)
                statement.bindText(2, path)
                statement.bindBlob(3, encodeFileSyncContentVerificationProgressRecord(progress))
                check(!statement.step())
            }
        }
    }
}
