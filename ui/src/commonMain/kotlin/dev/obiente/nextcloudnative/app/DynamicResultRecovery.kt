package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeResultRecoveryRead
import kotlinx.coroutines.CancellationException

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
    // A status that was cached while the attempt was still queued would be repeated forever.
    val apiRequest = buildDynamicResultRecoveryRequest(descriptor, mutation, request.values, runtimeContext)
        ?.copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
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
