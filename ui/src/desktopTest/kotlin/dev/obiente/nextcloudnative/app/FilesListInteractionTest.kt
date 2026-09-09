package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.delay

class FilesListInteractionTest {
    @Test
    fun desktopSelectionDoesNotOpenUntilDoubleClick() {
        val folder = NextcloudFile("Projects", "Projects", true, null, null, null, null, false)
        var selected by mutableStateOf<NextcloudFile?>(null)
        var opened = 0
        nativeSceneTest(700, 400, content = {
            NativeFileWorkspaceList(
                files = listOf(folder), compact = false, offlineAvailability = emptyMap(),
                offlineStorageSupported = false, fileSharing = NextcloudFileSharingCapabilities(),
                externalHandoffCapability = null, selectedFile = selected,
                onSelectedFileChanged = { selected = it }, onOpenPath = { opened++ },
                onOpenFile = {}, onAction = { _, _ -> }, desktop = true,
            )
        }) {
            click("Projects")
            delay(400)
            settle()
            assertEquals(folder, selected)
            assertEquals(0, opened)
            click("Projects")
            delay(400)
            settle()
            assertEquals(0, opened)
            val position = assertNotNull(node("Projects")).boundsInRoot.center
            repeat(2) {
                scene.sendPointerEvent(PointerEventType.Press, position)
                scene.sendPointerEvent(PointerEventType.Release, position)
                delay(70)
            }
            settle()
            assertEquals(1, opened)
        }
    }
}
