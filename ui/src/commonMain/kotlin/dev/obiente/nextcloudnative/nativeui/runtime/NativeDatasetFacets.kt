package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

internal data class NativeDatasetFacet(
    val field: FieldSpec,
    val options: List<NativeDatasetFacetOption>,
)

internal data class NativeDatasetFacetOption(
    val value: String,
    val label: String,
    val count: Int,
)

internal enum class NativeDatasetSortMode(val label: String) {
    Server("Default order"),
    NameAscending("Name A-Z"),
    NameDescending("Name Z-A"),
}

/**
 * Returns an opaque, deterministic identity for ephemeral collection-browsing state.
 *
 * Search, filter, and sort choices belong to one concrete rendered dataset, not merely a resource
 * name. Dynamic apps can reuse resource IDs across views or nested parent scopes, so keeping that
 * state by [ResourceSpec.id] alone can apply one collection's choices to another collection. The
 * key incorporates the declared schema, view, displayed resource, and verified parent scope while
 * hashing the parts so no raw parent record identity appears in the composable state key.
 */
internal fun nativeDatasetBrowseStateKey(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    datasetContext: NativeDatasetContext,
): String = "dataset-browse:${nativeDatasetStateScopeDigest(
    listOf(
        schema.schemaVersion,
        schema.app.id,
        schema.app.version,
        view.id,
        view.resourceId,
        view.sourceActionId,
        resource.id,
        datasetContext.parentResourceId.orEmpty(),
        datasetContext.parentRecord?.id.orEmpty(),
    ),
)}"

private fun nativeDatasetStateScopeDigest(parts: List<String>): String {
    var hash = 0xcbf29ce484222325UL

    fun mix(text: String) {
        text.forEach { character ->
            hash = hash xor character.code.toULong()
            hash *= 0x100000001b3UL
        }
    }

    parts.forEach { part ->
        mix(part.length.toString())
        mix(":")
        mix(part)
        mix(";")
    }
    return hash.toString(16)
}

/**
 * Infers bounded collection facets from the schema and rows already loaded for display.
 *
 * Enumeration/boolean fields are explicit evidence. String fields are admitted only when their
 * field name has known grouping semantics. High-cardinality and singleton fields are excluded so
 * arbitrary names, identifiers, and free text never become noisy navigation.
 */
internal fun inferNativeDatasetFacets(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    maximumFacets: Int = 3,
    maximumOptions: Int = 8,
): List<NativeDatasetFacet> {
    if (maximumFacets <= 0 || maximumOptions < 2 || records.size < 2) return emptyList()
    return resource.fields.asSequence()
        .filter(FieldSpec::canDefineNativeDatasetFacet)
        .mapNotNull { field ->
            val values = records.mapNotNull { record ->
                record.presentationValue(field.id)?.trim()?.takeIf(String::isNotBlank)
            }
            val counts = linkedMapOf<String, Int>()
            values.forEach { value -> counts[value] = counts.getOrElse(value) { 0 } + 1 }
            if (counts.size !in 2..maximumOptions) return@mapNotNull null
            if (field.kind !in setOf(FieldKind.enumeration, FieldKind.boolean) && counts.size == records.size) {
                return@mapNotNull null
            }
            NativeDatasetFacet(
                field = field,
                options = counts.map { (value, count) ->
                    NativeDatasetFacetOption(
                        value = value,
                        label = formatNativeField(field, value).displayValue,
                        count = count,
                    )
                },
            )
        }
        .sortedByDescending { facet -> facet.field.nativeDatasetFacetPriority() }
        .take(maximumFacets)
        .toList()
}

internal fun filterNativeDatasetRecords(
    records: List<NativeRecord>,
    selections: Map<String, Set<String>>,
): List<NativeRecord> {
    val active = selections.filterValues(Set<String>::isNotEmpty)
    if (active.isEmpty()) return records
    return records.filter { record ->
        active.all { (fieldId, acceptedValues) ->
            record.presentationValue(fieldId)?.trim() in acceptedValues
        }
    }
}

/**
 * Derives the currently visible rows from data that has already been loaded.
 *
 * This intentionally has no transport, cache, or mutation dependency: table browsing remains
 * responsive without changing the server query or writing user data.
 */
internal fun browseNativeDatasetRecords(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    selections: Map<String, Set<String>> = emptyMap(),
    searchQuery: String = "",
    sortMode: NativeDatasetSortMode = NativeDatasetSortMode.Server,
): List<NativeRecord> {
    val faceted = filterNativeDatasetRecords(records, selections)
    val searched = searchQuery.trim().takeIf(String::isNotBlank)?.let { query ->
        faceted.filter { record ->
            nativeRecordMatchesCollectionQuery(resource, record, query)
        }
    } ?: faceted
    val alphabetical = compareBy<NativeRecord>(
        { nativeRecordPresentation(resource, it).title.lowercase() },
        NativeRecord::id,
    )
    return when (sortMode) {
        NativeDatasetSortMode.Server -> searched
        NativeDatasetSortMode.NameAscending -> searched.sortedWith(alphabetical)
        NativeDatasetSortMode.NameDescending -> searched.sortedWith(alphabetical.reversed())
    }
}

private fun FieldSpec.canDefineNativeDatasetFacet(): Boolean {
    if (kind in setOf(FieldKind.enumeration, FieldKind.boolean)) return true
    if (kind != FieldKind.string) return false
    val semantic = (id + " " + label).nativeFacetWords()
    return semantic.any { word -> word in NATIVE_FACET_WORDS }
}

private fun FieldSpec.nativeDatasetFacetPriority(): Int {
    val words = (id + " " + label).nativeFacetWords()
    return when {
        words.any { it in setOf("status", "state", "stage") } -> 300
        kind == FieldKind.enumeration -> 250
        words.any { it in setOf("category", "group", "priority", "type") } -> 200
        kind == FieldKind.boolean -> 150
        else -> 100
    }
}

private fun String.nativeFacetWords(): Set<String> =
    lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()

private val NATIVE_FACET_WORDS = setOf(
    "category",
    "group",
    "priority",
    "stage",
    "state",
    "status",
    "tag",
    "type",
)
