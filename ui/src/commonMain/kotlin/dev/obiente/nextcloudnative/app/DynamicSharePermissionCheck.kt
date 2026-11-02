package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.valueNamed
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * R8: a share is sent only when the record it targets states, through the contract's declared
 * permission field, that the current actor may share it. Declaring the field is not enough; its
 * value is read fresh, bounded and typed, immediately before the share.
 */

/** Why a share was not sent. */
internal enum class DynamicSharePermissionRefusal(val message: String) {
    /** The contract declares no permission field or no read that returns it. */
    Undeclared("Sharing is offered only when the app states that you may share this item."),

    /** The app states that the current actor may not share this item. */
    Denied("The app states that you may not share this item, so nothing was shared."),

    /** The permission could not be read for exactly this item. */
    Unverified("Whether you may share this item could not be checked, so nothing was shared."),
}

/**
 * Reads the declared permission field of the share's target through the route-family read and
 * returns null only when it is the JSON boolean `true`. Any other value, a missing field, a failed
 * read, or a read that does not identify exactly the target refuses the share.
 */
internal suspend fun DynamicWriteContext.sharePermissionRefusal(request: NativeActionRequest.Submit): DynamicSharePermissionRefusal? {
    val field = action.sharePermissionFieldId ?: return DynamicSharePermissionRefusal.Undeclared
    val read = action.readBackActionId?.let { id -> descriptor.actions.singleOrNull { it.id == id } }
        ?.takeIf { field in it.responseFieldIds }
        ?: return DynamicSharePermissionRefusal.Undeclared
    val records = try {
        val apiRequest = buildDynamicApiRequest(descriptor, read, request.values, runtimeContext + request.values)
            .copy(cachePolicy = NextcloudApiCachePolicy.ForceNetwork)
        parseDynamicRecords(read, services.executeNextcloudApi(session, apiRequest), read.responseFieldIds.toSet())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Transport and parser fault boundary: an unread permission never grants a share.
        return DynamicSharePermissionRefusal.Unverified
    }
    val target = shareTarget(records, request) ?: return DynamicSharePermissionRefusal.Unverified
    val value = target.wireValues.valueNamed(field) as? JsonPrimitive
    return when {
        value == null || value.isString -> DynamicSharePermissionRefusal.Unverified
        value.booleanOrNull == true -> null
        value.booleanOrNull == false -> DynamicSharePermissionRefusal.Denied
        else -> DynamicSharePermissionRefusal.Unverified
    }
}

/** The record the share addresses: the one carrying the write's last bound path identity. */
private fun DynamicWriteContext.shareTarget(records: List<NativeRecord>, request: NativeActionRequest.Submit): NativeRecord? {
    val identity = action.binding.pathParameters.lastOrNull()?.name
    val candidates = if (identity == null) {
        records
    } else {
        val id = request.values[identity] ?: runtimeContext[identity] ?: return null
        records.filter { it.id == id }
    }
    return candidates.singleOrNull()?.takeIf(NativeRecord::actionSafeIdentity)
}
