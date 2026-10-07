package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    collectionCreateControl: NativeCollectionCreateControl? = null,
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
    val playlist = remember(schema, view, readyRecords) { nativePlaylistTracks(schema, view, readyRecords) }
    if (playlist != null) {
        NativePlaylistTrackCollection(playlist, imageLoader, audioPlayer, mediaArtworkResolver)
        return
    }
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
    val collectionCreatePlans = nativeCollectionCreatePlans(
        schema, view.sourceActionId, presentedResource, presentedRecords, datasetContext,
        collectionComplete = onLoadMore == null,
        enabled = showCollectionCreateAction && state is NativeScreenState.Ready && pendingMutationStore != null,
    )
    val collectionCreatePlan = collectionCreatePlans?.form
    val collectionCreateRecoveryPlan = collectionCreatePlans?.recovery
    val openCollectionCreate: (() -> Unit)? = collectionCreatePlan?.let { plan ->
        val actionResourceId = plan.action.resourceId
        create@{
            if (formMutationRecovery?.blocksSubmission == true) return@create
            pendingRecordFormActionToken = RestorableNativeRecordFormAction(
                actionId = plan.action.id,
                resourceId = actionResourceId,
                kind = plan.kind,
                recordId = null,
            ).encode()
        }
    }
    BindNativeCollectionCreateControl(collectionCreateControl, collectionCreatePlan?.action,
        openCollectionCreate.takeUnless { formMutationRecovery?.blocksSubmission == true })
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
                    presentedResource, presentedRecords, nativeAudioCollectionContext(schema, datasetContext),
                    onSelectRecord, imageLoader, audioPlayer, mediaArtworkResolver,
                    onLoadMore, loadingMore, loadMoreError,
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
                    state.generation,
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
        GenericRecipeCollection(recipes, onSelectRecord, imageLoader, onLoadMore, loadingMore, loadMoreError)
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
    val primaryContent = remember(schema, resource) { nativeCollectionCardPrimaryContent(schema, resource) }
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
                primaryContent = primaryContent,
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
internal fun NativeCollectionReorderRecoveryMessage(
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
    val pantryItems = nativePantryCollectionKind(schema, resource) == NativePantryCollectionKind.Item
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
                        if (pantryItems) NativePantryTaskDetails(record)
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
internal fun NativeCollectionGridAutoPager(
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
    val tableDetail = remember(schema, resource, record, datasetContext) { nativeTableRecordDetail(schema, resource, record, datasetContext) }
    if (tableDetail != null) { NativeTableRecordDetail(tableDetail); return }
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
        NativeRecordMetadata(record.id, detail.fields, collapsible = recipe != null || finance != null, onOpenLink = onOpenLink)
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

private const val MAX_NATIVE_COLLECTION_BATCH_RELATIONS = 16
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_BINDINGS = 32
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_RECORDS = 500
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_ERROR_LENGTH = 1_024
