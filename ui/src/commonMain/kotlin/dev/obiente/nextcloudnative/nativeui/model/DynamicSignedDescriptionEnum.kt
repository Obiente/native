package dev.obiente.nextcloudnative.nativeui.model

/**
 * Recovers only the narrow signed prose shape `field ('first' or 'second')`.
 *
 * The field name must occur exactly once as a complete word, the parenthesized expression must
 * immediately follow it, and every alternative must be a quoted bounded scalar. General prose,
 * examples, comma lists, and unquoted words remain non-authoritative.
 */
internal fun String.exactSignedDescriptionEnum(fieldId: String): List<String>? {
    if (
        length !in 1..MAX_SIGNED_DESCRIPTION_ENUM_CHARACTERS ||
        fieldId.isBlank() ||
        fieldId.length > MAX_SIGNED_DESCRIPTION_ENUM_FIELD_LENGTH
    ) {
        return null
    }
    val occurrences = indices.filter { index ->
        index + fieldId.length <= length &&
            regionMatches(index, fieldId, 0, fieldId.length, ignoreCase = true) &&
            (index == 0 || !this[index - 1].isLetterOrDigit()) &&
            (index + fieldId.length == length || !this[index + fieldId.length].isLetterOrDigit())
    }
    val start = occurrences.singleOrNull() ?: return null
    val tail = substring(start + fieldId.length).trimStart()
    if (!tail.startsWith('(')) return null
    val close = tail.indexOf(')')
    if (close <= 1) return null
    val expression = tail.substring(1, close)
    val tokens = expression.split(Regex("""\s+or\s+""", RegexOption.IGNORE_CASE))
    if (tokens.size !in 2..MAX_SIGNED_DESCRIPTION_ENUM_VALUES) return null
    val values = tokens.mapNotNull { token ->
        val quoted = token.trim()
        if (
            quoted.length < 3 ||
            quoted.first() !in setOf('\'', '"') ||
            quoted.last() != quoted.first()
        ) {
            return@mapNotNull null
        }
        quoted.substring(1, quoted.lastIndex).takeIf { value ->
            value.length in 1..MAX_SIGNED_DESCRIPTION_ENUM_VALUE_LENGTH &&
                value.all { character ->
                    character.isLetterOrDigit() || character in setOf('-', '_', '.', ' ')
                }
        }
    }
    return values.takeIf {
        it.size == tokens.size &&
            it.distinct().size == it.size
    }
}

private const val MAX_SIGNED_DESCRIPTION_ENUM_CHARACTERS = 2_048
private const val MAX_SIGNED_DESCRIPTION_ENUM_FIELD_LENGTH = 128
private const val MAX_SIGNED_DESCRIPTION_ENUM_VALUES = 16
private const val MAX_SIGNED_DESCRIPTION_ENUM_VALUE_LENGTH = 128
