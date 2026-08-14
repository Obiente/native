package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDurableMutationRecoveryStoreTest {
    @Test
    fun `recovery state is exclusively published and compare cleared across instances`() {
        val root = Files.createTempDirectory("mutation-recovery-test").toFile()
        try {
            val firstStore = DesktopDurableMutationRecoveryStore(root)
            val secondStore = DesktopDurableMutationRecoveryStore(root)
            val scope = "a".repeat(64)

            assertNull(firstStore.load(scope, DurableMutationRecoveryKind.Calendar))
            assertTrue(firstStore.save(scope, DurableMutationRecoveryKind.Calendar, "first"))
            assertFalse(secondStore.save(scope, DurableMutationRecoveryKind.Calendar, "second"))
            assertEquals("first", secondStore.load(scope, DurableMutationRecoveryKind.Calendar))
            assertFalse(secondStore.clear(scope, DurableMutationRecoveryKind.Calendar, "second"))
            assertEquals("first", firstStore.load(scope, DurableMutationRecoveryKind.Calendar))
            assertTrue(firstStore.clear(scope, DurableMutationRecoveryKind.Calendar, "first"))
            assertNull(secondStore.load(scope, DurableMutationRecoveryKind.Calendar))
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

    @Test
    fun `recovery state fails closed when owner-only permissions drift`() {
        val root = Files.createTempDirectory("mutation-recovery-permissions-test").toFile()
        try {
            if (Files.getFileAttributeView(root.toPath(), PosixFileAttributeView::class.java) == null) return
            val store = DesktopDurableMutationRecoveryStore(root)
            val scope = "c".repeat(64)

            assertTrue(store.save(scope, DurableMutationRecoveryKind.NoteDeletion, "safe"))
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(root.toPath()),
            )
            val record = root.resolve("${DurableMutationRecoveryKind.NoteDeletion.storageKey}-$scope.json")
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(record.toPath()),
            )

            Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            assertFailsWith<IllegalStateException> {
                store.load(scope, DurableMutationRecoveryKind.NoteDeletion)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
