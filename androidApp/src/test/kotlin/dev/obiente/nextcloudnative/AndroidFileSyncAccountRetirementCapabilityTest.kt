package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidFileSyncAccountRetirementCapabilityTest {
    @Test
    fun `duplicate legacy roots release once after every retired owner is persisted`() = runBlocking {
        val fixture = fixture()
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT), pair(SECOND_PAIR_ID, REMOVED_ACCOUNT))
        fixture.lifecycle.reconcile(state(retired))

        retire(fixture.lifecycle, retired) { Unit }

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertEquals(1, fixture.grants.releaseCount)
    }

    @Test
    fun `retained account owner keeps a shared legacy root grant`() = runBlocking {
        val fixture = fixture()
        val retired = pair(FIRST_PAIR_ID, REMOVED_ACCOUNT)
        val retained = pair(SECOND_PAIR_ID, RETAINED_ACCOUNT)
        fixture.lifecycle.reconcile(state(listOf(retired, retained)))

        retire(fixture.lifecycle, listOf(retired)) { Unit }

        val record = fixture.store.list().single()
        assertEquals(AndroidFileSyncCapabilityPhase.Owned, record.phase)
        assertEquals(setOf(SECOND_PAIR_ID), record.pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertEquals(0, fixture.grants.releaseCount)
    }

    @Test
    fun `successful retirement persists cleanup before releasing the grant`() = runBlocking {
        val fixture = fixture()
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT))
        fixture.lifecycle.reconcile(state(retired))
        val events = mutableListOf<String>()

        retireConfiguredFileSyncAccountPairs(
            retiredPairs = retired,
            reconcileLocalDownloads = { true },
            cancelSchedule = {},
            cancelNotification = {},
            prepareLocalGrantCleanup = { pairId ->
                events += "prepare-$pairId"
                fixture.lifecycle.preparePairCleanup(pairId)
            },
            persistRetirement = {
                assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)
                assertTrue(fixture.grants.readGranted)
                events += "persist"
            },
            finishLocalGrantCleanup = { pairId ->
                events += "finish-$pairId"
                fixture.lifecycle.finishPairCleanup(pairId)
            },
        )

        assertEquals(listOf("prepare-$FIRST_PAIR_ID", "persist", "finish-$FIRST_PAIR_ID"), events)
        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `failed grant preparation leaves account sync schedules active`() = runBlocking {
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT))
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = retired,
                reconcileLocalDownloads = { true },
                cancelSchedule = { events += "cancel-schedule" },
                cancelNotification = { events += "cancel-notification" },
                prepareLocalGrantCleanup = {
                    events += "prepare-grant"
                    error("synthetic grant preparation failure")
                },
                persistRetirement = { events += "persist-retirement" },
                finishLocalGrantCleanup = { events += "finish-grant" },
            )
        }

        assertEquals(listOf("prepare-grant"), events)
    }

    @Test
    fun `failed precommit save restores ownership from the authoritative pair on restart`() = runBlocking {
        val fixture = fixture(OLD_GENERATION)
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT))
        val authoritative = state(retired)
        fixture.lifecycle.reconcile(authoritative)

        assertFailsWith<IllegalStateException> {
            retire(fixture.lifecycle, retired) { error("save failed before commit") }
        }
        assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)

        restarted(fixture).reconcile(authoritative)

        val record = fixture.store.list().single()
        assertEquals(AndroidFileSyncCapabilityPhase.Owned, record.phase)
        assertEquals(setOf(FIRST_PAIR_ID), record.pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
    }

    @Test
    fun `failed postcommit save releases from authoritative removal on restart`() = runBlocking {
        val fixture = fixture(OLD_GENERATION)
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT))
        fixture.lifecycle.reconcile(state(retired))

        assertFailsWith<IllegalStateException> {
            retire(fixture.lifecycle, retired) { error("save reported failure after commit") }
        }
        assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)

        restarted(fixture).reconcile(state(emptyList()))

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `empty account retirement retry still reconciles committed capability cleanup`() {
        val fixture = fixture()
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT))
        fixture.lifecycle.reconcile(state(retired))
        fixture.lifecycle.preparePairCleanup(FIRST_PAIR_ID)

        val remaining = reconcileAndroidFileSyncAccountRetirement(
            state(emptyList()),
            REMOVED_ACCOUNT,
            fixture.lifecycle,
        )

        assertTrue(remaining.isEmpty())
        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `failed retirement grant cleanup remains journaled for an empty-state retry`() = runBlocking {
        val fixture = fixture()
        val retired = listOf(pair(FIRST_PAIR_ID, REMOVED_ACCOUNT))
        fixture.lifecycle.reconcile(state(retired))
        fixture.grants.failRelease = true

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = retired,
                reconcileLocalDownloads = { true },
                cancelSchedule = {},
                cancelNotification = {},
                prepareLocalGrantCleanup = fixture.lifecycle::preparePairCleanup,
                persistRetirement = {},
                finishLocalGrantCleanup = { pairId ->
                    fixture.lifecycle.finishPairCleanupOrRetry(pairId) { state(emptyList()) }
                },
            )
        }
        assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)

        fixture.grants.failRelease = false
        val remaining = reconcileAndroidFileSyncAccountRetirement(
            state(emptyList()),
            REMOVED_ACCOUNT,
            fixture.lifecycle,
        )

        assertTrue(remaining.isEmpty())
        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    private suspend fun retire(
        lifecycle: AndroidFileSyncCapabilityLifecycle,
        retiredPairs: List<FileSyncPair>,
        persist: suspend () -> Unit,
    ) {
        retireConfiguredFileSyncAccountPairs(
            retiredPairs = retiredPairs,
            reconcileLocalDownloads = { true },
            cancelSchedule = {},
            cancelNotification = {},
            prepareLocalGrantCleanup = { pairId -> lifecycle.preparePairCleanup(pairId) },
            persistRetirement = persist,
            finishLocalGrantCleanup = { pairId ->
                lifecycle.finishPairCleanupOrRetry(pairId) { state(emptyList()) }
            },
        )
    }

    private fun fixture(generation: String = NEW_GENERATION): Fixture {
        val store = AndroidFileSyncCapabilityStore(MemoryStorage(), IdentityCipher)
        val grants = GrantAccess()
        return Fixture(store, grants, AndroidFileSyncCapabilityLifecycle(store, grants, generation))
    }

    private fun restarted(fixture: Fixture) =
        AndroidFileSyncCapabilityLifecycle(fixture.store, fixture.grants, NEW_GENERATION)

    private fun pair(id: String, accountId: String) = FileSyncPair(
        id = id,
        accountId = accountId,
        localRootId = ROOT_URI,
        remoteRootPath = "Notes",
        configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )

    private fun state(pairs: List<FileSyncPair>) = AndroidFileSyncPersistedState(
        coordinator = FileSyncCoordinatorState(pairs),
        localDisplayNames = pairs.associate { it.id to "Notes" },
    )

    private data class Fixture(
        val store: AndroidFileSyncCapabilityStore,
        val grants: GrantAccess,
        val lifecycle: AndroidFileSyncCapabilityLifecycle,
    )

    private class MemoryStorage : AndroidFileSyncCapabilityEncryptedStorage {
        private var value: String? = null
        override fun read(): String? = value
        override fun write(value: String): Boolean {
            this.value = value
            return true
        }
    }

    private class GrantAccess : AndroidFileSyncGrantAccess {
        var readGranted = true
        var writeGranted = true
        var releaseCount = 0
        var failRelease = false

        override fun exactGrant(uri: String) = AndroidFileSyncGrantState(readGranted, writeGranted)
        override fun takeExactReadWriteGrant(uri: String) = error("Legacy adoption must not take a grant")
        override fun releaseExactGrant(uri: String, read: Boolean, write: Boolean) {
            releaseCount += 1
            if (failRelease) error("release failed")
            if (read) readGranted = false
            if (write) writeGranted = false
        }
    }

    private object IdentityCipher : AndroidFileSyncCapabilityCipher {
        override fun encrypt(value: String): String = value
        override fun decrypt(value: String): String = value
    }

    private companion object {
        const val ROOT_URI = "content://example.documents/tree/notes"
        const val REMOVED_ACCOUNT = "removed-account"
        const val RETAINED_ACCOUNT = "retained-account"
        const val FIRST_PAIR_ID = "10000000-0000-0000-0000-000000000001"
        const val SECOND_PAIR_ID = "10000000-0000-0000-0000-000000000002"
        const val OLD_GENERATION = "20000000-0000-0000-0000-000000000001"
        const val NEW_GENERATION = "20000000-0000-0000-0000-000000000002"
    }
}
