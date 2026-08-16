package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class NativeDashboardAction(
    val type: String,
    val label: String,
    val link: String,
)

data class NativeDashboardWidget(
    val id: String,
    val title: String,
    val order: Int,
    val iconUrl: String?,
    val iconClass: String?,
    val widgetUrl: String?,
    val itemApiVersions: Set<Int>,
    val itemIconsRound: Boolean,
    val reloadIntervalSeconds: Int?,
    val actions: List<NativeDashboardAction>,
)

data class NativeDashboardItem(
    val widgetId: String,
    val title: String,
    val subtitle: String?,
    val link: String?,
    val iconUrl: String?,
    val overlayIconUrl: String?,
    val sinceId: String,
)

data class NativeDashboardSnapshot(
    val widgets: List<NativeDashboardWidget>,
    val itemsByWidget: Map<String, List<NativeDashboardItem>>,
    val emptyContentMessagesByWidget: Map<String, String> = emptyMap(),
    val halfEmptyContentMessagesByWidget: Map<String, String> = emptyMap(),
    val failedWidgetIds: Set<String> = emptySet(),
    val unsupportedWidgetIds: Set<String> = emptySet(),
    val loadingWidgetIds: Set<String> = emptySet(),
) {
    init {
        val widgetIds = widgets.mapTo(mutableSetOf(), NativeDashboardWidget::id)
        require(widgets.map(NativeDashboardWidget::id).distinct().size == widgets.size) {
            "The dashboard contains duplicate widget IDs."
        }
        require(itemsByWidget.keys.all(widgetIds::contains)) {
            "Dashboard items reference an unknown widget."
        }
        require(emptyContentMessagesByWidget.keys.all(widgetIds::contains)) {
            "Dashboard empty messages reference an unknown widget."
        }
        require(halfEmptyContentMessagesByWidget.keys.all(widgetIds::contains)) {
            "Dashboard half-empty messages reference an unknown widget."
        }
        require(failedWidgetIds.all(widgetIds::contains)) {
            "Dashboard failures reference an unknown widget."
        }
        require(unsupportedWidgetIds.all(widgetIds::contains)) {
            "Unsupported Dashboard widgets reference an unknown widget."
        }
        require(loadingWidgetIds.all(widgetIds::contains)) {
            "Loading Dashboard widgets reference an unknown widget."
        }
        require((failedWidgetIds + unsupportedWidgetIds + loadingWidgetIds).size ==
            failedWidgetIds.size + unsupportedWidgetIds.size + loadingWidgetIds.size) {
            "A Dashboard widget cannot have conflicting load states."
        }
    }

    /** Dashboard feeds return their newest known cursor first. Retain it as opaque data. */
    val latestSinceIds: Map<String, String> = itemsByWidget.mapNotNull { (widgetId, items) ->
        items.firstOrNull()?.sinceId?.let { widgetId to it }
    }.toMap()
}

internal enum class DashboardItemApiVersion(val wireValue: Int) {
    V1(1),
    V2(2),
}

internal data class DashboardItemsPayload(
    val itemsByWidget: Map<String, List<NativeDashboardItem>>,
    val emptyContentMessagesByWidget: Map<String, String> = emptyMap(),
    val halfEmptyContentMessagesByWidget: Map<String, String> = emptyMap(),
)

internal data class DashboardItemsRequestPlan(
    val apiVersion: DashboardItemApiVersion,
    val widgetIds: Set<String>,
    val request: NextcloudApiRequest,
)

internal sealed interface DashboardItemsFetchResult {
    val widgetIds: Set<String>

    data class Loaded(
        override val widgetIds: Set<String>,
        val payload: DashboardItemsPayload,
    ) : DashboardItemsFetchResult

    data class Failed(
        override val widgetIds: Set<String>,
    ) : DashboardItemsFetchResult
}

internal fun dashboardItemsFetchResult(
    requestedWidgetIds: Set<String>,
    payload: DashboardItemsPayload,
): DashboardItemsFetchResult {
    require(requestedWidgetIds.isNotEmpty()) { "A Dashboard item request must name a widget." }
    require(payload.itemsByWidget.keys.all(requestedWidgetIds::contains)) {
        "Dashboard items reference a widget outside the request."
    }
    return if (payload.itemsByWidget.keys.containsAll(requestedWidgetIds)) {
        DashboardItemsFetchResult.Loaded(requestedWidgetIds, payload)
    } else {
        DashboardItemsFetchResult.Failed(requestedWidgetIds)
    }
}

/**
 * A missing Dashboard route means the optional server app is unavailable. Keep this deliberately
 * narrow so authentication, permission, transport, and malformed-response failures remain visible.
 */
