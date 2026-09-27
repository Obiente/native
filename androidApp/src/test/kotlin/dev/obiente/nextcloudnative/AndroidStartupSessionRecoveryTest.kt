package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.NextcloudSessionStorageUnavailableException
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AndroidStartupSessionRecoveryTest {
    @Test
    fun unreadableStoresAndMissingActiveSlotsExposeRecoveryInsteadOfLogin() {
        val session = NextcloudSession("https://cloud.example.test", "alice", "synthetic-password")
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(session.accountRecord())
        assertFailsWith<NextcloudSessionStorageUnavailableException> { loadAndroidStartupSession(null, { true }, { null }) }
        assertFailsWith<NextcloudSessionStorageUnavailableException> { loadAndroidStartupSession(registry, { true }, { null }) }
        assertNull(loadAndroidStartupSession(null, { false }, { error("No credentials exist") }))
        assertEquals(session, loadAndroidStartupSession(registry, { true }, { session }))
    }
}
