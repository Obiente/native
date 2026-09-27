package dev.obiente.nextcloudnative.app

internal suspend fun <Result> withDesktopDeckCardDraftSession(
    expectedSession: NextcloudSession,
    guard: DesktopAccountOperationGuard,
    accountCredentials: DesktopAccountCredentialPersistence,
    action: suspend () -> Result,
): Result = guard.withAccountPrivateStatePublication(
    expectedSession = expectedSession,
    resolveSession = { accountCredentials.loadSession(expectedSession.accountId) },
    unavailable = { error("The account changed before the Deck draft operation could complete.") },
    publish = action,
)
