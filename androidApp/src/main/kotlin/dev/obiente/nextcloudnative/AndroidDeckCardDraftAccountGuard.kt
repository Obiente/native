package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession

internal suspend fun <Result> withAndroidDeckCardDraftSession(
    expectedSession: NextcloudSession,
    accountCredentials: AndroidAccountCredentialController,
    action: suspend () -> Result,
): Result = withAndroidAccountPrivateStatePublication(
    expectedSession = expectedSession,
    credentialMutationMutex = ANDROID_ACCOUNT_CREDENTIAL_MUTATION_MUTEX,
    guard = ANDROID_ACCOUNT_OPERATION_GUARD,
    resolveSession = { accountCredentials.loadSession(expectedSession.accountId) },
    unavailable = { error("The account changed before the Deck draft operation could complete.") },
    publish = action,
)
