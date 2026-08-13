package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestinationSection
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationHost
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.resolveNextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.ApiBinding
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

/**
 * Inventory of production-generic behavior exercised by the capture-only Tables fixture.
 *
 * The app identity is explicit so public evidence looks like the product people use. All rows,
 * paths, identities, and actions remain deterministic synthetic data and never contact a server.
 */
internal enum class MarketingDynamicUiFeature {
    ContractIdentity,
    RecordVisualIdentity,
    NestedCollection,
    EnumField,
    OptionalRelationClear,
    LargeRelationSearch,
    BooleanControl,
    DatasetInsights,
    SemanticForm,
    StaleMutationSuppression,
    RelationRetry,
}

internal data class MarketingDynamicUiFixture(
    val appName: String,
    val description: String,
    val iconText: String,
    val accentArgb: Long,
    val breadcrumbs: List<String>,
    val relationOptionCount: Int,
    val features: Set<MarketingDynamicUiFeature>,
)

internal val marketingDynamicUiFixture = MarketingDynamicUiFixture(
    appName = "Tables",
    description = "Community inventory",
    iconText = "T",
    accentArgb = 0xFF00679E,
    breadcrumbs = listOf("Tables", "Community inventory", "Rows"),
    relationOptionCount = 240,
    features = MarketingDynamicUiFeature.entries.toSet(),
)

private val marketingDynamicGroupsResource = ResourceSpec(
    id = "locations",
    name = "Locations",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("id", "ID", FieldKind.string, required = true, readOnly = true),
        FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = true),
    ),
)

internal val marketingDynamicWorkItemsResource = ResourceSpec(
    id = "rows",
    name = "Inventory rows",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("item", "Item", FieldKind.string, required = true, readOnly = false),
        FieldSpec(
            id = "category",
            label = "Category",
            kind = FieldKind.enumeration,
            required = true,
            readOnly = false,
            enumValues = listOf("tools", "materials", "safety"),
        ),
        FieldSpec("quantity", "Quantity", FieldKind.integer, required = true, readOnly = false),
        FieldSpec("reorderLevel", "Reorder level", FieldKind.integer, required = false, readOnly = false),
        FieldSpec("locationId", "Location", FieldKind.string, required = false, readOnly = false),
        FieldSpec("active", "Active item", FieldKind.boolean, required = false, readOnly = false),
    ),
)

private val marketingTablesColumnsResource = ResourceSpec(
    id = "columns",
    name = "Columns",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("id", "ID", FieldKind.integer, required = true, readOnly = true),
        FieldSpec("title", "Column", FieldKind.string, required = true, readOnly = false),
        FieldSpec("type", "Type", FieldKind.enumeration, required = true, readOnly = true),
        FieldSpec("description", "Description", FieldKind.string, required = false, readOnly = false),
        FieldSpec("mandatory", "Required", FieldKind.boolean, required = true, readOnly = false),
        FieldSpec("orderWeight", "Order", FieldKind.integer, required = true, readOnly = false),
    ),
)

private val marketingTablesViewsResource = ResourceSpec(
    id = "views",
    name = "Views",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("id", "ID", FieldKind.integer, required = true, readOnly = true),
        FieldSpec("title", "View", FieldKind.string, required = true, readOnly = false),
        FieldSpec("rows", "Rows", FieldKind.integer, required = false, readOnly = true),
        FieldSpec("columns", "Columns", FieldKind.integer, required = false, readOnly = true),
        FieldSpec("lastEdit", "Last edited", FieldKind.dateTime, required = false, readOnly = true),
        FieldSpec("shareCount", "Shares", FieldKind.integer, required = false, readOnly = true),
    ),
)

