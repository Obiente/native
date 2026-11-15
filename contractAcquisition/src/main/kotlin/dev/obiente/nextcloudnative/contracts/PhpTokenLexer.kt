package dev.obiente.nextcloudnative.contracts

internal sealed interface PhpToken {
    data class Word(val value: String) : PhpToken

    /**
     * A string literal. [literal] is false for interpolated double-quoted strings, heredocs, and
     * strings that exceed the literal length limit in declaration mode; their [value] is never a
     * trustworthy constant and callers that need one must reject the token.
     */
    data class Text(val value: String, val literal: Boolean = true) : PhpToken
    data class Symbol(val value: Char) : PhpToken
    data object Arrow : PhpToken

    /** `#[` opening a PHP 8 attribute group. Emitted only in declaration mode. */
    data object AttributeOpen : PhpToken

    /**
     * A PHP documentation comment (opened by slash-star-star). Emitted only in declaration mode. [complete] is false
     * when the comment exceeded [MAX_PHP_DOC_COMMENT_CHARS]; its text is then empty and no tag can
     * be read from it.
     */
    data class DocComment(val text: String, val complete: Boolean = true) : PhpToken
}

/**
 * Tokenizes the small PHP subset read by the static contract extractors. PHP is never executed.
 *
 * The default mode keeps the original metadata behavior: comments, including PHP 8 attributes,
 * are skipped. [declarations] mode keeps attribute openings and documentation comments as tokens
 * so controller attributes and psalm docblocks can be read, and tolerates long strings by marking
 * them non-literal instead of failing the whole file.
 */
internal class PhpLiteralLexer(
    private val source: String,
    private val declarations: Boolean = false,
) {
    private var index = 0
    private val result = mutableListOf<PhpToken>()

    fun tokens(): List<PhpToken> {
        check(source.length <= MAX_STATIC_PHP_SOURCE_CHARS) { "The PHP metadata file is too large." }
        while (index < source.length) {
            when {
                source[index].isWhitespace() -> index += 1
                source.startsWith("//", index) -> skipLineComment()
                declarations && source.startsWith("/**", index) && !source.startsWith("/**/", index) ->
                    result += readDocComment()
                source.startsWith("/*", index) -> skipBlockComment()
                declarations && source.startsWith("#[", index) -> {
                    result += PhpToken.AttributeOpen
                    index += 2
                }
                source[index] == '#' -> skipLineComment()
                source.startsWith("=>", index) -> {
                    result += PhpToken.Arrow
                    index += 2
                }
                source.startsWith("<<<", index) -> result += readHeredoc()
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

    private fun readDocComment(): PhpToken.DocComment {
        val end = source.indexOf("*/", index + 3)
        check(end >= 0) { "The PHP metadata has an unterminated comment." }
        val body = source.substring(index + 3, end)
        index = end + 2
        return if (body.length <= MAX_PHP_DOC_COMMENT_CHARS) {
            PhpToken.DocComment(body)
        } else {
            PhpToken.DocComment("", complete = false)
        }
    }

    private fun readText(): PhpToken.Text {
        val quote = source[index++]
        val value = StringBuilder()
        var interpolated = false
        var oversized = false
        while (index < source.length) {
            val character = source[index++]
            if (character == quote) {
                return PhpToken.Text(if (oversized) "" else value.toString(), literal = !interpolated && !oversized)
            }
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
                if (quote == '"' && character == '$') interpolated = true
                value.append(character)
            }
            if (value.length > MAX_PHP_LITERAL_STRING_CHARS) {
                check(declarations) { "A PHP string literal is too long." }
                oversized = true
                value.setLength(0)
            }
        }
        error("The PHP metadata has an unterminated string literal.")
    }

    /**
     * Consumes a heredoc or nowdoc as one opaque, non-literal string. Its body may contain quotes,
     * comment markers, or attribute-like text that must never become syntax.
     */
    private fun readHeredoc(): PhpToken.Text {
        index += 3
        while (index < source.length && (source[index] == ' ' || source[index] == '\t')) index += 1
        val quote = source.getOrNull(index)?.takeIf { it == '\'' || it == '"' }
        if (quote != null) index += 1
        val start = index
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index += 1
        val label = source.substring(start, index)
        check(label.isNotEmpty() && !label.first().isDigit()) { "The PHP heredoc label is malformed." }
        if (quote != null) {
            check(source.getOrNull(index) == quote) { "The PHP heredoc label is malformed." }
            index += 1
        }
        val lineEnd = source.indexOf('\n', index)
        check(lineEnd >= 0) { "The PHP heredoc is truncated." }
        index = lineEnd + 1
        while (index < source.length) {
            var lineStart = index
            while (lineStart < source.length && (source[lineStart] == ' ' || source[lineStart] == '\t')) lineStart += 1
            if (source.startsWith(label, lineStart)) {
                val after = source.getOrNull(lineStart + label.length)
                if (after == null || !(after.isLetterOrDigit() || after == '_')) {
                    index = lineStart + label.length
                    return PhpToken.Text("", literal = false)
                }
            }
            val next = source.indexOf('\n', index)
            check(next >= 0) { "The PHP heredoc is truncated." }
            index = next + 1
        }
        error("The PHP heredoc is truncated.")
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
internal const val MAX_PHP_DOC_COMMENT_CHARS = 65_536
