package dev.obiente.nextcloudnative.app

import java.util.prefs.Preferences
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopPendingLegacyCredentialCleanup(
    val serverUrl: String,
    val loginName: String,
)

internal data class DesktopLegacyCredentialCleanupSnapshot(
    val slots: List<Pair<String?, String?>>,
    val legacyServer: String?,
    val legacyLogin: String?,
)

internal class DesktopLegacyCredentialCleanupJournal(
    private val preferences: Preferences,
    private val flush: () -> Unit,
    private val recordMalformed: () -> Unit,
) {
    private val malformedReported = AtomicBoolean(false)

    fun pending(): List<DesktopPendingLegacyCredentialCleanup> {
        var malformed = false
        val cleanups = buildList {
            repeat(MAX_LOCAL_ACCOUNTS) { index ->
                val serverKey = serverKey(index)
                val loginKey = loginKey(index)
                val cleanup = decode(serverKey, loginKey)
                if (cleanup != null) {
                    add(cleanup)
                } else if (preferences.get(serverKey, null) != null || preferences.get(loginKey, null) != null) {
                    malformed = true
                }
            }
            val legacyCleanup = decode(LEGACY_SERVER_KEY, LEGACY_LOGIN_KEY)
            if (legacyCleanup != null) {
                add(legacyCleanup)
            } else if (preferences.get(LEGACY_SERVER_KEY, null) != null ||
                preferences.get(LEGACY_LOGIN_KEY, null) != null
            ) {
                malformed = true
            }
        }.distinct()
        if (malformed && malformedReported.compareAndSet(false, true)) runCatching(recordMalformed)
        return cleanups
    }

    fun prepareAdd(cleanup: DesktopPendingLegacyCredentialCleanup) {
        if (cleanup in pending()) return
        val slot = (0 until MAX_LOCAL_ACCOUNTS).firstOrNull { index ->
            preferences.get(serverKey(index), null) == null && preferences.get(loginKey(index), null) == null
        } ?: error("The legacy credential cleanup journal is full.")
        preferences.put(serverKey(slot), cleanup.serverUrl)
        preferences.put(loginKey(slot), cleanup.loginName)
    }

    fun clear(cleanup: DesktopPendingLegacyCredentialCleanup) {
        val previous = snapshot()
        try {
            repeat(MAX_LOCAL_ACCOUNTS) { index ->
                if (decode(serverKey(index), loginKey(index)) == cleanup) {
                    preferences.remove(serverKey(index))
                    preferences.remove(loginKey(index))
                }
            }
            if (decode(LEGACY_SERVER_KEY, LEGACY_LOGIN_KEY) == cleanup) {
                preferences.remove(LEGACY_SERVER_KEY)
                preferences.remove(LEGACY_LOGIN_KEY)
            }
            flush()
        } catch (failure: Exception) {
            restore(previous)
            runCatching(flush)
            throw failure
        }
    }

    fun snapshot() = DesktopLegacyCredentialCleanupSnapshot(
        slots = (0 until MAX_LOCAL_ACCOUNTS).map { index ->
            preferences.get(serverKey(index), null) to preferences.get(loginKey(index), null)
        },
        legacyServer = preferences.get(LEGACY_SERVER_KEY, null),
        legacyLogin = preferences.get(LEGACY_LOGIN_KEY, null),
    )

    fun restore(snapshot: DesktopLegacyCredentialCleanupSnapshot) {
        snapshot.slots.forEachIndexed { index, (server, login) ->
            preferences.restoreString(serverKey(index), server)
            preferences.restoreString(loginKey(index), login)
        }
        preferences.restoreString(LEGACY_SERVER_KEY, snapshot.legacyServer)
        preferences.restoreString(LEGACY_LOGIN_KEY, snapshot.legacyLogin)
    }

    private fun decode(serverKey: String, loginKey: String): DesktopPendingLegacyCredentialCleanup? {
        val server = preferences.get(serverKey, null)?.takeIf(String::isNotBlank) ?: return null
        val login = preferences.get(loginKey, null)?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            deriveNextcloudAccountId(server, login)
            DesktopPendingLegacyCredentialCleanup(server, login)
        }.getOrNull()
    }

    private fun serverKey(index: Int) = "$SLOT_PREFIX.$index.server"
    private fun loginKey(index: Int) = "$SLOT_PREFIX.$index.login"

    private companion object {
        const val SLOT_PREFIX = "accountLegacyCleanupV2"
        const val LEGACY_SERVER_KEY = "accountLegacyCleanupServer"
        const val LEGACY_LOGIN_KEY = "accountLegacyCleanupLogin"
    }
}

private fun Preferences.restoreString(key: String, value: String?) {
    if (value == null) remove(key) else put(key, value)
}
