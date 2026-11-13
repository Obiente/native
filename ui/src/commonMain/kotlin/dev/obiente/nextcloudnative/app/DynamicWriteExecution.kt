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
import dev.obiente.nextcloudnative.nativeui.runtime.executionRulePlan
import dev.obiente.nextcloudnative.nativeui.runtime.NativeNotAppliedReason
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeVerifiedWriteOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteExpectation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePostcondition
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePrecondition
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreRead
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreconditionFailure
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteReadBack
import dev.obiente.nextcloudnative.nativeui.runtime.editedBaselineHolds
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteSendResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWriteVerification
import dev.obiente.nextcloudnative.nativeui.runtime.contradictsPostcondition
import dev.obiente.nextcloudnative.nativeui.runtime.runNativeVerifiedWrite
import dev.obiente.nextcloudnative.nativeui.runtime.sameWireValue
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
    val ruleAuthority: () -> dev.obiente.nextcloudnative.nativeui.runtime.NativeRuleAuthority? = { null },
    val membershipAuthority: () -> dev.obiente.nextcloudnative.nativeui.runtime.NativeItemReadAuthority? = { null },
    val accountProducer: AccountPrivateMemoryProducer? = sharedAccountPrivateMemoryGate.producer(session.accountId.storageKey),
    val writeIsAuthorized: () -> Boolean = { true },
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

    /**
     * The quarantine key of this route for writes whose encoding the contract itself declares. It
     * always forms: an app ID, version, or route outside the key alphabet is keyed by its digest.
     */
    val ruleKey: DynamicWriteRuleKey by lazy {
        DynamicWriteRuleKey.of(
            accountDigest = session.accountId.storageKey,
            appId = descriptor.app.id,
            appVersion = descriptor.app.version,
            route = "${action.binding.method.name} ${action.binding.path}",
            ruleId = DYNAMIC_CONTRACT_DECLARED_RULE_ID,
            ruleVersion = DYNAMIC_CONTRACT_DECLARED_RULE_VERSION,
        )
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
    DynamicWriteRefusal.pendingVerification,
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
    val route = context.patternRuleAdmissionGate()
    if (route != null && !DynamicWriteInFlightGate.tryAcquire(route)) {
        val message = "Another change here is still being checked. Wait for it to finish."
        return if (request.executionRulePlan == null) refusal(message)
        else NativeActionExecutionResult.Failure(message, NativeActionFailureOutcome.Unknown)
    }
    try {
        return if (request.executionRulePlan != null) executeDynamicRuleWrite(context, request)
        else executeDeclaredDynamicWrite(context, request)
    } finally {
        route?.let(DynamicWriteInFlightGate::release)
    }
}

