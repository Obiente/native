package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSyncSettingsInteractionTest {
    @Test fun dismissGestureKeepsDirtyEditorVisibleWhenDiscardIsDeclined() {
        var dismissed = false
        nativeSceneTest(390, 844, content = {
            FileSyncSettingsEditor(pair(), false, null, { dismissed = true }) { _, _ -> error("Unexpected save") }
        }) {
            replaceText("Test device", "Travel device")
            val dismiss = assertNotNull(nodes().firstNotNullOfOrNull {
                it.config.getOrNull(SemanticsActions.Dismiss)?.action
            })
            assertTrue(dismiss.invoke())
            settleUntil { has("Discard settings changes?") }
            click("Keep editing")
            settleUntil { has("Save changes") }
            assertFalse(dismissed)
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Travel device" })
            click("Save changes")
            assertTrue(has("Apply sync settings?"))
        }
    }

    @Test fun immediateBackAfterTypingRequiresDiscardWithoutWaitingForRecomposition() {
        var dismissed = false
        nativeSceneTest(390, 844, content = {
            FileSyncSettingsEditor(pair(), false, null, { dismissed = true }) { _, _ -> error("Unexpected save") }
        }) {
            val field = assertNotNull(nodes().lastOrNull {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Test device"
            })
            val cancel = actionNode("Cancel").config[SemanticsActions.OnClick].action!!
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Travel device"))
            cancel.invoke()
            settle()
            assertFalse(dismissed)
            assertTrue(has("Discard settings changes?"))
        }
    }

    @Test fun editedSettingsRequireExplicitApplyAndPreserveRules() {
        val pair = pair()
        var saved: FileSyncConfiguration? = null
        nativeSceneTest(390, 844, content = {
            FileSyncSettingsEditor(pair, false, null, {}) { expected, updated ->
                assertEquals(pair.configuration, expected)
                saved = updated
            }
        }) {
            replaceText("Test device", "Travel device")
            click("Save changes")
            assertTrue(has("Apply sync settings?"))
            assertNull(saved)
            click("Apply changes")
            assertEquals("Travel device", saved?.deviceLabel)
            assertEquals(pair.configuration.ignoredPatterns, saved?.ignoredPatterns)
        }
    }

    @Test fun dirtyEditorRequiresDiscardAndOfflineEditorCanClose() {
        var dismissed = false
        nativeSceneTest(390, 844, content = {
            FileSyncSettingsEditor(pair(), false, null, { dismissed = true }, saveEnabled = false) { _, _ ->
                error("An offline editor cannot save")
            }
        }) {
            replaceText("Test device", "Travel device")
            assertTrue(actionNode("Save changes").config.contains(SemanticsProperties.Disabled))
            click("Cancel")
            assertTrue(has("Discard settings changes?"))
            assertFalse(dismissed)
            click("Discard changes")
            assertTrue(dismissed)
        }
    }

    @Test fun restoredDraftCannotOverwriteAChangedConfiguration() {
        var registry = SaveableStateRegistry(null) { true }
        val current = mutableStateOf(pair())
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                FileSyncSettingsEditor(current.value, false, null, {}) { _, _ -> error("Stale draft saved") }
            }
        }
        nativeSceneTest(390, 844, content = content) {
            replaceText("Test device", "Travel device")
            registry = SaveableStateRegistry(registry.performSave()) { true }
        }
        current.value = current.value.copy(configuration = current.value.configuration.copy(deviceLabel = "Another device"))
        nativeSceneTest(390, 844, content = content) {
            assertTrue(has("Sync settings changed. Close this editor and review them before saving again."))
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "Travel device" })
            assertTrue(actionNode("Save changes").config.contains(SemanticsProperties.Disabled))
        }
    }

    @Test fun emptyLabelCannotBeSaved() {
        nativeSceneTest(390, 844, content = {
            FileSyncSettingsEditor(pair(), false, null, {}) { _, _ -> error("Invalid label saved") }
        }) {
            replaceText("Test device", "")
            assertTrue(has("Enter a device label without control characters."))
            assertTrue(actionNode("Save changes").config.contains(SemanticsProperties.Disabled))
        }
    }

    private fun NativeSceneTestDriver.actionNode(label: String) = assertNotNull(nodes().lastOrNull {
        it.config.getOrNull(SemanticsActions.OnClick) != null &&
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    }, "No action for $label")

    private fun pair() = FileSyncPairSummary(
        id = "pair", localDisplayName = "Example documents", localRootPath = "Documents",
        remoteRootPath = "Documents", configuration = FileSyncConfiguration(deviceLabel = "Test device",
            ignoredPatterns = listOf("**/*.tmp")),
        readyCount = 0, runningCount = 0, conflicts = emptyList(), failedCount = 0,
        skippedCount = 0, lastScanEpochMillis = null,
    )
}
