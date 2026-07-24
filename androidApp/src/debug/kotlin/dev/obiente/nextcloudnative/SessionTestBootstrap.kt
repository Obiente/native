package dev.obiente.nextcloudnative

import android.content.Context
import java.io.File
import java.net.URI
import org.json.JSONObject

internal object SessionTestBootstrap {
    private const val IMPORT_FILENAME = "nc-native-test-session.json"
    private const val PREFERENCES_NAME = "nextcloud_native"
    private const val KEY_SESSION = "encrypted_session"
    private const val KEY_TEST_READ_ONLY = "emulator_test_read_only"

    fun importIfPresent(context: Context) {
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
                context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SESSION, encryptedSession)
                    .putBoolean(KEY_TEST_READ_ONLY, true)
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