internal fun isDashboardApiUnavailable(response: NextcloudApiResponse): Boolean {
    if (response.status == 404) return true
    if (response.status !in 200..299) return false
    val statusCode = runCatching {
        val root = dashboardJson.parseToJsonElement(response.body.decodeToString()).jsonObject
        val ocs = root["ocs"] as? JsonObject
        val meta = ocs?.get("meta") as? JsonObject
        (meta?.get("statuscode") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    }.getOrNull()
    return statusCode == 404
}

internal fun DashboardItemsRequestPlan.v1FallbackRequest(
    widgets: List<NativeDashboardWidget>,
): NextcloudApiRequest? {
    if (apiVersion != DashboardItemApiVersion.V2) return null
    val selectedWidgets = widgets.filter { it.id in widgetIds }
    if (selectedWidgets.mapTo(mutableSetOf(), NativeDashboardWidget::id) != widgetIds) return null
    if (selectedWidgets.any { 1 !in it.itemApiVersions }) return null
    return request.copy(relativePath = "/ocs/v2.php/apps/dashboard/api/v1/widget-items")
}

internal fun dashboardFallbackResponseBudget(reservedBytes: Long, firstResponseBytes: Long): Long {
    require(reservedBytes > 0L)
    require(firstResponseBytes in 0L..reservedBytes)
    return reservedBytes - firstResponseBytes
}

enum class NativeUserPresence(val wireValue: String) {
    Online("online"),
    Away("away"),
    DoNotDisturb("dnd"),
    Invisible("invisible"),
    Offline("offline"),
    Busy("busy"),
}

data class NativeUserStatusCapabilities(
    val enabled: Boolean,
    val restore: Boolean,
    val supportsEmoji: Boolean,
    val supportsBusy: Boolean,
)

data class NativeUserStatus(
    val userId: String,
    val presence: NativeUserPresence,
    val message: String?,
    val icon: String?,
    val messageId: String?,
    val clearAtEpochSeconds: Long?,
    val messageIsPredefined: Boolean,
    val statusIsUserDefined: Boolean,
) {
    fun expiresWithin(nowEpochSeconds: Long, seconds: Long): Boolean =
        clearAtEpochSeconds?.let { it in (nowEpochSeconds + 1)..(nowEpochSeconds + seconds) } == true
}

data class NativePredefinedStatus(
    val id: String,
    val message: String,
    val icon: String?,
    val clearAt: NativeStatusExpiryOption?,
)

data class NativeStatusExpiryOption(
    val type: String,
    val time: Long,
)

fun dashboardWidgetsRequest(
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/ocs/v2.php/apps/dashboard/api/v1/widgets",
    queryParameters = mapOf("format" to "json"),
    ocsApiRequest = true,
    maximumResponseBytes = DASHBOARD_RESPONSE_LIMIT_BYTES,
    cachePolicy = cachePolicy,
).requireSafe()

fun dashboardItemsRequest(
    sinceIds: Map<String, String> = emptyMap(),
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): NextcloudApiRequest {
    return dashboardItemsRequest(
        apiVersion = DashboardItemApiVersion.V1,
        widgetIds = emptySet(),
        sinceIds = sinceIds,
        cachePolicy = cachePolicy,
    )
}

internal fun dashboardItemsRequest(
    apiVersion: DashboardItemApiVersion,
    widgetIds: Set<String>,
    sinceIds: Map<String, String> = emptyMap(),
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): NextcloudApiRequest {
    require(widgetIds.size <= MAX_DASHBOARD_WIDGETS) { "The dashboard widget set is too large." }
    require(sinceIds.size <= MAX_DASHBOARD_WIDGETS) { "The dashboard cursor set is too large." }
    widgetIds.forEach { widgetId ->
        require(widgetId.isDashboardIdentifier()) { "The dashboard widget ID is invalid." }
    }
    sinceIds.forEach { (widgetId, sinceId) ->
        require(widgetId.isDashboardIdentifier()) { "The dashboard widget cursor ID is invalid." }
        require(widgetIds.isEmpty() || widgetId in widgetIds) {
            "The dashboard cursor does not belong to a requested widget."
        }
        require(sinceId.isSafeDashboardText(MAX_DASHBOARD_CURSOR_LENGTH)) {
            "The dashboard cursor is invalid."
        }
    }
    return NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = "/ocs/v2.php/apps/dashboard/api/v${apiVersion.wireValue}/widget-items",
        // Nextcloud documents this GET with a JSON request body, but Android/OkHttp correctly
        // rejects GET bodies. PHP's request binder accepts the equivalent bracketed query shape.
        queryParameters = buildMap {
            put("format", "json")
            widgetIds.toSortedSet().forEachIndexed { index, widgetId ->
                put("widgets[$index]", widgetId)
            }
            sinceIds.toSortedMap().forEach { (widgetId, sinceId) ->
                put("sinceIds[$widgetId]", sinceId)
            }
        },
        ocsApiRequest = true,
        maximumResponseBytes = DASHBOARD_ITEM_RESPONSE_LIMIT_BYTES,
        cachePolicy = cachePolicy,
    ).requireSafe()
}

