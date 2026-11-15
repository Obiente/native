package dev.obiente.nextcloudnative.nativeui.model

/*
 * The admission matrix: which evidence each risk class needs before an operation may be offered,
 * and which safeguards the surface that offers it must apply. It is a pure function so every
 * write path, test, and diagnostic reads the same rules.
 */

/** How the target identity of a write is bound to its path placeholders. */
enum class DynamicIdentityBinding {
    /** The write addresses no record: a create in the navigated collection or a singleton. */
    notRequired,

    /** A contract-declared identity field of a record loaded through the same route family. */
    declared,

    /** An id observed only in a list response, proven by every observed-binding invariant. */
    observed,

    /** An identity was needed and could not be proven. */
    refused,
}

/** The state of the pattern rule (E4) a write depends on, if any. */
enum class DynamicWriteRuleState {
    /** The write uses only contract-declared encodings and semantics; no learned rule applies. */
    notRequired,

    /** A rule applies and this device has not yet confirmed it for this route and app version. */
    probation,

    /** A rule applies and an earlier read-back on this device confirmed it. */
    confirmed,

    /** A read-back contradicted the rule; the route stays disabled until the person clears it. */
    quarantined,
    /** An operation may have been sent; ordinary admission cannot clear or replay it. */
    pendingVerification,
}

/** Contract facts and surface guarantees that the admission rules consult. */
data class DynamicWriteFacts(
    /** R1: the state change is declared by the operation itself (declared effect and typed body). */
    val declaredStateSemantics: Boolean = false,
    /** R2/R3: the method is PATCH or the contract declares merge semantics. */
    val declaredMerge: Boolean = false,
    /** R4: the request echoes the complete representation with exact round-trip values. */
    val completeRepresentation: Boolean = false,
    /** R5 and read-backs: a verified read exists in the same route family. */
    val routeFamilyRead: Boolean = false,
    /** R6: every body field has a native input or a role-derived value; none is invented. */
    val bodyFullyRepresentable: Boolean = false,
    /** R6: the operation declares an Idempotency-Key request header. */
    val declaredIdempotencyKey: Boolean = false,
    /** R6: a discoverable outbox or queue makes the send a verifiable create. */
    val outbox: Boolean = false,
    /** R6: the offering surface records a durable attempt and never resends unconfirmed. */
    val exactlyOnceSurface: Boolean = false,
    /** R7: the contract declares that the target moves to a trash it can be restored from. */
    val declaredTrash: Boolean = false,
    /** R7: the separate confirmation names the target and labels the change as permanent. */
    val permanentConfirmation: Boolean = false,
    /** R8: a declared permission or capability field proves the actor may share. */
    val declaredSharePermission: Boolean = false,
)

data class DynamicWriteAdmissionInput(
    val riskClass: DynamicOperationRiskClass,
    val tier: DynamicEvidenceTier?,
    val identityBinding: DynamicIdentityBinding,
    val rule: DynamicWriteRuleState,
    val facts: DynamicWriteFacts = DynamicWriteFacts(),
)

/** A safeguard the offering surface must apply for an admitted write. */
enum class DynamicWriteSafeguard {
    boundedSameOriginRead,
    optimisticApplyWithReadBack,
    rollbackOnMismatch,
    quarantineOnContradiction,
    preReadCompare,
    readBackDiff,
    baselineUndo,
    fullEcho,
    durablePendingRecord,
    durableAttemptRecord,
    singleAttempt,
    unknownBlocksResend,
    mayHaveBeenSentState,
    confirmedResendOnly,
    noAutomaticRetry,
    separateConfirmation,
    readBackAbsence,
    confirmationNamesGrantee,
    readBackShareList,
    /** One in-flight write per route until the rule's first read-back confirms it. */
    probationLimit,
}

/** Why a write is not offered. */
enum class DynamicWriteRefusal {
    insufficientEvidence,
    identityUnbound,
    ruleQuarantined,
    pendingVerification,
    semanticsUndeclared,
    mergeUndeclared,
    incompleteRepresentation,
    noVerifiedRead,
    bodyNotRepresentable,
    noSendSafeguard,
    noTrashOrPermanentConfirmation,
    noDeclaredSharePermission,
    administrationNotDynamic,
    credentialNeverDynamic,
}

sealed interface DynamicWriteAdmission {
    data class Admitted(
        val safeguards: Set<DynamicWriteSafeguard>,
        /** True when repeating the same request after an unknown result is harmless by construction. */
        val repeatAfterUnknown: Boolean,
    ) : DynamicWriteAdmission

    data class Refused(val reason: DynamicWriteRefusal) : DynamicWriteAdmission
}

