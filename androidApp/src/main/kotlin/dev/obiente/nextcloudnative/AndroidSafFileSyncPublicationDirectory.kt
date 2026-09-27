package dev.obiente.nextcloudnative

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.OutputStream

internal class AndroidSafFileSyncPublicationDirectory(
    private val resolver: ContentResolver,
    private val parentUri: Uri,
    private val documents: () -> List<AndroidSafPublicationDocument<Uri>>,
    private val createDirectory: (String) -> Uri?,
    private val writeDocument: (Uri, (OutputStream) -> Unit) -> Unit,
    private val providerRecovery: AndroidDocumentsProviderRecoveryAccess,
) : AndroidSafPublicationDirectory<Uri> {
    override fun documents(): List<AndroidSafPublicationDocument<Uri>> = documents.invoke()

    override fun createFile(displayName: String): Uri = requireNotNull(
        DocumentsContract.createDocument(resolver, parentUri, "application/octet-stream", displayName),
    ) { "A staged local file could not be created." }

    override fun createDirectory(displayName: String): Uri = requireNotNull(createDirectory.invoke(displayName)) {
        "A staged local folder could not be created."
    }

    override fun writeFile(document: Uri, write: (OutputStream) -> Unit) = writeDocument(document, write)

    override fun rename(document: Uri, displayName: String): Uri? =
        providerRecovery.run(
            document,
            AndroidDocumentsProviderRecoveryOperation.Rename,
        ) { recoveryUri ->
            providerRecovery.normalizeResult(
                document,
                DocumentsContract.renameDocument(resolver, recoveryUri, displayName),
            )
        }

    override fun delete(document: Uri): Boolean =
        providerRecovery.run(
            document,
            AndroidDocumentsProviderRecoveryOperation.Delete,
        ) { recoveryUri ->
            DocumentsContract.deleteDocument(resolver, recoveryUri)
        }
}