private val marketingTablesSharesResource = ResourceSpec(
    id = "shares",
    name = "Shares",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("id", "ID", FieldKind.integer, required = true, readOnly = true),
        FieldSpec("receiverDisplayName", "Person or group", FieldKind.string, required = true, readOnly = true),
        FieldSpec("receiverType", "Type", FieldKind.enumeration, required = true, readOnly = true),
        FieldSpec("permissionRead", "Read", FieldKind.boolean, required = true, readOnly = false),
        FieldSpec("permissionCreate", "Create", FieldKind.boolean, required = true, readOnly = false),
        FieldSpec("permissionUpdate", "Update", FieldKind.boolean, required = true, readOnly = false),
        FieldSpec("permissionDelete", "Delete", FieldKind.boolean, required = true, readOnly = false),
        FieldSpec("permissionManage", "Manage", FieldKind.boolean, required = true, readOnly = false),
        FieldSpec("displayMode", "Display mode", FieldKind.enumeration, required = false, readOnly = false),
    ),
)

private val marketingDynamicListView = ViewSpec(
    id = "rows.collection",
    title = "Rows",
    resourceId = marketingDynamicWorkItemsResource.id,
    component = NativeComponent.dataTable,
    sourceActionId = "rows.list",
    confidence = Confidence.verified,
)

private val marketingDynamicInsightsView = ViewSpec(
    id = "rows.insights",
    title = "Insights",
    resourceId = marketingDynamicWorkItemsResource.id,
    component = NativeComponent.dashboard,
    sourceActionId = marketingDynamicListView.sourceActionId,
    confidence = Confidence.verified,
)

private val marketingDynamicFormView = ViewSpec(
    id = "rows.create",
    title = "Add inventory row",
    resourceId = marketingDynamicWorkItemsResource.id,
    component = NativeComponent.form,
    sourceActionId = "rows.create",
    confidence = Confidence.verified,
)

private val marketingTablesColumnsView = ViewSpec(
    id = "columns.collection",
    title = "Columns",
    resourceId = marketingTablesColumnsResource.id,
    component = NativeComponent.dataTable,
    sourceActionId = "api1-index-table-columns",
    confidence = Confidence.verified,
)

private val marketingTablesViewsView = ViewSpec(
    id = "views.collection",
    title = "Views",
    resourceId = marketingTablesViewsResource.id,
    component = NativeComponent.dataTable,
    sourceActionId = "api1-index-views",
    confidence = Confidence.verified,
)

private val marketingTablesSharesView = ViewSpec(
    id = "shares.collection",
    title = "Share",
    resourceId = marketingTablesSharesResource.id,
    component = NativeComponent.dataTable,
    sourceActionId = "api1-index-table-shares",
    confidence = Confidence.verified,
)

private val marketingTablesShareDetailView = ViewSpec(
    id = "shares.detail",
    title = "Workshop team permissions",
    resourceId = marketingTablesSharesResource.id,
    component = NativeComponent.detail,
    sourceActionId = "api1-get-share",
    confidence = Confidence.verified,
)

private fun marketingTablesReadAction(
    id: String,
    label: String,
    resourceId: String,
    path: String,
    pathParameterName: String = "id",
    intent: ActionIntent = ActionIntent.list,
) = ActionSpec(
    id = id,
    label = label,
    resourceId = resourceId,
    binding = ApiBinding(
        method = HttpMethod.GET,
        path = path,
        operationId = id,
        pathParameterNames = listOf(pathParameterName),
        requiredPathParameterNames = listOf(pathParameterName),
    ),
    intent = intent,
    risk = ActionRisk.readOnly,
    requiresConfirmation = false,
    confidence = Confidence.verified,
)

private val marketingDynamicCreateAction = ActionSpec(
    id = marketingDynamicFormView.sourceActionId,
    label = "Add inventory row",
    resourceId = marketingDynamicWorkItemsResource.id,
    binding = ApiBinding(
        method = HttpMethod.POST,
        path = "/index.php/apps/tables/api/1/tables/{id}/rows",
        operationId = "api1-create-row-in-table",
        pathParameterNames = listOf("id"),
        requiredPathParameterNames = listOf("id"),
        bodyFieldNames = listOf("item", "category", "quantity", "reorderLevel", "locationId", "active"),
        requiredBodyFieldNames = listOf("item", "category", "quantity"),
        bodyContentType = "application/json",
    ),
    intent = ActionIntent.create,
    risk = ActionRisk.mutating,
    requiresConfirmation = false,
    confidence = Confidence.verified,
)

