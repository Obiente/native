package dev.obiente.nextcloudnative

import android.content.Context
import android.provider.DocumentsContract
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.UUID

internal sealed interface AndroidDocumentProviderIncarnationRecord {
    val incarnation: NextcloudDocumentIncarnation

    data class Active(
        override val incarnation: NextcloudDocumentIncarnation,
    ) : AndroidDocumentProviderIncarnationRecord

    data class Retired(
        override val incarnation: NextcloudDocumentIncarnation,
    ) : AndroidDocumentProviderIncarnationRecord
}

internal class AndroidDocumentProviderIncarnationStore(
    private val read: (String) -> String?,
    private val commit: (String, String) -> Boolean,
    private val createIncarnation: () -> NextcloudDocumentIncarnation.Versioned = {
        NextcloudDocumentIncarnation.Versioned(UUID.randomUUID().toString().replace("-", ""))
    },
) {
    constructor(context: Context) : this(
        read = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)::getStringOrNull,
        commit = { accountIdentity, encoded ->
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(accountIdentity, encoded)
                .commit()
        },
    )

    fun activeIncarnation(accountIdentity: String): NextcloudDocumentIncarnation = synchronized(LOCK) {
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

    fun retire(accountIdentity: String): NextcloudDocumentIncarnation = synchronized(LOCK) {
        val incarnation = when (val record = readRecordOrNullOnMalformed(accountIdentity)) {
            null -> NextcloudDocumentIncarnation.Legacy
            is AndroidDocumentProviderIncarnationRecord.Active -> record.incarnation
            is AndroidDocumentProviderIncarnationRecord.Retired -> record.incarnation
        }
        persist(accountIdentity, AndroidDocumentProviderIncarnationRecord.Retired(incarnation))
        incarnation
    }

    fun retiredIncarnation(accountIdentity: String): NextcloudDocumentIncarnation? = synchronized(LOCK) {
        (readRecord(accountIdentity) as? AndroidDocumentProviderIncarnationRecord.Retired)?.incarnation
    }

    private fun readRecord(accountIdentity: String): AndroidDocumentProviderIncarnationRecord? {
        requireAccountIdentity(accountIdentity)
        return read(accountIdentity)?.let(::decodeAndroidDocumentProviderIncarnationRecord)
    }

    private fun readRecordOrNullOnMalformed(accountIdentity: String): AndroidDocumentProviderIncarnationRecord? =
        try {
            readRecord(accountIdentity)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }

    private fun persist(accountIdentity: String, record: AndroidDocumentProviderIncarnationRecord) {
        check(commit(accountIdentity, encodeAndroidDocumentProviderIncarnationRecord(record))) {
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

    private companion object {
        const val PREFERENCES_NAME = "documents-provider-incarnations-v1"
        val ACCOUNT_IDENTITY_PATTERN = Regex("[0-9a-f]{32}")
        val LOCK = Any()
    }
}

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
    AndroidDocumentProviderIncarnationStore(context).prepareForAccountSave(
        NextcloudDocumentIds.accountKey(session),
        session.accountId in current.sessions,
    )
}

internal fun notifyAndroidDocumentChanged(context: Context, session: NextcloudSession, path: String) {
    val appContext = context.applicationContext
    val incarnation = runCatching {
        AndroidDocumentProviderIncarnationStore(appContext)
            .activeIncarnation(NextcloudDocumentIds.accountKey(session))
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
