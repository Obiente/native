package dev.obiente.nextcloudnative.contracts

import org.json.JSONArray
import org.json.JSONObject

/**
 * Who the server's SecurityMiddleware lets call a controller method, derived only from the
 * method's attributes and legacy annotations (server v33 lib/private/AppFramework/Middleware/
 * Security/SecurityMiddleware.php). Without NoAdminRequired, SubAdminRequired, PublicPage,
 * ExAppRequired, or AuthorizedAdminSetting the method is admin-only.
 */
internal enum class StaticAccessLevel(val wireName: String) {
    PublicPage("public"),
    AuthenticatedUser("user"),
    SubAdmin("subadmin"),
    DelegatedAdmin("delegated-admin"),
    Admin("admin"),
    ExternalApp("ex-app"),
}

internal data class StaticRateLimit(val limit: Long, val periodSeconds: Long)

/** An app-defined or unrecognized attribute, kept as its name and literal arguments only. */
internal data class StaticOpaqueAttribute(val name: String, val arguments: Map<String, String>)

internal data class StaticOperationPolicy(
    val access: StaticAccessLevel,
    val csrfRequired: Boolean,
    val cors: Boolean,
    /** Non-null when the method requires password confirmation; strict demands a fresh login. */
    val passwordConfirmationStrict: Boolean?,
    val userRateLimit: StaticRateLimit?,
    val anonRateLimit: StaticRateLimit?,
    val bruteForceActions: List<String>,
    val useSession: Boolean,
    val strictCookiesRequired: Boolean,
    val requestHeaders: List<String>,
    val openApiScope: String?,
    val appAttributes: List<StaticOpaqueAttribute>,
    /** True when the docblock was too large to read, so legacy annotations are unknown. */
    val annotationsUnreadable: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("access", access.wireName)
        .put("csrfRequired", csrfRequired)
        .put("cors", cors)
        .also { json ->
            passwordConfirmationStrict?.let { json.put("passwordConfirmation", JSONObject().put("strict", it)) }
            userRateLimit?.let { json.put("userRateLimit", it.toJson()) }
            anonRateLimit?.let { json.put("anonRateLimit", it.toJson()) }
            if (bruteForceActions.isNotEmpty()) json.put("bruteForceActions", JSONArray(bruteForceActions))
            if (useSession) json.put("useSession", true)
            if (strictCookiesRequired) json.put("strictCookiesRequired", true)
            if (requestHeaders.isNotEmpty()) json.put("requestHeaders", JSONArray(requestHeaders))
            openApiScope?.let { json.put("openApiScope", it) }
            if (appAttributes.isNotEmpty()) {
                json.put(
                    "appAttributes",
                    JSONArray(
                        appAttributes.map { attribute ->
                            JSONObject().put("name", attribute.name).put("arguments", JSONObject(attribute.arguments))
                        },
                    ),
                )
            }
            if (annotationsUnreadable) json.put("annotationsUnreadable", true)
        }

    private fun StaticRateLimit.toJson(): JSONObject = JSONObject().put("limit", limit).put("period", periodSeconds)
}

