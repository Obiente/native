package dev.obiente.nextcloudnative.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class NativeAppCatalogContract {
    AppStoreOcsV1,
    ProvisioningOcsV1,
}

enum class NativeAppLifecycleAction {
    InstallAndEnable,
    Enable,
    Disable,
    Update,
    Uninstall,
}

enum class NativeAdminAuthorization {
    /** Strict OCS middleware must receive the real account password on this one request. */
    AccountPasswordOnRequest,

    /** Non-strict OCS middleware has a recent server-side password confirmation. */
    RecentlyPasswordConfirmed,
}

data class NativeManagedApp(
    val id: String,
    val name: String,
    val summary: String? = null,
    val installedVersion: String? = null,
    val availableVersion: String? = null,
    val installed: Boolean,
    val enabled: Boolean,
    val compatible: Boolean? = null,
    val missingDependencies: List<String> = emptyList(),
    val internal: Boolean = false,
    val removable: Boolean = false,
    val externalApp: Boolean = false,
    val groups: List<String> = emptyList(),
) {
    init {
        require(id.isSafeNextcloudAppId()) { "The managed app ID is invalid." }
        require(name.isNotBlank() && name.length <= 200) { "The managed app name is invalid." }
    }

    val updateAvailable: Boolean
        get() = availableVersion?.isNotBlank() == true
}

data class NativeAppCatalog(
    val contract: NativeAppCatalogContract,
    val apps: List<NativeManagedApp>,
    /** True only after an administrator-only OCS inventory request succeeds. */
    val administratorAuthorized: Boolean,
    val includesAvailableApps: Boolean,
    val includesUpdateAvailability: Boolean,
)

sealed interface NativeAppCatalogResult {
    data class Available(val catalog: NativeAppCatalog) : NativeAppCatalogResult
    data object Forbidden : NativeAppCatalogResult
    data object Unavailable : NativeAppCatalogResult
    data class InvalidResponse(val reason: String) : NativeAppCatalogResult
}

data class NativeAppMutationApproval(
    val appId: String,
    val action: NativeAppLifecycleAction,
    val observedVersion: String?,
    val confirmationChallenge: String,
    val destructiveImpactAccepted: Boolean = false,
)

data class NativeAppMutationRequestPlan(
    val request: NextcloudApiRequest,
    val action: NativeAppLifecycleAction,
    val authorization: NativeAdminAuthorization,
    val destructive: Boolean,
    /**
     * This plan must use a request-scoped administrator confirmation transport. It is deliberately
     * not executable through the stored Login Flow app-password transport.
     */
    val requiresDedicatedAdminTransport: Boolean = true,
)

/**
 * Feature-probes the official Nextcloud 35 app-store OCS catalog, then falls back to the stable
 * provisioning inventory. Every request is GET-only. Authorization is inferred only from endpoint
 * success, never from a group name or client-side account metadata.
 */
suspend fun loadNativeAppCatalog(
    execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
): NativeAppCatalogResult {
    val appStoreResponse = execute(
        NextcloudApiRequest(
            method = NextcloudApiMethod.GET,
            relativePath = APP_STORE_CATALOG_PATH,
            queryParameters = mapOf("details" to "true", "format" to "json"),
            ocsApiRequest = true,
            maximumResponseBytes = APP_CATALOG_RESPONSE_LIMIT_BYTES,
        ),
    )
    when (appStoreResponse.status) {
        401, 403 -> return NativeAppCatalogResult.Forbidden
        in 200..299 -> return parseAppStoreCatalog(appStoreResponse)
    }
    if (appStoreResponse.status !in setOf(404, 405, 501)) {
        return NativeAppCatalogResult.InvalidResponse("The app catalog endpoint returned HTTP ${appStoreResponse.status}.")
    }

    val enabledResponse = execute(legacyAppListRequest("enabled"))
    if (enabledResponse.status == 401 || enabledResponse.status == 403) return NativeAppCatalogResult.Forbidden
    if (enabledResponse.status !in 200..299) return NativeAppCatalogResult.Unavailable
    val disabledResponse = execute(legacyAppListRequest("disabled"))
    if (disabledResponse.status == 401 || disabledResponse.status == 403) return NativeAppCatalogResult.Forbidden
    if (disabledResponse.status !in 200..299) return NativeAppCatalogResult.Unavailable
    return parseProvisioningCatalog(enabledResponse, disabledResponse)
}