internal val marketingDynamicUiSchema = NativeAppSchema(
    schemaVersion = "visual-qa",
    app = AppIdentity("tables", "Tables", "fixture"),
    confidence = Confidence.verified,
    resources = listOf(
        marketingDynamicGroupsResource,
        marketingDynamicWorkItemsResource,
        marketingTablesColumnsResource,
        marketingTablesViewsResource,
        marketingTablesSharesResource,
    ),
    views = listOf(
        marketingDynamicInsightsView,
        marketingDynamicListView,
        marketingDynamicFormView,
        marketingTablesColumnsView,
        marketingTablesViewsView,
        marketingTablesSharesView,
        marketingTablesShareDetailView,
    ),
    actions = listOf(
        marketingTablesReadAction(
            id = marketingDynamicListView.sourceActionId,
            label = "List rows",
            resourceId = marketingDynamicWorkItemsResource.id,
            path = "/index.php/apps/tables/api/1/tables/{id}/rows",
        ),
        marketingTablesReadAction(
            id = marketingTablesColumnsView.sourceActionId,
            label = "List columns",
            resourceId = marketingTablesColumnsResource.id,
            path = "/index.php/apps/tables/api/1/tables/{id}/columns",
        ),
        marketingTablesReadAction(
            id = marketingTablesViewsView.sourceActionId,
            label = "List views",
            resourceId = marketingTablesViewsResource.id,
            path = "/index.php/apps/tables/api/1/tables/{id}/views",
        ),
        marketingTablesReadAction(
            id = marketingTablesSharesView.sourceActionId,
            label = "List shares",
            resourceId = marketingTablesSharesResource.id,
            path = "/index.php/apps/tables/api/1/tables/{id}/shares",
        ),
        marketingTablesReadAction(
            id = marketingTablesShareDetailView.sourceActionId,
            label = "Get share",
            resourceId = marketingTablesSharesResource.id,
            path = "/index.php/apps/tables/api/1/shares/{shareId}",
            pathParameterName = "shareId",
            intent = ActionIntent.read,
        ),
        marketingDynamicCreateAction,
    ),
    relationships = listOf(
        ResourceRelationshipSpec(
            parentResourceId = marketingDynamicGroupsResource.id,
            childResourceId = marketingDynamicWorkItemsResource.id,
            parentFieldId = "id",
            childFieldId = "locationId",
            confidence = Confidence.verified,
        ),
    ),
)

internal val marketingDynamicWorkItemRecords = listOf(
    NativeRecord(
        id = "row-1",
        values = mapOf(
            "item" to "Cordless drills",
            "category" to "tools",
            "quantity" to "6",
            "reorderLevel" to "3",
            "locationId" to "location-1",
            "active" to "true",
        ),
    ),
    NativeRecord(
        id = "row-2",
        values = mapOf(
            "item" to "Safety glasses",
            "category" to "safety",
            "quantity" to "18",
            "reorderLevel" to "10",
            "locationId" to "location-2",
            "active" to "true",
        ),
    ),
    NativeRecord(
        id = "row-3",
        values = mapOf(
            "item" to "Timber boards",
            "category" to "materials",
            "quantity" to "24",
            "reorderLevel" to "12",
            "locationId" to "location-1",
            "active" to "true",
        ),
    ),
    NativeRecord(
        id = "row-4",
        values = mapOf(
            "item" to "Work gloves",
            "category" to "safety",
            "quantity" to "14",
            "reorderLevel" to "8",
            "locationId" to "location-2",
            "active" to "true",
        ),
    ),
    NativeRecord(
        id = "row-5",
        values = mapOf(
            "item" to "Paint rollers",
            "category" to "tools",
            "quantity" to "9",
            "reorderLevel" to "4",
            "locationId" to "location-1",
            "active" to "true",
        ),
    ),
    NativeRecord(
        id = "row-6",
        values = mapOf(
            "item" to "Drop cloths",
            "category" to "materials",
            "quantity" to "11",
            "reorderLevel" to "6",
            "locationId" to "location-2",
            "active" to "true",
        ),
    ),
    NativeRecord(
        id = "row-7",
        values = mapOf(
            "item" to "Extension leads",
            "category" to "tools",
            "quantity" to "5",
            "reorderLevel" to "3",
            "locationId" to "location-1",
            "active" to "false",
        ),
    ),
)

