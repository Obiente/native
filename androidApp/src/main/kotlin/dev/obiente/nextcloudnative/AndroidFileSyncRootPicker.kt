package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Single-flight bridge from common suspend APIs to Android's native document-tree picker.
 *
 * Only the selected tree receives a durable read/write grant. The sync engine never needs broad
 * storage access for SAF-backed pairs.
 */
internal class AndroidFileSyncRootPicker(
    context: Context,
    private val capabilities: AndroidFileSyncCapabilityLifecycle = AndroidFileSyncCapabilityLifecycle(context),
) {
    private val acquisitionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext
    private var launcher: ActivityResultLauncher<Uri?>? = null
    private val requests = AndroidFileSyncPickerRequestSlot<PendingFileSyncRootSelection>()

    fun attach(launcher: ActivityResultLauncher<Uri?>) {
        check(this.launcher == null) { "The sync-root picker is already attached." }
        this.launcher = launcher
    }

    suspend fun choose(
        expectedSession: NextcloudSession,
        resolveSession: suspend () -> NextcloudSession?,
        initialRootHint: String? = null,
    ): FileSyncLocalRoot? =
        suspendCancellableCoroutine { continuation ->
        val activeLauncher = checkNotNull(launcher) { "The folder chooser is not attached." }
        val accountId = AndroidFileSyncCapabilityAccountId(NextcloudDocumentIds.accountKey(expectedSession))
        val selection = PendingFileSyncRootSelection(
            expectedSession, resolveSession, accountId,
            AndroidFileSyncRootAcquisitionGenerations.capture(accountId, expectedSession.accountId.storageKey), continuation,
        )
        requests.begin(selection)
        try {
            activeLauncher.launch(initialRootHint?.let(Uri::parse))
        } catch (failure: Exception) {
            requests.launchFailed(selection)
            throw failure
        }
    }

    fun complete(uri: Uri?) {
        val selection = requests.complete() ?: return
        val continuation = selection.continuation
        if (!continuation.isActive) return
        if (uri == null) {
            continuation.resume(null)
            return
        }
        acquireFileSyncRootForDelivery(
            scope = acquisitionScope,
            continuation = continuation,
            acquire = {
                requireExternalAndroidPickerUri(uri.toString(), appContext.packageName)
                acquireCurrentAccountFileSyncRoot(
                    expectedSession = selection.expectedSession,
                    resolveSession = selection.resolveSession,
                    isRequestActive = { continuation.isActive },
                    queryDisplayName = { queryDisplayName(appContext.contentResolver, uri) },
                ) { displayName ->
                    capabilities.acquire(selection.accountId, uri.toString(), displayName, selection.generation)
                }
            },
            abandon = capabilities::abandonSelection,
        )
    }

    fun abandon(root: FileSyncLocalRoot): Boolean {
        if (root.savedStateId == null && !root.localRootId.startsWith("content://")) return true
        val reference = root.savedStateId ?: root.localRootId
        capabilities.requestSelectionAbandonment(reference)
        acquisitionScope.launch(NonCancellable) {
            reclaimUndeliveredFileSyncRoot(reference, capabilities::abandonSelection)
        }
        return true
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

internal fun <Value> resumeAndroidFileSyncPickerContinuation(
    continuation: CancellableContinuation<Value>,
    result: Result<Value>,
) {
    result.fold(continuation::resume, continuation::resumeWithException)
}

internal fun abandonAndroidFileSyncRoot(
    localRootId: String,
    abandonContentRoot: (String) -> Boolean,
): Boolean = if (localRootId.startsWith("content://")) {
    runCatching { abandonContentRoot(localRootId) }.getOrDefault(false)
} else {
    true
}

private data class PendingFileSyncRootSelection(
    val expectedSession: NextcloudSession,
    val resolveSession: suspend () -> NextcloudSession?,
    val accountId: AndroidFileSyncCapabilityAccountId,
    val generation: AndroidFileSyncRootAcquisitionGeneration,
    val continuation: CancellableContinuation<FileSyncLocalRoot?>,
)

internal fun resumeFileSyncRootSelection(
    continuation: CancellableContinuation<FileSyncLocalRoot?>,
    localRoot: FileSyncLocalRoot,
    abandon: (String) -> Unit,
) {
    continuation.resume(localRoot) { _, undeliveredRoot, _ ->
        runCatching { abandon(undeliveredRoot.localRootId) }
    }
}
