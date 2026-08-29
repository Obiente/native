package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.coroutines.launch

@Composable
internal fun GenericNativeForm(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    initialRecord: NativeRecord?,
    datasetContext: NativeDatasetContext,
    executor: NativeActionExecutor,
    filePicker: NativeFileFieldPicker?,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    onActionOutcomeUnknown: ((ActionSpec) -> Unit)?,
    mutationReconciliationGeneration: Int,
) {
    val action = schema.action(view.sourceActionId)
    if (action == null || action.resourceId != resource.id) {
        GenericRendererError("This form has no matching schema-declared action.")
        return
    }
    val prefillRecord = remember(
        action,
        resource,
        initialRecord,
        datasetContext.parentResourceId,
    ) {
        nativeFormPrefillRecord(
            action = action,
            resource = resource,
            record = initialRecord,
            parentResourceId = datasetContext.parentResourceId,
        )
    }
    val formResource = remember(resource, action, prefillRecord) {
        resource.withObservedSettingsFormTypes(action, prefillRecord)
    }
    val formAction = remember(action, formResource) {
        action.withObservedSettingsInputTypes(formResource)
    }
    val formSchema = remember(schema, formResource, formAction) {
        schema.copy(
            resources = schema.resources.map { existing ->
                if (existing.id == formResource.id) formResource else existing
            },
            actions = schema.actions.map { existing ->
                if (existing.id == formAction.id) formAction else existing
            },
        )
    }
    val currentExecutor = rememberUpdatedState(executor)
    val stableExecutor = remember {
        NativeActionExecutor { request ->
            currentExecutor.value.execute(request)
        }
    }
    val coordinator = remember(formSchema, view) {
        NativeActionCoordinator(formSchema, view, stableExecutor)
    }
    val bindingRecord = remember(
        initialRecord,
        datasetContext.parentResourceId,
        datasetContext.parentRecord,
    ) {
        nativeFormBindingRecord(
            initialRecord = initialRecord,
            parentResourceId = datasetContext.parentResourceId,
            parentRecord = datasetContext.parentRecord,
        )
    }
    val autoBinding = remember(
        action,
        bindingRecord,
        datasetContext.parentResourceId,
        datasetContext.bindingValues,
    ) {
        nativeFormAutoBindingResolution(
            schema = schema,
            action = action,
            resource = formResource,
            record = bindingRecord,
            parentResourceId = datasetContext.parentResourceId,
            navigationValues = datasetContext.bindingValues,
        )
    }
    val autoBoundValues = autoBinding.values
    val initialDraft = remember(formSchema, view, formResource, prefillRecord, autoBoundValues) {
        initialNativeFormDraft(formResource, action, prefillRecord).let { draft ->
            draft.copy(values = draft.values + autoBoundValues)
        }
    }
    var draft by remember(
        formSchema,
        view,
        formResource,
        prefillRecord,
        datasetContext.parentResourceId,
        datasetContext.parentRecord?.id,
        autoBoundValues,
    ) {
        mutableStateOf(initialDraft)
    }
    val scope = rememberCoroutineScope()
    val executionState = coordinator.state
    val validationErrors = (executionState as? NativeActionExecutionState.ValidationFailed)?.fieldErrors.orEmpty()
    val submitting = executionState is NativeActionExecutionState.Running
    val awaitingReconciliation = executionState is NativeActionExecutionState.AwaitingReconciliation
    val submissionBlocked = submitting || awaitingReconciliation
    val fields = editableNativeFields(formResource, action)
        .filterNot { field -> field.id in autoBoundValues }
        .let { editableFields ->
            nativeFormDisplayFields(
                fields = editableFields,
                relationFieldIds = editableFields
                    .filter { field ->
                        nativeRelationFieldRequiresChoice(field, formResource, schema, datasetContext)
                    }
                    .mapTo(linkedSetOf(), FieldSpec::id),
            )
        }
    val uneditableBodyFieldIds = uneditableNativeBodyFieldIds(
        action = action,
        editableFields = fields,
        autoBoundValues = autoBoundValues,
    )
    val structuredSpecs = remember(fields) {
        fields.mapNotNull { field ->
            field.repeatableObjectInput?.let { spec -> field.id to spec }
        }.toMap()
    }
    val initialStructuredDraft = remember(fields, initialDraft.values) {
        if (action.intent == ActionIntent.create) {
            initialNativeCreateRepeatableObjectDraft(fields, initialDraft.values)
        } else {
            initialNativeRepeatableObjectDraft(fields, initialDraft.values)
        }
    }
    val emptyStructuredDraft = remember(fields, structuredSpecs) {
        requireNotNull(initialNativeRepeatableObjectDraft(fields, emptyMap()))
    }
    val structuredDraftSaver = remember(structuredSpecs) {
        nativeRepeatableObjectDraftSaver(structuredSpecs)
    }
    var repeatableObjectValues by rememberSaveable(
        formSchema.app.id,
        view.id,
        formResource.id,
        initialRecord?.id,
        initialDraft.values,
        stateSaver = structuredDraftSaver,
    ) {
        mutableStateOf(initialStructuredDraft ?: emptyStructuredDraft)
    }
    val structuredDraftSafe = initialStructuredDraft != null
    val hasUneditableBodyFields = uneditableBodyFieldIds.isNotEmpty() || !structuredDraftSafe
    val settingsWrite = action.isSettingsWrite(resource)
    val hasChanges = draft.hasChangesFrom(initialDraft) ||
        structuredDraftSafe && repeatableObjectValues != initialStructuredDraft
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls

    LaunchedEffect(executionState) {
        when (executionState) {
            is NativeActionExecutionState.Succeeded -> onActionSucceeded?.invoke(action)
            is NativeActionExecutionState.AwaitingReconciliation -> onActionOutcomeUnknown?.invoke(action)
            else -> Unit
        }
    }

    LaunchedEffect(mutationReconciliationGeneration) {
        coordinator.reconcileAuthoritativeRefresh(mutationReconciliationGeneration)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = nativeFormTitle(view, resource, action)
            },
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (dense) NextcloudSpacing.XLarge else NextcloudSpacing.Large,
                        vertical = NextcloudSpacing.Large,
                    ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    Text(
                        "Details",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Fields marked with * are required.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            autoBinding.error?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (fields.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                ) {
                    Column(
                        modifier = Modifier.padding(
                            if (dense) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        fields.forEach { field ->
                            val repeatableSpec = field.repeatableObjectInput
                            if (repeatableSpec != null) {
                                GenericRepeatableObjectField(
                                    field = field,
                                    spec = repeatableSpec,
                                    rows = repeatableObjectValues[field.id].orEmpty(),
                                    error = validationErrors[field.id],
                                    enabled = !submissionBlocked && structuredDraftSafe,
                                    onRowsChange = { rows ->
                                        coordinator.clearStatus()
                                        repeatableObjectValues = repeatableObjectValues + (field.id to rows)
                                    },
                                )
                                return@forEach
                            }
                            val relationOptions =
                                nativeRelationOptions(field, formResource, schema, datasetContext)
                            if (nativeRelationFieldRequiresChoice(field, formResource, schema, datasetContext)) {
                                GenericRelationshipField(
                                    field = field,
                                    value = draft.values[field.id].orEmpty(),
                                    options = relationOptions,
                                    choicesLoaded = nativeRelationChoicesLoaded(
                                        field,
                                        formResource,
                                        schema,
                                        datasetContext,
                                    ),
                                    choiceSourceHasRecords = nativeRelationChoiceSourceHasRecords(
                                        field,
                                        formResource,
                                        schema,
                                        datasetContext,
                                    ),
                                    choiceUnavailableReason = nativeRelationChoiceUnavailableReason(
                                        field,
                                        formResource,
                                        schema,
                                        datasetContext,
                                    ),
                                    paging = nativeRelationPaging(field, formResource, schema, datasetContext),
                                    error = validationErrors[field.id],
                                    enabled = !submissionBlocked,
                                    onValueChange = { value ->
                                        coordinator.clearStatus()
                                        draft = draft.update(field.id, value)
                                    },
                                )
                            } else {
                                GenericFormField(
                                    field = field,
                                    value = draft.values[field.id].orEmpty(),
                                    error = validationErrors[field.id],
                                    enabled = !submissionBlocked,
                                    filePicker = filePicker,
                                    onValueChange = { value ->
                                        coordinator.clearStatus()
                                        draft = draft.update(field.id, value)
                                    },
                                )
                            }
                        }
                    }
                }
            } else if (!hasUneditableBodyFields) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Text(
                        "No additional information is needed for this action.",
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Text(
                        "This action needs structured information that cannot be edited safely yet.",
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .align(Alignment.Center)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(
                            horizontal = NextcloudSpacing.Large,
                            vertical = NextcloudSpacing.Medium,
                        ),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                GenericActionStatus(executionState, coordinator::clearStatus)
                if (action.risk == ActionRisk.destructive) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(NextcloudIcons.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                "You will be asked to confirm this destructive action.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Button(
                    enabled =
                        autoBinding.error == null &&
                            !hasUneditableBodyFields &&
                            !submissionBlocked &&
                            (!settingsWrite || hasChanges),
                    onClick = {
                        scope.launch {
                            val structuredValues = when (
                                val encoded = encodeNativeRepeatableObjectSubmitValues(
                                    repeatableObjectValues,
                                    structuredSpecs,
                                )
                            ) {
                                is NativeRepeatableObjectSubmitEncoding.Ready -> encoded.values
                                is NativeRepeatableObjectSubmitEncoding.Invalid -> {
                                    coordinator.reportValidationFailure(
                                        "Review the structured fields and try again.",
                                        encoded.fieldErrors,
                                    )
                                    return@launch
                                }
                            }
                            coordinator.submit(
                                values = (draft.values - structuredSpecs.keys) + structuredValues,
                                reconciliationGeneration = mutationReconciliationGeneration,
                            )
                        }
                    },
                    modifier = Modifier
                        .then(if (dense) Modifier.align(Alignment.End) else Modifier.fillMaxWidth())
                        .heightIn(min = 52.dp)
                        .semantics {
                            contentDescription = nativeFormSubmitLabel(resource, action)
                        },
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics {
                                    contentDescription = "Saving changes"
                                },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            nativeFormSubmitLabel(resource, action),
                        )
                    }
                }
                if (settingsWrite) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (hasChanges) "Unsaved changes" else "Settings are up to date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasChanges) {
                            TextButton(
                                enabled = !submissionBlocked,
                                onClick = {
                                    coordinator.clearStatus()
                                    draft = initialDraft
                                    initialStructuredDraft?.let { repeatableObjectValues = it }
                                },
                            ) {
                                Text("Reset changes")
                            }
                        }
                    }
                }
                }
            }
        }
    }

    val pending = executionState as? NativeActionExecutionState.AwaitingConfirmation
    if (pending != null) {
        NativeConfirmationDialog(
            action = pending.request.action,
            onDismiss = coordinator::cancelConfirmation,
            onConfirm = {
                scope.launch {
                    coordinator.confirm(mutationReconciliationGeneration)
                }
            },
        )
    }
}

