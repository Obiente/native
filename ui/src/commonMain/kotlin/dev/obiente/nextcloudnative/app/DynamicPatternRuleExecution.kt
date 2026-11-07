package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.rules.PatternRouteFamily
import dev.obiente.nextcloudnative.nativeui.rules.PatternRuleRegistry
import dev.obiente.nextcloudnative.nativeui.rules.requiresPatternRulePlan
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Re-match with the runtime's descriptor and load. Caller-supplied safety facts are not authority. */
internal suspend fun executeDynamicRuleWrite(context: DynamicWriteContext, request: NativeActionRequest.Submit): NativeActionExecutionResult {
    val submitted = request.executionRulePlan ?: return ruleRefused()
    if (request.rulePlan != null && request.membershipPlan != null) return ruleRefused()
    val plan = context.rebuildRulePlan(submitted, request.values) ?: return ruleRefused()
    if (plan != submitted || request.values != plan.writeBindings || request.baselineRestore || request.sendAttempt != null) return ruleRefused()
    val store = context.store ?: return ruleRefused("Encrypted recovery and write safety storage are required before changing this item.")
    val key = context.ruleKey(plan)
    val gate = context.targetGate(plan)
    if (!DynamicWriteInFlightGate.tryAcquire(gate)) return ruleUnknown("Another change to this item is still being checked.")
    var unresolved = false
    var reservedEvidence: DynamicRuleRecoveryEvidence? = null
    var sendStarted = false
    try {
        if (!context.accountIsCurrent()) return ruleRefused()
        val generation = store.generation(key.accountDigest)
        if (!context.accountIsCurrent()) return ruleRefused()
        val records = store.records(key.accountDigest, context.descriptor.app.id)
        if (records.any { it.key.sameCanonicalRoute(key) && it.reason == DynamicWriteQuarantineReason.abandonedUnknown }) {
            return ruleRefused("Changes here remain paused because an earlier result is unknown. Review it in Contract info before resuming.")
        }
        val pending = records.singleOrNull {
            it.key.sameCanonicalRoute(key) && it.pendingVerification != null
        }
        if (pending != null) {
            unresolved = true
            if (request.ruleRecovery == null) return ruleUnknown(PENDING_MESSAGE)
            val evidence = context.loadRecovery(pending) ?: return ruleUnknown(UNREADABLE_MESSAGE)
            if (!evidence.plan.sameOperation(plan)) return ruleUnknown("Check the earlier change before changing another item or state.")
            return context.reconcile(evidence, store, repeat = request.ruleRecovery == NativeRuleRecoveryIntent.RepeatSameOperation)
        }
        if (request.ruleRecovery != null) return ruleRefused("No matching unresolved change is available. Refresh before changing this item.")
        store.observeAppVersion(key.accountDigest, context.descriptor.app.id, context.descriptor.app.version)
        if (store.record(key)?.status == DynamicWriteRuleStatus.quarantined) return ruleRefused("Changes here are paused. Review the compatibility details before resuming.")
        val record = store.beginProbation(key, context.clock(), generation)
        val admission = admitDynamicWrite(DynamicWriteAdmissionInput(DynamicOperationRiskClass.idempotentStateSet,
            context.action.evidenceTier, if (plan.identity.observed) DynamicIdentityBinding.observed else DynamicIdentityBinding.declared,
            record.ruleState, DynamicWriteFacts(routeFamilyRead = true)))
        if (admission !is DynamicWriteAdmission.Admitted) return ruleRefused()
        val read = context.descriptor.actions.singleOrNull { it.id == plan.verificationReadActionId } ?: return ruleRefused()
        val source = context.descriptor.actions.singleOrNull { it.id == plan.identity.sourceActionId } ?: return ruleRefused()
        // Prepare before reserving. Malformed bindings are known-before-send refusals.
        val prepared = buildDynamicApiRequest(context.descriptor, context.action, plan.writeBindings, emptyMap())
        if (plan.identity.roundTripReadActionId != null) {
            val before = context.readRuleState(plan, read)
            if (before.singleOrNull()?.id != plan.targetId || !context.identifiesRuleTarget(plan, before)) return ruleRefused()
            if (plan is NativeMembershipRulePlan && !plan.postcondition.baselineMatches(before.single())) return ruleRefused("The labels changed. Refresh this item before trying again.")
        }
        if (context.rebuildRulePlan(plan, request.values) != plan || !context.accountIsCurrent()) return ruleRefused()
        val token = newRuleToken(context.clock())
        val recovery = context.recovery(token.operationId) ?: return ruleRefused()
        val evidence = DynamicRuleRecoveryEvidence(key = key, pending = token, appId = context.descriptor.app.id,
            appVersion = context.descriptor.app.version, plan = plan, write = context.action, read = read, sourceRead = source,
            inverseWrite = (plan as? NativeMembershipRulePlan)?.let { membership -> context.descriptor.actions.single {
                it.id == if (membership.actionId == membership.proof.assignActionId) membership.proof.removeActionId else membership.proof.assignActionId
            } })
        val reserved = withRuleRecoveryMaintenance {
            context.services.pruneRuleRecovery(context.session, store)
            try {
                recovery.save(evidence)
                currentCoroutineContext().ensureActive()
                reservedEvidence = evidence
                unresolved = true
                store.reserve(key, token, generation).also { if (!it) recovery.remove() }
            } catch (failure: Exception) {
                // A save or reserve may publish and then throw. Preserve a durable reservation;
                // otherwise remove its draft even when the caller's coroutine was cancelled.
                withContext(NonCancellable) {
                    try {
                        if (store.record(key)?.pendingVerification != token) recovery.remove()
                    } catch (_: Exception) {
                        // The next bounded sweep retries cleanup, retaining every pending token.
                    }
                }
                throw failure
            }
        }
        if (!reserved) return ruleUnknown(PENDING_MESSAGE)
        // From reservation onwards cancellation and failures retain recovery. No background replay.
        if (context.rebuildRulePlan(plan, request.values) != plan || !context.accountIsCurrent()) {
            context.finish(evidence, store, DynamicPendingRuleResolution.rejected)
            return ruleRefused()
        }
        if (record.status == DynamicWriteRuleStatus.probation) context.ruleDiagnostic(plan, DynamicDiagnosticCode.WriteRuleProbation, "probation")
        sendStarted = true
        return context.sendRule(evidence, store, prepared)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (!sendStarted && reservedEvidence?.let { context.clearUnsentReservation(it, store) } == true) {
            return ruleRefused("This change could not be sent. Nothing was changed by this attempt.")
        }
        // Persistence/transport boundary. Never call an unresolved reservation unsent.
        return if (unresolved) ruleUnknown("This change could not be completed or checked. Check its status before another change.")
        else ruleRefused("This change could not be safely prepared or saved for recovery. Nothing was sent.")
    } finally {
        DynamicWriteInFlightGate.release(gate)
    }
}

