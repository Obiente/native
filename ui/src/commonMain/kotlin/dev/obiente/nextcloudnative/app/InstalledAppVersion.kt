package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val installedAppVersionJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    explicitNulls = false
}

internal suspend fun discoverInstalledAppVersion(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    appId: String,
): String? {
    val appInfoVersion = runCatchingPreservingCancellation {
        val response = services.executeNextcloudApi(
            session,
            NextcloudApiRequest(
                method = NextcloudApiMethod.GET,
                relativePath = "/ocs/v2.php/cloud/apps/$appId",
                queryParameters = mapOf("format" to "json"),
                ocsApiRequest = true,
            ),
        )
        if (response.status !in 200..299) return@runCatchingPreservingCancellation null
        val root = installedAppVersionJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
        val ocs = root?.get("ocs") as? JsonObject
        val data = ocs?.get("data") as? JsonObject
        (data?.get("version") as? JsonPrimitive)?.contentOrNull?.safeDynamicVersionHint()
    }.getOrNull()
    if (appInfoVersion != null) return appInfoVersion

    return runCatchingPreservingCancellation {
        val response = services.executeNextcloudApi(
            session,
            NextcloudApiRequest(
                method = NextcloudApiMethod.GET,
                relativePath = "/ocs/v1.php/cloud/capabilities",
                queryParameters = mapOf("format" to "json"),
                ocsApiRequest = true,
            ),
        )
        installedAppVersionFromCapabilities(appId, response)
    }.getOrNull()
}

internal fun installedAppVersionFromCapabilities(
    appId: String,
    response: NextcloudApiResponse,
): String? {
    if (response.status !in 200..299 || !appId.matches(Regex("[A-Za-z0-9_.-]+"))) return null
    val root = runCatching {
        installedAppVersionJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject
    }.getOrNull() ?: return null
    val ocs = root["ocs"] as? JsonObject ?: return null
    val data = ocs["data"] as? JsonObject ?: return null
    val capabilities = data["capabilities"] as? JsonObject ?: return null
    val appCapabilities = capabilities[appId] as? JsonObject ?: return null
    return (appCapabilities["version"] as? JsonPrimitive)
        ?.contentOrNull
        ?.safeDynamicVersionHint()
}

internal fun String.safeDynamicVersionHint(): String? = trim()
    .takeIf { version ->
        version.length in 1..MAX_DYNAMIC_VERSION_HINT_CHARACTERS &&
            version.all { character ->
                character.isLetterOrDigit() || character in setOf('.', '-', '_', '+')
            }
    }

private const val MAX_DYNAMIC_VERSION_HINT_CHARACTERS = 128
