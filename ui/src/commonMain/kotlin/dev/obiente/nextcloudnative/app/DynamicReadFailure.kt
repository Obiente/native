package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal enum class DynamicReadFailureKind {
    Authentication, Permission, Missing, Throttled, Server, Rejected, MailboxNotSynchronized,
}

internal class DynamicReadLoadException(
    message: String,
    val kind: DynamicReadFailureKind,
    val httpStatus: Int,
    val method: HttpMethod,
) : IllegalStateException(message) {
    val specificity: Int get() = if (kind == DynamicReadFailureKind.MailboxNotSynchronized) 2 else 1
}

internal fun NextcloudApiResponse.toDynamicReadLoadException(action: DynamicAction): DynamicReadLoadException =
    toDynamicReadLoadException(action.resourceId, action.binding.method)

internal fun NextcloudApiResponse.toDynamicReadLoadException(
    resourceId: String,
    method: HttpMethod,
): DynamicReadLoadException {
    val kind = when {
        status == 401 -> DynamicReadFailureKind.Authentication
        status == 403 -> DynamicReadFailureKind.Permission
        status == 404 || status == 410 -> DynamicReadFailureKind.Missing
        status == 429 -> DynamicReadFailureKind.Throttled
        status == 400 && hasMailboxNotSynchronizedMessage() -> DynamicReadFailureKind.MailboxNotSynchronized
        status in 500..599 -> DynamicReadFailureKind.Server
        else -> DynamicReadFailureKind.Rejected
    }
    val resource = resourceId.substringAfterLast('.').take(80)
        .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()
        .replaceFirstChar { it.uppercase() }.ifBlank { "this view" }
    val guidance = when (kind) {
        DynamicReadFailureKind.Authentication -> "Sign in again to continue."
        DynamicReadFailureKind.Permission -> "Your account does not have permission to view this content."
        DynamicReadFailureKind.Missing -> "This content is no longer available. Refresh to check for changes."
        DynamicReadFailureKind.Throttled -> "The server is busy. Wait a moment, then try again."
        DynamicReadFailureKind.Server -> "The server could not load this content. Try again in a moment."
        DynamicReadFailureKind.Rejected -> "The server could not complete this request. Refresh and try again."
        DynamicReadFailureKind.MailboxNotSynchronized -> "This mailbox has not been synchronized on the server yet."
    }
    return DynamicReadLoadException("Could not load $resource. $guidance", kind, status, method)
}

/** Only reviewed translations can enter product copy; arbitrary server messages stay private. */
private fun NextcloudApiResponse.hasMailboxNotSynchronizedMessage(): Boolean {
    if (body.size > MAX_DYNAMIC_ERROR_BODY_BYTES) return false
    // This optional translation needs only a shallow envelope. Conservatively bound structural
    // tokens before parsing, including tokens in strings, rather than parsing arbitrary deep JSON.
    if (body.count { it == '{'.code.toByte() || it == '['.code.toByte() } > 64) return false
    val root = try {
        Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        null
    } ?: return false
    val candidates = listOf(
        root["message"],
        (root["data"] as? JsonObject)?.get("message"),
        (root["error"] as? JsonObject)?.get("message"),
        ((root["ocs"] as? JsonObject)?.get("meta") as? JsonObject)?.get("message"),
    )
    return candidates.any { candidate ->
        val message = (candidate as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        message != null && message.length <= 240 && message.startsWith("mailbox ") &&
            message.endsWith(" is not cached") && message.none(Char::isISOControl)
    }
}

private const val MAX_DYNAMIC_ERROR_BODY_BYTES = 8_192
