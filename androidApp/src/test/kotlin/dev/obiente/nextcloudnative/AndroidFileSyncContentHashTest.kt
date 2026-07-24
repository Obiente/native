package dev.obiente.nextcloudnative

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidFileSyncContentHashTest {
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
}
