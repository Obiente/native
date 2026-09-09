package dev.obiente.nextcloudnative.app

internal fun activityHistoryCountLabel(activities: List<NextcloudActivity>): String {
    val events = "${activities.size} ${if (activities.size == 1) "event" else "events"}"
    val entryCount = bundleDesktopActivities(activities).size
    return if (entryCount == activities.size) events
    else "$entryCount ${if (entryCount == 1) "entry" else "entries"} / $events"
}

/** A highlighted event remains reachable once, without repeating it in the timeline below. */
internal fun activityHistoryGroups(
    groups: List<ActivityFeedDayGroup>,
    highlighted: List<NextcloudActivity>,
): List<ActivityFeedDayGroup> {
    val highlightedIds = highlighted.mapTo(mutableSetOf(), NextcloudActivity::id)
    return groups.mapNotNull { group ->
        val remaining = group.activities.filterNot { it.id in highlightedIds }
        if (remaining.isEmpty()) null else group.copy(activities = remaining)
    }
}
