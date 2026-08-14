package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.Evidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeChoresInvitationRecoveryTest {
    @Test
    fun `accept marker binds the exact verified invitation and disappearance postcondition`() {
        val fixture = fixture()
        val marker = assertNotNull(
            fixture.plan.stage(fixture.request, NativeCreateMutationPhase.TransportMayHaveObserved),
        )
        val pending = assertNotNull(
            nativeChoresInvitationAcceptPostcondition(fixture.plan.pendingKey, marker),
        )

        assertEquals("invite-7", pending.invitationRecordId)
        assertEquals("42", pending.teamId)
        assertTrue(!pending.satisfiedBy(listOf(fixture.record)))
        assertTrue(
            pending.satisfiedBy(
                listOf(
                    NativeRecord(
                        id = "invite-8",
                        values = mapOf("inviteId" to "invite-8", "teamId" to "43"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `ambiguous invitation acceptance is reconciled and never replayed`() = runBlocking {
        val fixture = fixture()
        var stored: Map<String, String>? = null
        var accepted = false
        val events = mutableListOf<String>()
        val store = object : NativePendingMutationStore {
            override suspend fun load(key: NativePendingMutationKey): Map<String, String>? = stored.also {
                events += "load"
            }

            override suspend fun save(key: NativePendingMutationKey, values: Map<String, String>) {
                events += "save:${nativeChoresInvitationAcceptPostcondition(key, values)?.phase}"
                stored = values
            }

            override suspend fun postconditionSatisfied(
                key: NativePendingMutationKey,
                values: Map<String, String>,
            ): Boolean = accepted.also { events += "reconcile" }

            override suspend fun clear(key: NativePendingMutationKey) {
                events += "clear"
                stored = null
            }
        }
        val unknown = executeNativeChoresInvitationAccept(
            plan = fixture.plan,
            request = fixture.request,
            actionExecutor = NativeActionExecutor {
                events += "execute"
                NativeActionExecutionResult.Failure(
                    "Connection ended before the response arrived.",
                    NativeActionFailureOutcome.Unknown,
                )
            },
            pendingMutationStore = store,
        )
        assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(unknown).outcome)
        assertEquals(
            listOf(
                "load",
                "save:Staged",
                "save:TransportMayHaveObserved",
                "execute",
                "reconcile",
            ),
            events,
        )

        events.clear()
        val stillPending = executeNativeChoresInvitationAccept(
            plan = fixture.plan,
            request = fixture.request,
            actionExecutor = NativeActionExecutor { error("An ambiguous accept must not be sent twice.") },
            pendingMutationStore = store,
        )
        assertIs<NativeActionExecutionResult.Failure>(stillPending)
        assertEquals(listOf("load", "reconcile"), events)

        accepted = true
        events.clear()
        val reconciled = executeNativeChoresInvitationAccept(
            plan = fixture.plan,
            request = fixture.request,
            actionExecutor = NativeActionExecutor { error("A reconciled accept must not be sent twice.") },
            pendingMutationStore = store,
        )
        assertIs<NativeActionExecutionResult.Success>(reconciled)
        assertEquals(listOf("load", "reconcile", "clear"), events)
        assertNull(stored)
    }

    private data class Fixture(
        val plan: NativeChoresInvitationAcceptRecoveryPlan,
        val request: NativeActionRequest.Submit,
        val record: NativeRecord,
    )

    private fun fixture(): Fixture {
        val evidence = listOf(Evidence(EvidenceSource.verifiedAppPackage, "Signed package route"))
        val invitations = ResourceSpec(
            id = "invites",
            name = "Invitations",
            confidence = Confidence.verified,
        )
        val commandResource = ResourceSpec(
            id = "invitations",
            name = "Invitation actions",
            confidence = Confidence.verified,
        )
        val read = ActionSpec(
            id = "invitations.list",
            label = "Invitations",
            resourceId = invitations.id,
            binding = ApiBinding(
                method = HttpMethod.GET,
                path = "/apps/chores/api/v1.0/account/invites",
                operationId = "invitations.list",
            ),
            intent = ActionIntent.list,
            risk = ActionRisk.readOnly,
            requiresConfirmation = false,
            confidence = Confidence.verified,
            evidence = evidence,
            effect = ActionEffect.list,
        )
        val accept = ActionSpec(
            id = "invitations.accept",
            label = "Accept invitation",
            resourceId = commandResource.id,
            binding = ApiBinding(
                method = HttpMethod.POST,
                path = "/apps/chores/api/v1.0/account/invites/accept",
                operationId = "invitations.accept",
                bodyFieldNames = listOf("teamId"),
                requiredBodyFieldNames = listOf("teamId"),
            ),
            intent = ActionIntent.execute,
            risk = ActionRisk.destructive,
            requiresConfirmation = true,
            confidence = Confidence.verified,
            evidence = evidence,
            effect = ActionEffect.execute,
        )
        val schema = NativeAppSchema(
            schemaVersion = "1",
            app = AppIdentity("chores", "Chores", "0.1.0"),
            confidence = Confidence.verified,
            resources = listOf(invitations, commandResource),
            actions = listOf(read, accept),
        )
        val record = NativeRecord(
            id = "invite-7",
            values = mapOf(
                "inviteId" to "invite-7",
                "teamId" to "42",
                "teamName" to "Home",
                "userId" to "alice",
            ),
        )
        val plan = assertNotNull(
            nativeChoresInvitationAcceptRecoveryPlan(
                schema = schema,
                activeReadAction = read,
                action = accept,
                record = record,
                values = mapOf("teamId" to "42"),
            ),
        )
        return Fixture(
            plan = plan,
            request = NativeActionRequest.Submit(
                action = accept,
                values = mapOf("teamId" to "42"),
                confirmed = true,
            ),
            record = record,
        )
    }
}
