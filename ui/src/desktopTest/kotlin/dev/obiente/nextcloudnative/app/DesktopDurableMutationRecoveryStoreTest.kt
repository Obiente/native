package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDurableMutationRecoveryStoreTest {
    @Test
    fun `recovery state is atomically replaced and durably cleared`() {
        val root = Files.createTempDirectory("mutation-recovery-test").toFile()
        try {
            val store = DesktopDurableMutationRecoveryStore(root)
            val scope = "a".repeat(64)

            assertNull(store.load(scope, DurableMutationRecoveryKind.Calendar))
            assertTrue(store.save(scope, DurableMutationRecoveryKind.Calendar, "first"))
            assertEquals("first", store.load(scope, DurableMutationRecoveryKind.Calendar))
            assertTrue(store.save(scope, DurableMutationRecoveryKind.Calendar, "second"))
            assertEquals("second", store.load(scope, DurableMutationRecoveryKind.Calendar))
            assertTrue(store.clear(scope, DurableMutationRecoveryKind.Calendar))
            assertNull(store.load(scope, DurableMutationRecoveryKind.Calendar))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `recovery state rejects oversized values without replacing the last record`() {
        val root = Files.createTempDirectory("mutation-recovery-limit-test").toFile()
        try {
            val store = DesktopDurableMutationRecoveryStore(root)
            val scope = "b".repeat(64)

            assertTrue(store.save(scope, DurableMutationRecoveryKind.Contacts, "safe"))
            assertFalse(
                store.save(
                    scope,
                    DurableMutationRecoveryKind.Contacts,
                    "x".repeat(MAX_DURABLE_MUTATION_RECOVERY_BYTES + 1),
                ),
            )
            assertEquals("safe", store.load(scope, DurableMutationRecoveryKind.Contacts))
        } finally {
            root.deleteRecursively()
        }
    }
}