private val marketingTablesColumnRecords = listOf(
    marketingTablesColumnRecord("column-1", "1", "Item", "text", "Inventory item name", true, "10"),
    marketingTablesColumnRecord(
        "column-2", "2", "Category", "selection", "Tools, materials, or safety", true, "20",
    ),
    marketingTablesColumnRecord("column-3", "3", "Quantity", "number", "Current stock", true, "30"),
    marketingTablesColumnRecord(
        "column-4", "4", "Reorder level", "number", "Low-stock threshold", false, "40",
    ),
    marketingTablesColumnRecord("column-5", "5", "Location", "selection", "Storage location", false, "50"),
    marketingTablesColumnRecord("column-6", "6", "Active item", "selection", "Available for use", false, "60"),
)

private val marketingTablesViewRecords = listOf(
    marketingTablesViewRecord("view-1", "1", "All inventory", "7", "6", "2026-08-12T20:14:00Z", "2"),
    marketingTablesViewRecord("view-2", "2", "Needs reorder", "2", "5", "2026-08-11T09:42:00Z", "1"),
    marketingTablesViewRecord("view-3", "3", "Safety equipment", "2", "4", "2026-08-08T16:05:00Z", "0"),
)

private val marketingTablesShareRecords = listOf(
    marketingTablesShareRecord("share-1", "1", "Workshop team", "group", true, true, true, "editor"),
    marketingTablesShareRecord("share-2", "2", "Maya Chen", "user", true, false, false, "viewer"),
)

private fun marketingTablesColumnRecord(
    recordId: String,
    id: String,
    title: String,
    type: String,
    description: String,
    mandatory: Boolean,
    orderWeight: String,
) = NativeRecord(
    recordId,
    mapOf(
        "id" to id,
        "title" to title,
        "type" to type,
        "description" to description,
        "mandatory" to mandatory.toString(),
        "orderWeight" to orderWeight,
    ),
)

private fun marketingTablesViewRecord(
    recordId: String,
    id: String,
    title: String,
    rows: String,
    columns: String,
    lastEdit: String,
    shareCount: String,
) = NativeRecord(
    recordId,
    mapOf(
        "id" to id,
        "title" to title,
        "rows" to rows,
        "columns" to columns,
        "lastEdit" to lastEdit,
        "shareCount" to shareCount,
    ),
)

private fun marketingTablesShareRecord(
    recordId: String,
    id: String,
    receiverDisplayName: String,
    receiverType: String,
    permissionRead: Boolean,
    permissionCreate: Boolean,
    permissionUpdate: Boolean,
    displayMode: String,
) = NativeRecord(
    recordId,
    mapOf(
        "id" to id,
        "receiverDisplayName" to receiverDisplayName,
        "receiverType" to receiverType,
        "permissionRead" to permissionRead.toString(),
        "permissionCreate" to permissionCreate.toString(),
        "permissionUpdate" to permissionUpdate.toString(),
        "permissionDelete" to "false",
        "permissionManage" to "false",
        "displayMode" to displayMode,
    ),
)

internal val marketingDynamicRelatedGroupRecords = List(marketingDynamicUiFixture.relationOptionCount) { index ->
    val number = index + 1
    NativeRecord(
        id = "location-$number",
        values = mapOf(
            "id" to "location-$number",
            "title" to when (number) {
                1 -> "Workshop"
                2 -> "Storage room"
                else -> "Location ${number.toString().padStart(3, '0')}"
            },
        ),
    )
}

internal val marketingDynamicDatasetContext = NativeDatasetContext(
    bindingValues = mapOf("id" to "42", "shareId" to "1"),
    relatedRecords = mapOf(marketingDynamicGroupsResource.id to marketingDynamicRelatedGroupRecords),
)

private val marketingCaptureActionExecutor = NativeActionExecutor {
    NativeActionExecutionResult.Failure("Synthetic visual QA actions are disabled.")
}

