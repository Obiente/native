package dev.obiente.nextcloudnative

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTestBootstrapInstrumentedTest {
    @Test
    fun initializedEmptyRegistryReceivesConsistentReadOnlyCredentialState() = withPreferences { preferences ->
        preferences.edit().putString(ANDROID_ACCOUNT_REGISTRY_KEY,
            encodeNextcloudAccountRegistry(AndroidAccountCredentialState.Empty.registry)).commit()
        val session = NextcloudSession("https://fixture.invalid", "bootstrap-user", "synthetic-password")
        val cipher = SessionCipher()
        SessionTestBootstrap.persistImportedSession(preferences, session, cipher::encrypt)

        val registry = requireNotNull(restoreAndroidCredentialFreeRegistry(
            requireNotNull(preferences.getString(ANDROID_ACCOUNT_REGISTRY_KEY, null))).registry)
        assertEquals(session.accountId, registry.activeAccountId)
        val aggregate = decodeAndroidAccountCredentialState(cipher.decrypt(
            requireNotNull(preferences.getString(ANDROID_ACCOUNT_SESSION_KEY, null)))).state
        assertEquals(session, aggregate?.activeSession)
        val slot = readAndroidAccountCredentialSlot(session.accountId,
            { preferences.getString(it, null) }, cipher::decrypt, ::decodeAndroidAccountCredentialState)
        assertEquals(AndroidAccountCredentialSlotRead.Available(session), slot)
        assertTrue(preferences.getBoolean(KEY_TEST_READ_ONLY, false))
        preferences.edit().putString(KEY_TEST_WRITE_SCOPE_PATH, "/stale-scope").commit()
        SessionTestBootstrap.persistImportedSession(preferences, session, cipher::encrypt)
        assertFalse(preferences.contains(KEY_TEST_WRITE_SCOPE_PATH))
        assertTrue(preferences.getBoolean(KEY_TEST_READ_ONLY, false))
    }

    @Test
    fun normalAccountIsRejectedWithoutModifyingPreferences() = withPreferences { preferences ->
        val session = NextcloudSession("https://fixture.invalid", "normal-user", "synthetic-password")
        val state = AndroidAccountCredentialState.Empty.upsertAndSelect(session)
        preferences.edit().putString(ANDROID_ACCOUNT_REGISTRY_KEY,
            encodeNextcloudAccountRegistry(state.registry)).commit()
        val before = preferences.all
        val failure = runCatching {
            SessionTestBootstrap.persistImportedSession(preferences, session) { error("Must not encrypt") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, preferences.all)
    }

    private fun withPreferences(test: (android.content.SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-bootstrap-regression"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        try {
            test(preferences)
        } finally {
            check(preferences.edit().clear().commit())
        }
    }
}
