package dev.obiente.nextcloudnative

import java.net.URI
import java.net.URISyntaxException

internal const val NEXTCLOUD_DOCUMENTS_AUTHORITY_SUFFIX = ".documents"

/** Matches the manifest's `${applicationId}.documents` authority for every build variant. */
internal fun nextcloudDocumentsAuthority(applicationId: String): String {
    require(applicationId.isNotBlank()) { "The application ID must not be blank." }
    return applicationId + NEXTCLOUD_DOCUMENTS_AUTHORITY_SUFFIX
}

internal enum class AndroidPickerUriRejection(val message: String) {
    OwnDocumentsProvider("Files from nati.ve cannot be selected here."),
    Invalid("The selected file provider returned an invalid URI."),
}

internal class AndroidPickerUriRejectedException(
    val rejection: AndroidPickerUriRejection,
) : IllegalArgumentException(rejection.message)

internal fun requireExternalAndroidPickerUri(
    uri: String,
    applicationId: String,
): Unit {
    androidPickerUriRejection(uri, applicationId)?.let { rejection ->
        throw AndroidPickerUriRejectedException(rejection)
    }
}

internal fun androidPickerUriRejection(
    uri: String,
    applicationId: String,
): AndroidPickerUriRejection? {
    val parsed = try {
        URI(uri)
    } catch (_: URISyntaxException) {
        return AndroidPickerUriRejection.Invalid
    }
    if (!parsed.scheme.equals("content", ignoreCase = true)) {
        return AndroidPickerUriRejection.Invalid
    }
    val authority = parsed.authority?.takeIf(String::isNotBlank)
        ?: return AndroidPickerUriRejection.Invalid
    if (authority.any { character ->
            character.isWhitespace() || character.isISOControl() || character in ":/\\?#"
        }
    ) {
        return AndroidPickerUriRejection.Invalid
    }
    val userSeparator = authority.indexOf('@')
    val providerAuthority = when {
        userSeparator < 0 -> authority
        userSeparator != authority.lastIndexOf('@') -> return AndroidPickerUriRejection.Invalid
        userSeparator == 0 -> return AndroidPickerUriRejection.Invalid
        authority.take(userSeparator).any { character -> !character.isDigit() } ->
            return AndroidPickerUriRejection.Invalid
        else -> authority.substring(userSeparator + 1).takeIf(String::isNotBlank)
            ?: return AndroidPickerUriRejection.Invalid
    }
    return if (providerAuthority.equals(nextcloudDocumentsAuthority(applicationId), ignoreCase = true)) {
        AndroidPickerUriRejection.OwnDocumentsProvider
    } else {
        null
    }
}
