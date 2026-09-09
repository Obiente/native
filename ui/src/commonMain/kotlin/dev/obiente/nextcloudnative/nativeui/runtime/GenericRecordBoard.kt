package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.obiente.nextcloudnative.app.design.BoardDragVerticalScrollTarget
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.resolveBoardDragVerticalLane
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun GenericRecordBoard(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    declaredLanes: List<NativeBoardLane>? = null,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    actionExecutor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    reconciliation: NativeBoardMoveReconciliation,
) {
    val discoveredLanes = remember(resource, records, declaredLanes) {
        declaredLanes ?: nativeBoardLanes(resource, records)
    }
    val initialLaneOrder = remember(resource.id) { discoveredLanes.map(NativeBoardLane::key) }
    val orderedLaneKeys = stableNativeBoardLaneOrder(
        initialLaneKeys = initialLaneOrder,
        currentLaneKeys = discoveredLanes.map(NativeBoardLane::key),
    )
    val lanes = discoveredLanes.sortedBy { lane -> orderedLaneKeys.indexOf(lane.key) }
    val scope = rememberCoroutineScope()
    var editTarget by remember(resource.id) { mutableStateOf<NativeBoardEditTarget?>(null) }
    var moveTarget by remember(resource.id) { mutableStateOf<NativeBoardMoveTargetSelection?>(null) }
    var createTarget by remember(resource.id) { mutableStateOf<NativeBoardCreatePlan?>(null) }
    var confirmTarget by remember(resource.id) { mutableStateOf<NativeBoardDirectActionTarget?>(null) }
    var busyRecordId by remember(resource.id) { mutableStateOf<String?>(null) }
    var actionMessage by remember(resource.id) { mutableStateOf<String?>(null) }
    var actionError by remember(resource.id) { mutableStateOf<String?>(null) }
    val laneBounds = remember(resource.id) { mutableStateMapOf<String, Rect>() }
    val laneScrollBounds = remember(resource.id) { mutableMapOf<String, Rect>() }
    val laneScrollStates = remember(resource.id) { mutableMapOf<String, LazyListState>() }
    val boardScrollState = key(NativeBoardScrollStateKey(resource.id)) {
        rememberScrollState()
    }
    var boardBounds by remember(resource.id) { mutableStateOf<Rect?>(null) }
    var draggedRecord by remember(resource.id) { mutableStateOf<NativeRecord?>(null) }
    var draggedMovePlan by remember(resource.id) { mutableStateOf<NativeBoardMovePlan?>(null) }
    var dragOrigin by remember(resource.id) { mutableStateOf<Offset?>(null) }
    var dragPosition by remember(resource.id) { mutableStateOf<Offset?>(null) }
    var dragTargetLaneKey by remember(resource.id) { mutableStateOf<String?>(null) }
    var dragAllowedLaneKeys by remember(resource.id) { mutableStateOf<Set<String>>(emptySet()) }
    var terminalDropRequested by remember(resource.id) { mutableStateOf(false) }
    val fingerprint = remember(lanes) { nativeBoardFingerprint(lanes) }

    fun resolveDragTarget(position: Offset): String? {
        val viewport = boardBounds ?: return null
        return resolveNativeBoardLaneDropTarget(
            position = position,
            boardViewport = viewport,
            laneBounds = laneBounds,
            allowedLaneKeys = dragAllowedLaneKeys,
        )
    }

    fun updateDragPosition(position: Offset) {
        dragPosition = position
        dragTargetLaneKey = resolveDragTarget(position)
    }

    fun clearDrag() {
        draggedRecord = null
        draggedMovePlan = null
        dragOrigin = null
        dragPosition = null
        dragTargetLaneKey = null
        dragAllowedLaneKeys = emptySet()
        terminalDropRequested = false
    }

    val pendingMove = reconciliation.pendingMove
    LaunchedEffect(fingerprint, pendingMove) {
        val pending = pendingMove ?: return@LaunchedEffect
        if (fingerprint == pending.beforeFingerprint) return@LaunchedEffect
        when (
            verifyNativeBoardMove(
                lanes = lanes,
                recordId = pending.recordId,
                targetLaneKey = pending.targetLaneKey,
                beforeFingerprint = pending.beforeFingerprint,
                refreshCompleted = true,
            )
        ) {
            NativeBoardMoveVerification.Confirmed -> {
                actionMessage = "Move confirmed in ${pending.targetLaneTitle}."
                actionError = null
            }
            NativeBoardMoveVerification.NotMoved -> {
                actionMessage = null
                actionError = "The server accepted the move request, but the refreshed board did not place the " +
                    "card in ${pending.targetLaneTitle}."
            }
            NativeBoardMoveVerification.WaitingForRefresh -> return@LaunchedEffect
        }
        reconciliation.clear(pending)
    }
    LaunchedEffect(pendingMove) {
        val pending = pendingMove ?: return@LaunchedEffect
        delay(BOARD_MOVE_VERIFICATION_TIMEOUT_MILLIS)
        if (reconciliation.pendingMove != pending) return@LaunchedEffect
        when (
            verifyNativeBoardMove(
                lanes = lanes,
                recordId = pending.recordId,
                targetLaneKey = pending.targetLaneKey,
                beforeFingerprint = pending.beforeFingerprint,
                refreshCompleted = true,
            )
        ) {
            NativeBoardMoveVerification.Confirmed -> {
                actionMessage = "Move confirmed in ${pending.targetLaneTitle}."
                actionError = null
            }
            NativeBoardMoveVerification.NotMoved,
            NativeBoardMoveVerification.WaitingForRefresh,
            -> {
                actionMessage = null
                actionError = "The move could not be verified after refreshing the board. The card remains " +
                    "unchanged in this view."
            }
        }
        reconciliation.clear(pending)
    }

    fun executeEdit(target: NativeBoardEditTarget, values: Map<String, String>) {
        if (busyRecordId != null) return
        busyRecordId = target.record.id
        actionError = null
        scope.launch {
            try {
                val executionResult = runCatchingUnlessCancelled {
                    actionExecutor.execute(target.plan.request(values))
                }.getOrElse { failure ->
                    actionError = failure.message?.takeIf(String::isNotBlank)
                        ?: "The board action failed before the server returned a result."
                    return@launch
                }
                when (executionResult) {
                    is NativeActionExecutionResult.Success -> {
                        editTarget = null
                        actionMessage = "Update accepted. Refreshing the card..."
                        onActionSucceeded?.invoke(target.plan.action)
                    }
                    is NativeActionExecutionResult.Failure -> actionError = executionResult.message
                }
            } finally {
                busyRecordId = null
            }
        }
    }

    fun executeMove(target: NativeBoardMoveTargetSelection, destination: NativeBoardMoveTarget) {
        if (busyRecordId != null) return
        busyRecordId = target.record.id
        actionError = null
        scope.launch {
            try {
                val executionResult = runCatchingUnlessCancelled {
                    actionExecutor.execute(target.plan.request(destination.key))
                }.getOrElse { failure ->
                    actionError = failure.message?.takeIf(String::isNotBlank)
                        ?: "The board action failed before the server returned a result."
                    return@launch
                }
                when (executionResult) {
                    is NativeActionExecutionResult.Success -> {
                        moveTarget = null
                        reconciliation.begin(
                            recordId = target.record.id,
                            targetLaneKey = destination.key,
                            targetLaneTitle = destination.title,
                            beforeFingerprint = fingerprint,
                        )
                        actionMessage = "Move accepted. Refreshing the board to verify it..."
                        onActionSucceeded?.invoke(target.plan.action)
                    }
                    is NativeActionExecutionResult.Failure -> actionError = executionResult.message
                }
            } finally {
                busyRecordId = null
            }
        }
    }

    fun executeCreate(target: NativeBoardCreatePlan, title: String, description: String) {
        if (busyRecordId != null) return
        busyRecordId = BOARD_CREATE_BUSY_ID
        actionError = null
        scope.launch {
            try {
                val executionResult = runCatchingUnlessCancelled {
                    actionExecutor.execute(target.request(title, description))
                }.getOrElse { failure ->
                    actionError = failure.message?.takeIf(String::isNotBlank)
                        ?: "The board action failed before the server returned a result."
                    return@launch
                }
                when (executionResult) {
                    is NativeActionExecutionResult.Success -> {
                        createTarget = null
                        actionMessage = "Card created in ${target.lane.title}. Refreshing the board..."
                        onActionSucceeded?.invoke(target.action)
                    }
                    is NativeActionExecutionResult.Failure -> actionError = executionResult.message
                }
            } finally {
                busyRecordId = null
            }
        }
    }

    fun executeDirect(target: NativeBoardDirectActionTarget) {
        if (busyRecordId != null) return
        busyRecordId = target.record.id
        actionError = null
        scope.launch {
            try {
                val executionResult = runCatchingUnlessCancelled {
                    actionExecutor.execute(target.plan.request())
                }.getOrElse { failure ->
                    actionError = failure.message?.takeIf(String::isNotBlank)
                        ?: "The board action failed before the server returned a result."
                    return@launch
                }
                when (executionResult) {
                    is NativeActionExecutionResult.Success -> {
                        confirmTarget = null
                        actionMessage = "${target.plan.label} accepted. Refreshing the board..."
                        onActionSucceeded?.invoke(target.plan.action)
                    }
                    is NativeActionExecutionResult.Failure -> actionError = executionResult.message
                }
            } finally {
                busyRecordId = null
            }
        }
    }

    fun commitDragDrop() {
        val record = draggedRecord
        val movePlan = draggedMovePlan
        val destination = dragTargetLaneKey?.let { targetKey ->
            movePlan?.targets?.firstOrNull { it.key == targetKey }
        }
        clearDrag()
        if (record != null && movePlan != null && destination != null) {
            executeMove(
                NativeBoardMoveTargetSelection(record, movePlan),
                destination,
            )
        }
    }

    NextcloudBoardDragAutoScroll(
        activeDragKey = draggedRecord?.id,
        position = dragPosition,
        dragOrigin = dragOrigin,
        boardViewport = boardBounds,
        horizontalScrollState = boardScrollState,
        verticalScrollTargetAt = { position, boardViewport, activationHalo ->
            val laneKey = resolveBoardDragVerticalLane(
                position = position,
                boardViewport = boardViewport,
                laneViewports = laneScrollBounds,
                verticalActivationHalo = activationHalo,
            )
            val viewport = laneKey?.let(laneScrollBounds::get)
            val state = laneKey?.let(laneScrollStates::get)
            if (viewport != null && state != null) {
                BoardDragVerticalScrollTarget(state, viewport)
            } else {
                null
            }
        },
        terminalDropRequested = terminalDropRequested,
        onTargetRefresh = {
            dragPosition?.let(::updateDragPosition)
        },
        onTerminalDropReady = ::commitDragDrop,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        actionError?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        actionMessage?.let { message ->
            Text(
                message,
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.XSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    boardBounds = coordinates.boundsInWindow()
                },
        ) {
            val laneWidth = nativeBoardLaneWidth(maxWidth.value, lanes.size).dp
            val density = androidx.compose.ui.platform.LocalDensity.current
            NativeBoardLaneJump(
                lanes = lanes,
                onSelectLane = { index ->
                    scope.launch {
                        boardScrollState.animateScrollTo(
                            with(density) { ((laneWidth + NextcloudSpacing.Medium) * index).roundToPx() },
                        )
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxSize().horizontalScroll(boardScrollState)
                    .padding(top = 48.dp)
                    .padding(NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                lanes.forEach { lane ->
                    val laneStateKey = NativeBoardLaneStateKey(
                        resourceId = resource.id,
                        laneKey = lane.key,
                    )
                    key(laneStateKey) {
                    val laneScrollState = rememberLazyListState()
                    DisposableEffect(laneStateKey, laneScrollState) {
                        laneScrollStates[lane.key] = laneScrollState
                        onDispose {
                            if (laneScrollStates[lane.key] === laneScrollState) {
                                laneBounds.remove(lane.key)
                                laneScrollBounds.remove(lane.key)
                                laneScrollStates.remove(lane.key)
                            }
                        }
                    }
                    val createPlan = remember(schema, resource, lane) {
                        nativeBoardLaneCreatePlan(schema, resource, lane)
                    }
                    val isDragTarget = dragTargetLaneKey == lane.key
                    Column(
                        modifier = Modifier.width(laneWidth).fillMaxHeight()
                            .onGloballyPositioned { coordinates ->
                                laneBounds[lane.key] = coordinates.boundsInWindow()
                            }
                            .then(
                                if (isDragTarget) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(NextcloudRadii.Card),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(NextcloudSpacing.XSmall),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XSmall),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lane.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.small) {
                            Text(
                                lane.records.size.toString(),
                                modifier = Modifier.padding(horizontal = NextcloudSpacing.Small, vertical = 3.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        if (createPlan != null) {
                            TextButton(
                                enabled = busyRecordId == null,
                                onClick = { createTarget = createPlan },
                            ) {
                                Text("Add card")
                            }
                        }
                    }
                }
                if (lane.records.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Text(
                            "No cards",
                            modifier = Modifier.padding(NextcloudSpacing.Large),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                        LazyColumn(
                            state = laneScrollState,
                            modifier = Modifier.weight(1f)
                                .onGloballyPositioned { coordinates ->
                                    laneScrollBounds[lane.key] = coordinates.boundsInWindow()
                                },
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            contentPadding = PaddingValues(bottom = NextcloudSpacing.XXLarge),
                        ) {
                            items(lane.records, key = NativeRecord::id) { record ->
                                val actions = remember(schema, resource, record, lanes) {
                                    nativeBoardCardActionPlan(schema, resource, record, lanes)
                                }
                                val movePlan = actions.move
                                GenericBoardCard(
                                    resource = resource,
                                    record = record,
                                    actions = actions,
                                    busy = busyRecordId == record.id,
                                    dragging = draggedRecord?.id == record.id,
                                    onOpen = onSelectRecord?.let { callback -> { callback(record) } },
                                    onEdit = actions.edit?.let { plan ->
                                        { editTarget = NativeBoardEditTarget(record, plan) }
                                    },
                                    onMove = movePlan?.let { plan ->
                                        { moveTarget = NativeBoardMoveTargetSelection(record, plan) }
                                    },
                                    onDragStart = movePlan?.takeIf { busyRecordId == null }?.let {
                                        { position ->
                                            draggedRecord = record
                                            draggedMovePlan = movePlan
                                            dragOrigin = position
                                            dragAllowedLaneKeys = movePlan.targets
                                                .mapTo(linkedSetOf(), NativeBoardMoveTarget::key)
                                            terminalDropRequested = false
                                            updateDragPosition(position)
                                        }
                                    },
                                    onDrag = { amount ->
                                        dragPosition?.let { position ->
                                            updateDragPosition(position + amount)
                                        }
                                    },
                                    onDragEnd = { terminalDropRequested = true },
                                    onDragCancel = ::clearDrag,
                                    onDirectAction = { plan ->
                                        val target = NativeBoardDirectActionTarget(record, plan)
                                        if (plan.kind == NativeBoardDirectActionKind.Delete) {
                                            confirmTarget = target
                                        } else {
                                            executeDirect(target)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    }
                }
            }
            val previewRecord = draggedRecord
            val previewPosition = dragPosition
            val viewport = boardBounds
            if (previewRecord != null && previewPosition != null && viewport != null) {
                val preview = nativeRecordPresentation(resource, previewRecord)
                Surface(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (previewPosition.x - viewport.left - 20.dp.toPx()).roundToInt(),
                                y = (previewPosition.y - viewport.top - 20.dp.toPx()).roundToInt(),
                            )
                        }
                        .width(264.dp)
                        .zIndex(2f),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                    shadowElevation = 12.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(NextcloudSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            preview.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            dragTargetLaneKey
                                ?.let { key -> lanes.firstOrNull { it.key == key }?.title }
                                ?.let { title -> "Move to $title" }
                                ?: "Move over a list",
                            style = MaterialTheme.typography.labelMedium,
                            color = dragTargetLaneKey?.let { MaterialTheme.colorScheme.primary }
                                ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    editTarget?.let { target ->
        NativeBoardEditDialog(
            target = target,
            busy = busyRecordId == target.record.id,
            onDismiss = { if (busyRecordId == null) editTarget = null },
            onSave = { values -> executeEdit(target, values) },
        )
    }
    moveTarget?.let { target ->
        NativeBoardMoveDialog(
            target = target,
            busy = busyRecordId == target.record.id,
            onDismiss = { if (busyRecordId == null) moveTarget = null },
            onMove = { destination -> executeMove(target, destination) },
        )
    }
    createTarget?.let { target ->
        NativeBoardCreateDialog(
            target = target,
            busy = busyRecordId == BOARD_CREATE_BUSY_ID,
            onDismiss = { if (busyRecordId == null) createTarget = null },
            onCreate = { title, description -> executeCreate(target, title, description) },
        )
    }
    confirmTarget?.let { target ->
        NativeBoardDirectActionDialog(
            target = target,
            busy = busyRecordId == target.record.id,
            onDismiss = { if (busyRecordId == null) confirmTarget = null },
            onConfirm = { executeDirect(target) },
        )
    }
}

internal data class NativeBoardEditTarget(
    val record: NativeRecord,
    val plan: NativeBoardEditPlan,
)

internal data class NativeBoardMoveTargetSelection(
    val record: NativeRecord,
    val plan: NativeBoardMovePlan,
)

internal data class NativeBoardDirectActionTarget(
    val record: NativeRecord,
    val plan: NativeBoardDirectActionPlan,
)

internal data class PendingNativeBoardMove(
    val recordId: String,
    val targetLaneKey: String,
    val targetLaneTitle: String,
    val beforeFingerprint: String,
)

internal class NativeBoardMoveReconciliation {
    var pendingMove by mutableStateOf<PendingNativeBoardMove?>(null)
        private set

    fun begin(
        recordId: String,
        targetLaneKey: String,
        targetLaneTitle: String,
        beforeFingerprint: String,
    ) {
        pendingMove = PendingNativeBoardMove(
            recordId = recordId,
            targetLaneKey = targetLaneKey,
            targetLaneTitle = targetLaneTitle,
            beforeFingerprint = beforeFingerprint,
        )
    }

    fun clear(expected: PendingNativeBoardMove) {
        if (pendingMove == expected) pendingMove = null
    }
}

private const val BOARD_MOVE_VERIFICATION_TIMEOUT_MILLIS = 6_000L
private const val BOARD_CREATE_BUSY_ID = "__creating_board_card__"
