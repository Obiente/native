package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlin.test.fail

/** Test-only fence for scenes that do not exercise deletes; production callers always pass a real fence. */
internal val NoFileWriteBlocksForTests = FileWriteFence { null }

/** A delete owner for [session] in a test-only registry scoped to the scene. */
@Composable
internal fun rememberFileDeletesForTests(session: NextcloudSession): FileDeleteCoordinator {
    val scope = rememberCoroutineScope()
    return remember(scope, session) { FileWriteFenceRegistry(scope).account(session.accountId).deletes }
}

/**
 * Runs [block] with a registry whose deletes run on a dedicated single thread owned by the test, so
 * they outlive any scene without sharing a thread with it or with the test's own waits.
 */
internal fun <T> withTestDeleteOwnerThread(block: (FileWriteFenceRegistry) -> T): T {
    val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "test-delete-owner").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    return try {
        block(FileWriteFenceRegistry(scope))
    } finally {
        scope.cancel()
        dispatcher.close()
    }
}

/** Waits on the calling test thread, never a scene or owner thread, and fails with [message] after [timeoutMillis]. */
internal fun awaitTestCondition(message: String, timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) fail("Timed out after $timeoutMillis ms: $message")
        Thread.sleep(10L)
    }
}
