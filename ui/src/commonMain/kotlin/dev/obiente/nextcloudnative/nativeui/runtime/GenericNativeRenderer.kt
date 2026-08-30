package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudVerticalDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragHandle
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedControl
import dev.obiente.nextcloudnative.app.design.NextcloudSegmentedOption
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

fun interface NativeFileFieldPicker {
    fun requestFile(field: FieldSpec, onSelected: (String) -> Unit)
}

internal val LocalNativeFinanceCurrency = compositionLocalOf<String?> { null }

fun interface NativeImageLoader {
    suspend fun load(relativePath: String): ImageBitmap?
}

data class NativeWorkspaceNavigationItem(
    val id: String,
    val label: String,
    val selected: Boolean,
)

data class NativeRecordImagePreview(
    val image: ImageBitmap,
    val contentDescription: String,
)

fun interface NativeRecordImageLoader {
    suspend fun load(resource: ResourceSpec, record: NativeRecord): NativeRecordImagePreview?
}

data class NativeCollectionBatchRelationLoadRequest(
    val actionId: String,
    val resourceId: String,
    val relatedResourceIdsByField: Map<String, String>,
    val bindingValues: Map<String, String>,
    val forceRefresh: Boolean,
) {
    init {
        require(actionId.isNotBlank() && resourceId.isNotBlank())
        require(relatedResourceIdsByField.isNotEmpty())
        require(relatedResourceIdsByField.size <= MAX_NATIVE_COLLECTION_BATCH_RELATIONS)
        require(relatedResourceIdsByField.all { (fieldId, relatedResourceId) ->
            fieldId.isNotBlank() && relatedResourceId.isNotBlank()
        })
        require(bindingValues.size <= MAX_NATIVE_COLLECTION_BATCH_RELATION_BINDINGS)
    }
}

data class NativeCollectionBatchRelationLoadResult(
    val recordsByResourceId: Map<String, List<NativeRecord>>,
    val errorsByResourceId: Map<String, String> = emptyMap(),
) {
    init {
        require(recordsByResourceId.size <= MAX_NATIVE_COLLECTION_BATCH_RELATIONS)
        require(errorsByResourceId.size <= MAX_NATIVE_COLLECTION_BATCH_RELATIONS)
        require(recordsByResourceId.values.all { records ->
            records.size <= MAX_NATIVE_COLLECTION_BATCH_RELATION_RECORDS &&
                records.map(NativeRecord::id).distinct().size == records.size
        })
        require(errorsByResourceId.values.all { message ->
            message.isNotBlank() && message.length <= MAX_NATIVE_COLLECTION_BATCH_RELATION_ERROR_LENGTH
        })
    }
}

fun interface NativeCollectionBatchRelationLoader {
    suspend fun load(
        request: NativeCollectionBatchRelationLoadRequest,
    ): NativeCollectionBatchRelationLoadResult
}

internal fun NativeDatasetContext.withCollectionBatchRelationRecords(
    recordsByResourceId: Map<String, List<NativeRecord>>,
): NativeDatasetContext = copy(
    // A batch picker is scoped to its own verified load. Ambient records may belong to another
    // parent or an earlier form and therefore must never satisfy this dialog accidentally.
    relatedRecords = recordsByResourceId,
    relatedRecordPaging = emptyMap(),
    fieldChoices = emptyMap(),
)

/**
 * Drop-in renderer for a compiler- or adapter-produced [NativeAppSchema].
 *
 * The host owns data loading, link opening, file selection, and action execution. This component
 * performs no network calls, embeds no web content, and only renders actions that the active view
 * references by schema ID.
 */
