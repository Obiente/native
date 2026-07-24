package dev.obiente.nextcloudnative.template

/** One simple `{name}` token found without using platform-specific regular expressions. */
internal data class BracedTemplateToken(
    val startIndex: Int,
    val endIndexExclusive: Int,
    val name: String,
)

/**
 * Result of parsing remote brace-template text.
 *
 * The shared runtime deliberately does not use regular expressions for brace templates. Android's
 * ICU and the desktop JVM accept different brace syntax, while these strings come from remote app
 * contracts and messages.
 */
internal data class BracedTemplateScan(
    val tokens: List<BracedTemplateToken>,
    val malformed: Boolean,
)

internal fun String.scanBracedTemplate(): BracedTemplateScan {
    val tokens = mutableListOf<BracedTemplateToken>()
    var malformed = false
    var cursor = 0
    while (cursor < length) {
        when (this[cursor]) {
            '}' -> {
                malformed = true
                cursor += 1
            }

            '{' -> {
                val closingBrace = indexOf('}', startIndex = cursor + 1)
                if (closingBrace < 0) {
                    malformed = true
                    break
                }
                val name = substring(cursor + 1, closingBrace)
                if (name.isBlank() || '{' in name || '}' in name) {
                    malformed = true
                } else {
                    tokens += BracedTemplateToken(cursor, closingBrace + 1, name)
                }
                cursor = closingBrace + 1
            }

            else -> cursor += 1
        }
    }
    return BracedTemplateScan(tokens = tokens, malformed = malformed)
}

internal fun String.replaceBracedTemplateTokens(
    replacement: (name: String, original: String) -> String,
): String {
    val scan = scanBracedTemplate()
    if (scan.tokens.isEmpty()) return this
    return buildString(length) {
        var copiedUntil = 0
        scan.tokens.forEach { token ->
            append(this@replaceBracedTemplateTokens, copiedUntil, token.startIndex)
            val original = this@replaceBracedTemplateTokens.substring(token.startIndex, token.endIndexExclusive)
            append(replacement(token.name, original))
            copiedUntil = token.endIndexExclusive
        }
        append(this@replaceBracedTemplateTokens, copiedUntil, this@replaceBracedTemplateTokens.length)
    }
}
