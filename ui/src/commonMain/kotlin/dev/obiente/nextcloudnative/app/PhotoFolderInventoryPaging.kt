package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException

const val MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS = 50_000
private const val MAX_PHOTO_FOLDER_INVENTORY_CURSOR_LENGTH = 2_048

data class PhotoFolderInventoryPagingOwner(
    val accountKey: String,
    val generation: Long,
) {
    init {
        require(accountKey.isNotBlank() && accountKey.none(Char::isISOControl)) {
            "The photo folder inventory account key is invalid."
        }
        require(generation >= 0) { "The photo folder inventory generation is invalid." }
    }
}

@JvmInline
value class PhotoFolderInventoryCursor(
    val value: String,
) {
    init {
        require(value.isNotEmpty() && value.length <= MAX_PHOTO_FOLDER_INVENTORY_CURSOR_LENGTH) {
            "The photo folder inventory cursor is invalid."
        }
        require(value.none(Char::isISOControl)) {
            "The photo folder inventory cursor contains control characters."
        }
    }
}

data class PhotoFolderInventoryPage(
    val records: List<NextcloudFile>,
    val nextCursor: PhotoFolderInventoryCursor?,
    val rawObserved: Boolean = false,
    val rawStackFileIdsByRecordPath: Map<String, List<Long>> = emptyMap(),
    val rawStackRelationshipsAuthoritative: Boolean = false,
) {
    init {
        validatePhotoFolderRawStackRelationships(
            records = records,
            relationships = rawStackFileIdsByRecordPath,
        )
    }
}

enum class PhotoFolderInventorySafetyStopReason {
    RepeatedCursor,
    NoNovelRecords,
}

data class PhotoFolderInventoryPagingState(
    val owner: PhotoFolderInventoryPagingOwner,
    val publication: PhotoFolderInventoryPublication?,
    /**
     * Cursor for the next request. It is written before a request starts, so a failed or cancelled
     * request can be retried without replaying already accepted pages.
     */
    val resumeCursor: PhotoFolderInventoryCursor?,
    val rawPreviouslyObserved: Boolean,
    val loading: Boolean,
    val complete: Boolean,
    val truncationReason: PhotoFolderInventoryTruncationReason?,
    val safetyStopReason: PhotoFolderInventorySafetyStopReason?,
    val error: String?,
    val publishedPageCount: Int,
    val contentGeneration: Long,
) {
    init {
        require(publishedPageCount >= 0) { "The published photo folder page count is invalid." }
        require(contentGeneration >= 0L) {
            "The photo folder inventory content generation is invalid."
        }
        require(!(complete && truncationReason != null)) {
            "A truncated photo folder inventory cannot be complete."
        }
        require(!(complete && safetyStopReason != null)) {
            "A safety-stopped photo folder inventory cannot be complete."
        }
    }
}

fun PhotoFolderInventoryPagingState.incompleteInventoryMessage(): String? = when {
    truncationReason == PhotoFolderInventoryTruncationReason.MediaRecordLimit ->
        "Folder indexing reached its media safety limit. Showing the folders and photos indexed so far."
    truncationReason == PhotoFolderInventoryTruncationReason.FolderLimit ->
        "Folder indexing reached its folder safety limit. Showing the folders and photos indexed so far."
    safetyStopReason == PhotoFolderInventorySafetyStopReason.RepeatedCursor ->
        "The server repeated a photo page while indexing folders. Showing the results loaded so far."
    safetyStopReason == PhotoFolderInventorySafetyStopReason.NoNovelRecords ->
        "The server stopped returning new photos while indexing folders. Showing the results loaded so far."
    else -> null
}

/**
 * Pure cursor and accumulation state for one account generation.
 *
 * Transport is injected through [loadPage]. The caller can adapt a `PhotoTimelinePage` by mapping
 * its entries to [PhotoFolderInventoryPage.records] and wrapping its opaque cursor value.
 */
