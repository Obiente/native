package dev.obiente.nextcloudnative.app

import dev.obiente.nextcloudnative.nativeui.model.DynamicWriteRuleState
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic accounts, apps, and routes only. */
class DynamicWriteQuarantineStoreTest {
    private val directory: File = Files.createTempDirectory("write-safety-test").toFile()

    @AfterTest
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `a quarantine survives a simulated process restart`() = runBlocking<Unit> {
        FileDynamicWriteQuarantineStore(directory).quarantine(key(), DynamicWriteQuarantineReason.untouchedFieldDrift, 10)

        val restarted = FileDynamicWriteQuarantineStore(directory)
        val record = restarted.record(key())
        assertEquals(DynamicWriteRuleStatus.quarantined, record?.status)
        assertEquals(DynamicWriteQuarantineReason.untouchedFieldDrift, record?.reason)
        assertEquals(DynamicWriteRuleState.quarantined, restarted.ruleState(key(), ruleApplies = false))
        assertEquals(DynamicWriteRuleState.quarantined, restarted.ruleState(key(), ruleApplies = true))
    }

    @Test
    fun `probation is confirmed by a read-back and a confirmation never lifts a quarantine`() = runBlocking<Unit> {
        val store = FileDynamicWriteQuarantineStore(directory)
        assertEquals(DynamicWriteRuleState.probation, store.ruleState(key(), ruleApplies = true), "An unseen rule starts in probation.")
        assertEquals(DynamicWriteRuleState.notRequired, store.ruleState(key(), ruleApplies = false))
        store.beginProbation(key(), 1)
        store.confirm(key(), 2)
        assertEquals(DynamicWriteRuleState.confirmed, FileDynamicWriteQuarantineStore(directory).ruleState(key(), ruleApplies = true))

        store.quarantine(key(), DynamicWriteQuarantineReason.encodingMismatch, 3)
        store.confirm(key(), 4)
        assertEquals(DynamicWriteRuleStatus.quarantined, store.record(key())?.status)
    }

    @Test
    fun `a rule version bump restarts probation for the same route`() = runBlocking<Unit> {
        val store = FileDynamicWriteQuarantineStore(directory)
        store.confirm(key(ruleVersion = 1), 1)
        assertEquals(DynamicWriteRuleState.probation, store.ruleState(key(ruleVersion = 2), ruleApplies = true))
        store.beginProbation(key(ruleVersion = 2), 2)
        assertNull(store.record(key(ruleVersion = 1)), "The superseded rule version no longer counts.")
        assertEquals(DynamicWriteRuleStatus.probation, FileDynamicWriteQuarantineStore(directory).record(key(ruleVersion = 2))?.status)
    }

    @Test
    fun `only an app version change or the person clears a quarantine`() = runBlocking<Unit> {
        val store = FileDynamicWriteQuarantineStore(directory)
        store.quarantine(key(), DynamicWriteQuarantineReason.notAppliedAfterSuccess, 1)
        store.observeAppVersion(ACCOUNT, "notebook", "2.0.0")
        assertEquals(DynamicWriteRuleStatus.quarantined, store.record(key())?.status, "The same version keeps it.")

        store.observeAppVersion(ACCOUNT, "notebook", "2.1.0")
        assertNull(FileDynamicWriteQuarantineStore(directory).record(key()))

        store.quarantine(key(), DynamicWriteQuarantineReason.notAppliedAfterSuccess, 2)
        store.clear(key())
        assertNull(FileDynamicWriteQuarantineStore(directory).record(key()))
    }

    @Test
    fun `accounts and apps stay separate`() = runBlocking<Unit> {
        val store = FileDynamicWriteQuarantineStore(directory)
        store.quarantine(key(), DynamicWriteQuarantineReason.untouchedFieldDrift, 1)
        store.quarantine(key(appId = "ledger"), DynamicWriteQuarantineReason.untouchedFieldDrift, 1)
        store.quarantine(key(account = OTHER_ACCOUNT), DynamicWriteQuarantineReason.untouchedFieldDrift, 1)

        assertEquals(listOf("notebook"), store.records(ACCOUNT, "notebook").map { it.key.appId })
        store.clearApp(ACCOUNT, "notebook")
        assertTrue(store.records(ACCOUNT, "notebook").isEmpty())
        assertEquals(1, store.records(ACCOUNT, "ledger").size)
        assertEquals(1, store.records(OTHER_ACCOUNT, "notebook").size)

        store.removeAccount(OTHER_ACCOUNT)
        assertTrue(FileDynamicWriteQuarantineStore(directory).records(OTHER_ACCOUNT, "notebook").isEmpty())
    }

    @Test
    fun `an unreadable file fails closed until the person clears it`() = runBlocking<Unit> {
        directory.mkdirs()
        File(directory, "$ACCOUNT.json").writeText("""{"version":1,"records":[{"broken":true}]}""")
        val store = FileDynamicWriteQuarantineStore(directory)
        assertFailsWith<IllegalStateException> { store.record(key()) }
        assertFailsWith<IllegalStateException> { store.beginProbation(key(), 1) }

        store.clearApp(ACCOUNT, "notebook")
        assertNull(store.record(key()))
    }

    @Test
    fun `records naming another account are rejected as a whole`() = runBlocking<Unit> {
        val store = FileDynamicWriteQuarantineStore(directory)
        store.quarantine(key(), DynamicWriteQuarantineReason.untouchedFieldDrift, 1)
        File(directory, "$ACCOUNT.json").copyTo(File(directory, "$OTHER_ACCOUNT.json"))
        assertFailsWith<IllegalStateException> { FileDynamicWriteQuarantineStore(directory).record(key(account = OTHER_ACCOUNT)) }
    }

    @Test
    fun `the record bound keeps quarantines over older states`() = runBlocking<Unit> {
        val store = MemoryDynamicWriteQuarantineStore()
        store.quarantine(key(route = "PUT /apps/notebook/api/q"), DynamicWriteQuarantineReason.untouchedFieldDrift, 0)
        repeat(MAX_RECORDS_PER_ACCOUNT + 5) { index ->
            store.confirm(key(route = "PUT /apps/notebook/api/r$index"), index + 1L)
        }
        val records = store.records(ACCOUNT, "notebook")
        assertEquals(MAX_RECORDS_PER_ACCOUNT, records.size)
        assertTrue(records.any { it.status == DynamicWriteRuleStatus.quarantined })
    }

    @Test
    fun `keys reject unsafe parts and never print the account`() {
        assertFailsWith<IllegalArgumentException> { key(account = "not-a-digest") }
        assertFailsWith<IllegalArgumentException> { key(appId = "../notebook") }
        assertFailsWith<IllegalArgumentException> { key(route = "PUT /a\n/b") }
        assertTrue(ACCOUNT !in key().toString())
    }

    private fun key(
        account: String = ACCOUNT,
        appId: String = "notebook",
        route: String = "PUT /apps/notebook/api/notes/{id}",
        ruleVersion: Int = 1,
    ) = DynamicWriteRuleKey(account, appId, "2.0.0", route, "full-echo", ruleVersion)

    private companion object {
        val ACCOUNT = "a".repeat(64)
        val OTHER_ACCOUNT = "b".repeat(64)
    }
}
