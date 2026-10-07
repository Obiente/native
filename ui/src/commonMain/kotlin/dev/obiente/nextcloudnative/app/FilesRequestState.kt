package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reject even a non-cooperative service's completion after the caller is cancelled. */
internal suspend fun <T> filesRequest(block: suspend () -> T): Result<T> {
    val result = runCatchingPreservingCancellation(block)
    currentCoroutineContext().ensureActive()
    return result
}

/** Main-thread owner of optimistic flags. A failed item never restores an entire listing. */
internal class FilesFavoriteMutations {
    private data class Change(val favorite: Boolean, var previous: Boolean, val revision: Long, var pending: Boolean)
    private val changes = mutableMapOf<String, Change>()
    var revision: Long = 0
        private set

    fun begin(file: NextcloudFile, favorite: Boolean): Boolean {
        if (changes[file.path]?.pending == true) return false
        changes[file.path] = Change(favorite, file.favorite, ++revision, true)
        return true
    }

    fun refreshed(files: List<NextcloudFile>, readRevision: Long): List<NextcloudFile> = files.map { file ->
        val change = changes[file.path] ?: return@map file
        if (change.pending) change.previous = file.favorite
        if (change.pending || change.revision > readRevision) file.copy(favorite = change.favorite) else file
    }

    fun succeeded(path: String) {
        changes[path]?.let { changes[path] = it.copy(revision = ++revision, pending = false) }
    }

    fun failed(path: String): Boolean? = changes.remove(path)?.previous
}

/** A closed/replaced dialog cannot accept an earlier load, including reopening the same file. */
internal class FileShareLoadIdentity {
    private var generation = 0L
    fun replace(): Long = ++generation
    fun accepts(request: Long): Boolean = request == generation
}

internal fun List<NextcloudFile>?.withFavorite(path: String, favorite: Boolean): List<NextcloudFile>? =
    this?.map { if (it.path == path) it.copy(favorite = favorite) else it }
