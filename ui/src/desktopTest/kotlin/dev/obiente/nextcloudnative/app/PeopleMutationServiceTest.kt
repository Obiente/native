package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeopleMutationServiceTest {
    @Test
    fun `unconfirmed action never mints a token or sends a mutation`() = runBlocking {
        val calls = Calls()
        val service = service(calls)

        val result = service.execute(
            session,
            bridge,
            planRenamePerson(source, "Grace", "Grace Hopper", support()),
            confirmed = false,
        )

        assertIs<PeopleMutationServiceResult.Planning>(result)
        assertEquals(0, calls.mints)
        assertEquals(0, calls.mutations.size)
    }

    @Test
    fun `confirmed recognize action mints parses and scopes token before mutation`() = runBlocking {
        val calls = Calls()
        val service = service(calls)

        val result = service.execute(
            session,
            bridge,
            planRenamePerson(source, "Grace", "Grace Hopper", support()),
            confirmed = true,
        )

        assertIs<PeopleMutationExecutionOutcome.SingleSucceeded>(
            assertIs<PeopleMutationServiceResult.Outcome>(result).outcome,
        )
        assertEquals(1, calls.mints)
        val request = calls.mutations.single()
        val authorization = assertIs<PeopleTransportAuthorization.RecognizeBridgeToken>(request.authorization)
        assertEquals("opaque-test-token", authorization.bridgeToken.value)
        assertEquals("X-Recognize-Api-Key", authorization.headerName)
    }

    @Test
    fun `bridge absence remains disabled without attempting token request`() = runBlocking {
        val calls = Calls()
        val result = service(calls).execute(
            session,
            RecognizeBridgeDiscovery.NotAdvertised,
            planDeletePerson(source, "Grace", support()),
            confirmed = true,
        )

        assertIs<PeopleMutationServiceResult.TokenUnavailable>(result)
        assertEquals(0, calls.mints)
        assertTrue(calls.mutations.isEmpty())
    }

    @Test
    fun `memories cover write needs no recognize bridge or token`() = runBlocking {
        val calls = Calls()
        val result = service(calls).execute(
            session,
            RecognizeBridgeDiscovery.NotAdvertised,
            planSetPersonCover(source, "Grace", photo, support()),
            confirmed = true,
        )

        assertIs<PeopleMutationExecutionOutcome.SingleSucceeded>(
            assertIs<PeopleMutationServiceResult.Outcome>(result).outcome,
        )
        assertEquals(0, calls.mints)
        assertEquals(PeopleMutationSurface.MemoriesApi, calls.mutations.single().surface)
    }

    @Test
    fun `dav forbidden invalidates lease but never retries mutation automatically`() = runBlocking {
        val calls = Calls(mutationStatuses = ArrayDeque(listOf(403, 204)))
        val service = service(calls)
        val plan = planDeletePerson(source, "Grace", support())

        val rejected = service.execute(session, bridge, plan, confirmed = true)
        assertEquals(
            403,
            assertIs<PeopleMutationExecutionOutcome.SingleRejected>(
                assertIs<PeopleMutationServiceResult.Outcome>(rejected).outcome,
            ).status,
        )
        assertEquals(1, calls.mints)
        assertEquals(1, calls.mutations.size)

        assertIs<PeopleMutationServiceResult.Outcome>(
            service.execute(session, bridge, plan, confirmed = true),
        )
        assertEquals(2, calls.mints)
        assertEquals(2, calls.mutations.size)
    }

    @Test
    fun `invalid token response stops before mutation`() = runBlocking {
        val calls = Calls(validToken = false)
        val result = service(calls).execute(
            session,
            bridge,
            planDeletePerson(source, "Grace", support()),
            confirmed = true,
        )

        assertEquals(
            RecognizeBridgeTokenFailure.InvalidToken,
            assertIs<PeopleMutationServiceResult.TokenUnavailable>(result).reason,
        )
        assertTrue(calls.mutations.isEmpty())
    }

    @Test
    fun `merge transport result maps to completed workflow without hidden retries`() = runBlocking {
        val calls = Calls()
        val workflow = PersonMergeWorkflow.create(source, target, listOf(face))
        val result = service(calls).execute(
            session,
            bridge,
            planMergePeople(source, "Grace", target, "Ada", support()),
            confirmed = true,
            mergeWorkflow = workflow,
        )

        val completed = assertIs<PeopleMutationExecutionOutcome.MergeCompleted>(
            assertIs<PeopleMutationServiceResult.Outcome>(result).outcome,
        )
        assertEquals(PersonMergePhase.Completed, completed.workflow.phase)
        assertEquals(1, calls.mutations.size)
    }

    private fun service(calls: Calls) = PeopleMutationService(
        mintRequest = { _, request ->
            calls.mints += 1
            assertTrue(request.ocsApiRequest)
            tokenResponse(calls.validToken)
        },
        mutationRequest = { _, request ->
            calls.mutations += request
            NextcloudApiResponse(
                calls.mutationStatuses.removeFirstOrNull() ?: 204,
                ByteArray(0),
                null,
                null,
            )
        },
        nowEpochSeconds = { 1_000L },
    )

    private class Calls(
        var mints: Int = 0,
        val mutations: MutableList<PeopleTransportRequest> = mutableListOf(),
        val mutationStatuses: ArrayDeque<Int> = ArrayDeque(),
        val validToken: Boolean = true,
    )

    private fun tokenResponse(valid: Boolean) = NextcloudApiResponse(
        status = 200,
        body = """
            {"ocs":{"data":{
              "token":"${if (valid) "opaque-test-token" else ""}",
              "header_name":"X-Recognize-Api-Key",
              "expires_in":3600,
              "expires_at":"2026-07-23T22:00:00+00:00",
              "recognize_version":"12.0.0"
            }}}
        """.trimIndent().encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private fun support() = PeopleActionSupport(
        currentUserId = "ada",
        memoriesPeopleApiAvailable = true,
        recognizeDavAvailable = true,
        recognizeApiKeyAvailable = true,
    )

    private companion object {
        val session = NextcloudSession("https://cloud.example.test", "ada", "unused")
        val source = PersonMediaReference(NextcloudPeopleBackend.Recognize, 1L, "ada", "Grace")
        val target = PersonMediaReference(NextcloudPeopleBackend.Recognize, 2L, "ada", "Ada")
        val face = RecognizeFaceReference(source, 11L, "one.jpg")
        val photo = NextcloudFile(
            path = "Photos/one.jpg",
            name = "one.jpg",
            isDirectory = false,
            mimeType = "image/jpeg",
            size = 12L,
            lastModified = null,
            fileId = 44L,
            hasPreview = true,
        )
        val capability = RecognizeBridgeCapability(
            bridgeApiVersion = 1,
            recognizeVersion = "12.0.0",
            minimumRecognizeVersion = "11.0.0",
            tokenEndpoint = "/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token",
            davHeaderName = "X-Recognize-Api-Key",
            tokenLifetimeSeconds = 86_400L,
        )
        val bridge = RecognizeBridgeDiscovery.Available(capability)
    }
}
