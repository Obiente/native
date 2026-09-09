package dev.obiente.nextcloudnative.nativeui.runtime

internal data class NativeCategoryRow(
    val record: NativeRecord,
    val presentation: NativeCategoryPresentation,
    val depth: Int,
    val hasChildren: Boolean,
)

private data class NativeCategoryVisit(
    val entry: Pair<NativeRecord, NativeCategoryPresentation>,
    val depth: Int,
    val visible: Boolean,
)

private fun flattenNativeCategoryRows(
    rows: List<Pair<NativeRecord, NativeCategoryPresentation>>,
    expandedIds: Set<String>,
): List<NativeCategoryRow> {
    val ids = rows.map { (record, _) -> record.id }.toSet()
    val children = rows.groupBy { (_, category) -> category.parentId?.takeIf(ids::contains) }
    val output = mutableListOf<NativeCategoryRow>()
    val visited = mutableSetOf<String>()
    val pending = ArrayDeque<NativeCategoryVisit>()
    fun enqueue(parentId: String?, depth: Int, visible: Boolean) {
        children[parentId].orEmpty()
            .sortedBy { (_, category) -> category.name.lowercase() }
            .asReversed()
            .forEach { entry -> pending.addLast(NativeCategoryVisit(entry, depth, visible)) }
    }
    enqueue(null, 0, true)
    while (pending.isNotEmpty()) {
        val visit = pending.removeLast()
        val (record, category) = visit.entry
        if (!visited.add(record.id)) continue
        val hasChildren = children[record.id].orEmpty().isNotEmpty()
        if (visit.visible) output += NativeCategoryRow(record, category, visit.depth, hasChildren)
        if (hasChildren) enqueue(record.id, visit.depth + 1, visit.visible && record.id in expandedIds)
    }
    rows.filterNot { (record, _) -> record.id in visited }.forEach { (record, category) ->
        output += NativeCategoryRow(record, category, 0, hasChildren = false)
    }
    return output
}

/**
 * Keeps a verified flat collection in its authoritative order while a reorder draft is active.
 *
 * Category hierarchy presentation normally sorts siblings by name. Applying that projection to a
 * reorderable flat collection hides every drag-state update by immediately sorting the rows back
 * into their previous visual positions. Hierarchical collections remain projected and sorted;
 * their partial tree traversal is not eligible for the complete-order mutation in the first place.
 */
internal fun nativeCategoryRowsForDisplay(
    rows: List<Pair<NativeRecord, NativeCategoryPresentation>>,
    expandedIds: Set<String>,
    preserveAuthoritativeOrder: Boolean,
): List<NativeCategoryRow> {
    val knownIds = rows.mapTo(hashSetOf()) { (record, _) -> record.id }
    val hasHierarchy = rows.any { (_, category) -> category.parentId in knownIds }
    return if (preserveAuthoritativeOrder && !hasHierarchy) {
        rows.map { (record, category) ->
            NativeCategoryRow(record, category, depth = 0, hasChildren = false)
        }
    } else {
        flattenNativeCategoryRows(rows, expandedIds)
    }
}
