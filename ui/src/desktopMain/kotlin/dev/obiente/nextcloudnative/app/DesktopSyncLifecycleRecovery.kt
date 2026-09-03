package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import java.util.prefs.Preferences

internal fun desktopStoredSessionAccountId(preferences: Preferences): String? =
    preferences.get("server", null)?.let { server ->
        preferences.get("login", null)?.let { login ->
            desktopFileCacheAccountId(NextcloudSession(server, login, "unused"))
        }
    }

internal suspend fun reconcileDesktopBackgroundSession(
    loadSession: () -> NextcloudSession?,
    reconcile: suspend (NextcloudSession?) -> Unit,
    onFailure: (NextcloudSession?, Throwable) -> Unit = { _, _ -> },
): Boolean {
    val loaded = loadNextcloudSessionSafely(loadSession)
    val session = when (loaded) {
        is NextcloudSessionLoadState.Loaded -> loaded.session
        NextcloudSessionLoadState.SecureStorageUnavailable,
        NextcloudSessionLoadState.LegacyMigrationUnavailable -> return false
    }
    try {
        reconcile(session)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        onFailure(session, failure)
    }
    return true
}
