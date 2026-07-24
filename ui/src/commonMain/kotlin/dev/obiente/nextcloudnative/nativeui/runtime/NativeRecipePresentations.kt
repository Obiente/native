package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/**
 * Schema.org-style recipe semantics derived from field shape rather than an application id.
 *
 * Any discovered app can receive this presentation by exposing recipe-shaped records. Unknown
 * fields remain on [NativeRecord] and continue through the generic detail/form pipeline.
 */
internal data class NativeRecipePresentation(
    val title: String,
    val description: String?,
    val category: String?,
    val servings: String?,
    val preparationTime: String?,
    val cookingTime: String?,
    val totalTime: String?,
    val keywords: List<String>,
    val imagePath: String?,
    val placeholderImagePath: String?,
) {
    val collectionMetadata: String?
        get() = listOfNotNull(
            category,
            totalTime,
            servings?.let(::servingsLabel),
        ).distinct().joinToString(" · ").takeIf(String::isNotBlank)
}

internal fun nativeRecipePresentation(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeRecipePresentation? {
    val values = NativeRecipeValues(record)
    val resourceTokens = recipeSemanticTokens(resource.id, resource.name)
    val recordType = values.string("type", "@type")
    val structuredRecipe = values.hasAny(
        "recipeingredient", "recipeingredients", "ingredients", "ingredient",
    ) && values.hasAny(
        "recipeinstruction", "recipeinstructions", "instructions", "instruction", "directions", "steps",
    )
    val recipeSpecificFieldCount = listOf(
        "recipeyield",
        "preptime",
        "cooktime",
        "totaltime",
        "recipecategory",
        "recipecuisine",
    ).count(values::hasAny)
    val resourceRecipe = resourceTokens.any { token -> token in RECIPE_RESOURCE_WORDS }
    val typeRecipe = recordType
        ?.let { type -> recipeSemanticTokens(type).any { token -> token in RECIPE_RESOURCE_WORDS } }
        ?: false
    if (!structuredRecipe && !typeRecipe && !(resourceRecipe && recipeSpecificFieldCount >= 1)) {
        // List stubs intentionally omit full detail fields, so the resource noun plus the stable
        // stub image contract is sufficient evidence without consulting an app identifier.
        if (!(resourceRecipe && values.hasAny("imageurl", "imageplaceholderurl") && values.hasAny("name", "title"))) {
            return null
        }
    }

    val title = values.string("name", "title", "headline")
        ?: nativeRecordPresentation(resource, record).title
    val imagePath = values.string("imageurl", "contenturl", "image", "photo", "coverurl")
        ?.let(::safeNativeAssetPath)
    val placeholderPath = values.string(
        "imageplaceholderurl",
        "placeholderimageurl",
        "thumbnailurl",
        "thumburl",
        "previewurl",
        "thumbnail",
    )?.let(::safeNativeAssetPath)
    return NativeRecipePresentation(
        title = title,
        description = values.string("description", "summary"),
        category = values.string("recipecategory", "category", "categoryname"),
        servings = values.string("recipeyield", "yield", "servings", "serves"),
        preparationTime = values.duration("preptime", "preparationtime"),
        cookingTime = values.duration("cooktime", "cookingtime"),
        totalTime = values.duration("totaltime", "duration"),
        keywords = values.string("keywords", "tags")
            ?.split(',', ';')
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty(),
        imagePath = imagePath,
        placeholderImagePath = placeholderPath,
    )
}

internal fun nativeRecipeCollectionPresentations(
    resource: ResourceSpec,
    records: List<NativeRecord>,
): List<Pair<NativeRecord, NativeRecipePresentation>>? {
    if (records.isEmpty()) return null
    return records.map { record ->
        record to (nativeRecipePresentation(resource, record) ?: return null)
    }
}

internal fun filterNativeRecipeCollection(
    rows: List<Pair<NativeRecord, NativeRecipePresentation>>,
    query: String,
    category: String?,
): List<Pair<NativeRecord, NativeRecipePresentation>> {
    val normalizedQuery = query.trim().lowercase()
    return rows.filter { (_, recipe) ->
        (category == null || recipe.category.equals(category, ignoreCase = true)) &&
            (
                normalizedQuery.isBlank() ||
                    sequenceOf(
                        recipe.title,
                        recipe.description,
                        recipe.category,
                        recipe.keywords.joinToString(" "),
                    ).filterNotNull().any { value -> normalizedQuery in value.lowercase() }
                )
    }
}

internal fun nativeRecipeCategories(
    rows: List<Pair<NativeRecord, NativeRecipePresentation>>,
): List<String> = rows.mapNotNull { (_, recipe) -> recipe.category?.trim()?.takeIf(String::isNotBlank) }
    .distinctBy { it.lowercase() }
    .sortedBy { it.lowercase() }

internal fun NativeStructuredDetailSection.isRecipeIngredientSection(): Boolean =
    fieldId.recipePresentationKey() in RECIPE_INGREDIENT_KEYS

internal fun NativeStructuredDetailSection.isRecipeInstructionSection(): Boolean =
    fieldId.recipePresentationKey() in RECIPE_INSTRUCTION_KEYS

internal fun NativeStructuredDetailSection.recipeTextItems(): List<String>? {
    val list = value as? NativeStructuredValue.ListValue ?: return null
    return list.items.mapNotNull(NativeStructuredValue::recipeItemText)
        .takeIf { items -> items.isNotEmpty() }
}

internal data class NativeRecipeInstructionPresentation(
    val heading: String?,
    val body: String?,
)

internal fun nativeRecipeInstructionPresentation(value: String): NativeRecipeInstructionPresentation {
    val lines = value.trim().lines()
    val first = lines.firstOrNull().orEmpty().trim()
    val markerCount = first.takeWhile { character -> character == '#' }.length
    val hasHeading = markerCount in 1..6 &&
        first.getOrNull(markerCount)?.isWhitespace() == true &&
        first.drop(markerCount).isNotBlank()
    if (!hasHeading) {
        return NativeRecipeInstructionPresentation(heading = null, body = value.trim().takeIf(String::isNotBlank))
    }
    return NativeRecipeInstructionPresentation(
        heading = first.drop(markerCount).trim(),
        body = lines.drop(1).joinToString("\n").trim().takeIf(String::isNotBlank),
    )
}

private fun NativeStructuredValue.recipeItemText(): String? = when (this) {
    is NativeStructuredValue.Scalar -> value?.trim()?.takeIf(String::isNotBlank)
    is NativeStructuredValue.ObjectValue -> entries.firstNotNullOfOrNull { entry ->
        entry.takeIf { candidate ->
            candidate.key.recipePresentationKey() in setOf("text", "name", "description", "value")
        }?.value?.recipeItemText()
    }
    is NativeStructuredValue.ListValue -> items.mapNotNull(NativeStructuredValue::recipeItemText)
        .joinToString(" ")
        .takeIf(String::isNotBlank)
}

private class NativeRecipeValues(record: NativeRecord) {
    private val values = buildMap {
        record.values.forEach { (key, value) ->
            value?.takeIf(String::isNotBlank)?.let { put(key.recipePresentationKey(), it) }
        }
        record.displayValues.forEach { (key, value) ->
            value.takeIf(String::isNotBlank)?.let { putIfAbsent(key.recipePresentationKey(), it) }
        }
    }
    private val keys = buildSet {
        addAll(values.keys)
        addAll(record.structuredValues.keys.map(String::recipePresentationKey))
    }

    fun hasAny(alias: String): Boolean = alias.recipePresentationKey() in keys

    fun hasAny(vararg aliases: String): Boolean = aliases.any(::hasAny)

    fun string(vararg aliases: String): String? = aliases.firstNotNullOfOrNull { alias ->
        values[alias.recipePresentationKey()]?.trim()?.takeIf(String::isNotBlank)
    }

    fun duration(vararg aliases: String): String? = string(*aliases)?.let { value ->
        value.formatIsoDuration() ?: value
    }
}

private fun String.recipePresentationKey(): String = lowercase().filter(Char::isLetterOrDigit)

private fun recipeSemanticTokens(vararg values: String): Set<String> = values
    .flatMap { value ->
        value.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
    }
    .filter(String::isNotBlank)
    .toSet()

private fun servingsLabel(value: String): String {
    val normalized = value.trim()
    return if (
        normalized.contains("serv", ignoreCase = true) ||
        normalized.contains("portion", ignoreCase = true)
    ) {
        normalized
    } else {
        "$normalized servings"
    }
}

private val RECIPE_RESOURCE_WORDS = setOf("recipe", "recipes")
private val RECIPE_INGREDIENT_KEYS = setOf(
    "recipeingredient", "recipeingredients", "ingredient", "ingredients",
)
private val RECIPE_INSTRUCTION_KEYS = setOf(
    "recipeinstruction", "recipeinstructions", "instruction", "instructions",
    "direction", "directions", "step", "steps",
)
