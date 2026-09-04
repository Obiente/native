package dev.obiente.nextcloudnative

import kotlin.test.Test
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
}
