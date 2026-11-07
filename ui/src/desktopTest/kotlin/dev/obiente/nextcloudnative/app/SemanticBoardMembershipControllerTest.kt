package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SemanticBoardMembershipControllerTest {
    private fun board() = parseDeckBoard(DynamicRuleExecutionFixture.response(200,
        """{"id":7,"title":"Synthetic board","permissions":{"edit":true,"manage":true},"labels":[{"id":3,"title":"Kept"},{"id":5,"title":"New"},{"id":8,"title":"Later"}]}"""))
    private fun controller(f: MembershipExecutionFixture): SemanticBoardMembershipController {
        val contract = assertNotNull(resolveSemanticBoardContract(f.descriptor, true))
        val gate = DynamicContractWriteGate().also { it.settle(f.descriptor) }
        return SemanticBoardMembershipController(f.transport.services, f.transport.session, SemanticBoardRoutes(contract), gate) {
            f.transport.services.executeNextcloudApi(f.transport.session, it)
        }
    }

    @Test fun typedSameFamilyContractUsesManagedPathWhileCrossFamilyRetainsLegacy() = runBlocking<Unit> {
        for (nested in listOf(false, true)) MembershipExecutionFixture(nested = nested).use { f ->
            val contract = assertNotNull(resolveSemanticBoardContract(f.descriptor, true))
            assertIs<BoardMembershipCandidate.Managed>(contract.membershipCandidate(BoardOperation.AssignLabel))
            val result = controller(f).apply(board(), f.card(), board().labels.take(2))
            assertIs<NativeActionExecutionResult.Success>(result.result)
            assertEquals(1, result.completed)
            assertEquals(DynamicWriteRuleStatus.confirmed, f.transport.entries().single().status)
        }
        MembershipExecutionFixture().use { f ->
            val selectedCard = f.card()
            f.descriptor = semanticDescriptor("planning-app", "unknown", boardRoutes().forApp("planning-app"))
            val contract = assertNotNull(resolveSemanticBoardContract(f.descriptor, true))
            assertIs<BoardMembershipCandidate.NotManaged>(contract.membershipCandidate(BoardOperation.AssignLabel))
            val result = controller(f).apply(board(), selectedCard, board().labels.take(2))
            assertIs<NativeActionExecutionResult.Success>(result.result)
            assertEquals(1, f.transport.writes)
            assertTrue(f.transport.entries().isEmpty())
        }
    }

    @Test fun managedMissingMalformedDeniedAndStaleDataNeverFallBack() = runBlocking<Unit> {
        for (case in listOf("missing", "malformed", "display-dropped", "permission", "drift", "wrong-type", "no-origin", "wrong-origin")) MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            val board = if (case == "permission") board().copy(permissions = board().permissions.copy(canEdit = false)) else board()
            val card = when (case) {
                "missing" -> f.card().copy(membershipObservation = NativeMembershipObservation.Absent)
                "malformed" -> f.card(MembershipExecutionFixture.body("[null]"))
                "display-dropped" -> f.card(MembershipExecutionFixture.body("""[{"id":3,"title":"Kept"},{"id":9}]"""))
                "no-origin" -> f.card().copy(readSource = null)
                "wrong-origin" -> f.card().copy(readSource = f.card().readSource!!.copy(requestPath = "/apps/planning-app/api/cards/99"))
                else -> f.card()
            }
            if (case == "drift") f.transport.readBody = MembershipExecutionFixture.body("[]")
            if (case == "wrong-type") f.transport.readBody = MembershipExecutionFixture.body().replace("\"id\":41", "\"id\":\"41\"")
            assertIs<NativeActionExecutionResult.Failure>(controller.apply(board, card, board.labels.take(2)).result)
            assertEquals(0, f.transport.writes, case)
        }
    }

    @Test fun staleNoOpCannotClaimSuccessAndARefreshedBaselineCanRestoreTheSelection() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            val original = f.card()
            f.transport.onSend = {
                if (f.transport.writes == 2) { f.transport.writeStatus = 403; f.transport.afterWrite = null }
            }
            val partial = controller.apply(board(), original, board().labels)
            assertEquals(1, partial.completed)
            assertIs<NativeActionExecutionResult.Failure>(partial.result)
            assertIs<NativeActionExecutionResult.Failure>(controller.apply(board(), original, original.labels).result)
            assertEquals(2, f.transport.writes)
            val refreshed = assertNotNull(controller.refreshCard(original))
            assertEquals(setOf(3L, 5L), refreshed.labels.map { it.id }.toSet())
            f.transport.writeStatus = 200
            f.transport.afterWrite = MembershipExecutionFixture.body()
            f.transport.onSend = {}
            assertIs<NativeActionExecutionResult.Success>(controller.apply(board(), refreshed, original.labels).result)
            assertEquals(3, f.transport.writes)
            assertTrue(f.transport.requests.last { it.method != NextcloudApiMethod.GET }.relativePath.endsWith("/removeLabel"))
        }
    }

    @Test fun unchangedNoOpSucceedsWithClearSafetyAndFreshMembership() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val original = f.card()
            val result = controller(f).apply(board(), original, original.labels)
            assertIs<NativeActionExecutionResult.Success>(result.result)
            assertEquals(0, result.completed)
            assertEquals(0, f.transport.writes)
            assertTrue(f.transport.requests.any { it.method == NextcloudApiMethod.GET })
            assertTrue(f.transport.entries().isEmpty())
        }
    }

    @Test fun noOpPreservesUnknownDeliveryAcrossRestartAndUnusableEvidence() = runBlocking<Unit> {
        for (restart in listOf(false, true)) MembershipExecutionFixture().use { f ->
            val original = f.card()
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            assertIs<NativeActionExecutionResult.Failure>(controller(f).apply(board(), original, board().labels.take(2)).result)
            val pending = assertNotNull(f.transport.entries().single().pendingVerification)
            if (restart) f.restart()
            val active = controller(f)
            val noOp = active.apply(board(), original, original.labels)
            assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(noOp.result).outcome)
            assertEquals(0, noOp.completed)
            assertEquals(1, f.transport.writes)
            assertEquals(pending, f.transport.entries().single().pendingVerification)
            // Unreadable encrypted evidence must not turn a pending safety token into a successful no-op.
            f.transport.draftDirectory.listFiles().orEmpty().filter { it.extension == "draft" }.forEach { it.writeText("unreadable") }
            assertEquals(NativeActionFailureOutcome.Unknown,
                assertIs<NativeActionExecutionResult.Failure>(active.apply(board(), original, original.labels).result).outcome)
            assertEquals(1, f.transport.writes)
            assertEquals(pending, f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun crossFamilyCardsWithEqualIdsAndLabelsNeverAcquireManagedAuthority() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val directCard = f.card()
            val directContract = assertNotNull(resolveSemanticBoardContract(f.descriptor, true))
            val nested = membershipContract(nested = true)
            val nestedRead = nested.actions.single { it.binding.method == HttpMethod.GET && it.binding.path.endsWith("/{id}") }
            val alternate = nestedRead.copy(id = "alternate-card-read", resourceId = f.read.resourceId)
            f.descriptor = f.descriptor.copy(actions = f.descriptor.actions + alternate)
            val alternateBinding = assertNotNull(resolveSemanticBoardContract(nested, true)).binding(BoardOperation.ReadCard).copy(action = alternate)
            val preferred = directContract.copy(descriptor = f.descriptor,
                operations = directContract.operations + (BoardOperation.ReadCard to alternateBinding))
            assertIs<BoardMembershipCandidate.NotManaged>(preferred.membershipCandidate(BoardOperation.AssignLabel))
            val gate = DynamicContractWriteGate().also { it.settle(f.descriptor) }
            fun controller(contract: SemanticBoardContract) = SemanticBoardMembershipController(f.transport.services,
                f.transport.session, SemanticBoardRoutes(contract), gate) { f.transport.services.executeNextcloudApi(f.transport.session, it) }
            assertFalse(controller(preferred).managesMembership)
            val nestedCard = directCard.copy(readSource = DeckCardReadSource(alternate.id, alternate.binding.path,
                "/apps/planning-app/api/boards/7/stacks/10/cards/41"))
            val aligned = controller(directContract.copy(descriptor = f.descriptor))
            assertTrue(aligned.managesMembership)
            assertIs<NativeActionExecutionResult.Failure>(aligned.apply(board(), nestedCard, board().labels.take(2)).result)
            assertTrue(f.transport.requests.isEmpty(), "An equal ID and labels do not authorize an alternate item read or a write.")
        }
    }

    @Test fun batchStopsAtUnknownAndRestartStatusAndRepeatKeepExactMember() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            val controller = controller(f)
            val result = controller.apply(board(), f.card(), board().labels)
            assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(result.result).outcome)
            assertEquals(0, result.completed)
            assertEquals(1, f.transport.writes)
            f.restart()
            val restarted = controller(f)
            assertTrue(restarted.canRepeat(f.card()))
            assertIs<NativeActionExecutionResult.Failure>(restarted.checkStatus())
            assertEquals(1, f.transport.writes)
            f.transport.writeStatus = 200
            f.transport.afterWrite = MembershipExecutionFixture.body("""[{"id":3,"title":"Kept"},{"id":5,"title":"New"}]""")
            assertIs<NativeActionExecutionResult.Success>(restarted.repeat(board(), f.card()))
            assertEquals(2, f.transport.writes)
            assertFalse(restarted.canRepeat(f.card()))
        }
    }

    @Test fun batchesSerializeAndProvenLegacySiblingCannotOverlapOrBypassPending() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.transport.onSend = { entered.complete(Unit); release.await() }
            f.transport.writeStatus = 503; f.transport.afterWrite = null
            val first = async { controller.apply(board(), f.card(), board().labels.take(2)) }
            withTimeout(30_000) { entered.await() }
            val sibling = NextcloudApiRequest(NextcloudApiMethod.PUT, "/apps/planning-app/api/cards/41", body = "{}".encodeToByteArray())
            assertFailsWith<IllegalStateException> { controller.execute(sibling) }
            val second = async { controller.apply(board(), f.card(), board().labels) }
            yield()
            assertEquals(1, f.transport.writes)
            release.complete(Unit)
            assertIs<NativeActionExecutionResult.Failure>(first.await().result)
            assertIs<NativeActionExecutionResult.Failure>(second.await().result)
            assertEquals(1, f.transport.writes)
            assertFailsWith<IllegalStateException> { controller.execute(sibling) }
        }
    }

    @Test fun ordinarySiblingHoldingRouteGateStopsManagedSendInReverseOrder() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.transport.onSend = { entered.complete(Unit); release.await() }
            val legacy = async { controller.execute(NextcloudApiRequest(NextcloudApiMethod.PUT, "/apps/planning-app/api/cards/41")) }
            withTimeout(30_000) { entered.await() }
            val noOp = controller(f).apply(board(), f.card(), f.card().labels)
            assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(noOp.result).outcome)
            val result = controller.apply(board(), f.card(), board().labels.take(2))
            assertIs<NativeActionExecutionResult.Failure>(result.result)
            assertEquals(1, f.transport.writes)
            release.complete(Unit); legacy.await()
        }
    }

    @Test fun managedRawSenderAndQuarantinedSiblingAreBlockedButUnrelatedFamiliesStayOnTheirPath() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            assertFailsWith<IllegalStateException> { controller.execute(NextcloudApiRequest(NextcloudApiMethod.POST, "/apps/planning-app/api/cards/41/assignLabel")) }
            f.transport.afterWrite = MembershipExecutionFixture.body("[]")
            assertIs<NativeActionExecutionResult.Failure>(controller.apply(board(), f.card(), board().labels.take(2)).result)
            assertFailsWith<IllegalStateException> { controller.execute(NextcloudApiRequest(NextcloudApiMethod.PUT, "/apps/planning-app/api/cards/41")) }
            val before = f.transport.writes
            controller.execute(NextcloudApiRequest(NextcloudApiMethod.PUT, "/apps/planning-app/api/boards/7/stacks/10/cards/41"))
            assertEquals(before + 1, f.transport.writes)
        }
    }

    @Test fun aLocalMembershipGuardRefusalNeverClaimsTheLegacyWriteWasSent() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            val controller = controller(f)
            val request = NextcloudApiRequest(NextcloudApiMethod.POST, "/apps/planning-app/api/cards/41/assignLabel")
            val failure = assertFailsWith<BoardMembershipWriteBlocked> { controller.execute(request) }
            assertFalse(isAmbiguousDeckMutationFailure(null, failure))
            assertFalse(requiresDeckBatchMutationReconciliation(0, null, failure))
            var read = false
            val result = runVerifiedDeckWrite(execute = controller::execute, request = request,
                reread = { read = true; f.card() }, verify = { DeckWriteVerification.Confirmed }, actionLabel = "Change label")
            assertIs<DeckVerifiedWriteOutcome.Rejected<*>>(result)
            assertFalse(read)
            assertEquals(0, f.transport.writes)
        }
    }

    @Test fun aChangedContractCannotDowngradeRecordedMembershipToLegacySending() = runBlocking<Unit> {
        for (status in listOf(200, 503)) MembershipExecutionFixture().use { f ->
            f.transport.writeStatus = status
            if (status == 503) f.transport.afterWrite = null
            controller(f).apply(board(), f.card(), board().labels.take(2))
            f.descriptor = membershipContract(includeInverse = false)
            val changed = controller(f)
            assertFalse(changed.managesMembership)
            assertIs<NativeActionExecutionResult.Failure>(changed.apply(board(), f.card(), board().labels).result)
            assertEquals(1, f.transport.writes)
            // An unrelated route still has no inferred identity relationship with the recorded route.
            changed.execute(NextcloudApiRequest(NextcloudApiMethod.PUT, "/apps/planning-app/api/boards/7/stacks/10/cards/41"))
            assertEquals(2, f.transport.writes)
        }
    }

    @Test fun aSuccessfulBatchVerifiesEachBaselineAndDirection() = runBlocking<Unit> {
        MembershipExecutionFixture(nested = true).use { f ->
            f.transport.onSend = {
                f.transport.afterWrite = MembershipExecutionFixture.body(when (f.transport.writes) {
                    1 -> """[{"id":3,"title":"Kept"},{"id":5,"title":"New"}]"""
                    2 -> """[{"id":3,"title":"Kept"},{"id":5,"title":"New"},{"id":8,"title":"Later"}]"""
                    else -> """[{"id":5,"title":"New"},{"id":8,"title":"Later"}]"""
                })
            }
            val result = controller(f).apply(board(), f.card(), board().labels.drop(1))
            assertIs<NativeActionExecutionResult.Success>(result.result)
            assertEquals(3, result.completed)
            val writes = f.transport.requests.filter { it.method != NextcloudApiMethod.GET }
            assertEquals(listOf("assignLabel", "assignLabel", "removeLabel"), writes.map { it.relativePath.substringAfterLast('/') })
            assertEquals(listOf(5, 8, 3), writes.map { Json.parseToJsonElement(it.body!!.decodeToString()).jsonObject.getValue("labelId").jsonPrimitive.int })
            assertNull(f.transport.entries().single().pendingVerification)
        }
    }

    @Test fun aPartlyCompletedBatchStopsAtTheFirstUnknownMember() = runBlocking<Unit> {
        MembershipExecutionFixture().use { f ->
            f.transport.onSend = {
                if (f.transport.writes == 2) { f.transport.writeStatus = 503; f.transport.afterWrite = null }
            }
            val result = controller(f).apply(board(), f.card(), board().labels.drop(1))
            assertEquals(1, result.completed)
            assertEquals(NativeActionFailureOutcome.Unknown, assertIs<NativeActionExecutionResult.Failure>(result.result).outcome)
            assertEquals(2, f.transport.writes)
            assertNotNull(f.transport.entries().single().pendingVerification)
        }
    }
}
