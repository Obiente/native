package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.afterProcessRecovery
import dev.obiente.nextcloudnative.app.localUploadFile
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray

class AndroidDurableMultipartUploadPolicyTest {
    @Test
    fun `account cleanup removes a row only after its source capability is released`() = runBlocking {
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeAndroidDurableUploadJobs(
                jobs = listOf(first, second),
                cancelWork = { job -> events += "cancel:${job.id}" },
                releaseCapability = { job ->
                    events += "release:${job.id}"
                    job == first
                },
                removeJob = { jobId -> events += "remove:$jobId" },
            )
        }

        assertEquals(
            listOf(
                "cancel:${first.id}",
                "cancel:${second.id}",
                "release:${first.id}",
                "remove:${first.id}",
                "release:${second.id}",
            ),
            events,
        )
    }

    @Test
    fun `removing an account deletes only its queued upload recovery rows`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val store = AndroidDurableMultipartUploadStore(storage, FakeDurableUploadCipher())
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        store.add(first)
        store.add(second)

        assertEquals(listOf(first), store.removeForAccount(ACCOUNT_A))
        assertEquals(listOf(second), store.list())
    }

    @Test
    fun `encrypted queue read and decryption failures preserve recoverable jobs`() {
        listOf("read", "decrypt").forEach { failureMode ->
            val storage = FakeDurableUploadEncryptedStorage()
            val cipher = FakeDurableUploadCipher()
            val recoverable = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
            AndroidDurableMultipartUploadStore(storage, cipher).add(recoverable)
            val encryptedBeforeFailure = storage.value
            if (failureMode == "read") storage.readFailure = IOException("synthetic read failure")
            if (failureMode == "decrypt") cipher.decryptFailure = IOException("synthetic decrypt failure")

            val restarted = AndroidDurableMultipartUploadStore(storage, cipher)
            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
                restarted.add(fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43))
            }
            assertEquals(encryptedBeforeFailure, storage.value)

            storage.readFailure = null
            cipher.decryptFailure = null
            assertEquals(listOf(recoverable), AndroidDurableMultipartUploadStore(storage, cipher).list())
        }
    }

    @Test
    fun `corrupt encrypted queue is never replaced by a later write`() {
        val storage = FakeDurableUploadEncryptedStorage(value = "not-json")
        val cipher = FakeDurableUploadCipher()
        val restarted = AndroidDurableMultipartUploadStore(storage, cipher)

        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
            restarted.add(fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42))
        }

        assertEquals("not-json", storage.value)
    }

    @Test
    fun `one malformed row blocks queue rewrites without dropping valid rows`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val cipher = FakeDurableUploadCipher()
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        AndroidDurableMultipartUploadStore(storage, cipher).apply {
            add(first)
            add(second)
        }
        val validSnapshot = requireNotNull(storage.value)
        val malformed = JSONArray(validSnapshot).also { array ->
            array.getJSONObject(1).remove("relativePath")
        }.toString()
        storage.value = malformed
        val restarted = AndroidDurableMultipartUploadStore(storage, cipher)

        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.remove(first.id) }
        assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
            restarted.transition(
                first.id,
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
                null,
            )
        }
        assertEquals(malformed, storage.value)

        storage.value = validSnapshot
        assertEquals(listOf(first, second), AndroidDurableMultipartUploadStore(storage, cipher).list())
    }

    @Test
    fun `failed queue write leaves the previous restart snapshot readable`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val cipher = FakeDurableUploadCipher()
        val first = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val second = fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43)
        AndroidDurableMultipartUploadStore(storage, cipher).add(first)
        val snapshotBeforeWrite = storage.value
        storage.failWrites = true

        assertFailsWith<IllegalStateException> {
            AndroidDurableMultipartUploadStore(storage, cipher).add(second)
        }

        assertEquals(snapshotBeforeWrite, storage.value)
        storage.failWrites = false
        assertEquals(listOf(first), AndroidDurableMultipartUploadStore(storage, cipher).list())
    }

    @Test
    fun `duplicate and oversized queue snapshots block rewrites`() {
        val storage = FakeDurableUploadEncryptedStorage()
        val cipher = FakeDurableUploadCipher()
        AndroidDurableMultipartUploadStore(storage, cipher).add(
            fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42),
        )
        val storedRow = JSONArray(requireNotNull(storage.value)).getJSONObject(0)
        val invalidSnapshots = listOf(
            JSONArray().put(storedRow).put(storedRow).toString(),
            JSONArray().also { array ->
                repeat(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS + 1) {
                    array.put(storedRow)
                }
            }.toString(),
        )

        invalidSnapshots.forEach { invalidSnapshot ->
            storage.value = invalidSnapshot
            val restarted = AndroidDurableMultipartUploadStore(storage, cipher)

            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> { restarted.list() }
            assertFailsWith<AndroidDurableMultipartUploadRecoveryException> {
                restarted.add(fixtureJob(index = 2, account = ACCOUNT_A, cardId = 43))
            }
            assertEquals(invalidSnapshot, storage.value)
        }
    }

    @Test
    fun `deck attachment resource binds board stack card and request path`() {
        val scope = DurableUploadScope("deck-attachment", "42")
        val resource = resolveDurableUploadResource(scope, fixtureRequest(cardId = 42))

        assertEquals("deck-attachment", resource.feature)
        assertEquals("7", resource.boardId)
        assertEquals("11", resource.stackId)
        assertEquals("42", resource.itemId)

        assertFailsWith<IllegalArgumentException> {
            resolveDurableUploadResource(scope, fixtureRequest(cardId = 43))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveDurableUploadResource(
                scope,
                fixtureRequest(cardId = 42).copy(
                    relativePath = "/index.php/apps/deck/api/v1.1/boards/7/cards/42/attachments",
                ),
            )
        }
    }

    @Test
    fun `queue rejects duplicates and bounds account and card ownership`() {
        val fourForCard = (1..4).map { index ->
            fixtureJob(index = index, account = ACCOUNT_A, cardId = 42)
        }
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                fourForCard,
                fixtureJob(index = 5, account = ACCOUNT_A, cardId = 42),
            )
        }

        requireCanAddDurableUpload(
            fourForCard,
            fixtureJob(index = 5, account = ACCOUNT_A, cardId = 43),
        )
        requireCanAddDurableUpload(
            fourForCard,
            fixtureJob(index = 5, account = ACCOUNT_B, cardId = 42),
        )

        val duplicateSelection = fixtureJob(index = 20, account = ACCOUNT_B, cardId = 99).copy(
            request = fixtureRequest(cardId = 99, selectionId = fourForCard.first().request.file.selectionId),
        )
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(fourForCard, duplicateSelection)
        }

        val twelveForAccount = (1..12).map { index ->
            fixtureJob(index = index, account = ACCOUNT_A, cardId = index.toLong())
        }
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                twelveForAccount,
                fixtureJob(index = 13, account = ACCOUNT_A, cardId = 13),
            )
        }
    }

    @Test
    fun `queue pruning retains active work and only the newest terminal history`() {
        val active = listOf(
            fixtureJob(index = 1, account = ACCOUNT_A, cardId = 1),
            fixtureJob(index = 2, account = ACCOUNT_B, cardId = 2),
        )
        val terminal = (1..70).map { index ->
            fixtureJob(
                index = index + 100,
                account = if (index % 2 == 0) ACCOUNT_A else ACCOUNT_B,
                cardId = (index + 100).toLong(),
                state = DurableUploadState.Completed,
                updatedAt = index.toLong(),
            )
        }

        val pruned = pruneDurableUploadJobs(active + terminal)

        assertEquals(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS, pruned.size)
        assertTrue(pruned.containsAll(active))
        assertFalse(pruned.any { it.state == DurableUploadState.Completed && it.updatedAtEpochMillis <= 8L })
        assertTrue(pruned.any { it.state == DurableUploadState.Completed && it.updatedAtEpochMillis == 70L })
    }

    @Test
    fun `only queued work can start and recovered in flight work becomes unknown`() {
        assertTrue(
            isAllowedDurableUploadTransition(
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
            ),
        )
        assertTrue(
            isAllowedDurableUploadTransition(
                DurableUploadState.Uploading,
                DurableUploadState.OutcomeUnknown,
            ),
        )
        assertFalse(
            isAllowedDurableUploadTransition(
                DurableUploadState.OutcomeUnknown,
                DurableUploadState.Uploading,
            ),
        )
        assertFalse(
            isAllowedDurableUploadTransition(
                DurableUploadState.Failed,
                DurableUploadState.Uploading,
            ),
        )
        assertEquals(
            DurableUploadState.OutcomeUnknown,
            DurableUploadState.Uploading.afterProcessRecovery(),
        )
        assertEquals(
            DurableUploadState.Queued,
            DurableUploadState.Queued.afterProcessRecovery(),
        )
    }

    @Test
    fun `only definite client rejection avoids an unknown outcome`() {
        assertEquals(DurableUploadState.Completed, durableUploadStateForHttpResponse(201))
        assertEquals(DurableUploadState.Failed, durableUploadStateForHttpResponse(400))
        assertEquals(DurableUploadState.Failed, durableUploadStateForHttpResponse(409))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(302))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(408))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(425))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(429))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(500))
    }

    @Test
    fun `retained background account is deferred without reading its credential`() {
        val retainedSession = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "alice",
            appPassword = "fixture-password",
        )
        val accountId = NextcloudDocumentIds.accountKey(retainedSession)

        assertEquals(
            DurableUploadAccountMismatchOutcome.DeferAccountActivation,
            durableUploadAccountMismatchOutcome(
                accountId,
                AndroidAccountRetentionSnapshot.Available(listOf(retainedSession.accountRecord())),
            ),
        )
    }

    @Test
    fun `unreadable account registry defers queued upload recovery`() {
        assertEquals(
            DurableUploadAccountMismatchOutcome.RetryAccountRecovery,
            durableUploadAccountMismatchOutcome(ACCOUNT_A, AndroidAccountRetentionSnapshot.Unavailable),
        )
    }

    @Test
    fun `active account with unreadable credential keeps its upload scheduled`() {
        val retainedSession = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "alice",
            appPassword = "fixture-password",
        )
        val accountId = NextcloudDocumentIds.accountKey(retainedSession)

        assertEquals(
            DurableUploadAccountMismatchOutcome.RetryAccountRecovery,
            durableUploadAccountMismatchOutcome(
                accountId,
                AndroidAccountRetentionSnapshot.Available(
                    accounts = listOf(retainedSession.accountRecord()),
                    activeAccountId = retainedSession.accountId,
                ),
            ),
        )
    }

    @Test
    fun `valid account registry without expected account makes upload unavailable`() {
        val retainedSession = NextcloudSession(
            serverUrl = "https://cloud.example.test/nextcloud",
            loginName = "alice",
            appPassword = "fixture-password",
        )
        val accountId = NextcloudDocumentIds.accountKey(retainedSession)

        assertEquals(
            DurableUploadAccountMismatchOutcome.AccountUnavailable,
            durableUploadAccountMismatchOutcome(
                accountId,
                AndroidAccountRetentionSnapshot.Available(emptyList()),
            ),
        )
        assertEquals(
            DurableUploadAccountMismatchOutcome.AccountUnavailable,
            durableUploadAccountMismatchOutcome(
                accountId,
                AndroidAccountRetentionSnapshot.Available(
                    listOf(retainedSession.copy(loginName = "another-account").accountRecord()),
                ),
            ),
        )
    }

    @Test
    fun `account activation resumes only its queued uploads`() {
        val queuedForA = fixtureJob(index = 1, account = ACCOUNT_A, cardId = 42)
        val queuedForB = fixtureJob(index = 2, account = ACCOUNT_B, cardId = 43)
        val completedForA = fixtureJob(
            index = 3,
            account = ACCOUNT_A,
            cardId = 44,
            state = DurableUploadState.Completed,
        )

        assertEquals(
            listOf(queuedForA),
            queuedDurableUploadsForAccount(listOf(queuedForA, queuedForB, completedForA), ACCOUNT_A),
        )
    }

    @Test
    fun `background upload resolves the queued account instead of the active account`() {
        val queuedSession = fixtureSession("alice")
        val activeSession = fixtureSession("bob")
        val loadedAccountIds = mutableListOf<String>()

        val resolved = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            accounts = listOf(activeSession.accountRecord(), queuedSession.accountRecord()),
            loadSession = { accountId ->
                loadedAccountIds += accountId.storageKey
                when (accountId) {
                    queuedSession.accountId -> queuedSession
                    activeSession.accountId -> activeSession
                    else -> null
                }
            },
        )

        assertEquals(queuedSession, resolved)
        assertEquals(listOf(queuedSession.accountId.storageKey), loadedAccountIds)
    }

    @Test
    fun `background upload never substitutes another account on the same server path`() {
        val queuedSession = fixtureSession("alice")
        val otherSession = fixtureSession("bob")
        var credentialRead = false

        val missing = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            accounts = listOf(otherSession.accountRecord()),
            loadSession = {
                credentialRead = true
                otherSession
            },
        )

        assertNull(missing)
        assertFalse(credentialRead)
    }

    @Test
    fun `background upload rejects a credential that does not match its registry owner`() {
        val queuedSession = fixtureSession("alice")
        val otherSession = fixtureSession("bob")

        val resolved = resolveDurableUploadSession(
            expectedAccountId = NextcloudDocumentIds.accountKey(queuedSession),
            accounts = listOf(queuedSession.accountRecord(), otherSession.accountRecord()),
            loadSession = { otherSession },
        )

        assertNull(resolved)
    }

    private fun fixtureJob(
        index: Int,
        account: String,
        cardId: Long,
        state: DurableUploadState = DurableUploadState.Queued,
        updatedAt: Long = index.toLong(),
    ): AndroidDurableMultipartUploadJob {
        val scope = DurableUploadScope("deck-attachment", cardId.toString())
        val request = fixtureRequest(cardId, selectionId = selectionId(index))
        return AndroidDurableMultipartUploadJob(
            id = "upload-${index.toString().padStart(16, '0')}",
            accountId = account,
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = state,
            message = null,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun fixtureRequest(
        cardId: Long,
        selectionId: String = selectionId(cardId.toInt()),
    ): NextcloudMultipartUploadRequest = NextcloudMultipartUploadRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/$cardId/attachments",
        file = localUploadFile(
            selectionId = selectionId,
            displayName = "fixture-$cardId.txt",
            mimeType = "text/plain",
            sizeBytes = 16L,
        ),
        maximumFileBytes = 1024L,
    )

    private fun selectionId(index: Int): String = "selection-${index.toString().padStart(16, '0')}"

    private fun fixtureSession(loginName: String): NextcloudSession = NextcloudSession(
        serverUrl = "https://cloud.example.test/nextcloud",
        loginName = loginName,
        appPassword = "fixture-password",
    )

    private companion object {
        const val ACCOUNT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

private class FakeDurableUploadEncryptedStorage(
    var value: String? = null,
) : AndroidDurableMultipartUploadEncryptedStorage {
    var readFailure: IOException? = null
    var failWrites: Boolean = false

    override fun read(): String? {
        readFailure?.let { throw it }
        return value
    }

    override fun write(value: String): Boolean {
        if (failWrites) return false
        this.value = value
        return true
    }
}

private class FakeDurableUploadCipher : AndroidDurableMultipartUploadCipher {
    var decryptFailure: IOException? = null

    override fun encrypt(value: String): String = value

    override fun decrypt(value: String): String {
        decryptFailure?.let { throw it }
        return value
    }
}
