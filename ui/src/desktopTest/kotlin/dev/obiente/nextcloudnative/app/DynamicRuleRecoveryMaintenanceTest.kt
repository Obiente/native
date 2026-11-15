package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicRuleRecoveryMaintenanceTest {
    @Test
    fun `cancelled evidence saves do not exhaust the shared draft capacity`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.afterSave = { throw CancellationException("Synthetic cancellation after publication") }
            repeat(36) { assertFailsWith<CancellationException> { f.executor().execute(f.request()) } }
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            f.storage.bind(f.session.accountId.storageKey, "/document.txt").save(TextEditorDraft("", "draft", null))
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun `status maintenance reaps resolved evidence after failed deletion and restart`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.removeFails = true
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request()))
            assertEquals(1, f.draftDirectory.listFiles().orEmpty().size)
            f.removeFails = false
            f.restart()
            assertIs<NativeActionExecutionResult.Success>(f.executor().recoverRuleOperations())
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun `new writes reclaim orphaned recovery capacity without deleting ordinary drafts`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val document = f.storage.bind(f.session.accountId.storageKey, "/document.txt")
            val draft = TextEditorDraft("original", "private draft", null)
            document.save(draft)
            f.removeFails = true
            repeat(36) {
                f.readBody = DynamicRuleExecutionFixture.records()
                f.authority = f.freshAuthority()
                assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request()))
            }
            assertEquals(draft, document.load())
            assertEquals(2, f.draftDirectory.listFiles().orEmpty().size)
        }
    }

    @Test
    fun `sweeping cannot remove evidence between its save and reservation`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val saved = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            f.afterSave = { saved.complete(Unit); resume.await() }
            f.writeStatus = 503
            f.afterWrite = null
            val writing = async { f.executor().execute(f.request()) }
            saved.await()
            val checking = async { f.executor().recoverRuleOperations() }
            resume.complete(Unit)
            writing.await()
            checking.await()
            assertNotNull(f.entries().single().pendingVerification)
            assertEquals(1, f.draftDirectory.listFiles().orEmpty().size)
        }
    }

    @Test
    fun `pending evidence for an older app version survives cleanup`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503
            f.afterWrite = null
            f.executor().execute(f.request())
            f.descriptor = f.descriptor.copy(app = f.descriptor.app.copy(version = "99.0.0"))
            f.restart()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            assertNotNull(f.entries().single().pendingVerification)
            assertEquals(1, f.draftDirectory.listFiles().orEmpty().size)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun `retirement during generation capture cannot authorize a new safety file`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val original = f.safety
            f.safety = object : DynamicWriteQuarantineStore by original {
                override suspend fun generation(accountDigest: String): Long {
                    AccountPrivateMemoryLifecycle.retireAccount(accountDigest)
                    original.removeAccount(accountDigest)
                    return original.generation(accountDigest)
                }
            }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertTrue(f.safetyDirectory.listFiles().orEmpty().isEmpty())
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun `retirement between safety reads and probation cannot recreate the account file`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            val original = f.safety
            f.safety = object : DynamicWriteQuarantineStore by original {
                override suspend fun records(accountDigest: String, appId: String): List<DynamicWriteRuleRecord> {
                    val records = original.records(accountDigest, appId)
                    AccountPrivateMemoryLifecycle.retireAccount(accountDigest)
                    original.removeAccount(accountDigest)
                    return records
                }
            }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertTrue(f.safetyDirectory.listFiles().orEmpty().isEmpty())
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            assertEquals(0, f.writes)
        }
    }

    @Test
    fun `offline status checks describe pending verification instead of corrupted evidence`() = runBlocking<Unit> {
        for (thrown in listOf(false, true)) DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503
            f.afterWrite = null
            f.executor().execute(f.request())
            if (thrown) f.onRead = { throw IOException("Synthetic offline") } else f.readStatus = 503
            val result = assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            assertEquals(NativeActionFailureOutcome.Unknown, result.outcome)
            assertTrue(result.message.startsWith("An earlier change still needs verification."))
            assertNotNull(f.entries().single().pendingVerification)
            assertEquals(1, f.writes)
        }
    }
}
