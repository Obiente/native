package dev.obiente.nextcloudnative.app

import androidx.sqlite.SQLiteConnection

internal fun readDesktopFileSyncRoots(connection: SQLiteConnection): List<DesktopFileSyncRootRecord> {
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

internal fun upsertDesktopFileSyncRoot(connection: SQLiteConnection, root: DesktopFileSyncRootRecord) {
    connection.prepare(
        "INSERT OR REPLACE INTO sync_roots(id, absolute_path, display_name) VALUES (?, ?, ?)",
    ).use { statement ->
        statement.bindText(1, root.id)
        statement.bindText(2, root.absolutePath)
        statement.bindText(3, root.displayName)
        check(!statement.step())
    }
}

internal fun readDesktopFileSyncBaselines(connection: SQLiteConnection, pairId: String): List<FileSyncBaseline> {
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

internal fun persistDesktopFileSyncBaselines(
    connection: SQLiteConnection,
    pairId: String,
    before: List<FileSyncBaseline>,
    after: List<FileSyncBaseline>,
) {
    val old = before.associateBy(FileSyncBaseline::relativePath)
    val current = after.associateBy(FileSyncBaseline::relativePath)
    (old.keys - current.keys).forEach { path ->
        connection.prepare("DELETE FROM sync_baselines WHERE pair_id = ? AND relative_path = ?").use { statement ->
            statement.bindText(1, pairId)
            statement.bindText(2, path)
            check(!statement.step())
        }
    }
    current.forEach { (path, baseline) ->
        if (old[path] != baseline) upsertDesktopFileSyncBaseline(connection, pairId, baseline)
    }
}

internal fun upsertDesktopFileSyncBaseline(
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
