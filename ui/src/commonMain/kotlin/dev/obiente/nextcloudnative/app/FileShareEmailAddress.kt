package dev.obiente.nextcloudnative.app

/**
 * Result of checking a person-entered address for an email share (OCS share type 4).
 *
 * The check applies only to an address a person types. The Nextcloud Share API validates every
 * email share when it creates it; this check keeps an obviously incomplete or malformed typed
 * address from becoming a recipient and explains what is wrong while the person types. Sharee
 * search results are sent as the server returned them.
 */
sealed interface FileShareEmailAddressValidation {
    data class Valid(val address: String) : FileShareEmailAddressValidation
    data class Invalid(val reason: String) : FileShareEmailAddressValidation
}

/**
 * Accepts an unquoted `local@domain` address with a dotted host name.
 *
 * Quoted local parts, address literals, and single-label hosts are rejected because they are
 * almost always typing mistakes in a share dialog. Letters outside ASCII remain valid so
 * internationalized addresses are not refused before the server can decide.
 */
fun validateFileShareEmailAddress(input: String): FileShareEmailAddressValidation {
    val address = input.trim()
    val invalid = when {
        address.isEmpty() -> "Enter an email address."
        address.length > MAX_FILE_SHARE_EMAIL_ADDRESS_LENGTH -> "This email address is too long."
        '@' !in address -> "Enter a complete email address, such as name@example.com."
        address.count { it == '@' } > 1 -> "An email address can contain only one @."
        else -> null
    }
    if (invalid != null) return FileShareEmailAddressValidation.Invalid(invalid)
    val localPart = address.substringBefore('@')
    val domain = address.substringAfter('@')
    val reason = when {
        localPart.isEmpty() -> "Enter the name before the @."
        domain.isEmpty() -> "Enter the domain after the @."
        localPart.length > MAX_FILE_SHARE_EMAIL_LOCAL_PART_LENGTH ||
            domain.length > MAX_FILE_SHARE_EMAIL_DOMAIN_LENGTH -> "This email address is too long."
        !localPart.isValidEmailLocalPart() -> "The part before the @ contains characters an email address cannot use."
        '.' !in domain -> "Enter a complete domain after the @, such as example.com."
        !domain.isValidEmailDomain() -> "The domain after the @ is not valid."
        else -> null
    }
    return if (reason == null) {
        FileShareEmailAddressValidation.Valid(address)
    } else {
        FileShareEmailAddressValidation.Invalid(reason)
    }
}

/**
 * The email recipient a person typed in full, or null when the text is not a usable address.
 *
 * The recipient identity is the address itself, which is exactly the `shareWith` value the
 * Share API expects for an email share.
 */
fun typedFileShareEmailRecipient(input: String): FileShareRecipient? =
    when (val validation = validateFileShareEmailAddress(input)) {
        is FileShareEmailAddressValidation.Valid -> FileShareRecipient(
            id = validation.address,
            displayName = validation.address,
            target = FileShareTarget.Email,
            exact = true,
            origin = FileShareRecipientOrigin.Typed,
        )
        is FileShareEmailAddressValidation.Invalid -> null
    }

private fun String.isValidEmailLocalPart(): Boolean =
    first() != '.' && last() != '.' && ".." !in this &&
        all { it.isLetterOrDigit() || it == '.' || it in EMAIL_LOCAL_PART_SYMBOLS }

private fun String.isValidEmailDomain(): Boolean {
    val labels = split('.')
    return labels.size >= 2 && labels.all { label ->
        label.isNotEmpty() &&
            label.length <= MAX_FILE_SHARE_EMAIL_DOMAIN_LABEL_LENGTH &&
            label.first() != '-' && label.last() != '-' &&
            label.all { it.isLetterOrDigit() || it == '-' }
    } && !labels.last().all(Char::isDigit)
}

private const val EMAIL_LOCAL_PART_SYMBOLS = "!#$%&'*+-/=?^_`{|}~"
private const val MAX_FILE_SHARE_EMAIL_ADDRESS_LENGTH = 254
private const val MAX_FILE_SHARE_EMAIL_LOCAL_PART_LENGTH = 64
private const val MAX_FILE_SHARE_EMAIL_DOMAIN_LENGTH = 253
private const val MAX_FILE_SHARE_EMAIL_DOMAIN_LABEL_LENGTH = 63
