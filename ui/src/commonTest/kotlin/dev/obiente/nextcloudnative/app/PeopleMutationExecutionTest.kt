package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeopleMutationExecutionTest {
    @Test
    fun `path segments use uppercase utf8 percent encoding without changing origin`() {
        assertEquals("Zo%C3%AB%20%252F.jpg", encodePeoplePathSegment("Zoë %2F.jpg"))
        assertEquals(
            "/remote.php/dav/recognize/ada/faces/Zo%C3%AB%20Hopper",
            expandPeoplePathTemplate(
                "/remote.php/dav/recognize/{uid}/faces/{person}",
                mapOf("uid" to "ada", "person" to "Zoë Hopper"),
            ),
        )
        assertTrue(
            runCatching {
                expandPeoplePathTemplate(
                    "/remote.php/dav/recognize/{uid}/faces/{person}",
                    mapOf("uid" to "ada", "person" to "Grace", "origin" to "https://other.example"),
                )
            }.isFailure,
        )
        assertTrue(runCatching { encodePeoplePathSegment("..") }.isFailure)
    }

    @Test
    fun `recognize token lease is account scoped redacted and refreshes before expiry`() {
        val lease = lease(acquiredAt = 1_000L, lifetime = 100L)

        assertIs<RecognizeTokenLifecycle.Usable>(lease.lifecycle(account, 1_050L))
        assertEquals(
            RecognizeTokenRefreshReason.SafetyWindowReached,
            assertIs<RecognizeTokenLifecycle.RefreshRequired>(lease.lifecycle(account, 1_091L)).reason,
        )
        assertEquals(
            RecognizeTokenRefreshReason.Expired,
            assertIs<RecognizeTokenLifecycle.RefreshRequired>(lease.lifecycle(account, 1_100L)).reason,
        )
        assertEquals(
            RecognizeTokenRefreshReason.AccountChanged,
            assertIs<RecognizeTokenLifecycle.RefreshRequired>(
                lease.lifecycle(otherAccount, 1_050L),
            ).reason,
        )
        assertFalse(lease.toString().contains(TOKEN_VALUE))
        assertTrue(lease.toString().contains("[redacted]"))
    }

    @Test
    fun `rename plans a same origin non overwriting dav move`() {
        val ready = assertIs<PeopleExecutionPlanningResult.Ready>(
            planPeopleMutationExecution(
                planRenamePerson(source, "Grace", "Zoë Hopper", support()),
                account,
                nowEpochSeconds = 1_010L,
                confirmed = true,
                tokenLease = lease(),
            ),
        )

        assertEquals(PeopleMutationMethod.MOVE, ready.request.method)
        assertEquals(
            "/remote.php/dav/recognize/ada/faces/Grace",
            ready.request.relativePath,
        )
        assertEquals(
            "/remote.php/dav/recognize/ada/faces/Zo%C3%AB%20Hopper",
            ready.request.destinationRelativePath,
        )
        assertEquals(false, ready.request.overwrite)
        assertIs<PeopleTransportAuthorization.RecognizeBridgeToken>(ready.request.authorization)
    }

    @Test
    fun `remove face delete person and set cover preserve their distinct request shapes`() {
        val remove = ready(
            planRemoveFace(face(11L), "Grace", support()),
            tokenLease = lease(),
        )
        val delete = ready(
            planDeletePerson(source, "Grace", support()),
            tokenLease = lease(),
        )
        val cover = ready(
            planSetPersonCover(source, "Grace", photo(44L), support()),
            tokenLease = null,
        )

        assertEquals(PeopleMutationMethod.DELETE, remove.request.method)
        assertTrue(remove.request.relativePath.endsWith("/11-one.jpg"))
        assertEquals(PeopleMutationMethod.DELETE, delete.request.method)
        assertTrue(delete.request.relativePath.endsWith("/Grace"))
        assertEquals(PeopleMutationMethod.POST, cover.request.method)
        assertEquals(mapOf("name" to "ada/Grace", "fileid" to "44"), cover.request.formFields)
        assertEquals(PeopleTransportAuthorization.NextcloudSession, cover.request.authorization)
    }

    @Test
    fun `confirmation and bridge token are explicit gates`() {
        val plan = planDeletePerson(source, "Grace", support())
        assertIs<PeopleExecutionPlanningResult.ConfirmationRequired>(
            planPeopleMutationExecution(plan, account, 1_010L, confirmed = false, tokenLease = lease()),
        )
        assertEquals(
            RecognizeTokenRefreshReason.Missing,
            assertIs<PeopleExecutionPlanningResult.BridgeTokenRequired>(
                planPeopleMutationExecution(plan, account, 1_010L, confirmed = true),
            ).reason,
        )
    }

    @Test
    fun `single outcomes distinguish success rejection and ambiguity`() {
        val ready = ready(
            planRemoveFace(face(11L), "Grace", support()),
            tokenLease = lease(),
        )

        assertIs<PeopleMutationExecutionOutcome.SingleSucceeded>(
            reducePeopleMutationObservation(ready, PeopleTransportObservation.Response(204)),
        )
        assertEquals(
            409,
            assertIs<PeopleMutationExecutionOutcome.SingleRejected>(
                reducePeopleMutationObservation(ready, PeopleTransportObservation.Response(409)),
            ).status,
        )
        assertIs<PeopleMutationExecutionOutcome.SingleOutcomeUnknown>(
            reducePeopleMutationObservation(ready, PeopleTransportObservation.NoResponse("Connection closed.")),
        )
        assertIs<PeopleMutationExecutionOutcome.SingleOutcomeUnknown>(
            reducePeopleMutationObservation(ready, PeopleTransportObservation.Response(503)),
        )
    }

    @Test
    fun `merge resumes one face at a time only after explicit reconciliation`() {
        val plan = planMergePeople(source, "Grace", target, "Ada", support())
        val initial = PersonMergeWorkflow.create(source, target, listOf(face(11L), face(12L)))
        val first = assertIs<PeopleExecutionPlanningResult.Ready>(
            planPeopleMutationExecution(plan, account, 1_010L, true, lease(), initial),
        )
        assertEquals(11L, first.mergeWorkflow?.items?.single {
            it.state == PersonMergeItemState.InFlight
        }?.face?.detectionId)

        val paused = assertIs<PeopleMutationExecutionOutcome.MergePaused>(
            reducePeopleMutationObservation(first, PeopleTransportObservation.NoResponse("Connection closed.")),
        )
        assertTrue(paused.outcomeUnknown)
        assertEquals(PersonMergePhase.NeedsManualReconciliation, paused.workflow.phase)
        assertIs<PeopleExecutionPlanningResult.ReconciliationRequired>(
            planPeopleMutationExecution(plan, account, 1_020L, true, lease(), paused.workflow),
        )

        val reconciliation = reconcilePeopleMergeAfterRefresh(
            workflow = paused.workflow,
            sourceDetectionIds = setOf(12L),
            targetDetectionIds = setOf(11L),
        )
        val second = assertIs<PeopleExecutionPlanningResult.Ready>(
            planPeopleMutationExecution(
                plan,
                account,
                1_030L,
                true,
                lease(),
                reconciliation.workflow,
                mergeReconciliation = reconciliation,
            ),
        )
        assertEquals(12L, second.mergeWorkflow?.items?.single {
            it.state == PersonMergeItemState.InFlight
        }?.face?.detectionId)
        val completed = assertIs<PeopleMutationExecutionOutcome.MergeCompleted>(
            reducePeopleMutationObservation(second, PeopleTransportObservation.Response(201)),
        )
        assertEquals(PersonMergePhase.Completed, completed.workflow.phase)
    }

    @Test
    fun `known merge rejection pauses for refresh and is never retried automatically`() {
        val plan = planMergePeople(source, "Grace", target, "Ada", support())
        val first = assertIs<PeopleExecutionPlanningResult.Ready>(
            planPeopleMutationExecution(
                plan,
                account,
                1_010L,
                true,
                lease(),
                PersonMergeWorkflow.create(source, target, listOf(face(11L))),
            ),
        )
        val paused = assertIs<PeopleMutationExecutionOutcome.MergePaused>(
            reducePeopleMutationObservation(first, PeopleTransportObservation.Response(409)),
        )

        assertFalse(paused.outcomeUnknown)
        assertEquals(PersonMergePhase.PausedForRefresh, paused.workflow.phase)
        assertEquals(PersonMergeItemState.Failed, paused.workflow.items.single().state)
        assertIs<PeopleExecutionPlanningResult.ReconciliationRequired>(
            planPeopleMutationExecution(plan, account, 1_020L, true, lease(), paused.workflow),
        )
    }

    private fun ready(
        plan: PeopleActionPlan,
        tokenLease: RecognizeBridgeTokenLease?,
    ): PeopleExecutionPlanningResult.Ready = assertIs(
        planPeopleMutationExecution(
            plan,
            account,
            nowEpochSeconds = 1_010L,
            confirmed = true,
            tokenLease = tokenLease,
        ),
    )

    private fun lease(
        acquiredAt: Long = 1_000L,
        lifetime: Long = 3_600L,
    ) = RecognizeBridgeTokenLease.create(
        token = RecognizeBridgeToken(
            value = TOKEN_VALUE,
            headerName = "X-Recognize-Api-Key",
            expiresInSeconds = lifetime,
            expiresAt = "not-used-by-the-planner",
            recognizeVersion = "12.0.0",
        ),
        accountScope = account,
        acquiredAtEpochSeconds = acquiredAt,
    )

    private fun support() = PeopleActionSupport(
        currentUserId = "ada",
        memoriesPeopleApiAvailable = true,
        recognizeDavAvailable = true,
        recognizeApiKeyAvailable = true,
    )

    private fun face(id: Long) = RecognizeFaceReference(
        person = source,
        detectionId = id,
        sourceFileName = if (id == 11L) "one.jpg" else "two.jpg",
    )

    private fun photo(fileId: Long) = NextcloudFile(
        path = "Photos/portrait.jpg",
        name = "portrait.jpg",
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 123L,
        lastModified = null,
        fileId = fileId,
        hasPreview = true,
    )

    private companion object {
        const val TOKEN_VALUE = "sensitive-token"
        val session = NextcloudSession("https://cloud.example.test/nextcloud", "ada", "unused")
        val otherSession = NextcloudSession("https://other.example.test", "ada", "unused")
        val account = PeopleMutationAccountScope.from(session)
        val otherAccount = PeopleMutationAccountScope.from(otherSession)
        val source = PersonMediaReference(NextcloudPeopleBackend.Recognize, 1L, "ada", "Grace")
        val target = PersonMediaReference(NextcloudPeopleBackend.Recognize, 2L, "ada", "Ada")
    }
}