internal fun dashboardItemsRequestPlans(
    widgets: List<NativeDashboardWidget>,
    sinceIds: Map<String, String> = emptyMap(),
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): List<DashboardItemsRequestPlan> {
    return widgets.mapNotNull { widget ->
        val apiVersion = when {
            2 in widget.itemApiVersions -> DashboardItemApiVersion.V2
            1 in widget.itemApiVersions -> DashboardItemApiVersion.V1
            else -> null
        } ?: return@mapNotNull null
        val widgetIds = setOf(widget.id)
        DashboardItemsRequestPlan(
            apiVersion = apiVersion,
            widgetIds = widgetIds,
            request = dashboardItemsRequest(
                apiVersion = apiVersion,
                widgetIds = widgetIds,
                sinceIds = sinceIds.filterKeys(widgetIds::contains),
                cachePolicy = cachePolicy,
            ),
        )
    }
}

internal fun unsupportedDashboardWidgetIds(widgets: List<NativeDashboardWidget>): Set<String> =
    widgets.filter { widget ->
        widget.itemApiVersions.isNotEmpty() &&
            widget.itemApiVersions.none { version -> version == 1 || version == 2 }
    }.mapTo(mutableSetOf(), NativeDashboardWidget::id)

internal class DashboardResponseBudget(
    totalBytes: Long = DASHBOARD_REFRESH_RESPONSE_BUDGET_BYTES,
) {
    init {
        require(totalBytes > 0L) { "The Dashboard response budget must be positive." }
    }

    var remainingBytes: Long = totalBytes
        private set

    fun reserve(maximumBytes: Long = DASHBOARD_ITEM_RESPONSE_LIMIT_BYTES): Long {
        require(maximumBytes > 0L) { "The Dashboard response reservation must be positive." }
        val reserved = minOf(remainingBytes, maximumBytes)
        remainingBytes -= reserved
        return reserved
    }

    fun releaseUnused(reservedBytes: Long, responseBytes: Long) {
        require(reservedBytes >= 0L && responseBytes in 0L..reservedBytes) {
            "The Dashboard response usage is invalid."
        }
        remainingBytes += reservedBytes - responseBytes
    }

    fun releaseFailed(reservedBytes: Long) {
        require(reservedBytes >= 0L) { "The Dashboard response reservation is invalid." }
        remainingBytes += reservedBytes
    }
}

fun currentUserStatusRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = USER_STATUS_BASE_PATH,
    queryParameters = mapOf("format" to "json"),
    ocsApiRequest = true,
    maximumResponseBytes = STATUS_RESPONSE_LIMIT_BYTES,
).requireSafe()

fun userStatusCapabilitiesRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/ocs/v2.php/cloud/capabilities",
    queryParameters = mapOf("format" to "json"),
    ocsApiRequest = true,
    maximumResponseBytes = STATUS_RESPONSE_LIMIT_BYTES,
).requireSafe()

fun predefinedStatusesRequest(): NextcloudApiRequest = NextcloudApiRequest(
    method = NextcloudApiMethod.GET,
    relativePath = "/ocs/v2.php/apps/user_status/api/v1/predefined_statuses",
    queryParameters = mapOf("format" to "json"),
    ocsApiRequest = true,
    maximumResponseBytes = STATUS_RESPONSE_LIMIT_BYTES,
).requireSafe()

fun parseDashboardWidgets(response: NextcloudApiResponse): List<NativeDashboardWidget> {
    val data = response.requireOcsData("dashboard widgets")
    require(data is JsonObject) { "The dashboard widget response is not an object." }
    require(data.size <= MAX_DASHBOARD_WIDGETS) { "The dashboard returned too many widgets." }
    return data.entries.map { (key, value) ->
        require(key.isDashboardIdentifier()) { "The dashboard widget ID is invalid." }
        val item = value as? JsonObject ?: error("Dashboard widget $key is not an object.")
        val id = item.requiredDashboardText("id", MAX_DASHBOARD_ID_LENGTH)
        require(id == key) { "The dashboard widget key does not match its ID." }
        val actions = (item["buttons"] as? JsonArray).orEmpty().also {
            require(it.size <= MAX_DASHBOARD_ACTIONS) { "The dashboard widget has too many actions." }
        }.mapIndexed { index, action ->
            val objectValue = action as? JsonObject ?: error("Dashboard action $index is not an object.")
            NativeDashboardAction(
                type = objectValue.requiredDashboardText("type", MAX_DASHBOARD_TYPE_LENGTH),
                label = objectValue.requiredDashboardText("text", MAX_DASHBOARD_TEXT_LENGTH),
                link = objectValue.requiredDashboardLink("link"),
            )
        }
        NativeDashboardWidget(
            id = id,
            title = item.requiredDashboardText("title", MAX_DASHBOARD_TEXT_LENGTH),
            order = item.optionalInt("order") ?: 0,
            iconUrl = item.optionalDashboardLink("icon_url"),
            iconClass = item.optionalDashboardText("icon_class", MAX_DASHBOARD_TYPE_LENGTH),
            widgetUrl = item.optionalDashboardLink("widget_url"),
            itemApiVersions = item.integerSet("item_api_versions"),
            itemIconsRound = item.optionalBoolean("item_icons_round") ?: false,
            reloadIntervalSeconds = item.optionalInt("reload_interval")?.takeIf { it > 0 }?.also {
                require(it in MIN_DASHBOARD_RELOAD_SECONDS..MAX_DASHBOARD_RELOAD_SECONDS) {
                    "The dashboard reload interval is invalid."
                }
            },
            actions = actions,
        )
    }.sortedWith(compareBy<NativeDashboardWidget>(NativeDashboardWidget::order).thenBy(NativeDashboardWidget::id))
}

