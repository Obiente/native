package dev.obiente.nextcloudnative.app

interface NextcloudAccountCredentialServices {
    fun loadSession(): NextcloudSession?

    suspend fun saveSession(session: NextcloudSession)

    suspend fun clearSession()

    /** Lists credential-free local account records without loading their secrets. */
    fun listAccounts(): List<NextcloudAccountRecord> = loadSession()?.let { session ->
        listOf(session.accountRecord())
    }.orEmpty()

    /** Returns the selected local account identity, or null when no account is selected. */
    fun activeAccountId(): NextcloudAccountId? = loadSession()?.accountId

    /** Loads one account's credentials without changing the active selection. */
    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? =
        loadSession()?.takeIf { session -> session.accountId == accountId }

    /** Selects a stored account and returns its session after the selection is durable. */
    suspend fun selectAccount(accountId: NextcloudAccountId): NextcloudSession? = loadSession(accountId)

    /** Removes one stored account. The compatibility default supports only the active account. */
    suspend fun removeAccount(accountId: NextcloudAccountId): Boolean {
        if (activeAccountId() != accountId) return false
        clearSession()
        return true
    }
}
