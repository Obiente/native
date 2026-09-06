package dev.obiente.nextcloudnative

import android.content.Context
import android.provider.DocumentsContract
import android.util.Log
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.nio.charset.CharacterCodingException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal sealed interface AndroidDocumentProviderIncarnationRecord {
    val incarnation: NextcloudDocumentIncarnation

    data class Active(
        override val incarnation: NextcloudDocumentIncarnation,
    ) : AndroidDocumentProviderIncarnationRecord

    data class Retired(
        override val incarnation: NextcloudDocumentIncarnation,
    ) : AndroidDocumentProviderIncarnationRecord
}

private sealed interface AndroidDocumentProviderCredentialResetRecord {
    data object Missing : AndroidDocumentProviderCredentialResetRecord
    data class Readable(
        val record: AndroidDocumentProviderIncarnationRecord,
    ) : AndroidDocumentProviderCredentialResetRecord
    data object Unreadable : AndroidDocumentProviderCredentialResetRecord
}

internal data class AndroidDocumentProviderIncarnationRetirement(
    val accountIdentity: String,
    val previousEncoded: String?,
    val retiredEncoded: String,
    val incarnation: NextcloudDocumentIncarnation,
)

internal enum class AndroidDocumentProviderAccountOwnership {
    Present,
    Absent,
    Unknown,
}

