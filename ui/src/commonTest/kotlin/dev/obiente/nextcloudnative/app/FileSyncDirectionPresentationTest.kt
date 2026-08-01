package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSyncDirectionPresentationTest {
    @Test
    fun `sync route marker follows configured direction`() {
        assertEquals(
            "Device <-> Nextcloud /Notes",
            fileSyncRouteLabel(FileSyncDirection.Bidirectional, "Notes"),
        )
        assertEquals(
            "Nextcloud /Documents -> device",
            fileSyncRouteLabel(FileSyncDirection.DownloadOnly, "/Documents"),
        )
        assertEquals(
            "Device -> Nextcloud /Photos/Camera",
            fileSyncRouteLabel(FileSyncDirection.UploadOnly, "Photos/Camera"),
        )
    }
}
