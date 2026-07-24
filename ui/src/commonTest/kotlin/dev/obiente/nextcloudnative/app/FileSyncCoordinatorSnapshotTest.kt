package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class FileSyncCoordinatorSnapshotTest {
    @Test
    fun `snapshot round trip preserves pair baselines and decisions deterministically`() {
        var first = state()
        first = scanFileSyncPair(
            first,
            "pair-b",
            listOf(
                LocalSyncEntry(
                    "vault.md",
                    SyncEntryKind.File,
                    "local-2",
                    contentHash = "sha256:" + "11".repeat(32),
                ),
            ),
            listOf(
                RemoteSyncEntry(
                    "vault.md",
                    SyncEntryKind.File,
                    "remote-2",
                    contentHash = "sha256:" + "22".repeat(32),
                ),
            ),
            100,
        )
        first = resolveFileSyncDecision(
            first,
            "pair-b",
            first.pairs.single { it.id == "pair-b" }.workItems.single().id,
            FileSyncDecisionChoice.UseLocal,
        )
        val second = first.copy(pairs = first.pairs.reversed())

        val encoded = encodeFileSyncCoordinatorSnapshot(first)
        val restored = decodeFileSyncCoordinatorSnapshot(encoded)

        assertEquals(
            first.copy(pairs = first.pairs.sortedBy(FileSyncPair::id)),
            restored,
        )
        assertEquals(encoded.decodeToString(), encodeFileSyncCoordinatorSnapshot(second).decodeToString())
        assertFalse(encoded.decodeToString().contains("password", ignoreCase = true))
        assertFalse(encoded.decodeToString().contains("authorization", ignoreCase = true))
    }

    @Test
    fun `interrupted running command is ready after restart and not considered complete`() {
        var state = FileSyncCoordinatorState(
            listOf(
                pair(
                    id = "pair-a",
                    baselines = listOf(baseline("note.md", "l1", "r1")),
                ),
            ),
        )
        state = scanFileSyncPair(
            state,
            "pair-a",
            listOf(LocalSyncEntry("note.md", SyncEntryKind.File, "l2")),
            listOf(RemoteSyncEntry("note.md", SyncEntryKind.File, "r1")),
            10,
        )
        state = claimNextFileSyncOperation(state, "pair-a", 20).state

        val restored = decodeFileSyncCoordinatorSnapshot(encodeFileSyncCoordinatorSnapshot(state))
        val restoredPair = restored.pairs.single()

        assertEquals(FileSyncExecutionState.Ready, restoredPair.workItems.single().state)
        assertEquals(1, restoredPair.workItems.single().attemptCount)
        assertEquals(listOf(baseline("note.md", "l1", "r1")), restoredPair.baselines)
    }

    @Test
    fun `strict decoder rejects future malformed and inconsistent state`() {
        assertFails {
            decodeFileSyncCoordinatorSnapshot(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFails {
            decodeFileSyncCoordinatorSnapshot(
                """{"schemaVersion":2,"pairs":[]}""".encodeToByteArray(),
            )
        }
        assertFails {
            decodeFileSyncCoordinatorSnapshot(
                """
                {
                  "schemaVersion":1,
                  "pairs":[{
                    "id":"pair",
                    "accountId":"account",
                    "localRootId":"root",
                    "remoteRootPath":"Notes",
                    "direction":"Bidirectional",
                    "conflictPolicy":"Ask",
                    "deletionPolicy":"Ask",
                    "deviceLabel":"Phone",
                    "baselines":[],
                    "workItems":[],
                    "nextWorkId":0,
                    "lastScanEpochMillis":null
                  }]
                }
                """.trimIndent().encodeToByteArray(),
            )
        }
    }

    private fun state() = FileSyncCoordinatorState(
        pairs = listOf(
            pair("pair-b", listOf(baseline("vault.md", "local-1", "remote-1"))),
            pair("pair-a"),
        ),
    )

    private fun pair(id: String, baselines: List<FileSyncBaseline> = emptyList()) = FileSyncPair(
        id = id,
        accountId = "account-a",
        localRootId = "bookmark-$id",
        remoteRootPath = "Notes",
        configuration = FileSyncConfiguration(
            deviceLabel = "Test phone",
            networkPolicy = FileSyncNetworkPolicy.Unmetered,
            powerPolicy = FileSyncPowerPolicy.Charging,
        ),
        baselines = baselines,
    )

    private fun baseline(path: String, localRevision: String, remoteEtag: String) =
        FileSyncBaseline(path, SyncEntryKind.File, localRevision, remoteEtag)
}
