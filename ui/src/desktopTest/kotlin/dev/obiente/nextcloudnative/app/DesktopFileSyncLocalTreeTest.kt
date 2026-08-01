package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            root.resolve("Notes/.today.md.nextcloud-native-backup-4d6f8828-7d52-4f2d-945b-f46aa4c97b41")
                .writeText("protected")
            root.resolve("Notes/.draft.md.nextcloud-native-download-801e8c87-592d-4d1d-9d77-61383e22bd3a")
                .writeText("partial")

            DesktopFileSyncLocalTree(root.toFile()).scan()

            assertEquals("protected", root.resolve("Notes/today.md").toFile().readText())
            assertFalse(
                Files.exists(
                    root.resolve("Notes/.draft.md.nextcloud-native-download-801e8c87-592d-4d1d-9d77-61383e22bd3a"),
                ),
            )
            assertTrue(Files.exists(root.resolve("Notes")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `marker like user files are preserved unless they carry an owned uuid suffix`() {
        val root = Files.createTempDirectory("desktop-sync-owned-stage-")
        try {
            root.resolve(".notes.nextcloud-native-download-archive").writeText("keep")
            root.resolve(".notes.nextcloud-native-backup-personal").writeText("keep too")

            val entries = DesktopFileSyncLocalTree(root.toFile()).scan().map { it.entry.relativePath }

            assertTrue(".notes.nextcloud-native-download-archive" in entries)
            assertTrue(".notes.nextcloud-native-backup-personal" in entries)
            assertTrue(Files.exists(root.resolve(".notes.nextcloud-native-download-archive")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scan reclaims the backup after a completed replacement`() {
        val root = Files.createTempDirectory("desktop-sync-visible-backup-")
        try {
            root.resolve("notes.txt").writeText("published")
            val backup = ".notes.txt.nextcloud-native-backup-0b88c03f-55d1-4ccb-b92e-aa8ee32caf65"
            root.resolve(backup).writeText("protected original")

            val entries = DesktopFileSyncLocalTree(root.toFile()).scan().map { it.entry.relativePath }

            assertTrue("notes.txt" in entries)
            assertFalse(backup in entries)
            assertFalse(Files.exists(root.resolve(backup)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ambiguous replacement artifacts are bounded by the next recovery scan`() {
        val root = Files.createTempDirectory("desktop-sync-bounded-backup-")
        val token = "00000000-0000-4000-8000-000000000001"
        try {
            root.resolve("notes.txt").writeText("published")
            val backup = root.resolve(".notes.txt.nextcloud-native-backup-$token").apply {
                writeText("protected original")
            }
            val download = root.resolve(".notes.txt.nextcloud-native-download-$token").apply {
                writeText("complete replacement")
            }

            DesktopFileSyncLocalTree(root.toFile()).scan()
            DesktopFileSyncLocalTree(root.toFile()).scan()

            assertFalse(Files.exists(backup))
            assertFalse(Files.exists(download))
            assertEquals("published", root.resolve("notes.txt").toFile().readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `same size edits with a preserved timestamp change the local revision`() {
        val root = Files.createTempDirectory("desktop-sync-content-revision-")
        try {
            val file = root.resolve("notes.txt")
            file.writeText("first")
            val fixedTime = FileTime.fromMillis(1_700_000_000_000L)
            Files.setLastModifiedTime(file, fixedTime)
            val tree = DesktopFileSyncLocalTree(root.toFile())
            val before = requireNotNull(tree.resolve("notes.txt")).entry

            file.writeText("later")
            Files.setLastModifiedTime(file, fixedTime)
            val after = requireNotNull(tree.resolve("notes.txt")).entry

            assertTrue(before.revision != after.revision)
            assertTrue(before.contentHash != after.contentHash)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unchanged files reuse the persisted digest when change metadata is stable`() {
        val root = Files.createTempDirectory("desktop-sync-digest-cache-")
        try {
            root.resolve("notes.txt").writeText("unchanged")
            var digestCount = 0
            val tree = DesktopFileSyncLocalTree(
                root.toFile(),
                changeTokenProvider = { "stable-change-token" },
            ) {
                digestCount += 1
                "a".repeat(64)
            }
            val first = tree.scan()
            val cachedRevisions = first.associate { document ->
                document.entry.relativePath to document.entry.revision
            }

            val second = tree.scan(cachedRevisions)

            assertEquals(first.map { it.entry }, second.map { it.entry })
            assertEquals(1, digestCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing stable change metadata forces content to be rehashed`() {
        val root = Files.createTempDirectory("desktop-sync-digest-fail-closed-")
        try {
            root.resolve("notes.txt").writeText("unchanged")
            var digestCount = 0
            val tree = DesktopFileSyncLocalTree(
                root.toFile(),
                changeTokenProvider = { null },
            ) {
                digestCount += 1
                "b".repeat(64)
            }
            val first = tree.scan()

            tree.scan(first.associate { it.entry.relativePath to it.entry.revision })

            assertEquals(2, digestCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `operations reject an ancestor replaced by a symlink after scan`() {
        val root = Files.createTempDirectory("desktop-sync-symlink-race-")
        val outside = Files.createTempDirectory("desktop-sync-outside-")
        val staged = Files.createTempFile("desktop-sync-upload-", ".tmp").toFile()
        try {
            root.resolve("Notes").createDirectories()
            root.resolve("Notes/today.md").writeText("inside")
            outside.resolve("today.md").writeText("outside")
            val tree = DesktopFileSyncLocalTree(root.toFile())
            val scanned = tree.scan().single { it.entry.relativePath == "Notes/today.md" }
            Files.move(root.resolve("Notes"), root.resolve("Notes-original"))
            val linked = runCatching { Files.createSymbolicLink(root.resolve("Notes"), outside) }.isSuccess
            if (!linked) return

            assertFailsWith<IllegalArgumentException> {
                tree.stageForUpload("Notes/today.md", staged, maximumBytes = 1024L)
            }
            assertFailsWith<IllegalArgumentException> {
                tree.delete("Notes/today.md", scanned.entry.revision)
            }
            assertEquals("outside", outside.resolve("today.md").toFile().readText())
        } finally {
            staged.delete()
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory to file replacement protects the original until complete bytes are published`() {
        val root = Files.createTempDirectory("desktop-sync-type-replace-")
        val source = Files.createTempFile("desktop-sync-source-", ".tmp")
        try {
            root.resolve("Notes/today.md/child.txt").parent.createDirectories()
            root.resolve("Notes/today.md/child.txt").writeText("protected")
            source.writeText("replacement file")
            val tree = DesktopFileSyncLocalTree(root.toFile())
            val before = requireNotNull(tree.resolve("Notes/today.md"))

            tree.replaceWithFile("Notes/today.md", source.toFile(), before.entry.revision)

            assertEquals("replacement file", root.resolve("Notes/today.md").toFile().readText())
            assertFalse(root.toFile().walkTopDown().any { ".nextcloud-native-" in it.name })
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(source)
        }
    }

    @Test
    fun `file to directory replacement keeps a recoverable backup until publication`() {
        val root = Files.createTempDirectory("desktop-sync-directory-replace-")
        try {
            root.resolve("Albums").createDirectories()
            root.resolve("Albums/Shared").writeText("protected")
            val tree = DesktopFileSyncLocalTree(root.toFile())
            val before = requireNotNull(tree.resolve("Albums/Shared"))

            tree.replaceWithDirectory("Albums/Shared", before.entry.revision)

            assertTrue(Files.isDirectory(root.resolve("Albums/Shared")))
            assertFalse(root.toFile().walkTopDown().any { ".nextcloud-native-" in it.name })
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
