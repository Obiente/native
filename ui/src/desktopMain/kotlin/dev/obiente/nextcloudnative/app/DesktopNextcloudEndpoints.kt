package dev.obiente.nextcloudnative.app

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

internal fun talkMessageHistoryPath(
    token: String,
    olderCursor: Long?,
    limit: Int,
): String {
    require(limit in 1..MAX_TALK_MESSAGE_PAGE_SIZE) {
        "Talk message page size must be between 1 and $MAX_TALK_MESSAGE_PAGE_SIZE."
    }
    require(olderCursor == null || olderCursor >= 0L) {
        "Talk history cursor must not be negative."
    }
    val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20")
    return "/ocs/v2.php/apps/spreed/api/v1/chat/$encodedToken" +
        "?format=json&lookIntoFuture=0&limit=$limit&lastKnownMessageId=${olderCursor ?: 0L}" +
        "&includeLastKnown=0&setReadMarker=0&markNotificationsAsRead=0&noStatusUpdate=1"
}

internal const val NOTES_LIST_RELATIVE_PATH = "/index.php/apps/notes/api/v1/notes?exclude=content"

internal fun <T> invokeOnSwingEventThread(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val outcome = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { outcome.set(runCatching(action)) }
    return outcome.get().getOrThrow()
}

internal fun notesDetailRelativePath(noteId: Long): String {
    require(noteId >= 0L) { "The note ID is invalid." }
    return "/index.php/apps/notes/api/v1/notes/$noteId"
}

internal fun notesConditionalHeaders(expectedEtag: String?): Map<String, String> =
    expectedEtag?.takeIf(String::isNotBlank)?.let { mapOf("If-None-Match" to it) }.orEmpty()

internal fun resolvedNoteEtag(responseEtag: String?, documentEtag: String?): String? =
    responseEtag?.takeIf(String::isNotBlank) ?: documentEtag?.takeIf(String::isNotBlank)