@Composable
fun GenericNativeAppScreen(
    schema: NativeAppSchema,
    view: ViewSpec,
    state: NativeScreenState,
    actionExecutor: NativeActionExecutor,
    modifier: Modifier = Modifier,
    selectedRecordId: String? = null,
    selectedRecordResourceId: String? = null,
    showSelectedRecordDetail: Boolean = false,
    onSelectRecord: ((NativeRecord) -> Unit)? = null,
    onOpenLink: ((String) -> Unit)? = null,
    filePicker: NativeFileFieldPicker? = null,
    onActionSucceeded: ((ActionSpec) -> Unit)? = null,
    datasetContext: NativeDatasetContext = NativeDatasetContext(),
    onInlineActionSucceeded: ((ActionSpec) -> Unit)? = null,
    showCollectionCreateAction: Boolean = false,
    imageLoader: NativeImageLoader? = null,
    recordImageLoader: NativeRecordImageLoader? = null,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    loadMoreError: String? = null,
    audioPlayer: NativeAudioRecordPlayer? = null,
    mediaArtworkResolver: NativeMediaArtworkResolver? = null,
    mutationReconciliationGeneration: Int = 0,
    pendingMutationStore: NativePendingMutationStore? = null,
    collectionBatchRelationLoader: NativeCollectionBatchRelationLoader? = null,
    workspaceNavigationItems: List<NativeWorkspaceNavigationItem> = emptyList(),
    onWorkspaceNavigate: ((String) -> Unit)? = null,
) {
    var pendingCollectionReorderActionId by rememberSaveable(schema.app.id) { mutableStateOf<String?>(null) }
    var pendingCollectionReorderResourceId by rememberSaveable(schema.app.id) { mutableStateOf<String?>(null) }
    var pendingCollectionReorderScopeId by rememberSaveable(schema.app.id) { mutableStateOf<String?>(null) }
    var pendingCollectionReorderIds by rememberSaveable(schema.app.id) { mutableStateOf<List<String>?>(null) }
    var pendingCollectionReorderRecoveryRequested by rememberSaveable(schema.app.id) { mutableStateOf(false) }
    val resource = schema.resource(view.resourceId)
    val boardMoveReconciliation = remember(schema.app.id, view.id, resource?.id) {
        NativeBoardMoveReconciliation()
    }
    val readyRecords = (state as? NativeScreenState.Ready)?.records.orEmpty()
    val displayResource = remember(resource, readyRecords) {
        resource?.withEphemeralDisplayFields(readyRecords)
    }
    val nestedBoard = remember(schema, displayResource, readyRecords) {
        displayResource?.let { expandNestedBoardDataset(schema, it, readyRecords) }
    }
    val baseResource = nestedBoard?.resource ?: displayResource
    val baseRecords = nestedBoard?.records ?: readyRecords
    val hydrated = remember(schema, baseResource, baseRecords, datasetContext) {
        baseResource?.let { hydrateNativeDataset(schema, it, baseRecords, datasetContext) }
    }
    val presentedResource = hydrated?.resource ?: baseResource
    val presentedRecords = hydrated?.records ?: baseRecords
    val presentedSurface = when {
        showSelectedRecordDetail &&
            selectedRecordId != null &&
            presentedRecords.any { record -> record.id == selectedRecordId } ->
            GenericNativeSurface.Detail
        shouldAutoOpenSyntheticRecord(presentedRecords) -> GenericNativeSurface.Detail
        nestedBoard != null -> GenericNativeSurface.Board
        else -> view.genericSurface(presentedResource, presentedRecords)
    }
    val mailWorkspaceSection = remember(schema, presentedResource, datasetContext) {
        presentedResource?.let { currentResource ->
            nativeMailWorkspaceSection(schema, currentResource, datasetContext)
        } ?: NativeMailWorkspaceSection.Unknown
    }
    val mailWorkspaceEligible = remember(schema, mailWorkspaceSection) {
        schema.hasNativeMailWorkspaceSemantics() &&
            mailWorkspaceSection != NativeMailWorkspaceSection.Unknown
    }
    val searchableCollection = genericCollectionSearchAvailable(
        state = state,
        recordCount = presentedRecords.size,
        surface = presentedSurface,
        nativeMailWorkspaceEligible = mailWorkspaceEligible,
    )
    val collectionSearchContextKey = remember(datasetContext) {
        datasetContext.collectionSearchScopeKey ?: buildString {
            append(datasetContext.parentResourceId.orEmpty())
            append('\u0000')
            append(datasetContext.parentRecord?.id.orEmpty())
            datasetContext.bindingValues.toSortedMap().forEach { (key, value) ->
                append('\u0000')
                append(key)
                append('=')
                append(value)
            }
        }
    }
    var collectionQuery by rememberSaveable(
        schema.app.id,
        collectionSearchContextKey,
        view.id.takeIf { datasetContext.collectionSearchScopeKey == null },
    ) { mutableStateOf("") }
    val visiblePresentedRecords = remember(
        schema, view, datasetContext, presentedSurface,
        presentedResource,
        presentedRecords,
        collectionQuery,
        searchableCollection,
    ) {
        if (!searchableCollection || collectionQuery.isBlank() || presentedResource == null) {
            presentedRecords
        } else {
            val queryProjection = if (presentedSurface == GenericNativeSurface.Table) {
                nativeCollectionTableProjection(schema, view, presentedResource, presentedRecords, datasetContext)
            } else null
            val queryRecords = queryProjection?.records?.associateBy(NativeRecord::id)
            presentedRecords.filter { record ->
                nativeRecordMatchesCollectionQuery(
                    resource = queryProjection?.resource ?: presentedResource,
                    record = queryRecords?.get(record.id) ?: record,
                    query = collectionQuery,
                )
            }
        }
    }
    val dedicatedPresentedState = nativeDedicatedCollectionState(
        state = state,
        presentedRecords = presentedRecords,
        visiblePresentedRecords = visiblePresentedRecords,
        searchableCollection = searchableCollection,
    )
    val choresWorkspace = presentedResource
        ?.takeUnless {
            searchableCollection && collectionQuery.isNotBlank() && visiblePresentedRecords.isEmpty()
        }
        ?.let { resourceSpec ->
            nativeChoresPresentation(schema, view, resourceSpec, dedicatedPresentedState)
        }
    val rosterPresentation = choresWorkspace
        ?.takeIf { presentation -> presentation.kind == NativeChoresWorkspaceKind.Team }
        ?.let {
            (dedicatedPresentedState as? NativeScreenState.Ready)
                ?.records
                ?.singleOrNull()
                ?.let(::nativeRosterPresentation)
        }
    LaunchedEffect(
        collectionQuery,
        visiblePresentedRecords.size,
        presentedRecords.size,
        onLoadMore,
        loadingMore,
        loadMoreError,
    ) {
        if (
            searchableCollection &&
            collectionQuery.isNotBlank() &&
            visiblePresentedRecords.isEmpty() &&
            onLoadMore != null &&
            !loadingMore &&
            loadMoreError == null
        ) {
            onLoadMore()
        }
    }
    var pendingRecordFormActionToken by rememberSaveable(schema.app.id, view.id) {
        mutableStateOf<String?>(null)
    }
    var pendingRecordCommandFormActionToken by rememberSaveable(schema.app.id, view.id) {
        mutableStateOf<String?>(null)
    }
    var pendingRecordDeleteAction by remember(schema, view.id) {
        mutableStateOf<PendingNativeRecordDeleteAction?>(null)
    }
    var pendingRecordCommandAction by remember(schema, view.id) {
        mutableStateOf<PendingNativeRecordCommandAction?>(null)
    }
    var pendingCollectionCommandActionId by rememberSaveable(schema.app.id, view.id) {
        mutableStateOf<String?>(null)
    }
    var pendingCollectionBatchActionId by rememberSaveable(schema.app.id, view.id) {
        mutableStateOf<String?>(null)
    }
    val recordCommandsInFlight = remember(schema, view.id) { mutableSetOf<String>() }
    val recordCommandScope = rememberCoroutineScope()
    val inlineActionSucceeded = onInlineActionSucceeded ?: onActionSucceeded
    val activeMutationOwners = remember(schema.app.id) {
        mutableSetOf<NativeFormMutationRecoveryOwner>()
    }
    var formMutationRecoveryToken by rememberSaveable(schema.app.id) {
        mutableStateOf<String?>(null)
    }
    val formMutationRecovery = resolveNativeFormMutationRecoveryState(
        encoded = formMutationRecoveryToken,
        currentReconciliationGeneration = mutationReconciliationGeneration,
        ownerStillExecuting = activeMutationOwners::contains,
    )
    val normalizedFormMutationRecoveryToken = formMutationRecovery?.encode()
    LaunchedEffect(normalizedFormMutationRecoveryToken, formMutationRecoveryToken) {
        if (formMutationRecoveryToken != normalizedFormMutationRecoveryToken) {
            formMutationRecoveryToken = normalizedFormMutationRecoveryToken
        }
    }
    LaunchedEffect(formMutationRecovery?.owner, formMutationRecovery?.phase) {
        val actionId = formMutationRecovery?.authoritativeReconciliationActionId
            ?: return@LaunchedEffect
        schema.action(actionId)?.let { action ->
            inlineActionSucceeded?.invoke(action)
        }
    }
    val openRecordEdit: (NativeRecord, NativeRecordFormActionPlan) -> Unit = edit@{ record, plan ->
        if (formMutationRecovery?.blocksSubmission == true) return@edit
        val actionResource = presentedResource ?: return@edit
        pendingRecordFormActionToken = RestorableNativeRecordFormAction(
            actionId = plan.action.id,
            resourceId = actionResource.id,
            kind = plan.kind,
            recordId = record.id,
        ).encode()
    }
    val openRecordCommandForm: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit =
        commandForm@{ record, plan ->
            if (formMutationRecovery?.blocksSubmission == true) return@commandForm
            val actionResource = presentedResource ?: return@commandForm
            pendingRecordCommandFormActionToken = RestorableNativeRecordFormAction(
                actionId = plan.action.id,
                resourceId = actionResource.id,
                kind = NativeRecordFormActionKind.Edit,
                recordId = record.id,
            ).encode()
        }
    val openRecordDelete: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit = { record, plan ->
        pendingRecordDeleteAction = PendingNativeRecordDeleteAction(
            plan = plan,
            itemLabel = presentedResource
                ?.let { resourceSpec -> nativeRecordPresentation(resourceSpec, record).title }
                ?: record.id,
        )
    }
    val executeRecordCommand: (NativeRecord, NativeRecordCommandActionPlan) -> Unit = command@{ record, plan ->
        val itemLabel = presentedResource
            ?.let { resourceSpec -> nativeRecordPresentation(resourceSpec, record).title }
            ?: record.id
        if (plan.requiresConfirmation) {
            pendingRecordCommandAction = PendingNativeRecordCommandAction(
                plan = plan,
                targetRecordId = record.id,
                itemLabel = itemLabel,
            )
            return@command
        }
        val executionKey = "${record.id}\u0000${plan.action.id}"
        if (!recordCommandsInFlight.add(executionKey)) return@command
        recordCommandScope.launch {
            try {
                when (val result = actionExecutor.execute(plan.request())) {
                    is NativeActionExecutionResult.Success -> inlineActionSucceeded?.invoke(plan.action)
                    is NativeActionExecutionResult.Failure -> {
                        if (result.outcome.requiresCommandReconciliation()) {
                            inlineActionSucceeded?.invoke(plan.action)
                        }
                        pendingRecordCommandAction = PendingNativeRecordCommandAction(
                            plan = plan,
                            targetRecordId = record.id,
                            itemLabel = itemLabel,
                            initialError = result.message,
                            initialFailureOutcome = result.outcome,
                        )
                    }
                }
            } finally {
                recordCommandsInFlight.remove(executionKey)
            }
        }
    }
    val collectionCreateCandidate = presentedResource
        ?.takeIf { showCollectionCreateAction }
        ?.let { resource ->
        nativeRecordActions(
            schema = schema,
            resource = resource,
            navigationContext = datasetContext.bindingValues,
        ).create
    }
    val collectionCreateRecoveryPlan = collectionCreateCandidate?.let { createPlan ->
        val activeReadAction = schema.action(view.sourceActionId) ?: return@let null
        nativeCreateMutationRecoveryPlan(
            schema = schema,
            activeReadAction = activeReadAction,
            resource = presentedResource,
            createPlan = createPlan,
            records = presentedRecords,
            navigationContext = datasetContext.bindingValues,
            collectionComplete = onLoadMore == null,
        ) ?: nativeChoresInviteMutationRecoveryPlan(
            schema = schema,
            activeReadAction = activeReadAction,
            resource = presentedResource,
            createPlan = createPlan,
            records = presentedRecords,
            navigationContext = datasetContext.bindingValues,
            collectionComplete = onLoadMore == null,
        )
    }
    // Non-idempotent creates remain unavailable until the active collection supplies the exact
    // complete baseline and request shape needed for durable postcondition reconciliation.
    val collectionCreatePlan = collectionCreateCandidate?.takeIf {
        collectionCreateRecoveryPlan != null
    }
    val openCollectionCreate: (() -> Unit)? = collectionCreatePlan?.let { plan ->
        val actionResource = presentedResource
        create@{
            if (formMutationRecovery?.blocksSubmission == true) return@create
            pendingRecordFormActionToken = RestorableNativeRecordFormAction(
                actionId = plan.action.id,
                resourceId = actionResource.id,
                kind = plan.kind,
                recordId = null,
            ).encode()
        }
    }
    val collectionActionCapabilities = remember(
        schema,
        view.sourceActionId,
        resource,
        readyRecords,
        datasetContext.bindingValues,
        datasetContext.parentResourceId,
        datasetContext.parentRecord,
        datasetContext.currentUserId,
        onLoadMore,
        presentedSurface,
        nestedBoard,
        state is NativeScreenState.Ready,
    ) {
        val activeReadAction = schema.action(view.sourceActionId)
        if (
            state is NativeScreenState.Ready &&
            resource != null &&
            activeReadAction != null &&
            nestedBoard == null &&
            presentedSurface !in setOf(GenericNativeSurface.Detail, GenericNativeSurface.Form)
        ) {
            nativeCollectionActions(
                schema = schema,
                activeReadAction = activeReadAction,
                resource = resource,
                records = readyRecords,
                navigationContext = datasetContext.bindingValues,
                collectionComplete = onLoadMore == null,
                authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
            )
        } else {
            NativeCollectionActionCapabilities(
                commands = emptyList(),
                reorder = null,
                batches = emptyList(),
            )
        }
    }
    val collectionBatchPlans = collectionActionCapabilities.batches
        .takeIf { readyRecords.isNotEmpty() }
        .orEmpty()
    val pendingCollectionCommandAction = pendingCollectionCommandActionId?.let { actionId ->
        collectionActionCapabilities.commands.singleOrNull { plan -> plan.action.id == actionId }
    }
    val pendingCollectionBatchAction = pendingCollectionBatchActionId?.let { actionId ->
        collectionBatchPlans.singleOrNull { plan -> plan.action.id == actionId }
    }
    LaunchedEffect(
        pendingCollectionCommandActionId,
        collectionActionCapabilities.commands.map { plan -> plan.action.id },
    ) {
        if (pendingCollectionCommandActionId != null && pendingCollectionCommandAction == null) {
            pendingCollectionCommandActionId = null
        }
    }
    LaunchedEffect(
        pendingCollectionBatchActionId,
        collectionBatchPlans.map { plan -> plan.action.id },
    ) {
        if (pendingCollectionBatchActionId != null && pendingCollectionBatchAction == null) {
            pendingCollectionBatchActionId = null
        }
    }
    val pendingCollectionBatchRecoveryOwner = pendingCollectionBatchAction?.let { plan ->
        nativeCollectionBatchMutationRecoveryOwner(
            appId = schema.app.id,
            viewId = view.id,
            actionId = plan.action.id,
            resourceId = requireNotNull(resource).id,
        )
    }
    val pendingRecordFormAction = pendingRecordFormActionToken
        ?.let(::decodeRestorableNativeRecordFormAction)
        ?.let pending@{ saved ->
            val actionResource = presentedResource
                ?.takeIf { resourceSpec -> resourceSpec.id == saved.resourceId }
                ?: schema.resource(saved.resourceId)
                ?: return@pending null
            val record = if (saved.recordId != null) {
                presentedRecords.firstOrNull { candidate -> candidate.id == saved.recordId }
                    ?: return@pending null
            } else {
                null
            }
            val plan = nativeRecordActions(
                schema = schema,
                resource = actionResource,
                record = record,
                navigationContext = datasetContext.bindingValues,
                authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
            ).let { capabilities ->
                when (saved.kind) {
                    NativeRecordFormActionKind.Create -> capabilities.create
                    NativeRecordFormActionKind.Edit -> capabilities.edit
                }
            }?.takeIf { candidate -> candidate.action.id == saved.actionId }
                ?: return@pending null
            val mutationRecoveryOwner = nativeFormMutationRecoveryOwner(
                appId = schema.app.id,
                viewId = view.id,
                actionId = plan.action.id,
                resourceId = actionResource.id,
                intent = plan.action.intent,
                recordId = record?.id,
            ) ?: return@pending null
            val createMutationRecoveryPlan = if (plan.kind == NativeRecordFormActionKind.Create) {
                collectionCreateRecoveryPlan?.takeIf { recoveryPlan ->
                    recoveryPlan.action.id == plan.action.id
                } ?: return@pending null
            } else {
                null
            }
            PendingNativeRecordFormAction(
                plan = plan,
                itemLabel = record
                    ?.let { nativeRecordPresentation(actionResource, it).title }
                    ?: actionResource.name,
                resource = actionResource,
                datasetContext = datasetContext,
                restoreKey = pendingRecordFormActionToken.orEmpty(),
                mutationRecoveryOwner = mutationRecoveryOwner,
                createMutationRecoveryPlan = createMutationRecoveryPlan,
            )
        }
    val pendingRecordCommandFormAction = pendingRecordCommandFormActionToken
        ?.let(::decodeRestorableNativeRecordFormAction)
        ?.let pending@{ saved ->
            val actionResource = presentedResource
                ?.takeIf { resourceSpec -> resourceSpec.id == saved.resourceId }
                ?: schema.resource(saved.resourceId)
                ?: return@pending null
            val recordId = saved.recordId ?: return@pending null
            val record = presentedRecords.firstOrNull { candidate -> candidate.id == recordId }
                ?: return@pending null
            val plan = nativeRecordActions(
                schema = schema,
                resource = actionResource,
                record = record,
                navigationContext = datasetContext.bindingValues,
                authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
            ).commandForms.singleOrNull { candidate -> candidate.action.id == saved.actionId }
                ?: return@pending null
            val mutationRecoveryOwner = nativeFormMutationRecoveryOwner(
                appId = schema.app.id,
                viewId = view.id,
                actionId = plan.action.id,
                resourceId = actionResource.id,
                intent = plan.action.intent,
                recordId = record.id,
            ) ?: return@pending null
            PendingNativeRecordCommandFormAction(
                plan = plan,
                itemLabel = nativeRecordPresentation(actionResource, record).title,
                resource = schema.resource(plan.action.resourceId) ?: return@pending null,
                datasetContext = datasetContext,
                restoreKey = pendingRecordCommandFormActionToken.orEmpty(),
                mutationRecoveryOwner = mutationRecoveryOwner,
            )
        }
    val persistentCollectionCreate = openCollectionCreate?.takeIf {
        state is NativeScreenState.Ready &&
            presentedRecords.isNotEmpty() &&
            presentedSurface in setOf(
                GenericNativeSurface.List,
                GenericNativeSurface.Grid,
                GenericNativeSurface.Table,
            )
    }
    val mailWorkspacePlan = remember(
        schema,
        presentedResource,
        presentedRecords,
        datasetContext,
        selectedRecordId,
        selectedRecordResourceId,
        mailWorkspaceEligible,
        mailWorkspaceSection,
    ) {
        presentedResource
            ?.takeIf { mailWorkspaceEligible }
            ?.let { currentResource ->
                nativeMailWorkspacePlan(
                    schema = schema,
                    currentResource = currentResource,
                    currentRecords = presentedRecords,
                    context = datasetContext,
                    selectedRecordId = selectedRecordId,
                    selectedRecordResourceId = selectedRecordResourceId,
                )
            }
    }
    val mailWorkspaceSearchable = nativeMailWorkspaceSearchAvailable(
        stateReady = state is NativeScreenState.Ready,
        messageCount = mailWorkspacePlan?.messages?.size ?: 0,
        query = collectionQuery,
    )
    val mailWorkspaceDetailTarget = remember(
        schema,
        presentedResource,
        presentedRecords,
        datasetContext,
        mailWorkspacePlan?.selectedMessage,
    ) {
        presentedResource
            ?.takeIf { mailWorkspaceEligible }
            ?.let { currentResource ->
                nativeMailWorkspaceDetailTarget(
                    schema = schema,
                    currentResource = currentResource,
                    currentRecords = presentedRecords,
                    context = datasetContext,
                    selectedMessage = mailWorkspacePlan?.selectedMessage,
                )
            }
    }
    val mailWorkspaceContentState = remember(state, mailWorkspaceSection) {
        when (state) {
            NativeScreenState.Loading -> NativeMailWorkspaceContentState.Loading(mailWorkspaceSection)
            is NativeScreenState.Error -> NativeMailWorkspaceContentState.Error(
                section = mailWorkspaceSection,
                message = state.message,
                retry = state.retry,
                retryLabel = state.retryLabel,
            )
            is NativeScreenState.Ready -> if (state.records.isEmpty()) {
                NativeMailWorkspaceContentState.Empty(mailWorkspaceSection)
            } else {
                NativeMailWorkspaceContentState.Ready
            }
        }
    }
    val inlineRecordForm = pendingRecordFormAction?.let {
        nativeRecordFormPresentation(it.plan.kind) == NativeRecordFormPresentation.Inline
    } == true
    val recordFormContent: @Composable (PendingNativeRecordFormAction) -> Unit = { pending ->
        GenericRecordActionForm(
            pending = pending,
            presentation = nativeRecordFormPresentation(pending.plan.kind),
            schema = schema,
            actionExecutor = actionExecutor,
            filePicker = filePicker,
            pendingMutationStore = pendingMutationStore,
            mutationRecovery = formMutationRecovery,
            onMutationStarted = { owner ->
                activeMutationOwners += owner
                formMutationRecoveryToken = owner.begin(mutationReconciliationGeneration).encode()
            },
            onMutationFinished = { owner, result ->
                activeMutationOwners -= owner
                val current = decodeNativeFormMutationRecoveryState(formMutationRecoveryToken)
                if (current?.owner == owner) {
                    formMutationRecoveryToken = current.afterExecutionResult(
                        result = result,
                        currentReconciliationGeneration = mutationReconciliationGeneration,
                    )?.encode()
                }
            },
            onDismiss = { pendingRecordFormActionToken = null },
            onActionSucceeded = { action ->
                pendingRecordFormActionToken = null
                inlineActionSucceeded?.invoke(action)
            },
        )
    }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "Dynamic surface action ${view.sourceActionId}"
            },
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (
                !inlineRecordForm &&
                state is NativeScreenState.Ready &&
                (
                    searchableCollection ||
                    persistentCollectionCreate != null ||
                        collectionActionCapabilities.commands.isNotEmpty() ||
                        collectionBatchPlans.isNotEmpty()
                    )
            ) {
                GenericCollectionCommandBar(
                    resourceName = presentedResource?.name ?: resource?.name.orEmpty(),
                    searchQuery = collectionQuery,
                    onSearchQueryChanged = if (searchableCollection) {
                        { query -> collectionQuery = query }
                    } else {
                        null
                    },
                    createLabel = collectionCreatePlan?.action?.label,
                    onCreate = persistentCollectionCreate,
                    commands = collectionActionCapabilities.commands,
                    batches = collectionBatchPlans,
                    onCommand = { plan -> pendingCollectionCommandActionId = plan.action.id },
                    onBatch = { plan ->
                        if (formMutationRecovery?.blocksSubmission != true) {
                            pendingCollectionBatchActionId = plan.action.id
                        }
                    },
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
            inlineRecordForm -> recordFormContent(requireNotNull(pendingRecordFormAction))
            presentedResource == null -> GenericRendererError("This view references an unknown resource.")
            choresWorkspace != null && !showSelectedRecordDetail -> NativeChoresWorkspaceSurface(
                presentation = choresWorkspace,
                onSelectRecord = onSelectRecord,
                recordActions = { record ->
                    nativeRecordCardActions(
                        capabilities = nativeRecordActions(
                            schema = schema,
                            resource = presentedResource,
                            record = record,
                            navigationContext = datasetContext.bindingValues,
                            authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
                        ),
                        record = record,
                        onEditRecord = openRecordEdit,
                        onDeleteRecord = openRecordDelete,
                        onCommandRecord = executeRecordCommand,
                        onCommandFormRecord = openRecordCommandForm,
                    )
                },
                navigationItems = workspaceNavigationItems,
                onNavigate = onWorkspaceNavigate,
                createLabel = collectionCreatePlan?.action?.label,
                onCreate = openCollectionCreate,
                roster = rosterPresentation,
                rosterMemberActions = { person ->
                    val teamRecord = datasetContext.parentRecord
                    val plan = teamRecord?.let { record ->
                        nativeChoresRosterMemberRemovalPlan(
                            schema = schema,
                            teamRecord = record,
                            person = person,
                            authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
                        )
                    }
                    listOfNotNull(
                        plan?.let { removal ->
                            NextcloudCardAction(
                                label = "Remove member",
                                semanticId = removal.action.id,
                                destructive = true,
                                onClick = {
                                    pendingRecordDeleteAction = PendingNativeRecordDeleteAction(
                                        plan = removal,
                                        itemLabel = person.displayName,
                                    )
                                },
                            )
                        },
                    )
                },
            )
            mailWorkspacePlan != null && state is NativeScreenState.Loading ->
                NativeMailWorkspace(
                    plan = mailWorkspacePlan,
                    onSelectRecord = onSelectRecord,
                    collectionStateKey = collectionSearchContextKey,
                    contentState = mailWorkspaceContentState,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    searchQuery = collectionQuery,
                    onSearchQueryChanged = { query: String -> collectionQuery = query }.takeIf {
                        mailWorkspaceSearchable
                    },
                )
            mailWorkspacePlan != null && state is NativeScreenState.Error ->
                NativeMailWorkspace(
                    plan = mailWorkspacePlan,
                    onSelectRecord = onSelectRecord,
                    collectionStateKey = collectionSearchContextKey,
                    contentState = mailWorkspaceContentState,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    searchQuery = collectionQuery,
                    onSearchQueryChanged = { query: String -> collectionQuery = query }.takeIf {
                        mailWorkspaceSearchable
                    },
                )
            state is NativeScreenState.Loading -> GenericRendererLoading(view.title)
            state is NativeScreenState.Error -> GenericRendererError(
                state.message,
                state.retry,
                state.retryLabel,
            )
            state is NativeScreenState.Ready && presentedSurface == GenericNativeSurface.Form ->
                GenericNativeForm(
                    schema = schema,
                    view = view,
                    resource = presentedResource,
                    initialRecord = presentedRecords.firstOrNull() ?: datasetContext.parentRecord,
                    datasetContext = datasetContext,
                    executor = actionExecutor,
                    filePicker = filePicker,
                    onActionSucceeded = onActionSucceeded,
                    // A standalone form has no authoritative collection to refresh in place.
                    // Return to its caller so that surface can reload and verify an ambiguous
                    // mutation result before the user retries it.
                    onActionOutcomeUnknown = onActionSucceeded,
                    mutationReconciliationGeneration = mutationReconciliationGeneration,
                )
            state is NativeScreenState.Ready &&
                state.records.isEmpty() &&
                mailWorkspacePlan != null ->
                NativeMailWorkspace(
                    plan = mailWorkspacePlan,
                    onSelectRecord = onSelectRecord,
                    collectionStateKey = collectionSearchContextKey,
                    contentState = mailWorkspaceContentState,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    searchQuery = collectionQuery,
                    onSearchQueryChanged = { query: String -> collectionQuery = query }.takeIf {
                        mailWorkspaceSearchable
                    },
                )
            state is NativeScreenState.Ready &&
                presentedRecords.isEmpty() &&
                view.compositeDataGrid == null &&
                nestedBoard == null -> {
                GenericRendererEmpty(
                    resourceId = presentedResource.id,
                    resourceName = presentedResource.name,
                    createLabel = collectionCreatePlan?.action?.label,
                    onCreate = openCollectionCreate,
                )
            }
            state is NativeScreenState.Ready &&
                searchableCollection &&
                visiblePresentedRecords.isEmpty() -> {
                if (loadingMore || onLoadMore != null || loadMoreError != null) {
                    GenericRendererSearchPagingState(
                        query = collectionQuery,
                        loading = loadingMore || (onLoadMore != null && loadMoreError == null),
                        error = loadMoreError,
                        onRetry = onLoadMore,
                        onClear = { collectionQuery = "" },
                    )
                } else {
                    GenericRendererNoSearchResults(
                        query = collectionQuery,
                        onClear = { collectionQuery = "" },
                    )
                }
            }
            state is NativeScreenState.Ready && mailWorkspacePlan != null ->
                NativeMailWorkspace(
                    plan = mailWorkspacePlan,
                    onSelectRecord = onSelectRecord,
                    collectionStateKey = collectionSearchContextKey,
                    contentState = mailWorkspaceContentState,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    searchQuery = collectionQuery,
                    onSearchQueryChanged = { query: String -> collectionQuery = query }.takeIf {
                        mailWorkspaceSearchable
                    },
                    detailContent = mailWorkspaceDetailTarget
                        ?.let { target ->
                        {
                            GenericMailMessageDetail(
                                schema = schema,
                                resource = target.resource,
                                record = target.record,
                                message = target.presentation,
                                datasetContext = datasetContext,
                                actionExecutor = actionExecutor,
                                onActionSucceeded = onActionSucceeded,
                                onInlineActionSucceeded = onInlineActionSucceeded,
                            )
                        }
                    },
                )
            state is NativeScreenState.Ready -> when (presentedSurface) {
                GenericNativeSurface.List -> GenericRecordCollection(
                    schema = schema,
                    resource = presentedResource,
                    records = visiblePresentedRecords,
                    datasetContext = datasetContext,
                    actionExecutor = actionExecutor,
                    onSelectRecord = onSelectRecord,
                    onInlineActionSucceeded = inlineActionSucceeded,
                    onEditRecord = openRecordEdit,
                    onDeleteRecord = openRecordDelete,
                    onCommandRecord = executeRecordCommand,
                    onCommandFormRecord = openRecordCommandForm,
                    imageLoader = imageLoader,
                    reorder = collectionActionCapabilities.reorder.takeIf {
                        collectionQuery.isBlank()
                    },
                    pendingCollectionReorderOrder = collectionActionCapabilities.reorder
                        ?.takeIf { plan ->
                            val key = nativePendingCollectionReorderKey(plan, presentedResource.id)
                            pendingCollectionReorderActionId == plan.action.id &&
                                pendingCollectionReorderResourceId == presentedResource.id &&
                                pendingCollectionReorderScopeId == key.targetRecordId
                        }
                        ?.let { pendingCollectionReorderIds },
                    pendingCollectionReorderRecoveryRequested = pendingCollectionReorderRecoveryRequested,
                    onPendingCollectionReorderChanged = { plan, orderedRecordIds, recoveryRequested ->
                        val scopeId = nativePendingCollectionReorderKey(
                            plan,
                            presentedResource.id,
                        ).targetRecordId
                        if (orderedRecordIds == null) {
                            if (
                                pendingCollectionReorderActionId == plan.action.id &&
                                pendingCollectionReorderResourceId == presentedResource.id &&
                                pendingCollectionReorderScopeId == scopeId
                            ) {
                                pendingCollectionReorderActionId = null
                                pendingCollectionReorderResourceId = null
                                pendingCollectionReorderScopeId = null
                                pendingCollectionReorderIds = null
                                pendingCollectionReorderRecoveryRequested = false
                            }
                        } else {
                            pendingCollectionReorderActionId = plan.action.id
                            pendingCollectionReorderResourceId = presentedResource.id
                            pendingCollectionReorderScopeId = scopeId
                            pendingCollectionReorderIds = orderedRecordIds.toCollection(ArrayList())
                            pendingCollectionReorderRecoveryRequested = recoveryRequested
                        }
                    },
                    pendingMutationStore = pendingMutationStore,
                    authoritativeRecordsKey = NativeAuthoritativeRecordsKey(presentedRecords),
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                )
                GenericNativeSurface.Grid -> GenericRecordGrid(
                    presentedResource,
                    visiblePresentedRecords,
                    onSelectRecord,
                    recordImageLoader,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                )
                GenericNativeSurface.Board -> GenericRecordBoard(
                    schema = schema,
                    resource = presentedResource,
                    records = presentedRecords,
                    declaredLanes = nestedBoard?.boardLanes?.let { lanes ->
                        val recordsById = presentedRecords.associateBy(NativeRecord::id)
                        lanes.map { lane ->
                            lane.copy(records = lane.records.map { record -> recordsById[record.id] ?: record })
                        }
                    },
                    onSelectRecord = onSelectRecord,
                    actionExecutor = actionExecutor,
                    onActionSucceeded = onInlineActionSucceeded ?: onActionSucceeded,
                    reconciliation = boardMoveReconciliation,
                )
                GenericNativeSurface.Mailbox -> GenericMailboxCollection(presentedResource, presentedRecords, onSelectRecord)
                GenericNativeSurface.MediaLibrary -> GenericMediaLibraryCollection(
                    presentedResource,
                    presentedRecords,
                    nativeAudioCollectionContext(schema, datasetContext),
                    onSelectRecord,
                    imageLoader,
                    audioPlayer,
                    mediaArtworkResolver,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                )
                GenericNativeSurface.Insights -> GenericInsightCollection(presentedResource, presentedRecords, onSelectRecord)
                GenericNativeSurface.Table -> GenericTableCollection(
                    schema,
                    view,
                    presentedResource,
                    presentedRecords,
                    datasetContext,
                    actionExecutor,
                    onSelectRecord,
                    onInlineActionSucceeded,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                    collectionQuery,
                )
                GenericNativeSurface.Detail -> GenericRecordDetail(
                    schema = schema,
                    resource = presentedResource,
                    record = selectedRecordId?.let { id -> presentedRecords.firstOrNull { it.id == id } }
                        ?: presentedRecords.first(),
                    datasetContext = datasetContext,
                    actionExecutor = actionExecutor,
                    onActionSucceeded = onActionSucceeded,
                    onInlineActionSucceeded = onInlineActionSucceeded,
                    onOpenLink = onOpenLink,
                    imageLoader = imageLoader,
                )
                GenericNativeSurface.Form -> Unit
            }
            }
            }
        }
    }
    if (!inlineRecordForm) pendingRecordFormAction?.let { recordFormContent(it) }
    pendingRecordCommandFormAction?.let { pending ->
        GenericRecordActionForm(
            pending = pending,
            schema = schema,
            actionExecutor = actionExecutor,
            filePicker = filePicker,
            pendingMutationStore = pendingMutationStore,
            mutationRecovery = formMutationRecovery,
            onMutationStarted = { owner ->
                activeMutationOwners += owner
                formMutationRecoveryToken = owner.begin(mutationReconciliationGeneration).encode()
            },
            onMutationFinished = { owner, result ->
                activeMutationOwners -= owner
                val current = decodeNativeFormMutationRecoveryState(formMutationRecoveryToken)
                if (current?.owner == owner) {
                    formMutationRecoveryToken = current.afterExecutionResult(
                        result = result,
                        currentReconciliationGeneration = mutationReconciliationGeneration,
                    )?.encode()
                }
            },
            onDismiss = { pendingRecordCommandFormActionToken = null },
            onActionSucceeded = { action ->
                pendingRecordCommandFormActionToken = null
                inlineActionSucceeded?.invoke(action)
            },
        )
    }
    pendingRecordDeleteAction?.let { pending ->
        GenericRecordDeleteActionDialog(
            pending = pending,
            actionExecutor = actionExecutor,
            onDismiss = { pendingRecordDeleteAction = null },
            onActionSucceeded = { action ->
                pendingRecordDeleteAction = null
                inlineActionSucceeded?.invoke(action)
            },
            onOutcomeUnknown = { action ->
                inlineActionSucceeded?.invoke(action)
            },
        )
    }
    pendingRecordCommandAction?.let { pending ->
        GenericRecordCommandActionDialog(
            pending = pending,
            actionExecutor = actionExecutor,
            pendingMutationStore = pendingMutationStore,
            onDismiss = { pendingRecordCommandAction = null },
            onActionSucceeded = { action ->
                pendingRecordCommandAction = null
                inlineActionSucceeded?.invoke(action)
            },
            onOutcomeUnknown = { action ->
                inlineActionSucceeded?.invoke(action)
            },
        )
    }
    pendingCollectionCommandAction?.let { plan ->
        GenericCollectionCommandDialog(
            plan = plan,
            resourceName = resource?.name ?: view.title,
            resourceId = requireNotNull(resource).id,
            actionExecutor = actionExecutor,
            onDismiss = { pendingCollectionCommandActionId = null },
            onActionSucceeded = { action ->
                pendingCollectionCommandActionId = null
                inlineActionSucceeded?.invoke(action)
            },
            onOutcomeUnknown = { action ->
                inlineActionSucceeded?.invoke(action)
            },
        )
    }
    pendingCollectionBatchAction?.let { plan ->
        val recoveryOwner = pendingCollectionBatchRecoveryOwner ?: return@let
        GenericCollectionBatchDialog(
            plan = plan,
            resource = requireNotNull(resource),
            records = readyRecords,
            schema = schema,
            datasetContext = datasetContext,
            actionExecutor = actionExecutor,
            relationLoader = collectionBatchRelationLoader,
            mutationRecovery = formMutationRecovery,
            mutationRecoveryOwner = recoveryOwner,
            onMutationStarted = { owner ->
                activeMutationOwners += owner
                formMutationRecoveryToken = owner.begin(mutationReconciliationGeneration).encode()
            },
            onMutationFinished = { owner, result ->
                activeMutationOwners -= owner
                val current = decodeNativeFormMutationRecoveryState(formMutationRecoveryToken)
                if (current?.owner == owner) {
                    formMutationRecoveryToken = current.afterExecutionResult(
                        result = result,
                        currentReconciliationGeneration = mutationReconciliationGeneration,
                    )?.encode()
                }
            },
            onDismiss = { pendingCollectionBatchActionId = null },
            onActionSucceeded = { action ->
                pendingCollectionBatchActionId = null
                inlineActionSucceeded?.invoke(action)
            },
            onOutcomeUnknown = { action ->
                inlineActionSucceeded?.invoke(action)
            },
        )
    }
}

