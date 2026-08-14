package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.app.publicContentSha256
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema

internal const val NATIVE_CHORES_INVITATION_ACCEPT_NAMESPACE = "chores-invitation-accept-v1"

internal data class NativeChoresInvitationAcceptPostcondition(
    val actionId: String,
    val readActionId: String,
    val invitationRecordId: String,
    val teamId: String,
    val confirmed: Boolean,
    val phase: NativeCreateMutationPhase,
) {
    internal fun satisfiedBy(records: List<NativeRecord>): Boolean {
        if (records.any { record ->
                !record.actionSafeIdentity || !record.actionBindingProvenanceValid ||
                    record.id.isBlank()
            }
        ) {
            return false
        }
        return records.none { record ->
            val recordInvitationId = record.values["inviteId"]?.trim() ?: record.id
            val recordTeamId = record.values["teamId"]?.trim()
            recordInvitationId == invitationRecordId && recordTeamId == teamId
        }
    }
}

internal data class NativeChoresInvitationAcceptRecoveryPlan(
    val action: ActionSpec,
    val readActionId: String,
    val invitationRecordId: String,
    val teamId: String,
) {
    val pendingKey = NativePendingMutationKey(
        actionId = "$NATIVE_CHORES_INVITATION_ACCEPT_NAMESPACE:${action.id}",
        targetRecordId = invitationAcceptScopeDigest(readActionId, invitationRecordId, teamId),
    )

    internal fun stage(
        request: NativeActionRequest.Submit,
        phase: NativeCreateMutationPhase,
    ): Map<String, String>? {
        if (
            request.action.id != action.id || request.values != mapOf("teamId" to teamId) ||
            !request.confirmed
        ) {
            return null
        }
        return mapOf(
            ACCEPT_MARKER_VERSION_KEY to ACCEPT_MARKER_VERSION,
            ACCEPT_MARKER_PHASE_KEY to phase.name,
            ACCEPT_MARKER_ACTION_KEY to action.id,
            ACCEPT_MARKER_READ_ACTION_KEY to readActionId,
            ACCEPT_MARKER_INVITATION_KEY to invitationRecordId,
            ACCEPT_MARKER_TEAM_KEY to teamId,
            ACCEPT_MARKER_CONFIRMED_KEY to "true",
        )
    }
}

internal fun isNativeChoresInvitationAcceptAction(
    schema: NativeAppSchema,
    action: ActionSpec,
): Boolean = schema.app.id == "chores" && schema.app.version == "0.1.0" &&
    action.intent == ActionIntent.execute && action.risk != ActionRisk.readOnly &&
    action.binding.method == HttpMethod.POST &&
    action.binding.path == "/apps/chores/api/v1.0/account/invites/accept" &&
    action.binding.bodyFieldNames.toSet() == setOf("teamId") &&
    action.binding.requiredBodyFieldNames.toSet() == setOf("teamId") &&
    action.confidence == Confidence.verified &&
    action.evidence.any { evidence -> evidence.source == EvidenceSource.verifiedAppPackage }

