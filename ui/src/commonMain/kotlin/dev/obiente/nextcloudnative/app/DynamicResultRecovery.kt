package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.HttpParameter
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeResultRecoveryRead
import kotlinx.coroutines.CancellationException

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
    if (mutation.binding.method != HttpMethod.PUT) return null
    val recoveryId = mutation.resultRecoveryActionId ?: return null
    val recovery = descriptor.actions.singleOrNull { action -> action.id == recoveryId } ?: return null
    val safeKinds = setOf(
        dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind.verifiedAppPackage,
        dev.obiente.nextcloudnative.nativeui.model.ProvenanceKind.appStoreLinkedSourceTag,
    )
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
    return runCatching {
        buildDynamicApiRequest(
            descriptor = descriptor,
            action = recovery,
            values = values,
            runtimeContext = runtimeContext,
        )
    }.getOrNull()
}

/**
 * Runs the recovery read that the descriptor pairs with [request]'s replacement write, with the
 * same bindings the write used. It never runs another operation: a write without an exact
 * recovery read is [NativeResultRecoveryRead.Unavailable]. Only a single returned record is
 * evidence about the write; an empty or multi-record answer carries no fields.
 */
internal suspend fun readDynamicResultRecovery(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    request: NativeActionRequest.Submit,
    runtimeContext: Map<String, String>,
): NativeResultRecoveryRead {
    val mutation = descriptor.actions.firstOrNull { it.id == request.action.id } ?: return NativeResultRecoveryRead.Unavailable
    val recovery = descriptor.actions.singleOrNull { it.id == mutation.resultRecoveryActionId }
        ?: return NativeResultRecoveryRead.Unavailable
    val apiRequest = buildDynamicResultRecoveryRequest(descriptor, mutation, request.values, runtimeContext)
        ?: return NativeResultRecoveryRead.Unavailable
    val records = try {
        parseDynamicRecords(recovery, services.executeNextcloudApi(session, apiRequest))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Transport and parser fault boundary: the status is simply not known.
        return NativeResultRecoveryRead.Failed("The status could not be read.")
    }
    val fields = records.singleOrNull()?.values.orEmpty()
        .mapNotNull { (key, value) -> value?.let { key to it } }
        .toMap()
    return NativeResultRecoveryRead.Read(fields)
}
