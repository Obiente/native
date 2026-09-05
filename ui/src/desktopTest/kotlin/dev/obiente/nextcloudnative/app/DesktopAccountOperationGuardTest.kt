package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.concurrent.thread

class DesktopAccountOperationGuardTest {
    @Test
    fun lateDurableWriterCannotPublishAfterRemovalAndCredentialReplacement() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val replacement = original.copy(appPassword = "replacement-password")
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var current: NextcloudSession? = original
        var durablePublished = false
        val removal = async {
            guard.serialize {
                current = replacement
                removalEntered.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalEntered.await()

        val staleWriter = async {
            guard.withAccountPrivateStatePublication(
                expectedSession = original,
                resolveSession = { current },
                unavailable = { false },
            ) {
                durablePublished = true
                true
            }
        }
        yield()
        assertFalse(staleWriter.isCompleted)
        releaseRemoval.complete(Unit)
        removal.await()

        assertFalse(staleWriter.await())
        assertFalse(durablePublished)
        assertTrue(
            guard.withAccountPrivateStatePublication(
                expectedSession = replacement,
                resolveSession = { current },
                unavailable = { false },
                publish = { true },
            ),
        )
    }

    @Test
    fun latePendingWriterCannotPublishAfterRemovalAndReadd() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val original = NextcloudSession("https://cloud.example.test", "alice", "old-password")
        val readded = original.copy(appPassword = "new-password")
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var current: NextcloudSession? = original
        var pendingPublished = false
        val removal = async {
            guard.serialize {
                current = readded
                removalEntered.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalEntered.await()

        val staleWriter = async {
            guard.withAccountPrivateStatePublication(
                expectedSession = original,
                resolveSession = { current },
                unavailable = { false },
            ) {
                pendingPublished = true
                true
            }
        }
        yield()
        releaseRemoval.complete(Unit)
        removal.await()

        assertFalse(staleWriter.await())
        assertFalse(pendingPublished)
        assertTrue(
            guard.withAccountPrivateStatePublication(
                expectedSession = readded,
                resolveSession = { current },
                unavailable = { false },
                publish = { true },
            ),
        )
    }

    @Test
    fun abortedAccountSelectionAlwaysRestartsDesktopSync() = runBlocking {
        var restartCount = 0

        assertFailsWith<CancellationException> {
            restartDesktopSyncAfterSelection<String>(
                select = { throw CancellationException("selection cancelled") },
                restart = { restartCount += 1 },
            )
        }
        assertFailsWith<IllegalStateException> {
            restartDesktopSyncAfterSelection<String>(
                select = { error("credential persistence failed") },
                restart = { restartCount += 1 },
            )
        }

        assertEquals(2, restartCount)
    }

    @Test
    fun resourceActivationCannotPassAConcurrentAccountMutation() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        var resourceActivated = false

        val mutation = async {
            guard.serialize {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()
        val activation = async {
            guard.serializeResourceActivation { resourceActivated = true }
        }
        yield()

        assertFalse(resourceActivated)
        releaseMutation.complete(Unit)
        mutation.await()
        activation.await()
        assertTrue(resourceActivated)
    }

    @Test
    fun synchronousRangeRegistrationCannotEnterDuringAccountMutation() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        val mutation = async {
            guard.serialize {
                mutationEntered.complete(Unit)
                releaseMutation.await()
            }
        }
        mutationEntered.await()

        assertFalse(guard.tryActivateResource { true })

        releaseMutation.complete(Unit)
        mutation.await()
        assertTrue(guard.tryActivateResource { true })
    }

    @Test
    fun accountMutationObservesAResourceRegisteredJustBeforeItStarts() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val mutationEntered = CompletableDeferred<Unit>()
        val registration = thread {
            assertTrue(
                guard.tryActivateResource {
                    registrationEntered.countDown()
                    check(releaseRegistration.await(5, TimeUnit.SECONDS))
                    true
                },
            )
        }
        check(registrationEntered.await(5, TimeUnit.SECONDS))

        val mutation = async(Dispatchers.Default) {
            guard.serialize { mutationEntered.complete(Unit) }
        }
        yield()
        assertFalse(mutationEntered.isCompleted)

        releaseRegistration.countDown()
        registration.join()
        mutation.await()
        assertTrue(mutationEntered.isCompleted)
    }

    @Test
    fun resourceActivationRejectsAStaleAccountAfterWaitingForTheGuard() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")
        val guard = DesktopAccountOperationGuard()
        var hydrationRegistered = false

        assertTrue(desktopResourceActivationMatchesActiveSession(first, first.copy()))
        assertFalse(desktopResourceActivationMatchesActiveSession(second, first))
        assertFalse(desktopResourceActivationMatchesActiveSession(null, first))
        assertFalse(
            desktopResourceActivationMatchesActiveSession(
                first.copy(appPassword = "rotated"),
                first,
            ),
        )
        assertFalse(
            guard.tryActivateResource {
                desktopResourceActivationMatchesActiveSession(second, first) &&
                    true.also { hydrationRegistered = true }
            },
        )
        assertFalse(hydrationRegistered)
    }

