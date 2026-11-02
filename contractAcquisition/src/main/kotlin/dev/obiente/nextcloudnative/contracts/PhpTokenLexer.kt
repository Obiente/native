package dev.obiente.nextcloudnative.contracts

internal sealed interface PhpToken {
    data class Word(val value: String) : PhpToken
    data class Text(val value: String) : PhpToken
    data class Symbol(val value: Char) : PhpToken
    data object Arrow : PhpToken
}

internal class PhpLiteralLexer(private val source: String) {
    private var index = 0
    private val result = mutableListOf<PhpToken>()

    fun tokens(): List<PhpToken> {
        check(source.length <= MAX_STATIC_PHP_SOURCE_CHARS) { "The PHP metadata file is too large." }
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index += 1
                source.startsWith("//", index) -> skipLineComment()
                source.startsWith("/*", index) -> skipBlockComment()
                source[index] == '#' -> skipLineComment()
                source.startsWith("=>", index) -> {
                    result += PhpToken.Arrow
                    index += 2
                }
                source[index] == '\'' || source[index] == '"' -> result += readText()
                source[index].isLetterOrDigit() || source[index] == '_' -> result += readWord()
                else -> {
                    result += PhpToken.Symbol(source[index])
                    index += 1
                }
            }
            check(result.size <= MAX_PHP_LITERAL_TOKENS) { "The PHP metadata has too many tokens." }
        }
        return result
    }

    private fun skipLineComment() {
        while (index < source.length && source[index] != '\n') index += 1
    }

    private fun skipBlockComment() {
        val end = source.indexOf("*/", index + 2)
        check(end >= 0) { "The PHP metadata has an unterminated comment." }
        index = end + 2
    }

    private fun readText(): PhpToken.Text {
        val quote = source[index++]
        val value = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            if (character == quote) return PhpToken.Text(value.toString())
            if (character == '\\') {
                check(index < source.length) { "The PHP string literal is truncated." }
                val escaped = source[index++]
                if (escaped == quote || escaped == '\\') {
                    value.append(escaped)
                } else {
                    // PHP single-quoted route requirements commonly contain regex escapes such
                    // as \d. Preserve unknown escapes so they can never be mistaken for a fixed
                    // literal path segment.
                    value.append('\\')
                    value.append(escaped)
                }
            } else {
                value.append(character)
            }
            check(value.length <= MAX_PHP_LITERAL_STRING_CHARS) { "A PHP string literal is too long." }
        }
        error("The PHP metadata has an unterminated string literal.")
    }

    private fun readWord(): PhpToken.Word {
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index += 1
        return PhpToken.Word(source.substring(start, index))
    }
}

internal const val MAX_PHP_LITERAL_TOKENS = 80_000
internal const val MAX_PHP_LITERAL_STRING_CHARS = 2_048
internal const val MAX_STATIC_PHP_SOURCE_CHARS = 1_048_576
