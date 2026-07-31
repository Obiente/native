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
import androidx.compose.material3.HorizontalDivider
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
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.ResourceRelationshipSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRelationOptionWindow
import dev.obiente.nextcloudnative.nativeui.runtime.nativeRelationOptions
import dev.obiente.nextcloudnative.nativeui.runtime.nativeScalarRelationClearChoice

/**
 * Inventory of the production-generic behavior exercised by this capture-only fixture.
 *
 * The fixture deliberately contains no installed app identity, endpoint, account, or server data.
 */
internal enum class MarketingDynamicUiFeature {
    ContractIdentity,
    RecordVisualIdentity,
    NestedCollection,
    EnumField,
    OptionalRelationClear,
    LargeRelationSearch,
    BooleanControl,
    RecurrenceControl,
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
    appName = "Community workspace",
    description = "A contract-driven nested collection with reusable native controls.",
    iconText = "CW",
    accentArgb = 0xFF5B5BD6,
    breadcrumbs = listOf("Projects", "Garden renewal", "Work items"),
    relationOptionCount = 240,
    features = MarketingDynamicUiFeature.entries.toSet(),
)

private val marketingDynamicGroupsResource = ResourceSpec(
    id = "groups",
    name = "Groups",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("id", "ID", FieldKind.string, required = true, readOnly = true),
        FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = true),
    ),
)

internal val marketingDynamicWorkItemsResource = ResourceSpec(
    id = "work-items",
    name = "Work items",
    confidence = Confidence.verified,
    fields = listOf(
        FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = false),
        FieldSpec("description", "Description", FieldKind.string, required = false, readOnly = true),
        FieldSpec("icon", "Icon", FieldKind.string, required = false, readOnly = true),
        FieldSpec("color", "Color", FieldKind.string, required = false, readOnly = true),
        FieldSpec(
            id = "status",
            label = "Status",
            kind = FieldKind.enumeration,
            required = true,
            readOnly = false,
            enumValues = listOf("planned", "in-progress", "ready"),
        ),
        FieldSpec("groupId", "Parent group", FieldKind.string, required = false, readOnly = false),
        FieldSpec("sendReminders", "Send reminders", FieldKind.boolean, required = false, readOnly = false),
        FieldSpec("rrule", "Repeat", FieldKind.string, required = false, readOnly = false),
    ),
)

private val marketingDynamicListView = ViewSpec(
    id = "work-items.collection",
    title = "Work items",
    resourceId = marketingDynamicWorkItemsResource.id,
    component = NativeComponent.collectionList,
    sourceActionId = "work-items.list",
    confidence = Confidence.verified,
)

private val marketingDynamicFormView = ViewSpec(
    id = "work-items.create",
    title = "Create work item",
    resourceId = marketingDynamicWorkItemsResource.id,
    component = NativeComponent.form,
    sourceActionId = "work-items.create",
    confidence = Confidence.verified,
)

private val marketingDynamicCreateAction = ActionSpec(
    id = marketingDynamicFormView.sourceActionId,
    label = "Create work item",
    resourceId = marketingDynamicWorkItemsResource.id,
    binding = ApiBinding(
        method = HttpMethod.POST,
        path = "/synthetic/work-items",
        operationId = "createSyntheticWorkItem",
        bodyFieldNames = listOf("title", "status", "groupId", "sendReminders", "rrule"),
        requiredBodyFieldNames = listOf("title", "status"),
        bodyContentType = "application/json",
    ),
    intent = ActionIntent.create,
    risk = ActionRisk.mutating,
    requiresConfirmation = false,
    confidence = Confidence.verified,
)

internal val marketingDynamicUiSchema = NativeAppSchema(
    schemaVersion = "visual-qa",
    app = AppIdentity("synthetic-dynamic-ui", "Synthetic dynamic UI", "fixture"),
    confidence = Confidence.verified,
    resources = listOf(marketingDynamicGroupsResource, marketingDynamicWorkItemsResource),
    views = listOf(marketingDynamicListView, marketingDynamicFormView),
    actions = listOf(marketingDynamicCreateAction),
    relationships = listOf(
        ResourceRelationshipSpec(
            parentResourceId = marketingDynamicGroupsResource.id,
            childResourceId = marketingDynamicWorkItemsResource.id,
            parentFieldId = "id",
            childFieldId = "groupId",
            confidence = Confidence.verified,
        ),
    ),
)

