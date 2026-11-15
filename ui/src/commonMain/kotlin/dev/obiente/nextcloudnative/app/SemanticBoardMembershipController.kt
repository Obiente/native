package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.*
import dev.obiente.nextcloudnative.nativeui.rules.*
import dev.obiente.nextcloudnative.nativeui.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive

internal sealed interface BoardMembershipCandidate {
    data object NotManaged : BoardMembershipCandidate
    data class Managed(val proof: NativeMembershipRuleProof) : BoardMembershipCandidate
}

internal fun SemanticBoardContract.membershipCandidate(operation: BoardOperation): BoardMembershipCandidate {
    if (operation !in setOf(BoardOperation.AssignLabel, BoardOperation.RemoveLabel)) return BoardMembershipCandidate.NotManaged
    val binding = operations[operation] ?: return BoardMembershipCandidate.NotManaged
    val schema = descriptor.toNativeAppSchema()
    val actions = schema.actions + schema.dedicatedActions
    val action = actions.singleOrNull { it.id == binding.action.id } ?: return BoardMembershipCandidate.NotManaged
    val proof = SetMembershipRule().proof(schema, PatternRouteFamily(action, actions.filter { it.binding.method == HttpMethod.GET }))
        ?: return BoardMembershipCandidate.NotManaged
    // The board's item read is the source of the selected card, not an interchangeable ID lookup.
    if (operations[BoardOperation.ReadCard]?.action?.id != proof.readActionId) return BoardMembershipCandidate.NotManaged
    return BoardMembershipCandidate.Managed(proof)
}

internal data class BoardMembershipBatchResult(val completed: Int, val result: NativeActionExecutionResult)

