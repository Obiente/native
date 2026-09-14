package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncLocalRoot
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class AndroidFileSyncRootAcquisitionOwnershipTest {
    private val session = NextcloudSession("https://cloud.example.test", "person", "synthetic-password")
    private val root = FileSyncLocalRoot("content://example.documents/tree/folder", "Folder")

    @Test fun accountRemovalDuringProviderMetadataCannotAcquireAGrant(): Unit = runBlocking {
        var current: NextcloudSession? = session
        var acquired = false
        assertFailsWith<AndroidFileSyncRootAcquisitionRevokedException> {
            acquireCurrentAccountFileSyncRoot(session, { current }, { true }, {
                current = null
                "Folder"
            }, AndroidAccountOperationGuard()) { acquired = true; root }
        }
        assertFalse(acquired)
    }

    @Test fun credentialRotationDuringProviderMetadataCannotAcquireAGrant(): Unit = runBlocking {
        var current = session
        var acquired = false
        assertFailsWith<AndroidFileSyncRootAcquisitionRevokedException> {
            acquireCurrentAccountFileSyncRoot(session, { current }, { true }, {
                current = session.copy(appPassword = "replacement")
                "Folder"
            }, AndroidAccountOperationGuard()) { acquired = true; root }
        }
        assertFalse(acquired)
    }

    @Test fun cancellationDuringProviderMetadataCannotAcquireAGrant(): Unit = runBlocking {
        var active = true
        var acquired = false
        assertFailsWith<CancellationException> {
            acquireCurrentAccountFileSyncRoot(session, { session }, { active }, {
                active = false
                "Folder"
            }, AndroidAccountOperationGuard()) { acquired = true; root }
        }
        assertFalse(acquired)
    }

    @Test fun cancellationDuringSessionReloadCannotAcquireAGrant(): Unit = runBlocking {
        var active = true
        var acquired = false
        assertFailsWith<CancellationException> {
            acquireCurrentAccountFileSyncRoot(session, { active = false; session }, { active }, {
                "Folder"
            }, AndroidAccountOperationGuard()) { acquired = true; root }
        }
        assertFalse(acquired)
    }

    @Test fun metadataHoldsNoLeaseButAcquisitionHoldsBothAccountIdentities(): Unit = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val identities = androidAccountOperationIdentities(session)
        val selected = acquireCurrentAccountFileSyncRoot(session, { session }, { true }, {
            assertTrue(runBlocking { guard.tryWithAccounts(identities, { false }, { true }) })
            "Folder"
        }, guard) { name ->
            assertEquals("Folder", name)
            identities.forEach { identity ->
                assertFalse(runBlocking { guard.tryWithAccount(identity, { false }, { true }) })
            }
            root
        }
        assertEquals(root, selected)
    }

    @Test fun removalAndIdenticalAccountReadditionCannotReviveAnOldPicker() {
        val fixture = Fixture()
        val beforeChooser = AndroidFileSyncRootAcquisitionGenerations.capture(fixture.account)
        fixture.lifecycle.retireAccountSetup(fixture.account, AndroidFileSyncPersistedState())
        assertFailsWith<AndroidFileSyncRootAcquisitionRevokedException> {
            fixture.lifecycle.acquire(fixture.account, root.localRootId, root.displayName, beforeChooser)
        }
        assertEquals(0, fixture.grants.takes)
        assertTrue(fixture.store.list().isEmpty())
        fixture.lifecycle.acquire(fixture.account, root.localRootId, root.displayName,
            AndroidFileSyncRootAcquisitionGenerations.capture(fixture.account))
        assertEquals(1, fixture.grants.takes)
    }

    @Test fun failedRetirementStillFencesTheOldPickerBeforeTouchingGrants() {
        val fixture = Fixture()
        val beforeChooser = AndroidFileSyncRootAcquisitionGenerations.capture(fixture.account)
        fixture.lifecycle.acquire(fixture.account, root.localRootId, root.displayName, beforeChooser)
        fixture.grants.failRelease = true
        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.retireAccountSetup(fixture.account, AndroidFileSyncPersistedState())
        }
        assertFailsWith<AndroidFileSyncRootAcquisitionRevokedException> {
            fixture.lifecycle.acquire(fixture.account, root.localRootId, root.displayName, beforeChooser)
        }
        assertEquals(1, fixture.grants.takes)
        assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)
    }

    @Test fun canonicalRetirementRejectsChooserFromEarlierServerSpelling() {
        val original = session.copy(loginName = "alias-" + UUID.randomUUID())
        val replacement = original.copy(serverUrl = "https://CLOUD.example.test:443/")
        assertEquals(original.accountId, replacement.accountId)
        val oldRaw = AndroidFileSyncCapabilityAccountId(NextcloudDocumentIds.accountKey(original))
        val newRaw = AndroidFileSyncCapabilityAccountId(NextcloudDocumentIds.accountKey(replacement))
        assertFalse(oldRaw == newRaw)
        val fixture = Fixture(oldRaw)
        val beforeChooser = AndroidFileSyncRootAcquisitionGenerations.capture(oldRaw, original.accountId.storageKey)
        AndroidFileSyncRootAcquisitionGenerations.retireCanonical(replacement.accountId.storageKey)
        fixture.lifecycle.retireAccountSetup(newRaw, AndroidFileSyncPersistedState())
        assertFailsWith<AndroidFileSyncRootAcquisitionRevokedException> {
            fixture.lifecycle.acquire(oldRaw, root.localRootId, root.displayName, beforeChooser)
        }
        assertEquals(0, fixture.grants.takes)
        fixture.lifecycle.acquire(oldRaw, root.localRootId, root.displayName,
            AndroidFileSyncRootAcquisitionGenerations.capture(oldRaw, original.accountId.storageKey))
        assertEquals(1, fixture.grants.takes)
    }

    private class Fixture(val account: AndroidFileSyncCapabilityAccountId = AndroidFileSyncCapabilityAccountId(UUID.randomUUID().toString())) {
        val store = AndroidFileSyncCapabilityStore(object : AndroidFileSyncCapabilityEncryptedStorage {
            private var value: String? = null
            override fun read() = value
            override fun write(value: String): Boolean { this.value = value; return true }
        }, object : AndroidFileSyncCapabilityCipher {
            override fun encrypt(value: String) = value
            override fun decrypt(value: String) = value
        })
        val grants = Grants()
        val lifecycle = AndroidFileSyncCapabilityLifecycle(store, grants, UUID.randomUUID().toString())
    }

    private class Grants : AndroidFileSyncGrantAccess {
        var takes = 0
        var granted = false
        var failRelease = false
        override fun exactGrant(uri: String) = AndroidFileSyncGrantState(granted, granted)
        override fun takeExactReadWriteGrant(uri: String) { takes++; granted = true }
        override fun releaseExactGrant(uri: String, read: Boolean, write: Boolean) {
            check(!failRelease) { "Synthetic grant failure" }
            granted = false
        }
    }
}
