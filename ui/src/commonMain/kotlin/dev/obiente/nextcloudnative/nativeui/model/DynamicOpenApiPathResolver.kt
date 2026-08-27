package dev.obiente.nextcloudnative.nativeui.model

import dev.obiente.nextcloudnative.template.replaceBracedTemplateTokens

internal fun resolveOpenApiPath(base: String, path: String, policy: EndpointPolicy): String =
    path.withServerApiVersionDefault(base).let { resolved ->
        if (resolved.isApproved(policy)) resolved else combineOpenApiPaths(base, resolved)
    }

private fun combineOpenApiPaths(base: String, path: String): String =
    "${base.trimEnd('/')}/${path.trimStart('/')}"

internal fun String.isApproved(policy: EndpointPolicy): Boolean =
    isSafeRelativePath() && policy.approvedApiPrefixes.any(::matchesPrefix)

private fun String.withServerApiVersionDefault(base: String): String {
    val segments = base.trim('/').split('/')
    val apiIndex = segments.indexOfLast { segment -> segment.equals("api", ignoreCase = true) }
    val defaultVersion = segments.getOrNull(apiIndex + 1)
        ?.takeIf { value -> value.matches(OPEN_API_VERSION_VALUE) }
        ?: return this
    return replaceBracedTemplateTokens { name, original ->
        defaultVersion.takeIf {
            name.lowercase().filter(Char::isLetterOrDigit) in OPEN_API_VERSION_PARAMETER_NAMES
        } ?: original
    }
}

private val OPEN_API_VERSION_VALUE = Regex("(?i)v?[0-9]+(?:[.][0-9]+)*")
private val OPEN_API_VERSION_PARAMETER_NAMES = setOf("apiversion")