fun parseDashboardItems(
    response: NextcloudApiResponse,
    widgets: List<NativeDashboardWidget>,
): Map<String, List<NativeDashboardItem>> {
    val data = response.requireDashboardItemsObject("dashboard v1 items")
    require(data.size <= MAX_DASHBOARD_WIDGETS) { "The dashboard returned too many item groups." }
    val widgetIds = widgets.mapTo(mutableSetOf(), NativeDashboardWidget::id)
    return data.mapValues { (widgetId, value) ->
        require(widgetId in widgetIds) { "The dashboard returned items for an unknown widget." }
        val items = value as? JsonArray ?: error("Dashboard items for $widgetId are not an array.")
        items.parseDashboardItemList(widgetId)
    }
}

internal fun parseDashboardItemsV2(
    response: NextcloudApiResponse,
    widgets: List<NativeDashboardWidget>,
): DashboardItemsPayload {
    val data = response.requireDashboardItemsObject("dashboard v2 items")
    require(data.size <= MAX_DASHBOARD_WIDGETS) { "The dashboard returned too many item groups." }
    val widgetIds = widgets.mapTo(mutableSetOf(), NativeDashboardWidget::id)
    val itemsByWidget = mutableMapOf<String, List<NativeDashboardItem>>()
    val emptyMessages = mutableMapOf<String, String>()
    val halfEmptyMessages = mutableMapOf<String, String>()
    data.forEach { (widgetId, value) ->
        require(widgetId in widgetIds) { "The dashboard returned items for an unknown widget." }
        val group = value as? JsonObject ?: error("Dashboard items for $widgetId are not an object.")
        val items = group["items"] as? JsonArray
            ?: error("Dashboard items for $widgetId have no item list.")
        itemsByWidget[widgetId] = items.parseDashboardItemList(widgetId)
        group.optionalDashboardText("emptyContentMessage", MAX_DASHBOARD_TEXT_LENGTH)?.let {
            emptyMessages[widgetId] = it
        }
        group.optionalDashboardText("halfEmptyContentMessage", MAX_DASHBOARD_TEXT_LENGTH)?.let {
            halfEmptyMessages[widgetId] = it
        }
    }
    return DashboardItemsPayload(
        itemsByWidget = itemsByWidget,
        emptyContentMessagesByWidget = emptyMessages,
        halfEmptyContentMessagesByWidget = halfEmptyMessages,
    )
}

internal fun mergeDashboardItemFetchResults(
    widgets: List<NativeDashboardWidget>,
    previousSnapshot: NativeDashboardSnapshot?,
    results: List<DashboardItemsFetchResult>,
    unsupportedWidgetIds: Set<String> = emptySet(),
    loadingWidgetIds: Set<String> = emptySet(),
): NativeDashboardSnapshot {
    val widgetIds = widgets.mapTo(mutableSetOf(), NativeDashboardWidget::id)
    val resolvedWidgetIds = results.flatMap(DashboardItemsFetchResult::widgetIds)
    require(resolvedWidgetIds.distinct().size == resolvedWidgetIds.size) {
        "A dashboard widget was loaded more than once."
    }
    require(resolvedWidgetIds.all(widgetIds::contains)) {
        "A dashboard item result references an unknown widget."
    }
    require(unsupportedWidgetIds.all(widgetIds::contains)) {
        "An unsupported Dashboard widget result references an unknown widget."
    }
    require(resolvedWidgetIds.none(unsupportedWidgetIds::contains)) {
        "An unsupported Dashboard widget cannot have an item result."
    }
    require(loadingWidgetIds.all(widgetIds::contains)) {
        "A loading Dashboard widget result references an unknown widget."
    }
    require(resolvedWidgetIds.none(loadingWidgetIds::contains)) {
        "A resolved Dashboard widget cannot still be loading."
    }
    require(unsupportedWidgetIds.intersect(loadingWidgetIds).isEmpty()) {
        "An unsupported Dashboard widget cannot still be loading."
    }

    val loaded = results.filterIsInstance<DashboardItemsFetchResult.Loaded>()
    val failedWidgetIds = results.filterIsInstance<DashboardItemsFetchResult.Failed>()
        .flatMapTo(mutableSetOf(), DashboardItemsFetchResult.Failed::widgetIds)
    val loadedItems = loaded.flatMap { result -> result.payload.itemsByWidget.entries }
        .associate { it.toPair() }
    val loadedEmptyMessages = loaded.flatMap { result -> result.payload.emptyContentMessagesByWidget.entries }
        .associate { it.toPair() }
    val loadedHalfEmptyMessages = loaded
        .flatMap { result -> result.payload.halfEmptyContentMessagesByWidget.entries }
        .associate { it.toPair() }

    return NativeDashboardSnapshot(
        widgets = widgets,
        itemsByWidget = buildMap {
            widgets.forEach { widget ->
                val items = if (widget.id in failedWidgetIds || widget.id in loadingWidgetIds) {
                    previousSnapshot?.itemsByWidget?.get(widget.id).orEmpty()
                } else {
                    loadedItems[widget.id].orEmpty()
                }
                put(widget.id, items)
            }
        },
        emptyContentMessagesByWidget = buildMap {
            widgets.forEach { widget ->
                val message = if (widget.id in failedWidgetIds || widget.id in loadingWidgetIds) {
                    previousSnapshot?.emptyContentMessagesByWidget?.get(widget.id)
                } else {
                    loadedEmptyMessages[widget.id]
                }
                message?.let { put(widget.id, it) }
            }
        },
        halfEmptyContentMessagesByWidget = buildMap {
            widgets.forEach { widget ->
                val message = if (widget.id in failedWidgetIds || widget.id in loadingWidgetIds) {
                    previousSnapshot?.halfEmptyContentMessagesByWidget?.get(widget.id)
                } else {
                    loadedHalfEmptyMessages[widget.id]
                }
                message?.let { put(widget.id, it) }
            }
        },
        failedWidgetIds = failedWidgetIds.toSet(),
        unsupportedWidgetIds = unsupportedWidgetIds.toSet(),
        loadingWidgetIds = loadingWidgetIds.toSet(),
    )
}