@Composable
internal fun MarketingDynamicUiScenario(
    scenario: MarketingCaptureScenario,
    fixture: MarketingDynamicUiFixture = marketingDynamicUiFixture,
) {
    require(
        scenario == MarketingCaptureScenario.HomepageAppsDesktopDark ||
            scenario == MarketingCaptureScenario.HomepageAppsDesktopLight ||
            scenario == MarketingCaptureScenario.AdaptiveApp ||
            scenario == MarketingCaptureScenario.TablesRowsDesktop ||
            scenario == MarketingCaptureScenario.TablesRowFormDesktop ||
            scenario == MarketingCaptureScenario.TablesColumnsDesktop ||
            scenario == MarketingCaptureScenario.TablesViewsDesktop ||
            scenario == MarketingCaptureScenario.TablesSharesDesktop ||
            scenario == MarketingCaptureScenario.AdaptiveAppMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppCollectionMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppContextMenuMobile ||
            scenario == MarketingCaptureScenario.TablesColumnsMobile ||
            scenario == MarketingCaptureScenario.TablesViewsMobile ||
            scenario == MarketingCaptureScenario.TablesSharesMobile,
    ) {
        "${scenario.id} is not a Tables capture."
    }
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    Column(modifier = Modifier.fillMaxSize()) {
        MarketingDynamicContractHeader(fixture, compact = !desktop)
        if (scenario == MarketingCaptureScenario.AdaptiveAppContextMenuMobile) {
            MarketingTablesMobileScaffold(
                fixture = fixture,
                selectedDestinationId = marketingDynamicInsightsView.id,
                contentTitle = "Community inventory",
                contentSubtitle = "Overview",
                modifier = Modifier.weight(1f),
            ) {
                GenericNativeAppScreen(
                    schema = marketingDynamicUiSchema,
                    view = marketingDynamicInsightsView,
                    state = NativeScreenState.Ready(marketingDynamicWorkItemRecords),
                    actionExecutor = marketingCaptureActionExecutor,
                    modifier = Modifier.fillMaxSize(),
                    datasetContext = marketingDynamicDatasetContext,
                )
            }
        } else if (
            scenario == MarketingCaptureScenario.HomepageAppsDesktopDark ||
            scenario == MarketingCaptureScenario.HomepageAppsDesktopLight
        ) {
            GenericNativeAppScreen(
                schema = marketingDynamicUiSchema,
                view = marketingDynamicListView,
                state = NativeScreenState.Ready(marketingDynamicWorkItemRecords),
                actionExecutor = marketingCaptureActionExecutor,
                modifier = Modifier.weight(1f),
                datasetContext = marketingDynamicDatasetContext,
                showCollectionCreateAction = true,
            )
        } else if (desktop) {
            val view = when (scenario) {
                MarketingCaptureScenario.TablesRowsDesktop -> marketingDynamicListView
                MarketingCaptureScenario.TablesRowFormDesktop -> marketingDynamicFormView
                MarketingCaptureScenario.TablesColumnsDesktop -> marketingTablesColumnsView
                MarketingCaptureScenario.TablesViewsDesktop -> marketingTablesViewsView
                MarketingCaptureScenario.TablesSharesDesktop -> marketingTablesShareDetailView
                else -> marketingDynamicInsightsView
            }
            MarketingDynamicDesktopCapture(
                fixture = fixture,
                view = view,
                contentTitle = if (view == marketingDynamicFormView) {
                    "Add inventory row"
                } else {
                    "Community inventory"
                },
                contentSubtitle = marketingTablesViewSubtitle(view),
                modifier = Modifier.weight(1f),
            )
        } else if (scenario == MarketingCaptureScenario.AdaptiveAppCollectionMobile) {
            MarketingTablesMobileScaffold(
                fixture = fixture,
                selectedDestinationId = marketingDynamicListView.id,
                contentTitle = "Community inventory",
                contentSubtitle = "Rows",
                modifier = Modifier.weight(1f),
            ) {
                GenericNativeAppScreen(
                    schema = marketingDynamicUiSchema,
                    view = marketingDynamicListView,
                    state = NativeScreenState.Ready(marketingDynamicWorkItemRecords),
                    actionExecutor = marketingCaptureActionExecutor,
                    modifier = Modifier.fillMaxSize(),
                    datasetContext = marketingDynamicDatasetContext,
                    showCollectionCreateAction = true,
                )
            }
        } else if (scenario == MarketingCaptureScenario.AdaptiveAppMobile) {
            MarketingTablesMobileScaffold(
                fixture = fixture,
                selectedDestinationId = marketingDynamicListView.id,
                contentTitle = "Add inventory row",
                contentSubtitle = "Community inventory",
                modifier = Modifier.weight(1f),
            ) {
                GenericNativeAppScreen(
                    schema = marketingDynamicUiSchema,
                    view = marketingDynamicFormView,
                    state = NativeScreenState.Ready(emptyList()),
                    actionExecutor = marketingCaptureActionExecutor,
                    modifier = Modifier.fillMaxSize(),
                    datasetContext = marketingDynamicDatasetContext,
                )
            }
        } else {
            val view = when (scenario) {
                MarketingCaptureScenario.TablesColumnsMobile -> marketingTablesColumnsView
                MarketingCaptureScenario.TablesViewsMobile -> marketingTablesViewsView
                MarketingCaptureScenario.TablesSharesMobile -> marketingTablesShareDetailView
                else -> error("Unsupported Tables mobile capture: ${scenario.id}")
            }
            MarketingTablesMobileScaffold(
                fixture = fixture,
                selectedDestinationId = if (view == marketingTablesShareDetailView) {
                    marketingTablesSharesView.id
                } else {
                    view.id
                },
                contentTitle = "Community inventory",
                contentSubtitle = marketingTablesViewSubtitle(view),
                modifier = Modifier.weight(1f),
            ) {
                GenericNativeAppScreen(
                    schema = marketingDynamicUiSchema,
                    view = view,
                    state = NativeScreenState.Ready(marketingTablesRecords(view)),
                    actionExecutor = marketingCaptureActionExecutor,
                    modifier = Modifier.fillMaxSize(),
                    datasetContext = marketingDynamicDatasetContext,
                    showCollectionCreateAction = false,
                )
            }
        }
        if (!desktop) {
            NextcloudBottomNavigation(
                selected = NextcloudDestination.Apps,
                onSelected = {},
            )
        }
    }
}

