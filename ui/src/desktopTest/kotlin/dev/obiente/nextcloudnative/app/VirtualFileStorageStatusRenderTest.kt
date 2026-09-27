package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualFileStorageStatusRenderTest {
    @Test
    fun `slow checks and failures remain readable and retry is reachable`() {
        for (width in listOf(390, 1280)) {
            for (loading in listOf(true, false)) {
                var retries = 0
                val message = if (loading) {
                    "Storage checks are taking longer than expected. You can use other parts of nati.ve while they finish."
                } else {
                    "Could not load storage status. Try checking again."
                }
                nativeSceneTest(width, 600, content = {
                    VirtualFileStorageCard(
                        snapshot = null,
                        loading = loading,
                        statusMessage = message,
                        onReload = { retries += 1 },
                        busy = false,
                        onManage = {}, onFreeUp = {}, onActivateProvider = {}, onDeactivateProvider = {},
                        onAcknowledgeRecovery = {}, onChangeLocation = {}, onChangeCacheTiers = {},
                        onChoosePinnedFolder = {}, onReleaseFolder = {}, onRetryFolder = {},
                    )
                }) {
                    assertTrue(has(message))
                    if (loading) {
                        assertFalse(has("Check again"))
                    } else {
                        assertTrue(has("Storage status is unavailable."))
                        assertFalse(has("Loading on-demand storage status..."))
                        click("Check again")
                        assertEquals(1, retries)
                    }
                    capture("virtual-storage-$width-${if (loading) "slow" else "failed"}")
                }
            }
        }
    }
}