/** Applies the admission matrix. Administration and credential operations are never admitted. */
fun admitDynamicWrite(input: DynamicWriteAdmissionInput): DynamicWriteAdmission {
    val facts = input.facts
    val riskClass = input.riskClass
    if (riskClass == DynamicOperationRiskClass.administration) return refuse(DynamicWriteRefusal.administrationNotDynamic)
    if (riskClass == DynamicOperationRiskClass.credential) return refuse(DynamicWriteRefusal.credentialNeverDynamic)
    val tier = input.tier
    if (riskClass == DynamicOperationRiskClass.read) {
        return if (tier != null && (tier.declaresOperation || tier == DynamicEvidenceTier.observedRead)) {
            DynamicWriteAdmission.Admitted(setOf(DynamicWriteSafeguard.boundedSameOriginRead), repeatAfterUnknown = true)
        } else {
            refuse(DynamicWriteRefusal.insufficientEvidence)
        }
    }
    // Every write needs a declared operation. A rule or an observation never invents one.
    if (tier == null || !tier.declaresOperation) return refuse(DynamicWriteRefusal.insufficientEvidence)
    if (input.rule == DynamicWriteRuleState.quarantined) return refuse(DynamicWriteRefusal.ruleQuarantined)
    if (input.rule == DynamicWriteRuleState.pendingVerification) return refuse(DynamicWriteRefusal.pendingVerification)
    if (input.identityBinding == DynamicIdentityBinding.refused) return refuse(DynamicWriteRefusal.identityUnbound)
    val ruleApplied = input.rule == DynamicWriteRuleState.probation || input.rule == DynamicWriteRuleState.confirmed
    val probation = if (input.rule == DynamicWriteRuleState.probation) setOf(DynamicWriteSafeguard.probationLimit, DynamicWriteSafeguard.durablePendingRecord) else emptySet()
    val verifiedEdit = setOf(DynamicWriteSafeguard.preReadCompare, DynamicWriteSafeguard.readBackDiff, DynamicWriteSafeguard.baselineUndo)
    return when (riskClass) {
        DynamicOperationRiskClass.idempotentStateSet -> when {
            !facts.declaredStateSemantics && !ruleApplied -> refuse(DynamicWriteRefusal.semanticsUndeclared)
            else -> DynamicWriteAdmission.Admitted(
                setOf(
                    DynamicWriteSafeguard.optimisticApplyWithReadBack,
                    DynamicWriteSafeguard.rollbackOnMismatch,
                    DynamicWriteSafeguard.quarantineOnContradiction,
                ) + probation,
                repeatAfterUnknown = true,
            )
        }
        DynamicOperationRiskClass.reversibleScalarEdit -> when {
            !facts.declaredMerge && input.rule != DynamicWriteRuleState.confirmed -> refuse(DynamicWriteRefusal.mergeUndeclared)
            else -> DynamicWriteAdmission.Admitted(verifiedEdit + probation, repeatAfterUnknown = false)
        }
        // A pattern rule alone never unlocks a sparse update: merge semantics are not learned.
        DynamicOperationRiskClass.partialUpdate -> when {
            !facts.declaredMerge -> refuse(DynamicWriteRefusal.mergeUndeclared)
            else -> DynamicWriteAdmission.Admitted(verifiedEdit + probation, repeatAfterUnknown = false)
        }
        DynamicOperationRiskClass.replacement -> when {
            !facts.completeRepresentation -> refuse(DynamicWriteRefusal.incompleteRepresentation)
            else -> DynamicWriteAdmission.Admitted(verifiedEdit + DynamicWriteSafeguard.fullEcho + probation, repeatAfterUnknown = true)
        }
        DynamicOperationRiskClass.create -> when {
            !facts.routeFamilyRead -> refuse(DynamicWriteRefusal.noVerifiedRead)
            else -> DynamicWriteAdmission.Admitted(
                setOf(
                    DynamicWriteSafeguard.durablePendingRecord,
                    DynamicWriteSafeguard.singleAttempt,
                    DynamicWriteSafeguard.unknownBlocksResend,
                    DynamicWriteSafeguard.noAutomaticRetry,
                ) + probation,
                repeatAfterUnknown = false,
            )
        }
        DynamicOperationRiskClass.externalEffect -> when {
            !facts.bodyFullyRepresentable -> refuse(DynamicWriteRefusal.bodyNotRepresentable)
            !facts.declaredIdempotencyKey && !facts.outbox && !facts.exactlyOnceSurface -> refuse(DynamicWriteRefusal.noSendSafeguard)
            else -> DynamicWriteAdmission.Admitted(
                setOf(
                    DynamicWriteSafeguard.durableAttemptRecord,
                    DynamicWriteSafeguard.singleAttempt,
                    DynamicWriteSafeguard.mayHaveBeenSentState,
                    DynamicWriteSafeguard.confirmedResendOnly,
                    DynamicWriteSafeguard.noAutomaticRetry,
                ) + probation,
                repeatAfterUnknown = false,
            )
        }
        DynamicOperationRiskClass.destructive -> when {
            !facts.declaredTrash && !facts.permanentConfirmation -> refuse(DynamicWriteRefusal.noTrashOrPermanentConfirmation)
            else -> DynamicWriteAdmission.Admitted(
                setOf(
                    DynamicWriteSafeguard.separateConfirmation,
                    DynamicWriteSafeguard.readBackAbsence,
                    DynamicWriteSafeguard.noAutomaticRetry,
                ) + probation,
                repeatAfterUnknown = true,
            )
        }
        // Sharing is never unlocked by a rule alone; the contract must declare the permission.
        DynamicOperationRiskClass.sharing -> when {
            !facts.declaredSharePermission -> refuse(DynamicWriteRefusal.noDeclaredSharePermission)
            else -> DynamicWriteAdmission.Admitted(
                setOf(DynamicWriteSafeguard.confirmationNamesGrantee, DynamicWriteSafeguard.readBackShareList) + probation,
                repeatAfterUnknown = false,
            )
        }
        DynamicOperationRiskClass.read,
        DynamicOperationRiskClass.administration,
        DynamicOperationRiskClass.credential,
        -> error("Handled above.")
    }
}

private fun refuse(reason: DynamicWriteRefusal) = DynamicWriteAdmission.Refused(reason)

/** True when only a dedicated surface (or nothing) may offer this action. */
val DynamicAction.requiresDedicatedWriteSurface: Boolean
    get() = operationRiskClass.requiresDedicatedSurface ||
        (operationRiskClass == DynamicOperationRiskClass.sharing && sharePermissionFieldId == null)

/** True when only a dedicated surface (or nothing) may offer this action. */
val ActionSpec.requiresDedicatedWriteSurface: Boolean
    get() = operationRiskClass.requiresDedicatedSurface ||
        (operationRiskClass == DynamicOperationRiskClass.sharing && sharePermissionFieldId == null)
