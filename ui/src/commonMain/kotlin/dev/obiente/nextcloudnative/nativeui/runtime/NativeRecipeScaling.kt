package dev.obiente.nextcloudnative.nativeui.runtime

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Parses a declared recipe yield without consulting application identity or mutating recipe data.
 *
 * Common forms such as `4`, `4 servings`, and `Serves 4` are accepted. Ranges and unbounded text
 * remain unadjustable because choosing one endpoint would silently change the recipe's meaning.
 */
internal fun parseRecipeServingCount(value: String?): Double? {
    val text = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    if ('-' in text || '–' in text || '—' in text) return null
    val withoutPrefix = SERVING_PREFIXES.firstNotNullOfOrNull { prefix ->
        text.takeIf { it.startsWith(prefix, ignoreCase = true) }
            ?.drop(prefix.length)
            ?.trimStart()
            ?.trimStart(':')
            ?.trimStart()
    } ?: text
    return parseLeadingRecipeQuantity(withoutPrefix)
        ?.value
        ?.takeIf { it.isFinite() && it > 0.0 }
}

/**
 * Scales only a confidently parsed leading quantity and preserves the original unit/instruction.
 * Free-text lines and ambiguous compact forms such as `2x400 g tomatoes` are returned verbatim.
 */
internal fun scaleRecipeIngredient(
    ingredient: String,
    multiplier: Double,
): String {
    if (!multiplier.isFinite() || multiplier <= 0.0 || abs(multiplier - 1.0) < QUANTITY_EPSILON) {
        return ingredient
    }
    val leadingWhitespace = ingredient.takeWhile(Char::isWhitespace)
    val content = ingredient.drop(leadingWhitespace.length)
    val parsed = parseLeadingRecipeQuantity(content) ?: return ingredient
    val scaled = parsed.value * multiplier
    if (!scaled.isFinite() || scaled <= 0.0) return ingredient
    val amount = formatRecipeQuantity(scaled)
    return buildString {
        append(leadingWhitespace)
        append(amount)
        if (parsed.suffix.isNotEmpty()) {
            append(' ')
            append(parsed.suffix.withScaledRecipeUnit(scaled))
        }
    }
}

private fun String.withScaledRecipeUnit(quantity: Double): String {
    if (abs(quantity - 1.0) < QUANTITY_EPSILON) return this
    val unit = takeWhile(Char::isLetter)
    if (unit.isEmpty() || unit.endsWith("s", ignoreCase = true) || unit.lowercase() !in PLURAL_RECIPE_UNITS) {
        return this
    }
    return unit + "s" + drop(unit.length)
}

internal fun formatRecipeQuantity(value: Double): String {
    require(value.isFinite() && value > 0.0) { "Recipe quantity must be positive and finite." }
    val nearestWhole = round(value)
    if (abs(value - nearestWhole) < QUANTITY_EPSILON) return nearestWhole.toLong().toString()

    val whole = floor(value).toLong()
    val fractional = value - whole
    val approximation = (2..MAX_RECIPE_FRACTION_DENOMINATOR)
        .map { denominator ->
            val numerator = round(fractional * denominator).toInt()
            RecipeFraction(numerator, denominator, abs(fractional - numerator.toDouble() / denominator))
        }
        .filter { fraction -> fraction.numerator in 1 until fraction.denominator }
        .minWithOrNull(compareBy<RecipeFraction>(RecipeFraction::error).thenBy(RecipeFraction::denominator))
        ?.takeIf { fraction -> fraction.error <= MAX_RECIPE_FRACTION_ERROR }
    if (approximation != null) {
        val divisor = greatestCommonDivisor(approximation.numerator, approximation.denominator)
        val numerator = approximation.numerator / divisor
        val denominator = approximation.denominator / divisor
        return if (whole > 0) "$whole $numerator/$denominator" else "$numerator/$denominator"
    }

    val rounded = round(value * 100.0) / 100.0
    return rounded.toString().trimEnd('0').trimEnd('.')
}

private data class ParsedRecipeQuantity(
    val value: Double,
    val suffix: String,
)

private data class RecipeFraction(
    val numerator: Int,
    val denominator: Int,
    val error: Double,
)

