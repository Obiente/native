package dev.obiente.nextcloudnative.app

import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

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
