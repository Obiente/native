package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences

internal class DesktopAccountCredentialPersistence(
    private val preferences: Preferences,
    private val secretStore: DesktopSecretStore,
    private val recordDiagnostic: (SupportDiagnosticEventDraft) -> Unit,
) {
    fun loadActiveSession(): NextcloudSession? {
        val read = readRegistry()
        if (read.registry == null) {
            return restoreLegacySession(read.encoded != null)
        }
        val active = read.registry.activeAccount ?: return null
        return loadSession(active.id)
    }

    fun listAccounts(): List<NextcloudAccountRecord> {
        val read = readRegistry()
        if (read.registry != null) return read.registry.accounts
        restoreLegacySession(read.encoded != null)
        return readRegistry().registry?.accounts.orEmpty()
    }

    fun activeAccountId(): NextcloudAccountId? {
        val read = readRegistry()
        if (read.registry != null) return read.registry.activeAccountId
        return restoreLegacySession(read.encoded != null)?.accountId
    }

    fun loadSession(accountId: NextcloudAccountId): NextcloudSession? {
        val registry = readRegistry().registry ?: return null
        val record = registry.accounts.firstOrNull { account -> account.id == accountId } ?: return null
        val secret = loadSecret(desktopAccountSecretReference(accountId))
        if (secret != null) return record.toSession(secret)

        val legacy = loadLegacySession() ?: return null
        if (legacy.accountId != accountId || legacy.accountRecord() != record) {
            recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_ACTIVE_MISMATCH", "account-credentials.restore")
            return null
        }
        migrateLegacyCredential(legacy)
        return legacy
    }

    fun saveSession(session: NextcloudSession) {
        val read = readRegistry()
        val registry = read.registry
            ?: restoreLegacySession(read.encoded != null)?.let { requireNotNull(readRegistry().registry) }
            ?: if (read.encoded == null) NextcloudAccountRegistry.Empty else throw invalidRegistryForMutation()
        val updatedRegistry = registry.upsertAndSelect(session.accountRecord())
        val encodedRegistry = prepareRegistry(updatedRegistry)
        saveSecret(session)
        persistRegistry(encodedRegistry)
        persistLegacyActiveMetadata(session.accountRecord())
    }

    fun selectAccount(accountId: NextcloudAccountId): NextcloudSession? {
        val registry = readRegistry().registry ?: return null
        val session = loadSession(accountId) ?: return null
        persistRegistry(requireNotNull(registry.select(accountId)))
        persistLegacyActiveMetadata(session.accountRecord())
        return session
    }

    fun removeAccount(accountId: NextcloudAccountId): Boolean {
        val registry = readRegistry().registry ?: return false
        val record = registry.accounts.firstOrNull { account -> account.id == accountId } ?: return false
        clearSecret(desktopAccountSecretReference(accountId))
        if (legacyMetadataMatches(record)) {
            clearSecret(desktopSessionSecretReference(record.serverUrl, record.loginName))
            preferences.remove(KEY_SERVER)
            preferences.remove(KEY_LOGIN)
        }
        persistRegistry(registry.remove(accountId))
        return true
    }

    private fun restoreLegacySession(malformedRegistry: Boolean): NextcloudSession? {
        val legacy = loadLegacySession() ?: run {
            if (malformedRegistry) {
                recordCredentialDiagnostic("ACCOUNT_REGISTRY_MALFORMED", "account-registry.restore")
            }
            return null
        }
        val restored = restoreNextcloudAccountRegistry(
            encoded = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null),
            legacySession = legacy,
        )
        restored.recoveryReason?.diagnosticCode?.let { code ->
            recordCredentialDiagnostic(code, "account-registry.restore")
        }
        if (!restored.needsPersistence) return legacy
        try {
            val encodedRegistry = prepareRegistry(restored.registry)
            saveSecret(legacy)
            persistRegistry(encodedRegistry)
        } catch (failure: Exception) {
            recordCredentialDiagnostic(
                code = "ACCOUNT_CREDENTIAL_STORE_MIGRATION_FAILED",
                operation = "account-credentials.migrate",
                failure = failure,
            )
            return legacy
        }
        persistLegacyActiveMetadata(legacy.accountRecord())
        clearLegacyCredentialAfterMigration(legacy)
        return legacy
    }

    private fun loadLegacySession(): NextcloudSession? {
        val server = preferences.get(KEY_SERVER, null) ?: return null
        val login = preferences.get(KEY_LOGIN, null) ?: return null
        val password = loadSecret(desktopSessionSecretReference(server, login)) ?: return null
        return NextcloudSession(server, login, password)
    }

    private fun migrateLegacyCredential(session: NextcloudSession) {
        saveSecret(session)
        clearLegacyCredentialAfterMigration(session)
    }

    private fun clearLegacyCredentialAfterMigration(session: NextcloudSession) {
        try {
            secretStore.clear(desktopSessionSecretReference(session.serverUrl, session.loginName))
        } catch (_: Exception) {
            recordCredentialDiagnostic(
                "ACCOUNT_CREDENTIAL_LEGACY_CLEANUP_FAILED",
                "account-credentials.migrate",
            )
        }
    }

    private fun saveSecret(session: NextcloudSession) {
        try {
            secretStore.save(
                reference = desktopAccountSecretReference(session.accountId),
                username = session.loginName,
                secret = session.appPassword.encodeToByteArray(),
            )
        } catch (failure: Exception) {
            recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_WRITE_FAILED", "account-credentials.persist")
            throw failure
        }
    }

    private fun loadSecret(reference: DesktopSecretReference): String? = try {
        secretStore.load(reference)
            ?.decodeToString()
            ?.takeIf(String::isNotBlank)
    } catch (_: Exception) {
        recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_READ_FAILED", "account-credentials.restore")
        null
    }

    private fun clearSecret(reference: DesktopSecretReference) {
        try {
            secretStore.clear(reference)
        } catch (failure: Exception) {
            recordCredentialDiagnostic("ACCOUNT_CREDENTIAL_STORE_CLEAR_FAILED", "account-credentials.remove")
            throw failure
        }
    }

    private fun readRegistry(): DesktopRegistryRead {
        val encoded = preferences.get(DESKTOP_ACCOUNT_REGISTRY_KEY, null)
        return DesktopRegistryRead(encoded, encoded?.let(::decodeNextcloudAccountRegistry))
    }

    private fun prepareRegistry(registry: NextcloudAccountRegistry): String =
        encodeNextcloudAccountRegistry(registry).also { encoded ->
            require(encoded.length <= Preferences.MAX_VALUE_LENGTH) {
                "The account registry exceeds the desktop preference value limit."
            }
        }

    private fun persistRegistry(registry: NextcloudAccountRegistry) {
        persistRegistry(prepareRegistry(registry))
    }

    private fun persistRegistry(encodedRegistry: String) {
        require(encodedRegistry.length <= Preferences.MAX_VALUE_LENGTH)
        preferences.put(DESKTOP_ACCOUNT_REGISTRY_KEY, encodedRegistry)
    }

    private fun persistLegacyActiveMetadata(record: NextcloudAccountRecord) {
        preferences.put(KEY_SERVER, record.serverUrl)
        preferences.put(KEY_LOGIN, record.loginName)
    }

    private fun legacyMetadataMatches(record: NextcloudAccountRecord): Boolean =
        preferences.get(KEY_SERVER, null) == record.serverUrl &&
            preferences.get(KEY_LOGIN, null) == record.loginName

    private fun invalidRegistryForMutation(): IllegalStateException {
        recordCredentialDiagnostic("ACCOUNT_REGISTRY_MALFORMED", "account-registry.persist")
        return IllegalStateException("The local account registry is invalid.")
    }

    private fun recordCredentialDiagnostic(
        code: String,
        operation: String,
        failure: Throwable? = null,
    ) {
        recordDiagnostic(
            SupportDiagnosticEventDraft(
                severity = SupportDiagnosticSeverity.Warning,
                component = SupportDiagnosticComponent.Authentication,
                operation = operation,
                outcome = "failed",
                code = code,
                exception = failure?.toNonSecretSupportDiagnosticExceptionDraft(),
            ),
        )
    }

    private data class DesktopRegistryRead(
        val encoded: String?,
        val registry: NextcloudAccountRegistry?,
    )

    private companion object {
        const val KEY_SERVER = "server"
        const val KEY_LOGIN = "login"
    }
}

private fun NextcloudAccountRecord.toSession(appPassword: String) = NextcloudSession(
    serverUrl = serverUrl,
    loginName = loginName,
    appPassword = appPassword,
)

internal fun desktopFileCacheAccountId(account: NextcloudAccountRecord): String =
    desktopFileCacheAccountId(account.toSession(appPassword = ""))

internal class DesktopAccountSessionPublication(
    private val registerPrivateValue: (String) -> Unit,
    private val publishAccountIdentity: (String) -> Unit,
) {
    fun register(session: NextcloudSession) {
        listOf(session.serverUrl, session.loginName, session.appPassword).forEach(registerPrivateValue)
    }

    fun publish(session: NextcloudSession) {
        register(session)
        publishAccountIdentity(desktopFileCacheAccountId(session))
    }
}

internal fun desktopAccountSelectionBlockedDiagnostic() = SupportDiagnosticEventDraft(
    severity = SupportDiagnosticSeverity.Warning,
    component = SupportDiagnosticComponent.Authentication,
    operation = "account.select",
    outcome = "blocked",
    code = "ACCOUNT_SELECTION_ACTIVE_RESOURCES",
)
