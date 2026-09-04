package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
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

    @Test
    fun `staged hash authenticates a differently sized conflict from replacement evidence`() {
        val contentHash = "sha256:${"a".repeat(64)}"
        val observed = LocalSyncEntry(
            relativePath = "note.md",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 4L,
            replacementAuthentication = contentHash,
        )
        val staged = observed.copy(contentHash = contentHash, replacementAuthentication = null)

        assertEquals(contentHash, authenticatedStagedAndroidFileSyncContentHash(observed, staged))
    }

    @Test
    fun `changed staged hash cannot use older replacement evidence`() {
        val observed = LocalSyncEntry(
            relativePath = "note.md",
            kind = SyncEntryKind.File,
            revision = "local-1",
            size = 4L,
            replacementAuthentication = "sha256:${"a".repeat(64)}",
        )
        val staged = observed.copy(
            contentHash = "sha256:${"b".repeat(64)}",
            replacementAuthentication = null,
        )

        assertFailsWith<IllegalArgumentException> {
            authenticatedStagedAndroidFileSyncContentHash(observed, staged)
        }
    }
}
