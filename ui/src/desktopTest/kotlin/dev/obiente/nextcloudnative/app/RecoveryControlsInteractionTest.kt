package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryControlsInteractionTest {
    @Test
    fun retainedContentRemainsUsableWhileRefreshCanBeRetried() {
        var refreshes = 0
        var opens = 0
        nativeSceneTest(390, 844, content = {
            Column(Modifier.fillMaxSize()) {
                RetainedContentNotice(
                    message = "Could not refresh. Saved photos are still available.",
                    onRetry = { refreshes++ },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { opens++ }) { Text("Open saved photo") }
            }
        }) {
            click("Retry")
            assertEquals(1, refreshes)
            assertTrue(has("Could not refresh. Saved photos are still available."))
            click("Open saved photo")
            assertEquals(1, opens)
            capture("retained-content-retry")
        }
    }

    @Test
    fun recipientRetryAndSelectionRespectBusyStateAndPreserveExactIdentity() {
        val recipient = FileShareRecipient("team-design", "Design team", FileShareTarget.Group)
        val state = mutableStateOf(FileShareRecipientPickerUiState(query = "Design", error = "Connection interrupted"))
        val enabled = mutableStateOf(false)
        var retries = 0
        var selections = 0
        nativeSceneTest(390, 844, content = {
            FileShareRecipientPickerContent(
                target = FileShareTarget.Group,
                state = state.value,
                enabled = enabled.value,
                onQueryChanged = { state.value = state.value.copy(query = it) },
                onSelected = { selections++; state.value = state.value.copy(selectedRecipient = it.id) },
                onRetry = {
                    retries++
                    state.value = state.value.copy(error = null, results = listOf(recipient))
                },
            )
        }) {
            click("Retry search")
            assertEquals(0, retries, "Busy sharing must not start another recipient request")
            enabled.value = true
            settle()
            click("Retry search")
            assertEquals(1, retries)
            assertTrue(has("Design team"))
            enabled.value = false
            settle()
            click("Design team")
            assertEquals(0, selections)
            enabled.value = true
            settle()
            click("Design team")
            assertEquals(1, selections)
            assertEquals("team-design", state.value.selectedRecipient)
            assertTrue(has("Selected: team-design"))
            capture("recipient-selected")
        }
    }

    @Test
    fun storageRecoveryIsExplicitAndDisconnectRemainsASecondaryAction() {
        val busy = mutableStateOf(false)
        var acknowledgements = 0
        var disconnects = 0
        val snapshot = defaultVirtualFileStorageSnapshot().copy(
            support = VirtualFileStorageSupport.Available,
            integration = VirtualFilePlatformIntegration.WindowsCloudFiles,
            policy = VirtualFileCachePolicy(automaticCleanup = false, maximumCacheBytes = null),
            providerState = VirtualFileProviderState.NeedsAttention,
            providerActive = true,
            providerRecoveryNotice = "A preserved copy is available for review.",
            pendingWritebackCount = 1,
            limitations = emptyList(),
        )
        nativeSceneTest(600, 1100, content = {
            VirtualFileStorageCard(
                snapshot = snapshot, loading = false, busy = busy.value,
                onManage = {}, onFreeUp = {}, onActivateProvider = {},
                onDeactivateProvider = { disconnects++ },
                onAcknowledgeRecovery = { acknowledgements++ },
                onChangeLocation = {}, onChangeCacheTiers = {}, onChoosePinnedFolder = {},
                onReleaseFolder = {}, onRetryFolder = {},
            )
        }) {
            assertTrue(has("Edits need review"))
            assertFalse(has("Disconnect from file manager"))
            click("I've reviewed the preserved folder")
            assertEquals(1, acknowledgements)
            assertEquals(0, disconnects)
            busy.value = true
            settle()
            click("I've reviewed the preserved folder")
            assertEquals(1, acknowledgements, "Recovery acknowledgement must respect the host busy state")
            busy.value = false
            settle()
            click("Actions for file-manager connection")
            assertTrue(has("Disconnect from file manager"))
            click("Disconnect from file manager")
            assertEquals(1, disconnects, "The menu emits the host action; confirmation stays in its owner")
            capture("storage-recovery-actions")
        }
    }
}
