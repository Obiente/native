package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicRuleAdmissionConcurrencyTest {
    @Test
    fun `ordinary admission holds the rule route before its safety snapshot can go stale`() = runBlocking<Unit> {
        for (target in listOf("41", "42")) DynamicRuleExecutionFixture().use { f ->
            val ordinary = f.ordinaryRequest(target)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.pauseFirstSafetyRead(entered, release)
            val first = async { f.executor().execute(ordinary) }
            entered.await()
            try {
                assertUnknown(f.executor().execute(f.request()))
                assertEquals(0, f.writes, "No rule may reserve or send while the sibling owns admission")
                assertTrue(f.entries().isEmpty())
            } finally { release.complete(Unit) }
            assertIs<NativeActionExecutionResult.Success>(first.await())
            f.readBody = DynamicRuleExecutionFixture.records()
            f.authority = f.freshAuthority()
            f.writeStatus = 503
            f.afterWrite = null
            assertUnknown(f.executor().execute(f.request()))
            assertUnknown(f.executor().execute(ordinary))
            assertEquals(2, f.writes, "A persisted unknown result blocks the later ordinary sibling")
        }
    }

    @Test
    fun `rule admission excludes ordinary siblings before reservation and after an unknown result`() = runBlocking<Unit> {
        for (target in listOf("41", "42")) DynamicRuleExecutionFixture().use { f ->
            val ordinary = f.ordinaryRequest(target)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.pauseFirstSafetyRead(entered, release)
            f.writeStatus = 503
            f.afterWrite = null
            val first = async { f.executor().execute(f.request()) }
            entered.await()
            try {
                val blocked = assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(ordinary))
                assertEquals(NativeActionFailureOutcome.Rejected, blocked.outcome, "The ordinary request never started")
                assertEquals(0, f.writes)
            } finally { release.complete(Unit) }
            assertUnknown(first.await())
            assertUnknown(f.executor().execute(ordinary))
            assertEquals(1, f.writes)
            assertNotNull(f.entries().single().pendingVerification)
        }
    }

    private fun DynamicRuleExecutionFixture.ordinaryRequest(target: String): NativeActionRequest.Submit {
        val sibling = descriptor.actions.single { it.binding.path.endsWith("/read") }.let { action ->
            action.copy(id = "item-touch", binding = action.binding.copy(path = action.binding.path.removeSuffix("read") + "touch"))
        }
        descriptor = descriptor.copy(actions = descriptor.actions + sibling)
        return NativeActionRequest.Submit(descriptor.toNativeAppSchema().actions.single { it.id == sibling.id }, mapOf("itemId" to target), true)
    }

    private fun DynamicRuleExecutionFixture.pauseFirstSafetyRead(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
        val original = safety
        var first = true
        safety = object : DynamicWriteQuarantineStore by original {
            override suspend fun records(accountDigest: String, appId: String): List<DynamicWriteRuleRecord> {
                val snapshot = original.records(accountDigest, appId)
                if (first) { first = false; entered.complete(Unit); release.await() }
                return snapshot
            }
        }
    }

    private fun assertUnknown(result: NativeActionExecutionResult) {
        assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(result).outcome)
    }
}