internal fun dashboardLoadFailureDiagnostic(
    stage: String,
    code: String,
    cachedAvailable: Boolean,
    severity: SupportDiagnosticSeverity,
): SupportDiagnosticEventDraft = SupportDiagnosticEventDraft(
    severity = severity,
    component = SupportDiagnosticComponent.App,
    operation = "dashboard.load",
    outcome = "failed",
    code = code,
    fields = listOf(
        SupportDiagnosticFieldDraft("stage", stage),
        SupportDiagnosticFieldDraft("cached_available", cachedAvailable.toString()),
    ),
)

fun parseUserStatusCapabilities(response: NextcloudApiResponse): NativeUserStatusCapabilities {
    val capabilities = response.requireOcsData("capabilities").jsonObject["capabilities"] as? JsonObject
        ?: error("The capability response has no capabilities object.")
    val status = capabilities["user_status"] as? JsonObject
        ?: return NativeUserStatusCapabilities(false, false, false, false)
    return NativeUserStatusCapabilities(
        enabled = status.requiredBoolean("enabled"),
        restore = status.optionalBoolean("restore") ?: false,
        supportsEmoji = status.optionalBoolean("supports_emoji") ?: false,
        supportsBusy = status.optionalBoolean("supports_busy") ?: false,
    )
}

fun parseCurrentUserStatus(response: NextcloudApiResponse): NativeUserStatus {
    val item = response.requireOcsData("user status") as? JsonObject
        ?: error("The user status response is not an object.")
    val presenceValue = item.requiredDashboardText("status", MAX_DASHBOARD_TYPE_LENGTH)
    val presence = NativeUserPresence.entries.singleOrNull { it.wireValue == presenceValue }
        ?: error("The user status presence is unsupported.")
    return NativeUserStatus(
        userId = item.requiredDashboardText("userId", MAX_DASHBOARD_ID_LENGTH),
        presence = presence,
        message = item.optionalDashboardText("message", MAX_STATUS_MESSAGE_LENGTH),
        icon = item.optionalDashboardText("icon", MAX_STATUS_ICON_LENGTH),
        messageId = item.optionalDashboardText("messageId", MAX_DASHBOARD_ID_LENGTH),
        clearAtEpochSeconds = item.optionalLong("clearAt")?.also {
            require(it >= 0L) { "The user status expiry is invalid." }
        },
        messageIsPredefined = item.optionalBoolean("messageIsPredefined") ?: false,
        statusIsUserDefined = item.optionalBoolean("statusIsUserDefined") ?: false,
    )
}

fun parsePredefinedStatuses(response: NextcloudApiResponse): List<NativePredefinedStatus> {
    val data = response.requireOcsData("predefined statuses") as? JsonArray
        ?: error("The predefined status response is not an array.")
    require(data.size <= MAX_PREDEFINED_STATUSES) { "The server returned too many predefined statuses." }
    return data.mapIndexed { index, element ->
        val item = element as? JsonObject ?: error("Predefined status $index is not an object.")
        NativePredefinedStatus(
            id = item.requiredDashboardText("id", MAX_DASHBOARD_ID_LENGTH),
            message = item.requiredDashboardText("message", MAX_STATUS_MESSAGE_LENGTH),
            icon = item.optionalDashboardText("icon", MAX_STATUS_ICON_LENGTH),
            clearAt = item.statusExpiryOption("clearAt"),
        )
    }.also { statuses ->
        require(statuses.map(NativePredefinedStatus::id).distinct().size == statuses.size) {
            "The predefined status response contains duplicate IDs."
        }
    }
}

