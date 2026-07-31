package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed as indexedListItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import dev.obiente.nextcloudnative.app.design.NextcloudAppBackground
import dev.obiente.nextcloudnative.app.design.NextcloudAppTile
import dev.obiente.nextcloudnative.app.design.NextcloudBottomNavigation
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionDestination
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationHost
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionNavigationModel
import dev.obiente.nextcloudnative.app.design.NextcloudCollectionWorkspaceScaffold
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudNativeTheme
import dev.obiente.nextcloudnative.app.design.NextcloudNavigationRail
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopIdentity
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.LocalNextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.NextcloudWorkspaceCapabilities
import dev.obiente.nextcloudnative.app.design.accountAvatarContentDescription
import dev.obiente.nextcloudnative.app.design.NextcloudNavigationStyle
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import dev.obiente.nextcloudnative.app.design.isNextcloudDarkTheme
import dev.obiente.nextcloudnative.app.design.resolveNextcloudRootShellLayout
import dev.obiente.nextcloudnative.app.design.resolveNextcloudCollectionNavigationMode
import dev.obiente.nextcloudnative.app.design.shouldUseNextcloudRootShell
import dev.obiente.nextcloudnative.nativeui.model.DynamicAppDescriptor
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.DynamicResourceRecordContext
import dev.obiente.nextcloudnative.nativeui.model.ActionIntent
import dev.obiente.nextcloudnative.nativeui.model.ActionSpec
import dev.obiente.nextcloudnative.nativeui.model.FieldKind
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.model.planDynamicNavigation
import dev.obiente.nextcloudnative.nativeui.model.preferredSemanticContextualChild
import dev.obiente.nextcloudnative.nativeui.model.singleSafeContextualChild
import dev.obiente.nextcloudnative.nativeui.model.dynamicNavigationState
import dev.obiente.nextcloudnative.nativeui.model.toNativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.resolveDynamicRecordReadParameters
import dev.obiente.nextcloudnative.nativeui.model.sameDynamicResourceAs
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionRequest
import dev.obiente.nextcloudnative.nativeui.runtime.NativeCollectionBatchRelationLoader
import dev.obiente.nextcloudnative.nativeui.runtime.NativeCollectionBatchRelationLoadResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRelatedRecordPaging
import dev.obiente.nextcloudnative.nativeui.runtime.NativeImageLoader
import dev.obiente.nextcloudnative.nativeui.runtime.NativeFileFieldPicker
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioRecordPlayer
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.effectiveNativeResourceId
import dev.obiente.nextcloudnative.nativeui.runtime.actionBindingValues
import dev.obiente.nextcloudnative.nativeui.runtime.safeActionBindingValues
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.settingsFormPrefillView
import dev.obiente.nextcloudnative.nativeui.runtime.editableNativeFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private sealed interface Screen {
    @Serializable
    data object Root : Screen
    @Serializable
    data object Search : Screen
    @Serializable
    data class Files(val path: String) : Screen
    @Serializable
    data object Media : Screen
    @Serializable
    data class PersonMedia(val person: NextcloudPerson) : Screen
    @Serializable
    data object Talk : Screen
    @Serializable
    data object Notes : Screen
    @Serializable
    data object Dashboard : Screen
    @Serializable
    data object UserStatus : Screen
    @Serializable
    data object Calendar : Screen
    @Serializable
    data object Contacts : Screen
    @Serializable
    data object Deck : Screen
    @Serializable
    data object AdminApps : Screen
    @Serializable
    data object OfflineCenter : Screen
    @Serializable
    data object Transfers : Screen
    @Serializable
    data object ProjectNews : Screen
    @Serializable
    data class ProjectNewsArticleView(val article: ProjectNewsArticle) : Screen
    @Serializable
    data class Chat(val room: TalkRoom) : Screen
    @Serializable
    data class NoteEditor(val note: NextcloudNote) : Screen
    @Serializable
    data class AppInfo(
        val app: NextcloudAppEntry,
        val navigation: DynamicAppNavigationState = DynamicAppNavigationState(),
        val lastKnownServerVersion: String? = null,
        val lastKnownInstalledAppVersion: String? = null,
    ) : Screen
    @Serializable
    data class MediaViewer(
        val navigationKey: String,
        val selectedIndex: Int,
        val selectedSourceIndex: Int,
        val returnTo: Screen,
    ) : Screen
    @Serializable
    data class FileInfo(
        val file: NextcloudFile,
        val parentPath: String,
        val showVersions: Boolean = false,
    ) : Screen
    @Serializable
    data class DocumentPreview(val file: NextcloudFile, val parentPath: String) : Screen
    @Serializable
    data class TextEditor(val file: NextcloudFile, val parentPath: String) : Screen
}

internal enum class RootDestinationContent {
    HomeWorkspace,
    Apps,
    Activity,
    Settings,
}

internal fun rootDestinationContent(
    destination: NextcloudDestination,
): RootDestinationContent = when (destination) {
    NextcloudDestination.Home -> RootDestinationContent.HomeWorkspace
    NextcloudDestination.Apps -> RootDestinationContent.Apps
    NextcloudDestination.Activity -> RootDestinationContent.Activity
    NextcloudDestination.Settings -> RootDestinationContent.Settings
}

private val navigationStateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val screenSaver = Saver<Screen, String>(
    save = { screen -> navigationStateJson.encodeToString(screen) },
    restore = { encoded ->
        runCatching { navigationStateJson.decodeFromString<Screen>(encoded) }.getOrDefault(Screen.Root)
    },
)

internal data class DynamicContractResumePlan(
    val serverVersion: String?,
    val installedAppVersionHint: String?,
    val serverVersionVerified: Boolean,
)

internal fun planDynamicContractResume(
    liveServerVersion: String?,
    lastKnownServerVersion: String?,
    lastKnownInstalledAppVersion: String?,
): DynamicContractResumePlan {
    val live = liveServerVersion?.trim()?.takeIf(String::isNotEmpty)
    return DynamicContractResumePlan(
        serverVersion = live ?: lastKnownServerVersion?.trim()?.takeIf(String::isNotEmpty),
        installedAppVersionHint = lastKnownInstalledAppVersion?.trim()?.takeIf(String::isNotEmpty),
        serverVersionVerified = live != null,
    )
}

internal fun resolveDynamicContractRediscovery(
    cachedDiscovery: DynamicDescriptorDiscovery?,
    candidate: DynamicDescriptorDiscovery,
): DynamicDescriptorDiscovery {
    val retainedCachedContract = cachedDiscovery?.takeIf { cached ->
        candidate.acquisition == DynamicDescriptorAcquisition.MetadataFallback &&
            cached.acquisition != DynamicDescriptorAcquisition.MetadataFallback
    } ?: return candidate
    // Rediscovery did not verify that this cached contract still matches the installed version.
    // Retain its useful read surfaces, but never retain its former write authority.
    return retainedCachedContract.copy(versionStatus = DynamicContractVersionStatus.LastKnownReadOnly)
}

internal fun retainedDynamicContractAfterDiscoveryFailure(
    cachedDiscovery: DynamicDescriptorDiscovery?,
): DynamicDescriptorDiscovery? = cachedDiscovery?.copy(
    versionStatus = DynamicContractVersionStatus.LastKnownReadOnly,
)

/**
 * Projects a retained contract into the action surface its current version evidence permits.
 *
 * Executor rejection remains the final defense, but unavailable writes must not be offered as
 * forms, record controls, inline actions, or header actions in the first place.
 */
internal fun NativeAppSchema.forDynamicContractVersion(
    versionStatus: DynamicContractVersionStatus,
): NativeAppSchema {
    if (versionStatus == DynamicContractVersionStatus.VerifiedCurrent) return this
    val availableActions = actions.filter { action -> versionStatus.allows(action.risk) }
    val availableActionIds = availableActions.mapTo(hashSetOf(), ActionSpec::id)
    return copy(
        actions = availableActions,
        views = views.filter { view -> view.sourceActionId in availableActionIds },
    )
}

internal data class DynamicFormRelationCacheKey(
    val resourceId: String,
    val actionId: String,
    val bindingValues: Map<String, String>,
)

internal data class DynamicFormRelationLoadRequest(
    val plan: DynamicFormRelationLoadPlan,
    val cacheKey: DynamicFormRelationCacheKey,
)

internal data class DynamicFormRelationContinuation(
    val spec: DynamicPaginationSpec,
    val nextPageNumber: Int,
    val nextRequestValue: String,
    val loadedRecordCount: Int,
)

private data class DynamicFormRelationLoadResult(
    val records: List<NativeRecord>,
    val pagination: DynamicPaginationSpec?,
)

internal data class DynamicFormRelationCacheState(
    val recordsByKey: Map<DynamicFormRelationCacheKey, List<NativeRecord>> = emptyMap(),
    val continuationsByKey: Map<DynamicFormRelationCacheKey, DynamicFormRelationContinuation> = emptyMap(),
    val discardedRecordCountsByKey: Map<DynamicFormRelationCacheKey, Int> = emptyMap(),
    val failedKeys: Set<DynamicFormRelationCacheKey> = emptySet(),
) {
    fun pendingRequests(
        requests: List<DynamicFormRelationLoadRequest>,
    ): List<DynamicFormRelationLoadRequest> = requests.filter { request ->
        request.cacheKey !in recordsByKey && request.cacheKey !in failedKeys
    }

    fun relatedRecords(
        requests: List<DynamicFormRelationLoadRequest>,
    ): Map<String, List<NativeRecord>> = requests.mapNotNull { request ->
        recordsByKey[request.cacheKey]?.let { records -> request.plan.resourceId to records }
    }.toMap()

    fun datasetRelatedRecords(
        genericRecords: Map<String, List<NativeRecord>>,
        requests: List<DynamicFormRelationLoadRequest>,
    ): Map<String, List<NativeRecord>> {
        val scopedResourceIds = requests.mapTo(hashSetOf()) { request -> request.plan.resourceId }
        return genericRecords.filterKeys { resourceId -> resourceId !in scopedResourceIds } +
            relatedRecords(requests)
    }

    fun failedRequests(
        requests: List<DynamicFormRelationLoadRequest>,
    ): List<DynamicFormRelationLoadRequest> = requests.filter { request ->
        request.cacheKey in failedKeys
    }

    fun loadSucceeded(
        request: DynamicFormRelationLoadRequest,
        records: List<NativeRecord>,
        pagination: DynamicPaginationSpec? = null,
    ): DynamicFormRelationCacheState {
        val distinctRecords = records.distinctBy(NativeRecord::id)
        val discardedRecordCount =
            (distinctRecords.size - MAX_DYNAMIC_FORM_RELATION_RECORDS).coerceAtLeast(0)
        val boundedRecords = distinctRecords.takeLast(MAX_DYNAMIC_FORM_RELATION_RECORDS)
        val continuation = pagination?.nextDynamicFormRelationContinuation(
            lastPage = records,
            loadedRecordCount = records.size,
        )
        return copy(
            recordsByKey = recordsByKey.putBounded(request.cacheKey, boundedRecords),
            continuationsByKey = if (continuation == null) {
                continuationsByKey - request.cacheKey
            } else {
                continuationsByKey.putBounded(request.cacheKey, continuation)
            },
            discardedRecordCountsByKey = if (discardedRecordCount == 0) {
                discardedRecordCountsByKey - request.cacheKey
            } else {
                discardedRecordCountsByKey.putBounded(request.cacheKey, discardedRecordCount)
            },
            failedKeys = failedKeys - request.cacheKey,
        )
    }

    fun appendPageSucceeded(
        request: DynamicFormRelationLoadRequest,
        page: List<NativeRecord>,
    ): DynamicFormRelationCacheState {
        val current = recordsByKey[request.cacheKey].orEmpty()
        val activeContinuation = continuationsByKey[request.cacheKey] ?: return this
        val currentIds = current.mapTo(hashSetOf(), NativeRecord::id)
        val novelRecords = page.distinctBy(NativeRecord::id)
            .filterNot { record -> record.id in currentIds }
        val unboundedWindow = current + novelRecords
        val discardedFromWindow =
            (unboundedWindow.size - MAX_DYNAMIC_FORM_RELATION_RECORDS).coerceAtLeast(0)
        val merged = unboundedWindow.takeLast(MAX_DYNAMIC_FORM_RELATION_RECORDS)
        val nextContinuation = activeContinuation.spec.nextDynamicFormRelationContinuation(
            lastPage = page,
            loadedRecordCount = activeContinuation.loadedRecordCount + page.size,
            novelRecordCount = novelRecords.size,
            nextPageNumber = activeContinuation.nextPageNumber + 1,
        )
        val discardedRecordCount =
            ((discardedRecordCountsByKey[request.cacheKey] ?: 0).toLong() + discardedFromWindow)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        return copy(
            recordsByKey = recordsByKey.putBounded(request.cacheKey, merged),
            continuationsByKey = if (nextContinuation == null) {
                continuationsByKey - request.cacheKey
            } else {
                continuationsByKey.putBounded(request.cacheKey, nextContinuation)
            },
            discardedRecordCountsByKey = if (discardedRecordCount == 0) {
                discardedRecordCountsByKey - request.cacheKey
            } else {
                discardedRecordCountsByKey.putBounded(request.cacheKey, discardedRecordCount)
            },
        )
    }

    fun continuation(
        request: DynamicFormRelationLoadRequest,
    ): DynamicFormRelationContinuation? = continuationsByKey[request.cacheKey]

    fun discardedRecordCount(request: DynamicFormRelationLoadRequest): Int =
        discardedRecordCountsByKey[request.cacheKey] ?: 0

    fun loadFailed(
        request: DynamicFormRelationLoadRequest,
    ): DynamicFormRelationCacheState = copy(
        recordsByKey = recordsByKey - request.cacheKey,
        continuationsByKey = continuationsByKey - request.cacheKey,
        discardedRecordCountsByKey = discardedRecordCountsByKey - request.cacheKey,
        failedKeys = (failedKeys + request.cacheKey)
            .toList()
            .takeLast(MAX_DYNAMIC_FORM_RELATION_CACHE_SCOPES)
            .toSet(),
    )

    fun retry(
        requests: List<DynamicFormRelationLoadRequest>,
    ): DynamicFormRelationCacheState {
        val retryKeys = requests.mapTo(hashSetOf(), DynamicFormRelationLoadRequest::cacheKey)
        return copy(failedKeys = failedKeys - retryKeys)
    }
}

private fun DynamicPaginationSpec.nextDynamicFormRelationContinuation(
    lastPage: List<NativeRecord>,
    loadedRecordCount: Int,
    novelRecordCount: Int = lastPage.size,
    nextPageNumber: Int = 2,
): DynamicFormRelationContinuation? {
    if (!canContinue(lastPage.size, novelRecordCount)) return null
    val nextValue = nextValue(nextPageNumber, loadedRecordCount, lastPage) ?: return null
    return DynamicFormRelationContinuation(this, nextPageNumber, nextValue, loadedRecordCount)
}

private suspend fun loadInitialDynamicFormRelationRecords(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    descriptor: DynamicAppDescriptor,
    request: DynamicFormRelationLoadRequest,
    values: Map<String, String>,
    cachePolicy: NextcloudApiCachePolicy = NextcloudApiCachePolicy.PreferCache,
): DynamicFormRelationLoadResult {
    val action = descriptor.actions.singleOrNull { action -> action.id == request.plan.actionId }
        ?: error("This relation has no declared load action.")
    val boundValues = dynamicFormRelationRuntimeValues(request, values)
    return DynamicFormRelationLoadResult(
        records = loadDynamicRecords(
            services = services,
            session = session,
            descriptor = descriptor,
            actionId = action.id,
            values = boundValues,
            runtimeContext = boundValues,
            cachePolicy = cachePolicy,
        ),
        pagination = action.dynamicPaginationSpec(),
    )
}

internal fun dynamicFormRelationRuntimeValues(
    request: DynamicFormRelationLoadRequest,
    availableValues: Map<String, String>,
    additionalValues: Map<String, String> = emptyMap(),
): Map<String, String> =
    availableValues + request.cacheKey.bindingValues + additionalValues

internal fun dynamicFormRelationLoadRequests(
    schema: NativeAppSchema,
    formView: ViewSpec,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = dynamicRelationLoadRequests(
    schema = schema,
    plans = dynamicFormRelationLoadPlans(
        schema = schema,
        formView = formView,
        availableValues = availableValues,
    ),
    availableValues = availableValues,
)

internal fun dynamicCollectionBatchRelationLoadRequests(
    schema: NativeAppSchema,
    childResourceId: String,
    relatedFieldIds: Set<String>,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = dynamicRelationLoadRequests(
    schema = schema,
    plans = dynamicRelationLoadPlans(
        schema = schema,
        childResourceId = childResourceId,
        editableFieldIds = relatedFieldIds,
        availableValues = availableValues,
    ),
    availableValues = availableValues,
)

private fun dynamicRelationLoadRequests(
    schema: NativeAppSchema,
    plans: List<DynamicFormRelationLoadPlan>,
    availableValues: Map<String, String>,
): List<DynamicFormRelationLoadRequest> = plans.mapNotNull { plan ->
    val action = schema.action(plan.actionId) ?: return@mapNotNull null
    val bindingNames = (
        action.binding.pathParameterNames +
            action.binding.requiredPathParameterNames +
            action.binding.queryParameterNames +
            action.binding.requiredQueryParameterNames
        ).distinct()
    if (bindingNames.size > MAX_DYNAMIC_FORM_RELATION_BINDINGS) return@mapNotNull null
    val bindingValues = dynamicFormRelationBindingValues(action, availableValues)
    DynamicFormRelationLoadRequest(
        plan = plan,
        cacheKey = DynamicFormRelationCacheKey(
            resourceId = plan.resourceId,
            actionId = plan.actionId,
            bindingValues = bindingValues,
        ),
    )
}

private fun <K, V> Map<K, V>.putBounded(key: K, value: V): Map<K, V> =
    ((this - key) + (key to value))
        .entries
        .toList()
        .takeLast(MAX_DYNAMIC_FORM_RELATION_CACHE_SCOPES)
        .associate(Map.Entry<K, V>::toPair)

private const val MAX_DYNAMIC_FORM_RELATION_BINDINGS = 32
private const val MAX_DYNAMIC_FORM_RELATION_CACHE_SCOPES = 16
internal const val MAX_DYNAMIC_FORM_RELATION_RECORDS = 500
private const val DYNAMIC_MUTATION_AUTHORITATIVE_READ_DELAY_MILLIS = 500L

private class PhotoTimelineUiState {
    val timeline = mutableStateOf(PhotoTimelineState(pageSize = MAX_PHOTO_TIMELINE_PAGE_SIZE))
    val backupStatuses = mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    val initialLoadCompleted = mutableStateOf(false)
}

private object PhotoTimelineUiStateRepository {
    private const val MAXIMUM_ACCOUNT_STATES = 4
    private val accountStates = linkedMapOf<String, PhotoTimelineUiState>()

    fun stateFor(session: NextcloudSession): PhotoTimelineUiState {
        val accountKey = previewCacheDigest(session)
        accountStates.remove(accountKey)?.let { existing ->
            accountStates[accountKey] = existing
            return existing
        }
        val created = PhotoTimelineUiState()
        accountStates[accountKey] = created
        while (accountStates.size > MAXIMUM_ACCOUNT_STATES) {
            accountStates.remove(accountStates.keys.first())
        }
        return created
    }
}

private val mediaViewerNavigationRepository = MediaViewerNavigationRepository()

private inline fun <reified T : Enum<T>> enumSaver() = Saver<T, String>(
    save = { value -> value.name },
    restore = { saved -> enumValues<T>().firstOrNull { it.name == saved } ?: enumValues<T>().first() },
)

private val photoBrowserStateSaver = Saver<PhotoBrowserState, String>(
    save = { state -> encodePhotoBrowserState(state) },
    restore = { encoded -> restorePhotoBrowserState(encoded) },
)

private enum class FileLayout { List, Grid }
private enum class PersonPhotoSelectionMode { Cover, RemoveFace }

private class MediaCollectionsUiState {
    var catalog by mutableStateOf<NativeMediaCollectionCatalog?>(null)
    var browserState by mutableStateOf(NativeMediaCollectionBrowserState())
    var selectedCollection by mutableStateOf<NativeMediaCollection?>(null)
    var dayIndex by mutableStateOf<NativeMediaDayIndex?>(null)
    var collectionItems by mutableStateOf<List<NativeMediaItem>>(emptyList())
    var resolvedFiles by mutableStateOf<Map<Long, NextcloudFile>>(emptyMap())
    var backupStatuses by mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    var cursor by mutableStateOf<NativeMediaDayCursor?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pendingAction by mutableStateOf<NativeMediaCollectionActionPlan?>(null)
    var createAlbumVisible by mutableStateOf(false)
    var createAlbumName by mutableStateOf("")
    var mediaToAdd by mutableStateOf<NextcloudFile?>(null)
    var mutationRunning by mutableStateOf(false)
    var mutationError by mutableStateOf<String?>(null)
    var loadAttempt by mutableStateOf(0)
    var requestGeneration: Long = 0L
}

private class PhotoFolderInventoryUiState(
    private val accountKey: String,
) {
    private var generation = 0L
    private var selectionKey = ""
    var pager by mutableStateOf(newPager())
        private set
    var pagingState by mutableStateOf(pager.state)
    var backupStatuses by mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())

    fun selectFolder(key: String): PhotoFolderInventoryPager {
        require(key.isNotBlank() && key.none(Char::isISOControl)) {
            "The photo folder inventory selection is invalid."
        }
        if (selectionKey != key) {
            selectionKey = key
            generation += 1
            pager = newPager()
            pagingState = pager.state
            backupStatuses = emptyMap()
        }
        return pager
    }

    private fun newPager(): PhotoFolderInventoryPager = PhotoFolderInventoryPager(
        owner = PhotoFolderInventoryPagingOwner(
            accountKey = accountKey,
            generation = generation,
        ),
    )
}

private class PhotoFolderViewportUiState(
    initialBrowseState: PhotoFolderBrowseState,
) {
    var browseState by mutableStateOf(initialBrowseState)
}

private val nativeAppIds = setOf(
    "files",
    "photos",
    "memories",
    "spreed",
    "talk",
    "activity",
    "notes",
    "dashboard",
    "user_status",
)

@Composable
fun NextcloudNativeApp(
    services: NextcloudPlatformServices,
    presentation: NextcloudPresentation = NextcloudPresentation.Adaptive,
) {
    var themePreference by remember { mutableStateOf(services.loadThemePreference()) }
    val darkTheme = isNextcloudDarkTheme(themePreference)

    NextcloudNativeTheme(darkTheme = darkTheme) {
        NextcloudAppBackground {
            var session by remember { mutableStateOf(services.loadSession()) }
            if (session == null) {
                LoginScreen(
                    services = services,
                    onLoggedIn = { authenticated ->
                        services.saveSession(authenticated)
                        session = authenticated
                    },
                )
            } else {
                AuthenticatedApp(
                    services = services,
                    session = requireNotNull(session),
                    presentation = presentation,
                    themePreference = themePreference,
                    onThemePreferenceChanged = { selected ->
                        services.saveThemePreference(selected)
                        themePreference = selected
                    },
                    onLoggedOut = {
                        services.clearSession()
                        session = null
                    },
                )
            }
        }
    }
}

/**
 * Renders the real root shell and home components against the compile-time synthetic fixture.
 *
 * Capture builds call this directly, so they cannot load sessions, caches, endpoints, or media.
 */
@Composable
fun NextcloudNativeMarketingCapture(
    scenario: MarketingCaptureScenario,
    assets: MarketingCaptureAssets,
    fixture: MarketingDemoFixture = nextcloudNativeMarketingFixture,
) {
    NextcloudNativeTheme(darkTheme = true) {
        NextcloudAppBackground {
            val desktop = scenario.presentation == NextcloudPresentation.Desktop
            CompositionLocalProvider(
                LocalNextcloudWorkspaceCapabilities provides NextcloudWorkspaceCapabilities(
                    isDesktop = desktop,
                    usesDenseControls = desktop,
                    supportsAuxiliaryPane = desktop,
                ),
            ) {
                when (scenario) {
                    MarketingCaptureScenario.DesktopHome,
                    MarketingCaptureScenario.MobileHome,
                    -> {
                        RootShell(
                            presentation = scenario.presentation,
                            selected = NextcloudDestination.Home,
                            onSelected = {},
                            identity = NextcloudDesktopIdentity(
                                displayName = fixture.displayName,
                                cloudName = fixture.cloudName,
                                avatar = assets.avatar,
                            ),
                        ) {
                            MarketingHomeDashboardScenario(scenario, fixture)
                        }
                    }
                    MarketingCaptureScenario.AdaptiveApp,
                    MarketingCaptureScenario.AdaptiveAppMobile,
                    MarketingCaptureScenario.AdaptiveAppCollectionMobile,
                    MarketingCaptureScenario.AdaptiveAppContextMenuMobile,
                    -> MarketingAdaptiveAppScenario(scenario)
                    MarketingCaptureScenario.PhotoTimelineRevalidationErrorMobile,
                    MarketingCaptureScenario.PhotoTimelineReturnToNewestErrorMobile,
                    MarketingCaptureScenario.PhotoTimelineRawRetryMobile,
                    -> MarketingPhotoTimelineFailureScenario(scenario)
                    MarketingCaptureScenario.PhotoFolderBrowserMobile,
                    MarketingCaptureScenario.PhotoFolderBrowserDesktop,
                    -> MarketingPhotoFolderScenario(scenario, assets)
                    MarketingCaptureScenario.ObsidianSync -> MarketingObsidianSyncScenario()
                    MarketingCaptureScenario.MediaBackup -> MarketingMediaBackupScenario()
                    MarketingCaptureScenario.FileSyncRulesMobile -> MarketingFileSyncRulesScenario()
                    MarketingCaptureScenario.FileSyncStatusMobile -> MarketingFileSyncStatusMobileScenario()
                    MarketingCaptureScenario.FileSyncStatusDesktop -> MarketingFileSyncStatusDesktopScenario()
                    MarketingCaptureScenario.FileSyncSetupDesktop -> MarketingFileSyncSetupDesktopScenario()
                    MarketingCaptureScenario.VirtualFileStorageMobile -> MarketingVirtualFileStorageMobileScenario()
                    MarketingCaptureScenario.VirtualFileStorageDesktop -> MarketingVirtualFileStorageDesktopScenario()
                    MarketingCaptureScenario.DesktopStartupSettings ->
                        MarketingDesktopStartupSettingsScenario(fixture, assets)
                    MarketingCaptureScenario.RawPreviewLoadingMobile,
                    MarketingCaptureScenario.RawPreviewErrorMobile,
                    MarketingCaptureScenario.RawPreviewMemoriesReadyMobile,
                    MarketingCaptureScenario.RawPreviewHighDetailDesktop,
                    MarketingCaptureScenario.NativeTiffPreviewMobile,
                    -> error("Native media captures require the isolated desktop fixture renderer.")
                    MarketingCaptureScenario.LivePhotoMotionFailureMobile ->
                        MarketingLivePhotoMotionFailureScenario(assets.mediaPreview)
                    MarketingCaptureScenario.FileShareUserMobile,
                    MarketingCaptureScenario.FileShareGroupDesktop,
                    MarketingCaptureScenario.FileShareLoadingMobile,
                    MarketingCaptureScenario.FileShareErrorMobile,
                    -> MarketingFileShareScenario(scenario)
                    MarketingCaptureScenario.TransferMobilePending,
                    MarketingCaptureScenario.TransferMobileFailed,
                    MarketingCaptureScenario.TransferDesktopActive,
                    MarketingCaptureScenario.TransferDesktopCompleted,
                    -> MarketingMediaTransferScenario(scenario)
                    MarketingCaptureScenario.DeckBoardDesktop,
                    MarketingCaptureScenario.DeckBoardMobile,
                    -> MarketingDeckBoardScenario()
                }
            }
        }
    }
}

@Composable
private fun MarketingDesktopStartupSettingsScenario(
    fixture: MarketingDemoFixture,
    assets: MarketingCaptureAssets,
) {
    RootShell(
        presentation = NextcloudPresentation.Desktop,
        selected = NextcloudDestination.Settings,
        onSelected = {},
        identity = NextcloudDesktopIdentity(
            displayName = fixture.displayName,
            cloudName = fixture.cloudName,
            avatar = assets.avatar,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ProductHeader(title = "Settings", showSettings = false)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
            ) {
                item {
                    SectionTitle("Appearance")
                    Row(
                        modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        ThemePreference.entries.forEach { preference ->
                            FilterChip(
                                selected = preference == ThemePreference.System,
                                onClick = {},
                                label = { Text(preference.name) },
                            )
                        }
                    }
                }
                item {
                    SectionTitle("Desktop")
                    DesktopStartOnLoginSettingsCard(
                        enabled = true,
                        message = null,
                        onEnabledChanged = {},
                    )
                }
                item {
                    SectionTitle("Files")
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        ) {
                            Icon(NextcloudIcons.Cloud, contentDescription = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sync and offline", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Folder sync, virtual files, conflicts, and storage",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(NextcloudIcons.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    services: NextcloudPlatformServices,
    onLoggedIn: (NextcloudSession) -> Unit,
) {
    var serverUrl by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 460.dp).padding(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            Surface(
                color = NextcloudTheme.colors.appIconContainer,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Icon(
                    NextcloudIcons.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(14.dp).size(34.dp),
                )
            }
            Text("Nextcloud Native", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Your cloud, in one native app.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("https://cloud.example.com") },
                singleLine = true,
                enabled = !connecting,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = serverUrl.isNotBlank() && !connecting,
                onClick = {
                    connecting = true
                    error = null
                    status = "Contacting your server..."
                    scope.launch {
                        runCatching {
                            val challenge = services.beginLogin(serverUrl)
                            services.openExternalUrl(challenge.loginUrl)
                            status = "Finish signing in in your browser, then return here."
                            repeat(150) {
                                services.pollLogin(challenge)?.let { return@runCatching it }
                                delay(2_000)
                            }
                            error("Login approval timed out. Please try again.")
                        }.onSuccess(onLoggedIn).onFailure { failure ->
                            error = failure.message ?: "Could not connect to this server."
                            connecting = false
                            status = null
                        }
                    }
                },
            ) {
                if (connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 4.dp))
                }
                Text(if (connecting) "Waiting for approval" else "Connect")
            }
        }
    }
}

@Composable
private fun AuthenticatedApp(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    presentation: NextcloudPresentation,
    themePreference: ThemePreference,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onLoggedOut: () -> Unit,
) {
    var screen by rememberSaveable(session.serverUrl, session.loginName, stateSaver = screenSaver) {
        mutableStateOf<Screen>(Screen.Root)
    }
    var destination by rememberSaveable(
        session.serverUrl,
        session.loginName,
        stateSaver = enumSaver<NextcloudDestination>(),
    ) { mutableStateOf(NextcloudDestination.Home) }
    var returnDestination by rememberSaveable(
        session.serverUrl,
        session.loginName,
        stateSaver = enumSaver<NextcloudDestination>(),
    ) { mutableStateOf(NextcloudDestination.Home) }
    var serverInfo by remember(session) { mutableStateOf<NextcloudServerInfo?>(null) }
    var memoriesLivePhotoCapability by remember(session) {
        mutableStateOf<MemoriesLivePhotoCapability>(MemoriesLivePhotoCapability.NotAdvertised)
    }
    val cachedAppDiscoveries = remember(session) { mutableStateMapOf<String, DynamicDescriptorDiscovery>() }
    var discoveryError by remember(session) { mutableStateOf<String?>(null) }
    var discoveryAttempt by remember(session) { mutableStateOf(0) }
    var fileLayout by rememberSaveable(stateSaver = enumSaver<FileLayout>()) { mutableStateOf(FileLayout.List) }
    var photoBrowserState by rememberSaveable(
        session.serverUrl,
        session.loginName,
        stateSaver = photoBrowserStateSaver,
    ) {
        mutableStateOf(PhotoBrowserState())
    }
    val mediaCollectionsState = remember(session) { MediaCollectionsUiState() }
    val mediaCollectionGridState = rememberLazyGridState()
    val photoAccountScope = remember(session) { previewCacheDigest(session) }
    val photoFolderInventoryState = remember(session) {
        PhotoFolderInventoryUiState(photoAccountScope)
    }
    val photoFolderViewportState = remember(session) {
        PhotoFolderViewportUiState(photoBrowserState.folder)
    }
    val photoFolderGridState = key(photoAccountScope, "photo-folder-grid") {
        rememberLazyGridState()
    }
    val photoFolderListState = key(photoAccountScope, "photo-folder-list") {
        rememberLazyListState()
    }
    val photoTimelineUiState = remember(session) {
        PhotoTimelineUiStateRepository.stateFor(session)
    }
    val photoTimelineGridState = rememberLazyGridState()

    LaunchedEffect(session, discoveryAttempt) {
        serverInfo = null
        discoveryError = null
        runCatching { services.loadServerInfo(session) }
            .onSuccess { serverInfo = it }
            .onFailure { discoveryError = it.message ?: "Could not load server details." }
    }

    LaunchedEffect(session, serverInfo?.apps) {
        memoriesLivePhotoCapability = MemoriesLivePhotoCapability.NotAdvertised
        if (serverInfo?.apps?.any { app -> app.id == "memories" } == true) {
            memoriesLivePhotoCapability = discoverMemoriesLivePhotoCapability(services, session)
        }
    }

    fun openApp(app: NextcloudAppEntry, from: NextcloudDestination) {
        returnDestination = from
        services.saveLastOpenedAppId(app.id)
        screen = when (app.id) {
            "files" -> Screen.Files("")
            "photos", "memories" -> Screen.Media
            "spreed", "talk" -> Screen.Talk
            "notes" -> Screen.Notes
            "dashboard" -> {
                destination = NextcloudDestination.Home
                Screen.Root
            }
            "user_status" -> Screen.UserStatus
            "calendar" -> Screen.Calendar
            "contacts" -> Screen.Contacts
            "deck" -> Screen.Deck
            "activity" -> {
                destination = NextcloudDestination.Activity
                Screen.Root
            }
            else -> Screen.AppInfo(
                app = app,
                lastKnownServerVersion = serverInfo?.version,
            )
        }
    }

    fun openSearch() {
        returnDestination = destination
        screen = Screen.Search
    }

    fun mediaViewerScreen(
        media: List<NextcloudFile>,
        selected: NextcloudFile,
        returnTo: Screen,
        sourceMembers: List<NextcloudFile> = media,
        navigationIdentityBySourceIdentity: Map<String, String> = emptyMap(),
    ): Screen.MediaViewer {
        val route = mediaViewerNavigationRepository.register(
            media = media,
            selected = selected,
            sourceMembers = sourceMembers,
            navigationIdentityBySourceIdentity = navigationIdentityBySourceIdentity,
        )
        return Screen.MediaViewer(
            navigationKey = route.key,
            selectedIndex = route.selectedIndex,
            selectedSourceIndex = route.selectedSourceIndex,
            returnTo = returnTo,
        )
    }

    fun openMediaViewer(
        media: List<NextcloudFile>,
        selected: NextcloudFile,
        returnTo: Screen,
        sourceMembers: List<NextcloudFile> = media,
        navigationIdentityBySourceIdentity: Map<String, String> = emptyMap(),
    ) {
        screen = mediaViewerScreen(
            media,
            selected,
            returnTo,
            sourceMembers,
            navigationIdentityBySourceIdentity,
        )
    }

    fun navigateBack() {
        when (val current = screen) {
            Screen.Root -> destination = NextcloudDestination.Home
            is Screen.Files -> {
                screen = if (current.path.isBlank()) Screen.Root
                else Screen.Files(current.path.substringBeforeLast('/', ""))
                if (screen == Screen.Root) destination = returnDestination
            }
            Screen.Search,
            Screen.Media,
            Screen.Talk,
            Screen.Notes,
            Screen.Dashboard,
            Screen.UserStatus,
            Screen.Calendar,
            Screen.Contacts,
            Screen.Deck,
            is Screen.AppInfo,
            -> {
                screen = Screen.Root
                destination = returnDestination
            }
            Screen.AdminApps -> {
                screen = Screen.Root
                destination = NextcloudDestination.Settings
            }
            Screen.OfflineCenter -> {
                screen = Screen.Root
                destination = NextcloudDestination.Settings
            }
            Screen.Transfers -> {
                screen = Screen.Root
                destination = NextcloudDestination.Settings
            }
            Screen.ProjectNews -> {
                screen = Screen.Root
                destination = NextcloudDestination.Settings
            }
            is Screen.ProjectNewsArticleView -> screen = Screen.ProjectNews
            is Screen.PersonMedia -> screen = Screen.Media
            is Screen.Chat -> screen = Screen.Talk
            is Screen.NoteEditor -> screen = Screen.Notes
            is Screen.MediaViewer -> {
                mediaViewerNavigationRepository.release(current.navigationKey)
                screen = current.returnTo
            }
            is Screen.FileInfo -> screen = Screen.Files(current.parentPath)
            is Screen.DocumentPreview -> screen = Screen.Files(current.parentPath)
            is Screen.TextEditor -> screen = Screen.Files(current.parentPath)
        }
    }

    PlatformBackHandler(
        enabled = when (screen) {
            is Screen.NoteEditor, is Screen.TextEditor -> false
            Screen.Root -> destination != NextcloudDestination.Home
            else -> true
        },
        onBack = ::navigateBack,
    )

    val desktopIdentity = serverInfo?.let { info ->
        NextcloudDesktopIdentity(
            displayName = info.displayName,
            cloudName = info.themeName ?: "Nextcloud",
        )
    }
    val screenContent: @Composable () -> Unit = {
        when (val current = screen) {
            Screen.Root -> when (rootDestinationContent(destination)) {
                RootDestinationContent.HomeWorkspace -> NativeDashboardScreen(
                    services = services,
                    session = session,
                    installedApps = serverInfo?.apps.orEmpty(),
                    onOpenApp = { openApp(it, NextcloudDestination.Home) },
                    onOpenStatus = serverInfo?.apps
                        ?.firstOrNull { it.id == "user_status" }
                        ?.let { statusApp ->
                            { openApp(statusApp, NextcloudDestination.Home) }
                        },
                    onBack = null,
                    onSearch = ::openSearch,
                    onSettings = { destination = NextcloudDestination.Settings },
                )
                RootDestinationContent.Apps -> AppsScreen(
                    serverInfo = serverInfo,
                    error = discoveryError,
                    onRetry = { discoveryAttempt += 1 },
                    onSettings = { destination = NextcloudDestination.Settings },
                    onSearch = ::openSearch,
                    onOpenApp = { openApp(it, NextcloudDestination.Apps) },
                )
                RootDestinationContent.Activity -> ActivityScreen(
                    services = services,
                    session = session,
                    activityInstalled = serverInfo?.apps?.any { it.id == "activity" } == true,
                    installedApps = serverInfo?.apps.orEmpty(),
                    onApps = { destination = NextcloudDestination.Apps },
                    onOpenApp = { app -> openApp(app, NextcloudDestination.Activity) },
                )
                RootDestinationContent.Settings -> SettingsScreen(
                    services = services,
                    session = session,
                    serverInfo = serverInfo,
                    themePreference = themePreference,
                    onThemePreferenceChanged = onThemePreferenceChanged,
                    onAdminApps = { screen = Screen.AdminApps },
                    onOfflineCenter = { screen = Screen.OfflineCenter },
                    onTransfers = { screen = Screen.Transfers },
                    onProjectNews = { screen = Screen.ProjectNews },
                    onLoggedOut = onLoggedOut,
                )
            }
            Screen.ProjectNews -> ProjectNewsScreen(
                services = services,
                onBack = ::navigateBack,
                onOpenArticle = { screen = Screen.ProjectNewsArticleView(it) },
            )
            is Screen.ProjectNewsArticleView -> ProjectNewsArticleScreen(
                services = services,
                article = current.article,
                onBack = ::navigateBack,
            )
            Screen.OfflineCenter -> FileOfflineCenterScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            onBack = ::navigateBack,
        )
        Screen.Transfers -> NativeMediaTransferCenterHost(
            services = services,
            session = session,
            onBack = ::navigateBack,
        )
        is Screen.Files -> FilesScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId,
            fileSharing = serverInfo?.fileSharing ?: NextcloudFileSharingCapabilities.Unavailable,
            path = current.path,
            layout = fileLayout,
            onLayoutChanged = { fileLayout = it },
            onBack = ::navigateBack,
            onOpenFolder = { screen = Screen.Files(it) },
            onOpenFile = { file, siblings ->
                val document = describeDocument(file)
                screen = when {
                    file.isEditableText() -> Screen.TextEditor(file, current.path)
                    document.method == DocumentPreviewMethod.ServerRaster ->
                        Screen.DocumentPreview(file, current.path)
                    file.canOpenInMediaViewer() -> mediaViewerScreen(
                        siblings.filter(NextcloudFile::canOpenInMediaViewer),
                        file,
                        current,
                    )
                    else -> Screen.FileInfo(file, current.path)
                }
            },
            onFileAction = { file, action, siblings ->
                when (action) {
                    FileMenuAction.Open -> if (file.isDirectory) screen = Screen.Files(file.path)
                    FileMenuAction.Preview -> {
                        val document = describeDocument(file)
                        screen = if (document.method != DocumentPreviewMethod.Unsupported) {
                            Screen.DocumentPreview(file, current.path)
                        } else if (file.canOpenInMediaViewer()) {
                            mediaViewerScreen(
                                siblings.filter(NextcloudFile::canOpenInMediaViewer),
                                file,
                                current,
                            )
                        } else {
                            Screen.FileInfo(file, current.path)
                        }
                    }
                    FileMenuAction.Details -> screen = Screen.FileInfo(file, current.path)
                    FileMenuAction.VersionHistory -> screen = Screen.FileInfo(
                        file = file,
                        parentPath = current.path,
                        showVersions = true,
                    )
                    FileMenuAction.EditText -> if (file.isEditableText()) {
                        screen = Screen.TextEditor(file, current.path)
                    }
                    else -> Unit
                }
            },
        )
        Screen.Search -> Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            NextcloudUnifiedSearchScreen(
                services = services,
                session = session,
                onBack = ::navigateBack,
                onOpenResult = { selection ->
                    val fileParent = selection.nativeFileParentPathOrNull()
                    if (fileParent != null) {
                        screen = Screen.Files(fileParent)
                    } else {
                        val app = serverInfo?.apps?.firstOrNull { candidate ->
                            candidate.id == selection.provider.appId ||
                                selection.provider.id.startsWith(candidate.id)
                        }
                        if (app != null) {
                            openApp(app, returnDestination)
                        } else {
                            selection.entry.resourceUrl?.let { resource ->
                                val absolute = if (resource.startsWith("/")) session.serverUrl.trimEnd('/') + resource else resource
                                services.openExternalUrl(absolute)
                            }
                        }
                    }
                },
            )
        }
        Screen.Dashboard -> NativeDashboardScreen(
            services = services,
            session = session,
            installedApps = serverInfo?.apps.orEmpty(),
            onOpenApp = { app -> openApp(app, returnDestination) },
            onOpenStatus = serverInfo?.apps
                ?.firstOrNull { it.id == "user_status" }
                ?.let { statusApp ->
                    { openApp(statusApp, returnDestination) }
                },
            onBack = ::navigateBack,
        )
        Screen.UserStatus -> NativeUserStatusScreen(
            services = services,
            session = session,
            onBack = ::navigateBack,
        )
        Screen.Calendar -> NativeGroupwareCalendarScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId ?: session.loginName,
            onBack = ::navigateBack,
        )
        Screen.Contacts -> NativeGroupwareContactsScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId ?: session.loginName,
            onBack = ::navigateBack,
        )
        Screen.Deck -> NativeDeckScreen(
            services = services,
            session = session,
            currentUserId = serverInfo?.userId ?: session.loginName,
            onBack = ::navigateBack,
            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        )
        Screen.AdminApps -> AdminAppsScreen(
            services = services,
            session = session,
            serverInfo = serverInfo,
            onOpenApp = { openApp(it, NextcloudDestination.Settings) },
            onBack = ::navigateBack,
        )
        Screen.Media -> MediaScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId,
            photoBrowserState = photoBrowserState,
            collectionState = mediaCollectionsState,
            collectionGridState = mediaCollectionGridState,
            folderInventoryState = photoFolderInventoryState,
            folderViewportState = photoFolderViewportState,
            folderGridState = photoFolderGridState,
            folderListState = photoFolderListState,
            onPhotoBrowserStateChanged = { photoBrowserState = it },
            timelineState = photoTimelineUiState,
            timelineGridState = photoTimelineGridState,
            onBack = ::navigateBack,
            onOpenMedia = { file, sequence ->
                openMediaViewer(
                    media = sequence.navigationItems,
                    selected = file,
                    returnTo = Screen.Media,
                    sourceMembers = sequence.sourceMembers,
                    navigationIdentityBySourceIdentity =
                        sequence.navigationIdentityBySourceIdentity,
                )
            },
            onOpenPerson = { screen = Screen.PersonMedia(it) },
        )
        is Screen.PersonMedia -> PersonMediaScreen(
            services = services,
            session = session,
            currentUserId = serverInfo?.userId ?: session.loginName,
            recognizeBridge = serverInfo?.recognizeBridge ?: RecognizeBridgeDiscovery.NotAdvertised,
            person = current.person,
            onBack = ::navigateBack,
            onPersonChanged = { refreshed -> screen = Screen.PersonMedia(refreshed) },
            onOpenMedia = { file, media ->
                openMediaViewer(media, file, current)
            },
        )
        Screen.Talk -> TalkScreen(
            services = services,
            session = session,
            onBack = ::navigateBack,
            onOpenRoom = { screen = Screen.Chat(it) },
        )
        Screen.Notes -> NextcloudNotesScreen(
            services = services,
            session = session,
            onBack = ::navigateBack,
            onOpenNote = { screen = Screen.NoteEditor(it) },
        )
        is Screen.NoteEditor -> NextcloudNoteEditor(
            services = services,
            session = session,
            note = current.note,
            onBack = ::navigateBack,
        )
        is Screen.Chat -> ChatScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            room = current.room,
            onBack = ::navigateBack,
            onOpenAttachment = { file ->
                openMediaViewer(listOf(file), file, current)
            },
        )
        is Screen.AppInfo -> {
            val resumePlan = planDynamicContractResume(
                liveServerVersion = serverInfo?.version,
                lastKnownServerVersion = current.lastKnownServerVersion,
                lastKnownInstalledAppVersion = current.lastKnownInstalledAppVersion,
            )
            AppInfoScreen(
                services = services,
                session = session,
                app = current.app,
                serverVersion = resumePlan.serverVersion,
                installedAppVersionHint = resumePlan.installedAppVersionHint,
                serverVersionVerified = resumePlan.serverVersionVerified,
                onRetryServerInfo = { discoveryAttempt += 1 },
                cachedDiscovery = cachedAppDiscoveries[current.app.id]
                    ?: sharedDynamicNativeMemoryCache.discovery(session, current.app.id),
                onDiscovery = { candidate ->
                    val cached = cachedAppDiscoveries[current.app.id]
                    if (cached == null || candidate.acquisition != DynamicDescriptorAcquisition.MetadataFallback) {
                        cachedAppDiscoveries[current.app.id] = candidate
                        sharedDynamicNativeMemoryCache.storeDiscovery(session, current.app.id, candidate)
                    }
                    val liveServerVersion = serverInfo?.version
                    val active = screen as? Screen.AppInfo
                    if (
                        active?.app?.id == current.app.id &&
                        liveServerVersion != null &&
                        candidate.versionStatus == DynamicContractVersionStatus.VerifiedCurrent &&
                        candidate.acquisition.usesAppStoreContract()
                    ) {
                        screen = active.copy(
                            lastKnownServerVersion = liveServerVersion,
                            lastKnownInstalledAppVersion = candidate.descriptor.app.version,
                        )
                    }
                },
                navigation = current.navigation,
                onNavigationChanged = { navigation ->
                    val active = screen as? Screen.AppInfo
                    if (active?.app?.id == current.app.id && active.navigation != navigation) {
                        screen = active.copy(navigation = navigation)
                    }
                },
                onBack = ::navigateBack,
            )
        }
        is Screen.MediaViewer -> {
            val route = MediaViewerNavigationRoute(
                key = current.navigationKey,
                selectedIndex = current.selectedIndex,
                selectedSourceIndex = current.selectedSourceIndex,
            )
            val snapshot = mediaViewerNavigationRepository.resolve(route)
            if (snapshot == null) {
                LaunchedEffect(current.navigationKey) {
                    screen = current.returnTo
                }
                LoadingMessage("Restoring the media timeline...")
            } else {
                NextcloudMediaViewer(
                    media = snapshot.media,
                    selected = snapshot.selected,
                    session = session,
                    userId = serverInfo?.userId.orEmpty(),
                    services = services,
                    taggingAvailable = serverInfo?.apps?.any { it.id == "memories" } == true,
                    memoriesLivePhotoCapability = memoriesLivePhotoCapability,
                    sharingCapabilities = serverInfo?.fileSharing
                        ?: NextcloudFileSharingCapabilities.Unavailable,
                    onSelect = { selected ->
                        mediaViewerNavigationRepository.select(route, selected)?.let { next ->
                            screen = current.copy(
                                selectedIndex = next.selectedIndex,
                                selectedSourceIndex = next.selectedSourceIndex,
                            )
                        }
                    },
                    onSourceRemoved = {
                        mediaViewerNavigationRepository.release(current.navigationKey)
                        screen = current.returnTo
                    },
                    onClose = {
                        mediaViewerNavigationRepository.release(current.navigationKey)
                        screen = current.returnTo
                    },
                    sourceMembers = snapshot.sourceMembers,
                )
            }
        }
        is Screen.FileInfo -> FileInfoScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            file = current.file,
            onBack = ::navigateBack,
            showVersions = current.showVersions,
            onVersionRestored = { screen = Screen.Files(current.parentPath) },
            onEdit = if (current.file.isEditableText()) {
                { screen = Screen.TextEditor(current.file, current.parentPath) }
            } else {
                null
            },
        )
        is Screen.DocumentPreview -> Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScreenHeader(current.file.name, "Document preview", ::navigateBack)
            NextcloudDocumentPreview(
                file = current.file,
                session = session,
                userId = serverInfo?.userId.orEmpty(),
                services = services,
                modifier = Modifier.weight(1f),
            )
        }
            is Screen.TextEditor -> TextEditorScreen(
            services = services,
            session = session,
            userId = serverInfo?.userId.orEmpty(),
            file = current.file,
            onBack = ::navigateBack,
            )
        }
    }
    if (shouldUseNextcloudRootShell(presentation, screen == Screen.Root)) {
        RootShell(
            presentation = presentation,
            selected = destination,
            onSelected = {
                destination = it
                screen = Screen.Root
            },
            identity = desktopIdentity,
            content = screenContent,
        )
    } else {
        screenContent()
    }
}

