package dev.obiente.nextcloudnative.contracts

/**
 * The statically readable declaration surface of one PHP class: namespace, imports, attributes,
 * documentation comments, and method boundaries. PHP is never executed; every value that is not a
 * literal, an array of literals, or a class constant reference is kept as [PhpValue.Unsupported].
 */
internal data class PhpClassDeclaration(
    val namespace: String?,
    /** Lower-case alias to fully qualified name, without a leading backslash. */
    val imports: Map<String, String>,
    val name: String,
    val baseName: String?,
    val interfaces: List<String>,
    val docComment: PhpToken.DocComment?,
    val attributes: List<PhpAttribute>,
    val methods: List<PhpMethodDeclaration>,
    val tokens: List<PhpToken>,
) {
    val qualifiedName: String = namespace?.let { "$it\\$name" } ?: name

    /** Resolves a class name as PHP does for class references: imports first, then the namespace. */
    fun resolveClass(raw: String): String = resolvePhpClassName(raw, namespace, imports)
}

internal data class PhpMethodDeclaration(
    val name: String,
    /** Index of the method name in [PhpClassDeclaration.tokens]. */
    val nameIndex: Int,
    val public: Boolean,
    val static: Boolean,
    val attributes: List<PhpAttribute>,
    val docComment: PhpToken.DocComment?,
)

internal data class PhpAttribute(
    /** Fully qualified attribute class name without a leading backslash. */
    val name: String,
    val arguments: List<PhpAttributeArgument>,
) {
    val shortName: String get() = name.substringAfterLast('\\')

    fun argument(name: String, position: Int): PhpValue? =
        arguments.firstOrNull { it.name == name }?.value
            ?: arguments.getOrNull(position)?.takeIf { it.name == null }?.value
}

internal data class PhpAttributeArgument(val name: String?, val value: PhpValue)

internal sealed interface PhpValue {
    data class Text(val value: String) : PhpValue
    data class Integer(val value: Long) : PhpValue
    data class Bool(val value: Boolean) : PhpValue
    data object Null : PhpValue

    /** `Class::NAME`, with the class resolved through the file's imports. */
    data class Constant(val className: String, val constant: String) : PhpValue
    data class Array(val entries: List<Pair<PhpValue?, PhpValue>>) : PhpValue
    data object Unsupported : PhpValue
}

internal fun PhpValue.literalText(): String? = when (this) {
    is PhpValue.Text -> value
    is PhpValue.Integer -> value.toString()
    is PhpValue.Bool -> value.toString()
    else -> null
}

/** Parses the first class declared in [source], or returns null when its structure is unreadable. */
internal fun parsePhpClassDeclaration(source: String): PhpClassDeclaration? = runCatching {
    PhpDeclarationReader(PhpLiteralLexer(source, declarations = true).tokens()).read()
}.getOrNull()

/**
 * Reads one value expression starting at [start] and ending at a top-level `;`. Literal arrays keep
 * their structure; any non-literal element is [PhpValue.Unsupported]. Null when unreadable.
 */
internal fun readPhpValueExpression(tokens: List<PhpToken>, start: Int): PhpValue? = runCatching {
    PhpDeclarationReader(tokens).readExpressionAt(start)
}.getOrNull()

private class PhpDeclarationReader(private val tokens: List<PhpToken>) {
    private var index = 0

    fun readExpressionAt(start: Int): PhpValue {
        index = start
        return readValueExpression(depth = 0, terminators = setOf(';'))
    }
    private var namespace: String? = null
    private val imports = linkedMapOf<String, String>()
    private var valueNodes = 0

