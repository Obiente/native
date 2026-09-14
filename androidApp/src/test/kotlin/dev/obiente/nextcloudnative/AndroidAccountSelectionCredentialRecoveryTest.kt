package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidAccountSelectionCredentialRecoveryTest {
    @Test
    fun `valid credential slots recover account selection around a malformed aggregate`() {
        val first = NextcloudSession("https://one.example.test", "alice", "first-secret")
        val second = NextcloudSession("https://two.example.test", "bob", "second-secret")
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
        val slots = mapOf(first.accountId to first, second.accountId to second)

        val recovery = recoverAndroidAccountCredentialStateForSelection(
            AndroidAccountCredentialStoreRead.Invalid("malformed-encrypted-aggregate"),
        ) {
            reconstructAndroidAccountCredentialState(registry, slots::get)
        }
        val selected = assertNotNull(recovery.state.select(first.accountId))

        assertEquals(first, selected.activeSession)
        assertEquals(slots, selected.sessions)
        assertEquals("malformed-encrypted-aggregate", recovery.suspectEncrypted)
    }

    @Test
    fun `recovered state preserves unreadable slots until their registry account is removed`() {
        val available = NextcloudSession("https://one.example.test", "alice", "first-secret")
        val unreadable = NextcloudSession("https://two.example.test", "bob", "second-secret")
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(unreadable.accountRecord())
            .upsertAndSelect(available.accountRecord())
        val recovered = assertNotNull(
            reconstructAndroidAccountCredentialState(registry) { accountId ->
                available.takeIf { accountId == available.accountId }
            },
        )

        assertNull(recovered.sessions[unreadable.accountId])
        assertEquals(
            setOf(
                androidAccountCredentialSlotKey(available.accountId),
                androidAccountCredentialSlotKey(unreadable.accountId),
            ),
            retainedAndroidAccountCredentialSlotKeys(recovered),
        )
        assertEquals(
            setOf(androidAccountCredentialSlotKey(available.accountId)),
            retainedAndroidAccountCredentialSlotKeys(recovered.remove(unreadable.accountId)),
        )
    }
}
