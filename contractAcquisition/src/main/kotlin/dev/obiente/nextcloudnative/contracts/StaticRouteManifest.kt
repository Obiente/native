package dev.obiente.nextcloudnative.contracts

/** Where a static route was declared in the signed package. */
internal enum class StaticRouteSource { RouteFile, Attribute }

internal data class StaticRoute(
    val controller: String,
    val method: String,
    val url: String,
    val verb: String,
    val ocs: Boolean,
    val requirements: Map<String, String>,
    /** Literal `defaults` values, keyed by placeholder or parameter name. */
    val defaults: Map<String, String> = emptyMap(),
    /** Route-name postfix; it distinguishes repeated routes and never changes the URL. */
    val postfix: String? = null,
    val source: StaticRouteSource = StaticRouteSource.RouteFile,
    /** True for an action expanded from a `resources` or `ocs-resources` declaration. */
    val resourceAction: Boolean = false,
)

/** Route entries the manifest declared but the extractor could not represent exactly. */
internal data class StaticRouteManifest(
    val routes: List<StaticRoute>,
    val skippedCustomRootRoutes: Int = 0,
)

private sealed interface PhpLiteral {
    data class Text(val value: String) : PhpLiteral
    data class Sequence(val entries: List<Entry>) : PhpLiteral
    data class Variable(val name: String) : PhpLiteral
    data object Null : PhpLiteral
    data object Unsupported : PhpLiteral

    data class Entry(val key: String?, val value: PhpLiteral)
}

internal fun parseRouteManifest(source: String): List<StaticRoute>? = parseRouteManifestDetails(source)?.routes

/**
 * Mirrors the route groups the Nextcloud router reads from appinfo/routes.php: `routes`, `ocs`,
 * `resources`, and `ocs-resources`. Resource entries expand to the router's five conventional
 * actions. A route that declares a custom `root` is skipped and counted because the router only
 * honors that key for selected apps and the result would leave the app's own URL namespace.
 */
internal fun parseRouteManifestDetails(source: String): StaticRouteManifest? = runCatching {
    val tokens = PhpLiteralLexer(source).tokens()
    val helpers = routeHelperTemplates(tokens)
    val root = tokens.indices
        .filter { index -> (tokens[index] as? PhpToken.Word)?.value == "return" }
        .asReversed()
        .mapNotNull { returnIndex ->
            runCatching {
                PhpLiteralParser(tokens, returnIndex + 1, helpers).parseValue() as? PhpLiteral.Sequence
            }.getOrNull()
        }
        .firstOrNull { candidate ->
            ROUTE_GROUPS.any { (name, _) -> candidate.named(name) is PhpLiteral.Sequence } ||
                RESOURCE_GROUPS.any { (name, _) -> candidate.named(name) is PhpLiteral.Sequence }
        }
        ?: return null
    var skippedCustomRoot = 0
    val routes = buildList {
        ROUTE_GROUPS.forEach { (groupName, ocs) ->
            val group = root.named(groupName) as? PhpLiteral.Sequence ?: return@forEach
            group.entries.take(MAX_STATIC_ROUTE_COUNT).forEach routeLoop@ { entry ->
                val route = entry.value as? PhpLiteral.Sequence ?: return@routeLoop
                val name = route.text("name") ?: return@routeLoop
                val separator = name.indexOf('#')
                if (separator <= 0 || separator != name.lastIndexOf('#') || separator == name.lastIndex) {
                    return@routeLoop
                }
                val url = route.text("url") ?: return@routeLoop
                // The router defaults an omitted verb to GET.
                val verb = if (route.named("verb") == null) "GET" else route.text("verb")?.uppercase() ?: return@routeLoop
                if (route.named("root") != null) {
                    skippedCustomRoot += 1
                    return@routeLoop
                }
                add(
                    StaticRoute(
                        controller = name.substring(0, separator),
                        method = name.substring(separator + 1),
                        url = url,
                        verb = verb,
                        ocs = ocs,
                        requirements = route.textMap("requirements"),
                        defaults = route.textMap("defaults"),
                        postfix = route.text("postfix"),
                    ),
                )
            }
        }
        RESOURCE_GROUPS.forEach { (groupName, ocs) ->
            val resources = root.named(groupName) as? PhpLiteral.Sequence ?: return@forEach
            resources.entries.take(MAX_STATIC_ROUTE_COUNT).forEach resourceLoop@ { entry ->
                val controller = entry.key ?: return@resourceLoop
                val resource = entry.value as? PhpLiteral.Sequence ?: return@resourceLoop
                val url = resource.text("url") ?: return@resourceLoop
                if (resource.named("root") != null) {
                    skippedCustomRoot += RESOURCE_ACTIONS.size
                    return@resourceLoop
                }
                val only = resource.optionalTextList("only") ?: return@resourceLoop
                val except = resource.optionalTextList("except") ?: return@resourceLoop
                RESOURCE_ACTIONS.forEach { action ->
                    val enabled = (only.values == null || action.method in only.values) &&
                        action.method !in except.values.orEmpty()
                    if (!enabled) return@forEach
                    add(
                        StaticRoute(
                            controller = controller,
                            method = action.method,
                            url = if (action.onCollection) url else "${url.trimEnd('/')}/{id}",
                            verb = action.verb,
                            ocs = ocs,
                            requirements = emptyMap(),
                            resourceAction = true,
                        ),
                    )
                }
            }
        }
    }
    StaticRouteManifest(routes, skippedCustomRoot)
}.getOrNull()

