package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Runs before application services and only uses an in-memory database. */
internal fun verifyDesktopSqliteRuntime() {
    BundledSQLiteDriver().open(":memory:").use { connection ->
        connection.prepare("SELECT sqlite_version()").use { statement ->
            check(statement.step()) { "SQLite runtime verification returned no version." }
            check(statement.getText(0).isNotBlank()) { "SQLite runtime verification returned an empty version." }
        }
    }
}
