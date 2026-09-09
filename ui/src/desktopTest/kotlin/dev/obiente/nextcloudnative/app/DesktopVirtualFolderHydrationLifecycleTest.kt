package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopVirtualFolderHydrationLifecycleTest {
    @Test
    fun `account resource preflight counts a registered lazy hydration job`() = runBlocking {
        val registeredJob = launch(start = CoroutineStart.LAZY) {}

        assertFalse(registeredJob.isActive)
        assertTrue(hasLiveVirtualFolderHydrationJobs(listOf(registeredJob)))

        registeredJob.cancelAndJoin()
        assertFalse(hasLiveVirtualFolderHydrationJobs(listOf(registeredJob)))
    }
}
