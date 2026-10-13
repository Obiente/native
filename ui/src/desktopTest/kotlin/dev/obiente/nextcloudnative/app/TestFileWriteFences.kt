package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

/** Test-only fence for scenes that do not exercise deletes; production callers always pass a real fence. */
internal val NoFileWriteBlocksForTests = FileWriteFence { null }

/** A delete owner for [session] in a test-only registry scoped to the scene. */
@Composable
internal fun rememberFileDeletesForTests(session: NextcloudSession): FileDeleteCoordinator {
    val scope = rememberCoroutineScope()
    return remember(scope, session) { FileWriteFenceRegistry(scope).account(session.accountId).deletes }
}