internal val marketingDynamicWorkItemRecords = listOf(
    NativeRecord(
        id = "work-item-1",
        values = mapOf(
            "title" to "Map planting beds",
            "description" to "Prepare a clear layout for volunteers.",
            "icon" to "garden",
            "color" to "5B5BD6",
            "status" to "in-progress",
            "groupId" to "group-1",
            "sendReminders" to "true",
            "rrule" to "FREQ=WEEKLY;INTERVAL=2",
        ),
    ),
    NativeRecord(
        id = "work-item-2",
        values = mapOf(
            "title" to "Confirm volunteer schedule",
            "description" to "Check availability for the next work day.",
            "icon" to "calendar",
            "color" to "2F9E44",
            "status" to "planned",
            "groupId" to "group-2",
            "sendReminders" to "false",
            "rrule" to "",
        ),
    ),
    NativeRecord(
        id = "work-item-3",
        values = mapOf(
            "title" to "Review materials",
            "description" to "Verify the shared tools and supplies list.",
            "icon" to "tools",
            "color" to "D97706",
            "status" to "ready",
            "groupId" to null,
            "sendReminders" to "true",
            "rrule" to "FREQ=MONTHLY",
        ),
    ),
)

internal val marketingDynamicRelatedGroupRecords = List(marketingDynamicUiFixture.relationOptionCount) { index ->
    val number = index + 1
    NativeRecord(
        id = "group-$number",
        values = mapOf(
            "id" to "group-$number",
            "title" to when (number) {
                1 -> "Garden team"
                2 -> "Planning group"
                else -> "Workspace group ${number.toString().padStart(3, '0')}"
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
        scenario == MarketingCaptureScenario.AdaptiveApp ||
            scenario == MarketingCaptureScenario.AdaptiveAppMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppCollectionMobile ||
            scenario == MarketingCaptureScenario.AdaptiveAppContextMenuMobile,
    ) {
        "${scenario.id} is not a synthetic dynamic UI capture."
    }
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    Column(modifier = Modifier.fillMaxSize()) {
        MarketingDynamicContractHeader(fixture, compact = !desktop)
        if (scenario == MarketingCaptureScenario.AdaptiveAppContextMenuMobile) {
            MarketingDynamicContextMenuCapture(
                fixture = fixture,
                modifier = Modifier.weight(1f),
            )
        } else if (desktop) {
            MarketingDynamicDesktopCapture(fixture, Modifier.weight(1f))
        } else if (scenario == MarketingCaptureScenario.AdaptiveAppCollectionMobile) {
            GenericNativeAppScreen(
                schema = marketingDynamicUiSchema,
                view = marketingDynamicListView,
                state = NativeScreenState.Ready(marketingDynamicWorkItemRecords),
                actionExecutor = marketingCaptureActionExecutor,
                modifier = Modifier.weight(1f),
                datasetContext = marketingDynamicDatasetContext,
                showCollectionCreateAction = true,
            )
        } else {
            GenericNativeAppScreen(
                schema = marketingDynamicUiSchema,
                view = marketingDynamicFormView,
                state = NativeScreenState.Ready(emptyList()),
                actionExecutor = marketingCaptureActionExecutor,
                modifier = Modifier.weight(1f),
                datasetContext = marketingDynamicDatasetContext,
            )
        }
    }
}

private val marketingContextMenuViews = listOf(
    ViewSpec(
        id = "context.tasks",
        title = "Tasks",
        resourceId = "context-tasks",
        component = NativeComponent.taskList,
        sourceActionId = "context.tasks.list",
        confidence = Confidence.verified,
    ),
    ViewSpec(
        id = "context.notes",
        title = "Notes",
        resourceId = "context-notes",
        component = NativeComponent.collectionList,
        sourceActionId = "context.notes.list",
        confidence = Confidence.verified,
    ),
    ViewSpec(
        id = "context.media",
        title = "Media",
        resourceId = "context-media",
        component = NativeComponent.mediaGrid,
        sourceActionId = "context.media.list",
        confidence = Confidence.verified,
    ),
    ViewSpec(
        id = "context.members",
        title = "Members",
        resourceId = "context-members",
        component = NativeComponent.contactList,
        sourceActionId = "context.members.list",
        confidence = Confidence.verified,
    ),
    ViewSpec(
        id = "context.activity",
        title = "Activity",
        resourceId = "context-activity",
        component = NativeComponent.timeline,
        sourceActionId = "context.activity.list",
        confidence = Confidence.verified,
    ),
    ViewSpec(
        id = "context.preferences",
        title = "Preferences",
        resourceId = "context-preferences",
        component = NativeComponent.dataTable,
        sourceActionId = "context.preferences.read",
        confidence = Confidence.verified,
    ),
)

private val marketingContextMenuSchema = marketingDynamicUiSchema.copy(
    resources = marketingContextMenuViews.map { view ->
        ResourceSpec(
            id = view.resourceId,
            name = view.title,
            confidence = Confidence.verified,
            fields = emptyList(),
        )
    },
    views = marketingContextMenuViews,
    actions = emptyList(),
    relationships = emptyList(),
)

private val marketingContextMenuDestinations = marketingContextMenuViews.map { view ->
    DynamicNavigationDestination(
        layoutId = view.id,
        label = view.title,
        resourceId = view.resourceId,
        actionId = view.sourceActionId,
        pathParameterValues = mapOf("workspaceId" to "synthetic-workspace"),
    ) to NextcloudCollectionDestination(
        id = view.id,
        label = view.title,
        accessibilityId = view.sourceActionId,
        supportingText = when (view.component) {
            NativeComponent.taskList -> "Track and complete shared work"
            NativeComponent.mediaGrid -> "Browse workspace photos and media"
            NativeComponent.contactList -> "Manage workspace members"
            NativeComponent.timeline -> "Review recent workspace activity"
            NativeComponent.dataTable -> "Adjust workspace preferences"
            else -> "Browse and manage ${view.title.lowercase()}"
        },
        section = if (
            view.component == NativeComponent.contactList ||
            view.component == NativeComponent.dataTable
        ) {
            NextcloudCollectionDestinationSection.Manage
        } else {
            NextcloudCollectionDestinationSection.Primary
        },
    )
}

@Composable
private fun MarketingDynamicContextMenuCapture(
    fixture: MarketingDynamicUiFixture,
    modifier: Modifier,
) {
    val navigationModel = NextcloudCollectionNavigationModel.create(
        destinations = marketingContextMenuDestinations.map { (_, destination) -> destination },
        selectedDestinationId = null,
    )
    NextcloudCollectionWorkspaceScaffold(
        model = navigationModel,
        mode = NextcloudCollectionNavigationMode.Drawer,
        workspaceLabel = fixture.appName,
        contentTitle = "Garden renewal",
        contentSubtitle = "Overview",
        onBack = {},
        hasHierarchyBack = true,
        onDestinationSelected = {},
        destinationIcon = { NextcloudIcons.Apps },
        modifier = modifier,
    ) {
        DynamicContextDestinationMenu(
            recordLabel = "Garden renewal",
            destinations = marketingContextMenuDestinations,
            schema = marketingContextMenuSchema,
            onDestinationSelected = { _, _ -> },
        )
    }
}

@Composable
private fun MarketingDynamicDesktopCapture(
    fixture: MarketingDynamicUiFixture,
    modifier: Modifier,
) {
    val destinations = listOf(
        NextcloudCollectionDestination(
            id = marketingDynamicListView.id,
            label = "Work items",
            supportingText = "3 active items",
            accessibilityId = marketingDynamicListView.sourceActionId,
        ),
        NextcloudCollectionDestination(
            id = "notes",
            label = "Notes",
            supportingText = "Shared project notes",
            accessibilityId = "notes.list",
        ),
        NextcloudCollectionDestination(
            id = "media",
            label = "Photos",
            supportingText = "Project photos and files",
            accessibilityId = "media.list",
        ),
        NextcloudCollectionDestination(
            id = "members",
            label = "Members",
            supportingText = "People with workspace access",
            accessibilityId = "members.list",
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
            selectedDestinationId = marketingDynamicListView.id,
        ),
        mode = NextcloudCollectionNavigationMode.Sidebar,
        workspaceLabel = fixture.appName,
        contentTitle = "Work items",
        contentSubtitle = "Garden renewal",
        onBack = {},
        hasHierarchyBack = true,
        onDestinationSelected = {},
        destinationIcon = { destination ->
            when (destination.id) {
                marketingDynamicListView.id -> NextcloudIcons.Task
                "notes" -> NextcloudIcons.File
                "media" -> NextcloudIcons.Photo
                "members" -> NextcloudIcons.People
                else -> NextcloudIcons.Settings
            }
        },
        modifier = modifier.fillMaxSize(),
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
            Surface(
                color = accent.copy(alpha = 0.18f),
                contentColor = accent,
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    "Verified contract",
                    modifier = Modifier.padding(
                        horizontal = NextcloudSpacing.Small,
                        vertical = NextcloudSpacing.XSmall,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * The production relation picker owns its expanded state privately. This capture-only companion
 * uses the same schema relationship resolution, clear choice, search filtering, and bounded
 * option-window functions so that those otherwise pointer-driven states remain visible in a
 * deterministic screenshot.
 */
@Composable
private fun MarketingExpandedRelationCaptureState() {
    val field = marketingDynamicWorkItemsResource.fields.single { it.id == "groupId" }
    val options = nativeRelationOptions(
        field = field,
        formResource = marketingDynamicWorkItemsResource,
        schema = marketingDynamicUiSchema,
        context = marketingDynamicDatasetContext,
    )
    val clearChoice = nativeScalarRelationClearChoice(field)
    val window = nativeRelationOptionWindow(options, query = "")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text("Expanded relation menu (capture state)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Search parent group",
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NextcloudSpacing.Medium,
                    vertical = NextcloudSpacing.Small,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Text("${clearChoice?.label} - ${clearChoice?.supportingText}")
            window.options.take(2).forEach { option ->
                Text("${option.label} - ${option.supportingText}")
            }
            if (window.hasMore) {
                Text(
                    "Showing the first ${window.options.size} of ${options.size} choices. Search to narrow the list.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
