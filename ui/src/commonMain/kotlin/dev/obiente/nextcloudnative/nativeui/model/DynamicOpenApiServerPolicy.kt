package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.template.scanBracedTemplate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private data class NormalizedOpenApiServer(
    val pathBase: String,
    val requiresTrustedRebase: Boolean,
    val original: String,
)

internal fun openApiServerBase(
    document: JsonObject,
    origin: String,
    allowTrustedRebase: Boolean,
): String {
    val base = declaredOpenApiServerBase(document, origin, allowTrustedRebase)
    fun validateOverride(owner: JsonObject) {
        if ((owner["servers"] as? JsonArray)?.isEmpty() == true || "servers" !in owner) return
        require(declaredOpenApiServerBase(owner, origin, allowTrustedRebase) == base) {
            "OpenAPI server overrides with different path bases are unsupported"
        }
    }
    (document["paths"] as? JsonObject)?.values?.forEach { path ->
        val item = path as? JsonObject ?: return@forEach
        validateOverride(item)
        item.filterKeys { it in setOf("get", "put", "post", "delete", "options", "head", "patch", "trace") }
            .values.forEach { operation -> (operation as? JsonObject)?.let(::validateOverride) }
    }
    return base
}

private fun declaredOpenApiServerBase(
    document: JsonObject,
    origin: String,
    allowTrustedRebase: Boolean,
): String {
    val declared = document["servers"] ?: return ""
    require(declared is JsonArray) { "OpenAPI servers must be an array" }
    val servers = declared
        .map { server ->
            val url = (server as? JsonObject)?.get("url") as? JsonPrimitive
            require(url != null && url.isString) { "OpenAPI server URL must be a string" }
            url.content
        }
        .distinct()
    if (servers.isEmpty()) return ""
    if (servers.size == 1) {
        val value = servers.single()
        require(!value.contains('?') && !value.contains('#')) {
            "Templated or qualified OpenAPI server URL is unsupported: $value"
        }
        return normalizeOpenApiServer(value, origin).also { normalized ->
            require(!normalized.requiresTrustedRebase || allowTrustedRebase) { "Cross-origin OpenAPI server: $value" }
        }.pathBase
    }

    val normalized = servers.map { value -> normalizeOpenApiServer(value, origin) }
    val pathBases = normalized.map(NormalizedOpenApiServer::pathBase).distinct().sorted()
    require(pathBases.size == 1) {
        "OpenAPI server entries resolve to conflicting path bases: ${pathBases.joinToString()}"
    }
    val rebased = normalized.filter(NormalizedOpenApiServer::requiresTrustedRebase)
    require(rebased.isEmpty() || allowTrustedRebase) {
        "OpenAPI server entries require cross-origin or templated rebasing: " +
            rebased.joinToString { server -> server.original }
    }
    return pathBases.single()
}

private fun normalizeOpenApiServer(value: String, origin: String): NormalizedOpenApiServer {
    require(value.isNotBlank() && value.length <= 4_096 && value.none { it.isWhitespace() || it.isISOControl() } &&
        !value.contains('?') && !value.contains('#') && !value.contains('\\')) {
        "OpenAPI server URL is unsupported: $value"
    }
    if (value.startsWith('/') && !value.startsWith("//")) {
        require(value.none { it == '{' || it == '}' } && value.isSafeRelativePath()) {
            "OpenAPI server path is unsupported: $value"
        }
        return NormalizedOpenApiServer(value.trimEnd('/'), false, value)
    }

    val separator = value.indexOf("://")
    require(separator > 0) { "OpenAPI server URL is unsupported: $value" }
    require(value.substring(0, separator).lowercase() in setOf("http", "https")) {
        "OpenAPI server scheme is unsupported: $value"
    }
    val authorityStart = separator + 3
    val pathStart = value.indexOf('/', authorityStart)
    val authority = if (pathStart < 0) value.substring(authorityStart) else value.substring(authorityStart, pathStart)
    require(authority.isNotBlank() && !authority.contains('@') && authority.none(Char::isWhitespace)) {
        "OpenAPI server authority is unsupported: $value"
    }
    val path = if (pathStart < 0) "" else value.substring(pathStart)
    require(path.none { it == '{' || it == '}' } && (path.isEmpty() || path.isSafeRelativePath())) {
        "OpenAPI server path is unsupported: $value"
    }
    val declaredOrigin = value.substring(0, authorityStart) + authority
    val sameOrigin = !declaredOrigin.contains('{') &&
        declaredOrigin.normalizedHttpOrigin() == origin.normalizedHttpOrigin()
    require(sameOrigin || authority.isOpenApiHostTemplate()) {
        "Concrete cross-origin OpenAPI server rebasing is unsupported: $value"
    }
    return NormalizedOpenApiServer(path.trimEnd('/'), !sameOrigin, value)
}

private fun String.isOpenApiHostTemplate(): Boolean {
    fun String.isVariable(): Boolean {
        val scan = scanBracedTemplate()
        val token = scan.tokens.singleOrNull() ?: return false
        return !scan.malformed && token.startIndex == 0 && token.endIndexExclusive == length &&
            token.name.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))
    }
    if (!substringBefore(':').isVariable()) return false
    if (':' !in this) return true
    val port = substringAfter(':')
    return port.isVariable() || port.length in 1..5 && port.all { it in '0'..'9' }
}

private fun String.normalizedHttpOrigin(): String = lowercase().trimEnd('/').let {
    when {
        it.startsWith("https://") -> it.removeSuffix(":443")
        it.startsWith("http://") -> it.removeSuffix(":80")
        else -> it
    }
}
