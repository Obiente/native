package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class FileSyncSettingsController(
    private val scope: CoroutineScope,
    private val save: suspend (FileSyncConfiguration, FileSyncConfiguration) -> FileSyncCenterActionResult,
) {
    private var job: Job? = null
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun apply(expected: FileSyncConfiguration, configuration: FileSyncConfiguration, onApplied: (String) -> Unit) {
        if (busy) return
        busy = true
        error = null
        job = scope.launch {
            try {
                val result = save(expected, configuration)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is FileSyncCenterActionResult.Completed -> onApplied(result.message)
                    is FileSyncCenterActionResult.Rejected -> error = result.reason
                    is FileSyncCenterActionResult.Unsupported -> error = result.reason
                    is FileSyncCenterActionResult.Stopped -> error = result.message
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Could not save sync settings. Reopen the sync to check which settings were saved."
            } finally { busy = false }
        }
    }

    fun close() { job?.cancel() }
}

@Composable
internal fun FileSyncSettingsEditorRoute(
    services: FileSyncPlatformServices,
    session: NextcloudSession,
    userId: String,
    pair: FileSyncPairSummary,
    onDismiss: () -> Unit,
    onApplied: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(services, session, userId, pair.id, scope) {
        FileSyncSettingsController(scope) { expected, configuration ->
            services.updateFileSyncPair(session, userId, pair.id, expected, configuration)
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    FileSyncSettingsEditor(
        pair, controller.busy,
        controller.error ?: if (userId.isBlank()) "Connect to your account to save these changes." else null,
        onDismiss, saveEnabled = userId.isNotBlank(),
    ) { expected, configuration ->
        controller.apply(expected, configuration, onApplied)
    }
}
