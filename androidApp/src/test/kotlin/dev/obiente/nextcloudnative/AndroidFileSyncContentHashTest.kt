package dev.obiente.nextcloudnative

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException

class AndroidFileSyncContentHashTest {
    @Test
    fun zeroByteContentUsesAPositiveReadCeiling() {
        assertEquals(
            "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256SyncContentHash(ByteArrayInputStream(byteArrayOf()), expectedBytes = 0L, maximumBytes = 1L),
        )
    }

    @Test
    fun `bounded content identity detects same-size note edits`() {
        val first = sha256SyncContentHash(
            ByteArrayInputStream("alpha note".encodeToByteArray()),
            expectedBytes = 10,
            maximumBytes = 1024,
        )
        val edited = sha256SyncContentHash(
            ByteArrayInputStream("bravo note".encodeToByteArray()),
            expectedBytes = 10,
            maximumBytes = 1024,
        )

        assertEquals(
            "sha256:5a4a74872cdfe8ef44a4496591946edd2e07fecb4054674637951cb71bb81746",
            first,
        )
        assertEquals(
            "sha256:dd668773c46fe5ed677ba30b9d9d406e62a886a3ec7d3902ad6250cdb933ea26",
            edited,
        )
    }

    @Test
    fun `content identity rejects changed length and oversized input`() {
        assertNull(
            sha256SyncContentHash(
                ByteArrayInputStream("changed".encodeToByteArray()),
                expectedBytes = 6,
                maximumBytes = 1024,
            ),
        )
        assertNull(
            sha256SyncContentHash(
                ByteArrayInputStream("too large".encodeToByteArray()),
                expectedBytes = 9,
                maximumBytes = 4,
            ),
        )
    }

    @Test
    fun `upload staging binds resumable progress to exact copied bytes`() {
        val destination = Files.createTempFile("android-sync-staged-", ".bin").toFile()
        try {
            val first = stageAndroidFileSyncUpload(
                ByteArrayInputStream("alpha note".encodeToByteArray()),
                destination,
                expectedBytes = 10,
                maximumBytes = Long.MAX_VALUE,
            )
            val edited = stageAndroidFileSyncUpload(
                ByteArrayInputStream("bravo note".encodeToByteArray()),
                destination,
                expectedBytes = 10,
                maximumBytes = Long.MAX_VALUE,
            )

            assertEquals("staged-$first", androidStagedFileSyncRevision(first))
            assertEquals(false, first == edited)
            assertFailsWith<IllegalArgumentException> {
                stageAndroidFileSyncUpload(
                    ByteArrayInputStream("short".encodeToByteArray()),
                    destination,
                    expectedBytes = 10,
                    maximumBytes = Long.MAX_VALUE,
                )
            }
        } finally {
            destination.delete()
        }
    }

    @Test
    fun `upload staging observes worker cancellation between copied blocks`() {
        val destination = Files.createTempFile("android-sync-cancelled-", ".bin").toFile()
        var checks = 0
        try {
            assertFailsWith<CancellationException> {
                stageAndroidFileSyncUpload(
                    ByteArrayInputStream(ByteArray(128 * 1024)),
                    destination,
                    expectedBytes = 128L * 1024L,
                    maximumBytes = Long.MAX_VALUE,
                    shouldContinue = { ++checks < 2 },
                )
            }
            assertEquals(64L * 1024L, destination.length())
        } finally {
            destination.delete()
        }
    }
}
