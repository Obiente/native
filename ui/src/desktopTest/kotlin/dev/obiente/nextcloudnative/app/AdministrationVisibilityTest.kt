package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals

class AdministrationVisibilityTest {
    @Test
    fun hiddenWindowStopsAndCancelsPollingAndResumeRestartsIt() {
        val visible = MutableStateFlow(false)
        val session = NextcloudSession("https://permissions.example.test", "sample", "synthetic")
        var calls = 0
        var cancelled = 0
        nativeSceneTest(390, 844, content = {
            CompositionLocalProvider(LocalAppWindowVisibility provides visible) {
                rememberAdministrationAccess(session, 0, active = true) {
                    calls++
                    try { awaitCancellation() } finally { cancelled++ }
                }
            }
        }) {
            assertEquals(0, calls)
            visible.value = true
            settle()
            assertEquals(1, calls)
            visible.value = false
            settle()
            assertEquals(1, cancelled)
            assertEquals(1, calls)
            visible.value = true
            settle()
            assertEquals(2, calls)
            visible.value = false
            settle()
            assertEquals(2, cancelled)
        }
    }
}