sealed interface NativeUserStatusEdit {
    data class Presence(val presence: NativeUserPresence) : NativeUserStatusEdit

    data class CustomMessage(
        val message: String,
        val icon: String?,
        val clearAtEpochSeconds: Long?,
    ) : NativeUserStatusEdit {
        override fun toString(): String =
            "CustomMessage(message=<redacted>, icon=${if (icon == null) "none" else "<redacted>"}, clearAt=$clearAtEpochSeconds)"
    }

    data class PredefinedMessage(
        val messageId: String,
        val clearAtEpochSeconds: Long?,
    ) : NativeUserStatusEdit

    data object ClearMessage : NativeUserStatusEdit

    data class Restore(val messageId: String) : NativeUserStatusEdit
}

/**
 * Builds but never executes an editable status action. Capability and expiry checks happen before a
 * request exists, and custom status text remains only in the explicit request body.
 */
fun planUserStatusEdit(
    edit: NativeUserStatusEdit,
    capabilities: NativeUserStatusCapabilities,
    nowEpochSeconds: Long,
): NextcloudApiRequest {
    require(capabilities.enabled) { "User status is not enabled on this server." }
    require(nowEpochSeconds >= 0L) { "The current status timestamp is invalid." }
    val request = when (edit) {
        is NativeUserStatusEdit.Presence -> {
            require(edit.presence != NativeUserPresence.Busy || capabilities.supportsBusy) {
                "This server does not support busy presence."
            }
            statusMutationRequest(
                NextcloudApiMethod.PUT,
                "$USER_STATUS_BASE_PATH/status",
                mapOf("statusType" to edit.presence.wireValue),
            )
        }
        is NativeUserStatusEdit.CustomMessage -> {
            require(edit.message.isSafeDashboardText(MAX_STATUS_MESSAGE_LENGTH)) {
                "The custom status message is invalid."
            }
            require(edit.icon == null || capabilities.supportsEmoji) {
                "This server does not support status emoji."
            }
            require(edit.icon == null || edit.icon.isSafeDashboardText(MAX_STATUS_ICON_LENGTH)) {
                "The custom status icon is invalid."
            }
            val fields = buildMap {
                put("message", edit.message)
                edit.icon?.let { put("statusIcon", it) }
                put("clearAt", edit.clearAtEpochSeconds.validStatusExpiry(nowEpochSeconds).toString())
            }
            statusMutationRequest(NextcloudApiMethod.PUT, "$USER_STATUS_BASE_PATH/message/custom", fields)
        }
        is NativeUserStatusEdit.PredefinedMessage -> {
            require(edit.messageId.isDashboardIdentifier()) { "The predefined status ID is invalid." }
            statusMutationRequest(
                NextcloudApiMethod.PUT,
                "$USER_STATUS_BASE_PATH/message/predefined",
                mapOf(
                    "messageId" to edit.messageId,
                    "clearAt" to edit.clearAtEpochSeconds.validStatusExpiry(nowEpochSeconds).toString(),
                ),
            )
        }
        NativeUserStatusEdit.ClearMessage ->
            statusMutationRequest(NextcloudApiMethod.DELETE, "$USER_STATUS_BASE_PATH/message")
        is NativeUserStatusEdit.Restore -> {
            require(capabilities.restore) { "This server does not support restoring a status." }
            require(edit.messageId.isDashboardIdentifier()) { "The status restore ID is invalid." }
            statusMutationRequest(
                NextcloudApiMethod.DELETE,
                "$USER_STATUS_BASE_PATH/revert/${edit.messageId}",
            )
        }
    }
    return request.requireSafe()
}

data class CachedDashboardStatus(
    val dashboard: NativeDashboardSnapshot,
    val status: NativeUserStatus?,
    val storedAtEpochSeconds: Long,
)

/** Account-private process cache. It stores no password and expires quickly. */
internal class DashboardStatusMemoryCache(
    private val ttlSeconds: Long = DASHBOARD_STATUS_CACHE_TTL_SECONDS,
) {
    private val entries = mutableMapOf<String, CachedDashboardStatus>()

    fun get(session: NextcloudSession, nowEpochSeconds: Long): CachedDashboardStatus? {
        val entry = entries[session.dashboardCacheKey()] ?: return null
        return entry.takeIf {
            nowEpochSeconds >= it.storedAtEpochSeconds &&
                nowEpochSeconds - it.storedAtEpochSeconds <= ttlSeconds
        } ?: run {
            entries.remove(session.dashboardCacheKey())
            null
        }
    }

    fun store(
        session: NextcloudSession,
        dashboard: NativeDashboardSnapshot,
        status: NativeUserStatus?,
        nowEpochSeconds: Long,
    ) {
        require(nowEpochSeconds >= 0L) { "The dashboard cache timestamp is invalid." }
        entries[session.dashboardCacheKey()] = CachedDashboardStatus(dashboard, status, nowEpochSeconds)
    }

    fun invalidate(session: NextcloudSession) {
        entries.remove(session.dashboardCacheKey())
    }
}

