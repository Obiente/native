package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopSidebarApp
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopWorkspaceKind
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
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
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericRecordActionForm
import dev.obiente.nextcloudnative.nativeui.runtime.LocalNativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionFailureOutcome
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeFormMutationKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeFormMutationRecoveryOwner
import dev.obiente.nextcloudnative.nativeui.runtime.NativeInlineEditorNavigation
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecordFormActionKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecordFormActionPlan
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecordFormPresentation
import dev.obiente.nextcloudnative.nativeui.runtime.PendingNativeRecordFormAction

@Composable
internal fun MarketingInlineRecordEditShell(
    scenario: MarketingCaptureScenario,
    fixture: MarketingDemoFixture,
    assets: MarketingCaptureAssets,
) {
    RootShell(
        presentation = scenario.presentation,
        selected = NextcloudDestination.Apps,
        desktopWorkspaceKind = NextcloudDesktopWorkspaceKind.AppWorkspace,
        onSelected = {},
        identity = marketingDesktopIdentity(fixture, assets.avatar).copy(
            recentApp = NextcloudDesktopSidebarApp("synthetic-records", "Project records"),
        ),
        activeAppId = "synthetic-records",
    ) { MarketingInlineRecordEditScenario(scenario) }
}

/** Synthetic contract used only to exercise the real shared record editor. No server is contacted. */
internal val marketingInlineRecordResource = ResourceSpec(
    id = "records", name = "Project records", confidence = Confidence.high,
    fields = listOf(
        FieldSpec("title", "Title", FieldKind.string, required = true, readOnly = false),
        FieldSpec("description", "Description", FieldKind.longText, required = false, readOnly = false),
        FieldSpec("status", "Status", FieldKind.enumeration, required = true, readOnly = false,
            enumValues = listOf("planned", "in progress", "complete")),
        FieldSpec("active", "Active", FieldKind.boolean, required = false, readOnly = false),
    ),
)

internal val marketingInlineRecordAction = ActionSpec(
    id = "edit-record", label = "Edit", resourceId = marketingInlineRecordResource.id,
    binding = ApiBinding(HttpMethod.PATCH, "/synthetic/records/{recordId}", "editRecord",
        bodyFieldNames = marketingInlineRecordResource.fields.map(FieldSpec::id)),
    intent = ActionIntent.update, risk = ActionRisk.mutating, requiresConfirmation = false,
    confidence = Confidence.high,
)

internal val marketingInlineRecordSchema = NativeAppSchema(
    schemaVersion = "visual-qa", app = AppIdentity("synthetic-records", "Project records", "fixture"),
    confidence = Confidence.high, resources = listOf(marketingInlineRecordResource),
    views = emptyList(), actions = listOf(marketingInlineRecordAction),
)

internal val marketingInlineRecordPending = PendingNativeRecordFormAction(
    plan = NativeRecordFormActionPlan(
        kind = NativeRecordFormActionKind.Edit, action = marketingInlineRecordAction,
        fields = marketingInlineRecordResource.fields,
        initialValues = mapOf("title" to "Shared planning notes", "description" to
            "Review the project milestones with the team before the next planning session.",
            "status" to "in progress", "active" to "true"),
        bindingValues = mapOf("recordId" to "synthetic-42"),
    ),
    itemLabel = "Shared planning notes", resource = marketingInlineRecordResource,
    datasetContext = NativeDatasetContext(), restoreKey = "synthetic-record-edit",
    mutationRecoveryOwner = NativeFormMutationRecoveryOwner("synthetic-records", "records",
        "edit-record", "records", NativeFormMutationKind.Update, "synthetic-42"),
    createMutationRecoveryPlan = null,
)

@Composable
internal fun MarketingInlineRecordEditScenario(scenario: MarketingCaptureScenario) {
    require(scenario == MarketingCaptureScenario.InlineRecordEditDesktop ||
        scenario == MarketingCaptureScenario.InlineRecordEditMobile)
    val navigation = remember { NativeInlineEditorNavigation() }
    CompositionLocalProvider(LocalNativeInlineEditorNavigation provides navigation) {
        GenericRecordActionForm(
            pending = marketingInlineRecordPending, schema = marketingInlineRecordSchema,
            actionExecutor = NativeActionExecutor {
                NativeActionExecutionResult.Failure("Synthetic visual QA actions are disabled.",
                    NativeActionFailureOutcome.Rejected)
            },
            filePicker = null, pendingMutationStore = null, mutationRecovery = null,
            onMutationStarted = {}, onMutationFinished = { _, _ -> }, onDismiss = {},
            onActionSucceeded = {}, presentation = NativeRecordFormPresentation.Inline,
        )
    }
}
