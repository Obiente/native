package dev.obiente.nextcloudnative

import android.net.Uri
import android.os.Binder
import android.os.Process
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.FileNotFoundException

internal enum class AndroidDocumentsProviderRecoveryOperation {
    QueryDocument,
    QueryChildren,
    OpenRead,
    OpenWrite,
    Create,
    Rename,
    Delete,
    Move,
}

internal data class AndroidDocumentsProviderResolvedSession(
    val session: NextcloudSession,
    val recoveryAuthorized: Boolean,
)

private class AndroidDocumentsProviderRecoveryPermit(
    val session: NextcloudSession,
    val documentId: String,
    val operation: AndroidDocumentsProviderRecoveryOperation,
    var consumed: Boolean = false,
)

private val ANDROID_DOCUMENTS_PROVIDER_RECOVERY_PERMITS =
    ThreadLocal<MutableList<AndroidDocumentsProviderRecoveryPermit>>()

internal class AndroidDocumentsProviderRecoveryAccess(
    private val session: NextcloudSession?,
) {
    fun <Result> run(
        document: Uri,
        operation: AndroidDocumentsProviderRecoveryOperation,
        action: (Uri) -> Result,
    ): Result {
        val documentId = DocumentsContract.getDocumentId(document)
        val ordinaryUri = androidDocumentsProviderRecoveryUri(
            documentId = documentId,
            operation = operation,
            buildDocumentUri = { document },
            buildChildDocumentsUri = { id -> DocumentsContract.buildChildDocumentsUriUsingTree(document, id) },
        )
        val bound = session ?: return action(ordinaryUri)
        val authority = requireNotNull(document.authority) { "The recovery document authority is missing." }
        val recoveryUri = androidDocumentsProviderRecoveryUri(
            documentId = documentId,
            operation = operation,
            buildDocumentUri = { id -> DocumentsContract.buildDocumentUri(authority, id) },
            buildChildDocumentsUri = { id -> DocumentsContract.buildChildDocumentsUri(authority, id) },
        )
        return withAndroidDocumentsProviderRecoveryPermit(bound, documentId, operation) {
            action(recoveryUri)
        }
    }

    fun normalizeResult(document: Uri, result: Uri?): Uri? =
        normalizeAndroidDocumentsProviderRecoveryResult(
            recoveryEnabled = session != null,
            document = document,
            result = result,
            documentIdOf = DocumentsContract::getDocumentId,
            buildTreeDocumentUri = DocumentsContract::buildDocumentUriUsingTree,
        )
}

internal fun <Uri> androidDocumentsProviderRecoveryUri(
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    buildDocumentUri: (String) -> Uri,
    buildChildDocumentsUri: (String) -> Uri,
): Uri = when (operation) {
    AndroidDocumentsProviderRecoveryOperation.QueryChildren -> buildChildDocumentsUri(documentId)
    AndroidDocumentsProviderRecoveryOperation.OpenRead,
    AndroidDocumentsProviderRecoveryOperation.Rename,
    AndroidDocumentsProviderRecoveryOperation.Delete,
    -> buildDocumentUri(documentId)
    AndroidDocumentsProviderRecoveryOperation.QueryDocument,
    AndroidDocumentsProviderRecoveryOperation.OpenWrite,
    AndroidDocumentsProviderRecoveryOperation.Create,
    AndroidDocumentsProviderRecoveryOperation.Move,
    -> error("The document operation is not permitted for recovery.")
}

internal fun <Uri> normalizeAndroidDocumentsProviderRecoveryResult(
    recoveryEnabled: Boolean,
    document: Uri,
    result: Uri?,
    documentIdOf: (Uri) -> String,
    buildTreeDocumentUri: (Uri, String) -> Uri,
): Uri? = result?.let { renamed ->
    if (recoveryEnabled) buildTreeDocumentUri(document, documentIdOf(renamed)) else renamed
}

/**
 * Grants one exact provider operation to synchronous self-provider recovery. The provider must stay
 * in this app process because the one-shot authority intentionally cannot cross thread boundaries.
 */
internal fun <Result> withAndroidDocumentsProviderRecoveryPermit(
    session: NextcloudSession,
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    action: () -> Result,
): Result {
    NextcloudDocumentIds.requireForSession(documentId, session)
    requireAndroidDocumentsProviderRecoveryOperation(operation)
    val permit = AndroidDocumentsProviderRecoveryPermit(session, documentId, operation)
    val permits = ANDROID_DOCUMENTS_PROVIDER_RECOVERY_PERMITS.get()
        ?: mutableListOf<AndroidDocumentsProviderRecoveryPermit>().also { created ->
            ANDROID_DOCUMENTS_PROVIDER_RECOVERY_PERMITS.set(created)
        }
    permits += permit
    return try {
        action()
    } finally {
        check(permits.remove(permit)) { "The document recovery permit was already cleared." }
        if (permits.isEmpty()) ANDROID_DOCUMENTS_PROVIDER_RECOVERY_PERMITS.remove()
    }
}

