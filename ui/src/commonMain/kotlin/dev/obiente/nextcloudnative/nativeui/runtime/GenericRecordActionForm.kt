package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.launch

internal sealed interface PendingNativeRecordActionForm {
    val action: ActionSpec
    val fields: List<FieldSpec>
    val initialValues: Map<String, String>
    val itemLabel: String
    val resource: ResourceSpec
    val datasetContext: NativeDatasetContext
    val restoreKey: String
    val mutationRecoveryOwner: NativeFormMutationRecoveryOwner
    val operationLabel: String

    fun request(
        scalarInputValues: Map<String, String>,
        repeatableObjectValues: Map<String, List<RepeatableObjectInputRow>>,
        confirmed: Boolean,
    ): NativeActionRequest.Submit
}

internal data class PendingNativeRecordFormAction(
    val plan: NativeRecordFormActionPlan,
    override val itemLabel: String,
    override val resource: ResourceSpec,
    override val datasetContext: NativeDatasetContext,
    override val restoreKey: String,
    override val mutationRecoveryOwner: NativeFormMutationRecoveryOwner,
    val createMutationRecoveryPlan: NativeCreateMutationRecoveryPlan?,
) : PendingNativeRecordActionForm {
    override val action: ActionSpec
        get() = plan.action
    override val fields: List<FieldSpec>
        get() = plan.fields
    override val initialValues: Map<String, String>
        get() = plan.initialValues
    override val operationLabel: String
        get() = when (plan.kind) {
            NativeRecordFormActionKind.Create -> "Create"
            NativeRecordFormActionKind.Edit -> "Edit"
        }

    override fun request(
        scalarInputValues: Map<String, String>,
        repeatableObjectValues: Map<String, List<RepeatableObjectInputRow>>,
        confirmed: Boolean,
    ): NativeActionRequest.Submit = plan.requestWithStructuredInput(
        scalarInputValues = scalarInputValues,
        repeatableObjectValues = repeatableObjectValues,
        confirmed = confirmed,
    )
}

internal data class PendingNativeRecordCommandFormAction(
    val plan: NativeRecordCommandFormActionPlan,
    override val itemLabel: String,
    override val resource: ResourceSpec,
    override val datasetContext: NativeDatasetContext,
    override val restoreKey: String,
    override val mutationRecoveryOwner: NativeFormMutationRecoveryOwner,
) : PendingNativeRecordActionForm {
    override val action: ActionSpec
        get() = plan.action
    override val fields: List<FieldSpec>
        get() = plan.fields
    override val initialValues: Map<String, String>
        get() = plan.initialValues
    override val operationLabel: String
        get() = plan.action.label

    override fun request(
        scalarInputValues: Map<String, String>,
        repeatableObjectValues: Map<String, List<RepeatableObjectInputRow>>,
        confirmed: Boolean,
    ): NativeActionRequest.Submit = plan.requestWithStructuredInput(
        scalarInputValues = scalarInputValues,
        repeatableObjectValues = repeatableObjectValues,
        confirmed = confirmed,
    )
}