internal class AndroidDocumentProviderIncarnationStore(
    private val read: (String) -> String?,
    private val commit: (String, String?) -> Boolean,
    private val keys: () -> Set<String> = { emptySet() },
    private val createIncarnation: () -> NextcloudDocumentIncarnation.Versioned = {
        NextcloudDocumentIncarnation.Versioned(UUID.randomUUID().toString().replace("-", ""))
    },
) {
    constructor(context: Context) : this(
        read = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)::getStringOrNull,
        commit = { accountIdentity, encoded ->
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().apply {
                if (encoded == null) remove(accountIdentity) else putString(accountIdentity, encoded)
            }.commit()
        },
        keys = {
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).all.keys
        },
    )

    fun activeIncarnation(accountIdentity: String): NextcloudDocumentIncarnation = synchronized(LOCK) {
        requireNoPendingRetirement(accountIdentity)
        when (val record = readRecord(accountIdentity)) {
            null -> NextcloudDocumentIncarnation.Legacy
            is AndroidDocumentProviderIncarnationRecord.Active -> record.incarnation
            is AndroidDocumentProviderIncarnationRecord.Retired ->
                error("The document provider account incarnation is retired.")
        }
    }

    fun prepareForAccountSave(
        accountIdentity: String,
        accountAlreadyStored: Boolean,
    ): NextcloudDocumentIncarnation = synchronized(LOCK) {
        requireNoPendingRetirement(accountIdentity)
        when (val record = readRecord(accountIdentity)) {
            null -> if (accountAlreadyStored) {
                NextcloudDocumentIncarnation.Legacy
            } else {
                persistNewActiveIncarnation(accountIdentity)
            }
            is AndroidDocumentProviderIncarnationRecord.Active -> record.incarnation
            is AndroidDocumentProviderIncarnationRecord.Retired -> if (accountAlreadyStored) {
                error("The document provider account incarnation is retired.")
            } else {
                persistNewActiveIncarnation(accountIdentity)
            }
        }
    }

    fun retire(accountIdentity: String): NextcloudDocumentIncarnation =
        retireForRemoval(accountIdentity).incarnation

    fun retireForRemoval(accountIdentity: String): AndroidDocumentProviderIncarnationRetirement = synchronized(LOCK) {
        requireAccountIdentity(accountIdentity)
        requireNoPendingRetirement(accountIdentity)
        val previousEncoded = read(accountIdentity)
        val incarnation = when (val record = decodeRecordOrNullOnMalformed(previousEncoded)) {
            null -> NextcloudDocumentIncarnation.Legacy
            is AndroidDocumentProviderIncarnationRecord.Active -> record.incarnation
            is AndroidDocumentProviderIncarnationRecord.Retired -> record.incarnation
        }
        val retiredEncoded = encodeAndroidDocumentProviderIncarnationRecord(
            AndroidDocumentProviderIncarnationRecord.Retired(incarnation),
        )
        val retirement = AndroidDocumentProviderIncarnationRetirement(
            accountIdentity,
            previousEncoded,
            retiredEncoded,
            incarnation,
        )
        persistEncoded(retirementJournalKey(accountIdentity), encodeAndroidDocumentProviderRetirement(retirement))
        persistEncoded(accountIdentity, retiredEncoded)
        retirement
    }

    fun rollback(retirement: AndroidDocumentProviderIncarnationRetirement) = synchronized(LOCK) {
        if (!hasStoredRetirement(retirement)) {
            check(read(retirement.accountIdentity) == retirement.previousEncoded) {
                "The document provider removal rollback is not recoverable."
            }
            return@synchronized
        }
        reconcile(retirement, AndroidDocumentProviderAccountOwnership.Present)
    }

    fun complete(retirement: AndroidDocumentProviderIncarnationRetirement) = synchronized(LOCK) {
        if (!hasStoredRetirement(retirement)) {
            check(read(retirement.accountIdentity) == retirement.retiredEncoded) {
                "The document provider retirement is not committed."
            }
            return@synchronized
        }
        reconcile(retirement, AndroidDocumentProviderAccountOwnership.Absent)
    }

    fun reconcilePending(
        ownership: (String) -> AndroidDocumentProviderAccountOwnership,
        onMalformedJournal: (Exception) -> Unit = { failure -> throw failure },
    ) = synchronized(LOCK) {
        keys().asSequence()
            .filter { key -> key.startsWith(RETIREMENT_JOURNAL_KEY_PREFIX) }
            .sorted()
            .forEach { key ->
                val recovery = try {
                    val accountIdentity = key.removePrefix(RETIREMENT_JOURNAL_KEY_PREFIX)
                    requireAccountIdentity(accountIdentity)
                    val encoded = read(key) ?: return@forEach
                    val retirement = decodeAndroidDocumentProviderRetirement(encoded)
                    require(retirement.accountIdentity == accountIdentity) {
                        "The document provider retirement journal has the wrong account."
                    }
                    accountIdentity to retirement
                } catch (failure: Exception) {
                    onMalformedJournal(failure)
                    return@forEach
                }
                reconcile(recovery.second, ownership(recovery.first))
            }
    }

    fun retiredIncarnation(accountIdentity: String): NextcloudDocumentIncarnation? = synchronized(LOCK) {
        (readRecord(accountIdentity) as? AndroidDocumentProviderIncarnationRecord.Retired)?.incarnation
    }

    fun accountIdentitiesForCredentialReset(): List<String> = synchronized(LOCK) {
        keys().asSequence()
            .mapNotNull { key ->
                when {
                    ACCOUNT_IDENTITY_PATTERN.matches(key) -> key
                    key.startsWith(RETIREMENT_JOURNAL_KEY_PREFIX) ->
                        key.removePrefix(RETIREMENT_JOURNAL_KEY_PREFIX).takeIf(ACCOUNT_IDENTITY_PATTERN::matches)
                    else -> null
                }
            }
            .distinct()
            .sorted()
            .toList()
    }

    fun prepareForCredentialReset(
        accountIdentities: Collection<String>,
    ): List<AndroidDocumentProviderIncarnationRetirement> = synchronized(LOCK) {
        val retirements = mutableListOf<AndroidDocumentProviderIncarnationRetirement>()
        try {
            accountIdentities.distinct().sorted().forEach { accountIdentity ->
                requireAccountIdentity(accountIdentity)
                val pending = readPendingRetirementForCredentialReset(accountIdentity)
                if (pending != null) {
                    resumeRetirementForCredentialReset(pending)
                    retirements += pending
                } else {
                    when (val result = readRecordForCredentialReset(accountIdentity)) {
                        AndroidDocumentProviderCredentialResetRecord.Missing -> Unit
                        is AndroidDocumentProviderCredentialResetRecord.Readable -> when (result.record) {
                            is AndroidDocumentProviderIncarnationRecord.Active ->
                                retirements += retireForRemoval(accountIdentity)
                            is AndroidDocumentProviderIncarnationRecord.Retired -> Unit
                        }
                        AndroidDocumentProviderCredentialResetRecord.Unreadable -> persist(
                            accountIdentity,
                            AndroidDocumentProviderIncarnationRecord.Retired(NextcloudDocumentIncarnation.Legacy),
                        )
                    }
                }
            }
            retirements
        } catch (failure: Throwable) {
            retirements.asReversed().forEach { retirement ->
                try {
                    rollback(retirement)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    private fun readPendingRetirementForCredentialReset(
        accountIdentity: String,
    ): AndroidDocumentProviderIncarnationRetirement? = try {
        readPendingRetirement(accountIdentity)
    } catch (_: IllegalArgumentException) {
        quarantineMalformedPendingRetirement(accountIdentity)
        null
    } catch (_: ClassCastException) {
        quarantineMalformedPendingRetirement(accountIdentity)
        null
    }

    private fun quarantineMalformedPendingRetirement(accountIdentity: String) {
        val journalKey = retirementJournalKey(accountIdentity)
        val malformed = try {
            read(journalKey)
        } catch (_: ClassCastException) {
            null
        }
        malformed?.let { persistEncoded(quarantinedRetirementJournalKey(accountIdentity), it.take(MAX_RETIREMENT_JOURNAL_LENGTH)) }
        persist(accountIdentity, AndroidDocumentProviderIncarnationRecord.Retired(NextcloudDocumentIncarnation.Legacy))
        persistEncoded(journalKey, null)
    }

    private fun readPendingRetirement(accountIdentity: String): AndroidDocumentProviderIncarnationRetirement? {
        val encoded = read(retirementJournalKey(accountIdentity)) ?: return null
        return decodeAndroidDocumentProviderRetirement(encoded).also { retirement ->
            require(retirement.accountIdentity == accountIdentity) {
                "The document provider retirement journal has the wrong account."
            }
        }
    }

    private fun resumeRetirementForCredentialReset(retirement: AndroidDocumentProviderIncarnationRetirement) {
        when (read(retirement.accountIdentity)) {
            retirement.previousEncoded -> persistEncoded(retirement.accountIdentity, retirement.retiredEncoded)
            retirement.retiredEncoded -> Unit
            else -> error("The document provider account incarnation changed during credential reset.")
        }
    }

    private fun readRecordForCredentialReset(accountIdentity: String): AndroidDocumentProviderCredentialResetRecord = try {
        readRecord(accountIdentity)?.let(AndroidDocumentProviderCredentialResetRecord::Readable)
            ?: AndroidDocumentProviderCredentialResetRecord.Missing
    } catch (_: IllegalArgumentException) {
        AndroidDocumentProviderCredentialResetRecord.Unreadable
    } catch (_: ClassCastException) {
        AndroidDocumentProviderCredentialResetRecord.Unreadable
    }

    private fun readRecord(accountIdentity: String): AndroidDocumentProviderIncarnationRecord? {
        requireAccountIdentity(accountIdentity)
        return read(accountIdentity)?.let(::decodeAndroidDocumentProviderIncarnationRecord)
    }

    private fun decodeRecordOrNullOnMalformed(encoded: String?): AndroidDocumentProviderIncarnationRecord? =
        try {
            encoded?.let(::decodeAndroidDocumentProviderIncarnationRecord)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }

    private fun persist(accountIdentity: String, record: AndroidDocumentProviderIncarnationRecord) {
        persistEncoded(accountIdentity, encodeAndroidDocumentProviderIncarnationRecord(record))
    }

    private fun persistEncoded(accountIdentity: String, encoded: String?) {
        check(commit(accountIdentity, encoded)) {
            "Could not persist the document provider account incarnation."
        }
    }

    private fun persistNewActiveIncarnation(accountIdentity: String): NextcloudDocumentIncarnation.Versioned {
        val replacement = createIncarnation()
        persist(accountIdentity, AndroidDocumentProviderIncarnationRecord.Active(replacement))
        return replacement
    }

    private fun requireAccountIdentity(accountIdentity: String) {
        require(ACCOUNT_IDENTITY_PATTERN.matches(accountIdentity)) { "Invalid document account." }
    }

    private fun hasStoredRetirement(retirement: AndroidDocumentProviderIncarnationRetirement): Boolean {
        requireAccountIdentity(retirement.accountIdentity)
        val encoded = read(retirementJournalKey(retirement.accountIdentity))
            ?: return false
        check(decodeAndroidDocumentProviderRetirement(encoded) == retirement) {
            "The document provider retirement journal changed."
        }
        return true
    }

    private fun requireNoPendingRetirement(accountIdentity: String) {
        requireAccountIdentity(accountIdentity)
        check(read(retirementJournalKey(accountIdentity)) == null) {
            "The document provider account retirement must be reconciled."
        }
    }

    private fun reconcile(
        retirement: AndroidDocumentProviderIncarnationRetirement,
        ownership: AndroidDocumentProviderAccountOwnership,
    ) {
        val currentEncoded = read(retirement.accountIdentity)
        when (ownership) {
            AndroidDocumentProviderAccountOwnership.Present -> when (currentEncoded) {
                retirement.retiredEncoded -> persistEncoded(retirement.accountIdentity, retirement.previousEncoded)
                retirement.previousEncoded -> Unit
                else -> error("The document provider account incarnation changed during removal recovery.")
            }
            AndroidDocumentProviderAccountOwnership.Absent -> check(currentEncoded == retirement.retiredEncoded) {
                "The document provider retirement is not committed."
            }
            AndroidDocumentProviderAccountOwnership.Unknown ->
                error("Document provider account ownership is unavailable.")
        }
        persistEncoded(retirementJournalKey(retirement.accountIdentity), null)
    }

    private companion object {
        const val PREFERENCES_NAME = "documents-provider-incarnations-v1"
        const val RETIREMENT_JOURNAL_KEY_PREFIX = "retirement:"
        val ACCOUNT_IDENTITY_PATTERN = Regex("[0-9a-f]{64}")
        val LOCK = Any()

        fun retirementJournalKey(accountIdentity: String): String =
            "$RETIREMENT_JOURNAL_KEY_PREFIX$accountIdentity"

        fun quarantinedRetirementJournalKey(accountIdentity: String): String =
            "quarantined-$RETIREMENT_JOURNAL_KEY_PREFIX$accountIdentity"
    }
}

internal suspend fun retireAndroidDocumentProviderIncarnationsForCredentialReset(
    store: AndroidDocumentProviderIncarnationStore,
    lifetimeGuard: AndroidAccountRemovalLifetimeGuard,
    clearCredentials: suspend () -> Unit,
    recordCompletionFailure: (Exception) -> Unit = {},
) {
    val accountIdentities = store.accountIdentitiesForCredentialReset()
    lifetimeGuard.withCredentialReset(accountIdentities) {
        val retirements = store.prepareForCredentialReset(accountIdentities)
        withContext(NonCancellable) {
            try {
                clearCredentials()
            } catch (failure: Exception) {
                retirements.asReversed().forEach { retirement ->
                    runCatching { store.rollback(retirement) }.onFailure(failure::addSuppressed)
                }
                throw failure
            }
            retirements.forEach { retirement ->
                try {
                    store.complete(retirement)
                } catch (failure: Exception) {
                    recordCompletionFailure(failure)
                }
            }
        }
    }
}

internal fun encodeAndroidDocumentProviderRetirement(
    retirement: AndroidDocumentProviderIncarnationRetirement,
): String {
    val previous = retirement.previousEncoded
    val previousState = if (previous == null) "missing" else "present"
    return listOf(
        "1",
        retirement.accountIdentity,
        previousState,
        encodeRetirementField(previous.orEmpty()),
        encodeRetirementField(retirement.retiredEncoded),
    ).joinToString(":").also { encoded ->
        require(encoded.length <= MAX_RETIREMENT_JOURNAL_LENGTH) {
            "The document provider retirement journal is too large."
        }
    }
}

internal fun decodeAndroidDocumentProviderRetirement(
    encoded: String,
): AndroidDocumentProviderIncarnationRetirement {
    require(encoded.length <= MAX_RETIREMENT_JOURNAL_LENGTH) {
        "The document provider retirement journal is too large."
    }
    val fields = encoded.split(':')
    require(fields.size == 5 && fields[0] == "1") {
        "Unsupported document provider retirement journal."
    }
    val previousEncoded = when (fields[2]) {
        "missing" -> {
            require(fields[3].isEmpty())
            null
        }
        "present" -> decodeRetirementField(fields[3])
        else -> throw IllegalArgumentException("Invalid document provider retirement prior state.")
    }
    val retiredEncoded = decodeRetirementField(fields[4])
    val retired = decodeAndroidDocumentProviderIncarnationRecord(retiredEncoded)
    require(retired is AndroidDocumentProviderIncarnationRecord.Retired) {
        "The document provider retirement journal is not retired."
    }
    return AndroidDocumentProviderIncarnationRetirement(
        accountIdentity = fields[1],
        previousEncoded = previousEncoded,
        retiredEncoded = retiredEncoded,
        incarnation = retired.incarnation,
    )
}

private fun encodeRetirementField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())

private fun decodeRetirementField(value: String): String {
    val decoded = try {
        Base64.getUrlDecoder().decode(value)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid document provider retirement journal encoding.", failure)
    }
    val decodedText = try {
        decoded.decodeToString(throwOnInvalidSequence = true)
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("Invalid document provider retirement journal text.", failure)
    }
    require(encodeRetirementField(decodedText) == value) {
        "The document provider retirement journal encoding is not canonical."
    }
    return decodedText
}

private const val MAX_RETIREMENT_JOURNAL_LENGTH = 16_384

internal fun encodeAndroidDocumentProviderIncarnationRecord(
    record: AndroidDocumentProviderIncarnationRecord,
): String {
    val state = when (record) {
        is AndroidDocumentProviderIncarnationRecord.Active -> "active"
        is AndroidDocumentProviderIncarnationRecord.Retired -> "retired"
    }
    val incarnation = when (val value = record.incarnation) {
        NextcloudDocumentIncarnation.Legacy -> "legacy"
        is NextcloudDocumentIncarnation.Versioned -> value.value
    }
    return "1:$state:$incarnation"
}

internal fun decodeAndroidDocumentProviderIncarnationRecord(
    encoded: String,
): AndroidDocumentProviderIncarnationRecord {
    val parts = encoded.split(':')
    require(parts.size == 3 && parts[0] == "1") { "Unsupported document provider incarnation record." }
    val incarnation = if (parts[2] == "legacy") {
        NextcloudDocumentIncarnation.Legacy
    } else {
        NextcloudDocumentIncarnation.Versioned(parts[2])
    }
    return when (parts[1]) {
        "active" -> AndroidDocumentProviderIncarnationRecord.Active(incarnation)
        "retired" -> AndroidDocumentProviderIncarnationRecord.Retired(incarnation)
        else -> throw IllegalArgumentException("Invalid document provider incarnation state.")
    }
}

private fun android.content.SharedPreferences.getStringOrNull(key: String): String? = getString(key, null)

internal fun prepareAndroidDocumentProviderAccountSave(
    context: Context,
    session: NextcloudSession,
    current: AndroidAccountCredentialState,
) {
    val store = AndroidDocumentProviderIncarnationStore(context)
    store.reconcilePendingForCredentialAccess(current.registry)
    store.prepareForAccountSave(
        session.documentProviderIncarnationAccountIdentity(),
        session.accountId in current.sessions,
    )
}

internal fun reconcileAndroidDocumentProviderAccountRemovals(
    context: Context,
    registry: NextcloudAccountRegistry,
): NextcloudAccountRegistry = registry.also {
    reconcileAndroidDocumentProviderAccountRemovalsWhenCredentialMutationIdle(
        ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX,
    ) {
        AndroidDocumentProviderIncarnationStore(context).reconcilePendingForCredentialAccess(registry)
    }
}

internal inline fun reconcileAndroidDocumentProviderAccountRemovalsWhenCredentialMutationIdle(
    credentialMutationMutex: Mutex,
    reconcile: () -> Unit,
): Boolean {
    if (!credentialMutationMutex.tryLock()) return false
    return try {
        reconcile()
        true
    } finally {
        credentialMutationMutex.unlock()
    }
}

private fun AndroidDocumentProviderIncarnationStore.reconcilePendingForCredentialAccess(
    registry: NextcloudAccountRegistry,
) = reconcilePending(
    ownership = registry::documentProviderAccountOwnership,
    onMalformedJournal = { failure ->
        Log.e(
            "NextcloudDocuments",
            "A malformed document-provider retirement journal was left unavailable.",
            failure,
        )
    },
)

internal fun AndroidAccountRemovalCleanupJournal.completeDocumentRetirement(
    context: Context,
    retirement: AndroidDocumentProviderIncarnationRetirement?,
    accountStorageKey: String,
) {
    AndroidDocumentProviderIncarnationStore(context).complete(requireNotNull(retirement))
    clear(accountStorageKey)
}

private fun NextcloudAccountRegistry.documentProviderAccountOwnership(
    accountIdentity: String,
): AndroidDocumentProviderAccountOwnership = if (
    accounts.any { account -> account.id.storageKey == accountIdentity }
) {
    AndroidDocumentProviderAccountOwnership.Present
} else {
    AndroidDocumentProviderAccountOwnership.Absent
}

internal fun notifyAndroidDocumentChanged(context: Context, session: NextcloudSession, path: String) {
    val appContext = context.applicationContext
    val incarnation = runCatching {
        AndroidDocumentProviderIncarnationStore(appContext)
            .activeIncarnation(session.documentProviderIncarnationAccountIdentity())
    }.getOrNull() ?: return
    val authority = nextcloudDocumentsAuthority(appContext.packageName)
    appContext.contentResolver.notifyChange(
        DocumentsContract.buildDocumentUri(
            authority,
            NextcloudDocumentIds.documentId(session, incarnation, path),
        ),
        null,
    )
    appContext.contentResolver.notifyChange(
        DocumentsContract.buildChildDocumentsUri(
            authority,
            NextcloudDocumentIds.documentId(session, incarnation, NextcloudDocumentIds.parentPath(path)),
        ),
        null,
    )
}

internal fun NextcloudSession.documentProviderIncarnationAccountIdentity(): String = accountId.storageKey