@Composable
private fun MarketingTablesMobileScaffold(
    fixture: MarketingDynamicUiFixture,
    selectedDestinationId: String,
    contentTitle: String,
    contentSubtitle: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val destinations = listOf(
        NextcloudCollectionDestination(
            id = marketingDynamicInsightsView.id,
            label = "Overview",
            accessibilityId = marketingDynamicInsightsView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingDynamicListView.id,
            label = "Rows",
            accessibilityId = marketingDynamicListView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingTablesColumnsView.id,
            label = "Columns",
            accessibilityId = marketingTablesColumnsView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingTablesViewsView.id,
            label = "Views",
            accessibilityId = marketingTablesViewsView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingTablesSharesView.id,
            label = "Share",
            accessibilityId = marketingTablesSharesView.sourceActionId,
        ),
    )
    val navigationMode = resolveNextcloudCollectionNavigationMode(
        host = NextcloudCollectionNavigationHost.AdaptiveAndroid,
        availableWidthDp = 390,
        destinationCount = destinations.size,
    )
    NextcloudCollectionWorkspaceScaffold(
        model = NextcloudCollectionNavigationModel.create(
            destinations = destinations,
            selectedDestinationId = selectedDestinationId,
        ),
        mode = navigationMode,
        workspaceLabel = fixture.appName,
        contentTitle = contentTitle,
        contentSubtitle = contentSubtitle,
        onBack = {},
        hasHierarchyBack = false,
        onDestinationSelected = {},
        destinationIcon = { destination ->
            when (destination.id) {
                marketingDynamicInsightsView.id -> NextcloudIcons.Activity
                marketingDynamicListView.id -> NextcloudIcons.Table
                else -> NextcloudIcons.ListView
            }
        },
        modifier = modifier,
        compactHeader = true,
        content = content,
    )
}

