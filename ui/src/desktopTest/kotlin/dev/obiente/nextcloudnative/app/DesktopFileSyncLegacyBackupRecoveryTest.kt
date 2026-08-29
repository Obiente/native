package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class DesktopFileSyncLegacyBackupRecoveryTest {
    @Test
    fun `legacy replacement backups retain bounded destination recovery`() {
        val backup =
            "Photos/.today.md.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174000"

        assertEquals("Photos/today.md", desktopLegacyReplacementBackupDestination(backup))
        assertEquals(
            null,
            desktopLegacyReplacementBackupDestination(
                "Photos/.today.md.nextcloud-native-backup-not-a-uuid",
            ),
        )
        assertEquals(
            listOf(backup to "Photos/today.md"),
            desktopLegacyBackupRecoveryPlan(listOf(backup), maximumRecoveryItems = 1),
        )
        assertEquals(
            emptyList(),
            desktopLegacyBackupRecoveryPlan(
                listOf(backup, "Photos/today.md"),
                maximumRecoveryItems = 0,
            ),
        )
        assertFails {
            desktopLegacyBackupRecoveryPlan(
                listOf(
                    backup,
                    "Photos/.other.md.nextcloud-native-backup-123e4567-e89b-12d3-a456-426614174001",
                ),
                maximumRecoveryItems = 1,
            )
        }
    }
}