internal fun retainedDashboardRefreshSnapshot(
    cached: CachedDashboardStatus?,
    displayed: NativeDashboardSnapshot?,
): NativeDashboardSnapshot? = cached?.dashboard ?: displayed

internal fun DashboardResponseBudget.settleFailedRead(
    reservedBytes: Long,
    failure: Throwable,
) {
    if (
        failure is DashboardV2RouteUnavailableException ||
        failure is NextcloudApiReadFailure && !failure.responseBodyMayHaveStarted
    ) {
        releaseFailed(reservedBytes)
    }
}

internal val sharedDashboardStatusMemoryCache = DashboardStatusMemoryCache()

private fun statusMutationRequest(
    method: NextcloudApiMethod,
    path: String,
    fields: Map<String, String> = emptyMap(),
): NextcloudApiRequest = NextcloudApiRequest(
    method = method,
    relativePath = path,
    contentType = "application/x-www-form-urlencoded",
    body = fields.entries.joinToString("&") { (key, value) ->
        "${key.encodeStatusFormComponent()}=${value.encodeStatusFormComponent()}"
    }.encodeToByteArray().takeIf { fields.isNotEmpty() },
    ocsApiRequest = true,
    maximumResponseBytes = STATUS_RESPONSE_LIMIT_BYTES,
)

private fun Long?.validStatusExpiry(nowEpochSeconds: Long): Long {
    if (this == null) return 0L
    require(this > nowEpochSeconds && this <= nowEpochSeconds + MAX_STATUS_EXPIRY_SECONDS) {
        "The user status expiry is outside the allowed range."
    }
    return this
}

private fun NextcloudApiResponse.requireOcsData(label: String): JsonElement {
    require(status in 200..299) { "Loading $label failed (HTTP $status)." }
    require(body.size.toLong() <= maximumResponseForLabel(label)) { "The $label response is too large." }
    val root = runCatching { dashboardJson.parseToJsonElement(body.decodeToString()).jsonObject }
        .getOrNull() ?: error("The $label response is not valid JSON.")
    val ocs = root["ocs"] as? JsonObject ?: error("The $label response has no OCS envelope.")
    val meta = ocs["meta"] as? JsonObject ?: error("The $label response has no OCS metadata.")
    val statusCode = (meta["statuscode"] as? JsonPrimitive)?.intOrNull
    require(statusCode == 200 || statusCode == 100) { "The $label OCS request failed." }
    return ocs["data"] ?: error("The $label response has no data.")
}

private fun NextcloudApiResponse.requireDashboardItemsObject(label: String): JsonObject {
    return when (val data = requireOcsData(label)) {
        is JsonObject -> data
        is JsonArray -> {
            require(data.isEmpty()) { "The dashboard item response is not an object." }
            JsonObject(emptyMap())
        }
        else -> error("The dashboard item response is not an object.")
    }
}

private fun JsonArray.parseDashboardItemList(widgetId: String): List<NativeDashboardItem> {
    require(size <= MAX_DASHBOARD_ITEMS_PER_WIDGET) {
        "The dashboard widget returned too many items."
    }
    return mapIndexed { index, element ->
        val item = element as? JsonObject ?: error("Dashboard item $index is not an object.")
        NativeDashboardItem(
            widgetId = widgetId,
            title = item.requiredDashboardText("title", MAX_DASHBOARD_TEXT_LENGTH),
            subtitle = item.optionalDashboardText("subtitle", MAX_DASHBOARD_TEXT_LENGTH),
            link = item.optionalDashboardLink("link"),
            iconUrl = item.optionalDashboardLink("iconUrl"),
            overlayIconUrl = item.optionalDashboardLink("overlayIconUrl"),
            sinceId = item.requiredDashboardText("sinceId", MAX_DASHBOARD_CURSOR_LENGTH),
        )
    }
}

private fun maximumResponseForLabel(label: String): Long =
    if ("dashboard" in label) DASHBOARD_RESPONSE_LIMIT_BYTES else STATUS_RESPONSE_LIMIT_BYTES

private fun JsonObject.requiredDashboardText(name: String, maximumLength: Int): String =
    optionalDashboardText(name, maximumLength) ?: error("The dashboard response has no valid $name.")