fun buildNativeAppDetailsRequest(appId: String): NextcloudApiRequest {
    require(appId.isSafeNextcloudAppId()) { "The managed app ID is invalid." }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "$PROVISIONING_APPS_PATH/${appId.encodeAppManagementComponent()}",
        queryParameters = mapOf("format" to "json"),
        ocsApiRequest = true,
    )
}

fun parseNativeAppDetails(
    response: NextcloudApiResponse,
    inventoryRecord: NativeManagedApp,
): NativeManagedApp? {
    val data = response.ocsDataOrNull() as? JsonObject ?: return null
    val responseId = data.string("id") ?: inventoryRecord.id
    if (responseId != inventoryRecord.id) return null
    return inventoryRecord.copy(
        name = data.string("name")?.takeIf(String::isNotBlank) ?: inventoryRecord.name,
        summary = data.string("summary") ?: inventoryRecord.summary,
        installedVersion = data.string("version") ?: inventoryRecord.installedVersion,
        compatible = data.boolean("isCompatible") ?: inventoryRecord.compatible,
        internal = data.boolean("internal") ?: inventoryRecord.internal,
        removable = data.boolean("removable") ?: inventoryRecord.removable,
        externalApp = data.boolean("app_api") ?: inventoryRecord.externalApp,
        groups = data.stringList("groups").ifEmpty { inventoryRecord.groups },
    )
}

fun nativeAppConfirmationChallenge(
    app: NativeManagedApp,
    action: NativeAppLifecycleAction,
): String = listOf(action.name, app.id, app.installedVersion.orEmpty()).joinToString(":")

/**
 * Builds only official classic-app lifecycle contracts after exact state, capability, privilege,
 * authorization, and confirmation checks. ExApps stay read-only because AppAPI has a distinct
 * asynchronous lifecycle.
 *
 * The returned request is a plan, not permission to execute it with the stored app password.
 */
fun buildNativeAppMutationRequest(
    catalog: NativeAppCatalog,
    app: NativeManagedApp,
    action: NativeAppLifecycleAction,
    authorization: NativeAdminAuthorization,
    approval: NativeAppMutationApproval,
): NativeAppMutationRequestPlan {
    require(catalog.administratorAuthorized) { "Administrator app-management permission was not proven." }
    require(app in catalog.apps) { "The app is not part of the current verified catalog." }
    require(!app.externalApp) { "External apps require their dedicated AppAPI management flow." }
    require(!app.internal) { "Always-enabled internal apps cannot be managed here." }
    require(approval.appId == app.id && approval.action == action) { "The approval does not match this action." }
    require(approval.observedVersion == app.installedVersion) { "The app changed after confirmation." }
    require(approval.confirmationChallenge == nativeAppConfirmationChallenge(app, action)) {
        "The lifecycle action was not explicitly confirmed."
    }
    val destructive = action == NativeAppLifecycleAction.Uninstall
    require(!destructive || approval.destructiveImpactAccepted) {
        "Uninstall requires a separate destructive-impact acknowledgement."
    }

    val requiredAuthorization = when (action) {
        NativeAppLifecycleAction.Disable -> NativeAdminAuthorization.RecentlyPasswordConfirmed
        else -> NativeAdminAuthorization.AccountPasswordOnRequest
    }
    require(authorization == requiredAuthorization) {
        "This lifecycle action does not have the required administrator password confirmation."
    }
    validateLifecycleState(app, action)

    val request = when (catalog.contract) {
        NativeAppCatalogContract.AppStoreOcsV1 -> appStoreMutationRequest(app.id, action)
        NativeAppCatalogContract.ProvisioningOcsV1 -> provisioningMutationRequest(app.id, action)
    }
    return NativeAppMutationRequestPlan(
        request = request,
        action = action,
        authorization = authorization,
        destructive = destructive,
    )
}

fun availableNativeAppLifecycleActions(
    catalog: NativeAppCatalog,
    app: NativeManagedApp,
): Set<NativeAppLifecycleAction> {
    if (!catalog.administratorAuthorized || app !in catalog.apps || app.externalApp || app.internal) return emptySet()
    return buildSet {
        when {
            !app.installed -> if (app.canActivateSafely()) add(NativeAppLifecycleAction.InstallAndEnable)
            !app.enabled -> if (app.canActivateSafely()) add(NativeAppLifecycleAction.Enable)
            else -> add(NativeAppLifecycleAction.Disable)
        }
        if (
            catalog.contract == NativeAppCatalogContract.AppStoreOcsV1 &&
            app.installed && app.updateAvailable && app.canActivateSafely()
        ) {
            add(NativeAppLifecycleAction.Update)
        }
        if (
            catalog.contract == NativeAppCatalogContract.AppStoreOcsV1 &&
            app.installed && app.removable
        ) {
            add(NativeAppLifecycleAction.Uninstall)
        }
    }
}

