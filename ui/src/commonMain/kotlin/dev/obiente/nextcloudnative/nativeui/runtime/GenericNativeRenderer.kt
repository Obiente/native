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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.ui.material3.RichText
import dev.obiente.nextcloudnative.app.design.BoardDragVerticalScrollTarget
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudVerticalDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudCardAction
import dev.obiente.nextcloudnative.app.design.NextcloudCardOverflow
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragHandle
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.nextcloudCardInteractions
import dev.obiente.nextcloudnative.app.design.resolveBoardDragVerticalLane
import dev.obiente.nextcloudnative.nativeui.model.ActionEffect
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionRisk
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_INTEGER_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_ARRAY_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.DYNAMIC_STRING_LIST_FORMAT
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.FieldSpec
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputFieldSpec
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputRow
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputScalarKind
import dev.obiente.nextcloudnative.nativeui.model.RepeatableObjectInputSpec
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
    collectionBatchRelationLoader: NativeCollectionBatchRelationLoader? = null,
    workspaceNavigationItems: List<NativeWorkspaceNavigationItem> = emptyList(),
    onWorkspaceNavigate: ((String) -> Unit)? = null,
) {
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
    val presentedState = when (state) {
        is NativeScreenState.Ready -> NativeScreenState.Ready(presentedRecords)
        else -> state
    }
    val choresWorkspace = presentedResource?.let { resourceSpec ->
        nativeChoresPresentation(schema, view, resourceSpec, presentedState)
    }
    val rosterPresentation = choresWorkspace
        ?.takeIf { presentation -> presentation.kind == NativeChoresWorkspaceKind.Team }
        ?.let {
            (presentedState as? NativeScreenState.Ready)
                ?.records
                ?.singleOrNull()
                ?.let(::nativeRosterPresentation)
        }
    val presentedSurface = when {
        showSelectedRecordDetail &&
            selectedRecordId != null &&
            presentedRecords.any { record -> record.id == selectedRecordId } ->
            GenericNativeSurface.Detail
        shouldAutoOpenSyntheticRecord(presentedRecords) -> GenericNativeSurface.Detail
        nestedBoard != null -> GenericNativeSurface.Board
        else -> view.genericSurface(presentedResource, presentedRecords)
    }
    val searchableCollection = state is NativeScreenState.Ready &&
        presentedRecords.size > 1 &&
        presentedSurface in setOf(
            GenericNativeSurface.List,
            GenericNativeSurface.Grid,
            GenericNativeSurface.Table,
        )
    val collectionSearchContextKey = remember(datasetContext) {
        buildString {
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
        view.id,
        collectionSearchContextKey,
    ) { mutableStateOf("") }
    val visiblePresentedRecords = remember(
        presentedResource,
        presentedRecords,
        collectionQuery,
        searchableCollection,
    ) {
        if (!searchableCollection || collectionQuery.isBlank() || presentedResource == null) {
            presentedRecords
        } else {
            presentedRecords.filter { record ->
                nativeRecordMatchesCollectionQuery(
                    resource = presentedResource,
                    record = record,
                    query = collectionQuery,
                )
            }
        }
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
    val collectionCreatePlan = presentedResource
        ?.takeIf { showCollectionCreateAction }
        ?.let { resource ->
        nativeRecordActions(
            schema = schema,
            resource = resource,
            navigationContext = datasetContext.bindingValues,
        ).create
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
            PendingNativeRecordFormAction(
                plan = plan,
                itemLabel = record
                    ?.let { nativeRecordPresentation(actionResource, it).title }
                    ?: actionResource.name,
                resource = actionResource,
                datasetContext = datasetContext,
                restoreKey = pendingRecordFormActionToken.orEmpty(),
                mutationRecoveryOwner = mutationRecoveryOwner,
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
    val mailWorkspaceSection = remember(schema, presentedResource, datasetContext) {
        presentedResource?.let { currentResource ->
            nativeMailWorkspaceSection(schema, currentResource, datasetContext)
        } ?: NativeMailWorkspaceSection.Unknown
    }
    val mailWorkspaceEligible = remember(schema, mailWorkspaceSection) {
        schema.hasNativeMailWorkspaceSemantics() &&
            mailWorkspaceSection != NativeMailWorkspaceSection.Unknown
    }
    val mailWorkspacePlan = remember(
        schema,
        presentedResource,
        presentedRecords,
        datasetContext,
        selectedRecordId,
        selectedRecordResourceId,
        mailWorkspaceEligible,
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
            )
            mailWorkspacePlan != null && state is NativeScreenState.Loading ->
                NativeMailWorkspace(
                    plan = mailWorkspacePlan,
                    onSelectRecord = onSelectRecord,
                    contentState = mailWorkspaceContentState,
                )
            mailWorkspacePlan != null && state is NativeScreenState.Error ->
                NativeMailWorkspace(
                    plan = mailWorkspacePlan,
                    onSelectRecord = onSelectRecord,
                    contentState = mailWorkspaceContentState,
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
                    contentState = mailWorkspaceContentState,
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
                    contentState = mailWorkspaceContentState,
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
                    visiblePresentedRecords,
                    datasetContext,
                    actionExecutor,
                    onSelectRecord,
                    onInlineActionSucceeded,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
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
    pendingRecordFormAction?.let { pending ->
        GenericRecordActionFormDialog(
            pending = pending,
            schema = schema,
            actionExecutor = actionExecutor,
            filePicker = filePicker,
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
    pendingRecordCommandFormAction?.let { pending ->
        GenericRecordActionFormDialog(
            pending = pending,
            schema = schema,
            actionExecutor = actionExecutor,
            filePicker = filePicker,
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
private fun GenericCollectionSearchField(
    resourceName: String,
    query: String,
    onQueryChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = modifier,
        singleLine = true,
        leadingIcon = {
            Icon(
                NextcloudIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        placeholder = {
            Text(
                "Search ${resourceName.ifBlank { "items" }.lowercase()}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

internal fun nativeRecordMatchesCollectionQuery(
    resource: ResourceSpec,
    record: NativeRecord,
    query: String,
): Boolean {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (terms.isEmpty()) return true
    val presentation = nativeRecordPresentation(resource, record)
    val searchableText = buildList {
        add(presentation.title)
        presentation.subtitle?.let(::add)
        addAll(record.displayValues.values)
        resource.fields
            .filterNot { field -> field.id.isTechnicalCollectionSearchField() }
            .mapNotNullTo(this) { field -> record.values[field.id] }
    }.joinToString(" ").lowercase()
    return terms.all { term -> term.lowercase() in searchableText }
}

private fun String.isTechnicalCollectionSearchField(): Boolean {
    val words = replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
    return words.any { word ->
        word in setOf("id", "uuid", "etag", "order", "position", "sort", "token")
    }
}

@Composable
private fun GenericRendererNoSearchResults(
    query: String,
    onClear: () -> Unit,
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Search)
        Text("No matching items", style = MaterialTheme.typography.titleLarge)
        Text(
            "Nothing matches \"$query\".",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onClear) { Text("Clear search") }
    }
}

@Composable
private fun GenericRendererSearchPagingState(
    query: String,
    loading: Boolean,
    error: String?,
    onRetry: (() -> Unit)?,
    onClear: () -> Unit,
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Search)
        Text(
            if (loading) "Searching more items" else "Could not finish searching",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            error ?: "Looking through the rest of the collection for \"$query\".",
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                onRetry?.let { retry -> TextButton(onClick = retry) { Text("Try again") } }
                TextButton(onClick = onClear) { Text("Clear search") }
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

private sealed interface PendingNativeRecordActionForm {
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

private data class PendingNativeRecordFormAction(
    val plan: NativeRecordFormActionPlan,
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

private data class PendingNativeRecordCommandFormAction(
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

private fun nativeRecordFormDraftSaver(declaredFieldIds: Set<String>) = Saver<Map<String, String>, List<String>>(
    save = { draft ->
        if (draft.keys.all(declaredFieldIds::contains)) encodeNativeRecordFormDraft(draft) else null
    },
    restore = { saved ->
        decodeNativeRecordFormDraft(saved)?.takeIf { values -> values.keys.all(declaredFieldIds::contains) }
    },
)

private fun nativeRepeatableObjectDraftSaver(
    specs: Map<String, RepeatableObjectInputSpec>,
) = Saver<Map<String, List<RepeatableObjectInputRow>>, List<String>>(
    save = { draft -> encodeNativeRepeatableObjectDraft(draft, specs) },
    restore = { saved -> decodeNativeRepeatableObjectDraft(saved, specs) },
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
    val itemLabel: String,
    val initialError: String? = null,
    val initialFailureOutcome: NativeActionFailureOutcome? = null,
)

@Composable
private fun GenericRecordActionFormDialog(
    pending: PendingNativeRecordActionForm,
    schema: NativeAppSchema,
    actionExecutor: NativeActionExecutor,
    filePicker: NativeFileFieldPicker?,
    mutationRecovery: NativeFormMutationRecoveryState?,
    onMutationStarted: (NativeFormMutationRecoveryOwner) -> Unit,
    onMutationFinished: (NativeFormMutationRecoveryOwner, NativeActionExecutionResult) -> Unit,
    onDismiss: () -> Unit,
    onActionSucceeded: (ActionSpec) -> Unit,
) {
    val scalarFields = remember(pending.fields) {
        pending.fields.filter { field -> field.repeatableObjectInput == null }
    }
    val displayFields = remember(pending.fields, pending.resource, schema) {
        nativeFormDisplayFields(
            fields = pending.fields,
            relationFieldIds = pending.fields
                .filter { field -> nativeRelationFieldRequiresChoice(field, pending.resource, schema) }
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
        nativeRepeatableObjectDraftSaver(structuredSpecs)
    }
    var repeatableObjectValues by rememberSaveable(
        "${pending.restoreKey}:structured",
        stateSaver = structuredDraftSaver,
    ) {
        mutableStateOf(initialStructuredDraft ?: emptyStructuredDraft)
    }
    var structuredDraftSafe by rememberSaveable(pending.restoreKey) {
        mutableStateOf(initialStructuredDraft != null)
    }
    var error by remember(pending) {
        mutableStateOf(
            if (initialStructuredDraft == null) {
                "The existing structured value could not be edited safely."
            } else {
                null
            },
        )
    }
    var awaitingConfirmation by rememberSaveable(pending.restoreKey) { mutableStateOf(false) }
    var submitting by remember(pending) { mutableStateOf(false) }
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
            val result = actionExecutor.execute(request)
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

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
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
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
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
                                    enabled = !submitting && formRetryAllowed && structuredDraftSafe,
                                    onRowsChange = { rows ->
                                        repeatableObjectValues = repeatableObjectValues +
                                            (field.id to rows)
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
                            if (nativeRelationFieldRequiresChoice(field, pending.resource, schema)) {
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
                                repeatableObjectValues = emptyStructuredDraft
                                structuredDraftSafe = true
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
            if (formRetryAllowed) {
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
                        Text(if (awaitingConfirmation) "Confirm" else operation)
                    }
                }
            }
        },
    )
}

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
                            when (
                                val result = actionExecutor.execute(
                                    pending.plan.request(confirmed = pending.plan.requiresConfirmation),
                                )
                            ) {
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
private fun GenericRecordTable(
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
private fun GenericTableCollection(
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
) {
    val composite = view.compositeDataGrid
    val columnResource = composite?.let { schema.resource(it.columnResourceId) }
    val columnRecords = composite?.let { datasetContext.relatedRecords[it.columnResourceId].orEmpty() }.orEmpty()
    val projection = remember(resource, records, columnResource, columnRecords, composite) {
        nativeTableProjection(resource, records, columnResource, columnRecords, composite)
    }
    val insights = remember(projection) { nativeDatasetInsights(projection.resource, projection.records) }
    val facets = remember(projection) { inferNativeDatasetFacets(projection.resource, projection.records) }
    val browseStateKey = remember(schema, view, projection.resource, datasetContext) {
        nativeDatasetBrowseStateKey(schema, view, projection.resource, datasetContext)
    }
    var facetSelections by remember(browseStateKey) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var searchQuery by remember(browseStateKey) { mutableStateOf("") }
    var sortMode by remember(browseStateKey) { mutableStateOf(NativeDatasetSortMode.Server) }
    var filtersExpanded by remember(browseStateKey) { mutableStateOf(false) }
    val filteredRecords = remember(projection.records, facetSelections, searchQuery, sortMode) {
        browseNativeDatasetRecords(
            resource = projection.resource,
            records = projection.records,
            selections = facetSelections,
            searchQuery = searchQuery,
            sortMode = sortMode,
        )
    }

    fun toggleFacet(fieldId: String, value: String) {
        val nextValues = facetSelections[fieldId].orEmpty().toMutableSet().apply {
            if (!add(value)) remove(value)
        }
        facetSelections = facetSelections.toMutableMap().apply {
            if (nextValues.isEmpty()) remove(fieldId) else put(fieldId, nextValues)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactRecordList = shouldUseCompactTableRecordList(maxWidth.value)
        val expandInsights = datasetInsightsDefaultExpanded(maxWidth.value, maxHeight.value)
        Column(modifier = Modifier.fillMaxSize()) {
            insights?.let {
                DatasetInsightsDisclosure(
                    insights = it,
                    compact = !expandInsights,
                    initiallyExpanded = expandInsights,
                    stateKey = "table-insights:$browseStateKey",
                )
            }
            NativeTableBrowseControls(
                searchQuery = searchQuery,
                onSearchQueryChanged = { searchQuery = it },
                facets = facets,
                selections = facetSelections,
                filtersExpanded = filtersExpanded,
                onFiltersExpandedChange = { filtersExpanded = it },
                onToggleFacet = ::toggleFacet,
                onClearFilters = { facetSelections = emptyMap() },
                sortMode = sortMode,
                onSortModeChanged = { sortMode = it },
            )
            if (filteredRecords.isEmpty()) {
                LaunchedEffect(
                    projection.records.size,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                ) {
                    if (onLoadMore != null && !loadingMore && loadMoreError == null) {
                        onLoadMore()
                    }
                }
                GenericCenteredState {
                    Text("No matching records", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Clear or adjust the current search and filters to see more records.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    NativeCollectionPagingStatus(
                        loadingMore = loadingMore,
                        loadMoreError = loadMoreError,
                        onRetry = onLoadMore,
                    )
                }
            } else if (compactRecordList) {
                GenericEditableTableRecordList(
                    schema = schema,
                    sourceResource = resource,
                    projection = projection,
                    records = filteredRecords,
                    onSelectRecord = onSelectRecord,
                    actionExecutor = actionExecutor,
                    onInlineActionSucceeded = onInlineActionSucceeded,
                    onLoadMore = onLoadMore,
                    loadingMore = loadingMore,
                    loadMoreError = loadMoreError,
                    modifier = Modifier.weight(1f),
                )
            } else {
                GenericRecordTable(
                    schema,
                    view,
                    resource,
                    filteredRecords,
                    datasetContext,
                    actionExecutor,
                    onSelectRecord,
                    onInlineActionSucceeded,
                    onLoadMore,
                    loadingMore,
                    loadMoreError,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NativeTableBrowseControls(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    facets: List<NativeDatasetFacet>,
    selections: Map<String, Set<String>>,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onToggleFacet: (fieldId: String, value: String) -> Unit,
    onClearFilters: () -> Unit,
    sortMode: NativeDatasetSortMode,
    onSortModeChanged: (NativeDatasetSortMode) -> Unit,
) {
    val activeFilterCount = selections.values.sumOf(Set<String>::size)
    val sortModes = NativeDatasetSortMode.entries
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.Large,
            vertical = NextcloudSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search records") },
            leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(
                    enabled = facets.isNotEmpty(),
                    onClick = { onFiltersExpandedChange(!filtersExpanded) },
                ) {
                    Icon(NextcloudIcons.Filter, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        if (activeFilterCount == 0) "Filter" else "Filter ($activeFilterCount)",
                        modifier = Modifier.padding(start = NextcloudSpacing.XSmall),
                    )
                }
                DropdownMenu(
                    expanded = filtersExpanded,
                    onDismissRequest = { onFiltersExpandedChange(false) },
                ) {
                    facets.forEachIndexed { index, facet ->
                        Text(
                            facet.field.label,
                            modifier = Modifier.padding(
                                start = NextcloudSpacing.Large,
                                top = if (index == 0) NextcloudSpacing.Small else NextcloudSpacing.Medium,
                                end = NextcloudSpacing.Large,
                                bottom = NextcloudSpacing.XSmall,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        facet.options.forEach { option ->
                            val selected = option.value in selections[facet.field.id].orEmpty()
                            DropdownMenuItem(
                                text = { Text("${option.label} (${option.count})") },
                                trailingIcon = if (selected) {
                                    {
                                        Icon(
                                            NextcloudIcons.CheckCircle,
                                            contentDescription = "Selected",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    null
                                },
                                onClick = { onToggleFacet(facet.field.id, option.value) },
                            )
                        }
                    }
                    if (activeFilterCount > 0) {
                        DropdownMenuItem(
                            text = { Text("Clear filters") },
                            onClick = {
                                onClearFilters()
                                onFiltersExpandedChange(false)
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    val next = (sortModes.indexOf(sortMode) + 1) % sortModes.size
                    onSortModeChanged(sortModes[next])
                },
            ) {
                Text(sortMode.label)
            }
        }
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
private fun GenericRendererLoading(title: String) {
    GenericCenteredState {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.medium) {
            Box(modifier = Modifier.padding(NextcloudSpacing.Large), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
            }
        }
        Text("Loading $title", style = MaterialTheme.typography.titleMedium)
        Text(
            "Fetching the latest data from your server...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GenericRendererEmpty(
    resourceId: String,
    resourceName: String,
    createLabel: String? = null,
    onCreate: (() -> Unit)? = null,
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Apps)
        Text("No ${resourceName.lowercase()} yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "New items will appear here when the server returns them.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onCreate != null) {
            Button(
                onClick = onCreate,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Create $resourceId"
                    },
            ) {
                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    createLabel?.takeIf(String::isNotBlank) ?: "Create item",
                    modifier = Modifier.padding(start = NextcloudSpacing.Small),
                )
            }
        }
    }
}

@Composable
private fun GenericCenteredState(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            content = content,
        )
    }
}

@Composable
private fun GenericStateIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, error: Boolean = false) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else NextcloudTheme.colors.appIconContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (error) MaterialTheme.colorScheme.onErrorContainer else NextcloudTheme.colors.appIcon,
            modifier = Modifier.padding(NextcloudSpacing.Large).size(32.dp),
        )
    }
}

@Composable
private fun GenericRendererError(
    message: String,
    retry: (() -> Unit)? = null,
    retryLabel: String = "Try again",
) {
    GenericCenteredState {
        GenericStateIcon(NextcloudIcons.Error, error = true)
        Text("Could not show this view", style = MaterialTheme.typography.titleLarge)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        retry?.let { action ->
            Button(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(NextcloudIcons.Refresh, contentDescription = null, modifier = Modifier.size(19.dp))
                Text(retryLabel, modifier = Modifier.padding(start = NextcloudSpacing.Small))
            }
        }
    }
}

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
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    loadMoreError: String? = null,
) {
    val authoritativeOrder = remember(records) { records.map(NativeRecord::id) }
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
    val rowBounds = remember(reorder?.action?.id, resource.id) { mutableStateMapOf<String, Rect>() }
    var listBounds by remember(reorder?.action?.id, resource.id) { mutableStateOf<Rect?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val recordsById = remember(records) { records.associateBy(NativeRecord::id) }
    val displayedRecords = remember(recordsById, orderedRecordIds, reorder) {
        if (reorder == null) records else orderedRecordIds.mapNotNull(recordsById::get)
    }
    val submitReorder: () -> Unit = submit@{
        val plan = reorder ?: return@submit
        val executor = actionExecutor ?: return@submit
        val submittedOrder = orderedRecordIds
        if (submittedOrder == authoritativeOrder) return@submit
        val request = runCatching { plan.requestInOrder(submittedOrder) }.getOrElse { failure ->
            reorderError = failure.message ?: "The new order could not be submitted."
            orderedRecordIds = authoritativeOrder
            return@submit
        }
        reorderExecuting = true
        reorderError = null
        scope.launch {
            when (val result = executor.execute(request)) {
                is NativeActionExecutionResult.Success -> onActionSucceeded?.invoke(plan.action)
                is NativeActionExecutionResult.Failure -> {
                    reorderError = result.message
                    orderedRecordIds = authoritativeOrder
                    if (result.outcome.requiresMutationReconciliation()) {
                        onActionSucceeded?.invoke(plan.action)
                    }
                }
            }
            reorderExecuting = false
        }
    }
    LaunchedEffect(authoritativeOrder, reorder?.action?.id) {
        if (draggingRecordId == null && !reorderExecuting) {
            orderedRecordIds = authoritativeOrder
            reorderError = null
        }
    }
    fun moveDraggedRecord(position: Offset) {
        val recordId = draggingRecordId ?: return
        val targetId = orderedRecordIds.firstOrNull { id ->
            rowBounds[id]?.contains(position) == true
        }
        val targetIndex = targetId?.let(orderedRecordIds::indexOf) ?: -1
        if (targetIndex >= 0 && targetId != recordId) {
            orderedRecordIds = moveNativeCollectionRecordToIndex(
                orderedRecordIds = orderedRecordIds,
                recordId = recordId,
                targetIndex = targetIndex,
            )
        }
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
        reorderError?.let { message ->
            item(key = "collection-reorder-error") {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
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
                leadingContent = reorder?.takeUnless { reorderExecuting }?.let {
                    {
                        NextcloudBoardDragHandle(
                            itemLabel = nativeRecordPresentation(resource, record).title,
                            dragActive = dragging,
                            onDragStart = { position ->
                                draggingRecordId = record.id
                                dragOrigin = position
                                dragPosition = position
                                reorderError = null
                            },
                            onDrag = { delta ->
                                val position = (dragPosition ?: return@NextcloudBoardDragHandle) + delta
                                dragPosition = position
                                orderedRecordIds = moveNativeCollectionRecordAcrossAdjacentMidpoint(
                                    orderedRecordIds = orderedRecordIds,
                                    recordId = record.id,
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
                    }
                },
                busy = reorderExecuting,
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
private fun GenericEditableTableRecordList(
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

internal data class NativeCategoryRow(
    val record: NativeRecord,
    val presentation: NativeCategoryPresentation,
    val depth: Int,
    val hasChildren: Boolean,
)

private fun flattenNativeCategoryRows(
    rows: List<Pair<NativeRecord, NativeCategoryPresentation>>,
    expandedIds: Set<String>,
): List<NativeCategoryRow> {
    val ids = rows.map { (record, _) -> record.id }.toSet()
    val children = rows.groupBy { (_, category) -> category.parentId?.takeIf(ids::contains) }
    val output = mutableListOf<NativeCategoryRow>()
    val visited = mutableSetOf<String>()
    fun append(parentId: String?, depth: Int) {
        children[parentId].orEmpty()
            .sortedBy { (_, category) -> category.name.lowercase() }
            .forEach { (record, category) ->
                if (!visited.add(record.id)) return@forEach
                val hasChildren = children[record.id].orEmpty().isNotEmpty()
                output += NativeCategoryRow(record, category, depth, hasChildren)
                if (hasChildren && record.id in expandedIds) append(record.id, depth + 1)
            }
    }
    append(null, 0)
    rows.filterNot { (record, _) -> record.id in visited }.forEach { (record, category) ->
        output += NativeCategoryRow(record, category, 0, hasChildren = false)
    }
    return output
}

/**
 * Keeps a verified flat collection in its authoritative order while a reorder draft is active.
 *
 * Category hierarchy presentation normally sorts siblings by name. Applying that projection to a
 * reorderable flat collection hides every drag-state update by immediately sorting the rows back
 * into their previous visual positions. Hierarchical collections remain projected and sorted;
 * their partial tree traversal is not eligible for the complete-order mutation in the first place.
 */
internal fun nativeCategoryRowsForDisplay(
    rows: List<Pair<NativeRecord, NativeCategoryPresentation>>,
    expandedIds: Set<String>,
    preserveAuthoritativeOrder: Boolean,
): List<NativeCategoryRow> {
    val knownIds = rows.mapTo(hashSetOf()) { (record, _) -> record.id }
    val hasHierarchy = rows.any { (_, category) -> category.parentId in knownIds }
    return if (preserveAuthoritativeOrder && !hasHierarchy) {
        rows.map { (record, category) ->
            NativeCategoryRow(record, category, depth = 0, hasChildren = false)
        }
    } else {
        flattenNativeCategoryRows(rows, expandedIds)
    }
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
        parentIds.isEmpty() && filter == NativeCategoryFilter.All
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
    val rowBounds = remember(reorder?.action?.id, resource.id) { mutableStateMapOf<String, Rect>() }
    var listBounds by remember(reorder?.action?.id, resource.id) { mutableStateOf<Rect?>(null) }
    val displayedRows = remember(rows, rowsById, orderedRecordIds, activeReorder) {
        if (activeReorder == null) rows else orderedRecordIds.mapNotNull(rowsById::get)
    }
    val submitReorder: () -> Unit = submit@{
        val plan = activeReorder ?: return@submit
        val submittedOrder = orderedRecordIds
        if (submittedOrder == authoritativeOrder) return@submit
        val request = runCatching { plan.requestInOrder(submittedOrder) }.getOrElse { failure ->
            reorderError = failure.message ?: "The new order could not be submitted."
            orderedRecordIds = authoritativeOrder
            return@submit
        }
        reorderExecuting = true
        reorderError = null
        scope.launch {
            when (val result = actionExecutor.execute(request)) {
                is NativeActionExecutionResult.Success -> onActionSucceeded?.invoke(plan.action)
                is NativeActionExecutionResult.Failure -> {
                    reorderError = result.message
                    orderedRecordIds = authoritativeOrder
                    if (result.outcome.requiresMutationReconciliation()) {
                        onActionSucceeded?.invoke(plan.action)
                    }
                }
            }
            reorderExecuting = false
        }
    }
    LaunchedEffect(authoritativeOrder, reorder?.action?.id) {
        if (draggingRecordId == null && !reorderExecuting) {
            orderedRecordIds = authoritativeOrder
            reorderError = null
        }
    }
    fun moveDraggedRecord(position: Offset) {
        val recordId = draggingRecordId ?: return
        val targetId = orderedRecordIds.firstOrNull { id ->
            rowBounds[id]?.contains(position) == true
        }
        val targetIndex = targetId?.let(orderedRecordIds::indexOf) ?: -1
        if (targetIndex >= 0 && targetId != recordId) {
            orderedRecordIds = moveNativeCollectionRecordToIndex(
                orderedRecordIds = orderedRecordIds,
                recordId = recordId,
                targetIndex = targetIndex,
            )
        }
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
    val listState = rememberLazyListState()
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
            Column(
                modifier = Modifier.padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    NativeCategoryFilter.entries.forEach { option ->
                        val count = when (option) {
                            NativeCategoryFilter.All -> rows.size
                            NativeCategoryFilter.Expenses -> expenseCount
                            NativeCategoryFilter.Income -> incomeCount
                        }
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text("${option.label} $count") },
                        )
                    }
                    if (parentIds.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                expandedIds = if (expandedIds.isEmpty()) parentIds.toList() else emptyList()
                            },
                        ) {
                            Text(if (expandedIds.isEmpty()) "Expand all" else "Collapse all")
                        }
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
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
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
                val secondaryActions = nativeRecordCardActions(
                    capabilities = actions,
                    record = row.record,
                    onEditRecord = onEditRecord,
                    onDeleteRecord = onDeleteRecord,
                    onCommandRecord = onCommandRecord,
                    onCommandFormRecord = onCommandFormRecord,
                )
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
internal class NativeAuthoritativeRecordsKey(
    private val records: List<NativeRecord>,
) {
    override fun equals(other: Any?): Boolean =
        other is NativeAuthoritativeRecordsKey && records === other.records

    override fun hashCode(): Int = 0
}

internal data class NativeCompletionOverride(
    val completed: Boolean,
    val sourceRecordsKey: NativeAuthoritativeRecordsKey,
)

internal fun effectiveNativeCompletion(
    override: NativeCompletionOverride?,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    authoritativeCompleted: Boolean,
): Boolean = override
    ?.takeIf { candidate -> candidate.sourceRecordsKey == authoritativeRecordsKey }
    ?.completed
    ?: authoritativeCompleted

internal fun MutableMap<String, NativeCompletionOverride>.reconcileNativeCompletionOverrides(
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
) {
    keys.filter { recordId ->
        get(recordId)?.sourceRecordsKey != authoritativeRecordsKey
    }.forEach(::remove)
}

/**
 * Records a completion result whose server outcome is unknown until a later authoritative refresh.
 * The Boolean return value tells the UI whether it must request that refresh.
 */
internal fun MutableMap<String, NativeAuthoritativeRecordsKey>.recordNativeCompletionFailure(
    recordId: String,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
    outcome: NativeActionFailureOutcome,
): Boolean {
    if (!outcome.requiresMutationReconciliation()) {
        remove(recordId)
        return false
    }
    this[recordId] = authoritativeRecordsKey
    return true
}

internal fun Map<String, NativeAuthoritativeRecordsKey>.isNativeCompletionReconciling(
    recordId: String,
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
): Boolean = get(recordId) == authoritativeRecordsKey

internal fun MutableMap<String, NativeAuthoritativeRecordsKey>.reconcileNativeCompletionFailures(
    authoritativeRecordsKey: NativeAuthoritativeRecordsKey,
): Set<String> {
    val reconciledRecordIds = keys.filterTo(linkedSetOf()) { recordId ->
        get(recordId) != authoritativeRecordsKey
    }
    reconciledRecordIds.forEach(::remove)
    return reconciledRecordIds
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
    onLoadMore: (() -> Unit)?,
    loadingMore: Boolean,
    loadMoreError: String?,
) {
    val scope = rememberCoroutineScope()
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    val listState = rememberLazyListState()
    val authoritativeOrder = remember(rows) { rows.map { (record, _) -> record.id } }
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
    val rowBounds = remember(reorder?.action?.id, resource.id) { mutableStateMapOf<String, Rect>() }
    var listBounds by remember(reorder?.action?.id, resource.id) { mutableStateOf<Rect?>(null) }
    val displayedRows = remember(rowsById, orderedRecordIds, reorder) {
        if (reorder == null) rows else orderedRecordIds.mapNotNull(rowsById::get)
    }
    val submitReorder: () -> Unit = submit@{
        val plan = reorder ?: return@submit
        val submittedOrder = orderedRecordIds
        if (submittedOrder == authoritativeOrder) return@submit
        val request = runCatching { plan.requestInOrder(submittedOrder) }.getOrElse { failure ->
            reorderError = failure.message ?: "The new order could not be submitted."
            orderedRecordIds = authoritativeOrder
            return@submit
        }
        reorderExecuting = true
        reorderError = null
        scope.launch {
            when (val result = actionExecutor.execute(request)) {
                is NativeActionExecutionResult.Success -> onActionSucceeded?.invoke(plan.action)
                is NativeActionExecutionResult.Failure -> {
                    reorderError = result.message
                    orderedRecordIds = authoritativeOrder
                    if (result.outcome.requiresMutationReconciliation()) {
                        onActionSucceeded?.invoke(plan.action)
                    }
                }
            }
            reorderExecuting = false
        }
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
    LaunchedEffect(authoritativeOrder, reorder?.action?.id) {
        if (draggingRecordId == null && !reorderExecuting) {
            orderedRecordIds = authoritativeOrder
            reorderError = null
        }
    }
    fun moveDraggedRecord(position: Offset) {
        val recordId = draggingRecordId ?: return
        val targetId = orderedRecordIds.firstOrNull { id ->
            rowBounds[id]?.contains(position) == true
        }
        val targetIndex = targetId?.let(orderedRecordIds::indexOf) ?: -1
        if (targetIndex >= 0 && targetId != recordId) {
            orderedRecordIds = moveNativeCollectionRecordToIndex(
                orderedRecordIds = orderedRecordIds,
                recordId = recordId,
                targetIndex = targetIndex,
            )
        }
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
        reorderError?.let { message ->
            item(key = "task-reorder-error") {
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
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
                    reorder?.takeUnless { reorderExecuting }?.let {
                        NextcloudBoardDragHandle(
                            itemLabel = task.title,
                            dragActive = dragging,
                            onDragStart = { position ->
                                draggingRecordId = record.id
                                dragOrigin = position
                                dragPosition = position
                                reorderError = null
                            },
                            onDrag = { delta ->
                                val position = (dragPosition ?: return@NextcloudBoardDragHandle) + delta
                                dragPosition = position
                                orderedRecordIds = moveNativeCollectionRecordAcrossAdjacentMidpoint(
                                    orderedRecordIds = orderedRecordIds,
                                    recordId = record.id,
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
                    if (reorderExecuting) {
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
private fun GenericRecordBoard(
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
            when (val result = actionExecutor.execute(target.plan.request(values))) {
                is NativeActionExecutionResult.Success -> {
                    editTarget = null
                    actionMessage = "Update accepted. Refreshing the card..."
                    onActionSucceeded?.invoke(target.plan.action)
                }
                is NativeActionExecutionResult.Failure -> actionError = result.message
            }
            busyRecordId = null
        }
    }

    fun executeMove(target: NativeBoardMoveTargetSelection, destination: NativeBoardMoveTarget) {
        if (busyRecordId != null) return
        busyRecordId = target.record.id
        actionError = null
        scope.launch {
            when (val result = actionExecutor.execute(target.plan.request(destination.key))) {
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
                is NativeActionExecutionResult.Failure -> actionError = result.message
            }
            busyRecordId = null
        }
    }

    fun executeCreate(target: NativeBoardCreatePlan, title: String, description: String) {
        if (busyRecordId != null) return
        busyRecordId = BOARD_CREATE_BUSY_ID
        actionError = null
        scope.launch {
            when (val result = actionExecutor.execute(target.request(title, description))) {
                is NativeActionExecutionResult.Success -> {
                    createTarget = null
                    actionMessage = "Card created in ${target.lane.title}. Refreshing the board..."
                    onActionSucceeded?.invoke(target.action)
                }
                is NativeActionExecutionResult.Failure -> actionError = result.message
            }
            busyRecordId = null
        }
    }

    fun executeDirect(target: NativeBoardDirectActionTarget) {
        if (busyRecordId != null) return
        busyRecordId = target.record.id
        actionError = null
        scope.launch {
            when (val result = actionExecutor.execute(target.plan.request())) {
                is NativeActionExecutionResult.Success -> {
                    confirmTarget = null
                    actionMessage = "${target.plan.label} accepted. Refreshing the board..."
                    onActionSucceeded?.invoke(target.plan.action)
                }
                is NativeActionExecutionResult.Failure -> actionError = result.message
            }
            busyRecordId = null
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
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    boardBounds = coordinates.boundsInWindow()
                },
        ) {
            Row(
                modifier = Modifier.fillMaxSize().horizontalScroll(boardScrollState)
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
                        modifier = Modifier.width(284.dp).fillMaxHeight()
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

private data class NativeBoardEditTarget(
    val record: NativeRecord,
    val plan: NativeBoardEditPlan,
)

private data class NativeBoardMoveTargetSelection(
    val record: NativeRecord,
    val plan: NativeBoardMovePlan,
)

private data class NativeBoardDirectActionTarget(
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

@Composable
private fun GenericBoardCard(
    resource: ResourceSpec,
    record: NativeRecord,
    actions: NativeBoardCardActionPlan,
    busy: Boolean,
    dragging: Boolean,
    onOpen: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onMove: (() -> Unit)?,
    onDragStart: ((Offset) -> Unit)?,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onDirectAction: (NativeBoardDirectActionPlan) -> Unit,
) {
    val presentation = nativeRecordPresentation(resource, record)
    var actionMenuExpanded by remember(record.id) { mutableStateOf(false) }
    val menuActions = buildList {
        onEdit?.let { edit -> add(NextcloudCardAction("Edit", enabled = !busy, onClick = edit)) }
        onMove?.let { move -> add(NextcloudCardAction("Move", enabled = !busy, onClick = move)) }
        actions.directActions.forEach { plan ->
            add(
                NextcloudCardAction(
                    label = plan.label,
                    destructive = plan.kind == NativeBoardDirectActionKind.Delete,
                    enabled = !busy,
                    onClick = { onDirectAction(plan) },
                ),
            )
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = if (dragging) 0.18f else 1f
            }
            .nextcloudCardInteractions(
                onOpen = onOpen,
                onShowActions = if (menuActions.isNotEmpty()) {
                    { actionMenuExpanded = true }
                } else {
                    null
                },
                openLabel = "Open ${presentation.title}",
                actionsLabel = "Show actions for ${presentation.title}",
            ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                onDragStart?.let { startDrag ->
                    NextcloudBoardDragHandle(
                        itemLabel = presentation.title,
                        dragActive = dragging,
                        onDragStart = startDrag,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
                Text(
                    presentation.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                NextcloudCardOverflow(
                    itemLabel = presentation.title,
                    actions = menuActions,
                    expanded = actionMenuExpanded,
                    onExpandedChange = { actionMenuExpanded = it },
                )
            }
            presentation.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = NextcloudSpacing.XSmall),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NativeBoardCreateDialog(
    target: NativeBoardCreatePlan,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
) {
    var title by remember(target.lane.key, target.action.id) { mutableStateOf("") }
    var description by remember(target.lane.key, target.action.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add card to ${target.lane.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !busy,
                )
                if (target.descriptionBodyFieldName != null) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description") },
                        minLines = 3,
                        enabled = !busy,
                    )
                }
                Text(
                    "The new card will be created directly in this lane.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy && title.isNotBlank(),
                onClick = { onCreate(title, description) },
            ) {
                Text(if (busy) "Creating..." else "Create")
            }
        },
    )
}

@Composable
private fun NativeBoardDirectActionDialog(
    target: NativeBoardDirectActionTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val destructive = target.plan.kind == NativeBoardDirectActionKind.Delete
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${target.plan.label} ${nativeRecordTitle(target.record)}?") },
        text = {
            Text(
                if (destructive) {
                    "This removes the card from the server. Continue only if you are sure."
                } else {
                    "This updates the card on the server."
                },
            )
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy,
                colors = if (destructive) {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                },
                onClick = onConfirm,
            ) {
                Text(if (busy) "Working..." else target.plan.label)
            }
        },
    )
}

@Composable
private fun NativeBoardEditDialog(
    target: NativeBoardEditTarget,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var values by remember(target.record.id, target.plan.action.id) {
        mutableStateOf(target.plan.initialValues)
    }
    var errors by remember(target.record.id, target.plan.action.id) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${nativeRecordTitle(target.record)}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                target.plan.fields.forEach { editable ->
                    OutlinedTextField(
                        value = values[editable.field.id].orEmpty(),
                        onValueChange = { value ->
                            values = values + (editable.field.id to value)
                            errors = errors - editable.field.id
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(editable.field.label) },
                        minLines = if (editable.field.kind == FieldKind.longText) 4 else 1,
                        isError = editable.field.id in errors,
                        supportingText = errors[editable.field.id]?.let { message -> { Text(message) } },
                        enabled = !busy,
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val validation = target.plan.fields.mapNotNull { editable ->
                        validateNativeCellEdit(editable.field, values[editable.field.id].orEmpty())
                            ?.let { editable.field.id to it }
                    }.toMap()
                    if (validation.isEmpty()) onSave(values) else errors = validation
                },
            ) {
                Text(if (busy) "Saving..." else "Save")
            }
        },
    )
}

@Composable
private fun NativeBoardMoveDialog(
    target: NativeBoardMoveTargetSelection,
    busy: Boolean,
    onDismiss: () -> Unit,
    onMove: (NativeBoardMoveTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${nativeRecordTitle(target.record)}") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Text(
                    "Choose a destination lane. The board will refresh before the move is reported as confirmed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                target.plan.targets.forEach { destination ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        onClick = { onMove(destination) },
                    ) {
                        Text(destination.title)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private fun nativeRecordTitle(record: NativeRecord): String =
    listOf("title", "name", "subject").firstNotNullOfOrNull { expected ->
        record.values.entries.firstOrNull { it.key.equals(expected, ignoreCase = true) }
            ?.value
            ?.takeIf(String::isNotBlank)
    } ?: "card"

private const val BOARD_MOVE_VERIFICATION_TIMEOUT_MILLIS = 6_000L
private const val BOARD_CREATE_BUSY_ID = "__creating_board_card__"

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
private fun NativeCollectionPagingStatus(
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
    val detail = remember(resource, record) { nativeStructuredDetail(resource, record) }
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
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        DatasetMetricCard(
                            "Budgeted",
                            formatNativeFinanceAmount(plan.budgeted, currency),
                            Modifier.weight(1f),
                        )
                        DatasetMetricCard(
                            "Spent",
                            formatNativeFinanceAmount(plan.spent, currency),
                            Modifier.weight(1f),
                        )
                    }
                    DatasetMetricCard(
                        "Remaining",
                        formatNativeFinanceAmount(plan.remaining, currency),
                        Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    DatasetMetricCard("Budgeted", formatNativeFinanceAmount(plan.budgeted, currency))
                    DatasetMetricCard("Spent", formatNativeFinanceAmount(plan.spent, currency))
                    DatasetMetricCard("Remaining", formatNativeFinanceAmount(plan.remaining, currency))
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
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "${formatNativeFinanceAmount(category.spent, currency)} spent",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "${formatNativeFinanceAmount(category.remaining, currency)} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@OptIn(ExperimentalRichTextApi::class)
@Composable
internal fun GenericMailMessageDetail(
    schema: NativeAppSchema,
    resource: ResourceSpec,
    record: NativeRecord,
    message: NativeMailMessageDetailPresentation,
    datasetContext: NativeDatasetContext,
    actionExecutor: NativeActionExecutor,
    onActionSucceeded: ((ActionSpec) -> Unit)?,
    onInlineActionSucceeded: ((ActionSpec) -> Unit)?,
) {
    val structured = remember(resource, record) { nativeStructuredDetail(resource, record) }
    val threadMessages = remember(resource, record) { nativeMailThreadPresentations(resource, record) }
    val attachments = structured.sections.filter { section ->
        section.fieldId.lowercase().filter(Char::isLetterOrDigit) in setOf("attachments", "inlineattachments")
    }
    val attachmentItems = remember(attachments) { attachments.flatMap { section -> section.value.mailAttachments() } }
    val htmlBody = remember(message.body, message.htmlBody) {
        message.body?.takeIf { value -> message.htmlBody || value.contains('<') && value.contains('>') }
            ?.let(::sanitizeNativeMailHtml)
    }
    val richTextState = rememberRichTextState()
    LaunchedEffect(htmlBody) {
        if (!htmlBody.isNullOrBlank()) richTextState.setHtml(htmlBody)
    }
    val plainBody = remember(message.body, htmlBody) {
        message.body?.takeIf { htmlBody == null }?.trim()
    }
    val messageActions = remember(schema, resource, record, datasetContext) {
        nativeMailMessageActionPlan(schema, resource, record, datasetContext)
    }
    var runningAction by remember(schema, resource, record) {
        mutableStateOf<NativeMailMessageActionKind?>(null)
    }
    var pendingDestructiveAction by remember(schema, resource, record) {
        mutableStateOf<NativeMailMessageActionPlan?>(null)
    }
    var actionError by remember(schema, resource, record) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun executeMailAction(plan: NativeMailMessageActionPlan) {
        runningAction = plan.kind
        actionError = null
        scope.launch {
            when (val result = actionExecutor.execute(plan.request())) {
                is NativeActionExecutionResult.Success -> {
                    if (
                        plan.kind in setOf(
                            NativeMailMessageActionKind.Archive,
                            NativeMailMessageActionKind.Delete,
                        )
                    ) {
                        onActionSucceeded?.invoke(plan.action)
                    } else {
                        (onInlineActionSucceeded ?: onActionSucceeded)?.invoke(plan.action)
                    }
                }
                is NativeActionExecutionResult.Failure -> actionError = result.message
            }
            runningAction = null
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
    ) {
        Text(message.subject, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.extraLarge) {
                Icon(
                    NextcloudIcons.app("mail"),
                    contentDescription = null,
                    modifier = Modifier.padding(NextcloudSpacing.Medium).size(26.dp),
                    tint = NextcloudTheme.colors.appIcon,
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    nativeMailSenderLabel(message.sender) ?: "Unknown sender",
                    style = MaterialTheme.typography.titleMedium,
                )
                message.recipients?.let { recipients ->
                    Text(
                        "To $recipients",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            nativeMailTimestampLabel(message.timestamp)?.let { timestamp ->
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (messageActions.all.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                messageActions.all.forEach { plan ->
                    OutlinedButton(
                        enabled = runningAction == null,
                        onClick = {
                            if (plan.kind == NativeMailMessageActionKind.Delete) {
                                pendingDestructiveAction = plan
                            } else {
                                executeMailAction(plan)
                            }
                        },
                    ) {
                        if (runningAction == plan.kind) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(plan.label)
                        }
                    }
                }
            }
            actionError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (threadMessages.size > 1) {
            Text(
                "${threadMessages.size} messages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            threadMessages.forEach { threadMessage ->
                GenericMailThreadMessage(threadMessage)
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                SelectionContainer {
                    if (!htmlBody.isNullOrBlank()) {
                        RichText(
                            state = richTextState,
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(
                            plainBody?.takeIf(String::isNotBlank) ?: "This message has no readable body.",
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        if (message.attachmentCount > 0 || attachmentItems.isNotEmpty()) {
            Text("Attachments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        attachmentItems.forEach { attachment ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Icon(NextcloudIcons.File, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(attachment.name, style = MaterialTheme.typography.titleSmall)
                        listOfNotNull(attachment.mime, attachment.size).joinToString(" · ")
                            .takeIf(String::isNotBlank)?.let { metadata ->
                                Text(
                                    metadata,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }
                }
            }
        }
        if (attachmentItems.isEmpty()) {
            attachments.forEach { section -> GenericStructuredDetailSection(section) }
        }
    }
    pendingDestructiveAction?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDestructiveAction = null },
            title = { Text("Delete this message?") },
            text = { Text("This removes the message from the mail server. This action may not be reversible.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDestructiveAction = null
                        executeMailAction(plan)
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDestructiveAction = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun GenericMailThreadMessage(message: NativeMailMessageDetailPresentation) {
    val htmlBody = remember(message.body, message.htmlBody) {
        message.body?.takeIf { value -> message.htmlBody || value.contains('<') && value.contains('>') }
            ?.let(::sanitizeNativeMailHtml)
    }
    val richTextState = rememberRichTextState()
    LaunchedEffect(htmlBody) {
        if (!htmlBody.isNullOrBlank()) richTextState.setHtml(htmlBody)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    nativeMailSenderLabel(message.sender) ?: "Unknown sender",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                nativeMailTimestampLabel(message.timestamp)?.let { timestamp ->
                    Text(
                        timestamp,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                if (!htmlBody.isNullOrBlank()) {
                    RichText(
                        state = richTextState,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        message.body?.takeIf(String::isNotBlank) ?: "This message has no readable body.",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

private data class NativeMailAttachment(val name: String, val mime: String?, val size: String?)

private fun NativeStructuredValue.mailAttachments(): List<NativeMailAttachment> = when (this) {
    is NativeStructuredValue.ListValue -> items.flatMap(NativeStructuredValue::mailAttachments)
    is NativeStructuredValue.ObjectValue -> {
        val values = entries.associate { entry ->
            entry.key.lowercase().filter(Char::isLetterOrDigit) to entry.value.scalarText()
        }
        val name = listOf("filename", "name", "title").firstNotNullOfOrNull(values::get)
        if (name.isNullOrBlank()) emptyList() else listOf(
            NativeMailAttachment(
                name = name,
                mime = listOf("mime", "mimetype", "contenttype").firstNotNullOfOrNull(values::get),
                size = listOf("size", "filesize", "bytes").firstNotNullOfOrNull(values::get)
                    ?.toLongOrNull()
                    ?.formatNativeByteSize(),
            ),
        )
    }
    is NativeStructuredValue.Scalar -> emptyList()
}

private fun NativeStructuredValue.scalarText(): String? = when (this) {
    is NativeStructuredValue.Scalar -> value
    else -> null
}

private fun Long.formatNativeByteSize(): String = when {
    this >= 1_048_576 -> "${(this / 104_857.6).toLong() / 10.0} MB"
    this >= 1_024 -> "${(this / 102.4).toLong() / 10.0} KB"
    else -> "$this B"
}

/** Converts untrusted mail HTML into readable inert text without executing or embedding it. */
internal fun emailBodyToPlainText(html: String): String {
    val output = StringBuilder(html.length.coerceAtMost(64_000))
    var cursor = 0
    var hiddenTag: String? = null
    while (cursor < html.length && output.length < 64_000) {
        if (html[cursor] != '<') {
            if (hiddenTag == null) output.append(html[cursor])
            cursor += 1
            continue
        }
        val close = html.indexOf('>', startIndex = cursor + 1)
        if (close < 0) {
            if (hiddenTag == null) output.append(html[cursor])
            cursor += 1
            continue
        }
        val rawTag = html.substring(cursor + 1, close).trim().lowercase()
        val closing = rawTag.startsWith('/')
        val tagName = rawTag.removePrefix("/").takeWhile { character ->
            character.isLetterOrDigit() || character == '-'
        }
        when {
            hiddenTag != null && closing && tagName == hiddenTag -> hiddenTag = null
            hiddenTag != null -> Unit
            !closing && tagName in setOf("script", "style", "head") -> hiddenTag = tagName
            tagName in setOf("br", "p", "div", "li", "tr", "h1", "h2", "h3", "h4", "blockquote") -> {
                if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
            }
        }
        cursor = close + 1
    }
    return output.toString()
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .lines()
        .map(String::collapseMailWhitespace)
        .dropWhile(String::isBlank)
        .dropLastWhile(String::isBlank)
        .joinToString("\n")
}

private fun String.collapseMailWhitespace(): String {
    val output = StringBuilder(length)
    var previousWhitespace = false
    trim().forEach { character ->
        val whitespace = character == ' ' || character == '\t' || character == '\r'
        if (!whitespace || !previousWhitespace) output.append(if (whitespace) ' ' else character)
        previousWhitespace = whitespace
    }
    return output.toString()
}

@Composable
private fun GenericStructuredDetailSection(section: NativeStructuredDetailSection) {
    Text(
        section.label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        GenericStructuredValue(section.value, ordered = section.ordered)
    }
}

@Composable
private fun GenericStructuredValue(
    value: NativeStructuredValue,
    ordered: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (value) {
        is NativeStructuredValue.Scalar -> Text(
            value.structuredDisplayValue(),
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge,
        )
        is NativeStructuredValue.ListValue -> Column(modifier = modifier.fillMaxWidth()) {
            value.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Large,
                        vertical = NextcloudSpacing.Medium,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        if (ordered) "${index + 1}." else "•",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    GenericStructuredValue(item, modifier = Modifier.weight(1f))
                }
            }
            if (value.omittedItems > 0) GenericStructuredOmission(value.omittedItems)
        }
        is NativeStructuredValue.ObjectValue -> Column(modifier = modifier.fillMaxWidth()) {
            value.entries.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    Text(
                        entry.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GenericStructuredValue(entry.value)
                }
            }
            if (value.omittedEntries > 0) GenericStructuredOmission(value.omittedEntries)
        }
    }
}

@Composable
private fun GenericStructuredOmission(count: Int) {
    Text(
        "+$count more not shown",
        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun GenericResourceIcon(
    resource: ResourceSpec,
    recordIconKey: String? = null,
    recordColorArgb: Int? = null,
    large: Boolean = false,
) {
    val dense = LocalNextcloudWorkspaceCapabilities.current.usesDenseControls
    val icon = recordIconKey?.let(NextcloudIcons::semanticOrFallback)
        ?: nativeResourceIconAppId(resource)?.let(NextcloudIcons::app)
        ?: when {
        resource.fields.any { it.kind == FieldKind.image } -> NextcloudIcons.Image
        resource.fields.any { it.kind == FieldKind.file } -> NextcloudIcons.File
        resource.fields.any { it.kind == FieldKind.date || it.kind == FieldKind.dateTime } -> NextcloudIcons.Calendar
        resource.fields.any { it.kind == FieldKind.userReference } -> NextcloudIcons.People
        else -> NextcloudIcons.Apps
    }
    Surface(color = NextcloudTheme.colors.appIconContainer, shape = MaterialTheme.shapes.small) {
        Icon(
            icon,
            contentDescription = null,
            tint = recordColorArgb?.let(::Color) ?: NextcloudTheme.colors.appIcon,
            modifier = Modifier.padding(
                when {
                    large -> NextcloudSpacing.Medium
                    dense -> 6.dp
                    else -> NextcloudSpacing.Small
                },
            ).size(
                when {
                    large -> 30.dp
                    dense -> 20.dp
                    else -> 24.dp
                },
            ),
        )
    }
}

/**
 * Picks an app-style icon from resource semantics, independent of which Nextcloud app exposed it.
 * Field-shape fallbacks remain useful for unfamiliar resources, but semantic nouns must win: a
 * recipe that happens to contain dates is still a recipe, and a message with attachments is still
 * mail. Exact token matching avoids accidental matches such as `card` inside `discarded`.
 */
internal fun nativeResourceIconAppId(resource: ResourceSpec): String? {
    val tokens = buildSet {
        addAll(resource.id.nativeSemanticTokens())
        addAll(resource.name.nativeSemanticTokens())
    }
    return when {
        tokens.any { it in setOf("recipe", "recipes", "cookbook") } -> "cookbook"
        tokens.any { it in setOf("message", "messages", "mail", "mailbox", "mailboxes", "email", "emails") } -> "mail"
        tokens.any { it in setOf("song", "songs", "track", "tracks", "artist", "artists", "playlist", "playlists", "music") } -> "music"
        tokens.any { it in setOf("board", "boards", "card", "cards", "stack", "stacks", "deck") } -> "deck"
        tokens.any { it in setOf("table", "tables", "row", "rows", "column", "columns") } -> "tables"
        tokens.any { it in setOf("expense", "expenses", "payment", "payments", "transaction", "transactions", "budget", "budgets", "bill", "bills") } -> "cospend"
        tokens.any { it in setOf("file", "files", "folder", "folders", "directory", "directories") } -> "files"
        tokens.any { it in setOf("photo", "photos", "image", "images", "album", "albums", "memory", "memories") } -> "photos"
        tokens.any { it in setOf("conversation", "conversations", "chat", "chats", "room", "rooms", "talk") } -> "talk"
        tokens.any { it in setOf("task", "tasks", "todo", "todos") } -> "tasks"
        tokens.any { it in setOf("note", "notes") } -> "notes"
        tokens.any { it in setOf("contact", "contacts", "addressbook", "addressbooks") } -> "contacts"
        tokens.any { it in setOf("event", "events", "calendar", "calendars") } -> "calendar"
        else -> null
    }
}

private fun String.nativeSemanticTokens(): Set<String> {
    val tokens = linkedSetOf<String>()
    val token = StringBuilder()
    fun flush() {
        if (token.isNotEmpty()) {
            tokens += token.toString().lowercase()
            token.clear()
        }
    }
    forEachIndexed { index, character ->
        val previous = getOrNull(index - 1)
        val startsCamelWord = character.isUpperCase() && previous?.isLowerCase() == true
        if (startsCamelWord || !character.isLetterOrDigit()) flush()
        if (character.isLetterOrDigit()) token.append(character)
    }
    flush()
    return tokens
}

@Composable
private fun GenericNativeForm(
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
                    .filter { field -> nativeRelationFieldRequiresChoice(field, formResource, schema) }
                    .mapTo(linkedSetOf(), FieldSpec::id),
            )
        }
    val uneditableBodyFieldIds = uneditableNativeBodyFieldIds(
        action = action,
        editableFields = fields,
        autoBoundValues = autoBoundValues,
    )
    val hasUneditableBodyFields = uneditableBodyFieldIds.isNotEmpty()
    val settingsWrite = action.isSettingsWrite(resource)
    val hasChanges = draft.hasChangesFrom(initialDraft)
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
                            val relationOptions =
                                nativeRelationOptions(field, formResource, schema, datasetContext)
                            if (nativeRelationFieldRequiresChoice(field, formResource, schema)) {
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
                            coordinator.submit(
                                values = draft.values,
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

/**
 * A nested form may expose an observed child row while the selected parent record carries the
 * relationship identity required by the action. Use the parent for hidden relationship binding,
 * while the observed row remains available separately for safe form prefilling.
 */
internal fun nativeFormBindingRecord(
    initialRecord: NativeRecord?,
    parentResourceId: String?,
    parentRecord: NativeRecord?,
): NativeRecord? =
    if (parentResourceId.isNullOrBlank()) initialRecord else parentRecord ?: initialRecord

/**
 * A selected parent record supplies relationship identities to a child create action, but its
 * user-authored fields are not defaults for the new child. Creating a nested child must not copy
 * the parent's title or other editable content into the new record.
 */
internal fun nativeFormPrefillRecord(
    action: ActionSpec,
    resource: ResourceSpec,
    record: NativeRecord?,
    parentResourceId: String? = null,
): NativeRecord? {
    record ?: return null
    if (action.intent != ActionIntent.create || parentResourceId.isNullOrBlank()) return record
    val parentIdentities = parentResourceId.nativeRelationResourceIdentities()
    val formIdentities = resource.id.nativeRelationResourceIdentities()
    return record.takeIf { parentIdentities.intersect(formIdentities).isNotEmpty() }
}

/**
 * Resolves already-known technical identities without exposing them as text inputs.
 * Destination fields remain user choices: source context is never destination intent.
 */
internal fun nativeFormAutoBoundValues(
    schema: NativeAppSchema,
    action: ActionSpec,
    resource: ResourceSpec,
    record: NativeRecord?,
    parentResourceId: String? = null,
): Map<String, String> = nativeFormAutoBindingResolution(
    schema = schema,
    action = action,
    resource = resource,
    record = record,
    parentResourceId = parentResourceId,
).values

internal data class NativeFormAutoBindingResolution(
    val values: Map<String, String>,
    val error: String? = null,
)

/**
 * Resolves schema-declared technical inputs from verified navigation and record provenance.
 *
 * No app, endpoint, or domain vocabulary participates. Conflicting identities leave the action
 * disabled instead of silently selecting one source. User-selected destinations remain visible.
 */
internal fun nativeFormAutoBindingResolution(
    schema: NativeAppSchema,
    action: ActionSpec,
    resource: ResourceSpec,
    record: NativeRecord?,
    parentResourceId: String? = null,
    navigationValues: Map<String, String> = emptyMap(),
): NativeFormAutoBindingResolution {
    val declaredBindingNames = buildSet {
        addAll(action.binding.pathParameterNames)
        addAll(action.binding.queryParameterNames)
        addAll(action.binding.bodyFieldNames)
    }
    val declaredNavigationValues = navigationValues.filterKeys(declaredBindingNames::contains)
    val available = when (record) {
        null -> safeActionBindingValues(declaredNavigationValues)
        else -> {
            if (!record.actionBindingProvenanceValid) {
                return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected item's identity provenance is ambiguous.",
                )
            }
            val recordValues = record.nativeFormDeclaredBindingValues(declaredBindingNames)
                ?: return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected item contains conflicting declared identities.",
                )
            safeActionBindingValues(recordValues, declaredNavigationValues)
                ?: return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected item no longer matches the navigation context.",
                )
        }
    } ?: return NativeFormAutoBindingResolution(
        values = emptyMap(),
        error = "This action cannot be linked because its navigation context is invalid.",
    )
    if (
        schema.resources.count { candidate -> candidate.id == resource.id } != 1 ||
        schema.actions.count { candidate -> candidate.id == action.id && candidate == action } != 1
    ) {
        return NativeFormAutoBindingResolution(
            values = emptyMap(),
            error = "This action cannot be linked because its schema contract is ambiguous.",
        )
    }
    val acceptedRelationships = schema.relationships.filter { relationship ->
        relationship.childResourceId == resource.id &&
            relationship.parentResourceId == parentResourceId &&
            relationship.childFieldId != null &&
            relationship.confidence == Confidence.verified
    }
    if (
        acceptedRelationships.groupBy { relationship -> relationship.childFieldId }
            .any { (_, relationships) -> relationships.distinct().size > 1 }
    ) {
        return NativeFormAutoBindingResolution(
            values = emptyMap(),
            error = "This action cannot be linked because its parent relationship is ambiguous.",
        )
    }
    val acceptedRelationshipFieldIds = acceptedRelationships.mapNotNullTo(mutableSetOf()) { relationship ->
        relationship.childFieldId
    }
    val requiredInputFieldNames = ((action.inputSchema as? JsonObject)?.get("required") as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
        .orEmpty()
        .toSet()
    val resolved = linkedMapOf<String, String>()
    if (action.intent == ActionIntent.create) {
        acceptedRelationships.distinct().forEach { relationship ->
            val childFieldId = requireNotNull(relationship.childFieldId)
            if (childFieldId !in declaredBindingNames) return@forEach
            val parentValues = record
                ?.takeIf { parent ->
                    parent.actionSafeIdentity && parent.actionBindingProvenanceValid
                }
                ?.safeActionBindingValues()
                ?.get(relationship.parentFieldId)
                ?.let(::listOf)
                .orEmpty()
            val exactValues = buildList {
                declaredNavigationValues[childFieldId]?.takeIf(String::isNotBlank)?.let(::add)
                addAll(parentValues.filter(String::isNotBlank))
            }.distinct()
            if (exactValues.size > 1) {
                return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be safely linked to the selected parent because its identities conflict.",
                )
            }
            val value = exactValues.singleOrNull()
            if (
                value == null &&
                childFieldId in (
                    action.binding.pathParameterNames +
                        action.binding.requiredQueryParameterNames +
                        action.binding.requiredBodyFieldNames +
                        requiredInputFieldNames
                    )
            ) {
                return NativeFormAutoBindingResolution(
                    values = emptyMap(),
                    error = "This action cannot be linked because the selected parent identity could not be verified.",
                )
            }
            value?.let { resolved[childFieldId] = it }
        }
    }
    if (action.intent != ActionIntent.create) {
        val requiredBindingNames = buildSet {
            addAll(action.binding.requiredPathParameterNames)
            addAll(action.binding.requiredQueryParameterNames)
            addAll(action.binding.requiredBodyFieldNames)
        }
        resource.fields.asSequence()
            .filter { field ->
                val normalized = field.id.nativeRelationSemanticId()
                field.id in requiredBindingNames &&
                    field.id !in acceptedRelationshipFieldIds &&
                    normalized.length > 2 &&
                    normalized.endsWith("id")
            }
            .forEach { field ->
                available[field.id]
                    ?.takeIf(String::isNotBlank)
                    ?.let { value -> resolved[field.id] = value }
            }
    }
    val technicalParameterNames = (
        action.binding.pathParameterNames + action.binding.queryParameterNames
        ).distinct()
    technicalParameterNames.forEach { parameterName ->
        if (parameterName in resolved) return@forEach
        if (
            action.intent == ActionIntent.create &&
            parentResourceId != null &&
            parameterName.nativeRelationSemanticId().isIdentityForNativeParent(parentResourceId) &&
            parameterName !in acceptedRelationshipFieldIds
        ) {
            declaredNavigationValues[parameterName]
                ?.takeIf(String::isNotBlank)
                ?.let { value -> resolved[parameterName] = value }
            return@forEach
        }
        val exactCandidates = available[parameterName]
            ?.takeIf(String::isNotBlank)
            ?.let(::listOf)
            .orEmpty()
        val canonicalRecordIdentity = record
            ?.takeIf {
                it.actionSafeIdentity &&
                    it.actionBindingProvenanceValid &&
                    parameterName.nativeRelationSemanticId().isIdentityForNativeParent(resource.id)
            }
            ?.id
            ?.takeIf(String::isNotBlank)
        val candidates = (exactCandidates + listOfNotNull(canonicalRecordIdentity)).distinct()
        if (candidates.size > 1) {
            return NativeFormAutoBindingResolution(
                values = emptyMap(),
                error = "This action cannot be safely linked because the required identity is ambiguous.",
            )
        }
        candidates.singleOrNull()?.let { value -> resolved[parameterName] = value }
    }
    return NativeFormAutoBindingResolution(values = resolved)
}

private fun NativeRecord.nativeFormDeclaredBindingValues(
    declaredBindingNames: Set<String>,
): Map<String, String>? {
    val declaredSemanticNames = declaredBindingNames
        .mapTo(linkedSetOf()) { name -> name.nativeRelationSemanticId() }
    val declaredContext = bindingContext.filterKeys { key ->
        key.nativeRelationSemanticId() in declaredSemanticNames
    }
    val declaredObservedValues = values.mapNotNull { (key, value) ->
        val semanticKey = key.nativeRelationSemanticId()
        value
            ?.takeIf {
                key !in structuredValues &&
                    semanticKey in declaredSemanticNames &&
                    semanticKey != "id"
            }
            ?.let { key to it }
    }.toMap()
    val canonicalIdentity = if (actionSafeIdentity) {
        declaredBindingNames
            .filter { name -> name.nativeRelationSemanticId() == "id" }
            .associateWith { id }
    } else {
        emptyMap()
    }
    return safeActionBindingValues(
        declaredContext,
        declaredObservedValues,
        canonicalIdentity,
    )
}

private fun String.nativeRelationSemanticId(): String = lowercase().filter(Char::isLetterOrDigit)

private fun String.isIdentityForNativeParent(parentResourceId: String): Boolean {
    if (length <= 2 || !endsWith("id")) return false
    return dropLast(2).nativeRelationResourceIdentities()
        .intersect(parentResourceId.nativeRelationResourceIdentities())
        .isNotEmpty()
}

private fun String.nativeRelationResourceIdentities(): Set<String> {
    val normalized = nativeRelationSemanticId()
    return buildSet {
        add(normalized)
        if (normalized.endsWith('s') && normalized.length > 1) add(normalized.dropLast(1))
        if (normalized.endsWith("ies") && normalized.length > 3) add(normalized.dropLast(3) + "y")
        if (
            normalized.endsWith("ches") ||
            normalized.endsWith("shes") ||
            normalized.endsWith("sses") ||
            normalized.endsWith("xes") ||
            normalized.endsWith("zes")
        ) {
            add(normalized.dropLast(2))
        }
    }
}

internal fun nativeScalarRelationClearChoice(field: FieldSpec): NativeRelationOption? =
    NativeRelationOption(
        value = "",
        label = "None",
        supportingText = "Clear selection",
    ).takeIf {
        !field.required &&
            field.format !in setOf(
                DYNAMIC_INTEGER_ARRAY_FORMAT,
                DYNAMIC_STRING_ARRAY_FORMAT,
                DYNAMIC_STRING_LIST_FORMAT,
            )
    }

internal data class NativeRelationOptionWindow(
    val options: List<NativeRelationOption>,
    val hasMore: Boolean,
)

internal fun nativeRelationOptionWindow(
    options: List<NativeRelationOption>,
    query: String,
): NativeRelationOptionWindow {
    val boundedQuery = query.take(NATIVE_RELATION_MAX_QUERY_LENGTH)
    val matches = options.asSequence()
        .filter { option ->
            boundedQuery.isBlank() ||
                option.label.contains(boundedQuery, ignoreCase = true) ||
                option.supportingText?.contains(boundedQuery, ignoreCase = true) == true
        }
        .take(NATIVE_RELATION_OPTION_WINDOW_SIZE + 1)
        .toList()
    return NativeRelationOptionWindow(
        options = matches.take(NATIVE_RELATION_OPTION_WINDOW_SIZE),
        hasMore = matches.size > NATIVE_RELATION_OPTION_WINDOW_SIZE,
    )
}

internal fun retainSelectedNativeRelationOptions(
    retained: List<NativeRelationOption>,
    available: List<NativeRelationOption>,
    selectedValues: Collection<String>,
): List<NativeRelationOption> {
    val selected = selectedValues.asSequence()
        .filter(String::isNotBlank)
        .distinct()
        .take(NATIVE_RELATION_RETAINED_SELECTION_LIMIT)
        .toSet()
    if (selected.isEmpty()) return emptyList()
    return (available + retained).asSequence()
        .filter { option -> option.value in selected }
        .distinctBy(NativeRelationOption::value)
        .take(NATIVE_RELATION_RETAINED_SELECTION_LIMIT)
        .toList()
}

@Composable
private fun GenericRelationshipField(
    field: FieldSpec,
    value: String,
    options: List<NativeRelationOption>,
    choicesLoaded: Boolean,
    choiceSourceHasRecords: Boolean,
    choiceUnavailableReason: NativeRelationChoiceUnavailableReason?,
    paging: NativeRelatedRecordPaging?,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    val displayField = field.copy(label = field.nativeRelationshipDisplayLabel())
    val clearChoice = nativeScalarRelationClearChoice(displayField)
    when {
        options.isEmpty() && clearChoice == null && paging == null ->
            GenericUnavailableRelationField(displayField, error)
        field.format in setOf(DYNAMIC_INTEGER_ARRAY_FORMAT, DYNAMIC_STRING_ARRAY_FORMAT) ->
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
                GenericRelationMultiPicker(
                    displayField,
                    value,
                    options,
                    choicesLoaded,
                    choiceSourceHasRecords,
                    choiceUnavailableReason,
                    paging,
                    error,
                    enabled,
                    onValueChange,
                )
                if (options.isEmpty() && choiceSourceHasRecords) {
                    GenericRelationUnavailableReason(displayField, choiceUnavailableReason)
                }
            }
        else -> Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
            GenericRelationPicker(
                field = displayField,
                value = value,
                options = options,
                clearChoice = clearChoice,
                choicesLoaded = choicesLoaded,
                choiceSourceHasRecords = choiceSourceHasRecords,
                choiceUnavailableReason = choiceUnavailableReason,
                paging = paging,
                error = error,
                enabled = enabled,
                onValueChange = onValueChange,
            )
            if (options.isEmpty() && choiceSourceHasRecords) {
                GenericRelationUnavailableReason(displayField, choiceUnavailableReason)
            }
        }
    }
}

@Composable
private fun GenericRelationUnavailableReason(
    field: FieldSpec,
    reason: NativeRelationChoiceUnavailableReason?,
) {
    Text(
        reason.nativeRelationUnavailableMessage(field),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun nativeRelationPaging(
    field: FieldSpec,
    formResource: ResourceSpec,
    schema: NativeAppSchema,
    context: NativeDatasetContext,
): NativeRelatedRecordPaging? = nativeRelationRelationship(field, formResource, schema)
    ?.parentResourceId
    ?.let(context.relatedRecordPaging::get)

@Composable
private fun GenericUnavailableRelationField(field: FieldSpec, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(requiredFieldLabel(field), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .semantics {
                    contentDescription = "Choose ${field.id}"
                },
        ) {
            Text("No verified choices available", modifier = Modifier.weight(1f))
        }
        Text(
            error ?: if (field.required) {
                "Create or load a server record before choosing this required value."
            } else {
                "No choices are available. This optional value will be left empty."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun FieldSpec.nativeRelationshipDisplayLabel(): String {
    val trimmed = label.trim()
    return when {
        trimmed.endsWith(" ids", ignoreCase = true) -> trimmed.dropLast(4)
        trimmed.endsWith(" id", ignoreCase = true) -> trimmed.dropLast(3)
        else -> trimmed
    }.ifBlank { label }
}

@Composable
private fun GenericRelationPicker(
    field: FieldSpec,
    value: String,
    options: List<NativeRelationOption>,
    clearChoice: NativeRelationOption?,
    choicesLoaded: Boolean,
    choiceSourceHasRecords: Boolean,
    choiceUnavailableReason: NativeRelationChoiceUnavailableReason?,
    paging: NativeRelatedRecordPaging?,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var query by rememberSaveable(field.id) { mutableStateOf("") }
    var retainedSelection by remember(field.id) {
        mutableStateOf<List<NativeRelationOption>>(emptyList())
    }
    LaunchedEffect(value, options) {
        retainedSelection = retainSelectedNativeRelationOptions(
            retained = retainedSelection,
            available = options,
            selectedValues = listOf(value),
        )
    }
    val displayedOptions = remember(options, retainedSelection) {
        (options + retainedSelection).distinctBy(NativeRelationOption::value)
    }
    val selected = displayedOptions.firstOrNull { option -> option.value == value }
    val optionWindow = nativeRelationOptionWindow(displayedOptions, query)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(
            requiredFieldLabel(field),
            style = MaterialTheme.typography.labelLarge,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics {
                        contentDescription = buildString {
                            append("Choose ${field.id}")
                            append("; relation loaded ")
                            append(choicesLoaded)
                            append("; relation options ")
                            append(options.size)
                            if (options.isEmpty()) {
                                append("; relation reason ")
                                append(choiceUnavailableReason?.name ?: "unknown")
                            }
                        }
                    },
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        selected?.label
                            ?: clearChoice?.label?.takeIf { value.isBlank() }
                            ?: "Select ${field.label}",
                    )
                    selected?.supportingText?.let { supportingText ->
                        Text(
                            supportingText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                if (displayedOptions.size > NATIVE_RELATION_SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(NATIVE_RELATION_MAX_QUERY_LENGTH) },
                        modifier = Modifier.padding(
                            horizontal = NextcloudSpacing.Small,
                            vertical = NextcloudSpacing.XSmall,
                        ).widthIn(min = 280.dp).semantics {
                            contentDescription = "Search relations for ${field.id}"
                        },
                        label = { Text("Search ${field.label.lowercase()}") },
                        singleLine = true,
                    )
                    GenericRelationSearchGuidance(
                        totalOptionCount = displayedOptions.size,
                        discardedChoiceCount = paging?.discardedChoiceCount ?: 0,
                        query = query,
                        window = optionWindow,
                    )
                }
                clearChoice?.let { choice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(choice.label)
                                choice.supportingText?.let { supportingText ->
                                    Text(
                                        supportingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(choice.value)
                            expanded = false
                            query = ""
                        },
                    )
                    if (optionWindow.options.isNotEmpty()) HorizontalDivider()
                }
                if (optionWindow.options.isEmpty() && paging?.loading != true) {
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = when {
                                !choicesLoaded -> "Relation choices unavailable for ${field.id}"
                                choiceSourceHasRecords ->
                                    "No usable relation choices for ${field.id} " +
                                        "reason ${choiceUnavailableReason?.name ?: "unknown"}"
                                else -> "No relation choices for ${field.id}"
                            }
                        },
                        text = {
                            Text(
                                when {
                                    !choicesLoaded -> "${field.label} choices could not be loaded."
                                    choiceSourceHasRecords ->
                                        choiceUnavailableReason.nativeRelationUnavailableMessage(field)
                                    else -> "No ${field.label.lowercase()} choices are available."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
                optionWindow.options.forEach { option ->
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = "Choose ${field.id} relation ${option.label}"
                        },
                        text = {
                            Column {
                                Text(option.label)
                                option.supportingText?.let { supportingText ->
                                    Text(
                                        supportingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            onValueChange(option.value)
                            expanded = false
                            query = ""
                        },
                    )
                }
                GenericRelationPagingItem(paging)
            }
        }
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun NativeRelationChoiceUnavailableReason?.nativeRelationUnavailableMessage(
    field: FieldSpec,
): String = when (this) {
    NativeRelationChoiceUnavailableReason.unsafeIdentity ->
        "The server returned ${field.label.lowercase()} records without a verified selectable identity."
    NativeRelationChoiceUnavailableReason.ambiguousBinding ->
        "The server returned ${field.label.lowercase()} records with conflicting identity data."
    NativeRelationChoiceUnavailableReason.scopeMismatch ->
        "The available ${field.label.lowercase()} records belong to a different parent."
    NativeRelationChoiceUnavailableReason.invalidValue ->
        "The available ${field.label.lowercase()} records do not contain a safe selectable value."
    NativeRelationChoiceUnavailableReason.duplicateValue ->
        "The available ${field.label.lowercase()} records contain duplicate selectable values."
    else ->
        "The available ${field.label.lowercase()} records cannot be selected safely."
}

@Composable
private fun GenericRelationMultiPicker(
    field: FieldSpec,
    value: String,
    options: List<NativeRelationOption>,
    choicesLoaded: Boolean,
    choiceSourceHasRecords: Boolean,
    choiceUnavailableReason: NativeRelationChoiceUnavailableReason?,
    paging: NativeRelatedRecordPaging?,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var query by rememberSaveable(field.id) { mutableStateOf("") }
    val selectedValues = remember(value, field.format) {
        value.nativeRelationSelectedValues(field.format)
    }
    var retainedSelections by remember(field.id) {
        mutableStateOf<List<NativeRelationOption>>(emptyList())
    }
    LaunchedEffect(selectedValues, options) {
        retainedSelections = retainSelectedNativeRelationOptions(
            retained = retainedSelections,
            available = options,
            selectedValues = selectedValues,
        )
    }
    val displayedOptions = remember(options, retainedSelections) {
        (options + retainedSelections).distinctBy(NativeRelationOption::value)
    }
    val optionWindow = nativeRelationOptionWindow(displayedOptions, query)
    val selectedLabels = displayedOptions.filter { option -> option.value in selectedValues }
        .joinToString(", ", transform = NativeRelationOption::label)
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(requiredFieldLabel(field), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .semantics {
                        contentDescription = buildString {
                            append("Choose ${field.id}")
                            append("; relation loaded ")
                            append(choicesLoaded)
                            append("; relation options ")
                            append(options.size)
                            if (options.isEmpty()) {
                                append("; relation reason ")
                                append(choiceUnavailableReason?.name ?: "unknown")
                            }
                        }
                    },
            ) {
                Text(
                    selectedLabels.ifBlank { "Choose ${field.label.lowercase()}" },
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                if (displayedOptions.size > NATIVE_RELATION_SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(NATIVE_RELATION_MAX_QUERY_LENGTH) },
                        modifier = Modifier.padding(
                            horizontal = NextcloudSpacing.Small,
                            vertical = NextcloudSpacing.XSmall,
                        ).widthIn(min = 280.dp).semantics {
                            contentDescription = "Search relations for ${field.id}"
                        },
                        label = { Text("Search ${field.label.lowercase()}") },
                        singleLine = true,
                    )
                    GenericRelationSearchGuidance(
                        totalOptionCount = displayedOptions.size,
                        discardedChoiceCount = paging?.discardedChoiceCount ?: 0,
                        query = query,
                        window = optionWindow,
                    )
                }
                if (optionWindow.options.isEmpty() && paging?.loading != true) {
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = when {
                                !choicesLoaded -> "Relation choices unavailable for ${field.id}"
                                choiceSourceHasRecords ->
                                    "No usable relation choices for ${field.id} " +
                                        "reason ${choiceUnavailableReason?.name ?: "unknown"}"
                                else -> "No relation choices for ${field.id}"
                            }
                        },
                        text = {
                            Text(
                                when {
                                    !choicesLoaded -> "${field.label} choices could not be loaded."
                                    choiceSourceHasRecords ->
                                        choiceUnavailableReason.nativeRelationUnavailableMessage(field)
                                    else -> "No ${field.label.lowercase()} choices are available."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        enabled = false,
                        onClick = {},
                    )
                }
                optionWindow.options.forEach { option ->
                    val selected = option.value in selectedValues
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = "Choose ${field.id} relation ${option.label}"
                        },
                        leadingIcon = {
                            Checkbox(checked = selected, onCheckedChange = null)
                        },
                        text = {
                            Column {
                                Text(option.label)
                                option.supportingText?.let { supportingText ->
                                    Text(
                                        supportingText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        onClick = {
                            val updated = if (selected) {
                                selectedValues - option.value
                            } else {
                                selectedValues + option.value
                            }
                            onValueChange(updated.toNativeRelationArray(field.format))
                        },
                    )
                }
                GenericRelationPagingItem(paging)
            }
        }
        error?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GenericRelationPagingItem(paging: NativeRelatedRecordPaging?) {
    paging ?: return
    if (paging.loading) {
        DropdownMenuItem(
            text = { Text("Loading choices...") },
            onClick = {},
            enabled = false,
        )
        return
    }
    paging.loadMore?.let { loadMore ->
        DropdownMenuItem(
            text = { Text(if (paging.error == null) "Load more choices" else "Try loading more choices") },
            onClick = loadMore,
        )
    }
    paging.returnToFirstPage?.let { returnToFirstPage ->
        DropdownMenuItem(
            text = {
                Column {
                    Text("Return to first choices")
                    Text(
                        "${paging.discardedChoiceCount} earlier choices were released to keep memory bounded.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            onClick = returnToFirstPage,
        )
    }
    paging.error?.let { message ->
        DropdownMenuItem(
            text = {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {},
            enabled = false,
        )
    }
}

@Composable
private fun GenericRelationSearchGuidance(
    totalOptionCount: Int,
    discardedChoiceCount: Int,
    query: String,
    window: NativeRelationOptionWindow,
) {
    val message = when {
        query.isBlank() && discardedChoiceCount > 0 ->
            "Showing ${window.options.size} of $totalOptionCount current choices. " +
                "$discardedChoiceCount earlier choices can be restored below."
        query.isBlank() ->
            "Showing the first ${window.options.size} of $totalOptionCount choices. Search to narrow the list."
        window.hasMore ->
            "Showing the first ${window.options.size} matches. Refine your search to see fewer choices."
        window.options.isEmpty() -> "No matching choices."
        else -> "${window.options.size} matching choices."
    }
    Text(
        message,
        modifier = Modifier.padding(horizontal = NextcloudSpacing.Small),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun String.nativeRelationSelectedValues(format: String?): List<String> {
    if (isBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(this) }.getOrNull() as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val scalar = element as? JsonPrimitive ?: return@mapNotNull null
        when (format) {
            DYNAMIC_INTEGER_ARRAY_FORMAT -> scalar.takeUnless(JsonPrimitive::isString)?.contentOrNull
            DYNAMIC_STRING_ARRAY_FORMAT -> scalar.takeIf(JsonPrimitive::isString)?.contentOrNull
            else -> null
        }
    }.distinct()
}

private fun List<String>.toNativeRelationArray(format: String?): String = when (format) {
    DYNAMIC_INTEGER_ARRAY_FORMAT -> joinToString(prefix = "[", postfix = "]", separator = ",")
    DYNAMIC_STRING_ARRAY_FORMAT -> JsonArray(map(::JsonPrimitive)).toString()
    else -> ""
}

private const val NATIVE_RELATION_SEARCH_THRESHOLD = 8
private const val NATIVE_RELATION_MAX_QUERY_LENGTH = 120
private const val NATIVE_ENUM_SEARCH_THRESHOLD = 8
private const val NATIVE_ENUM_MAX_QUERY_LENGTH = 120
internal const val NATIVE_RELATION_RETAINED_SELECTION_LIMIT = 64
internal const val NATIVE_RELATION_OPTION_WINDOW_SIZE = 40

/**
 * Presents contract fields in a human task order without changing their wire names or request
 * bindings. The ordering is deliberately semantic and app-neutral: identify the record first,
 * describe it next, then show supporting choices and advanced controls.
 */
internal fun nativeFormDisplayFields(
    fields: List<FieldSpec>,
    relationFieldIds: Set<String> = emptySet(),
): List<FieldSpec> =
    fields
        .filterNot(FieldSpec::isServerManagedOptionalOrderingField)
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<FieldSpec>>(
                { (_, field) -> field.nativeFormDisplayPriority(field.id in relationFieldIds) },
                IndexedValue<FieldSpec>::index,
            ),
        )
        .map(IndexedValue<FieldSpec>::value)

private fun FieldSpec.isServerManagedOptionalOrderingField(): Boolean {
    if (required) return false
    val semanticId = id
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter(String::isNotBlank)
    return semanticId.isNotEmpty() && semanticId.all { word ->
        word in setOf("display", "index", "order", "ordering", "position", "rank", "sort")
    }
}

private fun FieldSpec.nativeFormDisplayPriority(relation: Boolean): Int {
    val semanticId = id.lowercase().filter(Char::isLetterOrDigit)
    return when {
        semanticId in setOf("name", "title", "subject", "label", "displayname") -> 0
        kind == FieldKind.longText ||
            semanticId in setOf("description", "content", "body", "notes", "summary") -> 10
        kind == FieldKind.file || kind == FieldKind.image -> 15
        isNativeVisualIconField() -> 30
        semanticId in setOf("color", "colour") -> 31
        kind == FieldKind.enumeration -> 40
        kind in setOf(FieldKind.date, FieldKind.dateTime) -> 45
        relation -> 50
        kind == FieldKind.boolean -> 60
        hasNativeRecurrenceRuleSemantics() -> 70
        repeatableObjectInput != null || kind == FieldKind.objectValue -> 80
        else -> 20
    }
}

internal fun nativeFormTitle(view: ViewSpec, resource: ResourceSpec, action: ActionSpec): String =
    if (action.isSettingsWrite(resource)) "Settings" else view.title

internal fun nativeFormSubmitLabel(resource: ResourceSpec, action: ActionSpec): String =
    if (action.isSettingsWrite(resource)) "Save settings" else action.label

private fun ActionSpec.isSettingsWrite(resource: ResourceSpec): Boolean {
    if (risk == ActionRisk.readOnly || binding.method == dev.obiente.nextcloudnative.nativeui.model.HttpMethod.GET) {
        return false
    }
    if (binding.allowsObservedBodyFields) return true
    val words = (resource.id + " " + resource.name)
        .lowercase()
        .map { character -> if (character.isLetterOrDigit()) character else ' ' }
        .joinToString("")
        .split(' ')
        .filter(String::isNotBlank)
        .toSet()
    return words.any { it in setOf("config", "configuration", "setting", "settings", "preference", "preferences") } &&
        (binding.bodyFieldNames.isNotEmpty() || inputSchema != null)
}

@Composable
private fun GenericSectionHeading(title: String, supporting: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GenericRepeatableObjectField(
    field: FieldSpec,
    spec: RepeatableObjectInputSpec,
    rows: List<RepeatableObjectInputRow>,
    enabled: Boolean,
    onRowsChange: (List<RepeatableObjectInputRow>) -> Unit,
) {
    if (spec.minimumItems == 1 && spec.maximumItems == 1 && rows.size == 1) {
        val row = rows.single()
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            spec.fields.forEach { itemField ->
                val explicitNull = itemField.id in row.nullFieldIds
                GenericFormField(
                    field = itemField.toNativeRepeatableObjectFieldSpec(),
                    value = row.values[itemField.id].orEmpty(),
                    error = null,
                    enabled = enabled && !explicitNull,
                    filePicker = null,
                    automationFieldId = nativeRepeatableObjectAutomationFieldId(
                        fieldId = field.id,
                        rowIndex = 0,
                        itemFieldId = itemField.id,
                    ),
                    onValueChange = { value ->
                        onRowsChange(
                            updateNativeRepeatableObjectValue(
                                rows = rows,
                                rowIndex = 0,
                                field = itemField,
                                value = value,
                            ),
                        )
                    },
                )
                if (itemField.nullable) {
                    Row(
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Send ${itemField.label} as null"
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = explicitNull,
                            enabled = enabled,
                            onCheckedChange = { checked ->
                                onRowsChange(
                                    updateNativeRepeatableObjectNull(
                                        rows = rows,
                                        rowIndex = 0,
                                        field = itemField,
                                        explicitNull = checked,
                                    ),
                                )
                            },
                        )
                        Text("Send ${itemField.label} as null", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(requiredFieldLabel(field), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${rows.size} of ${spec.maximumItems} items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                enabled = enabled && rows.size < spec.maximumItems,
                onClick = {
                    onRowsChange(addNativeRepeatableObjectRow(rows, spec))
                },
                modifier = Modifier.semantics {
                    contentDescription = "Add ${field.id} row"
                },
            ) {
                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Add item", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No items added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEachIndexed { rowIndex, row ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "${field.id} row ${rowIndex + 1}"
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = RoundedCornerShape(NextcloudRadii.Card),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Item ${rowIndex + 1}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(
                            enabled = enabled && rows.size > spec.minimumItems,
                            onClick = {
                                onRowsChange(
                                    removeNativeRepeatableObjectRow(
                                        rows = rows,
                                        index = rowIndex,
                                        spec = spec,
                                    ),
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Remove ${field.id} row ${rowIndex + 1}"
                            },
                        ) {
                            Text("Remove")
                        }
                    }
                    spec.fields.forEach { itemField ->
                        val automationFieldId = nativeRepeatableObjectAutomationFieldId(
                            fieldId = field.id,
                            rowIndex = rowIndex,
                            itemFieldId = itemField.id,
                        )
                        val explicitNull = itemField.id in row.nullFieldIds
                        GenericFormField(
                            field = itemField.toNativeRepeatableObjectFieldSpec(),
                            value = row.values[itemField.id].orEmpty(),
                            error = null,
                            enabled = enabled && !explicitNull,
                            filePicker = null,
                            automationFieldId = automationFieldId,
                            onValueChange = { value ->
                                onRowsChange(
                                    updateNativeRepeatableObjectValue(
                                        rows = rows,
                                        rowIndex = rowIndex,
                                        field = itemField,
                                        value = value,
                                    ),
                                )
                            },
                        )
                        if (itemField.nullable) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        contentDescription = "Send ${itemField.label} as null"
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = explicitNull,
                                    enabled = enabled,
                                    onCheckedChange = { checked ->
                                        onRowsChange(
                                            updateNativeRepeatableObjectNull(
                                                rows = rows,
                                                rowIndex = rowIndex,
                                                field = itemField,
                                                explicitNull = checked,
                                            ),
                                        )
                                    },
                                )
                                Text(
                                    "Send ${itemField.label} as null",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun nativeRepeatableObjectAutomationFieldId(
    fieldId: String,
    rowIndex: Int,
    itemFieldId: String,
): String = "$fieldId row ${rowIndex + 1} $itemFieldId"

internal fun RepeatableObjectInputFieldSpec.toNativeRepeatableObjectFieldSpec(): FieldSpec =
    FieldSpec(
        id = id,
        label = label,
        kind = when (kind) {
            RepeatableObjectInputScalarKind.String -> FieldKind.string
            RepeatableObjectInputScalarKind.Integer -> FieldKind.integer
            RepeatableObjectInputScalarKind.Decimal -> FieldKind.decimal
            RepeatableObjectInputScalarKind.Boolean -> FieldKind.boolean
            RepeatableObjectInputScalarKind.Enumeration -> FieldKind.enumeration
        },
        required = required,
        readOnly = false,
        format = format,
        enumValues = enumValues,
        enumLabels = enumLabels,
    )

@Composable
private fun GenericFormField(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    filePicker: NativeFileFieldPicker?,
    automationFieldId: String = field.id,
    onValueChange: (String) -> Unit,
) {
    when {
        field.format == SETTINGS_BOOLEAN_MAP_FORMAT -> {
            val entries = parseNativeBooleanMap(value).orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(requiredFieldLabel(field), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Choose which details are shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entries.forEach { (key, checked) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(key.dynamicSettingLabel(), modifier = Modifier.weight(1f))
                        Switch(
                            checked = checked,
                            enabled = enabled,
                            modifier = Modifier.semantics {
                                contentDescription = "Toggle $automationFieldId.$key"
                            },
                            onCheckedChange = { onValueChange(updateNativeBooleanMap(value, key, it)) },
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        field.kind == FieldKind.boolean -> Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(requiredFieldLabel(field), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (value == "true") "On" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Switch(
                checked = value == "true",
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = "Toggle $automationFieldId"
                },
                onCheckedChange = { onValueChange(it.toString()) },
            )
        }

        field.hasNativeRecurrenceRuleSemantics() ->
            GenericRecurrenceRuleField(
                field,
                value,
                error,
                enabled,
                automationFieldId,
                onValueChange,
            )
        field.kind == FieldKind.enumeration ->
            GenericEnumField(field, value, error, enabled, automationFieldId, onValueChange)
        field.kind == FieldKind.file -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Field $automationFieldId"
                    },
                label = { Text(requiredFieldLabel(field)) },
                supportingText = error?.let { message -> { Text(message) } },
                isError = error != null,
                singleLine = true,
            )
            filePicker?.let { picker ->
                OutlinedButton(
                    enabled = enabled,
                    onClick = { picker.requestFile(field, onValueChange) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Choose file for $automationFieldId"
                        },
                ) {
                    Icon(NextcloudIcons.File, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (value.isBlank()) "Choose file" else "Choose another file")
                }
            }
        }

        else -> {
            val multiLine = field.kind == FieldKind.longText ||
                field.format in setOf(
                    DYNAMIC_INTEGER_ARRAY_FORMAT,
                    DYNAMIC_STRING_LIST_FORMAT,
                    DYNAMIC_STRING_ARRAY_FORMAT,
                )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Field $automationFieldId"
                    },
                label = { Text(requiredFieldLabel(field)) },
                supportingText = when {
                    error != null -> ({ Text(error) })
                    field.format in setOf(
                        DYNAMIC_INTEGER_ARRAY_FORMAT,
                        DYNAMIC_STRING_LIST_FORMAT,
                        DYNAMIC_STRING_ARRAY_FORMAT,
                    ) ->
                        ({ Text("One value per line") })
                    else -> null
                },
                isError = error != null,
                minLines = if (multiLine) 4 else 1,
                maxLines = if (multiLine) 12 else 1,
                singleLine = !multiLine,
                placeholder = when (field.kind) {
                    FieldKind.date -> ({ Text("YYYY-MM-DD") })
                    FieldKind.dateTime -> ({ Text("YYYY-MM-DDTHH:MM") })
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.kind) {
                        FieldKind.integer ->
                            if (field.format == DYNAMIC_INTEGER_ARRAY_FORMAT) {
                                KeyboardType.Text
                            } else {
                                KeyboardType.Number
                            }
                        FieldKind.decimal, FieldKind.currency -> KeyboardType.Decimal
                        else -> KeyboardType.Text
                    },
                ),
            )
        }
    }
}

@Composable
private fun GenericRecurrenceRuleField(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    automationFieldId: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember(field.id) { mutableStateOf(false) }
    var custom by remember(field.id, value) {
        mutableStateOf(value.isNotBlank() && NATIVE_RECURRENCE_PRESETS.none { (_, rule) -> rule == value })
    }
    val selectedLabel = NATIVE_RECURRENCE_PRESETS.firstOrNull { (_, rule) -> rule == value }?.first
        ?: if (custom) "Custom rule" else "Does not repeat"
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text("Repeat", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Choose $automationFieldId"
                    },
            ) {
                Text(selectedLabel, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NATIVE_RECURRENCE_PRESETS.forEach { (label, rule) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            custom = false
                            expanded = false
                            onValueChange(rule)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom rule") },
                    onClick = {
                        custom = true
                        expanded = false
                    },
                )
            }
        }
        if (custom) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Field $automationFieldId"
                    },
                label = { Text("RFC 5545 recurrence rule") },
                placeholder = { Text("FREQ=WEEKLY;INTERVAL=2") },
                singleLine = true,
                isError = error != null,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun FieldSpec.hasNativeRecurrenceRuleSemantics(): Boolean =
    id.lowercase().filter(Char::isLetterOrDigit) in setOf("rrule", "recurrencerule") &&
        kind in setOf(FieldKind.string, FieldKind.longText)

private val NATIVE_RECURRENCE_PRESETS = listOf(
    "Does not repeat" to "",
    "Every day" to "FREQ=DAILY",
    "Every weekday" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
    "Every week" to "FREQ=WEEKLY",
    "Every month" to "FREQ=MONTHLY",
    "Every year" to "FREQ=YEARLY",
)

private fun String.dynamicSettingLabel(): String = replace('-', ' ').replace('_', ' ')
    .split(' ')
    .filter(String::isNotBlank)
    .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercaseChar) }

@Composable
private fun GenericEnumField(
    field: FieldSpec,
    value: String,
    error: String?,
    enabled: Boolean,
    automationFieldId: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by rememberSaveable(field.id) { mutableStateOf("") }
    val options = field.enumValues.orEmpty()
    val visibleOptions = remember(field.enumLabels, options, query) {
        nativeEnumOptionsMatchingQuery(field, query)
    }
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(requiredFieldLabel(field), style = MaterialTheme.typography.labelLarge)
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && !field.enumValues.isNullOrEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = "Choose $automationFieldId"
                    },
            ) {
                value.takeIf(String::isNotBlank)
                    ?.takeIf { field.isNativeVisualIconField() }
                    ?.let(NextcloudIcons::semanticOrFallback)
                    ?.let { icon ->
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.padding(end = NextcloudSpacing.Small).size(20.dp),
                        )
                    }
                value.nativeFormColorOrNull(field)?.let { color ->
                    NativeColorSwatch(
                        color,
                        modifier = Modifier.padding(end = NextcloudSpacing.Small),
                    )
                }
                Text(
                    value.takeIf(String::isNotBlank)?.let { selected ->
                        field.enumLabels?.get(selected) ?: selected.dynamicSettingLabel()
                    } ?: "Choose an option",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    NextcloudIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    query = ""
                },
            ) {
                if (options.size > NATIVE_ENUM_SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(NATIVE_ENUM_MAX_QUERY_LENGTH) },
                        modifier = Modifier
                            .padding(
                                horizontal = NextcloudSpacing.Small,
                                vertical = NextcloudSpacing.XSmall,
                            )
                            .widthIn(min = 280.dp)
                            .semantics {
                                contentDescription = "Search options for $automationFieldId"
                            },
                        label = { Text("Search ${field.label.lowercase()}") },
                        singleLine = true,
                    )
                }
                visibleOptions.forEach { option ->
                    val optionIcon = option.takeIf { field.isNativeVisualIconField() }
                        ?.let(NextcloudIcons::semanticOrFallback)
                    val optionColor = option.nativeFormColorOrNull(field)
                    DropdownMenuItem(
                        modifier = Modifier.semantics {
                            contentDescription = "Choose $automationFieldId option $option"
                        },
                        leadingIcon = if (optionIcon != null || optionColor != null) {
                            {
                                optionIcon?.let { icon ->
                                    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                                }
                                optionColor?.let { color -> NativeColorSwatch(color) }
                            }
                        } else {
                            null
                        },
                        text = { Text(field.enumLabels?.get(option) ?: option.dynamicSettingLabel()) },
                        onClick = {
                            expanded = false
                            query = ""
                            onValueChange(option)
                        },
                    )
                }
                if (visibleOptions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No matching options") },
                        onClick = {},
                        enabled = false,
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun nativeEnumOptionsMatchingQuery(field: FieldSpec, query: String): List<String> {
    val options = field.enumValues.orEmpty()
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) return options
    return options.filter { option ->
        normalizedQuery in option.lowercase() ||
            normalizedQuery in option.dynamicSettingLabel().lowercase() ||
            normalizedQuery in field.enumLabels?.get(option).orEmpty().lowercase()
    }
}

@Composable
private fun NativeColorSwatch(color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(20.dp),
        color = color,
        shape = RoundedCornerShape(5.dp),
        content = {},
    )
}

private fun String.nativeFormColorOrNull(field: FieldSpec): Color? {
    return nativeFormColorArgbOrNull(field)?.let(::Color)
}

internal fun String.nativeFormColorArgbOrNull(field: FieldSpec): Int? {
    if (field.id.lowercase().filter(Char::isLetterOrDigit) !in setOf("color", "colour")) return null
    val hex = trim().removePrefix("#")
    if (hex.length != 6 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    val rgb = hex.toLongOrNull(16) ?: return null
    return (0xFF000000L or rgb).toInt()
}

private fun requiredFieldLabel(field: FieldSpec): String = if (field.required) "${field.label} *" else field.label

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

internal data class NativeRecordPresentation(
    val title: String,
    val subtitle: String?,
    val iconKey: String? = null,
    val colorArgb: Int? = null,
)

internal data class NativeDetailFieldPresentation(
    val fieldId: String,
    val formatted: NativeFormattedField,
)

internal data class NativeStructuredDetailPresentation(
    val fields: List<NativeDetailFieldPresentation>,
    val sections: List<NativeStructuredDetailSection>,
)

internal data class NativeStructuredDetailSection(
    val fieldId: String,
    val label: String,
    val value: NativeStructuredValue,
    val ordered: Boolean,
)

internal fun nativeRecordPresentation(resource: ResourceSpec, record: NativeRecord): NativeRecordPresentation {
    val iconKey = nativeRecordIconKey(resource, record)
    val colorArgb = nativeRecordColorArgb(resource, record)
    nativeHouseholdPresentation(resource, record)?.let { presentation ->
        return NativeRecordPresentation(presentation.title, presentation.subtitle, iconKey, colorArgb)
    }
    nativeGroupwarePresentation(resource, record)?.let { presentation ->
        return NativeRecordPresentation(presentation.title, presentation.subtitle, iconKey, colorArgb)
    }
    val titleField = nativeRecordTitleField(resource, record)
    val title = titleField
        ?.let { field -> record.presentationValue(field.id)?.let { value -> formatNativeField(field, value).displayValue } }
        ?.takeIf(String::isNotBlank)
        ?: record.id
    val subtitle = resource.fields
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<FieldSpec>> { it.value.subtitlePriority() }
                .thenBy(IndexedValue<FieldSpec>::index),
        )
        .firstNotNullOfOrNull { (_, field) ->
        if (field.id == titleField?.id || field.subtitlePriority() <= 0) return@firstNotNullOfOrNull null
        record.presentationValue(field.id)
            ?.takeIf(String::isNotBlank)
            ?.let { formatNativeField(field, it).displayValue }
            ?.takeIf { value ->
                value.isNotBlank() &&
                    !value.equals(title, ignoreCase = true) &&
                    !value.equals(record.id, ignoreCase = true) &&
                    value != "Structured data" &&
                    !value.isPresentationMimeType()
            }
        }
    return NativeRecordPresentation(title, subtitle, iconKey, colorArgb)
}

internal fun nativeRecordIconKey(resource: ResourceSpec, record: NativeRecord): String? {
    val declaredIconFields = resource.fields.filter(FieldSpec::isNativeVisualIconField)
    if (declaredIconFields.isEmpty()) return null
    val populated = declaredIconFields.mapNotNull { field ->
        record.values[field.id]?.takeIf(String::isNotBlank)
    }
    if (populated.isEmpty()) return null
    val resolved = populated.map { raw ->
        raw.takeIf { value ->
            value.length <= MAX_NATIVE_RECORD_ICON_KEY_LENGTH &&
                value.all { character ->
                    character.isLetterOrDigit() || character in setOf('-', '_', ' ')
                }
        }
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.replace(' ', '-')
            ?: return null
    }.distinct()
    return resolved.singleOrNull()
}

internal fun nativeRecordColorArgb(resource: ResourceSpec, record: NativeRecord): Int? {
    val declaredColorFields = resource.fields.filter { field ->
        field.id.lowercase().filter(Char::isLetterOrDigit) in setOf("color", "colour") &&
            field.kind in setOf(FieldKind.string, FieldKind.enumeration)
    }
    if (declaredColorFields.isEmpty()) return null
    val populated = declaredColorFields.mapNotNull { field ->
        record.values[field.id]
            ?.takeIf(String::isNotBlank)
            ?.nativeFormColorArgbOrNull(field)
    }
    return populated.distinct().singleOrNull()
}

/**
 * MIME types describe transport rather than a record, so they should never occupy the only
 * subtitle slot on cards or collection rows. This parser intentionally avoids a permissive regex:
 * both sides of the slash must be simple MIME tokens and URLs therefore cannot match.
 */
private fun String.isPresentationMimeType(): Boolean {
    val mediaType = substringBefore(';').trim()
    val slash = mediaType.indexOf('/')
    if (slash <= 0 || slash != mediaType.lastIndexOf('/') || slash == mediaType.lastIndex) return false
    fun String.isMimeToken(): Boolean = isNotEmpty() && all { character ->
        character.isLetterOrDigit() || character in setOf('!', '#', '$', '&', '^', '_', '.', '+', '-')
    }
    return mediaType.substring(0, slash).isMimeToken() &&
        mediaType.substring(slash + 1).isMimeToken()
}

internal fun nativeDetailFields(
    resource: ResourceSpec,
    record: NativeRecord,
): List<NativeDetailFieldPresentation> = resource.fields
    .filter { field -> field.isSafeNativeDetailField(resource) }
    .mapNotNull { field ->
        record.presentationValue(field.id)
            ?.takeIf(String::isNotBlank)
            ?.let { NativeDetailFieldPresentation(field.id, formatNativeField(field, it)) }
    }

internal fun nativeStructuredDetail(
    resource: ResourceSpec,
    record: NativeRecord,
): NativeStructuredDetailPresentation {
    val sections = resource.fields
        .filter { field -> field.isSafeNativeDetailField(resource) }
        .mapNotNull { field ->
            val value = record.structuredValues[field.id]?.takeIf { it.hasVisibleContent() }
                ?: return@mapNotNull null
            NativeStructuredDetailSection(
                fieldId = field.id,
                label = field.label,
                value = value,
                ordered = value is NativeStructuredValue.ListValue && field.hasStepSemantics(),
            )
        }
    val generic = NativeStructuredDetailPresentation(
        fields = nativeDetailFields(resource, record).filterNot { it.fieldId in record.structuredValues },
        sections = sections,
    )
    return if (record.hasRecipeDetailSemantics()) generic.asRecipeDetail(resource) else generic
}

private fun NativeRecord.hasRecipeDetailSemantics(): Boolean {
    val keys = (structuredValues.keys + values.keys + displayValues.keys)
        .map { key -> key.lowercase().filter(Char::isLetterOrDigit) }
        .toSet()
    return keys.any(RECIPE_INGREDIENT_SECTION_KEYS::contains) &&
        keys.any(RECIPE_INSTRUCTION_SECTION_KEYS::contains)
}

private fun NativeStructuredDetailPresentation.asRecipeDetail(
    resource: ResourceSpec,
): NativeStructuredDetailPresentation {
    val fieldsById = resource.fields.associateBy(FieldSpec::id)
    val cleanedFields = fields
        .filterNot { detail -> detail.fieldId.recipeSemanticKey() in RECIPE_TECHNICAL_FIELDS }
        .map { detail ->
            val field = fieldsById[detail.fieldId]
            val label = when (detail.fieldId.recipeSemanticKey()) {
                "recipeyield" -> "Servings"
                "preptime" -> "Preparation"
                "cooktime" -> "Cooking"
                "totaltime" -> "Total"
                "recipecategory", "category" -> "Category"
                else -> field?.label ?: detail.formatted.label
            }
            detail.copy(formatted = detail.formatted.copy(label = label))
        }
        .sortedWith(compareBy({ RECIPE_FIELD_ORDER[it.fieldId.recipeSemanticKey()] ?: 100 }, { it.formatted.label }))
    val cleanedSections = sections
        .filter { section -> section.fieldId.recipeSemanticKey() in RECIPE_SECTION_ORDER }
        .map { section ->
            section.copy(
                label = when (section.fieldId.recipeSemanticKey()) {
                    in RECIPE_INGREDIENT_SECTION_KEYS -> "Ingredients"
                    in RECIPE_INSTRUCTION_SECTION_KEYS -> "Instructions"
                    in RECIPE_TOOL_SECTION_KEYS -> "Tools"
                    "nutrition" -> "Nutrition"
                    else -> section.label
                },
            )
        }
        .sortedBy { section -> RECIPE_SECTION_ORDER.getValue(section.fieldId.recipeSemanticKey()) }
    return copy(fields = cleanedFields, sections = cleanedSections)
}

private fun String.recipeSemanticKey(): String = lowercase().filter(Char::isLetterOrDigit)

private val RECIPE_TECHNICAL_FIELDS = setOf(
    "id", "name", "image", "imageurl", "imageplaceholderurl", "mainentityofpage",
    "datecreated", "datemodified", "url", "printimage", "context", "type",
)
private val RECIPE_FIELD_ORDER = mapOf(
    "description" to 0,
    "recipeyield" to 1,
    "preptime" to 2,
    "cooktime" to 3,
    "totaltime" to 4,
    "recipecategory" to 5,
    "category" to 5,
    "keywords" to 6,
    "datepublished" to 7,
)
private val RECIPE_INGREDIENT_SECTION_KEYS = setOf(
    "recipeingredient", "recipeingredients", "ingredient", "ingredients",
)
private val RECIPE_INSTRUCTION_SECTION_KEYS = setOf(
    "recipeinstruction", "recipeinstructions", "instruction", "instructions",
    "direction", "directions", "step", "steps",
)
private val RECIPE_TOOL_SECTION_KEYS = setOf("tool", "tools", "equipment")
private val RECIPE_SECTION_ORDER = buildMap {
    RECIPE_INGREDIENT_SECTION_KEYS.forEach { key -> put(key, 0) }
    RECIPE_INSTRUCTION_SECTION_KEYS.forEach { key -> put(key, 1) }
    RECIPE_TOOL_SECTION_KEYS.forEach { key -> put(key, 2) }
    put("nutrition", 3)
}

private fun NativeStructuredValue.hasVisibleContent(): Boolean = when (this) {
    is NativeStructuredValue.Scalar -> value != null
    is NativeStructuredValue.ListValue -> items.isNotEmpty()
    is NativeStructuredValue.ObjectValue -> entries.isNotEmpty()
}

private fun FieldSpec.hasStepSemantics(): Boolean {
    val semantic = (id + label).lowercase().filter(Char::isLetterOrDigit)
    return listOf("instruction", "step", "direction", "procedure", "method").any(semantic::contains)
}

private fun NativeStructuredValue.Scalar.structuredDisplayValue(): String = when (kind) {
    NativeStructuredScalarKind.boolean -> when (value?.lowercase()) {
        "true" -> "Yes"
        "false" -> "No"
        else -> value.orEmpty()
    }
    NativeStructuredScalarKind.nullValue -> "-"
    else -> value.orEmpty()
}

private fun nativeRecordTitle(resource: ResourceSpec, record: NativeRecord): String =
    nativeRecordTitleField(resource, record)
        ?.let { field -> record.presentationValue(field.id)?.let { value -> formatNativeField(field, value).displayValue } }
        ?.takeIf(String::isNotBlank)
        ?: record.id

private fun nativeRecordTitleField(resource: ResourceSpec, record: NativeRecord): FieldSpec? = resource.fields
    .withIndex()
    .filter { (_, field) -> !record.presentationValue(field.id).isNullOrBlank() && field.titlePriority() > 0 }
    .maxWithOrNull(
        compareBy<IndexedValue<FieldSpec>> { it.value.titlePriority() }
            .thenByDescending(IndexedValue<FieldSpec>::index),
    )
    ?.value

private fun FieldSpec.titlePriority(): Int {
    val normalized = id.lowercase().replace("_", "").replace("-", "")
    return when (normalized) {
        "displayname" -> 500
        "name", "title", "subject" -> 480
        "what", "merchant", "label" -> 470
        "summary" -> 460
        "description" -> 420
        "comment", "note", "notes", "memo" -> 0
        else -> when (kind) {
            FieldKind.string, FieldKind.longText, FieldKind.enumeration ->
                if (isTechnicalPresentationField()) 0 else 200
            FieldKind.userReference -> 160
            else -> 0
        }
    }
}

private fun FieldSpec.subtitlePriority(): Int {
    if (isTechnicalPresentationField() || isBinaryPresentationField()) return 0
    val normalized = id.lowercase().replace("_", "").replace("-", "")
    val semantic = when (normalized) {
        "description", "summary", "subtitle", "note", "notes" -> 500
        "status", "state", "category", "type" -> 420
        "members", "participants", "users", "owner", "assignee" -> 390
        "date", "datetime", "created", "modified", "updated", "duedate" -> 360
        else -> 0
    }
    val typed = when (kind) {
        FieldKind.longText -> 300
        FieldKind.string -> 260
        FieldKind.enumeration -> 250
        FieldKind.date, FieldKind.dateTime -> 240
        FieldKind.currency, FieldKind.decimal -> 230
        FieldKind.objectValue -> 220
        FieldKind.userReference -> 210
        FieldKind.integer -> 0
        FieldKind.boolean, FieldKind.image, FieldKind.file, FieldKind.unknown -> 0
    }
    return semantic + typed
}

private fun FieldSpec.isBinaryPresentationField(): Boolean {
    if (kind == FieldKind.image || kind == FieldKind.file) return true
    val normalized = id.lowercase().filter(Char::isLetterOrDigit)
    return normalized in setOf(
        "imageurl", "imageplaceholderurl", "thumbnailurl", "previewurl", "downloadurl",
        "avatarurl", "coverurl", "contenturl", "enclosureurl",
    )
}

private fun FieldSpec.isTechnicalPresentationField(): Boolean {
    val normalized = id.lowercase().replace("_", "").replace("-", "")
    return normalized in setOf(
        "id", "uuid", "token", "etag", "href", "permissions", "permission", "capabilities",
        "active", "enabled", "deleted", "favorite", "favourite", "archived", "readonly",
        "icon", "symbol", "color", "colour",
    ) || normalized.endsWith("id")
}

private fun FieldSpec.isNativeVisualIconField(): Boolean =
    id.lowercase().filter(Char::isLetterOrDigit) in setOf("icon", "symbol") &&
        kind in setOf(FieldKind.string, FieldKind.enumeration)

private const val MAX_NATIVE_RECORD_ICON_KEY_LENGTH = 64
private const val MAX_NATIVE_COLLECTION_BATCH_RELATIONS = 16
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_BINDINGS = 32
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_RECORDS = 500
private const val MAX_NATIVE_COLLECTION_BATCH_RELATION_ERROR_LENGTH = 1_024
