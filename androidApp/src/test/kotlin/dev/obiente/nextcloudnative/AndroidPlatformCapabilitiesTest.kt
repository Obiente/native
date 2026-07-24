package dev.obiente.nextcloudnative

import android.Manifest
import dev.obiente.nextcloudnative.app.PlatformCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPlatformCapabilitiesTest {
    @Test
    fun notificationsAndBluetoothRespectAndroidRuntimePermissionLevels() {
        assertTrue(PlatformCapability.Notifications.permissions(32).isEmpty())
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            PlatformCapability.Notifications.permissions(33),
        )
        assertTrue(PlatformCapability.NearbyAudio.permissions(30).isEmpty())
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_CONNECT),
            PlatformCapability.NearbyAudio.permissions(31),
        )
    }

    @Test
    fun filesAndMediaNeverRequestsBroadStorageAccess() {
        assertTrue(PlatformCapability.FilesAndMedia.permissions(36).isEmpty())
        assertTrue(PlatformCapability.BackgroundSync.permissions(36).isEmpty())
        assertEquals(listOf(Manifest.permission.CAMERA), PlatformCapability.Camera.permissions(36))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), PlatformCapability.Microphone.permissions(36))
        assertTrue(PlatformCapability.AllFilesAccess.permissions(36).isEmpty())
        assertEquals(
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            PlatformCapability.AllFilesAccess.permissions(29),
        )
    }
}
