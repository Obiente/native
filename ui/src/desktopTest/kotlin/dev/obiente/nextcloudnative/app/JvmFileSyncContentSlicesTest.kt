package dev.obiente.nextcloudnative.app

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

class JvmFileSyncContentSlicesTest {
    @Test
    fun `matching slices advance a durable aggregate and complete only at EOF`() {
        val candidate = FileSyncContentVerificationCandidate("large.bin", "local", "remote", 6L)
        val completeHash = hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6)), 6)
        val first = FileSyncContentVerificationSlice(candidate, 0L, 3, EMPTY_FILE_SYNC_IDENTITY_AGGREGATE)
        val firstHash = hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val firstOutcome = completeJvmFileSyncContentSlice(first, firstHash, firstHash, completeHash)
        val progress = requireNotNull(firstOutcome.progress)

        assertEquals(3L, progress.verifiedBytes)
        assertNotEquals(EMPTY_FILE_SYNC_IDENTITY_AGGREGATE, progress.aggregateHash)

        val second = FileSyncContentVerificationSlice(candidate, 3L, 3, progress.aggregateHash)
        val secondHash = hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(4, 5, 6)), 3)
        val completed = requireNotNull(
            completeJvmFileSyncContentSlice(second, secondHash, secondHash, completeHash).result,
        )

        assertEquals(completed.localContentHash, completed.matchingContentHash)
        assertEquals(completeHash, completed.matchingContentHash)
    }

    @Test
    fun `one different range proves a mismatch without reading the rest`() {
        val candidate = FileSyncContentVerificationCandidate("large.bin", "local", "remote", Long.MAX_VALUE)
        val slice = FileSyncContentVerificationSlice(candidate, 0L, 3, EMPTY_FILE_SYNC_IDENTITY_AGGREGATE)
        val local = hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)
        val remote = hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(1, 2, 4)), 3)
        val localContentHash = hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(1, 2, 3, 5)), 4)

        val result = requireNotNull(
            completeJvmFileSyncContentSlice(slice, local, remote, localContentHash).result,
        )

        assertEquals(null, result.matchingContentHash)
        assertEquals(localContentHash, result.localContentHash)
    }

    @Test
    fun `sequential range fallback skips exact bytes without seek support`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6))

        skipExactJvmFileSyncBytes(input, 3L)

        assertEquals(
            hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf(4, 5)), 2),
            hashExactJvmFileSyncSlice(input, 2),
        )
    }

    @Test
    fun `sequential range fallback preserves cancellation`() {
        assertFailsWith<CancellationException> {
            skipExactJvmFileSyncBytes(ByteArrayInputStream(byteArrayOf(1)), 1L) { false }
        }
    }
}