/** A source-approved rule action cannot fall through to the old unverified execution path. */
internal fun DynamicWriteContext.requiresRulePlan(): Boolean {
    val schema = descriptor.toNativeAppSchema()
    val actions = schema.actions + schema.dedicatedActions
    val write = actions.singleOrNull { it.id == action.id } ?: return false
    return schema.requiresPatternRulePlan(write)
}

internal fun DynamicWriteContext.canonicalRuleRoute(path: String = action.binding.path): String {
    val segments = path.split('/').filter(String::isNotEmpty)
    val target = if (segments.lastOrNull()?.startsWith('{') == false && segments.getOrNull(segments.lastIndex - 1)?.startsWith('{') == true) segments.dropLast(1) else segments
    val route = target.joinToString("/", prefix = "/") { if (it.startsWith('{') && it.endsWith('}')) "{}" else it }
    return DynamicWriteRuleKey.of(session.accountId.storageKey, descriptor.app.id, descriptor.app.version,
        route, DYNAMIC_CONTRACT_DECLARED_RULE_ID, DYNAMIC_CONTRACT_DECLARED_RULE_VERSION).route
}

/** Serializes admission and delivery for rule routes, including ordinary sibling commands. */
internal fun DynamicWriteContext.patternRuleAdmissionGate(): String? {
    val route = canonicalRuleRoute()
    val schema = descriptor.toNativeAppSchema()
    if ((schema.actions + schema.dedicatedActions).none {
            it.binding.method != HttpMethod.GET && canonicalRuleRoute(it.binding.path) == route && schema.requiresPatternRulePlan(it)
        }) return null
    return listOf("rule-route", session.accountId.storageKey, descriptor.app.id, route).joinToString("\u0000")
}

