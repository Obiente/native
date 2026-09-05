package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Single-flight bridge from common suspend APIs to Android's native document-tree picker.
 *
 * Only the selected tree receives a durable read/write grant. The sync engine never needs broad
 * storage access for SAF-backed pairs.
 */
internal class AndroidFileSyncRootPicker(
    private val context: Context,
    private val capabilities: AndroidFileSyncCapabilityLifecycle = AndroidFileSyncCapabilityLifecycle(context),
) {
    private var launcher: ActivityResultLauncher<Uri?>? = null
    private var pending: CancellableContinuation<FileSyncLocalRoot?>? = null

    fun attach(launcher: ActivityResultLauncher<Uri?>) {
        check(this.launcher == null) { "The sync-root picker is already attached." }
        this.launcher = launcher
    }

    suspend fun choose(initialRootHint: String? = null): FileSyncLocalRoot? =
        suspendCancellableCoroutine { continuation ->
        check(pending == null) { "A folder chooser is already open." }
        val activeLauncher = checkNotNull(launcher) { "The folder chooser is not attached." }
        pending = continuation
        continuation.invokeOnCancellation {
            if (pending === continuation) pending = null
        }
        activeLauncher.launch(initialRootHint?.let(Uri::parse))
    }

    fun complete(uri: Uri?) {
        val continuation = pending ?: return
        pending = null
        if (!continuation.isActive) return
        if (uri == null) {
            continuation.resume(null)
            return
        }
        val result = runCatching {
            capabilities.acquire(uri.toString(), queryDisplayName(context.contentResolver, uri))
        }
        result.onSuccess { localRoot ->
            resumeFileSyncRootSelection(continuation, localRoot, capabilities::abandonSelection)
        }
            .onFailure { continuation.cancel(it) }
    }

    fun abandon(localRootId: String) {
        runCatching { capabilities.abandonSelection(localRootId) }
    }

    private fun queryDisplayName(resolver: ContentResolver, treeUri: Uri): String {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return resolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.trim().orEmpty() else ""
        }.orEmpty().ifBlank { "Selected folder" }
    }
}

internal fun resumeFileSyncRootSelection(
    continuation: CancellableContinuation<FileSyncLocalRoot?>,
    localRoot: FileSyncLocalRoot,
    abandon: (String) -> Unit,
) {
    continuation.resume(localRoot) { _, undeliveredRoot, _ ->
        runCatching { abandon(undeliveredRoot.localRootId) }
    }
}
