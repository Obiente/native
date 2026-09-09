package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun NativeMailWorkspace(
    plan: NativeMailWorkspacePlan,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    collectionStateKey: String,
    modifier: Modifier = Modifier,
    detailContent: (@Composable () -> Unit)? = null,
    contentState: NativeMailWorkspaceContentState = NativeMailWorkspaceContentState.Ready,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    loadMoreError: String? = null,
    searchQuery: String = "",
    onSearchQueryChanged: ((String) -> Unit)? = null,
) {
    key(collectionStateKey) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            Column(modifier = Modifier.fillMaxSize()) {
                if (availableWidth < 680.dp) NativeMailCompactNavigation(plan, onSelectRecord)
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
            availableWidth >= 980.dp -> Row(modifier = Modifier.fillMaxSize()) {
                NativeMailRail(
                    plan = plan,
                    onSelectRecord = onSelectRecord,
                    modifier = Modifier.width(252.dp).fillMaxHeight(),
                )
                MailPaneDivider()
                NativeMailSearchableMessageList(
                    items = plan.visibleMessages,
                    selectedMessage = plan.selectedMessage,
                    onSelectRecord = onSelectRecord,
                    contentState = contentState,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    searchQuery = searchQuery,
                    onSearchQueryChanged = onSearchQueryChanged,
                    emptyContent = {
                        NativeMailSelectionPlaceholder(plan)
                    },
                    modifier = Modifier.weight(0.44f).fillMaxHeight(),
                )
                MailPaneDivider()
                Box(modifier = Modifier.weight(0.56f).fillMaxHeight()) {
                    if (detailContent != null) {
                        detailContent()
                    } else if (
                        plan.selectedMessage != null &&
                        contentState != NativeMailWorkspaceContentState.Ready
                    ) {
                        NativeMailWorkspaceStatus(contentState)
                    } else {
                        NativeMailDetailPlaceholder()
                    }
                }
            }

            availableWidth >= 680.dp -> Row(modifier = Modifier.fillMaxSize()) {
                NativeMailRail(
                    plan = plan,
                    onSelectRecord = onSelectRecord,
                    modifier = Modifier.width(232.dp).fillMaxHeight(),
                )
                MailPaneDivider()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (detailContent != null) {
                        detailContent()
                    } else if (
                        plan.selectedMessage != null &&
                        contentState != NativeMailWorkspaceContentState.Ready
                    ) {
                        NativeMailWorkspaceStatus(contentState)
                    } else {
                        NativeMailSearchableMessageList(
                            items = plan.visibleMessages,
                            selectedMessage = plan.selectedMessage,
                            onSelectRecord = onSelectRecord,
                            contentState = contentState,
                            onLoadMore = onLoadMore,
                            loadingMore = loadingMore,
                            loadMoreError = loadMoreError,
                            searchQuery = searchQuery,
                            onSearchQueryChanged = onSearchQueryChanged,
                            emptyContent = {
                                NativeMailSelectionPlaceholder(plan)
                            },
                        )
                    }
                }
            }

            detailContent != null -> detailContent()

            contentState != NativeMailWorkspaceContentState.Ready ->
                NativeMailWorkspaceStatus(contentState)

            else -> {
                val compactItems = when {
                    plan.currentItems.isNotEmpty() -> plan.currentItems
                    plan.visibleMessages.isNotEmpty() -> plan.visibleMessages
                    plan.folders.isNotEmpty() -> plan.folders
                    else -> plan.accounts
                }
                if (
                    nativeMailCompactSearchAvailable(
                        items = compactItems,
                        searchHandlerAvailable = onSearchQueryChanged != null,
                        query = searchQuery,
                    )
                ) {
                    NativeMailSearchableMessageList(
                        items = compactItems,
                        selectedMessage = plan.selectedMessage,
                        onSelectRecord = onSelectRecord,
                        contentState = contentState,
                        onLoadMore = onLoadMore,
                        loadingMore = loadingMore,
                        loadMoreError = loadMoreError,
                        searchQuery = searchQuery,
                        onSearchQueryChanged = onSearchQueryChanged,
                    )
                } else {
                    NativeMailMessageList(
                        items = compactItems,
                        selectedMessage = plan.selectedMessage,
                        onSelectRecord = onSelectRecord,
                        contentState = contentState,
                        onLoadMore = onLoadMore,
                        loadingMore = loadingMore,
                        loadMoreError = loadMoreError,
                    )
                }
            }
            }
                }
            }
        }
    }
}
