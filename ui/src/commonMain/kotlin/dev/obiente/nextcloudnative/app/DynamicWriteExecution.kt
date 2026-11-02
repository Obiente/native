package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicIdentityBinding
import dev.obiente.nextcloudnative.nativeui.model.DynamicOperationRiskClass
import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteAdmission
import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteAdmissionInput
import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteFacts
import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteRefusal
import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteRuleState
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.admitDynamicWrite
import dev.obiente.nextcloudnative.nativeui.model.evidenceTier
import dev.obiente.nextcloudnative.nativeui.model.operationRiskClass
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeNotAppliedReason
import dev.obiente.nextcloudnative.nativeui.runtime.NativeVerifiedWriteOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteExpectation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePostcondition
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreRead
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreconditionFailure
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteReadBack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteSendResult
import dev.obiente.nextcloudnative.nativeui.runtime.contradictsPostcondition
import dev.obiente.nextcloudnative.nativeui.runtime.runNativeVerifiedWrite
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * The transport-boundary write path. Every dynamic write passes the admission matrix here, holds
 * the one-in-flight-write-per-target gate, and, when its surface asked for it, runs through the
 * verified write: pre-read compare, one send, read-back, and quarantine on contradiction.
 */

/** Everything the executor knows about one write; built once per request. */
internal class DynamicWriteContext(
    val services: NextcloudPlatformServices,
    val session: NextcloudSession,
    val descriptor: DynamicAppDescriptor,
    val action: DynamicAction,
    val runtimeContext: Map<String, String>,
    val observedInputSchema: JsonElement?,
    val onMultipartUploadSucceeded: (LocalUploadFile) -> Unit,
    val clock: () -> Long,
) {
    /** The platform's durable store; a platform without one has no persisted write safety state. */
    val store: DynamicWriteQuarantineStore? by lazy {
        try {
            services.dynamicWriteQuarantineStore()
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalStateException) {
            // Service doubles and platforms without the capability expose no store.
            null
        }
    }

    /** The quarantine key of this route for writes whose encoding the contract itself declares. */
    val ruleKey: DynamicWriteRuleKey? by lazy {
        try {
            DynamicWriteRuleKey(
                accountDigest = session.accountId.storageKey,
                appId = descriptor.app.id,
                appVersion = descriptor.app.version,
                route = "${action.binding.method.name} ${action.binding.path}",
                ruleId = DYNAMIC_CONTRACT_DECLARED_RULE_ID,
                ruleVersion = DYNAMIC_CONTRACT_DECLARED_RULE_VERSION,
            )
        } catch (_: IllegalArgumentException) {
            // An app identity or route that cannot form a safe key has no persisted state.
            null
        }
    }
}

/**
 * Admission enforced at the transport boundary. Phase one enforces the refusals no generic
 * surface may override: administration, credentials, unsafeguarded sends, undeclared share
 * permission, insufficient evidence, and quarantine. The remaining safeguards are applied by the
 * surfaces that opt in through [NativeActionRequest.Submit.readBack].
 */
private val ENFORCED_REFUSALS = setOf(
    DynamicWriteRefusal.insufficientEvidence,
    DynamicWriteRefusal.ruleQuarantined,
    DynamicWriteRefusal.identityUnbound,
    DynamicWriteRefusal.bodyNotRepresentable,
    DynamicWriteRefusal.noSendSafeguard,
    DynamicWriteRefusal.noDeclaredSharePermission,
    DynamicWriteRefusal.administrationNotDynamic,
    DynamicWriteRefusal.credentialNeverDynamic,
)

internal suspend fun executeDynamicWrite(
    context: DynamicWriteContext,
    request: NativeActionRequest.Submit,
): NativeActionExecutionResult {
    val action = context.action
    val ruleState = try {
        context.ruleKey?.let { key ->
            context.store?.let { store ->
                store.observeAppVersion(key.accountDigest, key.appId, key.appVersion)
                store.ruleState(key, ruleApplies = false)
            }
        } ?: DynamicWriteRuleState.notRequired
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Persistence fault boundary: an unreadable quarantine must not be forgotten silently.
        return refusal("This app's write safety state on this device could not be read, so nothing was changed.")
    }
    val admission = admitDynamicWrite(context.admissionInput(request, ruleState))
    if (admission is DynamicWriteAdmission.Refused && admission.reason in ENFORCED_REFUSALS) {
        context.record(
            code = when (admission.reason) {
                DynamicWriteRefusal.ruleQuarantined -> DynamicDiagnosticCode.WriteQuarantined
                DynamicWriteRefusal.identityUnbound -> DynamicDiagnosticCode.IdentityBindingRefused
                else -> null
            },
            outcome = "refused",
            detail = admission.reason.name,
        )
        return refusal(admission.reason.message())
    }
    val target = request.targetKey(context)
    if (!DynamicWriteInFlightGate.tryAcquire(target)) {
        return refusal("Another change to this item is still being saved. Wait for it to finish.")
    }
    try {
        val readBack = request.readBack
        val readAction = context.readBackAction()
        return if (readBack != null && readAction != null) {
            executeVerified(context, request, readBack, readAction)
        } else {
            executeUnverified(context, request)
        }
    } finally {
        DynamicWriteInFlightGate.release(target)
    }
}

