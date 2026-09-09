package dev.obiente.nextcloudnative.app

/**
 * Credential-free process identity for one Nextcloud account.
 *
 * The value is a one-way digest of the normalized server address and login name. Callers keep the
 * type intact so account-scoped state cannot accidentally use a path, username, or password as its
 * owner. The persistent account registry stores the same identity, so account-scoped state keeps a
 * stable owner across process restarts.
 */
@JvmInline
value class NextcloudAccountId internal constructor(val storageKey: String) {
    init {
        require(storageKey.length == SHA_256_HEX_LENGTH && storageKey.all(Char::isLowerHexDigit)) {
            "The local account identity must be a canonical SHA-256 digest."
        }
    }

    override fun toString(): String = "NextcloudAccountId(<redacted>)"
}

internal expect fun deriveNextcloudAccountId(serverUrl: String, loginName: String): NextcloudAccountId

private fun Char.isLowerHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

private const val SHA_256_HEX_LENGTH = 64
