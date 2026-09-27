package dev.obiente.nextcloudnative

import android.content.Context

internal suspend fun removeAndroidAccountDiagnosticsAndGrants(
    context: Context,
    accountStorageKey: String,
    accountIdentity: String,
    removeSupportAccount: suspend (String) -> Unit,
) {
    val retirement = AndroidDocumentProviderIncarnationStore(context).retiredIncarnation(accountStorageKey)
    val incarnation = retirement ?: NextcloudDocumentIncarnation.Legacy
    val aliases = AndroidDocumentLegacyAliases(context)
    val verified = if (retirement != null) aliases.readVerified(accountStorageKey, incarnation)
        else aliases.read(accountStorageKey, incarnation)
    retireAndroidDiagnosticScopes(
        scopes = verified + accountIdentity,
        removePrimary = removeSupportAccount,
        removeShared = { AndroidSupportDiagnostics.get(context).removeAccount(it) },
        revokeGrantsAndRetireAliases = { revokeAndroidAccountDocumentGrants(context, accountIdentity, accountStorageKey) },
    )
}

/** Keep alias provenance until both diagnostic sinks and grant revocation have completed. */
internal suspend fun retireAndroidDiagnosticScopes(
    scopes: Set<String>,
    removePrimary: suspend (String) -> Unit,
    removeShared: suspend (String) -> Unit,
    revokeGrantsAndRetireAliases: () -> Unit,
) {
    scopes.forEach { scope ->
        removePrimary(scope)
        removeShared(scope)
    }
    revokeGrantsAndRetireAliases()
}
