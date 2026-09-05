package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex

class AndroidFileSyncCapabilityLifecycleTest {
    @Test
    fun `cancelled result delivery abandons the selected root`() {
        val dispatcher = PausedDispatcher()
        val scopeJob = Job()
        var resumeSelection: (() -> Unit)? = null
        var delivered = false
        var abandoned: String? = null
        val selectionJob = CoroutineScope(scopeJob + dispatcher).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { continuation ->
                resumeSelection = {
                    resumeFileSyncRootSelection(
                        continuation,
                        dev.obiente.nextcloudnative.app.FileSyncLocalRoot(ROOT_URI, "Notes"),
                        abandon = { abandoned = it },
                    )
                }
            }
            delivered = true
        }

        checkNotNull(resumeSelection).invoke()
        selectionJob.cancel()
        dispatcher.runAll()

        assertTrue(selectionJob.isCancelled)
        assertFalse(delivered)
        assertEquals(ROOT_URI, abandoned)
        scopeJob.cancel()
    }

    @Test
    fun `acquisition records intent before taking and ends ready`() {
        val fixture = fixture()

        val root = fixture.lifecycle.acquire(ROOT_URI, "Notes")

        assertEquals(ROOT_URI, root.localRootId)
        assertEquals(listOf("query", "take", "query"), fixture.grants.events)
        assertEquals(AndroidFileSyncCapabilityPhase.Ready, fixture.store.list().single().phase)
    }

    @Test
    fun `pre-existing exact grant is never taken or revoked`() {
        val fixture = fixture(readGranted = true, writeGranted = true)
        val root = fixture.lifecycle.acquire(ROOT_URI, "Notes")

        assertTrue(fixture.lifecycle.abandonSelection(root.localRootId))

        assertEquals(listOf("query", "query"), fixture.grants.events)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `cleanup releases only the permission mode acquired for sync`() {
        val fixture = fixture(readGranted = true)
        val root = fixture.lifecycle.acquire(ROOT_URI, "Notes")

        assertTrue(fixture.lifecycle.abandonSelection(root.localRootId))

        assertTrue(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertEquals(listOf(false to true), fixture.grants.releaseRequests)
    }

    @Test
    fun `grant inspection failure prevents acquisition`() {
        val fixture = fixture()
        fixture.grants.failQuery = true

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.acquire(ROOT_URI, "Notes")
        }

        assertEquals(listOf("query"), fixture.grants.events)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `duplicate exact uri is rejected before a second grant is taken`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ROOT_URI, "Notes")
        fixture.grants.events.clear()

        assertFailsWith<IllegalArgumentException> {
            fixture.lifecycle.acquire(ROOT_URI, "Notes again")
        }

        assertEquals(listOf("query"), fixture.grants.events)
        assertEquals(1, fixture.store.list().size)
    }

    @Test
    fun `saf roots cannot be shared by a second pair`() {
        assertTrue(hasDuplicateAndroidFileSyncRoot(listOf(pair()), "other-account", ROOT_URI, "Archive"))
    }

    @Test
    fun `non-saf roots retain the existing per-account destination rule`() {
        val mediaPair = pair().copy(localRootId = "media-store://primary/DCIM/Camera")

        assertFalse(
            hasDuplicateAndroidFileSyncRoot(
                listOf(mediaPair),
                mediaPair.accountId,
                mediaPair.localRootId,
                "Archive",
            ),
        )
        assertTrue(
            hasDuplicateAndroidFileSyncRoot(
                listOf(mediaPair),
                mediaPair.accountId,
                mediaPair.localRootId,
                mediaPair.remoteRootPath,
            ),
        )
    }

    @Test
    fun `failed ready persistence releases a newly acquired grant`() {
        val fixture = fixture()
        fixture.storage.failWriteNumber = 2

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.acquire(ROOT_URI, "Notes")
        }

        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `ambiguous acquiring commit cleans a possibly written record before take`() {
        val fixture = fixture()
        fixture.storage.failWriteNumber = 1
        fixture.storage.persistFailedWrite = true

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.acquire(ROOT_URI, "Notes")
        }

        assertEquals(listOf("query", "query"), fixture.grants.events)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `repeated persistence failure retains acquiring evidence for restart`() {
        val fixture = fixture()
        fixture.storage.failWritesFrom = 2

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.acquire(ROOT_URI, "Notes")
        }

        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        val retained = fixture.store.list()
        assertEquals(AndroidFileSyncCapabilityPhase.Acquiring, retained.single().phase)
    }

    @Test
    fun `pair cleanup is durable before release and retries a failed release`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ROOT_URI, "Notes")
        fixture.lifecycle.bindReady(ROOT_URI, PAIR_ID)

        assertTrue(fixture.lifecycle.preparePairCleanup(PAIR_ID))
        assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)
        fixture.grants.failRelease = true
        assertFalse(fixture.lifecycle.finishPairCleanup(PAIR_ID))
        assertEquals(AndroidFileSyncCapabilityPhase.CleanupPending, fixture.store.list().single().phase)

        fixture.grants.failRelease = false
        assertTrue(fixture.lifecycle.finishPairCleanup(PAIR_ID))
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `prior process ready record is released when no pair owns it`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedReady(OLD_GENERATION)

        fixture.lifecycle.reconcile(state())

        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `current process ready record remains available to the live setup ui`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedReady(NEW_GENERATION)

        fixture.lifecycle.reconcile(state())

        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertEquals(AndroidFileSyncCapabilityPhase.Ready, fixture.store.list().single().phase)
    }

    @Test
    fun `reselect before startup reconcile remains abandonable`() {
        val fixture = fixture(generation = NEW_GENERATION, readGranted = true, writeGranted = true)
        val selection = fixture.lifecycle.acquire(ROOT_URI, "Notes again")

        fixture.lifecycle.reconcile(state(pair()))

        val record = fixture.store.list().single()
        assertEquals(AndroidFileSyncCapabilityPhase.Ready, record.phase)
        assertTrue(record.pairIds.isEmpty())
        assertTrue(fixture.lifecycle.abandonSelection(selection.localRootId))
        assertTrue(fixture.store.list().isEmpty())
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
    }

    @Test
    fun `restart binds a unique ready record to its committed pair`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedReady(OLD_GENERATION)

        fixture.lifecycle.reconcile(state(pair()))

        val record = fixture.store.list().single()
        assertEquals(AndroidFileSyncCapabilityPhase.Owned, record.phase)
        assertEquals(setOf(PAIR_ID), record.pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
    }

    @Test
    fun `cleanup pending returns to owned when pair deletion did not commit`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedOwned(OLD_GENERATION, AndroidFileSyncCapabilityPhase.CleanupPending)

        fixture.lifecycle.reconcile(state(pair()))

        assertEquals(AndroidFileSyncCapabilityPhase.Owned, fixture.store.list().single().phase)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
    }

    @Test
    fun `prior process owned record without a pair is cleaned`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedOwned(OLD_GENERATION, AndroidFileSyncCapabilityPhase.Owned)

        fixture.lifecycle.reconcile(state())

        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertTrue(fixture.store.list().isEmpty())
    }

    @Test
    fun `unique legacy root is adopted before removal releases its grant`() {
        val fixture = fixture(generation = NEW_GENERATION, readGranted = true, writeGranted = true)

        fixture.lifecycle.reconcile(state(pair()))

        val record = fixture.store.list().single()
        assertEquals(AndroidFileSyncCapabilityPhase.Owned, record.phase)
        assertEquals(setOf(PAIR_ID), record.pairIds)
        assertTrue(fixture.lifecycle.preparePairCleanup(PAIR_ID))
        assertTrue(fixture.lifecycle.finishPairCleanup(PAIR_ID))
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `read-only legacy grant is adopted and released on removal`() {
        val fixture = fixture(generation = NEW_GENERATION, readGranted = true)

        fixture.lifecycle.reconcile(state(pair()))
        assertTrue(fixture.lifecycle.preparePairCleanup(PAIR_ID))
        assertTrue(fixture.lifecycle.finishPairCleanup(PAIR_ID))

        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertEquals(listOf(true to true), fixture.grants.releaseRequests)
    }

    @Test
    fun `write-only legacy grant is adopted and released on removal`() {
        val fixture = fixture(generation = NEW_GENERATION, writeGranted = true)

        fixture.lifecycle.reconcile(state(pair()))
        assertTrue(fixture.lifecycle.preparePairCleanup(PAIR_ID))
        assertTrue(fixture.lifecycle.finishPairCleanup(PAIR_ID))

        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertEquals(listOf(true to true), fixture.grants.releaseRequests)
    }

    @Test
    fun `legacy shared roots are adopted and released after the last owner is removed`() {
        val fixture = fixture(generation = NEW_GENERATION, readGranted = true, writeGranted = true)

        fixture.lifecycle.reconcile(state(pair(), pair(id = OTHER_PAIR_ID)))

        assertEquals(setOf(PAIR_ID, OTHER_PAIR_ID), fixture.store.list().single().pairIds)
        assertTrue(fixture.lifecycle.preparePairCleanup(PAIR_ID))
        assertFalse(fixture.lifecycle.finishPairCleanup(PAIR_ID))
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertTrue(fixture.lifecycle.preparePairCleanup(OTHER_PAIR_ID))
        assertTrue(fixture.lifecycle.finishPairCleanup(OTHER_PAIR_ID))
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `legacy duplicates adopt a ready grant without releasing it`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedReady(OLD_GENERATION)

        fixture.lifecycle.reconcile(state(pair(), pair(id = OTHER_PAIR_ID)))

        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        val record = fixture.store.list().single()
        assertEquals(AndroidFileSyncCapabilityPhase.Owned, record.phase)
        assertEquals(setOf(PAIR_ID, OTHER_PAIR_ID), record.pairIds)
    }

    @Test
    fun `same uri pair replaces a stale owner without releasing the live grant`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedOwned(OLD_GENERATION, AndroidFileSyncCapabilityPhase.Owned)

        fixture.lifecycle.reconcile(state(pair(id = OTHER_PAIR_ID)))

        assertEquals(setOf(OTHER_PAIR_ID), fixture.store.list().single().pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertFalse("release" in fixture.grants.events)
    }

    @Test
    fun `owner id attached to another root fails closed`() {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedOwned(OLD_GENERATION, AndroidFileSyncCapabilityPhase.Owned)

        fixture.lifecycle.reconcile(state(pair(localRootId = "content://example.documents/tree/other")))

        assertEquals(setOf(PAIR_ID), fixture.store.list().single().pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertTrue(fixture.grants.events.isEmpty())
    }

    @Test
    fun `unreadable capability state releases nothing`() {
        val storage = FakeStorage("unreadable")
        val grants = FakeGrantAccess(readGranted = true, writeGranted = true)
        val store = AndroidFileSyncCapabilityStore(storage, ThrowingCipher)
        val lifecycle = AndroidFileSyncCapabilityLifecycle(store, grants, NEW_GENERATION)

        assertFailsWith<AndroidFileSyncCapabilityRecoveryException> {
            lifecycle.reconcile(state())
        }

        assertTrue(grants.readGranted)
        assertTrue(grants.writeGranted)
        assertTrue(grants.events.isEmpty())
    }

    @Test
    fun `startup leaves grants unchanged when pair state is unreadable`() = runBlocking {
        val fixture = fixture(generation = NEW_GENERATION)
        fixture.seedReady(OLD_GENERATION)
        fixture.grants.events.clear()

        reconcileFileSyncCapabilities(
            lock = Mutex(),
            load = { error("pair state unavailable") },
            capabilities = fixture.lifecycle,
        )

        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
        assertTrue(fixture.grants.events.isEmpty())
        assertEquals(AndroidFileSyncCapabilityPhase.Ready, fixture.store.list().single().phase)
    }

    @Test
    fun `failed pair save retains ownership when authoritative reload contains the pair`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ROOT_URI, "Notes")
        fixture.lifecycle.bindReady(ROOT_URI, PAIR_ID)

        recoverFailedFileSyncPairSave(PAIR_ID, { state(pair()) }, fixture.lifecycle::abandonUncommittedPair)

        assertEquals(setOf(PAIR_ID), fixture.store.list().single().pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
    }

    @Test
    fun `failed pair save releases ownership only when authoritative reload excludes the pair`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ROOT_URI, "Notes")
        fixture.lifecycle.bindReady(ROOT_URI, PAIR_ID)

        recoverFailedFileSyncPairSave(PAIR_ID, { state() }, fixture.lifecycle::abandonUncommittedPair)

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `failed pair save retains ownership when authoritative reload is unreadable`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ROOT_URI, "Notes")
        fixture.lifecycle.bindReady(ROOT_URI, PAIR_ID)

        recoverFailedFileSyncPairSave(
            PAIR_ID,
            load = { error("pair state unavailable") },
            abandonUncommittedPair = fixture.lifecycle::abandonUncommittedPair,
        )

        assertEquals(setOf(PAIR_ID), fixture.store.list().single().pairIds)
        assertTrue(fixture.grants.readGranted)
        assertTrue(fixture.grants.writeGranted)
    }

    @Test
    fun `postcommit pair removal failure releases from the authoritative state immediately`() {
        val fixture = fixture()
        fixture.lifecycle.acquire(ROOT_URI, "Notes")
        fixture.lifecycle.bindReady(ROOT_URI, PAIR_ID)
        fixture.lifecycle.preparePairCleanup(PAIR_ID)

        assertFailsWith<IllegalStateException> {
            fixture.lifecycle.persistPairRemoval(load = { state() }) {
                error("save reported failure after commit")
            }
        }

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `pair cleanup retries an unavailable grant query against authoritative removal`() {
        val fixture = preparedCleanup()
        fixture.grants.failQueryCount = 1

        fixture.lifecycle.finishPairCleanupOrRetry(PAIR_ID) { state() }

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    @Test
    fun `pair cleanup retries a failed grant release against authoritative removal`() {
        val fixture = preparedCleanup()
        fixture.grants.failReleaseCount = 1

        fixture.lifecycle.finishPairCleanupOrRetry(PAIR_ID) { state() }

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
        assertEquals(2, fixture.grants.releaseRequests.size)
    }

    @Test
    fun `pair cleanup retries a failed capability record removal`() {
        val fixture = preparedCleanup()
        fixture.storage.failWriteNumber = fixture.storage.writes + 1

        fixture.lifecycle.finishPairCleanupOrRetry(PAIR_ID) { state() }

        assertTrue(fixture.store.list().isEmpty())
        assertFalse(fixture.grants.readGranted)
        assertFalse(fixture.grants.writeGranted)
    }

    private fun preparedCleanup(): Fixture = fixture().also {
        it.lifecycle.acquire(ROOT_URI, "Notes")
        it.lifecycle.bindReady(ROOT_URI, PAIR_ID)
        it.lifecycle.preparePairCleanup(PAIR_ID)
    }

    private fun fixture(
        generation: String = NEW_GENERATION,
        readGranted: Boolean = false,
        writeGranted: Boolean = false,
    ): Fixture {
        val storage = FakeStorage()
        val store = AndroidFileSyncCapabilityStore(storage, IdentityCipher)
        val grants = FakeGrantAccess(readGranted, writeGranted)
        return Fixture(storage, store, grants, AndroidFileSyncCapabilityLifecycle(store, grants, generation))
    }

    private fun pair(id: String = PAIR_ID, localRootId: String = ROOT_URI) = FileSyncPair(
        id = id,
        accountId = "account",
        localRootId = localRootId,
        remoteRootPath = "Notes",
        configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )

    private fun state(vararg pairs: FileSyncPair) = AndroidFileSyncPersistedState(
        coordinator = dev.obiente.nextcloudnative.app.FileSyncCoordinatorState(pairs.toList()),
        localDisplayNames = pairs.associate { it.id to "Notes" },
    )

    private data class Fixture(
        val storage: FakeStorage,
        val store: AndroidFileSyncCapabilityStore,
        val grants: FakeGrantAccess,
        val lifecycle: AndroidFileSyncCapabilityLifecycle,
    ) {
        fun seedReady(generation: String) {
            store.add(record(generation, AndroidFileSyncCapabilityPhase.Ready))
            grants.readGranted = true
            grants.writeGranted = true
        }

        fun seedOwned(generation: String, phase: AndroidFileSyncCapabilityPhase) {
            store.add(record(generation, phase, pairIds = setOf(PAIR_ID)))
            grants.readGranted = true
            grants.writeGranted = true
        }
    }

    private class FakeStorage(var value: String? = null) : AndroidFileSyncCapabilityEncryptedStorage {
        var writes = 0
        var failWriteNumber: Int? = null
        var failWritesFrom: Int? = null
        var persistFailedWrite = false

        override fun read(): String? = value

        override fun write(value: String): Boolean {
            writes += 1
            if (writes == failWriteNumber || writes >= (failWritesFrom ?: Int.MAX_VALUE)) {
                if (persistFailedWrite) this.value = value
                return false
            }
            this.value = value
            return true
        }
    }

    private class FakeGrantAccess(
        var readGranted: Boolean,
        var writeGranted: Boolean,
    ) : AndroidFileSyncGrantAccess {
        var failQuery = false
        var failQueryCount = 0
        var failRelease = false
        var failReleaseCount = 0
        val events = mutableListOf<String>()
        val releaseRequests = mutableListOf<Pair<Boolean, Boolean>>()

        override fun exactGrant(uri: String): AndroidFileSyncGrantState {
            events += "query"
            if (failQuery || failQueryCount > 0) {
                failQueryCount = (failQueryCount - 1).coerceAtLeast(0)
                error("grant metadata unavailable")
            }
            return AndroidFileSyncGrantState(readGranted, writeGranted)
        }

        override fun takeExactReadWriteGrant(uri: String) {
            events += "take"
            readGranted = true
            writeGranted = true
        }

        override fun releaseExactGrant(uri: String, read: Boolean, write: Boolean) {
            events += "release"
            releaseRequests += read to write
            if (failRelease || failReleaseCount > 0) {
                failReleaseCount = (failReleaseCount - 1).coerceAtLeast(0)
                error("release failed")
            }
            if (read) readGranted = false
            if (write) writeGranted = false
        }
    }

    private object IdentityCipher : AndroidFileSyncCapabilityCipher {
        override fun encrypt(value: String): String = value
        override fun decrypt(value: String): String = value
    }

    private object ThrowingCipher : AndroidFileSyncCapabilityCipher {
        override fun encrypt(value: String): String = error("not used")
        override fun decrypt(value: String): String = error("cipher unavailable")
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private companion object {
        const val ROOT_URI = "content://example.documents/tree/notes"
        val RECORD_ID: String = UUID.randomUUID().toString()
        val PAIR_ID: String = UUID.randomUUID().toString()
        val OTHER_PAIR_ID: String = UUID.randomUUID().toString()
        val OLD_GENERATION: String = UUID.randomUUID().toString()
        val NEW_GENERATION: String = UUID.randomUUID().toString()

        fun record(
            generation: String,
            phase: AndroidFileSyncCapabilityPhase,
            pairIds: Set<String> = emptySet(),
        ) = AndroidFileSyncCapabilityRecord(
            id = RECORD_ID,
            uri = ROOT_URI,
            displayName = "Notes",
            phase = phase,
            processGeneration = generation,
            preExistingReadGrant = false,
            preExistingWriteGrant = false,
            pairIds = pairIds,
        )
    }
}