private suspend fun executeDeclaredDynamicWrite(
    context: DynamicWriteContext,
    request: NativeActionRequest.Submit,
): NativeActionExecutionResult {
    if (context.requiresRulePlan()) return refusal("This item change needs a current verified plan. Refresh and use its item controls.")
    val action = context.action
    val ruleState = try {
        context.store?.let { store ->
            if (store.records(context.session.accountId.storageKey, context.descriptor.app.id).any {
                    (it.pendingVerification != null || it.reason == DynamicWriteQuarantineReason.abandonedUnknown ||
                        it.key.ruleId == "label-membership" && it.status == DynamicWriteRuleStatus.quarantined) && it.key.route == context.canonicalRuleRoute()
                }) return NativeActionExecutionResult.Failure("Changes here are paused because an earlier result remains unknown. Review it in Contract info.", NativeActionFailureOutcome.Unknown)
            store.observeAppVersion(context.ruleKey.accountDigest, context.descriptor.app.id, context.descriptor.app.version)
            store.ruleState(context.ruleKey, ruleApplies = false)
        } ?: DynamicWriteRuleState.notRequired
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Persistence fault boundary: an unreadable quarantine must not be forgotten silently.
        return refusal("This app's write safety state on this device could not be read, so nothing was changed.")
    }
    if (request.baselineRestore && !DynamicBaselineRestores.isOffered(context.ruleKey, request)) {
        return refusal(RESTORE_UNAVAILABLE)
    }
    // The offered baseline restore is the one write a quarantine lets through; every other
    // admission rule still applies to it.
    val admittedRule = if (request.baselineRestore && ruleState == DynamicWriteRuleState.quarantined) {
        DynamicWriteRuleState.notRequired
    } else {
        ruleState
    }
    val admission = admitDynamicWrite(context.admissionInput(request, admittedRule))
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
    val target = try { context.canonicalTargetGate(action, request.values) } catch (_: IllegalArgumentException) {
        return refusal("This item's target could not be resolved, so nothing was changed.")
    }
    if (!DynamicWriteInFlightGate.tryAcquire(target)) {
        return refusal("Another change to this item is still being saved. Wait for it to finish.")
    }
    try {
        if (action.operationRiskClass == DynamicOperationRiskClass.sharing) {
            context.sharePermissionRefusal(request)?.let { refused ->
                context.record(code = null, outcome = "refused", detail = "share-permission-${refused.name.lowercase()}")
                return refusal(refused.message)
            }
        }
        val readBack = request.readBack
        val readAction = context.readBackAction()
        return when {
            readBack != null && readAction != null -> executeVerified(context, request, readBack, readAction)
            request.baselineRestore -> refusal("This restore cannot be checked by reading it back, so nothing was changed.")
            else -> executeUnverified(context, request)
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

/**
 * A merge write to a record the editor read earlier compares the edited fields with a fresh
 * route-family read first, so an edit never silently replaces a concurrent change.
 */
private fun baselinePreRead(
    context: DynamicWriteContext,
    request: NativeActionRequest.Submit,
    readBack: NativeWriteReadBack,
    readAction: DynamicAction,
): (suspend () -> NativeWritePreRead)? {
    if (!readBack.preReadBaseline) return null
    return {
        val read = buildDynamicApiRequest(context.descriptor, readAction, request.values, context.runtimeContext + request.values)
            .copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
        val records = parseDynamicRecords(readAction, context.services.executeNextcloudApi(context.session, read), readAction.responseFieldIds.toSet())
        val target = records.filter { it.id == readBack.targetId }.singleOrNull()?.takeIf(NativeRecord::actionSafeIdentity)
        when {
            target == null -> NativeWritePreRead.Unverified
            readBack.editedBaselineHolds(target) -> NativeWritePreRead.Unchanged
            else -> NativeWritePreRead.Changed
        }
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
    // Context inputs travel in the body but are not edits. An edited field can be absent from
    // the original read, so membership cannot be inferred from the previous-value map.
    val submitted = prepared.submittedBody.filterKeys { it in readBack.editedFields && it !in readBack.customFields }
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
        } ?: baselinePreRead(context, request, readBack, readAction),
        send = {
            // Preparation and pre-read failures preserve the offer. Consume it immediately before
            // the transport attempt, while the target gate is held, including ambiguous attempts.
            if (request.baselineRestore && !DynamicBaselineRestores.take(context.ruleKey, request)) {
                NativeWriteSendResult.Rejected(RESTORE_UNAVAILABLE)
            } else {
                val execution = context.send(prepared)
                val result = execution.response.toDynamicActionExecutionResult(context.action)
                releaseMultipartUploadAfterSuccess(result, execution.multipartFile, context.onMultipartUploadSucceeded)
                when {
                    result is NativeActionExecutionResult.Success -> NativeWriteSendResult.Accepted
                    (result as NativeActionExecutionResult.Failure).outcome == NativeActionFailureOutcome.Rejected ->
                        NativeWriteSendResult.Rejected(result.message)
                    else -> NativeWriteSendResult.Ambiguous(result.message)
                }
            }
        },
        readBack = {
            val read = buildDynamicApiRequest(context.descriptor, readAction, request.values, context.runtimeContext + request.values)
                .copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
            parseDynamicRecords(readAction, context.services.executeNextcloudApi(context.session, read), readAction.responseFieldIds.toSet())
        },
        verify = { records ->
            val untouched = readBack.untouched.filterKeys { it !in readBack.customFields }
            val verification = NativeWriteExpectation(
                readBack.targetId, submitted, untouched, readBack.previous, readBack.verifyTarget, readBack.absentPrevious,
            ).verify(records)
            if (request.baselineRestore && verification is NativeWriteVerification.Adjusted) {
                NativeWriteVerification.NotApplied("The previous values were not fully restored.")
            } else verification
        },
    )
    context.recordOutcome(outcome)
    return outcome.toExecutionResult(request, readBack, context.ruleKey)
}

private suspend fun DynamicWriteContext.recordOutcome(outcome: NativeVerifiedWriteOutcome<*>) {
    if (outcome.contradictsPostcondition) {
        val reason = if (outcome is NativeVerifiedWriteOutcome.Adjusted) {
            DynamicWriteQuarantineReason.untouchedFieldDrift
        } else {
            DynamicWriteQuarantineReason.notAppliedAfterSuccess
        }
        try {
            store?.quarantine(ruleKey, reason, clock())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The diagnostic below still records the contradiction; the next read fails closed.
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

private fun NativeVerifiedWriteOutcome<List<NativeRecord>>.toExecutionResult(
    request: NativeActionRequest.Submit,
    readBack: NativeWriteReadBack,
    ruleKey: DynamicWriteRuleKey,
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
            restoreBaseline = if (drifted.isEmpty()) null else request.baselineRestore(readBack, snapshot, ruleKey),
        ),
    )
    is NativeVerifiedWriteOutcome.NotApplied -> NativeActionExecutionResult.Failure(
        message = when {
            request.baselineRestore -> message
            reason == NativeNotAppliedReason.NotAppliedAfterSuccess ->
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
 * only offered, never sent automatically, and never offered for a restore itself. Its pre-read
 * compare expects the drifted state the person saw, so a further change stops it, and its
 * read-back verifies every baseline field. The executor registers it so only this exact request
 * passes the quarantine the drift caused.
 */
private fun NativeActionRequest.Submit.baselineRestore(
    readBack: NativeWriteReadBack,
    snapshot: List<NativeRecord>,
    ruleKey: DynamicWriteRuleKey,
): NativeActionRequest.Submit? {
    if (!readBack.echoUntouched || baselineRestore) return null
    // There is no declared way to restore an absent field, or a custom shape without its own verifier.
    if (readBack.absentPrevious.isNotEmpty() || readBack.customFields.isNotEmpty() && readBack.verifyRestoreTarget == null) return null
    val original = precondition ?: return null
    val drifted = snapshot.filter { it.id == readBack.targetId }.singleOrNull()?.takeIf(NativeRecord::actionSafeIdentity) ?: return null
    val baseline = readBack.untouched + readBack.previous
    return copy(
        values = values.filterKeys { key -> key !in readBack.editedFields && key !in readBack.customFields },
        precondition = NativeWritePrecondition(original.readValues, readBack.targetId) { current ->
            current.values == drifted.values && sameWireValue(JsonObject(current.wireValues), JsonObject(drifted.wireValues))
        },
        readBack = NativeWriteReadBack(
            readBack.targetId, emptyMap(), baseline, echoUntouched = true,
            verifyTarget = readBack.verifyRestoreTarget, customFields = readBack.customFields,
        ),
        baselineRestore = true,
    ).also { DynamicBaselineRestores.issue(ruleKey, it) }
}

/**
 * Baseline restores this process offered, each accepted once and only as the exact request that
 * was offered, so a restore can never carry values other than the drifted write's baseline.
 */
internal object DynamicBaselineRestores {
    private val lock = DynamicNativeMemoryCacheLock()
    private val issued = ArrayDeque<Pair<DynamicWriteRuleKey, NativeActionRequest.Submit>>()

    fun issue(key: DynamicWriteRuleKey, request: NativeActionRequest.Submit) {
        lock.withLock {
            issued.addLast(key to request)
            while (issued.size > MAX_OFFERED_RESTORES) issued.removeFirst()
        }
    }

    fun isOffered(key: DynamicWriteRuleKey, request: NativeActionRequest.Submit): Boolean = lock.withLock {
        issued.any { it.first == key && it.second === request }
    }

    fun take(key: DynamicWriteRuleKey, request: NativeActionRequest.Submit): Boolean = lock.withLock {
        val index = issued.indexOfFirst { it.first == key && it.second === request }
        if (index < 0) false else { issued.removeAt(index); true }
    }
}

private const val MAX_OFFERED_RESTORES = 32

private const val RESTORE_UNAVAILABLE = "This restore is no longer available. Refresh to see the current values."

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