private fun JsonObject.optionalDashboardText(name: String, maximumLength: Int): String? {
    val value = (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
    require(value.isSafeDashboardText(maximumLength)) { "The dashboard $name is invalid." }
    return value
}

private fun JsonObject.requiredBoolean(name: String): Boolean =
    optionalBoolean(name) ?: error("The response has no valid $name capability.")

private fun JsonObject.optionalBoolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.optionalInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.optionalLong(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.integerSet(name: String): Set<Int> {
    val element = this[name] ?: return emptySet()
    val values = when (element) {
        is JsonArray -> element
        is JsonPrimitive -> listOf(element)
        else -> error("The dashboard $name is invalid.")
    }.map { value ->
        (value as? JsonPrimitive)?.intOrNull?.also { require(it in 1..MAX_DASHBOARD_API_VERSION) }
            ?: error("The dashboard $name is invalid.")
    }
    require(values.distinct().size == values.size) { "The dashboard $name contains duplicates." }
    return values.toSet()
}

private fun JsonObject.statusExpiryOption(name: String): NativeStatusExpiryOption? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val objectValue = element as? JsonObject ?: error("The predefined status $name is invalid.")
    val type = objectValue.requiredDashboardText("type", MAX_DASHBOARD_TYPE_LENGTH)
    val time = objectValue.optionalLong("time")?.also { require(it >= 0L) }
        ?: error("The predefined status expiry is invalid.")
    return NativeStatusExpiryOption(type, time)
}

private fun JsonObject.requiredDashboardLink(name: String): String =
    optionalDashboardLink(name) ?: error("The dashboard response has no valid $name.")

private fun JsonObject.optionalDashboardLink(name: String): String? {
    val value = optionalDashboardText(name, MAX_DASHBOARD_LINK_LENGTH) ?: return null
    require(value.isSafeDashboardLink()) { "The dashboard $name is unsafe." }
    return value
}

private fun String.isSafeDashboardLink(): Boolean {
    if (any { it.isISOControl() || it.isWhitespace() } || '\\' in this || startsWith("//")) return false
    if (startsWith('/')) {
        return split('/').none { segment ->
            val decodedDots = segment.replace("%2e", ".", ignoreCase = true)
            decodedDots == "." || decodedDots == ".."
        }
    }
    if (!startsWith("https://")) return false
    val authority = removePrefix("https://").substringBefore('/')
    return authority.isNotBlank() && '@' !in authority
}

private fun String.isDashboardIdentifier(): Boolean =
    isNotBlank() && length <= MAX_DASHBOARD_ID_LENGTH &&
        all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

private fun String.isSafeDashboardText(maximumLength: Int): Boolean =
    isNotBlank() && length <= maximumLength && none(Char::isISOControl)

private fun String.encodeStatusFormComponent(): String = buildString {
    for (byte in encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val unreserved = value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code || value in listOf('-'.code, '.'.code, '_'.code, '~'.code)
        if (unreserved) append(value.toChar()) else {
            append('%')
            append(STATUS_HEX[value ushr 4])
            append(STATUS_HEX[value and 0x0F])
        }
    }
}

private fun NextcloudSession.dashboardCacheKey(): String =
    serverUrl.trim().trimEnd('/').lowercase() + '\u0000' + loginName

private val dashboardJson = Json { ignoreUnknownKeys = true }

private const val USER_STATUS_BASE_PATH = "/ocs/v2.php/apps/user_status/api/v1/user_status"
private const val DASHBOARD_RESPONSE_LIMIT_BYTES = 4L * 1024L * 1024L
internal const val DASHBOARD_ITEM_RESPONSE_LIMIT_BYTES = 512L * 1024L
internal const val DASHBOARD_REFRESH_RESPONSE_BUDGET_BYTES = 8L * 1024L * 1024L
private const val STATUS_RESPONSE_LIMIT_BYTES = 1L * 1024L * 1024L
private const val MAX_DASHBOARD_WIDGETS = 128
private const val MAX_DASHBOARD_ITEMS_PER_WIDGET = 100
private const val MAX_DASHBOARD_ACTIONS = 16
private const val MAX_DASHBOARD_ID_LENGTH = 128
private const val MAX_DASHBOARD_CURSOR_LENGTH = 1_024
private const val MAX_DASHBOARD_TEXT_LENGTH = 4_096
private const val MAX_DASHBOARD_LINK_LENGTH = 8_192
private const val MAX_DASHBOARD_TYPE_LENGTH = 128
private const val MAX_DASHBOARD_API_VERSION = 32
internal const val MAX_CONCURRENT_DASHBOARD_ITEM_REQUESTS = 4
private const val MIN_DASHBOARD_RELOAD_SECONDS = 5
private const val MAX_DASHBOARD_RELOAD_SECONDS = 86_400
private const val MAX_PREDEFINED_STATUSES = 128
private const val MAX_STATUS_MESSAGE_LENGTH = 512
private const val MAX_STATUS_ICON_LENGTH = 32
private const val MAX_STATUS_EXPIRY_SECONDS = 366L * 24L * 60L * 60L
private const val DASHBOARD_STATUS_CACHE_TTL_SECONDS = 60L
private const val STATUS_HEX = "0123456789ABCDEF"
