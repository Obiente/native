package dev.obiente.nextcloudnative.app

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCleanupSessionLoadingTest {
    @Test
    fun preparedCleanupProducesHandledRetryStateAndLoadsAfterRecovery() {
        val preferences = Preferences.userRoot().node("synthetic-cleanup-load-${UUID.randomUUID()}")
        val session = NextcloudSession("https://cloud.example.test", "alice", "synthetic-password")
        val identity = desktopFileCacheAccountId(session)
        val journal = DesktopAccountSyncPairCleanupJournal(preferences)
        var publications = 0
        try {
            preferences.put("fsac.$identity", "prepared")
            fun load() = loadNextcloudSessionSafely {
                loadDesktopSessionAfterCleanupGate(session.accountRecord(), journal, { session }, { publications++ })
            }
            assertEquals(NextcloudSessionLoadState.AccountCleanupUnavailable(NextcloudSessionCleanupReason.Pending), load())
            assertEquals(0, publications)
            journal.clear(identity)
            assertEquals(NextcloudSessionLoadState.Loaded(session), load())
            assertEquals(1, publications)
        } finally { preferences.removeNode() }
    }
}
