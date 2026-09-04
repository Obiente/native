package dev.obiente.nextcloudnative

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidFileSyncKeepBothRecoveryTest {
    @Test
    fun `source authentication failure publishes no conflict copies`() {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalArgumentException> {
            publishAuthenticatedAndroidFileSyncKeepBoth(
                authenticateSource = {
                    events += "authenticate-source"
                    throw IllegalArgumentException("same-revision content changed")
                },
                publishConflictCopies = listOf(
                    { events += "publish-local-copy" },
                    { events += "publish-server-copy" },
                ),
                replaceOriginal = { events += "replace-original" },
            )
        }

        assertEquals(listOf("authenticate-source"), events)
    }

    @Test
    fun `retry reuses an existing exact conflict copy`() {
        val events = mutableListOf<String>()

        ensureExactAndroidFileSyncConflictCopy(
            exists = {
                events += "find"
                true
            },
            create = { events += "create" },
            verify = { events += "verify-exact-content" },
        )

        assertEquals(listOf("find", "verify-exact-content"), events)
    }

    @Test
    fun `first publication creates then authenticates the conflict copy`() {
        val events = mutableListOf<String>()

        ensureExactAndroidFileSyncConflictCopy(
            exists = {
                events += "find"
                false
            },
            create = { events += "create" },
            verify = { events += "verify-exact-content" },
        )

        assertEquals(listOf("find", "create", "verify-exact-content"), events)
    }

    @Test
    fun `local conflict copy stops streaming when the sync is cancelled`() {
        val source = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        var continuationChecks = 0
        val input = object : FilterInputStream(ByteArrayInputStream(source)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, length.coerceAtMost(2))
        }

        assertFailsWith<CancellationException> {
            copyAndroidFileSyncWithCancellation(input, output) {
                continuationChecks += 1
                continuationChecks < 3
            }
        }

        assertContentEquals(byteArrayOf(1, 2), output.toByteArray())
    }
}
