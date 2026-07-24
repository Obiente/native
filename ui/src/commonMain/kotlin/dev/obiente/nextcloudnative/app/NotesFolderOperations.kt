package dev.obiente.nextcloudnative.app

internal data class PreparedNoteFolderMutation(
    val note: NextcloudNote,
    val destinationCategory: String?,
)

internal class PartialNoteFolderMutationException(
    val completedCount: Int,
    val totalCount: Int,
    val refreshedSummaries: List<NextcloudNote>?,
    cause: Throwable,
    refreshFailure: Throwable?,
) : IllegalStateException(
    buildString {
        append("The folder operation stopped after ")
        append(completedCount)
        append(" of ")
        append(totalCount)
        append(if (totalCount == 1) " note." else " notes.")
        if (refreshedSummaries != null) {
            append(" Notes were refreshed to show the server's current state.")
        } else {
            append(" Refreshing Notes also failed")
            refreshFailure?.message?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
            append('.')
        }
        cause.message?.takeIf(String::isNotBlank)?.let { append(" ").append(it) }
    },
    cause,
)

internal fun preflightNoteFolderRename(
    summaries: List<NextcloudNote>,
    loadedNotes: List<NextcloudNote>,
    oldCategory: String,
    newCategory: String,
): List<PreparedNoteFolderMutation> {
    val source = normalizeNoteCategory(oldCategory)
    val destination = normalizeNoteCategory(newCategory)
    require(source.isNotEmpty() && destination.isNotEmpty() && source != destination) {
        "Choose a valid destination folder."
    }
    require(!destination.startsWith("$source/")) { "A folder cannot be moved inside itself." }
    val sourcePrefix = "$source/"
    val targets = summaries.filter { note ->
        note.category == source || note.category.startsWith(sourcePrefix)
    }
    require(targets.isNotEmpty()) { "The note folder no longer exists." }
    val targetIds = targets.mapTo(linkedSetOf(), NextcloudNote::id)
    val collision = summaries.any { note ->
        note.id !in targetIds &&
            (note.category == destination || note.category.startsWith("$destination/"))
    }
    require(!collision) { "A note folder already exists at the rename destination." }
    return prepareLoadedFolderNotes(targets, loadedNotes, source, sourcePrefix) { note ->
        if (note.category == source) {
            destination
        } else {
            destination + "/" + note.category.removePrefix(sourcePrefix)
        }
    }
}

internal fun preflightNoteFolderDelete(
    summaries: List<NextcloudNote>,
    loadedNotes: List<NextcloudNote>,
    category: String,
): List<PreparedNoteFolderMutation> {
    val path = normalizeNoteCategory(category)
    require(path.isNotEmpty()) { "The root note folder cannot be deleted." }
    val prefix = "$path/"
    val targets = summaries.filter { note ->
        note.category == path || note.category.startsWith(prefix)
    }
    require(targets.isNotEmpty()) { "The note folder no longer exists." }
    return prepareLoadedFolderNotes(targets, loadedNotes, path, prefix) { null }
}

internal suspend fun executeNoteFolderRename(
    summaries: List<NextcloudNote>,
    oldCategory: String,
    newCategory: String,
    loadNote: suspend (Long) -> NextcloudNote,
    updateNote: suspend (PreparedNoteFolderMutation) -> Unit,
    reloadSummaries: suspend () -> List<NextcloudNote>,
) {
    val source = normalizeNoteCategory(oldCategory)
    val sourcePrefix = "$source/"
    val targetSummaries = summaries.filter { note ->
        note.category == source || note.category.startsWith(sourcePrefix)
    }
    require(targetSummaries.isNotEmpty()) { "The note folder no longer exists." }
    val loaded = targetSummaries.map { summary -> loadNote(summary.id) }
    val prepared = preflightNoteFolderRename(summaries, loaded, source, newCategory)
    executePreparedNoteFolderMutation(prepared, updateNote, reloadSummaries)
}

internal suspend fun executeNoteFolderDelete(
    summaries: List<NextcloudNote>,
    category: String,
    loadNote: suspend (Long) -> NextcloudNote,
    deleteNote: suspend (PreparedNoteFolderMutation) -> Unit,
    reloadSummaries: suspend () -> List<NextcloudNote>,
) {
    val path = normalizeNoteCategory(category)
    val prefix = "$path/"
    val targetSummaries = summaries.filter { note ->
        note.category == path || note.category.startsWith(prefix)
    }
    require(targetSummaries.isNotEmpty()) { "The note folder no longer exists." }
    val loaded = targetSummaries.map { summary -> loadNote(summary.id) }
    val prepared = preflightNoteFolderDelete(summaries, loaded, path)
    executePreparedNoteFolderMutation(prepared, deleteNote, reloadSummaries)
}

private fun prepareLoadedFolderNotes(
    targetSummaries: List<NextcloudNote>,
    loadedNotes: List<NextcloudNote>,
    source: String,
    sourcePrefix: String,
    destination: (NextcloudNote) -> String?,
): List<PreparedNoteFolderMutation> {
    require(loadedNotes.size == targetSummaries.size) { "Not every affected note was loaded for safety checks." }
    val loadedById = loadedNotes.associateBy(NextcloudNote::id)
    return targetSummaries.map { summary ->
        val note = loadedById[summary.id]
            ?: error("${summary.title} no longer exists, so no notes were changed.")
        check(note.category == source || note.category.startsWith(sourcePrefix)) {
            "${note.title} moved on the server, so no notes were changed."
        }
        check(!note.readOnly) { "${note.title} is read only, so no notes were changed." }
        check(!note.etag.isNullOrBlank()) {
            "${note.title} has no server version, so the operation cannot be performed safely."
        }
        check(note.content != null) {
            "${note.title} was not fully loaded, so the operation cannot be performed safely."
        }
        PreparedNoteFolderMutation(note, destination(note))
    }
}

private suspend fun executePreparedNoteFolderMutation(
    prepared: List<PreparedNoteFolderMutation>,
    mutate: suspend (PreparedNoteFolderMutation) -> Unit,
    reloadSummaries: suspend () -> List<NextcloudNote>,
) {
    var completed = 0
    prepared.forEach { mutation ->
        try {
            mutate(mutation)
            completed += 1
        } catch (failure: Throwable) {
            val refreshed = runCatching { reloadSummaries() }
            throw PartialNoteFolderMutationException(
                completedCount = completed,
                totalCount = prepared.size,
                refreshedSummaries = refreshed.getOrNull(),
                cause = failure,
                refreshFailure = refreshed.exceptionOrNull(),
            )
        }
    }
}
