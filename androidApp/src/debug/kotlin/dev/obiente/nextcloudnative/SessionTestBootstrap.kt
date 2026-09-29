package dev.obiente.nextcloudnative

import android.content.Context
import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import java.io.File
import java.net.URI
import org.json.JSONObject

internal object SessionTestBootstrap {
    private const val IMPORT_FILENAME = "nc-native-test-session.json"
    private const val WRITE_SCOPE_IMPORT_FILENAME = "nc-native-test-write-scope.json"
    private const val KEY_SESSION = "encrypted_session"

    fun importIfPresent(context: Context) {
        importSessionIfPresent(context)
        importWriteScopeIfPresent(context)
    }

    private fun importSessionIfPresent(context: Context) {
        val importFile = File(context.filesDir, IMPORT_FILENAME)
        if (!importFile.isFile) return

        try {
            val source = JSONObject(importFile.readText())
            val serverUrl = source.getString("serverUrl").validatedServerUrl()
            val loginName = source.getString("loginName").validatedSecretField("login name")
            val appPassword = source.getString("appPassword").validatedSecretField("app password")
            persistImportedSession(
                context.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE),
                NextcloudSession(serverUrl, loginName, appPassword),
                SessionCipher()::encrypt,
            )
        } finally {
            check(importFile.delete() || !importFile.exists()) {
                "Could not remove the temporary emulator session import."
            }
        }
    }

    private fun importWriteScopeIfPresent(context: Context) {
        val importFile = File(context.filesDir, WRITE_SCOPE_IMPORT_FILENAME)
        if (!importFile.isFile) return
        try {
            val source = JSONObject(importFile.readText())
            val preferences = context.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (source.optBoolean("clear", false)) {
                check(
                    preferences.edit()
                        .remove(KEY_TEST_WRITE_SCOPE_SERVER)
                        .remove(KEY_TEST_WRITE_SCOPE_PATH)
                        .commit(),
                ) {
                    "Could not clear the emulator write scope."
                }
                return
            }
            require(preferences.getBoolean(KEY_TEST_READ_ONLY, false)) {
                "A write scope requires the imported read-only emulator session."
            }
            val encryptedSession = requireNotNull(preferences.getString(KEY_SESSION, null)) {
                "The imported emulator session is missing."
            }
            val serverUrl = requireNotNull(
                decodeAndroidAccountCredentialState(SessionCipher().decrypt(encryptedSession)).state?.activeSession,
            ) { "The imported emulator session is unavailable." }.serverUrl.validatedServerUrl()
            val apiPathPrefix = source.getString("apiPathPrefix")
            requireNotNull(ScopedTestWriteAuthorization.create(serverUrl, apiPathPrefix)) {
                "The emulator write scope is invalid."
            }
            check(
                preferences.edit()
                    .putString(KEY_TEST_WRITE_SCOPE_SERVER, serverUrl)
                    .putString(KEY_TEST_WRITE_SCOPE_PATH, apiPathPrefix.trim().trimEnd('/'))
                    .commit(),
            ) {
                "Could not store the emulator write scope."
            }
        } finally {
            check(importFile.delete() || !importFile.exists()) {
                "Could not remove the temporary emulator write scope."
            }
        }
    }

    internal fun persistImportedSession(
        preferences: SharedPreferences,
        session: NextcloudSession,
        encrypt: (String) -> String,
    ) = ANDROID_ACCOUNT_CREDENTIAL_STORE_GUARD.serialize {
        val existingRegistry = preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null)?.let {
            requireNotNull(restoreAndroidCredentialFreeRegistry(it).registry) {
                "The existing account registry cannot be imported over."
            }
        }
        val existingAccountIds = existingRegistry?.accounts.orEmpty().map { it.id }
        val hasCredentials = preferences.contains(KEY_SESSION) || preferences.all.keys.any {
            it.startsWith(ANDROID_ACCOUNT_CREDENTIAL_SLOT_KEY_PREFIX)
        }
        require(
            (existingAccountIds.isEmpty() && !hasCredentials) ||
                (preferences.getBoolean(KEY_TEST_READ_ONLY, false) &&
                    existingAccountIds == listOf(session.accountId)),
        ) { "Session import requires an empty app or the same read-only test account." }
        val state = AndroidAccountCredentialState.Empty.upsertAndSelect(session)
        val encrypted = encrypt(encodeAndroidAccountCredentialState(state))
        check(preferences.edit()
            .putString(KEY_SESSION, encrypted)
            .putString(ANDROID_ACCOUNT_REGISTRY_KEY, encodeNextcloudAccountRegistry(state.registry))
            .putString(androidAccountCredentialSlotKey(session.accountId), encrypted)
            .putBoolean(KEY_TEST_READ_ONLY, true)
            .remove(KEY_TEST_WRITE_SCOPE_SERVER)
            .remove(KEY_TEST_WRITE_SCOPE_PATH)
            .commit()) { "Could not store the read-only emulator session." }
    }

    private fun String.validatedServerUrl(): String {
        val normalized = trim().trimEnd('/')
        val uri = URI(normalized)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "The desktop session does not contain a valid secure server address."
        }
        return normalized
    }

    private fun String.validatedSecretField(label: String): String {
        require(isNotBlank() && length <= 4_096 && none(Char::isISOControl)) {
            "The desktop session contains an invalid $label."
        }
        return this
    }
}