internal fun nativeChoresInvitationAcceptRecoveryPlan(
    schema: NativeAppSchema,
    activeReadAction: ActionSpec,
    action: ActionSpec,
    record: NativeRecord,
    values: Map<String, String>,
): NativeChoresInvitationAcceptRecoveryPlan? {
    if (
        !isNativeChoresInvitationAcceptAction(schema, action) ||
        activeReadAction.intent !in setOf(ActionIntent.list, ActionIntent.read) ||
        activeReadAction.binding.method != HttpMethod.GET ||
        activeReadAction.binding.path != "/apps/chores/api/v1.0/account/invites" ||
        activeReadAction.confidence != Confidence.verified ||
        activeReadAction.evidence.none { evidence -> evidence.source == EvidenceSource.verifiedAppPackage } ||
        schema.actions.count { candidate -> candidate.id == action.id } != 1 ||
        schema.actions.count { candidate -> candidate.id == activeReadAction.id } != 1 ||
        !record.actionSafeIdentity || !record.actionBindingProvenanceValid
    ) {
        return null
    }
    val invitationId = record.values["inviteId"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val teamId = record.values["teamId"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (
        record.id != invitationId || values != mapOf("teamId" to teamId) ||
        invitationId.length > MAX_ACCEPT_ID_LENGTH || teamId.length > MAX_ACCEPT_ID_LENGTH ||
        '\u0000' in invitationId || '\u0000' in teamId
    ) {
        return null
    }
    return NativeChoresInvitationAcceptRecoveryPlan(
        action = action,
        readActionId = activeReadAction.id,
        invitationRecordId = invitationId,
        teamId = teamId,
    )
}

internal fun nativeChoresInvitationAcceptPostcondition(
    key: NativePendingMutationKey,
    values: Map<String, String>,
): NativeChoresInvitationAcceptPostcondition? {
    if (!key.actionId.startsWith("$NATIVE_CHORES_INVITATION_ACCEPT_NAMESPACE:")) return null
    if (values.keys != ACCEPT_MARKER_KEYS || values[ACCEPT_MARKER_VERSION_KEY] != ACCEPT_MARKER_VERSION) {
        return null
    }
    val actionId = values[ACCEPT_MARKER_ACTION_KEY]?.safeAcceptId() ?: return null
    val readActionId = values[ACCEPT_MARKER_READ_ACTION_KEY]?.safeAcceptId() ?: return null
    val invitationId = values[ACCEPT_MARKER_INVITATION_KEY]?.safeAcceptId() ?: return null
    val teamId = values[ACCEPT_MARKER_TEAM_KEY]?.safeAcceptId() ?: return null
    if (
        key.actionId != "$NATIVE_CHORES_INVITATION_ACCEPT_NAMESPACE:$actionId" ||
        key.targetRecordId != invitationAcceptScopeDigest(readActionId, invitationId, teamId) ||
        values[ACCEPT_MARKER_CONFIRMED_KEY] != "true"
    ) {
        return null
    }
    val phase = NativeCreateMutationPhase.entries.firstOrNull { candidate ->
        candidate.name == values[ACCEPT_MARKER_PHASE_KEY]
    } ?: return null
    return NativeChoresInvitationAcceptPostcondition(
        actionId = actionId,
        readActionId = readActionId,
        invitationRecordId = invitationId,
        teamId = teamId,
        confirmed = true,
        phase = phase,
    )
}

internal suspend fun executeNativeChoresInvitationAccept(
    plan: NativeChoresInvitationAcceptRecoveryPlan,
    request: NativeActionRequest.Submit,
    actionExecutor: NativeActionExecutor,
    pendingMutationStore: NativePendingMutationStore,
): NativeActionExecutionResult {
    val key = plan.pendingKey
    var staged = runCatching { pendingMutationStore.load(key) }.getOrElse { failure ->
        return NativeActionExecutionResult.Failure(
            failure.message ?: "The saved invitation recovery marker could not be read.",
            NativeActionFailureOutcome.Unknown,
        )
    }
    if (staged != null) {
        val pending = nativeChoresInvitationAcceptPostcondition(key, staged)
            ?: return NativeActionExecutionResult.Failure(
                "The saved invitation recovery marker is invalid.",
                NativeActionFailureOutcome.Unknown,
            )
        if (runCatching { pendingMutationStore.postconditionSatisfied(key, staged) }.getOrDefault(false)) {
            return clearConfirmedInvitationAccept(pendingMutationStore, key)
        }
        if (pending.phase == NativeCreateMutationPhase.TransportMayHaveObserved) {
            return NativeActionExecutionResult.Failure(
                "The earlier invitation acceptance is still awaiting authoritative server confirmation.",
                NativeActionFailureOutcome.Unknown,
            )
        }
        if (
            pending.teamId != request.values["teamId"] ||
            pending.confirmed != request.confirmed || request.values.size != 1
        ) {
            runCatching { pendingMutationStore.clear(key) }.getOrElse { failure ->
                return NativeActionExecutionResult.Failure(
                    failure.message ?: "The superseded invitation acceptance could not be cleared.",
                    NativeActionFailureOutcome.Rejected,
                )
            }
            staged = null
        }
    }
    if (staged == null) {
        staged = plan.stage(request, NativeCreateMutationPhase.Staged)
            ?: return NativeActionExecutionResult.Failure(
                "This invitation acceptance could not be staged safely.",
                NativeActionFailureOutcome.Rejected,
            )
        runCatching { pendingMutationStore.save(key, staged) }.getOrElse { failure ->
            return NativeActionExecutionResult.Failure(
                failure.message ?: "This invitation acceptance could not be staged safely.",
                NativeActionFailureOutcome.Rejected,
            )
        }
    }
    val transport = staged +
        (ACCEPT_MARKER_PHASE_KEY to NativeCreateMutationPhase.TransportMayHaveObserved.name)
    runCatching { pendingMutationStore.save(key, transport) }.getOrElse { failure ->
        return NativeActionExecutionResult.Failure(
            failure.message ?: "This invitation acceptance could not enter its durable send phase.",
            NativeActionFailureOutcome.Rejected,
        )
    }
    val result = actionExecutor.execute(request)
    if ((result as? NativeActionExecutionResult.Failure)?.outcome == NativeActionFailureOutcome.Rejected) {
        return runCatching {
            pendingMutationStore.save(key, staged)
            pendingMutationStore.clear(key)
        }.fold(
            onSuccess = { result },
            onFailure = { failure ->
                NativeActionExecutionResult.Failure(
                    failure.message ?: "The rejected invitation recovery marker could not be cleared.",
                    NativeActionFailureOutcome.Unknown,
                )
            },
        )
    }
    if (runCatching { pendingMutationStore.postconditionSatisfied(key, transport) }.getOrDefault(false)) {
        return clearConfirmedInvitationAccept(pendingMutationStore, key)
    }
    return when (result) {
        is NativeActionExecutionResult.Success -> NativeActionExecutionResult.Failure(
            "The server accepted the invitation action, but refreshed data does not confirm it yet.",
            NativeActionFailureOutcome.Unknown,
        )
        is NativeActionExecutionResult.Failure -> result
    }
}

private suspend fun clearConfirmedInvitationAccept(
    store: NativePendingMutationStore,
    key: NativePendingMutationKey,
): NativeActionExecutionResult = runCatching { store.clear(key) }.fold(
    onSuccess = { NativeActionExecutionResult.Success("The invitation acceptance is confirmed by the server.") },
    onFailure = { failure ->
        NativeActionExecutionResult.Failure(
            failure.message ?: "The confirmed invitation recovery marker could not be cleared.",
            NativeActionFailureOutcome.Unknown,
        )
    },
)

private fun invitationAcceptScopeDigest(
    readActionId: String,
    invitationRecordId: String,
    teamId: String,
): String = publicContentSha256(
    listOf(readActionId, invitationRecordId, teamId).joinToString("\u0000").encodeToByteArray(),
)

private fun String.safeAcceptId(): String? = takeIf { value ->
    value.isNotBlank() && value.length <= MAX_ACCEPT_ID_LENGTH && '\u0000' !in value
}

private const val ACCEPT_MARKER_VERSION = "1"
private const val ACCEPT_MARKER_VERSION_KEY = "version"
private const val ACCEPT_MARKER_PHASE_KEY = "phase"
private const val ACCEPT_MARKER_ACTION_KEY = "actionId"
private const val ACCEPT_MARKER_READ_ACTION_KEY = "readActionId"
private const val ACCEPT_MARKER_INVITATION_KEY = "invitationRecordId"
private const val ACCEPT_MARKER_TEAM_KEY = "teamId"
private const val ACCEPT_MARKER_CONFIRMED_KEY = "confirmed"
private val ACCEPT_MARKER_KEYS = setOf(
    ACCEPT_MARKER_VERSION_KEY,
    ACCEPT_MARKER_PHASE_KEY,
    ACCEPT_MARKER_ACTION_KEY,
    ACCEPT_MARKER_READ_ACTION_KEY,
    ACCEPT_MARKER_INVITATION_KEY,
    ACCEPT_MARKER_TEAM_KEY,
    ACCEPT_MARKER_CONFIRMED_KEY,
)
private const val MAX_ACCEPT_ID_LENGTH = 512
