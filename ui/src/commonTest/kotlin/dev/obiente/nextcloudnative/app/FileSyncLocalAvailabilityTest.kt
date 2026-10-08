package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSyncLocalAvailabilityTest {
    @Test
    fun `unavailable local subtrees are withheld from both sides and nested reports collapse`() {
        val observed = withholdUnavailableFileSyncPaths(
            localEntries = listOf(
                local(".git", SyncEntryKind.Directory),
                local(".git/objects", SyncEntryKind.Directory),
                local(".git/objects/ab", SyncEntryKind.Directory),
                local(".git/HEAD"),
                local(".git/objects pack"),
                local("README.md"),
            ),
            remoteEntries = listOf(
                remote(".git/objects", SyncEntryKind.Directory),
                remote(".git/objects/ab/cdef"),
                remote(".git/index.lock"),
                remote(".git/HEAD"),
            ),
            unavailable = listOf(
                FileSyncUnavailableLocalItem(".git/objects/ab", FileSyncLocalUnavailableReason.Vanished),
                FileSyncUnavailableLocalItem(".git/objects", FileSyncLocalUnavailableReason.Unreadable),
                FileSyncUnavailableLocalItem(".git/index.lock", FileSyncLocalUnavailableReason.Vanished),
            ),
        )

        assertEquals(
            listOf(".git", ".git/HEAD", ".git/objects pack", "README.md"),
            observed.localEntries.map(LocalSyncEntry::relativePath),
        )
        assertEquals(listOf(".git/HEAD"), observed.remoteEntries.map(RemoteSyncEntry::relativePath))
        assertEquals(
            listOf(
                FileSyncUnavailableLocalItem(".git/index.lock", FileSyncLocalUnavailableReason.Vanished),
                FileSyncUnavailableLocalItem(".git/objects", FileSyncLocalUnavailableReason.Unreadable),
            ),
            observed.unavailable,
        )
    }

    @Test
    fun `an unreadable synced git file is never planned as a deletion and is reported`() {
        val configuration = FileSyncConfiguration(
            deviceLabel = "Workstation",
            deletionPolicy = FileSyncDeletionPolicy.Propagate,
        )
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Repo",
            configuration = configuration,
            baselines = listOf(
                FileSyncBaseline(".git", SyncEntryKind.Directory, "local-dir", "remote-dir"),
                FileSyncBaseline(".git/index", SyncEntryKind.File, "local-index", "remote-index"),
                FileSyncBaseline(".git/objects", SyncEntryKind.Directory, "local-objects", "remote-objects"),
                FileSyncBaseline(".git/objects/ab", SyncEntryKind.File, "local-object", "remote-object"),
            ),
        )
        val unavailable = listOf(
            FileSyncUnavailableLocalItem(".git/index", FileSyncLocalUnavailableReason.Unreadable),
            FileSyncUnavailableLocalItem(".git/objects", FileSyncLocalUnavailableReason.Unreadable),
        )
        val observed = withholdUnavailableFileSyncPaths(
            localEntries = listOf(
                LocalSyncEntry(".git", SyncEntryKind.Directory, "local-dir"),
                local(".git/COMMIT_EDITMSG"),
            ),
            remoteEntries = listOf(
                RemoteSyncEntry(".git", SyncEntryKind.Directory, "remote-dir"),
                RemoteSyncEntry(".git/index", SyncEntryKind.File, "remote-index"),
                RemoteSyncEntry(".git/objects", SyncEntryKind.Directory, "remote-objects"),
                RemoteSyncEntry(".git/objects/ab", SyncEntryKind.File, "remote-object"),
            ),
            unavailable = unavailable,
        )

        val scanned = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            observed.localEntries,
            observed.remoteEntries,
            nowEpochMillis = 10L,
            maximumWorkItems = MAX_FILE_SYNC_WORK_ITEMS - observed.unavailable.size,
        ).withUnavailableFileSyncReports(pair.id, observed.unavailable).pairs.single()

        val operations = scanned.workItems.associate { it.relativePath to it.operation }
        assertIs<FileSyncOperation.Upload>(operations.getValue(".git/COMMIT_EDITMSG"))
        assertTrue(operations.values.none { it is FileSyncOperation.DeleteLocal || it is FileSyncOperation.DeleteRemote })
        val indexReport = assertIs<FileSyncOperation.Skipped>(operations.getValue(".git/index"))
        assertTrue(indexReport.reason.contains(".git/index could not be read"))
        assertIs<FileSyncOperation.Skipped>(operations.getValue(".git/objects"))
        assertEquals(pair.baselines, scanned.baselines)
        assertEquals(
            setOf(".git/index", ".git/objects"),
            scanned.workItems.filter { it.state == FileSyncExecutionState.Skipped }.map { it.relativePath }.toSet(),
        )
        assertTrue(scanned.workItems.all { it.id < scanned.nextWorkId })

        val recovered = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(scanned)),
            pair.id,
            localEntries = listOf(
                LocalSyncEntry(".git", SyncEntryKind.Directory, "local-dir"),
                local(".git/COMMIT_EDITMSG"),
                LocalSyncEntry(".git/index", SyncEntryKind.File, "local-index"),
                LocalSyncEntry(".git/objects", SyncEntryKind.Directory, "local-objects"),
                LocalSyncEntry(".git/objects/ab", SyncEntryKind.File, "local-object"),
            ),
            remoteEntries = observed.remoteEntries + listOf(
                RemoteSyncEntry(".git/index", SyncEntryKind.File, "remote-index"),
                RemoteSyncEntry(".git/objects", SyncEntryKind.Directory, "remote-objects"),
                RemoteSyncEntry(".git/objects/ab", SyncEntryKind.File, "remote-object"),
            ),
            nowEpochMillis = 20L,
        ).pairs.single()

        assertEquals(listOf(".git/COMMIT_EDITMSG"), recovered.workItems.map(FileSyncWorkItem::relativePath))
    }

    @Test
    fun `reports are bounded skip planned paths and keep reasons within persisted limits`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Repo",
            configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
        )
        val planned = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            localEntries = listOf(local("README.md")),
            remoteEntries = emptyList(),
            nowEpochMillis = 10L,
        )
        val longName = "segment-".repeat(200).trimEnd('-')
        val unavailable = listOf(
            FileSyncUnavailableLocalItem("README.md", FileSyncLocalUnavailableReason.Vanished),
            FileSyncUnavailableLocalItem(".git/$longName", FileSyncLocalUnavailableReason.Unsupported),
            FileSyncUnavailableLocalItem(".git/index.lock", FileSyncLocalUnavailableReason.Vanished),
        )

        val reported = planned.withUnavailableFileSyncReports(pair.id, unavailable, maximumReports = 1)
            .pairs.single()

        assertEquals(listOf("README.md", ".git/$longName"), reported.workItems.map(FileSyncWorkItem::relativePath))
        val reason = (reported.workItems.last().operation as FileSyncOperation.Skipped).reason
        assertTrue(reason.length <= MAX_FILE_SYNC_FAILURE_LENGTH)
        assertTrue(reason.contains("..."))
        assertTrue(reason.endsWith("is not a regular file or folder."))
        assertEquals(planned, planned.withUnavailableFileSyncReports(pair.id, emptyList()))
    }

    @Test
    fun `persisted pairs accept generated reports and reject unobserved skips with other reasons`() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Repo",
            configuration = FileSyncConfiguration(deviceLabel = "Workstation"),
        )
        val item = FileSyncUnavailableLocalItem(".git/index.lock", FileSyncLocalUnavailableReason.Vanished)
        val reported = FileSyncCoordinatorState(listOf(pair)).withUnavailableFileSyncReports(pair.id, listOf(item))
            .pairs.single()
        val report = reported.workItems.single()

        assertEquals(reported, reported.copy())
        assertFailsWith<IllegalArgumentException> {
            reported.copy(
                workItems = listOf(
                    report.copy(operation = FileSyncOperation.Skipped(report.relativePath, "Forged skip.")),
                ),
            )
        }
    }

    private fun local(path: String, kind: SyncEntryKind = SyncEntryKind.File) =
        LocalSyncEntry(path, kind, "local-$path", size = if (kind == SyncEntryKind.File) 1L else null)

    private fun remote(path: String, kind: SyncEntryKind = SyncEntryKind.File) =
        RemoteSyncEntry(path, kind, "remote-$path", size = if (kind == SyncEntryKind.File) 1L else null)
}
