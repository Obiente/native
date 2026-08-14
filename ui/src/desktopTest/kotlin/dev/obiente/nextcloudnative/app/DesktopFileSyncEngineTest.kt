package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopFileSyncEngineTest {
    @Test
    fun `resolved conflict guard rejects a replacement operation`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Notes",
            configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
        )
        var state = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            localEntries = listOf(LocalSyncEntry("note.md", SyncEntryKind.File, "local-1")),
            remoteEntries = listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "remote-1")),
            nowEpochMillis = 10L,
        )
        val resolvedWorkId = state.pairs.single().workItems.single().id
        state = resolveFileSyncDecision(
            state,
            pair.id,
            resolvedWorkId,
            FileSyncDecisionChoice.UseLocal,
        )
        assertTrue(state.pairs.single().retainsResolvedFileSyncDecision(resolvedWorkId))

        state = scanFileSyncPair(
            state,
            pair.id,
            localEntries = emptyList(),
            remoteEntries = listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "remote-1")),
            nowEpochMillis = 20L,
        )

        assertFalse(state.pairs.single().retainsResolvedFileSyncDecision(resolvedWorkId))
        assertIs<FileSyncOperation.Download>(state.pairs.single().workItems.single().operation)
    }

    @Test
    fun `resolved conflict guard accepts a retained skip decision`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Notes",
            configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
        )
        var state = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            localEntries = listOf(LocalSyncEntry("note.md", SyncEntryKind.File, "local-1")),
            remoteEntries = listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "remote-1")),
            nowEpochMillis = 10L,
        )
        val resolvedWorkId = state.pairs.single().workItems.single().id
        state = resolveFileSyncDecision(
            state,
            pair.id,
            resolvedWorkId,
            FileSyncDecisionChoice.Skip,
        )
        state = scanFileSyncPair(
            state,
            pair.id,
            localEntries = listOf(LocalSyncEntry("note.md", SyncEntryKind.File, "local-1")),
            remoteEntries = listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "remote-1")),
            nowEpochMillis = 20L,
        )

        assertTrue(state.pairs.single().retainsResolvedFileSyncDecision(resolvedWorkId))
        assertEquals(FileSyncExecutionState.Skipped, state.pairs.single().workItems.single().state)
    }

    @Test
    fun `baseline capacity is checked before executing expanding operations`() {
        val baselines = setOf("existing.jpg", "second.jpg")

        requireDesktopFileSyncBaselineCapacity(
            FileSyncOperation.Upload("existing.jpg", expectedRemoteEtag = null),
            baselines,
            maximumEntries = 2,
        )
        assertFails {
            requireDesktopFileSyncBaselineCapacity(
                FileSyncOperation.Upload("new.jpg", expectedRemoteEtag = null),
                baselines,
                maximumEntries = 2,
            )
        }
        assertFails {
            requireDesktopFileSyncBaselineCapacity(
                FileSyncOperation.KeepBoth(
                    "existing.jpg",
                    "existing (Workstation).jpg",
                    "existing (server).jpg",
                ),
                baselines,
                maximumEntries = 3,
            )
        }
    }

    @Test
    fun `desktop execution preparation retries a large queue in one state transition`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Pictures",
            configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
        )
        val planned = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            localEntries = listOf(
                LocalSyncEntry("first.jpg", SyncEntryKind.File, "local-first"),
                LocalSyncEntry("second.jpg", SyncEntryKind.File, "local-second"),
            ),
            remoteEntries = emptyList(),
            nowEpochMillis = 10L,
        ).pairs.single()
        val retryable = planned.workItems[0].copy(
            state = FileSyncExecutionState.Failed,
            attemptCount = 1,
            failureMessage = "Temporary failure",
        )
        val exhausted = planned.workItems[1].copy(
            state = FileSyncExecutionState.Failed,
            attemptCount = MAX_FILE_SYNC_ATTEMPTS,
            failureMessage = "Repeated failure",
        )
        val failed = planned.copy(workItems = listOf(retryable, exhausted))

        val automatic = failed.prepareForDesktopExecution(resetExhaustedFailures = false)
        assertEquals(FileSyncExecutionState.Ready, automatic.workItems[0].state)
        assertEquals(FileSyncExecutionState.Failed, automatic.workItems[1].state)

        val explicit = failed.prepareForDesktopExecution(resetExhaustedFailures = true)
        assertTrue(explicit.workItems.all { it.state == FileSyncExecutionState.Ready })
        assertEquals(0, explicit.workItems[1].attemptCount)
    }

    @Test
    fun `remote mutation paths include the configured pair root`() {
        assertEquals(
            "Photography/Albums/2026/cover.jpg",
            desktopFileSyncRemoteMutationPath(
                remoteRootPath = "/Photography/Albums/",
                relativePath = "/2026/cover.jpg/",
            ),
        )
        assertEquals("cover.jpg", desktopFileSyncRemoteMutationPath("", "cover.jpg"))
    }

    @Test
    fun `stale owned stages are reclaimed without touching lookalikes`() {
        val root = Files.createTempDirectory("desktop-sync-stage-recovery-").toFile()
        try {
            val stale = root.resolve("nextcloud-native-download-${UUID.randomUUID()}.tmp").apply {
                writeText("partial download")
            }
            val unknownPrefix = root.resolve("nextcloud-native-preview-${UUID.randomUUID()}.tmp").apply {
                writeText("keep")
            }
            val invalidToken = root.resolve("nextcloud-native-download-not-a-uuid.tmp").apply {
                writeText("keep")
            }
            val ownedDirectory = root.resolve("nextcloud-native-download-${UUID.randomUUID()}.tmp").apply {
                mkdir()
            }

            assertEquals(1, reclaimDesktopFileSyncStages(root))

            assertFalse(stale.exists())
            assertTrue(unknownPrefix.isFile)
            assertTrue(invalidToken.isFile)
            assertTrue(ownedDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}
