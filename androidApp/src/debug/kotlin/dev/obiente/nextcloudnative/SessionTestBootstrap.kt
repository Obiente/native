package dev.obiente.nextcloudnative

import android.content.Context
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
            val encryptedSession = JSONObject()
                .put("serverUrl", serverUrl)
                .put("loginName", loginName)
                .put("appPassword", appPassword)
                .toString()
                .let(SessionCipher()::encrypt)

            check(
                context.getSharedPreferences(TEST_PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SESSION, encryptedSession)
                    .putBoolean(KEY_TEST_READ_ONLY, true)
                    .remove(KEY_TEST_WRITE_SCOPE_SERVER)
                    .remove(KEY_TEST_WRITE_SCOPE_PATH)
                    .commit(),
            ) {
                "Could not store the read-only emulator session."
            }
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
            val serverUrl = JSONObject(SessionCipher().decrypt(encryptedSession))
                .getString("serverUrl")
                .validatedServerUrl()
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
