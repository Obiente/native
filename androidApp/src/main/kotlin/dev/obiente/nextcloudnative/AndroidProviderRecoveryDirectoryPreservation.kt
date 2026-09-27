package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import java.util.UUID

/** A collection ETag cannot authorize recursive destruction of concurrently added descendants. */
internal fun retireAndroidProviderRecoveryDocument(
    documentId: String,
    file: NextcloudFile,
    delete: (String) -> Unit,
    preserve: (String, String) -> Unit,
    uniqueSuffix: () -> String = { UUID.randomUUID().toString() },
): String? {
    val etag = androidProviderRecoveryMutationEtag(documentId,
        requireNotNull(file.etag?.takeIf(String::isNotBlank)) { "Recovery requires a remote generation." }, file.isDirectory)
    if (!file.isDirectory || !hasConsumedAndroidProviderRecovery(documentId, AndroidDocumentsProviderRecoveryOperation.Delete)) {
        delete(etag)
        return null
    }
    return preserveAndroidRecoveryDirectory(file.path, etag, uniqueSuffix, preserve)
}

internal fun preserveAndroidRecoveryDirectory(
    source: String,
    etag: String,
    uniqueSuffix: () -> String,
    moveWithoutOverwrite: (String, String) -> Unit,
): String {
    val suffix = uniqueSuffix()
    require(suffix.matches(Regex("[a-zA-Z0-9-]{1,64}")))
    val recoveryName = "Recovered folder - $suffix"
    val parent = NextcloudDocumentIds.parentPath(source)
    val destination = listOf(parent, recoveryName).filter(String::isNotEmpty).joinToString("/")
    require(destination != source)
    moveWithoutOverwrite(destination, etag)
    return destination
}
