package dev.obiente.nextcloudnative.app

data class NextcloudSession(
    val serverUrl: String,
    val loginName: String,
    val appPassword: String,
) {
    /** Opaque, credential-free identity for account-scoped process state. */
    val accountId: NextcloudAccountId
        get() = deriveNextcloudAccountId(serverUrl, loginName)

    override fun toString(): String =
        "NextcloudSession(serverUrl=<redacted>, loginName=<redacted>, appPassword=<redacted>)"
}