internal fun staticOperationPolicy(method: PhpMethodDeclaration): StaticOperationPolicy {
    val framework = method.attributes.filter { it.name.startsWith(FRAMEWORK_ATTRIBUTE_NAMESPACE) }
    val known = framework.filter { it.shortName in KNOWN_FRAMEWORK_ATTRIBUTES }
    // Legacy annotations count only in the form the server's reflector reads them: an indented
    // `*` line with a tag that starts with an uppercase letter, matched case-sensitively.
    val serverAnnotations = method.docComment?.serverAnnotationNames().orEmpty()
    val annotations = method.docComment?.tags().orEmpty()
        .filter { tag -> tag.name in serverAnnotations }
        .associateBy { it.name.lowercase() }
    fun has(attribute: String, annotation: String? = attribute): Boolean =
        known.any { it.shortName == attribute } || (annotation != null && annotation in serverAnnotations)

    val publicPage = has("PublicPage")
    val access = when {
        publicPage -> StaticAccessLevel.PublicPage
        has("ExAppRequired", annotation = null) -> StaticAccessLevel.ExternalApp
        has("SubAdminRequired") -> StaticAccessLevel.SubAdmin
        has("NoAdminRequired") -> StaticAccessLevel.AuthenticatedUser
        has("AuthorizedAdminSetting", annotation = null) -> StaticAccessLevel.DelegatedAdmin
        else -> StaticAccessLevel.Admin
    }
    val passwordAttribute = known.firstOrNull { it.shortName == "PasswordConfirmationRequired" }
    val passwordConfirmationStrict = when {
        passwordAttribute != null -> (passwordAttribute.argument("strict", 0) as? PhpValue.Bool)?.value ?: false
        "passwordconfirmationrequired" in annotations -> false
        else -> null
    }
    return StaticOperationPolicy(
        access = access,
        csrfRequired = !has("NoCSRFRequired"),
        cors = has("CORS"),
        passwordConfirmationStrict = passwordConfirmationStrict,
        userRateLimit = rateLimit(known, "UserRateLimit", annotations["userratethrottle"]),
        anonRateLimit = rateLimit(known, "AnonRateLimit", annotations["anonratethrottle"]),
        bruteForceActions = (
            known.filter { it.shortName == "BruteForceProtection" }
                .mapNotNull { (it.argument("action", 0) as? PhpValue.Text)?.value } +
                method.docComment?.tags().orEmpty()
                    .filter { it.name.equals("BruteForceProtection", ignoreCase = true) }
                    .mapNotNull { annotationArguments(it.content)["action"] }
            )
            .filter(String::isSafeFactValue)
            .distinct()
            .take(MAX_POLICY_LIST_ENTRIES),
        useSession = has("UseSession"),
        strictCookiesRequired = has("StrictCookiesRequired", annotation = "StrictCookieRequired"),
        requestHeaders = known.filter { it.shortName == "RequestHeader" }
            .mapNotNull { (it.argument("name", 0) as? PhpValue.Text)?.value }
            .filter(String::isSafeFactValue)
            .distinct()
            .take(MAX_POLICY_LIST_ENTRIES),
        openApiScope = known.firstOrNull { it.shortName == "OpenAPI" }?.let { attribute ->
            when (val scope = attribute.argument("scope", 0)) {
                is PhpValue.Text -> scope.value
                is PhpValue.Constant -> scope.constant.takeIf { scope.className.endsWith("\\OpenAPI") }
                    ?.let(OPEN_API_SCOPE_CONSTANTS::get)
                else -> null
            }?.takeIf(String::isSafeFactValue)
        } ?: if (known.any { it.shortName == "IgnoreOpenAPI" }) "ignore" else null,
        appAttributes = method.attributes
            .filterNot { it.shortName in KNOWN_FRAMEWORK_ATTRIBUTES && it.name.startsWith(FRAMEWORK_ATTRIBUTE_NAMESPACE) }
            .filterNot { it.name.startsWith(FRAMEWORK_ATTRIBUTE_NAMESPACE) && it.shortName in ROUTE_ATTRIBUTES }
            .take(MAX_POLICY_LIST_ENTRIES)
            .map { attribute ->
                StaticOpaqueAttribute(
                    name = attribute.name.take(MAX_FACT_VALUE_CHARS),
                    arguments = attribute.arguments.withIndex().mapNotNull { (position, argument) ->
                        val value = when (val raw = argument.value) {
                            is PhpValue.Constant -> "${raw.className}::${raw.constant}"
                            else -> raw.literalText()
                        }?.takeIf(String::isSafeFactValue) ?: return@mapNotNull null
                        (argument.name ?: "#$position") to value
                    }.take(MAX_POLICY_LIST_ENTRIES).toMap(),
                )
            },
        annotationsUnreadable = method.docComment?.complete == false,
    )
}