private data class ResourceAction(val method: String, val verb: String, val onCollection: Boolean)

/** The Nextcloud router's resource expansion (lib/private/Route/RouteParser.php). */
private val RESOURCE_ACTIONS = listOf(
    ResourceAction("index", "GET", onCollection = true),
    ResourceAction("show", "GET", onCollection = false),
    ResourceAction("create", "POST", onCollection = true),
    ResourceAction("update", "PUT", onCollection = false),
    ResourceAction("destroy", "DELETE", onCollection = false),
)
private val ROUTE_GROUPS = listOf("routes" to false, "ocs" to true)
private val RESOURCE_GROUPS = listOf("resources" to false, "ocs-resources" to true)

private fun PhpLiteral.Sequence.named(name: String): PhpLiteral? =
    entries.firstOrNull { entry -> entry.key == name }?.value

private fun PhpLiteral.Sequence.text(name: String): String? = (named(name) as? PhpLiteral.Text)?.value

private fun PhpLiteral.Sequence.textMap(name: String): Map<String, String> =
    (named(name) as? PhpLiteral.Sequence)
        ?.entries
        ?.mapNotNull { entry ->
            val key = entry.key ?: return@mapNotNull null
            val value = (entry.value as? PhpLiteral.Text)?.value ?: return@mapNotNull null
            key to value
        }
        ?.toMap()
        .orEmpty()

private data class OptionalTextList(val values: Set<String>?)

private fun PhpLiteral.Sequence.optionalTextList(name: String): OptionalTextList? {
    val value = named(name) ?: return OptionalTextList(null)
    val sequence = value as? PhpLiteral.Sequence ?: return null
    val entries = sequence.entries.map { entry ->
        (entry.value as? PhpLiteral.Text)?.value?.normalizedPhpName() ?: return null
    }
    return OptionalTextList(entries.toSet())
}

/**
 * A route helper declared in the same routes.php whose complete body is `return $route;` or
 * `return array_merge($route, [...]);`. The merged literal may set only keys that cannot change
 * the route's identity (name, URL, verb, root, or postfix), so unwrapping a call never invents an
 * endpoint. Values may reference the helper's other parameters, bound to the call's literal
 * arguments. Any other helper stays opaque and its route is discarded.
 */
private data class RouteHelperTemplate(
    val parameters: List<String>,
    val merged: PhpLiteral.Sequence?,
)

private fun routeHelperTemplates(tokens: List<PhpToken>): Map<String, RouteHelperTemplate> {
    val templates = linkedMapOf<String, RouteHelperTemplate>()
    tokens.indices.forEach { index ->
        if ((tokens[index] as? PhpToken.Word)?.value != "function") return@forEach
        val name = (tokens.getOrNull(index + 1) as? PhpToken.Word)?.value ?: return@forEach
        if (templates.size >= MAX_ROUTE_HELPERS) return@forEach
        runCatching { parseRouteHelper(tokens, index + 2) }.getOrNull()?.let { templates[name.lowercase()] = it }
    }
    return templates
}

private fun parseRouteHelper(tokens: List<PhpToken>, start: Int): RouteHelperTemplate? {
    var index = start
    fun symbol(value: Char): Boolean = ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == value).also {
        if (it) index += 1
    }
    fun variable(): String? {
        if (!symbol('$')) return null
        return (tokens.getOrNull(index++) as? PhpToken.Word)?.value
    }
    if (!symbol('(')) return null
    val parameters = mutableListOf<String>()
    while (!symbol(')')) {
        // Optional scalar or array type declarations before the variable are allowed.
        while (tokens.getOrNull(index) is PhpToken.Word) index += 1
        parameters += variable() ?: return null
        if (!symbol(',') && (tokens.getOrNull(index) as? PhpToken.Symbol)?.value != ')') return null
        if (parameters.size > 8) return null
    }
    if (parameters.isEmpty()) return null
    if (symbol(':')) {
        if (tokens.getOrNull(index) !is PhpToken.Word) return null
        index += 1
    }
    if (!symbol('{')) return null
    if ((tokens.getOrNull(index++) as? PhpToken.Word)?.value != "return") return null
    val template = if ((tokens.getOrNull(index) as? PhpToken.Word)?.value == "array_merge") {
        index += 1
        if (!symbol('(')) return null
        if (variable() != parameters.first()) return null
        if (!symbol(',')) return null
        val parser = PhpLiteralParser(tokens, index, emptyMap(), allowVariables = true)
        val merged = parser.parseValue() as? PhpLiteral.Sequence ?: return null
        index = parser.position
        if (!symbol(')')) return null
        if (merged.entries.any { entry -> entry.key == null || entry.key in ROUTE_IDENTITY_KEYS }) return null
        RouteHelperTemplate(parameters, merged)
    } else {
        if (variable() != parameters.first()) return null
        RouteHelperTemplate(parameters, merged = null)
    }
    if (!symbol(';') || !symbol('}')) return null
    return template
}

