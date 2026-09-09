package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopStagingSpaceReservationsTest {
    @Test
    fun `different staging directories on one filesystem share reservations`() {
        val root = Files.createTempDirectory("nextcloud-stage-filesystem-key-").toFile()
        try {
            val offline = root.resolve("offline").apply { mkdir() }
            val handoff = root.resolve("handoff").apply { mkdir() }
            assertEquals(jvmStagingStorageKey(offline), jvmStagingStorageKey(handoff))
            val ledger = JvmStagingSpaceReservations()
            ledger.reserve(jvmStagingStorageKey(offline), 1_000L, 600L, 100L).use {
                ledger.reserve(jvmStagingStorageKey(handoff), 1_000L, 300L, 100L).use {
                    assertFailsWith<IllegalStateException> {
                        ledger.reserve(jvmStagingStorageKey(handoff), 1_000L, 1L, 100L)
                    }
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `concurrent reservations cannot claim the same staging bytes`() {
        val root = Files.createTempDirectory("nextcloud-stage-reservations-").toFile()
        try {
            val reservations = DesktopStagingSpaceReservations(usableSpace = { 1_000L })
            val first = reservations.reserve(root, declaredByteCount = 600L, reserveBytes = 100L)
            val second = reservations.reserve(root, declaredByteCount = 300L, reserveBytes = 100L)
            try {
                assertEquals(600L, first.maximumBytes)
                assertEquals(300L, second.maximumBytes)
                assertFailsWith<IllegalStateException> {
                    reservations.reserve(root, declaredByteCount = 1L, reserveBytes = 100L)
                }
            } finally {
                first.close()
                second.close()
            }

            reservations.reserve(root, declaredByteCount = 900L, reserveBytes = 100L).use { reset ->
                assertEquals(900L, reset.maximumBytes)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `unknown transfer receives only currently unclaimed safe space`() {
        val root = Files.createTempDirectory("nextcloud-stage-reservations-").toFile()
        try {
            val reservations = DesktopStagingSpaceReservations(usableSpace = { 1_000L })
            reservations.reserve(root, declaredByteCount = 250L, reserveBytes = 100L).use {
                reservations.reserve(root, declaredByteCount = null, reserveBytes = 100L).use { unknown ->
                    assertEquals(650L, unknown.maximumBytes)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
