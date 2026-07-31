package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFileSyncLocalTreeTest {
    @Test
    fun `scan prunes ignored trees and retains selective parents`() {
        val root = Files.createTempDirectory("desktop-sync-local-")
        try {
            root.resolve("Photos/Keep").createDirectories()
            root.resolve("Photos/Ignore/cache").createDirectories()
            root.resolve("Photos/Keep/a.RAF").writeText("raw")
            root.resolve("Photos/Keep/a.jpg").writeText("jpeg")
            root.resolve("Photos/Ignore/cache/private.jpg").writeText("ignored")
            val configuration = FileSyncConfiguration(
                deviceLabel = "Desktop",
                selectedPaths = listOf("Photos/Keep"),
                ignoredPatterns = listOf("Photos/Ignore"),
            )

            val entries = DesktopFileSyncLocalTree(root.toFile()).scan { path, kind ->
                configuration.includesSyncPath(path, kind)
            }.map { it.entry.relativePath }

            assertEquals(listOf("Photos", "Photos/Keep", "Photos/Keep/a.RAF", "Photos/Keep/a.jpg"), entries)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replacement is revision guarded and publishes only complete bytes`() {
        val root = Files.createTempDirectory("desktop-sync-write-")
        val source = Files.createTempFile("desktop-sync-source-", ".tmp")
        try {
            root.resolve("Notes").createDirectories()
            root.resolve("Notes/today.md").writeText("old")
            source.writeText("complete replacement")
            val tree = DesktopFileSyncLocalTree(root.toFile())
            val before = requireNotNull(tree.resolve("Notes/today.md"))

            tree.writeFile("Notes/today.md", source.toFile(), before.entry.revision)

            assertEquals("complete replacement", root.resolve("Notes/today.md").toFile().readText())
            assertFalse(root.toFile().walkTopDown().any { ".nextcloud-native-" in it.name })
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun `scan restores an owned backup after interrupted replacement`() {
        val root = Files.createTempDirectory("desktop-sync-recover-")
        try {
            root.resolve("Notes").createDirectories()
            root.resolve("Notes/.today.md.nextcloud-native-backup-token").writeText("protected")
            root.resolve("Notes/.draft.md.nextcloud-native-download-token").writeText("partial")

            DesktopFileSyncLocalTree(root.toFile()).scan()

            assertEquals("protected", root.resolve("Notes/today.md").toFile().readText())
            assertFalse(Files.exists(root.resolve("Notes/.draft.md.nextcloud-native-download-token")))
            assertTrue(Files.exists(root.resolve("Notes")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
