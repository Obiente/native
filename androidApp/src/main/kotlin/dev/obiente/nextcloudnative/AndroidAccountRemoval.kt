package dev.obiente.nextcloudnative

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.NextcloudSession

internal val NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS: Int =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION

internal fun requireAndroidAccountRemovalWritebacksResolved(resolved: Boolean) {
    check(resolved) {
        "Finish or discard pending document changes before removing this account."
    }
}

internal suspend fun revokeAndroidSessionAfterWritebackPreflight(
    writebacksResolved: Boolean,
    revoke: suspend () -> Unit,
) {
    requireAndroidAccountRemovalWritebacksResolved(writebacksResolved)
    revoke()
}

internal enum class AndroidAccountDocumentGrantScope(val pathSegment: String) {
    Document("document"),
    Tree("tree"),
}

internal fun AndroidAccountDocumentGrantScope.uri(authority: String, rootId: String) = when (this) {
    AndroidAccountDocumentGrantScope.Document -> DocumentsContract.buildDocumentUri(authority, rootId)
    AndroidAccountDocumentGrantScope.Tree -> DocumentsContract.buildTreeDocumentUri(authority, rootId)
}

internal fun prepareAndroidAccountRemoval(context: Context, session: NextcloudSession) {
    requireAndroidAccountRemovalWritebacksResolved(androidDocumentPendingWritebacks(context, session).isEmpty())
    AndroidAccountDocumentGrantScope.entries.forEach { scope ->
        context.revokeUriPermission(
            scope.uri(nextcloudDocumentsAuthority(context.packageName), NextcloudDocumentIds.rootId(session)),
            NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS,
        )
    }
}
