package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VirtualFileProviderLocationTest {
    @Test
    fun acceptsReadableCrossPlatformFolderNames() {
        assertTrue("Nextcloud Native".isValidVirtualFileProviderFolderName())
        assertTrue("Photography Library".isValidVirtualFileProviderFolderName())
        assertTrue("Cloud-2".isValidVirtualFileProviderFolderName())
    }

    @Test
    fun rejectsTraversalSeparatorsControlsAndWindowsDeviceNames() {
        listOf(
            "", " ", ".", "..", "../Cloud", "Cloud/Photos", "Cloud\\Photos", "Cloud:Photos",
            "Cloud*", "Cloud\n", "name.", "name ", "CON", "con.txt", "LPT1",
        ).forEach { name ->
            assertFalse(name.isValidVirtualFileProviderFolderName(), name)
        }
    }
}
