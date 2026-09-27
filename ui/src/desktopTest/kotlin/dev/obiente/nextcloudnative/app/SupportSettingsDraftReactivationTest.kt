package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class SupportSettingsDraftReactivationTest {
    @Test
    fun `live settings composition acquires a new holder after safe reactivation`() = runBlocking {
        val session = NextcloudSession(
            serverUrl = "https://support-reactivation.example.test",
            loginName = "alice",
            appPassword = "password",
        )
        val account = session.accountId.storageKey
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val recomposerJob = launch(frameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(EmptyUnitApplier(), recomposer)
        var frameTime = 0L
        suspend fun advanceComposition() {
            yield()
            Snapshot.sendApplyNotifications()
            yield()
            frameClock.sendFrame(frameTime++)
            recomposer.awaitIdle()
        }
        var rendered: SupportSettingsDraftState? = null
        try {
            composition.setContent {
                rendered = rememberAccountSupportSettingsDraftState(session)
            }
            advanceComposition()
            val retiredHolder = requireNotNull(rendered)
            val staleCallback = retiredHolder::updateReportDraft
            retiredHolder.updateReportDraft("Private text before removal")

            AccountPrivateMemoryLifecycle.retireAccount(account)

            assertFalse(retiredHolder.hasDraftContent())
            AccountPrivateMemoryLifecycle.activateAccount(account)
            withTimeout(5_000L) {
                while (rendered === retiredHolder) advanceComposition()
            }
            val currentHolder = requireNotNull(rendered)
            assertNotSame(retiredHolder, currentHolder)

            staleCallback("Late callback from the retired screen")
            currentHolder.updateReportDraft("Fresh text after recovery")

            assertEquals("", retiredHolder.reportDraft)
            assertEquals("Fresh text after recovery", currentHolder.reportDraft)
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
            AccountPrivateMemoryLifecycle.retireAccount(account)
            AccountPrivateMemoryLifecycle.activateAccount(account)
        }
    }

    private class EmptyUnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
