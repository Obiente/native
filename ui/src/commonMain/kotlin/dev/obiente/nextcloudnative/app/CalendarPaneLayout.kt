package dev.obiente.nextcloudnative.app

internal data class CalendarPaneLayout(
    val sourcesVisible: Boolean,
    val detailsVisible: Boolean,
    val sourcesInline: Boolean,
    val detailsInline: Boolean,
)

internal fun calendarPaneLayout(
    widthDp: Int,
    sourcesRequested: Boolean?,
    detailsRequested: Boolean?,
    eventSelected: Boolean,
): CalendarPaneLayout {
    val sources = sourcesRequested ?: (widthDp >= 1240)
    val details = detailsRequested ?: (widthDp >= 1050 && eventSelected)
    return CalendarPaneLayout(
        sourcesVisible = sources && !(details && widthDp < 1050),
        detailsVisible = details,
        sourcesInline = sources && widthDp >= 1240,
        detailsInline = details && widthDp >= 1050,
    )
}