private fun rateLimit(known: List<PhpAttribute>, attribute: String, annotation: PhpDocTag?): StaticRateLimit? {
    known.firstOrNull { it.shortName == attribute }?.let { declared ->
        val limit = (declared.argument("limit", 0) as? PhpValue.Integer)?.value
        val period = (declared.argument("period", 1) as? PhpValue.Integer)?.value
        return if (limit != null && period != null && limit > 0 && period > 0) StaticRateLimit(limit, period) else null
    }
    val arguments = annotation?.let { annotationArguments(it.content) } ?: return null
    val limit = arguments["limit"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val period = arguments["period"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    return StaticRateLimit(limit, period)
}

/** Reads `(key=value, key2=value2)` legacy annotation arguments; quotes around values are removed. */
private fun annotationArguments(content: String): Map<String, String> {
    val inner = content.trim().takeIf { it.startsWith('(') }?.substringAfter('(')?.substringBefore(')') ?: return emptyMap()
    return ANNOTATION_ARGUMENT.findAll(inner).take(MAX_POLICY_LIST_ENTRIES).associate { match ->
        match.groupValues[1] to match.groupValues[2].trim('"', '\'')
    }
}

internal fun String.isSafeFactValue(): Boolean =
    isNotEmpty() && length <= MAX_FACT_VALUE_CHARS && all { it.code in 0x20..0x7e }

internal const val FRAMEWORK_ATTRIBUTE_NAMESPACE = "OCP\\AppFramework\\Http\\Attribute\\"
internal val ROUTE_ATTRIBUTES = setOf("Route", "ApiRoute", "FrontpageRoute")
private val KNOWN_FRAMEWORK_ATTRIBUTES = setOf(
    "NoAdminRequired", "SubAdminRequired", "PublicPage", "NoCSRFRequired", "CORS", "PasswordConfirmationRequired",
    "UserRateLimit", "AnonRateLimit", "BruteForceProtection", "UseSession", "StrictCookiesRequired",
    "ExAppRequired", "AuthorizedAdminSetting", "RequestHeader", "OpenAPI", "IgnoreOpenAPI",
)

/** OCP\AppFramework\Http\Attribute\OpenAPI scope constants. */
private val OPEN_API_SCOPE_CONSTANTS = mapOf(
    "SCOPE_DEFAULT" to "default",
    "SCOPE_ADMINISTRATION" to "administration",
    "SCOPE_FEDERATION" to "federation",
    "SCOPE_IGNORE" to "ignore",
    "SCOPE_EX_APP" to "ex_app",
)
private val ANNOTATION_ARGUMENT = Regex("""([A-Za-z_][A-Za-z0-9_]{0,63})\s*=\s*("[^"]*"|'[^']*'|[^,\s]+)""")
private const val MAX_POLICY_LIST_ENTRIES = 16
private const val MAX_FACT_VALUE_CHARS = 256

/**
 * Methods that explicitly demand sub-administrator, delegated administrator, or external-app
 * access, or password confirmation, need an authenticated handoff, so they are never published
 * as dynamic writes. Administrator access inferred from a missing `NoAdminRequired` stays a
 * recorded fact on the operation for the descriptor's admission step: an inherited or trait
 * annotation this extractor cannot see would otherwise hide a user-level write. A method whose
 * declaration could not be read keeps the earlier behavior and is judged by its signature.
 */
internal fun StaticMethodEvidence?.allowsDynamicWrite(): Boolean {
    val policy = this?.policy ?: return true
    return policy.access !in HANDOFF_ACCESS && policy.passwordConfirmationStrict == null
}

private val HANDOFF_ACCESS = setOf(
    StaticAccessLevel.SubAdmin,
    StaticAccessLevel.DelegatedAdmin,
    StaticAccessLevel.ExternalApp,
)

/** Server rule (ControllerMethodReflector): `^\h+\*\h+@(?P<annotation>[A-Z]\w+)`. */
private val SERVER_ANNOTATION_LINE = Regex("""^[ \t]+\*[ \t]+@([A-Z]\w+)""")

private fun PhpToken.DocComment.serverAnnotationNames(): Set<String> =
    if (!complete) {
        emptySet()
    } else {
        text.lineSequence().mapNotNull { line -> SERVER_ANNOTATION_LINE.find(line)?.groupValues?.get(1) }.toSet()
    }
