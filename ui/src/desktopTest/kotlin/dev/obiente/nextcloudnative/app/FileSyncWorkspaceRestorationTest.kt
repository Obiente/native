package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FileSyncWorkspaceRestorationTest {
    @Test fun savedNoticeAndResponsiveReparentingPreserveTheSelectedSettingsTab() {
        val savedNotice = mutableStateOf(false)
        val wideLayout = mutableStateOf(false)
        nativeSceneTest(390, 844, content = {
            val selection = rememberFileSyncWorkspaceSelection("synthetic-account")
            val workspace: @Composable () -> Unit = {
                FileSyncWorkspace(snapshot(1), false, null, selection = selection,
                    onAdd = {}, onRun = {}, onRemove = {}, onResolve = { _, _, _ -> }, onEdit = {})
            }
            if (wideLayout.value) {
                Row(Modifier.fillMaxSize()) { Column { workspace() } }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (savedNotice.value) item(key = "action-message") { Text("Sync settings saved.") }
                    item(key = "folder-sync-workspace") { workspace() }
                }
            }
        }) {
            click("Example documents 1")
            settleUntil { has("Close sync details") }
            repeat(3) { settle() }
            click("Settings")
            settleUntil { has("Edit settings") }
            savedNotice.value = true
            settle()
            assertTrue(has("Edit settings"))
            wideLayout.value = true
            settleUntil { has("Close sync details") }
            assertTrue(has("Edit settings"))
            click("Close sync details")
            settleUntil { !has("Close sync details") }
            assertTrue(has("Example documents 1"))
        }
    }

    @Test fun editingReplacesDetailSheetAcrossRecreationAndReturnsToSelectedTab() {
        var registry = SaveableStateRegistry(null) { true }
        val snapshot = mutableStateOf<FileSyncCenterSnapshot?>(snapshot(1))
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                var editing by rememberSaveable { mutableStateOf(false) }
                FileSyncWorkspace(snapshot.value, snapshot.value == null, null,
                    onAdd = {}, onRun = {}, onRemove = {}, onResolve = { _, _, _ -> },
                    onEdit = { editing = true }, detailSheetVisible = !editing, fillAvailableHeight = true)
                if (editing) snapshot.value?.pairs?.single()?.let { pair ->
                    FileSyncSettingsEditor(pair, false, null, { editing = false }) { _, _ -> error("Unexpected save") }
                }
            }
        }
        nativeSceneTest(390, 844, content = content) {
            click("Example documents 1")
            settleUntil { has("Close sync details") }
            click("Settings")
            click("Edit settings")
            settleUntil { has("Save changes") }
            assertFalse(has("Close sync details"))
            replaceText("Test device", "Travel device")
            registry = SaveableStateRegistry(registry.performSave()) { true }
        }
        snapshot.value = null
        nativeSceneTest(390, 844, content = content) {
            snapshot.value = snapshot(1)
            settleUntil { has("Save changes") }
            assertFalse(has("Close sync details"))
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Travel device" })
            click("Cancel")
            click("Discard changes")
            settleUntil { has("Edit settings") }
            assertTrue(has("Close sync details"))
        }
    }

    @Test fun selectedPairAndSettingsSurviveRecreationWhileSnapshotLoads() {
        var registry = SaveableStateRegistry(null) { true }
        val snapshot = mutableStateOf<FileSyncCenterSnapshot?>(snapshot(1))
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                FileSyncWorkspace(snapshot.value, snapshot.value == null, null,
                    onAdd = {}, onRun = {}, onRemove = {}, onResolve = { _, _, _ -> },
                    onEdit = {}, fillAvailableHeight = true)
            }
        }
        nativeSceneTest(390, 844, content = content) {
            click("Example documents 1")
            settleUntil { has("Close sync details") }
            repeat(3) { settle() }
            click("Settings")
            settleUntil { has("Edit settings") }
            capture("sync-settings-after-open")
            assertTrue(has("Edit settings"))
            registry = SaveableStateRegistry(registry.performSave()) { true }
        }
        snapshot.value = null
        nativeSceneTest(390, 844, content = content) {
            assertFalse(has("Add your first sync"))
            snapshot.value = snapshot(1)
            settleUntil { has("Edit settings") }
            assertTrue(has("Edit settings"))
            assertTrue(has("Close sync details"))
        }
    }

    @Test fun phoneListVirtualizesAndScrollsToTheLastPair() {
        nativeSceneTest(390, 844, content = {
            FileSyncWorkspace(snapshot(64), false, null, onAdd = {}, onRun = {}, onRemove = {},
                onResolve = { _, _, _ -> }, fillAvailableHeight = true)
        }) {
            assertTrue(has("Example documents 1"))
            assertFalse(has("Example documents 64"))
            val scroll = assertNotNull(nodes().firstNotNullOfOrNull {
                it.config.getOrNull(SemanticsActions.ScrollToIndex)?.action
            })
            assertTrue(scroll.invoke(63))
            settleUntil { has("Example documents 64") }
            click("Example documents 64")
            settleUntil { has("Close sync details") }
            assertTrue(has("Close sync details"))
        }
    }

    private fun snapshot(count: Int) = FileSyncCenterSnapshot(FileSyncCenterSupport.Available,
        (1..count).map { index -> FileSyncPairSummary(
            id = "pair-$index", localDisplayName = "Example documents $index", localRootPath = "Documents/$index",
            remoteRootPath = "Documents/$index", configuration = FileSyncConfiguration(deviceLabel = "Test device"),
            readyCount = 0, runningCount = 0, conflicts = emptyList(), failedCount = 0,
            skippedCount = 0, lastScanEpochMillis = null,
        ) })
}
