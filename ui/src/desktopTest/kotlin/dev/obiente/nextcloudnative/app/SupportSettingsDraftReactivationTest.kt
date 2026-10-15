package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.fail

class SupportSettingsDraftReactivationTest {
    @Test
    fun `live settings composition acquires a new holder after safe reactivation`() = runBlocking<Unit> {
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

        // Apply notifications can also arrive from another thread, for example after an earlier
        // desktop UI test started the global snapshot manager. A single frame can then be sent
        // before the recomposer waits for one, so keep offering frames while work is pending.
        suspend fun advanceCompositionUntil(stage: String, settled: () -> Boolean) {
            withTimeoutOrNull(COMPOSITION_TIMEOUT_MILLIS) {
                do {
                    yield()
                    Snapshot.sendApplyNotifications()
                    yield()
                    while (recomposer.hasPendingWork) {
                        frameClock.sendFrame(frameTime++)
                        yield()
                    }
                } while (!settled())
            } ?: fail("Composition did not settle within $COMPOSITION_TIMEOUT_MILLIS ms while $stage.")
        }

        var rendered: SupportSettingsDraftState? = null
        var teardownFailure: String? = null
        try {
            composition.setContent {
                rendered = rememberAccountSupportSettingsDraftState(session)
            }
            advanceCompositionUntil("composing the initial holder") { rendered != null }
            val retiredHolder = requireNotNull(rendered)
            val staleCallback = retiredHolder::updateReportDraft
            retiredHolder.updateReportDraft("Private text before removal")

            AccountPrivateMemoryLifecycle.retireAccount(account)

            assertFalse(retiredHolder.hasDraftContent())
            AccountPrivateMemoryLifecycle.activateAccount(account)
            advanceCompositionUntil("waiting for the reactivated holder") { rendered !== retiredHolder }
            val currentHolder = requireNotNull(rendered)
            assertNotSame(retiredHolder, currentHolder)

            staleCallback("Late callback from the retired screen")
            currentHolder.updateReportDraft("Fresh text after recovery")

            assertEquals("", retiredHolder.reportDraft)
            assertEquals("Fresh text after recovery", currentHolder.reportDraft)
        } finally {
            try {
                composition.dispose()
                teardownFailure = stopRecomposer(recomposer, recomposerJob)
            } finally {
                AccountPrivateMemoryLifecycle.retireAccount(account)
                AccountPrivateMemoryLifecycle.activateAccount(account)
            }
        }
        teardownFailure?.let { fail(it) }
    }

    /**
     * Recomposer.close() does not resume a runner that already waits for a frame once an effect
     * is still completing, and this frame clock sends no more frames, so join() would never
     * return. cancel() stops the runner in every state; the runner job is cancelled as a fallback.
     */
    private suspend fun stopRecomposer(recomposer: Recomposer, runner: Job): String? =
        withContext(NonCancellable) {
            recomposer.cancel()
            if (withTimeoutOrNull(TEARDOWN_TIMEOUT_MILLIS) { runner.join() } != null) {
                null
            } else {
                runner.cancel()
                "Recomposer did not stop within $TEARDOWN_TIMEOUT_MILLIS ms after cancellation."
            }
        }

    private class EmptyUnitApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        const val COMPOSITION_TIMEOUT_MILLIS = 5_000L
        const val TEARDOWN_TIMEOUT_MILLIS = 5_000L
    }
}