/** Board events enter here, outside composition. Managed commands never use the legacy sender. */
internal class SemanticBoardMembershipController(
    private val services: NextcloudPlatformServices,
    private val session: NextcloudSession,
    private val routes: SemanticBoardRoutes,
    private val writeGate: DynamicContractWriteGate,
    private val executeLegacy: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
) {
    private val contract = routes.contract
    private val descriptor = contract.descriptor
    private val schema = descriptor.toNativeAppSchema()
    private val candidates = listOf(BoardOperation.AssignLabel, BoardOperation.RemoveLabel).associateWith(contract::membershipCandidate)
    private val managed = candidates.values.filterIsInstance<BoardMembershipCandidate.Managed>().map { it.proof }.distinct()
    private val batches = Mutex()
    private val producer = sharedAccountPrivateMemoryGate.producer(session.accountId.storageKey)
    private var authority: NativeItemReadAuthority? = null
    private val executor = DynamicNextcloudActionExecutor(services, session, descriptor, writeGate = writeGate,
        membershipAuthority = { authority })

    val managesMembership: Boolean get() = managed.isNotEmpty()

    suspend fun apply(board: DeckBoard, card: DeckCard, selected: List<DeckLabel>): BoardMembershipBatchResult = batches.withLock {
        var completed = 0
        try {
            val access = DeckBoardAccess.from(board)
            access.requireEdit(DeckBoardId(card.boardId))
            if (!currentAccount() || !writeGate.authorizesWrite(ActionRisk.mutating, descriptor)) return@withLock batchRefused()
            if (selected.map { it.id }.distinct().size != selected.size) return@withLock batchRefused()
            selected.forEach { access.requireLabel(it.id) }
            val current = card.labels.map { it.id }.toSet()
            val desired = selected.map { it.id }.toSet()
            val changes = (desired - current).map { BoardOperation.AssignLabel to it } +
                (current - desired).map { BoardOperation.RemoveLabel to it }
            // Display filtering must not choose a delta from an incomplete label array.
            if (managesMembership && !card.hasCompletePickerMembership()) {
                return@withLock batchRefused()
            }
            if (managesMembership && changes.isEmpty()) {
                return@withLock BoardMembershipBatchResult(0, verifyNoChange(card))
            }
            var baseline = card.membershipObservation
            for ((index, change) in changes.withIndex()) {
                val (operation, member) = change
                val result = when (val candidate = candidates.getValue(operation)) {
                    BoardMembershipCandidate.NotManaged -> {
                        val request = if (operation == BoardOperation.AssignLabel) routes.assignLabel(access, card, member)
                            else routes.removeLabel(access, card, member)
                        legacyResult(request)
                    }
                    is BoardMembershipCandidate.Managed -> {
                        val request = prepare(candidate.proof, operation, board, card, JsonPrimitive(member))
                            ?: return@withLock BoardMembershipBatchResult(completed, refused())
                        val condition = checkNotNull(request.membershipPlan).postcondition
                        if (baseline !is NativeMembershipObservation.Complete ||
                            baseline.raw.projectMembership(condition.memberType, condition.projection) !=
                            condition.baseline.projectMembership(condition.memberType, condition.projection)) {
                            return@withLock BoardMembershipBatchResult(completed, refused("The labels changed. Refresh this card before trying again."))
                        }
                        executor.execute(request).also { result ->
                            if (result is NativeActionExecutionResult.Success && index < changes.lastIndex) {
                                // Read the exact target again for the next batch member; never simulate server metadata.
                                val next = try { readAuthority(candidate.proof, board, card) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { null }
                                if (next == null) return@withLock BoardMembershipBatchResult(completed + 1, refused("The saved change was verified, but the remaining labels could not be loaded. Refresh before continuing."))
                                if (checkNotNull(request.membershipPlan).verifyRuleState(next.state.records) != NativeWriteVerification.Confirmed) {
                                    return@withLock BoardMembershipBatchResult(completed + 1, refused("The labels changed again. Refresh before continuing."))
                                }
                                baseline = next.state.records.single().membership(candidate.proof.membershipField)
                            }
                        }
                    }
                }
                if (result is NativeActionExecutionResult.Failure) return@withLock BoardMembershipBatchResult(completed, result)
                completed++
            }
            BoardMembershipBatchResult(completed, NativeActionExecutionResult.Success(if (changes.isEmpty()) "No label changes to save." else "Labels updated."))
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            BoardMembershipBatchResult(completed, refused())
        } finally { authority = null }
    }

    suspend fun checkStatus(): NativeActionExecutionResult = batches.withLock { executor.recoverRuleOperations() }

    private suspend fun verifyNoChange(card: DeckCard): NativeActionExecutionResult {
        val route = managed.map { membershipGuardRoute(session, descriptor, it.targetRoute) }.distinct().singleOrNull()
            ?: return refused()
        val gate = listOf("rule-route", session.accountId.storageKey, descriptor.app.id, route).joinToString("\u0000")
        if (!DynamicWriteInFlightGate.tryAcquire(gate)) return unknown()
        try {
            val store = services.dynamicWriteQuarantineStore() ?: return unknown()
            val records = store.records(session.accountId.storageKey, descriptor.app.id).filter { it.key.route == route }
            // A baseline read cannot settle an earlier ambiguous send, even when the draft matches it.
            if (records.any { it.pendingVerification != null }) return unknown()
            if (records.any { it.status == DynamicWriteRuleStatus.quarantined }) {
                return refused("Changes here are paused. Review the earlier change in Contract info.")
            }
            val current = refreshCard(card) ?: return refused()
            return if (current.pickerMembership() == card.pickerMembership())
                NativeActionExecutionResult.Success("No label changes to save.")
            else refused("The labels changed. Refresh this card before trying again.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return unknown() }
        finally { DynamicWriteInFlightGate.release(gate) }
    }

    /** Refresh the open picker's baseline without replacing its separate unsaved selection. */
    suspend fun refreshCard(card: DeckCard): DeckCard? = try {
        if (!currentAccount() || managed.none { boardReadBindings(it, card) != null }) null
        else fetchAuthoritativeCard(executeLegacy, routes, card).takeIf { current ->
            currentAccount() && current.id == card.id && current.boardId == card.boardId && current.stackId == card.stackId &&
                current.hasCompletePickerMembership() && managed.any { boardReadBindings(it, current) != null }
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }

    /** Recreate the exact recorded member/direction from encrypted evidence, never from picker state. */
    suspend fun repeat(board: DeckBoard, card: DeckCard): NativeActionExecutionResult = batches.withLock {
        try {
            val evidence = pending(card) ?: return@withLock refused("No compatible earlier label change is available.")
            val old = evidence.plan as? NativeMembershipRulePlan ?: return@withLock refused()
            val operation = if (old.postcondition.assign) BoardOperation.AssignLabel else BoardOperation.RemoveLabel
            val proof = (candidates[operation] as? BoardMembershipCandidate.Managed)?.proof ?: return@withLock refused()
            val request = prepare(proof, operation, board, card, old.postcondition.member) ?: return@withLock refused()
            executor.execute(request.copy(ruleRecovery = NativeRuleRecoveryIntent.RepeatSameOperation))
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { unknown() }
        finally { authority = null }
    }

    suspend fun canRepeat(card: DeckCard): Boolean = try {
        val evidence = pending(card)
        evidence != null && managed.any { it == (evidence.plan as? NativeMembershipRulePlan)?.proof }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }

    private suspend fun pending(card: DeckCard): DynamicRuleRecoveryEvidence? {
        if (!currentAccount()) return null
        val store = services.dynamicWriteQuarantineStore() ?: return null
        val records = store.records(session.accountId.storageKey, descriptor.app.id).filter { it.pendingVerification != null }
        return records.mapNotNull { record ->
            val evidence = services.ruleRecoveryStore(session, checkNotNull(record.pendingVerification).operationId)?.load()
                ?: return@mapNotNull null
            val plan = evidence.plan as? NativeMembershipRulePlan ?: return@mapNotNull null
            val bindings = boardReadBindings(plan.proof, card) ?: return@mapNotNull null
            evidence.takeIf { it.key == record.key && it.pending == record.pendingVerification &&
                record.status != DynamicWriteRuleStatus.quarantined && it.appVersion == descriptor.app.version &&
                plan.targetId == card.id.toString() && plan.readBindings == bindings }
        }.singleOrNull()
    }

    private suspend fun prepare(proof: NativeMembershipRuleProof, operation: BoardOperation, board: DeckBoard, card: DeckCard,
        member: JsonPrimitive): NativeActionRequest.Submit? {
        val access = DeckBoardAccess.from(board)
        access.requireEdit(DeckBoardId(card.boardId))
        member.content.toLongOrNull()?.let(access::requireLabel) ?: return null
        if (!currentAccount() || !writeGate.authorizesWrite(ActionRisk.mutating, descriptor)) return null
        authority = readAuthority(proof, board, card) ?: return null
        val actionId = contract.binding(operation).action.id
        val plan = planNativeMembershipRule(schema, actionId, card.id.toString(), member, checkNotNull(authority)) ?: return null
        val action = (schema.actions + schema.dedicatedActions).single { it.id == actionId }
        return NativeActionRequest.Submit(action, plan.writeBindings, true, membershipPlan = plan)
    }

    private suspend fun readAuthority(proof: NativeMembershipRuleProof, board: DeckBoard, card: DeckCard): NativeItemReadAuthority? {
        val bindings = boardReadBindings(proof, card) ?: return null
        val read = descriptor.actions.singleOrNull { it.id == proof.readActionId } ?: return null
        val request = buildDynamicApiRequest(descriptor, read, bindings, emptyMap()).copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
        val records = parseDynamicRecords(read, executeLegacy(request), read.responseFieldIds.toSet())
        if (!currentAccount()) return null
        return NativeItemReadAuthority(NativeScreenState.Ready.authoritative(records), read.id, bindings,
            canEdit = board.id == card.boardId && board.permissions.canEdit && !board.archived)
    }

    private fun boardReadBindings(proof: NativeMembershipRuleProof, card: DeckCard): Map<String, String>? {
        val source = card.readSource ?: return null
        val read = descriptor.actions.singleOrNull { it.id == proof.readActionId } ?: return null
        if (source.actionId != read.id || source.routeTemplate != read.binding.path) return null
        val binding = contract.operations.values.singleOrNull { it.action.id == proof.assignActionId } ?: return null
        val ids = mapOf(BoardEntity.Board to card.boardId, BoardEntity.Lane to card.stackId, BoardEntity.Card to card.id)
        val result = linkedMapOf<String, String>()
        for ((index, mapping) in proof.bindings.withIndex()) {
            val role = binding.pathRoles[mapping.writeName] as? BoardValueRole.Identity ?: return null
            if ((index == proof.bindings.lastIndex) != (role.entity == BoardEntity.Card) || role.entity == BoardEntity.Label) return null
            // The board model has numeric identities. It cannot supply a string identity by coercion.
            if (mapping.type != NativeMemberScalarType.Integer) return null
            result[mapping.readName] = ids[role.entity]?.takeIf { it > 0 }?.toString() ?: return null
        }
        val request = buildDynamicApiRequest(descriptor, read, result, emptyMap())
        return result.takeIf { source.requestPath == request.relativePath }
    }

    /** Only proven exact targets and their one-segment commands share these guards. */
    suspend fun execute(request: NextcloudApiRequest): NextcloudApiResponse =
        guardBoardMembershipSibling(services, session, descriptor, managed, producer, request, executeLegacy)

    private suspend fun legacyResult(request: NextcloudApiRequest): NativeActionExecutionResult = try {
        val response = execute(request)
        when (response.status) {
            in 200..299 -> NativeActionExecutionResult.Success("Saved.")
            in 400..499 -> refused("The app rejected this label change.")
            else -> unknown()
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (blocked: BoardMembershipWriteBlocked) { refused(blocked.message ?: "Nothing was sent.") }
    catch (_: Exception) { unknown() }

    private fun currentAccount() = sharedAccountPrivateMemoryGate.read(producer, false) { true }
}

private fun DeckCard.hasCompletePickerMembership(): Boolean {
    val projected = pickerMembership() ?: return false
    return projected.entries.keys.map { it.content.toLong() }.toSet() == labels.map { it.id }.toSet() && labels.distinctBy { it.id }.size == labels.size
}
private fun DeckCard.pickerMembership(): NativeMembershipProjectionResult.Valid? =
    (membershipObservation as? NativeMembershipObservation.Complete)?.raw
        ?.projectMembership(NativeMemberScalarType.Integer, NativeMembershipProjection.ObjectId) as? NativeMembershipProjectionResult.Valid
private fun refused(message: String = "This card's labels or permissions could not be verified. Refresh before changing them.") =
    NativeActionExecutionResult.Failure(message, NativeActionFailureOutcome.Rejected)
private fun unknown() = NativeActionExecutionResult.Failure("An earlier change still needs verification. Check its status before changing labels.", NativeActionFailureOutcome.Unknown)
private fun batchRefused() = BoardMembershipBatchResult(0, refused())
