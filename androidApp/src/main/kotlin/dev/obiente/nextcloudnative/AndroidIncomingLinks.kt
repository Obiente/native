package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudNativeLinkRequest

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
    val candidate = dataUrl
        ?.takeIf { action == ANDROID_ACTION_VIEW }
        ?.trim()
        ?.takeIf(String::isSupportedAndroidIncomingLink)
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
): Boolean = currentDeliveryId == null || currentDeliveryId != lastDeliveryId

internal fun restoreAndroidIncomingLinkQueue(
    restoredSequence: Long,
    sequences: LongArray?,
    urls: List<String>?,
    legacyUrl: String?,
): List<NextcloudNativeLinkRequest> {
    if (restoredSequence < 0L) return emptyList()
    if (sequences != null || urls != null) {
        if (sequences == null || urls == null || sequences.size != urls.size) return emptyList()
        var previous = 0L
        return sequences.indices.map { index ->
            val sequence = sequences[index]
            val url = urls[index]
            if (sequence <= previous || sequence > restoredSequence || !url.isSupportedAndroidIncomingLink()) {
                return emptyList()
            }
            previous = sequence
            NextcloudNativeLinkRequest(sequence, url)
        }
    }
    return legacyUrl
        ?.takeIf(String::isSupportedAndroidIncomingLink)
        ?.takeIf { restoredSequence > 0L }
        ?.let { listOf(NextcloudNativeLinkRequest(restoredSequence, it)) }
        .orEmpty()
}

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
