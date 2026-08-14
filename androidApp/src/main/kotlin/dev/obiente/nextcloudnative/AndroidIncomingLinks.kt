package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudNativeLinkRequest
import java.security.MessageDigest

internal data class AndroidIncomingLinkState(
    val sequence: Long,
    val request: NextcloudNativeLinkRequest?,
)

internal fun nextAndroidIncomingLinkState(
    previousSequence: Long,
    action: String?,
    dataUrl: String?,
): AndroidIncomingLinkState {
    require(previousSequence >= 0L) { "The incoming link sequence is invalid." }
    val candidate = normalizedAndroidIncomingLink(action, dataUrl)
        ?: return AndroidIncomingLinkState(previousSequence, null)
    val sequence = previousSequence + 1L
    check(sequence > previousSequence) { "The incoming link sequence is exhausted." }
    return AndroidIncomingLinkState(
        sequence = sequence,
        request = NextcloudNativeLinkRequest(sequence, candidate),
    )
}

internal fun isNewAndroidIncomingLinkDelivery(
    lastDeliveryId: String?,
    currentDeliveryId: String?,
    lastPayloadIdentity: String?,
    currentPayloadIdentity: String?,
    restoringLaunchIntent: Boolean,
): Boolean = when {
    currentDeliveryId != null -> currentDeliveryId != lastDeliveryId
    restoringLaunchIntent && currentPayloadIdentity != null -> currentPayloadIdentity != lastPayloadIdentity
    else -> true
}

internal fun androidIncomingLinkPayloadIdentity(
    action: String?,
    dataUrl: String?,
): String? {
    val candidate = normalizedAndroidIncomingLink(action, dataUrl) ?: return null
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$action\u0000$candidate".encodeToByteArray())
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

internal fun restoreAndroidIncomingLinkQueue(
    restoredSequence: Long,
    sequences: LongArray?,
    urls: List<String>?,
    legacyUrl: String?,
): List<NextcloudNativeLinkRequest> {
    if (restoredSequence < 0L) return emptyList()
    if (sequences != null || urls != null) {
        if (sequences == null || urls == null || sequences.size != urls.size) return emptyList()
        if (sequences.size > MAX_ANDROID_INCOMING_LINK_QUEUE_COUNT) return emptyList()
        var previous = 0L
        val restored = mutableListOf<NextcloudNativeLinkRequest>()
        sequences.indices.forEach { index ->
            val sequence = sequences[index]
            val url = urls[index]
            if (sequence <= previous || sequence > restoredSequence || !url.isSupportedAndroidIncomingLink()) {
                return emptyList()
            }
            previous = sequence
            val request = NextcloudNativeLinkRequest(sequence, url)
            if (!canEnqueueAndroidIncomingLink(restored, request)) return emptyList()
            restored += request
        }
        return restored
    }
    return legacyUrl
        ?.takeIf(String::isSupportedAndroidIncomingLink)
        ?.takeIf { restoredSequence > 0L }
        ?.let { listOf(NextcloudNativeLinkRequest(restoredSequence, it)) }
        .orEmpty()
}

internal fun canEnqueueAndroidIncomingLink(
    queued: List<NextcloudNativeLinkRequest>,
    request: NextcloudNativeLinkRequest,
): Boolean {
    if (queued.size >= MAX_ANDROID_INCOMING_LINK_QUEUE_COUNT) return false
    val queuedBytes = queued.sumOf { pending -> pending.url.encodeToByteArray().size }
    return queuedBytes + request.url.encodeToByteArray().size <= MAX_ANDROID_INCOMING_LINK_QUEUE_BYTES
}

private fun normalizedAndroidIncomingLink(action: String?, dataUrl: String?): String? =
    dataUrl
        ?.takeIf { action == ANDROID_ACTION_VIEW }
        ?.trim()
        ?.takeIf(String::isSupportedAndroidIncomingLink)

private fun String.isSupportedAndroidIncomingLink(): Boolean {
    if (length !in 1..MAX_ANDROID_INCOMING_LINK_LENGTH) return false
    if (any { it.isWhitespace() || it.isISOControl() } || '\\' in this) return false
    if (startsWith("nextcloudnative://open?", ignoreCase = true)) return true
    if (!startsWith("https://", ignoreCase = true) && !startsWith("http://", ignoreCase = true)) {
        return false
    }
    val authority = substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return authority.isNotBlank() && '@' !in authority
}

internal const val ANDROID_ACTION_VIEW = "android.intent.action.VIEW"
private const val MAX_ANDROID_INCOMING_LINK_LENGTH = 8_192
internal const val MAX_ANDROID_INCOMING_LINK_QUEUE_COUNT = 16
internal const val MAX_ANDROID_INCOMING_LINK_QUEUE_BYTES = 64 * 1024
