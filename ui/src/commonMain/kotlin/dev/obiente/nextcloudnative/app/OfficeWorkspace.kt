package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun isOfficeWorkspaceAppId(appId: String): Boolean =
    appId in setOf("office", "richdocuments", "onlyoffice", "collabora", "nextcloud_office")

/** Read-only boundaries for the native Office browser. Opening it never creates an edit token. */
internal class OfficeWorkspaceOperations(
    val cachedFiles: suspend (String) -> NextcloudFileListing?,
    val files: suspend (String) -> NextcloudFileListing,
    val capabilities: suspend () -> NextcloudDocumentEditingCapabilities,
)

internal data class OfficeWorkspaceState(
    val path: String = "",
    val files: List<NextcloudFile> = emptyList(),
    val capabilities: NextcloudDocumentEditingCapabilities = NextcloudDocumentEditingCapabilities.Unavailable,
    val loading: Boolean = true,
    val listingNetworkConfirmed: Boolean = false,
    val error: String? = null,
)

internal class OfficeWorkspace(private val operations: OfficeWorkspaceOperations) {
    private val mutableState = MutableStateFlow(OfficeWorkspaceState())
    val state = mutableState.asStateFlow()
    private var generation = 0L

    suspend fun load(path: String) {
        require(canonicalRemoteFolderPath(path) != null)
        val request = ++generation
        val previous = mutableState.value
        mutableState.value = OfficeWorkspaceState(
            path = path,
            files = previous.files.takeIf { previous.path == path }.orEmpty(),
            capabilities = previous.capabilities,
        )
        val cached = runCatchingPreservingCancellation { operations.cachedFiles(path) }.getOrNull()
        if (request != generation) return
        if (cached != null) mutableState.value = mutableState.value.copy(files = cached.files)

        val capabilities = runCatchingPreservingCancellation { operations.capabilities() }
        if (request != generation) return
        val listing = runCatchingPreservingCancellation { operations.files(path) }
        if (request != generation) return
        mutableState.value = mutableState.value.copy(
            files = listing.getOrNull()?.files ?: mutableState.value.files,
            capabilities = capabilities.getOrNull() ?: mutableState.value.capabilities,
            loading = false,
            listingNetworkConfirmed = listing.getOrNull()?.source == NextcloudFileListingSource.Network,
            error = when {
                listing.isFailure -> "Could not refresh this folder. Connect to Nextcloud and retry."
                capabilities.isFailure -> "Could not discover the server's document editors. Retry to refresh."
                listing.getOrNull()?.source != NextcloudFileListingSource.Network ->
                    "These files are cached. Connect to Nextcloud before opening an editor."
                else -> null
            },
        )
    }
}

internal fun officeWorkspaceFiles(state: OfficeWorkspaceState, query: String): List<NextcloudFile> =
    state.files.asSequence()
        .filter { file ->
            canonicalRemoteFolderPath(file.path) == file.path && file.path.isNotEmpty() &&
                remoteFolderParentPath(file.path) == state.path
        }
        .filter { file ->
            file.isDirectory || describeDocument(file).method != DocumentPreviewMethod.Unsupported ||
                state.capabilities.editors.values.any { editor ->
                    editor.secure && editor.id.isSafeDocumentCapabilityId() &&
                        describeDocument(file).mimeType.let { mime ->
                            mime in editor.mimeTypes || mime in editor.optionalMimeTypes
                        }
                }
        }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .distinctBy(NextcloudFile::path)
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        .toList()

internal fun officeWorkspaceOperations(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
) = OfficeWorkspaceOperations(
    cachedFiles = { services.listFilesCachedWithSource(session, userId, it) },
    files = { services.listFilesWithSource(session, userId, it) },
    capabilities = {
        val cached = sharedDocumentEditingCapabilitiesCache.get(session)
        when (val result = services.loadDocumentEditingCapabilities(session, cached?.etag, cached?.capabilities)) {
            is NextcloudConditionalRead.Modified -> result.value.also {
                sharedDocumentEditingCapabilitiesCache.store(session, it, result.responseEtag)
            }
            NextcloudConditionalRead.NotModified -> cached?.capabilities
                ?: error("Document editor metadata was not returned.")
        }
    },
)
