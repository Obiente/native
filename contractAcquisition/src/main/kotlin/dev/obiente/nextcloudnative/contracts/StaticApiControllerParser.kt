package dev.obiente.nextcloudnative.contracts

internal data class StaticApiController(
    val name: String,
    val methods: Set<String>,
    val singleParameters: Map<String, StaticPhpParameter>,
    val parameters: Map<String, List<StaticPhpParameter>>,
    val completeParameters: Map<String, List<StaticPhpParameter>>,
    val readParameters: Map<String, List<StaticPhpParameter>>,
    val methodsWithRequestInput: Set<String>,
    /** Exact request reads per method; consulted only for methods in [methodsWithRequestInput]. */
    val requestInputs: Map<String, StaticRequestInputs> = emptyMap(),
) {
    val normalizedName: String = name.removeSuffix("Controller").normalizedPhpName()
}

internal data class StaticControllerHeader(
    val name: String,
    val baseName: String,
)

/**
 * Reads the class name and the extended class with the declaration parser, so attributes such as
 * `#[Attr(Foo::class)]` and `implements` lists are never mistaken for the class or its base.
 */
internal fun parseControllerHeader(source: String): StaticControllerHeader? {
    val declaration = parsePhpClassDeclaration(source) ?: return null
    val baseName = declaration.baseName?.substringAfterLast('\\')?.takeIf(String::isNotEmpty) ?: return null
    return StaticControllerHeader(name = declaration.name, baseName = baseName)
}

internal fun resolveTrustedControllerBase(
    header: StaticControllerHeader,
    headers: Map<String, StaticControllerHeader>,
): String? {
    var current = header
    val visited = mutableSetOf(header.name.normalizedPhpName())
    repeat(MAX_STATIC_CONTROLLER_INHERITANCE_DEPTH) {
        if (current.baseName in STATIC_READ_CONTROLLER_BASES) return current.baseName
        val nextName = current.baseName.normalizedPhpName()
        if (!visited.add(nextName)) return null
        current = headers[nextName] ?: return null
    }
    return null
}

internal fun parseApiController(
    source: String,
    trustedBase: String,
): StaticApiController? = runCatching {
    val tokens = PhpLiteralLexer(source, declarations = true).tokens()
    val className = parseControllerHeader(source)?.name ?: return null
    val methodEntries = tokens.indices.mapNotNull { index ->
        val token = tokens[index]
        if (token !is PhpToken.Word || token.value != "function") return@mapNotNull null
        val nameIndex = (index + 1 until tokens.size).firstOrNull { candidate ->
            tokens[candidate] is PhpToken.Word
        } ?: return@mapNotNull null
        val methodName = (tokens[nameIndex] as PhpToken.Word).value.normalizedPhpName()
        val safe = trustedBase == "ApiController" || trustedBase == "OCSController" ||
            hasSafeJsonReturnType(tokens, nameIndex) ||
            hasOnlyDataResponseReturns(tokens, nameIndex)
        if (!safe) return@mapNotNull null
        ParsedStaticMethod(
            name = methodName,
            singleParameter = singleSerializableParameter(tokens, nameIndex),
            parameters = serializableParameters(tokens, nameIndex),
            readParameters = serializableParameters(tokens, nameIndex, allowScalarUnions = true),
            completeParameters = serializableParameters(
                tokens = tokens,
                methodNameIndex = nameIndex,
                allowUnsupportedOptional = false,
            ),
            requestInputs = methodBodyTokens(tokens, nameIndex)?.staticRequestInputs(),
        )
    }
    StaticApiController(
        name = className,
        methods = methodEntries.mapTo(linkedSetOf(), ParsedStaticMethod::name),
        singleParameters = methodEntries.mapNotNull { method ->
            method.singleParameter?.let { method.name to it }
        }.toMap(),
        parameters = methodEntries.mapNotNull { method ->
            method.parameters?.let { method.name to it }
        }.toMap(),
        completeParameters = methodEntries.mapNotNull { method ->
            method.completeParameters?.let { method.name to it }
        }.toMap(),
        readParameters = methodEntries.mapNotNull { method ->
            method.readParameters?.let { method.name to it }
        }.toMap(),
        methodsWithRequestInput = methodEntries
            .filter { method -> method.requestInputs?.empty == false }
            .mapTo(linkedSetOf(), ParsedStaticMethod::name),
        requestInputs = methodEntries.mapNotNull { method ->
            method.requestInputs?.takeIf { !it.empty }?.let { method.name to it }
        }.toMap(),
    )
}.getOrNull()

private data class ParsedStaticMethod(
    val name: String,
    val singleParameter: StaticPhpParameter?,
    val parameters: List<StaticPhpParameter>?,
    val completeParameters: List<StaticPhpParameter>?,
    val readParameters: List<StaticPhpParameter>?,
    val requestInputs: StaticRequestInputs?,
)

/**
 * Accepts legacy controllers which omit a return type only when every explicit return constructs
 * Nextcloud's JSON-only DataResponse family. This keeps page, redirect, download and stream
 * controllers outside the contract while supporting older apps whose API class extends the generic
 * Controller base.
 */
