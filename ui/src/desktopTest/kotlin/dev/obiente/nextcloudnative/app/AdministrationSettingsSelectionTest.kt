package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals

class AdministrationSettingsSelectionTest {
    @Test
    fun restoredAdministrationSelectionSurvivesInitialPermissionCheck() {
        var registry = SaveableStateRegistry(null) { true }
        val visible = mutableStateOf(listOf(SettingsWorkspaceSection.Account, SettingsWorkspaceSection.Administration))
        var requested: MutableState<String?>? = null
        var displayed: SettingsWorkspaceSection? = null
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                val selection = rememberSettingsSectionSelection("https://permissions.example.test", "sample")
                requested = selection
                displayed = resolveSettingsWorkspaceSection(selection.value, visible.value)
                InitializeSettingsSectionSelection(selection, expanded = true, displayed)
            }
        }
        nativeSceneTest(1280, 800, content = content) {
            requested!!.value = SettingsWorkspaceSection.Administration.name
            settle()
            registry = SaveableStateRegistry(registry.performSave()) { true }
        }
        visible.value = listOf(SettingsWorkspaceSection.Account)
        nativeSceneTest(1280, 800, content = content) {
            assertEquals(SettingsWorkspaceSection.Account, displayed)
            assertEquals(SettingsWorkspaceSection.Administration.name, requested!!.value)
            visible.value = listOf(SettingsWorkspaceSection.Account, SettingsWorkspaceSection.Administration)
            settle()
            assertEquals(SettingsWorkspaceSection.Administration, displayed)
        }
    }

    @Test
    fun temporarilyHiddenAdministrationDoesNotReplaceRequestedSelection() {
        val allowed = listOf(SettingsWorkspaceSection.Account, SettingsWorkspaceSection.Administration)
        val visible = mutableStateOf(allowed)
        var requested: MutableState<String?>? = null
        var displayed: SettingsWorkspaceSection? = null
        nativeSceneTest(1280, 800, content = {
            val selection = rememberSettingsSectionSelection("https://permissions.example.test", "sample")
            requested = selection
            displayed = resolveSettingsWorkspaceSection(selection.value, visible.value)
            InitializeSettingsSectionSelection(selection, expanded = true, displayed)
        }) {
            assertEquals(SettingsWorkspaceSection.Account.name, requested!!.value)
            requested!!.value = SettingsWorkspaceSection.Administration.name
            settle()
            assertEquals(SettingsWorkspaceSection.Administration, displayed)
            visible.value = listOf(SettingsWorkspaceSection.Account)
            settle()
            assertEquals(SettingsWorkspaceSection.Account, displayed)
            assertEquals(SettingsWorkspaceSection.Administration.name, requested!!.value)
            visible.value = allowed
            settle()
            assertEquals(SettingsWorkspaceSection.Administration, displayed)
            visible.value = listOf(SettingsWorkspaceSection.Account)
            settle()
            requested!!.value = SettingsWorkspaceSection.Account.name
            settle()
            visible.value = allowed
            settle()
            assertEquals(SettingsWorkspaceSection.Account, displayed)
        }
    }
}