@Composable
private fun RootShell(
    presentation: NextcloudPresentation,
    selected: NextcloudDestination,
    onSelected: (NextcloudDestination) -> Unit,
    identity: NextcloudDesktopIdentity?,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        if (presentation == NextcloudPresentation.Desktop) {
            NextcloudDesktopShell(
                selected = selected,
                onSelected = onSelected,
                identity = identity,
                content = content,
            )
        } else {
            val layout = resolveNextcloudRootShellLayout(
                presentation = presentation,
                availableWidthDp = maxWidth.value.toInt(),
                destination = selected,
            )
            when (layout.navigationStyle) {
                NextcloudNavigationStyle.BottomBar -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
                        NextcloudBottomNavigation(selected = selected, onSelected = onSelected)
                    }
                }

                NextcloudNavigationStyle.CompactRail,
                NextcloudNavigationStyle.ExpandedSidebar,
                -> {
                    Row(modifier = Modifier.fillMaxSize()) {
                        NextcloudNavigationRail(selected = selected, onSelected = onSelected)
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            val maxContentWidth = requireNotNull(layout.contentMaximumWidthDp).dp
                            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = maxContentWidth)) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsScreen(
    serverInfo: NextcloudServerInfo?,
    error: String?,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Apps", onSettings = onSettings, onSearch = onSearch)
        when {
            error != null -> ErrorMessage(error, onRetry)
            serverInfo == null -> LoadingMessage("Loading installed apps...")
            else -> {
                val apps = serverInfo.apps.filter { app ->
                    app.id != "dashboard" &&
                        (search.isBlank() || app.name.contains(search, ignoreCase = true))
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp)
                        .semantics { contentDescription = "Search apps" },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    placeholder = { Text("Find an app") },
                    singleLine = true,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                )
                if (apps.isEmpty()) {
                    EmptyMessage("No installed app matches \"$search\".")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        contentPadding = PaddingValues(
                            start = NextcloudSpacing.XLarge,
                            top = NextcloudSpacing.Small,
                            end = NextcloudSpacing.XLarge,
                            bottom = NextcloudSpacing.XXLarge,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(apps, key = NextcloudAppEntry::id) { app ->
                            NextcloudAppTile(
                                title = app.name,
                                icon = NextcloudIcons.app(app.id),
                                supportingText = if (app.id in nativeAppIds) nativeSubtitle(app.id) else nativeFamily(app.id),
                                onClick = { onOpenApp(app) },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                accessibilityId = app.id,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAppsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    serverInfo: NextcloudServerInfo?,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onBack: () -> Unit,
) {
    var search by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf(NativeAppCatalogFilter.All) }
    var catalogResult by remember(session) { mutableStateOf<NativeAppCatalogResult?>(null) }
    var catalogAttempt by remember(session) { mutableStateOf(0) }
    var pendingLifecycleAction by remember {
        mutableStateOf<Pair<NativeManagedApp, NativeAppLifecycleAction>?>(null)
    }
    LaunchedEffect(session, catalogAttempt) {
        catalogResult = null
        catalogResult = runCatching {
            loadNativeAppCatalog { request -> services.executeNextcloudApi(session, request) }
        }.getOrElse {
            NativeAppCatalogResult.InvalidResponse("The administrator app catalog could not be loaded.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            title = "Server apps",
            subtitle = "Administrator app management",
            onBack = onBack,
        )
        when (val result = catalogResult) {
            null -> LoadingMessage("Loading administrator app catalog...")
            is NativeAppCatalogResult.Available -> NativeAppCatalogSurface(
                catalog = result.catalog,
                query = search,
                filter = catalogFilter,
                onQueryChanged = { search = it },
                onFilterChanged = { catalogFilter = it },
                onOpenInstalledApp = { managed ->
                    serverInfo?.apps?.firstOrNull { app -> app.id == managed.id }?.let(onOpenApp)
                },
                onLifecycleAction = { app, action -> pendingLifecycleAction = app to action },
            )
            NativeAppCatalogResult.Forbidden -> ErrorMessage(
                "This account does not have permission to manage server apps.",
                onRetry = { catalogAttempt += 1 },
            )
            NativeAppCatalogResult.Unavailable -> ErrorMessage(
                "Administrator app management is unavailable on this server.",
                onRetry = { catalogAttempt += 1 },
            )
            is NativeAppCatalogResult.InvalidResponse -> ErrorMessage(
                result.reason,
                onRetry = { catalogAttempt += 1 },
            )
        }
    }

    pendingLifecycleAction?.let { (app, action) ->
        AlertDialog(
            onDismissRequest = { pendingLifecycleAction = null },
            title = { Text("${action.uiLabel()} ${app.name}?") },
            text = {
                Text(
                    when (action) {
                        NativeAppLifecycleAction.InstallAndEnable ->
                            "This downloads server-side code and enables the app for users."
                        NativeAppLifecycleAction.Enable ->
                            "This activates the app and may add navigation, jobs, and integrations for users."
                        NativeAppLifecycleAction.Disable ->
                            "This makes the app and its integrations unavailable until an administrator enables it again."
                        NativeAppLifecycleAction.Update ->
                            "The server may enter maintenance mode while the app package is updated. Do not interrupt it."
                        NativeAppLifecycleAction.Uninstall ->
                            "This removes the app package. App data retention depends on the app and is not guaranteed."
                    } + "\n\nNextcloud requires administrator password confirmation. Continue in the authenticated server administration page.",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingLifecycleAction = null }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingLifecycleAction = null
                        services.openExternalUrl(session.serverUrl.trimEnd('/') + "/index.php/settings/apps")
                        catalogAttempt += 1
                    },
                    colors = if (action == NativeAppLifecycleAction.Uninstall) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text("Continue in browser")
                }
            },
        )
    }
}

@Composable
private fun AppInfoScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    app: NextcloudAppEntry,
    serverVersion: String?,
    installedAppVersionHint: String?,
    serverVersionVerified: Boolean,
    onRetryServerInfo: () -> Unit,
    cachedDiscovery: DynamicDescriptorDiscovery?,
    onDiscovery: (DynamicDescriptorDiscovery) -> Unit,
    navigation: DynamicAppNavigationState,
    onNavigationChanged: (DynamicAppNavigationState) -> Unit,
    onBack: () -> Unit,
) {
    val fallback = remember(app) { buildGenericNativeFallback(app, nativeFamily(app.id)) }
    var discovery by remember(app.id, session) { mutableStateOf(cachedDiscovery) }
    var discoveryError by remember(app.id, session) { mutableStateOf<String?>(null) }
    var discoveryAttempt by remember(app.id, session) { mutableStateOf(0) }

    fun retryDiscoveryAndServerInfo() {
        discoveryAttempt += 1
        onRetryServerInfo()
    }

    LaunchedEffect(
        app.id,
        session,
        serverVersion,
        installedAppVersionHint,
        serverVersionVerified,
        discoveryAttempt,
    ) {
        if (cachedDiscovery != null) {
            discovery = cachedDiscovery
        }
        val shouldRetry = discoveryAttempt > 0 || sharedDynamicNativeMemoryCache.shouldRetryDiscovery(session, app.id) ||
            !sharedDynamicNativeMemoryCache.isDiscoveryFresh(session, app.id)
        if (!shouldRetry && cachedDiscovery != null) {
            discoveryError = null
            return@LaunchedEffect
        }
        if (!shouldRetry) discovery = null
        discoveryError = null
        runCatching {
            discoverDynamicAppDescriptor(
                services = services,
                session = session,
                app = app,
                serverVersion = serverVersion,
                installedAppVersionHint = installedAppVersionHint,
                serverVersionVerified = serverVersionVerified,
            )
        }
            .onSuccess { candidate ->
                val resolvedDiscovery = resolveDynamicContractRediscovery(cachedDiscovery, candidate)
                val retainedCachedContract = resolvedDiscovery !== candidate
                onDiscovery(resolvedDiscovery)
                discovery = resolvedDiscovery
                sharedDynamicNativeMemoryCache.storeDiscovery(session, app.id, resolvedDiscovery)
                if (retainedCachedContract) {
                    discoveryError =
                        "Could not verify the current server and app versions. " +
                            "The last verified contract remains available in read-only mode."
                } else {
                    discoveryError = null
                }
            }
            .onFailure { failure ->
                if (failure is CancellationException) throw failure
                val retainedReadOnly = retainedDynamicContractAfterDiscoveryFailure(cachedDiscovery)
                if (retainedReadOnly != null) {
                    onDiscovery(retainedReadOnly)
                    discovery = retainedReadOnly
                    sharedDynamicNativeMemoryCache.storeDiscovery(session, app.id, retainedReadOnly)
                }
                sharedDynamicNativeMemoryCache.markDiscoveryFailure(session, app.id)
                discoveryError = if (cachedDiscovery == null) {
                    failure.message ?: "Could not discover this app's native API."
                } else {
                    "Could not verify the current server and app versions. " +
                        "The last verified contract remains available in read-only mode."
                }
            }
    }

    val unavailableExecutor = remember {
        NativeActionExecutor {
            NativeActionExecutionResult.Failure("No schema-declared action is available.")
        }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val resolved = discovery
        val isDiscovering = resolved == null
        val discoveryMessage = when {
            discoveryError != null -> "Using metadata fallback"
            isDiscovering -> "Preparing your workspace"
            else -> "Preparing actions"
        }
        // The discovered screen owns its own contextual header. Keeping the
        // discovery header around would stack two toolbars on every native app
        // (and makes the back action ambiguous). The outer header is only needed
        // while the contract is still being resolved or when using fallback UI.
        if (resolved == null) {
            ScreenHeader(app.name, discoveryMessage, onBack)
        }
        if (isDiscovering && discoveryError == null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = NextcloudSpacing.Large)
                    .padding(top = NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Discovering this app's native contract", style = MaterialTheme.typography.bodySmall)
            }
        }
        discoveryError?.let { message ->
            ErrorMessage("Dynamic contract failed: $message", ::retryDiscoveryAndServerInfo)
        }
        if (resolved == null) {
            GenericNativeAppScreen(
                schema = fallback.schema,
                view = fallback.view,
                state = fallback.state,
                actionExecutor = unavailableExecutor,
                modifier = Modifier.weight(1f),
            )
        } else {
            if (resolved.versionStatus == DynamicContractVersionStatus.LastKnownReadOnly) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            horizontal = NextcloudSpacing.Large,
                            vertical = NextcloudSpacing.Small,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Using the last verified contract. Browsing remains available, but changes require " +
                                "a fresh server and app version check.",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        TextButton(onClick = ::retryDiscoveryAndServerInfo) {
                            Text("Retry")
                        }
                    }
                }
            }
            DynamicDiscoveredAppScreen(
                services = services,
                session = session,
                discovery = resolved,
                restoredNavigation = navigation,
                onNavigationChanged = onNavigationChanged,
                onRetryDiscovery = ::retryDiscoveryAndServerInfo,
                onExit = onBack,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DynamicDiscoveredAppScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    discovery: DynamicDescriptorDiscovery,
    restoredNavigation: DynamicAppNavigationState,
    onNavigationChanged: (DynamicAppNavigationState) -> Unit,
    onRetryDiscovery: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val descriptor = discovery.descriptor
    val schema = remember(descriptor, discovery.versionStatus) {
        descriptor.toNativeAppSchema().forDynamicContractVersion(discovery.versionStatus)
    }
    val initialViewId = remember(descriptor, schema) {
        descriptor.planDynamicNavigation().rootDestinations.firstOrNull()?.layoutId
            ?: schema.views.firstOrNull { it.component != NativeComponent.form }?.id
            ?: schema.views.firstOrNull()?.id
    }
    // A persisted navigation snapshot can outlive the request context that
    // created it. Reject it only when its authoritative load bindings conflict
    // with the stored route parameters. A same-resource record is valid: it is
    // how the generic native fallback detail survives rotation and relaunch.
    val restoredRecordIsInvalidCollectionContext = restoredNavigation.selectedRecord?.bindingContext
        ?.any { (name, value) ->
            restoredNavigation.pathParameterValues[name]?.let { stored -> stored != value } == true
        } == true
    val restoredRecord = restoredNavigation.selectedRecord
        .takeUnless { restoredRecordIsInvalidCollectionContext }
    val restoredRecordResourceId = restoredNavigation.selectedRecordResourceId
        .takeUnless { restoredRecordIsInvalidCollectionContext }
    val restoredPathParameterValues = restoredNavigation.pathParameterValues
        .takeUnless { restoredRecordIsInvalidCollectionContext }
        .orEmpty()
    var selectedViewId by remember(descriptor) {
        mutableStateOf(
            if (restoredRecordIsInvalidCollectionContext) initialViewId
            else restoredNavigation.selectedViewId ?: initialViewId,
        )
    }
    var selectedRecord by remember(descriptor) { mutableStateOf(restoredRecord) }
    var selectedRecordResourceId by remember(descriptor) {
        mutableStateOf(restoredRecordResourceId)
    }
    var selectedPathParameterValues by remember(descriptor) {
        mutableStateOf(restoredPathParameterValues)
    }
    var contextualMenuRecordToken by rememberSaveable(
        session.serverUrl,
        session.loginName,
        descriptor.app.id,
    ) {
        mutableStateOf<String?>(null)
    }
    var contextualMenuOpen by rememberSaveable(
        session.serverUrl,
        session.loginName,
        descriptor.app.id,
    ) {
        mutableStateOf(false)
    }
    var navigationHistory by remember(descriptor) {
        mutableStateOf(restoreDynamicNavigationHistory(restoredNavigation.history))
    }
    var viewState by remember(descriptor) { mutableStateOf<NativeScreenState>(NativeScreenState.Loading) }
    var recordsByResourceId by remember(descriptor) {
        mutableStateOf<Map<String, List<NativeRecord>>>(emptyMap())
    }
    var formRelationCache by remember(
        session.serverUrl,
        session.loginName,
        descriptor,
        discovery.versionStatus,
    ) {
        mutableStateOf(DynamicFormRelationCacheState())
    }
    var formRelationLoadAttempt by remember(
        session.serverUrl,
        session.loginName,
        descriptor,
        discovery.versionStatus,
    ) {
        mutableStateOf(0)
    }
    var loadingFormRelationPageKeys by remember(descriptor) {
        mutableStateOf<Set<DynamicFormRelationCacheKey>>(emptySet())
    }
    var formRelationPageErrors by remember(descriptor) {
        mutableStateOf<Map<DynamicFormRelationCacheKey, String>>(emptyMap())
    }
    var loadAttempt by remember(descriptor) { mutableStateOf(0) }
    val dynamicReadCachePolicy = if (loadAttempt == 0) {
        NextcloudApiCachePolicy.PreferCache
    } else {
        NextcloudApiCachePolicy.ForceNetwork
    }
    var mutationReconciliationGeneration by rememberSaveable(
        session.serverUrl,
        session.loginName,
        descriptor.app.id,
    ) {
        mutableStateOf(0)
    }
    var paginationState by remember(descriptor) { mutableStateOf<DynamicPaginationState?>(null) }
    var loadingMore by remember(descriptor) { mutableStateOf(false) }
    var loadMoreError by remember(descriptor) { mutableStateOf<String?>(null) }
    val dynamicRecoveryScope = rememberCoroutineScope()
    val dynamicPaginationScope = rememberCoroutineScope()
    val formRelationPageScope = rememberCoroutineScope()
    val selectedDynamicUploadFiles = remember(descriptor) {
        mutableMapOf<String, LocalUploadFile>()
    }
    fun releaseSelectedDynamicUploadFile(file: LocalUploadFile) {
        val selectedField = selectedDynamicUploadFiles.entries
            .firstOrNull { (_, selected) -> selected.selectionId == file.selectionId }
            ?.key
        if (selectedField != null) {
            selectedDynamicUploadFiles.remove(selectedField)
            services.releaseLocalUploadFile(file)
        }
    }
    DisposableEffect(services, descriptor) {
        onDispose {
            selectedDynamicUploadFiles.values.forEach(services::releaseLocalUploadFile)
            selectedDynamicUploadFiles.clear()
        }
    }
    val dynamicFilePicker = remember(services, descriptor) {
        NativeFileFieldPicker { field, onSelected ->
            dynamicRecoveryScope.launch {
                val acceptedMimeType = field.format
                    ?.takeIf { format ->
                        format != "binary" &&
                            runCatching {
                                requireSafeUploadPickerRequest(
                                    listOf(format),
                                    DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
                                )
                            }.isSuccess
                    }
                    ?: "*/*"
                when (
                    val selection = services.chooseLocalUploadFile(
                        acceptedMimeTypes = listOf(acceptedMimeType),
                        maximumBytes = DEFAULT_LOCAL_UPLOAD_LIMIT_BYTES,
                    )
                ) {
                    is LocalUploadSelectionResult.Selected -> {
                        selectedDynamicUploadFiles.put(field.id, selection.file)
                            ?.let(services::releaseLocalUploadFile)
                        onSelected(encodeDynamicLocalUploadSelection(selection.file))
                    }
                    LocalUploadSelectionResult.Cancelled -> Unit
                    is LocalUploadSelectionResult.Rejected -> Unit
                    is LocalUploadSelectionResult.Unavailable -> Unit
                }
            }
        }
    }
    val dynamicAssetCache = remember(session.serverUrl, session.loginName, descriptor.app.id) {
        DynamicArtworkMemoryCache<ImageBitmap>(
            maximumBytes = MAX_DYNAMIC_ARTWORK_DECODED_BYTES,
            sizeOf = { image ->
                image.width.toLong().coerceAtLeast(1) *
                    image.height.toLong().coerceAtLeast(1) *
                    ARGB_8888_BYTES_PER_PIXEL
            },
        )
    }
    val imageLoader = remember(services, session, descriptor.app.id, dynamicAssetCache) {
        NativeImageLoader { assetPath ->
            val cacheKey = stableDynamicAssetCacheKey(assetPath)
            dynamicAssetCache.getOrLoad(cacheKey) {
                dynamicAppAssetRequest(descriptor.app.id, assetPath)?.let { request ->
                    val firstResponse = services.executeNextcloudApi(session, request)
                    val response = if (firstResponse.status in 300..399) {
                        firstResponse.location
                            ?.let { location -> dynamicAppAssetRequest(descriptor.app.id, location) }
                            ?.let { redirectedRequest -> services.executeNextcloudApi(session, redirectedRequest) }
                            ?: firstResponse
                    } else {
                        firstResponse
                    }
                    response.takeIf {
                        it.status in 200..299 &&
                            it.contentType.isSupportedDynamicArtworkContentType()
                    }
                        ?.body
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { bytes ->
                            decodePlatformImageSampled(
                                bytes,
                                MAX_DYNAMIC_ARTWORK_DIMENSION,
                            )?.image
                        }
                }
            }
        }
    }
    val selectedView = schema.views.firstOrNull { it.id == selectedViewId }
        ?: schema.views.firstOrNull()
    val formRelationValues = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
    val formRelationRequests = remember(schema, selectedView, formRelationValues) {
        selectedView?.let { view ->
            dynamicFormRelationLoadRequests(schema, view, formRelationValues)
        }.orEmpty()
    }
    val audioSourceCapability = remember(discovery) {
        descriptor.actions.firstNotNullOfOrNull { action ->
            nativeAudioSourceCapability(discovery, action)
        }
    }
    val mediaArtworkResolver = remember(discovery) {
        nativeMediaArtworkResolver(discovery)
    }
    val audioEngine = rememberPlatformAudioPlaybackEngine()
    val audioEngineState by audioEngine.state.collectAsState()
    var audioQueue by remember(descriptor) { mutableStateOf(NativeAudioQueueState()) }

    fun playCurrentAudioTrack(queue: NativeAudioQueueState) {
        val track = queue.currentTrack ?: return
        val sources = queue.tracks.mapNotNull { candidate ->
            audioSourceCapability?.source(candidate)
        }
        val source = audioSourceCapability?.source(track) ?: return
        val sourceIndex = sources.indexOfFirst { candidate -> candidate.id == source.id }
        if (sourceIndex < 0) return
        audioQueue = queue
        audioEngine.playQueue(session, sources, sourceIndex)
    }

    LaunchedEffect(audioEngineState.sourceId) {
        val sourceId = audioEngineState.sourceId ?: return@LaunchedEffect
        val index = audioQueue.tracks.indexOfFirst { track ->
            audioSourceCapability?.source(track)?.id == sourceId
        }
        if (index >= 0 && index != audioQueue.currentIndex) {
            audioQueue = audioQueue.copy(currentIndex = index)
        }
    }

    LaunchedEffect(audioEngineState.status, audioEngineState.sourceId) {
        if (audioEngineState.status == NativeAudioEngineStatus.Ended) {
            val advanced = audioQueue.next()
            if (advanced.currentTrack != null) playCurrentAudioTrack(advanced)
        }
    }

    LaunchedEffect(
        selectedViewId,
        selectedRecord,
        selectedRecordResourceId,
        selectedPathParameterValues,
        navigationHistory,
    ) {
        onNavigationChanged(
            DynamicAppNavigationState(
                selectedViewId = selectedViewId,
                selectedRecord = selectedRecord,
                selectedRecordResourceId = selectedRecordResourceId,
                pathParameterValues = selectedPathParameterValues,
                history = saveDynamicNavigationHistory(navigationHistory),
            ),
        )
    }

    LaunchedEffect(
        descriptor,
        selectedView?.id,
        selectedRecord?.id,
        selectedPathParameterValues,
        formRelationRequests,
        formRelationLoadAttempt,
        loadAttempt,
    ) {
        val view = selectedView ?: return@LaunchedEffect
        if (loadAttempt > 0) {
            // Some app controllers acknowledge a mutation just before the corresponding GET
            // projection becomes visible. Debounce the forced authoritative reload so that an
            // immediately stale response cannot be promoted to a fresh process-level snapshot.
            delay(DYNAMIC_MUTATION_AUTHORITATIVE_READ_DELAY_MILLIS)
            currentCoroutineContext().ensureActive()
        }
        val cacheKey = dynamicScreenCacheKey(
            session = session,
            appId = descriptor.app.id,
            viewId = view.id,
            selectedRecordId = selectedRecord?.id,
            parameterValues = selectedPathParameterValues,
        )
        paginationState = null
        loadingMore = false
        loadMoreError = null
        if (view.component == NativeComponent.form) {
            val values = formRelationValues
            val pendingRelationRequests = formRelationCache.pendingRequests(formRelationRequests)
            if (pendingRelationRequests.isNotEmpty()) {
                viewState = NativeScreenState.Loading
                val relationOutcomes = coroutineScope {
                    pendingRelationRequests.map { request ->
                        async {
                            val result = runCatching {
                                loadInitialDynamicFormRelationRecords(
                                    services = services,
                                    session = session,
                                    descriptor = descriptor,
                                    request = request,
                                    values = values,
                                    cachePolicy = dynamicReadCachePolicy,
                                )
                            }.getOrElse { failure ->
                                if (failure is CancellationException) throw failure
                                null
                            }
                            request to result
                        }
                    }.awaitAll()
                }
                currentCoroutineContext().ensureActive()
                var updatedRelationCache = formRelationCache
                relationOutcomes.forEach { (request, result) ->
                    updatedRelationCache = if (result == null) {
                        updatedRelationCache.loadFailed(request)
                    } else {
                        updatedRelationCache.loadSucceeded(
                            request = request,
                            records = result.records,
                            pagination = result.pagination,
                        )
                    }
                }
                formRelationCache = updatedRelationCache
            }
            val rememberedRecords = recordsByResourceId[view.resourceId].orEmpty()
            val cachedRecords = rememberedRecords.ifEmpty {
                sharedDynamicNativeMemoryCache.screen(cacheKey)?.records.orEmpty()
            }
            if (cachedRecords.isNotEmpty()) {
                recordsByResourceId = recordsByResourceId + (view.resourceId to cachedRecords)
                viewState = NativeScreenState.Ready(cachedRecords)
                return@LaunchedEffect
            }
            val prefillView = schema.settingsFormPrefillView(view)
            if (prefillView == null) {
                viewState = NativeScreenState.Ready(emptyList())
                return@LaunchedEffect
            }
            viewState = NativeScreenState.Loading
            runCatching {
                val records = loadDynamicRecords(
                    services = services,
                    session = session,
                    descriptor = descriptor,
                    actionId = prefillView.sourceActionId,
                    values = values,
                    runtimeContext = values,
                    cachePolicy = dynamicReadCachePolicy,
                )
                currentCoroutineContext().ensureActive()
                records
            }.onSuccess { records ->
                if (records.isEmpty()) {
                    viewState = NativeScreenState.Error(
                        message = "The server returned no current settings to edit.",
                        retry = { loadAttempt += 1 },
                    )
                } else {
                    val updatedRecords = recordsByResourceId + (view.resourceId to records)
                    recordsByResourceId = updatedRecords
                    viewState = NativeScreenState.Ready(records)
                    mutationReconciliationGeneration += 1
                    sharedDynamicNativeMemoryCache.storeScreen(
                        cacheKey,
                        DynamicScreenSnapshot(records, updatedRecords),
                    )
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                viewState = NativeScreenState.Error(
                    message = failure.message ?: "Could not load the current settings.",
                    retry = { loadAttempt += 1 },
                )
            }
            return@LaunchedEffect
        }
        val freshSnapshot = if (loadAttempt == 0) {
            sharedDynamicNativeMemoryCache.screen(cacheKey, freshOnly = true)
        } else {
            null
        }
        if (freshSnapshot != null) {
            recordsByResourceId = freshSnapshot.relatedRecords
            viewState = NativeScreenState.Ready(freshSnapshot.records)
            paginationState = freshSnapshot.pagination?.let { checkpoint ->
                descriptor.actions.firstOrNull { action -> action.id == view.sourceActionId }
                    ?.dynamicPaginationSpec()
                    ?.let { spec ->
                        DynamicPaginationState(
                            viewId = view.id,
                            spec = spec,
                            nextPageNumber = checkpoint.nextPageNumber,
                            nextRequestValue = checkpoint.nextRequestValue,
                        )
                    }
            }
            return@LaunchedEffect
        }
        val staleSnapshot = if (loadAttempt == 0) sharedDynamicNativeMemoryCache.screen(cacheKey) else null
        if (staleSnapshot != null) {
            recordsByResourceId = staleSnapshot.relatedRecords
            viewState = NativeScreenState.Ready(staleSnapshot.records)
        }
        val composite = view.compositeDataGrid
        if (composite != null) {
            if (staleSnapshot == null) viewState = NativeScreenState.Loading
            val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
            runCatching {
                val loaded = coroutineScope {
                    listOf(
                        composite.columnResourceId to composite.columnSourceActionId,
                        composite.rowResourceId to composite.rowSourceActionId,
                    ).map { (resourceId, actionId) ->
                        async {
                            resourceId to loadDynamicRecords(
                                services = services,
                                session = session,
                                descriptor = descriptor,
                                actionId = actionId,
                                values = values,
                                runtimeContext = values,
                                cachePolicy = dynamicReadCachePolicy,
                            )
                        }
                    }.awaitAll()
                }
                currentCoroutineContext().ensureActive()
                loaded
            }.onSuccess { loaded ->
                val updatedRecords = recordsByResourceId + loaded.toMap()
                val rows = loaded.first { (resourceId, _) -> resourceId == composite.rowResourceId }.second
                recordsByResourceId = updatedRecords
                viewState = NativeScreenState.Ready(rows)
                mutationReconciliationGeneration += 1
                sharedDynamicNativeMemoryCache.storeScreen(
                    cacheKey,
                    DynamicScreenSnapshot(rows, updatedRecords),
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                viewState = staleSnapshot?.let { NativeScreenState.Ready(it.records) }
                    ?: NativeScreenState.Error(
                        message = failure.message ?: "Could not load ${view.title}.",
                        retry = { loadAttempt += 1 },
                    )
            }
            return@LaunchedEffect
        }
        if (view.sourceActionId.isBlank()) {
            val records = metadataRecordsForDynamicView(discovery, view.resourceId)
            val updatedRecords = recordsByResourceId + (view.resourceId to records)
            recordsByResourceId = updatedRecords
            viewState = NativeScreenState.Ready(records)
            sharedDynamicNativeMemoryCache.storeScreen(
                cacheKey,
                DynamicScreenSnapshot(records, updatedRecords),
            )
            return@LaunchedEffect
        }
        if (staleSnapshot == null) viewState = NativeScreenState.Loading
        val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() + selectedPathParameterValues
        runCatching {
            val records = loadDynamicRecords(
                services = services,
                session = session,
                descriptor = descriptor,
                actionId = view.sourceActionId,
                values = values,
                runtimeContext = values,
                cachePolicy = dynamicReadCachePolicy,
            )
            currentCoroutineContext().ensureActive()
            records
        }.onSuccess { records ->
            val updatedRecords = recordsByResourceId + (view.resourceId to records)
            val nextPagination = descriptor.actions.firstOrNull { action -> action.id == view.sourceActionId }
                ?.dynamicPaginationSpec()
                ?.toDynamicPaginationState(view.id, records)
            recordsByResourceId = updatedRecords
            viewState = NativeScreenState.Ready(records)
            mutationReconciliationGeneration += 1
            records.firstOrNull()?.let { authoritative ->
                if (
                    view.component == NativeComponent.detail &&
                    selectedRecord?.id == authoritative.id
                ) {
                    // Promote the freshly loaded server record over the sparse list stub. This
                    // gives contextual actions a declared identity and prefills edit forms with
                    // the complete current recipe.
                    selectedRecord = authoritative
                }
            }
            paginationState = nextPagination
            sharedDynamicNativeMemoryCache.storeScreen(
                cacheKey,
                DynamicScreenSnapshot(
                    records = records,
                    relatedRecords = updatedRecords,
                    pagination = nextPagination?.toCheckpoint(),
                ),
            )
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            if (staleSnapshot != null) {
                viewState = NativeScreenState.Ready(staleSnapshot.records)
                return@onFailure
            }
            val recoveryRequest = failure.takeIf(Throwable::isUnsynchronizedDynamicCollectionFailure)
                ?.let { buildDynamicRefreshRecoveryRequest(descriptor, values) }
            viewState = NativeScreenState.Error(
                message = failure.message ?: "Could not load ${view.title}.",
                retry = recoveryRequest?.let { request ->
                    {
                        dynamicRecoveryScope.launch {
                            viewState = NativeScreenState.Loading
                            runCatching {
                                val response = services.executeNextcloudApi(session, request)
                                check(response.acceptedDynamicRefresh()) {
                                    "Mailbox synchronization failed (HTTP ${response.status})."
                                }
                                delay(1_200)
                                currentCoroutineContext().ensureActive()
                            }.onSuccess {
                                loadAttempt += 1
                            }.onFailure { recoveryFailure ->
                                if (recoveryFailure is CancellationException) throw recoveryFailure
                                viewState = NativeScreenState.Error(
                                    message = recoveryFailure.message ?: "Could not synchronize this mailbox.",
                                    retry = { loadAttempt += 1 },
                                )
                            }
                        }
                    }
                } ?: { loadAttempt += 1 },
                retryLabel = if (recoveryRequest == null) "Try again" else "Sync and retry",
            )
        }
    }

    if (selectedView == null) {
        val fallback = remember(descriptor.app) {
            buildGenericNativeFallback(
                NextcloudAppEntry(descriptor.app.id, descriptor.app.name, href = null),
                "No dynamic views advertised",
            )
        }
        GenericNativeAppScreen(
            schema = fallback.schema,
            view = fallback.view,
            state = NativeScreenState.Error("The discovered descriptor did not contain a renderable view."),
            actionExecutor = NativeActionExecutor {
                NativeActionExecutionResult.Failure("No action is available.")
            },
            modifier = modifier,
        )
        return
    }

    val selectedRuntimeValues = selectedDynamicRecordRuntimeValues(
        record = selectedRecord,
        resourceId = selectedRecordResourceId,
        parameterNames = schema.action(selectedView.sourceActionId)
            ?.binding
            ?.pathParameterNames
            .orEmpty(),
    )
    val runtimeValues = selectedRuntimeValues
        ?.let { values -> safeActionBindingValues(values, selectedPathParameterValues) }
        .orEmpty()
    val datasetBindingValues = dynamicDatasetBindingValues(
        component = selectedView.component,
        declaredParameterNames = schema.action(selectedView.sourceActionId)
            ?.binding
            ?.let { binding -> binding.pathParameterNames + binding.queryParameterNames }
            .orEmpty(),
        selectedPathParameterValues = selectedPathParameterValues,
        runtimeValues = runtimeValues,
    )
    val datasetRelatedRecords = formRelationCache.datasetRelatedRecords(
        genericRecords = recordsByResourceId,
        requests = formRelationRequests,
    )
    val relatedRecordPaging = remember(
        formRelationRequests,
        formRelationCache,
        loadingFormRelationPageKeys,
        formRelationPageErrors,
        formRelationValues,
    ) {
        formRelationRequests.mapNotNull { request ->
            val continuation = formRelationCache.continuation(request)
            val loading = request.cacheKey in loadingFormRelationPageKeys
            val discardedRecordCount = formRelationCache.discardedRecordCount(request)
            val error = formRelationPageErrors[request.cacheKey]
            if (
                continuation == null &&
                error == null &&
                !loading &&
                discardedRecordCount == 0
            ) {
                return@mapNotNull null
            }
            request.plan.resourceId to NativeRelatedRecordPaging(
                loading = loading,
                error = error,
                discardedChoiceCount = discardedRecordCount,
                loadMore = continuation?.takeUnless { loading }?.let {
                    {
                        if (request.cacheKey !in loadingFormRelationPageKeys) {
                            loadingFormRelationPageKeys += request.cacheKey
                            formRelationPageErrors -= request.cacheKey
                            formRelationPageScope.launch {
                                val active = formRelationCache.continuation(request)
                                if (active == null) {
                                    loadingFormRelationPageKeys -= request.cacheKey
                                    return@launch
                                }
                                val pageValues = dynamicFormRelationRuntimeValues(
                                    request = request,
                                    availableValues = formRelationValues,
                                    additionalValues = mapOf(
                                        active.spec.parameterName to active.nextRequestValue,
                                    ),
                                )
                                runCatching {
                                    loadDynamicRecords(
                                        services = services,
                                        session = session,
                                        descriptor = descriptor,
                                        actionId = request.plan.actionId,
                                        values = pageValues,
                                        runtimeContext = pageValues,
                                        cachePolicy = dynamicReadCachePolicy,
                                    )
                                }.onSuccess { page ->
                                    formRelationCache = formRelationCache.appendPageSucceeded(request, page)
                                    formRelationPageErrors -= request.cacheKey
                                }.onFailure { failure ->
                                    if (failure is CancellationException) throw failure
                                    formRelationPageErrors = formRelationPageErrors.putBounded(
                                        request.cacheKey,
                                        failure.message ?: "Could not load more choices.",
                                    )
                                }
                                loadingFormRelationPageKeys -= request.cacheKey
                            }
                        }
                    }
                },
                returnToFirstPage = discardedRecordCount.takeIf { count -> count > 0 && !loading }?.let {
                    {
                        if (request.cacheKey !in loadingFormRelationPageKeys) {
                            loadingFormRelationPageKeys += request.cacheKey
                            formRelationPageErrors -= request.cacheKey
                            formRelationPageScope.launch {
                                runCatching {
                                    loadInitialDynamicFormRelationRecords(
                                        services = services,
                                        session = session,
                                        descriptor = descriptor,
                                        request = request,
                                        values = formRelationValues,
                                        cachePolicy = dynamicReadCachePolicy,
                                    )
                                }.onSuccess { result ->
                                    formRelationCache = formRelationCache.loadSucceeded(
                                        request = request,
                                        records = result.records,
                                        pagination = result.pagination,
                                    )
                                    formRelationPageErrors -= request.cacheKey
                                }.onFailure { failure ->
                                    if (failure is CancellationException) throw failure
                                    formRelationPageErrors = formRelationPageErrors.putBounded(
                                        request.cacheKey,
                                        failure.message ?: "Could not return to the first choices.",
                                    )
                                }
                                loadingFormRelationPageKeys -= request.cacheKey
                            }
                        }
                    }
                },
            )
        }.toMap()
    }
    val failedFormRelationRequests = formRelationCache.failedRequests(formRelationRequests)
    val executor = remember(services, session, descriptor, runtimeValues, discovery.versionStatus) {
        DynamicNextcloudActionExecutor(
            services = services,
            session = session,
            descriptor = descriptor,
            runtimeContext = runtimeValues,
            versionStatus = discovery.versionStatus,
            onMultipartUploadSucceeded = ::releaseSelectedDynamicUploadFile,
        )
    }
    val collectionBatchRelationLoader = remember(services, session, descriptor, schema) {
        NativeCollectionBatchRelationLoader { request ->
            val action = schema.action(request.actionId)
                ?.takeIf { candidate ->
                    candidate.resourceId.sameDynamicResourceAs(request.resourceId) &&
                        request.relatedResourceIdsByField.keys.all { fieldId ->
                            fieldId in candidate.binding.bodyFieldNames ||
                                fieldId in candidate.binding.queryParameterNames
                        }
                }
            if (action == null) {
                NativeCollectionBatchRelationLoadResult(
                    recordsByResourceId = emptyMap(),
                    errorsByResourceId = request.relatedResourceIdsByField.values
                        .distinct()
                        .associateWith { "The batch relation contract is unavailable." },
                )
            } else {
                val requestedResourceIds = request.relatedResourceIdsByField.values.toSet()
                val loadRequests = dynamicCollectionBatchRelationLoadRequests(
                    schema = schema,
                    childResourceId = request.resourceId,
                    relatedFieldIds = request.relatedResourceIdsByField.keys,
                    availableValues = request.bindingValues,
                ).associateBy { candidate -> candidate.plan.resourceId }
                val outcomes = coroutineScope {
                    requestedResourceIds.map { relatedResourceId ->
                        async {
                            val relationRequest = loadRequests[relatedResourceId]
                                ?: return@async Triple(
                                    relatedResourceId,
                                    null,
                                    "No verified, fully bound relation read is available.",
                                )
                            runCatching {
                                loadInitialDynamicFormRelationRecords(
                                    services = services,
                                    session = session,
                                    descriptor = descriptor,
                                    request = relationRequest,
                                    values = request.bindingValues,
                                    cachePolicy = if (request.forceRefresh) {
                                        NextcloudApiCachePolicy.ForceNetwork
                                    } else {
                                        NextcloudApiCachePolicy.PreferCache
                                    },
                                )
                            }.fold(
                                onSuccess = { result ->
                                    val boundedRecords = DynamicFormRelationCacheState()
                                        .loadSucceeded(
                                            request = relationRequest,
                                            records = result.records,
                                            pagination = result.pagination,
                                        )
                                        .relatedRecords(listOf(relationRequest))[relatedResourceId]
                                        .orEmpty()
                                    Triple(relatedResourceId, boundedRecords, null)
                                },
                                onFailure = { failure ->
                                    if (failure is CancellationException) throw failure
                                    Triple(
                                        relatedResourceId,
                                        null,
                                        (failure.message ?: "Could not load choices.").take(
                                            MAX_DYNAMIC_BATCH_RELATION_ERROR_LENGTH,
                                        ),
                                    )
                                },
                            )
                        }
                    }.awaitAll()
                }
                NativeCollectionBatchRelationLoadResult(
                    recordsByResourceId = outcomes.mapNotNull { (resourceId, records, _) ->
                        records?.let { resourceId to it }
                    }.toMap(),
                    errorsByResourceId = outcomes.mapNotNull { (resourceId, _, error) ->
                        error?.let { resourceId to it }
                    }.toMap(),
                )
            }
        }
    }
    val recordContext = selectedRecord?.let { record ->
        val visitedStates = buildSet {
            navigationHistory.forEach { snapshot ->
                add(
                    dynamicNavigationState(
                        resourceId = snapshot.resourceId,
                        layoutId = snapshot.viewId,
                        parameterValues = snapshot.pathParameterValues,
                    ),
                )
            }
            add(
                dynamicNavigationState(
                    resourceId = selectedView.resourceId,
                    layoutId = selectedView.id,
                    parameterValues = selectedPathParameterValues,
                ),
            )
        }
        DynamicResourceRecordContext(
            resourceId = selectedRecordResourceId.orEmpty(),
            recordId = record.id,
            fieldValues = record.values,
            parameterValues = selectedPathParameterValues,
            actionSafeIdentity = record.actionSafeIdentity,
            actionBindingProvenanceValid = record.actionBindingProvenanceValid,
            currentLayoutId = selectedView.id,
            visitedStates = visitedStates,
        )
    }
    val navigationPlan = remember(descriptor, recordContext) {
        descriptor.planDynamicNavigation(recordContext)
    }
    val contextDetailResolution = remember(descriptor, schema, recordContext) {
        val context = recordContext ?: return@remember null
        schema.bestDynamicDetailView(context.resourceId)?.let { view ->
            descriptor.resolveDynamicRecordReadParameters(view.sourceActionId, context)
                ?.let { parameters -> view to parameters }
        }
    }
    val contextDetailView = contextDetailResolution?.first
    val contextDetailPathParameters = contextDetailResolution?.second.orEmpty()
    val navigationDestinations = remember(
        navigationPlan,
        schema,
        contextDetailView,
        selectedRecord,
        selectedRecordResourceId,
    ) {
        if (selectedRecord == null) {
            navigationPlan.rootDestinations.mapNotNull { destination ->
                schema.views.firstOrNull { it.id == destination.layoutId }?.let { view -> destination to view }
            }
        } else {
            buildList {
                schema.views.filter { view ->
                    view.compositeDataGrid?.parentResourceId == selectedRecordResourceId
                }.forEach { view ->
                    val grid = requireNotNull(view.compositeDataGrid)
                    val sourceDestinations = navigationPlan.contextualChildDestinations.filter { destination ->
                        destination.actionId in setOf(grid.columnSourceActionId, grid.rowSourceActionId)
                    }
                    add(
                        DynamicNavigationDestination(
                            layoutId = view.id,
                            label = view.title,
                            resourceId = view.resourceId,
                            actionId = view.sourceActionId,
                            pathParameterValues = sourceDestinations
                                .flatMap { it.pathParameterValues.entries }
                                .associate(Map.Entry<String, String>::toPair),
                        ) to view,
                    )
                }
                contextDetailView?.let { view ->
                    add(
                        DynamicNavigationDestination(
                            layoutId = view.id,
                            label = "Overview",
                            resourceId = view.resourceId,
                            actionId = view.sourceActionId,
                            pathParameterValues = contextDetailPathParameters,
                        ) to view,
                    )
                }
                navigationPlan.contextualChildDestinations
                    .filterNot { destination ->
                        selectedRecordResourceId.isDynamicMessageResource() &&
                            destination.resourceId.isMailNavigationAncestor()
                    }
                    .forEach { destination ->
                    schema.views.firstOrNull { it.id == destination.layoutId }?.let { view -> add(destination to view) }
                }
            }
        }
    }
    // Every verified read destination belongs in the adaptive, scrollable navigator. Keeping
    // technical or trash collections in the small header popup makes them unreachable on compact
    // screens once the menu exceeds the viewport. Semantic ranking still controls the preferred
    // automatic child; it must not hide an explicitly verified user destination.
    val primaryNavigationDestinations = navigationDestinations
    val secondaryNavigationDestinations =
        emptyList<Pair<DynamicNavigationDestination, ViewSpec>>()
    val selectedCollectionState = remember(schema, selectedView.sourceActionId) {
        dynamicCollectionState(schema.action(selectedView.sourceActionId))
    }
    val actionViews = remember(
        navigationPlan,
        schema,
        selectedRecord,
        selectedView.resourceId,
        selectedCollectionState,
    ) {
        val planned = if (selectedRecord == null) {
            navigationPlan.rootFormActions.filter { action ->
                action.resourceId == selectedView.resourceId &&
                    selectedCollectionState == null
            }
        } else {
            val currentResourceId = selectedRecordResourceId.orEmpty()
            navigationPlan.contextualFormActions.filter { action ->
                val spec = schema.action(action.actionId)
                    ?: return@filter false
                val formView = schema.views.singleOrNull { candidate ->
                    candidate.id == action.formId &&
                        candidate.resourceId.sameDynamicResourceAs(spec.resourceId)
                } ?: return@filter false
                val activeReadAction = schema.actions.singleOrNull { candidate ->
                    candidate.id == selectedView.sourceActionId
                }
                val actionResource = schema.resources.singleOrNull { candidate ->
                    candidate.id.sameDynamicResourceAs(spec.resourceId)
                }
                dynamicContextualFormTargetsActiveSurface(
                    action = spec,
                    formView = formView,
                    activeView = selectedView,
                    activeReadAction = activeReadAction,
                    plannedBindingValues = action.pathParameterValues,
                    selectedRecordResourceId = currentResourceId,
                    selectedCollectionState = selectedCollectionState,
                    hasEditableFileField = actionResource
                        ?.let { resource -> editableNativeFields(resource, spec) }
                        ?.any { field -> field.kind == FieldKind.file }
                        ?: false,
                    uniqueTargetResource = actionResource != null,
                )
            }
        }
        planned.mapNotNull { action ->
            schema.views.firstOrNull { it.id == action.formId }?.let { view -> action to view }
        }.distinctBy { (action, view) ->
            // Contracts from apps such as Cospend can expose the same semantic
            // action through several resource-specific form routes. Keep one
            // menu item per user-facing action instead of rendering a long list
            // of duplicate Edit/Delete entries.
            val spec = schema.action(action.actionId)
            val label = (spec?.let { dynamicHeaderActionLabel(it, view.dynamicActionLabel()) }
                ?: view.dynamicActionLabel()).trim().lowercase()
            // Deduplicate aliases only when they resolve to the same semantic
            // operation. Distinct HTTP routes remain available to the user.
            val route = spec?.let { "${it.binding.method}:${it.binding.path}" } ?: view.id
            "$label|$route"
        }
    }
    val primaryCreateAction = remember(actionViews, schema) {
        actionViews
            .filter { (action, _) -> schema.action(action.actionId)?.intent == ActionIntent.create }
            .minByOrNull { (action, _) -> dynamicQuickActionPriority(schema.action(action.actionId)) }
    }
    val overflowActionViews = remember(actionViews, primaryCreateAction) {
        actionViews.filterNot { candidate -> candidate == primaryCreateAction }
    }
    var actionMenuExpanded by remember(descriptor) { mutableStateOf(false) }
    var pendingDirectAction by remember(descriptor, discovery.versionStatus) {
        mutableStateOf<PendingDynamicDirectAction?>(null)
    }
    var directActionRunning by remember(descriptor) { mutableStateOf(false) }
    var directActionError by remember(descriptor) { mutableStateOf<String?>(null) }
    var directActionFailureState by remember(descriptor) {
        mutableStateOf<DynamicDirectActionFailurePolicy?>(null)
    }
    var contractInfoExpanded by remember(descriptor) { mutableStateOf(false) }
    val contractInfo = remember(discovery, recordContext) { discovery.toContractInfo(recordContext) }
    val dynamicActionScope = rememberCoroutineScope()

    val activePagination = paginationState?.takeIf { pagination -> pagination.viewId == selectedView.id }
    val onLoadMore = activePagination?.let { pagination ->
        {
            if (!loadingMore) {
                loadingMore = true
                loadMoreError = null
                val pagingView = selectedView
                val existingRecords = (viewState as? NativeScreenState.Ready)?.records.orEmpty()
                val values = selectedRecord?.toDynamicRuntimeValues().orEmpty() +
                    selectedPathParameterValues +
                    (pagination.spec.parameterName to pagination.nextRequestValue)
                dynamicPaginationScope.launch {
                    runCatching {
                        loadDynamicRecords(
                            services = services,
                            session = session,
                            descriptor = descriptor,
                            actionId = pagingView.sourceActionId,
                            values = values,
                            runtimeContext = values,
                            cachePolicy = dynamicReadCachePolicy,
                        )
                    }.onSuccess { pageRecords ->
                        if (selectedViewId != pagingView.id) return@onSuccess
                        val existingIds = existingRecords.mapTo(hashSetOf(), NativeRecord::id)
                        val novelRecords = pageRecords.distinctBy(NativeRecord::id)
                            .filterNot { record -> record.id in existingIds }
                        val mergedRecords = existingRecords + novelRecords
                        recordsByResourceId = recordsByResourceId + (pagingView.resourceId to mergedRecords)
                        viewState = NativeScreenState.Ready(mergedRecords)
                        paginationState = pagination.spec.toDynamicPaginationState(
                            viewId = pagingView.id,
                            lastPage = pageRecords,
                            loadedRecordCount = mergedRecords.size,
                            novelRecordCount = novelRecords.size,
                            nextPageNumber = pagination.nextPageNumber + 1,
                        )
                        sharedDynamicNativeMemoryCache.storeScreen(
                            dynamicScreenCacheKey(
                                session = session,
                                appId = descriptor.app.id,
                                viewId = pagingView.id,
                                selectedRecordId = selectedRecord?.id,
                                parameterValues = selectedPathParameterValues,
                            ),
                            DynamicScreenSnapshot(
                                records = mergedRecords,
                                relatedRecords = recordsByResourceId,
                                pagination = paginationState?.toCheckpoint(),
                            ),
                        )
                        loadingMore = false
                    }.onFailure { failure ->
                        if (selectedViewId != pagingView.id) return@onFailure
                        loadMoreError = failure.message ?: "Could not load the next page."
                        loadingMore = false
                    }
                }
            }
        }
    }

    fun rememberCurrentLocation() {
        navigationHistory = (
            navigationHistory +
                DynamicNavigationSnapshot(
                    viewId = selectedView.id,
                    resourceId = selectedView.resourceId,
                    record = selectedRecord,
                    recordResourceId = selectedRecordResourceId,
                    pathParameterValues = selectedPathParameterValues,
                )
        ).takeLast(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY)
    }

    fun navigateWithinDynamicApp() {
        val activeContextToken = selectedRecord?.dynamicContextNavigationToken(
            selectedRecordResourceId.orEmpty(),
        )
        if (activeContextToken != null && activeContextToken == contextualMenuRecordToken) {
            if (!contextualMenuOpen) {
                contextualMenuOpen = true
                return
            }
            contextualMenuOpen = false
            contextualMenuRecordToken = null
        }
        navigationHistory.lastOrNull()?.let { previous ->
            navigationHistory = navigationHistory.dropLast(1)
            selectedViewId = previous.viewId
            selectedRecord = previous.record
            selectedRecordResourceId = previous.recordResourceId
            selectedPathParameterValues = previous.pathParameterValues
            return
        }
        val contextResource = selectedRecordResourceId
        if (selectedRecord != null && contextResource != null && selectedView.resourceId != contextResource) {
            selectedViewId = schema.views.firstOrNull { view ->
                view.resourceId == contextResource && view.component == NativeComponent.detail
            }?.id ?: initialViewId
            selectedPathParameterValues = emptyMap()
            return
        }
        if (selectedRecord != null) {
            selectedRecord = null
            selectedRecordResourceId = null
            selectedPathParameterValues = emptyMap()
            selectedViewId = initialViewId
            return
        }
        if (selectedViewId != initialViewId) {
            selectedViewId = initialViewId
            return
        }
        onExit()
    }

    fun reconcileSuccessfulMutation(
        action: ActionSpec,
        leaveMutatedSurface: Boolean,
    ) {
        sharedDynamicNativeMemoryCache.invalidateScreens(session, descriptor.app.id)
        formRelationCache = DynamicFormRelationCacheState()
        val refreshPlan = schema.planDynamicMutationRefresh(
            action = action,
            selectedRecordResourceId = selectedRecordResourceId,
        )
        if (refreshPlan != null) {
            recordsByResourceId = refreshPlan.discardAffectedRelatedRecords(recordsByResourceId)
        }
        val deletedSelectedRecord = refreshPlan?.selectedRecordReconciliation ==
            DynamicSelectedRecordReconciliation.ClearDeletedSelection
        when {
            deletedSelectedRecord && navigationHistory.isNotEmpty() -> navigateWithinDynamicApp()
            deletedSelectedRecord -> {
                navigationHistory = emptyList()
                selectedRecord = null
                selectedRecordResourceId = null
                selectedPathParameterValues = emptyMap()
                selectedViewId = initialViewId
            }
            leaveMutatedSurface -> navigateWithinDynamicApp()
        }
        loadAttempt += 1
    }

    fun selectDynamicAction(
        action: dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationFormAction,
        view: ViewSpec,
    ) {
        actionMenuExpanded = false
        val actionSpec = schema.action(action.actionId)
        val editableFieldCount = actionSpec?.let { spec ->
            schema.resource(spec.resourceId)?.let { resource ->
                editableNativeFields(resource, spec).size
            }
        } ?: 0
        if (
            actionSpec != null &&
            dynamicActionUiMode(actionSpec, editableFieldCount) == DynamicActionUiMode.ConfirmDirectly
        ) {
            val label = selectedRecord?.values?.entries?.firstOrNull { (key, value) ->
                key.lowercase().filter(Char::isLetterOrDigit) in setOf("name", "title", "displayname") &&
                    !value.isNullOrBlank()
            }?.value ?: selectedRecord?.id ?: schema.resource(actionSpec.resourceId)?.name ?: "item"
            directActionError = null
            directActionFailureState = null
            pendingDirectAction = PendingDynamicDirectAction(
                action = actionSpec,
                values = action.pathParameterValues,
                targetLabel = label,
            )
            return
        }
        rememberCurrentLocation()
        selectedPathParameterValues = action.pathParameterValues
        selectedViewId = view.id
    }

    fun selectCollectionDestination(
        destination: DynamicNavigationDestination,
        view: ViewSpec,
    ) {
        actionMenuExpanded = false
        contextualMenuOpen = false
        val selection = planDynamicCollectionDestinationSelection(
            isTopLevelDestination = selectedRecord == null,
            destinationPathParameterValues = destination.pathParameterValues,
        )
        if (selection.clearHierarchyContext) {
            navigationHistory = emptyList()
            selectedRecord = null
            selectedRecordResourceId = null
            paginationState = null
            loadingMore = false
            loadMoreError = null
        }
        selectedPathParameterValues = selection.pathParameterValues
        selectedViewId = view.id
    }

    val hasCollectionHierarchyBack = navigationHistory.isNotEmpty() || selectedRecord != null
    PlatformBackHandler(enabled = true, onBack = ::navigateWithinDynamicApp)
    val showFallbackRecordDetail = shouldShowDynamicRecordFallbackDetail(
        viewResourceId = selectedView.resourceId,
        viewComponent = selectedView.component,
        selectedRecord = selectedRecord,
        selectedRecordResourceId = selectedRecordResourceId,
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactLandscape = shouldUseCompactDynamicAppChrome(maxWidth.value, maxHeight.value)
        val collectionDestinationEntries = remember(
            primaryNavigationDestinations,
            descriptor.app.name,
        ) {
            primaryNavigationDestinations
                .distinctBy { (_, view) -> view.id }
                .map { (destination, view) ->
                    destination to NextcloudCollectionDestination(
                        id = view.id,
                        label = destination.label
                            .dynamicUiLabel(descriptor.app.name)
                            .ifBlank { view.dynamicNavigationLabel(descriptor.app.name) },
                        accessibilityId = destination.actionId,
                    )
                }
        }
        val selectedCollectionDestinationId = collectionDestinationEntries
            .firstOrNull { (_, item) -> item.id == selectedView.id }
            ?.second
            ?.id
        val collectionNavigationModel = remember(
            collectionDestinationEntries,
            selectedCollectionDestinationId,
        ) {
            NextcloudCollectionNavigationModel.create(
                destinations = collectionDestinationEntries.map { (_, item) -> item },
                selectedDestinationId = selectedCollectionDestinationId,
            )
        }
        val workspaceCapabilities = LocalNextcloudWorkspaceCapabilities.current
        val collectionNavigationHost = if (workspaceCapabilities.isDesktop) {
            NextcloudCollectionNavigationHost.Desktop
        } else {
            NextcloudCollectionNavigationHost.AdaptiveAndroid
        }
        val collectionNavigationMode = resolveNextcloudCollectionNavigationMode(
            host = collectionNavigationHost,
            availableWidthDp = maxWidth.value.toInt(),
            destinationCount = collectionNavigationModel.destinations.size,
        )
        val collectionSubtitle = selectedRecord?.dynamicContextSubtitle(
            selectedView,
            schema.resource(selectedRecordResourceId.orEmpty())?.name,
        ) ?: selectedView.dynamicRootSubtitle(descriptor.app.name)
        val activeContextToken = selectedRecord?.dynamicContextNavigationToken(
            selectedRecordResourceId.orEmpty(),
        )
        val showContextDestinationMenu = contextualMenuOpen &&
            contextualMenuRecordToken == activeContextToken &&
            shouldShowDynamicContextDestinationMenu(
                collectionDestinationEntries.map { (_, destination) -> destination.id },
            )

        NextcloudCollectionWorkspaceScaffold(
            model = collectionNavigationModel,
            mode = collectionNavigationMode,
            title = descriptor.app.name,
            subtitle = if (showContextDestinationMenu) {
                selectedRecord?.dynamicContextLabel()
            } else {
                collectionSubtitle
            },
            onBack = ::navigateWithinDynamicApp,
            hasHierarchyBack = hasCollectionHierarchyBack,
            onDestinationSelected = { selected ->
                collectionDestinationEntries
                    .firstOrNull { (_, item) -> item.id == selected.id }
                    ?.first
                    ?.let { destination ->
                        schema.views.firstOrNull { view -> view.id == selected.id }
                            ?.let { view -> selectCollectionDestination(destination, view) }
                    }
            },
            compactHeader = compactLandscape,
            destinationIcon = { destination ->
                schema.views.firstOrNull { view -> view.id == destination.id }
                    ?.dynamicCollectionNavigationIcon()
            },
            headerActions = {
                if (!showContextDestinationMenu) {
                    primaryCreateAction?.let { (action, view) ->
                        val actionSpec = schema.action(action.actionId)
                        val label = actionSpec?.let { spec ->
                            dynamicHeaderActionLabel(spec, view.dynamicActionLabel())
                        } ?: view.dynamicActionLabel()
                        IconButton(
                            onClick = { selectDynamicAction(action, view) },
                            modifier = Modifier.semantics {
                                contentDescription = "$label; action ${action.actionId}"
                            },
                        ) {
                            Icon(
                                NextcloudIcons.Add,
                                contentDescription = null,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { actionMenuExpanded = true }) {
                            Icon(NextcloudIcons.More, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false },
                        ) {
                            overflowActionViews.forEach { (action, view) ->
                                val actionSpec = schema.action(action.actionId)
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            actionSpec?.let { spec ->
                                                dynamicHeaderActionLabel(
                                                    spec,
                                                    view.dynamicActionLabel(),
                                                )
                                            } ?: view.dynamicActionLabel(),
                                        )
                                    },
                                    onClick = { selectDynamicAction(action, view) },
                                )
                            }
                            if (
                                overflowActionViews.isNotEmpty() &&
                                secondaryNavigationDestinations.isNotEmpty()
                            ) {
                                HorizontalDivider()
                            }
                            secondaryNavigationDestinations.forEach { (destination, view) ->
                                val baseLabel = destination.label.dynamicUiLabel(descriptor.app.name)
                                val duplicate = secondaryNavigationDestinations.count {
                                        (candidate, _) ->
                                    candidate.label.dynamicUiLabel(descriptor.app.name)
                                        .equals(baseLabel, ignoreCase = true)
                                } > 1
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            dynamicSecondaryDestinationLabel(
                                                destinationLabel = baseLabel,
                                                resourceLabel = schema.resource(view.resourceId)?.name
                                                    ?: view.resourceId,
                                                duplicate = duplicate,
                                            ),
                                        )
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription =
                                            "Open destination ${destination.actionId}"
                                    },
                                    onClick = {
                                        selectCollectionDestination(destination, view)
                                    },
                                )
                            }
                            if (
                                overflowActionViews.isNotEmpty() ||
                                secondaryNavigationDestinations.isNotEmpty()
                            ) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text("Contract info") },
                                onClick = {
                                    actionMenuExpanded = false
                                    contractInfoExpanded = true
                                },
                            )
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showContextDestinationMenu) {
                DynamicContextDestinationMenu(
                    recordLabel = requireNotNull(selectedRecord).dynamicContextLabel(),
                    destinations = collectionDestinationEntries,
                    schema = schema,
                    onDestinationSelected = { destination, view ->
                        selectCollectionDestination(destination, view)
                    },
                )
            } else Column(modifier = Modifier.fillMaxSize()) {
            if (discovery.acquisition == DynamicDescriptorAcquisition.MetadataFallback) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Column(
                    modifier = Modifier.padding(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    Text("No verified native API", style = MaterialTheme.typography.titleSmall)
                    Text(
                        discovery.diagnostics.lastOrNull()
                            ?: "No usable API contract or verified static read routes were found for this app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onRetryDiscovery) {
                        Text("Retry discovery")
                    }
                }
            }
        }
            if (failedFormRelationRequests.isNotEmpty()) {
                val failedResources = failedFormRelationRequests
                    .map { request ->
                        schema.resource(request.plan.resourceId)?.name ?: request.plan.resourceId
                    }
                    .distinct()
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Large,
                        vertical = NextcloudSpacing.Small,
                    ),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Small),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (failedResources.size == 1) {
                                "Could not load ${failedResources.single()} choices. " +
                                    "Other form fields remain available."
                            } else {
                                "Some choices could not be loaded. Other form fields remain available."
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                formRelationCache = formRelationCache.retry(formRelationRequests)
                                formRelationLoadAttempt += 1
                            },
                        ) {
                            Text("Retry choices")
                        }
                    }
                }
            }
            GenericNativeAppScreen(
                schema = schema,
                view = selectedView,
                state = viewState,
                actionExecutor = executor,
                selectedRecordId = selectedRecord?.id,
                showSelectedRecordDetail = showFallbackRecordDetail,
                datasetContext = NativeDatasetContext(
                    parentResourceId = selectedRecordResourceId,
                    parentRecord = selectedRecord,
                    bindingValues = datasetBindingValues,
                    relatedRecords = datasetRelatedRecords,
                    relatedRecordPaging = relatedRecordPaging,
                ),
                mutationReconciliationGeneration = mutationReconciliationGeneration,
                collectionBatchRelationLoader = collectionBatchRelationLoader,
                filePicker = dynamicFilePicker,
            onSelectRecord = selectedView.takeIf {
                it.component != NativeComponent.detail && it.component != NativeComponent.form
            }?.let {
                { record ->
                    rememberCurrentLocation()
                    val selectedParentResourceId = record.effectiveNativeResourceId(selectedView.resourceId)
                    val inheritedParameters = inheritDynamicParentParameters(
                        selectedPathParameterValues = selectedPathParameterValues,
                        runtimeValues = runtimeValues,
                    )
                    val nextContext = DynamicResourceRecordContext(
                        resourceId = selectedParentResourceId,
                        recordId = record.id,
                        fieldValues = record.values,
                        parameterValues = inheritedParameters,
                        actionSafeIdentity = record.actionSafeIdentity,
                        actionBindingProvenanceValid = record.actionBindingProvenanceValid,
                        currentLayoutId = selectedView.id,
                    )
                    val nextPlan = descriptor.planDynamicNavigation(nextContext)
                    val compositeTarget = schema.views.firstOrNull { candidate ->
                        candidate.compositeDataGrid?.parentResourceId == selectedParentResourceId
                    }
                    val compositeActionIds = compositeTarget?.compositeDataGrid?.let { grid ->
                        setOf(grid.columnSourceActionId, grid.rowSourceActionId)
                    }.orEmpty()
                    val detailResolution = schema.bestDynamicDetailView(selectedParentResourceId)
                        ?.takeIf { target -> target.id != selectedView.id }
                        ?.let { target ->
                            descriptor.resolveDynamicRecordReadParameters(target.sourceActionId, nextContext)
                                ?.let { parameters -> target to parameters }
                    }
                    val detailTarget = detailResolution?.first
                    val directChild = descriptor.singleSafeContextualChild(
                        context = nextContext,
                        hasDedicatedSurface = compositeTarget != null || detailTarget != null,
                    )
                    val preferredCollectionChild = descriptor.preferredSemanticContextualChild(nextContext)
                    val primaryContentTarget = primaryDynamicContentDestination(
                        parentResourceId = selectedParentResourceId,
                        destinations = nextPlan.contextualChildDestinations,
                    )
                    val contextualSurfaceIds = buildSet {
                        nextPlan.contextualChildDestinations.mapTo(this) { destination ->
                            destination.layoutId
                        }
                        compositeTarget?.id?.let(::add)
                        detailTarget?.id?.let(::add)
                    }
                    val showDestinationMenu = shouldShowDynamicContextDestinationMenu(
                        contextualSurfaceIds.toList(),
                    )
                    val nextViewId = if (showDestinationMenu) {
                        selectedViewId
                    } else {
                        compositeTarget?.id
                            ?: primaryContentTarget?.layoutId
                            ?: preferredCollectionChild?.layoutId
                            ?: detailTarget?.id
                            ?: directChild?.layoutId
                            ?: selectedViewId
                    }
                    val explicitTargetParameters = primaryContentTarget?.pathParameterValues
                        ?: preferredCollectionChild?.pathParameterValues
                        ?: directChild?.pathParameterValues
                        ?: detailResolution?.second
                    val fallbackTargetParameters = inheritedParameters +
                        nextPlan.contextualChildDestinations
                            .filter { destination -> destination.actionId in compositeActionIds }
                            .flatMap { destination -> destination.pathParameterValues.entries }
                            .associate(Map.Entry<String, String>::toPair)
                    selectedRecord = record
                    selectedRecordResourceId = selectedParentResourceId
                    contextualMenuRecordToken = if (showDestinationMenu) {
                        record.dynamicContextNavigationToken(selectedParentResourceId)
                    } else {
                        null
                    }
                    contextualMenuOpen = showDestinationMenu
                    selectedPathParameterValues = if (showDestinationMenu) {
                        inheritedParameters
                    } else {
                        resolveDynamicRecordSelectionParameters(
                            currentViewId = selectedViewId.orEmpty(),
                            nextViewId = nextViewId.orEmpty(),
                            currentParameters = selectedPathParameterValues,
                            explicitTargetParameters = explicitTargetParameters,
                            fallbackTargetParameters = fallbackTargetParameters,
                        )
                    }
                    selectedViewId = nextViewId
                }
                },
                onActionSucceeded = { action ->
                    reconcileSuccessfulMutation(
                        action = action,
                        leaveMutatedSurface = true,
                    )
                },
                onInlineActionSucceeded = { action ->
                    reconcileSuccessfulMutation(
                        action = action,
                        leaveMutatedSurface = false,
                    )
                },
                showCollectionCreateAction = primaryCreateAction == null &&
                    selectedCollectionState == null,
                onOpenLink = services::openExternalUrl,
                imageLoader = imageLoader,
                audioPlayer = audioSourceCapability?.let {
                    NativeAudioRecordPlayer { resource, records, selected, collectionContext ->
                        val queue = startNativeAudioQueue(
                            tracks = records.mapNotNull { record ->
                                nativeAudioTrack(resource, record, collectionContext)
                            },
                            selectedRecordId = selected.id,
                        )
                        playCurrentAudioTrack(queue)
                    }
                },
                mediaArtworkResolver = mediaArtworkResolver,
                onLoadMore = onLoadMore.takeUnless { showFallbackRecordDetail },
                loadingMore = loadingMore,
                loadMoreError = loadMoreError,
                modifier = Modifier.weight(1f),
            )
            if (audioSourceCapability != null && audioQueue.currentTrack != null) {
                NativeAudioMiniPlayer(
                    queue = audioQueue,
                    engineState = audioEngineState,
                    artworkRelativePath = audioQueue.currentTrack
                        ?.let { track -> audioSourceCapability.source(track)?.artworkRelativePath },
                    imageLoader = imageLoader,
                    onPrevious = {
                        if (audioEngineState.positionMillis > AUDIO_PREVIOUS_RESTART_THRESHOLD_MILLIS) {
                            audioEngine.seekTo(0)
                        } else {
                            playCurrentAudioTrack(audioQueue.previous())
                        }
                    },
                    onTogglePlayback = {
                        when (audioEngineState.status) {
                            NativeAudioEngineStatus.Playing -> audioEngine.pause()
                            NativeAudioEngineStatus.Loading -> audioEngine.stop()
                            NativeAudioEngineStatus.Paused -> audioEngine.resume()
                            NativeAudioEngineStatus.Ended,
                            NativeAudioEngineStatus.Error,
                            NativeAudioEngineStatus.Idle,
                            -> playCurrentAudioTrack(audioQueue)
                        }
                    },
                    onNext = {
                        val advanced = audioQueue.next()
                        if (advanced.currentTrack != null) playCurrentAudioTrack(advanced)
                    },
                    onSelectTrack = { index ->
                        playCurrentAudioTrack(audioQueue.copy(currentIndex = index))
                    },
                    onSeek = audioEngine::seekTo,
                    onStop = {
                        audioEngine.stop()
                        audioQueue = NativeAudioQueueState()
                    },
                )
            }
        }
    }
    }
    if (contractInfoExpanded) {
        DynamicContractInfoDialog(
            info = contractInfo,
            onDismiss = { contractInfoExpanded = false },
        )
    }
    pendingDirectAction?.let { pending ->
        val outcomeUnknown = directActionFailureState?.requiresReconciliation == true
        AlertDialog(
            onDismissRequest = {
                if (!directActionRunning) {
                    pendingDirectAction = null
                    directActionError = null
                    directActionFailureState = null
                }
            },
            title = {
                Text(
                    if (outcomeUnknown) {
                        "${dynamicHeaderActionLabel(pending.action, pending.action.label)} result unknown"
                    } else {
                        dynamicDirectActionTitle(pending.action, pending.targetLabel)
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    if (outcomeUnknown) {
                        Text(
                            "The server may already have completed this action. The view is being refreshed " +
                                "to reconcile the result. Review the refreshed state before trying again.",
                        )
                    } else {
                        Text(dynamicDirectActionDescription(pending.action))
                    }
                    directActionError?.let { error ->
                        Text(
                            error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !directActionRunning,
                    onClick = {
                        pendingDirectAction = null
                        directActionError = null
                        directActionFailureState = null
                    },
                ) {
                    Text(if (outcomeUnknown) "Close" else "Cancel")
                }
            },
            confirmButton = {
                if (directActionFailureState?.retryAllowed != false) {
                    Button(
                        enabled = !directActionRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            directActionRunning = true
                            directActionError = null
                            directActionFailureState = null
                            dynamicActionScope.launch {
                                when (
                                    val result = executor.execute(
                                        NativeActionRequest.Submit(
                                            action = pending.action,
                                            values = pending.values,
                                            confirmed = true,
                                        ),
                                    )
                                ) {
                                    is NativeActionExecutionResult.Success -> {
                                        pendingDirectAction = null
                                        reconcileSuccessfulMutation(
                                            action = pending.action,
                                            leaveMutatedSurface = true,
                                        )
                                    }
                                    is NativeActionExecutionResult.Failure -> {
                                        directActionError = result.message
                                        val failurePolicy = dynamicDirectActionFailurePolicy(result.outcome)
                                        directActionFailureState = failurePolicy
                                        if (failurePolicy.requiresReconciliation) {
                                            reconcileSuccessfulMutation(
                                                action = pending.action,
                                                leaveMutatedSurface = true,
                                            )
                                        }
                                    }
                                }
                                directActionRunning = false
                            }
                        },
                    ) {
                        if (directActionRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                if (directActionFailureState == null) {
                                    dynamicDirectActionConfirmLabel(pending.action)
                                } else {
                                    "Try again"
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

internal fun shouldUseCompactDynamicAppChrome(widthDp: Float, heightDp: Float): Boolean =
    widthDp > heightDp && heightDp < 600f

/**
 * Shared contextual chrome for discovered native app surfaces.
 *
 * The runtime and deterministic captures both render this component. Loading, route resolution,
 * action execution, and contract diagnostics remain owned by their respective hosts.
 */
@Composable
internal fun DynamicAppChromeHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    compact: Boolean,
    onContractInfo: () -> Unit,
    trailingContent: @Composable () -> Unit = {},
) {
    ScreenHeader(
        title = title,
        subtitle = subtitle,
        onBack = onBack,
        compact = compact,
        trailingContent = trailingContent,
    )
    if (!compact) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onContractInfo) {
                Text("Contract info")
            }
        }
    }
}

@Composable
private fun NativeAudioMiniPlayer(
    queue: NativeAudioQueueState,
    engineState: NativeAudioEngineState,
    artworkRelativePath: String?,
    imageLoader: NativeImageLoader?,
    onPrevious: () -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSeek: (Long) -> Unit,
    onStop: () -> Unit,
) {
    val track = queue.currentTrack ?: return
    val duration = engineState.durationMillis ?: track.durationMillis
    var queueExpanded by remember(queue.tracks) { mutableStateOf(false) }
    val artwork by produceState<ImageBitmap?>(
        initialValue = null,
        artworkRelativePath,
        imageLoader,
    ) {
        value = if (artworkRelativePath != null && imageLoader != null) {
            imageLoader.load(artworkRelativePath)
        } else {
            null
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Small,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    color = NextcloudTheme.colors.appIconContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    if (artwork != null) {
                        Image(
                            bitmap = artwork!!,
                            contentDescription = "Album artwork",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                NextcloudIcons.Play,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).clickable {
                        queueExpanded = !queueExpanded
                    },
                ) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    listOfNotNull(track.artist, track.album).distinct().joinToString(" · ")
                        .takeIf(String::isNotBlank)
                        ?.let { subtitle ->
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                }
                Text(
                    "${(queue.currentIndex ?: 0) + 1}/${queue.tracks.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onPrevious) {
                    Icon(NextcloudIcons.SkipPrevious, contentDescription = "Previous track")
                }
                IconButton(onClick = onTogglePlayback) {
                    if (engineState.status == NativeAudioEngineStatus.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (engineState.status == NativeAudioEngineStatus.Playing) {
                                NextcloudIcons.Pause
                            } else {
                                NextcloudIcons.Play
                            },
                            contentDescription = if (engineState.status == NativeAudioEngineStatus.Playing) {
                                "Pause"
                            } else {
                                "Play"
                            },
                        )
                    }
                }
                IconButton(
                    onClick = onNext,
                    enabled = (queue.currentIndex ?: 0) < queue.tracks.lastIndex,
                ) {
                    Icon(NextcloudIcons.SkipNext, contentDescription = "Next track")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { queueExpanded = !queueExpanded }) {
                    Text(if (queueExpanded) "Hide queue" else "Queue")
                }
                TextButton(onClick = onStop) {
                    Text("Stop")
                }
            }
            if (duration != null && duration > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    Text(
                        formatAudioPosition(engineState.positionMillis),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = engineState.positionMillis.coerceIn(0, duration).toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(formatAudioPosition(duration), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (queueExpanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 224.dp),
                ) {
                    indexedListItems(
                        items = queue.tracks,
                        key = { _, queuedTrack -> queuedTrack.recordId },
                    ) { index, queuedTrack ->
                        val selected = index == queue.currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectTrack(index) }
                                .padding(
                                    horizontal = NextcloudSpacing.Small,
                                    vertical = NextcloudSpacing.Small,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        ) {
                            Text(
                                (index + 1).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    queuedTrack.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                listOfNotNull(queuedTrack.artist, queuedTrack.album)
                                    .distinct()
                                    .joinToString(" · ")
                                    .takeIf(String::isNotBlank)
                                    ?.let { subtitle ->
                                        Text(
                                            subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                            }
                            queuedTrack.durationMillis?.let { trackDuration ->
                                Text(
                                    formatAudioPosition(trackDuration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            engineState.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatAudioPosition(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private const val AUDIO_PREVIOUS_RESTART_THRESHOLD_MILLIS = 3_000L

/** Exact resources beat semantic singular/plural aliases such as random `album` versus `albums`. */
internal fun dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema.bestDynamicDetailView(
    resourceId: String,
): ViewSpec? = views.asSequence()
    .filter { view ->
        view.component == NativeComponent.detail && view.sourceActionId.isNotBlank() &&
            view.resourceId.sameDynamicResourceAs(resourceId)
    }
    .sortedWith(
        compareByDescending<ViewSpec> { view -> view.resourceId == resourceId }
            .thenByDescending { view -> action(view.sourceActionId)?.binding?.requiredPathParameterNames?.isNotEmpty() == true }
            .thenBy(ViewSpec::id),
    )
    .firstOrNull()

internal fun dynamicAppAssetRequest(appId: String, assetPath: String): NextcloudApiRequest? {
    if (appId.isBlank() || appId.any { !it.isLetterOrDigit() && it !in setOf('_', '-') }) return null
    if ('#' in assetPath) return null
    val path = assetPath.substringBefore('?')
    val allowed = path.startsWith("/apps/$appId/") || path.startsWith("/index.php/apps/$appId/")
    if (!allowed) return null
    val query = assetPath.substringAfter('?', "")
    val parameters = if (query.isBlank()) {
        emptyMap()
    } else {
        val pairs = query.split('&')
        if (pairs.size > 8) return null
        buildMap {
            pairs.forEach { pair ->
                val separator = pair.indexOf('=')
                if (separator !in 1 until pair.lastIndex) return null
                val key = pair.take(separator)
                val value = pair.drop(separator + 1)
                if (!key.isSafeDynamicAssetQueryPart() || !value.isSafeDynamicAssetQueryPart()) return null
                if (put(key, value) != null) return null
            }
        }
    }
    return runCatching { NextcloudApiRequest(
        method = NextcloudApiMethod.GET,
        relativePath = path,
        queryParameters = parameters,
        ocsApiRequest = true,
        maximumResponseBytes = 8L * 1024L * 1024L,
    ).requireSafe() }.getOrNull()
}

internal fun stableDynamicAssetCacheKey(assetPath: String): String {
    val pathSegments = assetPath.substringBefore('?')
        .split('/')
        .filter(String::isNotBlank)
        .let { segments -> if (segments.firstOrNull() == "index.php") segments.drop(1) else segments }
    val query = assetPath.substringAfter('?', "")
        .split('&')
        .filter(String::isNotBlank)
        .sorted()
        .joinToString("&")
    val path = pathSegments.joinToString("/", prefix = "/")
    return if (query.isBlank()) path else "$path?$query"
}

private fun String.isSafeDynamicAssetQueryPart(): Boolean =
    length in 1..128 && all { character ->
        character.isLetterOrDigit() || character in setOf('-', '_', '.', '~')
    }

internal fun String?.isSupportedDynamicArtworkContentType(): Boolean {
    val mime = this?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return mime.startsWith("image/") || mime == "application/octet-stream"
}

private const val MAX_DYNAMIC_ARTWORK_DECODED_BYTES = 32L * 1024L * 1024L
private const val MAX_DYNAMIC_ARTWORK_DIMENSION = 1_024
private const val ARGB_8888_BYTES_PER_PIXEL = 4L

@Composable
private fun DynamicContractInfoDialog(
    info: DynamicContractInfo,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contract info") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                ContractInfoSection("Acquisition", info.acquisition)
                ContractInfoSection("App version", info.appVersion)
                ContractInfoSection("Source spec", info.sourceSpecFile)
                ContractInfoSection("Descriptor", info.countSummary())
                ContractInfoSection("Resources", info.resourceIds.safeContractList())
                ContractInfoSection("Layouts", info.layoutIds.safeContractList())
                ContractInfoSection("Links", info.linkIds.safeContractList())
                ContractInfoSection("Actions", info.actionIds.safeContractList())
                ContractInfoSection(
                    "Diagnostics",
                    info.diagnosticCodes.ifEmpty { listOf("none") }.joinToString(", "),
                )
                if (info.childCandidates.isNotEmpty()) {
                    Text("Current record navigation", style = MaterialTheme.typography.titleSmall)
                    info.childCandidates.forEach { child ->
                        val missing = child.missingContextParameters.takeIf(List<String>::isNotEmpty)
                            ?.joinToString(", ", prefix = " · needs ")
                            .orEmpty()
                        Text(
                            "${child.resourceId} · ${child.reasonLabel()}$missing",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    ContractInfoSection("Current record navigation", "No child collection candidates")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ContractInfoSection(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun List<String>.safeContractList(): String = ifEmpty { listOf("none") }.joinToString(", ")

internal data class DynamicNavigationSnapshot(
    val viewId: String,
    val resourceId: String,
    val record: NativeRecord?,
    val recordResourceId: String?,
    val pathParameterValues: Map<String, String>,
)

@Serializable
internal data class SavedDynamicNavigationSnapshot(
    val viewId: String,
    val resourceId: String,
    val recordId: String? = null,
    val recordResourceId: String? = null,
    val pathParameterValues: Map<String, String> = emptyMap(),
)

@Serializable
private data class DynamicAppNavigationState(
    val selectedViewId: String? = null,
    val selectedRecord: NativeRecord? = null,
    val selectedRecordResourceId: String? = null,
    val pathParameterValues: Map<String, String> = emptyMap(),
    val history: List<SavedDynamicNavigationSnapshot> = emptyList(),
)

internal fun saveDynamicNavigationHistory(
    history: List<DynamicNavigationSnapshot>,
): List<SavedDynamicNavigationSnapshot> = history
    .takeLast(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY)
    .mapNotNull(DynamicNavigationSnapshot::toSavedDynamicNavigationSnapshot)

internal fun restoreDynamicNavigationHistory(
    history: List<SavedDynamicNavigationSnapshot>,
): List<DynamicNavigationSnapshot> = history
    .takeLast(MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY)
    .mapNotNull(SavedDynamicNavigationSnapshot::toDynamicNavigationSnapshot)

private fun DynamicNavigationSnapshot.toSavedDynamicNavigationSnapshot(): SavedDynamicNavigationSnapshot? {
    if (!viewId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    if (!resourceId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    val savedParameters = pathParameterValues.toSavedDynamicNavigationParameters() ?: return null
    val savedRecordId = record?.id?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_RECORD_ID_CHARS) }
            ?: return null
    }
    val savedRecordResourceId = recordResourceId?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS) }
            ?: return null
    }?.takeIf { savedRecordId != null }
    return SavedDynamicNavigationSnapshot(
        viewId = viewId,
        resourceId = resourceId,
        recordId = savedRecordId,
        recordResourceId = savedRecordResourceId,
        pathParameterValues = savedParameters,
    )
}

private fun SavedDynamicNavigationSnapshot.toDynamicNavigationSnapshot(): DynamicNavigationSnapshot? {
    if (!viewId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    if (!resourceId.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS)) return null
    val restoredParameters = pathParameterValues.toSavedDynamicNavigationParameters() ?: return null
    val restoredRecordId = recordId?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_RECORD_ID_CHARS) }
            ?: return null
    }
    val restoredRecordResourceId = recordResourceId?.let { value ->
        value.takeIf { it.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS) }
            ?: return null
    }?.takeIf { restoredRecordId != null }
    return DynamicNavigationSnapshot(
        viewId = viewId,
        resourceId = resourceId,
        record = restoredRecordId?.let { recordId ->
            NativeRecord(
                id = recordId,
                values = emptyMap(),
                // A persisted identity can reload a detail route, but only the authoritative
                // read response may authorize a mutation after process restoration.
                actionSafeIdentity = false,
            )
        },
        recordResourceId = restoredRecordResourceId,
        pathParameterValues = restoredParameters,
    )
}

private fun Map<String, String>.toSavedDynamicNavigationParameters(): Map<String, String>? {
    if (size > MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETERS) return null
    if (any { (key, value) ->
            !key.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_NAME_CHARS) ||
                !value.isSafeSavedDynamicNavigationValue(MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_VALUE_CHARS)
        }
    ) {
        return null
    }
    return toMap()
}

private fun String.isSafeSavedDynamicNavigationValue(maximumChars: Int): Boolean =
    isNotBlank() && length <= maximumChars && none(Char::isISOControl)

internal const val MAX_SAVED_DYNAMIC_NAVIGATION_HISTORY = 16
private const val MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETERS = 8
private const val MAX_SAVED_DYNAMIC_NAVIGATION_ID_CHARS = 128
private const val MAX_SAVED_DYNAMIC_RECORD_ID_CHARS = 256
private const val MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_NAME_CHARS = 64
private const val MAX_SAVED_DYNAMIC_NAVIGATION_PARAMETER_VALUE_CHARS = 256

internal data class DynamicCollectionDestinationSelectionPlan(
    val pathParameterValues: Map<String, String>,
    val clearHierarchyContext: Boolean,
)

internal fun planDynamicCollectionDestinationSelection(
    isTopLevelDestination: Boolean,
    destinationPathParameterValues: Map<String, String>,
): DynamicCollectionDestinationSelectionPlan = DynamicCollectionDestinationSelectionPlan(
    pathParameterValues = destinationPathParameterValues.toMap(),
    clearHierarchyContext = isTopLevelDestination,
)

private data class DynamicPaginationState(
    val viewId: String,
    val spec: DynamicPaginationSpec,
    val nextPageNumber: Int = 2,
    val nextRequestValue: String,
)

private fun DynamicPaginationState.toCheckpoint(): DynamicPaginationCheckpoint = DynamicPaginationCheckpoint(
    nextPageNumber = nextPageNumber,
    nextRequestValue = nextRequestValue,
)

private fun DynamicPaginationSpec.toDynamicPaginationState(
    viewId: String,
    lastPage: List<NativeRecord>,
    loadedRecordCount: Int = lastPage.size,
    novelRecordCount: Int = lastPage.size,
    nextPageNumber: Int = 2,
): DynamicPaginationState? {
    if (!canContinue(lastPage.size, novelRecordCount)) return null
    val nextValue = nextValue(nextPageNumber, loadedRecordCount, lastPage) ?: return null
    return DynamicPaginationState(viewId, this, nextPageNumber, nextValue)
}

private fun String.dynamicUiLabel(appName: String): String {
    val cleaned = removePrefix("API ").removePrefix("Api ").removePrefix("api ").trim()
    return if (cleaned.equals("general", ignoreCase = true)) appName else cleaned
}

private fun String.dynamicResourceWords(): Set<String> = lowercase()
    .map { character -> if (character.isLetterOrDigit()) character else ' ' }
    .joinToString("")
    .split(' ')
    .filter(String::isNotBlank)
    .toSet()

private fun String?.isDynamicMessageResource(): Boolean = this?.dynamicResourceWords().orEmpty().any { word ->
    word in setOf("message", "messages", "email", "emails", "thread", "threads")
}

internal fun primaryDynamicContentDestination(
    parentResourceId: String,
    destinations: List<DynamicNavigationDestination>,
): DynamicNavigationDestination? {
    val parentWords = parentResourceId.dynamicResourceWords()
    return when {
        parentResourceId.isDynamicMessageResource() -> destinations.firstOrNull { destination ->
            destination.resourceId.dynamicResourceWords().any { word ->
                word in setOf("body", "content", "messagebody", "htmlbody")
            }
        }
        parentWords.any { it in setOf("board", "boards", "kanban", "workflow") } ->
            destinations.firstOrNull { destination ->
                destination.resourceId.dynamicResourceWords().any { word ->
                    word in setOf("stack", "stacks", "lane", "lanes", "column", "columns")
                }
            }
        parentWords.any { it in setOf("project", "projects", "budget", "budgets", "ledger", "ledgers") } ->
            destinations.firstOrNull { destination ->
                destination.resourceId.dynamicResourceWords().any { word ->
                    word in setOf(
                        "bill",
                        "bills",
                        "expense",
                        "expenses",
                        "transaction",
                        "transactions",
                        "entry",
                        "entries",
                    )
                }
            }
        else -> null
    }
}

private fun String.isMailNavigationAncestor(): Boolean = dynamicResourceWords().any { word ->
    word in setOf(
        "account", "accounts", "alias", "aliases", "mailbox", "mailboxes",
        "certificate", "certificates", "internaladdress", "internaladdresses",
    )
}

internal fun shouldShowDynamicContextDestinationMenu(destinationIds: List<String>): Boolean =
    destinationIds.filter(String::isNotBlank).distinct().size >= 2

private fun NativeRecord.dynamicContextNavigationToken(resourceId: String): String =
    "$resourceId\u0000$id"

private fun NativeRecord.dynamicContextLabel(): String =
    listOf("name", "title", "displayName", "subject", "what", "merchant", "label", "description")
        .firstNotNullOfOrNull { key ->
            (displayValues[key] ?: values[key])?.takeIf(String::isNotBlank)
        }
        ?: id

private fun NativeRecord.dynamicContextSubtitle(view: ViewSpec, resourceName: String?): String {
    val title = dynamicContextLabel()
    val section = view.dynamicNavigationLabel(resourceName.orEmpty()).takeIf(String::isNotBlank)
    return listOfNotNull(title, section?.takeUnless { it.equals(title, ignoreCase = true) }).joinToString(" · ")
}

@Composable
internal fun DynamicContextDestinationMenu(
    recordLabel: String,
    destinations: List<Pair<DynamicNavigationDestination, NextcloudCollectionDestination>>,
    schema: NativeAppSchema,
    onDestinationSelected: (DynamicNavigationDestination, ViewSpec) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = NextcloudSpacing.XLarge,
                top = NextcloudSpacing.Large,
                end = NextcloudSpacing.XLarge,
                bottom = NextcloudSpacing.Medium,
            ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                "Choose a section",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Go directly to the part of $recordLabel you need.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(
                start = NextcloudSpacing.XLarge,
                top = NextcloudSpacing.Small,
                end = NextcloudSpacing.XLarge,
                bottom = NextcloudSpacing.XXLarge,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            items(destinations, key = { (_, destination) -> destination.id }) { (planned, destination) ->
                val view = schema.views.singleOrNull { candidate -> candidate.id == destination.id }
                    ?: return@items
                val resourceLabel = schema.resource(view.resourceId)?.name
                    ?.takeUnless { name -> name.equals(destination.label, ignoreCase = true) }
                NextcloudAppTile(
                    title = destination.label,
                    icon = view.dynamicCollectionNavigationIcon(),
                    supportingText = resourceLabel,
                    onClick = { onDestinationSelected(planned, view) },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    accessibilityId = destination.accessibilityId,
                    accessibilityDescription = "Open destination ${destination.accessibilityId}",
                )
            }
        }
    }
}

private fun ViewSpec.dynamicRootSubtitle(appName: String): String = when (component) {
    NativeComponent.form -> dynamicActionLabel()
    NativeComponent.detail -> "Overview"
    else -> dynamicNavigationLabel(appName)
}

private fun ViewSpec.dynamicCollectionNavigationIcon(): ImageVector = when (component) {
    NativeComponent.fileBrowser -> NextcloudIcons.Folder
    NativeComponent.mediaGrid,
    NativeComponent.mediaLibrary,
    -> NextcloudIcons.Photo

    NativeComponent.calendar,
    NativeComponent.timeline,
    -> NextcloudIcons.Calendar

    NativeComponent.board -> NextcloudIcons.Board
    NativeComponent.mailbox -> NextcloudIcons.Mail
    NativeComponent.taskList -> NextcloudIcons.Task
    NativeComponent.dataTable -> NextcloudIcons.Table
    NativeComponent.recipeList -> NextcloudIcons.Recipe
    NativeComponent.form,
    NativeComponent.documentEditor,
    -> NextcloudIcons.Edit

    NativeComponent.detail -> NextcloudIcons.Info
    NativeComponent.dashboard -> NextcloudIcons.Home
    NativeComponent.collectionList,
    NativeComponent.contactList,
    NativeComponent.conversationList,
    NativeComponent.chatThread,
    -> NextcloudIcons.ListView
}

private fun ViewSpec.dynamicNavigationLabel(appName: String): String {
    val cleaned = title
        .removePrefix("API ")
        .removePrefix("Api ")
        .removePrefix("api ")
        .trim()
    return if (cleaned.equals("general", ignoreCase = true)) appName else cleaned
}

private fun ViewSpec.dynamicActionLabel(): String = title
    .replace(Regex("^\\[api\\s+v?[0-9.]+]\\s*", RegexOption.IGNORE_CASE), "")
    .trim()
    .replaceFirstChar { character -> character.titlecase() }

private fun ViewSpec.dynamicActionMenuKey(): String = dynamicActionLabel()
    .lowercase()
    .replace(" a ", " ")
    .replace(" an ", " ")
    .replace(Regex("\\s+"), " ")

private fun metadataRecordsForDynamicView(
    discovery: DynamicDescriptorDiscovery,
    resourceId: String,
): List<NativeRecord> {
    if (resourceId != "app-metadata") return emptyList()
    val app = discovery.descriptor.app
    return listOf(
        NativeRecord(
            id = app.id,
            values = mapOf("id" to app.id, "name" to app.name, "version" to app.version),
        ),
    )
}

private fun NativeRecord.toDynamicRuntimeValues(): Map<String, String> = buildMap {
    putAll(actionBindingValues(allowUnsafeIdentity = true))
}

/**
 * Qualifies a selected parent identity with the exact path-parameter name declared by the active
 * action. This lets any nested resource supply its parent identity to a declared child action even
 * when a restored form no longer carries the navigation planner's transient parameter map.
 */
internal fun selectedDynamicRecordRuntimeValues(
    record: NativeRecord?,
    resourceId: String?,
    parameterNames: List<String>,
): Map<String, String>? {
    record ?: return emptyMap()
    val runtimeValues = record.safeActionBindingValues() ?: return null
    val parentResourceId = resourceId?.takeIf(String::isNotBlank) ?: return runtimeValues
    val identity = record.id.takeIf { record.actionSafeIdentity && it.isNotBlank() } ?: return runtimeValues
    val qualifiedParentValues = buildMap {
        parameterNames.forEach { parameterName ->
            val resourceStem = parameterName
                .takeIf { it.length > 2 && it.endsWith("Id", ignoreCase = true) }
                ?.dropLast(2)
                ?: return@forEach
            if (resourceStem.sameDynamicResourceAs(parentResourceId)) {
                put(parameterName, identity)
            }
        }
    }
    return safeActionBindingValues(runtimeValues, qualifiedParentValues)
}

/**
 * Makes only the active form action's declared technical parameters available to form binding.
 *
 * The action executor already receives these qualified values. Supplying the same bounded subset
 * to the renderer keeps its preflight validation aligned with execution without exposing the
 * selected parent's ordinary fields as child-form defaults.
 */
internal fun dynamicDatasetBindingValues(
    component: NativeComponent,
    declaredParameterNames: List<String>,
    selectedPathParameterValues: Map<String, String>,
    runtimeValues: Map<String, String>,
): Map<String, String> {
    if (component != NativeComponent.form) return selectedPathParameterValues
    val declaredNames = declaredParameterNames.toSet()
    return (
        runtimeValues.filterKeys(declaredNames::contains) +
            selectedPathParameterValues.filterKeys(declaredNames::contains)
        )
}

internal fun inheritDynamicParentParameters(
    selectedPathParameterValues: Map<String, String>,
    runtimeValues: Map<String, String>,
): Map<String, String> = selectedPathParameterValues
    .filterKeys { key -> !key.equals("id", ignoreCase = true) } +
    runtimeValues.filterKeys { key ->
        !key.equals("id", ignoreCase = true) && key.endsWith("Id", ignoreCase = true)
    }

/**
 * Selecting a record without a destination keeps the current collection on screen. Its path
 * bindings still belong to that collection and must survive the selection. Otherwise a child's
 * generic `id` can replace the parent's generic `id` when the collection reloads.
 */
internal fun resolveDynamicRecordSelectionParameters(
    currentViewId: String,
    nextViewId: String,
    currentParameters: Map<String, String>,
    explicitTargetParameters: Map<String, String>?,
    fallbackTargetParameters: Map<String, String>,
): Map<String, String> = explicitTargetParameters
    ?: if (nextViewId == currentViewId) currentParameters else fallbackTargetParameters

internal fun shouldShowDynamicRecordFallbackDetail(
    viewResourceId: String,
    viewComponent: NativeComponent,
    selectedRecord: NativeRecord?,
    selectedRecordResourceId: String?,
): Boolean = selectedRecord != null &&
    viewComponent != NativeComponent.detail &&
    viewComponent != NativeComponent.form &&
    selectedRecordResourceId?.sameDynamicResourceAs(viewResourceId) == true

@Composable
private fun ActivityScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    activityInstalled: Boolean,
    installedApps: List<NextcloudAppEntry>,
    onApps: () -> Unit,
    onOpenApp: (NextcloudAppEntry) -> Unit,
) {
    var timeline by remember(session, activityInstalled) { mutableStateOf(ActivityTimelineState()) }
    var loadAttempt by remember(session, activityInstalled) { mutableStateOf(0) }
    var olderPageAttempt by remember(session, activityInstalled) { mutableStateOf(0) }
    var query by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var selectedApp by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf<String?>(null) }
    var selectedType by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf<String?>(null) }
    var selectedSemanticName by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf<String?>(null)
    }
    val selectedSemantic = selectedSemanticName?.let { value ->
        NextcloudActivitySemantic.entries.firstOrNull { semantic -> semantic.name == value }
    }
    val filter = ActivityFeedFilter(
        query = query,
        app = selectedApp,
        type = selectedType,
        semantic = selectedSemantic,
    )
    val feed = buildActivityFeedPresentation(timeline.activities, filter)
    val installedAppIds = installedApps.mapTo(linkedSetOf(), NextcloudAppEntry::id)

    fun clearFilters() {
        query = ""
        selectedApp = null
        selectedType = null
        selectedSemanticName = null
    }

    fun openActivityAction(action: ActivityOpenAction) {
        val app = action.appId?.let { appId ->
            installedApps.firstOrNull { installed -> installed.id == appId }
        }
        when {
            app != null -> onOpenApp(app)
            action.sameOriginUrl != null -> services.openExternalUrl(action.sameOriginUrl)
        }
    }

    LaunchedEffect(session, activityInstalled, loadAttempt) {
        if (!activityInstalled) return@LaunchedEffect
        timeline = timeline.beginActivityRefresh()
        runCatching {
            loadNextcloudActivityPage { request -> services.executeNextcloudApi(session, request) }
        }
            .onSuccess { page -> timeline = timeline.applyActivityRefresh(page) }
            .onFailure { failure ->
                timeline = timeline.failActivityLoad(failure.message ?: "Could not load your activity.")
            }
    }

    LaunchedEffect(session, activityInstalled, olderPageAttempt) {
        if (!activityInstalled || olderPageAttempt == 0) return@LaunchedEffect
        val cursor = timeline.nextSince ?: return@LaunchedEffect
        timeline = timeline.beginNextActivityPage()
        runCatching {
            loadNextcloudActivityPage(since = cursor) { request ->
                services.executeNextcloudApi(session, request)
            }
        }
            .onSuccess { page -> timeline = timeline.applyNextActivityPage(page) }
            .onFailure { failure ->
                timeline = timeline.failActivityLoad(failure.message ?: "Could not load more activity.")
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Activity")
        when {
            !activityInstalled -> Box(
                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                        Icon(
                            NextcloudIcons.Activity,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(18.dp).size(34.dp),
                        )
                    }
                    Text("Activity is not installed", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Install the Nextcloud Activity app to make its events available here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onApps) { Text("View installed apps") }
                }
            }
            !timeline.initialized && timeline.error != null ->
                ErrorMessage(requireNotNull(timeline.error)) { loadAttempt += 1 }
            !timeline.initialized -> LoadingMessage("Loading activity...")
            timeline.activities.isEmpty() -> EmptyMessage("There is no recent activity.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = NextcloudSpacing.XLarge,
                    end = NextcloudSpacing.XLarge,
                    top = NextcloudSpacing.Medium,
                    bottom = NextcloudSpacing.XXLarge,
                ),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Recent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        TextButton(
                            enabled = !timeline.refreshing && !timeline.loadingMore,
                            onClick = { loadAttempt += 1 },
                        ) {
                            Icon(NextcloudIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                if (timeline.refreshing) "Refreshing..." else "Refresh",
                                modifier = Modifier.padding(start = NextcloudSpacing.Small),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search activity") },
                        placeholder = { Text("People, files, messages, or apps") },
                        singleLine = true,
                    )
                }
                item {
                    Text(
                        "Type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                    ) {
                        NextcloudActivitySemantic.entries.forEach { semantic ->
                            val count = feed.semanticCounts[semantic] ?: 0
                            if (count > 0) {
                                item(semantic.name) {
                                    FilterChip(
                                        selected = selectedSemantic == semantic,
                                        onClick = {
                                            selectedSemanticName =
                                                if (selectedSemantic == semantic) null else semantic.name
                                        },
                                        label = { Text("${readableActivitySemantic(semantic)} $count") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (feed.appFacets.size > 1) {
                    item {
                        Text(
                            "App",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        ) {
                            feed.appFacets.forEach { facet ->
                                item(facet.key) {
                                    FilterChip(
                                        selected = selectedApp == facet.key,
                                        onClick = {
                                            selectedApp = facet.key.takeUnless { selectedApp == facet.key }
                                        },
                                        label = { Text("${facet.label} ${facet.count}") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (feed.typeFacets.size > 1) {
                    item {
                        Text(
                            "Event",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        ) {
                            feed.typeFacets.forEach { facet ->
                                item(facet.key) {
                                    FilterChip(
                                        selected = selectedType == facet.key,
                                        onClick = {
                                            selectedType = facet.key.takeUnless { selectedType == facet.key }
                                        },
                                        label = { Text("${facet.label} ${facet.count}") },
                                    )
                                }
                            }
                        }
                    }
                }
                timeline.error?.let { message ->
                    item {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (feed.groups.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.XLarge),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Text("No activity matches these filters.", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = ::clearFilters) { Text("Clear filters") }
                        }
                    }
                }
                feed.groups.forEach { group ->
                    item("day:${group.dateKey}") {
                        Text(
                            group.label,
                            modifier = Modifier.padding(top = NextcloudSpacing.Large),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    listItems(group.activities, key = NextcloudActivity::id) { activity ->
                        ActivityRow(
                            activity = activity,
                            action = activity.activityOpenAction(installedAppIds, session.serverUrl),
                            onOpenAction = ::openActivityAction,
                        )
                    }
                }
                if (timeline.hasMore || timeline.loadingMore) {
                    item {
                        TextButton(
                            enabled = !timeline.loadingMore && !timeline.refreshing,
                            onClick = { olderPageAttempt += 1 },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (timeline.loadingMore) "Loading..." else "Load older activity")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(
    activity: NextcloudActivity,
    action: ActivityOpenAction?,
    onOpenAction: (ActivityOpenAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
            Icon(
                NextcloudIcons.app(activity.app),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(11.dp).size(22.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(activity.subject, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            activity.message?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val metadata = listOfNotNull(
                activity.app.takeIf(String::isNotBlank)?.let(::readableAppName),
                activity.dateTime?.let(::readableActivityDate),
            ).joinToString(" · ")
            if (metadata.isNotBlank()) {
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.let { plannedAction ->
                TextButton(
                    onClick = { onOpenAction(plannedAction) },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(plannedAction.label)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun readableActivitySemantic(semantic: NextcloudActivitySemantic): String = when (semantic) {
    NextcloudActivitySemantic.Message -> "Messages"
    NextcloudActivitySemantic.Media -> "Media"
    NextcloudActivitySemantic.File -> "Files"
    NextcloudActivitySemantic.General -> "Other"
}

private fun readableActivityDate(value: String): String = value
    .replace('T', ' ')
    .substringBefore('+')
    .removeSuffix("Z")

private fun readableAppName(value: String): String = value
    .replace('_', ' ')
    .split(' ')
    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
private fun FilesScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    fileSharing: NextcloudFileSharingCapabilities,
    path: String,
    layout: FileLayout,
    onLayoutChanged: (FileLayout) -> Unit,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile, List<NextcloudFile>) -> Unit,
    onFileAction: (NextcloudFile, FileMenuAction, List<NextcloudFile>) -> Unit,
) {
    var files by remember(path, userId) { mutableStateOf<List<NextcloudFile>?>(null) }
    var error by remember(path, userId) { mutableStateOf<String?>(null) }
    var refreshing by remember(path, userId) { mutableStateOf(false) }
    var listingSource by remember(path, userId) {
        mutableStateOf<NextcloudFileListingSource?>(null)
    }
    var loadAttempt by remember(path, userId) { mutableStateOf(0) }
    var renameTarget by remember(path, userId) { mutableStateOf<NextcloudFile?>(null) }
    var renameValue by remember(path, userId) { mutableStateOf("") }
    var transferTarget by remember(path, userId) { mutableStateOf<Pair<NextcloudFile, FileMenuAction>?>(null) }
    var transferDirectory by remember(path, userId) { mutableStateOf("") }
    var transferName by remember(path, userId) { mutableStateOf("") }
    var deleteTarget by remember(path, userId) { mutableStateOf<NextcloudFile?>(null) }
    var creationKind by remember(path, userId) { mutableStateOf<FileCreationKind?>(null) }
    var creationName by remember(path, userId) { mutableStateOf("") }
    var creationError by remember(path, userId) { mutableStateOf<String?>(null) }
    var creationRunning by remember(path, userId) { mutableStateOf(false) }
    var filterVisible by remember(path, userId) { mutableStateOf(false) }
    var filterQuery by remember(path, userId) { mutableStateOf("") }
    var mutationRunning by remember(path, userId) { mutableStateOf(false) }
    var mutationError by remember(path, userId) { mutableStateOf<String?>(null) }
    var mutationNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    var offlineAvailability by remember(path, userId) {
        mutableStateOf<Map<String, FileOfflineAvailability>>(emptyMap())
    }
    var offlineError by remember(path, userId) { mutableStateOf<String?>(null) }
    var offlineNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    var handoffError by remember(path, userId) { mutableStateOf<String?>(null) }
    var handoffNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    var shareTarget by remember(path, userId) { mutableStateOf<NextcloudFile?>(null) }
    var fileShares by remember(path, userId) { mutableStateOf<List<NextcloudFileShare>?>(null) }
    var shareType by remember(path, userId) { mutableStateOf(FileShareTarget.PublicLink) }
    var shareRecipient by remember(path, userId) { mutableStateOf("") }
    var shareAllowsEditing by remember(path, userId) { mutableStateOf(false) }
    var shareDetails by remember(path, userId) { mutableStateOf(FileShareCreationDetails()) }
    var effectiveFileSharing by remember(path, userId, fileSharing) { mutableStateOf(fileSharing) }
    var shareRunning by remember(path, userId) { mutableStateOf(false) }
    var shareError by remember(path, userId) { mutableStateOf<String?>(null) }
    var shareNotice by remember(path, userId) { mutableStateOf<String?>(null) }
    val externalHandoffCapability = remember(services) {
        (services.externalFileHandoffSupport as? ExternalFileHandoffSupport.Available)?.capability
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(path, userId, loadAttempt) {
        if (userId == null) return@LaunchedEffect
        val retainedFiles = files
        error = null
        val cached = runCatching { services.listFilesCachedWithSource(session, userId, path) }.getOrNull()
        if (cached != null) {
            files = cached.files
            listingSource = cached.source
            if (services.supportsFileOfflineStorage) {
                runCatching { services.loadFileOfflineAvailability(session, userId, cached.files) }
                    .onSuccess { offlineAvailability = it }
                    .onFailure { offlineError = it.message ?: "Could not read offline file status." }
            }
        }
        val hasRetainedFiles = cached != null || retainedFiles != null
        refreshing = hasRetainedFiles
        runCatching { services.listFilesWithSource(session, userId, path) }
            .onSuccess { listing ->
                files = listing.files
                listingSource = listing.source
                refreshing = false
                if (services.supportsFileOfflineStorage) {
                    runCatching { services.loadFileOfflineAvailability(session, userId, listing.files) }
                        .onSuccess { offlineAvailability = it }
                        .onFailure { offlineError = it.message ?: "Could not read offline file status." }
                }
            }
            .onFailure {
                refreshing = false
                error = nextcloudFileRefreshFailure(hasRetainedFiles, it)
            }
    }
    LaunchedEffect(mutationNotice) {
        if (mutationNotice != null) {
            delay(3_500)
            mutationNotice = null
        }
    }
    LaunchedEffect(offlineNotice) {
        if (offlineNotice != null) {
            delay(3_500)
            offlineNotice = null
        }
    }
    LaunchedEffect(handoffNotice) {
        if (handoffNotice != null) {
            delay(3_500)
            handoffNotice = null
        }
    }
    val offlineWorkPending = offlineAvailability.values.any { availability ->
        availability in setOf(
            FileOfflineAvailability.Queued,
            FileOfflineAvailability.Downloading,
            FileOfflineAvailability.Removing,
            FileOfflineAvailability.WaitingForNetwork,
        )
    }
    LaunchedEffect(path, userId, offlineWorkPending) {
        if (!offlineWorkPending || userId == null || !services.supportsFileOfflineStorage) return@LaunchedEffect
        while (true) {
            delay(800)
            val loaded = files ?: break
            val refreshed = runCatching {
                services.loadFileOfflineAvailability(session, userId, loaded)
            }.getOrElse {
                offlineError = it.message ?: "Could not refresh offline file status."
                break
            }
            offlineAvailability = refreshed
            if (refreshed.values.none { availability ->
                    availability in setOf(
                        FileOfflineAvailability.Queued,
                        FileOfflineAvailability.Downloading,
                        FileOfflineAvailability.Removing,
                        FileOfflineAvailability.WaitingForNetwork,
                    )
                }
            ) break
        }
    }

    fun dispatchFileAction(
        file: NextcloudFile,
        action: FileMenuAction,
        loadedFiles: List<NextcloudFile>,
    ) {
        when (action) {
            FileMenuAction.Rename -> {
                renameTarget = file
                renameValue = file.name
                mutationError = null
            }
            FileMenuAction.Delete -> {
                deleteTarget = file
                mutationError = null
            }
            FileMenuAction.Move, FileMenuAction.Copy -> {
                transferTarget = file to action
                transferDirectory = path
                transferName = file.name
                mutationError = null
            }
            FileMenuAction.MakeAvailableOffline, FileMenuAction.RemoveOffline -> {
                val makeAvailable = action == FileMenuAction.MakeAvailableOffline
                val previous = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly
                offlineError = null
                offlineAvailability = offlineAvailability + (
                    file.path to if (makeAvailable) FileOfflineAvailability.Queued else FileOfflineAvailability.Removing
                    )
                scope.launch {
                    runCatching {
                        services.setFileAvailableOffline(session, requireNotNull(userId), file, makeAvailable)
                    }.onSuccess { availability ->
                        offlineAvailability = offlineAvailability + (file.path to availability)
                        offlineNotice = if (makeAvailable) {
                            "${file.name} queued for offline use"
                        } else {
                            "Removing the offline copy of ${file.name}"
                        }
                    }.onFailure {
                        offlineAvailability = offlineAvailability + (file.path to previous)
                        offlineError = it.message ?: "Could not update offline availability."
                    }
                }
            }
            FileMenuAction.Share -> {
                shareTarget = file
                fileShares = null
                shareRecipient = ""
                shareAllowsEditing = false
                shareDetails = FileShareCreationDetails()
                shareType = FileShareTarget.entries.firstOrNull(effectiveFileSharing::canOffer)
                    ?: FileShareTarget.PublicLink
                shareError = null
                shareNotice = null
                scope.launch {
                    runCatching { services.listFileShares(session, file.path) }
                        .onSuccess { fileShares = it }
                        .onFailure {
                            fileShares = emptyList()
                            shareError = it.message ?: "Could not load existing shares."
                        }
                }
            }
            FileMenuAction.OpenWith -> {
                handoffError = null
                handoffNotice = "Preparing ${file.name}..."
                scope.launch {
                    runCatching {
                        services.handoffFileToExternalApp(
                            session = session,
                            userId = requireNotNull(userId),
                            file = file,
                            action = ExternalFileHandoffAction.OpenWith,
                        )
                    }.onSuccess { result ->
                        when (result) {
                            is ExternalFileHandoffResult.Launched -> handoffNotice = null
                            is ExternalFileHandoffResult.Rejected -> {
                                handoffNotice = null
                                handoffError = result.message
                            }
                            is ExternalFileHandoffResult.NoCompatibleApplication -> {
                                handoffNotice = null
                                handoffError = "No installed app can open this file."
                            }
                            is ExternalFileHandoffResult.Unsupported -> {
                                handoffNotice = null
                                handoffError = result.reason
                            }
                        }
                    }.onFailure {
                        handoffNotice = null
                        handoffError = it.message ?: "Could not prepare ${file.name} for another app."
                    }
                }
            }
            FileMenuAction.SendCopy -> {
                handoffError = null
                handoffNotice = "Preparing ${file.name}..."
                scope.launch {
                    runCatching {
                        services.handoffFileToExternalApp(
                            session = session,
                            userId = requireNotNull(userId),
                            file = file,
                            action = ExternalFileHandoffAction.Share,
                        )
                    }.onSuccess { result ->
                        when (result) {
                            is ExternalFileHandoffResult.Launched -> handoffNotice = null
                            is ExternalFileHandoffResult.Rejected -> {
                                handoffNotice = null
                                handoffError = result.message
                            }
                            is ExternalFileHandoffResult.NoCompatibleApplication -> {
                                handoffNotice = null
                                handoffError = "No installed app can receive this file."
                            }
                            is ExternalFileHandoffResult.Unsupported -> {
                                handoffNotice = null
                                handoffError = result.reason
                            }
                        }
                    }.onFailure {
                        handoffNotice = null
                        handoffError = it.message ?: "Could not prepare ${file.name} to send."
                    }
                }
            }
            else -> onFileAction(file, action, loadedFiles)
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Files", if (path.isBlank()) "All files" else "/$path", onBack)
        mutationNotice?.let { notice ->
            Surface(
                color = NextcloudTheme.colors.success.copy(alpha = 0.12f),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    notice,
                    color = NextcloudTheme.colors.success,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        offlineNotice?.let { notice ->
            Surface(
                color = NextcloudTheme.colors.success.copy(alpha = 0.12f),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    notice,
                    color = NextcloudTheme.colors.success,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        offlineError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        handoffNotice?.let { notice ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        handoffError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        if (files != null && error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
                modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 4.dp),
            ) {
                Text(
                    requireNotNull(error),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Medium),
                )
            }
        }
        when {
            error != null && files == null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
            files == null -> LoadingMessage("Loading files...")
            files?.isEmpty() == true -> EmptyMessage("This folder is empty.")
            else -> {
                val loadedFiles = requireNotNull(files)
                val visibleFiles = remember(loadedFiles, filterQuery) { presentFiles(loadedFiles, filterQuery) }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.XLarge, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            nextcloudFileListingSummary(
                                source = listingSource,
                                visibleCount = visibleFiles.size,
                                totalCount = loadedFiles.size,
                                filtered = filterQuery.isNotBlank(),
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            OutlinedButton(
                                onClick = {
                                    creationKind = FileCreationKind.Folder
                                    creationName = ""
                                    creationError = null
                                },
                            ) {
                                Icon(NextcloudIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("New")
                            }
                            IconButton(
                                onClick = {
                                    filterVisible = !filterVisible
                                    if (!filterVisible) filterQuery = ""
                                },
                            ) {
                                Icon(NextcloudIcons.Search, contentDescription = "Search this folder")
                            }
                            IconButton(
                                onClick = { loadAttempt += 1 },
                                enabled = !refreshing,
                            ) {
                                if (refreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(NextcloudIcons.Refresh, contentDescription = "Refresh folder")
                                }
                            }
                            IconButton(
                                onClick = {
                                    onLayoutChanged(
                                        if (layout == FileLayout.List) FileLayout.Grid else FileLayout.List,
                                    )
                                },
                            ) {
                                Icon(
                                    if (layout == FileLayout.List) NextcloudIcons.Apps else NextcloudIcons.ListView,
                                    contentDescription = if (layout == FileLayout.List) {
                                        "Switch to grid layout"
                                    } else {
                                        "Switch to list layout"
                                    },
                                )
                            }
                        }
                    }
                    if (filterVisible) {
                        OutlinedTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            label = { Text("Search this folder") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = NextcloudSpacing.XLarge, vertical = NextcloudSpacing.Small),
                        )
                    }
                    if (visibleFiles.isEmpty()) {
                        EmptyMessage("No files match \"${filterQuery.trim()}\".")
                    } else if (layout == FileLayout.List) {
                        FileList(
                            files = visibleFiles,
                            offlineAvailability = offlineAvailability,
                            offlineStorageSupported = services.supportsFileOfflineStorage,
                            fileSharing = fileSharing,
                            externalHandoffCapability = externalHandoffCapability,
                            onOpenFolder = onOpenFolder,
                            onOpenFile = { onOpenFile(it, visibleFiles) },
                            onAction = { file, action -> dispatchFileAction(file, action, loadedFiles) },
                        )
                    } else {
                        FileGrid(
                            files = visibleFiles,
                            offlineAvailability = offlineAvailability,
                            offlineStorageSupported = services.supportsFileOfflineStorage,
                            fileSharing = fileSharing,
                            externalHandoffCapability = externalHandoffCapability,
                            services = services,
                            session = session,
                            userId = userId,
                            onOpenFolder = onOpenFolder,
                            onOpenFile = { onOpenFile(it, visibleFiles) },
                            onAction = { file, action -> dispatchFileAction(file, action, loadedFiles) },
                        )
                    }
                }
            }
        }
    }

    creationKind?.let { selectedKind ->
        val creationPlan = runCatching { planFileCreation(selectedKind, path, creationName) }.getOrNull()
        val validationError = if (creationName.isBlank()) null else runCatching {
            planFileCreation(selectedKind, path, creationName)
        }.exceptionOrNull()?.message
        AlertDialog(
            onDismissRequest = {
                if (!creationRunning) {
                    creationKind = null
                    creationError = null
                }
            },
            title = { Text("Create in ${if (path.isBlank()) "All files" else path.substringAfterLast('/')}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "Create a folder or start a native Markdown/text document. Existing items are never overwritten.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        FileCreationKind.entries.forEach { kind ->
                            FilterChip(
                                selected = selectedKind == kind,
                                enabled = !creationRunning,
                                onClick = {
                                    creationKind = kind
                                    creationError = null
                                },
                                label = {
                                    Text(
                                        when (kind) {
                                            FileCreationKind.Folder -> "Folder"
                                            FileCreationKind.Markdown -> "Markdown"
                                            FileCreationKind.Text -> "Text"
                                        },
                                    )
                                },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = creationName,
                        onValueChange = {
                            creationName = it
                            creationError = null
                        },
                        label = {
                            Text(if (selectedKind == FileCreationKind.Folder) "Folder name" else "Document name")
                        },
                        supportingText = creationPlan?.takeIf { it.name != creationName.trim() }?.let { plan ->
                            { Text("Will be created as ${plan.name}") }
                        },
                        singleLine = true,
                        enabled = !creationRunning,
                        isError = creationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (creationError ?: validationError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !creationRunning,
                    onClick = {
                        creationKind = null
                        creationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = creationPlan != null && !creationRunning && userId != null,
                    onClick = {
                        val plan = creationPlan ?: return@Button
                        val loadedFiles = files.orEmpty()
                        creationRunning = true
                        creationError = null
                        scope.launch {
                            runCatching {
                                when (plan.kind) {
                                    FileCreationKind.Folder -> {
                                        check(services.createDirectoryIfAbsent(session, requireNotNull(userId), plan.path)) {
                                            "An item named ${plan.name} already exists."
                                        }
                                        null
                                    }
                                    FileCreationKind.Markdown, FileCreationKind.Text -> {
                                        val result = services.createTextFileIfAbsent(
                                            session,
                                            requireNotNull(userId),
                                            plan.path,
                                            "",
                                        )
                                        check(result.wasCreated) { "An item named ${plan.name} already exists." }
                                        NextcloudFile(
                                            path = plan.path,
                                            name = plan.name,
                                            isDirectory = false,
                                            mimeType = if (plan.kind == FileCreationKind.Markdown) {
                                                "text/markdown"
                                            } else {
                                                "text/plain"
                                            },
                                            size = 0,
                                            lastModified = null,
                                            fileId = null,
                                            hasPreview = false,
                                            etag = result.etag,
                                            permissions = "WDNVR",
                                        )
                                    }
                                }
                            }.onSuccess { createdDocument ->
                                creationKind = null
                                creationName = ""
                                mutationNotice = "Created ${plan.name}"
                                if (createdDocument == null) {
                                    files = null
                                    loadAttempt += 1
                                } else {
                                    onOpenFile(createdDocument, loadedFiles + createdDocument)
                                }
                            }.onFailure {
                                creationError = it.message ?: "Could not create ${plan.name}."
                            }
                            creationRunning = false
                        }
                    },
                ) {
                    if (creationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (creationRunning) "Creating..." else "Create")
                }
            },
        )
    }

    renameTarget?.let { target ->
        val validationError = fileRenameValidationError(target, renameValue)
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    renameTarget = null
                    mutationError = null
                }
            },
            title = { Text("Rename ${if (target.isDirectory) "folder" else "file"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "The rename is protected by the version currently shown. If the item changed on the server, it will stop instead of overwriting it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = {
                            renameValue = it
                            mutationError = null
                        },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !mutationRunning,
                        isError = mutationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (mutationError ?: validationError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        renameTarget = null
                        mutationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = validationError == null && !mutationRunning,
                    onClick = {
                        val etag = target.etag?.takeIf(String::isNotBlank)
                        if (etag == null) {
                            mutationError = "Refresh the folder before renaming this item."
                            return@Button
                        }
                        mutationRunning = true
                        mutationError = null
                        scope.launch {
                            runCatching {
                                services.executeFileMutation(
                                    session,
                                    requireNotNull(userId),
                                    NextcloudFileMutation.Rename(
                                        target.path,
                                        renameValue,
                                        etag,
                                        sourceIsDirectory = target.isDirectory,
                                    ),
                                )
                            }.onSuccess {
                                renameTarget = null
                                mutationNotice = "Renamed to $renameValue"
                                files = null
                                loadAttempt += 1
                            }.onFailure {
                                mutationError = it.message ?: "Could not rename this item."
                            }
                            mutationRunning = false
                        }
                    },
                ) {
                    if (mutationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (mutationRunning) "Renaming..." else "Rename")
                }
            },
        )
    }

    transferTarget?.let { (target, action) ->
        val moving = action == FileMenuAction.Move
        val verb = if (moving) "Move" else "Copy"
        val validationError = fileTransferValidationError(target, transferDirectory, transferName)
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    transferTarget = null
                    mutationError = null
                }
            },
            title = { Text("$verb ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "Choose a folder relative to your Nextcloud root. Leave the folder blank for the root.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = transferDirectory,
                        onValueChange = {
                            transferDirectory = it
                            mutationError = null
                        },
                        label = { Text("Destination folder") },
                        placeholder = { Text("Photos/Edited") },
                        singleLine = true,
                        enabled = !mutationRunning,
                        isError = mutationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = transferName,
                        onValueChange = {
                            transferName = it
                            mutationError = null
                        },
                        label = { Text("Name at destination") },
                        singleLine = true,
                        enabled = !mutationRunning,
                        isError = mutationError != null || validationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "An existing item is never overwritten. The source ETag is checked before the operation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    (mutationError ?: validationError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        transferTarget = null
                        mutationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = validationError == null && !mutationRunning,
                    onClick = {
                        val etag = target.etag?.takeIf(String::isNotBlank)
                        if (etag == null) {
                            mutationError = "Refresh the folder before changing this item."
                            return@Button
                        }
                        mutationRunning = true
                        mutationError = null
                        scope.launch {
                            val mutation = if (moving) {
                                NextcloudFileMutation.Move(
                                    target.path,
                                    transferDirectory,
                                    transferName,
                                    etag,
                                    sourceIsDirectory = target.isDirectory,
                                )
                            } else {
                                NextcloudFileMutation.Copy(
                                    target.path,
                                    transferDirectory,
                                    transferName,
                                    etag,
                                    sourceIsDirectory = target.isDirectory,
                                )
                            }
                            runCatching {
                                services.executeFileMutation(session, requireNotNull(userId), mutation)
                            }.onSuccess {
                                transferTarget = null
                                mutationNotice = if (moving) {
                                    "Moved ${target.name}"
                                } else {
                                    "Copied ${target.name}"
                                }
                                files = null
                                loadAttempt += 1
                            }.onFailure {
                                mutationError = it.message ?: "Could not ${verb.lowercase()} this item."
                            }
                            mutationRunning = false
                        }
                    },
                ) {
                    if (mutationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (mutationRunning) "${verb}ing..." else verb)
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    deleteTarget = null
                    mutationError = null
                }
            },
            title = { Text("Delete ${target.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        if (target.isDirectory) {
                            "This removes the folder and everything inside it from Nextcloud. This cannot be undone here."
                        } else {
                            "This removes the file from Nextcloud. This cannot be undone here."
                        },
                    )
                    Text(
                        "The delete is ETag-protected and will stop if the item changed since this folder was loaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    mutationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        deleteTarget = null
                        mutationError = null
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = !mutationRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val etag = target.etag?.takeIf(String::isNotBlank)
                        if (etag == null) {
                            mutationError = "Refresh the folder before deleting this item."
                            return@Button
                        }
                        mutationRunning = true
                        mutationError = null
                        scope.launch {
                            runCatching {
                                services.executeFileMutation(
                                    session,
                                    requireNotNull(userId),
                                    NextcloudFileMutation.Delete(
                                        target.path,
                                        etag,
                                        sourceIsDirectory = target.isDirectory,
                                    ),
                                )
                            }.onSuccess {
                                deleteTarget = null
                                mutationNotice = "Deleted ${target.name}"
                                files = null
                                loadAttempt += 1
                            }.onFailure {
                                mutationError = it.message ?: "Could not delete this item."
                            }
                            mutationRunning = false
                        }
                    },
                ) {
                    if (mutationRunning) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (mutationRunning) "Deleting..." else "Delete")
                }
            },
        )
    }

    shareTarget?.let { target ->
        FileShareDialog(
            state = FileShareDialogUiState(
                file = target,
                capabilities = effectiveFileSharing,
                existingShares = fileShares,
                target = shareType,
                recipient = shareRecipient,
                allowEditing = shareAllowsEditing,
                details = shareDetails,
                running = shareRunning,
                notice = shareNotice,
                error = shareError,
            ),
            onDismiss = {
                shareTarget = null
                shareError = null
                shareNotice = null
            },
            onTargetChanged = { targetType ->
                shareType = targetType
                shareRecipient = ""
                shareDetails = shareDetails.copy(
                    password = "",
                    expiration = FileShareExpiration.ServerDefault,
                )
                shareError = null
            },
            onAllowEditingChanged = { shareAllowsEditing = it },
            onDetailsChanged = {
                shareDetails = it
                shareError = null
            },
            onCreate = { ready ->
                shareRunning = true
                shareError = null
                shareNotice = null
                scope.launch {
                    runCatching { services.createFileShare(session, ready.request) }
                        .onSuccess { created ->
                            val safeUrl = safeFileShareUrl(session, created)
                            val copied = safeUrl != null &&
                                services.copyTextToClipboard("Nextcloud share link", safeUrl)
                            shareNotice = if (copied) "Share created and link copied" else "Share created"
                            fileShares = runCatching {
                                services.listFileShares(session, target.path)
                            }.getOrElse { fileShares.orEmpty() + created }
                            shareRecipient = ""
                        }
                        .onFailure {
                            shareError = it.message ?: "Could not create the share."
                        }
                    shareRunning = false
                }
            },
            recipientPicker = { targetType ->
                FileShareRecipientPicker(
                    session = session,
                    services = services,
                    target = targetType,
                    file = target,
                    selectedRecipient = shareRecipient,
                    enabled = !shareRunning,
                    onSelected = {
                        shareRecipient = it?.id.orEmpty()
                        shareError = null
                    },
                    onResultsObserved = { recipients ->
                        effectiveFileSharing = effectiveFileSharing.withObservedRecipientProvider(
                            targetType,
                            recipients,
                        )
                    },
                )
            },
            existingShare = { existing ->
                ExistingFileShareManager(
                    share = existing,
                    sourceIsDirectory = target.isDirectory,
                    session = session,
                    services = services,
                    capabilities = effectiveFileSharing,
                    onChanged = { changed ->
                        fileShares = fileShares.orEmpty().map {
                            if (it.id == changed.id) changed else it
                        }
                    },
                    onRevoked = { revoked ->
                        fileShares = fileShares.orEmpty().filterNot { it.id == revoked.id }
                        shareNotice = "Access revoked"
                    },
                )
            },
        )
    }
}

@Composable
private fun FileList(
    files: List<NextcloudFile>,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NextcloudSpacing.XXLarge)) {
        listItems(files, key = NextcloudFile::path) { file ->
            var menuExpanded by remember(file.path) { mutableStateOf(false) }
            val availability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly
            Row(
                modifier = Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = primaryFileActionLabel(file),
                        onLongClickLabel = "Show actions for ${file.name}",
                        onClick = { if (file.isDirectory) onOpenFolder(file.path) else onOpenFile(file) },
                        onLongClick = { menuExpanded = true },
                    )
                    .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(10.dp)) {
                    Icon(
                        if (file.isDirectory) NextcloudIcons.Folder else fileIcon(file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(9.dp).size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        availability.readableStatus()
                            ?: if (file.isDirectory) "Folder" else formatBytes(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                    }
                    FileActionMenu(
                        file = file,
                        offlineAvailability = availability,
                        offlineStorageSupported = offlineStorageSupported,
                        fileSharing = fileSharing,
                        externalHandoffCapability = externalHandoffCapability,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onAction = { onAction(file, it) },
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(start = 80.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun FileGrid(
    files: List<NextcloudFile>,
    offlineAvailability: Map<String, FileOfflineAvailability>,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (NextcloudFile) -> Unit,
    onAction: (NextcloudFile, FileMenuAction) -> Unit,
) {
    LazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = GridCells.Adaptive(128.dp),
        contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large, vertical = NextcloudSpacing.Small),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        items(files, key = NextcloudFile::path) { file ->
            FileGridTile(
                file = file,
                offlineAvailability = offlineAvailability[file.path] ?: FileOfflineAvailability.OnlineOnly,
                offlineStorageSupported = offlineStorageSupported,
                fileSharing = fileSharing,
                externalHandoffCapability = externalHandoffCapability,
                services = services,
                session = session,
                userId = userId,
                onClick = { if (file.isDirectory) onOpenFolder(file.path) else onOpenFile(file) },
                onAction = { onAction(file, it) },
            )
        }
    }
}

@Composable
private fun FileGridTile(
    file: NextcloudFile,
    offlineAvailability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    onClick: () -> Unit,
    onAction: (FileMenuAction) -> Unit,
) {
    var menuExpanded by remember(file.path) { mutableStateOf(false) }
    var preview by remember(
        file.fileId,
        file.etag,
        file.hasPreview,
        file.memoriesRenderAllowed,
    ) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(
        session,
        userId,
        file.fileId,
        file.etag,
        file.hasPreview,
        file.memoriesRenderAllowed,
    ) {
        file.fileId ?: return@LaunchedEffect
        if (file.isDirectory || !file.isPhotoMedia()) return@LaunchedEffect
        preview = services.loadMediaThumbnailDecoded(
            session = session,
            userId = userId,
            file = file,
            width = 320,
            height = 320,
        ) { payload ->
            decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClickLabel = primaryFileActionLabel(file),
            onLongClickLabel = "Show actions for ${file.name}",
            onClick = onClick,
            onLongClick = { menuExpanded = true },
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)
                .background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(it, file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                if (file.isDirectory) NextcloudIcons.Folder else fileIcon(file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(NextcloudSpacing.Small)) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    shape = CircleShape,
                ) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(38.dp)) {
                        Icon(NextcloudIcons.More, contentDescription = "Actions for ${file.name}")
                    }
                }
                FileActionMenu(
                    file = file,
                    offlineAvailability = offlineAvailability,
                    offlineStorageSupported = offlineStorageSupported,
                    fileSharing = fileSharing,
                    externalHandoffCapability = externalHandoffCapability,
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    onAction = onAction,
                )
            }
        }
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Text(
                offlineAvailability.readableStatus()
                    ?: if (file.isDirectory) "Folder" else formatBytes(file.size),
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileActionMenu(
    file: NextcloudFile,
    offlineAvailability: FileOfflineAvailability,
    offlineStorageSupported: Boolean,
    fileSharing: NextcloudFileSharingCapabilities,
    externalHandoffCapability: ExternalFileHandoffCapability?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (FileMenuAction) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        planFilesScreenActions(
            file = file,
            support = FileActionSupport(
                sharing = fileSharing.apiEnabled,
                externalSharing = ExternalFileHandoffAction.Share in
                    externalHandoffCapability?.supportedActions.orEmpty(),
                offlineStorage = offlineStorageSupported,
                platformViewer = ExternalFileHandoffAction.OpenWith in externalHandoffCapability?.supportedActions.orEmpty(),
                maximumExternalFileBytes = externalHandoffCapability?.maximumFileBytes,
            ),
            offlineState = offlineAvailability.toFileActionOfflineState(),
        ).actions.forEach { action ->
            DropdownMenuItem(
                text = {
                    Column {
                        Text(action.label)
                        action.disabledReason?.let { reason ->
                            Text(
                                reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = fileActionIcon(action.action),
                        contentDescription = null,
                        tint = if (action.tone == FileActionTone.Destructive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                enabled = action.enabled,
                onClick = {
                    onDismiss()
                    onAction(action.action)
                },
            )
        }
    }
}

private fun fileActionIcon(action: FileMenuAction): ImageVector = when (action) {
    FileMenuAction.Open -> NextcloudIcons.FolderOpen
    FileMenuAction.Preview -> NextcloudIcons.Image
    FileMenuAction.OpenWith -> NextcloudIcons.File
    FileMenuAction.EditText, FileMenuAction.EditWith, FileMenuAction.Rename -> NextcloudIcons.Edit
    FileMenuAction.Details -> NextcloudIcons.Info
    FileMenuAction.VersionHistory -> NextcloudIcons.Refresh
    FileMenuAction.Download -> NextcloudIcons.Cloud
    FileMenuAction.Move -> NextcloudIcons.FolderOpen
    FileMenuAction.Copy -> NextcloudIcons.File
    FileMenuAction.Share -> NextcloudIcons.People
    FileMenuAction.SendCopy -> NextcloudIcons.Cloud
    FileMenuAction.MakeAvailableOffline, FileMenuAction.RemoveOffline -> NextcloudIcons.CheckCircle
    FileMenuAction.Delete -> NextcloudIcons.Error
}

private const val PHOTO_TIMELINE_PREFETCH_GRID_ITEMS = 18
private const val PHOTO_FOLDER_INITIAL_BACKUP_STATUS_ITEMS = 24

@Composable
internal fun PhotoTimelineFailureNotice(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "Retry",
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NextcloudSpacing.Medium,
                vertical = NextcloudSpacing.Small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun PhotoTimelineViewModeControl(
    viewMode: PhotoTimelineViewMode,
    onViewModeChanged: (PhotoTimelineViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.selectableGroup()) {
            PhotoTimelineViewMode.entries.forEach { mode ->
                val selected = viewMode == mode
                val label = when (mode) {
                    PhotoTimelineViewMode.Grid -> "Photo grid"
                    PhotoTimelineViewMode.List -> "Full-width photo list"
                }
                Surface(
                    modifier = Modifier
                        .size(48.dp)
                        .selectable(
                            selected = selected,
                            onClick = { onViewModeChanged(mode) },
                            role = Role.RadioButton,
                        ),
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                    color = if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when (mode) {
                                PhotoTimelineViewMode.Grid -> NextcloudIcons.Apps
                                PhotoTimelineViewMode.List -> NextcloudIcons.ListView
                            },
                            contentDescription = label,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoFolderStaleNotice(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = NextcloudSpacing.Medium,
                vertical = NextcloudSpacing.Small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Text(
                text = "Could not refresh photo folders. Showing saved folder content. $message",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun PhotoFolderIncompleteNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(NextcloudRadii.Card),
        modifier = modifier,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(
                horizontal = NextcloudSpacing.Medium,
                vertical = NextcloudSpacing.Small,
            ),
        )
    }
}

@Composable
private fun MediaScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String?,
    photoBrowserState: PhotoBrowserState,
    collectionState: MediaCollectionsUiState,
    collectionGridState: LazyGridState,
    folderInventoryState: PhotoFolderInventoryUiState,
    folderViewportState: PhotoFolderViewportUiState,
    folderGridState: LazyGridState,
    folderListState: LazyListState,
    onPhotoBrowserStateChanged: (PhotoBrowserState) -> Unit,
    timelineState: PhotoTimelineUiState,
    timelineGridState: LazyGridState,
    onBack: () -> Unit,
    onOpenMedia: (NextcloudFile, MediaStackViewerSequence) -> Unit,
    onOpenPerson: (NextcloudPerson) -> Unit,
) {
    val destination = photoBrowserState.destination
    val photoFolderState = photoBrowserState.folder
    val folderPagingState = folderInventoryState.pagingState
    val pagedFolderInventory = remember(
        folderPagingState.contentGeneration,
        photoFolderState.selectedFolderPath,
        photoFolderState.query,
        photoFolderState.scope,
    ) {
        folderPagingState.publication?.let {
            folderInventoryState.pager.selectionSnapshot(photoFolderState)
        }
    }
    val pagedFolderBackupStatuses = folderInventoryState.backupStatuses
    var timeline by timelineState.timeline
    var timelineBackupStatuses by timelineState.backupStatuses
    var timelineInitialLoadCompleted by timelineState.initialLoadCompleted
    val timelineMonthResolver = remember { platformLocalPhotoTimelineMonthResolver() }
    var timelineNavigationSnapshot by remember(session, userId) {
        mutableStateOf<MemoriesTimelineNavigationSnapshot?>(null)
    }
    var timelineNavigationError by remember(session, userId) {
        mutableStateOf<String?>(null)
    }
    var timelineNavigationRequestGeneration by remember(session, userId) {
        mutableStateOf(0L)
    }
    var resolvedTimelineRawFilesById by remember(session, userId) {
        mutableStateOf<Map<Long, NextcloudFile>>(emptyMap())
    }
    val timelineRawFileIds = remember(timeline.rawStackFileIdsByEntryIdentity) {
        timeline.rawStackFileIdsByEntryIdentity.values
            .asSequence()
            .flatten()
            .distinct()
            .sorted()
            .toList()
    }
    LaunchedEffect(session, services, userId, timelineRawFileIds) {
        if (userId == null || timelineRawFileIds.isEmpty()) {
            resolvedTimelineRawFilesById = emptyMap()
            return@LaunchedEffect
        }
        resolvedTimelineRawFilesById = try {
            services.resolveFilesById(session, userId, timelineRawFileIds)
                .filter { (fileId, file) ->
                    fileId in timelineRawFileIds && file.fileId == fileId && file.isRawPhoto()
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
    }
    val timelineRawStackFilesByEntryIdentity = remember(
        timeline.rawStackFileIdsByEntryIdentity,
        resolvedTimelineRawFilesById,
    ) {
        timeline.rawStackFileIdsByEntryIdentity.mapValues { (_, fileIds) ->
            fileIds.mapNotNull(resolvedTimelineRawFilesById::get)
        }
    }
    val indexedTimelineStacks = remember(
        timeline.entries,
        timelineRawStackFilesByEntryIdentity,
    ) {
        buildPhotoTimelineStackEntries(
            entries = timeline.entries,
            rawStackFilesByEntryIdentity = timelineRawStackFilesByEntryIdentity,
        )
    }
    val timelineStacks = remember(indexedTimelineStacks) {
        indexedTimelineStacks.map(PhotoTimelineStackEntry::stack)
    }
    val timelineDateIndex = remember(indexedTimelineStacks, timelineMonthResolver) {
        buildPhotoTimelineDateIndex(
            indexedTimelineStacks.map(PhotoTimelineStackEntry::timelineEntry),
            timelineMonthResolver,
        )
    }
    val timelineViewerSequence = remember(timelineStacks) {
        mediaStackViewerSequence(timelineStacks)
    }
    val peopleByBackend = remember(userId) {
        mutableStateMapOf<NextcloudPeopleBackend, List<NextcloudPerson>>()
    }
    var peopleBackend by rememberSaveable(userId, stateSaver = enumSaver<NextcloudPeopleBackend>()) {
        mutableStateOf(NextcloudPeopleBackend.Recognize)
    }
    var peopleNameFilter by rememberSaveable(userId, stateSaver = enumSaver<PeopleNameFilter>()) {
        mutableStateOf(PeopleNameFilter.All)
    }
    var peopleError by remember(userId) { mutableStateOf<String?>(null) }
    var peopleSearch by rememberSaveable(userId) { mutableStateOf("") }
    var mediaLoadAttempt by remember(session, userId) { mutableStateOf(0) }
    var peopleLoadAttempt by remember(userId) { mutableStateOf(0) }
    val folderInventoryService = remember(services) {
        MemoriesPreferredFolderInventoryReadService(services)
    }
    val collectionService = remember(services) { NativeMediaCollectionReadService(services) }
    val collectionMutationService = remember(services) { NativeMediaCollectionMutationService(services) }
    PlatformBackHandler(
        enabled = destination == PhotoDestination.Folders &&
            photoFolderState.selectedFolderPath.isNotEmpty(),
        onBack = {
            onPhotoBrowserStateChanged(
                photoBrowserState.copy(
                    folder = photoFolderState.copy(
                        selectedFolderPath = photoFolderState.selectedFolderPath.substringBeforeLast(
                            '/',
                            missingDelimiterValue = "",
                        ),
                        query = "",
                    ),
                ),
            )
        },
    )
    with(collectionState) {
    DisposableEffect(collectionState) {
        onDispose {
            requestGeneration += 1
            loading = false
        }
    }
    suspend fun loadTimelinePage(
        kind: PhotoTimelineLoadKind,
        replacePendingLoad: Boolean = false,
    ) {
        if (userId == null) return
        val start = if (replacePendingLoad) {
            timeline.beginReplacingPendingLoad(kind)
        } else {
            when (kind) {
                PhotoTimelineLoadKind.Refresh -> timeline.beginRefresh()
                PhotoTimelineLoadKind.RevalidateNewest -> timeline.beginNewestRevalidation()
                PhotoTimelineLoadKind.NextPage -> timeline.beginNextPage()
            }
        }
        val token = start.token ?: return
        timeline = start.state
        if (kind != PhotoTimelineLoadKind.NextPage) {
            timelineNavigationSnapshot = null
            timelineNavigationError = null
            timelineNavigationRequestGeneration =
                if (timelineNavigationRequestGeneration == Long.MAX_VALUE) {
                    1L
                } else {
                    timelineNavigationRequestGeneration + 1L
                }
        }
        try {
            val page = services.listMediaTimelinePage(
                session = session,
                userId = userId,
                cursor = token.cursor,
                rawPreviouslyObserved = timeline.rawEverObserved,
                queryOwner = PhotoMediaQueryOwner.Timeline,
            )
            val files = page.entries.map(PhotoTimelineEntry::file)
            timeline = timeline.accept(token, page)
            if (timeline.generation == token.generation && timeline.loading == null) {
                timelineNavigationSnapshot = runCatching {
                    services.loadMediaTimelineNavigationSnapshot(
                        session = session,
                        monthResolver = timelineMonthResolver,
                    )
                }.getOrNull()
            }
            if (kind == PhotoTimelineLoadKind.Refresh) {
                timelineGridState.scrollToItem(0)
            }
            if (kind != PhotoTimelineLoadKind.NextPage) timelineInitialLoadCompleted = true
            val statuses = runCatching {
                services.loadMediaBackupStatuses(session, userId, files)
            }.getOrDefault(emptyMap())
            if (timeline.generation == token.generation) {
                val retainedPaths = timeline.entries
                    .mapTo(mutableSetOf()) { entry -> entry.file.path.trim('/') }
                timelineBackupStatuses = (
                    if (kind == PhotoTimelineLoadKind.Refresh) {
                        statuses
                    } else {
                        timelineBackupStatuses + statuses
                    }
                    )
                    .filterKeys(retainedPaths::contains)
            }
        } catch (cancellation: CancellationException) {
            timeline = timeline.cancel(token)
            throw cancellation
        } catch (failure: Throwable) {
            timeline = timeline.fail(
                token = token,
                message = failure.message ?: "Could not load the photo timeline.",
            )
            if (kind != PhotoTimelineLoadKind.NextPage) timelineInitialLoadCompleted = true
        }
    }

    suspend fun loadTimelineNavigationTarget(targetDayId: Long) {
        if (userId == null) return
        val snapshotAtRequest = timelineNavigationSnapshot ?: return
        val timelineGenerationAtRequest = timeline.generation
        val requestGeneration = if (timelineNavigationRequestGeneration == Long.MAX_VALUE) {
            1L
        } else {
            timelineNavigationRequestGeneration + 1L
        }
        timelineNavigationRequestGeneration = requestGeneration
        timelineNavigationError = null
        val result = try {
            services.loadMediaTimelineNavigationTarget(
                session = session,
                sourceGeneration = snapshotAtRequest.sourceGeneration,
                targetDayId = targetDayId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (
                timelineNavigationRequestGeneration == requestGeneration &&
                timeline.generation == timelineGenerationAtRequest
            ) {
                timelineNavigationError =
                    failure.message ?: "Could not load photos for this date."
            }
            return
        }
        if (
            timelineNavigationRequestGeneration != requestGeneration ||
            timeline.generation != timelineGenerationAtRequest ||
            timelineNavigationSnapshot?.sourceGeneration !=
            snapshotAtRequest.sourceGeneration
        ) {
            return
        }
        when (
            val applied = applyMemoriesTimelineNavigationResult(
                state = timeline,
                snapshot = snapshotAtRequest,
                result = result,
            )
        ) {
            is PhotoTimelineNavigationApplyResult.Applied -> {
                timeline = applied.state
                timelineNavigationError = null
                timelineInitialLoadCompleted = true
                timelineGridState.scrollToItem(0)
                val files = applied.state.entries.map(PhotoTimelineEntry::file)
                val statuses = runCatching {
                    services.loadMediaBackupStatuses(session, userId, files)
                }.getOrDefault(emptyMap())
                if (
                    timelineNavigationRequestGeneration == requestGeneration &&
                    timeline.generation == applied.state.generation
                ) {
                    timelineBackupStatuses = statuses
                }
            }

            is PhotoTimelineNavigationApplyResult.Retained -> {
                if (applied.snapshotStale) timelineNavigationSnapshot = null
                timelineNavigationError = applied.message
            }
        }
    }

    LaunchedEffect(
        session,
        services,
        userId,
        mediaLoadAttempt,
        destination,
        photoFolderState.selectedFolderPath,
        photoFolderState.scope,
    ) {
        if (
            userId == null ||
            PhotoMediaQueryOwner.FolderInventory !in photoMediaQueryOwners(destination)
        ) {
            return@LaunchedEffect
        }
        val activePager = folderInventoryState.selectFolder(
            "${photoFolderState.selectedFolderPath}|${photoFolderState.scope.name}",
        )
        val publishInventory: (PhotoFolderInventoryPagingState) -> Unit = { published ->
            if (folderInventoryState.pager === activePager) {
                folderInventoryState.pagingState = published
            }
        }
        val loadInventoryPage:
            suspend (PhotoFolderInventoryCursor?, Boolean) -> PhotoFolderInventoryPage =
            { cursor, rawPreviouslyObserved ->
                folderInventoryService.loadPage(
                    session = session,
                    accountScope = folderInventoryState.pager.owner.accountKey,
                    selectedFolderPath = photoFolderState.selectedFolderPath,
                    scope = photoFolderState.scope,
                    cursor = cursor,
                    fallback = { fallbackCursor ->
                        val page = services.listMediaTimelinePage(
                            session = session,
                            userId = userId,
                            cursor = fallbackCursor?.value?.let(::PhotoTimelineCursor),
                            rawPreviouslyObserved = rawPreviouslyObserved,
                            queryOwner = PhotoMediaQueryOwner.FolderInventory,
                        )
                        val recordPathByEntryIdentity = page.entries.associate { entry ->
                            entry.identity to entry.file.path.trim('/')
                        }
                        PhotoFolderInventoryPage(
                            records = page.entries.map(PhotoTimelineEntry::file),
                            nextCursor = page.nextCursor?.value?.let(::PhotoFolderInventoryCursor),
                            rawObserved = page.rawObserved,
                            rawStackFileIdsByRecordPath =
                                page.rawStackFileIdsByEntryIdentity.mapKeys { (identity, _) ->
                                    requireNotNull(recordPathByEntryIdentity[identity]) {
                                        "The folder timeline RAW relationship has no page record."
                                    }
                                },
                            rawStackRelationshipsAuthoritative =
                                page.rawStackRelationshipsAuthoritative,
                        )
                    },
                )
            }
        folderInventoryState.pagingState = if (activePager.state.complete) {
            activePager.revalidate(publishInventory, loadInventoryPage)
        } else {
            activePager.load(publishInventory, loadInventoryPage)
        }
        if (folderInventoryState.pager !== activePager) return@LaunchedEffect
        val retainedPaths = folderInventoryState.pager
            .selectionSnapshot(photoFolderState)
            .selectedMediaFiles
            .mapTo(mutableSetOf()) { file -> file.path.trim('/') }
        folderInventoryState.backupStatuses =
            folderInventoryState.backupStatuses.filterKeys(retainedPaths::contains)
    }
    LaunchedEffect(userId, mediaLoadAttempt, destination) {
        if (
            userId == null ||
            PhotoMediaQueryOwner.Timeline !in photoMediaQueryOwners(destination)
        ) {
            return@LaunchedEffect
        }
        val inheritedLoadKind = timeline.loading?.kind
        loadTimelinePage(
            kind = inheritedLoadKind ?: when {
                mediaLoadAttempt > 0 -> PhotoTimelineLoadKind.Refresh
                timelineInitialLoadCompleted -> PhotoTimelineLoadKind.RevalidateNewest
                else -> PhotoTimelineLoadKind.Refresh
            },
            replacePendingLoad = inheritedLoadKind != null,
        )
    }
    LaunchedEffect(
        destination,
        userId,
        timelineGridState,
    ) {
        if (destination != PhotoDestination.Timeline) return@LaunchedEffect
        snapshotFlow {
            val layout = timelineGridState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            val totalItems = layout.totalItemsCount
            (
                timeline.canPrefetchNextPage &&
                    totalItems > 0 &&
                    lastVisible >= totalItems - PHOTO_TIMELINE_PREFETCH_GRID_ITEMS
                ) to timeline.nextCursor?.value
        }.distinctUntilChanged().collect { (shouldLoad, _) ->
            if (shouldLoad) loadTimelinePage(PhotoTimelineLoadKind.NextPage)
        }
    }
    val currentPhotoDestination by rememberUpdatedState(destination)
    val currentPhotoFolderState by rememberUpdatedState(photoFolderState)
    val currentFolderInventory by rememberUpdatedState(pagedFolderInventory)
    val currentTimeline by rememberUpdatedState(timeline)
    val currentTimelineDateIndex by rememberUpdatedState(timelineDateIndex)
    val currentIndexedTimelineStacks by rememberUpdatedState(indexedTimelineStacks)
    val currentSelectedCollection by rememberUpdatedState(selectedCollection)
    val currentCollectionItems by rememberUpdatedState(collectionItems)
    val currentResolvedFiles by rememberUpdatedState(resolvedFiles)
    LaunchedEffect(
        userId,
        services,
        destination,
        pagedFolderInventory,
        photoFolderState.preference.viewMode,
    ) {
        val inventoryAtRequest = pagedFolderInventory
        if (
            userId == null ||
            destination != PhotoDestination.Folders ||
            inventoryAtRequest == null
        ) {
            return@LaunchedEffect
        }
        val folderStateAtRequest = photoFolderState
        val presentationAtRequest = buildPhotoFolderBrowseResult(
            inventory = inventoryAtRequest,
            state = folderStateAtRequest,
        )
        snapshotFlow {
            when (folderStateAtRequest.preference.viewMode) {
                PhotoFolderViewMode.Grid ->
                    folderGridState.layoutInfo.visibleItemsInfo.map { item -> item.index }
                PhotoFolderViewMode.List ->
                    folderListState.layoutInfo.visibleItemsInfo.map { item -> item.index }
            }
        }.distinctUntilChanged().collectLatest { visibleIndices ->
            val visibleFiles = visibleIndices.mapNotNull { index ->
                presentationAtRequest.media
                    .getOrNull(index - presentationAtRequest.folders.size)
                    ?.cover
            }.ifEmpty {
                presentationAtRequest.media
                    .take(PHOTO_FOLDER_INITIAL_BACKUP_STATUS_ITEMS)
                    .map(MediaStack::cover)
            }
            if (visibleFiles.isEmpty()) return@collectLatest
            val requestedPaths = visibleFiles
                .mapTo(mutableSetOf()) { file -> file.path.trim('/') }
            val statuses = runCatching {
                services.loadMediaBackupStatuses(session, userId, visibleFiles)
            }.getOrDefault(emptyMap())
            if (
                currentPhotoDestination == PhotoDestination.Folders &&
                currentPhotoFolderState == folderStateAtRequest &&
                currentFolderInventory === inventoryAtRequest
            ) {
                folderInventoryState.backupStatuses =
                    folderInventoryState.backupStatuses +
                        statuses.filterKeys(requestedPaths::contains)
            }
        }
    }
    LaunchedEffect(userId, services) {
        if (userId == null) return@LaunchedEffect
        services.observeMediaBackupStatusChanges(session).collectLatest {
            val destinationAtRequest = currentPhotoDestination
            val folderStateAtRequest = currentPhotoFolderState
            val timelineGenerationAtRequest = currentTimeline.generation
            val collectionKeyAtRequest = currentSelectedCollection?.key
            fun visibleTimelineFiles(): List<NextcloudFile> =
                photoTimelineStackIndicesForGridItems(
                    dateIndex = currentTimelineDateIndex,
                    gridItemIndices = timelineGridState.layoutInfo.visibleItemsInfo.map { item ->
                        item.index
                    },
                ).mapNotNull { index ->
                    currentIndexedTimelineStacks.getOrNull(index)?.stack?.cover
                }

            fun visibleCollectionFiles(): List<NextcloudFile> =
                collectionGridState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    currentCollectionItems
                        .getOrNull(item.index)
                        ?.fileId
                        ?.let(currentResolvedFiles::get)
                }

            fun visibleFolderFiles(): List<NextcloudFile> {
                val visibleIndices = when (folderStateAtRequest.preference.viewMode) {
                    PhotoFolderViewMode.Grid ->
                        folderGridState.layoutInfo.visibleItemsInfo.map { item -> item.index }
                    PhotoFolderViewMode.List ->
                        folderListState.layoutInfo.visibleItemsInfo.map { item -> item.index }
                }
                val inventory = currentFolderInventory ?: return emptyList()
                val presentation = buildPhotoFolderBrowseResult(
                    inventory = inventory,
                    state = folderStateAtRequest,
                )
                return visibleIndices.mapNotNull { index ->
                    presentation.media
                        .getOrNull(index - presentation.folders.size)
                        ?.cover
                }
            }

            val visibleFiles = when {
                destinationAtRequest == PhotoDestination.Timeline -> visibleTimelineFiles()
                destinationAtRequest == PhotoDestination.Albums && collectionKeyAtRequest != null ->
                    visibleCollectionFiles()
                destinationAtRequest == PhotoDestination.Folders -> visibleFolderFiles()
                else -> emptyList()
            }
            if (visibleFiles.isNotEmpty()) {
                val requestedPaths = visibleFiles
                    .mapTo(mutableSetOf()) { file -> file.path.trim('/') }
                val statuses = runCatching {
                    services.loadMediaBackupStatuses(session, userId, visibleFiles)
                }.getOrDefault(emptyMap())
                val requestStillCurrent =
                    currentPhotoDestination == destinationAtRequest &&
                        currentPhotoFolderState == folderStateAtRequest &&
                        currentSelectedCollection?.key == collectionKeyAtRequest &&
                        (
                            destinationAtRequest != PhotoDestination.Timeline ||
                                currentTimeline.generation == timelineGenerationAtRequest
                            )
                if (requestStillCurrent) {
                    val currentlyVisiblePaths = when {
                        destinationAtRequest == PhotoDestination.Timeline -> visibleTimelineFiles()
                        destinationAtRequest == PhotoDestination.Albums &&
                            collectionKeyAtRequest != null -> visibleCollectionFiles()
                        destinationAtRequest == PhotoDestination.Folders -> visibleFolderFiles()
                        else -> emptyList()
                    }.mapTo(mutableSetOf()) { file -> file.path.trim('/') }
                    val acceptedPaths = requestedPaths.intersect(currentlyVisiblePaths)
                    val acceptedStatuses = statuses.filterKeys(acceptedPaths::contains)
                    when (destinationAtRequest) {
                        PhotoDestination.Timeline -> {
                            timelineBackupStatuses = timelineBackupStatuses + acceptedStatuses
                        }
                        PhotoDestination.Albums -> {
                            backupStatuses = backupStatuses + acceptedStatuses
                        }
                        PhotoDestination.Folders -> {
                            folderInventoryState.backupStatuses =
                                folderInventoryState.backupStatuses + acceptedStatuses
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    LaunchedEffect(photoFolderState) {
        if (photoFolderState != folderViewportState.browseState) {
            folderViewportState.browseState = photoFolderState
            when (photoFolderState.preference.viewMode) {
                PhotoFolderViewMode.Grid -> folderGridState.scrollToItem(0)
                PhotoFolderViewMode.List -> folderListState.scrollToItem(0)
            }
        }
    }
    LaunchedEffect(destination, peopleBackend, peopleLoadAttempt) {
        if (destination != PhotoDestination.People || peopleBackend in peopleByBackend) {
            return@LaunchedEffect
        }
        peopleError = null
        runCatching { services.listPeople(session, peopleBackend.apiValue) }
            .onSuccess { peopleByBackend[peopleBackend] = it }
            .onFailure { peopleError = it.message ?: "Could not load people from Memories." }
    }

    LaunchedEffect(destination, loadAttempt) {
        if (destination != PhotoDestination.Albums || catalog != null) return@LaunchedEffect
        error = null
        try {
            catalog = loadActiveNativeMediaCollectionCatalog {
                collectionService.loadCatalog(session)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load albums and tags."
        }
    }

    suspend fun loadCollectionPage(collection: NativeMediaCollection, reset: Boolean) {
        val generation = ++requestGeneration
        loading = true
        error = null
        val result = runCatching {
            val index = if (reset || dayIndex?.collectionKey != collection.key) {
                collectionService.loadDayIndex(session, collection)
            } else {
                requireNotNull(dayIndex)
            }
            val page = collectionService.loadPage(
                session = session,
                collection = collection,
                index = index,
                cursor = if (reset) null else cursor,
            )
            val resolved = if (userId == null || page.items.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.resolveFilesById(session, userId, page.items.map(NativeMediaItem::fileId))
                }.getOrDefault(emptyMap())
            }
            val statuses = if (userId == null || resolved.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.loadMediaBackupStatuses(session, userId, resolved.values)
                }.getOrDefault(emptyMap())
            }
            Triple(index, page, resolved) to statuses
        }
        if (generation != requestGeneration || selectedCollection?.key != collection.key) return
        result.onSuccess { (loaded, statuses) ->
            val (index, page, resolved) = loaded
            dayIndex = index
            collectionItems = if (reset) page.items else (collectionItems + page.items).distinctBy { it.fileId }
            resolvedFiles = if (reset) resolved else resolvedFiles + resolved
            backupStatuses = if (reset) statuses else backupStatuses + statuses
            cursor = page.nextCursor
        }.onFailure {
            error = it.message ?: "Could not load this collection."
        }
        loading = false
    }

    val scope = rememberCoroutineScope()
    selectedCollection?.let { collection ->
        fun closeCollection() {
            requestGeneration += 1
            selectedCollection = null
            dayIndex = null
            collectionItems = emptyList()
            resolvedFiles = emptyMap()
            backupStatuses = emptyMap()
            cursor = null
            loading = false
            error = null
            pendingAction = null
            mutationRunning = false
            mutationError = null
        }
        PlatformBackHandler(enabled = true, onBack = ::closeCollection)
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            ScreenHeader(
                collection.name,
                when (collection.type) {
                    NativeMediaCollectionType.Album -> "Album"
                    NativeMediaCollectionType.SystemTag -> "Tagged media"
                },
                onBack = ::closeCollection,
            )
            when {
                error != null && collectionItems.isEmpty() -> ErrorMessage(requireNotNull(error)) {
                    scope.launch { loadCollectionPage(collection, reset = true) }
                }
                loading && collectionItems.isEmpty() -> LoadingMessage("Loading ${collection.name}...")
                collectionItems.isEmpty() && dayIndex != null -> EmptyMessage("This collection has no indexed media.")
                else -> NativeMediaCollectionContent(
                    collection = collection,
                    items = collectionItems,
                    resolvedFiles = resolvedFiles,
                    backupStatuses = backupStatuses,
                    services = services,
                    session = session,
                    loadingMore = loading,
                    canLoadMore = cursor != null,
                    loadMoreError = error,
                    onOpenMedia = { file, files ->
                        onOpenMedia(
                            file,
                            mediaStackViewerSequence(stackMediaFiles(files)),
                        )
                    },
                    onLongPressMedia = if (collection.type == NativeMediaCollectionType.Album) {
                        { item ->
                            pendingAction = planRemoveItemFromMediaCollection(
                                collection = collection,
                                item = item,
                                currentUserId = userId,
                            )
                            mutationError = null
                        }
                    } else {
                        null
                    },
                    onLoadMore = { scope.launch { loadCollectionPage(collection, reset = false) } },
                    gridState = collectionGridState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        pendingAction?.let { plan ->
            AlertDialog(
                onDismissRequest = {
                    if (!mutationRunning) {
                        pendingAction = null
                        mutationError = null
                    }
                },
                title = { Text(plan.confirmation.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                        Text(plan.confirmation.message)
                        Text(
                            plan.disabledReason
                                ?: mutationError
                                ?: "This changes album membership only. The original file is not deleted.",
                            color = when {
                                plan.disabledReason != null || mutationError != null ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (mutationRunning) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text("Updating album...")
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !mutationRunning,
                        onClick = {
                            pendingAction = null
                            mutationError = null
                        },
                    ) {
                        Text(if (plan.enabled) "Cancel" else "Close")
                    }
                },
                confirmButton = {
                    if (plan.enabled) {
                        Button(
                            enabled = !mutationRunning,
                            onClick = {
                                mutationRunning = true
                                mutationError = null
                                scope.launch {
                                    runCatching {
                                        collectionMutationService.executeConfirmed(
                                            session = session,
                                            plan = plan,
                                            confirmed = true,
                                        )
                                    }.onSuccess { result ->
                                        collectionItems = collectionItems.filterNot {
                                            it.fileId == result.removedFileId
                                        }
                                        val updatedCollection = collection.copy(
                                            itemCount = collection.itemCount?.let { count ->
                                                (count - 1).coerceAtLeast(0)
                                            },
                                        )
                                        selectedCollection = updatedCollection
                                        catalog = catalog?.let { currentCatalog ->
                                            currentCatalog.copy(
                                                albums = currentCatalog.albums.map { album ->
                                                    if (album.key == updatedCollection.key) {
                                                        updatedCollection
                                                    } else {
                                                        album
                                                    }
                                                },
                                            )
                                        }
                                        pendingAction = null
                                        mutationRunning = false
                                        mutationError = null
                                    }.onFailure { failure ->
                                        mutationRunning = false
                                        mutationError = failure.message
                                            ?: "Could not remove this item from the album."
                                    }
                                }
                            },
                        ) {
                            Text(plan.confirmation.confirmLabel)
                        }
                    }
                },
            )
        }
        return
    }

    val createAlbumPlan = remember(createAlbumName, userId, catalog) {
        planCreateMediaAlbum(
            name = createAlbumName,
            currentUserId = userId,
            existingAlbums = catalog?.albums.orEmpty(),
        )
    }
    if (createAlbumVisible) {
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    createAlbumVisible = false
                    createAlbumName = ""
                }
            },
            title = { Text("New album") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    OutlinedTextField(
                        value = createAlbumName,
                        onValueChange = { createAlbumName = it },
                        label = { Text("Album name") },
                        singleLine = true,
                        isError = createAlbumName.isNotEmpty() && createAlbumPlan.disabledReason != null,
                    )
                    Text(
                        createAlbumPlan.disabledReason
                            ?: "The album starts empty. No files will be moved, copied, or deleted.",
                        color = if (createAlbumPlan.disabledReason != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        createAlbumVisible = false
                        createAlbumName = ""
                    },
                ) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    enabled = createAlbumPlan.enabled,
                    onClick = {
                        pendingAction = createAlbumPlan
                        createAlbumVisible = false
                        mutationError = null
                    },
                ) { Text("Review") }
            },
        )
    }

    mediaToAdd?.takeIf { pendingAction == null }?.let { file ->
        val albums = catalog?.albums.orEmpty()
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    mediaToAdd = null
                    mutationError = null
                }
            },
            title = { Text("Add ${file.name} to album") },
            text = {
                when {
                    mutationError != null && catalog == null -> Text(
                        requireNotNull(mutationError),
                        color = MaterialTheme.colorScheme.error,
                    )
                    catalog == null -> Row(
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Loading albums...")
                    }
                    albums.isEmpty() -> Text("Create an album first.")
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        listItems(albums, key = NativeMediaCollection::key) { album ->
                            val plan = planAddFileToMediaCollection(album, file, userId)
                            FilledTonalButton(
                                enabled = plan.enabled,
                                onClick = {
                                    pendingAction = plan
                                    mutationError = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(album.name)
                                    if (!plan.enabled) {
                                        Text(
                                            requireNotNull(plan.disabledReason),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mediaToAdd = null }) { Text("Close") }
            },
        )
    }

    pendingAction?.let { plan ->
        AlertDialog(
            onDismissRequest = {
                if (!mutationRunning) {
                    pendingAction = null
                    mutationError = null
                }
            },
            title = { Text(plan.confirmation.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(plan.confirmation.message)
                    Text(
                        plan.disabledReason
                            ?: mutationError
                            ?: when (plan.risk) {
                                NativeMediaCollectionActionRisk.CollectionStructure ->
                                    "This creates an empty Photos album only."
                                NativeMediaCollectionActionRisk.CollectionMembership ->
                                    "This changes album membership only. The original file is not deleted."
                            },
                        color = when {
                            plan.disabledReason != null || mutationError != null ->
                                MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (mutationRunning) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("Updating album...")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !mutationRunning,
                    onClick = {
                        pendingAction = null
                        mutationError = null
                    },
                ) { Text(if (plan.enabled) "Cancel" else "Close") }
            },
            confirmButton = {
                if (plan.enabled) {
                    Button(
                        enabled = !mutationRunning,
                        onClick = {
                            mutationRunning = true
                            mutationError = null
                            scope.launch {
                                runCatching {
                                    collectionMutationService.executeConfirmed(
                                        session = session,
                                        plan = plan,
                                        confirmed = true,
                                    )
                                }.onSuccess { result ->
                                    when (result.action) {
                                        NativeMediaCollectionAction.CreateCollection -> {
                                            createAlbumName = ""
                                            catalog = null
                                            loadAttempt += 1
                                        }
                                        NativeMediaCollectionAction.AddItem -> {
                                            catalog = catalog?.let { currentCatalog ->
                                                currentCatalog.copy(
                                                    albums = currentCatalog.albums.map { album ->
                                                        if (album.key == plan.collectionKey && !result.alreadyPresent) {
                                                            album.copy(itemCount = album.itemCount?.plus(1))
                                                        } else {
                                                            album
                                                        }
                                                    },
                                                )
                                            }
                                            mediaToAdd = null
                                        }
                                        NativeMediaCollectionAction.RemoveItem -> Unit
                                    }
                                    pendingAction = null
                                    mutationRunning = false
                                    mutationError = null
                                }.onFailure { failure ->
                                    mutationRunning = false
                                    mutationError = failure.message ?: "Could not update this album."
                                }
                            }
                        },
                    ) { Text(plan.confirmation.confirmLabel) }
                }
            },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        val widthClass = when {
            maxWidth < 600.dp -> PhotoNavigationWidthClass.Compact
            maxWidth < 900.dp -> PhotoNavigationWidthClass.Medium
            else -> PhotoNavigationWidthClass.Expanded
        }
        val navigationIntent = planPhotoNavigation(
            state = PhotoNavigationState(destination),
            capabilities = PhotoNavigationCapabilities(
                albumsAvailable = true,
                peopleAvailable = true,
                favoritesAvailable = false,
            ),
            widthClass = widthClass,
        )
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Photos & Memories",
                photoDestinationSubtitle(navigationIntent.activeDestination),
                onBack,
            )
            PhotoAdaptiveNavigationLayout(
                intent = navigationIntent,
                onDestinationSelected = { selected ->
                    onPhotoBrowserStateChanged(photoBrowserState.copy(destination = selected))
                },
                modifier = Modifier.weight(1f),
            ) {
                when (navigationIntent.activeDestination) {
                    PhotoDestination.Timeline -> {
            val recoveryLoadKind = timeline.recoveryLoadKind
            when {
                timeline.error != null && timeline.entries.isEmpty() -> ErrorMessage(
                    requireNotNull(timeline.error),
                ) { mediaLoadAttempt += 1 }
                timeline.loading?.kind == PhotoTimelineLoadKind.Refresh &&
                    timeline.entries.isEmpty() -> LoadingMessage("Building your photo timeline...")
                timeline.entries.isEmpty() -> EmptyMessage("No previewable media was found.")
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (
                            timeline.error != null &&
                            recoveryLoadKind != PhotoTimelineLoadKind.NextPage
                        ) {
                            PhotoTimelineFailureNotice(
                                message = requireNotNull(timeline.error),
                                onRetry = {
                                    scope.launch {
                                        loadTimelinePage(
                                            recoveryLoadKind ?: PhotoTimelineLoadKind.Refresh,
                                        )
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = NextcloudSpacing.Medium,
                                        vertical = NextcloudSpacing.Small,
                                    ),
                                actionLabel = if (
                                    recoveryLoadKind == PhotoTimelineLoadKind.Refresh
                                ) {
                                    "Refresh"
                                } else {
                                    "Retry"
                                },
                            )
                        }
                        if (timelineNavigationError != null) {
                            PhotoTimelineFailureNotice(
                                message = requireNotNull(timelineNavigationError),
                                onRetry = { timelineNavigationError = null },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = NextcloudSpacing.Medium,
                                        vertical = NextcloudSpacing.Small,
                                    ),
                                actionLabel = "Dismiss",
                            )
                        }
                        if (timeline.optionalRawSearchRetryPending) {
                            PhotoTimelineFailureNotice(
                                message = "Some RAW photos could not be loaded. " +
                                    "They will be retried without hiding other photos.",
                                onRetry = {
                                    scope.launch {
                                        loadTimelinePage(PhotoTimelineLoadKind.NextPage)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = NextcloudSpacing.Medium,
                                        vertical = NextcloudSpacing.Small,
                                    ),
                                actionLabel = "Retry RAW",
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = NextcloudSpacing.Medium,
                                    vertical = NextcloudSpacing.XSmall,
                                ),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            PhotoTimelineViewModeControl(
                                viewMode = photoBrowserState.timelineViewMode,
                                onViewModeChanged = { mode ->
                                    onPhotoBrowserStateChanged(
                                        photoBrowserState.copy(timelineViewMode = mode),
                                    )
                                },
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            LazyVerticalGrid(
                                columns = when (photoBrowserState.timelineViewMode) {
                                    PhotoTimelineViewMode.Grid -> GridCells.Adaptive(120.dp)
                                    PhotoTimelineViewMode.List -> GridCells.Fixed(1)
                                },
                                state = timelineGridState,
                                contentPadding = PaddingValues(
                                    start = 4.dp,
                                    top = 4.dp,
                                    end = PhotoTimelineScrubberTouchLaneWidth,
                                    bottom = NextcloudSpacing.XLarge,
                                ),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                timelineDateIndex.sections.forEach { section ->
                                    item(
                                        key = "month:${section.month.year}:${section.month.month}",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        Text(
                                            text = section.month.label,
                                            modifier = Modifier.padding(
                                                horizontal = NextcloudSpacing.Medium,
                                                vertical = NextcloudSpacing.Large,
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                    items(
                                        count = section.itemCount,
                                        key = { offset ->
                                            indexedTimelineStacks[
                                                section.firstItemIndex + offset
                                            ].stack.id
                                        },
                                    ) { offset ->
                                        val stack = indexedTimelineStacks[
                                            section.firstItemIndex + offset
                                        ].stack
                                        MediaTile(
                                            services = services,
                                            session = session,
                                            userId = userId,
                                            file = stack.cover,
                                            badge = stack.badge,
                                            backupStatus = timelineBackupStatuses[
                                                stack.cover.path.trim('/')
                                            ],
                                            layout = when (photoBrowserState.timelineViewMode) {
                                                PhotoTimelineViewMode.Grid -> MediaTileLayout.SquareCrop
                                                PhotoTimelineViewMode.List -> MediaTileLayout.FullWidthFit
                                            },
                                            onClick = {
                                                onOpenMedia(stack.cover, timelineViewerSequence)
                                            },
                                            onLongClick = {
                                                mediaToAdd = stack.cover
                                                mutationError = null
                                                if (catalog == null) {
                                                    scope.launch {
                                                        runCatching {
                                                            collectionService.loadCatalog(session)
                                                        }.onSuccess { catalog = it }
                                                            .onFailure {
                                                                mutationError = it.message
                                                                    ?: "Could not load albums."
                                                            }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                                if (timeline.loading?.kind == PhotoTimelineLoadKind.NextPage) {
                                    item(
                                        key = "timeline-loading",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        LoadingMessage("Loading older photos...")
                                    }
                                } else if (
                                    timeline.error != null &&
                                    recoveryLoadKind == PhotoTimelineLoadKind.NextPage
                                ) {
                                    item(
                                        key = "timeline-error",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        ErrorMessage(requireNotNull(timeline.error)) {
                                            scope.launch {
                                                loadTimelinePage(PhotoTimelineLoadKind.NextPage)
                                            }
                                        }
                                    }
                                } else if (timeline.canPrefetchNextPage) {
                                    item(
                                        key = "timeline-load-older",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(NextcloudSpacing.Large),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        loadTimelinePage(
                                                            PhotoTimelineLoadKind.NextPage,
                                                        )
                                                    }
                                                },
                                            ) {
                                                Text("Load older photos")
                                            }
                                        }
                                    }
                                }
                            }
                            PhotoTimelineDateScrubber(
                                dateIndex = timelineDateIndex,
                                activeGridItemIndex = timelineGridState.firstVisibleItemIndex,
                                onJumpToGridItem = { index ->
                                    timelineGridState.scrollToItem(index)
                                },
                                fullGeometry = timelineNavigationSnapshot?.geometry,
                                onJumpToAdvertisedDay =
                                    timelineNavigationSnapshot?.let {
                                        { dayId -> loadTimelineNavigationTarget(dayId) }
                                    },
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight(),
                            )
                            if (timeline.hasDiscardedNewerEntries) {
                                OutlinedButton(
                                    enabled = timeline.loading == null,
                                    onClick = {
                                        scope.launch {
                                            loadTimelinePage(PhotoTimelineLoadKind.Refresh)
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(
                                            top = NextcloudSpacing.Small,
                                            end = (
                                                PhotoTimelineScrubberTouchLaneWidth +
                                                    NextcloudSpacing.Small
                                                ),
                                        ),
                                ) {
                                    Text("Back to newest")
                                }
                            }
                        }
                    }
                }
            }
                    }
                    PhotoDestination.Folders -> {
                        when (
                            photoFolderInventoryReadiness(
                                hasInventory = pagedFolderInventory != null,
                                refreshError = folderPagingState.error,
                            )
                        ) {
                            PhotoFolderInventoryReadiness.InitialFailure ->
                                ErrorMessage(requireNotNull(folderPagingState.error)) {
                                    mediaLoadAttempt += 1
                                }
                            PhotoFolderInventoryReadiness.Loading ->
                                LoadingMessage("Loading photo folders...")
                            PhotoFolderInventoryReadiness.Ready,
                            PhotoFolderInventoryReadiness.Stale ->
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (folderPagingState.error != null) {
                                        PhotoFolderStaleNotice(
                                            message = requireNotNull(folderPagingState.error),
                                            onRetry = { mediaLoadAttempt += 1 },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = NextcloudSpacing.Medium,
                                                    vertical = NextcloudSpacing.Small,
                                            ),
                                        )
                                    }
                                    folderPagingState.incompleteInventoryMessage()?.let { message ->
                                        PhotoFolderIncompleteNotice(
                                            message = message,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = NextcloudSpacing.Medium,
                                                    vertical = NextcloudSpacing.Small,
                                                ),
                                        )
                                    }
                                    PhotoFolderBrowser(
                                        inventory = requireNotNull(pagedFolderInventory),
                                        selectedFolderPath = photoFolderState.selectedFolderPath,
                                        query = photoFolderState.query,
                                        scope = photoFolderState.scope,
                                        viewMode = photoFolderState.preference.viewMode,
                                        backupStatuses = pagedFolderBackupStatuses,
                                        gridState = folderGridState,
                                        listState = folderListState,
                                        services = services,
                                        session = session,
                                        userId = userId,
                                        onSelectedFolderPathChanged = { selectedPath ->
                                            onPhotoBrowserStateChanged(
                                                photoBrowserState.copy(
                                                    folder = photoFolderState.copy(
                                                        selectedFolderPath = selectedPath,
                                                        query = "",
                                                    ),
                                                ),
                                            )
                                        },
                                        onQueryChanged = { query ->
                                            onPhotoBrowserStateChanged(
                                                photoBrowserState.copy(
                                                    folder = photoFolderState.copy(query = query),
                                                ),
                                            )
                                        },
                                        onScopeChanged = { scope ->
                                            onPhotoBrowserStateChanged(
                                                photoBrowserState.copy(
                                                    folder = photoFolderState.copy(scope = scope),
                                                ),
                                            )
                                        },
                                        onViewModeChanged = { viewMode ->
                                            onPhotoBrowserStateChanged(
                                                photoBrowserState.copy(
                                                    folder = photoFolderState.copy(
                                                        preference = photoFolderState.preference.copy(
                                                            viewMode = viewMode,
                                                        ),
                                                    ),
                                                ),
                                            )
                                        },
                                        onOpenMedia = onOpenMedia,
                                        modifier = Modifier.fillMaxWidth().weight(1f),
                                    )
                                }
                        }
                    }
                    PhotoDestination.Albums -> {
            when {
                error != null -> ErrorMessage(requireNotNull(error)) {
                    catalog = null
                    loadAttempt += 1
                }
                catalog == null -> LoadingMessage("Loading albums and tags...")
                else -> NativeMediaCollectionBrowser(
                    catalog = requireNotNull(catalog),
                    state = browserState,
                    services = services,
                    session = session,
                    onStateChange = { browserState = it },
                    onCreateAlbum = {
                        createAlbumName = ""
                        createAlbumVisible = true
                        mutationError = null
                    },
                    onOpenCollection = { collection ->
                        requestGeneration += 1
                        selectedCollection = collection
                        dayIndex = null
                        collectionItems = emptyList()
                        resolvedFiles = emptyMap()
                        backupStatuses = emptyMap()
                        cursor = null
                        loading = true
                        error = null
                        scope.launch { loadCollectionPage(collection, reset = true) }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
                    }
                    PhotoDestination.People -> {
            val people = peopleByBackend[peopleBackend]
            val gallery = buildPeopleGalleryPresentation(
                people = people.orEmpty(),
                backend = peopleBackend,
                query = peopleSearch,
                nameFilter = peopleNameFilter,
            )
            Column(Modifier.fillMaxSize()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = NextcloudSpacing.Large),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                item {
                    FilterChip(
                        selected = peopleBackend == NextcloudPeopleBackend.Recognize,
                        onClick = {
                            peopleBackend = NextcloudPeopleBackend.Recognize
                            peopleError = null
                        },
                        label = { Text("Recognize") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleBackend == NextcloudPeopleBackend.FaceRecognition,
                        onClick = {
                            peopleBackend = NextcloudPeopleBackend.FaceRecognition
                            peopleError = null
                        },
                        label = { Text("Face Recognition") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleNameFilter == PeopleNameFilter.All,
                        onClick = { peopleNameFilter = PeopleNameFilter.All },
                        label = { Text("All ${gallery.totalCount}") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleNameFilter == PeopleNameFilter.Named,
                        onClick = { peopleNameFilter = PeopleNameFilter.Named },
                        label = { Text("Named ${gallery.namedCount}") },
                    )
                }
                item {
                    FilterChip(
                        selected = peopleNameFilter == PeopleNameFilter.Unnamed,
                        onClick = { peopleNameFilter = PeopleNameFilter.Unnamed },
                        label = { Text("Unnamed ${gallery.unnamedCount}") },
                    )
                }
            }
            if (people != null) {
                OutlinedTextField(
                    value = peopleSearch,
                    onValueChange = { peopleSearch = it },
                    modifier = Modifier.fillMaxWidth().padding(
                        start = NextcloudSpacing.Large,
                        end = NextcloudSpacing.Large,
                        bottom = NextcloudSpacing.Small,
                    ),
                    label = { Text("Find a person") },
                    leadingIcon = { Icon(NextcloudIcons.Search, contentDescription = null) },
                    singleLine = true,
                )
            }
            val visiblePeople = gallery.people
            when {
                peopleError != null -> ErrorMessage(requireNotNull(peopleError)) {
                    peopleByBackend.remove(peopleBackend)
                    peopleLoadAttempt += 1
                }
                people == null -> LoadingMessage("Loading recognized people...")
                people.isEmpty() -> EmptyMessage("Memories has not returned any recognized people yet.")
                visiblePeople.isEmpty() -> EmptyMessage("No recognized person matches your search.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    items(visiblePeople, key = NextcloudPerson::id) { person ->
                        PersonTile(
                            services = services,
                            session = session,
                            person = person,
                            onClick = { onOpenPerson(person) },
                        )
                    }
                }
            }
            }
                    }
                    PhotoDestination.Favorites -> EmptyMessage(
                        "Favorite media is not available from this server.",
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun PersonTile(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    person: NextcloudPerson,
    onClick: () -> Unit,
) {
    var image by remember(person.id, person.coverFileId, person.coverEtag) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(person.id, person.coverFileId, person.coverEtag) {
        if (person.coverFileId == null) return@LaunchedEffect
        image = runCatching {
            decodePlatformImage(
                services.loadPersonCoverCached(session, person),
                EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            )
        }.getOrNull()
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(NextcloudTheme.colors.appIconContainer),
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                Image(it, person.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } ?: Icon(
                NextcloudIcons.People,
                contentDescription = person.name,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
        }
        Column(modifier = Modifier.padding(NextcloudSpacing.Medium)) {
            Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                "${person.count} ${if (person.count == 1) "photo" else "photos"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonMediaScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    currentUserId: String,
    recognizeBridge: RecognizeBridgeDiscovery,
    person: NextcloudPerson,
    onBack: () -> Unit,
    onPersonChanged: (NextcloudPerson) -> Unit,
    onOpenMedia: (NextcloudFile, List<NextcloudFile>) -> Unit,
) {
    var mediaItems by remember(person.id, person.backend) { mutableStateOf<List<NativeMediaItem>?>(null) }
    var resolvedMediaFiles by remember(person.id, person.backend) {
        mutableStateOf<Map<Long, NextcloudFile>>(emptyMap())
    }
    var mediaBackupStatuses by remember(person.id, person.backend) {
        mutableStateOf<Map<String, MediaBackupStatus>>(emptyMap())
    }
    var mediaDayIndex by remember(person.id, person.backend) { mutableStateOf<PersonMediaDayIndex?>(null) }
    var mediaCursor by remember(person.id, person.backend) { mutableStateOf<NativeMediaDayCursor?>(null) }
    var mediaLoadingMore by remember(person.id, person.backend) { mutableStateOf(false) }
    var mediaLoadMoreError by remember(person.id, person.backend) { mutableStateOf<String?>(null) }
    var mediaRequestGeneration by remember(person.id, person.backend) { mutableStateOf(0) }
    var error by remember(person.id, person.backend) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(person.id, person.backend) { mutableStateOf(0) }
    var actionMenuExpanded by remember(person.id) { mutableStateOf(false) }
    // Server info is loaded again after Android restores the activity. During that window,
    // currentUserId falls back to the login name and can then change to the canonical user ID.
    // Keep saveable workflow state keyed to the stable session identity instead.
    var showFaceRectangles by rememberSaveable(
        session.serverUrl,
        session.loginName,
        person.id,
        person.backend,
    ) {
        mutableStateOf(false)
    }
    var photoSelectionMode by remember(person.id) { mutableStateOf<PersonPhotoSelectionMode?>(null) }
    var renameDialogVisible by remember(person.id) { mutableStateOf(false) }
    var renameDraft by remember(person.id) { mutableStateOf(person.name) }
    var renameValidationError by remember(person.id) { mutableStateOf<String?>(null) }
    var mergePickerVisible by remember(person.id) { mutableStateOf(false) }
    var mergeTargets by remember(person.id) { mutableStateOf<List<NextcloudPerson>?>(null) }
    var mergeTargetsError by remember(person.id) { mutableStateOf<String?>(null) }
    var mergeSearch by remember(person.id) { mutableStateOf("") }
    var mergePreparing by remember(person.id) { mutableStateOf(false) }
    var mergePrepareError by remember(person.id) { mutableStateOf<String?>(null) }
    var pendingMergeWorkflow by remember(person.id) { mutableStateOf<PersonMergeWorkflow?>(null) }
    var pendingPlan by remember(person.id) { mutableStateOf<PeopleActionPlan?>(null) }
    var postMutationRefreshExpectationState by rememberSaveable(
        session.serverUrl,
        session.loginName,
        person.id,
        person.backend,
    ) {
        mutableStateOf<String?>(null)
    }
    val postMutationRefreshExpectation = remember(postMutationRefreshExpectationState) {
        postMutationRefreshExpectationState?.let(::decodePeoplePostMutationExpectation)
    }
    var postMutationRefreshAttempt by rememberSaveable(
        session.serverUrl,
        session.loginName,
        person.id,
        person.backend,
    ) {
        mutableStateOf(0)
    }
    var postMutationRefreshRunning by remember(person.id, person.backend) { mutableStateOf(false) }
    var postMutationRefreshError by remember(person.id, person.backend) { mutableStateOf<String?>(null) }
    var recognizedFaces by remember(person.id) { mutableStateOf<List<RecognizedFaceMedia>?>(null) }
    var recognizedFaceDayIndex by remember(person.id) { mutableStateOf<PersonMediaDayIndex?>(null) }
    var recognizedFaceCursor by remember(person.id) { mutableStateOf<NativeMediaDayCursor?>(null) }
    var recognizedFacesLoadingMore by remember(person.id) { mutableStateOf(false) }
    var recognizedFacesLoadMoreError by remember(person.id) { mutableStateOf<String?>(null) }
    var recognizedFacesRequestGeneration by remember(person.id) { mutableStateOf(0) }
    var recognizedFacesError by remember(person.id) { mutableStateOf<String?>(null) }
    var recognizedFacesLoadAttempt by remember(person.id) { mutableStateOf(0) }
    val personReference = remember(person) { person.toMediaReference() }
    val personMediaReadService = remember(services) { NextcloudPersonMediaReadService(services) }
    val recognizedFaceReadService = remember(services) { RecognizedFaceReadService(services) }
    val mutationService = remember(services, session.serverUrl, session.loginName) {
        PeopleMutationService(services)
    }
    val peopleMergeService = remember(recognizedFaceReadService, mutationService) {
        PeopleMergeService(recognizedFaceReadService, mutationService)
    }
    val scope = rememberCoroutineScope()
    val mediaGridState = rememberLazyGridState()
    val mediaFiles = remember(personReference, mediaItems, resolvedMediaFiles) {
        mediaItems?.map { item ->
            item.toPersonMediaFile(personReference, resolvedMediaFiles[item.fileId])
        }
    }
    val actionSupport = remember(currentUserId, person.id, person.backend, recognizeBridge) {
        PeopleActionSupport(
            currentUserId = currentUserId,
            memoriesPeopleApiAvailable = true,
            recognizeDavAvailable = true,
            recognizeApiKeyRequired = true,
            recognizeApiKeyAvailable = recognizeBridge is RecognizeBridgeDiscovery.Available,
        )
    }

    suspend fun loadPersonMediaPage(reset: Boolean) {
        val generation = ++mediaRequestGeneration
        mediaLoadingMore = true
        if (reset) {
            error = null
            mediaLoadMoreError = null
        } else {
            mediaLoadMoreError = null
        }
        val result = runCatching {
            val index = if (reset || mediaDayIndex?.person != personReference) {
                personMediaReadService.loadDayIndex(session, personReference)
            } else {
                requireNotNull(mediaDayIndex)
            }
            val page = personMediaReadService.loadPage(
                session = session,
                person = personReference,
                index = index,
                cursor = if (reset) null else mediaCursor,
            )
            val resolved = if (page.items.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.resolveFilesById(
                        session = session,
                        userId = currentUserId,
                        fileIds = page.items.map(NativeMediaItem::fileId),
                    )
                }.getOrDefault(emptyMap())
            }
            val statuses = if (resolved.isEmpty()) {
                emptyMap()
            } else {
                runCatching {
                    services.loadMediaBackupStatuses(session, currentUserId, resolved.values)
                }.getOrDefault(emptyMap())
            }
            Triple(index, page, resolved) to statuses
        }
        if (generation != mediaRequestGeneration) return
        result.onSuccess { (loaded, statuses) ->
            val (index, page, resolved) = loaded
            mediaDayIndex = index
            mediaItems = if (reset) {
                page.items
            } else {
                (mediaItems.orEmpty() + page.items).distinctBy(NativeMediaItem::fileId)
            }
            resolvedMediaFiles = if (reset) resolved else resolvedMediaFiles + resolved
            mediaBackupStatuses = if (reset) statuses else mediaBackupStatuses + statuses
            mediaCursor = page.nextCursor
        }.onFailure { failure ->
            val message = failure.message ?: "Could not load photos for this person."
            if (reset || mediaItems.isNullOrEmpty()) error = message else mediaLoadMoreError = message
        }
        mediaLoadingMore = false
    }

    suspend fun loadRecognizedFacePage(reset: Boolean) {
        val generation = ++recognizedFacesRequestGeneration
        recognizedFacesLoadingMore = true
        if (reset) {
            recognizedFacesError = null
            recognizedFacesLoadMoreError = null
        } else {
            recognizedFacesLoadMoreError = null
        }
        val result = runCatching {
            val index = if (reset || recognizedFaceDayIndex?.person != personReference) {
                recognizedFaceReadService.loadDayIndex(session, personReference)
            } else {
                requireNotNull(recognizedFaceDayIndex)
            }
            val page = recognizedFaceReadService.loadPage(
                session = session,
                person = personReference,
                index = index,
                cursor = if (reset) null else recognizedFaceCursor,
            )
            index to page
        }
        if (
            generation != recognizedFacesRequestGeneration ||
            photoSelectionMode != PersonPhotoSelectionMode.RemoveFace
        ) return
        result.onSuccess { (index, page) ->
            recognizedFaceDayIndex = index
            recognizedFaces = if (reset) {
                page.faces
            } else {
                (recognizedFaces.orEmpty() + page.faces).distinctBy(RecognizedFaceMedia::detectionId)
            }
            recognizedFaceCursor = page.nextCursor
        }.onFailure { failure ->
            val message = failure.message ?: "Could not load exact face assignments."
            if (reset || recognizedFaces.isNullOrEmpty()) {
                recognizedFacesError = message
            } else {
                recognizedFacesLoadMoreError = message
            }
        }
        recognizedFacesLoadingMore = false
    }

    fun requestPersonRefresh(expectation: PeoplePostMutationExpectation) {
        postMutationRefreshExpectationState = expectation.encodeForSavedState()
        postMutationRefreshAttempt += 1
        postMutationRefreshError = null
    }

    LaunchedEffect(postMutationRefreshExpectationState, postMutationRefreshAttempt) {
        val expectation = postMutationRefreshExpectation ?: return@LaunchedEffect
        postMutationRefreshRunning = true
        postMutationRefreshError = null
        try {
            val people = services.listPeople(session, person.backend)
            val initialReconciliation = reconcilePersonAfterMutation(
                expectation = expectation,
                previous = person,
                refreshedPeople = people,
            )
            val reconciliation =
                if (
                    expectation is PeoplePostMutationExpectation.RemoveFace &&
                    initialReconciliation is PeoplePostMutationReconciliation.Pending
                ) {
                    val refreshedFaceDetectionIds =
                        recognizedFaceReadService.loadCompleteFacesForReconciliation(session, personReference)
                            .mapTo(linkedSetOf(), RecognizedFaceMedia::detectionId)
                    reconcilePersonAfterMutation(
                        expectation = expectation,
                        previous = person,
                        refreshedPeople = people,
                        refreshedFaceDetectionIds = refreshedFaceDetectionIds,
                    )
                } else {
                    initialReconciliation
                }
            when (reconciliation) {
                is PeoplePostMutationReconciliation.CurrentPerson -> {
                    onPersonChanged(reconciliation.person)
                    loadAttempt += 1
                    postMutationRefreshExpectationState = null
                }
                PeoplePostMutationReconciliation.Gallery -> {
                    postMutationRefreshExpectationState = null
                    onBack()
                }
                PeoplePostMutationReconciliation.Pending -> {
                    postMutationRefreshError =
                        "The change was saved, but the exact server postcondition is not visible yet. Retry the refresh."
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            postMutationRefreshError = failure.message?.takeIf(String::isNotBlank)
                ?: "The change was saved, but this person could not be refreshed."
        } finally {
            postMutationRefreshRunning = false
        }
    }

    fun closePhotoSelection() {
        if (photoSelectionMode == PersonPhotoSelectionMode.RemoveFace) {
            recognizedFacesRequestGeneration += 1
            recognizedFacesLoadingMore = false
        }
        photoSelectionMode = null
    }

    DisposableEffect(person.id, person.backend) {
        onDispose {
            mediaRequestGeneration += 1
            mediaLoadingMore = false
        }
    }
    LaunchedEffect(person.id, person.backend, loadAttempt) {
        mediaItems = null
        resolvedMediaFiles = emptyMap()
        mediaDayIndex = null
        mediaCursor = null
        mediaLoadMoreError = null
        error = null
        loadPersonMediaPage(reset = true)
    }
    LaunchedEffect(person.id, person.backend, services) {
        services.observeMediaBackupStatusChanges(session).collectLatest {
            val visibleFiles = resolvedMediaFiles.values
            if (visibleFiles.isNotEmpty()) {
                val statuses = runCatching {
                    services.loadMediaBackupStatuses(session, currentUserId, visibleFiles)
                }.getOrDefault(emptyMap())
                mediaBackupStatuses = mediaBackupStatuses + statuses
            }
        }
    }
    LaunchedEffect(mergePickerVisible, person.id, person.backend) {
        if (!mergePickerVisible || mergeTargets != null) return@LaunchedEffect
        mergeTargetsError = null
        runCatching { services.listPeople(session, person.backend) }
            .onSuccess { people ->
                mergeTargets = people.filter { candidate ->
                    candidate.id != person.id && candidate.userId == person.userId
                }
            }
            .onFailure { mergeTargetsError = it.message ?: "Could not load merge targets." }
    }
    LaunchedEffect(photoSelectionMode, person.id, recognizedFacesLoadAttempt) {
        if (photoSelectionMode != PersonPhotoSelectionMode.RemoveFace) return@LaunchedEffect
        recognizedFaces = null
        recognizedFaceDayIndex = null
        recognizedFaceCursor = null
        recognizedFacesLoadMoreError = null
        recognizedFacesError = null
        loadRecognizedFacePage(reset = true)
    }

    val menuItems = personActionMenuItems(
        person = personReference,
        support = actionSupport,
        hasSelectablePhoto = mediaFiles.orEmpty().any { !it.isDirectory && it.fileId != null },
        hasDirectFaceReferences =
            NextcloudPeopleBackend.fromApiValue(person.backend) == NextcloudPeopleBackend.Recognize,
    )
    val faceRectanglesAvailable = remember(mediaItems) {
        mediaItems.orEmpty().any { item -> item.faceOutlineGeometryOrNull() != null }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(
            person.name,
            when (photoSelectionMode) {
                PersonPhotoSelectionMode.Cover -> "Choose a new cover photo"
                PersonPhotoSelectionMode.RemoveFace -> "Choose a face to remove"
                null -> mediaFiles?.let { "${it.size} loaded · ${person.count} recognized" } ?: "Recognized photos"
            },
            onBack = {
                if (photoSelectionMode != null) closePhotoSelection() else onBack()
            },
            trailingContent = {
                Box {
                    IconButton(
                        enabled = postMutationRefreshExpectation == null,
                        onClick = { actionMenuExpanded = true },
                    ) {
                        Icon(NextcloudIcons.More, contentDescription = "Person actions")
                    }
                    DropdownMenu(
                        expanded = actionMenuExpanded,
                        onDismissRequest = { actionMenuExpanded = false },
                        modifier = Modifier.widthIn(min = 280.dp, max = 360.dp),
                    ) {
                        if (faceRectanglesAvailable) {
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(if (showFaceRectangles) "Hide face outlines" else "Show face outlines")
                                        Text(
                                            "See which recognized face matched each photo",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(NextcloudIcons.People, contentDescription = null)
                                },
                                onClick = {
                                    showFaceRectangles = !showFaceRectangles
                                    actionMenuExpanded = false
                                },
                            )
                        }
                        menuItems.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(item.label)
                                        item.disabledReason?.let { reason ->
                                            Text(
                                                reason,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                enabled = item.enabled,
                                onClick = {
                                    actionMenuExpanded = false
                                    when (item.action) {
                                        PeopleAction.RenamePerson -> {
                                            renameDraft = person.name
                                            renameValidationError = null
                                            renameDialogVisible = true
                                        }
                                        PeopleAction.MergePerson -> {
                                            mergeSearch = ""
                                            mergeTargets = null
                                            mergeTargetsError = null
                                            mergePickerVisible = true
                                        }
                                        PeopleAction.SetCover -> photoSelectionMode = PersonPhotoSelectionMode.Cover
                                        PeopleAction.RemoveFace -> photoSelectionMode = PersonPhotoSelectionMode.RemoveFace
                                        PeopleAction.DeletePerson -> pendingPlan = planDeletePerson(
                                            person = personReference,
                                            personDisplayName = person.name,
                                            support = actionSupport,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
        if (postMutationRefreshExpectation != null) {
            Surface(color = NextcloudTheme.colors.appTile) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = NextcloudSpacing.Large,
                        vertical = NextcloudSpacing.Medium,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (postMutationRefreshRunning) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            NextcloudIcons.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (postMutationRefreshRunning) {
                                "Refreshing person..."
                            } else {
                                "Change saved, refresh needed"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        postMutationRefreshError?.let { message ->
                            Text(
                                message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!postMutationRefreshRunning) {
                        TextButton(
                            onClick = {
                                postMutationRefreshAttempt += 1
                            },
                        ) { Text("Retry") }
                    }
                }
            }
        }
        photoSelectionMode?.takeIf { it == PersonPhotoSelectionMode.Cover }?.let { mode ->
            PersonPhotoSelectionBanner(
                title = when (mode) {
                    PersonPhotoSelectionMode.Cover -> "Choose one photo to use as ${person.name}'s cover."
                    PersonPhotoSelectionMode.RemoveFace ->
                        "Choose the exact face to remove. The source photo will stay in Files."
                },
                onCancel = ::closePhotoSelection,
            )
        }
        when {
            photoSelectionMode == PersonPhotoSelectionMode.RemoveFace -> when {
                recognizedFacesError != null -> ErrorMessage(requireNotNull(recognizedFacesError)) {
                    recognizedFacesLoadAttempt += 1
                }
                recognizedFaces == null -> LoadingMessage("Loading exact face assignments...")
                else -> RecognizedFaceRemovalPicker(
                    services = services,
                    session = session,
                    person = personReference,
                    personDisplayName = person.name,
                    faces = requireNotNull(recognizedFaces),
                    support = actionSupport,
                    loadingMore = recognizedFacesLoadingMore,
                    canLoadMore = recognizedFaceCursor != null,
                    loadMoreError = recognizedFacesLoadMoreError,
                    onLoadMore = {
                        if (!recognizedFacesLoadingMore && recognizedFaceCursor != null) {
                            scope.launch { loadRecognizedFacePage(reset = false) }
                        }
                    },
                    onPlanSelected = { plan ->
                        pendingPlan = plan
                        closePhotoSelection()
                    },
                    onCancel = ::closePhotoSelection,
                )
            }
            error != null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
            mediaItems == null -> LoadingMessage("Loading ${person.name}...")
            mediaItems?.isEmpty() == true -> EmptyMessage("No photos were returned for this person.")
            else -> {
                val loadedItems = requireNotNull(mediaItems)
                val loadedFiles = requireNotNull(mediaFiles)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    state = mediaGridState,
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(loadedItems, key = NativeMediaItem::fileId) { item ->
                        val file = item.toPersonMediaFile(personReference, resolvedMediaFiles[item.fileId])
                        val selectable = !file.isDirectory && file.fileId != null
                        MediaTile(
                            services = services,
                            session = session,
                            userId = currentUserId,
                            file = file,
                            badge = when {
                                postMutationRefreshRunning -> "Refreshing"
                                postMutationRefreshExpectation != null -> "Refresh needed"
                                photoSelectionMode != null && selectable -> "Choose"
                                else -> null
                            },
                            backupStatus = mediaBackupStatuses[file.path.trim('/')],
                            faceRectangle = item.faceRectangle.takeIf { showFaceRectangles },
                            sourceWidth = item.width,
                            sourceHeight = item.height,
                            enabled = postMutationRefreshExpectation == null,
                            onClick = {
                                when (photoSelectionMode) {
                                    PersonPhotoSelectionMode.Cover -> if (selectable) {
                                        pendingPlan = planSetPersonCover(
                                            person = personReference,
                                            personDisplayName = person.name,
                                            sourceFile = file,
                                            support = actionSupport,
                                        )
                                        photoSelectionMode = null
                                    }
                                    PersonPhotoSelectionMode.RemoveFace -> Unit
                                    null -> onOpenMedia(file, loadedFiles)
                                }
                            },
                        )
                    }
                    loadMoreItem(
                        loadingMore = mediaLoadingMore,
                        canLoadMore = mediaCursor != null,
                        error = mediaLoadMoreError,
                        onLoadMore = {
                            if (!mediaLoadingMore && mediaCursor != null) {
                                scope.launch { loadPersonMediaPage(reset = false) }
                            }
                        },
                    )
                }
            }
        }
    }

    if (renameDialogVisible) {
        val validationMessage = validatePersonRename(personReference, renameDraft)
        AlertDialog(
            onDismissRequest = { renameDialogVisible = false },
            title = { Text("Rename ${person.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = {
                            renameDraft = it
                            renameValidationError = null
                        },
                        label = { Text("Person name") },
                        singleLine = true,
                        isError = validationMessage != null || renameValidationError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    (renameValidationError ?: validationMessage)?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { renameDialogVisible = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    enabled = validationMessage == null,
                    onClick = {
                        runCatching {
                            planRenamePerson(
                                person = personReference,
                                currentDisplayName = person.name,
                                requestedName = renameDraft,
                                support = actionSupport,
                            )
                        }.onSuccess { plan ->
                            renameDialogVisible = false
                            pendingPlan = plan
                        }.onFailure { failure ->
                            renameValidationError = failure.message ?: "This name cannot be used."
                        }
                    },
                ) { Text("Review") }
            },
        )
    }

    if (mergePickerVisible) {
        val visibleTargets = mergeTargets.orEmpty().filter { target ->
            mergeSearch.isBlank() || target.name.contains(mergeSearch.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = { mergePickerVisible = false },
            title = { Text("Merge ${person.name} into...") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    Text(
                        "Choose the person who should remain. Nothing changes while you review.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = mergeSearch,
                        onValueChange = { mergeSearch = it },
                        label = { Text("Search people") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        mergeTargetsError != null -> Text(
                            requireNotNull(mergeTargetsError),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        mergeTargets == null -> Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Loading people...")
                        }
                        visibleTargets.isEmpty() -> Text(
                            "No other recognized person matches.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            listItems(visibleTargets, key = NextcloudPerson::id) { target ->
                                Surface(
                                    onClick = {
                                        val targetReference = target.toMediaReference()
                                        val plan = planMergePeople(
                                            source = personReference,
                                            sourceDisplayName = person.name,
                                            target = targetReference,
                                            targetDisplayName = target.name,
                                            support = actionSupport,
                                        )
                                        mergePickerVisible = false
                                        mergePreparing = true
                                        mergePrepareError = null
                                        pendingMergeWorkflow = null
                                        scope.launch {
                                            runCatching {
                                                peopleMergeService.prepare(
                                                    session = session,
                                                    source = personReference,
                                                    target = targetReference,
                                                )
                                            }.onSuccess { workflow ->
                                                pendingMergeWorkflow = workflow
                                                pendingPlan = plan
                                            }.onFailure { failure ->
                                                mergePrepareError = failure.message
                                                    ?: "Could not build a complete face inventory."
                                            }
                                            mergePreparing = false
                                        }
                                    },
                                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                                    color = NextcloudTheme.colors.appTile,
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(NextcloudIcons.People, contentDescription = null)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(target.name, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${target.count} ${if (target.count == 1) "photo" else "photos"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(NextcloudIcons.ChevronRight, contentDescription = null)
                                    }
                                }
                                Spacer(Modifier.size(NextcloudSpacing.Small))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergePickerVisible = false }) { Text("Cancel") } },
        )
    }

    if (mergePreparing || mergePrepareError != null) {
        AlertDialog(
            onDismissRequest = {
                if (!mergePreparing) mergePrepareError = null
            },
            title = { Text("Preparing safe merge") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                    if (mergePreparing) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Loading every face assigned to ${person.name}...")
                        }
                        Text(
                            "No faces are changed during this inventory step.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            requireNotNull(mergePrepareError),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (!mergePreparing) {
                    TextButton(onClick = { mergePrepareError = null }) { Text("Close") }
                }
            },
        )
    }

    pendingPlan?.let { plan ->
        PeopleActionPlanReviewDialog(
            plan = plan,
            session = session,
            recognizeBridge = recognizeBridge,
            mutationService = mutationService,
            mergeService = peopleMergeService,
            initialMergeWorkflow = pendingMergeWorkflow,
            onDismiss = {
                pendingPlan = null
                pendingMergeWorkflow = null
            },
            onSucceeded = {
                pendingPlan = null
                pendingMergeWorkflow = null
                when (plan.action) {
                    PeopleAction.MergePerson,
                    PeopleAction.DeletePerson,
                    -> onBack()
                    PeopleAction.SetCover,
                    PeopleAction.RemoveFace,
                    -> {
                        val expectation = plan.postMutationExpectation()
                        closePhotoSelection()
                        requestPersonRefresh(expectation)
                    }
                    PeopleAction.RenamePerson ->
                        requestPersonRefresh(plan.postMutationExpectation())
                }
            },
        )
    }
}

@Composable
private fun PersonPhotoSelectionBanner(title: String, onCancel: () -> Unit) {
    Surface(color = NextcloudTheme.colors.appTile) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Medium,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NextcloudIcons.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PeopleActionPlanReviewDialog(
    plan: PeopleActionPlan,
    session: NextcloudSession,
    recognizeBridge: RecognizeBridgeDiscovery,
    mutationService: PeopleMutationService,
    mergeService: PeopleMergeService,
    initialMergeWorkflow: PersonMergeWorkflow?,
    onDismiss: () -> Unit,
    onSucceeded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var running by remember(plan) { mutableStateOf(false) }
    var resultMessage by remember(plan) { mutableStateOf<String?>(null) }
    var resultIsError by remember(plan) { mutableStateOf(false) }
    var mergeWorkflow by remember(plan, initialMergeWorkflow) { mutableStateOf(initialMergeWorkflow) }
    var mergePaused by remember(plan) { mutableStateOf(false) }
    val needsBridge = PeopleActionAuthRequirement.ShortLivedRecognizeApiKey in plan.authRequirements
    val bridgeAvailable = recognizeBridge is RecognizeBridgeDiscovery.Available
    val isMerge = plan.action == PeopleAction.MergePerson
    val executable = plan.enabled && (!needsBridge || bridgeAvailable) && (!isMerge || mergeWorkflow != null)

    fun runMerge(refreshBeforeResume: Boolean) {
        val workflow = mergeWorkflow ?: return
        running = true
        resultMessage = null
        resultIsError = false
        scope.launch {
            val reconciliation = if (refreshBeforeResume) {
                runCatching { mergeService.reconcileAfterRefresh(session, workflow) }
                    .getOrElse { failure ->
                        running = false
                        resultIsError = true
                        resultMessage = failure.message ?: "Could not refresh both people."
                        return@launch
                    }
            } else {
                null
            }
            val result = mergeService.runConfirmed(
                session = session,
                bridgeDiscovery = recognizeBridge,
                plan = plan,
                initialWorkflow = reconciliation?.workflow ?: workflow,
                initialReconciliation = reconciliation,
                onProgress = { updated -> mergeWorkflow = updated },
            )
            running = false
            when (result) {
                is PeopleMergeRunResult.Completed -> onSucceeded()
                is PeopleMergeRunResult.Paused -> {
                    mergeWorkflow = result.workflow
                    mergePaused = true
                    resultIsError = true
                    resultMessage = if (result.outcomeUnknown) {
                        "A face move has an unknown result. Refresh both people before deciding whether to resume."
                    } else {
                        "The server rejected a face move. Refresh both people before resuming."
                    }
                }
                is PeopleMergeRunResult.Unavailable -> {
                    resultIsError = true
                    resultMessage = result.message
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(plan.confirmation.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium)) {
                Text(plan.confirmation.message)
                Surface(
                    color = if (resultIsError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                ) {
                    Text(
                        text = resultMessage
                            ?: plan.disabledReason
                            ?: when {
                                needsBridge && !bridgeAvailable ->
                                    "Install and enable the Obiente Native Bridge to make this Recognize change."
                                isMerge && mergeWorkflow != null -> {
                                    val progress = requireNotNull(mergeWorkflow).progress
                                    if (running) {
                                        "Moved ${progress.succeeded} of ${progress.total} faces..."
                                    } else {
                                        "Complete inventory ready: ${progress.total} faces. The merge stops on the first rejected or uncertain move."
                                    }
                                }
                                isMerge -> "A complete face inventory is required before merging."
                                else -> "Ready. Nothing changes until you press ${plan.confirmation.confirmLabel}."
                            },
                        modifier = Modifier.padding(NextcloudSpacing.Medium),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (resultIsError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !running, onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                enabled = executable && !running,
                onClick = {
                    if (isMerge) {
                        runMerge(refreshBeforeResume = mergePaused)
                        return@Button
                    }
                    running = true
                    resultMessage = null
                    resultIsError = false
                    scope.launch {
                        when (
                            val result = mutationService.execute(
                                session = session,
                                bridgeDiscovery = recognizeBridge,
                                plan = plan,
                                confirmed = true,
                            )
                        ) {
                            is PeopleMutationServiceResult.Outcome -> when (val outcome = result.outcome) {
                                is PeopleMutationExecutionOutcome.SingleSucceeded -> {
                                    running = false
                                    onSucceeded()
                                }
                                is PeopleMutationExecutionOutcome.SingleRejected -> {
                                    running = false
                                    resultIsError = true
                                    resultMessage = "The server rejected this change (HTTP ${outcome.status}). Nothing was retried."
                                }
                                is PeopleMutationExecutionOutcome.SingleOutcomeUnknown -> {
                                    running = false
                                    resultIsError = true
                                    resultMessage = "${outcome.reason} Refresh before trying again."
                                }
                                is PeopleMutationExecutionOutcome.MergeAdvanced,
                                is PeopleMutationExecutionOutcome.MergePaused,
                                is PeopleMutationExecutionOutcome.MergeCompleted,
                                -> {
                                    running = false
                                    resultIsError = true
                                    resultMessage = "Merge progress is not connected to this dialog yet."
                                }
                            }
                            is PeopleMutationServiceResult.TokenUnavailable -> {
                                running = false
                                resultIsError = true
                                resultMessage = result.message
                            }
                            is PeopleMutationServiceResult.Planning -> {
                                running = false
                                resultIsError = true
                                resultMessage = result.result.peoplePlanningMessage()
                            }
                        }
                    }
                },
            ) {
                if (running) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (mergePaused) "Refresh and resume" else plan.confirmation.confirmLabel)
                }
            }
        },
    )
}

private fun PeopleExecutionPlanningResult.peoplePlanningMessage(): String = when (this) {
    is PeopleExecutionPlanningResult.Disabled -> reason
    is PeopleExecutionPlanningResult.ConfirmationRequired -> "Confirm this change before continuing."
    PeopleExecutionPlanningResult.FaceInventoryRequired -> "Refresh the face inventory before merging."
    is PeopleExecutionPlanningResult.BridgeTokenRequired -> "A fresh Recognize key is required."
    is PeopleExecutionPlanningResult.ReconciliationRequired -> reason
    is PeopleExecutionPlanningResult.Ready -> "The change is ready but was not sent."
    is PeopleExecutionPlanningResult.Completed -> "The merge is already complete."
    is PeopleExecutionPlanningResult.Invalid -> reason
}

private fun validatePersonRename(person: PersonMediaReference, value: String): String? {
    val name = value.trim()
    return when {
        name.isEmpty() -> "Enter a name."
        '/' in name -> "Names cannot contain a slash."
        name.toLongOrNull() != null -> "Recognize does not allow a number-only name."
        name == person.lookupName -> "Enter a different name."
        else -> null
    }
}

@Composable
internal fun MediaTile(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String? = null,
    file: NextcloudFile,
    badge: String? = null,
    backupStatus: MediaBackupStatus? = null,
    faceRectangle: NativeFaceRectangle? = null,
    sourceWidth: Int? = null,
    sourceHeight: Int? = null,
    layout: MediaTileLayout = MediaTileLayout.SquareCrop,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var image by remember(
        file.fileId,
        file.etag,
        file.hasPreview,
        file.memoriesRenderAllowed,
    ) {
        mutableStateOf<ImageBitmap?>(null)
    }
    val faceOutlineGeometry = remember(faceRectangle, sourceWidth, sourceHeight) {
        nativeFaceOutlineGeometryOrNull(faceRectangle, sourceWidth, sourceHeight)
    }
    LaunchedEffect(
        session,
        userId,
        file.fileId,
        file.etag,
        file.hasPreview,
        file.memoriesRenderAllowed,
    ) {
        file.fileId ?: return@LaunchedEffect
        image = services.loadMediaThumbnailDecoded(
            session = session,
            file = file,
            userId = userId,
        ) { payload ->
            decodePlatformImage(payload.bytes, payload.kind.orientationPolicy())
        }
    }
    val tileAspectRatio = when (layout) {
        MediaTileLayout.SquareCrop -> 1f
        MediaTileLayout.FullWidthFit -> image
            ?.takeIf { bitmap -> bitmap.width > 0 && bitmap.height > 0 }
            ?.let { bitmap -> bitmap.width.toFloat() / bitmap.height.toFloat() }
            ?.coerceIn(0.5f, 2.4f)
            ?: (4f / 3f)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().aspectRatio(tileAspectRatio).then(
            if (onLongClick == null) {
                Modifier.clickable(enabled = enabled, onClick = onClick)
            } else {
                Modifier.combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            },
        ),
        color = NextcloudTheme.colors.appTile,
    ) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    // A face outline must map to the complete source image. Switching to Fit while
                    // it is visible avoids drawing a plausible-looking box over a cropped preview.
                    contentScale = if (
                        layout == MediaTileLayout.FullWidthFit || faceOutlineGeometry != null
                    ) {
                        ContentScale.Fit
                    } else {
                        ContentScale.Crop
                    },
                )
                FaceRectangleOverlay(
                    geometry = faceOutlineGeometry,
                    color = MaterialTheme.colorScheme.primary,
                )
            } ?: Icon(
                NextcloudIcons.Image,
                contentDescription = file.name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).size(28.dp),
            )
            badge?.let { label ->
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            backupStatus?.let { status ->
                MediaBackupStatusIndicator(
                    status = status,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                )
            }
        }
    }
}

internal enum class MediaTileLayout {
    SquareCrop,
    FullWidthFit,
}

@Composable
private fun FileInfoScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    onBack: () -> Unit,
    showVersions: Boolean,
    onVersionRestored: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(file.name, if (file.isDirectory) "Folder details" else "File details", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
        ) {
            item {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = RoundedCornerShape(NextcloudRadii.Medium)) {
                    Icon(
                        if (file.isDirectory) NextcloudIcons.Folder else fileIcon(file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(18.dp).size(38.dp),
                    )
                }
            }
            item {
                Text(file.name, style = MaterialTheme.typography.headlineMedium)
                Text(
                    file.path,
                    modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Column(
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        FileMetadataLine("Type", if (file.isDirectory) "Folder" else file.mimeType ?: "Unknown")
                        if (!file.isDirectory) FileMetadataLine("Size", formatBytes(file.size))
                        file.lastModified?.let { FileMetadataLine("Modified", it) }
                        if (!file.isDirectory) {
                            FileMetadataLine("Preview", if (file.hasPreview) "Available" else "Unavailable")
                        }
                    }
                }
            }
            if (!file.isDirectory && file.fileId != null) {
                item {
                    FileVersionHistorySection(
                        services = services,
                        session = session,
                        userId = userId,
                        file = file,
                        initiallyExpanded = showVersions,
                        onVersionRestored = onVersionRestored,
                    )
                }
            }
            onEdit?.let { edit ->
                item {
                    Button(onClick = edit) {
                        Icon(NextcloudIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Edit text")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileMetadataLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            modifier = Modifier.padding(start = NextcloudSpacing.Large),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TextEditorScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    file: NextcloudFile,
    onBack: () -> Unit,
) {
    val descriptor = remember(file) { describeDocument(file) }
    val isMarkdown = descriptor.kind == DocumentKind.Markdown
    var originalText by remember(file.path) { mutableStateOf<String?>(null) }
    var draft by remember(file.path) { mutableStateOf("") }
    var etag by remember(file.path) { mutableStateOf(file.etag) }
    var loadingError by remember(file.path) { mutableStateOf<String?>(null) }
    var saveError by remember(file.path) { mutableStateOf<String?>(null) }
    var saving by remember(file.path) { mutableStateOf(false) }
    var confirmSave by remember(file.path) { mutableStateOf(false) }
    var confirmDiscard by remember(file.path) { mutableStateOf(false) }
    var savedMessage by remember(file.path) { mutableStateOf<String?>(null) }
    var markdownViewMode by rememberSaveable(file.path) {
        mutableStateOf(
            if (
                isMarkdown &&
                (file.size == null || file.size <= MAX_RENDERED_MARKDOWN_PREVIEW_BYTES)
            ) {
                MarkdownFileViewMode.Preview
            } else {
                MarkdownFileViewMode.Edit
            },
        )
    }
    val scope = rememberCoroutineScope()
    val dirty = originalText != null && draft != originalText
    val textPresentation = remember(descriptor, draft) {
        planNativeTextPresentation(descriptor, draft.utf8Size())
    }
    val markdownPreviewAvailable = textPresentation == NativeTextPresentation.RenderedMarkdown

    LaunchedEffect(file.path, userId) {
        if (userId.isBlank()) return@LaunchedEffect
        loadingError = null
        runCatching {
            services.downloadFile(
                session = session,
                userId = userId,
                path = file.path,
                maxBytes = MAX_EDITABLE_TEXT_BYTES,
            )
        }.onSuccess { downloaded ->
            runCatching { downloaded.bytes.decodeToString(throwOnInvalidSequence = true) }
                .onSuccess { text ->
                    originalText = text
                    draft = text
                    etag = downloaded.etag ?: file.etag
                }
                .onFailure { loadingError = "This file is not valid UTF-8 text." }
        }.onFailure { loadingError = it.message ?: "Could not download this file." }
    }

    fun requestBack() {
        if (dirty) confirmDiscard = true else onBack()
    }
    PlatformBackHandler(enabled = true, onBack = ::requestBack)

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(file.name, if (dirty) "Unsaved changes" else "Text editor", ::requestBack)
        when {
            loadingError != null -> ErrorMessage(requireNotNull(loadingError))
            originalText == null -> LoadingMessage("Opening ${file.name}...")
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NextcloudSpacing.Large, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        savedMessage ?: saveError ?: if (etag.isNullOrBlank()) {
                            "Saving is disabled until the server version is verified."
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            saveError != null -> MaterialTheme.colorScheme.error
                            etag.isNullOrBlank() -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> NextcloudTheme.colors.success
                        },
                    )
                    Button(
                        enabled = dirty && !saving && !etag.isNullOrBlank(),
                        onClick = { confirmSave = true },
                    ) {
                        Icon(NextcloudIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (saving) "Saving..." else "Save")
                    }
                }
                if (isMarkdown) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Medium,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = markdownViewMode == MarkdownFileViewMode.Preview,
                            onClick = { markdownViewMode = MarkdownFileViewMode.Preview },
                            enabled = markdownPreviewAvailable,
                            label = { Text("Preview") },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.File,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        FilterChip(
                            selected = markdownViewMode == MarkdownFileViewMode.Edit,
                            onClick = { markdownViewMode = MarkdownFileViewMode.Edit },
                            label = { Text("Edit source") },
                            leadingIcon = {
                                Icon(
                                    NextcloudIcons.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        if (!markdownPreviewAvailable) {
                            Text(
                                "Rendered preview is limited to " +
                                    "${MAX_RENDERED_MARKDOWN_PREVIEW_BYTES / 1024} KiB.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (isMarkdown && markdownViewMode == MarkdownFileViewMode.Preview) {
                    Surface(
                        modifier = Modifier.fillMaxSize().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Large,
                        ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        when {
                            !markdownPreviewAvailable -> Box(
                                modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.Large),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Switch to Edit source to continue.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            draft.isBlank() -> Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "This document is empty.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> Markdown(
                                content = draft,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(NextcloudSpacing.Large),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            saveError = null
                            savedMessage = null
                        },
                        modifier = Modifier.fillMaxSize().padding(
                            start = NextcloudSpacing.Large,
                            end = NextcloudSpacing.Large,
                            bottom = NextcloudSpacing.Large,
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        enabled = !saving,
                    )
                }
            }
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Save changes to Nextcloud?") },
            text = { Text("This updates ${file.name} on the server. A conflict will stop the save instead of overwriting newer changes.") },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text("Cancel") } },
            confirmButton = {
                Button(onClick = {
                    confirmSave = false
                    saving = true
                    saveError = null
                    scope.launch {
                        runCatching {
                            services.saveTextFile(
                                session,
                                userId,
                                file.path,
                                draft,
                                requireNotNull(etag?.takeIf(String::isNotBlank)),
                            )
                        }.onSuccess { saved ->
                            originalText = draft
                            etag = saved.etag ?: etag
                            savedMessage = "Saved to Nextcloud"
                        }.onFailure { saveError = it.message ?: "Could not save this file." }
                        saving = false
                    }
                }) { Text("Save") }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your local edits to ${file.name} have not been saved.") },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
            confirmButton = {
                Button(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text("Discard") }
            },
        )
    }
}

private enum class MarkdownFileViewMode {
    Preview,
    Edit,
}

@Composable
private fun TalkScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
    onOpenRoom: (TalkRoom) -> Unit,
) {
    var rooms by remember { mutableStateOf<List<TalkRoom>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(loadAttempt) {
        rooms = null
        error = null
        runCatching { services.listTalkRooms(session) }
            .onSuccess { rooms = it }
            .onFailure { error = it.message ?: "Could not load Talk conversations." }
    }
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader("Talk", "Conversations", onBack)
        when {
            error != null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
            rooms == null -> LoadingMessage("Loading conversations...")
            rooms?.isEmpty() == true -> EmptyMessage("No Talk conversations yet.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = NextcloudSpacing.XXLarge)) {
                listItems(requireNotNull(rooms), key = TalkRoom::token) { room ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenRoom(room) }
                            .padding(horizontal = NextcloudSpacing.XLarge, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.app("talk"),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(10.dp).size(24.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(room.displayName, fontWeight = FontWeight.SemiBold)
                            room.lastMessage?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (room.unreadMessages > 0) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                                Text(
                                    room.unreadMessages.toString(),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                        Icon(NextcloudIcons.ChevronRight, contentDescription = "Open ${room.displayName}")
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 84.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    room: TalkRoom,
    onBack: () -> Unit,
    onOpenAttachment: (NextcloudFile) -> Unit,
) {
    var messages by remember(room.token) { mutableStateOf<List<TalkMessage>?>(null) }
    var olderCursor by remember(room.token) { mutableStateOf<Long?>(null) }
    var hasMoreHistory by remember(room.token) { mutableStateOf(false) }
    var loadingEarlier by remember(room.token) { mutableStateOf(false) }
    var historyError by remember(room.token) { mutableStateOf<String?>(null) }
    var draft by remember(room.token) { mutableStateOf("") }
    var error by remember(room.token) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var loadAttempt by remember(room.token) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()
    val orderedMessages = remember(messages) { messages?.sortedBy(TalkMessage::id) }

    suspend fun refresh() {
        val page = services.listTalkMessagePage(session, room.token)
        messages = page.messages
        olderCursor = page.olderCursor
        hasMoreHistory = page.hasMoreHistory
    }
    LaunchedEffect(room.token, loadAttempt) {
        messages = null
        error = null
        runCatching { refresh() }.onFailure { error = it.message ?: "Could not load messages." }
    }
    LaunchedEffect(orderedMessages?.lastOrNull()?.id) {
        val lastIndex = orderedMessages?.lastIndex ?: return@LaunchedEffect
        messageListState.scrollToItem(lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(room.displayName, "Talk", onBack)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> ErrorMessage(requireNotNull(error)) { loadAttempt += 1 }
                messages == null -> LoadingMessage("Loading messages...")
                messages?.isEmpty() == true -> EmptyMessage("No messages in this conversation yet.")
                else -> LazyColumn(
                    state = messageListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(NextcloudSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    if ((hasMoreHistory && olderCursor != null) || historyError != null) {
                        item(key = "talk-load-earlier") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                historyError?.let { message ->
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (hasMoreHistory && olderCursor != null) {
                                    TextButton(
                                        enabled = !loadingEarlier,
                                        onClick = {
                                            val cursor = olderCursor ?: return@TextButton
                                            loadingEarlier = true
                                            historyError = null
                                            scope.launch {
                                                runCatching {
                                                    services.listTalkMessagePage(
                                                        session = session,
                                                        token = room.token,
                                                        olderCursor = cursor,
                                                    )
                                                }.onSuccess { page ->
                                                    messages = mergeTalkMessageHistory(
                                                        messages.orEmpty(),
                                                        page.messages,
                                                    )
                                                    olderCursor = page.olderCursor
                                                    hasMoreHistory = page.hasMoreHistory
                                                }.onFailure { failure ->
                                                    historyError =
                                                        failure.message ?: "Could not load earlier messages."
                                                }
                                                loadingEarlier = false
                                            }
                                        },
                                    ) {
                                        if (loadingEarlier) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            Text("Load earlier messages")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    listItems(requireNotNull(orderedMessages), key = TalkMessage::id) { message ->
                        TalkMessageCard(
                            services = services,
                            session = session,
                            message = message,
                            mine = message.actorId == userId,
                            onOpenAttachment = { attachment ->
                                onOpenAttachment(attachment.asNextcloudFile())
                            },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = !sending,
                shape = RoundedCornerShape(NextcloudRadii.Card),
            )
            IconButton(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val message = draft.trim()
                    sending = true
                    scope.launch {
                        runCatching {
                            services.sendTalkMessage(session, room.token, message)
                            draft = ""
                            refresh()
                        }.onFailure { error = it.message ?: "Could not send message." }
                        sending = false
                    }
                },
            ) { Icon(NextcloudIcons.Send, contentDescription = "Send message") }
        }
    }
}

@Composable
private fun ProjectNewsScreen(
    services: NextcloudPlatformServices,
    onBack: () -> Unit,
    onOpenArticle: (ProjectNewsArticle) -> Unit,
) {
    var result by remember { mutableStateOf<ProjectNewsResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        error = null
        runCatching { services.loadProjectNews(forceRefresh = refresh > 0) }
            .onSuccess { result = it }
            .onFailure { error = it.message ?: "Could not load project news." }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Project news",
            subtitle = result?.let { if (it.cached) "Cached on this device" else "Latest from Obiente" },
            onBack = onBack,
        )
        when {
            error != null && result == null -> ErrorMessage(requireNotNull(error)) { refresh += 1 }
            result == null -> LoadingMessage("Loading project news...")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                error?.let { message ->
                    item {
                        Text(
                            "$message Showing the last verified cache.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                val articles = requireNotNull(result).feed.entries
                items(articles.size, key = { index -> articles[index].id }) { index ->
                    val article = articles[index]
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenArticle(article) },
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Column(
                            modifier = Modifier.padding(NextcloudSpacing.Large),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            Text(
                                if (article.lastUpdated != null) {
                                    "Published ${article.publishedDate} · Updated ${article.lastUpdated}"
                                } else {
                                    article.publishedDate
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(article.title, style = MaterialTheme.typography.titleLarge)
                            Text(
                                article.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                                article.tags.take(3).forEach { tag ->
                                    Surface(
                                        color = NextcloudTheme.colors.appIconContainer,
                                        shape = RoundedCornerShape(NextcloudRadii.Pill),
                                    ) {
                                        Text(
                                            tag,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    TextButton(onClick = { refresh += 1 }) {
                        Icon(NextcloudIcons.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Refresh news")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectNewsArticleScreen(
    services: NextcloudPlatformServices,
    article: ProjectNewsArticle,
    onBack: () -> Unit,
) {
    val presentation = remember(article) { projectNewsArticlePresentation(article) }
    var heroImage by remember(article.image.sha256) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(article.image.sha256) {
        heroImage = runCatching {
            decodePlatformImage(services.loadProjectNewsImage(presentation.heroImage))
        }.getOrNull()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = article.title,
            subtitle = if (article.lastUpdated != null) {
                "Published ${article.publishedDate} · Updated ${article.lastUpdated}"
            } else {
                article.publishedDate
            },
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        ) {
            heroImage?.let { image ->
                item {
                    Image(
                        bitmap = image,
                        contentDescription = presentation.heroImage.alt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                presentation.heroImage.width.toFloat() /
                                    presentation.heroImage.height.toFloat(),
                            )
                            .clip(RoundedCornerShape(NextcloudRadii.Card)),
                    )
                }
            }
            item {
                Text(
                    article.description,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Markdown(
                    content = article.bodyMarkdown,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AppUpdateSettingsCard(services: NextcloudPlatformServices) {
    val scope = rememberCoroutineScope()
    val support = remember(services) { services.appUpdateSupport() }
    var updateChannel by remember(services) {
        mutableStateOf(services.loadAppUpdateChannel())
    }
    val channelPresentation = remember(support, updateChannel) {
        appUpdateChannelPresentation(support, updateChannel)
    }
    val updateState by remember(services) {
        services.observeAppUpdateInstallState()
    }.collectAsState(AppUpdateInstallState.Idle)
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<AppUpdateCheckResult?>(null) }
    var installMessage by remember { mutableStateOf<String?>(null) }
    fun beginInstall(release: AndroidDirectRelease) {
        installing = true
        installMessage = null
        scope.launch {
            installMessage = when (val install = services.beginAppUpdate(release)) {
                AppUpdateInstallResult.ConfirmationOpened ->
                    "Android opened the update confirmation."
                AppUpdateInstallResult.Cancelled ->
                    "Download paused. You can resume it without starting over."
                is AppUpdateInstallResult.PermissionRequired -> install.message
                is AppUpdateInstallResult.Rejected -> install.message
            }
            installing = false
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Small),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.padding(NextcloudSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                    Icon(
                        NextcloudIcons.Cloud,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(26.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("App updates", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (channelPresentation.selectorVisible) {
                            "Version ${support.currentVersionName} - ${updateChannel.name} channel"
                        } else {
                            "Version ${support.currentVersionName} - ${support.channel.name}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (support.canCheckDirectUpdates) {
                    TextButton(
                        enabled = !checking && updateChannel.available,
                        onClick = {
                            checking = true
                            checkResult = null
                            installMessage = null
                            scope.launch {
                                checkResult = services.checkForAppUpdate(updateChannel)
                                checking = false
                            }
                        },
                    ) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Check")
                        }
                    }
                }
            }
            Text(
                support.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (channelPresentation.selectorVisible) {
                Text(
                    "Update channel",
                    style = MaterialTheme.typography.titleSmall,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    channelPresentation.options.forEach { option ->
                        val enabled = option.enabled && !checking && !installing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = option.selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (services.saveAppUpdateChannel(option.channel)) {
                                            checkResult = retainedAppUpdateCheckResult(
                                                previousChannel = updateChannel,
                                                selectedChannel = option.channel,
                                                previousResult = checkResult,
                                            )
                                            updateChannel = option.channel
                                            installMessage = null
                                        }
                                    },
                                )
                                .padding(vertical = NextcloudSpacing.Small),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = option.selected,
                                enabled = enabled,
                                onClick = null,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(option.label, style = MaterialTheme.typography.titleSmall)
                                    option.availabilityLabel?.let { label ->
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            when (val checked = checkResult) {
                is AppUpdateCheckResult.Available -> {
                    val release = checked.release
                    val releaseState = updateState.takeIf { state ->
                        when (state) {
                            is AppUpdateInstallState.Downloading -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Verifying -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.PermissionRequired -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Cancelled -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.Failed -> state.versionCode == release.versionCode
                            is AppUpdateInstallState.ConfirmationOpened -> state.versionCode == release.versionCode
                            AppUpdateInstallState.Idle -> false
                        }
                    } ?: AppUpdateInstallState.Idle
                    Text(
                        "Version ${release.versionName} is available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (releaseState) {
                        is AppUpdateInstallState.Downloading -> {
                            val progress =
                                (releaseState.downloadedBytes.toDouble() / releaseState.totalBytes.toDouble())
                                    .coerceIn(0.0, 1.0)
                                    .toFloat()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                            )
                            Text(
                                buildString {
                                    append(formatBytes(releaseState.downloadedBytes))
                                    append(" of ")
                                    append(formatBytes(releaseState.totalBytes))
                                    if (releaseState.resumedFromBytes > 0) append(" · resumed")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { services.cancelAppUpdate() }) {
                                Text("Pause download")
                            }
                        }
                        is AppUpdateInstallState.Verifying -> {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Download complete. Verifying package and signing certificate...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is AppUpdateInstallState.Cancelled -> {
                            Text(
                                if (releaseState.canResume) {
                                    "${formatBytes(releaseState.downloadedBytes)} saved for resume."
                                } else {
                                    "The download was paused before any data was saved."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { beginInstall(release) },
                                enabled = !installing,
                            ) {
                                Text(if (releaseState.canResume) "Resume download" else "Retry download")
                            }
                        }
                        is AppUpdateInstallState.Failed -> {
                            Text(
                                releaseState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(
                                onClick = { beginInstall(release) },
                                enabled = !installing,
                            ) {
                                Text(if (releaseState.canResume) "Resume download" else "Retry download")
                            }
                        }
                        is AppUpdateInstallState.PermissionRequired -> {
                            Text(
                                releaseState.message,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                onClick = { beginInstall(release) },
                                enabled = !installing,
                            ) {
                                Text("Continue update")
                            }
                        }
                        is AppUpdateInstallState.ConfirmationOpened -> Text(
                            "Android opened the update confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NextcloudTheme.colors.success,
                        )
                        AppUpdateInstallState.Idle -> Button(
                            onClick = { beginInstall(release) },
                            enabled = !installing,
                        ) {
                            if (installing) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Download, verify, and review")
                            }
                        }
                    }
                }
                is AppUpdateCheckResult.Current -> Text(
                    "This installation is up to date.",
                    color = NextcloudTheme.colors.success,
                    style = MaterialTheme.typography.bodySmall,
                )
                is AppUpdateCheckResult.Failed -> Text(
                    checked.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                is AppUpdateCheckResult.Unavailable, null -> Unit
            }
            installMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    serverInfo: NextcloudServerInfo?,
    themePreference: ThemePreference,
    onThemePreferenceChanged: (ThemePreference) -> Unit,
    onAdminApps: () -> Unit,
    onOfflineCenter: () -> Unit,
    onTransfers: () -> Unit,
    onProjectNews: () -> Unit,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    var capabilityRefresh by remember { mutableStateOf(0) }
    var startOnLogin by remember(services) { mutableStateOf(services.loadStartOnLoginPreference()) }
    var startOnLoginMessage by remember(services) { mutableStateOf<String?>(null) }
    val platformCapabilities = remember(services, capabilityRefresh) { services.platformCapabilities() }
    Column(modifier = Modifier.fillMaxSize()) {
        ProductHeader(title = "Settings", showSettings = false)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(NextcloudSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
        ) {
            item {
                SectionTitle("Appearance")
                Row(
                    modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    ThemePreference.entries.forEach { preference ->
                        FilterChip(
                            selected = themePreference == preference,
                            onClick = { onThemePreferenceChanged(preference) },
                            label = { Text(preference.name) },
                            leadingIcon = {
                                Icon(
                                    when (preference) {
                                        ThemePreference.System -> NextcloudIcons.SystemMode
                                        ThemePreference.Light -> NextcloudIcons.LightMode
                                        ThemePreference.Dark -> NextcloudIcons.DarkMode
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
            }
            if (services.supportsStartOnLogin) {
                item {
                    SectionTitle("Desktop")
                    DesktopStartOnLoginSettingsCard(
                        enabled = startOnLogin,
                        message = startOnLoginMessage,
                        onEnabledChanged = { enabled ->
                            startOnLogin = enabled
                            startOnLoginMessage = services.saveStartOnLoginPreference(enabled)
                        },
                    )
                }
            }
            item {
                SectionTitle("Account")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Profile,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(serverInfo?.displayName ?: session.loginName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                session.serverUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            serverInfo?.version?.let {
                                Text(
                                    "Nextcloud $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("Files")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onOfflineCenter,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Cloud,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Sync & offline", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (services.supportsFileOfflineStorage) {
                                    if (services.supportsRecursiveFileOfflineStorage) {
                                        "Folder sync, offline files, conflicts, and storage"
                                    } else {
                                        "Pinned files, downloads, conflicts, and device storage"
                                    }
                                } else {
                                    "Review this platform's offline file support and limitations"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open Sync & offline",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (services.supportsMediaTransferCenter) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onTransfers,
                        color = NextcloudTheme.colors.appTile,
                        shape = RoundedCornerShape(NextcloudRadii.Card),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                                Icon(
                                    NextcloudIcons.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(12.dp).size(26.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Media transfers", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Pending, active, failed, and completed uploads",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                NextcloudIcons.ChevronRight,
                                contentDescription = "Open media transfers",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            if (platformCapabilities.isNotEmpty()) {
                item {
                    SectionTitle("Device features")
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        platformCapabilities.forEach { status ->
                            Surface(
                                color = NextcloudTheme.colors.appTile,
                                shape = RoundedCornerShape(NextcloudRadii.Card),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        NextcloudIcons.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(status.label, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            status.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    when (status.state) {
                                        PlatformCapabilityState.NeedsPermission,
                                        PlatformCapabilityState.Blocked,
                                        -> TextButton(
                                            onClick = {
                                                services.requestPlatformCapability(status.capability)
                                                capabilityRefresh += 1
                                            },
                                        ) {
                                            Text(if (status.state == PlatformCapabilityState.Blocked) "Settings" else "Enable")
                                        }
                                        PlatformCapabilityState.Granted -> Text("Enabled", color = NextcloudTheme.colors.success)
                                        PlatformCapabilityState.AvailableWithoutPermission -> Text("Available")
                                        PlatformCapabilityState.Unsupported -> Text("Unavailable")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("Nextcloud Native")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onProjectNews,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Activity,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Project news", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Read development notes in a native, cached view",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open project news",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                AppUpdateSettingsCard(services)
            }
            item {
                SectionTitle("Administration")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    onClick = onAdminApps,
                    color = NextcloudTheme.colors.appTile,
                    shape = RoundedCornerShape(NextcloudRadii.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                            Icon(
                                NextcloudIcons.Apps,
                                contentDescription = null,
                                modifier = Modifier.padding(12.dp).size(26.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Server apps", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Install, update, enable, or disable apps as an administrator",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            NextcloudIcons.ChevronRight,
                            contentDescription = "Open server app management",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    enabled = !loggingOut,
                    onClick = {
                        loggingOut = true
                        scope.launch {
                            runCatching { services.revokeSession(session) }
                            onLoggedOut()
                        }
                    },
                ) {
                    Icon(NextcloudIcons.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (loggingOut) "Signing out..." else "Sign out and revoke access")
                }
            }
        }
    }
}

@Composable
internal fun DesktopStartOnLoginSettingsCard(
    enabled: Boolean,
    message: String?,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = onEnabledChanged,
            ).padding(NextcloudSpacing.Large),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                Icon(
                    NextcloudIcons.Schedule,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Start on login", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep folder sync and virtual files available after signing in to this computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = NextcloudSpacing.Small),
                    )
                }
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
private fun ProductHeader(
    title: String,
    onSettings: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    showSettings: Boolean = true,
    accountAvatar: ImageBitmap? = null,
    accountDisplayName: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(76.dp).padding(horizontal = NextcloudSpacing.XLarge),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (showSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onSearch?.let {
                    IconButton(onClick = it) { Icon(NextcloudIcons.Search, contentDescription = "Search Nextcloud") }
                }
                Surface(color = NextcloudTheme.colors.appIconContainer, shape = CircleShape) {
                    if (accountAvatar != null) {
                        Image(
                            bitmap = accountAvatar,
                            contentDescription = accountAvatarContentDescription(accountDisplayName),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(38.dp).clip(CircleShape),
                        )
                    } else {
                        Icon(
                            NextcloudIcons.Profile,
                            contentDescription = "Account",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp).size(22.dp),
                        )
                    }
                }
                onSettings?.let {
                    IconButton(onClick = it) { Icon(NextcloudIcons.Settings, contentDescription = "Settings") }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun UnifiedSearchSelection.nativeFileParentPathOrNull(): String? {
    if (provider.appId != "files" && !provider.id.startsWith("files")) return null
    val candidate = entry.attributes["path"]
        ?: entry.attributes["filePath"]
        ?: entry.subline?.takeIf { it.startsWith('/') }
        ?: return null
    val segments = candidate.substringBefore('?').trim('/').split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." || it.any(Char::isISOControl) }) return null
    val path = segments.joinToString("/")
    return if (segments.lastOrNull() == entry.title) path.substringBeforeLast('/', "") else path
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    compact: Boolean = false,
    trailingContent: @Composable () -> Unit = {},
) {
    val workspace = LocalNextcloudWorkspaceCapabilities.current
    val desktop = workspace.isDesktop
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(
                min = when {
                    desktop -> 62.dp
                    compact -> 54.dp
                    else -> 76.dp
                },
            )
            .padding(horizontal = if (desktop) NextcloudSpacing.Large else NextcloudSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) NextcloudSpacing.Medium else NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (desktop) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(NextcloudRadii.Small),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        NextcloudIcons.Back,
                        contentDescription = "Back",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        } else {
            IconButton(onClick = onBack) { Icon(NextcloudIcons.Back, contentDescription = "Back") }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = when {
                    desktop -> MaterialTheme.typography.titleLarge
                    compact -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.headlineSmall
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LoadingMessage(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.padding(top = NextcloudSpacing.Large))
    }
}

@Composable
private fun EmptyMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, modifier = Modifier.padding(NextcloudSpacing.XLarge), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: (() -> Unit)? = null) {
    Column(modifier = Modifier.padding(NextcloudSpacing.XLarge), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(NextcloudIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(message, color = MaterialTheme.colorScheme.error)
        onRetry?.let { retry -> OutlinedButton(onClick = retry) { Text("Try again") } }
    }
}

private fun fileIcon(file: NextcloudFile): ImageVector = when {
    file.mimeType?.startsWith("image/") == true -> NextcloudIcons.Image
    file.mimeType?.startsWith("video/") == true -> NextcloudIcons.Video
    else -> NextcloudIcons.File
}

private fun nativeSubtitle(appId: String): String = when (appId) {
    "files" -> "Browse your server files"
    "photos", "memories" -> "Photos, videos and RAW previews"
    "spreed", "talk" -> "Continue your conversations"
    "activity" -> "See recent changes across your cloud"
    "notes" -> "Write and organize Markdown notes"
    "dashboard" -> "See your cloud at a glance"
    "user_status" -> "Manage your presence and status message"
    else -> "Open native experience"
}

private fun nativeFamily(appId: String): String = when (appId.lowercase()) {
    "dashboard", "github" -> "dashboard and timeline"
    "activity" -> "activity timeline"
    "mail" -> "mailbox and composer"
    "contacts" -> "contact list"
    "calendar" -> "calendar and agenda"
    "cospend", "budget", "money" -> "collection, totals and form"
    "notes", "office", "richdocuments", "collectives" -> "document editor"
    "music", "audioplayer" -> "media library"
    "deck" -> "board and cards"
    "tasks", "chores" -> "task list"
    "tables" -> "typed data table"
    "cookbook" -> "recipe collection"
    else -> "adaptive collection"
}

private fun formatBytes(bytes: Long?): String = when {
    bytes == null -> "File"
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${bytes / 1_048_576} MB"
    else -> "${bytes / 1_073_741_824} GB"
}

private const val MAX_DYNAMIC_BATCH_RELATION_ERROR_LENGTH = 1_024