private fun DynamicWriteContext.admissionInput(request: NativeActionRequest.Submit, rule: DynamicWriteRuleState): DynamicWriteAdmissionInput {
    val riskClass = action.operationRiskClass
    val body = action.binding.body?.schema as? JsonObject
    val required = (body?.get("required") as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
    val echo = request.readBack?.takeIf { it.echoUntouched }?.untouched.orEmpty()
    return DynamicWriteAdmissionInput(
        riskClass = riskClass,
        tier = action.evidenceTier,
        identityBinding = if (action.binding.pathParameters.isEmpty()) DynamicIdentityBinding.notRequired else DynamicIdentityBinding.declared,
        rule = rule,
        facts = DynamicWriteFacts(
            declaredStateSemantics = riskClass == DynamicOperationRiskClass.idempotentStateSet,
            declaredMerge = action.binding.method == HttpMethod.PATCH,
            completeRepresentation = request.readBack?.echoUntouched == true,
            routeFamilyRead = readBackAction() != null,
            bodyFullyRepresentable = required.all { name -> name in echo || !request.values[name].isNullOrBlank() },
            declaredIdempotencyKey = action.idempotencyKeyHeader != null && request.sendAttempt?.idempotencyKey != null,
            exactlyOnceSurface = request.sendAttempt != null,
            permanentConfirmation = request.confirmed && action.requiresConfirmation,
            declaredSharePermission = action.sharePermissionFieldId != null,
        ),
    )
}

/** The exact same-route read when declared, otherwise the verified route-family read. */
private fun DynamicWriteContext.readBackAction(): DynamicAction? =
    (action.resultRecoveryActionId ?: action.readBackActionId)?.let { id -> descriptor.actions.singleOrNull { it.id == id } }

private suspend fun executeUnverified(context: DynamicWriteContext, request: NativeActionRequest.Submit): NativeActionExecutionResult {
    request.precondition?.let { precondition ->
        checkDynamicWritePrecondition(context.services, context.session, context.descriptor, context.action, precondition, context.runtimeContext)
            ?.let { refusal -> return refusal }
    }
    return try {
        val execution = context.send(context.prepare(request))
        execution.response.toDynamicActionExecutionResult(context.action).also { result ->
            releaseMultipartUploadAfterSuccess(result, execution.multipartFile, context.onMultipartUploadSucceeded)
            context.record(code = null, outcome = if (result is NativeActionExecutionResult.Success) "sent" else "failed")
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Transport and request-building fault boundary: the result is not known.
        NativeActionExecutionResult.Failure(failure.message ?: "The dynamic action failed.")
    }
}

private suspend fun executeVerified(
    context: DynamicWriteContext,
    request: NativeActionRequest.Submit,
    readBack: NativeWriteReadBack,
    readAction: DynamicAction,
): NativeActionExecutionResult {
    val prepared = try {
        context.prepare(request)
    } catch (invalid: IllegalArgumentException) {
        // Nothing was sent: the request could not be built from the contract and the values.
        return refusal(invalid.message ?: "This change could not be prepared.")
    }
    val submitted = prepared.submittedBody.filterKeys { it !in readBack.untouched && it !in readBack.customFields }
    val outcome = runNativeVerifiedWrite(
        actionLabel = context.action.label,
        preRead = request.precondition?.let { precondition ->
            {
                when (
                    checkDynamicWritePrecondition(
                        context.services, context.session, context.descriptor, context.action, precondition, context.runtimeContext,
                    )?.precondition
                ) {
                    null -> NativeWritePreRead.Unchanged
                    NativeWritePreconditionFailure.Changed -> NativeWritePreRead.Changed
                    NativeWritePreconditionFailure.Unverified -> NativeWritePreRead.Unverified
                }
            }
        },
        send = {
            val execution = context.send(prepared)
            val result = execution.response.toDynamicActionExecutionResult(context.action)
            releaseMultipartUploadAfterSuccess(result, execution.multipartFile, context.onMultipartUploadSucceeded)
            when {
                result is NativeActionExecutionResult.Success -> NativeWriteSendResult.Accepted
                (result as NativeActionExecutionResult.Failure).outcome == NativeActionFailureOutcome.Rejected ->
                    NativeWriteSendResult.Rejected(result.message)
                else -> NativeWriteSendResult.Ambiguous(result.message)
            }
        },
        readBack = {
            val read = buildDynamicApiRequest(context.descriptor, readAction, request.values, context.runtimeContext + request.values)
                .copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
            parseDynamicRecords(readAction, context.services.executeNextcloudApi(context.session, read), readAction.responseFieldIds.toSet())
        },
        verify = { records ->
            val untouched = readBack.untouched.filterKeys { it !in readBack.customFields }
            NativeWriteExpectation(readBack.targetId, submitted, untouched, readBack.previous, readBack.verifyTarget).verify(records)
        },
    )
    context.recordOutcome(outcome)
    return outcome.toExecutionResult(request, readBack)
}

private suspend fun DynamicWriteContext.recordOutcome(outcome: NativeVerifiedWriteOutcome<*>) {
    if (outcome.contradictsPostcondition) {
        val reason = if (outcome is NativeVerifiedWriteOutcome.Adjusted) {
            DynamicWriteQuarantineReason.untouchedFieldDrift
        } else {
            DynamicWriteQuarantineReason.notAppliedAfterSuccess
        }
        ruleKey?.let { key ->
            try {
                store?.quarantine(key, reason, clock())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The diagnostic below still records the contradiction; the next read fails closed.
            }
        }
        record(DynamicDiagnosticCode.WritePostconditionMismatch, "contradicted", reason.name)
        record(DynamicDiagnosticCode.WriteQuarantined, "quarantined", reason.name)
        return
    }
    record(
        code = null,
        outcome = when (outcome) {
            is NativeVerifiedWriteOutcome.Confirmed -> "confirmed"
            is NativeVerifiedWriteOutcome.Adjusted -> "adjusted"
            is NativeVerifiedWriteOutcome.NotApplied -> "not-applied"
            is NativeVerifiedWriteOutcome.Unknown -> "unknown"
        },
    )
}

private fun NativeVerifiedWriteOutcome<*>.toExecutionResult(
    request: NativeActionRequest.Submit,
    readBack: NativeWriteReadBack,
): NativeActionExecutionResult = when (this) {
    is NativeVerifiedWriteOutcome.Confirmed -> NativeActionExecutionResult.Success("Saved.")
    is NativeVerifiedWriteOutcome.Adjusted -> NativeActionExecutionResult.Success(
        message = if (drifted.isEmpty()) {
            "Saved. The app adjusted some values."
        } else {
            "Saved, but other values of this item changed at the same time. Changes here are paused until you review them."
        },
        postcondition = NativeWritePostcondition(
            adjusted = adjusted,
            drifted = drifted,
            restoreBaseline = request.baselineRestore(readBack).takeIf { drifted.isNotEmpty() },
        ),
    )
    is NativeVerifiedWriteOutcome.NotApplied -> NativeActionExecutionResult.Failure(
        message = when (reason) {
            NativeNotAppliedReason.NotAppliedAfterSuccess ->
                "The app reported success, but the change was not applied. Changes here are paused on this device."
            else -> message
        },
        outcome = NativeActionFailureOutcome.Rejected,
        precondition = when (reason) {
            NativeNotAppliedReason.PreconditionChanged -> NativeWritePreconditionFailure.Changed
            NativeNotAppliedReason.PreconditionUnverified -> NativeWritePreconditionFailure.Unverified
            else -> null
        },
    )
    is NativeVerifiedWriteOutcome.Unknown -> NativeActionExecutionResult.Failure(message, NativeActionFailureOutcome.Unknown)
}

/**
 * The full-echo write that restores the baseline: every field as it was before this write. It is
 * only offered, never sent automatically, and it carries no precondition of its own because the
 * person decides with the drifted state in front of them.
 */
private fun NativeActionRequest.Submit.baselineRestore(readBack: NativeWriteReadBack): NativeActionRequest.Submit? {
    if (!readBack.echoUntouched) return null
    val baseline = readBack.untouched + readBack.previous
    return copy(
        values = values.filterKeys { key -> key !in readBack.previous },
        precondition = null,
        // Every field travels verbatim; a custom-shaped field is echoed but not compared by wire value.
        readBack = NativeWriteReadBack(readBack.targetId, emptyMap(), baseline, echoUntouched = true, customFields = readBack.customFields),
    )
}

/** Builds the exact request; throws [IllegalArgumentException] before anything is sent. */
private fun DynamicWriteContext.prepare(request: NativeActionRequest.Submit): DynamicPreparedWrite {
    val echo = request.readBack?.takeIf { it.echoUntouched }?.untouched.orEmpty()
    val idempotencyKey = request.sendAttempt?.idempotencyKey?.takeIf { action.idempotencyKeyHeader != null }
    val apiRequest = buildDynamicApiRequest(
        descriptor = descriptor,
        action = action,
        values = request.values,
        runtimeContext = runtimeContext,
        observedInputSchema = observedInputSchema,
        exactBodyValues = echo,
    ).copy(idempotencyKey = idempotencyKey)
    val submitted = apiRequest.body?.let { bytes ->
        try {
            dynamicJson.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
    }.orEmpty()
    return DynamicPreparedWrite(apiRequest, submitted)
}

private class DynamicPreparedWrite(val request: NextcloudApiRequest, val submittedBody: Map<String, JsonElement>)

private class DynamicWriteExecutionResult(val response: NextcloudApiResponse, val multipartFile: LocalUploadFile?)

private suspend fun DynamicWriteContext.send(prepared: DynamicPreparedWrite): DynamicWriteExecutionResult =
    DynamicWriteExecutionResult(services.executeNextcloudApi(session, prepared.request), prepared.request.multipartBody?.file)

private fun NativeActionRequest.Submit.targetKey(context: DynamicWriteContext): String =
    listOf(
        context.session.accountId.storageKey,
        context.descriptor.app.id,
        context.action.binding.method.name,
        context.action.binding.path,
        context.action.binding.pathParameters.joinToString("/") { parameter -> values[parameter.name] ?: context.runtimeContext[parameter.name].orEmpty() },
    ).joinToString("\u0000")

private fun DynamicWriteContext.record(code: DynamicDiagnosticCode?, outcome: String, detail: String? = null) {
    val event = SupportDiagnosticEventDraft(
        severity = if (code == null) SupportDiagnosticSeverity.Info else SupportDiagnosticSeverity.Warning,
        component = SupportDiagnosticComponent.AdaptiveApps,
        operation = "dynamic-write",
        outcome = outcome,
        code = code?.code,
        fields = listOfNotNull(
            SupportDiagnosticFieldDraft("app", descriptor.app.id.take(64), SupportDiagnosticValuePrivacy.Identifier),
            SupportDiagnosticFieldDraft("evidence_tier", action.evidenceTier?.code ?: "none"),
            SupportDiagnosticFieldDraft("risk_class", action.operationRiskClass.code),
            SupportDiagnosticFieldDraft("rule_id", DYNAMIC_CONTRACT_DECLARED_RULE_ID),
            SupportDiagnosticFieldDraft("rule_version", DYNAMIC_CONTRACT_DECLARED_RULE_VERSION.toString()),
            detail?.let { SupportDiagnosticFieldDraft("reason", it) },
        ),
    )
    try {
        services.recordSupportDiagnostic(event)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Diagnostics never block or fail the primary workflow.
    }
}

private fun refusal(message: String) = NativeActionExecutionResult.Failure(message, NativeActionFailureOutcome.Rejected)

private fun DynamicWriteRefusal.message(): String = when (this) {
    DynamicWriteRefusal.ruleQuarantined ->
        "Changes here are paused on this device because an earlier change did not turn out as the app reported. " +
            "Review it, then resume changes from the app's compatibility details."
    DynamicWriteRefusal.administrationNotDynamic, DynamicWriteRefusal.credentialNeverDynamic ->
        "This change needs the app's own administration or security settings; it is not offered here."
    DynamicWriteRefusal.noSendSafeguard, DynamicWriteRefusal.bodyNotRepresentable ->
        "Sending is offered only where each attempt is recorded and never repeated without your confirmation."
    DynamicWriteRefusal.noDeclaredSharePermission ->
        "Sharing is offered only when the app states that you may share this item."
    DynamicWriteRefusal.identityUnbound -> "This item could not be identified safely, so nothing was changed."
    else -> "The installed app's contract does not describe this change well enough to make it safely."
}

/**
 * One in-flight write per target record, process wide: a second write to the same route and
 * bound identities waits for the person instead of racing the first one.
 */
internal object DynamicWriteInFlightGate {
    private val lock = DynamicNativeMemoryCacheLock()
    private val targets = mutableSetOf<String>()

    fun tryAcquire(target: String): Boolean = lock.withLock { targets.add(target) }

    fun release(target: String) {
        lock.withLock { targets.remove(target) }
    }
}
