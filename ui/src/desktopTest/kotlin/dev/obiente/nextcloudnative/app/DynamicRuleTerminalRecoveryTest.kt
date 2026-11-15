package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.rules.requiresPatternRulePlan
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DynamicRuleTerminalRecoveryTest {
    @Test
    fun `terminal unknown can be stopped without replay and stays paused until explicit resume`() = runBlocking<Unit> {
        for (terminal in listOf("purged", "upgraded", "unreadable")) DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503
            f.afterWrite = null
            f.executor().execute(f.request())
            val record = f.entries().single()
            val handle = DynamicPendingRuleHandle(record.key, record.pendingVerification!!.operationId)
            when (terminal) {
                "purged" -> f.readBody = "[]"
                "upgraded" -> f.descriptor = f.descriptor.copy(app = f.descriptor.app.copy(version = "99.0.0"))
                "unreadable" -> f.draftDirectory.listFiles()!!.single().writeText("synthetic damaged evidence")
            }
            assertIs<NativeActionExecutionResult.Failure>(f.executor().recoverRuleOperations())
            val reads = f.requests.size
            assertTrue(f.executor().stopCheckingRuleOperation(handle))
            assertEquals(reads, f.requests.size, "Stopping recovery performs no transport request")
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            assertNull(f.entries().single().pendingVerification)
            assertEquals(DynamicWriteRuleStatus.quarantined, f.entries().single().status)
            assertEquals(DynamicWriteQuarantineReason.abandonedUnknown, f.entries().single().reason)
            f.safety.observeAppVersion(f.session.accountId.storageKey, f.descriptor.app.id, "100.0.0")
            f.readBody = DynamicRuleExecutionFixture.records()
            f.authority = f.freshAuthority()
            assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(f.request()))
            assertEquals(1, f.writes)
            assertTrue(f.events.any { it.code == DynamicDiagnosticCode.WriteRecoveryStopped.code })
            f.safety.clearApp(f.session.accountId.storageKey, f.descriptor.app.id)
            f.writeStatus = 200
            f.afterWrite = DynamicRuleExecutionFixture.records(false)
            assertIs<NativeActionExecutionResult.Success>(f.executor().execute(f.request()))
            assertEquals(2, f.writes)
        }
    }

    @Test
    fun `failed deletion of damaged evidence keeps its token for another explicit stop`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            f.executor().execute(f.request())
            val pending = f.entries().single()
            val handle = DynamicPendingRuleHandle(pending.key, pending.pendingVerification!!.operationId)
            f.draftDirectory.listFiles()!!.single().writeText("synthetic damaged evidence")
            f.removeFails = true
            val requests = f.requests.size
            assertFalse(f.executor().stopCheckingRuleOperation(handle))
            assertEquals(pending.pendingVerification, f.entries().single().pendingVerification)
            assertEquals(1, f.draftDirectory.listFiles().orEmpty().size)
            f.removeFails = false
            assertTrue(f.executor().stopCheckingRuleOperation(handle))
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            assertNull(f.entries().single().pendingVerification)
            assertEquals(DynamicWriteQuarantineReason.abandonedUnknown, f.entries().single().reason)
            assertEquals(requests, f.requests.size)
        }
    }

    @Test
    fun `terminal resolution refuses stale and foreign handles`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            f.executor().execute(f.request())
            val record = f.entries().single()
            val handle = DynamicPendingRuleHandle(record.key, record.pendingVerification!!.operationId)
            assertFalse(f.executor().stopCheckingRuleOperation(handle.copy(operationId = "00000000-0000-4000-8000-000000000000")))
            assertFalse(f.executor().stopCheckingRuleOperation(handle.copy(key = handle.key.copy(accountDigest = "a".repeat(64)))))
            assertFalse(f.executor().stopCheckingRuleOperation(handle.copy(key = handle.key.copy(appId = "other-app"))))
            assertEquals(record.pendingVerification, f.entries().single().pendingVerification)
            assertEquals(1, f.draftDirectory.listFiles().orEmpty().size)
            assertEquals(1, f.writes)
        }
    }

    @Test
    fun `a rejected repeat with an authoritative unchanged state resolves the pending intent`() = runBlocking<Unit> {
        DynamicRuleExecutionFixture().use { f ->
            f.writeStatus = 503; f.afterWrite = null
            f.executor().execute(f.request())
            f.writeStatus = 403
            val result = assertIs<NativeActionExecutionResult.Failure>(f.executor().execute(
                f.request().copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
            assertEquals(NativeActionFailureOutcome.Rejected, result.outcome)
            assertTrue(result.message.contains("current state is unchanged"))
            assertNull(f.entries().single().pendingVerification)
            assertTrue(f.draftDirectory.listFiles().orEmpty().isEmpty())
            assertEquals(2, f.writes)
        }
    }

    @Test
    fun `generic record and mailbox controls omit operations requiring a rule plan`() {
        for (booleanUpdate in listOf(false, true)) DynamicRuleExecutionFixture(booleanUpdate = booleanUpdate).use { f ->
            val schema = f.descriptor.toNativeAppSchema()
            val resource = checkNotNull(schema.resource(f.source().resourceId))
            val original = f.authority.state.records.single()
            val record = original.copy(values = original.values + mapOf("subject" to "Synthetic message", "from" to "sender@example.test"))
            val gated = schema.actions.filter { schema.requiresPatternRulePlan(it) }.map { it.id }.toSet()
            assertTrue(gated.isNotEmpty())
            val actions = nativeRecordActions(schema, resource, record)
            assertTrue(actions.commands.none { it.action.id in gated })
            assertTrue(actions.commandForms.none { it.action.id in gated })
            actions.edit?.let { assertFalse(it.action.id in gated) }
            assertEquals(NativeMailboxItemKind.Message, nativeMailboxPresentation(resource, record).kind)
            assertTrue(nativeMailMessageActionPlan(schema, resource, record).all.none { it.action.id in gated })
        }
    }
}