private fun parseLeadingRecipeQuantity(text: String): ParsedRecipeQuantity? {
    if (text.isEmpty()) return null
    var index = 0
    var whole = 0.0
    var hasWhole = false

    while (index < text.length && text[index].isDigit()) index += 1
    if (index > 0) {
        whole = text.substring(0, index).toDoubleOrNull() ?: return null
        hasWhole = true
    }

    if (hasWhole && index < text.length && text[index] in setOf('.', ',')) {
        val decimalSeparator = index
        var decimalEnd = index + 1
        while (decimalEnd < text.length && text[decimalEnd].isDigit()) decimalEnd += 1
        if (decimalEnd == decimalSeparator + 1) return null
        val normalized = text.substring(0, decimalEnd).replace(',', '.')
        val value = normalized.toDoubleOrNull() ?: return null
        return parsedQuantity(text, decimalEnd, value)
    }

    if (hasWhole && index < text.length && text[index] == '/') {
        val denominatorStart = index + 1
        var denominatorEnd = denominatorStart
        while (denominatorEnd < text.length && text[denominatorEnd].isDigit()) denominatorEnd += 1
        val denominator = text.substring(denominatorStart, denominatorEnd).toDoubleOrNull()
            ?.takeIf { it > 0.0 }
            ?: return null
        return parsedQuantity(text, denominatorEnd, whole / denominator)
    }

    if (hasWhole && index < text.length) {
        UNICODE_RECIPE_FRACTIONS[text[index]]?.let { fraction ->
            return parsedQuantity(text, index + 1, whole + fraction)
        }
    }
    if (!hasWhole) {
        UNICODE_RECIPE_FRACTIONS[text.first()]?.let { fraction ->
            return parsedQuantity(text, 1, fraction)
        } ?: return null
    }

    val afterWhole = index
    while (index < text.length && text[index].isWhitespace()) index += 1
    if (index > afterWhole && index < text.length) {
        UNICODE_RECIPE_FRACTIONS[text[index]]?.let { fraction ->
            return parsedQuantity(text, index + 1, whole + fraction)
        }
    }
    if (index > afterWhole && index < text.length && text[index].isDigit()) {
        val numeratorStart = index
        while (index < text.length && text[index].isDigit()) index += 1
        if (index < text.length && text[index] == '/') {
            val numerator = text.substring(numeratorStart, index).toDoubleOrNull() ?: return null
            val denominatorStart = index + 1
            index = denominatorStart
            while (index < text.length && text[index].isDigit()) index += 1
            val denominator = text.substring(denominatorStart, index).toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?: return null
            return parsedQuantity(text, index, whole + numerator / denominator)
        }
    }
    return parsedQuantity(text, afterWhole, whole)
}

private fun parsedQuantity(text: String, endIndex: Int, value: Double): ParsedRecipeQuantity? {
    if (!value.isFinite() || value <= 0.0) return null
    if (endIndex < text.length && !text[endIndex].isWhitespace()) return null
    return ParsedRecipeQuantity(value, text.drop(endIndex).trimStart())
}

private fun greatestCommonDivisor(first: Int, second: Int): Int {
    var left = first
    var right = second
    while (right != 0) {
        val remainder = left % right
        left = right
        right = remainder
    }
    return left.coerceAtLeast(1)
}

private const val QUANTITY_EPSILON = 0.000_001
private const val MAX_RECIPE_FRACTION_DENOMINATOR = 16
private const val MAX_RECIPE_FRACTION_ERROR = 0.005
private val SERVING_PREFIXES = listOf("serves", "servings", "yield")
private val PLURAL_RECIPE_UNITS = setOf("can", "cup", "clove", "egg", "handful", "jar", "package", "piece", "slice")
private val UNICODE_RECIPE_FRACTIONS = mapOf(
    '½' to 1.0 / 2.0,
    '⅓' to 1.0 / 3.0,
    '⅔' to 2.0 / 3.0,
    '¼' to 1.0 / 4.0,
    '¾' to 3.0 / 4.0,
    '⅕' to 1.0 / 5.0,
    '⅖' to 2.0 / 5.0,
    '⅗' to 3.0 / 5.0,
    '⅘' to 4.0 / 5.0,
    '⅛' to 1.0 / 8.0,
    '⅜' to 3.0 / 8.0,
    '⅝' to 5.0 / 8.0,
    '⅞' to 7.0 / 8.0,
)