@Composable
internal fun GenericRecordActionForm(
    pending: PendingNativeRecordActionForm,
    schema: NativeAppSchema,
    actionExecutor: NativeActionExecutor,
    filePicker: NativeFileFieldPicker?,
    pendingMutationStore: NativePendingMutationStore?,
    mutationRecovery: NativeFormMutationRecoveryState?,
    onMutationStarted: (NativeFormMutationRecoveryOwner) -> Unit,
    onMutationFinished: (NativeFormMutationRecoveryOwner, NativeActionExecutionResult) -> Unit,
    onDismiss: () -> Unit,
    onActionSucceeded: (ActionSpec) -> Unit,
    presentation: NativeRecordFormPresentation = NativeRecordFormPresentation.Dialog,
) {
    val scalarFields = remember(pending.fields) {
        pending.fields.filter { field -> field.repeatableObjectInput == null }
    }
    val displayFields = remember(pending.fields, pending.resource, pending.datasetContext, schema) {
        nativeFormDisplayFields(
            fields = pending.fields,
            relationFieldIds = pending.fields
                .filter { field ->
                    nativeRelationFieldRequiresChoice(field, pending.resource, schema, pending.datasetContext)
                }
                .mapTo(linkedSetOf(), FieldSpec::id),
        )
    }
    val structuredSpecs = remember(pending.fields) {
        pending.fields.mapNotNull { field ->
            field.repeatableObjectInput?.let { spec -> field.id to spec }
        }.toMap()
    }
    val draftSaver = remember(scalarFields) {
        nativeRecordFormDraftSaver(scalarFields.mapTo(linkedSetOf(), FieldSpec::id))
    }
    var values by rememberSaveable(pending.restoreKey, stateSaver = draftSaver) {
        mutableStateOf(
            pending.initialValues.filterKeys { fieldId ->
                fieldId !in structuredSpecs
            },
        )
    }
    val initialStructuredDraft = remember(pending.restoreKey, structuredSpecs, pending.initialValues) {
        initialNativeRepeatableObjectDraft(pending.fields, pending.initialValues)
    }
    val emptyStructuredDraft = remember(pending.restoreKey, structuredSpecs) {
        requireNotNull(initialNativeRepeatableObjectDraft(pending.fields, emptyMap()))
    }
    val structuredDraftSaver = remember(structuredSpecs) {
        nativeRepeatableObjectDraftStateSaver(structuredSpecs)
    }
    val structuredDraft = rememberSaveable(
        "${pending.restoreKey}:structured",
        structuredSpecs,
        saver = structuredDraftSaver,
    ) {
        NativeRepeatableObjectDraftState(initialStructuredDraft, structuredSpecs)
    }
    val repeatableObjectValues = structuredDraft.values
    val structuredDraftSafe = structuredDraft.editable
    var error by remember(pending.restoreKey) {
        mutableStateOf(
            if (initialStructuredDraft == null) {
                "The existing structured value could not be edited safely."
            } else {
                null
            },
        )
    }
    var awaitingConfirmation by rememberSaveable(pending.restoreKey) { mutableStateOf(false) }
    var submitting by remember(pending.restoreKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val operation = pending.operationLabel
    val formTitle = if (
        pending is PendingNativeRecordFormAction &&
        pending.plan.kind == NativeRecordFormActionKind.Create
    ) {
        pending.action.label
    } else {
        "$operation ${pending.itemLabel}"
    }

    fun submit(confirmed: Boolean) {
        val request = runCatching {
            pending.request(
                scalarInputValues = values,
                repeatableObjectValues = repeatableObjectValues,
                confirmed = confirmed,
            )
        }.getOrElse { failure ->
            error = failure.message ?: "The values could not be submitted."
            return
        }
        submitting = true
        error = null
        onMutationStarted(pending.mutationRecoveryOwner)
        scope.launch {
            val createRecoveryPlan = (pending as? PendingNativeRecordFormAction)
                ?.createMutationRecoveryPlan
            val result = if (createRecoveryPlan != null) {
                val store = pendingMutationStore
                if (store == null) {
                    NativeActionExecutionResult.Failure(
                        "Crash-safe create staging is unavailable on this platform.",
                        NativeActionFailureOutcome.Rejected,
                    )
                } else {
                    executeNativeCreateMutation(
                        plan = createRecoveryPlan,
                        request = request,
                        actionExecutor = actionExecutor,
                        pendingMutationStore = store,
                    )
                }
            } else {
                actionExecutor.execute(request)
            }
            onMutationFinished(pending.mutationRecoveryOwner, result)
            when (result) {
                is NativeActionExecutionResult.Success -> onActionSucceeded(pending.action)
                is NativeActionExecutionResult.Failure -> {
                    error = result.message
                    awaitingConfirmation = false
                }
            }
            submitting = false
        }
    }
    val outcomeUnknown =
        mutationRecovery?.owner == pending.mutationRecoveryOwner &&
            mutationRecovery.phase == NativeFormMutationRecoveryPhase.AwaitingReconciliation
    val formRetryAllowed = mutationRecovery == null
    val closeRequest = rememberNativeInlineEditorCloseRequest(
        enabled = presentation == NativeRecordFormPresentation.Inline,
        dirty = values != pending.initialValues.filterKeys { it !in structuredSpecs } ||
            repeatableObjectValues != initialStructuredDraft,
        submissionBlocked = submitting || !formRetryAllowed,
        onClose = onDismiss,
        allowReconciliationRefresh = outcomeUnknown && !submitting,
    )

    NativeRecordFormPresentationHost(
        presentation = if (awaitingConfirmation) NativeRecordFormPresentation.Dialog else presentation,
        onDismissRequest = { if (!submitting) closeRequest() },
        title = {
            Text(
                if (outcomeUnknown) {
                    "$operation result unknown"
                } else if (awaitingConfirmation) {
                    "Confirm ${operation.lowercase()}"
                } else {
                    formTitle
                },
            )
        },
        text = {
            if (awaitingConfirmation) {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "${pending.action.label} will change server data for ${pending.itemLabel}. Continue?",
                    )
                    error?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().then(
                        if (presentation == NativeRecordFormPresentation.Dialog) {
                            Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                        } else Modifier,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    if (pending.fields.isEmpty()) {
                        Text("No additional information is needed.")
                    } else {
                        displayFields.forEach { field ->
                            val repeatableSpec = field.repeatableObjectInput
                            if (repeatableSpec != null) {
                                GenericRepeatableObjectField(
                                    field = field,
                                    spec = repeatableSpec,
                                    rows = repeatableObjectValues[field.id].orEmpty(),
                                    error = structuredDraft.error,
                                    enabled = !submitting && formRetryAllowed && structuredDraftSafe,
                                    onRowsChange = { rows ->
                                        structuredDraft.update(field.id, rows)
                                        error = null
                                    },
                                )
                                return@forEach
                            }
                            val relationOptions = nativeRelationOptions(
                                field = field,
                                formResource = pending.resource,
                                schema = schema,
                                context = pending.datasetContext,
                            )
                            if (
                                nativeRelationFieldRequiresChoice(
                                    field,
                                    pending.resource,
                                    schema,
                                    pending.datasetContext,
                                )
                            ) {
                                GenericRelationshipField(
                                    field = field,
                                    value = values[field.id].orEmpty(),
                                    options = relationOptions,
                                    choicesLoaded = nativeRelationChoicesLoaded(
                                        field,
                                        pending.resource,
                                        schema,
                                        pending.datasetContext,
                                    ),
                                    choiceSourceHasRecords = nativeRelationChoiceSourceHasRecords(
                                        field,
                                        pending.resource,
                                        schema,
                                        pending.datasetContext,
                                    ),
                                    choiceUnavailableReason = nativeRelationChoiceUnavailableReason(
                                        field,
                                        pending.resource,
                                        schema,
                                        pending.datasetContext,
                                    ),
                                    paging = nativeRelationPaging(
                                        field,
                                        pending.resource,
                                        schema,
                                        pending.datasetContext,
                                    ),
                                    error = null,
                                    enabled = !submitting && formRetryAllowed,
                                    onValueChange = { value ->
                                        values = values + (field.id to value)
                                        error = null
                                    },
                                )
                            } else {
                                GenericFormField(
                                    field = field,
                                    value = values[field.id].orEmpty(),
                                    error = null,
                                    enabled = !submitting && formRetryAllowed,
                                    filePicker = filePicker,
                                    onValueChange = { value ->
                                        values = values + (field.id to value)
                                        error = null
                                    },
                                )
                            }
                        }
                    }
                    error?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!structuredDraftSafe && structuredSpecs.isNotEmpty()) {
                        OutlinedButton(
                            enabled = !submitting && formRetryAllowed,
                            onClick = {
                                structuredDraft.replace(emptyStructuredDraft)
                                error = null
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Reset structured fields for ${pending.action.id}"
                            },
                        ) {
                            Text("Reset structured items")
                        }
                    }
                    if (outcomeUnknown) {
                        Text(
                            "The data is being refreshed to check the server result. " +
                                "Review the refreshed data before trying this action again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (awaitingConfirmation) {
                        awaitingConfirmation = false
                        error = null
                    } else {
                        closeRequest()
                    }
                },
            ) {
                Text(
                    when {
                        outcomeUnknown -> "Close"
                        awaitingConfirmation -> "Back"
                        else -> "Cancel"
                    },
                )
            }
        },
        confirmButton = {
            if (formRetryAllowed || submitting) {
                Button(
                    enabled = !submitting && structuredDraftSafe,
                    onClick = {
                        when {
                            awaitingConfirmation -> submit(confirmed = true)
                            pending.action.requiresConfirmation -> {
                                val validation = runCatching {
                                    pending.request(
                                        scalarInputValues = values,
                                        repeatableObjectValues = repeatableObjectValues,
                                        confirmed = true,
                                    )
                                }.exceptionOrNull()
                                if (validation == null) {
                                    error = null
                                    awaitingConfirmation = true
                                } else {
                                    error = validation.message ?: "The values could not be submitted."
                                }
                            }
                            else -> submit(confirmed = false)
                        }
                    },
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(when {
                            awaitingConfirmation -> "Confirm"
                            presentation == NativeRecordFormPresentation.Inline -> "Save changes"
                            else -> operation
                        })
                    }
                }
            }
        },
    )
}
