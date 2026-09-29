package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudVerticalDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragHandle
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
internal fun GenericRecordList(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    modifier: Modifier = Modifier,
    secondaryActions: (NativeRecord) -> List<NextcloudCardAction> = { emptyList() },
    reorder: NativeCollectionReorderActionPlan? = null,
    actionExecutor: NativeActionExecutor? = null,
    onActionSucceeded: ((ActionSpec) -> Unit)? = null,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey = NativeAuthoritativeRecordsKey(records),
    pendingReorderOrder: List<String>? = null,
    pendingReorderRecoveryRequested: Boolean = false,
    onPendingReorderChanged: (NativeCollectionReorderActionPlan, List<String>?, Boolean) -> Unit =
        { _, _, _ -> },
    pendingMutationStore: NativePendingMutationStore? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    loadMoreError: String? = null,
    primaryContent: (@Composable (NativeRecord) -> Unit)? = null,
) {
    val authoritativeOrder = remember(records) { records.map(NativeRecord::id) }
    val activeReorder = reorder.takeIf { actionExecutor != null && pendingMutationStore != null }
    var draggingRecordId by remember(reorder?.action?.id, resource.id) {
        mutableStateOf<String?>(null)
    }
    var dragOrigin by remember(reorder?.action?.id, resource.id) { mutableStateOf<Offset?>(null) }
    var dragPosition by remember(reorder?.action?.id, resource.id) { mutableStateOf<Offset?>(null) }
    val rowBounds = remember(reorder?.action?.id, resource.id) { mutableStateMapOf<String, Rect>() }
    var listBounds by remember(reorder?.action?.id, resource.id) { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val recordsById = remember(records) { records.associateBy(NativeRecord::id) }
    val reorderState = rememberNativeDurableCollectionReorderState(
        plan = activeReorder,
        resourceId = resource.id,
        authoritativeOrder = authoritativeOrder,
        authoritativeRecordsKey = authoritativeRecordsKey,
        draggingRecordId = draggingRecordId,
        pendingOrder = pendingReorderOrder,
        pendingRecoveryRequested = pendingReorderRecoveryRequested,
        actionExecutor = actionExecutor ?: NativeActionExecutor {
            NativeActionExecutionResult.Failure(
                "Order changes are unavailable.",
                NativeActionFailureOutcome.Rejected,
            )
        },
        pendingMutationStore = pendingMutationStore,
        onPendingChanged = onPendingReorderChanged,
        onActionSucceeded = onActionSucceeded,
    )
    val displayedRecords = remember(recordsById, reorderState.orderedRecordIds, activeReorder) {
        if (activeReorder == null) records else reorderState.orderedRecordIds.mapNotNull(recordsById::get)
    }
    fun moveDraggedRecord(position: Offset) {
        val recordId = draggingRecordId ?: return
        val visibleItemKeys = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { item -> item.key as? String }.toSet()
        moveNativeCollectionRecordToVisibleTarget(
            orderedRecordIds = reorderState.orderedRecordIds,
            recordId = recordId,
            rowBounds = rowBounds,
            pointerPosition = position,
            visibleItemKeys = visibleItemKeys,
        )?.let { orderedRecordIds -> reorderState.updateOrder(orderedRecordIds) }
    }
    NativeCollectionAutoPager(
        listState = listState,
        itemCount = displayedRecords.size,
        onLoadMore = onLoadMore,
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )
    NextcloudVerticalDragAutoScroll(
        activeDragKey = draggingRecordId,
        position = dragPosition,
        dragOrigin = dragOrigin,
        viewport = listBounds,
        scrollState = listState,
    )
    LazyColumn(
        modifier = modifier.onGloballyPositioned { coordinates ->
            listBounds = coordinates.boundsInWindow()
        },
        state = listState,
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Large,
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (LocalNextcloudWorkspaceCapabilities.current.usesDenseControls) 1.dp
            else NextcloudSpacing.Small,
        ),
    ) {
        reorderState.error?.let { message ->
            item(key = "collection-reorder-error") {
                NativeCollectionReorderRecoveryMessage(
                    message = message,
                    recoveryAvailable = reorderState.recoveryAvailable,
                    retryRecovery = reorderState.retryRecovery,
                    discardRecovery = reorderState.discardRecovery,
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                )
            }
        }
        itemsIndexed(displayedRecords, key = { _, record -> record.id }) { index, record ->
            if (LocalNextcloudWorkspaceCapabilities.current.usesDenseControls && index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            val dragging = draggingRecordId == record.id
            GenericCollectionCard(
                resource = resource,
                record = record,
                onSelectRecord = onSelectRecord,
                secondaryActions = secondaryActions(record),
                primaryContent = primaryContent?.let { content -> { content(record) } },
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        rowBounds[record.id] = coordinates.boundsInWindow()
                    }
                    .graphicsLayer { alpha = if (dragging) 0.56f else 1f },
                leadingContent = activeReorder?.takeUnless { reorderState.executing }?.let {
                    {
                        NextcloudBoardDragHandle(
                            itemLabel = nativeRecordPresentation(resource, record).title,
                            dragActive = dragging,
                            onDragStart = { position ->
                                draggingRecordId = record.id
                                dragOrigin = position
                                dragPosition = position
                            },
                            onDrag = { delta ->
                                val position = (dragPosition ?: return@NextcloudBoardDragHandle) + delta
                                dragPosition = position
                                reorderState.updateOrder(
                                    moveNativeCollectionRecordAcrossAdjacentMidpoint(
                                        orderedRecordIds = reorderState.orderedRecordIds,
                                        recordId = record.id,
                                        pointerY = position.y,
                                        movementY = delta.y,
                                        rowBounds = rowBounds,
                                    ),
                                )
                            },
                            onDragEnd = {
                                dragPosition?.let(::moveDraggedRecord)
                                draggingRecordId = null
                                dragOrigin = null
                                dragPosition = null
                                reorderState.submit(reorderState.orderedRecordIds)
                            },
                            onDragCancel = {
                                draggingRecordId = null
                                dragOrigin = null
                                dragPosition = null
                                reorderState.updateOrder(authoritativeOrder)
                            },
                        )
                    }
                },
                busy = reorderState.executing,
            )
        }
        NativeCollectionPagingFooter(
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
            onRetry = onLoadMore,
        )
    }
}

