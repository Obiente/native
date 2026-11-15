package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePrecondition
import dev.obiente.nextcloudnative.nativeui.runtime.NativeWritePreconditionFailure
import dev.obiente.nextcloudnative.nativeui.runtime.nativeWritePreconditionFailure
import kotlinx.coroutines.CancellationException

/*
 * The same-route read that belongs to a replacement (PUT) write.
 *
 * The descriptor compiler pairs a PUT with a GET on exactly the same route only for a declared
 * contract: the app's advertised OpenAPI (E2) or its verified package or source tag (E3). That read serves two purposes: recovering the result
 * after an unconfirmed write, and re-reading the target immediately before a replacement so a
 * stale copy never overwrites a concurrent change.
 */

/**
 * Builds the exact authoritative GET declared for an idempotent replacement mutation.
 *
 * The descriptor validator proves same-route PUT/GET pairing. This function repeats the critical
 * checks at the execution boundary so deserialized or independently constructed descriptors fail
 * closed instead of turning an arbitrary action reference into a recovery read.
 */
internal fun buildDynamicResultRecoveryRequest(
    descriptor: DynamicAppDescriptor,
    mutation: DynamicAction,
    values: Map<String, String>,
    runtimeContext: Map<String, String> = values,
): NextcloudApiRequest? {
    val recovery = descriptor.replacementReadFor(mutation) ?: return null
    return runCatching {
        buildDynamicApiRequest(
            descriptor = descriptor,
            action = recovery,
            values = values,
            runtimeContext = runtimeContext,
        )
    }.getOrNull()
}

private fun DynamicAppDescriptor.replacementReadFor(mutation: DynamicAction): DynamicAction? {
    if (mutation.binding.method != HttpMethod.PUT) return null
    val recoveryId = mutation.resultRecoveryActionId ?: return null
    val recovery = actions.singleOrNull { action -> action.id == recoveryId } ?: return null
    val safeKinds = setOf(ProvenanceKind.advertisedOpenApi, ProvenanceKind.verifiedAppPackage, ProvenanceKind.appStoreLinkedSourceTag)
    if (
        recovery.binding.method != HttpMethod.GET ||
        recovery.binding.path != mutation.binding.path ||
        recovery.binding.body != null ||
        recovery.binding.queryParameters.any(HttpParameter::required) ||
        recovery.fallbackOnly ||
        mutation.provenance.none { provenance -> provenance.kind in safeKinds } ||
        recovery.provenance.none { provenance -> provenance.kind in safeKinds }
    ) {
        return null
    }
    return recovery
}

/**
 * Re-reads the target of [mutation] through its same-route read, bypassing caches, and returns a
 * refusal when the write must not be sent: the write declares no such read, the read fails, or
 * [precondition] reports that the target changed. Returns null when the write may proceed.
 *
 * A refusal is always [dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome.Rejected]:
 * nothing was sent, so the draft stays intact.
 */
internal suspend fun checkDynamicWritePrecondition(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    mutation: DynamicAction,
    precondition: NativeWritePrecondition,
    runtimeContext: Map<String, String>,
): NativeActionExecutionResult.Failure? {
    val read = descriptor.replacementReadFor(mutation)
        ?: return nativeWritePreconditionFailure(NativeWritePreconditionFailure.Unverified)
    val records = try {
        val request = buildDynamicApiRequest(descriptor, read, precondition.readValues, runtimeContext + precondition.readValues)
            .copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
        parseDynamicRecords(
            action = read,
            response = services.executeNextcloudApi(session, request),
            declaredFieldIds = read.responseFieldIds.toSet(),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // Parser and transport fault boundary: any failure means the target could not be checked.
        return nativeWritePreconditionFailure(NativeWritePreconditionFailure.Unverified)
    }
    return if (precondition.holds(records)) null else nativeWritePreconditionFailure(NativeWritePreconditionFailure.Changed)
}
