package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

/** Which notes the list shows. Favorites span every folder, like a search. */
enum class NotesListFilter(val label: String) {
    All("All notes"),
    Favorites("Favorites"),
}

fun filterNotesForList(
    notes: List<NextcloudNote>,
    folderNotes: List<NextcloudNote>,
    query: String,
    filter: NotesListFilter,
): List<NextcloudNote> {
    val searching = query.isNotBlank()
    val candidates = when {
        filter == NotesListFilter.Favorites -> notes.filter(NextcloudNote::favorite)
        searching -> notes
        else -> folderNotes
    }
    val matches = candidates.filter { note ->
        !searching || note.title.contains(query, ignoreCase = true) ||
            note.category.contains(query, ignoreCase = true) ||
            note.content?.contains(query, ignoreCase = true) == true
    }
    return if (filter == NotesListFilter.Favorites) matches.sortedByDescending(NextcloudNote::modified) else matches
}

sealed interface NoteFavoriteOutcome {
    data class Saved(val note: NextcloudNote) : NoteFavoriteOutcome
    data class Failed(val message: String, val latest: NextcloudNote? = null) : NoteFavoriteOutcome
    data class Unknown(val message: String) : NoteFavoriteOutcome
}

/**
 * Sets a note's favorite flag from the list.
 *
 * The note is read first so the update carries its current content, category, and ETag; the
 * Notes API then rejects the write with 412 if the note changed in between. Setting a flag is
 * idempotent, so a failed or unreadable write is resolved by reading the note again.
 */
internal suspend fun setNoteFavorite(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    noteId: Long,
    favorite: Boolean,
): NoteFavoriteOutcome {
    val current = try {
        services.loadNote(session, noteId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        return NoteFavoriteOutcome.Failed(failure.message ?: "The note could not be loaded.")
    }
    if (current.favorite == favorite) return NoteFavoriteOutcome.Saved(current)
    if (current.readOnly) return NoteFavoriteOutcome.Failed("This note is read only.", current)
    val content = current.content
        ?: return NoteFavoriteOutcome.Failed("The note content is unavailable. Refresh and try again.", current)
    val writeFailure = try {
        val saved = services.updateNote(
            session = session,
            noteId = current.id,
            content = content,
            category = current.category,
            favorite = favorite,
            expectedEtag = current.etag,
            title = current.title,
        )
        if (saved.favorite == favorite) return NoteFavoriteOutcome.Saved(saved)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure
    }
    return try {
        val latest = services.loadNote(session, noteId)
        if (latest.favorite == favorite) {
            NoteFavoriteOutcome.Saved(latest)
        } else {
            NoteFavoriteOutcome.Failed(writeFailure?.message ?: "Notes did not change the favorite.", latest)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NoteFavoriteOutcome.Unknown("Notes may have changed the favorite. Refresh to check.")
    }
}
