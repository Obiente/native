package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudAccountId
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import kotlinx.coroutines.CancellationException

internal fun durableUploadCredentialNeedsRecovery(context: Context, accounts: List<NextcloudAccountRecord>, workIdentity: String): Boolean {
    val account = accounts.singleOrNull { NextcloudDocumentIds.accountKey(it.serverUrl, it.loginName) == workIdentity }
        ?: return false
    val preferences = context.getSharedPreferences(ANDROID_ACCOUNT_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val cipher by lazy { SessionCipher() }
    return durableUploadCredentialNeedsRecovery(
        listOf(ANDROID_ACCOUNT_SESSION_KEY, androidAccountCredentialSlotKey(account.id)),
        { key -> preferences.getString(key, null) }, { cipher.decrypt(it) }, account.id,
    )
}

// Called only after account restoration found no usable credentials. A surviving
// transient candidate can still recover; damaged bytes alone need user recovery.
internal fun durableUploadCredentialNeedsRecovery(
    keys: List<String>,
    read: (String) -> String?,
    decrypt: (String) -> String,
    accountId: NextcloudAccountId? = null,
): Boolean {
    var damaged = false
    var retryable = false
    for (key in keys) {
        try {
            val encrypted = read(key) ?: continue
            val restored = decodeAndroidAccountCredentialState(decrypt(encrypted))
            if (restored.unsupportedVersion != null) return true
            if (restored.state == null) {
                damaged = true
            } else if (accountId == null || accountId in restored.state.sessions) {
                // A usable fallback may have become available since restoration.
                retryable = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ClassCastException) {
            damaged = true
        } catch (failure: Exception) {
            when (durableUploadQueueDecryptionDisposition(failure)) {
                DurableUploadQueueRecoveryDisposition.Quarantine -> damaged = true
                DurableUploadQueueRecoveryDisposition.Retry -> retryable = true
            }
        }
    }
    return damaged && !retryable
}
