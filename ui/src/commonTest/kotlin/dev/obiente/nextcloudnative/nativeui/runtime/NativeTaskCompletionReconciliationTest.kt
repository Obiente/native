package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeTaskCompletionReconciliationTest {
    @Test
    fun `optimistic completion is scoped to one authoritative record list`() {
        val records = listOf(NativeRecord("task-1", mapOf("completed" to "false")))
        val authoritativeKey = NativeAuthoritativeRecordsKey(records)
        val equivalentKey = NativeAuthoritativeRecordsKey(records)
        val refreshedKey = NativeAuthoritativeRecordsKey(records.toList())
        val override = NativeCompletionOverride(
            completed = true,
            sourceRecordsKey = authoritativeKey,
        )

        assertTrue(effectiveNativeCompletion(override, equivalentKey, authoritativeCompleted = false))
        assertFalse(effectiveNativeCompletion(override, refreshedKey, authoritativeCompleted = false))

        val overrides = mutableMapOf("task-1" to override)
        overrides.reconcileNativeCompletionOverrides(refreshedKey)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun `only an unknown completion result waits for authoritative reconciliation`() {
        val initialKey = NativeAuthoritativeRecordsKey(
            listOf(NativeRecord("task-1", mapOf("completed" to "false"))),
        )
        val failures = mutableMapOf<String, NativeAuthoritativeRecordsKey>()

        assertTrue(
            failures.recordNativeCompletionFailure(
                recordId = "task-1",
                authoritativeRecordsKey = initialKey,
                outcome = NativeActionFailureOutcome.Unknown,
            ),
        )
        assertTrue(failures.isNativeCompletionReconciling("task-1", initialKey))

        val refreshedKey = NativeAuthoritativeRecordsKey(
            listOf(NativeRecord("task-1", mapOf("completed" to "true"))),
        )
        assertEquals(setOf("task-1"), failures.reconcileNativeCompletionFailures(refreshedKey))
        assertFalse(failures.isNativeCompletionReconciling("task-1", refreshedKey))

        assertFalse(
            failures.recordNativeCompletionFailure(
                recordId = "task-1",
                authoritativeRecordsKey = refreshedKey,
                outcome = NativeActionFailureOutcome.Rejected,
            ),
        )
        assertTrue(failures.isEmpty())
    }
}
