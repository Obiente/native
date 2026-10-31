package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.json.JsonObject

/** Whether a recipe write body creates a recipe in a collection or updates one recipe item. */
internal enum class SchemaOrgRecipeWriteKind { Create, Update }

/** A body whose properties carry the schema.org Recipe markers, whatever app declares it. */
internal fun JsonObject.declaresSchemaOrgRecipe(): Boolean {
    val properties = get("properties") as? JsonObject ?: return false
    return SCHEMA_ORG_RECIPE_MARKERS.all(properties::containsKey)
}

/** POST on a collection creates; PUT or PATCH on an item route updates; anything else is neither. */
internal fun schemaOrgRecipeWriteKind(path: String, method: HttpMethod): SchemaOrgRecipeWriteKind? {
    val last = path.substringBefore('?').trimEnd('/').substringAfterLast('/')
    val item = last.length >= 3 && last.startsWith('{') && last.endsWith('}')
    return when {
        method == HttpMethod.POST && !item -> SchemaOrgRecipeWriteKind.Create
        method in setOf(HttpMethod.PUT, HttpMethod.PATCH) && item -> SchemaOrgRecipeWriteKind.Update
        else -> null
    }
}

/** https://schema.org/Recipe properties that identify a recipe document. */
private val SCHEMA_ORG_RECIPE_MARKERS = setOf("name", "recipeIngredient", "recipeInstructions")

/** The text vocabulary a person edits; server-maintained dates, images, and nutrition stay out. */
internal val SCHEMA_ORG_RECIPE_WRITE_PROPERTIES = setOf(
    "id",
    "name",
    "description",
    "keywords",
    "prepTime",
    "cookTime",
    "totalTime",
    "recipeYield",
    "recipeCategory",
    "url",
    "tool",
    "recipeIngredient",
    "recipeInstructions",
)