private fun parseAppStoreCatalog(response: NextcloudApiResponse): NativeAppCatalogResult {
    val data = response.ocsDataOrNull()
        ?: return NativeAppCatalogResult.InvalidResponse("The app-store catalog response is not valid OCS JSON.")
    val entries = data as? JsonArray
        ?: return NativeAppCatalogResult.InvalidResponse("The app-store catalog data is not an array.")
    val apps = entries.mapNotNull { element ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val id = value.string("id")?.takeIf(String::isSafeNextcloudAppId) ?: return@mapNotNull null
        val installed = value.boolean("installed")
            ?: value.boolean("needsDownload")?.not()
            ?: return@mapNotNull null
        val enabled = value.boolean("active").orFalse()
        NativeManagedApp(
            id = id,
            name = value.string("name")?.takeIf(String::isNotBlank) ?: id.humanizeAppId(),
            summary = value.string("summary"),
            installedVersion = value.string("version").takeIf { installed },
            availableVersion = value.string("update"),
            installed = installed,
            enabled = enabled,
            compatible = value.boolean("isCompatible"),
            missingDependencies = value.stringList("missingDependencies"),
            internal = value.boolean("internal").orFalse(),
            removable = value.boolean("removable").orFalse(),
            externalApp = value.boolean("app_api").orFalse(),
            groups = value.stringList("groups"),
        )
    }.distinctBy(NativeManagedApp::id).sortedBy { app -> app.name.lowercase() }
    if (entries.isNotEmpty() && apps.isEmpty()) {
        return NativeAppCatalogResult.InvalidResponse("The app-store catalog contains no valid app records.")
    }
    return NativeAppCatalogResult.Available(
        NativeAppCatalog(
            contract = NativeAppCatalogContract.AppStoreOcsV1,
            apps = apps,
            administratorAuthorized = true,
            includesAvailableApps = true,
            includesUpdateAvailability = true,
        ),
    )
}

private fun parseProvisioningCatalog(
    enabledResponse: NextcloudApiResponse,
    disabledResponse: NextcloudApiResponse,
): NativeAppCatalogResult {
    val enabled = enabledResponse.legacyAppIdsOrNull()
        ?: return NativeAppCatalogResult.InvalidResponse("The enabled-app response is not valid OCS JSON.")
    val disabled = disabledResponse.legacyAppIdsOrNull()
        ?: return NativeAppCatalogResult.InvalidResponse("The disabled-app response is not valid OCS JSON.")
    if (enabled.intersect(disabled).isNotEmpty()) {
        return NativeAppCatalogResult.InvalidResponse("The app inventory contains conflicting enabled states.")
    }
    val apps = (enabled + disabled).sorted().map { id ->
        NativeManagedApp(
            id = id,
            name = id.humanizeAppId(),
            installed = true,
            enabled = id in enabled,
        )
    }
    return NativeAppCatalogResult.Available(
        NativeAppCatalog(
            contract = NativeAppCatalogContract.ProvisioningOcsV1,
            apps = apps,
            administratorAuthorized = true,
            includesAvailableApps = false,
            includesUpdateAvailability = false,
        ),
    )
}

private fun legacyAppListRequest(filter: String): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = PROVISIONING_APPS_PATH,
    queryParameters = mapOf("filter" to filter, "format" to "json"),
    ocsApiRequest = true,
)

private fun appStoreMutationRequest(
    appId: String,
    action: NativeAppLifecycleAction,
): NextcloudApiRequest {
    val endpoint = when (action) {
        NativeAppLifecycleAction.InstallAndEnable, NativeAppLifecycleAction.Enable -> "enable"
        NativeAppLifecycleAction.Disable -> "disable"
        NativeAppLifecycleAction.Update -> "update"
        NativeAppLifecycleAction.Uninstall -> "uninstall"
    }
    val body = "appId=${appId.encodeAppManagementComponent()}".encodeToByteArray()
    return NextcloudApiRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "$APP_STORE_CATALOG_PATH/$endpoint",
        queryParameters = mapOf("format" to "json"),
        contentType = FORM_CONTENT_TYPE,
        body = body,
        ocsApiRequest = true,
    )
}