@Composable
private fun GenericActionStatus(state: NativeActionExecutionState, onDismiss: () -> Unit) {
    val message = when (state) {
        NativeActionExecutionState.Idle,
        is NativeActionExecutionState.AwaitingConfirmation,
        is NativeActionExecutionState.Running,
        -> null
        is NativeActionExecutionState.ValidationFailed -> state.message
        is NativeActionExecutionState.AwaitingReconciliation ->
            "${state.message} Refreshing authoritative server data before this action can be tried again."
        is NativeActionExecutionState.Succeeded -> state.message ?: "Action completed."
        is NativeActionExecutionState.Failed -> state.message
    } ?: return
    val failure =
        state is NativeActionExecutionState.Failed ||
            state is NativeActionExecutionState.ValidationFailed ||
            state is NativeActionExecutionState.AwaitingReconciliation
    val dismissible = state !is NativeActionExecutionState.AwaitingReconciliation
    val statusDescription = when (state) {
        is NativeActionExecutionState.ValidationFailed -> buildString {
            append("Action status validation failed")
            state.fieldErrors.keys.sorted().takeIf(List<String>::isNotEmpty)?.let { fields ->
                append(" fields ")
                append(fields.joinToString(" "))
            }
        }
        is NativeActionExecutionState.AwaitingReconciliation -> "Action status awaiting reconciliation"
        is NativeActionExecutionState.Succeeded -> "Action status succeeded"
        is NativeActionExecutionState.Failed -> "Action status failed"
        NativeActionExecutionState.Idle,
        is NativeActionExecutionState.AwaitingConfirmation,
        is NativeActionExecutionState.Running,
        -> error("Only visible action states have a status description.")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (dismissible) Modifier.clickable(onClick = onDismiss) else Modifier)
            .semantics {
                contentDescription = statusDescription
            },
        color = if (failure) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (failure) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (failure) NextcloudIcons.Error else NextcloudIcons.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NativeConfirmationDialog(action: ActionSpec, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (action.risk == ActionRisk.destructive) "Confirm destructive action" else "Confirm action") },
        text = {
            Text(
                if (action.risk == ActionRisk.destructive) {
                    "${action.label} can remove or overwrite server data. Continue?"
                } else {
                    "Confirm ${action.label.lowercase()} before changing server data."
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { Button(onClick = onConfirm) { Text("Confirm") } },
    )
}