/** One process gate for the actual item URL, including sibling commands and ordinary edits. */
internal fun DynamicWriteContext.canonicalTargetGate(action: DynamicAction, values: Map<String, String>): String {
    val path = bindDynamicPath(action.binding, values, runtimeContext, action.resourceId)
    val segments = action.binding.path.split('/').filter(String::isNotEmpty)
    val command = segments.lastOrNull()?.startsWith('{') == false && segments.getOrNull(segments.lastIndex - 1)?.startsWith('{') == true
    val target = if (command) path.substringBeforeLast('/') else path
    return listOf(session.accountId.storageKey, descriptor.app.id, target).joinToString("\u0000")
}

internal suspend fun recoverDynamicRuleOperations(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    clock: () -> Long,
    producer: AccountPrivateMemoryProducer?,
): NativeActionExecutionResult {
    try {
        val store = services.dynamicWriteQuarantineStore() ?: return ruleUnknown(UNREADABLE_MESSAGE)
        withRuleRecoveryMaintenance { services.pruneRuleRecovery(session, store) }
        val pending = store.records(session.accountId.storageKey, descriptor.app.id).filter { it.pendingVerification != null }
        if (pending.isEmpty()) return NativeActionExecutionResult.Success("No changes are waiting for verification.")
        var remaining = 0
        for (record in pending) {
            val action = descriptor.actions.firstOrNull { it.binding.method != HttpMethod.GET } ?: return ruleUnknown(PENDING_MESSAGE)
            val context = DynamicWriteContext(services, session, descriptor, action, emptyMap(), null, {}, clock, accountProducer = producer)
            val evidence = context.loadRecovery(record)
            if (evidence == null) { remaining++; continue }
            val gate = context.targetGate(evidence.plan)
            if (!DynamicWriteInFlightGate.tryAcquire(gate)) { remaining++; continue }
            try {
                if (context.reconcile(evidence, store, repeat = false) is NativeActionExecutionResult.Failure) remaining++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed network status read leaves valid recovery pending, not unreadable.
                remaining++
            } finally {
                DynamicWriteInFlightGate.release(gate)
            }
        }
        return if (remaining == 0) NativeActionExecutionResult.Success("The earlier changes were verified.") else ruleUnknown(PENDING_MESSAGE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return ruleUnknown(UNREADABLE_MESSAGE)
    }
}

private suspend fun DynamicWriteContext.loadRecovery(record: DynamicWriteRuleRecord): DynamicRuleRecoveryEvidence? {
    val token = record.pendingVerification ?: return null
    val evidence = recovery(token.operationId)?.load() ?: return null
    if (evidence.key != record.key || evidence.pending != token || evidence.appId != descriptor.app.id ||
        evidence.appVersion != descriptor.app.version || evidence.key.accountDigest != session.accountId.storageKey) return null
    if (evidence.write.id != evidence.plan.actionId || evidence.read.id != evidence.plan.verificationReadActionId ||
        evidence.sourceRead.id != evidence.plan.identity.sourceActionId || evidence.plan.identity.actionId != evidence.plan.actionId) return null
    if (descriptor.actions.singleOrNull { it.id == evidence.write.id } != evidence.write ||
        descriptor.actions.singleOrNull { it.id == evidence.read.id } != evidence.read ||
        descriptor.actions.singleOrNull { it.id == evidence.sourceRead.id } != evidence.sourceRead) return null
    val schema = descriptor.toNativeAppSchema()
    val actions = schema.actions + schema.dedicatedActions
    val write = actions.singleOrNull { it.id == evidence.plan.actionId } ?: return null
    val rule = PatternRuleRegistry.shipped.ruleFor(schema, PatternRouteFamily(write, actions.filter { it.binding.method == HttpMethod.GET })) ?: return null
    if (rule.ruleId != evidence.plan.ruleId || rule.ruleVersion != evidence.plan.ruleVersion || ruleKey(evidence.plan) != record.key) return null
    if (evidence.inverseWrite != null && descriptor.actions.singleOrNull { it.id == evidence.inverseWrite.id } != evidence.inverseWrite) return null
    if (!evidence.hasConsistentPlan(schema)) return null
    return evidence
}

internal suspend fun canRepeatDynamicRuleOperation(context: DynamicWriteContext, request: NativeActionRequest.Submit): Boolean = try {
    val plan = request.executionRulePlan
    val record = plan?.let { candidate -> context.store?.records(context.session.accountId.storageKey, context.descriptor.app.id)
        ?.singleOrNull { it.key.sameCanonicalRoute(context.ruleKey(candidate)) && it.pendingVerification != null } }
    if (plan == null || record == null || record.status == DynamicWriteRuleStatus.quarantined || !context.accountIsCurrent()) false
    else context.loadRecovery(record)?.plan?.sameOperation(plan) == true
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
    // Missing or incompatible recovery never advertises a repeat.
    false
}

private suspend fun DynamicWriteContext.reconcile(
    evidence: DynamicRuleRecoveryEvidence,
    store: DynamicWriteQuarantineStore,
    repeat: Boolean,
): NativeActionExecutionResult {
    if (!accountIsCurrent()) return ruleUnknown(PENDING_MESSAGE)
    val plan = evidence.plan
    val records = readRuleState(plan, evidence.read)
    if (!identifiesRuleTarget(plan, records) || !accountIsCurrent()) return ruleUnknown(PENDING_MESSAGE)
    if (plan.verifyRuleState(records) == NativeWriteVerification.Confirmed) {
        return if (finish(evidence, store, DynamicPendingRuleResolution.confirmed)) NativeActionExecutionResult.Success("The earlier change was verified.")
        else ruleUnknown(PENDING_MESSAGE)
    }
    if (!plan.postcondition.baselineMatches(records.single { it.id == plan.targetId })) {
        store.resolve(evidence.key, evidence.pending.operationId, DynamicPendingRuleResolution.contradictedPending, clock())
        ruleDiagnostic(plan, DynamicDiagnosticCode.WritePostconditionMismatch, "contradicted")
        return ruleUnknown("The earlier change read back differently than expected. Changes remain paused.")
    }
    if (!repeat) return ruleUnknown(PENDING_MESSAGE)
    // Explicit R1 repeat after status reconciliation, retaining the same logical operation token.
    val current = rebuildRulePlan(plan, plan.writeBindings) ?: return ruleRefused()
    if (!current.sameOperation(plan) || !accountIsCurrent()) return ruleRefused()
    val record = store.record(evidence.key)
    if (record?.pendingVerification != evidence.pending || record.status == DynamicWriteRuleStatus.quarantined) return ruleUnknown(PENDING_MESSAGE)
    val prepared = buildDynamicApiRequest(descriptor, evidence.write, plan.writeBindings, emptyMap())
    return sendRule(evidence, store, prepared, repeating = true)
}

private suspend fun DynamicWriteContext.sendRule(
    evidence: DynamicRuleRecoveryEvidence,
    store: DynamicWriteQuarantineStore,
    prepared: NextcloudApiRequest,
    repeating: Boolean = false,
): NativeActionExecutionResult {
    currentCoroutineContext().ensureActive()
    if (!accountIsCurrent() || store.record(evidence.key)?.pendingVerification != evidence.pending) return ruleUnknown(PENDING_MESSAGE)
    // The entry check preceded network and disk suspensions. Never wait for rediscovery here:
    // its result could leave the already-read baseline stale. A repeat keeps its earlier token.
    if (!accountIsCurrent() || !writeIsAuthorized()) {
        if (repeating) return ruleUnknown("The app contract changed before the repeat. The earlier result remains unknown.")
        return if (finish(evidence, store, DynamicPendingRuleResolution.rejected))
            ruleRefused("The app contract changed before sending. Refresh before trying again.") else ruleUnknown(PENDING_MESSAGE)
    }
    var repeatRejected = false
    var invalidMembershipReadBack = false
    val outcome = runNativeVerifiedWrite(
        send = {
            val result = services.executeNextcloudApi(session, prepared).toDynamicActionExecutionResult(evidence.write)
            when (result) {
                is NativeActionExecutionResult.Success -> NativeWriteSendResult.Accepted
                is NativeActionExecutionResult.Failure -> {
                    repeatRejected = repeating && result.outcome == NativeActionFailureOutcome.Rejected
                    if (result.outcome == NativeActionFailureOutcome.Rejected && !repeating)
                        NativeWriteSendResult.Rejected(result.message) else NativeWriteSendResult.Ambiguous(result.message)
                }
            }
        },
        readBack = { readRuleState(evidence.plan, evidence.read) },
        verify = { records ->
            if (identifiesRuleTarget(evidence.plan, records)) {
                invalidMembershipReadBack = !evidence.plan.postcondition.observationAvailable(records.single { it.id == evidence.plan.targetId })
                evidence.plan.verifyRuleState(records)
            } else NativeWriteVerification.Unidentified
        },
        actionLabel = "Change item",
    )
    return when (outcome) {
        is NativeVerifiedWriteOutcome.Confirmed -> if (finish(evidence, store, DynamicPendingRuleResolution.confirmed)) NativeActionExecutionResult.Success("Saved and verified.") else ruleUnknown(PENDING_MESSAGE)
        is NativeVerifiedWriteOutcome.NotApplied -> when (outcome.reason) {
            NativeNotAppliedReason.Rejected -> {
                if (finish(evidence, store, DynamicPendingRuleResolution.rejected)) ruleRefused("The app rejected this change.") else ruleUnknown(PENDING_MESSAGE)
            }
            NativeNotAppliedReason.NotAppliedAfterSuccess -> {
                if (!finish(evidence, store, DynamicPendingRuleResolution.contradicted)) return ruleUnknown(PENDING_MESSAGE)
                ruleDiagnostic(evidence.plan, DynamicDiagnosticCode.WritePostconditionMismatch, "contradicted")
                ruleDiagnostic(evidence.plan, DynamicDiagnosticCode.WriteQuarantined, "quarantined")
                ruleRefused("The app reported success but did not store the requested state. Changes here are paused.")
            }
            else -> {
                val baselineMatches = outcome.snapshot?.singleOrNull { it.id == evidence.plan.targetId }?.let(evidence.plan.postcondition::baselineMatches) == true
                if (repeatRejected && baselineMatches) {
                    return if (finish(evidence, store, DynamicPendingRuleResolution.rejected))
                        ruleRefused("The app rejected the repeat, and the current state is unchanged.") else ruleUnknown(PENDING_MESSAGE)
                }
                if (!baselineMatches && accountIsCurrent()) {
                    store.resolve(evidence.key, evidence.pending.operationId, DynamicPendingRuleResolution.contradictedPending, clock())
                    ruleDiagnostic(evidence.plan, DynamicDiagnosticCode.WriteQuarantined, "contradicted")
                }
                ruleUnknown(PENDING_MESSAGE)
            }
        }
        is NativeVerifiedWriteOutcome.Unknown -> {
            if (invalidMembershipReadBack && accountIsCurrent()) {
                store.resolve(evidence.key, evidence.pending.operationId, DynamicPendingRuleResolution.contradictedPending, clock())
                ruleDiagnostic(evidence.plan, DynamicDiagnosticCode.WriteQuarantined, "unreadable-membership")
            }
            ruleUnknown(PENDING_MESSAGE)
        }
        is NativeVerifiedWriteOutcome.Adjusted -> error("Rule postconditions never adjust values.")
    }
}

private suspend fun DynamicWriteContext.readRuleState(plan: NativeRuleExecutionPlan, read: DynamicAction): List<NativeRecord> {
    val request = buildDynamicApiRequest(descriptor, read, plan.readBindings, emptyMap()).copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
    return parseDynamicRecords(read, services.executeNextcloudApi(session, request), read.responseFieldIds.toSet())
}

private fun DynamicWriteContext.identifiesRuleTarget(plan: NativeRuleExecutionPlan, records: List<NativeRecord>): Boolean {
    if (plan is NativeMembershipRulePlan) return records.singleOrNull()?.let {
        it.id == plan.targetId && membershipIdentityMatches(plan.proof, it, plan.readBindings)
    } == true
    val record = records.singleOrNull { it.id == plan.targetId } ?: return false
    val schema = descriptor.toNativeAppSchema()
    val actions = schema.actions + schema.dedicatedActions
    val write = actions.singleOrNull { it.id == plan.actionId } ?: return false
    val targetName = write.binding.path.split('/').last { it.startsWith('{') }.removeSurrounding("{", "}")
    if (!record.matchesRuleIdentityType(write.binding.pathParameterTypes[targetName])) return false
    val source = actions.singleOrNull { it.id == plan.identity.sourceActionId } ?: return false
    val read = actions.singleOrNull { it.id == plan.verificationReadActionId } ?: return false
    val navigation = source.binding.path.split('/').mapIndexedNotNull { index, segment ->
        if (!segment.startsWith('{')) null else {
            val writeName = write.binding.path.split('/')[index].removeSurrounding("{", "}")
            segment.removeSurrounding("{", "}") to (plan.writeBindings[writeName] ?: return false)
        }
    }.toMap()
    val proof = bindObservedIdentity(NativeIdentityRoute(write.binding.path, write.binding.pathParameterTypes),
        NativeIdentityRoute(source.binding.path, source.binding.pathParameterTypes),
        read.takeIf { it.id != source.id }?.let { NativeIdentityRoute(it.binding.path, it.binding.pathParameterTypes) },
        record, records, 1, 1, navigation)
    return proof is NativeObservedIdentityProof.Bound && proof.values.all { (key, value) -> plan.writeBindings[key] == value }
}

private suspend fun DynamicWriteContext.finish(evidence: DynamicRuleRecoveryEvidence, store: DynamicWriteQuarantineStore, resolution: DynamicPendingRuleResolution): Boolean {
    if (!accountIsCurrent() || !store.resolve(evidence.key, evidence.pending.operationId, resolution, clock())) return false
    if (resolution == DynamicPendingRuleResolution.confirmed) ruleDiagnostic(evidence.plan, DynamicDiagnosticCode.WriteRuleConfirmed, "confirmed")
    try { recovery(evidence.pending.operationId)?.remove() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
        // The verified token is already durably resolved. An orphan encrypted draft is not a pending write.
    }
    return true
}

private suspend fun DynamicWriteContext.clearUnsentReservation(evidence: DynamicRuleRecoveryEvidence, store: DynamicWriteQuarantineStore): Boolean = try {
    val current = store.record(evidence.key)
    if (current?.pendingVerification == evidence.pending) finish(evidence, store, DynamicPendingRuleResolution.rejected)
    else { recovery(evidence.pending.operationId)?.remove(); true }
} catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
    // A failed clear retains the pending token; recovery must not claim the operation is unsent.
    false
}