    fun read(): PhpClassDeclaration? {
        var pendingDoc: PhpToken.DocComment? = null
        var pendingAttributes = mutableListOf<RawAttribute>()
        while (index < tokens.size) {
            when (val token = tokens[index]) {
                is PhpToken.DocComment -> {
                    pendingDoc = token
                    index += 1
                }
                PhpToken.AttributeOpen -> pendingAttributes += readAttributeGroup()
                is PhpToken.Word -> when {
                    token.value == "namespace" -> {
                        index += 1
                        namespace = readQualifiedName()
                    }
                    token.value == "use" -> {
                        index += 1
                        readImport()
                    }
                    token.value == "class" && !precededByDoubleColon(index) -> {
                        return readClass(pendingDoc, pendingAttributes)
                    }
                    token.value in CLASS_MODIFIERS -> index += 1
                    else -> {
                        index += 1
                        pendingDoc = null
                        pendingAttributes = mutableListOf()
                    }
                }
                else -> {
                    index += 1
                    if ((token as? PhpToken.Symbol)?.value == ';') {
                        pendingDoc = null
                        pendingAttributes = mutableListOf()
                    }
                }
            }
        }
        return null
    }

    private fun precededByDoubleColon(position: Int): Boolean =
        (tokens.getOrNull(position - 1) as? PhpToken.Symbol)?.value == ':' &&
            (tokens.getOrNull(position - 2) as? PhpToken.Symbol)?.value == ':'

    /** Reads `A\B\C`; a leading backslash is kept only for class references that need it. */
    private fun readQualifiedName(keepLeadingSlash: Boolean = false): String? {
        val name = StringBuilder()
        var expectWord = true
        while (index < tokens.size) {
            when (val token = tokens[index]) {
                // Names alternate words and backslashes; `use A\B as C` must stop before `as`.
                is PhpToken.Word -> if (expectWord) name.append(token.value) else break
                is PhpToken.Symbol -> if (token.value == '\\' && (expectWord.not() || name.isEmpty())) name.append('\\') else break
                else -> break
            }
            expectWord = tokens[index] is PhpToken.Symbol
            index += 1
            check(name.length <= MAX_PHP_QUALIFIED_NAME_CHARS) { "A PHP name is too long." }
        }
        val text = name.toString()
        return (if (keepLeadingSlash) text else text.removePrefix("\\")).takeIf { it.isNotEmpty() && it != "\\" }
    }

