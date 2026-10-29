package dev.obiente.nextcloudnative.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.ui.graphics.vector.ImageVector
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import kotlin.time.Instant

private const val UNREAD_DESTINATION = "news:unread"
private const val STARRED_DESTINATION = "news:starred"
private const val ALL_DESTINATION = "news:all"
private const val FOLDER_DESTINATION_PREFIX = "news:folder:"
private const val FEED_DESTINATION_PREFIX = "news:feed:"

/**
 * Smart views first, then folders, then feeds. Feeds inside a folder name that folder so the
 * flat sheet and sidebar still show where each feed lives.
 */
internal fun newsDestinations(subscriptions: NewsSubscriptions?): List<NextcloudCollectionDestination> = buildList {
    add(NextcloudCollectionDestination(UNREAD_DESTINATION, "Unread", count = subscriptions?.totalUnread))
    add(NextcloudCollectionDestination(STARRED_DESTINATION, "Starred", count = subscriptions?.starredCount))
    add(NextcloudCollectionDestination(ALL_DESTINATION, "All articles"))
    subscriptions ?: return@buildList
    subscriptions.folders.forEach { folder ->
        add(
            NextcloudCollectionDestination(
                id = FOLDER_DESTINATION_PREFIX + folder.id,
                label = folder.name,
                count = subscriptions.unreadInFolder(folder.id).takeIf { it > 0 },
                accessibilityId = "news-folder",
                supportingText = feedCountLabel(subscriptions.feedsInFolder(folder.id).size),
            ),
        )
    }
    subscriptions.feeds.forEach { feed ->
        add(
            NextcloudCollectionDestination(
                id = FEED_DESTINATION_PREFIX + feed.id,
                label = feed.title,
                count = feed.unreadCount.takeIf { it > 0 },
                accessibilityId = "news-feed",
                supportingText = when {
                    feed.updateErrorCount > 0 -> "Updates failing"
                    feed.folderId != null -> subscriptions.folder(feed.folderId)?.name
                    else -> null
                },
            ),
        )
    }
}

internal fun NewsSelection.destinationId(): String = when (this) {
    NewsSelection.Unread -> UNREAD_DESTINATION
    NewsSelection.Starred -> STARRED_DESTINATION
    NewsSelection.All -> ALL_DESTINATION
    is NewsSelection.Folder -> FOLDER_DESTINATION_PREFIX + folderId
    is NewsSelection.Feed -> FEED_DESTINATION_PREFIX + feedId
}

internal fun newsSelectionForDestination(id: String): NewsSelection? = when {
    id == UNREAD_DESTINATION -> NewsSelection.Unread
    id == STARRED_DESTINATION -> NewsSelection.Starred
    id == ALL_DESTINATION -> NewsSelection.All
    id.startsWith(FOLDER_DESTINATION_PREFIX) ->
        id.removePrefix(FOLDER_DESTINATION_PREFIX).toLongOrNull()?.takeIf { it > 0L }?.let(NewsSelection::Folder)
    id.startsWith(FEED_DESTINATION_PREFIX) ->
        id.removePrefix(FEED_DESTINATION_PREFIX).toLongOrNull()?.takeIf { it > 0L }?.let(NewsSelection::Feed)
    else -> null
}

internal fun newsDestinationIcon(destination: NextcloudCollectionDestination): ImageVector = when {
    destination.id == UNREAD_DESTINATION -> Icons.Outlined.Inbox
    destination.id == STARRED_DESTINATION -> Icons.Outlined.Star
    destination.id == ALL_DESTINATION -> Icons.Outlined.ViewList
    destination.id.startsWith(FOLDER_DESTINATION_PREFIX) -> Icons.Outlined.Folder
    else -> Icons.Outlined.RssFeed
}

internal fun NewsSelection.title(subscriptions: NewsSubscriptions?): String = when (this) {
    NewsSelection.Unread -> "Unread"
    NewsSelection.Starred -> "Starred"
    NewsSelection.All -> "All articles"
    is NewsSelection.Folder -> subscriptions?.folder(folderId)?.name ?: "Folder"
    is NewsSelection.Feed -> subscriptions?.feed(feedId)?.title ?: "Feed"
}

internal fun NewsSelection.subtitle(subscriptions: NewsSubscriptions?): String? {
    subscriptions ?: return null
    return when (this) {
        NewsSelection.Unread -> unreadCountLabel(subscriptions.totalUnread)
        NewsSelection.Starred -> null
        NewsSelection.All -> feedCountLabel(subscriptions.feeds.size)
        is NewsSelection.Folder -> unreadCountLabel(subscriptions.unreadInFolder(folderId))
        is NewsSelection.Feed -> subscriptions.feed(feedId)?.let { feed ->
            if (feed.updateErrorCount > 0) {
                "Last update failed" + (feed.lastUpdateError?.let { ": $it" } ?: "")
            } else {
                unreadCountLabel(feed.unreadCount)
            }
        }
    }
}

/** Compact age for article lists; older articles show their calendar date (UTC). */
internal fun newsPublishedLabel(publishedEpochSeconds: Long, nowEpochSeconds: Long): String {
    val age = nowEpochSeconds - publishedEpochSeconds
    return when {
        age < 0L -> newsCalendarDate(publishedEpochSeconds)
        age < 60L -> "Just now"
        age < 3_600L -> "${age / 60L} min ago"
        age < 86_400L -> "${age / 3_600L} h ago"
        age < 2L * 86_400L -> "Yesterday"
        age < 7L * 86_400L -> "${age / 86_400L} days ago"
        else -> newsCalendarDate(publishedEpochSeconds)
    }
}

internal fun newsEnclosureLabel(mime: String?): String = when {
    mime == null -> "Open attachment"
    mime.startsWith("audio/") -> "Play audio in browser"
    mime.startsWith("video/") -> "Play video in browser"
    else -> "Open attachment"
}

private fun newsCalendarDate(epochSeconds: Long): String =
    Instant.fromEpochSeconds(epochSeconds).toString().take(10)

private fun unreadCountLabel(count: Int): String = when (count) {
    0 -> "No unread articles"
    1 -> "1 unread article"
    else -> "$count unread articles"
}

private fun feedCountLabel(count: Int): String = if (count == 1) "1 feed" else "$count feeds"
