package dev.obiente.nextcloudnative.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopPendingDynamicMutationDirectoryTest {
    @Test
    fun `pending mutations use durable platform state roots`() {
        val home = File("/test-home")

        assertEquals(
            File("/state/nextcloud-native/pending-mutations-v1"),
            desktopPendingDynamicMutationDirectory(
                osName = "Linux",
                environment = mapOf("XDG_STATE_HOME" to "/state", "XDG_CACHE_HOME" to "/cache"),
                userHome = home,
            ),
        )
        assertEquals(
            File("/windows-local/Nextcloud Native/State/Pending Mutations"),
            desktopPendingDynamicMutationDirectory(
                osName = "Windows 11",
                environment = mapOf("LOCALAPPDATA" to "/windows-local"),
                userHome = home,
            ),
        )
        assertEquals(
            File("/test-home/Library/Application Support/Nextcloud Native/Pending Mutations"),
            desktopPendingDynamicMutationDirectory(
                osName = "Mac OS X",
                environment = emptyMap(),
                userHome = home,
            ),
        )
    }
}