    private fun readImport() {
        val kind = (tokens.getOrNull(index) as? PhpToken.Word)?.value
        if (kind == "function" || kind == "const") {
            skipTo(';')
            return
        }
        while (index < tokens.size) {
            val name = readQualifiedName() ?: break
            if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '{') {
                index += 1
                while ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value != '}') {
                    val member = readQualifiedName() ?: error("A grouped import is malformed.")
                    addImport(name.removeSuffix("\\") + "\\" + member)
                    if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == ',') index += 1
                    check(index < tokens.size) { "A grouped import is truncated." }
                }
                index += 1
            } else {
                addImport(name)
            }
            if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == ',') {
                index += 1
            } else {
                break
            }
        }
        skipTo(';')
    }

    private fun addImport(name: String) {
        val alias = if ((tokens.getOrNull(index) as? PhpToken.Word)?.value == "as") {
            index += 1
            (tokens.getOrNull(index++) as? PhpToken.Word)?.value ?: error("An import alias is malformed.")
        } else {
            name.substringAfterLast('\\')
        }
        check(imports.size < MAX_PHP_IMPORTS) { "The PHP file has too many imports." }
        imports[alias.lowercase()] = name
    }

    private fun skipTo(symbol: Char) {
        while (index < tokens.size && (tokens[index] as? PhpToken.Symbol)?.value != symbol) index += 1
        index += 1
    }

    private fun readClass(doc: PhpToken.DocComment?, rawAttributes: List<RawAttribute>): PhpClassDeclaration? {
        index += 1
        val name = (tokens.getOrNull(index++) as? PhpToken.Word)?.value ?: return null
        var baseName: String? = null
        val interfaces = mutableListOf<String>()
        while (index < tokens.size && (tokens[index] as? PhpToken.Symbol)?.value != '{') {
            when ((tokens[index] as? PhpToken.Word)?.value) {
                "extends" -> {
                    index += 1
                    baseName = readQualifiedName(keepLeadingSlash = true)
                }
                "implements" -> {
                    index += 1
                    while (true) {
                        interfaces += readQualifiedName(keepLeadingSlash = true) ?: break
                        if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == ',') index += 1 else break
                    }
                }
                else -> index += 1
            }
        }
        check(index < tokens.size) { "The PHP class body is missing." }
        index += 1
        val methods = mutableListOf<PhpMethodDeclaration>()
        var pendingDoc: PhpToken.DocComment? = null
        var pendingAttributes = mutableListOf<RawAttribute>()
        var modifiers = mutableSetOf<String>()
        while (index < tokens.size) {
            when (val token = tokens[index]) {
                is PhpToken.DocComment -> {
                    pendingDoc = token
                    index += 1
                }
                PhpToken.AttributeOpen -> pendingAttributes += readAttributeGroup()
                is PhpToken.Word -> if (token.value == "function") {
                    val nameIndex = index + 1
                    val methodName = (tokens.getOrNull(nameIndex) as? PhpToken.Word)?.value
                        ?: error("A PHP method name is missing.")
                    check(methods.size < MAX_PHP_METHODS) { "The PHP class has too many methods." }
                    methods += PhpMethodDeclaration(
                        name = methodName,
                        nameIndex = nameIndex,
                        public = "private" !in modifiers && "protected" !in modifiers,
                        static = "static" in modifiers,
                        attributes = pendingAttributes.map(::resolveAttribute),
                        docComment = pendingDoc,
                    )
                    index = nameIndex + 1
                    skipMethod()
                    pendingDoc = null
                    pendingAttributes = mutableListOf()
                    modifiers = mutableSetOf()
                } else {
                    if (token.value in MEMBER_MODIFIERS) modifiers += token.value
                    index += 1
                }
                is PhpToken.Symbol -> {
                    index += 1
                    when (token.value) {
                        '}' -> return PhpClassDeclaration(
                            namespace = namespace,
                            imports = imports.toMap(),
                            name = name,
                            baseName = baseName,
                            interfaces = interfaces,
                            docComment = doc,
                            attributes = rawAttributes.map(::resolveAttribute),
                            methods = methods,
                            tokens = tokens,
                        )
                        ';' -> {
                            pendingDoc = null
                            pendingAttributes = mutableListOf()
                            modifiers = mutableSetOf()
                        }
                        '{' -> {
                            index -= 1
                            skipBalanced('{', '}')
                        }
                    }
                }
                else -> index += 1
            }
        }
        error("The PHP class body is truncated.")
    }

    /** Skips a method signature and body, or the `;` ending an abstract or interface method. */
    private fun skipMethod() {
        check((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(') { "A PHP method signature is malformed." }
        skipBalanced('(', ')')
        while (index < tokens.size) {
            when ((tokens[index] as? PhpToken.Symbol)?.value) {
                ';' -> {
                    index += 1
                    return
                }
                '{' -> {
                    skipBalanced('{', '}')
                    return
                }
                else -> index += 1
            }
        }
        error("A PHP method body is truncated.")
    }

    private fun skipBalanced(open: Char, close: Char) {
        var depth = 0
        while (index < tokens.size) {
            when ((tokens[index++] as? PhpToken.Symbol)?.value) {
                open -> depth += 1
                close -> {
                    depth -= 1
                    if (depth == 0) return
                }
            }
        }
        error("A PHP block is truncated.")
    }

    private data class RawAttribute(val name: String, val arguments: List<PhpAttributeArgument>)

    private fun resolveAttribute(raw: RawAttribute): PhpAttribute = PhpAttribute(
        name = resolvePhpClassName(raw.name, namespace, imports),
        arguments = raw.arguments.map { argument ->
            val value = argument.value
            if (value is PhpValue.Constant) {
                argument.copy(value = value.copy(className = resolvePhpClassName(value.className, namespace, imports)))
            } else {
                argument
            }
        },
    )

    /** Reads `#[A, B(args), ...]`; the opening token is at [index]. */
    private fun readAttributeGroup(): List<RawAttribute> {
        index += 1
        val attributes = mutableListOf<RawAttribute>()
        while (true) {
            val symbol = (tokens.getOrNull(index) as? PhpToken.Symbol)?.value
            if (symbol == ']') {
                index += 1
                return attributes
            }
            val leadingSlash = symbol == '\\'
            val name = readQualifiedName() ?: error("A PHP attribute name is malformed.")
            val arguments = if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(') readArguments() else emptyList()
            check(attributes.size < MAX_PHP_ATTRIBUTES_PER_GROUP) { "A PHP attribute group is too large." }
            attributes += RawAttribute(if (leadingSlash) "\\$name" else name, arguments)
            when ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value) {
                ',' -> index += 1
                ']' -> Unit
                else -> error("A PHP attribute group is malformed.")
            }
        }
    }

    private fun readArguments(): List<PhpAttributeArgument> {
        index += 1
        val arguments = mutableListOf<PhpAttributeArgument>()
        while ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value != ')') {
            check(index < tokens.size) { "PHP attribute arguments are truncated." }
            val named = (tokens.getOrNull(index) as? PhpToken.Word)?.value?.takeIf {
                (tokens.getOrNull(index + 1) as? PhpToken.Symbol)?.value == ':' &&
                    (tokens.getOrNull(index + 2) as? PhpToken.Symbol)?.value != ':'
            }
            if (named != null) index += 2
            arguments += PhpAttributeArgument(named, readValueExpression(depth = 0, terminators = ARGUMENT_TERMINATORS))
            check(arguments.size <= MAX_PHP_ATTRIBUTE_ARGUMENTS) { "A PHP attribute has too many arguments." }
            if ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == ',') index += 1
        }
        index += 1
        return arguments
    }

    /**
     * Reads one expression up to a top-level terminator. A single literal, constant reference, or
     * array literal is retained; anything followed by an operator (concatenation, bitwise or,
     * arithmetic) or built by a call or `new` is [PhpValue.Unsupported].
     */
    private fun readValueExpression(depth: Int, terminators: Set<Char>): PhpValue {
        val start = index
        val value = runCatching { readValue(depth) }.getOrNull()
        val next = (tokens.getOrNull(index) as? PhpToken.Symbol)?.value
        if (value != null && next != null && next in terminators) return value
        index = start
        skipExpression(terminators)
        return PhpValue.Unsupported
    }

    private fun readValue(depth: Int): PhpValue {
        check(depth <= MAX_PHP_VALUE_DEPTH) { "A PHP attribute value is nested too deeply." }
        valueNodes += 1
        check(valueNodes <= MAX_PHP_VALUE_NODES) { "PHP attribute values are too large." }
        return when (val token = tokens.getOrNull(index) ?: error("A PHP attribute value is truncated.")) {
            is PhpToken.Text -> {
                index += 1
                if (token.literal) PhpValue.Text(token.value) else PhpValue.Unsupported
            }
            is PhpToken.Symbol -> when (token.value) {
                '[' -> readArray(depth, close = ']')
                '-' -> {
                    index += 1
                    val number = (tokens.getOrNull(index++) as? PhpToken.Word)?.value?.toLongOrNull()
                        ?: error("A negative PHP literal is malformed.")
                    PhpValue.Integer(-number)
                }
                '\\' -> readConstantReference()
                else -> error("Unsupported PHP attribute value.")
            }
            is PhpToken.Word -> when {
                token.value.lowercase() == "true" -> PhpValue.Bool(true).also { index += 1 }
                token.value.lowercase() == "false" -> PhpValue.Bool(false).also { index += 1 }
                token.value.lowercase() == "null" -> PhpValue.Null.also { index += 1 }
                token.value.all(Char::isDigit) -> {
                    index += 1
                    PhpValue.Integer(token.value.toLongOrNull() ?: error("A PHP integer is too large."))
                }
                token.value.lowercase() == "array" && (tokens.getOrNull(index + 1) as? PhpToken.Symbol)?.value == '(' -> {
                    index += 1
                    readArray(depth, close = ')')
                }
                else -> readConstantReference()
            }
            else -> error("Unsupported PHP attribute value.")
        }
    }

    private fun readConstantReference(): PhpValue {
        val leadingSlash = (tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '\\'
        val className = readQualifiedName() ?: error("A PHP constant reference is malformed.")
        val raw = if (leadingSlash) "\\$className" else className
        check(
            (tokens.getOrNull(index) as? PhpToken.Symbol)?.value == ':' &&
                (tokens.getOrNull(index + 1) as? PhpToken.Symbol)?.value == ':',
        ) { "A bare PHP constant is not supported." }
        index += 2
        val constant = (tokens.getOrNull(index++) as? PhpToken.Word)?.value ?: error("A PHP constant name is missing.")
        if (constant == "class") return PhpValue.Unsupported
        return PhpValue.Constant(raw, constant)
    }

    private fun readArray(depth: Int, close: Char): PhpValue.Array {
        index += 1
        val entries = mutableListOf<Pair<PhpValue?, PhpValue>>()
        val terminators = setOf(',', close)
        while ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value != close) {
            check(index < tokens.size) { "A PHP array literal is truncated." }
            val first = readValue(depth + 1)
            val entry = if (tokens.getOrNull(index) == PhpToken.Arrow) {
                index += 1
                first to readValueExpression(depth + 1, terminators)
            } else {
                null to first
            }
            entries += entry
            check(entries.size <= MAX_PHP_ARRAY_ENTRIES) { "A PHP array literal has too many entries." }
            when ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value) {
                ',' -> index += 1
                close -> Unit
                else -> error("A PHP array literal is malformed.")
            }
        }
        index += 1
        return PhpValue.Array(entries)
    }

    /** Skips to the next top-level terminator, keeping nested brackets balanced. */
    private fun skipExpression(terminators: Set<Char>) {
        var depth = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token == PhpToken.AttributeOpen) {
                depth += 1
                index += 1
                continue
            }
            val symbol = (token as? PhpToken.Symbol)?.value
            if (depth == 0 && symbol != null && symbol in terminators) return
            when (symbol) {
                '(', '[', '{' -> depth += 1
                ')', ']', '}' -> {
                    depth -= 1
                    check(depth >= 0) { "A PHP expression is unbalanced." }
                }
            }
            index += 1
        }
        error("A PHP expression is truncated.")
    }
}