private fun PhpLiteral.bind(bindings: Map<String, PhpLiteral>): PhpLiteral = when (this) {
    is PhpLiteral.Variable -> bindings[name] ?: PhpLiteral.Unsupported
    is PhpLiteral.Sequence -> PhpLiteral.Sequence(
        entries.map { entry ->
            val key = entry.key?.let { raw ->
                if (raw.startsWith(VARIABLE_KEY_PREFIX)) {
                    (bindings[raw.removePrefix(VARIABLE_KEY_PREFIX)] as? PhpLiteral.Text)?.value ?: return PhpLiteral.Unsupported
                } else {
                    raw
                }
            }
            PhpLiteral.Entry(key, entry.value.bind(bindings))
        },
    )
    else -> this
}

private class PhpLiteralParser(
    private val tokens: List<PhpToken>,
    private var index: Int,
    private val helpers: Map<String, RouteHelperTemplate>,
    private val allowVariables: Boolean = false,
) {
    private var nodes = 0
    val position: Int get() = index

    fun parseValue(depth: Int = 0): PhpLiteral {
        check(depth <= MAX_PHP_LITERAL_DEPTH) { "The route manifest is nested too deeply." }
        nodes += 1
        check(nodes <= MAX_PHP_LITERAL_NODES) { "The route manifest contains too many values." }
        return when (val token = tokens.getOrNull(index++) ?: error("The route manifest is truncated.")) {
            // An interpolated string or heredoc is never a constant route value.
            is PhpToken.Text -> if (token.literal) PhpLiteral.Text(token.value) else PhpLiteral.Unsupported
            is PhpToken.Word -> when {
                token.value == "null" -> PhpLiteral.Null
                (tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(' -> parseCall(token.value, depth)
                else -> PhpLiteral.Text(token.value)
            }
            is PhpToken.Symbol -> when {
                allowVariables && token.value == '$' -> {
                    val name = (tokens.getOrNull(index++) as? PhpToken.Word)?.value
                        ?: error("The route helper variable is malformed.")
                    PhpLiteral.Variable(name)
                }
                else -> {
                    check(token.value == '[') { "Only literal arrays are supported in route metadata." }
                    parseSequence(depth + 1)
                }
            }
            PhpToken.Arrow -> error("Unexpected array arrow in route metadata.")
            PhpToken.AttributeOpen, is PhpToken.DocComment -> error("Unexpected declaration token in route metadata.")
        }
    }

    /**
     * Unwraps a call to a recognized same-file route helper whose arguments are all literals.
     * Any other call is consumed as opaque syntax and its route is discarded; no function name,
     * argument, or result is interpreted as trusted metadata.
     */
    private fun parseCall(name: String, depth: Int): PhpLiteral {
        val template = helpers[name.lowercase()] ?: return skipUnsupportedCall()
        val start = index
        val unwrapped = runCatching {
            index += 1
            val arguments = mutableListOf<PhpLiteral>()
            while ((tokens.getOrNull(index) as? PhpToken.Symbol)?.value != ')') {
                arguments += parseValue(depth + 1)
                val separator = tokens.getOrNull(index) as? PhpToken.Symbol
                if (separator?.value == ',') index += 1 else check(separator?.value == ')')
            }
            index += 1
            val base = arguments.firstOrNull() as? PhpLiteral.Sequence ?: return@runCatching null
            if (arguments.size != template.parameters.size) return@runCatching null
            if (arguments.drop(1).any { it !is PhpLiteral.Text }) return@runCatching null
            val merged = template.merged?.bind(template.parameters.zip(arguments).toMap()) as? PhpLiteral.Sequence
            when {
                template.merged == null -> base
                merged == null -> null
                else -> PhpLiteral.Sequence(
                    base.entries.filterNot { entry -> merged.entries.any { it.key == entry.key } } + merged.entries,
                )
            }
        }.getOrNull()
        if (unwrapped != null) return unwrapped
        index = start
        return skipUnsupportedCall()
    }

    private fun skipUnsupportedCall(): PhpLiteral {
        check((tokens.getOrNull(index) as? PhpToken.Symbol)?.value == '(')
        var depth = 0
        while (index < tokens.size) {
            val symbol = tokens[index++] as? PhpToken.Symbol ?: continue
            when (symbol.value) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return PhpLiteral.Unsupported
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
                val key = when (first) {
                    is PhpLiteral.Text -> first.value
                    is PhpLiteral.Variable -> VARIABLE_KEY_PREFIX + first.name
                    else -> error("Route manifest keys must be literal strings.")
                }
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

private val ROUTE_IDENTITY_KEYS = setOf("name", "url", "verb", "root", "postfix")

/** Marks a helper-template key bound from a parameter; `$` cannot start a literal PHP key. */
private const val VARIABLE_KEY_PREFIX = "$"
private const val MAX_ROUTE_HELPERS = 16