private fun hasOnlyDataResponseReturns(tokens: List<PhpToken>, methodNameIndex: Int): Boolean {
    val body = methodBodyTokens(tokens, methodNameIndex) ?: return false
    val returns = body.indices.filter { index ->
        (body[index] as? PhpToken.Word)?.value == "return"
    }
    if (returns.isEmpty()) return false
    return returns.all { returnIndex ->
        val end = (returnIndex + 1 until body.size).firstOrNull { index ->
            (body[index] as? PhpToken.Symbol)?.value == ';'
        } ?: return false
        body.subList(returnIndex + 1, end).constructsOnlySafeDataResponse()
    }
}

private fun List<PhpToken>.constructsOnlySafeDataResponse(): Boolean {
    val constructedTypes = indices.mapNotNull { index ->
        if ((get(index) as? PhpToken.Word)?.value != "new") return@mapNotNull null
        val openingParenthesis = (index + 1 until size).firstOrNull { candidate ->
            (get(candidate) as? PhpToken.Symbol)?.value == '('
        } ?: return@mapNotNull null
        subList(index + 1, openingParenthesis)
            .filterIsInstance<PhpToken.Word>()
            .lastOrNull()
            ?.value
    }
    return constructedTypes.isNotEmpty() &&
        constructedTypes.all { type -> type in STATIC_JSON_RESPONSE_TYPES }
}

internal fun methodBodyTokens(tokens: List<PhpToken>, methodNameIndex: Int): List<PhpToken>? {
    val openingParenthesis = (methodNameIndex + 1 until tokens.size).firstOrNull { index ->
        (tokens[index] as? PhpToken.Symbol)?.value == '('
    } ?: return null
    var parenthesisDepth = 0
    var signatureEnd = -1
    for (index in openingParenthesis until tokens.size) {
        val symbol = tokens[index] as? PhpToken.Symbol ?: continue
        when (symbol.value) {
            '(' -> parenthesisDepth += 1
            ')' -> {
                parenthesisDepth -= 1
                if (parenthesisDepth == 0) {
                    signatureEnd = index
                    break
                }
            }
        }
    }
    if (signatureEnd < 0) return null
    val bodyStart = (signatureEnd + 1 until tokens.size).firstOrNull { index ->
        (tokens[index] as? PhpToken.Symbol)?.value.let { symbol -> symbol == '{' || symbol == ';' }
    } ?: return null
    if ((tokens[bodyStart] as? PhpToken.Symbol)?.value != '{') return null
    var bodyDepth = 0
    for (index in bodyStart until tokens.size) {
        val symbol = tokens[index] as? PhpToken.Symbol ?: continue
        when (symbol.value) {
            '{' -> bodyDepth += 1
            '}' -> {
                bodyDepth -= 1
                if (bodyDepth == 0) return tokens.subList(bodyStart + 1, index)
            }
        }
    }
    return null
}

private fun hasSafeJsonReturnType(tokens: List<PhpToken>, methodNameIndex: Int): Boolean {
    val open = (methodNameIndex + 1 until tokens.size).firstOrNull { index ->
        val token = tokens[index]
        token is PhpToken.Symbol && token.value == '('
    } ?: return false
    var depth = 0
    var close = -1
    for (index in open until tokens.size) {
        val token = tokens[index]
        if (token is PhpToken.Symbol) {
            if (token.value == '(') depth += 1
            if (token.value == ')') {
                depth -= 1
                if (depth == 0) {
                    close = index
                    break
                }
            }
        }
    }
    if (close < 0) return false
    val bodyStart = (close + 1 until tokens.size).firstOrNull { index ->
        val token = tokens[index]
        token is PhpToken.Symbol && (token.value == '{' || token.value == ';')
    } ?: return false
    val signature = tokens.subList(close + 1, bodyStart)
    if (signature.none { token -> token is PhpToken.Symbol && token.value == ':' }) return false
    val returnTypes = signature.filterIsInstance<PhpToken.Word>()
        .map { token -> token.value }
        .filterNot { value -> value == "null" }
    return returnTypes.isNotEmpty() && returnTypes.all(String::isSafeStaticJsonReturnType)
}

private fun String.isSafeStaticJsonReturnType(): Boolean {
    if (this in STATIC_JSON_RETURN_TYPES) return true
    if (isEmpty() || length > 128 || !first().isUpperCase()) return false
    if (!all { character -> character.isLetterOrDigit() || character == '_' }) return false
    // Nextcloud page, redirect, download, stream, and other transport response objects are not
    // native data contracts. App-domain entities such as Deck's Card are serialized by the
    // framework and are useful safe GET results.
    return !endsWith("Response") && this !in STATIC_NON_DATA_RETURN_TYPES
}

private val STATIC_READ_CONTROLLER_BASES = setOf("ApiController", "OCSController", "Controller")

private val STATIC_JSON_RETURN_TYPES = setOf("array", "DataResponse", "JSONResponse", "JsonResponse")

private val STATIC_JSON_RESPONSE_TYPES = setOf("DataResponse", "JSONResponse", "JsonResponse")

private val STATIC_NON_DATA_RETURN_TYPES = setOf("Response", "IResponse")

internal const val MAX_STATIC_CONTROLLER_INHERITANCE_DEPTH = 8
