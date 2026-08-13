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
    sharedText: String?,
): AndroidIncomingLinkState {
    require(previousSequence >= 0L) { "The incoming link sequence is invalid." }
    val candidate = when (action) {
        ANDROID_ACTION_VIEW -> dataUrl
        ANDROID_ACTION_SEND -> sharedText
        else -> null
    }?.trim()?.takeIf(String::isSupportedAndroidIncomingLink)
        ?: return AndroidIncomingLinkState(previousSequence, null)
    val sequence = previousSequence + 1L
    check(sequence > previousSequence) { "The incoming link sequence is exhausted." }
    return AndroidIncomingLinkState(
        sequence = sequence,
        request = NextcloudNativeLinkRequest(sequence, candidate),
    )
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
internal const val ANDROID_ACTION_SEND = "android.intent.action.SEND"
private const val MAX_ANDROID_INCOMING_LINK_LENGTH = 8_192
