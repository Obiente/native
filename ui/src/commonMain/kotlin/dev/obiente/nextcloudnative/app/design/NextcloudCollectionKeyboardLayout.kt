package dev.obiente.nextcloudnative.app.design

/** Keyboard order and lazy indices must describe the rendered rows, including section headings. */
internal data class NextcloudCollectionKeyboardLayout(
    val navigationModel: NextcloudCollectionNavigationModel,
    val lazyItemIndexByDestinationId: Map<String, Int>,
)

internal fun nextcloudCollectionKeyboardLayout(
    model: NextcloudCollectionNavigationModel,
    groupedSections: Boolean,
): NextcloudCollectionKeyboardLayout {
    val ordered = if (groupedSections) {
        NextcloudCollectionDestinationSection.entries.flatMap { section ->
            model.destinations.filter { it.section == section }
        }
    } else model.destinations
    val itemIndices = buildMap {
        var itemIndex = 0
        var manageHeadingAdded = false
        ordered.forEach { destination ->
            if (groupedSections && destination.section == NextcloudCollectionDestinationSection.Manage && !manageHeadingAdded) {
                itemIndex++
                manageHeadingAdded = true
            }
            put(destination.id, itemIndex++)
        }
    }
    return NextcloudCollectionKeyboardLayout(
        navigationModel = NextcloudCollectionNavigationModel.create(ordered, model.selectedDestinationId),
        lazyItemIndexByDestinationId = itemIndices,
    )
}
