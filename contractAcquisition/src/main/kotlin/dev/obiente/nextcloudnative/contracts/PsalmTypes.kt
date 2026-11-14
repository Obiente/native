package dev.obiente.nextcloudnative.contracts

/**
 * The subset of psalm type syntax that Nextcloud's openapi-extractor maps to OpenAPI: scalars,
 * `?T` and `T|null`, `array{...}` shapes with optional keys, `list<T>`, `non-empty-list<T>`,
 * `T[]`, `array<string, T>`, literal unions, `int<min, max>`, class constants, aliases, and
 * generic response classes such as `DataResponse<status, T, headers>`.
 *
 * Parsing is strict and bounded. Any syntax outside this subset, or input beyond the length,
 * depth, or node limits, makes [parsePsalmType] return null so the caller drops the type and
 * keeps whatever weaker evidence it already had.
 */
internal sealed interface PsalmType {
    data class Named(val name: String, val arguments: List<PsalmType> = emptyList()) : PsalmType
    data class StringLiteral(val value: String) : PsalmType
    data class IntLiteral(val value: Long) : PsalmType
    data class ClassConstant(val className: String, val constant: String) : PsalmType
    data class Union(val members: List<PsalmType>) : PsalmType
    data class Shape(val entries: List<ShapeEntry>) : PsalmType
    data class ListOf(val item: PsalmType) : PsalmType

    data class ShapeEntry(val key: String, val optional: Boolean, val type: PsalmType)
}

internal fun parsePsalmType(text: String): PsalmType? {
    if (text.isBlank() || text.length > MAX_PSALM_TYPE_CHARS) return null
    return runCatching {
        val parser = PsalmTypeParser(PsalmTypeLexer(text).tokens())
        parser.parseComplete()
    }.getOrNull()
}

/**
 * Splits a `@param`, `@return`, or `@psalm-type` tag body into its leading type expression and the
 * remainder. Whitespace inside brackets, around `|`, and after `:` or `,` belongs to the type.
 */
internal fun splitLeadingPsalmType(content: String): Pair<String, String>? {
    var depth = 0
    var quote: Char? = null
    var index = 0
    val text = content.trim()
    while (index < text.length) {
        val character = text[index]
        when {
            quote != null -> if (character == quote) quote = null
            character == '\'' || character == '"' -> quote = character
            character in "<{([" -> depth += 1
            character in ">})]" -> depth -= 1
            character.isWhitespace() && depth == 0 -> {
                val previous = text.substring(0, index).trimEnd().lastOrNull()
                val next = text.substring(index).trimStart().firstOrNull()
                if (previous != '|' && previous != '&' && previous != ':' && previous != ',' && next != '|' && next != '&') {
                    return text.substring(0, index) to text.substring(index).trim()
                }
            }
        }
        if (depth < 0 || index > MAX_PSALM_TYPE_CHARS) return null
        index += 1
    }
    return if (depth == 0 && quote == null) text to "" else null
}

private sealed interface PsalmToken {
    data class Identifier(val value: String) : PsalmToken
    data class Text(val value: String) : PsalmToken
    data class Number(val value: Long) : PsalmToken
    data class Symbol(val value: Char) : PsalmToken
    data object Ellipsis : PsalmToken
    data object DoubleColon : PsalmToken
}

private class PsalmTypeLexer(private val text: String) {
    private var index = 0

    fun tokens(): List<PsalmToken> = buildList {
        while (index < text.length) {
            val character = text[index]
            when {
                character.isWhitespace() -> index += 1
                text.startsWith("...", index) -> {
                    add(PsalmToken.Ellipsis)
                    index += 3
                }
                text.startsWith("::", index) -> {
                    add(PsalmToken.DoubleColon)
                    index += 2
                }
                character == '\'' || character == '"' -> add(readText(character))
                character.isDigit() || (character == '-' && text.getOrNull(index + 1)?.isDigit() == true) -> add(readNumber())
                character.isLetter() || character == '_' || character == '\\' -> add(readIdentifier())
                character in "<>{}()[],:|&?=" -> {
                    add(PsalmToken.Symbol(character))
                    index += 1
                }
                else -> error("Unsupported psalm type character.")
            }
            check(size <= MAX_PSALM_TYPE_TOKENS) { "The psalm type is too large." }
        }
    }

    private fun readText(quote: Char): PsalmToken.Text {
        val end = text.indexOf(quote, index + 1)
        check(end > index) { "The psalm string literal is unterminated." }
        val value = text.substring(index + 1, end)
        check('\\' !in value) { "Escaped psalm string literals are not supported." }
        index = end + 1
        return PsalmToken.Text(value)
    }

    private fun readNumber(): PsalmToken.Number {
        val start = index
        if (text[index] == '-') index += 1
        while (index < text.length && text[index].isDigit()) index += 1
        check(text.getOrNull(index) != '.') { "Float literals are not supported." }
        return PsalmToken.Number(text.substring(start, index).toLongOrNull() ?: error("The psalm integer is too large."))
    }

    private fun readIdentifier(): PsalmToken.Identifier {
        val start = index
        while (index < text.length) {
            val character = text[index]
            val dash = character == '-' && text.getOrNull(index + 1)?.isLetter() == true
            if (!(character.isLetterOrDigit() || character == '_' || character == '\\' || dash)) break
            index += 1
        }
        return PsalmToken.Identifier(text.substring(start, index))
    }
}

