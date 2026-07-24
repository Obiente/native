package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PeopleMergeServiceTest {
    @Test
    fun `confirmed merge advances every successful face sequentially`() = runBlocking {
        val calls = Calls(ArrayDeque(listOf(204, 201)))
        val coordinator = coordinator(calls, emptyFaceReader())
        val workflow = PersonMergeWorkflow.create(source, target, listOf(face(11), face(12)))
        val progress = mutableListOf<PersonMergeProgress>()

        val result = coordinator.runConfirmed(
            session = session,
            bridgeDiscovery = bridge,
            plan = mergePlan(),
            initialWorkflow = workflow,
            onProgress = { progress += it.progress },
        )

        assertEquals(PersonMergePhase.Completed, assertIs<PeopleMergeRunResult.Completed>(result).workflow.phase)
        assertEquals(2, calls.mutations.size)
        assertEquals(listOf(1, 2), progress.map(PersonMergeProgress::succeeded))
        assertTrue(calls.mutations.all { it.method == PeopleMutationMethod.MOVE && it.overwrite == false })
    }

    @Test
    fun `unknown outcome pauses once and requires explicit refreshed reconciliation`() = runBlocking {
        val calls = Calls(ArrayDeque(listOf(503, 204)))
        val reader = RecognizedFaceReadService { _, request ->
            when {
                request.relativePath.endsWith("/api/days") ->
                    response("""[{"dayid":1,"count":1}]""")
                request.queryParameters["recognize"]?.endsWith("/Grace") == true ->
                    response("""[{"fileid":1,"faceid":11,"basename":"one.jpg"}]""")
                else -> response("[]")
            }
        }
        val coordinator = coordinator(calls, reader)
        val workflow = PersonMergeWorkflow.create(source, target, listOf(face(11)))

        val paused = assertIs<PeopleMergeRunResult.Paused>(
            coordinator.runConfirmed(session, bridge, mergePlan(), workflow),
        )
        assertTrue(paused.outcomeUnknown)
        assertEquals(1, calls.mutations.size)

        val reconciliation = coordinator.reconcileAfterRefresh(session, paused.workflow)
        assertEquals(PersonMergeItemState.Pending, reconciliation.workflow.items.single().state)
        assertEquals(1, calls.mutations.size)

        val completed = coordinator.runConfirmed(
            session = session,
            bridgeDiscovery = bridge,
            plan = mergePlan(),
            initialWorkflow = reconciliation.workflow,
            initialReconciliation = reconciliation,
        )
        assertIs<PeopleMergeRunResult.Completed>(completed)
        assertEquals(2, calls.mutations.size)
    }

    @Test
    fun `prepare refuses an empty source before any write`() = runBlocking {
        val calls = Calls()
        val coordinator = coordinator(
            calls,
            RecognizedFaceReadService { _, request ->
                if (request.relativePath.endsWith("/api/days")) response("[]") else response("[]")
            },
        )

        val failure = runCatching { coordinator.prepare(session, source, target) }.exceptionOrNull()

        assertEquals("This person has no face assignments to merge.", failure?.message)
        assertTrue(calls.mutations.isEmpty())
        assertEquals(0, calls.mints)
    }

    private fun coordinator(calls: Calls, reader: RecognizedFaceReadService) = PeopleMergeService(
        faceReader = reader,
        mutationService = PeopleMutationService(
            mintRequest = { _, _ ->
                calls.mints += 1
                tokenResponse()
            },
            mutationRequest = { _, request ->
                calls.mutations += request
                NextcloudApiResponse(
                    status = calls.statuses.removeFirstOrNull() ?: 204,
                    body = ByteArray(0),
                    contentType = null,
                    etag = null,
                )
            },
            nowEpochSeconds = { 1_000L },
        ),
    )

    private fun emptyFaceReader() = RecognizedFaceReadService { _, _ -> response("[]") }

    private fun mergePlan() = planMergePeople(source, "Grace", target, "Ada", support)

    private fun face(id: Long) = RecognizeFaceReference(source, id, "$id.jpg")

    private fun response(json: String) = NextcloudApiResponse(
        status = 200,
        body = json.encodeToByteArray(),
        contentType = "application/json",
        etag = null,
    )

    private fun tokenResponse() = response(
        """
        {"ocs":{"data":{
          "token":"opaque-test-token",
          "header_name":"X-Recognize-Api-Key",
          "expires_in":3600,
          "expires_at":"2026-07-23T22:00:00+00:00",
          "recognize_version":"12.0.0"
        }}}
        """.trimIndent(),
    )

    private data class Calls(
        val statuses: ArrayDeque<Int> = ArrayDeque(),
        var mints: Int = 0,
        val mutations: MutableList<PeopleTransportRequest> = mutableListOf(),
    )

    private companion object {
        val session = NextcloudSession("https://cloud.example.test", "ada", "unused")
        val source = PersonMediaReference(NextcloudPeopleBackend.Recognize, 1L, "ada", "Grace")
        val target = PersonMediaReference(NextcloudPeopleBackend.Recognize, 2L, "ada", "Ada")
        val support = PeopleActionSupport(
            currentUserId = "ada",
            memoriesPeopleApiAvailable = true,
            recognizeDavAvailable = true,
            recognizeApiKeyAvailable = true,
        )
        val bridge = RecognizeBridgeDiscovery.Available(
            RecognizeBridgeCapability(
                bridgeApiVersion = 1,
                recognizeVersion = "12.0.0",
                minimumRecognizeVersion = "11.0.0",
                tokenEndpoint = "/ocs/v2.php/apps/obiente_native_bridge/api/v1/recognize/token",
                davHeaderName = "X-Recognize-Api-Key",
                tokenLifetimeSeconds = 86_400L,
            ),
        )
    }
}
