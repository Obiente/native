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
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
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

private val marketingDynamicCreateAction = ActionSpec(
    id = marketingDynamicFormView.sourceActionId,
    label = "Add inventory row",
    resourceId = marketingDynamicWorkItemsResource.id,
    binding = ApiBinding(
        method = HttpMethod.POST,
        path = "/synthetic/tables/rows",
        operationId = "createSyntheticTableRow",
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
    resources = listOf(marketingDynamicGroupsResource, marketingDynamicWorkItemsResource),
    views = listOf(marketingDynamicInsightsView, marketingDynamicListView, marketingDynamicFormView),
    actions = listOf(marketingDynamicCreateAction),
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
            scenario == MarketingCaptureScenario.AdaptiveAppMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppCollectionMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppContextMenuMobile,
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
                contentSubtitle = when (view) {
                    marketingDynamicListView -> "Rows"
                    marketingDynamicFormView -> "Community inventory"
                    else -> "Overview"
                },
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
        } else {
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
            id = "columns",
            label = "Columns",
            accessibilityId = "columns.list",
        ),
    )
    NextcloudCollectionWorkspaceScaffold(
        model = NextcloudCollectionNavigationModel.create(
            destinations = destinations,
            selectedDestinationId = selectedDestinationId,
        ),
        mode = NextcloudCollectionNavigationMode.Tabs,
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
            id = "columns",
            label = "Columns",
            supportingText = "Typed table structure",
            accessibilityId = "columns.list",
        ),
        NextcloudCollectionDestination(
            id = "views",
            label = "Views",
            supportingText = "Saved filters and layouts",
            accessibilityId = "views.list",
        ),
        NextcloudCollectionDestination(
            id = "share",
            label = "Share",
            supportingText = "Table access",
            accessibilityId = "share.read",
            section = NextcloudCollectionDestinationSection.Manage,
        ),
        NextcloudCollectionDestination(
            id = "preferences",
            label = "Preferences",
            supportingText = "Workspace behavior and defaults",
            accessibilityId = "preferences.read",
            section = NextcloudCollectionDestinationSection.Manage,
        ),
    )
    NextcloudCollectionWorkspaceScaffold(
        model = NextcloudCollectionNavigationModel.create(
            destinations = destinations,
            selectedDestinationId = when (view) {
                marketingDynamicFormView -> marketingDynamicListView.id
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
                "columns" -> NextcloudIcons.ListView
                "views" -> NextcloudIcons.Apps
                "share" -> NextcloudIcons.People
                else -> NextcloudIcons.Settings
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        GenericNativeAppScreen(
            schema = marketingDynamicUiSchema,
            view = view,
            state = NativeScreenState.Ready(
                if (view == marketingDynamicFormView) emptyList() else marketingDynamicWorkItemRecords,
            ),
            actionExecutor = marketingCaptureActionExecutor,
            modifier = Modifier.fillMaxSize(),
            datasetContext = marketingDynamicDatasetContext,
            showCollectionCreateAction = view != marketingDynamicFormView,
        )
    }
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