internal fun resolveAndroidDocumentsProviderSession(
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    allowRecoveryPermit: Boolean,
    loadActiveSession: () -> NextcloudSession?,
): AndroidDocumentsProviderResolvedSession? {
    val accountIdentity = runCatching { NextcloudDocumentIds.parse(documentId).accountKey }.getOrNull()
        ?: return null
    if (allowRecoveryPermit) {
        ANDROID_DOCUMENTS_PROVIDER_RECOVERY_PERMITS.get()
            .orEmpty()
            .asReversed()
            .firstOrNull { permit ->
                !permit.consumed && permit.documentId == documentId && permit.operation == operation
            }
            ?.let { permit ->
                permit.consumed = true
                return AndroidDocumentsProviderResolvedSession(permit.session, recoveryAuthorized = true)
            }
    }
    loadActiveSession()?.takeIf { session ->
        NextcloudDocumentIds.accountKey(session) == accountIdentity
    }?.let { session -> return AndroidDocumentsProviderResolvedSession(session, recoveryAuthorized = false) }
    return null
}

private fun requireAndroidDocumentsProviderRecoveryOperation(
    operation: AndroidDocumentsProviderRecoveryOperation,
) {
    require(
        operation == AndroidDocumentsProviderRecoveryOperation.QueryChildren ||
            operation == AndroidDocumentsProviderRecoveryOperation.OpenRead ||
            operation == AndroidDocumentsProviderRecoveryOperation.Rename ||
            operation == AndroidDocumentsProviderRecoveryOperation.Delete,
    ) { "The document operation is not permitted for recovery." }
}

internal fun resolveAndroidDocumentsProviderSessionForCaller(
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    loadActiveSession: () -> NextcloudSession?,
): AndroidDocumentsProviderResolvedSession? = resolveAndroidDocumentsProviderSession(
    documentId = documentId,
    operation = operation,
    allowRecoveryPermit = Binder.getCallingUid() == Process.myUid(),
    loadActiveSession = loadActiveSession,
)

internal fun requireAndroidDocumentsProviderSession(
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    loadActiveSession: () -> NextcloudSession?,
): AndroidDocumentsProviderResolvedSession = resolveAndroidDocumentsProviderSessionForCaller(
    documentId,
    operation,
    loadActiveSession,
) ?: throw FileNotFoundException("This Nextcloud document is not available for the active account.")

internal fun requireAndroidDocumentsProviderCallSession(
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    loadActiveSession: () -> NextcloudSession?,
): AndroidDocumentsProviderResolvedSession =
    if (AndroidExternalFileHandoffRegistry.isHandoffDocumentId(documentId)) {
        val session = loadActiveSession() ?: throw FileNotFoundException("Sign in to nati.ve to browse files.")
        AndroidDocumentsProviderResolvedSession(session, recoveryAuthorized = false)
    } else {
        requireAndroidDocumentsProviderSession(documentId, operation, loadActiveSession)
    }

internal fun <Result> withAndroidDocumentsProviderMutation(
    documentId: String,
    operation: AndroidDocumentsProviderRecoveryOperation,
    loadActiveSession: () -> NextcloudSession?,
    action: (NextcloudSession) -> Result,
): Result {
    val resolved = requireAndroidDocumentsProviderSession(documentId, operation, loadActiveSession)
    return withAndroidDocumentMutation(
        resolved.session,
        { if (resolved.recoveryAuthorized) resolved.session else loadActiveSession() },
        action,
    )
}

internal fun requireAndroidDocumentsProviderQuerySession(
    documentId: String,
    loadActiveSession: () -> NextcloudSession?,
): NextcloudSession = requireAndroidDocumentsProviderCallSession(
    documentId,
    AndroidDocumentsProviderRecoveryOperation.QueryDocument,
    loadActiveSession,
).session

internal fun requireAndroidDocumentsProviderChildrenSession(
    documentId: String,
    loadActiveSession: () -> NextcloudSession?,
): NextcloudSession = requireAndroidDocumentsProviderSession(
    documentId,
    AndroidDocumentsProviderRecoveryOperation.QueryChildren,
    loadActiveSession,
).session

internal fun requireAndroidDocumentsProviderOpenSession(
    documentId: String,
    mode: String,
    loadActiveSession: () -> NextcloudSession?,
): NextcloudSession = requireAndroidDocumentsProviderCallSession(
    documentId,
    if (mode == "r") AndroidDocumentsProviderRecoveryOperation.OpenRead else
        AndroidDocumentsProviderRecoveryOperation.OpenWrite,
    loadActiveSession,
).session

internal fun <Result> withAndroidDocumentsProviderCreate(
    documentId: String,
    loadActiveSession: () -> NextcloudSession?,
    action: (NextcloudSession) -> Result,
): Result = withAndroidDocumentsProviderMutation(
    documentId, AndroidDocumentsProviderRecoveryOperation.Create, loadActiveSession, action,
)

internal fun <Result> withAndroidDocumentsProviderRename(
    documentId: String,
    loadActiveSession: () -> NextcloudSession?,
    action: (NextcloudSession) -> Result,
): Result = withAndroidDocumentsProviderMutation(
    documentId, AndroidDocumentsProviderRecoveryOperation.Rename, loadActiveSession, action,
)

internal fun <Result> withAndroidDocumentsProviderDelete(
    documentId: String,
    loadActiveSession: () -> NextcloudSession?,
    action: (NextcloudSession) -> Result,
): Result = withAndroidDocumentsProviderMutation(
    documentId, AndroidDocumentsProviderRecoveryOperation.Delete, loadActiveSession, action,
)

internal fun <Result> withAndroidDocumentsProviderMove(
    documentId: String,
    loadActiveSession: () -> NextcloudSession?,
    action: (NextcloudSession) -> Result,
): Result = withAndroidDocumentsProviderMutation(
    documentId, AndroidDocumentsProviderRecoveryOperation.Move, loadActiveSession, action,
)
