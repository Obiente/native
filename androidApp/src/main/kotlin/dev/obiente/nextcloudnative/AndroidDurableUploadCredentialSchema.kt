package dev.obiente.nextcloudnative

import android.content.Context
import dev.obiente.nextcloudnative.app.NextcloudAccountRecord
import kotlinx.coroutines.CancellationException

internal fun durableUploadCredentialNeedsUpgrade(context: Context, accounts: List<NextcloudAccountRecord>, workIdentity: String): Boolean {
    val account = accounts.singleOrNull { NextcloudDocumentIds.accountKey(it.serverUrl, it.loginName) == workIdentity }
        ?: return false
    val preferences = context.getSharedPreferences(ANDROID_ACCOUNT_PREFERENCES_NAME, Context.MODE_PRIVATE)
    val cipher = SessionCipher()
    return durableUploadCredentialNeedsUpgrade(
        listOf(ANDROID_ACCOUNT_SESSION_KEY, androidAccountCredentialSlotKey(account.id)),
        { key -> preferences.getString(key, null) }, cipher::decrypt,
    )
}

internal fun durableUploadCredentialNeedsUpgrade(
    keys: List<String>,
    read: (String) -> String?,
    decrypt: (String) -> String,
): Boolean = keys.any { key ->
    try {
        read(key)?.let { decodeAndroidAccountCredentialState(decrypt(it)).unsupportedVersion != null } == true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // An inaccessible keystore can recover without an upgrade, so keep its normal retry policy.
        false
    }
}
