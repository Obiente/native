package dev.obiente.nextcloudnative.nativeui.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
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
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState

/**
 * Synthetic proof that shared board interactions do not depend on the Deck domain adapter.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Dynamic board interaction QA",
        state = rememberWindowState(width = 1_180.dp, height = 760.dp),
    ) {
        NextcloudNativeTheme(darkTheme = true) {
            CompositionLocalProvider(
                LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                    isDesktop = true,
                    usesDenseControls = true,
                    supportsAuxiliaryPane = true,
                ),
            ) {
                var records by remember { mutableStateOf(dynamicBoardRecords()) }
                val executor = NativeActionExecutor { request ->
                    val submit = request as? NativeActionRequest.Submit
                        ?: return@NativeActionExecutor NativeActionExecutionResult.Failure(
                            "The synthetic board accepts move submissions only.",
                        )
                    val recordId = submit.values["id"]
                        ?: return@NativeActionExecutor NativeActionExecutionResult.Failure(
                            "The synthetic move has no record identity.",
                        )
                    val destination = submit.values["stackId"]
                        ?: return@NativeActionExecutor NativeActionExecutionResult.Failure(
                            "The synthetic move has no destination.",
                        )
                    records = records.map { record ->
                        if (record.id == recordId) {
                            record.copy(values = record.values + ("stackId" to destination))
                        } else {
                            record
                        }
                    }
                    NativeActionExecutionResult.Success()
                }
                NextcloudAppBackground {
                    GenericNativeAppScreen(
                        schema = dynamicBoardSchema(),
                        view = dynamicBoardView(),
                        state = NativeScreenState.Ready(records),
                        actionExecutor = executor,
                        onActionSucceeded = { _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private fun dynamicBoardSchema(): NativeAppSchema {
    val resource = ResourceSpec(
        id = "work-items",
        name = "Work items",
        confidence = Confidence.verified,
        fields = listOf(
            FieldSpec(
                id = "title",
                label = "Title",
                kind = FieldKind.string,
                required = true,
                readOnly = false,
            ),
            FieldSpec(
                id = "stackId",
                label = "List",
                kind = FieldKind.string,
                required = true,
                readOnly = false,
            ),
        ),
    )
    val move = ActionSpec(
        id = "work-items.move",
        label = "Move item",
        resourceId = resource.id,
        binding = ApiBinding(
            method = HttpMethod.PUT,
            path = "/api/work-items/{id}",
            operationId = "moveWorkItem",
            pathParameterNames = listOf("id"),
            requiredPathParameterNames = listOf("id"),
            bodyFieldNames = listOf("stackId"),
            requiredBodyFieldNames = listOf("stackId"),
            bodyContentType = "application/json",
        ),
        intent = ActionIntent.update,
        risk = ActionRisk.mutating,
        requiresConfirmation = false,
        confidence = Confidence.verified,
    )
    return NativeAppSchema(
        schemaVersion = "0.1",
        app = AppIdentity("workflow", "Workflow", "1"),
        confidence = Confidence.verified,
        resources = listOf(resource),
        views = listOf(dynamicBoardView()),
        actions = listOf(move),
    )
}

private fun dynamicBoardView() = ViewSpec(
    id = "work-items.board",
    title = "Workflow",
    resourceId = "work-items",
    component = NativeComponent.board,
    sourceActionId = "",
    confidence = Confidence.verified,
)

private fun dynamicBoardRecords() = listOf(
    dynamicBoardRecord("1", "Plan release", "Planned"),
    dynamicBoardRecord("2", "Review packages", "Planned"),
    dynamicBoardRecord("3", "Exercise adaptive drag", "In progress"),
    dynamicBoardRecord("4", "Verify repository hygiene", "Done"),
)

private fun dynamicBoardRecord(
    id: String,
    title: String,
    stackId: String,
) = NativeRecord(
    id = id,
    values = mapOf(
        "id" to id,
        "title" to title,
        "stackId" to stackId,
    ),
)
