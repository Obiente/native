package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Credential-free metadata for one locally known account. */
data class NextcloudAccountRecord(
    val id: NextcloudAccountId,
    val serverUrl: String,
    val loginName: String,
) {
    init {
        require(serverUrl.isNotBlank() && serverUrl.length <= MAX_ACCOUNT_SERVER_URL_LENGTH)
        require(loginName.isNotBlank() && loginName.length <= MAX_ACCOUNT_LOGIN_NAME_LENGTH)
        require(id == deriveNextcloudAccountId(serverUrl, loginName)) {
            "The local account identity does not match its canonical server and login."
        }
    }
}

/**
 * Version-independent account registry state.
 *
 * Credentials remain in the platform secret store. An active account is always one of the stored
 * records, and removing it leaves selection empty rather than silently choosing another account.
 */
data class NextcloudAccountRegistry(
    val accounts: List<NextcloudAccountRecord>,
    val activeAccountId: NextcloudAccountId?,
) {
    init {
        require(accounts.size <= MAX_LOCAL_ACCOUNTS) { "The local account registry is too large." }
        require(accounts.map(NextcloudAccountRecord::id).distinct().size == accounts.size) {
            "The local account registry contains duplicate identities."
        }
        require(activeAccountId == null || accounts.any { account -> account.id == activeAccountId }) {
            "The active account is not present in the local registry."
        }
    }

    val activeAccount: NextcloudAccountRecord?
        get() = accounts.firstOrNull { account -> account.id == activeAccountId }

    fun upsertAndSelect(record: NextcloudAccountRecord): NextcloudAccountRegistry {
        val replaced = accounts.map { account -> if (account.id == record.id) record else account }
        return copy(
            accounts = if (replaced.any { account -> account.id == record.id }) replaced else replaced + record,
            activeAccountId = record.id,
        )
    }

    fun select(id: NextcloudAccountId): NextcloudAccountRegistry? =
        takeIf { registry -> registry.accounts.any { account -> account.id == id } }
            ?.copy(activeAccountId = id)

    fun remove(id: NextcloudAccountId): NextcloudAccountRegistry = copy(
        accounts = accounts.filterNot { account -> account.id == id },
        activeAccountId = activeAccountId?.takeUnless { active -> active == id },
    )

    companion object {
        val Empty = NextcloudAccountRegistry(emptyList(), null)
    }
}

enum class NextcloudAccountRegistrySource {
    Empty,
    Persisted,
    LegacySession,
}

enum class NextcloudAccountRegistryRecoveryReason(val diagnosticCode: String) {
    MalformedRegistry("ACCOUNT_REGISTRY_MALFORMED"),
    ActiveSessionMismatch("ACCOUNT_REGISTRY_ACTIVE_SESSION_MISMATCH"),
}

data class RestoredNextcloudAccountRegistry(
    val registry: NextcloudAccountRegistry,
    val source: NextcloudAccountRegistrySource,
    val recoveryReason: NextcloudAccountRegistryRecoveryReason? = null,
) {
    val needsPersistence: Boolean
        get() = source == NextcloudAccountRegistrySource.LegacySession
}

fun NextcloudSession.accountRecord(): NextcloudAccountRecord = NextcloudAccountRecord(
    id = accountId,
    serverUrl = serverUrl,
    loginName = loginName,
)

fun singleAccountRegistry(session: NextcloudSession): NextcloudAccountRegistry =
    NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord())

fun restoreNextcloudAccountRegistry(
    encoded: String?,
    legacySession: NextcloudSession?,
): RestoredNextcloudAccountRegistry {
    val persisted = encoded?.let(::decodeNextcloudAccountRegistry)
    if (persisted != null) {
        val legacyAccount = legacySession?.accountRecord()
        if (legacyAccount == null) {
            return RestoredNextcloudAccountRegistry(persisted, NextcloudAccountRegistrySource.Persisted)
        }
        if (persisted.activeAccountId == legacyAccount.id) {
            val refreshed = persisted.upsertAndSelect(legacyAccount)
            return RestoredNextcloudAccountRegistry(
                registry = refreshed,
                source = if (refreshed == persisted) {
                    NextcloudAccountRegistrySource.Persisted
                } else {
                    NextcloudAccountRegistrySource.LegacySession
                },
            )
        }
        return RestoredNextcloudAccountRegistry(
            registry = persisted.upsertAndSelect(legacyAccount),
            source = NextcloudAccountRegistrySource.LegacySession,
            recoveryReason = NextcloudAccountRegistryRecoveryReason.ActiveSessionMismatch,
        )
    }
    if (legacySession != null) {
        return RestoredNextcloudAccountRegistry(
            registry = singleAccountRegistry(legacySession),
            source = NextcloudAccountRegistrySource.LegacySession,
            recoveryReason = encoded?.let { NextcloudAccountRegistryRecoveryReason.MalformedRegistry },
        )
    }
    return RestoredNextcloudAccountRegistry(
        registry = NextcloudAccountRegistry.Empty,
        source = NextcloudAccountRegistrySource.Empty,
        recoveryReason = encoded?.let { NextcloudAccountRegistryRecoveryReason.MalformedRegistry },
    )
}

fun encodeNextcloudAccountRegistry(registry: NextcloudAccountRegistry): String =
    accountRegistryJson.encodeToString(
        PersistedNextcloudAccountRegistry(
            version = ACCOUNT_REGISTRY_VERSION,
            activeAccountId = registry.activeAccountId?.storageKey,
            accounts = registry.accounts.sortedBy { account -> account.id.storageKey }.map { account ->
                PersistedNextcloudAccountRecord(
                    id = account.id.storageKey,
                    serverUrl = account.serverUrl,
                    loginName = account.loginName,
                )
            },
        ),
    ).also { encoded ->
        require(encoded.encodeToByteArray().size <= MAX_ACCOUNT_REGISTRY_BYTES) {
            "The local account registry is too large to persist."
        }
    }

fun decodeNextcloudAccountRegistry(encoded: String): NextcloudAccountRegistry? {
    if (encoded.encodeToByteArray().size > MAX_ACCOUNT_REGISTRY_BYTES) return null
    return runCatching {
        val persisted = accountRegistryJson.decodeFromString<PersistedNextcloudAccountRegistry>(encoded)
        require(persisted.version == ACCOUNT_REGISTRY_VERSION)
        NextcloudAccountRegistry(
            accounts = persisted.accounts.map { account ->
                NextcloudAccountRecord(
                    id = NextcloudAccountId(account.id),
                    serverUrl = account.serverUrl,
                    loginName = account.loginName,
                )
            },
            activeAccountId = persisted.activeAccountId?.let(::NextcloudAccountId),
        )
    }.getOrNull()
}

@Serializable
private data class PersistedNextcloudAccountRegistry(
    val version: Int,
    val activeAccountId: String?,
    val accounts: List<PersistedNextcloudAccountRecord>,
)

@Serializable
private data class PersistedNextcloudAccountRecord(
    val id: String,
    val serverUrl: String,
    val loginName: String,
)

private val accountRegistryJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private const val ACCOUNT_REGISTRY_VERSION = 1
private const val MAX_LOCAL_ACCOUNTS = 64
private const val MAX_ACCOUNT_REGISTRY_BYTES = 256 * 1024
private const val MAX_ACCOUNT_SERVER_URL_LENGTH = 8 * 1024
private const val MAX_ACCOUNT_LOGIN_NAME_LENGTH = 1024
