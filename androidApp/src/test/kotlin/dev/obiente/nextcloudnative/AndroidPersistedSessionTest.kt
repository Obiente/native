package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.SupportDiagnosticEventDraft
import dev.obiente.nextcloudnative.app.accountRecord
import dev.obiente.nextcloudnative.app.encodeNextcloudAccountRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AndroidPersistedSessionTest {
    @Test
    fun credentialStoreGuardKeepsMigrationAndMutationWritesOrdered() {
        val guard = AndroidAccountCredentialStoreGuard()
        val migrationEntered = CountDownLatch(1)
        val releaseMigration = CountDownLatch(1)
        val mutationAttempted = CountDownLatch(1)
        val mutationEntered = CountDownLatch(1)

        val migration = thread {
            guard.serialize {
                migrationEntered.countDown()
                check(releaseMigration.await(5, TimeUnit.SECONDS))
            }
        }
        check(migrationEntered.await(5, TimeUnit.SECONDS))
        val mutation = thread {
            mutationAttempted.countDown()
            guard.serialize { mutationEntered.countDown() }
        }
        check(mutationAttempted.await(5, TimeUnit.SECONDS))

        assertFalse(mutationEntered.await(100, TimeUnit.MILLISECONDS))
        releaseMigration.countDown()
        migration.join()
        mutation.join()
        assertEquals(0L, mutationEntered.count)
    }

    @Test
    fun invalidCredentialStoreRecoveryPurgesCredentialBearingQuarantine() {
        val replacementWrites = linkedMapOf<String, String>()
        val replacementRemovals = linkedSetOf<String>()
        prepareInvalidAndroidAccountCredentialRecoveryEdit(
            editor = recoveryRecordingEditor(replacementWrites, replacementRemovals),
            replacementEncrypted = "new-encrypted-session",
        )

        assertFalse("encrypted_session_quarantine" in replacementWrites)
        assertTrue("encrypted_session_quarantine" in replacementRemovals)
        assertEquals("new-encrypted-session", replacementWrites["encrypted_session"])
        assertTrue("emulator_test_read_only" in replacementRemovals)

        val resetWrites = linkedMapOf<String, String>()
        val resetRemovals = linkedSetOf<String>()
        prepareInvalidAndroidAccountCredentialRecoveryEdit(
            editor = recoveryRecordingEditor(resetWrites, resetRemovals),
            replacementEncrypted = null,
        )

        assertFalse("encrypted_session_quarantine" in resetWrites)
        assertTrue("encrypted_session_quarantine" in resetRemovals)
        assertTrue("encrypted_session" in resetRemovals)
        assertTrue("emulator_test_read_only" in resetRemovals)
    }

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
    fun workerAccountResolutionRecoversALegacyAggregateBeforeEnumeration() {
        val session = firstSession()
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord())
        var aggregateRecoveryCount = 0

        val resolved = resolveStoredAndroidAccountSession(
            accountIdentity = NextcloudDocumentIds.accountKey(session),
            listAccounts = {
                recoverAndroidCredentialFreeRegistryForCredentialLoad(restored = null) {
                    aggregateRecoveryCount += 1
                    registry
                }?.accounts.orEmpty()
            },
            loadSession = { accountId -> session.takeIf { it.accountId == accountId } },
        )

        assertEquals(session, resolved)
        assertEquals(1, aggregateRecoveryCount)
    }

    @Test
    fun clearingRecoveredIndependentStateRemovesOnlyItsActiveAccount() {
        val first = firstSession()
        val second = secondSession()
        val recovered = requireNotNull(
            AndroidAccountCredentialState.Empty
                .upsertAndSelect(first)
                .upsertAndSelect(second)
                .select(first.accountId),
        )

        val cleared = removeActiveAndroidAccountCredentialState(recovered)

        assertNull(cleared.activeSession)
        assertEquals(listOf(second.accountRecord()), cleared.registry.accounts)
        assertEquals(second, cleared.sessions[second.accountId])
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
    fun equivalentReauthenticationRetainsThePersistedAndroidWorkIdentity() {
        val original = firstSession().copy(serverUrl = "https://CLOUD.EXAMPLE.TEST:443/")
        val reauthenticated = firstSession().copy(appPassword = "rotated-private-password")
        assertEquals(original.accountId, reauthenticated.accountId)

        val updated = AndroidAccountCredentialState.Empty
            .upsertAndSelect(original)
            .upsertAndSelect(reauthenticated)

        val active = requireNotNull(updated.activeSession)
        assertEquals(original.serverUrl, active.serverUrl)
        assertEquals(reauthenticated.appPassword, active.appPassword)
        assertEquals(NextcloudDocumentIds.accountKey(original), NextcloudDocumentIds.accountKey(active))
        assertEquals(NextcloudDocumentIds.cacheAccountId(original), NextcloudDocumentIds.cacheAccountId(active))
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
        assertEquals("failed", diagnostics.single().outcome)
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
        assertEquals("failed", diagnostics.single().outcome)
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
        assertEquals("recovered", diagnostics.single().outcome)
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
        assertEquals("unsupported", diagnostics.single().outcome)
        assertFailsWith<IllegalStateException> { readOnly.upsertAndSelect(secondSession()) }
        assertFailsWith<IllegalStateException> { readOnly.select(firstSession().accountId) }
        assertFailsWith<IllegalStateException> { readOnly.remove(firstSession().accountId) }
        assertFailsWith<IllegalStateException> { encodeAndroidAccountCredentialState(readOnly) }
    }

    @Test
    fun unsupportedFutureCredentialStoreIsReadOnlyAndNeverMigrated() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()
        var migrated = false
        val future = JSONObject(
            encodeAndroidAccountCredentialState(AndroidAccountCredentialState.Empty.upsertAndSelect(firstSession())),
        ).put("version", 3).toString()

        val restored = restoreAndroidAccountCredentialStore(
            encoded = future,
            persistMigrated = { migrated = true },
            recordDiagnostic = diagnostics::add,
        )

        assertNull(restored.state)
        assertEquals(3, restored.unsupportedVersion)
        assertFalse(migrated)
        assertEquals(
            listOf("ACCOUNT_CREDENTIAL_STORE_VERSION_UNSUPPORTED"),
            diagnostics.mapNotNull { it.code },
        )
        assertEquals("unsupported", diagnostics.single().outcome)
        assertFalse(
            androidCredentialStoreAllowsSessionRestore(
                AndroidAccountCredentialStoreRead.Unsupported("encrypted-future-store", 3),
            ),
        )
        assertTrue(
            androidCredentialStoreAllowsSessionRestore(
                AndroidAccountCredentialStoreRead.Invalid("encrypted-malformed-store"),
            ),
        )
        assertFalse(
            androidCredentialStoreAllowsSessionRestore(
                AndroidAccountCredentialStoreRead.Available(
                    AndroidAccountCredentialState.Empty.upsertAndSelect(firstSession())
                        .copy(mutationsAllowed = false),
                ),
            ),
        )
        assertTrue(
            androidCredentialStoreAllowsSessionRestore(
                AndroidAccountCredentialStoreRead.Available(
                    AndroidAccountCredentialState.Empty.upsertAndSelect(firstSession()),
                ),
            ),
        )
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
        assertEquals("failed", diagnostic.outcome)
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

    @Test
    fun accountListingDecodesTheCredentialFreeRegistryWithoutASecretPayload() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
        val encoded = encodeNextcloudAccountRegistry(registry)

        assertFalse(encoded.contains(first.appPassword))
        assertFalse(encoded.contains(second.appPassword))
        assertEquals(registry, decodeAndroidCredentialFreeRegistry(encoded))
    }

    @Test
    fun malformedCredentialFreeRegistryDefersCredentialBearingRecovery() {
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(firstSession().accountRecord())
        var recoveryAttempted = false

        val restored = restoreAndroidCredentialFreeRegistry("{not-json")

        assertFalse(recoveryAttempted)
        assertNull(restored.registry)
        assertTrue(restored.credentialRecoveryRequired)
        assertEquals("ACCOUNT_REGISTRY_MALFORMED", restored.diagnosticCode)

        val recovered = recoverAndroidCredentialFreeRegistryForCredentialLoad(restored) {
            recoveryAttempted = true
            registry
        }

        assertTrue(recoveryAttempted)
        assertEquals(registry, recovered)
    }

    @Test
    fun missingCredentialFreeRegistryIsRecoveredOnlyForCredentialLoad() {
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(firstSession().accountRecord())
        var recoveryAttempted = false

        val recovered = recoverAndroidCredentialFreeRegistryForCredentialLoad(restored = null) {
            recoveryAttempted = true
            registry
        }

        assertTrue(recoveryAttempted)
        assertEquals(registry, recovered)
    }

    @Test
    fun futureCredentialFreeRegistryIsNeverRebuiltFromAnOlderAggregate() {
        var recoveryAttempted = false

        val restored = restoreAndroidCredentialFreeRegistry("""{"version":99,"accounts":[]}""")
        val recovered = recoverAndroidCredentialFreeRegistryForCredentialLoad(restored) {
            recoveryAttempted = true
            NextcloudAccountRegistry.Empty
        }

        assertFalse(recoveryAttempted)
        assertNull(recovered)
        assertFalse(restored.credentialRecoveryRequired)
        assertEquals("ACCOUNT_REGISTRY_VERSION_UNSUPPORTED", restored.diagnosticCode)
    }

    @Test
    fun explicitResetDiscardsMalformedRegistryButPreservesFutureVersionState() {
        assertTrue(
            androidIndependentCredentialStateCanBeExplicitlyReset(
                restoreAndroidCredentialFreeRegistry("{not-json"),
            ),
        )
        assertFalse(
            androidIndependentCredentialStateCanBeExplicitlyReset(
                restoreAndroidCredentialFreeRegistry("""{"version":99,"accounts":[]}"""),
            ),
        )
        assertTrue(androidIndependentCredentialStateCanBeExplicitlyReset(null))
    }

    @Test
    fun credentialSlotReadDecryptsOnlyTheRequestedAccount() {
        val first = firstSession()
        val second = secondSession()
        val encryptedByKey = mapOf(
            androidAccountCredentialSlotKey(first.accountId) to "encrypted-first",
            androidAccountCredentialSlotKey(second.accountId) to "encrypted-second",
        )
        val requestedKeys = mutableListOf<String>()
        val decryptedValues = mutableListOf<String>()

        val restored = readAndroidAccountCredentialSlot(
            accountId = second.accountId,
            readEncrypted = { key ->
                requestedKeys += key
                encryptedByKey[key]
            },
            decrypt = { encrypted ->
                decryptedValues += encrypted
                "decoded-second"
            },
            decode = { decoded ->
                RestoredAndroidAccountCredentialState(
                    AndroidAccountCredentialState.Empty.upsertAndSelect(second)
                        .takeIf { decoded == "decoded-second" },
                )
            },
        )

        assertEquals(AndroidAccountCredentialSlotRead.Available(second), restored)
        assertEquals(listOf(androidAccountCredentialSlotKey(second.accountId)), requestedKeys)
        assertEquals(listOf("encrypted-second"), decryptedValues)
    }

    @Test
    fun credentialSlotReadRejectsASecretForAnotherAccount() {
        val first = firstSession()
        val second = secondSession()

        val restored = readAndroidAccountCredentialSlot(
            accountId = second.accountId,
            readEncrypted = { "encrypted-first" },
            decrypt = { "decoded-first" },
            decode = {
                RestoredAndroidAccountCredentialState(
                    AndroidAccountCredentialState.Empty.upsertAndSelect(first),
                )
            },
        )

        assertEquals(AndroidAccountCredentialSlotRead.Invalid, restored)
    }

    @Test
    fun futureCredentialSlotBlocksAggregateFallbackAndRepair() {
        val session = firstSession()
        val future = JSONObject(encodeAndroidPersistedSession(session)).put("version", 3).toString()

        val restored = readAndroidAccountCredentialSlot(
            accountId = session.accountId,
            readEncrypted = { "encrypted-future-slot" },
            decrypt = { future },
            decode = ::decodeAndroidAccountCredentialState,
        )

        assertEquals(AndroidAccountCredentialSlotRead.Unsupported(3), restored)
    }

    @Test
    fun pendingCleanupMatchesCanonicalAccountAndRetainsOriginalWorkIdentity() {
        val original = firstSession().copy(serverUrl = "HTTPS://CLOUD.EXAMPLE.TEST:443/")
        val replacement = firstSession().copy(serverUrl = "https://cloud.example.test")
        assertEquals(original.accountId, replacement.accountId)
        assertFalse(NextcloudDocumentIds.accountKey(original) == NextcloudDocumentIds.accountKey(replacement))
        val encoded = encodeAndroidPendingAccountRemovalCleanup(pendingAndroidAccountRemovalCleanup(original))
        val decoded = requireNotNull(decodeAndroidPendingAccountRemovalCleanup(encoded))

        val pending = pendingAndroidAccountRemovalCleanupForSession(replacement, listOf(decoded))

        assertEquals(NextcloudDocumentIds.accountKey(original), requireNotNull(pending).workIdentity)
        assertEquals(NextcloudDocumentIds.cacheAccountId(original), pending.previewCacheIdentity)
        assertFalse(pending.previewCacheIdentity == NextcloudDocumentIds.cacheAccountId(replacement))
        assertEquals(original.accountId.storageKey, pending.accountStorageKey)
    }

    @Test
    fun malformedPendingCleanupRowsAreIsolatedFromValidRecoveryWork() {
        val valid = pendingAndroidAccountRemovalCleanup(firstSession())

        val restored = restoreAndroidPendingAccountRemovalCleanups(
            setOf(encodeAndroidPendingAccountRemovalCleanup(valid), "truncated-row"),
        )

        assertEquals(setOf(valid), restored.cleanups)
        assertEquals(1, restored.malformedEntryCount)
    }

    @Test
    fun damagedCredentialSlotRecoversFromTheMatchingAggregateCredential() {
        val session = firstSession()
        val aggregate = AndroidAccountCredentialState.Empty.upsertAndSelect(session)

        val recovered = recoverAndroidAccountCredentialSlot(
            accountId = session.accountId,
            registry = aggregate.registry,
            storedSlot = null,
            aggregate = aggregate,
        )

        assertEquals(session, recovered)
    }

    @Test
    fun credentialSlotRecoveryRejectsAnAggregateThatDoesNotMatchTheVisibleRegistry() {
        val original = firstSession().copy(serverUrl = "https://CLOUD.EXAMPLE:443/")
        val aggregate = AndroidAccountCredentialState.Empty.upsertAndSelect(firstSession())
        val visibleRegistry = NextcloudAccountRegistry.Empty.upsertAndSelect(original.accountRecord())

        val recovered = recoverAndroidAccountCredentialSlot(
            accountId = original.accountId,
            registry = visibleRegistry,
            storedSlot = null,
            aggregate = aggregate,
        )

        assertNull(recovered)
    }

    @Test
    fun validIndependentSlotsCanRecoverAroundAMalformedAggregateStore() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
        val slots = mapOf(first.accountId to first, second.accountId to second)

        val restored = reconstructAndroidAccountCredentialState(registry, slots::get)

        assertEquals(slots, requireNotNull(restored).sessions)
        assertEquals(second, restored.activeSession)
    }

    @Test
    fun corruptInactiveCredentialSlotDoesNotHideTheHealthyActiveAccount() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
            .select(first.accountId)
            .let(::requireNotNull)

        val restored = reconstructAndroidAccountCredentialState(registry) { accountId ->
            first.takeIf { accountId == first.accountId }
        }

        assertEquals(mapOf(first.accountId to first), requireNotNull(restored).sessions)
        assertEquals(listOf(first.accountRecord(), second.accountRecord()), restored.registry.accounts)
        assertEquals(first, restored.activeSession)

        val roundTrip = decodeAndroidAccountCredentialState(encodeAndroidAccountCredentialState(restored)).state
        assertEquals(restored, roundTrip)
    }

    @Test
    fun corruptActiveCredentialSlotStillFailsClosed() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())

        assertNull(
            reconstructAndroidAccountCredentialState(registry) { accountId ->
                first.takeIf { accountId == first.accountId }
            },
        )
    }

    @Test
    fun corruptActiveCredentialSlotCanBeRemovedWithoutDroppingOtherAccounts() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())

        val recovered = reconstructAndroidAccountCredentialStateForRemoval(
            registry = registry,
            accountId = second.accountId,
            loadSession = { accountId -> first.takeIf { accountId == first.accountId } },
        )

        val afterRemoval = requireNotNull(recovered).remove(second.accountId)
        assertNull(afterRemoval.activeSession)
        assertEquals(listOf(first.accountRecord()), afterRemoval.registry.accounts)
        assertEquals(mapOf(first.accountId to first), afterRemoval.sessions)
    }

    @Test
    fun corruptActiveCredentialSlotCannotAuthorizeRemovingAnotherAccount() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())

        val recovered = reconstructAndroidAccountCredentialStateForRemoval(
            registry = registry,
            accountId = first.accountId,
            loadSession = { accountId -> first.takeIf { accountId == first.accountId } },
        )

        assertNull(recovered)
    }

    @Test
    fun validIndependentSlotsRecoverWhenTheAggregateKeyIsAbsent() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
        val slots = mapOf(first.accountId to first, second.accountId to second)

        val restored = restoreAndroidAccountCredentialStateWithoutAggregate(
            encodedRegistry = encodeNextcloudAccountRegistry(registry),
            loadSession = slots::get,
        )

        assertEquals(slots, requireNotNull(restored).sessions)
        assertEquals(second, restored.activeSession)
    }

    @Test
    fun independentSlotRecoveryRejectsRegistryCredentialMismatch() {
        val first = firstSession()
        val second = secondSession()
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(second.accountRecord())

        assertNull(reconstructAndroidAccountCredentialState(registry) { first })
    }

    @Test
    fun queuedUploadResumeFailureDoesNotHideACommittedAccountSelection() = runBlocking {
        val events = mutableListOf<String>()

        resumeAndroidQueuedUploadsAfterSelection(
            resume = {
                events += "resume"
                error("Synthetic unreadable upload queue")
            },
            notifyDocumentRootsChanged = { events += "notify" },
            recordFailure = { events += "diagnose" },
        )

        assertEquals(listOf("resume", "diagnose", "notify"), events)
    }

    @Test
    fun previewCleanupFailureDoesNotHideACommittedAccountSelection() {
        val previous = firstSession()
        val selected = secondSession()
        val events = mutableListOf<String>()

        clearAndroidPreviousPreviewAfterCommittedSelection(
            previousSession = previous,
            selectedSession = selected,
            clearPreviewAccount = {
                events += "clear-preview"
                error("synthetic preview cleanup failure")
            },
            recordFailure = { events += "diagnose-cleanup" },
        )

        assertEquals(listOf("clear-preview", "diagnose-cleanup"), events)
    }

    @Test
    fun failedAccountTransitionDoesNotClearExternalHandoffs() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            commitAndroidAccountTransitionBeforeHandoffCleanup(
                commitTransition = {
                    events += "commit-transition"
                    error("synthetic credential persistence failure")
                },
                clearHandoffs = { events += "clear-handoffs" },
                recordFailure = { events += "diagnose-cleanup" },
            )
        }

        assertEquals(listOf("commit-transition"), events)
    }

    @Test
    fun accountTransitionPersistsHandoffCleanupBeforeItCanCommit() {
        val writes = linkedMapOf<String, String>()

        prepareAndroidExternalHandoffCleanup(recoveryRecordingEditor(writes, linkedSetOf()))

        assertEquals("pending", writes[ANDROID_PENDING_EXTERNAL_HANDOFF_CLEANUP_KEY])
    }

    @Test
    fun handoffCleanupFailureDoesNotHideACommittedAccountTransition() {
        val events = mutableListOf<String>()

        commitAndroidAccountTransitionBeforeHandoffCleanup(
            commitTransition = { events += "commit-transition" },
            clearHandoffs = {
                events += "clear-handoffs"
                error("synthetic handoff cleanup failure")
            },
            recordFailure = { events += "diagnose-cleanup" },
        )

        assertEquals(listOf("commit-transition", "clear-handoffs", "diagnose-cleanup"), events)
    }

    @Test
    fun queuedUploadResumeCancellationNotifiesBeforePropagating() {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            runBlocking {
                resumeAndroidQueuedUploadsAfterSelection(
                    resume = { throw CancellationException("Selection owner stopped") },
                    notifyDocumentRootsChanged = { events += "notify" },
                    recordFailure = { events += "diagnose" },
                )
            }
        }
        assertEquals(listOf("notify"), events)
    }

    @Test
    fun parentCancellationStopsQueuedUploadResumeAndStillNotifies() = runBlocking {
        val resumeEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val selection = launch {
            resumeAndroidQueuedUploadsAfterSelection(
                resume = {
                    events += "resume"
                    resumeEntered.complete(Unit)
                    awaitCancellation()
                },
                notifyDocumentRootsChanged = { events += "notify" },
                recordFailure = { events += "diagnose" },
            )
        }
        resumeEntered.await()

        selection.cancelAndJoin()

        assertEquals(listOf("resume", "notify"), events)
    }

    @Test
    fun activeAccountRemovalDeletesTheCredentialBeforeIrreversibleUploadCleanup() = runBlocking {
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = true,
            prepareAccountRemoval = { events += "prepare-removal" },
            removeQueuedUploads = { events += "remove-uploads" },
            clearActiveAccount = { events += "clear-account" },
            rollbackActiveRemoval = { events += "rollback-active" },
            persistInactiveRemoval = { events += "persist-inactive" },
            rollbackInactiveRemoval = { events += "rollback-inactive" },
            completeCommittedCleanup = { events += "complete-cleanup" },
        )

        assertEquals(listOf("prepare-removal", "clear-account", "remove-uploads", "complete-cleanup"), events)
    }

    @Test
    fun blockedAccountRemovalDoesNotDeleteCredentialsOrQueuedWork() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeAndroidAccountCredentialData(
                active = true,
                prepareAccountRemoval = {
                    events += "prepare-removal"
                    error("pending document writeback")
                },
                removeQueuedUploads = { events += "remove-uploads" },
                clearActiveAccount = { events += "clear-account" },
                rollbackActiveRemoval = { events += "rollback-active" },
                persistInactiveRemoval = { events += "persist-inactive" },
                rollbackInactiveRemoval = { events += "rollback-inactive" },
            )
        }

        assertEquals(listOf("prepare-removal"), events)
    }

    @Test
    fun recoveredInvalidStoreRemovalAlsoCleansQueuedAccountWork() = runBlocking {
        val events = mutableListOf<String>()

        removeRecoveredAndroidAccountCredentialData(
            removeQueuedUploads = { events += "remove-queued-work" },
            clearRecoveredAccount = { events += "clear-recovered-account" },
            rollbackRecoveredAccount = { events += "rollback-recovered-account" },
        )

        assertEquals(listOf("clear-recovered-account", "remove-queued-work"), events)
    }

    @Test
    fun activeSignOutDeletesQueuedUploadsAfterTheCredentialIsCleared() = runBlocking {
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = true,
            removeQueuedUploads = { events += "remove-uploads" },
            clearActiveAccount = { events += "clear-session" },
            rollbackActiveRemoval = { events += "rollback-session" },
            persistInactiveRemoval = {},
            rollbackInactiveRemoval = {},
        )

        assertEquals(listOf("clear-session", "remove-uploads"), events)
    }

    @Test
    fun failedActiveUploadCleanupKeepsTheCredentialRemovalCommitted() = runBlocking {
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = true,
            removeQueuedUploads = {
                events += "remove-uploads"
                error("synthetic cleanup failure")
            },
            clearActiveAccount = { events += "clear-account" },
            rollbackActiveRemoval = { events += "rollback-active" },
            persistInactiveRemoval = { events += "persist-inactive" },
            rollbackInactiveRemoval = { events += "rollback-inactive" },
            completeCommittedCleanup = { events += "complete-cleanup" },
            recordCommittedCleanupFailure = { events += "diagnose-cleanup" },
        )

        assertEquals(listOf("clear-account", "remove-uploads", "diagnose-cleanup"), events)
    }

    @Test
    fun accountRemovalCleanupAttemptsEveryOwnerBeforeReportingFailure() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            runAndroidAccountRemovalCleanups(
                listOf(
                    {
                        events += "remove-offline"
                        error("synthetic offline cleanup failure")
                    },
                    { events += "remove-shares" },
                    { events += "remove-uploads" },
                    { events += "remove-sync-pairs" },
                ),
            )
        }

        assertEquals(
            listOf("remove-offline", "remove-shares", "remove-uploads", "remove-sync-pairs"),
            events,
        )
    }

    @Test
    fun failedActiveCredentialRemovalDoesNotStartUploadCleanupAndAttemptsRollback() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeAndroidAccountCredentialData(
                active = true,
                removeQueuedUploads = { events += "remove-uploads" },
                clearActiveAccount = {
                    events += "clear-account"
                    error("synthetic credential persistence failure")
                },
                rollbackActiveRemoval = { events += "rollback-active" },
                persistInactiveRemoval = { events += "persist-inactive" },
                rollbackInactiveRemoval = { events += "rollback-inactive" },
            )
        }

        assertEquals(listOf("clear-account", "rollback-active"), events)
    }

    @Test
    fun failedUnavailableAccountRemovalRestoresRecoveredStateBeforeClearingCleanup() = runBlocking {
        val recovered = AndroidAccountCredentialState.Empty
            .upsertAndSelect(firstSession())
            .upsertAndSelect(secondSession())
        val removed = recovered.remove(firstSession().accountId)
        var persisted = recovered
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeAndroidAccountCredentialData(
                active = false,
                removeQueuedUploads = { events += "remove-uploads" },
                clearActiveAccount = { events += "clear-account" },
                rollbackActiveRemoval = { events += "rollback-active" },
                persistInactiveRemoval = {
                    persisted = removed
                    events += "persist-removal"
                    error("synthetic commit result failure")
                },
                rollbackInactiveRemoval = {
                    rollbackUnavailableAndroidAccountRemoval(
                        recovered = recovered,
                        persistRecovered = { state ->
                            persisted = state
                            events += "restore-recovered"
                        },
                        clearCleanup = { events += "clear-cleanup" },
                    )
                },
            )
        }

        assertEquals(recovered, persisted)
        assertEquals(
            listOf("persist-removal", "restore-recovered", "clear-cleanup"),
            events,
        )
    }

    @Test
    fun cancelledInactiveAccountCleanupKeepsTheCredentialRemovalCommitted() = runBlocking {
        val cleanupEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val removal = launch {
            removeAndroidAccountCredentialData(
                active = false,
                removeQueuedUploads = {
                    events += "remove-uploads"
                    cleanupEntered.complete(Unit)
                    awaitCancellation()
                },
                clearActiveAccount = { events += "clear-account" },
                rollbackActiveRemoval = { events += "rollback-active" },
                persistInactiveRemoval = { events += "persist-removal" },
                rollbackInactiveRemoval = { events += "rollback" },
                onInactiveRemovalCommitted = { events += "notify-roots" },
            )
        }
        cleanupEntered.await()

        removal.cancelAndJoin()

        assertEquals(listOf("persist-removal", "notify-roots", "remove-uploads"), events)
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

    private fun recoveryRecordingEditor(
        writes: MutableMap<String, String>,
        removals: MutableSet<String>,
    ): SharedPreferences.Editor = Proxy.newProxyInstance(
        SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java),
    ) { proxy, method, arguments ->
        val callArguments = arguments.orEmpty()
        when (method.name) {
            "putString" -> {
                writes[callArguments[0] as String] = callArguments[1] as String
                proxy
            }
            "remove" -> {
                removals += callArguments[0] as String
                proxy
            }
            "commit" -> true
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
