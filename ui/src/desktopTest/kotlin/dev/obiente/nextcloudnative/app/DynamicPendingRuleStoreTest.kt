package dev.obiente.nextcloudnative.app

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlin.test.*

class DynamicPendingRuleStoreTest {
    @Test
    fun `pending token survives ordinary transitions version changes and restart`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("native-phase3-state-rule-store").toFile()
        try {
            val store = FileDynamicWriteQuarantineStore(directory)
            assertTrue(store.reserve(key, pending, store.generation(account)))
            store.confirm(key, 2)
            store.clear(key)
            store.clearApp(account, key.appId)
            store.observeAppVersion(account, key.appId, "2.0.0")
            store.beginProbation(key.copy(ruleVersion = 2, appVersion = "2.0.0"), 4)
            val restarted = FileDynamicWriteQuarantineStore(directory)
            assertEquals(pending, restarted.record(key)?.pendingVerification)
            assertFalse(restarted.reserve(key.copy(ruleVersion = 2, appVersion = "2.0.0"), pending.copy(operationId = otherToken), 0))
            assertFalse(restarted.resolve(key, otherToken, DynamicPendingRuleResolution.confirmed, 5))
            assertEquals(pending, restarted.record(key)?.pendingVerification)
            assertTrue(restarted.resolve(key, pending.operationId, DynamicPendingRuleResolution.confirmed, 6))
            assertNull(restarted.record(key)?.pendingVerification)
            assertEquals(DynamicWriteRuleStatus.confirmed, restarted.record(key)?.status)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `concurrent store instances reserve only one token and reload authoritative state`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("native-phase3-state-rule-concurrent").toFile()
        try {
            val one = FileDynamicWriteQuarantineStore(directory)
            val two = FileDynamicWriteQuarantineStore(directory.resolve("."))
            assertNull(one.record(key)); assertNull(two.record(key))
            val results = listOf(one, two).mapIndexed { index, store -> async(Dispatchers.Default) {
                store.reserve(key.copy(ruleVersion = index + 1), pending.copy(operationId = if (index == 0) pending.operationId else otherToken), 0)
            } }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(1, one.records(account, key.appId).count { it.pendingVerification != null })
            assertEquals(one.records(account, key.appId), two.records(account, key.appId))
            val generation = one.generation(account)
            two.removeAccount(account)
            assertFalse(one.reserve(key, pending, generation))
            assertTrue(one.records(account, key.appId).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `protected capacity refuses a new reservation instead of forgetting pending or quarantined state`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        repeat(MAX_RECORDS_PER_ACCOUNT) { index ->
            val route = key.copy(route = "/apps/synthetic/collection$index/{}")
            if (index % 2 == 0) assertTrue(store.reserve(route, pending, 0))
            else store.quarantine(route, DynamicWriteQuarantineReason.encodingMismatch, 1)
        }
        assertFalse(store.reserve(key, pending, 0))
        assertFailsWith<IllegalStateException> { store.beginProbation(key, 2) }
        assertEquals(MAX_RECORDS_PER_ACCOUNT, store.records(account, key.appId).size)
        assertEquals(MAX_RECORDS_PER_ACCOUNT / 2, store.records(account, key.appId).count { it.pendingVerification != null })
    }

    @Test
    fun `old persisted format restores and unreadable state cannot be cleared as unsent`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("native-phase3-state-rule-format").toFile()
        try {
            val encoded = Json.encodeToString(DynamicWriteRuleRecord.serializer(), DynamicWriteRuleRecord(key, DynamicWriteRuleStatus.probation, updatedAtEpochMillis = 1))
            directory.resolve("$account.json").writeText("""{"version":1,"records":[$encoded]}""")
            val store = FileDynamicWriteQuarantineStore(directory)
            assertNull(store.record(key)?.pendingVerification)
            assertTrue(store.reserve(key, pending, 0))
            val saved = directory.resolve("$account.json").readText()
            assertTrue("pendingVerification" in saved)
            directory.resolve("$account.json").writeText("invalid")
            assertFailsWith<IllegalStateException> { store.clearApp(account, key.appId) }
            assertFailsWith<IllegalStateException> { store.reserve(key, pending, 0) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `stale resolution never clears a replacement operation token`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("native-phase3-state-rule-clear").toFile()
        try {
            val store = FileDynamicWriteQuarantineStore(directory)
            assertTrue(store.reserve(key, pending, 0))
            assertTrue(store.resolve(key, pending.operationId, DynamicPendingRuleResolution.rejected, 2))
            val next = pending.copy(operationId = otherToken)
            assertTrue(store.reserve(key, next, 0))
            assertFalse(store.resolve(key, pending.operationId, DynamicPendingRuleResolution.contradictedPending, 3))
            assertEquals(next, store.record(key)?.pendingVerification)
            assertEquals(DynamicWriteRuleStatus.probation, store.record(key)?.status)
        } finally { directory.deleteRecursively() }
    }

    private val account = "a".repeat(64)
    private val key = DynamicWriteRuleKey(account, "synthetic", "1.0.0", "/apps/synthetic/items/{}", "item-state-pair", 1)
    private val pending = DynamicPendingRuleOperation("11111111-1111-4111-8111-111111111111", 1)
    private val otherToken = "22222222-2222-4222-8222-222222222222"
}