private fun provisioningMutationRequest(
    appId: String,
    action: NativeAppLifecycleAction,
): NextcloudApiRequest {
    require(action in setOf(
        NativeAppLifecycleAction.InstallAndEnable,
        NativeAppLifecycleAction.Enable,
        NativeAppLifecycleAction.Disable,
    )) { "The stable provisioning API does not prove this lifecycle action." }
    return NextcloudApiRequest(
        method = if (action == NativeAppLifecycleAction.Disable) NextcloudApiMethod.DELETE else NextcloudApiMethod.POST,
        relativePath = "$PROVISIONING_APPS_PATH/${appId.encodeAppManagementComponent()}",
        queryParameters = mapOf("format" to "json"),
        ocsApiRequest = true,
    )
}

private fun validateLifecycleState(app: NativeManagedApp, action: NativeAppLifecycleAction) {
    when (action) {
        NativeAppLifecycleAction.InstallAndEnable ->
            require(!app.installed && app.canActivateSafely()) {
                "Install is available only for compatible apps with satisfied dependencies."
            }
        NativeAppLifecycleAction.Enable ->
            require(app.installed && !app.enabled && app.canActivateSafely()) {
                "Enable is available only for compatible disabled apps with satisfied dependencies."
            }
        NativeAppLifecycleAction.Disable ->
            require(app.installed && app.enabled) { "Disable is available only for enabled installed apps." }
        NativeAppLifecycleAction.Update ->
            require(app.installed && app.updateAvailable && app.canActivateSafely()) {
                "No compatible verified update is available for this app."
            }
        NativeAppLifecycleAction.Uninstall ->
            require(app.installed && app.removable) { "This app is not proven removable." }
    }
}

private fun NativeManagedApp.canActivateSafely(): Boolean =
    compatible != false && missingDependencies.isEmpty()

private fun NextcloudApiResponse.ocsDataOrNull(): JsonElement? {
    if (status !in 200..299 || contentType?.substringBefore(';')?.trim() != "application/json") return null
    val root = runCatching { Json.parseToJsonElement(body.decodeToString()) as? JsonObject }.getOrNull() ?: return null
    val ocs = root["ocs"] as? JsonObject ?: return null
    val meta = ocs["meta"] as? JsonObject ?: return null
    val statusCode = (meta["statuscode"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    if (statusCode != null && statusCode !in setOf(100, 200)) return null
    val statusValue = (meta["status"] as? JsonPrimitive)?.contentOrNull
    if (statusValue != null && statusValue != "ok") return null
    return ocs["data"]
}

private fun NextcloudApiResponse.legacyAppIdsOrNull(): Set<String>? {
    val data = ocsDataOrNull() as? JsonObject ?: return null
    val apps = data["apps"] as? JsonArray ?: return null
    return apps.mapTo(linkedSetOf()) { element ->
        (element as? JsonPrimitive)?.contentOrNull?.takeIf(String::isSafeNextcloudAppId) ?: return null
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.length <= 4_096 }

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.let { primitive ->
        primitive.booleanOrNull ?: when (primitive.contentOrNull?.lowercase()) {
            "1", "yes" -> true
            "0", "no" -> false
            else -> null
        }
    }

private fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 512 } }
        ?.distinct()
        ?.take(128)
        .orEmpty()

private fun Boolean?.orFalse(): Boolean = this == true

private fun String.isSafeNextcloudAppId(): Boolean =
    length in 1..64 && all { character ->
        character.isLetterOrDigit() || character == '_' || character == '.' || character == '-'
    } && this != "." && this != ".."

private fun String.humanizeAppId(): String =
    split('_', '-', '.').filter(String::isNotBlank).joinToString(" ") { word ->
        word.replaceFirstChar(Char::uppercaseChar)
    }.ifBlank { this }

private fun String.encodeAppManagementComponent(): String = buildString {
    for (byte in this@encodeAppManagementComponent.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code ||
            unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code ||
            unsigned == '-'.code || unsigned == '.'.code || unsigned == '_'.code || unsigned == '~'.code
        if (unreserved) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(ADMIN_HEX[unsigned ushr 4])
            append(ADMIN_HEX[unsigned and 0x0f])
        }
    }
}

private const val PROVISIONING_APPS_PATH = "/ocs/v1.php/cloud/apps"
private const val APP_STORE_CATALOG_PATH = "/ocs/v2.php/apps/appstore/api/v1/apps"
private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
private const val APP_CATALOG_RESPONSE_LIMIT_BYTES = 8L * 1_024L * 1_024L
private const val ADMIN_HEX = "0123456789ABCDEF"
