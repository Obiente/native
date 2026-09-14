package dev.obiente.nextcloudnative.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertNotNull

class DesktopSqliteRuntimeTest {
    @Test fun distributedDriverIncludesEverySupportedDesktopBinary() {
        val loader = BundledSQLiteDriver::class.java.classLoader
        listOf(
            "linux_x64/libsqliteJni.so",
            "linux_arm64/libsqliteJni.so",
            "osx_x64/libsqliteJni.dylib",
            "osx_arm64/libsqliteJni.dylib",
            "windows_x64/sqliteJni.dll",
        ).forEach { binary ->
            assertNotNull(loader.getResource("natives/$binary"), "Missing SQLite runtime: $binary")
        }
    }

    @Test fun nativeDriverCanOpenAndQueryTheRuntime() = verifyDesktopSqliteRuntime()
}
