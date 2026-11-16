package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertTrue

class FileSyncSetupValidationTest {
    @Test fun invalidVisibleLabelBlocksCreationAcrossRecreationAndLayoutChange() {
        var registry = SaveableStateRegistry(null) { true }
        val configuration = mutableStateOf(FileSyncConfiguration(deviceLabel = "Test device"))
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                FileSyncSetupSurface(
                    localRoot = FileSyncLocalRoot("synthetic-root", "Example documents"),
                    mediaSuggestion = null, remotePath = "Documents", configuration = configuration.value,
                    mediaPreview = null, mediaPreviewLoading = false, mediaPreviewError = null, busy = false,
                    onDismiss = {}, onChooseDestination = {}, onConfigurationChanged = { configuration.value = it },
                    onAdd = { error("Invalid setup submitted") }, modifier = Modifier.fillMaxSize(),
                    initialStep = FileSyncSetupStep.Review, initialAdvancedSettingsVisible = true,
                )
            }
        }
        nativeSceneTest(1100, 1800, content = content) {
            replaceText("Test device", "")
            assertAddDisabled()
            registry = SaveableStateRegistry(registry.performSave()) { true }
        }
        nativeSceneTest(390, 844, content = content) {
            assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.EditableText)?.text == "" })
            assertAddDisabled()
        }
    }

    private fun NativeSceneTestDriver.assertAddDisabled() {
        assertTrue(nodes().any {
            it.config.getOrNull(SemanticsActions.OnClick) != null &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Add sync" } == true &&
                it.config.contains(SemanticsProperties.Disabled)
        })
    }
}
