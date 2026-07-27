package dev.obiente.nextcloudnative

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.localUploadFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDurableMultipartUploadStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences by lazy {
        context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
    }

    @Before
    fun clearFixtureStore() {
        check(preferences.edit().clear().commit())
    }

    @After
    fun removeFixtureStore() {
        check(preferences.edit().clear().commit())
    }

    @Test
    fun encryptedQueueSurvivesStoreRecreationAndKeepsAccountsIsolated() {
        val first = fixtureJob(
            id = "upload-0000000000000001",
            accountId = ACCOUNT_A,
            cardId = 42,
            selectionId = "selection-0000000000000001",
        )
        val second = fixtureJob(
            id = "upload-0000000000000002",
            accountId = ACCOUNT_B,
            cardId = 42,
            selectionId = "selection-0000000000000002",
        )
        AndroidDurableMultipartUploadStore(context, TEST_PREFERENCES).apply {
            add(first)
            add(second)
        }

        val encrypted = preferences.getString(
            AndroidDurableMultipartUploadStore.KEY_JOBS,
            null,
        ).orEmpty()
        assertTrue(encrypted.isNotBlank())
        assertFalse(first.request.relativePath in encrypted)
        assertFalse(first.request.file.displayName in encrypted)
        assertFalse(ACCOUNT_A in encrypted)

        val restored = AndroidDurableMultipartUploadStore(context, TEST_PREFERENCES)
        assertEquals(first, restored.find(first.id))
        assertEquals(
            listOf(first),
            restored.list(ACCOUNT_A, first.scope),
        )
        assertEquals(
            listOf(second),
            restored.list(ACCOUNT_B, second.scope),
        )
        assertNull(restored.list(ACCOUNT_A, first.scope).firstOrNull { it.accountId == ACCOUNT_B })
    }

    private fun fixtureJob(
        id: String,
        accountId: String,
        cardId: Long,
        selectionId: String,
    ): AndroidDurableMultipartUploadJob {
        val scope = DurableUploadScope("deck-attachment", cardId.toString())
        val request = NextcloudMultipartUploadRequest(
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
        return AndroidDurableMultipartUploadJob(
            id = id,
            accountId = accountId,
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = DurableUploadState.Queued,
            message = null,
            updatedAtEpochMillis = 1L,
        )
    }

    private companion object {
        const val TEST_PREFERENCES = "test_durable_uploads"
        const val ACCOUNT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
