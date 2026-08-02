package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VirtualFolderRetentionTest {
    @Test
    fun closestFolderRuleAllowsPinnedAlbumsAndOnlineOnlyExceptions() {
        val state = VirtualFolderRetentionState()
            .withRetention("Photos", VirtualFolderRetention.KeepOnDevice)
            .withRetention("Photos/Archive", VirtualFolderRetention.Automatic)

        assertEquals(VirtualFolderRetention.KeepOnDevice, state.retentionFor("Photos/Current/image.raf"))
        assertEquals(VirtualFolderRetention.Automatic, state.retentionFor("Photos/Archive/old.raf"))
        assertEquals(VirtualFolderRetention.Automatic, state.retentionFor("Documents/note.md"))
    }

    @Test
    fun plansHydrationWithoutMakingOpenedFilesSticky() {
        val key = FileOfflineKey("account", "Photos/Album/image.raf")
        val plan = planVirtualFolderRetention(
            VirtualFolderRetentionState().withRetention("Photos/Album", VirtualFolderRetention.KeepOnDevice),
            listOf(VirtualFolderContentState(key, "etag-1", 100L, 20L)),
        )
        assertEquals(80L, plan.hydrationBytes)
        assertIs<VirtualFolderRetentionAction.Hydrate>(plan.actions.single())
    }

    @Test
    fun releasingFolderNeverDiscardsDirtyOrOpenContent() {
        val plan = planVirtualFolderRetention(
            VirtualFolderRetentionState(),
            listOf(
                VirtualFolderContentState(FileOfflineKey("account", "dirty.raw"), "e1", 10L, 10L, dirty = true),
                VirtualFolderContentState(FileOfflineKey("account", "open.raw"), "e2", 20L, 20L, activeLeaseCount = 1),
                VirtualFolderContentState(FileOfflineKey("account", "safe.raw"), "e3", 30L, 30L),
            ),
        )
        assertIs<VirtualFolderRetentionAction.RetainUntilSafe>(plan.actions[0])
        assertIs<VirtualFolderRetentionAction.RetainUntilSafe>(plan.actions[1])
        assertIs<VirtualFolderRetentionAction.Dehydrate>(plan.actions[2])
        assertEquals(30L, plan.reclaimableBytes)
    }
}
