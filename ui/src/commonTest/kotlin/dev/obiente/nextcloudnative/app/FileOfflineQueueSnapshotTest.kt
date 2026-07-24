package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileOfflineQueueSnapshotTest {
    @Test
    fun `round trip preserves account scoped records jobs and conflict state`() {
        val key = FileOfflineKey("account-a", "Notes/Vault.md")
        val state = FileOfflineQueueState(
            records = listOf(
                FileOfflinePinRecord(
                    descriptor = FileOfflineDescriptor(
                        key = key,
                        displayName = "Vault.md",
                        remoteEtag = "\"remote-2\"",
                        size = 42,
                        mimeType = "text/markdown",
                    ),
                    intent = FileOfflineIntent.Pinned,
                    localRevision = "local-1",
                    syncedRemoteEtag = "\"remote-1\"",
                    attentionReason = FileSyncDecisionReason.SimultaneousEdit,
                    updatedAtEpochMillis = 123,
                ),
            ),
            jobs = listOf(
                FileOfflineJob(
                    id = 7,
                    key = key,
                    operation = FileOfflineJobOperation.Download,
                    expectedRemoteEtag = "\"remote-2\"",
                    expectedLocalRevision = "local-1",
                    status = FileOfflineJobStatus.NeedsAttention,
                    attemptCount = 2,
                    enqueuedAtEpochMillis = 100,
                    failureMessage = "Resolve the conflict before downloading.",
                ),
            ),
            nextJobId = 8,
        )

        assertEquals(state, decodeFileOfflineQueueSnapshot(encodeFileOfflineQueueSnapshot(state)))
    }

    @Test
    fun `interrupted running work is requeued and never considered complete`() {
        val key = FileOfflineKey("account-a", "Media/photo.jpg")
        val running = FileOfflineQueueState(
            records = listOf(
                FileOfflinePinRecord(
                    descriptor = FileOfflineDescriptor(
                        key,
                        "photo.jpg",
                        "\"generation\"",
                        1_024,
                        "image/jpeg",
                    ),
                    intent = FileOfflineIntent.Pinned,
                    localRevision = null,
                    syncedRemoteEtag = null,
                    updatedAtEpochMillis = 10,
                ),
            ),
            jobs = listOf(
                FileOfflineJob(
                    id = 1,
                    key = key,
                    operation = FileOfflineJobOperation.Download,
                    expectedRemoteEtag = "\"generation\"",
                    expectedLocalRevision = null,
                    status = FileOfflineJobStatus.Running,
                    attemptCount = 1,
                    enqueuedAtEpochMillis = 10,
                    failureMessage = "transient worker detail",
                ),
            ),
            nextJobId = 2,
        )

        val restored = decodeFileOfflineQueueSnapshot(encodeFileOfflineQueueSnapshot(running))

        assertEquals(FileOfflineJobStatus.Queued, restored.jobs.single().status)
        assertNull(restored.jobs.single().failureMessage)
        assertEquals(FileOfflineAvailability.Queued, restored.availability(key))
        assertNull(restored.records.single().localRevision)
    }

    @Test
    fun `snapshot is deterministic and contains no credential surface`() {
        val first = sampleState(recordsReversed = false)
        val second = sampleState(recordsReversed = true)

        val encoded = encodeFileOfflineQueueSnapshot(first)

        assertEquals(encoded.decodeToString(), encodeFileOfflineQueueSnapshot(second).decodeToString())
        assertFalse(encoded.decodeToString().contains("password", ignoreCase = true))
        assertFalse(encoded.decodeToString().contains("authorization", ignoreCase = true))
    }

    @Test
    fun `strict decoding rejects malformed future and inconsistent snapshots`() {
        assertFailsWith<IllegalArgumentException> {
            decodeFileOfflineQueueSnapshot(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertFailsWith<IllegalArgumentException> {
            decodeFileOfflineQueueSnapshot(
                """{"schemaVersion":2,"records":[],"jobs":[],"nextJobId":1}""".encodeToByteArray(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            decodeFileOfflineQueueSnapshot(
                """
                {
                  "schemaVersion":1,
                  "records":[],
                  "jobs":[{
                    "id":1,
                    "accountId":"account-a",
                    "relativePath":"orphan.txt",
                    "operation":"Download",
                    "expectedRemoteEtag":"etag",
                    "expectedLocalRevision":null,
                    "status":"Queued",
                    "attemptCount":0,
                    "enqueuedAtEpochMillis":0,
                    "failureMessage":null
                  }],
                  "nextJobId":2
                }
                """.trimIndent().encodeToByteArray(),
            )
        }
    }

    private fun sampleState(recordsReversed: Boolean): FileOfflineQueueState {
        val records = listOf("b.txt", "a.txt").mapIndexed { index, path ->
            FileOfflinePinRecord(
                descriptor = FileOfflineDescriptor(
                    FileOfflineKey("account-a", path),
                    path,
                    "\"etag-$index\"",
                    index.toLong(),
                    "text/plain",
                ),
                intent = FileOfflineIntent.Pinned,
                localRevision = null,
                syncedRemoteEtag = null,
                updatedAtEpochMillis = index.toLong(),
            )
        }.let { if (recordsReversed) it.reversed() else it }
        return FileOfflineQueueState(records = records)
    }
}