class PhotoFolderInventoryPager(
    val owner: PhotoFolderInventoryPagingOwner,
    private val maximumMediaRecords: Int = MAX_PHOTO_FOLDER_INVENTORY_PAGING_RECORDS,
    private val maximumFolders: Int = MAX_PHOTO_FOLDER_SUMMARY_FOLDERS,
    private val maximumSelectedMediaRecords: Int = MAX_PHOTO_FOLDER_SELECTED_MEDIA_RECORDS,
) {
    private var repository = newRepository()

    var state = PhotoFolderInventoryPagingState(
        owner = owner,
        publication = null,
        resumeCursor = null,
        rawPreviouslyObserved = false,
        loading = false,
        complete = false,
        truncationReason = null,
        safetyStopReason = null,
        error = null,
        publishedPageCount = 0,
        contentGeneration = 0L,
    )
        private set

    suspend fun load(
        onPublish: (PhotoFolderInventoryPagingState) -> Unit = {},
        loadPage: suspend (
            cursor: PhotoFolderInventoryCursor?,
            rawPreviouslyObserved: Boolean,
        ) -> PhotoFolderInventoryPage,
    ): PhotoFolderInventoryPagingState {
        if (state.complete || state.truncationReason != null || state.safetyStopReason != null) {
            return state
        }
        check(!state.loading) { "The photo folder inventory is already loading." }

        state = state.copy(loading = true, error = null)
        while (true) {
            val requestedCursor = state.resumeCursor
            val page = try {
                loadPage(requestedCursor, state.rawPreviouslyObserved)
            } catch (cancellation: CancellationException) {
                state = state.copy(loading = false)
                throw cancellation
            } catch (failure: Exception) {
                state = state.copy(
                    loading = false,
                    error = failure.message?.takeIf(String::isNotBlank)
                        ?: "The photo folder inventory page could not be loaded.",
                )
                return state
            }

            when (
                val acceptance = repository.tryAddPage(
                    records = page.records,
                    rawStackFileIdsByRecordPath = page.rawStackFileIdsByRecordPath,
                    rawStackRelationshipsAuthoritative =
                        page.rawStackRelationshipsAuthoritative,
                )
            ) {
                is PhotoFolderPageAcceptance.Truncated -> {
                    state = state.copy(
                        loading = false,
                        truncationReason = acceptance.reason,
                        error = null,
                    )
                    return state
                }
                is PhotoFolderPageAcceptance.Accepted -> {
                    val publication = checkNotNull(repository.publication())
                    state = state.copy(
                        publication = publication,
                        rawPreviouslyObserved =
                            state.rawPreviouslyObserved || page.rawObserved,
                        resumeCursor = page.nextCursor,
                        publishedPageCount = state.publishedPageCount + 1,
                        contentGeneration = Math.addExact(state.contentGeneration, 1L),
                        error = null,
                    )
                    onPublish(state)

                    val nextCursor = page.nextCursor
                    when {
                        nextCursor == null -> {
                            state = state.copy(
                                resumeCursor = null,
                                loading = false,
                                complete = true,
                            )
                            return state
                        }
                        publication.summary.indexedMediaRecordCount >= maximumMediaRecords -> {
                            state = state.copy(
                                loading = false,
                                truncationReason =
                                    PhotoFolderInventoryTruncationReason.MediaRecordLimit,
                            )
                            return state
                        }
                        nextCursor == requestedCursor -> {
                            state = state.copy(
                                loading = false,
                                safetyStopReason =
                                    PhotoFolderInventorySafetyStopReason.RepeatedCursor,
                            )
                            return state
                        }
                        acceptance.novelMediaRecords == 0 -> {
                            state = state.copy(
                                loading = false,
                                safetyStopReason =
                                    PhotoFolderInventorySafetyStopReason.NoNovelRecords,
                            )
                            return state
                        }
                        else -> state = state.copy(resumeCursor = nextCursor)
                    }
                }
            }
        }
    }

    /**
     * Rebuilds a completed inventory without clearing its current publication.
     *
     * A refresh is accumulated in an isolated repository. The cached publication remains the
     * screen's paint until the replacement reaches a complete terminal page, then the new
     * generation is promoted atomically. Failed, cancelled, truncated, or safety-stopped refreshes
     * retain the last complete publication.
     */
    suspend fun revalidate(
        onPublish: (PhotoFolderInventoryPagingState) -> Unit = {},
        loadPage: suspend (
            cursor: PhotoFolderInventoryCursor?,
            rawPreviouslyObserved: Boolean,
        ) -> PhotoFolderInventoryPage,
    ): PhotoFolderInventoryPagingState {
        if (!state.complete) return load(onPublish, loadPage)
        check(!state.loading) { "The photo folder inventory is already loading." }

        val cachedState = state
        state = cachedState.copy(loading = true, error = null)
        onPublish(state)
        val replacement = PhotoFolderInventoryPager(
            owner = owner,
            maximumMediaRecords = maximumMediaRecords,
            maximumFolders = maximumFolders,
            maximumSelectedMediaRecords = maximumSelectedMediaRecords,
        )
        val refreshed = try {
            replacement.load(loadPage = loadPage)
        } catch (cancellation: CancellationException) {
            state = cachedState.copy(loading = false)
            throw cancellation
        }

        if (refreshed.complete) {
            repository = replacement.repository
            state = refreshed.copy(
                contentGeneration = Math.addExact(cachedState.contentGeneration, 1L),
            )
        } else {
            state = cachedState.copy(
                loading = false,
                error = refreshed.error
                    ?: refreshed.incompleteInventoryMessage()
                    ?: "The photo folder inventory refresh did not complete.",
            )
        }
        onPublish(state)
        return state
    }

    fun browse(browseState: PhotoFolderBrowseState): PhotoFolderBrowseResult =
        repository.browse(browseState)

    fun selectionSnapshot(browseState: PhotoFolderBrowseState): PhotoFolderPagedInventory =
        repository.selectionSnapshot(browseState)

    private fun newRepository(): PhotoFolderInventoryRepository = PhotoFolderInventoryRepository(
        maximumMediaRecords = maximumMediaRecords,
        maximumFolders = maximumFolders,
        maximumSelectionRecords = maximumSelectedMediaRecords,
    )
}
