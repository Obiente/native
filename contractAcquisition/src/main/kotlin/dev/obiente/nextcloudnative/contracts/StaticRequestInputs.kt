package dev.obiente.nextcloudnative.contracts

/**
 * Request inputs a controller method reads outside its signature. Only two exact forms are
 * recognized: `$this->request->getParam('name')` (optionally with one literal default) and
 * `$this->request->getUploadedFile('name')`. Any other access to the request, including dynamic
 * keys, `getParams()`, or superglobals, leaves [unclassified] true so the method keeps the
 * existing rejection.
 */
internal data class StaticRequestInputs(
    val parameters: List<String>,
    val uploadedFiles: List<String>,
    val unclassified: Boolean,
) {
    val empty: Boolean get() = parameters.isEmpty() && uploadedFiles.isEmpty() && !unclassified
}

internal fun List<PhpToken>.staticRequestInputs(): StaticRequestInputs {
    val parameters = linkedSetOf<String>()
    val uploadedFiles = linkedSetOf<String>()
    val remainder = ArrayList<PhpToken>(size)
    var index = 0
    while (index < size) {
        val match = requestAccessAt(index)
        if (match == null) {
            remainder += get(index)
            index += 1
            continue
        }
        when (match.accessor) {
            "getparam" -> parameters += match.name
            "getuploadedfile" -> uploadedFiles += match.name
        }
        index = match.end
    }
    return StaticRequestInputs(
        parameters = parameters.toList(),
        uploadedFiles = uploadedFiles.toList(),
        unclassified = remainder.containsUnclassifiedRequestAccess(),
    )
}

/** The original conservative rule, applied to whatever the exact forms did not explain. */
internal fun List<PhpToken>.containsUnclassifiedRequestAccess(): Boolean {
    val words = filterIsInstance<PhpToken.Word>().map { token -> token.value.lowercase() }
    if ("request" !in words && "_post" !in words && "_get" !in words && "_files" !in words && "_request" !in words) return false
    return words.any { word -> word in REQUEST_INPUT_ACCESSORS } ||
        words.any { word -> word in SUPERGLOBALS }
}

private data class RequestAccessMatch(val accessor: String, val name: String, val end: Int)

private fun List<PhpToken>.requestAccessAt(start: Int): RequestAccessMatch? {
    var index = start
    fun symbol(value: Char): Boolean = ((getOrNull(index) as? PhpToken.Symbol)?.value == value).also { if (it) index += 1 }
    fun word(value: String? = null): String? =
        (getOrNull(index) as? PhpToken.Word)?.value?.takeIf { value == null || it == value }?.also { index += 1 }
    if (!symbol('$') || word("this") == null || !symbol('-') || !symbol('>')) return null
    if (word("request") == null || !symbol('-') || !symbol('>')) return null
    val accessor = word()?.lowercase()?.takeIf { it == "getparam" || it == "getuploadedfile" } ?: return null
    if (!symbol('(')) return null
    val name = (getOrNull(index) as? PhpToken.Text)?.takeIf { it.literal }?.value
        ?.takeIf { it.length in 1..64 && it.all { character -> character.isLetterOrDigit() || character == '_' } }
        ?: return null
    index += 1
    if (accessor == "getparam" && symbol(',')) {
        // One literal default is allowed; it never changes which input is read.
        when (val default = getOrNull(index)) {
            is PhpToken.Text -> if (!default.literal) return null
            is PhpToken.Word -> if (default.value.lowercase() !in LITERAL_WORDS && !default.value.all(Char::isDigit)) return null
            is PhpToken.Symbol -> if (default.value == '[' && (getOrNull(index + 1) as? PhpToken.Symbol)?.value == ']') index += 1 else return null
            else -> return null
        }
        index += 1
    }
    if (!symbol(')')) return null
    return RequestAccessMatch(accessor, name, index)
}

private val LITERAL_WORDS = setOf("null", "true", "false")
private val REQUEST_INPUT_ACCESSORS = setOf(
    "post", "put", "patch", "delete", "getparam", "getparams", "getuploadedfile",
)
private val SUPERGLOBALS = setOf("_post", "_get", "_files", "_request")
