package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.CancellationException

internal class FileSyncSetupDraftState private constructor(
    localRoot: FileSyncLocalRoot?,
    mediaSuggestionJson: String?,
    remotePath: String?,
    configurationJson: String?,
    remoteFolderPickerVisible: Boolean,
    selectionPickerVisible: Boolean,
) {
    constructor() : this(null, null, null, null, false, false)

    val localRoot: MutableState<FileSyncLocalRoot?> = mutableStateOf(localRoot)
    val mediaSuggestionJson: MutableState<String?> = mutableStateOf(mediaSuggestionJson)
    val remotePath: MutableState<String?> = mutableStateOf(remotePath)
    val configurationJson: MutableState<String?> = mutableStateOf(configurationJson)
    val remoteFolderPickerVisible: MutableState<Boolean> = mutableStateOf(remoteFolderPickerVisible)
    val selectionPickerVisible: MutableState<Boolean> = mutableStateOf(selectionPickerVisible)

    fun clear() {
        localRoot.value = null
        mediaSuggestionJson.value = null
        remotePath.value = null
        configurationJson.value = null
        remoteFolderPickerVisible.value = false
        selectionPickerVisible.value = false
    }

    fun abandon(abandonRoot: (FileSyncLocalRoot) -> Boolean): Boolean {
        val abandoned = localRoot.value?.let { root -> tryAbandonFileSyncRoot(root, abandonRoot) } ?: true
        if (abandoned) {
            clear()
        } else {
            remotePath.value = null
            configurationJson.value = null
            remoteFolderPickerVisible.value = false
            selectionPickerVisible.value = false
        }
        return abandoned
    }

    companion object {
        fun restore(saved: List<String>): FileSyncSetupDraftState? {
            if (saved.size != SAVED_SETUP_FIELD_COUNT || saved[0] != SAVED_SETUP_VERSION ||
                saved.sumOf(String::length) > MAX_SAVED_SETUP_CHARACTERS
            ) {
                return null
            }
            val root = when (saved[1]) {
                "0" -> null
                "1" -> runCatching { FileSyncLocalRoot(saved[2], saved[3]) }.getOrNull() ?: return null
                else -> return null
            }
            val remotePath = when (saved[5]) {
                "0" -> null
                "1" -> saved[6]
                else -> return null
            }
            val remotePickerVisible = saved[8].toBooleanStrictOrNull() ?: return null
            val selectionPickerVisible = saved[9].toBooleanStrictOrNull() ?: return null
            return FileSyncSetupDraftState(
                localRoot = root,
                mediaSuggestionJson = saved[4].ifEmpty { null },
                remotePath = remotePath,
                configurationJson = saved[7].ifEmpty { null },
                remoteFolderPickerVisible = remotePickerVisible,
                selectionPickerVisible = selectionPickerVisible,
            )
        }
    }
}

internal fun FileSyncSetupDraftState.savedState(): List<String>? {
    val root = localRoot.value
    val remote = remotePath.value
    val saved = listOf(
        SAVED_SETUP_VERSION,
        if (root == null) "0" else "1",
        root?.localRootId.orEmpty(),
        root?.displayName.orEmpty(),
        mediaSuggestionJson.value.orEmpty(),
        if (remote == null) "0" else "1",
        remote.orEmpty(),
        configurationJson.value.orEmpty(),
        remoteFolderPickerVisible.value.toString(),
        selectionPickerVisible.value.toString(),
    )
    if (saved.sumOf(String::length) <= MAX_SAVED_SETUP_CHARACTERS) return saved
    return listOf(
        SAVED_SETUP_VERSION,
        if (root == null) "0" else "1",
        root?.localRootId.orEmpty(),
        root?.displayName.orEmpty(),
        "",
        "0",
        "",
        "",
        "false",
        "false",
    )
}

internal val FileSyncSetupDraftSaver = Saver<FileSyncSetupDraftState, List<String>>(
    save = { draft -> draft.savedState() },
    restore = { saved -> FileSyncSetupDraftState.restore(saved) },
)

@Composable
internal fun AbandonFileSyncRootOnDispose(
    services: NextcloudPlatformServices,
    localRoot: State<FileSyncLocalRoot?>,
) {
    DisposableEffect(services, localRoot) {
        onDispose(fileSyncRootDisposal(
            currentRoot = { localRoot.value },
            abandon = services::abandonFileSyncLocalRoot,
            retainRoot = services::retainFileSyncRootOnDispose,
        ))
    }
}

internal fun fileSyncRootDisposal(
    currentRoot: () -> FileSyncLocalRoot?,
    abandon: (FileSyncLocalRoot) -> Boolean,
    retainRoot: () -> Boolean = { false },
): () -> Unit = { if (!retainRoot()) currentRoot()?.let(abandon) }

private fun tryAbandonFileSyncRoot(
    root: FileSyncLocalRoot,
    abandon: (FileSyncLocalRoot) -> Boolean,
): Boolean = try {
    abandon(root)
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    false
}

private const val SAVED_SETUP_FIELD_COUNT = 10
private const val MAX_SAVED_SETUP_CHARACTERS = 32 * 1024
private const val SAVED_SETUP_VERSION = "file-sync-setup-v1"
