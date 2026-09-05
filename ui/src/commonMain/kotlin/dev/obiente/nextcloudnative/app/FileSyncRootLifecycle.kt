package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State

@Composable
internal fun AbandonFileSyncRootOnDispose(
    services: NextcloudPlatformServices,
    localRoot: State<FileSyncLocalRoot?>,
) {
    DisposableEffect(services, localRoot) {
        onDispose(fileSyncRootDisposal({ localRoot.value }, services::abandonFileSyncLocalRoot))
    }
}

internal fun fileSyncRootDisposal(
    currentRoot: () -> FileSyncLocalRoot?,
    abandon: (FileSyncLocalRoot) -> Unit,
): () -> Unit = { currentRoot()?.let(abandon) }