    @Test
    fun resourceDeactivationRejectsAStaleAccountAndAnotherAccountsProvider() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")
        val firstIdentity = desktopFileCacheAccountId(first)
        val secondIdentity = desktopFileCacheAccountId(second)

        assertTrue(desktopResourceDeactivationTargetsCurrentProvider(first, first.copy(), firstIdentity))
        assertFalse(desktopResourceDeactivationTargetsCurrentProvider(second, first, secondIdentity))
        assertFalse(desktopResourceDeactivationTargetsCurrentProvider(first, first, secondIdentity))
        assertFalse(desktopResourceDeactivationTargetsCurrentProvider(null, first, firstIdentity))
    }

    @Test
    fun syncRunRejectsAStaleAccountAfterWaitingForSelection() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")

        assertTrue(desktopSyncRunMatchesActiveSession(first, first.copy()))
        assertFalse(desktopSyncRunMatchesActiveSession(second, first))
        assertFalse(desktopSyncRunMatchesActiveSession(activeSession = null, first))
        assertFalse(
            desktopSyncRunMatchesActiveSession(
                first.copy(appPassword = "rotated"),
                first,
            ),
        )
    }

    @Test
    fun fileSyncPairCreationWaitsForRemovalAndRejectsTheStaleSession() = runBlocking {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")
        val guard = DesktopAccountOperationGuard()
        val removalEntered = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var current = first
        var pairCreated = false
        val removal = async {
            guard.serializeWhenSyncIdle {
                current = second
                removalEntered.complete(Unit)
                releaseRemoval.await()
            }
        }
        removalEntered.await()

        val result = async {
            guard.serializeWhenSyncIdle {
                if (!desktopSyncRunMatchesActiveSession(current, first)) {
                    "rejected"
                } else {
                    pairCreated = true
                    "created"
                }
            }
        }
        yield()

        assertFalse(result.isCompleted)
        releaseRemoval.complete(Unit)
        removal.await()
        assertEquals("rejected", result.await())
        assertFalse(pairCreated)
    }

    @Test
    fun sessionRevocationWaitsForSyncAndBlocksMutationsUntilLocalRemoval() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var localRemovalCommitted = false

        val sync = async {
            guard.withSyncRunLock {
                syncEntered.complete(Unit)
                releaseSync.await()
            }
        }
        syncEntered.await()
        val revocation = async {
            guard.serializeWhenSyncIdle {
                events += "preflight"
                events += "revoke"
                localRemovalCommitted = true
                events += "remove-local"
            }
        }
        yield()
        val laterMutation = async {
            guard.serialize { localRemovalCommitted }
        }
        yield()

        assertFalse(revocation.isCompleted)
        assertFalse(laterMutation.isCompleted)
        releaseSync.complete(Unit)
        sync.await()
        revocation.await()
        assertTrue(laterMutation.await())
        assertEquals(listOf("preflight", "revoke", "remove-local"), events)
    }

    @Test
    fun differentAccountSaveRequiresTheSelectionTransition() {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")

        assertFalse(desktopSessionSaveSwitchesAccount(null, first.accountId))
        assertFalse(desktopSessionSaveSwitchesAccount(first.accountId, first.accountId))
        assertTrue(desktopSessionSaveSwitchesAccount(first.accountId, second.accountId))
    }

    @Test
    fun activeCredentialReplacementRequiresLiveResourcesToClose() {
        val original = NextcloudSession("https://first.example.test", "alice", "one")

        assertFalse(desktopSessionSaveReplacesActiveCredential(activeSession = null, savedSession = original))
        assertFalse(desktopSessionSaveReplacesActiveCredential(original, original.copy()))
        assertTrue(
            desktopSessionSaveReplacesActiveCredential(
                original,
                original.copy(appPassword = "replacement-password"),
            ),
        )
        assertFalse(
            desktopSessionSaveReplacesActiveCredential(
                original,
                NextcloudSession("https://second.example.test", "alice", "replacement-password"),
            ),
        )
    }

    @Test
    fun blockedAccountSaveRecordsTheSelectionDiagnosticBeforeFailing() {
        val diagnostics = mutableListOf<SupportDiagnosticEventDraft>()

        assertFailsWith<IllegalStateException> {
            requireDesktopSessionSaveAllowed(allowed = false, recordBlocked = diagnostics::add)
        }

        assertEquals(listOf("ACCOUNT_SELECTION_ACTIVE_RESOURCES"), diagnostics.map { it.code })
    }

    @Test
    fun retainedSelectionReopensTheDesktopSessionOnlyAfterSuccess() {
        var reopenCount = 0
        val session = NextcloudSession("https://first.example.test", "alice", "one")

        assertNull(reopenDesktopSessionAfterSelection<String>(null) { reopenCount += 1 })
        assertEquals(session, reopenDesktopSessionAfterSelection(session) { reopenCount += 1 })
        assertEquals(1, reopenCount)
    }
    @Test
    fun removalCannotPassAConcurrentSelection() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val selectionStarted = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val selection = async {
            guard.serialize {
                events += "selection-started"
                selectionStarted.complete(Unit)
                releaseSelection.await()
                events += "selection-finished"
            }
        }
        selectionStarted.await()

        val removal = async {
            guard.serialize { events += "removal" }
        }
        yield()

        assertFalse(removal.isCompleted)
        releaseSelection.complete(Unit)
        selection.await()
        removal.await()
        assertEquals(listOf("selection-started", "selection-finished", "removal"), events)
    }

    @Test
    fun accountMutationWaitsForAnIndependentSyncRun() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val releaseSync = CompletableDeferred<Unit>()
        val syncStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val sync = async {
            guard.withSyncRunLock {
                syncStarted.complete(Unit)
                releaseSync.await()
            }
        }
        syncStarted.await()
        val mutation = async {
            guard.serializeWhenSyncIdle {
                events += "account-mutated"
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        assertEquals(emptyList(), events)
        releaseSync.complete(Unit)
        sync.await()
        mutation.await()
        assertEquals(listOf("account-mutated"), events)
    }

    @Test
    fun pairRemovalWaitsForTheSelectionSyncBoundary() = runBlocking {
        val guard = DesktopAccountOperationGuard()
        val selectionEntered = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        var removalEntered = false
        val selection = async {
            guard.serialize {
                guard.withSyncRunLock {
                    selectionEntered.complete(Unit)
                    releaseSelection.await()
                }
            }
        }
        selectionEntered.await()
        val removal = async {
            guard.withSyncRunLock { removalEntered = true }
        }
        yield()

        assertFalse(removalEntered)
        releaseSelection.complete(Unit)
        selection.await()
        removal.await()
        assertTrue(removalEntered)
    }

    @Test
    fun authenticatedMutationWaitsForSelectionAndRejectsTheStaleSession() = runBlocking {
        val first = NextcloudSession("https://first.example.test", "alice", "one")
        val second = NextcloudSession("https://second.example.test", "bob", "two")
        val guard = DesktopAccountOperationGuard()
        val selectionEntered = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        var current = first
        var requestSent = false
        val selection = async {
            guard.serialize {
                current = second
                selectionEntered.complete(Unit)
                releaseSelection.await()
            }
        }
        selectionEntered.await()

        val mutation = async {
            runCatching {
                guard.withAuthenticatedMutationSession(first, { current }) {
                    requestSent = true
                }
            }
        }
        yield()

        assertFalse(mutation.isCompleted)
        releaseSelection.complete(Unit)
        selection.await()
        assertTrue(mutation.await().isFailure)
        assertFalse(requestSent)
    }

    @Test
    fun pendingLinuxWritebackBlocksAccountRemoval() {
        requireDesktopAccountRemovalWritebacksResolved(0)
        assertFailsWith<IllegalStateException> { requireDesktopAccountRemovalWritebacksResolved(1) }
    }

    @Test
    fun failedCredentialRemovalRestoresProviderActivationPreference() = runBlocking {
        val events = mutableListOf<String>()

        val failure = runCatching {
            removeDesktopCredentialWithoutProviderReactivation(
                providerWasEnabled = true,
                clearProviderPreference = { events += "cleared" },
                restoreProviderPreference = { enabled -> events += "restored:$enabled" },
                removeCredential = {
                    events += "remove"
                    error("credential removal failed")
                },
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf("cleared", "remove", "restored:true"), events)
    }

    @Test
    fun failedProviderPreferenceClearRestoresThePreviousValue() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeDesktopCredentialWithoutProviderReactivation(
                providerWasEnabled = true,
                clearProviderPreference = {
                    events += "clear"
                    error("synthetic preference flush failure")
                },
                restoreProviderPreference = { enabled -> events += "restore:$enabled" },
                removeCredential = {
                    events += "remove"
                    true
                },
            )
        }

        assertEquals(listOf("clear", "restore:true"), events)
    }

    @Test
    fun successfulCredentialRemovalLeavesProviderPreferenceDisabled() = runBlocking {
        val events = mutableListOf<String>()

        assertTrue(
            removeDesktopCredentialWithoutProviderReactivation(
                providerWasEnabled = true,
                clearProviderPreference = { events += "cleared" },
                restoreProviderPreference = { enabled -> events += "restored:$enabled" },
                removeCredential = {
                    events += "remove"
                    true
                },
            ),
        )

        assertEquals(listOf("cleared", "remove"), events)
    }

    @Test
    fun committedCredentialRemovalFailureFinishesProviderTeardownWithoutReactivation() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeDesktopCredentialWithoutProviderReactivation(
                providerWasEnabled = true,
                clearProviderPreference = { events += "cleared" },
                restoreProviderPreference = { enabled -> events += "restored:$enabled" },
                removalCommitted = { true },
                finishCommittedRemoval = { events += "finish" },
                removeCredential = {
                    events += "remove"
                    error("synthetic post-commit credential cleanup failure")
                },
            )
        }

        assertEquals(listOf("cleared", "remove", "finish"), events)
    }

    @Test
    fun unknownCredentialCommitStatusNeitherReactivatesNorTearsDownTheProvider() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            removeDesktopCredentialWithoutProviderReactivation(
                providerWasEnabled = true,
                clearProviderPreference = { events += "cleared" },
                restoreProviderPreference = { enabled -> events += "restored:$enabled" },
                removalCommitted = {
                    events += "probe"
                    error("synthetic registry read failure")
                },
                commitStatusObserved = { events += "status:$it" },
                finishCommittedRemoval = { events += "finish" },
                removeCredential = {
                    events += "remove"
                    error("synthetic credential removal failure")
                },
            )
        }

        assertEquals(listOf("cleared", "remove", "probe", "status:null"), events)
        assertEquals(1, failure.suppressedExceptions.size)
    }

    @Test
    fun linuxWritesResumeOnlyAfterPositivelyKnownPrecommitFailure() {
        assertTrue(
            shouldResumeDesktopWritesAfterRemovalFailure(
                removalCommitted = false,
                remoteRevocationAttempted = false,
                credentialRemovalStatus = false,
            ),
        )
        assertFalse(shouldResumeDesktopWritesAfterRemovalFailure(false, false, null))
        assertFalse(shouldResumeDesktopWritesAfterRemovalFailure(false, true, false))
        assertFalse(shouldResumeDesktopWritesAfterRemovalFailure(true, false, true))
    }

    @Test
    fun committedInactiveRemovalSurvivesSyncPairCleanupFailure() = runBlocking {
        val events = mutableListOf<String>()

        val removed = removeDesktopAccountBeforeSyncPairCleanup(
            accountId = CLEANUP_ACCOUNT_ID,
            prepareCleanup = { _, _ -> events += "prepare-cleanup" },
            commitCleanup = { events += "commit-cleanup" },
            clearCleanup = { events += "clear-cleanup" },
            accountOwnership = { DesktopAccountOwnership.Absent },
            removeCredential = {
                events += "remove-credential"
                true
            },
            removeSyncPairs = {
                events += "remove-pairs"
                error("synthetic pair cleanup failure")
            },
            recordCleanupFailure = { events += "diagnose-cleanup" },
        )

        assertTrue(removed)
        assertEquals(
            listOf(
                "prepare-cleanup",
                "remove-credential",
                "commit-cleanup",
                "remove-pairs",
                "diagnose-cleanup",
            ),
            events,
        )
    }

    @Test
    fun committedRemovalPreservesPairCleanupCancellation() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            removeDesktopAccountBeforeSyncPairCleanup(
                accountId = CLEANUP_ACCOUNT_ID,
                prepareCleanup = { _, _ -> events += "prepare-cleanup" },
                commitCleanup = { events += "commit-cleanup" },
                clearCleanup = { events += "clear-cleanup" },
                accountOwnership = { DesktopAccountOwnership.Absent },
                removeCredential = {
                    events += "remove-credential"
                    true
                },
                removeSyncPairs = {
                    events += "remove-pairs"
                    throw CancellationException("pair cleanup owner stopped")
                },
                recordCleanupFailure = { events += "diagnose-cleanup" },
            )
        }

        assertEquals(
            listOf("prepare-cleanup", "remove-credential", "commit-cleanup", "remove-pairs"),
            events,
        )
    }

    @Test
    fun postCommitCredentialFailureRetainsCommittedPairCleanupRecovery() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeDesktopAccountBeforeSyncPairCleanup(
                accountId = CLEANUP_ACCOUNT_ID,
                prepareCleanup = { _, _ -> events += "prepare-cleanup" },
                commitCleanup = { events += "commit-cleanup" },
                clearCleanup = { events += "clear-cleanup" },
                accountOwnership = { DesktopAccountOwnership.Absent },
                removeCredential = {
                    events += "remove-credential"
                    error("synthetic post-commit credential cleanup failure")
                },
                removeSyncPairs = { events += "remove-pairs" },
                recordCleanupFailure = { events += "diagnose-cleanup" },
            )
        }

        assertEquals(listOf("prepare-cleanup", "remove-credential", "commit-cleanup"), events)
    }

    @Test
    fun backgroundSyncContinuesAfterCleanupJournalReadFailure() = runBlocking {
        val events = mutableListOf<String>()

        recoverDesktopBackgroundAccountSyncPairCleanups(
            retry = {
                events += "retry-cleanup"
                error("synthetic cleanup journal read failure")
            },
            recordFailure = { events += "diagnose-cleanup" },
        )
        events += "continue-background-sync"

        assertEquals(
            listOf("retry-cleanup", "diagnose-cleanup", "continue-background-sync"),
            events,
        )
    }

    @Test
    fun failedActiveCredentialCommitPreservesSyncPairs() = runBlocking {
        val events = mutableListOf<String>()
        val preferences = Preferences.userRoot().node("desktop-account-cleanup-test-${UUID.randomUUID()}")

        try {
            assertFailsWith<IllegalStateException> {
                clearDesktopActiveAccountBeforeSyncPairCleanup(
                    accountId = CLEANUP_ACCOUNT_ID,
                    cleanupJournal = DesktopAccountSyncPairCleanupJournal(preferences),
                    accountOwnership = { DesktopAccountOwnership.Present },
                    commitRemoval = {
                        events += "remove-credential"
                        error("synthetic credential commit failure")
                    },
                    removeSyncPairs = { events += "remove-pairs-${it.accountId}" },
                    recordDiagnostic = { events += "diagnose-cleanup" },
                )
            }

            assertEquals(listOf("remove-credential"), events)
            assertTrue(DesktopAccountSyncPairCleanupJournal(preferences).pending().isEmpty())
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun futureCleanupEntryIsPreservedWithoutHidingValidTombstonesOrBlockingNewRemoval() {
        val preferences = Preferences.userRoot().node("desktop-account-cleanup-test-${UUID.randomUUID()}")
        val malformedAccountId = "1".repeat(64)
        val validAccountId = "2".repeat(64)
        val newAccountId = "3".repeat(64)
        var malformedCount = 0
        try {
            preferences.put("fsac.$malformedAccountId", "future-phase")
            preferences.put("fsac.$validAccountId", "committed")
            val journal = DesktopAccountSyncPairCleanupJournal(preferences) { malformedCount += 1 }

            assertEquals(
                listOf(
                    DesktopAccountSyncPairCleanup(
                        malformedAccountId,
                        DesktopAccountSyncPairCleanupPhase.Unknown,
                    ),
                    DesktopAccountSyncPairCleanup(
                        validAccountId,
                        DesktopAccountSyncPairCleanupPhase.Committed,
                    ),
                ),
                journal.pending(),
            )
            assertEquals("future-phase", preferences.get("fsac.$malformedAccountId", null))
            assertEquals("committed", preferences.get("fsac.$validAccountId", null))
            assertTrue(journal.blocksAccountActivation(malformedAccountId))
            assertFailsWith<IllegalStateException> { requireDesktopAccountActivationAllowed(true) }
            assertFalse(journal.blocksAccountActivation(validAccountId))
            assertEquals(1, malformedCount)

            journal.prepare(newAccountId)

            assertEquals(
                setOf(malformedAccountId, validAccountId, newAccountId),
                journal.pending().mapTo(linkedSetOf(), DesktopAccountSyncPairCleanup::accountId),
            )
            assertFalse(journal.blocksAccountActivation(newAccountId))
            assertEquals("future-phase", preferences.get("fsac.$malformedAccountId", null))
            assertFailsWith<IllegalStateException> { journal.prepare(malformedAccountId) }
            assertEquals("future-phase", preferences.get("fsac.$malformedAccountId", null))
            assertEquals(1, malformedCount)
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun committedPairCleanupFailureSurvivesRestartAndBlocksReactivationUntilRetry() = runBlocking {
        val preferences = Preferences.userRoot().node("desktop-account-cleanup-test-${UUID.randomUUID()}")
        val firstJournal = DesktopAccountSyncPairCleanupJournal(preferences)
        try {
            val removalEvents = mutableListOf<String>()
            assertTrue(
                removeDesktopAccountBeforeSyncPairCleanup(
                    accountId = CLEANUP_ACCOUNT_ID,
                    durableMutationAccountScope = MUTATION_SCOPE,
                    prepareCleanup = firstJournal::prepare,
                    commitCleanup = firstJournal::commit,
                    clearCleanup = firstJournal::clear,
                    accountOwnership = { DesktopAccountOwnership.Absent },
                    removeCredential = { true },
                    removeSyncPairs = { error("synthetic pair cleanup failure") },
                    recordCleanupFailure = { removalEvents += "diagnose" },
                ),
            )

            assertEquals(listOf("diagnose"), removalEvents)
            val restored = DesktopAccountSyncPairCleanupJournal(preferences)
            assertEquals(
                listOf(
                    DesktopAccountSyncPairCleanup(
                        CLEANUP_ACCOUNT_ID,
                        DesktopAccountSyncPairCleanupPhase.Committed,
                        MUTATION_SCOPE,
                    ),
                ),
                restored.pending(),
            )
            assertEquals("v2|committed|$MUTATION_SCOPE", preferences.get("fsac.$CLEANUP_ACCOUNT_ID", null))

            val retryEvents = mutableListOf<String>()
            retryDesktopAccountSyncPairCleanup(
                cleanup = restored.pending().single(),
                accountOwnership = { DesktopAccountOwnership.Present },
                removeSyncPairs = { retryEvents += "remove-pairs-${it.accountId}" },
                clearCleanup = {
                    retryEvents += "clear-cleanup-$it"
                    restored.clear(it)
                },
            )

            assertEquals(
                listOf("remove-pairs-$CLEANUP_ACCOUNT_ID", "clear-cleanup-$CLEANUP_ACCOUNT_ID"),
                retryEvents,
            )
            assertTrue(restored.pending().isEmpty())
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun preparedCleanupFromAnAbortedRemovalPreservesExistingPairs() = runBlocking {
        val events = mutableListOf<String>()

        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
            ),
            accountOwnership = { DesktopAccountOwnership.Present },
            removeSyncPairs = { events += "remove-pairs" },
            clearCleanup = { events += "clear-cleanup" },
        )

        assertEquals(listOf("clear-cleanup"), events)
    }

    @Test
    fun preparedCleanupPreservesPairsAndJournalWhenCredentialOwnershipIsUnknown() = runBlocking {
        val events = mutableListOf<String>()

        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Prepared,
            ),
            accountOwnership = { DesktopAccountOwnership.Unknown },
            removeSyncPairs = { events += "remove-pairs" },
            clearCleanup = { events += "clear-cleanup" },
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun malformedCleanupUsesCredentialFreeOwnershipToRecover() = runBlocking {
        val absentEvents = mutableListOf<String>()
        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Unknown,
            ),
            accountOwnership = { DesktopAccountOwnership.Absent },
            removeSyncPairs = { absentEvents += "remove-pairs" },
            clearCleanup = { absentEvents += "clear-cleanup" },
        )
        assertEquals(listOf("remove-pairs", "clear-cleanup"), absentEvents)

        val presentEvents = mutableListOf<String>()
        retryDesktopAccountSyncPairCleanup(
            cleanup = DesktopAccountSyncPairCleanup(
                CLEANUP_ACCOUNT_ID,
                DesktopAccountSyncPairCleanupPhase.Unknown,
            ),
            accountOwnership = { DesktopAccountOwnership.Present },
            removeSyncPairs = { presentEvents += "remove-pairs" },
            clearCleanup = { presentEvents += "clear-cleanup" },
        )
        assertEquals(listOf("clear-cleanup"), presentEvents)
    }

    private companion object {
        const val CLEANUP_ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val MUTATION_SCOPE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
