package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class AppWorkspaceStateTest {
    @Test fun shellChangesDoNotRegisterAnAppKeyTwiceAndAppStateRestores() = runBlocking {
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + clock)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(EmptyApplier(), recomposer)
        val fullScreen = mutableStateOf(false)
        val app = mutableStateOf<String?>("photos")
        var observed = -1
        var changeValue: (Int) -> Unit = {}
        composition.setContent {
            AppWorkspaceState(rememberSaveableStateHolder(), app.value) {
                val count = rememberSaveable { mutableStateOf(0) }
                SideEffect { observed = count.value; changeValue = { count.value = it } }
                // Both presentation branches may be replaced in a single apply pass.
                if (fullScreen.value) SideEffect {} else SideEffect {}
            }
        }
        suspend fun settle() {
            Snapshot.sendApplyNotifications()
            yield()
            clock.sendFrame(System.nanoTime())
            recomposer.awaitIdle()
        }
        try {
            settle()
            changeValue(7)
            settle()
            repeat(8) { fullScreen.value = !fullScreen.value; settle(); assertEquals(7, observed) }
            app.value = "files"
            settle()
            assertEquals(0, observed)
            app.value = "photos"
            settle()
            assertEquals(7, observed)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.join()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
