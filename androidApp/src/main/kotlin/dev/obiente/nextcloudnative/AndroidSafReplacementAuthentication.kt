package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.SyncEntryKind
import kotlinx.coroutines.CancellationException

internal fun collectAndroidSafReplacementEvidence(
    document: AndroidLocalSyncDocument,
    shouldContinue: () -> Boolean,
    maximumDepth: Int,
    maximumEntries: Int,
    listChildren: (AndroidLocalSyncDocument) -> List<AndroidLocalSyncDocument>,
    contentHash: (AndroidLocalSyncDocument) -> String,
): List<AndroidSafReplacementEvidence> {
    requireAndroidSafReplacementTraversalContinuation(shouldContinue)
    fun evidence(item: AndroidLocalSyncDocument): AndroidSafReplacementEvidence =
        item.androidSafReplacementEvidence(
            contentHash = if (item.entry.kind == SyncEntryKind.File) contentHash(item) else null,
        )

    val result = arrayListOf(evidence(document))
    val pending = ArrayDeque<AndroidLocalSyncDocument>()
    if (document.entry.kind == SyncEntryKind.Directory) pending += document
    while (pending.isNotEmpty()) {
        requireAndroidSafReplacementTraversalContinuation(shouldContinue)
        val parent = pending.removeFirst()
        require(parent.entry.relativePath.count { it == '/' } < maximumDepth) {
            "The local replacement folder is nested too deeply."
        }
        val children = listAndroidSafReplacementChildrenAfterCancellationCheck(shouldContinue) {
            listChildren(parent)
        }
        children.forEach { child ->
            requireAndroidSafReplacementTraversalContinuation(shouldContinue)
            require(result.size < maximumEntries) {
                "The local replacement folder contains too many entries."
            }
            result += evidence(child)
            if (child.entry.kind == SyncEntryKind.Directory) pending += child
        }
    }
    return result.sortedBy { it.entry.relativePath }
}

internal fun requireAndroidSafReplacementTraversalContinuation(shouldContinue: () -> Boolean) {
    if (!shouldContinue() || Thread.currentThread().isInterrupted) {
        throw CancellationException("Local replacement verification cancelled.")
    }
}

internal fun <T> listAndroidSafReplacementChildrenAfterCancellationCheck(
    shouldContinue: () -> Boolean,
    listChildren: () -> List<T>,
): List<T> {
    requireAndroidSafReplacementTraversalContinuation(shouldContinue)
    return listChildren().also {
        requireAndroidSafReplacementTraversalContinuation(shouldContinue)
    }
}
