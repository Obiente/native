package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRegistrySource
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.decodeNextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.restoreNextcloudAccountRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.json.JSONObject

class AndroidPersistedSessionTest {
    @Test
    fun legacyPayloadMigratesOnceAndRestartsWithTheSameActiveAccount() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var migrated: String? = null

        val first = restoreAndroidPersistedSession(
            encoded = legacyPayload(),
            persistMigrated = { encoded -> migrated = encoded },
            recordDiagnostic = diagnostics::add,
        )
        val migratedPayload = requireNotNull(migrated)
        val registry = decodeNextcloudAccountRegistry(
            JSONObject(migratedPayload).getString(ACCOUNT_REGISTRY_KEY),
        )

        assertEquals(first.accountId, requireNotNull(registry).activeAccountId)
        assertTrue(diagnostics.isEmpty())

        var unexpectedSecondMigration = false
        val restarted = restoreAndroidPersistedSession(
            encoded = migratedPayload,
            persistMigrated = { unexpectedSecondMigration = true },
            recordDiagnostic = diagnostics::add,
        )
        assertEquals(first, restarted)
        assertFalse(unexpectedSecondMigration)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun malformedRegistryFallsBackWithoutDiscardingTheLegacySession() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var migrated: String? = null
        val malformed = JSONObject(legacyPayload())
            .put(ACCOUNT_REGISTRY_KEY, "{not-json")
            .toString()

        val session = restoreAndroidPersistedSession(
            encoded = malformed,
            persistMigrated = { encoded -> migrated = encoded },
            recordDiagnostic = diagnostics::add,
        )
        val restoredRegistry = restoreNextcloudAccountRegistry(
            JSONObject(requireNotNull(migrated)).getString(ACCOUNT_REGISTRY_KEY),
            session,
        )

        assertEquals(NextcloudAccountRegistrySource.Persisted, restoredRegistry.source)
        assertEquals(session.accountId, restoredRegistry.registry.activeAccountId)
        assertEquals(listOf("ACCOUNT_REGISTRY_MALFORMED"), diagnostics.mapNotNull { it.code })
        val renderedDiagnostics = diagnostics.joinToString()
        assertFalse(renderedDiagnostics.contains("private-app-password"))
        assertFalse(renderedDiagnostics.contains("alice"))
        assertFalse(renderedDiagnostics.contains("cloud.example.test"))
    }

    @Test
    fun savedPayloadKeepsCredentialsOutsideTheRegistry() {
        val session = restoreAndroidPersistedSession(
            encoded = legacyPayload(),
            persistMigrated = {},
            recordDiagnostic = {},
        )

        val payload = JSONObject(encodeAndroidPersistedSession(session))
        val encodedRegistry = payload.getString(ACCOUNT_REGISTRY_KEY)

        assertEquals("private-app-password", payload.getString("appPassword"))
        assertFalse(encodedRegistry.contains("private-app-password"))
        assertFalse(encodedRegistry.contains("appPassword"))
    }

    private fun legacyPayload(): String = JSONObject()
        .put("serverUrl", "https://cloud.example.test")
        .put("loginName", "alice")
        .put("appPassword", "private-app-password")
        .toString()

    private companion object {
        const val ACCOUNT_REGISTRY_KEY = "account_registry_v1"
    }
}
