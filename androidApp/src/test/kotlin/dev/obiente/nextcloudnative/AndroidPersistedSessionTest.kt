package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import java.lang.reflect.Proxy

class AndroidPersistedSessionTest {
    @Test
    fun retainedAccountSessionResolvesWithoutSelectingIt() {
        val first = firstSession()
        val second = secondSession()
        val sessions = mapOf(first.accountId to first, second.accountId to second)

        val resolved = resolveStoredAndroidAccountSession(
            accountIdentity = NextcloudDocumentIds.accountKey(second),
            listAccounts = { listOf(first.accountRecord(), second.accountRecord()) },
            loadSession = sessions::get,
        )

        assertEquals(second, resolved)
    }

    @Test
    fun retainedAccountResolutionRejectsMismatchedCredential() {
        val first = firstSession()
        val second = secondSession()

        val resolved = resolveStoredAndroidAccountSession(
            accountIdentity = NextcloudDocumentIds.accountKey(second),
            listAccounts = { listOf(second.accountRecord()) },
            loadSession = { first },
        )

        assertNull(resolved)
    }
    @Test
    fun accountCredentialEditsUseCheckedSynchronousCommit() {
        val successfulCalls = mutableListOf<String>()
        requireCommittedAndroidAccountCredentialEdit(recordingEditor(commitResult = true, successfulCalls))
        assertEquals(listOf("commit"), successfulCalls)

        val failedCalls = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            requireCommittedAndroidAccountCredentialEdit(recordingEditor(commitResult = false, failedCalls))
        }
        assertEquals(listOf("commit"), failedCalls)
    }

    @Test
    fun legacyPayloadMigratesOnceAndRestartsWithTheSameActiveAccount() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var migrated: String? = null

        val first = restoreAndroidAccountCredentialState(
            encoded = legacyPayload(firstSession()),
            persistMigrated = { encoded -> migrated = encoded },
            recordDiagnostic = diagnostics::add,
        )
        val migratedPayload = requireNotNull(migrated)

        assertEquals(firstSession(), requireNotNull(first).activeSession)
        assertEquals(2, JSONObject(migratedPayload).getInt("version"))
        assertTrue(diagnostics.isEmpty())

        var unexpectedSecondMigration = false
        val restarted = restoreAndroidAccountCredentialState(
            encoded = migratedPayload,
            persistMigrated = { unexpectedSecondMigration = true },
            recordDiagnostic = diagnostics::add,
        )

        assertEquals(first, restarted)
        assertFalse(unexpectedSecondMigration)
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun versionlessRegistryPayloadFromAccountFoundationMigratesToCredentialSlots() {
        val session = firstSession()
        val versionless = JSONObject(legacyPayload(session))
            .put(
                "account_registry_v1",
                encodeNextcloudAccountRegistry(NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord())),
            )
            .toString()
        var migrated: String? = null

        val restored = restoreAndroidAccountCredentialState(
            encoded = versionless,
            persistMigrated = { migrated = it },
            recordDiagnostic = {},
        )

        assertEquals(session, requireNotNull(restored).activeSession)
        assertEquals(2, JSONObject(requireNotNull(migrated)).getInt("version"))
    }

    @Test
    fun twoCredentialSlotsRestartAndSelectTheExactAccount() {
        val first = firstSession()
        val second = secondSession()
        val state = AndroidAccountCredentialState.Empty
            .upsertAndSelect(first)
            .upsertAndSelect(second)
        val restarted = requireNotNull(
            decodeAndroidAccountCredentialState(encodeAndroidAccountCredentialState(state)).state,
        )

        assertEquals(
            listOf(first.accountId, second.accountId).sortedBy { it.storageKey },
            restarted.sessions.keys.sortedBy { it.storageKey },
        )
        assertEquals(second, restarted.activeSession)
        assertEquals(first, requireNotNull(restarted.select(first.accountId)).activeSession)
    }

    @Test
    fun encodingIsDeterministicAcrossCredentialInsertionOrder() {
        val firstThenSecond = AndroidAccountCredentialState.Empty
            .upsertAndSelect(firstSession())
            .upsertAndSelect(secondSession())
            .select(firstSession().accountId)
        val secondThenFirst = AndroidAccountCredentialState.Empty
            .upsertAndSelect(secondSession())
            .upsertAndSelect(firstSession())

        assertEquals(
            encodeAndroidAccountCredentialState(requireNotNull(firstThenSecond)),
            encodeAndroidAccountCredentialState(secondThenFirst),
        )
    }

    @Test
    fun malformedStoreDoesNotExposeOrOverwriteCredentialValues() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var persisted = false
        val malformed = "{\"appPassword\":\"private-app-password\",\"version\":2"

        val restored = restoreAndroidAccountCredentialState(
            encoded = malformed,
            persistMigrated = { persisted = true },
            recordDiagnostic = diagnostics::add,
        )

        assertNull(restored)
        assertFalse(persisted)
        assertEquals(listOf("ACCOUNT_CREDENTIAL_STORE_MALFORMED"), diagnostics.mapNotNull { it.code })
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    @Test
    fun claimedAccountMismatchRejectsTheWholeCredentialStore() {
        val encoded = JSONObject(
            encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(firstSession())),
        )
        encoded.getJSONArray("credentials").getJSONObject(0)
            .put("accountId", secondSession().accountId.storageKey)
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val restored = restoreAndroidAccountCredentialState(
            encoded = encoded.toString(),
            persistMigrated = {},
            recordDiagnostic = diagnostics::add,
        )

        assertNull(restored)
        assertEquals(listOf("ACCOUNT_CREDENTIAL_SLOT_MISMATCH"), diagnostics.mapNotNull { it.code })
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    @Test
    fun duplicateCredentialIdentityIsRejected() {
        val encoded = JSONObject(
            encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(firstSession())),
        )
        val credentials = encoded.getJSONArray("credentials")
        credentials.put(JSONObject(credentials.getJSONObject(0).toString()))

        val restored = decodeAndroidAccountCredentialState(encoded.toString())

        assertNull(restored.state)
        assertEquals("ACCOUNT_CREDENTIAL_SLOT_MISMATCH", restored.diagnosticCode)
    }

    @Test
    fun registryEntryWithoutCredentialIsRejected() {
        val first = firstSession()
        val second = secondSession()
        val state = AndroidAccountCredentialState.Empty.upsertAndSelect(first)
        val encoded = JSONObject(encodeAndroidAccountCredentialState(state))
            .put(
                "account_registry_v1",
                encodeNextcloudAccountRegistry(
                    NextcloudAccountRegistry.Empty
                        .upsertAndSelect(first.accountRecord())
                        .upsertAndSelect(second.accountRecord()),
                ),
            )

        val restored = decodeAndroidAccountCredentialState(encoded.toString())

        assertNull(restored.state)
        assertEquals("ACCOUNT_CREDENTIAL_SLOT_MISMATCH", restored.diagnosticCode)
    }

    @Test
    fun removingTheActiveSlotRetainsOtherCredentialsWithoutSelectingOne() {
        val first = firstSession()
        val second = secondSession()
        val state = AndroidAccountCredentialState.Empty
            .upsertAndSelect(first)
            .upsertAndSelect(second)
            .remove(second.accountId)
        val restarted = requireNotNull(
            decodeAndroidAccountCredentialState(encodeAndroidAccountCredentialState(state)).state,
        )

        assertNull(restarted.activeSession)
        assertEquals(setOf(first.accountId), restarted.sessions.keys)
        assertNull(restarted.registry.activeAccountId)
        assertFalse(restarted.sessions.values.any { session -> session.appPassword == second.appPassword })
    }

    @Test
    fun malformedLegacyRegistryFallsBackWithoutDiscardingTheValidSession() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var migrated: String? = null
        val malformed = JSONObject(legacyPayload(firstSession()))
            .put("account_registry_v1", "{not-json")
            .toString()

        val restored = restoreAndroidAccountCredentialState(
            encoded = malformed,
            persistMigrated = { migrated = it },
            recordDiagnostic = diagnostics::add,
        )

        assertEquals(firstSession(), requireNotNull(restored).activeSession)
        assertNotNull(migrated)
        assertEquals(listOf("ACCOUNT_REGISTRY_MALFORMED"), diagnostics.mapNotNull { it.code })
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    @Test
    fun unsupportedFutureLegacyRegistryIsNotPersistedOver() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var migrated = false
        val futureRegistry = """{"version":2,"futureAccounts":[]}"""
        val payload = JSONObject(legacyPayload(firstSession()))
            .put("account_registry_v1", futureRegistry)
            .toString()

        val restored = restoreAndroidAccountCredentialState(
            encoded = payload,
            persistMigrated = { migrated = true },
            recordDiagnostic = diagnostics::add,
        )

        val readOnly = requireNotNull(restored)
        assertEquals(firstSession(), readOnly.activeSession)
        assertFalse(migrated)
        assertEquals(listOf("ACCOUNT_REGISTRY_VERSION_UNSUPPORTED"), diagnostics.mapNotNull { it.code })
        assertFailsWith<IllegalStateException> { readOnly.upsertAndSelect(secondSession()) }
        assertFailsWith<IllegalStateException> { readOnly.select(firstSession().accountId) }
        assertFailsWith<IllegalStateException> { readOnly.remove(firstSession().accountId) }
        assertFailsWith<IllegalStateException> { encodeAndroidAccountCredentialState(readOnly) }
    }

    @Test
    fun migrationFailureUsesABoundedCauseWithoutPrivateValues() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        val restored = restoreAndroidAccountCredentialState(
            encoded = legacyPayload(firstSession()),
            persistMigrated = { error("private-app-password at cloud.example.test for alice") },
            recordDiagnostic = diagnostics::add,
        )

        assertEquals(firstSession(), requireNotNull(restored).activeSession)
        val diagnostic = diagnostics.single()
        assertEquals("ACCOUNT_CREDENTIAL_STORE_MIGRATION_FAILED", diagnostic.code)
        val exception = assertNotNull(diagnostic.exception)
        assertNull(exception.message)
        assertDiagnosticsExcludePrivateValues(diagnostics)
    }

    @Test
    fun accountRegistryInsideTheStoreContainsNoCredential() {
        val session = firstSession()
        val payload = JSONObject(
            encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(session)),
        )
        val registry = payload.getString("account_registry_v1")

        assertFalse(registry.contains(session.appPassword))
        assertFalse(registry.contains("appPassword"))
        assertEquals(1, payload.getJSONArray("credentials").length())
    }

    private fun assertDiagnosticsExcludePrivateValues(diagnostics: List<SupportDiagnosticEventDraft>) {
        val rendered = diagnostics.joinToString()
        assertFalse(rendered.contains("private-app-password"))
        assertFalse(rendered.contains("second-private-password"))
        assertFalse(rendered.contains("alice"))
        assertFalse(rendered.contains("cloud.example.test"))
    }

    private fun recordingEditor(
        commitResult: Boolean,
        calls: MutableList<String>,
    ): SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, _ ->
        calls += method.name
        when (method.name) {
            "commit" -> commitResult
            "apply" -> Unit
            else -> proxy
        }
    } as SharedPreferences.Editor

    private fun legacyPayload(session: NextcloudSession): String = JSONObject()
        .put("serverUrl", session.serverUrl)
        .put("loginName", session.loginName)
        .put("appPassword", session.appPassword)
        .toString()

    private fun firstSession() = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = "alice",
        appPassword = "private-app-password",
    )

    private fun secondSession() = NextcloudSession(
        serverUrl = "https://second.example.test/nextcloud",
        loginName = "bob",
        appPassword = "second-private-password",
    )
}
