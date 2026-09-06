package dev.obiente.nextcloudnative

import androidx.work.NetworkType
import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.localUploadFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class AndroidDurableUploadCleanupPruningTest {
    @Test
    fun `oversized terminal cleanup state is quarantined before capability loading`() {
        val jobs = (1..1_025).map { index ->
            fixtureJob(
                index = index,
                state = DurableUploadState.Failed,
                cleanupPending = true,
            )
        }
        val retained = durableUploadCapabilityRetainedSelectionIds(jobs)
        val storedIds = jobs.map { job -> job.request.file.selectionId }
        val scan = DurableUploadCapabilityRecoveryScan<CapabilityPhase>()

        var loaded = 0
        val snapshot = scan.loadPage(emptyMap(), storedIds, maximumRows = 1_024) {
            loaded += 1
            CapabilityPhase.CleanupPending
        }

        assertTrue(retained.isEmpty())
        assertFalse(snapshot.scanComplete)
        assertTrue(snapshot.recoveryQuarantined)
        assertTrue(snapshot.capabilities.isEmpty())
        assertEquals(0, loaded)
    }

    @Test
    fun `reconciliation runs terminal cleanup without consulting upload work ownership`() = runBlocking {
        val pending = fixtureJob(index = 1, cleanupPending = true)
        val history = fixtureJob(index = 2)
        val cleaned = mutableListOf<AndroidDurableMultipartUploadJob>()

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(pending, history),
            schedulerOwns = { error("Local cleanup must not wait for upload work ownership.") },
            cleanupCapability = { job -> cleaned += job },
            schedule = { error("Terminal cleanup must not use network-constrained upload work.") },
        )

        assertTrue(allScheduled)
        assertEquals(listOf(pending), cleaned)
    }

    @Test
    fun `permanently malformed terminal cleanup preserves its terminal status`() {
        val storage = MemoryStorage()
        val store = AndroidDurableMultipartUploadStore(storage, PlaintextCipher)
        val quarantined = fixtureJob(index = 1, cleanupPending = true)
        val retained = fixtureJob(index = 2, cleanupPending = true)
        store.add(quarantined)
        store.add(retained)

        val reconciled = reconcileTerminalDurableUploadCapabilityCleanup(
            release = { onQuarantined ->
                onQuarantined()
                false
            },
            complete = { store.completeCapabilityCleanup(quarantined.id) },
        )

        assertTrue(reconciled)
        assertEquals(
            listOf(quarantined.copy(capabilityCleanupPending = false), retained),
            AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list(),
        )
    }

    @Test
    fun `account cleanup accepts a quarantined unknowable capability`() {
        var quarantined = false

        val ready = releaseAndroidDurableUploadCapabilityForAccountRemoval { onQuarantined ->
            onQuarantined()
            quarantined = true
            false
        }

        assertTrue(ready)
        assertTrue(quarantined)
    }

    @Test
    fun `only queued uploads are eligible for connected upload work`() {
        val queued = fixtureJob(index = 1, state = DurableUploadState.Queued)
        val pending = fixtureJob(index = 2, cleanupPending = true)

        assertEquals(NetworkType.CONNECTED, networkTypeForDurableUploadWork(queued))
        assertFailsWith<IllegalArgumentException> {
            networkTypeForDurableUploadWork(pending)
        }
    }

    @Test
    fun `terminal cleanup scheduling failure is aggregated without blocking queued work`() = runBlocking {
        val pending = fixtureJob(index = 1, cleanupPending = true)
        val queued = fixtureJob(index = 2, state = DurableUploadState.Queued)
        val attempts = mutableListOf<String>()

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(pending, queued),
            cleanupCapability = { job ->
                attempts += job.id
                error("synthetic cleanup failure")
            },
            schedule = { job -> attempts += job.id },
        )

        assertFalse(allScheduled)
        assertEquals(listOf(pending.id, queued.id), attempts)
    }

    @Test
    fun `unsupported account registry runs terminal cleanup but not queued uploads`() = runBlocking {
        val pending = fixtureJob(index = 1, cleanupPending = true)
        val queued = fixtureJob(index = 2, state = DurableUploadState.Queued)
        val cleaned = mutableListOf<AndroidDurableMultipartUploadJob>()
        val scheduled = mutableListOf<AndroidDurableMultipartUploadJob>()
        val accountResolutionAvailable = androidCredentialFreeRegistryAllowsAccountResolution(
            """{"version":99,"accounts":[]}""",
        )

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(pending, queued),
            allowQueuedScheduling = accountResolutionAvailable,
            cleanupCapability = cleaned::add,
            schedule = scheduled::add,
        )

        assertFalse(accountResolutionAvailable)
        assertTrue(allScheduled)
        assertEquals(listOf(pending), cleaned)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `wrong typed account registry runs terminal cleanup but not queued uploads`() = runBlocking {
        val pending = fixtureJob(index = 1, cleanupPending = true)
        val queued = fixtureJob(index = 2, state = DurableUploadState.Queued)
        val cleaned = mutableListOf<AndroidDurableMultipartUploadJob>()
        val scheduled = mutableListOf<AndroidDurableMultipartUploadJob>()
        val accountResolutionAvailable = durableUploadAccountResolutionAvailable {
            throw ClassCastException("synthetic wrong-typed account registry")
        }

        val allScheduled = reconcileQueuedDurableUploads(
            jobs = listOf(pending, queued),
            allowQueuedScheduling = accountResolutionAvailable,
            cleanupCapability = cleaned::add,
            schedule = scheduled::add,
        )

        assertFalse(accountResolutionAvailable)
        assertTrue(allScheduled)
        assertEquals(listOf(pending), cleaned)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `terminal cleanup reconciliation preserves cancellation`() = runBlocking {
        val pending = fixtureJob(index = 1, cleanupPending = true)

        assertFailsWith<CancellationException> {
            reconcileQueuedDurableUploads(
                jobs = listOf(pending),
                cleanupCapability = { throw CancellationException("recovery stopped") },
                schedule = { error("Terminal cleanup must not schedule upload work.") },
            )
        }
        Unit
    }

    @Test
    fun `pruning retains terminal rows until capability cleanup commits`() {
        val pending = fixtureJob(
            index = 1,
            cleanupPending = true,
            updatedAt = 0L,
        )
        val history = (2..70).map { index -> fixtureJob(index = index, updatedAt = index.toLong()) }

        val pruned = pruneDurableUploadJobs(history + pending)

        assertEquals(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS, pruned.size)
        assertTrue(pending in pruned)
        assertFalse(pruned.any { job -> job.id == fixtureId(2) })
    }

    @Test
    fun `terminal transition persists cleanup until its commit`() {
        val storage = MemoryStorage()
        val store = AndroidDurableMultipartUploadStore(storage, PlaintextCipher)
        val queued = fixtureJob(index = 1, state = DurableUploadState.Queued)
        store.add(queued)

        store.transition(queued.id, DurableUploadState.Queued, DurableUploadState.Failed, "failed")

        assertTrue(AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list().single().capabilityCleanupPending)
        store.completeCapabilityCleanup(queued.id)
        assertFalse(AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list().single().capabilityCleanupPending)
    }

    @Test
    fun `legacy terminal rows default to completed cleanup`() {
        val storage = MemoryStorage()
        val store = AndroidDurableMultipartUploadStore(storage, PlaintextCipher)
        val queued = fixtureJob(index = 1, state = DurableUploadState.Queued)
        store.add(queued)
        store.transition(queued.id, DurableUploadState.Queued, DurableUploadState.Failed, "failed")
        val legacy = JSONArray(checkNotNull(storage.value))
        legacy.getJSONObject(0).remove("capabilityCleanupPending")
        storage.value = legacy.toString()

        val restored = AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list().single()

        assertFalse(restored.capabilityCleanupPending)
    }

    @Test
    fun `explicit cleanup marker booleans restore without coercion`() {
        listOf(true, false).forEach { cleanupPending ->
            val storage = MemoryStorage()
            AndroidDurableMultipartUploadStore(storage, PlaintextCipher).add(
                fixtureJob(index = 1, cleanupPending = cleanupPending),
            )

            val restored = AndroidDurableMultipartUploadStore(storage, PlaintextCipher).list().single()

            assertEquals(cleanupPending, restored.capabilityCleanupPending)
        }
    }

    @Test
    fun `malformed cleanup markers leave the recovery queue unchanged`() {
        val malformedValues = listOf(
            "true",
            "false",
            1,
            JSONObject.NULL,
            JSONObject().put("pending", true),
            JSONArray().put(true),
        )

        malformedValues.forEach { malformedValue ->
            val storage = MemoryStorage()
            val store = AndroidDurableMultipartUploadStore(storage, PlaintextCipher)
            store.add(fixtureJob(index = 1, cleanupPending = true))
            val malformedSnapshot = JSONArray(checkNotNull(storage.value)).also { array ->
                array.getJSONObject(0).put("capabilityCleanupPending", malformedValue)
            }.toString()
            storage.value = malformedSnapshot

            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { store.list() }
            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
                store.add(fixtureJob(index = 2, state = DurableUploadState.Queued))
            }
            assertEquals(malformedSnapshot, storage.value)
        }
    }

    @Test
    fun `pending cleanup consumes bounded queue capacity`() {
        val pending = (1..AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS).map { index ->
            fixtureJob(index = index, cleanupPending = true)
        }

        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                current = pending,
                job = fixtureJob(index = 100, state = DurableUploadState.Queued),
            )
        }
    }

    @Test
    fun `cleanup commit failure retries and preserves cancellation`() {
        var recoveryRequests = 0
        assertEquals(
            "retry",
            resultAfterDurableUploadCapabilityRelease(
                releaseCapability = { true },
                completeCapabilityCleanup = { error("queue unavailable") },
                onCleanupRetained = { recoveryRequests += 1 },
                releasedResult = "finished",
                retainedResult = "retry",
            ),
        )
        assertEquals(1, recoveryRequests)
        assertFailsWith<CancellationException> {
            resultAfterDurableUploadCapabilityRelease(
                releaseCapability = { true },
                completeCapabilityCleanup = { throw CancellationException("worker stopped") },
                releasedResult = "finished",
                retainedResult = "retry",
            )
        }
    }

    private fun fixtureJob(
        index: Int,
        state: DurableUploadState = DurableUploadState.Completed,
        cleanupPending: Boolean = false,
        updatedAt: Long = index.toLong(),
    ): AndroidDurableMultipartUploadJob {
        val cardId = index.toLong()
        val scope = DurableUploadScope("deck-attachment", cardId.toString())
        val request = NextcloudMultipartUploadRequest(
            method = NextcloudApiMethod.POST,
            relativePath = "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/$cardId/attachments",
            file = localUploadFile(
                selectionId = "selection-${index.toString().padStart(16, '0')}",
                displayName = "fixture-$index.txt",
                mimeType = "text/plain",
                sizeBytes = 16L,
            ),
            maximumFileBytes = 1024L,
        )
        return AndroidDurableMultipartUploadJob(
            id = fixtureId(index),
            accountId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = state,
            message = null,
            capabilityCleanupPending = cleanupPending,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun fixtureId(index: Int) = "upload-${index.toString().padStart(16, '0')}"

    private class MemoryStorage(var value: String? = null) : AndroidDurableMultipartUploadEncryptedStorage {
        override fun read(): String? = value
        override fun write(value: String): Boolean = true.also { this.value = value }
    }

    private object PlaintextCipher : AndroidDurableMultipartUploadCipher {
        override fun encrypt(value: String): String = value
        override fun decrypt(value: String): String = value
    }
}
