package dev.obiente.nextcloudnative.contracts

internal data class StaticRoute(
    val controller: String,
    val method: String,
    val url: String,
    val verb: String,
    val ocs: Boolean,
    val requirements: Map<String, String>,
)

private sealed interface PhpLiteral {
    data class Text(val value: String) : PhpLiteral
    data class Sequence(val entries: List<Entry>) : PhpLiteral
    data object Null : PhpLiteral
    data object Unsupported : PhpLiteral

    data class Entry(val key: String?, val value: PhpLiteral)
}

internal fun parseRouteManifest(source: String): List<StaticRoute>? = runCatching {
    val tokens = PhpLiteralLexer(source).tokens()
    val root = tokens.indices
        .filter { index -> (tokens[index] as? PhpToken.Word)?.value == "return" }
        .asReversed()
        .mapNotNull { returnIndex ->
            runCatching {
                PhpLiteralParser(tokens, returnIndex + 1).parseValue() as? PhpLiteral.Sequence
            }.getOrNull()
        }
        .firstOrNull { candidate ->
            candidate.named("routes") is PhpLiteral.Sequence ||
                candidate.named("ocs") is PhpLiteral.Sequence ||
                candidate.named("resources") is PhpLiteral.Sequence
        }
        ?: return null
    buildList {
        listOf("routes" to false, "ocs" to true).forEach { (groupName, ocs) ->
            val group = root.named(groupName) as? PhpLiteral.Sequence ?: return@forEach
            group.entries.take(MAX_STATIC_ROUTE_COUNT).forEach routeLoop@ { entry ->
                val route = entry.value as? PhpLiteral.Sequence ?: return@routeLoop
                val name = route.text("name") ?: return@routeLoop
                val separator = name.indexOf('#')
                if (separator <= 0 || separator != name.lastIndexOf('#') || separator == name.lastIndex) {
                    return@routeLoop
                }
                val url = route.text("url") ?: return@routeLoop
                val verb = route.text("verb")?.uppercase() ?: return@routeLoop
                val requirements = (route.named("requirements") as? PhpLiteral.Sequence)
                    ?.entries
                    ?.mapNotNull { requirement ->
                        val key = requirement.key ?: return@mapNotNull null
                        val value = (requirement.value as? PhpLiteral.Text)?.value ?: return@mapNotNull null
                        key to value
                    }
                    ?.toMap()
                    .orEmpty()
                add(
                    StaticRoute(
                        controller = name.substring(0, separator),
                        method = name.substring(separator + 1),
                        url = url,
                        verb = verb,
                        ocs = ocs,
                        requirements = requirements,
                    ),
                )
            }
        }
        val resources = root.named("resources") as? PhpLiteral.Sequence
        resources?.entries?.take(MAX_STATIC_ROUTE_COUNT)?.forEach resourceLoop@ { entry ->
            val controller = entry.key ?: return@resourceLoop
            val resource = entry.value as? PhpLiteral.Sequence ?: return@resourceLoop
            val url = resource.text("url") ?: return@resourceLoop
            val only = resource.optionalTextList("only") ?: return@resourceLoop
            val except = resource.optionalTextList("except") ?: return@resourceLoop
            fun enabled(method: String): Boolean =
                (only.values == null || method.normalizedPhpName() in only.values) &&
                    method.normalizedPhpName() !in except.values.orEmpty()
            if (enabled("index")) {
                add(
                    StaticRoute(
                        controller = controller,
                        method = "index",
                        url = url,
                        verb = "GET",
                        ocs = false,
                        requirements = emptyMap(),
                    ),
                )
            }
            if (enabled("show")) {
                add(
                    StaticRoute(
                        controller = controller,
                        method = "show",
                        url = "${url.trimEnd('/')}/{id}",
                        verb = "GET",
                        ocs = false,
                        requirements = emptyMap(),
                    ),
                )
            }
        }
    }
}.getOrNull()

private fun PhpLiteral.Sequence.named(name: String): PhpLiteral? =
    entries.firstOrNull { entry -> entry.key == name }?.value

private fun PhpLiteral.Sequence.text(name: String): String? = (named(name) as? PhpLiteral.Text)?.value

private data class OptionalTextList(val values: Set<String>?)

private fun PhpLiteral.Sequence.optionalTextList(name: String): OptionalTextList? {
    val value = named(name) ?: return OptionalTextList(null)
    val sequence = value as? PhpLiteral.Sequence ?: return null
    val entries = sequence.entries.map { entry ->
        (entry.value as? PhpLiteral.Text)?.value?.normalizedPhpName() ?: return null
    }
    return OptionalTextList(entries.toSet())
}

private class PhpLiteralParser(
    private val tokens: List<PhpToken>,
    private var index: Int,
) {
    private var nodes = 0

    fun parseValue(depth: Int = 0): PhpLiteral {
        check(depth <= MAX_PHP_LITERAL_DEPTH) { "The route manifest is nested too deeply." }
        nodes += 1
        check(nodes <= MAX_PHP_LITERAL_NODES) { "The route manifest contains too many values." }
        return when (val token = tokens.getOrNull(index++) ?: error("The route manifest is truncated.")) {
            is PhpToken.Text -> PhpLiteral.Text(token.value)
            is PhpToken.Word -> when {
                token.value == "null" -> PhpLiteral.Null
                (tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(' -> {
                    skipUnsupportedCall()
                    PhpLiteral.Unsupported
                }
                else -> PhpLiteral.Text(token.value)
            }
            is PhpToken.Symbol -> {
                check(token.value == '[') { "Only literal arrays are supported in route metadata." }
                parseSequence(depth + 1)
            }
            PhpToken.Arrow -> error("Unexpected array arrow in route metadata.")
        }
    }

    /**
     * Keeps a manifest parseable when an entry is produced by a PHP helper. The call is consumed
     * as opaque syntax and its route is discarded; no function name, argument, or result is
     * interpreted as trusted metadata.
     */
    private fun skipUnsupportedCall() {
        check((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(')
        var depth = 0
        while (index < tokens.size) {
            val symbol = tokens[index++] as? PhpToken.Symbol ?: continue
            when (symbol.value) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return
                    check(depth >= 0) { "The route helper call is malformed." }
                }
            }
        }
        error("The route helper call is truncated.")
    }

    private fun parseSequence(depth: Int): PhpLiteral.Sequence {
        val entries = mutableListOf<PhpLiteral.Entry>()
        while (true) {
            val next = tokens.getOrNull(index) ?: error("The route manifest array is truncated.")
            if (next is PhpToken.Symbol && next.value == ']') {
                index += 1
                return PhpLiteral.Sequence(entries)
            }
            val first = parseValue(depth)
            val entry = if (tokens.getOrNull(index) == PhpToken.Arrow) {
                index += 1
                val key = (first as? PhpLiteral.Text)?.value
                    ?: error("Route manifest keys must be literal strings.")
                PhpLiteral.Entry(key, parseValue(depth))
            } else {
                PhpLiteral.Entry(null, first)
            }
            entries += entry
            val separator = tokens.getOrNull(index)
            if (separator is PhpToken.Symbol && separator.value == ',') {
                index += 1
            } else if (separator !is PhpToken.Symbol || separator.value != ']') {
                error("Route manifest array entries must be comma separated.")
            }
        }
    }
}
