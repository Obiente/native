package dev.obiente.nextcloudnative.nativeui.model

internal val SEMANTIC_TAXONOMY_CONCEPTS: Set<String> = setOf(
    "category",
    "tag",
    "keyword",
    "label",
)

/**
 * Matches semantic concepts as complete identifier words, never as arbitrary substrings.
 *
 * Contract identifiers commonly mix route separators, camel case, and plural nouns. Tokens are
 * split at all of those boundaries and normalized to a singular concept so `category` and
 * `categories` match while unrelated words such as `staging` never become `tag`.
 */
internal fun String.hasAnySemanticConcept(concepts: Set<String>): Boolean =
    semanticConceptTokens().any(concepts::contains)

internal fun String.semanticConceptTokens(): Set<String> = semanticConceptTokenList().toSet()

/** Ordered words preserve repeated suffixes when a policy depends on the actual last word. */
internal fun String.semanticConceptTokenList(): List<String> {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isEmpty()) return
        tokens += current.toString().semanticConceptSingular()
        current.clear()
    }

    forEachIndexed { index, character ->
        if (!character.isLetterOrDigit()) {
            flush()
            return@forEachIndexed
        }
        val previous = getOrNull(index - 1)
        val next = getOrNull(index + 1)
        val startsWord = character.isUpperCase() &&
            current.isNotEmpty() &&
            (
                previous?.isLowerCase() == true ||
                    previous?.isDigit() == true ||
                    (previous?.isUpperCase() == true && next?.isLowerCase() == true)
                )
        if (startsWord) flush()
        current.append(character.lowercaseChar())
    }
    flush()
    return tokens
}

private fun String.semanticConceptSingular(): String = when {
    endsWith("ies") && length > 3 -> dropLast(3) + "y"
    endsWith("ches") || endsWith("shes") -> dropLast(2)
    endsWith("ses") || endsWith("xes") || endsWith("zes") -> dropLast(2)
    endsWith('s') && length > 1 && !endsWith("ss") -> dropLast(1)
    else -> this
}