private class PsalmTypeParser(private val tokens: List<PsalmToken>) {
    private var index = 0
    private var nodes = 0

    fun parseComplete(): PsalmType {
        val type = parseUnion(0)
        check(index == tokens.size) { "Trailing psalm type syntax." }
        return type
    }

    private fun node() {
        nodes += 1
        check(nodes <= MAX_PSALM_TYPE_NODES) { "The psalm type has too many nodes." }
    }

    private fun peekSymbol(): Char? = (tokens.getOrNull(index) as? PsalmToken.Symbol)?.value

    private fun expect(symbol: Char) {
        check(peekSymbol() == symbol) { "Expected '$symbol' in psalm type." }
        index += 1
    }

    private fun parseUnion(depth: Int): PsalmType {
        check(depth <= MAX_PSALM_TYPE_DEPTH) { "The psalm type is nested too deeply." }
        val members = mutableListOf(parsePostfix(depth))
        while (peekSymbol() == '|') {
            index += 1
            members += parsePostfix(depth)
        }
        // Intersections and conditional types are outside the supported subset.
        check(peekSymbol() != '&' && peekSymbol() != '?') { "Unsupported psalm type operator." }
        return members.singleOrNull() ?: PsalmType.Union(members.flatMap { (it as? PsalmType.Union)?.members ?: listOf(it) })
    }

    private fun parsePostfix(depth: Int): PsalmType {
        var type = parsePrimary(depth)
        while (peekSymbol() == '[' && (tokens.getOrNull(index + 1) as? PsalmToken.Symbol)?.value == ']') {
            index += 2
            node()
            type = PsalmType.ListOf(type)
        }
        return type
    }

    private fun parsePrimary(depth: Int): PsalmType {
        node()
        return when (val token = tokens.getOrNull(index++) ?: error("The psalm type is truncated.")) {
            is PsalmToken.Symbol -> when (token.value) {
                '?' -> PsalmType.Union(listOf(parsePostfix(depth + 1), PsalmType.Named("null")))
                '(' -> parseUnion(depth + 1).also { expect(')') }
                else -> error("Unexpected psalm type symbol.")
            }
            is PsalmToken.Text -> PsalmType.StringLiteral(token.value)
            is PsalmToken.Number -> PsalmType.IntLiteral(token.value)
            is PsalmToken.Identifier -> parseNamed(token.value, depth)
            else -> error("Unexpected psalm type token.")
        }
    }

    private fun parseNamed(name: String, depth: Int): PsalmType {
        if (tokens.getOrNull(index) == PsalmToken.DoubleColon) {
            index += 1
            val constant = (tokens.getOrNull(index++) as? PsalmToken.Identifier)?.value
                ?: error("A psalm class constant is malformed.")
            check(constant.all { it.isLetterOrDigit() || it == '_' }) { "Wildcard class constants are not supported." }
            return PsalmType.ClassConstant(name, constant)
        }
        val lower = name.lowercase()
        return when (peekSymbol()) {
            '{' -> {
                check(lower == "array" || lower == "non-empty-array") { "Only array shapes are supported." }
                index += 1
                parseShape(depth + 1)
            }
            '<' -> {
                index += 1
                val arguments = mutableListOf(parseUnion(depth + 1))
                while (peekSymbol() == ',') {
                    index += 1
                    arguments += parseUnion(depth + 1)
                }
                expect('>')
                check(arguments.size <= 4) { "Too many psalm type arguments." }
                PsalmType.Named(name, arguments)
            }
            else -> PsalmType.Named(name)
        }
    }

    private fun parseShape(depth: Int): PsalmType.Shape {
        check(depth <= MAX_PSALM_TYPE_DEPTH) { "The psalm type is nested too deeply." }
        val entries = mutableListOf<PsalmType.ShapeEntry>()
        while (peekSymbol() != '}') {
            check(tokens.getOrNull(index) != PsalmToken.Ellipsis) { "Unsealed psalm shapes are not supported." }
            val key = when (val token = tokens.getOrNull(index)) {
                is PsalmToken.Identifier -> token.value
                is PsalmToken.Text -> token.value
                is PsalmToken.Number -> token.value.toString()
                else -> error("A psalm shape key is malformed.")
            }
            index += 1
            val optional = peekSymbol() == '?'
            if (optional) index += 1
            expect(':')
            check(entries.none { it.key == key }) { "Duplicate psalm shape key." }
            entries += PsalmType.ShapeEntry(key, optional, parseUnion(depth + 1))
            check(entries.size <= MAX_PSALM_SHAPE_ENTRIES) { "The psalm shape is too large." }
            if (peekSymbol() == ',') index += 1 else check(peekSymbol() == '}') { "A psalm shape is malformed." }
        }
        index += 1
        return PsalmType.Shape(entries)
    }
}

internal const val MAX_PSALM_TYPE_CHARS = 8_192
private const val MAX_PSALM_TYPE_TOKENS = 4_096
private const val MAX_PSALM_TYPE_NODES = 2_048
private const val MAX_PSALM_TYPE_DEPTH = 16
private const val MAX_PSALM_SHAPE_ENTRIES = 256
