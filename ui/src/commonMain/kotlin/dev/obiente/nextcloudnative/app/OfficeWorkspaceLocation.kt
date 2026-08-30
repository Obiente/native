package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

/** Only navigation references survive recreation, never file metadata or editor tokens. */
internal data class OfficeWorkspaceLocation(
    val folderPath: String = "",
    val selectedFileId: Long? = null,
) {
    init {
        require(canonicalRemoteFolderPath(folderPath) == folderPath)
        require(selectedFileId == null || selectedFileId >= 0)
    }

    fun resolveSelection(state: OfficeWorkspaceState): NextcloudFile? {
        if (selectedFileId == null || state.path != folderPath || state.loading || !state.listingNetworkConfirmed) {
            return null
        }
        return officeWorkspaceFiles(state, "").singleOrNull { !it.isDirectory && it.fileId == selectedFileId }
    }
}

internal fun officeWorkspaceLocationSaver(accountScope: String): Saver<OfficeWorkspaceLocation, List<String>> {
    require(accountScope.length == 64 && accountScope.all { it in '0'..'9' || it in 'a'..'f' })
    return Saver(
        save = { listOf("office-location-v1", accountScope, it.folderPath, it.selectedFileId?.toString().orEmpty()) },
        restore = { saved ->
            if (saved.size != 4 || saved[0] != "office-location-v1" || saved[1] != accountScope ||
                canonicalRemoteFolderPath(saved[2]) != saved[2]) {
                null
            } else {
                val id = saved[3].toLongOrNull()?.takeIf { it >= 0 }
                if (saved[3].isNotEmpty() && id == null) null else OfficeWorkspaceLocation(saved[2], id)
            }
        },
    )
}

internal fun officeWorkspaceLocationStateSaver(accountScope: String): Saver<MutableState<OfficeWorkspaceLocation>, List<String>> {
    val locationSaver = officeWorkspaceLocationSaver(accountScope)
    return Saver(
        save = { state -> with(locationSaver) { save(state.value) } },
        // Reject the whole state holder so rememberSaveable uses its initial value. Wrapping a
        // nullable restore with the default mutable-state saver would retain a null value.
        restore = { saved -> locationSaver.restore(saved)?.let { mutableStateOf(it) } },
    )
}
