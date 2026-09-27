package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHomeWorkspaceLayoutStorageTest {
    @Test
    fun `account removal clears canonical and legacy workspace preferences without touching a peer`() {
        val directory = Files.createTempDirectory("nextcloud-native-home-workspace-removal-test").toFile()
        val node = "dev/obiente/nextcloudnative/test-home-workspace-${UUID.randomUUID()}"
        val preferences = Preferences.userRoot().node(node)
        val cleanupPreferences = Preferences.userRoot().node(node)
        val storage = DesktopHomeWorkspaceLayoutStorage(cleanupPreferences, directory.resolve("preferences.lock"))
        val canonical = "a".repeat(64)
        val legacy = "b".repeat(64)
        val retained = "c".repeat(64)
        val removedKeys = listOf(canonical, legacy).flatMap(::workspacePreferenceKeys)
        val retainedKeys = workspacePreferenceKeys(retained)
        try {
            removedKeys.forEach { key -> preferences.put(key, "removed") }
            retainedKeys.forEach { key -> preferences.put(key, "retained") }
            preferences.flush()

            storage.removeAccount(canonical, legacy)
            preferences.sync()

            removedKeys.forEach { key -> assertNull(preferences.get(key, null), key) }
            retainedKeys.forEach { key -> assertEquals("retained", preferences.get(key, null), key) }
        } finally {
            preferences.removeNode()
            Preferences.userRoot().flush()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `concurrent storage instances admit only one conditional promotion`() {
        val directory = Files.createTempDirectory("nextcloud-native-home-workspace-lock-test").toFile()
        val node = "dev/obiente/nextcloudnative/test-home-workspace-${UUID.randomUUID()}"
        val firstPreferences = Preferences.userRoot().node(node)
        val secondPreferences = Preferences.userRoot().node(node)
        val first = DesktopHomeWorkspaceLayoutStorage(firstPreferences, directory.resolve("preferences.lock"))
        val second = DesktopHomeWorkspaceLayoutStorage(secondPreferences, directory.resolve("preferences.lock"))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstResult = executor.submit<Boolean> {
                start.await()
                first.writeIfAbsent("pins", "first")
            }
            val secondResult = executor.submit<Boolean> {
                start.await()
                second.writeIfAbsent("pins", "second")
            }

            start.countDown()
            val results = listOf(
                firstResult.get(5, TimeUnit.SECONDS),
                secondResult.get(5, TimeUnit.SECONDS),
            )

            assertEquals(1, results.count { it })
            assertEquals(1, results.count { !it })
            assertTrue(first.read("pins") in setOf("first", "second"))
            assertFalse(Files.isSymbolicLink(directory.resolve("preferences.lock").toPath()))
        } finally {
            executor.shutdownNow()
            firstPreferences.removeNode()
            Preferences.userRoot().flush()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `lock path follows each desktop platform state directory`() {
        val home = Files.createTempDirectory("nextcloud-native-home-workspace-path-test").toFile()
        try {
            assertEquals(
                home.resolve("state/Nextcloud Native/Home Workspace/preferences.lock"),
                desktopHomeWorkspaceLockFile("Linux", home, mapOf("XDG_STATE_HOME" to home.resolve("state").path)),
            )
            assertEquals(
                home.resolve("local/Nextcloud Native/Home Workspace/preferences.lock"),
                desktopHomeWorkspaceLockFile("Windows 11", home, mapOf("LOCALAPPDATA" to home.resolve("local").path)),
            )
            assertEquals(
                home.resolve("Library/Application Support/Nextcloud Native/Home Workspace/preferences.lock"),
                desktopHomeWorkspaceLockFile("Mac OS X", home, emptyMap()),
            )
        } finally {
            home.deleteRecursively()
        }
    }

    private fun workspacePreferenceKeys(accountScopeDigest: String): List<String> =
        homeWorkspaceAccountPersistenceKeys(accountScopeDigest).toList()
}