private fun DynamicWriteContext.recovery(token: String): TextDraftBackedDynamicRuleRecoveryStore? =
    sharedAccountPrivateMemoryGate.read(accountProducer, null) { services.ruleRecoveryStore(session, token) }

private fun DynamicWriteContext.accountIsCurrent(): Boolean = sharedAccountPrivateMemoryGate.read(accountProducer, false) { true }

private fun DynamicWriteContext.ruleKey(plan: NativeRuleExecutionPlan) = DynamicWriteRuleKey.of(session.accountId.storageKey,
    descriptor.app.id, descriptor.app.version, plan.target.family, plan.ruleId, plan.ruleVersion)
private fun DynamicWriteContext.targetGate(plan: NativeRuleExecutionPlan) =
    canonicalTargetGate(descriptor.actions.single { it.id == plan.actionId }, plan.writeBindings)

private fun DynamicWriteContext.ruleDiagnostic(plan: NativeRuleExecutionPlan, code: DynamicDiagnosticCode, outcome: String) {
    try {
        services.recordSupportDiagnostic(SupportDiagnosticEventDraft(severity = SupportDiagnosticSeverity.Info,
            component = SupportDiagnosticComponent.AdaptiveApps, operation = "dynamic-rule-write", outcome = outcome, code = code.code,
            fields = listOf(SupportDiagnosticFieldDraft("rule_id", plan.ruleId), SupportDiagnosticFieldDraft("rule_version", plan.ruleVersion.toString()),
                SupportDiagnosticFieldDraft("evidence_tier", descriptor.actions.single { it.id == plan.actionId }.evidenceTier?.code ?: "none"),
                SupportDiagnosticFieldDraft("risk_class", DynamicOperationRiskClass.idempotentStateSet.code))))
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
        // Diagnostics must not alter operation delivery or recovery.
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun newRuleToken(now: Long) = DynamicPendingRuleOperation(Uuid.random().toString(), now)
private fun ruleRefused(message: String = "This item's current identity or contract could not be verified, so nothing was changed.") =
    NativeActionExecutionResult.Failure(message, NativeActionFailureOutcome.Rejected)
private fun ruleUnknown(message: String) = NativeActionExecutionResult.Failure(message, NativeActionFailureOutcome.Unknown)
private const val PENDING_MESSAGE = "An earlier change still needs verification. Check its status before changing another item. You can explicitly repeat the same change after checking."
private const val UNREADABLE_MESSAGE = "The earlier change's recovery information is unavailable or incompatible. Its result remains unknown and changes stay paused."
