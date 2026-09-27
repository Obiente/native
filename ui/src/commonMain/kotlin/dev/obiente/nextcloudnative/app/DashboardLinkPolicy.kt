package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** HTTP links are limited to the origin already approved when adding the account. */
class DashboardLinkPolicy private constructor(private val plainHttpOrigin: String?) {
    internal fun accepts(value: String): Boolean {
        if (value.length !in 1..MAX_DASHBOARD_LINK_LENGTH) return false
        if (value.any { it.isISOControl() || it.isWhitespace() } || '\\' in value || value.startsWith("//")) {
            return false
        }
        if (value.startsWith('/')) {
            return value.split('/').none { segment ->
                val decodedDots = segment.replace("%2e", ".", ignoreCase = true)
                decodedDots == "." || decodedDots == ".."
            }
        }
        val origin = validatedNextcloudWebOrigin(value) ?: return false
        return origin.startsWith("https://") || origin == plainHttpOrigin
    }

    companion object {
        val TlsOnly = DashboardLinkPolicy(null)

        fun forAccount(serverUrl: String): DashboardLinkPolicy = DashboardLinkPolicy(
            validatedNextcloudWebOrigin(serverUrl.trim())?.takeIf { it.startsWith("http://") },
        )
    }
}

internal fun JsonObject.requiredDashboardLink(name: String, policy: DashboardLinkPolicy): String =
    optionalDashboardLink(name, policy) ?: error("The dashboard response has no valid $name.")

internal fun JsonObject.optionalDashboardLink(name: String, policy: DashboardLinkPolicy): String? {
    val value = dashboardLinkText(name) ?: return null
    require(policy.accepts(value)) { "The dashboard $name is unsafe." }
    return value
}

internal fun JsonObject.optionalDashboardIconLink(name: String, policy: DashboardLinkPolicy): String? =
    dashboardLinkText(name)?.takeIf(policy::accepts)

private fun JsonObject.dashboardLinkText(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

private const val MAX_DASHBOARD_LINK_LENGTH = 8_192
