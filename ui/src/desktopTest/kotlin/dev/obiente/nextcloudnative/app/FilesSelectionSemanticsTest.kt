package dev.obiente.nextcloudnative.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FilesSelectionSemanticsTest {
    @Test
    fun listAndGridExposeSelectionOnlyWhenClickSelects() {
        val folder = NextcloudFile("Projects", "Projects", true, null, null, null, null, false)
        val services = Proxy.newProxyInstance(NextcloudPlatformServices::class.java.classLoader,
            arrayOf(NextcloudPlatformServices::class.java)) { _, _, _ -> error("No network in this scene") }
            as NextcloudPlatformServices
        for (desktop in listOf(false, true)) for (grid in listOf(false, true)) {
            var opened = 0
            var selections = 0
            nativeSceneTest(700, 400, content = {
                if (grid) NativeFileWorkspaceGrid(
                    files = listOf(folder), offlineAvailability = emptyMap(), offlineStorageSupported = false,
                    fileSharing = NextcloudFileSharingCapabilities(), externalHandoffCapability = null,
                    services = services, session = NextcloudSession("https://fixture.invalid", "fixture", "synthetic"),
                    userId = null, selectedFile = null, onSelectedFileChanged = { selections++ },
                    onOpenPath = { opened++ }, onOpenFile = {}, onAction = { _, _ -> }, desktop = desktop,
                ) else NativeFileWorkspaceList(
                    files = listOf(folder), compact = false, offlineAvailability = emptyMap(),
                    offlineStorageSupported = false, fileSharing = NextcloudFileSharingCapabilities(),
                    externalHandoffCapability = null, selectedFile = null,
                    onSelectedFileChanged = { selections++ }, onOpenPath = { opened++ },
                    onOpenFile = {}, onAction = { _, _ -> }, desktop = desktop,
                )
            }) {
                val item = assertNotNull(nodes().firstOrNull {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label?.contains("Projects") == true
                })
                assertEquals(if (desktop) false else null, item.config.getOrNull(SemanticsProperties.Selected))
                assertTrue(assertNotNull(item.config[SemanticsActions.OnClick].action).invoke())
                assertEquals(if (desktop) 1 else 0, selections)
                assertEquals(if (desktop) 0 else 1, opened)
            }
        }
    }
}