/**
 * Resolves a class name as PHP does for class references: a fully qualified name stands alone, an
 * imported alias expands, and anything else is relative to the current namespace. `self` and
 * `static` stay symbolic.
 */
internal fun resolvePhpClassName(raw: String, namespace: String?, imports: Map<String, String>): String {
    if (raw.equals("self", ignoreCase = true) || raw.equals("static", ignoreCase = true)) return raw.lowercase()
    if (raw.startsWith('\\')) return raw.removePrefix("\\")
    val first = raw.substringBefore('\\')
    val imported = imports[first.lowercase()]
    return when {
        imported != null && first == raw -> imported
        imported != null -> imported + "\\" + raw.substringAfter('\\')
        namespace != null -> "$namespace\\$raw"
        else -> raw
    }
}

private val CLASS_MODIFIERS = setOf("final", "abstract", "readonly")
private val MEMBER_MODIFIERS = setOf("public", "protected", "private", "static", "abstract", "final", "readonly")
private val ARGUMENT_TERMINATORS = setOf(',', ')')
private const val MAX_PHP_QUALIFIED_NAME_CHARS = 512
private const val MAX_PHP_IMPORTS = 512
private const val MAX_PHP_METHODS = 512
private const val MAX_PHP_ATTRIBUTES_PER_GROUP = 32
private const val MAX_PHP_ATTRIBUTE_ARGUMENTS = 16
private const val MAX_PHP_ARRAY_ENTRIES = 256
private const val MAX_PHP_VALUE_DEPTH = 8
private const val MAX_PHP_VALUE_NODES = 20_000
