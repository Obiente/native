package dev.obiente.nextcloudnative.contracts

/**
 * Route attributes on one controller, read as the server router does (v33
 * lib/private/Route/Router.php, getAttributeRoutes): every public method attribute that is an
 * OCP\AppFramework\Http\Attribute\Route, ApiRoute, or FrontpageRoute becomes a route named
 * `{ControllerWithoutSuffix}#{method}`. ApiRoute and `Route(type: 'ocs')` mount under OCS;
 * FrontpageRoute and `Route(type: 'routes')` mount under the app's index routes.
 *
 * Only literal verbs, URLs, requirement and default maps, and postfixes are accepted. A
 * concatenated, interpolated, or constant URL drops the route, never guesses it. A custom `root`
 * is counted in [StaticAttributeRoutes.skippedCustomRootRoutes] and skipped like routes.php roots.
 */
internal data class StaticAttributeRoutes(
    val routes: List<StaticRoute>,
    val skippedCustomRootRoutes: Int,
    val unreadableRoutes: Int,
)

internal fun PhpClassDeclaration.attributeRoutes(): StaticAttributeRoutes {
    if (!name.endsWith("Controller") || name.length == "Controller".length) {
        return StaticAttributeRoutes(emptyList(), 0, 0)
    }
    val controller = name.removeSuffix("Controller")
    var skippedRoot = 0
    var unreadable = 0
    val routes = methods.flatMap { method ->
        method.attributes
            .filter { it.name.startsWith(FRAMEWORK_ATTRIBUTE_NAMESPACE) && it.shortName in ROUTE_ATTRIBUTES }
            .mapNotNull { attribute ->
                if (!method.public || method.static) {
                    unreadable += 1
                    return@mapNotNull null
                }
                when (val parsed = attribute.routeOrNull(controller, method.name)) {
                    AttributeRouteResult.CustomRoot -> {
                        skippedRoot += 1
                        null
                    }
                    AttributeRouteResult.Unreadable -> {
                        unreadable += 1
                        null
                    }
                    is AttributeRouteResult.Route -> parsed.route
                }
            }
    }
    return StaticAttributeRoutes(routes.take(MAX_STATIC_ROUTE_COUNT), skippedRoot, unreadable)
}

private sealed interface AttributeRouteResult {
    data class Route(val route: StaticRoute) : AttributeRouteResult
    data object CustomRoot : AttributeRouteResult
    data object Unreadable : AttributeRouteResult
}

private fun PhpAttribute.routeOrNull(controller: String, method: String): AttributeRouteResult {
    // Route(type, verb, url, ...) takes the type first; ApiRoute and FrontpageRoute fix it.
    val offset = if (shortName == "Route") 1 else 0
    val ocs = when (shortName) {
        "ApiRoute" -> true
        "FrontpageRoute" -> false
        else -> when (val type = argument("type", 0)) {
            is PhpValue.Text -> when (type.value) {
                "ocs" -> true
                "routes" -> false
                else -> return AttributeRouteResult.Unreadable
            }
            is PhpValue.Constant -> when {
                !type.className.endsWith("\\Route") && type.className != "self" && type.className != "static" ->
                    return AttributeRouteResult.Unreadable
                type.constant == "TYPE_API" -> true
                type.constant == "TYPE_FRONTPAGE" -> false
                else -> return AttributeRouteResult.Unreadable
            }
            else -> return AttributeRouteResult.Unreadable
        }
    }
    val verb = (argument("verb", offset) as? PhpValue.Text)?.value?.uppercase()
        ?: return AttributeRouteResult.Unreadable
    val url = (argument("url", offset + 1) as? PhpValue.Text)?.value
        ?: return AttributeRouteResult.Unreadable
    val requirements = argument("requirements", offset + 2).literalMapOrNull() ?: return AttributeRouteResult.Unreadable
    val defaults = argument("defaults", offset + 3).literalMapOrNull() ?: return AttributeRouteResult.Unreadable
    when (argument("root", offset + 4)) {
        null, PhpValue.Null -> Unit
        else -> return AttributeRouteResult.CustomRoot
    }
    val postfix = when (val value = argument("postfix", offset + 5)) {
        null, PhpValue.Null -> null
        is PhpValue.Text -> value.value
        else -> return AttributeRouteResult.Unreadable
    }
    val known = setOf("type", "verb", "url", "requirements", "defaults", "root", "postfix")
    if (arguments.any { it.name != null && it.name !in known } || arguments.size > offset + 6) {
        return AttributeRouteResult.Unreadable
    }
    return AttributeRouteResult.Route(
        StaticRoute(
            controller = controller,
            method = method,
            url = url,
            verb = verb,
            ocs = ocs,
            requirements = requirements,
            defaults = defaults,
            postfix = postfix,
            source = StaticRouteSource.Attribute,
        ),
    )
}

/** Null when the value is not an absent, null, or literal string-keyed map of literals. */
private fun PhpValue?.literalMapOrNull(): Map<String, String>? = when (this) {
    null, PhpValue.Null -> emptyMap()
    is PhpValue.Array -> entries.associate { (key, value) ->
        val name = (key as? PhpValue.Text)?.value ?: return null
        name to (value.literalText() ?: return null)
    }
    else -> null
}
