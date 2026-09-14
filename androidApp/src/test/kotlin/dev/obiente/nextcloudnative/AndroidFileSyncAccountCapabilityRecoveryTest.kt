package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncPair
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidFileSyncAccountCapabilityRecoveryTest {
    @Test
    fun `current owned record from a failed uncommitted transition is reclaimed from authoritative state`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ACCOUNT_ID, ROOT_URI, "Notes")
        fixture.lifecycle.bindReady(ACCOUNT_ID, ROOT_URI, PAIR_ID)
        fixture.storage.failWriteNumber = fixture.storage.writes + 1
        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.abandonUncommittedPair(PAIR_ID)
        }
        assertEquals(AndroidFileSyncCapabilityPhase.Owned, fixture.store.list().single().phase)
        fixture.lifecycle.reconcile(state())
        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `account retirement cleans current acquisitions or keeps cleanup pending on failure`() {
        for (failRelease in listOf(false, true)) {
            val fixture = fixture()
            fixture.store.add(record(NEW_GENERATION, AndroidFileSyncCapabilityPhase.Acquiring))
            fixture.grants.readGranted = true
            fixture.grants.writeGranted = true
            fixture.grants.failRelease = failRelease
            if (failRelease) {
                assertFailsWith<IllegalStateException> { fixture.lifecycle.retireAccountSetup(ACCOUNT_ID, state()) }
                assertTrue(fixture.lifecycle.hasRecoveryWork())
                assertTrue(fixture.grants.readGranted)
                fixture.grants.failRelease = false
            }
            fixture.lifecycle.retireAccountSetup(ACCOUNT_ID, state())
            assertTrue(fixture.store.list().isEmpty())
            assertFalse(fixture.grants.readGranted)
            assertFalse(fixture.grants.writeGranted)
        }
    }

    @Test
    fun `authoritative configured pairs retain current acquiring and ready grants during setup retirement`() {
        for (phase in listOf(AndroidFileSyncCapabilityPhase.Acquiring, AndroidFileSyncCapabilityPhase.Ready)) {
            val fixture = fixture()
            fixture.store.add(record(NEW_GENERATION, phase))
            fixture.grants.readGranted = true
            fixture.grants.writeGranted = true
            fixture.lifecycle.retireAccountSetup(ACCOUNT_ID, state(pair()))
            assertEquals(AndroidFileSyncCapabilityPhase.Owned, fixture.store.list().single().phase)
            assertEquals(setOf(PAIR_ID), fixture.store.list().single().pairIds)
            assertTrue(fixture.grants.readGranted)
            assertTrue(fixture.grants.writeGranted)
        }
    }

    @Test
    fun `live reselect remains abandonable until explicit account retirement preserves its configured grant`() {
        for (retire in listOf(false, true)) {
            val fixture = fixture()
            fixture.grants.readGranted = true
            fixture.grants.writeGranted = true
            val selection = fixture.lifecycle.acquire(ACCOUNT_ID, ROOT_URI, "Notes again")
            fixture.lifecycle.reconcile(state(pair()))
            assertEquals(AndroidFileSyncCapabilityPhase.Ready, fixture.store.list().single().phase)
            assertTrue(fixture.store.list().single().pairIds.isEmpty())
            if (retire) {
                fixture.lifecycle.retireAccountSetup(ACCOUNT_ID, state(pair()))
                assertEquals(AndroidFileSyncCapabilityPhase.Owned, fixture.store.list().single().phase)
                assertEquals(setOf(PAIR_ID), fixture.store.list().single().pairIds)
            } else {
                assertTrue(fixture.lifecycle.abandonSelection(requireNotNull(selection.savedStateId)))
                assertTrue(fixture.store.list().isEmpty())
            }
            assertTrue(fixture.grants.readGranted)
            assertTrue(fixture.grants.writeGranted)
        }
    }

    private fun fixture() = Fixture()
    private class Fixture {
        val storage = Storage()
        val grants = Grants()
        val store = AndroidFileSyncCapabilityStore(storage, object : AndroidFileSyncCapabilityCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String) = value
        })
        val lifecycle = AndroidFileSyncCapabilityLifecycle(store, grants, NEW_GENERATION)
    }
    private class Storage : AndroidFileSyncCapabilityEncryptedStorage {
        var value: String? = null
        var writes = 0
        var failWriteNumber: Int? = null
        override fun read() = value
        override fun write(value: String): Boolean {
            writes++
            if (writes == failWriteNumber) return false
            this.value = value
            return true
        }
    }
    private class Grants : AndroidFileSyncGrantAccess {
        var readGranted = false
        var writeGranted = false
        var failRelease = false
        override fun exactGrant(uri: String) = AndroidFileSyncGrantState(readGranted, writeGranted)
        override fun takeExactReadWriteGrant(uri: String) { readGranted = true; writeGranted = true }
        override fun releaseExactGrant(uri: String, read: Boolean, write: Boolean) {
            check(!failRelease) { "synthetic grant failure" }
            if (read) readGranted = false
            if (write) writeGranted = false
        }
    }
    private fun pair() = FileSyncPair(PAIR_ID, "account", ROOT_URI, "Notes", FileSyncConfiguration(deviceLabel = "Phone"))
    private fun state(vararg pairs: FileSyncPair) = AndroidFileSyncPersistedState(coordinator = FileSyncCoordinatorState(pairs.toList()))
    private fun record(generation: String, phase: AndroidFileSyncCapabilityPhase) = AndroidFileSyncCapabilityRecord(
        id = UUID.randomUUID().toString(), uri = ROOT_URI, displayName = "Notes", phase = phase,
        processGeneration = generation, preExistingReadGrant = false, preExistingWriteGrant = false,
        accountId = ACCOUNT_ID,
    )
    private companion object {
        const val ROOT_URI = "content://example.documents/tree/notes"
        val ACCOUNT_ID = AndroidFileSyncCapabilityAccountId("account")
        val PAIR_ID = UUID.randomUUID().toString()
        val NEW_GENERATION = UUID.randomUUID().toString()
    }
}
