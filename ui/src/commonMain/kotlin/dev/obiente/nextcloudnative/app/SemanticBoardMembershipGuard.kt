package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.rules.NativeMembershipRuleProof
import kotlinx.coroutines.CancellationException

/** No route-equivalence inference: only exact target templates and their immediate commands. */
internal suspend fun guardBoardMembershipSibling(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    proofs: List<NativeMembershipRuleProof>,
    producer: AccountPrivateMemoryProducer?,
    request: NextcloudApiRequest,
    send: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
): NextcloudApiResponse {
    if (request.method == NextcloudApiMethod.GET) return send(request)
    requireMembershipWrite(sharedAccountPrivateMemoryGate.read(producer, false) { true }) { "The account changed. Nothing was sent." }
    val actual = request.relativePath.substringBefore('?').split('/').filter(String::isNotEmpty)
    fun matches(route: String): Boolean {
        val target = route.split('/').filter(String::isNotEmpty)
        return actual.size in target.size..target.size + 1 && target.indices.all { index ->
            target[index].startsWith('{') || target[index] == actual[index]
        }
    }
    val proven = proofs.map { it.targetRoute }.filter(::matches)
    // A changed descriptor cannot turn a previously managed route into an unguarded legacy send.
    // Canonical equality protects admission only; it does not supply an execution proof.
    val recorded = if (proven.isEmpty()) {
        val keys = readBoardMembershipSafety(services, session, descriptor).filter { it.key.ruleId == "label-membership" }
            .mapTo(mutableSetOf()) { it.key.route }
        descriptor.actions.mapNotNull { action ->
            val parts = action.binding.path.split('/').filter(String::isNotEmpty)
            val target = when {
                parts.lastOrNull()?.startsWith('{') == true -> parts
                parts.getOrNull(parts.lastIndex - 1)?.startsWith('{') == true -> parts.dropLast(1)
                else -> return@mapNotNull null
            }.joinToString("/", prefix = "/")
            target.takeIf { matches(it) && membershipGuardRoute(session, descriptor, it) in keys }
        }
    } else emptyList()
    val matched = (proven + recorded).distinctBy { membershipGuardRoute(session, descriptor, it) }
    if (matched.isEmpty()) {
        requireMembershipWrite(sharedAccountPrivateMemoryGate.read(producer, false) { true }) { "The account changed. Nothing was sent." }
        return send(request)
    }
    requireMembershipWrite(matched.size == 1) { "This card's target is ambiguous. Nothing was changed." }
    val segments = matched.single().split('/').filter(String::isNotEmpty)
    val canonical = membershipGuardRoute(session, descriptor, matched.single())
    val routeGate = listOf("rule-route", session.accountId.storageKey, descriptor.app.id, canonical).joinToString("\u0000")
    val targetGate = listOf(session.accountId.storageKey, descriptor.app.id, actual.take(segments.size).joinToString("/", prefix = "/")).joinToString("\u0000")
    requireMembershipWrite(DynamicWriteInFlightGate.tryAcquire(routeGate)) { "Another change here is still being checked." }
    try {
        requireMembershipWrite(DynamicWriteInFlightGate.tryAcquire(targetGate)) { "Another change to this card is still being checked." }
        try {
            requireMembershipWrite(sharedAccountPrivateMemoryGate.read(producer, false) { true }) { "The account changed. Nothing was sent." }
            val records = readBoardMembershipSafety(services, session, descriptor, required = true)
            requireMembershipWrite(records.none {
                it.key.route == canonical && (it.pendingVerification != null || it.status == DynamicWriteRuleStatus.quarantined)
            }) { "Changes here are paused. Check the earlier change in Contract info." }
            val command = actual.lastOrNull()?.lowercase()
            requireMembershipWrite(actual.size == segments.size || command !in setOf("assignlabel", "removelabel")) {
                "This app's label actions could not be verified. Refresh before trying again."
            }
            requireMembershipWrite(sharedAccountPrivateMemoryGate.read(producer, false) { true }) { "The account changed. Nothing was sent." }
            return send(request)
        } finally { DynamicWriteInFlightGate.release(targetGate) }
    } finally { DynamicWriteInFlightGate.release(routeGate) }
}

internal fun membershipGuardRoute(session: NextcloudSession, descriptor: DynamicAppDescriptor, route: String): String {
    val canonical = route.split('/').joinToString("/") { if (it.startsWith('{')) "{}" else it }
    return DynamicWriteRuleKey.of(session.accountId.storageKey, descriptor.app.id, descriptor.app.version,
        canonical, "label-membership", 1).route
}

private suspend fun readBoardMembershipSafety(services: NextcloudPlatformServices, session: NextcloudSession,
    descriptor: DynamicAppDescriptor, required: Boolean = false): List<DynamicWriteRuleRecord> = try {
    val store = services.dynamicWriteQuarantineStore()
    requireMembershipWrite(!required || store != null) { "Write recovery storage is unavailable." }
    store?.records(session.accountId.storageKey, descriptor.app.id).orEmpty()
} catch (cancelled: CancellationException) { throw cancelled }
catch (_: UnsupportedOperationException) {
    if (required) throw BoardMembershipWriteBlocked("Write recovery storage is unavailable.") else emptyList()
}
catch (_: Exception) { throw BoardMembershipWriteBlocked("Write safety could not be checked. Nothing was sent.") }

/** A known-before-send refusal, distinct from a transport result whose delivery is unknown. */
internal class BoardMembershipWriteBlocked(message: String) : IllegalStateException(message)

private inline fun requireMembershipWrite(allowed: Boolean, message: () -> String) {
    if (!allowed) throw BoardMembershipWriteBlocked(message())
}
