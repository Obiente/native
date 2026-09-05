package dev.obiente.nextcloudnative

import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccountRemovalCleanupRecoveryWorkTest {
    @Test
    fun newCleanupMarkersAppendBehindAStillRunningRecovery() {
        assertEquals(
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            ANDROID_ACCOUNT_REMOVAL_CLEANUP_WORK_POLICY,
        )
    }

    @Test
    fun removedAccountsAreCleanedWithoutBeingSavedAgain() = runBlocking {
        val removed = cleanup("a", "1")
        val restored = cleanup("b", "2")
        val events = mutableListOf<String>()

        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = listOf(removed, restored),
            accountOwnedByRegistry = { key -> key == restored.accountStorageKey },
            removeAccountOwnedWork = { events += "remove:${it.workIdentity}" },
            clearCleanup = { events += "clear:$it" },
            recordFailure = { events += "failure" },
        )

        assertTrue(completed)
        assertEquals(
            listOf(
                "remove:${removed.workIdentity}",
                "clear:${removed.accountStorageKey}",
                "clear:${restored.accountStorageKey}",
            ),
            events,
        )
    }

    @Test
    fun unreadableRegistryDefersCleanupWithoutDeletingAccountOwnedState() = runBlocking {
        val pending = cleanup("a", "1")
        val events = mutableListOf<String>()

        val completed = recoverPendingAndroidAccountRemovalCleanups(
            pending = listOf(pending),
            accountOwnedByRegistry = { null },
            removeAccountOwnedWork = { events += "remove" },
            clearCleanup = { events += "clear" },
            recordFailure = { events += "failure" },
        )

        assertFalse(completed)
        assertEquals(listOf("failure"), events)
    }

    private fun cleanup(accountCharacter: String, workCharacter: String) =
        AndroidPendingAccountRemovalCleanup(
            accountStorageKey = accountCharacter.repeat(64),
            workIdentity = workCharacter.repeat(32),
        )
}
