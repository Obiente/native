package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSyncPolicyTest {
    @Test
    fun `portable globs match names and paths case insensitively`() {
        assertTrue(fileSyncGlobMatches("*.raf", "Shoot/DSC_1001.RAF"))
        assertTrue(fileSyncGlobMatches("**/*.jp?g", "Shoot/Exports/preview.JPEG"))
        assertTrue(fileSyncGlobMatches("cache/**", "cache"))
        assertTrue(fileSyncGlobMatches("cache/**", "cache/previews/a.jpg"))
        assertFalse(fileSyncGlobMatches("Shoot/*.raf", "Shoot/Day-1/a.raf"))
        assertFalse(fileSyncGlobMatches("*.raf", "Shoot/a.jpg"))
    }

    @Test
    fun `selective roots retain ancestors and descendants but ignore rules win`() {
        val configuration = FileSyncConfiguration(
            deviceLabel = "Workstation",
            selectedPaths = listOf("Photos/2026/July", "Documents/report.odt"),
            ignoredPatterns = listOf("**/.thumbnails/**", "*.part"),
        )

        assertTrue(configuration.includesSyncPath("Photos", SyncEntryKind.Directory))
        assertTrue(configuration.includesSyncPath("Photos/2026", SyncEntryKind.Directory))
        assertTrue(configuration.includesSyncPath("Photos/2026/July", SyncEntryKind.Directory))
        assertTrue(configuration.includesSyncPath("Photos/2026/July/a.raf", SyncEntryKind.File))
        assertTrue(configuration.includesSyncPath("Documents/report.odt", SyncEntryKind.File))
        assertFalse(configuration.includesSyncPath("Photos/2025", SyncEntryKind.Directory))
        assertFalse(configuration.includesSyncPath("Photos/2026/July/a.part", SyncEntryKind.File))
        assertFalse(
            configuration.includesSyncPath(
                "Photos/2026/July/.thumbnails/a.jpg",
                SyncEntryKind.File,
            ),
        )
    }

    @Test
    fun `first matching priority rule wins and unmatched files follow`() {
        val configuration = FileSyncConfiguration(
            deviceLabel = "Workstation",
            priorityRules = listOf(
                FileSyncPriorityRule("**/*.raf"),
                FileSyncPriorityRule("**/*.jpg"),
                FileSyncPriorityRule("**/*.jpeg"),
            ),
        )

        assertEquals(0, configuration.fileSyncPriority("Shoot/a.RAF"))
        assertEquals(1, configuration.fileSyncPriority("Shoot/a.jpg"))
        assertEquals(2, configuration.fileSyncPriority("Shoot/a.jpeg"))
        assertEquals(3, configuration.fileSyncPriority("Shoot/a.xmp"))
    }
}
