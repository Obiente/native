package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdministrationAccessCompositionTest {
    @Test
    fun settingsReusesCacheAndAccountSwitchNeverRendersPreviousPermission() = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val recomposerJob = launch(frameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        val composition = Composition(EmptyUnitApplier(), recomposer)
        var frameTime = 0L
        suspend fun advance() {
            yield()
            Snapshot.sendApplyNotifications()
            yield()
            frameClock.sendFrame(frameTime++)
            recomposer.awaitIdle()
        }
        var session by mutableStateOf(NextcloudSession(
            serverUrl = "https://permissions.example.test", loginName = "admin", appPassword = "synthetic",
        ))
        var active by mutableStateOf(true)
        var denyAdmin = false
        var calls = 0
        var rendered: AdministrationAccessController? = null
        val regularRenders = mutableListOf<Boolean>()
        try {
            composition.setContent {
                val account = session.loginName
                val access = rememberAdministrationAccess(session, refreshRequest = 0, active = active) {
                    calls++
                    NextcloudApiResponse(
                        status = if (account == "admin" && !denyAdmin) 200 else 403,
                        contentType = "application/json", etag = null,
                        body = """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":[]}}""".encodeToByteArray(),
                    )
                }
                rendered = access
                if (account == "regular") regularRenders += access.state.canAdminister
            }
            withTimeout(5_000) {
                while (rendered?.state?.canAdminister != true) advance()
            }
            assertEquals(1, calls)
            active = false
            advance()
            active = true
            advance()
            assertEquals(1, calls)
            assertTrue(requireNotNull(rendered).state.canAdminister)

            denyAdmin = true
            val staleController = requireNotNull(rendered)
            requireNotNull(rendered).refresh()
            withTimeout(5_000) {
                while (rendered?.state?.result != NativeAppCatalogResult.Forbidden) advance()
            }
            assertFalse(requireNotNull(rendered).state.canAdminister)
            assertFalse(staleController.canAdminister)

            denyAdmin = false
            requireNotNull(rendered).refresh()
            withTimeout(5_000) {
                while (rendered?.state?.canAdminister != true) advance()
            }
            session = session.copy(loginName = "regular")
            withTimeout(5_000) {
                while (regularRenders.isEmpty() || rendered?.state?.result != NativeAppCatalogResult.Forbidden) advance()
            }
            assertTrue(regularRenders.isNotEmpty())
            assertTrue(regularRenders.none { it })
        } finally {
            composition.dispose()
            recomposer.close()
            recomposerJob.join()
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