@Composable
private fun GenericCollectionCommandBar(
    resourceName: String,
    searchQuery: String,
    onSearchQueryChanged: ((String) -> Unit)?,
    createLabel: String?,
    onCreate: (() -> Unit)?,
    commands: List<NativeCollectionCommandActionPlan>,
    batches: List<NativeCollectionBatchActionPlan>,
    onCommand: (NativeCollectionCommandActionPlan) -> Unit,
    onBatch: (NativeCollectionBatchActionPlan) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val hasSecondaryActions = commands.isNotEmpty() || batches.isNotEmpty()
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        BoxWithConstraints {
            val compactActions = maxWidth < 520.dp
            val actions: @Composable () -> Unit = {
                onCreate?.let { create ->
                    Button(
                        onClick = create,
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .semantics {
                                contentDescription = createLabel?.takeIf(String::isNotBlank)
                                    ?: "Create ${resourceName.ifBlank { "item" }}"
                            },
                    ) {
                        Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            createLabel?.takeIf(String::isNotBlank) ?: "Create item",
                            modifier = Modifier.padding(start = NextcloudSpacing.Small),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (hasSecondaryActions) Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier
                            .heightIn(min = 40.dp)
                            .semantics {
                                contentDescription = "More ${resourceName.ifBlank { "collection" }} actions"
                            },
                    ) {
                        Icon(
                            NextcloudIcons.More,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        if (!compactActions) {
                            Text(
                                "More actions",
                                modifier = Modifier.padding(start = NextcloudSpacing.Small),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.semantics {
                            contentDescription = "${resourceName.ifBlank { "Collection" }} actions"
                        },
                    ) {
                        commands.forEach { plan ->
                            DropdownMenuItem(
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = plan.action.label
                                },
                                text = {
                                    Text(
                                        plan.action.label,
                                        color = if (plan.action.risk == ActionRisk.destructive) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onCommand(plan)
                                },
                            )
                        }
                        batches.forEach { plan ->
                            DropdownMenuItem(
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = plan.action.label
                                },
                                text = {
                                    Text(
                                        plan.action.label,
                                        color = if (plan.action.risk == ActionRisk.destructive) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onBatch(plan)
                                },
                            )
                        }
                    }
                }
            }
            if (compactActions && onSearchQueryChanged != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Medium,
                        vertical = NextcloudSpacing.Small,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    GenericCollectionSearchField(
                        resourceName = resourceName,
                        query = searchQuery,
                        onQueryChanged = onSearchQueryChanged,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (onCreate != null || hasSecondaryActions) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            actions()
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = NextcloudSpacing.Large,
                            vertical = NextcloudSpacing.Small,
                        ),
                    horizontalArrangement = Arrangement.spacedBy(
                        NextcloudSpacing.Small,
                        alignment = if (dense) Alignment.End else Alignment.Start,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onSearchQueryChanged?.let { onQueryChanged ->
                        GenericCollectionSearchField(
                            resourceName = resourceName,
                            query = searchQuery,
                            onQueryChanged = onQueryChanged,
                            modifier = Modifier.weight(1f).widthIn(max = 560.dp),
                        )
                    }
                    actions()
                }
            }
        }
    }
}

@Composable
private fun GenericCollectionCommandDialog(
    plan: NativeCollectionCommandActionPlan,
    resourceName: String,
    resourceId: String,
    actionExecutor: NativeActionExecutor,
    onDismiss: () -> Unit,
    onActionSucceeded: (ActionSpec) -> Unit,
    onOutcomeUnknown: (ActionSpec) -> Unit,
) {
    var error by remember(plan.action.id) { mutableStateOf<String?>(null) }
    var failureOutcome by remember(plan.action.id) {
        mutableStateOf<NativeActionFailureOutcome?>(null)
    }
    var executing by remember(plan.action.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val outcomeUnknown = failureOutcome?.requiresMutationReconciliation() == true

    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = {
            Text(
                if (outcomeUnknown) {
                    "${plan.action.label} result unknown"
                } else {
                    "${plan.action.label} $resourceName?"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(
                    "This changes the entire collection and cannot be undone. Continue?",
                )
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (outcomeUnknown) {
                    Text(
                        "The collection is being refreshed to check the server result. " +
                            "Review the refreshed data before trying this action again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !executing, onClick = onDismiss) {
                Text(if (outcomeUnknown) "Close" else "Cancel")
            }
        },
        confirmButton = {
            if (!outcomeUnknown) {
                Button(
                    enabled = !executing,
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm collection action ${plan.action.id} for $resourceId"
                    },
                    onClick = {
                        val request = runCatching {
                            plan.request(confirmed = true)
                        }.getOrElse { failure ->
                            error = failure.message ?: "The collection action could not be submitted."
                            return@Button
                        }
                        executing = true
                        error = null
                        failureOutcome = null
                        scope.launch {
                            when (val result = actionExecutor.execute(request)) {
                                is NativeActionExecutionResult.Success ->
                                    onActionSucceeded(plan.action)
                                is NativeActionExecutionResult.Failure -> {
                                    error = result.message
                                    failureOutcome = result.outcome
                                    if (result.outcome.requiresMutationReconciliation()) {
                                        onOutcomeUnknown(plan.action)
                                    }
                                }
                            }
                            executing = false
                        }
                    },
                ) {
                    if (executing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(plan.action.label)
                    }
                }
            }
        },
    )
}

@Composable
private fun GenericCollectionBatchDialog(
    plan: NativeCollectionBatchActionPlan,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    schema: NativeAppSchema,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    relationLoader: NativeCollectionBatchRelationLoader?,
    mutationRecovery: NativeFormMutationRecoveryState?,
    mutationRecoveryOwner: NativeFormMutationRecoveryOwner,
    onMutationStarted: (NativeFormMutationRecoveryOwner) -> Unit,
    onMutationFinished: (NativeFormMutationRecoveryOwner, NativeActionExecutionResult) -> Unit,
    onDismiss: () -> Unit,
    onActionSucceeded: (ActionSpec) -> Unit,
    onOutcomeUnknown: (ActionSpec) -> Unit,
) {
    val selectableRecords = remember(records, plan.selectableRecordIds) {
        records.filter { record -> record.id in plan.selectableRecordIds }
    }
    val recordIds = remember(selectableRecords) { selectableRecords.map(NativeRecord::id) }
    var selectedRecordIds by rememberSaveable(plan.action.id, recordIds) {
        mutableStateOf(emptyList<String>())
    }
    var values by rememberSaveable(plan.action.id) {
        mutableStateOf(initialNativeCollectionBatchDraft(plan.fields))
    }
    var error by remember(plan.action.id) { mutableStateOf<String?>(null) }
    var failureOutcome by remember(plan.action.id) {
        mutableStateOf<NativeActionFailureOutcome?>(null)
    }
    var awaitingConfirmation by remember(plan.action.id) { mutableStateOf(false) }
    var executing by remember(plan.action.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val outcomeUnknown =
        failureOutcome?.requiresMutationReconciliation() == true ||
            (
                mutationRecovery?.owner == mutationRecoveryOwner &&
                    mutationRecovery.phase == NativeFormMutationRecoveryPhase.AwaitingReconciliation
                )
    val submissionBlocked = mutationRecovery != null
    val verifiedRelationsByField = remember(plan.fields, resource, schema) {
        plan.fields.mapNotNull { field ->
            val relatedResourceId = field.relatedResourceId ?: return@mapNotNull null
            val rendererField = field.toNativeCollectionFieldSpec()
            nativeRelationRelationship(rendererField, resource, schema)
                ?.takeIf { relationship -> relationship.parentResourceId == relatedResourceId }
                ?.let { field.id to relatedResourceId }
        }.toMap()
    }
    val relationAvailableValues = remember(datasetContext) {
        buildMap {
            datasetContext.parentRecord?.values?.forEach { (fieldId, value) ->
                value?.takeIf(String::isNotBlank)?.let { put(fieldId, it) }
            }
            datasetContext.parentRecord?.bindingContext?.forEach { (fieldId, value) ->
                value.takeIf(String::isNotBlank)?.let { put(fieldId, it) }
            }
            putAll(datasetContext.bindingValues.filterValues(String::isNotBlank))
        }
    }
    val relationRequest = verifiedRelationsByField.takeIf { relations -> relations.isNotEmpty() }?.let { relations ->
        NativeCollectionBatchRelationLoadRequest(
            actionId = plan.action.id,
            resourceId = resource.id,
            relatedResourceIdsByField = relations,
            bindingValues = relationAvailableValues,
            forceRefresh = false,
        )
    }
    var relationLoadAttempt by rememberSaveable(plan.action.id) { mutableStateOf(0) }
    var relationRecords by remember(plan.action.id, relationRequest) {
        mutableStateOf<Map<String, List<NativeRecord>>>(emptyMap())
    }
    var relationErrors by remember(plan.action.id, relationRequest) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var relationsLoading by remember(plan.action.id, relationRequest) {
        mutableStateOf(relationRequest != null)
    }
    LaunchedEffect(relationLoader, relationRequest, relationLoadAttempt) {
        val request = relationRequest ?: run {
            relationsLoading = false
            return@LaunchedEffect
        }
        relationsLoading = true
        relationErrors = emptyMap()
        val requestedResourceIds = request.relatedResourceIdsByField.values.toSet()
        val outcome = relationLoader?.let { loader ->
            runCatching {
                loader.load(request.copy(forceRefresh = relationLoadAttempt > 0))
            }
        }
        val result = outcome?.getOrNull()
        relationRecords = result?.recordsByResourceId.orEmpty()
            .filterKeys(requestedResourceIds::contains)
        relationErrors = requestedResourceIds.mapNotNull { resourceId ->
            when {
                outcome == null -> resourceId to "No verified choice loader is available."
                outcome.isFailure -> resourceId to (
                    outcome.exceptionOrNull()?.message?.takeIf(String::isNotBlank)
                        ?: "Could not load choices."
                    )
                result?.errorsByResourceId?.get(resourceId) != null ->
                    resourceId to requireNotNull(result.errorsByResourceId[resourceId])
                resourceId !in relationRecords -> resourceId to "Could not load verified choices."
                else -> null
            }
        }.toMap()
        relationsLoading = false
    }
    val relationContext = datasetContext.withCollectionBatchRelationRecords(relationRecords)
    val requiredRelationUnavailable = plan.fields.any { field ->
        field.required && field.relatedResourceId?.let { relatedResourceId ->
            relationsLoading || relatedResourceId in relationErrors || relatedResourceId !in relationRecords
        } == true
    }

    fun request(confirmed: Boolean): NativeActionRequest.Submit? = runCatching {
        plan.request(
            selectedRecordIds = selectedRecordIds,
            values = nativeCollectionBatchRequestValues(plan.fields, values),
            confirmed = confirmed,
        )
    }.getOrElse { failure ->
        error = failure.message ?: "The selected items could not be submitted."
        null
    }

    fun submit(confirmed: Boolean) {
        if (submissionBlocked || requiredRelationUnavailable) return
        val actionRequest = request(confirmed) ?: return
        executing = true
        error = null
        failureOutcome = null
        onMutationStarted(mutationRecoveryOwner)
        scope.launch {
            val result = actionExecutor.execute(actionRequest)
            onMutationFinished(mutationRecoveryOwner, result)
            when (result) {
                is NativeActionExecutionResult.Success -> onActionSucceeded(plan.action)
                is NativeActionExecutionResult.Failure -> {
                    error = result.message
                    failureOutcome = result.outcome
                    awaitingConfirmation = false
                    if (result.outcome.requiresMutationReconciliation()) {
                        onOutcomeUnknown(plan.action)
                    }
                }
            }
            executing = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = {
            Text(
                when {
                    outcomeUnknown -> "${plan.action.label} result unknown"
                    awaitingConfirmation -> "Confirm ${plan.action.label.lowercase()}"
                    else -> plan.action.label
                },
            )
        },
        text = {
            if (awaitingConfirmation) {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "${plan.action.label} will change ${selectedRecordIds.size} selected " +
                            "${if (selectedRecordIds.size == 1) "item" else "items"}. Continue?",
                    )
                    if (plan.action.risk == ActionRisk.destructive) {
                        Text(
                            "This action changes server data and may not be reversible.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    error?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "${selectedRecordIds.size} of ${plan.maximumSelectionSize} selected",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (plan.minimumSelectionSize > 1) {
                                    Text(
                                        "Select at least ${plan.minimumSelectionSize}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row {
                                TextButton(
                                    enabled = !executing && !submissionBlocked && selectedRecordIds.isNotEmpty(),
                                    onClick = {
                                        selectedRecordIds = emptyList()
                                        error = null
                                    },
                                ) {
                                    Text("Clear")
                                }
                                TextButton(
                                    enabled = !executing && !submissionBlocked && selectedRecordIds.size < minOf(
                                        recordIds.size,
                                        plan.maximumSelectionSize,
                                    ),
                                    onClick = {
                                        selectedRecordIds = recordIds.take(plan.maximumSelectionSize)
                                        error = null
                                    },
                                ) {
                                    Text(
                                        if (recordIds.size <= plan.maximumSelectionSize) {
                                            "Select all"
                                        } else {
                                            "Select ${plan.maximumSelectionSize}"
                                        },
                                    )
                                }
                            }
                        }
                    }
                    items(selectableRecords, key = NativeRecord::id) { record ->
                        val selected = record.id in selectedRecordIds
                        val canToggle = selected ||
                            selectedRecordIds.size < plan.maximumSelectionSize
                        val itemLabel = nativeRecordPresentation(resource, record).title
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .semantics {
                                    contentDescription = "Select $itemLabel"
                                }
                                .clickable(enabled = canToggle && !executing && !submissionBlocked) {
                                    selectedRecordIds = toggleNativeCollectionSelection(
                                        selectedRecordIds = selectedRecordIds,
                                        recordId = record.id,
                                        availableRecordIds = recordIds,
                                        maximumSelectionSize = plan.maximumSelectionSize,
                                    )
                                    error = null
                                }
                                .padding(horizontal = NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected,
                                enabled = canToggle && !executing && !submissionBlocked,
                                onCheckedChange = null,
                            )
                            Text(
                                itemLabel,
                                modifier = Modifier.padding(start = NextcloudSpacing.Small),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    items(plan.fields, key = NativeCollectionBatchInputField::id) { field ->
                        val rendererField = field.toNativeCollectionFieldSpec()
                        val relationship = remember(field, rendererField, resource, schema) {
                            nativeRelationRelationship(rendererField, resource, schema)
                                ?.takeIf { relation ->
                                    relation.parentResourceId == field.relatedResourceId
                                }
                        }
                        if (relationship != null) {
                            val relatedResourceId = requireNotNull(field.relatedResourceId)
                            val relationOptions = nativeRelationOptions(
                                field = rendererField,
                                formResource = resource,
                                schema = schema,
                                context = relationContext,
                            )
                            val relationError = relationErrors[relatedResourceId]
                            GenericRelationshipField(
                                field = rendererField,
                                value = values[field.id].orEmpty(),
                                options = relationOptions,
                                choicesLoaded = relationRecords.containsKey(relatedResourceId),
                                choiceSourceHasRecords =
                                    relationRecords[relatedResourceId].orEmpty().isNotEmpty(),
                                choiceUnavailableReason = when {
                                    relationsLoading || relationError != null ->
                                        NativeRelationChoiceUnavailableReason.source
                                    else -> nativeRelationChoiceUnavailableReason(
                                        rendererField,
                                        resource,
                                        schema,
                                        relationContext,
                                    )
                                },
                                paging = null,
                                error = relationError,
                                enabled = !executing && !outcomeUnknown && !submissionBlocked &&
                                    !relationsLoading && relationError == null,
                                onValueChange = { value ->
                                    values = values + (field.id to value)
                                    error = null
                                },
                            )
                            if (relationsLoading) {
                                Text(
                                    "Loading choices...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (relationError != null) {
                                TextButton(
                                    enabled = !executing && !submissionBlocked,
                                    onClick = { relationLoadAttempt += 1 },
                                ) {
                                    Text("Retry choices")
                                }
                            }
                        } else {
                            GenericFormField(
                                field = rendererField,
                                value = values[field.id].orEmpty(),
                                error = null,
                                enabled = !executing && !outcomeUnknown && !submissionBlocked,
                                filePicker = null,
                                onValueChange = { value ->
                                    values = values + (field.id to value)
                                    error = null
                                },
                            )
                        }
                    }
                    error?.let { message ->
                        item {
                            Text(
                                message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (outcomeUnknown) {
                        item {
                            Text(
                                "The collection is being refreshed to check the server result. " +
                                    "Review the refreshed data before trying this action again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !executing,
                onClick = {
                    if (awaitingConfirmation) {
                        awaitingConfirmation = false
                        error = null
                    } else {
                        onDismiss()
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
            if (!outcomeUnknown) {
                Button(
                    enabled = !executing && !submissionBlocked && !requiredRelationUnavailable,
                    modifier = Modifier.semantics {
                        contentDescription = if (awaitingConfirmation) {
                            "Confirm batch action ${plan.action.id} for ${resource.id}"
                        } else {
                            "Submit batch action ${plan.action.id} for ${resource.id}"
                        }
                    },
                    onClick = {
                        when {
                            awaitingConfirmation -> submit(confirmed = true)
                            plan.requiresConfirmation -> {
                                if (request(confirmed = true) != null) {
                                    error = null
                                    awaitingConfirmation = true
                                }
                            }
                            else -> submit(confirmed = false)
                        }
                    },
                ) {
                    if (executing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (awaitingConfirmation) "Confirm" else plan.action.label)
                    }
                }
            }
        },
    )
}


internal data class RestorableNativeRecordFormAction(
    val actionId: String,
    val resourceId: String,
    val kind: NativeRecordFormActionKind,
    val recordId: String?,
)

internal fun RestorableNativeRecordFormAction.encode(): String? {
    if (
        actionId.isBlank() ||
        resourceId.isBlank() ||
        actionId.length > MAX_SAVED_FORM_ID_LENGTH ||
        resourceId.length > MAX_SAVED_FORM_ID_LENGTH ||
        recordId?.length?.let { it > MAX_SAVED_FORM_ID_LENGTH } == true
    ) {
        return null
    }
    return JsonArray(
        listOf(
            JsonPrimitive(actionId),
            JsonPrimitive(resourceId),
            JsonPrimitive(kind.name),
            recordId?.let(::JsonPrimitive) ?: JsonNull,
        ),
    ).toString()
}

internal fun decodeRestorableNativeRecordFormAction(value: String): RestorableNativeRecordFormAction? {
    if (value.length > MAX_SAVED_FORM_TOKEN_LENGTH) return null
    val parts = runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonArray ?: return null
    if (parts.size != 4) return null
    val actionId = (parts[0] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null
    val resourceId = (parts[1] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null
    val kindName = (parts[2] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null
    val recordId = when (val record = parts[3]) {
        JsonNull -> null
        is JsonPrimitive -> record.takeIf(JsonPrimitive::isString)?.contentOrNull ?: return null
        else -> return null
    }
    if (
        actionId.isBlank() ||
        resourceId.isBlank() ||
        actionId.length > MAX_SAVED_FORM_ID_LENGTH ||
        resourceId.length > MAX_SAVED_FORM_ID_LENGTH ||
        recordId?.length?.let { it > MAX_SAVED_FORM_ID_LENGTH } == true
    ) {
        return null
    }
    val kind = NativeRecordFormActionKind.entries.firstOrNull { candidate -> candidate.name == kindName }
        ?: return null
    return RestorableNativeRecordFormAction(actionId, resourceId, kind, recordId)
}

internal fun encodeNativeRecordFormDraft(values: Map<String, String>): List<String>? {
    if (values.size > MAX_SAVED_FORM_FIELDS) return null
    var totalLength = 0
    val saved = ArrayList<String>(values.size * 2)
    values.entries.sortedBy(Map.Entry<String, String>::key).forEach { (key, value) ->
        if (
            key.isBlank() ||
            key.length > MAX_SAVED_FORM_ID_LENGTH ||
            value.length > MAX_SAVED_FORM_VALUE_LENGTH
        ) {
            return null
        }
        totalLength += key.length + value.length
        if (totalLength > MAX_SAVED_FORM_TOTAL_LENGTH) return null
        saved += key
        saved += value
    }
    return saved
}

internal fun decodeNativeRecordFormDraft(values: List<String>): Map<String, String>? {
    if (values.size % 2 != 0 || values.size / 2 > MAX_SAVED_FORM_FIELDS) return null
    val entries = linkedMapOf<String, String>()
    var totalLength = 0
    values.chunked(2).forEach { (key, value) ->
        if (
            key.isBlank() ||
            key in entries ||
            key.length > MAX_SAVED_FORM_ID_LENGTH ||
            value.length > MAX_SAVED_FORM_VALUE_LENGTH
        ) {
            return null
        }
        totalLength += key.length + value.length
        if (totalLength > MAX_SAVED_FORM_TOTAL_LENGTH) return null
        entries[key] = value
    }
    return entries
}

internal fun nativeRecordFormDraftSaver(declaredFieldIds: Set<String>) = Saver<Map<String, String>, List<String>>(
    save = { draft ->
        if (draft.keys.all(declaredFieldIds::contains)) encodeNativeRecordFormDraft(draft) else null
    },
    restore = { saved ->
        decodeNativeRecordFormDraft(saved)?.takeIf { values -> values.keys.all(declaredFieldIds::contains) }
    },
)

private const val MAX_SAVED_FORM_FIELDS = 64
private const val MAX_SAVED_FORM_ID_LENGTH = 256
private const val MAX_SAVED_FORM_VALUE_LENGTH = 64 * 1024
private const val MAX_SAVED_FORM_TOTAL_LENGTH = 256 * 1024
private const val MAX_SAVED_FORM_TOKEN_LENGTH = 2 * 1024

private data class PendingNativeRecordDeleteAction(
    val plan: NativeRecordDeleteActionPlan,
    val itemLabel: String,
)

private data class PendingNativeRecordCommandAction(
    val plan: NativeRecordCommandActionPlan,
    val targetRecordId: String,
    val itemLabel: String,
    val initialError: String? = null,
    val initialFailureOutcome: NativeActionFailureOutcome? = null,
)


@Composable
private fun GenericRecordDeleteActionDialog(
    pending: PendingNativeRecordDeleteAction,
    actionExecutor: NativeActionExecutor,
    onDismiss: () -> Unit,
    onActionSucceeded: (ActionSpec) -> Unit,
    onOutcomeUnknown: (ActionSpec) -> Unit,
) {
    var error by remember(pending) { mutableStateOf<String?>(null) }
    var deleting by remember(pending) { mutableStateOf(false) }
    var outcomeUnknown by remember(pending) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = {
            Text(
                if (outcomeUnknown) {
                    "Delete result unknown"
                } else {
                    "Delete ${pending.itemLabel}?"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text("This removes the item from the server and cannot be undone.")
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (outcomeUnknown) {
                    Text(
                        "The collection is being refreshed to check the server result. " +
                            "Review the refreshed data before trying to delete this item again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDismiss) {
                Text(if (outcomeUnknown) "Close" else "Cancel")
            }
        },
        confirmButton = {
            if (!outcomeUnknown) {
                Button(
                    enabled = !deleting,
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm record delete ${pending.plan.action.id}"
                    },
                    onClick = {
                        val request = pending.plan.request(confirmed = true)
                        deleting = true
                        error = null
                        outcomeUnknown = false
                        scope.launch {
                            when (val result = actionExecutor.execute(request)) {
                                is NativeActionExecutionResult.Success -> {
                                    onActionSucceeded(pending.plan.action)
                                }
                                is NativeActionExecutionResult.Failure -> {
                                    error = result.message
                                    outcomeUnknown = !result.outcome.allowsGenericDeleteRetry()
                                    if (outcomeUnknown) {
                                        onOutcomeUnknown(pending.plan.action)
                                    }
                                }
                            }
                            deleting = false
                        }
                    },
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Delete")
                    }
                }
            }
        },
    )
}

@Composable
private fun GenericRecordCommandActionDialog(
    pending: PendingNativeRecordCommandAction,
    actionExecutor: NativeActionExecutor,
    pendingMutationStore: NativePendingMutationStore?,
    onDismiss: () -> Unit,
    onActionSucceeded: (ActionSpec) -> Unit,
    onOutcomeUnknown: (ActionSpec) -> Unit,
) {
    val ui = nativeRecordCommandUi(
        effect = pending.plan.effect,
        itemLabel = pending.itemLabel,
        actionLabel = pending.plan.action.label,
    )
    var error by remember(pending) { mutableStateOf(pending.initialError) }
    var failureOutcome by remember(pending) { mutableStateOf(pending.initialFailureOutcome) }
    var executing by remember(pending) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val retryingDirectAction = !pending.plan.requiresConfirmation
    val outcomeUnknown = failureOutcome?.requiresCommandReconciliation() == true

    AlertDialog(
        onDismissRequest = { if (!executing) onDismiss() },
        title = {
            Text(
                if (outcomeUnknown) {
                    "${ui.label} result unknown"
                } else if (retryingDirectAction) {
                    "${ui.label} failed"
                } else {
                    requireNotNull(ui.confirmationTitle)
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                ui.confirmationMessage?.takeIf { pending.plan.requiresConfirmation }?.let { message ->
                    Text(message)
                }
                error?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (outcomeUnknown) {
                    Text(
                        "The collection is being refreshed to check the server result. " +
                            "Review the refreshed item before trying this action again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !executing, onClick = onDismiss) {
                Text(if (outcomeUnknown) "Close" else "Cancel")
            }
        },
        confirmButton = {
            if (!outcomeUnknown) {
                Button(
                    enabled = !executing,
                    onClick = {
                        executing = true
                        error = null
                        failureOutcome = null
                        scope.launch {
                            val result = runCatching {
                                executeNativeRecordCommand(
                                    plan = pending.plan,
                                    targetRecordId = pending.targetRecordId,
                                    confirmed = pending.plan.requiresConfirmation,
                                    actionExecutor = actionExecutor,
                                    pendingMutationStore = pendingMutationStore,
                                )
                            }.getOrElse { failure ->
                                error = failure.message ?: "The action could not be staged safely."
                                executing = false
                                return@launch
                            }
                            when (result) {
                                is NativeActionExecutionResult.Success -> {
                                    onActionSucceeded(pending.plan.action)
                                }
                                is NativeActionExecutionResult.Failure -> {
                                    error = result.message
                                    failureOutcome = result.outcome
                                    if (result.outcome.requiresCommandReconciliation()) {
                                        onOutcomeUnknown(pending.plan.action)
                                    }
                                }
                            }
                            executing = false
                        }
                    },
                ) {
                    if (executing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (retryingDirectAction) "Try again" else ui.label)
                    }
                }
            }
        },
    )
}

@Composable
internal fun GenericRecordTable(
    schema: NativeAppSchema,
    view: ViewSpec,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
    modifier: Modifier = Modifier,
) {
    val composite = view.compositeDataGrid
    val columnResource = composite?.let { schema.resource(it.columnResourceId) }
    val columnRecords = composite?.let { datasetContext.relatedRecords[it.columnResourceId].orEmpty() }.orEmpty()
    val projection = remember(resource, records, columnResource, columnRecords, composite) {
        nativeTableProjection(resource, records, columnResource, columnRecords, composite)
    }
    val projectedResource = projection.resource
    val projectedRecords = projection.records
    val fields = remember(projection) {
        if (projection.composite) {
            projectedResource.fields.filter { it.id in projection.projectedFieldIds } +
                listOfNotNull(projectedResource.fields.firstOrNull { it.id == projection.frozenFieldId })
        } else {
            nativeTableFields(projectedResource, projectedRecords)
        }
    }.distinctBy(FieldSpec::id)
    if (fields.isEmpty()) {
        GenericRecordList(projectedResource, projectedRecords, onSelectRecord, modifier)
        return
    }
    var activeEdit by remember(schema, projection) { mutableStateOf<NativeCellEditPlan?>(null) }
    var editValue by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }
    var savingEdit by remember { mutableStateOf(false) }
    val editedValues = remember(schema, projection) { mutableStateMapOf<NativeCellAddress, String>() }
    val scope = rememberCoroutineScope()
    val actionWidth = if (onSelectRecord == null) 0.dp else 48.dp
    val frozenField = fields.firstOrNull { it.id == projection.frozenFieldId }
    val scrollingFields = fields.filterNot { it.id == frozenField?.id }
    val horizontalState = rememberScrollState()
    val verticalState = rememberLazyListState()
    NativeCollectionAutoPager(
        listState = verticalState,
        itemCount = projectedRecords.size,
        onLoadMore = onLoadMore,
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )
    Column(
        modifier = modifier.fillMaxSize().padding(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            frozenField?.let { field -> GenericTableHeaderCell(field, field.nativeTableColumnWidth()) }
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(horizontalState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                scrollingFields.forEach { field -> GenericTableHeaderCell(field, field.nativeTableColumnWidth()) }
                if (actionWidth > 0.dp) Box(Modifier.width(actionWidth))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(
            state = verticalState,
            modifier = Modifier.weight(1f),
        ) {
            items(projectedRecords, key = NativeRecord::id) { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            onSelectRecord?.let { callback -> Modifier.clickable { callback(record) } } ?: Modifier,
                        )
                        .heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    frozenField?.let { field ->
                        val address = NativeCellAddress(record.id, field.id)
                        val plan = nativeCellEditPlan(schema, resource, projection, record, field)
                        GenericTableValueCell(
                            field = field,
                            rawValue = editedValues[address] ?: record.presentationValue(field.id),
                            editPlan = plan,
                            width = field.nativeTableColumnWidth(),
                            emphasized = true,
                            onEdit = {
                                activeEdit = it.copy(originalValue = editedValues[address] ?: it.originalValue)
                                editValue = editedValues[address] ?: it.originalValue
                                editError = null
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f).horizontalScroll(horizontalState),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        scrollingFields.forEachIndexed { index, field ->
                            val address = NativeCellAddress(record.id, field.id)
                            val plan = nativeCellEditPlan(schema, resource, projection, record, field)
                            GenericTableValueCell(
                                field = field,
                                rawValue = editedValues[address] ?: record.presentationValue(field.id),
                                editPlan = plan,
                                width = field.nativeTableColumnWidth(),
                                emphasized = frozenField == null && index == 0,
                                onEdit = {
                                    activeEdit = it.copy(originalValue = editedValues[address] ?: it.originalValue)
                                    editValue = editedValues[address] ?: it.originalValue
                                    editError = null
                                }
                            )
                        }
                        if (onSelectRecord != null) {
                            Icon(
                                NextcloudIcons.ChevronRight,
                                contentDescription = "Open ${nativeRecordPresentation(projectedResource, record).title}",
                                modifier = Modifier.width(actionWidth).padding(NextcloudSpacing.Small),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            NativeCollectionPagingFooter(
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
                onRetry = onLoadMore,
            )
        }
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
                                        editedValues[NativeCellAddress(plan.recordId, plan.field.id)] = editValue.trim()
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
                    if (savingEdit) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Save")
                }
            },
        )
    }
}


@Composable
private fun GenericTableHeaderCell(field: FieldSpec, width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .heightIn(min = 44.dp)
            .background(NextcloudTheme.colors.appTile)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.XSmall),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            field.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun FieldSpec.nativeTableColumnWidth(): androidx.compose.ui.unit.Dp {
    val semanticId = id.lowercase().filter(Char::isLetterOrDigit)
    return when {
        semanticId in setOf("id", "rowid", "recordid", "index", "position") -> 76.dp
        kind == FieldKind.boolean -> 96.dp
        kind == FieldKind.integer || kind == FieldKind.decimal -> 112.dp
        kind == FieldKind.date || kind == FieldKind.dateTime -> 148.dp
        kind == FieldKind.longText || semanticId in setOf("description", "content", "message", "notes") -> 240.dp
        else -> 184.dp
    }
}

@Composable
private fun GenericTableValueCell(
    field: FieldSpec,
    rawValue: String?,
    editPlan: NativeCellEditPlan?,
    width: androidx.compose.ui.unit.Dp,
    emphasized: Boolean,
    onEdit: (NativeCellEditPlan) -> Unit,
) {
    val value = rawValue?.takeIf(String::isNotBlank)?.let { formatNativeField(field, it).displayValue } ?: "-"
    Row(
        modifier = Modifier.width(width)
            .heightIn(min = 50.dp)
            .background(
                if (emphasized) NextcloudTheme.colors.appTile.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.background,
            )
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(editPlan?.let { plan -> Modifier.clickable { onEdit(plan) } } ?: Modifier)
            .padding(horizontal = NextcloudSpacing.Small, vertical = NextcloudSpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = if (emphasized) {
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (value == "-") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (editPlan != null) {
            Icon(
                NextcloudIcons.Edit,
                contentDescription = "Edit ${field.label}",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private data class NativeCellAddress(val recordId: String, val fieldId: String)

@Composable
private fun GenericRecordList(
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
private fun NativeCollectionAutoPager(
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

private fun androidx.compose.foundation.lazy.LazyListScope.NativeCollectionPagingFooter(
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
            GenericCollectionCard(
                resource = projection.resource,
                record = displayRecord,
                onSelectRecord = onSelectRecord,
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

@Composable
private fun GenericRecordCollection(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    records: List<NativeRecord>,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
    onEditRecord: (NativeRecord, NativeRecordFormActionPlan) -> Unit,
    onDeleteRecord: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit,
    onCommandRecord: (NativeRecord, NativeRecordCommandActionPlan) -> Unit,
    onCommandFormRecord: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit,
    imageLoader: NativeImageLoader?,
    reorder: NativeCollectionReorderActionPlan?,
    pendingCollectionReorderOrder: List<String>?,
    pendingCollectionReorderRecoveryRequested: Boolean,
    onPendingCollectionReorderChanged: (NativeCollectionReorderActionPlan, List<String>?, Boolean) -> Unit,
    pendingMutationStore: NativePendingMutationStore?,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val recipes = remember(resource, records) {
        nativeRecipeCollectionPresentations(resource, records)
    }
    if (recipes != null) {
        GenericRecipeCollection(recipes, onSelectRecord, imageLoader)
        return
    }
    val tasks = remember(resource, authoritativeRecordsKey) {
        nativeTaskCollectionPresentations(resource, records)
    }
    if (tasks != null) {
        GenericTaskCollection(
            schema = schema,
            resource = resource,
            rows = tasks,
            authoritativeRecordsKey = authoritativeRecordsKey,
            navigationContext = datasetContext.bindingValues,
            authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
            actionExecutor = actionExecutor,
            onSelectRecord = onSelectRecord,
            onActionSucceeded = onInlineActionSucceeded,
            onEditRecord = onEditRecord,
            onDeleteRecord = onDeleteRecord,
            onCommandRecord = onCommandRecord,
            onCommandFormRecord = onCommandFormRecord,
            reorder = reorder,
            pendingReorderOrder = pendingCollectionReorderOrder,
            pendingReorderRecoveryRequested = pendingCollectionReorderRecoveryRequested,
            onPendingReorderChanged = onPendingCollectionReorderChanged,
            pendingMutationStore = pendingMutationStore,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        return
    }
    val groupware = remember(resource, records) {
        nativeGroupwareCollectionPresentations(resource, records)
    }
    if (groupware != null) {
        GenericGroupwareCollection(groupware, onSelectRecord)
        return
    }
    val categories = remember(resource, records) {
        nativeCategoryCollectionPresentations(resource, records)
    }
    if (categories != null) {
        GenericCategoryCollection(
            schema = schema,
            resource = resource,
            rows = categories,
            authoritativeRecordsKey = authoritativeRecordsKey,
            navigationContext = datasetContext.bindingValues,
            authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
            actionExecutor = actionExecutor,
            onActionSucceeded = onInlineActionSucceeded,
            onSelectRecord = onSelectRecord,
            onEditRecord = onEditRecord,
            onDeleteRecord = onDeleteRecord,
            onCommandRecord = onCommandRecord,
            onCommandFormRecord = onCommandFormRecord,
            reorder = reorder,
            pendingReorderOrder = pendingCollectionReorderOrder,
            pendingReorderRecoveryRequested = pendingCollectionReorderRecoveryRequested,
            onPendingReorderChanged = onPendingCollectionReorderChanged,
            pendingMutationStore = pendingMutationStore,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        return
    }
    val financialAccounts = remember(resource, records) {
        nativeFinancialAccountCollectionPresentations(resource, records)
    }
    if (financialAccounts != null) {
        GenericFinancialAccountCollection(
            schema = schema,
            resource = resource,
            rows = financialAccounts,
            navigationContext = datasetContext.bindingValues,
            authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
            onSelectRecord = onSelectRecord,
            onEditRecord = onEditRecord,
            onDeleteRecord = onDeleteRecord,
            onCommandRecord = onCommandRecord,
            onCommandFormRecord = onCommandFormRecord,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        return
    }
    val finance = remember(resource, records) {
        nativeFinanceCollectionPresentations(resource, records)
    }
    if (finance != null) {
        GenericFinanceCollection(
            resource = resource,
            rows = finance,
            onSelectRecord = onSelectRecord,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        return
    }
    val insights = remember(resource, records) { nativeDatasetInsights(resource, records) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactViewport = !datasetInsightsDefaultExpanded(maxWidth.value, maxHeight.value)
        val showDesktopOverview = LocalNextcloudWorkspaceCapabilities.current.isDesktop &&
            maxWidth >= 980.dp && records.isNotEmpty()
        val collectionContent: @Composable ColumnScope.() -> Unit = {
            if (!showDesktopOverview) {
                insights?.let {
                    DatasetInsightsDisclosure(
                        insights = it,
                        compact = compactViewport,
                        initiallyExpanded = !compactViewport,
                        stateKey = "collection:${resource.id}",
                    )
                }
            }
            GenericRecordList(
                resource = resource,
                records = records,
                onSelectRecord = onSelectRecord,
                modifier = Modifier.weight(1f),
                secondaryActions = { record ->
                    nativeRecordCardActions(
                        capabilities = nativeRecordActions(
                            schema = schema,
                            resource = resource,
                            record = record,
                            navigationContext = datasetContext.bindingValues,
                            authorityContext = datasetContext.nativeRecordAuthorityContext(schema),
                        ),
                        record = record,
                        onEditRecord = onEditRecord,
                        onDeleteRecord = onDeleteRecord,
                        onCommandRecord = onCommandRecord,
                        onCommandFormRecord = onCommandFormRecord,
                    )
                },
                reorder = reorder,
                actionExecutor = actionExecutor,
                onActionSucceeded = onInlineActionSucceeded,
                authoritativeRecordsKey = authoritativeRecordsKey,
                pendingReorderOrder = pendingCollectionReorderOrder,
                pendingReorderRecoveryRequested = pendingCollectionReorderRecoveryRequested,
                onPendingReorderChanged = onPendingCollectionReorderChanged,
                pendingMutationStore = pendingMutationStore,
                onLoadMore = onLoadMore,
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
            )
        }
        if (showDesktopOverview) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1f), content = collectionContent)
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                GenericDesktopCollectionOverview(
                    resource = resource,
                    records = records,
                    insights = insights,
                    canOpenItems = onSelectRecord != null,
                    modifier = Modifier.width(304.dp).fillMaxHeight(),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize(), content = collectionContent)
        }
    }
}

@Composable
private fun GenericDesktopCollectionOverview(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    insights: NativeDatasetInsights?,
    canOpenItems: Boolean,
    modifier: Modifier = Modifier,
) {
    val facets = remember(resource, records) { inferNativeDatasetFacets(resource, records) }
    val visibleFields = remember(resource) {
        resource.fields.filterNot { field -> field.id.equals("id", ignoreCase = true) }
    }
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(rememberScrollState())
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            Text(
                "Overview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${records.size} ${if (records.size == 1) "item" else "items"} in ${resource.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                GenericOverviewMetric(
                    label = "Items",
                    value = records.size.toString(),
                )
                insights?.let { summary ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GenericOverviewMetric(
                        label = summary.measure.label,
                        value = formatNativeMetric(summary.measure, summary.total),
                    )
                }
                visibleFields.count(FieldSpec::required).takeIf { it > 0 }?.let { requiredCount ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GenericOverviewMetric(
                        label = "Required details",
                        value = requiredCount.toString(),
                    )
                }
            }
        }

        facets.take(2).forEach { facet ->
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(
                    facet.field.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                facet.options.take(5).forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Text(
                                option.count.toString(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }

        if (facets.isEmpty() && visibleFields.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(
                    "Available details",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                visibleFields.take(5).forEach { field ->
                    Text(
                        field.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (canOpenItems) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Text(
                    "Select an item to open its full workspace and available actions.",
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun GenericOverviewMetric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private enum class NativeCategoryFilter(val label: String) {
    All("All"),
    Expenses("Expenses"),
    Income("Income"),
}


@Composable
private fun GenericCategoryCollection(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    rows: List<Pair<NativeRecord, NativeCategoryPresentation>>,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    navigationContext: Map<String, String>,
    authorityContext: NativeRecordAuthorityContext?,
    actionExecutor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onEditRecord: (NativeRecord, NativeRecordFormActionPlan) -> Unit,
    onDeleteRecord: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit,
    onCommandRecord: (NativeRecord, NativeRecordCommandActionPlan) -> Unit,
    onCommandFormRecord: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit,
    reorder: NativeCollectionReorderActionPlan?,
    pendingReorderOrder: List<String>?,
    pendingReorderRecoveryRequested: Boolean,
    onPendingReorderChanged: (NativeCollectionReorderActionPlan, List<String>?, Boolean) -> Unit,
    pendingMutationStore: NativePendingMutationStore?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable(resource.id) { mutableStateOf(NativeCategoryFilter.All) }
    val parentIds = remember(rows) {
        val knownIds = rows.map { (record, _) -> record.id }.toSet()
        rows.mapNotNull { (_, category) -> category.parentId?.takeIf(knownIds::contains) }.toSet()
    }
    // A collection reorder payload must describe the complete authoritative order. Filtered and
    // hierarchical category projections are intentionally excluded because their visible order is
    // only a subset or a tree traversal, not the server's declared flat collection order.
    val activeReorder = reorder.takeIf {
        parentIds.isEmpty() && filter == NativeCategoryFilter.All && pendingMutationStore != null
    }
    val authoritativeOrder = remember(authoritativeRecordsKey) { rows.map { (record, _) -> record.id } }
    val rowsById = remember(rows) { rows.associateBy { (record, _) -> record.id } }
    var orderedRecordIds by remember(reorder?.action?.id, resource.id) {
        mutableStateOf(authoritativeOrder)
    }
    var draggingRecordId by remember(reorder?.action?.id, resource.id) {
        mutableStateOf<String?>(null)
    }
    var dragOrigin by remember(reorder?.action?.id, resource.id) { mutableStateOf<Offset?>(null) }
    var dragPosition by remember(reorder?.action?.id, resource.id) { mutableStateOf<Offset?>(null) }
    var reorderExecuting by remember(reorder?.action?.id, resource.id) { mutableStateOf(false) }
    var reorderError by remember(reorder?.action?.id, resource.id) { mutableStateOf<String?>(null) }
    var reorderRecoveryAvailable by remember(reorder?.action?.id, resource.id) { mutableStateOf(false) }
    var reorderRequestInFlight by remember(reorder?.action?.id, resource.id) { mutableStateOf(false) }
    var durableRestoreChecked by remember(reorder?.action?.id, resource.id) {
        mutableStateOf(activeReorder == null)
    }
    val rowBounds = remember(reorder?.action?.id, resource.id) { mutableStateMapOf<String, Rect>() }
    var listBounds by remember(reorder?.action?.id, resource.id) { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val displayedRows = remember(rows, rowsById, orderedRecordIds, activeReorder) {
        if (activeReorder == null) rows else orderedRecordIds.mapNotNull(rowsById::get)
    }
    LaunchedEffect(activeReorder?.action?.id, resource.id, pendingMutationStore) {
        val plan = activeReorder
        val store = pendingMutationStore
        if (plan == null || store == null) {
            durableRestoreChecked = true
            return@LaunchedEffect
        }
        durableRestoreChecked = false
        reorderExecuting = true
        val pending = runCatching {
            store.load(nativePendingCollectionReorderKey(plan, resource.id))
        }.getOrElse { failure ->
            reorderError = failure.message ?: "The saved order recovery marker could not be read."
            reorderRecoveryAvailable = true
            durableRestoreChecked = true
            return@LaunchedEffect
        }
        if (pending == null) {
            onPendingReorderChanged(plan, null, false)
            reorderExecuting = false
            reorderRecoveryAvailable = false
        } else {
            val restored = decodeNativePendingCollectionReorder(pending)
            if (restored == null) {
                reorderError = "The saved order recovery marker is invalid."
                reorderRecoveryAvailable = true
            } else {
                onPendingReorderChanged(
                    plan,
                    restored.orderedRecordIds,
                    restored.recoveryRequested,
                )
            }
        }
        durableRestoreChecked = true
    }
    fun submitReorder(submittedOrder: List<String> = orderedRecordIds) {
        val plan = activeReorder ?: return
        val store = pendingMutationStore ?: return
        if (submittedOrder == authoritativeOrder) return
        val request = runCatching { plan.requestInOrder(submittedOrder) }.getOrElse { failure ->
            reorderError = failure.message ?: "The new order could not be submitted."
            orderedRecordIds = authoritativeOrder
            return
        }
        val stagedValues = encodeNativePendingCollectionReorder(submittedOrder, recoveryRequested = false)
        if (stagedValues == null) {
            reorderError = "The new order is too large to stage safely."
            orderedRecordIds = authoritativeOrder
            return
        }
        val pendingKey = nativePendingCollectionReorderKey(plan, resource.id)
        onPendingReorderChanged(plan, submittedOrder, false)
        reorderRequestInFlight = true
        reorderExecuting = true
        reorderError = null
        reorderRecoveryAvailable = false
        scope.launch {
            runCatchingUnlessCancelled { store.save(pendingKey, stagedValues) }.onFailure { failure ->
                reorderError = failure.message ?: "The new order could not be staged safely."
                onPendingReorderChanged(plan, null, false)
                orderedRecordIds = authoritativeOrder
                reorderExecuting = false
                reorderRequestInFlight = false
                return@launch
            }
            when (val result = actionExecutor.execute(request)) {
                is NativeActionExecutionResult.Success -> {
                    encodeNativePendingCollectionReorder(submittedOrder, recoveryRequested = true)
                        ?.let { values -> runCatchingUnlessCancelled { store.save(pendingKey, values) } }
                    onPendingReorderChanged(plan, submittedOrder, true)
                    onActionSucceeded?.invoke(plan.action)
                }
                is NativeActionExecutionResult.Failure -> {
                    reorderError = result.message
                    if (result.outcome.requiresMutationReconciliation()) {
                        encodeNativePendingCollectionReorder(submittedOrder, recoveryRequested = true)
                            ?.let { values -> runCatchingUnlessCancelled { store.save(pendingKey, values) } }
                        onPendingReorderChanged(plan, submittedOrder, true)
                        onActionSucceeded?.invoke(plan.action)
                    } else {
                        runCatchingUnlessCancelled { store.clear(pendingKey) }
                        onPendingReorderChanged(plan, null, false)
                        orderedRecordIds = authoritativeOrder
                        reorderExecuting = false
                        reorderRecoveryAvailable = false
                    }
                }
            }
            reorderRequestInFlight = false
        }
    }
    fun moveRecordBy(recordId: String, offset: Int) {
        val currentIndex = orderedRecordIds.indexOf(recordId)
        if (currentIndex < 0) return
        val targetIndex = (currentIndex + offset).coerceIn(0, orderedRecordIds.lastIndex)
        if (targetIndex == currentIndex) return
        val nextOrder = moveNativeCollectionRecordToIndex(
            orderedRecordIds = orderedRecordIds,
            recordId = recordId,
            targetIndex = targetIndex,
        )
        orderedRecordIds = nextOrder
        submitReorder(nextOrder)
    }
    LaunchedEffect(
        authoritativeRecordsKey,
        authoritativeOrder,
        reorder?.action?.id,
        pendingReorderOrder,
        pendingReorderRecoveryRequested,
        reorderRequestInFlight,
        durableRestoreChecked,
    ) {
        if (!durableRestoreChecked) return@LaunchedEffect
        val pendingOrder = pendingReorderOrder
        val validPendingOrder = validPendingNativeCollectionOrder(authoritativeOrder, pendingOrder)
        if (pendingOrder != null && validPendingOrder == null) {
            activeReorder?.let { plan ->
                pendingMutationStore?.let { store ->
                    runCatching { store.clear(nativePendingCollectionReorderKey(plan, resource.id)) }
                        .onFailure { failure ->
                            reorderError = failure.message ?: "The obsolete order marker could not be cleared."
                            reorderExecuting = true
                            return@LaunchedEffect
                        }
                }
                onPendingReorderChanged(plan, null, false)
            }
            orderedRecordIds = authoritativeOrder
            reorderExecuting = false
            reorderError = "The saved order no longer matches the authoritative collection."
            reorderRecoveryAvailable = false
        } else if (validPendingOrder != null && authoritativeOrder == validPendingOrder) {
            orderedRecordIds = authoritativeOrder
            activeReorder?.let { plan ->
                pendingMutationStore?.let { store ->
                    runCatching { store.clear(nativePendingCollectionReorderKey(plan, resource.id)) }
                        .onFailure { failure ->
                            reorderError = failure.message ?: "The confirmed order marker could not be cleared."
                            reorderExecuting = true
                            return@LaunchedEffect
                        }
                }
                onPendingReorderChanged(plan, null, false)
            }
            reorderExecuting = false
            reorderError = null
            reorderRecoveryAvailable = false
        } else if (
            validPendingOrder != null && !reorderRequestInFlight && !pendingReorderRecoveryRequested
        ) {
            orderedRecordIds = validPendingOrder
            reorderExecuting = true
            reorderRecoveryAvailable = false
            activeReorder?.let { plan ->
                val pendingKey = nativePendingCollectionReorderKey(plan, resource.id)
                val values = encodeNativePendingCollectionReorder(
                    validPendingOrder,
                    recoveryRequested = true,
                )
                if (values == null) {
                    reorderError = "The saved order could not be prepared for recovery."
                    reorderRecoveryAvailable = pendingMutationStore != null
                    return@LaunchedEffect
                }
                val store = pendingMutationStore
                if (store == null) {
                    onPendingReorderChanged(plan, null, false)
                    orderedRecordIds = authoritativeOrder
                    reorderExecuting = false
                    reorderError = "Order recovery is unavailable; the authoritative server order is shown."
                    reorderRecoveryAvailable = false
                    return@LaunchedEffect
                }
                runCatchingUnlessCancelled { store.save(pendingKey, values) }.onFailure { failure ->
                    reorderError = failure.message ?: "The order recovery marker could not be updated."
                    reorderRecoveryAvailable = true
                    return@LaunchedEffect
                }
                onPendingReorderChanged(plan, validPendingOrder, true)
                onActionSucceeded?.invoke(plan.action)
            }
        } else if (
            validPendingOrder != null && pendingReorderRecoveryRequested && !reorderRequestInFlight
        ) {
            orderedRecordIds = validPendingOrder
            reorderExecuting = true
            reorderError = "The submitted order is awaiting authoritative server confirmation."
            reorderRecoveryAvailable = true
        } else if (draggingRecordId == null && !reorderExecuting) {
            orderedRecordIds = authoritativeOrder
            reorderError = null
            reorderRecoveryAvailable = false
        }
    }
    fun retryPendingReorderRecovery() {
        val plan = activeReorder ?: return
        val callback = onActionSucceeded ?: return
        reorderRecoveryAvailable = false
        reorderExecuting = true
        reorderError = "Checking the authoritative server order again."
        callback(plan.action)
    }
    fun discardPendingReorderRecovery() {
        val plan = activeReorder ?: return
        val store = pendingMutationStore ?: return
        reorderRecoveryAvailable = false
        reorderExecuting = true
        scope.launch {
            runCatchingUnlessCancelled {
                store.clear(nativePendingCollectionReorderKey(plan, resource.id))
            }.onSuccess {
                onPendingReorderChanged(plan, null, false)
                orderedRecordIds = authoritativeOrder
                reorderExecuting = false
                reorderError = null
            }.onFailure { failure ->
                reorderExecuting = true
                reorderRecoveryAvailable = true
                reorderError = failure.message ?: "The saved order recovery marker could not be cleared."
            }
        }
    }
    fun moveDraggedRecord(position: Offset) {
        val recordId = draggingRecordId ?: return
        val visibleItemKeys = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { item -> item.key as? String }
            .toSet()
        orderedRecordIds = moveNativeCollectionRecordToVisibleTarget(
            orderedRecordIds = orderedRecordIds,
            recordId = recordId,
            rowBounds = rowBounds,
            pointerPosition = position,
            visibleItemKeys = visibleItemKeys,
        ) ?: return
    }
    var expandedIds by rememberSaveable(resource.id) { mutableStateOf(parentIds.toList()) }
    LaunchedEffect(parentIds) {
        expandedIds = expandedIds.filter(parentIds::contains)
    }
    val filteredRows = remember(displayedRows, filter) {
        displayedRows.filter { (_, category) ->
            when (filter) {
                NativeCategoryFilter.All -> true
                NativeCategoryFilter.Expenses -> category.kind == NativeCategoryKind.Expense
                NativeCategoryFilter.Income -> category.kind == NativeCategoryKind.Income
            }
        }
    }
    val visibleRows = remember(filteredRows, expandedIds, activeReorder) {
        nativeCategoryRowsForDisplay(
            rows = filteredRows,
            expandedIds = expandedIds.toSet(),
            preserveAuthoritativeOrder = activeReorder != null,
        )
    }
    val expenseCount = rows.count { (_, category) -> category.kind == NativeCategoryKind.Expense }
    val incomeCount = rows.count { (_, category) -> category.kind == NativeCategoryKind.Income }
    NativeCollectionAutoPager(
        listState,
        visibleRows.size,
        onLoadMore.takeIf { filter == NativeCategoryFilter.All },
        loadingMore,
        loadMoreError,
    )
    NextcloudVerticalDragAutoScroll(
        activeDragKey = draggingRecordId,
        position = dragPosition,
        dragOrigin = dragOrigin,
        viewport = listBounds,
        scrollState = listState,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NextcloudSegmentedControl(
                    options = NativeCategoryFilter.entries.map { option ->
                        val count = when (option) {
                            NativeCategoryFilter.All -> rows.size
                            NativeCategoryFilter.Expenses -> expenseCount
                            NativeCategoryFilter.Income -> incomeCount
                        }
                        NextcloudSegmentedOption(option.name, "${option.label} $count")
                    },
                    selectedId = filter.name,
                    onSelected = { id -> NativeCategoryFilter.entries.firstOrNull { it.name == id }?.let { filter = it } },
                    modifier = Modifier.weight(1f), accessibilityLabel = "Category type", role = Role.RadioButton,
                )
                if (parentIds.isNotEmpty()) {
                    TextButton(onClick = { expandedIds = if (expandedIds.isEmpty()) parentIds.toList() else emptyList() }) {
                        Text(if (expandedIds.isEmpty()) "Expand all" else "Collapse all")
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).onGloballyPositioned { coordinates ->
                listBounds = coordinates.boundsInWindow()
            },
            contentPadding = PaddingValues(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Medium,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            reorderError?.let { message ->
                item(key = "category-reorder-error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (reorderRecoveryAvailable) {
                            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                                TextButton(
                                    onClick = ::retryPendingReorderRecovery,
                                    enabled = onActionSucceeded != null,
                                ) {
                                    Text("Check again")
                                }
                                TextButton(onClick = ::discardPendingReorderRecovery) {
                                    Text("Use server order")
                                }
                            }
                        }
                    }
                }
            }
            items(visibleRows, key = { row -> row.record.id }) { row ->
                val recordPresentation = nativeRecordPresentation(resource, row.record)
                val iconKey = recordPresentation.iconKey
                    ?.takeIf { key -> NextcloudIcons.semantic(key) != null }
                    ?: "category"
                val actions = remember(schema, resource, row.record, navigationContext, authorityContext) {
                    nativeRecordActions(
                        schema = schema,
                        resource = resource,
                        record = row.record,
                        navigationContext = navigationContext,
                        authorityContext = authorityContext,
                    )
                }
                val reorderIndex = orderedRecordIds.indexOf(row.record.id)
                val secondaryActions = nativeRecordCardActions(
                    capabilities = actions,
                    record = row.record,
                    onEditRecord = onEditRecord,
                    onDeleteRecord = onDeleteRecord,
                    onCommandRecord = onCommandRecord,
                    onCommandFormRecord = onCommandFormRecord,
                ) + if (activeReorder != null && !reorderExecuting && reorderIndex >= 0) {
                    listOf(
                        NextcloudCardAction(
                            label = "Move earlier",
                            semanticId = "${activeReorder.action.id}.move-earlier",
                            enabled = reorderIndex > 0,
                            onClick = { moveRecordBy(row.record.id, -1) },
                        ),
                        NextcloudCardAction(
                            label = "Move later",
                            semanticId = "${activeReorder.action.id}.move-later",
                            enabled = reorderIndex < orderedRecordIds.lastIndex,
                            onClick = { moveRecordBy(row.record.id, 1) },
                        ),
                    )
                } else {
                    emptyList()
                }
                var actionsExpanded by rememberSaveable(row.record.id) { mutableStateOf(false) }
                val dragging = draggingRecordId == row.record.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            rowBounds[row.record.id] = coordinates.boundsInWindow()
                        }
                        .graphicsLayer { alpha = if (dragging) 0.56f else 1f }
                        .nextcloudCardInteractions(
                        onOpen = onSelectRecord?.let { callback -> { callback(row.record) } },
                        onShowActions = if (secondaryActions.isNotEmpty()) {
                            { actionsExpanded = true }
                        } else {
                            null
                        },
                        openLabel = "Open ${row.presentation.name}",
                        actionsLabel = "Show actions for ${row.presentation.name}",
                    ),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (row.depth > 0) Box(Modifier.width((row.depth * 18).dp))
                        if (activeReorder != null && !reorderExecuting) {
                            NextcloudBoardDragHandle(
                                itemLabel = row.presentation.name,
                                dragActive = dragging,
                                onDragStart = { position ->
                                    draggingRecordId = row.record.id
                                    dragOrigin = position
                                    dragPosition = position
                                    reorderError = null
                                },
                                onDrag = { delta ->
                                    val position = (dragPosition ?: return@NextcloudBoardDragHandle) + delta
                                    dragPosition = position
                                    orderedRecordIds = moveNativeCollectionRecordAcrossAdjacentMidpoint(
                                        orderedRecordIds = orderedRecordIds,
                                        recordId = row.record.id,
                                        pointerY = position.y,
                                        movementY = delta.y,
                                        rowBounds = rowBounds,
                                    )
                                },
                                onDragEnd = {
                                    dragPosition?.let(::moveDraggedRecord)
                                    draggingRecordId = null
                                    dragOrigin = null
                                    dragPosition = null
                                    submitReorder()
                                },
                                onDragCancel = {
                                    draggingRecordId = null
                                    dragOrigin = null
                                    dragPosition = null
                                    orderedRecordIds = authoritativeOrder
                                },
                            )
                        } else if (row.hasChildren) {
                            Box(
                                modifier = Modifier.size(40.dp).clickable {
                                    expandedIds = if (row.record.id in expandedIds) {
                                        expandedIds - row.record.id
                                    } else {
                                        expandedIds + row.record.id
                                    }
                                }.semantics {
                                    contentDescription = if (row.record.id in expandedIds) {
                                        "Collapse ${row.presentation.name}"
                                    } else {
                                        "Expand ${row.presentation.name}"
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (row.record.id in expandedIds) NextcloudIcons.ExpandMore else NextcloudIcons.ChevronRight,
                                    contentDescription = null,
                                )
                            }
                        } else {
                            Box(Modifier.size(40.dp))
                        }
                        GenericResourceIcon(
                            resource,
                            iconKey,
                            recordPresentation.colorArgb,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                row.presentation.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val metadata = buildList {
                                add(
                                    when (row.presentation.kind) {
                                        NativeCategoryKind.Expense -> "Expense"
                                        NativeCategoryKind.Income -> "Income"
                                        NativeCategoryKind.Other -> "Category"
                                    },
                                )
                                row.presentation.transactionCount?.let { count ->
                                    add("$count ${if (count == 1) "transaction" else "transactions"}")
                                }
                                if (row.presentation.shared) {
                                    val owner = row.presentation.sharedBy?.let { " by $it" }.orEmpty()
                                    add(
                                        if (row.presentation.writable) {
                                            "Shared$owner, editable"
                                        } else {
                                            "Shared$owner, read only"
                                        },
                                    )
                                }
                            }.joinToString(" · ")
                            Text(
                                metadata,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (row.presentation.mutedFromReports) {
                            Text(
                                "Hidden",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (secondaryActions.isNotEmpty()) {
                            NextcloudCardOverflow(
                                itemLabel = row.presentation.name,
                                actions = secondaryActions,
                                expanded = actionsExpanded,
                                onExpandedChange = { actionsExpanded = it },
                            )
                        } else if (onSelectRecord != null) {
                            Icon(
                                NextcloudIcons.ChevronRight,
                                contentDescription = "Open ${row.presentation.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            NativeCollectionPagingFooter(loadingMore, loadMoreError, onLoadMore)
        }
    }
}

@Composable
private fun NativeCollectionReorderRecoveryMessage(
    message: String,
    recoveryAvailable: Boolean,
    retryRecovery: () -> Unit,
    discardRecovery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        if (recoveryAvailable) {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                TextButton(onClick = retryRecovery) { Text("Check again") }
                TextButton(onClick = discardRecovery) { Text("Use server order") }
            }
        }
    }
}

@Composable
private fun GenericFinancialAccountCollection(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    rows: List<Pair<NativeRecord, NativeFinancialAccountPresentation>>,
    navigationContext: Map<String, String>,
    authorityContext: NativeRecordAuthorityContext?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onEditRecord: (NativeRecord, NativeRecordFormActionPlan) -> Unit,
    onDeleteRecord: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit,
    onCommandRecord: (NativeRecord, NativeRecordCommandActionPlan) -> Unit,
    onCommandFormRecord: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val contextualCurrency = LocalNativeFinanceCurrency.current
    val accounts = remember(rows) { rows.map { (_, account) -> account } }
    val currency = remember(accounts, contextualCurrency) {
        accounts.mapNotNull(NativeFinancialAccountPresentation::baseCurrency).distinct().singleOrNull()
            ?: accounts.mapNotNull(NativeFinancialAccountPresentation::currency).distinct().singleOrNull()
            ?: contextualCurrency
    }
    fun convertedBalance(account: NativeFinancialAccountPresentation): Double? =
        account.convertedBalance
            ?: account.balance.takeIf {
                currency == null || account.currency == null || account.currency == currency
            }
    val assets = remember(rows) { rows.filter { (_, account) -> account.kind == NativeFinancialAccountKind.Asset } }
    val liabilities = remember(rows) {
        rows.filter { (_, account) -> account.kind == NativeFinancialAccountKind.Liability }
    }
    val other = remember(rows) { rows.filter { (_, account) -> account.kind == NativeFinancialAccountKind.Other } }
    val includedAccounts = accounts.filterNot(NativeFinancialAccountPresentation::excludedFromReports)
    val assetTotal = includedAccounts.filter { it.kind == NativeFinancialAccountKind.Asset }
        .mapNotNull(::convertedBalance).sum()
    val liabilityBalance = includedAccounts.filter { it.kind == NativeFinancialAccountKind.Liability }
        .mapNotNull(::convertedBalance).sum()
    val liabilityTotal = nativeFinanceLiabilityTotal(liabilityBalance)
    val netWorth = assetTotal + liabilityBalance
    val unconvertedCount = accounts.count { convertedBalance(it) == null }
    val totalsQualifier = if (onLoadMore != null || loadingMore || loadMoreError != null) " (loaded)" else ""
    val listState = rememberLazyListState()
    NativeCollectionAutoPager(listState, rows.size, onLoadMore, loadingMore, loadMoreError)

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Medium,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth < 600.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            ) {
                                FinancialAccountMetric(
                                    label = "Assets$totalsQualifier",
                                    value = formatNativeFinanceAmount(assetTotal, currency),
                                    modifier = Modifier.weight(1f),
                                )
                                FinancialAccountMetric(
                                    label = "Liabilities$totalsQualifier",
                                    value = formatNativeFinanceAmount(liabilityTotal, currency),
                                    negative = liabilityTotal > 0.0,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            FinancialAccountMetric(
                                label = "Net worth$totalsQualifier",
                                value = formatNativeFinanceAmount(netWorth, currency),
                                negative = netWorth < 0.0,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            FinancialAccountMetric(
                                label = "Assets$totalsQualifier",
                                value = formatNativeFinanceAmount(assetTotal, currency),
                                modifier = Modifier.weight(1f),
                            )
                            FinancialAccountMetric(
                                label = "Liabilities$totalsQualifier",
                                value = formatNativeFinanceAmount(liabilityTotal, currency),
                                negative = liabilityTotal > 0.0,
                                modifier = Modifier.weight(1f),
                            )
                            FinancialAccountMetric(
                                label = "Net worth$totalsQualifier",
                                value = formatNativeFinanceAmount(netWorth, currency),
                                negative = netWorth < 0.0,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (unconvertedCount > 0) {
                    Text(
                        "$unconvertedCount ${if (unconvertedCount == 1) "account is" else "accounts are"} excluded from totals because no exchange rate is available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Medium,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            fun section(
                key: String,
                label: String,
                sectionRows: List<Pair<NativeRecord, NativeFinancialAccountPresentation>>,
            ) {
                if (sectionRows.isEmpty()) return
                item(key = "$key-header") {
                    val subtotal = sectionRows.filterNot { (_, account) -> account.excludedFromReports }
                        .mapNotNull { (_, account) -> convertedBalance(account) }
                        .sum()
                        .let { amount -> if (key == "liabilities") nativeFinanceLiabilityTotal(amount) else amount }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.XSmall),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            formatNativeFinanceAmount(subtotal, currency),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(sectionRows, key = { (record, _) -> record.id }) { (record, account) ->
                    val actions = remember(schema, resource, record, navigationContext, authorityContext) {
                        nativeRecordActions(schema, resource, record, navigationContext, authorityContext)
                    }
                    FinancialAccountCard(
                        resource = resource,
                        account = account,
                        onClick = onSelectRecord?.let { callback -> { callback(record) } },
                        secondaryActions = nativeRecordCardActions(
                            capabilities = actions,
                            record = record,
                            onEditRecord = onEditRecord,
                            onDeleteRecord = onDeleteRecord,
                            onCommandRecord = onCommandRecord,
                            onCommandFormRecord = onCommandFormRecord,
                        ),
                    )
                }
            }
            section("assets", "Assets", assets)
            section("liabilities", "Liabilities", liabilities)
            section("other", "Other accounts", other)
            NativeCollectionPagingFooter(loadingMore, loadMoreError, onLoadMore)
        }
    }
}

internal fun nativeFinanceLiabilityTotal(signedLiabilityBalance: Double): Double =
    (-signedLiabilityBalance).coerceAtLeast(0.0)

@Composable
private fun FinancialAccountMetric(
    label: String,
    value: String,
    negative: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FinancialAccountCard(
    resource: ResourceSpec,
    account: NativeFinancialAccountPresentation,
    onClick: (() -> Unit)?,
    secondaryActions: List<NextcloudCardAction>,
) {
    var actionsExpanded by rememberSaveable(account.name) { mutableStateOf(false) }
    val liability = account.kind == NativeFinancialAccountKind.Liability
    val displayedBalance = if (liability) kotlin.math.abs(account.balance) else account.balance
    val balanceLabel = when {
        liability && account.balance < 0.0 -> "Owed"
        liability && account.balance > 0.0 -> "Credit"
        else -> "Balance"
    }
    Card(
        modifier = Modifier.fillMaxWidth().nextcloudCardInteractions(
            onOpen = onClick,
            onShowActions = if (secondaryActions.isNotEmpty()) ({ actionsExpanded = true }) else null,
            openLabel = "Open ${account.name}",
            actionsLabel = "Show actions for ${account.name}",
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GenericResourceIcon(resource, account.type?.replace('_', '-'))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        account.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metadata = listOfNotNull(
                        account.type?.replace('_', ' ')?.replaceFirstChar(Char::uppercase),
                        account.institution,
                    ).distinct().joinToString(" · ")
                    if (metadata.isNotBlank()) {
                        Text(
                            metadata,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        balanceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatNativeFinanceAmount(displayedBalance, account.currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if ((liability && account.balance < 0.0) || (!liability && account.balance < 0.0)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color(0xFF3F8F50)
                        },
                        maxLines = 1,
                    )
                }
                if (secondaryActions.isNotEmpty()) {
                    NextcloudCardOverflow(
                        itemLabel = account.name,
                        actions = secondaryActions,
                        expanded = actionsExpanded,
                        onExpandedChange = { actionsExpanded = it },
                    )
                }
            }
            val footer = listOfNotNull(
                account.accountNumber,
                account.lastReconciled?.let { "Reconciled $it" },
            ).joinToString(" · ")
            if (footer.isNotBlank() || account.excludedFromReports || account.convertedBalance != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        footer,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val trailing = when {
                        account.excludedFromReports -> "Excluded"
                        account.convertedBalance != null -> "≈ ${formatNativeFinanceAmount(account.convertedBalance, account.baseCurrency)}"
                        else -> null
                    }
                    trailing?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenericFinanceCollection(
    resource: ResourceSpec,
    rows: List<Pair<NativeRecord, NativeFinancePresentation?>>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val contextualCurrency = LocalNativeFinanceCurrency.current
    var filter by rememberSaveable(resource.id) { mutableStateOf(NativeFinanceLedgerFilter.All) }
    var categoryFilter by rememberSaveable(resource.id) { mutableStateOf<String?>(null) }
    var accountFilter by rememberSaveable(resource.id) { mutableStateOf<String?>(null) }
    var filtersExpanded by rememberSaveable(resource.id) { mutableStateOf(false) }
    val presentations = remember(rows) { rows.mapNotNull { (_, transaction) -> transaction } }
    val categories = remember(presentations) {
        presentations.mapNotNull(NativeFinancePresentation::category).distinct().sorted().take(12)
    }
    val accounts = remember(presentations) {
        presentations.mapNotNull(NativeFinancePresentation::paymentMethod).distinct().sorted().take(12)
    }
    val presentedRows = remember(rows, filter, categoryFilter, accountFilter) {
        rows.filter { (_, transaction) ->
            val directionMatches = when (filter) {
                NativeFinanceLedgerFilter.All -> true
                NativeFinanceLedgerFilter.Income -> transaction?.direction == NativeFinanceDirection.Credit
                NativeFinanceLedgerFilter.Expenses -> transaction?.direction == NativeFinanceDirection.Debit
            }
            directionMatches &&
                (categoryFilter == null || transaction?.category == categoryFilter) &&
                (accountFilter == null || transaction?.paymentMethod == accountFilter)
        }
    }
    val loadedCurrencies = remember(presentations) {
        presentations.mapNotNull(NativeFinancePresentation::currency).distinct()
    }
    val currency = remember(loadedCurrencies, contextualCurrency) {
        loadedCurrencies.singleOrNull() ?: contextualCurrency.takeIf { loadedCurrencies.isEmpty() }
    }
    val netFlow = remember(presentations, loadedCurrencies) {
        presentations.sumOf(NativeFinancePresentation::amount).takeIf { loadedCurrencies.size <= 1 }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${presentations.size} loaded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            netFlow?.let { "Loaded net ${formatNativeFinanceAmount(it, currency)}" }
                                ?: "Multiple currencies",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                netFlow == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                netFlow < 0 -> MaterialTheme.colorScheme.error
                                else -> Color(0xFF3F8F50)
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        val representedDirections = presentations.mapTo(hashSetOf(), NativeFinancePresentation::direction)
                        NativeFinanceLedgerFilter.entries.filter { option ->
                            option == NativeFinanceLedgerFilter.All ||
                                option == NativeFinanceLedgerFilter.Income && NativeFinanceDirection.Credit in representedDirections ||
                                option == NativeFinanceLedgerFilter.Expenses && NativeFinanceDirection.Debit in representedDirections
                        }.forEach { option ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(option.label) },
                            )
                        }
                        if (categories.size > 1 || accounts.size > 1) {
                            TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                                val activeCount = listOfNotNull(categoryFilter, accountFilter).size
                                Text(if (activeCount == 0) "Filters" else "Filters ($activeCount)")
                            }
                        }
                    }
                    if (filtersExpanded && (categories.size > 1 || accounts.size > 1)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            categories.forEach { category ->
                                FilterChip(
                                    selected = categoryFilter == category,
                                    onClick = { categoryFilter = category.takeUnless { it == categoryFilter } },
                                    label = { Text(category, maxLines = 1) },
                                )
                            }
                            accounts.forEach { account ->
                                FilterChip(
                                    selected = accountFilter == account,
                                    onClick = { accountFilter = account.takeUnless { it == accountFilter } },
                                    label = { Text(account, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            }
            val listState = rememberLazyListState()
            val localFiltersActive = filter != NativeFinanceLedgerFilter.All ||
                categoryFilter != null || accountFilter != null
            NativeCollectionAutoPager(
                listState,
                presentedRows.size,
                onLoadMore.takeUnless { localFiltersActive },
                loadingMore,
                loadMoreError,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = NextcloudSpacing.Large,
                    top = NextcloudSpacing.Medium,
                    end = NextcloudSpacing.Large,
                    bottom = NextcloudSpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                items(presentedRows, key = { (record, _) -> record.id }) { (record, transaction) ->
                    if (transaction == null) {
                        GenericCollectionCard(resource, record, onSelectRecord)
                        return@items
                    }
                    val interaction = onSelectRecord
                        ?.let { callback -> Modifier.clickable { callback(record) } }
                        ?: Modifier
                    Card(
                        modifier = interaction.fillMaxWidth().heightIn(min = 92.dp),
                        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val presentation = nativeRecordPresentation(resource, record)
                                GenericResourceIcon(
                                    resource,
                                    presentation.iconKey,
                                    presentation.colorArgb,
                                )
                                Text(
                                    transaction.title,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatNativeFinanceLedgerAmount(transaction, transaction.currency ?: currency),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (transaction.amount < 0) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                )
                            }
                            val split = financeSplitLabel(transaction)
                            if (transaction.participant != null || split != null) {
                                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                                    transaction.participant?.let { payer ->
                                        FinanceMetadataLine("Paid by", payer)
                                    }
                                    split?.let { value ->
                                        FinanceMetadataLine("Split", value)
                                    }
                                }
                            }
                            val context = listOfNotNull(transaction.category, transaction.paymentMethod)
                                .distinct().joinToString(" · ")
                            if (context.isNotBlank() || transaction.date != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                ) {
                                    Text(
                                        context,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    transaction.date?.let { date ->
                                        Text(
                                            date,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                NativeCollectionPagingFooter(loadingMore, loadMoreError, onLoadMore)
            }
        }
    }
}

private enum class NativeFinanceLedgerFilter(val label: String) {
    All("All"),
    Income("Income"),
    Expenses("Expenses"),
}

private fun formatNativeFinanceLedgerAmount(
    transaction: NativeFinancePresentation,
    currency: String?,
): String {
    val formatted = formatNativeFinanceAmount(transaction.amount, currency)
    return if (transaction.direction == NativeFinanceDirection.Credit && transaction.amount > 0.0) {
        "+$formatted"
    } else formatted
}

@Composable
private fun FinanceMetadataLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Text(
            "$label:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun financeSplitLabel(transaction: NativeFinancePresentation): String? =
    transaction.splitParticipants
        .takeIf(List<String>::isNotEmpty)
        ?.joinToString(", ")

@Composable
private fun GenericFinanceDetailHeader(
    resource: ResourceSpec,
    transaction: NativeFinancePresentation,
) {
    val contextualCurrency = LocalNativeFinanceCurrency.current
    val desktop = LocalNextcloudWorkspaceCapabilities.current.isDesktop
    val amount = formatNativeFinanceLedgerAmount(
        transaction,
        transaction.currency ?: contextualCurrency,
    )
    val amountColor = if (transaction.amount < 0) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            if (desktop) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GenericResourceIcon(resource, large = true)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            transaction.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        transaction.date?.let { date ->
                            Text(
                                date,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        amount,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GenericResourceIcon(resource, large = true)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            transaction.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        transaction.date?.let { date ->
                            Text(
                                date,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    amount,
                    modifier = Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = amountColor,
                )
            }
            transaction.participant?.let { payer ->
                FinanceMetadataLine("Paid by", payer)
            }
            financeSplitLabel(transaction)?.let { split ->
                FinanceMetadataLine("Split", split)
            }
            listOfNotNull(transaction.category, transaction.paymentMethod)
                .distinct()
                .joinToString(" · ")
                .takeIf(String::isNotBlank)
                ?.let { metadata ->
                                    Text(
                                        metadata,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
        }
    }
}

/**
 * Compose effect keys use structural equality, while an authoritative refresh can legitimately
 * return records equal to the previous snapshot. This key treats a newly allocated record list as
 * a refresh even when its contents are unchanged.
 */
@Composable
private fun GenericTaskCollection(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    rows: List<Pair<NativeRecord, NativeGroupwarePresentation>>,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    navigationContext: Map<String, String>,
    authorityContext: NativeRecordAuthorityContext?,
    actionExecutor: NativeActionExecutor,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    onEditRecord: (NativeRecord, NativeRecordFormActionPlan) -> Unit,
    onDeleteRecord: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit,
    onCommandRecord: (NativeRecord, NativeRecordCommandActionPlan) -> Unit,
    onCommandFormRecord: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit,
    reorder: NativeCollectionReorderActionPlan?,
    pendingReorderOrder: List<String>?,
    pendingReorderRecoveryRequested: Boolean,
    onPendingReorderChanged: (NativeCollectionReorderActionPlan, List<String>?, Boolean) -> Unit,
    pendingMutationStore: NativePendingMutationStore?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val scope = rememberCoroutineScope()
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    val listState = rememberLazyListState()
    val authoritativeOrder = remember(rows) { rows.map { (record, _) -> record.id } }
    val rowsById = remember(rows) { rows.associateBy { (record, _) -> record.id } }
    val activeReorder = reorder.takeIf { pendingMutationStore != null }
    var draggingRecordId by remember(reorder?.action?.id, resource.id) {
        mutableStateOf<String?>(null)
    }
    var dragOrigin by remember(reorder?.action?.id, resource.id) { mutableStateOf<Offset?>(null) }
    var dragPosition by remember(reorder?.action?.id, resource.id) { mutableStateOf<Offset?>(null) }
    val rowBounds = remember(reorder?.action?.id, resource.id) { mutableStateMapOf<String, Rect>() }
    var listBounds by remember(reorder?.action?.id, resource.id) { mutableStateOf<Rect?>(null) }
    val reorderState = rememberNativeDurableCollectionReorderState(
        plan = activeReorder,
        resourceId = resource.id,
        authoritativeOrder = authoritativeOrder,
        authoritativeRecordsKey = authoritativeRecordsKey,
        draggingRecordId = draggingRecordId,
        pendingOrder = pendingReorderOrder,
        pendingRecoveryRequested = pendingReorderRecoveryRequested,
        actionExecutor = actionExecutor,
        pendingMutationStore = pendingMutationStore,
        onPendingChanged = onPendingReorderChanged,
        onActionSucceeded = onActionSucceeded,
    )
    val displayedRows = remember(rowsById, reorderState.orderedRecordIds, activeReorder) {
        if (activeReorder == null) rows else reorderState.orderedRecordIds.mapNotNull(rowsById::get)
    }
    val currentAuthoritativeRecordsKey by rememberUpdatedState(authoritativeRecordsKey)
    val completionOverrides = remember(schema, resource.id) {
        mutableStateMapOf<String, NativeCompletionOverride>()
    }
    val completionInProgress = remember(schema, resource.id) { mutableStateMapOf<String, Boolean>() }
    val completionReconciliations = remember(schema, resource.id) {
        mutableStateMapOf<String, NativeAuthoritativeRecordsKey>()
    }
    val completionErrors = remember(schema, resource.id) { mutableStateMapOf<String, String>() }
    LaunchedEffect(authoritativeRecordsKey) {
        completionOverrides.reconcileNativeCompletionOverrides(authoritativeRecordsKey)
        completionReconciliations
            .reconcileNativeCompletionFailures(authoritativeRecordsKey)
            .forEach(completionErrors::remove)
    }
    fun moveDraggedRecord(position: Offset) {
        val recordId = draggingRecordId ?: return
        val visibleItemKeys = listState.layoutInfo.visibleItemsInfo
            .mapNotNull { item -> item.key as? String }
            .toSet()
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
        itemCount = displayedRows.size,
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
        modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            listBounds = coordinates.boundsInWindow()
        },
        state = listState,
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Large,
        ),
        verticalArrangement = Arrangement.spacedBy(if (dense) 1.dp else NextcloudSpacing.Small),
    ) {
        reorderState.error?.let { message ->
            item(key = "task-reorder-error") {
                NativeCollectionReorderRecoveryMessage(
                    message = message,
                    recoveryAvailable = reorderState.recoveryAvailable,
                    retryRecovery = reorderState.retryRecovery,
                    discardRecovery = reorderState.discardRecovery,
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                )
            }
        }
        itemsIndexed(displayedRows, key = { _, (record, _) -> record.id }) { index, (record, task) ->
            if (dense && index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            val actions = remember(schema, resource, record, navigationContext, authorityContext) {
                nativeRecordActions(
                    schema = schema,
                    resource = resource,
                    record = record,
                    navigationContext = navigationContext,
                    authorityContext = authorityContext,
                )
            }
            val completion = actions.completion
            val authoritativeCompleted = completion?.currentlyCompleted ?: task.completed
            val completed = effectiveNativeCompletion(
                override = completionOverrides[record.id],
                authoritativeRecordsKey = authoritativeRecordsKey,
                authoritativeCompleted = authoritativeCompleted,
            )
            val completing = completionInProgress[record.id] == true
            val reconciling = completionReconciliations.isNativeCompletionReconciling(
                recordId = record.id,
                authoritativeRecordsKey = authoritativeRecordsKey,
            )
            val secondaryActions = nativeRecordCardActions(
                capabilities = actions,
                record = record,
                onEditRecord = onEditRecord,
                onDeleteRecord = onDeleteRecord,
                onCommandRecord = onCommandRecord,
                onCommandFormRecord = onCommandFormRecord,
            )
            var actionsExpanded by rememberSaveable(record.id) { mutableStateOf(false) }
            val dragging = draggingRecordId == record.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        rowBounds[record.id] = coordinates.boundsInWindow()
                    }
                    .graphicsLayer { alpha = if (dragging) 0.56f else 1f }
                    .nextcloudCardInteractions(
                    onOpen = onSelectRecord?.let { callback -> { callback(record) } },
                    onShowActions = if (secondaryActions.isNotEmpty()) {
                        { actionsExpanded = true }
                    } else {
                        null
                    },
                    openLabel = "Open ${task.title}",
                    actionsLabel = "Show actions for ${task.title}",
                ),
                color = if (dense) MaterialTheme.colorScheme.background else NextcloudTheme.colors.appTile,
                shape = RoundedCornerShape(if (dense) 0.dp else NextcloudRadii.Card),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = if (dense) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                        vertical = if (dense) NextcloudSpacing.Small else NextcloudSpacing.Large,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(
                        if (dense) NextcloudSpacing.Small else NextcloudSpacing.Medium,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    activeReorder?.takeUnless { reorderState.executing }?.let {
                        NextcloudBoardDragHandle(
                            itemLabel = task.title,
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
                    Surface(
                        color = if (completed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            NextcloudTheme.colors.appIconContainer
                        },
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        if (completion != null) {
                            Checkbox(
                                checked = completed,
                                enabled = !completing && !reconciling,
                                modifier = Modifier.semantics {
                                    contentDescription = "Toggle completion for ${task.title}"
                                },
                                onCheckedChange = { requested ->
                                    completionErrors.remove(record.id)
                                    completionInProgress[record.id] = true
                                    scope.launch {
                                        when (
                                            val result = actionExecutor.execute(
                                                completion.request(completed = requested),
                                            )
                                        ) {
                                            is NativeActionExecutionResult.Success -> {
                                                completionReconciliations.remove(record.id)
                                                completionOverrides[record.id] = NativeCompletionOverride(
                                                    completed = requested,
                                                    sourceRecordsKey = authoritativeRecordsKey,
                                                )
                                                onActionSucceeded?.invoke(completion.action)
                                            }
                                            is NativeActionExecutionResult.Failure -> {
                                                completionErrors[record.id] = result.message
                                                val refreshRequired =
                                                    completionReconciliations.recordNativeCompletionFailure(
                                                        recordId = record.id,
                                                        authoritativeRecordsKey = currentAuthoritativeRecordsKey,
                                                        outcome = result.outcome,
                                                    )
                                                if (refreshRequired) {
                                                    onActionSucceeded?.invoke(completion.action)
                                                }
                                            }
                                        }
                                        completionInProgress.remove(record.id)
                                    }
                                },
                            )
                        } else {
                            Icon(
                                imageVector = if (completed) {
                                    NextcloudIcons.CheckCircle
                                } else {
                                    NextcloudIcons.FormatChecklist
                                },
                                contentDescription = if (completed) "Completed" else "Open item",
                                modifier = Modifier.padding(NextcloudSpacing.Small).size(24.dp),
                                tint = if (completed) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    NextcloudTheme.colors.appIcon
                                },
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        listOfNotNull(
                            task.due?.let { "Due ${it.compactSemanticDateTime()}" },
                            task.assignee?.let { "Assigned to $it" },
                            task.effortPoints?.let { "$it ${if (it == 1) "point" else "points"}" },
                            task.recurrenceRule?.taskRecurrenceLabel(),
                            task.status?.takeUnless { status ->
                                status.equals("completed", ignoreCase = true) && completed
                            },
                        ).distinct().joinToString(" · ").takeIf(String::isNotBlank)?.let { metadata ->
                            Text(
                                metadata,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        completionErrors[record.id]?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (reconciling) {
                            Text(
                                "Refreshing to verify the completion result before another change.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (reorderState.executing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (secondaryActions.isNotEmpty()) {
                        NextcloudCardOverflow(
                            itemLabel = task.title,
                            actions = secondaryActions,
                            expanded = actionsExpanded,
                            onExpandedChange = { actionsExpanded = it },
                        )
                    } else if (onSelectRecord != null) {
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open ${task.title}",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        NativeCollectionPagingFooter(
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
            onRetry = onLoadMore,
        )
    }
}

internal fun nativeRecordCardActions(
    capabilities: NativeRecordActionCapabilities,
    record: NativeRecord,
    onEditRecord: (NativeRecord, NativeRecordFormActionPlan) -> Unit,
    onDeleteRecord: (NativeRecord, NativeRecordDeleteActionPlan) -> Unit,
    onCommandRecord: (NativeRecord, NativeRecordCommandActionPlan) -> Unit,
    onCommandFormRecord: (NativeRecord, NativeRecordCommandFormActionPlan) -> Unit = { _, _ -> },
): List<NextcloudCardAction> = buildList {
    capabilities.edit?.let { plan ->
        add(
            NextcloudCardAction(
                label = "Edit",
                semanticId = plan.action.id,
                onClick = { onEditRecord(record, plan) },
            ),
        )
    }
    capabilities.commands.forEach { plan ->
        val ui = nativeRecordCommandUi(plan.effect, record.id, plan.action.label)
        add(
            NextcloudCardAction(
                label = ui.label,
                semanticId = plan.action.id,
                destructive = ui.destructive,
                onClick = { onCommandRecord(record, plan) },
            ),
        )
    }
    capabilities.commandForms.forEach { plan ->
        add(
            NextcloudCardAction(
                label = plan.action.label,
                semanticId = plan.action.id,
                destructive = plan.action.risk == ActionRisk.destructive,
                onClick = { onCommandFormRecord(record, plan) },
            ),
        )
    }
    capabilities.delete?.let { plan ->
        add(
            NextcloudCardAction(
                label = "Delete",
                semanticId = plan.action.id,
                destructive = true,
                onClick = { onDeleteRecord(record, plan) },
            ),
        )
    }
}

internal data class NativeRecordCommandUi(
    val label: String,
    val destructive: Boolean,
    val confirmationTitle: String? = null,
    val confirmationMessage: String? = null,
)

internal fun NativeActionFailureOutcome.requiresMutationReconciliation(): Boolean =
    this == NativeActionFailureOutcome.Unknown

internal fun NativeActionFailureOutcome.requiresCommandReconciliation(): Boolean =
    requiresMutationReconciliation()

internal fun NativeActionFailureOutcome.allowsGenericFormRetry(): Boolean =
    !requiresMutationReconciliation()

internal fun NativeActionFailureOutcome.allowsGenericDeleteRetry(): Boolean =
    !requiresMutationReconciliation()

internal fun nativeRecordCommandUi(
    effect: ActionEffect,
    itemLabel: String,
    actionLabel: String? = null,
): NativeRecordCommandUi = when (effect) {
    ActionEffect.archive -> NativeRecordCommandUi(label = "Archive", destructive = false)
    ActionEffect.unarchive -> NativeRecordCommandUi(label = "Unarchive", destructive = false)
    ActionEffect.restore -> NativeRecordCommandUi(label = "Restore", destructive = false)
    ActionEffect.copy -> NativeRecordCommandUi(label = "Copy", destructive = false)
    ActionEffect.permanentDelete -> NativeRecordCommandUi(
        label = "Delete permanently",
        destructive = true,
        confirmationTitle = "Delete $itemLabel permanently?",
        confirmationMessage = "This permanently removes the item from the server and cannot be undone.",
    )
    ActionEffect.clear -> NativeRecordCommandUi(
        label = "Clear",
        destructive = true,
        confirmationTitle = "Clear $itemLabel?",
        confirmationMessage = "This permanently clears the selected item and cannot be undone.",
    )
    ActionEffect.leave -> NativeRecordCommandUi(
        label = "Leave",
        destructive = true,
        confirmationTitle = "Leave $itemLabel?",
        confirmationMessage = "You may lose access to this item after leaving.",
    )
    ActionEffect.execute -> NativeRecordCommandUi(
        label = actionLabel?.takeIf(String::isNotBlank) ?: "Run",
        destructive = false,
        confirmationTitle = "${actionLabel?.takeIf(String::isNotBlank) ?: "Run action"}: $itemLabel?",
        confirmationMessage = "This records the action on the server.",
    )
    else -> error("Unsupported record command effect: $effect")
}

@Composable
private fun GenericGroupwareCollection(
    rows: List<Pair<NativeRecord, NativeGroupwarePresentation>>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(rows, key = { (record, _) -> record.id }) { (record, presentation) ->
            val interaction = onSelectRecord
                ?.let { callback -> Modifier.clickable { callback(record) } }
                ?: Modifier
            Card(
                modifier = interaction.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = NextcloudTheme.colors.appIconContainer,
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        if (presentation.kind == NativeGroupwareItemKind.Contact) {
                            Text(
                                presentation.title.nativeContactInitials(),
                                modifier = Modifier.padding(NextcloudSpacing.Medium),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NextcloudTheme.colors.appIcon,
                            )
                        } else {
                            Icon(
                                NextcloudIcons.Calendar,
                                contentDescription = null,
                                modifier = Modifier.padding(NextcloudSpacing.Medium).size(24.dp),
                                tint = NextcloudTheme.colors.appIcon,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        presentation.subtitle?.let { subtitle ->
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (onSelectRecord != null) {
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open ${presentation.title}",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun String.nativeContactInitials(): String = trim()
    .split(' ')
    .filter(String::isNotBlank)
    .take(2)
    .mapNotNull(String::firstOrNull)
    .joinToString("")
    .uppercase()
    .takeIf(String::isNotBlank)
    ?: "?"

@Composable
private fun GenericMailboxCollection(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    val rows = remember(resource, records) {
        records.map { record -> record to nativeMailboxPresentation(resource, record) }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Small,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.XXLarge,
        ),
    ) {
        items(rows, key = { (record, _) -> record.id }) { (record, presentation) ->
            val interaction = onSelectRecord?.let { callback -> Modifier.clickable { callback(record) } } ?: Modifier
            Row(
                modifier = interaction.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Surface(
                    color = if (presentation.unread) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        NextcloudTheme.colors.appIconContainer
                    },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        imageVector = when (presentation.kind) {
                            NativeMailboxItemKind.Folder -> NextcloudIcons.Folder
                            NativeMailboxItemKind.Account,
                            NativeMailboxItemKind.Message,
                            NativeMailboxItemKind.Unknown,
                            -> NextcloudIcons.app("mail")
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(NextcloudSpacing.Small).size(24.dp),
                        tint = if (presentation.unread) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            NextcloudTheme.colors.appIcon
                        },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            nativeMailSenderLabel(presentation.sender) ?: presentation.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = if (presentation.unread) FontWeight.Bold else FontWeight.Medium,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        nativeMailTimestampLabel(presentation.timestamp)?.let { timestamp ->
                            Text(
                                timestamp,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        presentation.unreadCount?.takeIf { it > 0 }?.let { count ->
                            Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraLarge) {
                                Text(
                                    count.toString(),
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    if (nativeMailSenderLabel(presentation.sender) != null) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (presentation.unread) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    presentation.preview?.let { preview ->
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (presentation.flagged) {
                    Icon(
                        NextcloudIcons.Favorite,
                        contentDescription = "Flagged",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (onSelectRecord != null) {
                    Icon(
                        NextcloudIcons.ChevronRight,
                        contentDescription = "Open ${presentation.title}",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun GenericMediaLibraryCollection(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    audioCollectionContext: NativeAudioCollectionContext?,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    imageLoader: NativeImageLoader?,
    audioPlayer: NativeAudioRecordPlayer?,
    mediaArtworkResolver: NativeMediaArtworkResolver?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val mediaItems = remember(resource, records) {
        records.map { record -> record to nativeMediaPresentation(resource, record) }
    }
    val trackList = mediaItems.count { (_, item) -> item.kind == NativeMediaItemKind.Track } > mediaItems.size / 2
    if (trackList) {
        val listState = rememberLazyListState()
        NativeCollectionAutoPager(
            listState = listState,
            itemCount = mediaItems.size,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Small,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
        ) {
            if (audioCollectionContext != null) {
                item(key = "media-collection-context") {
                    NativeMediaCollectionHeader(
                        resource = resource,
                        records = records,
                        collectionContext = audioCollectionContext,
                        imageLoader = imageLoader,
                        audioPlayer = audioPlayer,
                        mediaArtworkResolver = mediaArtworkResolver,
                    )
                }
            }
            items(mediaItems, key = { (record, _) -> record.id }) { (record, presentation) ->
                val artwork = remember(resource, record, mediaArtworkResolver) {
                    mediaArtworkResolver?.resolve(resource, record)
                        ?: presentation.nativeFallbackArtworkReference(record.id)
                }
                val playable = remember(resource, record, audioCollectionContext) {
                    nativeAudioTrack(resource, record, audioCollectionContext) != null
                }
                val interaction = when {
                    playable && audioPlayer != null -> Modifier.clickable {
                        audioPlayer.play(resource, records, record, audioCollectionContext)
                    }
                    onSelectRecord != null -> Modifier.clickable { onSelectRecord(record) }
                    else -> Modifier
                }
                Row(
                    modifier = interaction.fillMaxWidth().padding(vertical = NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    NativeMediaArtworkThumbnail(
                        reference = artwork,
                        title = presentation.title,
                        imageLoader = imageLoader,
                        modifier = Modifier.size(44.dp),
                    )
                    Text(
                        presentation.trackNumber ?: "♪",
                        modifier = Modifier.width(26.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        listOfNotNull(presentation.artist, presentation.album, presentation.detail)
                            .distinct().joinToString(" · ")
                            .takeIf(String::isNotBlank)?.let { subtitle ->
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                    }
                    if (presentation.favorite) {
                        Icon(
                            NextcloudIcons.Favorite,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    presentation.duration?.let { duration ->
                        Text(
                            duration,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (playable && audioPlayer != null) {
                        Icon(
                            NextcloudIcons.Play,
                            contentDescription = "Play ${presentation.title}",
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    } else if (onSelectRecord != null) {
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open ${presentation.title}",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            NativeCollectionPagingFooter(
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
                onRetry = onLoadMore,
            )
        }
    } else {
        val gridState = rememberLazyGridState()
        NativeCollectionGridAutoPager(
            gridState = gridState,
            onLoadMore = onLoadMore,
            loadingMore = loadingMore,
            loadMoreError = loadMoreError,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(168.dp),
            state = gridState,
            contentPadding = PaddingValues(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            items(mediaItems, key = { (record, _) -> record.id }) { (record, presentation) ->
                val artwork = remember(resource, record, mediaArtworkResolver) {
                    mediaArtworkResolver?.resolve(resource, record)
                        ?: presentation.nativeFallbackArtworkReference(record.id)
                }
                val interaction = onSelectRecord?.let { callback -> Modifier.clickable { callback(record) } } ?: Modifier
                Card(
                    modifier = interaction.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(108.dp),
                            color = NextcloudTheme.colors.appIconContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            NativeMediaArtworkThumbnail(
                                reference = artwork,
                                title = presentation.title,
                                imageLoader = imageLoader,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        (presentation.artist ?: presentation.detail)?.let { subtitle ->
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (loadingMore || loadMoreError != null) {
                item(
                    key = "media-grid-paging-footer",
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    NativeCollectionPagingStatus(
                        loadingMore = loadingMore,
                        loadMoreError = loadMoreError,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeMediaCollectionHeader(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    collectionContext: NativeAudioCollectionContext,
    imageLoader: NativeImageLoader?,
    audioPlayer: NativeAudioRecordPlayer?,
    mediaArtworkResolver: NativeMediaArtworkResolver?,
) {
    val firstPlayableRecord = remember(resource, records, collectionContext) {
        nativeAudioCollectionFirstPlayableRecord(resource, records, collectionContext)
    }
    val artworkReference = remember(
        resource,
        firstPlayableRecord,
        collectionContext,
        mediaArtworkResolver,
    ) {
        nativeAudioCollectionArtworkReference(
            collectionContext = collectionContext,
            childResource = resource,
            firstPlayableRecord = firstPlayableRecord,
            resolver = mediaArtworkResolver,
        )
    }
    val playableCount = remember(resource, records, collectionContext) {
        records.count { record -> nativeAudioTrack(resource, record, collectionContext) != null }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = NextcloudSpacing.Large),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 520.dp
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    if (compact) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(
                    if (compact) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                ),
            ) {
                NativeMediaArtworkThumbnail(
                    reference = artworkReference,
                    title = collectionContext.title,
                    imageLoader = imageLoader,
                    modifier = Modifier.size(if (compact) 80.dp else 112.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Text(
                        when (collectionContext.kind) {
                            NativeAudioCollectionKind.Album -> "Album"
                            NativeAudioCollectionKind.Artist -> "Artist"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        collectionContext.title,
                        style = if (compact) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (playableCount) {
                            0 -> "No playable tracks"
                            1 -> "1 track"
                            else -> "$playableCount tracks"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (firstPlayableRecord != null && audioPlayer != null) {
                        Button(
                            onClick = {
                                audioPlayer.playCollectionIfPossible(
                                    resource,
                                    records,
                                    collectionContext,
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Icon(
                                NextcloudIcons.Play,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text("Play", modifier = Modifier.padding(start = NextcloudSpacing.Small))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeMediaArtworkThumbnail(
    reference: NativeMediaArtworkReference,
    title: String,
    imageLoader: NativeImageLoader?,
    modifier: Modifier,
) {
    var image by remember(reference.cacheKey, imageLoader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(reference.cacheKey, imageLoader) {
        image = reference.relativePath?.let { path ->
            imageLoader?.let { loader -> runCatching { loader.load(path) }.getOrNull() }
        }
    }
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appIconContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        image?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = "Artwork for $title",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Box(contentAlignment = Alignment.Center) {
            if (reference.fallback == NativeMediaArtworkFallback.Artist) {
                Text(
                    title.trim().firstOrNull()?.uppercase() ?: "♪",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = NextcloudTheme.colors.appIcon,
                )
            } else {
                Icon(
                    NextcloudIcons.app("music"),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                    tint = NextcloudTheme.colors.appIcon,
                )
            }
        }
    }
}

@Composable
private fun GenericInsightCollection(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
) {
    val insights = remember(resource, records) { nativeDatasetInsights(resource, records) }
    val categoricalSummary = remember(resource, records) {
        nativeCategoricalSummary(resource, records)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactViewport = !datasetInsightsDefaultExpanded(maxWidth.value, maxHeight.value)
        Column(modifier = Modifier.fillMaxSize()) {
            if (insights != null) {
                DatasetInsightsDisclosure(
                    insights = insights,
                    compact = compactViewport,
                    initiallyExpanded = true,
                    stateKey = "insights:${resource.id}",
                )
            } else if (categoricalSummary != null) {
                CategoricalSummaryDisclosure(
                    summary = categoricalSummary,
                    initiallyExpanded = true,
                    stateKey = "summary:${resource.id}:${categoricalSummary.dimension.id}",
                )
            }
            GenericRecordList(resource, records, onSelectRecord, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CategoricalSummaryDisclosure(
    summary: NativeCategoricalSummary,
    initiallyExpanded: Boolean,
    stateKey: String,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Small,
                end = NextcloudSpacing.Small,
                bottom = NextcloudSpacing.XSmall,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.dimension.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${summary.recordCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            GenericCategoricalSummary(summary)
        } else {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun GenericCategoricalSummary(summary: NativeCategoricalSummary) {
    val maximum = summary.points.maxOfOrNull(NativeChartPoint::value)?.takeIf { it > 0.0 } ?: 1.0
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Small,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Medium,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        summary.points.forEach { point ->
            val count = point.value.roundToInt()
            val percentage = ((point.value / summary.recordCount) * 100.0).roundToInt()
            Column(
                modifier = Modifier.semantics {
                    contentDescription = "${point.label}, $count of ${summary.recordCount}, $percentage percent"
                },
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(point.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$count ($percentage%)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (point.value / maximum).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

internal fun datasetInsightsDefaultExpanded(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 720f && heightDp >= 600f

internal fun shouldUseCompactTableRecordList(widthDp: Float): Boolean = widthDp < 720f

@Composable
private fun DatasetInsightsDisclosure(
    insights: NativeDatasetInsights,
    compact: Boolean,
    initiallyExpanded: Boolean,
    stateKey: String,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.XSmall,
                end = NextcloudSpacing.Small,
                bottom = NextcloudSpacing.XSmall,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Insights",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${formatNativeMetric(insights.measure, insights.total)} total · ${insights.recordCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (expanded) {
            GenericDatasetInsights(insights, compact)
        } else {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = NextcloudSpacing.Large),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun GenericDatasetInsights(insights: NativeDatasetInsights, compact: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = NextcloudSpacing.Large,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Large,
            bottom = NextcloudSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        "Total ${insights.measure.label.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatNativeMetric(insights.measure, insights.total),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${insights.recordCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                DatasetMetricCard(
                    label = "Total ${insights.measure.label.lowercase()}",
                    value = formatNativeMetric(insights.measure, insights.total),
                )
                DatasetMetricCard(
                    label = "Average",
                    value = formatNativeMetric(insights.measure, insights.average),
                )
                DatasetMetricCard(label = "Items", value = insights.recordCount.toString())
            }
        }
        val displayedPoints = if (compact) insights.points.take(3) else insights.points
        if (displayedPoints.isNotEmpty()) {
            Text(
                "${insights.measure.label} by ${insights.dimension?.label.orEmpty().lowercase()}",
                style = MaterialTheme.typography.titleSmall,
            )
            val maximum = displayedPoints.maxOf { kotlin.math.abs(it.value) }.takeIf { it > 0.0 } ?: 1.0
            displayedPoints.forEach { point ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            point.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatNativeMetric(insights.measure, point.value),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (kotlin.math.abs(point.value) / maximum).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DatasetMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier.width(148.dp),
) {
    Surface(
        modifier = modifier,
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GenericCollectionCard(
    resource: ResourceSpec,
    record: NativeRecord,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    secondaryActions: List<NextcloudCardAction> = emptyList(),
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    busy: Boolean = false,
) {
    val presentation = nativeRecordPresentation(resource, record)
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    var actionsExpanded by rememberSaveable(record.id) { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = if (dense) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                vertical = if (dense) NextcloudSpacing.Small else NextcloudSpacing.Large,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (dense) NextcloudSpacing.Small else NextcloudSpacing.Medium,
            ),
        ) {
            leadingContent?.invoke()
            GenericResourceIcon(resource, presentation.iconKey, presentation.colorArgb)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                presentation.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                NativeRecordFacts(resource, record)
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (secondaryActions.isNotEmpty()) {
                NextcloudCardOverflow(
                    itemLabel = presentation.title,
                    actions = secondaryActions,
                    expanded = actionsExpanded,
                    onExpandedChange = { actionsExpanded = it },
                )
            } else if (onSelectRecord != null) {
                Icon(
                    NextcloudIcons.ChevronRight,
                    contentDescription = "Open ${presentation.title}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (onSelectRecord != null) {
                    contentDescription = "Open ${presentation.title}"
                }
            }
            .nextcloudCardInteractions(
                onOpen = onSelectRecord?.let { select -> { select(record) } },
                onShowActions = if (secondaryActions.isNotEmpty()) {
                    { actionsExpanded = true }
                } else {
                    null
                },
                openLabel = "Open ${presentation.title}",
                actionsLabel = "Show actions for ${presentation.title}",
            ),
        color = if (dense) MaterialTheme.colorScheme.background else NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(if (dense) 0.dp else NextcloudRadii.Card),
        content = content,
    )
}

@Composable
private fun GenericRecordGrid(
    resource: ResourceSpec,
    records: List<NativeRecord>,
    onSelectRecord: ((NativeRecord) -> Unit)?,
    recordImageLoader: NativeRecordImageLoader?,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    val gridState = rememberLazyGridState()
    NativeCollectionGridAutoPager(
        gridState = gridState,
        onLoadMore = onLoadMore,
        loadingMore = loadingMore,
        loadMoreError = loadMoreError,
    )
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(if (dense) 220.dp else 152.dp),
        contentPadding = PaddingValues(if (dense) NextcloudSpacing.XLarge else NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        items(records, key = NativeRecord::id) { record ->
            val presentation = nativeRecordPresentation(resource, record)
            val interaction = onSelectRecord?.let { callback -> Modifier.clickable { callback(record) } } ?: Modifier
            val noteLike = resource.fields.any { field -> field.kind == FieldKind.longText } &&
                presentation.colorArgb != null
            var recordImage by remember(resource.id, record, recordImageLoader) {
                mutableStateOf<NativeRecordImagePreview?>(null)
            }
            LaunchedEffect(resource.id, record, recordImageLoader) {
                recordImage = recordImageLoader?.let { loader ->
                    runCatching { loader.load(resource, record) }.getOrNull()
                }
            }
            val recordColor = presentation.colorArgb?.let(::Color)
            val containerColor = recordColor ?: NextcloudTheme.colors.appTile
            val contentColor = when {
                recordColor == null -> MaterialTheme.colorScheme.onSurface
                recordColor.luminance() > 0.42f -> Color(0xFF16131A)
                else -> Color.White
            }
            Card(
                modifier = interaction.fillMaxWidth().heightIn(min = if (dense) 92.dp else 112.dp),
                colors = CardDefaults.cardColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                ),
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                if (recordImage != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Image(
                            bitmap = requireNotNull(recordImage).image,
                            contentDescription = requireNotNull(recordImage).contentDescription,
                            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            contentScale = ContentScale.Crop,
                        )
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                presentation.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            presentation.subtitle?.let { subtitle ->
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(
                            if (dense) NextcloudSpacing.Medium else NextcloudSpacing.Large,
                        ),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        if (!noteLike) {
                            GenericResourceIcon(resource, presentation.iconKey, presentation.colorArgb)
                        }
                        Text(
                            presentation.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        presentation.subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor.copy(alpha = 0.78f),
                                maxLines = if (noteLike) 4 else 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        if (loadingMore || loadMoreError != null) {
            item(
                key = "collection-grid-paging-footer",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                NativeCollectionPagingStatus(
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    onRetry = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun NativeCollectionGridAutoPager(
    gridState: LazyGridState,
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    LaunchedEffect(gridState, onLoadMore, loadingMore, loadMoreError) {
        if (onLoadMore == null || loadingMore || loadMoreError != null) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && lastVisible >= total - 4
        }.distinctUntilChanged().collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }
}

@Composable
internal fun NativeCollectionPagingStatus(
    loadingMore: Boolean,
    loadMoreError: String?,
    onRetry: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loadingMore) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                "Loading more...",
                modifier = Modifier.padding(start = NextcloudSpacing.Small),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (loadMoreError != null) {
            Text(
                loadMoreError,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            onRetry?.let { retry ->
                TextButton(onClick = retry) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun GenericRecordDetail(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
    onOpenLink: ((String) -> Unit)?,
    imageLoader: NativeImageLoader?,
) {
    val mailTarget = remember(schema, resource, record, datasetContext) {
        nativeMailMessageRenderTarget(schema, resource, record, datasetContext)
    }
    if (mailTarget != null) {
        GenericMailMessageDetail(
            schema = schema,
            resource = mailTarget.resource,
            record = mailTarget.record,
            message = mailTarget.presentation,
            datasetContext = datasetContext,
            actionExecutor = actionExecutor,
            onActionSucceeded = onActionSucceeded,
            onInlineActionSucceeded = onInlineActionSucceeded,
        )
        return
    }
    val financeDashboard = remember(record) { nativeFinanceDashboardPresentation(record) }
    if (financeDashboard != null) {
        GenericFinanceStatisticsDashboard(financeDashboard)
        return
    }
    val budgetPlan = remember(record) { nativeBudgetPlanPresentation(record) }
    if (budgetPlan != null) {
        GenericBudgetPlanDashboard(budgetPlan)
        return
    }
    val groupware = remember(resource, record) { nativeGroupwarePresentation(resource, record) }
    if (groupware != null && groupware.kind != NativeGroupwareItemKind.Task) {
        GenericGroupwareDetail(groupware, onOpenLink)
        return
    }
    val accessSummary = remember(resource, record) { nativePermissionSummary(resource, record) }
    val detail = remember(resource, record, accessSummary) {
        nativeStructuredDetail(resource, record).let { detail ->
            detail.copy(fields = detail.fields.filterNot {
                it.fieldId in accessSummary?.fieldIds.orEmpty() ||
                    accessSummary != null && it.fieldId.equals("id", ignoreCase = true)
            })
        }
    }
    val recipe = remember(resource, record) { nativeRecipePresentation(resource, record) }
    val finance = remember(resource, record) { nativeFinancePresentation(resource, record) }
    val baseRecipeServings = remember(recipe?.servings) { parseRecipeServingCount(recipe?.servings) }
    var selectedRecipeServings by rememberSaveable(record.id, baseRecipeServings) {
        mutableStateOf(baseRecipeServings)
    }
    val adjustedRecipeServings = selectedRecipeServings
    val ingredientMultiplier = if (
        baseRecipeServings != null &&
        adjustedRecipeServings != null &&
        baseRecipeServings > 0.0
    ) {
        adjustedRecipeServings / baseRecipeServings
    } else {
        1.0
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        if (recipe != null) {
            GenericRecipeDetailHeader(
                recipe = recipe,
                imageLoader = imageLoader,
                baseServings = baseRecipeServings,
                selectedServings = adjustedRecipeServings,
                onSelectedServingsChange = baseRecipeServings?.let {
                    { servings -> selectedRecipeServings = servings }
                },
            )
        } else if (finance != null) {
            GenericFinanceDetailHeader(resource, finance)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                val presentation = nativeRecordPresentation(resource, record)
                GenericResourceIcon(
                    resource,
                    presentation.iconKey,
                    presentation.colorArgb,
                    large = true,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(presentation.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        resource.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        accessSummary?.let { NativePermissionSummary(it) }
        if (detail.fields.isNotEmpty()) {
            Text("Details", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Column {
                    detail.fields.forEachIndexed { index, field ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    field.formatted.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(field.formatted.displayValue, style = MaterialTheme.typography.bodyLarge)
                            }
                            if (field.formatted.safeLink != null && onOpenLink != null) {
                                TextButton(onClick = { onOpenLink(field.formatted.safeLink) }) {
                                    Icon(NextcloudIcons.FormatLink, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("Open", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
                                }
                            }
                        }
                    }
                }
            }
        }
        detail.sections.forEach { section ->
            if (
                recipe != null &&
                section.recipeTextItems() != null &&
                (section.isRecipeIngredientSection() || section.isRecipeInstructionSection())
            ) {
                GenericRecipeStructuredSection(record.id, section, ingredientMultiplier)
            } else {
                GenericStructuredDetailSection(section)
            }
        }
    }
}

private enum class NativeBudgetProgressFilter(val label: String) {
    All("All"),
    OverBudget("Over budget"),
    Watch("Watch"),
    OnTrack("On track"),
}

private fun NativeBudgetCategoryProgress.isOverBudget(): Boolean {
    val normalizedStatus = status?.lowercase()?.filter(Char::isLetterOrDigit)
    return when (normalizedStatus) {
        "over", "overbudget", "overspent", "exceeded" -> true
        "watch", "warning", "ontrack", "fullyspent", "spent", "complete", "completed" -> false
        else -> percentage > 100.0
    }
}

@Composable
private fun GenericBudgetPlanDashboard(plan: NativeBudgetPlanPresentation) {
    var filter by rememberSaveable { mutableStateOf(NativeBudgetProgressFilter.All) }
    val visibleCategories = remember(plan.categories, filter) {
        plan.categories.filter { category ->
            when (filter) {
                NativeBudgetProgressFilter.All -> true
                NativeBudgetProgressFilter.OverBudget -> category.isOverBudget()
                NativeBudgetProgressFilter.Watch -> !category.isOverBudget() && category.percentage >= 75.0
                NativeBudgetProgressFilter.OnTrack -> !category.isOverBudget() && category.percentage < 75.0
            }
        }
    }
    val period = listOfNotNull(plan.startDate, plan.endDate).joinToString(" - ")
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                top = NextcloudSpacing.Large,
                end = NextcloudSpacing.Large,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text("Budget progress", style = MaterialTheme.typography.headlineSmall)
            if (period.isNotBlank()) {
                Text(
                    period,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val currency = LocalNativeFinanceCurrency.current
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large)) {
            if (maxWidth < 600.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    Text(
                        nativeBudgetRemainingLabel(plan.remaining, currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (plan.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${formatNativeFinanceAmount(plan.spent, currency)} spent of ${formatNativeFinanceAmount(plan.budgeted, currency)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    DatasetMetricCard("Budgeted", formatNativeFinanceAmount(plan.budgeted, currency))
                    DatasetMetricCard("Spent", formatNativeFinanceAmount(plan.spent, currency))
                    DatasetMetricCard(if (plan.remaining < 0) "Over budget" else "Remaining", formatNativeFinanceAmount(kotlin.math.abs(plan.remaining), currency))
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large),
            colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Overall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${plan.percentage.roundToInt()}%", style = MaterialTheme.typography.titleMedium)
                }
                LinearProgressIndicator(
                    progress = { (plan.percentage / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth().height(9.dp),
                    color = if (plan.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                plan.overallStatus?.takeIf(String::isNotBlank)?.let { status ->
                    Text(
                        status.replaceFirstChar { character -> character.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            NativeBudgetProgressFilter.entries.forEach { option ->
                val count = plan.categories.count { category ->
                    when (option) {
                        NativeBudgetProgressFilter.All -> true
                        NativeBudgetProgressFilter.OverBudget -> category.isOverBudget()
                        NativeBudgetProgressFilter.Watch -> !category.isOverBudget() && category.percentage >= 75.0
                        NativeBudgetProgressFilter.OnTrack -> !category.isOverBudget() && category.percentage < 75.0
                    }
                }
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text("${option.label} $count") },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.Large,
                end = NextcloudSpacing.Large,
                bottom = NextcloudSpacing.XXLarge,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            if (visibleCategories.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    ) {
                        Text(
                            if (plan.categories.isEmpty()) {
                                "No category budgets yet"
                            } else {
                                "No ${filter.label.lowercase()} categories"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (plan.categories.isEmpty()) {
                                "Add a recurring budget to start planning this period."
                            } else {
                                "Choose another progress filter to review category budgets."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            visibleCategories.forEach { category ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                category.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text("${category.percentage.roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                        }
                        LinearProgressIndicator(
                            progress = { (category.percentage / 100.0).coerceIn(0.0, 1.0).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = if (category.isOverBudget() || category.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${formatNativeFinanceAmount(category.spent, currency)} spent",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                nativeBudgetRemainingLabel(category.remaining, currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (category.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (kotlin.math.abs(category.carried) >= 0.005) {
                            Text(
                                "Includes ${formatNativeFinanceAmount(category.carried, currency)} carryover",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenericFinanceStatisticsDashboard(
    dashboard: NativeFinanceDashboardPresentation,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            Text("Spending overview", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Balances and spending patterns",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            DatasetMetricCard("Paid", formatNativeFinanceAmount(dashboard.totalPaid, null))
            DatasetMetricCard("Spent", formatNativeFinanceAmount(dashboard.totalSpent, null))
            DatasetMetricCard("Balance", formatNativeFinanceAmount(dashboard.balance, null))
            DatasetMetricCard("Members", dashboard.members.size.toString())
        }

        Text("Members", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        dashboard.members.forEach { member ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Text(member.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FinanceStatisticValue("Paid", member.paid)
                        FinanceStatisticValue("Spent", member.spent)
                        FinanceStatisticValue("Balance", member.balance)
                    }
                }
            }
        }

        FinanceDashboardChart("Monthly spending", dashboard.monthlySpending)
        FinanceDashboardChart("Spending by category", dashboard.categories)
        FinanceDashboardChart("Payment methods", dashboard.paymentMethods)
    }
}

@Composable
private fun FinanceStatisticValue(label: String, value: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatNativeFinanceAmount(value, null),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FinanceDashboardChart(
    title: String,
    points: List<NativeChartPoint>,
) {
    if (points.isEmpty()) return
    val shown = points.take(8)
    val maximum = shown.maxOf { point -> kotlin.math.abs(point.value) }.takeIf { it > 0.0 } ?: 1.0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            shown.forEach { point ->
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            point.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatNativeFinanceAmount(point.value, null),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (kotlin.math.abs(point.value) / maximum).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GenericGroupwareDetail(
    presentation: NativeGroupwarePresentation,
    onOpenLink: ((String) -> Unit)?,
) {
    val emailUri = remember(presentation.primaryEmail) {
        nativeContactEmailUri(presentation.primaryEmail)
    }
    val phoneUri = remember(presentation.primaryPhone) {
        nativeContactPhoneUri(presentation.primaryPhone)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                if (presentation.kind == NativeGroupwareItemKind.Contact) {
                    Text(
                        presentation.title.nativeContactInitials(),
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = NextcloudTheme.colors.appIcon,
                    )
                } else {
                    Icon(
                        NextcloudIcons.Calendar,
                        contentDescription = null,
                        modifier = Modifier.padding(NextcloudSpacing.Large).size(32.dp),
                        tint = NextcloudTheme.colors.appIcon,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                listOfNotNull(
                    presentation.organization,
                    presentation.status?.takeIf { presentation.kind == NativeGroupwareItemKind.Event },
                ).distinct().joinToString(" · ").takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (
            presentation.kind == NativeGroupwareItemKind.Contact &&
            onOpenLink != null &&
            (emailUri != null || phoneUri != null)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                emailUri?.let { uri ->
                    Button(onClick = { onOpenLink(uri) }) {
                        Icon(NextcloudIcons.app("mail"), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Email", modifier = Modifier.padding(start = NextcloudSpacing.Small))
                    }
                }
                phoneUri?.let { uri ->
                    OutlinedButton(onClick = { onOpenLink(uri) }) {
                        Text("Call")
                    }
                }
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
            shape = RoundedCornerShape(NextcloudRadii.Card),
        ) {
            Column {
                val rows = when (presentation.kind) {
                    NativeGroupwareItemKind.Contact -> listOfNotNull(
                        presentation.primaryEmail?.let { "Email" to it },
                        presentation.primaryPhone?.let { "Phone" to it },
                        presentation.organization?.let { "Organization" to it },
                        presentation.address?.let { "Address" to it },
                        presentation.birthday?.let { "Birthday" to it.compactSemanticDateTime() },
                    )
                    NativeGroupwareItemKind.Event -> listOfNotNull(
                        presentation.start?.let {
                            (if (presentation.allDay) "Date" else "Starts") to it.compactSemanticDateTime()
                        },
                        presentation.end?.let { "Ends" to it.compactSemanticDateTime() },
                        presentation.location?.let { "Location" to it },
                        presentation.organizer?.let { "Organizer" to it },
                        presentation.attendeeCount?.let { "Attendees" to it.toString() },
                        presentation.status?.let { "Status" to it },
                        presentation.recurrenceRule?.let { "Repeats" to it },
                    )
                    NativeGroupwareItemKind.Task -> emptyList()
                }
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(value, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        presentation.description?.takeIf(String::isNotBlank)?.let { description ->
            Text(
                if (presentation.kind == NativeGroupwareItemKind.Contact) "Notes" else "Description",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                SelectionContainer {
                    Text(
                        description,
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

internal fun nativeDedicatedCollectionState(
    state: NativeScreenState,
    presentedRecords: List<NativeRecord>,
    visiblePresentedRecords: List<NativeRecord>,
    searchableCollection: Boolean,
): NativeScreenState = when (state) {
    is NativeScreenState.Ready -> NativeScreenState.Ready(
        if (searchableCollection) visiblePresentedRecords else presentedRecords,
    )
    else -> state
}

internal fun genericCollectionSearchAvailable(
    state: NativeScreenState,
    recordCount: Int,
    surface: GenericNativeSurface,
    nativeMailWorkspaceEligible: Boolean,
): Boolean = state is NativeScreenState.Ready &&
    recordCount > 0 &&
    !nativeMailWorkspaceEligible &&
    surface in setOf(
        GenericNativeSurface.List,
        GenericNativeSurface.Grid,
        GenericNativeSurface.Table,
        GenericNativeSurface.Mailbox,
    )

private const val MAX_NATIVE_COLLECTION_BATCH_RELATIONS = 16
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_BINDINGS = 32
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_RECORDS = 500
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_ERROR_LENGTH = 1_024