@Composable
internal fun NativeCollectionAutoPager(
    listState: LazyListState,
    itemCount: Int,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    LaunchedEffect(listState, itemCount, onLoadMore, loadingMore, loadMoreError) {
        if (onLoadMore == null || loadingMore || loadMoreError != null) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 3
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }
}

internal fun androidx.compose.foundation.lazy.LazyListScope.NativeCollectionPagingFooter(
    loadingMore: Boolean,
    loadMoreError: String?,
    onRetry: (() -> Unit)?,
) {
    if (!loadingMore && loadMoreError == null) return
    item(key = "collection-paging-footer") {
        NativeCollectionPagingStatus(
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
            onRetry = onRetry,
        )
    }
}

@Composable
internal fun GenericEditableTableRecordList(
    schema: NativeAppSchema,
    sourceResource: ResourceSpec,
    projection: NativeTableProjection,
    records: List<NativeRecord>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    actionExecutor: NativeActionExecutor,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
    modifier: Modifier = Modifier,
) {
    val fields = remember(projection) {
        if (projection.composite) {
            projection.resource.fields.filter { it.id in projection.projectedFieldIds } +
                listOfNotNull(projection.resource.fields.firstOrNull { it.id == projection.frozenFieldId })
        } else {
            nativeTableFields(projection.resource, records)
        }
    }.distinctBy(FieldSpec::id)
    var activeEdit by remember(schema, projection) { mutableStateOf<NativeCellEditPlan?>(null) }
    var editValue by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var savingEdit by remember { mutableStateOf(false) }
    val editedValues = remember(schema, projection) { mutableStateMapOf<NativeCellAddress, String>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    NativeCollectionAutoPager(
        listState = listState,
        itemCount = records.size,
        onLoadMore = onLoadMore,
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(records, key = NativeRecord::id) { record ->
            val editedFields = fields.mapNotNull { field ->
                editedValues[NativeCellAddress(record.id, field.id)]?.let { field.id to it }
            }.toMap()
            val displayRecord = record.copy(
                values = record.values + editedFields,
                displayValues = record.displayValues - editedFields.keys,
            )
            val editPlans = fields.mapNotNull { field ->
                nativeCellEditPlan(schema, sourceResource, projection, record, field)
            }
            val rowCardResource = nativeTableRowCardResource(schema, projection)
            GenericCollectionCard(
                resource = rowCardResource ?: projection.resource,
                record = displayRecord,
                onSelectRecord = onSelectRecord,
                primaryContent = nativeTableInventoryPresentation(schema, sourceResource, displayRecord)?.let { presentation ->
                    { NativeTableInventoryContent(presentation) }
                } ?: rowCardResource?.let { cardResource ->
                    { NativeTableRowCardContent(nativeTableRowCardPresentation(cardResource, displayRecord)) }
                },
                secondaryActions = editPlans.map { plan ->
                    NextcloudCardAction(
                        label = "Edit ${plan.field.label}",
                        enabled = !savingEdit,
                        onClick = {
                            activeEdit = plan.copy(
                                originalValue = editedValues[
                                    NativeCellAddress(plan.recordId, plan.field.id)
                                ] ?: plan.originalValue,
                            )
                            editValue = editedValues[
                                NativeCellAddress(plan.recordId, plan.field.id)
                            ] ?: plan.originalValue
                            editError = null
                        },
                    )
                },
            )
        }
        NativeCollectionPagingFooter(
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
            onRetry = onLoadMore,
        )
    }

    activeEdit?.let { plan ->
        AlertDialog(
            onDismissRequest = { if (!savingEdit) activeEdit = null },
            title = { Text("Edit ${plan.field.label}") },
            text = {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = {
                        editValue = it
                        editError = null
                    },
                    enabled = !savingEdit,
                    label = { Text(plan.field.label) },
                    supportingText = editError?.let { message -> { Text(message) } },
                    isError = editError != null,
                    singleLine = plan.field.kind != FieldKind.longText,
                    minLines = if (plan.field.kind == FieldKind.longText) 3 else 1,
                )
            },
            dismissButton = {
                TextButton(enabled = !savingEdit, onClick = { activeEdit = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !savingEdit,
                    onClick = {
                        val validation = validateNativeCellEdit(plan.field, editValue)
                        if (validation != null) {
                            editError = validation
                        } else {
                            savingEdit = true
                            scope.launch {
                                when (val result = actionExecutor.execute(plan.request(editValue.trim()))) {
                                    is NativeActionExecutionResult.Success -> {
                                        editedValues[
                                            NativeCellAddress(plan.recordId, plan.field.id)
                                        ] = editValue.trim()
                                        activeEdit = null
                                        onInlineActionSucceeded?.invoke(plan.action)
                                    }
                                    is NativeActionExecutionResult.Failure -> editError = result.message
                                }
                                savingEdit = false
                            }
                        }
                    },
                ) {
                    if (savingEdit) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save")
                    }
                }
            },
        )
    }
}