@Composable
private fun MarketingDynamicDesktopCapture(
    fixture: MarketingDynamicUiFixture,
    view: ViewSpec,
    contentTitle: String,
    contentSubtitle: String,
    modifier: Modifier,
) {
    val destinations = listOf(
        NextcloudCollectionDestination(
            id = marketingDynamicInsightsView.id,
            label = "Insights",
            supportingText = "Inventory totals and categories",
            accessibilityId = marketingDynamicInsightsView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingDynamicListView.id,
            label = "Rows",
            supportingText = "7 inventory items",
            accessibilityId = marketingDynamicListView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingTablesColumnsView.id,
            label = "Columns",
            supportingText = "Typed table structure",
            accessibilityId = marketingTablesColumnsView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingTablesViewsView.id,
            label = "Views",
            supportingText = "Saved filters and layouts",
            accessibilityId = marketingTablesViewsView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = marketingTablesSharesView.id,
            label = "Share",
            supportingText = "Table access",
            accessibilityId = marketingTablesSharesView.sourceActionId,
            section = NextcloudCollectionDestinationSection.Manage,
        ),
    )
    NextcloudCollectionWorkspaceScaffold(
        model = NextcloudCollectionNavigationModel.create(
            destinations = destinations,
            selectedDestinationId = when (view) {
                marketingDynamicFormView -> marketingDynamicListView.id
                marketingTablesShareDetailView -> marketingTablesSharesView.id
                else -> view.id
            },
        ),
        mode = NextcloudCollectionNavigationMode.Sidebar,
        workspaceLabel = fixture.appName,
        contentTitle = contentTitle,
        contentSubtitle = contentSubtitle,
        onBack = {},
        hasHierarchyBack = true,
        onDestinationSelected = {},
        destinationIcon = { destination ->
            when (destination.id) {
                marketingDynamicInsightsView.id -> NextcloudIcons.Activity
                marketingDynamicListView.id -> NextcloudIcons.Table
                marketingTablesColumnsView.id -> NextcloudIcons.ListView
                marketingTablesViewsView.id -> NextcloudIcons.Apps
                marketingTablesSharesView.id -> NextcloudIcons.People
                else -> NextcloudIcons.Settings
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        GenericNativeAppScreen(
            schema = marketingDynamicUiSchema,
            view = view,
            state = NativeScreenState.Ready(
                marketingTablesRecords(view),
            ),
            actionExecutor = marketingCaptureActionExecutor,
            modifier = Modifier.fillMaxSize(),
            datasetContext = marketingDynamicDatasetContext,
            showCollectionCreateAction = view != marketingDynamicFormView,
        )
    }
}

private fun marketingTablesViewSubtitle(view: ViewSpec): String = when (view) {
    marketingDynamicListView -> "Rows"
    marketingDynamicFormView -> "Community inventory"
    marketingTablesColumnsView -> "Columns"
    marketingTablesViewsView -> "Views"
    marketingTablesSharesView -> "Share"
    marketingTablesShareDetailView -> "Share permissions"
    else -> "Overview"
}

private fun marketingTablesRecords(view: ViewSpec): List<NativeRecord> = when (view) {
    marketingDynamicFormView -> emptyList()
    marketingTablesColumnsView -> marketingTablesColumnRecords
    marketingTablesViewsView -> marketingTablesViewRecords
    marketingTablesSharesView -> marketingTablesShareRecords
    marketingTablesShareDetailView -> marketingTablesShareRecords.take(1)
    else -> marketingDynamicWorkItemRecords
}

@Composable
private fun MarketingDynamicContractHeader(
    fixture: MarketingDynamicUiFixture,
    compact: Boolean,
) {
    val accent = Color(fixture.accentArgb)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) NextcloudSpacing.Medium else NextcloudSpacing.XLarge,
                vertical = if (compact) NextcloudSpacing.Small else NextcloudSpacing.Large,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(if (compact) 42.dp else 56.dp),
                color = accent,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        fixture.iconText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = if (compact) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    fixture.appName,
                    style = if (compact) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    fixture.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
