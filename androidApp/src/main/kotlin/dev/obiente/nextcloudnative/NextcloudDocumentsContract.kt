package dev.obiente.nextcloudnative

internal const val NEXTCLOUD_DOCUMENTS_AUTHORITY_SUFFIX = ".documents"

/** Matches the manifest's `${applicationId}.documents` authority for every build variant. */
internal fun nextcloudDocumentsAuthority(applicationId: String): String {
    require(applicationId.isNotBlank()) { "The application ID must not be blank." }
    return applicationId + NEXTCLOUD_DOCUMENTS_AUTHORITY_SUFFIX
}
