package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountHomeWorkspaceStorageTest {
    @Test
    fun retiredWritersCannotRecreatePinsOrLayoutsAfterReactivation() {
        val gate = AccountPrivateMemoryGate()
        val values = mutableMapOf<String, String>()
        val delegate = object : HomeWorkspaceLayoutStorage {
            override fun read(persistenceKey: String) = values[persistenceKey]
            override fun write(persistenceKey: String, encodedSnapshot: String) {
                values[persistenceKey] = encodedSnapshot
            }
        }
        val account = "a".repeat(64)
        val old = AccountHomeWorkspaceStorage(delegate, account, null, gate)
        val keys = homeWorkspaceAccountPersistenceKeys(account)
        keys.forEach { old.write(it, "original") }
        gate.retireAccount(account) { values.clear() }
        keys.forEach { key ->
            assertFailsWith<IllegalStateException> { old.write(key, "stale") }
            assertFailsWith<IllegalStateException> { old.writeIfAbsent(key, "stale migration") }
        }
        gate.activateAccount(account)
        val current = AccountHomeWorkspaceStorage(delegate, account, null, gate)
        keys.forEach { key ->
            assertFailsWith<IllegalStateException> { old.write(key, "stale") }
            assertNull(current.read(key))
            assertTrue(current.writeIfAbsent(key, "new"))
            assertEquals("new", current.read(key))
        }
    }

    @Test
    fun ownerFenceRejectsOtherAccountKeys() {
        val gate = AccountPrivateMemoryGate()
        val delegate = object : HomeWorkspaceLayoutStorage {
            override fun read(persistenceKey: String): String? = error("Unexpected read")
            override fun write(persistenceKey: String, encodedSnapshot: String) = error("Unexpected write")
        }
        val storage = AccountHomeWorkspaceStorage(delegate, "a".repeat(64), null, gate)
        val other = homeWorkspaceAccountPersistenceKeys("b".repeat(64)).first()
        assertFailsWith<IllegalArgumentException> { storage.write(other, "unrelated") }
    }
}
