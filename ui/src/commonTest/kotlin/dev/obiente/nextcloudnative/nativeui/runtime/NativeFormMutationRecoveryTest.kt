package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeFormMutationRecoveryTest {
    @Test
    fun `restored in-flight submission remains blocked until a newer authoritative read`() {
        val owner = createOwner()
        val inFlight = owner.begin(reconciliationGeneration = 4)
        val encodedInFlight = assertNotNull(inFlight.encode())

        val stillExecuting = assertNotNull(
            resolveNativeFormMutationRecoveryState(
                encoded = encodedInFlight,
                currentReconciliationGeneration = 4,
                ownerStillExecuting = { true },
            ),
        )
        assertEquals(NativeFormMutationRecoveryPhase.InFlight, stillExecuting.phase)

        val restored = assertNotNull(
            resolveNativeFormMutationRecoveryState(
                encoded = encodedInFlight,
                currentReconciliationGeneration = 4,
                ownerStillExecuting = { false },
            ),
        )
        assertEquals(NativeFormMutationRecoveryPhase.AwaitingReconciliation, restored.phase)
        assertTrue(restored.blocksSubmission)
        assertEquals(owner.actionId, restored.authoritativeReconciliationActionId)
        assertNotNull(restored.afterAuthoritativeReconciliation(currentGeneration = 4))
        assertNull(restored.afterAuthoritativeReconciliation(currentGeneration = 5))
        val restoredAfterRacingRead = assertNotNull(
            resolveNativeFormMutationRecoveryState(
                encoded = encodedInFlight,
                currentReconciliationGeneration = 5,
                ownerStillExecuting = { false },
            ),
        )
        assertEquals(5, restoredAfterRacingRead.reconciliationGeneration)
        assertNull(restoredAfterRacingRead.afterAuthoritativeReconciliation(currentGeneration = 6))
    }

    @Test
    fun `a read racing the original request does not count as post-ambiguity reconciliation`() {
        val inFlight = createOwner().begin(reconciliationGeneration = 2)
        assertNotNull(inFlight.afterAuthoritativeReconciliation(currentGeneration = 3))

        val restored = inFlight.afterLifecycleRestore(
            ownerStillExecuting = false,
            currentReconciliationGeneration = 3,
        )
        assertEquals(3, restored.reconciliationGeneration)
        assertNotNull(restored.afterAuthoritativeReconciliation(currentGeneration = 3))
        assertNull(restored.afterAuthoritativeReconciliation(currentGeneration = 4))

        val unknown = assertNotNull(
            inFlight.afterExecutionResult(
                result = NativeActionExecutionResult.Failure(
                    message = "Response unavailable",
                    outcome = NativeActionFailureOutcome.Unknown,
                ),
                currentReconciliationGeneration = 3,
            ),
        )
        assertEquals(3, unknown.reconciliationGeneration)
        assertNotNull(unknown.afterAuthoritativeReconciliation(currentGeneration = 3))
    }

    @Test
    fun `only an ambiguous execution result waits for reconciliation`() {
        val inFlight = createOwner().begin(reconciliationGeneration = 9)

        assertNull(inFlight.afterExecutionResult(NativeActionExecutionResult.Success()))
        assertNull(
            inFlight.afterExecutionResult(
                NativeActionExecutionResult.Failure(
                    message = "Validation failed",
                    outcome = NativeActionFailureOutcome.Rejected,
                ),
            ),
        )

        val ambiguous = inFlight.afterExecutionResult(
            NativeActionExecutionResult.Failure(
                message = "Connection ended before a response arrived",
                outcome = NativeActionFailureOutcome.Unknown,
            ),
        )
        assertEquals(
            NativeFormMutationRecoveryPhase.AwaitingReconciliation,
            assertNotNull(ambiguous).phase,
        )
    }

    @Test
    fun `saved update recovery state round trips its generic owner`() {
        val owner = assertNotNull(
            nativeFormMutationRecoveryOwner(
                appId = "example-app",
                viewId = "item-detail",
                actionId = "items.update",
                resourceId = "items",
                intent = ActionIntent.update,
                recordId = "item-42",
            ),
        )
        val state = owner.begin(reconciliationGeneration = 12)
            .afterLifecycleRestore(ownerStillExecuting = false)
        val encoded = assertNotNull(state.encode())

        assertEquals(state, decodeNativeFormMutationRecoveryState(encoded))
        assertNull(decodeNativeFormMutationRecoveryState("[\"incomplete\"]"))
        assertNull(decodeNativeFormMutationRecoveryState("not-json"))
    }

    @Test
    fun `recovery owners exist only for identifiable create and update mutations`() {
        assertNotNull(createOwner())
        assertNull(
            nativeFormMutationRecoveryOwner(
                appId = "example-app",
                viewId = "item-detail",
                actionId = "items.update",
                resourceId = "items",
                intent = ActionIntent.update,
                recordId = null,
            ),
        )
        assertNull(
            nativeFormMutationRecoveryOwner(
                appId = "example-app",
                viewId = "item-list",
                actionId = "items.delete",
                resourceId = "items",
                intent = ActionIntent.delete,
                recordId = "item-42",
            ),
        )
        assertNotNull(
            createOwner()
                .begin(reconciliationGeneration = 0)
                .afterLifecycleRestore(ownerStillExecuting = false)
                .afterAuthoritativeReconciliation(currentGeneration = 0),
        )
    }

    private fun createOwner(): NativeFormMutationRecoveryOwner = assertNotNull(
        nativeFormMutationRecoveryOwner(
            appId = "example-app",
            viewId = "item-list",
            actionId = "items.create",
            resourceId = "items",
            intent = ActionIntent.create,
            recordId = null,
        ),
    )
}
