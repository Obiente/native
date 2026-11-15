package dev.obiente.nextcloudnative.nativeui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DynamicWriteAdmissionTest {
    @Test
    fun `tiers come from provenance and never from metadata or local inference`() {
        assertEquals(DynamicEvidenceTier.advertisedOpenApi, ProvenanceKind.advertisedOpenApi.evidenceTier())
        assertEquals(DynamicEvidenceTier.signedPackage, ProvenanceKind.verifiedAppPackage.evidenceTier())
        assertEquals(DynamicEvidenceTier.signedPackage, ProvenanceKind.appStoreLinkedSourceTag.evidenceTier())
        assertEquals(DynamicEvidenceTier.observedRead, ProvenanceKind.successfulReadObservation.evidenceTier())
        assertNull(ProvenanceKind.appMetadata.evidenceTier())
        assertNull(ProvenanceKind.deterministicInference.evidenceTier())
        assertNull(EvidenceSource.localInference.evidenceTier())
        assertEquals(
            DynamicEvidenceTier.signedPackage,
            strongestEvidenceTier(listOf(DynamicEvidenceTier.observedRead, DynamicEvidenceTier.advertisedOpenApi, DynamicEvidenceTier.signedPackage)),
        )
        assertTrue(DynamicEvidenceTier.advertisedOpenApi.declaresOperation)
        assertFalse(DynamicEvidenceTier.patternRule.declaresOperation)
        assertFalse(DynamicEvidenceTier.observedRead.declaresOperation)
        assertEquals("E4", DynamicEvidenceTier.patternRule.code)
    }

    @Test
    fun `risk classes follow method effect and exact words`() {
        fun classify(method: HttpMethod, path: String, operationId: String, effect: ActionEffect = ActionEffect.execute, body: Boolean = true) =
            dynamicOperationRiskClass(method, effect, ActionRisk.mutating, path, operationId, "", body)

        assertEquals(DynamicOperationRiskClass.read, classify(HttpMethod.GET, "/apps/x/api/items", "items", ActionEffect.list))
        assertEquals(DynamicOperationRiskClass.externalEffect, classify(HttpMethod.POST, "/apps/x/api/messages", "messages.sendNow"))
        assertEquals(DynamicOperationRiskClass.externalEffect, classify(HttpMethod.POST, "/apps/x/api/records/{id}/merge", "records-combine"))
        assertEquals(DynamicOperationRiskClass.sharing, classify(HttpMethod.PUT, "/apps/x/api/entities/{id}/share", "entity-share"))
        assertEquals(DynamicOperationRiskClass.administration, classify(HttpMethod.POST, "/apps/x/api/admin/config", "config-set"))
        assertEquals(DynamicOperationRiskClass.credential, classify(HttpMethod.POST, "/apps/x/api/keys", "api-key-create"))
        // Placeholders are not words: a conversation token is not a credential.
        assertEquals(DynamicOperationRiskClass.create, classify(HttpMethod.POST, "/apps/x/api/room/{token}/message", "message-create", ActionEffect.create))
        // Neighbouring words that describe data stay ordinary.
        assertEquals(DynamicOperationRiskClass.create, classify(HttpMethod.POST, "/apps/x/api/senders", "sender-create", ActionEffect.create))
        assertEquals(DynamicOperationRiskClass.partialUpdate, classify(HttpMethod.PATCH, "/apps/x/api/preferences/shared", "shared-preferences-update", ActionEffect.update))
        assertEquals(DynamicOperationRiskClass.replacement, classify(HttpMethod.PUT, "/apps/x/api/mergeable/{id}", "mergeable-record-update", ActionEffect.update))
        assertEquals(DynamicOperationRiskClass.idempotentStateSet, classify(HttpMethod.POST, "/apps/x/api/tasks/{id}/complete", "task-complete", ActionEffect.toggle))
        assertEquals(DynamicOperationRiskClass.idempotentStateSet, classify(HttpMethod.PUT, "/apps/x/api/items/{id}/read", "item-read", body = false))
        assertEquals(DynamicOperationRiskClass.reversibleScalarEdit, classify(HttpMethod.POST, "/apps/x/api/stacks/reorder", "stacks-reorder", ActionEffect.reorder))
        assertEquals(DynamicOperationRiskClass.destructive, classify(HttpMethod.DELETE, "/apps/x/api/items/{id}", "item-delete", ActionEffect.delete))
        assertEquals(DynamicOperationRiskClass.create, classify(HttpMethod.POST, "/apps/x/api/items/{id}/duplicate", "item-duplicate", ActionEffect.copy))
    }

    @Test
    fun `a package access rule limiting a write to administrators makes it administration`() {
        val replacement = DynamicOperationRiskClass.replacement
        DynamicAccessLevel.entries.forEach { access ->
            val expected = if (access == DynamicAccessLevel.publicPage || access == DynamicAccessLevel.authenticatedUser) {
                replacement
            } else {
                DynamicOperationRiskClass.administration
            }
            assertEquals(expected, DynamicContractFacts(access = access).withAccessRule(replacement), "$access")
        }
        assertEquals(replacement, null.withAccessRule(replacement), "Missing facts are unknown, not admin.")
        assertEquals(replacement, DynamicContractFacts(access = null).withAccessRule(replacement))
        assertEquals(DynamicOperationRiskClass.read, DynamicContractFacts(access = DynamicAccessLevel.admin).withAccessRule(DynamicOperationRiskClass.read),
            "An admin-only read stays a read.")
    }

    @Test
    fun `administration and credentials are never admitted whatever the evidence`() {
        listOf(DynamicOperationRiskClass.administration, DynamicOperationRiskClass.credential).forEach { riskClass ->
            val admission = admitDynamicWrite(input(riskClass, facts = everyFact()))
            assertIs<DynamicWriteAdmission.Refused>(admission, "$riskClass")
        }
        assertTrue(DynamicOperationRiskClass.administration.requiresDedicatedSurface)
        assertTrue(DynamicOperationRiskClass.credential.requiresDedicatedSurface)
    }

    @Test
    fun `a write needs a declared operation, never a rule or an observation alone`() {
        listOf(null, DynamicEvidenceTier.patternRule, DynamicEvidenceTier.observedRoundTrip, DynamicEvidenceTier.observedRead).forEach { tier ->
            val admission = admitDynamicWrite(input(DynamicOperationRiskClass.create, tier = tier, facts = everyFact()))
            assertEquals(DynamicWriteAdmission.Refused(DynamicWriteRefusal.insufficientEvidence), admission, "$tier")
        }
        assertIs<DynamicWriteAdmission.Admitted>(
            admitDynamicWrite(input(DynamicOperationRiskClass.read, tier = DynamicEvidenceTier.observedRead)),
        )
    }

    @Test
    fun `quarantine and an unproven identity refuse every write class`() {
        writeClasses.forEach { riskClass ->
            assertEquals(
                DynamicWriteAdmission.Refused(DynamicWriteRefusal.ruleQuarantined),
                admitDynamicWrite(input(riskClass, rule = DynamicWriteRuleState.quarantined, facts = everyFact())),
            )
            assertEquals(
                DynamicWriteAdmission.Refused(DynamicWriteRefusal.identityUnbound),
                admitDynamicWrite(input(riskClass, binding = DynamicIdentityBinding.refused, facts = everyFact())),
            )
        }
    }

    @Test
    fun `each class requires its own minimum evidence`() {
        assertRefused(DynamicOperationRiskClass.idempotentStateSet, DynamicWriteRefusal.semanticsUndeclared)
        assertRefused(DynamicOperationRiskClass.reversibleScalarEdit, DynamicWriteRefusal.mergeUndeclared)
        assertRefused(DynamicOperationRiskClass.partialUpdate, DynamicWriteRefusal.mergeUndeclared)
        assertRefused(DynamicOperationRiskClass.replacement, DynamicWriteRefusal.incompleteRepresentation)
        assertRefused(DynamicOperationRiskClass.create, DynamicWriteRefusal.noVerifiedRead)
        assertRefused(DynamicOperationRiskClass.externalEffect, DynamicWriteRefusal.bodyNotRepresentable)
        assertRefused(
            DynamicOperationRiskClass.externalEffect,
            DynamicWriteRefusal.noSendSafeguard,
            DynamicWriteFacts(bodyFullyRepresentable = true),
        )
        assertRefused(DynamicOperationRiskClass.destructive, DynamicWriteRefusal.noTrashOrPermanentConfirmation)
        assertRefused(DynamicOperationRiskClass.sharing, DynamicWriteRefusal.noDeclaredSharePermission)
    }

    @Test
    fun `a pattern rule unlocks a state set or a confirmed scalar edit but never a sparse update or a share`() {
        assertIs<DynamicWriteAdmission.Admitted>(
            admitDynamicWrite(input(DynamicOperationRiskClass.idempotentStateSet, rule = DynamicWriteRuleState.probation)),
        )
        assertIs<DynamicWriteAdmission.Refused>(
            admitDynamicWrite(input(DynamicOperationRiskClass.reversibleScalarEdit, rule = DynamicWriteRuleState.probation)),
        )
        assertIs<DynamicWriteAdmission.Admitted>(
            admitDynamicWrite(input(DynamicOperationRiskClass.reversibleScalarEdit, rule = DynamicWriteRuleState.confirmed)),
        )
        assertIs<DynamicWriteAdmission.Refused>(
            admitDynamicWrite(input(DynamicOperationRiskClass.partialUpdate, rule = DynamicWriteRuleState.confirmed)),
        )
        assertIs<DynamicWriteAdmission.Refused>(
            admitDynamicWrite(input(DynamicOperationRiskClass.sharing, rule = DynamicWriteRuleState.confirmed)),
        )
    }

    @Test
    fun `admitted classes carry their mandatory safeguards`() {
        val stateSet = admitted(DynamicOperationRiskClass.idempotentStateSet)
        assertTrue(stateSet.repeatAfterUnknown)
        assertTrue(DynamicWriteSafeguard.rollbackOnMismatch in stateSet.safeguards)
        val replacement = admitted(DynamicOperationRiskClass.replacement)
        assertTrue(replacement.repeatAfterUnknown)
        assertTrue(
            replacement.safeguards.containsAll(
                setOf(DynamicWriteSafeguard.fullEcho, DynamicWriteSafeguard.preReadCompare, DynamicWriteSafeguard.readBackDiff, DynamicWriteSafeguard.baselineUndo),
            ),
        )
        val create = admitted(DynamicOperationRiskClass.create)
        assertFalse(create.repeatAfterUnknown)
        assertTrue(DynamicWriteSafeguard.durablePendingRecord in create.safeguards)
        val send = admitted(DynamicOperationRiskClass.externalEffect)
        assertFalse(send.repeatAfterUnknown)
        assertTrue(
            send.safeguards.containsAll(
                setOf(DynamicWriteSafeguard.durableAttemptRecord, DynamicWriteSafeguard.mayHaveBeenSentState, DynamicWriteSafeguard.confirmedResendOnly),
            ),
        )
        val delete = admitted(DynamicOperationRiskClass.destructive)
        assertTrue(delete.repeatAfterUnknown)
        assertTrue(DynamicWriteSafeguard.separateConfirmation in delete.safeguards)
        val share = admitted(DynamicOperationRiskClass.sharing)
        assertTrue(DynamicWriteSafeguard.confirmationNamesGrantee in share.safeguards)
    }

    @Test
    fun `a rule in probation limits the route to one unconfirmed write`() {
        val probation = assertIs<DynamicWriteAdmission.Admitted>(
            admitDynamicWrite(input(DynamicOperationRiskClass.create, rule = DynamicWriteRuleState.probation, facts = everyFact())),
        )
        assertTrue(DynamicWriteSafeguard.probationLimit in probation.safeguards)
        assertFalse(DynamicWriteSafeguard.probationLimit in admitted(DynamicOperationRiskClass.create).safeguards)
    }

    @Test
    fun `any of the three send safeguards admits a fully representable send`() {
        listOf(
            DynamicWriteFacts(bodyFullyRepresentable = true, declaredIdempotencyKey = true),
            DynamicWriteFacts(bodyFullyRepresentable = true, outbox = true),
            DynamicWriteFacts(bodyFullyRepresentable = true, exactlyOnceSurface = true),
        ).forEach { facts ->
            assertIs<DynamicWriteAdmission.Admitted>(admitDynamicWrite(input(DynamicOperationRiskClass.externalEffect, facts = facts)), "$facts")
        }
    }

    @Test
    fun `route families bind by position and accept item commands and collections`() {
        assertEquals(DynamicRouteFamilyRelation.sameRoute, dynamicRouteFamilyRelation("/a/items/{id}", "/a/items/{itemId}"))
        assertEquals(DynamicRouteFamilyRelation.itemCommand, dynamicRouteFamilyRelation("/a/items/{itemId}/read", "/a/items/{itemId}"))
        assertEquals(DynamicRouteFamilyRelation.collectionItem, dynamicRouteFamilyRelation("/a/items/{itemId}/read", "/a/items"))
        assertEquals(DynamicRouteFamilyRelation.collectionItem, dynamicRouteFamilyRelation("/a/items/{itemId}", "/a/items"))
        assertEquals(
            DynamicRouteFamilyRelation.collectionItem,
            dynamicRouteFamilyRelation("/a/boards/{boardId}/cards/{cardId}", "/a/boards/{boardId}/cards"),
        )
        // A different resource, a deeper route, or a command on a collection is not one family.
        assertNull(dynamicRouteFamilyRelation("/a/cards/{cardId}", "/a/boards"))
        assertNull(dynamicRouteFamilyRelation("/a/items/{itemId}/labels/{labelId}", "/a/items"))
        assertNull(dynamicRouteFamilyRelation("/a/items/read/multiple", "/a/items"))
        assertNull(dynamicRouteFamilyRelation("/a/items/{itemId}", "/a/items/{itemId}/history"))
    }

    private val writeClasses = DynamicOperationRiskClass.entries.filter { riskClass ->
        riskClass != DynamicOperationRiskClass.read &&
            riskClass != DynamicOperationRiskClass.administration &&
            riskClass != DynamicOperationRiskClass.credential
    }

    private fun assertRefused(
        riskClass: DynamicOperationRiskClass,
        reason: DynamicWriteRefusal,
        facts: DynamicWriteFacts = DynamicWriteFacts(),
    ) = assertEquals(DynamicWriteAdmission.Refused(reason), admitDynamicWrite(input(riskClass, facts = facts)), "$riskClass")

    private fun admitted(riskClass: DynamicOperationRiskClass) =
        assertIs<DynamicWriteAdmission.Admitted>(admitDynamicWrite(input(riskClass, facts = everyFact())), "$riskClass")

    private fun everyFact() = DynamicWriteFacts(
        declaredStateSemantics = true,
        declaredMerge = true,
        completeRepresentation = true,
        routeFamilyRead = true,
        bodyFullyRepresentable = true,
        exactlyOnceSurface = true,
        permanentConfirmation = true,
        declaredSharePermission = true,
    )

    private fun input(
        riskClass: DynamicOperationRiskClass,
        tier: DynamicEvidenceTier? = DynamicEvidenceTier.signedPackage,
        binding: DynamicIdentityBinding = DynamicIdentityBinding.declared,
        rule: DynamicWriteRuleState = DynamicWriteRuleState.notRequired,
        facts: DynamicWriteFacts = DynamicWriteFacts(),
    ) = DynamicWriteAdmissionInput(riskClass, tier, binding, rule, facts)
}
