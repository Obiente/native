package dev.obiente.nextcloudnative.nativeui.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Facts a verified app package states about one operation: where its route was declared, who
 * the server lets call it, CSRF and CORS handling, password confirmation, rate limits,
 * brute-force protection, app-defined attributes, path inputs the controller reads from the
 * request, and the structural merge hint. They are evidence for later admission decisions and
 * for honest UI copy; they never enable an action by themselves.
 */
@Serializable
data class DynamicContractFacts(
    val routeSource: DynamicRouteSource? = null,
    val access: DynamicAccessLevel? = null,
    /** Null when the package did not state it; true unless the method opts out of CSRF checks. */
    val csrfRequired: Boolean? = null,
    val cors: Boolean = false,
    val passwordConfirmation: DynamicPasswordConfirmation? = null,
    val userRateLimit: DynamicRateLimit? = null,
    val anonymousRateLimit: DynamicRateLimit? = null,
    val bruteForceActions: List<String> = emptyList(),
    val appAttributes: List<DynamicOpaqueAttribute> = emptyList(),
    /** All non-path inputs are nullable with null defaults: a merge hint, not a merge guarantee. */
    val mergeHint: Boolean = false,
    /** Path placeholders the controller reads with `getParam` rather than its signature. */
    val requestPathParameters: List<String> = emptyList(),
    /** True when the method docblock was too large to read, so legacy annotations are unknown. */
    val annotationsUnreadable: Boolean = false,
)

@Serializable
enum class DynamicRouteSource {
    routesFile,
    attribute,
}

/** Server access rule derived from method attributes and annotations. */
@Serializable
enum class DynamicAccessLevel {
    publicPage,
    authenticatedUser,
    subAdmin,
    delegatedAdmin,
    admin,
    externalApp,
}

@Serializable
data class DynamicPasswordConfirmation(val strict: Boolean)

@Serializable
data class DynamicRateLimit(val limit: Long, val periodSeconds: Long)

@Serializable
data class DynamicOpaqueAttribute(val name: String, val arguments: Map<String, String> = emptyMap())

/**
 * Reads the static fact extensions of [operation]. Only signed package or exact linked source
 * provenance may state them; a same-origin advertisement cannot assert its own access rules.
 * Malformed or oversized values are dropped individually.
 */
internal fun staticContractFacts(operation: JsonObject, source: Provenance): DynamicContractFacts? {
    if (source.kind != ProvenanceKind.verifiedAppPackage && source.kind != ProvenanceKind.appStoreLinkedSourceTag) {
        return null
    }
    val policy = operation[POLICY_EXTENSION] as? JsonObject
    val routeSource = when (operation.text(ROUTE_SOURCE_EXTENSION)) {
        "routes-file" -> DynamicRouteSource.routesFile
        "attribute" -> DynamicRouteSource.attribute
        else -> null
    }
    val requestPathParameters = (operation["parameters"] as? JsonArray).orEmpty().mapNotNull { element ->
        val parameter = element as? JsonObject ?: return@mapNotNull null
        parameter.text("name")?.takeIf {
            parameter.text("in") == "path" && parameter.text(REQUEST_INPUT_EXTENSION) == "request-parameter"
        }
    }.filter(String::isBoundedFact).take(MAX_FACT_ENTRIES)
    val mergeHint = operation.text(MERGE_HINT_EXTENSION) == "nullable-null-default-parameters"
    if (policy == null && routeSource == null && requestPathParameters.isEmpty() && !mergeHint) return null
    return DynamicContractFacts(
        routeSource = routeSource,
        access = when (policy?.text("access")) {
            "public" -> DynamicAccessLevel.publicPage
            "user" -> DynamicAccessLevel.authenticatedUser
            "subadmin" -> DynamicAccessLevel.subAdmin
            "delegated-admin" -> DynamicAccessLevel.delegatedAdmin
            "admin" -> DynamicAccessLevel.admin
            "ex-app" -> DynamicAccessLevel.externalApp
            else -> null
        },
        csrfRequired = policy?.flag("csrfRequired"),
        cors = policy?.flag("cors") == true,
        passwordConfirmation = (policy?.get("passwordConfirmation") as? JsonObject)?.let { confirmation ->
            DynamicPasswordConfirmation(strict = confirmation.flag("strict") == true)
        },
        userRateLimit = policy?.rateLimit("userRateLimit"),
        anonymousRateLimit = policy?.rateLimit("anonRateLimit"),
        bruteForceActions = policy?.texts("bruteForceActions").orEmpty(),
        appAttributes = (policy?.get("appAttributes") as? JsonArray).orEmpty().mapNotNull { element ->
            val attribute = element as? JsonObject ?: return@mapNotNull null
            val name = attribute.text("name")?.takeIf(String::isBoundedFact) ?: return@mapNotNull null
            val arguments = (attribute["arguments"] as? JsonObject).orEmpty().entries
                .mapNotNull { (key, value) ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    if (key.isBoundedFact() && text?.isBoundedFact() == true) key to text else null
                }
                .take(MAX_FACT_ENTRIES)
                .toMap()
            DynamicOpaqueAttribute(name, arguments)
        }.take(MAX_FACT_ENTRIES),
        mergeHint = mergeHint,
        requestPathParameters = requestPathParameters,
        annotationsUnreadable = policy?.flag("annotationsUnreadable") == true,
    )
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.texts(key: String): List<String> = (get(key) as? JsonArray).orEmpty().mapNotNull { element ->
    (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isBoundedFact)
}.take(MAX_FACT_ENTRIES)

private fun JsonObject.rateLimit(key: String): DynamicRateLimit? {
    val limit = get(key) as? JsonObject ?: return null
    val count = (limit["limit"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
    val period = (limit["period"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
    return DynamicRateLimit(count, period)
}

private fun String.isBoundedFact(): Boolean = isNotEmpty() && length <= MAX_FACT_CHARACTERS && all { it.code in 0x20..0x7e }

private const val POLICY_EXTENSION = "x-nextcloud-native-policy"
private const val ROUTE_SOURCE_EXTENSION = "x-nextcloud-native-route-source"
private const val MERGE_HINT_EXTENSION = "x-nextcloud-native-merge-hint"
private const val REQUEST_INPUT_EXTENSION = "x-nextcloud-native-request-input"
private const val MAX_FACT_ENTRIES = 16
private const val MAX_FACT_CHARACTERS = 256
