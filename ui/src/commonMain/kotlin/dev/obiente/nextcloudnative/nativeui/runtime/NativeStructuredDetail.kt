package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

internal data class NativeDetailFieldPresentation(
    val fieldId: String,
    val formatted: NativeFormattedField,
)

internal data class NativeStructuredDetailPresentation(
    val fields: List<NativeDetailFieldPresentation>,
    val sections: List<NativeStructuredDetailSection>,
)

internal data class NativeStructuredDetailSection(
    val fieldId: String,
    val label: String,
    val value: NativeStructuredValue,
    val ordered: Boolean,
)


internal fun nativeDetailFields(
    resource: ResourceSpec,
    record: NativeRecord,
): List<NativeDetailFieldPresentation> = resource.fields
    .filter { field -> field.isSafeNativeDetailField(resource) }
    .mapNotNull { field ->
        record.presentationValue(field.id)
            ?.takeIf(String::isNotBlank)
            ?.let { NativeDetailFieldPresentation(field.id, formatNativeField(field, it)) }
    }

internal fun nativeStructuredDetail(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeStructuredDetailPresentation {
    val sections = resource.fields
        .filter { field -> field.isSafeNativeDetailField(resource) }
        .mapNotNull { field ->
            val value = record.structuredValues[field.id]?.takeIf { it.hasVisibleContent() }
                ?: return@mapNotNull null
            NativeStructuredDetailSection(
                fieldId = field.id,
                label = field.label,
                value = value,
                ordered = value is NativeStructuredValue.ListValue && field.hasStepSemantics(),
            )
        }
    val generic = NativeStructuredDetailPresentation(
        fields = nativeDetailFields(resource, record).filterNot { it.fieldId in record.structuredValues },
        sections = sections,
    )
    return if (record.hasRecipeDetailSemantics()) generic.asRecipeDetail(resource) else generic
}

private fun NativeRecord.hasRecipeDetailSemantics(): Boolean {
    val keys = (structuredValues.keys + values.keys + displayValues.keys)
        .map { key -> key.lowercase().filter(Char::isLetterOrDigit) }
        .toSet()
    return keys.any(RECIPE_INGREDIENT_SECTION_KEYS::contains) &&
        keys.any(RECIPE_INSTRUCTION_SECTION_KEYS::contains)
}

private fun NativeStructuredDetailPresentation.asRecipeDetail(
    resource: ResourceSpec,
): NativeStructuredDetailPresentation {
    val fieldsById = resource.fields.associateBy(FieldSpec::id)
    val cleanedFields = fields
        .filterNot { detail -> detail.fieldId.recipeSemanticKey() in RECIPE_TECHNICAL_FIELDS }
        .map { detail ->
            val field = fieldsById[detail.fieldId]
            val label = when (detail.fieldId.recipeSemanticKey()) {
                "recipeyield" -> "Servings"
                "preptime" -> "Preparation"
                "cooktime" -> "Cooking"
                "totaltime" -> "Total"
                "recipecategory", "category" -> "Category"
                else -> field?.label ?: detail.formatted.label
            }
            detail.copy(formatted = detail.formatted.copy(label = label))
        }
        .sortedWith(compareBy({ RECIPE_FIELD_ORDER[it.fieldId.recipeSemanticKey()] ?: 100 }, { it.formatted.label }))
    val cleanedSections = sections
        .filter { section -> section.fieldId.recipeSemanticKey() in RECIPE_SECTION_ORDER }
        .map { section ->
            section.copy(
                label = when (section.fieldId.recipeSemanticKey()) {
                    in RECIPE_INGREDIENT_SECTION_KEYS -> "Ingredients"
                    in RECIPE_INSTRUCTION_SECTION_KEYS -> "Instructions"
                    in RECIPE_TOOL_SECTION_KEYS -> "Tools"
                    "nutrition" -> "Nutrition"
                    else -> section.label
                },
            )
        }
        .sortedBy { section -> RECIPE_SECTION_ORDER.getValue(section.fieldId.recipeSemanticKey()) }
    return copy(fields = cleanedFields, sections = cleanedSections)
}

private fun String.recipeSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

private val RECIPE_TECHNICAL_FIELDS = setOf(
    "id", "name", "image", "imageurl", "imageplaceholderurl", "mainentityofpage",
    "datecreated", "datemodified", "url", "printimage", "context", "type",
)
private val RECIPE_FIELD_ORDER = mapOf(
    "description" to 0,
    "recipeyield" to 1,
    "preptime" to 2,
    "cooktime" to 3,
    "totaltime" to 4,
    "recipecategory" to 5,
    "category" to 5,
    "keywords" to 6,
    "datepublished" to 7,
)
private val RECIPE_INGREDIENT_SECTION_KEYS = setOf(
    "recipeingredient", "recipeingredients", "ingredient", "ingredients",
)
private val RECIPE_INSTRUCTION_SECTION_KEYS = setOf(
    "recipeinstruction", "recipeinstructions", "instruction", "instructions",
    "direction", "directions", "step", "steps",
)
private val RECIPE_TOOL_SECTION_KEYS = setOf("tool", "tools", "equipment")
private val RECIPE_SECTION_ORDER = buildMap {
    RECIPE_INGREDIENT_SECTION_KEYS.forEach { key -> put(key, 0) }
    RECIPE_INSTRUCTION_SECTION_KEYS.forEach { key -> put(key, 1) }
    RECIPE_TOOL_SECTION_KEYS.forEach { key -> put(key, 2) }
    put("nutrition", 3)
}

private fun NativeStructuredValue.hasVisibleContent(): Boolean = when (this) {
    is NativeStructuredValue.Scalar -> value != null
    is NativeStructuredValue.ListValue -> items.isNotEmpty()
    is NativeStructuredValue.ObjectValue -> entries.isNotEmpty()
}

private fun FieldSpec.hasStepSemantics(): Boolean {
    val semantic = (id + label).lowercase().filter(Char::isLetterOrDigit)
    return listOf("instruction", "step", "direction", "procedure", "method").any(semantic::contains)
}

private fun NativeStructuredValue.Scalar.structuredDisplayValue(): String = when (kind) {
    NativeStructuredScalarKind.boolean -> when (value?.lowercase()) {
        "true" -> "Yes"
        "false" -> "No"
        else -> value.orEmpty()
    }
    NativeStructuredScalarKind.nullValue -> "-"
    else -> value.orEmpty()
}


@Composable
internal fun GenericStructuredDetailSection(section: NativeStructuredDetailSection) {
    Text(
        section.label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        GenericStructuredValue(section.value, ordered = section.ordered)
    }
}

@Composable
private fun GenericStructuredValue(
    value: NativeStructuredValue,
    ordered: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (value) {
        is NativeStructuredValue.Scalar -> Text(
            value.structuredDisplayValue(),
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
        )
        is NativeStructuredValue.ListValue -> Column(modifier = modifier.fillMaxWidth()) {
            value.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Large,
                        vertical = NextcloudSpacing.Medium,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        if (ordered) "${index + 1}." else "•",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    GenericStructuredValue(item, modifier = Modifier.weight(1f))
                }
            }
            if (value.omittedItems > 0) GenericStructuredOmission(value.omittedItems)
        }
        is NativeStructuredValue.ObjectValue -> Column(modifier = modifier.fillMaxWidth()) {
            value.entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GenericStructuredValue(entry.value)
                }
            }
            if (value.omittedEntries > 0) GenericStructuredOmission(value.omittedEntries)
        }
    }
}

@Composable
private fun GenericStructuredOmission(count: Int) {
    Text(
        "+$count more not shown",
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}
