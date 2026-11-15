package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Owned above notices, lazy items, and responsive layouts that can replace the workspace. */
@Stable
internal class FileSyncWorkspaceSelection(
    pairId: String? = null,
    tabName: String = FileSyncInspectorTab.Overview.name,
) {
    var pairId by mutableStateOf(pairId)
        private set
    var tabName by mutableStateOf(tabName)
        private set

    fun select(pairId: String?) {
        if (this.pairId != pairId) tabName = FileSyncInspectorTab.Overview.name
        this.pairId = pairId
    }

    fun selectTab(name: String) {
        tabName = FileSyncInspectorTab.entries.firstOrNull { it.name == name }?.name
            ?: FileSyncInspectorTab.Overview.name
    }
}

private val fileSyncWorkspaceSelectionSaver = listSaver<FileSyncWorkspaceSelection, String>(
    save = { listOf(it.pairId.orEmpty(), it.tabName) },
    restore = { values ->
        FileSyncWorkspaceSelection(values[0].ifEmpty { null }).apply { selectTab(values[1]) }
    },
)

@Composable
internal fun rememberFileSyncWorkspaceSelection(
    accountKey: String? = null,
    initialSelectedPairId: String? = null,
): FileSyncWorkspaceSelection = rememberSaveable(accountKey, initialSelectedPairId, saver = fileSyncWorkspaceSelectionSaver) {
    FileSyncWorkspaceSelection(initialSelectedPairId)
}
