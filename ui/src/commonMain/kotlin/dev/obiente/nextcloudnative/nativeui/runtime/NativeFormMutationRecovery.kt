package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class NativeFormMutationKind {
    Create,
    Update,
    Command,
}

internal enum class NativeFormMutationRecoveryPhase {
    InFlight,
    AwaitingReconciliation,
}

/**
 * Stable contract identity for one generic create/update submission.
 *
 * The owner deliberately contains only schema identities and the canonical target record. It is
 * independent of any installed app vocabulary and is safe to persist in saved state.
 */
internal data class NativeFormMutationRecoveryOwner(
    val appId: String,
    val viewId: String,
    val actionId: String,
    val resourceId: String,
    val kind: NativeFormMutationKind,
    val recordId: String?,
) {
    init {
        listOf(appId, viewId, actionId, resourceId).forEach { value ->
            require(value.isNotBlank() && value.length <= NATIVE_FORM_RECOVERY_ID_LIMIT)
        }
        require(recordId?.length?.let { length -> length <= NATIVE_FORM_RECOVERY_ID_LIMIT } != false)
        require(kind != NativeFormMutationKind.Create || recordId == null)
        require(
            kind !in setOf(NativeFormMutationKind.Update, NativeFormMutationKind.Command) ||
                !recordId.isNullOrBlank(),
        )
    }
}

internal data class NativeFormMutationRecoveryState(
    val owner: NativeFormMutationRecoveryOwner,
    val phase: NativeFormMutationRecoveryPhase,
    val reconciliationGeneration: Int,
) {
    init {
        require(reconciliationGeneration >= 0)
    }

    val blocksSubmission: Boolean
        get() = true

    val authoritativeReconciliationActionId: String?
        get() = owner.actionId.takeIf {
            phase == NativeFormMutationRecoveryPhase.AwaitingReconciliation
        }

    fun afterLifecycleRestore(
        ownerStillExecuting: Boolean,
        currentReconciliationGeneration: Int = reconciliationGeneration,
    ): NativeFormMutationRecoveryState =
        if (phase == NativeFormMutationRecoveryPhase.InFlight && !ownerStillExecuting) {
            copy(
                phase = NativeFormMutationRecoveryPhase.AwaitingReconciliation,
                reconciliationGeneration = maxOf(
                    reconciliationGeneration,
                    currentReconciliationGeneration,
                ),
            )
        } else {
            this
        }

    fun afterExecutionResult(
        result: NativeActionExecutionResult,
        currentReconciliationGeneration: Int = reconciliationGeneration,
    ): NativeFormMutationRecoveryState? =
        when (result) {
            is NativeActionExecutionResult.Success -> null
            is NativeActionExecutionResult.Failure -> when (result.outcome) {
                NativeActionFailureOutcome.Rejected -> null
                NativeActionFailureOutcome.Unknown ->
                    copy(
                        phase = NativeFormMutationRecoveryPhase.AwaitingReconciliation,
                        reconciliationGeneration = maxOf(
                            reconciliationGeneration,
                            currentReconciliationGeneration,
                        ),
                    )
            }
        }

    fun afterAuthoritativeReconciliation(
        currentGeneration: Int,
    ): NativeFormMutationRecoveryState? =
        takeUnless {
            phase == NativeFormMutationRecoveryPhase.AwaitingReconciliation &&
                currentGeneration > reconciliationGeneration
        }
}

internal fun NativeFormMutationRecoveryOwner.begin(
    reconciliationGeneration: Int,
): NativeFormMutationRecoveryState = NativeFormMutationRecoveryState(
    owner = this,
    phase = NativeFormMutationRecoveryPhase.InFlight,
    reconciliationGeneration = reconciliationGeneration,
)

internal fun nativeFormMutationRecoveryOwner(
    appId: String,
    viewId: String,
    actionId: String,
    resourceId: String,
    intent: ActionIntent,
    recordId: String?,
): NativeFormMutationRecoveryOwner? {
    val kind = when (intent) {
        ActionIntent.create -> NativeFormMutationKind.Create
        ActionIntent.update -> NativeFormMutationKind.Update
        ActionIntent.execute -> NativeFormMutationKind.Command
        else -> return null
    }
    return runCatching {
        NativeFormMutationRecoveryOwner(
            appId = appId,
            viewId = viewId,
            actionId = actionId,
            resourceId = resourceId,
            kind = kind,
            recordId = recordId,
        )
    }.getOrNull()
}

internal fun NativeFormMutationRecoveryState.encode(): String? {
    val encoded = JsonArray(
        listOf(
            JsonPrimitive(owner.appId),
            JsonPrimitive(owner.viewId),
            JsonPrimitive(owner.actionId),
            JsonPrimitive(owner.resourceId),
            JsonPrimitive(owner.kind.name),
            owner.recordId?.let(::JsonPrimitive) ?: JsonNull,
            JsonPrimitive(phase.name),
            JsonPrimitive(reconciliationGeneration),
        ),
    ).toString()
    return encoded.takeIf { it.length <= NATIVE_FORM_RECOVERY_TOKEN_LIMIT }
}

internal fun decodeNativeFormMutationRecoveryState(
    encoded: String?,
): NativeFormMutationRecoveryState? {
    if (encoded == null || encoded.length > NATIVE_FORM_RECOVERY_TOKEN_LIMIT) return null
    val parts = runCatching { Json.parseToJsonElement(encoded) }.getOrNull() as? JsonArray ?: return null
    if (parts.size != 8) return null
    fun string(index: Int): String? =
        (parts[index] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    val recordId = when (val record = parts[5]) {
        JsonNull -> null
        is JsonPrimitive -> record.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null
        else -> return null
    }
    val generation = (parts[7] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.toIntOrNull()
        ?: return null
    return runCatching {
        NativeFormMutationRecoveryState(
            owner = NativeFormMutationRecoveryOwner(
                appId = string(0) ?: return null,
                viewId = string(1) ?: return null,
                actionId = string(2) ?: return null,
                resourceId = string(3) ?: return null,
                kind = NativeFormMutationKind.entries.firstOrNull { kind -> kind.name == string(4) }
                    ?: return null,
                recordId = recordId,
            ),
            phase = NativeFormMutationRecoveryPhase.entries.firstOrNull { phase ->
                phase.name == string(6)
            } ?: return null,
            reconciliationGeneration = generation,
        )
    }.getOrNull()
}

internal fun resolveNativeFormMutationRecoveryState(
    encoded: String?,
    currentReconciliationGeneration: Int,
    ownerStillExecuting: (NativeFormMutationRecoveryOwner) -> Boolean,
): NativeFormMutationRecoveryState? {
    val saved = decodeNativeFormMutationRecoveryState(encoded) ?: return null
    return saved.afterLifecycleRestore(
        ownerStillExecuting = ownerStillExecuting(saved.owner),
        currentReconciliationGeneration = currentReconciliationGeneration,
    ).afterAuthoritativeReconciliation(currentReconciliationGeneration)
}

private const val NATIVE_FORM_RECOVERY_ID_LIMIT = 256
private const val NATIVE_FORM_RECOVERY_TOKEN_LIMIT = 3 * 1024
