package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class DynamicRuleAuthorizationTest {
    @Test fun revocationAfterEntryPreventsBothRuleKindsFromSending() = runBlocking<Unit> {
        for (membership in listOf(false, true)) for (boundary in listOf("read", "save", "checking", "replacement")) {
            MembershipExecutionFixture().use { labels ->
                DynamicRuleExecutionFixture(itemRead = true).use { feed ->
                    val f = if (membership) labels.transport else feed
                    val gate = DynamicContractWriteGate().also { it.settle(f.descriptor) }
                    val executor = DynamicNextcloudActionExecutor(f.services, f.session, f.descriptor,
                        writeGate = gate, ruleAuthority = { f.authority }, membershipAuthority = { labels.authority })
                    val request = if (membership) labels.request() else f.request()
                    if (boundary == "read") f.onRead = { gate.settle(null) }
                    else f.afterSave = {
                        when (boundary) {
                            "checking" -> gate.beginCheck()
                            "replacement" -> gate.settle(f.descriptor.copy(app = f.descriptor.app.copy(version = "changed")))
                            else -> gate.settle(null)
                        }
                    }
                    val result = assertIs<NativeActionExecutionResult.Failure>(executor.execute(request))
                    assertEquals(NativeActionFailureOutcome.Rejected, result.outcome, "$membership/$boundary")
                    assertEquals(0, f.writes, "$membership/$boundary")
                    assertTrue(f.entries().none { it.pendingVerification != null })
                    assertTrue(f.draftDirectory.listFiles().orEmpty().none { it.extension == "draft" })
                }
            }
        }
    }

    @Test fun revocationDuringRepeatPreservesEarlierUnknownToken() = runBlocking<Unit> {
        for (membership in listOf(false, true)) MembershipExecutionFixture().use { labels ->
            DynamicRuleExecutionFixture(itemRead = true).use { feed ->
                val f = if (membership) labels.transport else feed
                val gate = DynamicContractWriteGate().also { it.settle(f.descriptor) }
                val executor = DynamicNextcloudActionExecutor(f.services, f.session, f.descriptor,
                    writeGate = gate, ruleAuthority = { f.authority }, membershipAuthority = { labels.authority })
                val request = if (membership) labels.request() else f.request()
                f.writeStatus = 503; f.afterWrite = null
                assertIs<NativeActionExecutionResult.Failure>(executor.execute(request))
                val pending = assertNotNull(f.entries().single().pendingVerification)
                f.onRead = { gate.settle(null) }
                val result = assertIs<NativeActionExecutionResult.Failure>(executor.execute(
                    request.copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation)))
                assertEquals(NativeActionFailureOutcome.Unknown, result.outcome)
                assertEquals(1, f.writes)
                assertEquals(pending, f.entries().single().pendingVerification)
            }
        }
    }
}
