package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudBoardDragHandle
import dev.obiente.nextcloudnative.app.design.NextcloudVerticalDragAutoScroll
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock

internal sealed interface DashboardSurfaceState {
    data object Loading : DashboardSurfaceState
    data class Available(
        val snapshot: NativeDashboardSnapshot,
        val status: NativeUserStatus?,
        val widgetsAuthoritative: Boolean = true,
    ) : DashboardSurfaceState
    data class Failed(val message: String) : DashboardSurfaceState
}

private data class DashboardLoadResult(
    val snapshot: NativeDashboardSnapshot,
    val status: NativeUserStatus?,
    val widgetsAuthoritative: Boolean,
)

private data class DashboardItemsHttpResult(
    val response: NextcloudApiResponse,
    val apiVersion: DashboardItemApiVersion,
    val combinedResponseBytes: Long,
)

private enum class DashboardV2RouteAvailability {
    Unknown,
    Available,
    Unavailable,
}

private enum class DashboardV2ExecutionDecision {
    ExecuteV2,
    ExecuteV1Fallback,
    ReturnProbe,
}

internal class DashboardV2RouteUnavailableException : IllegalStateException(
    "The Dashboard widget-items V2 route is unavailable.",
)

internal class DashboardFallbackReadFailure(
    val priorResponseBytes: Long,
    cause: Throwable,
) : RuntimeException(cause)

private suspend fun executeDashboardItemsPlan(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    plan: DashboardItemsRequestPlan,
    widgets: List<NativeDashboardWidget>,
    reservedBytes: Long,
    v2RouteMutex: Mutex,
    readV2RouteAvailability: () -> DashboardV2RouteAvailability,
    writeV2RouteAvailability: (DashboardV2RouteAvailability) -> Unit,
    onEffectiveApiVersion: (DashboardItemApiVersion) -> Unit,
): DashboardItemsHttpResult {
    val fallback = plan.v1FallbackRequest(widgets)

    suspend fun execute(
        request: NextcloudApiRequest,
        apiVersion: DashboardItemApiVersion,
        maximumBytes: Long,
        priorResponseBytes: Long = 0L,
    ): DashboardItemsHttpResult {
        onEffectiveApiVersion(apiVersion)
        require(maximumBytes > 0L) { "The Dashboard fallback response budget is exhausted." }
        val response = services.executeNextcloudApi(
            session,
            request.copy(maximumResponseBytes = maximumBytes),
        )
        return DashboardItemsHttpResult(
            response = response,
            apiVersion = apiVersion,
            combinedResponseBytes = priorResponseBytes + response.body.size.toLong(),
        )
    }

    if (plan.apiVersion != DashboardItemApiVersion.V2) {
        return execute(plan.request, plan.apiVersion, reservedBytes)
    }
    return when (v2RouteMutex.withLock { readV2RouteAvailability() }) {
        DashboardV2RouteAvailability.Available ->
            execute(plan.request, DashboardItemApiVersion.V2, reservedBytes)
        DashboardV2RouteAvailability.Unavailable ->
            fallback?.let { execute(it, DashboardItemApiVersion.V1, reservedBytes) }
                ?: throw DashboardV2RouteUnavailableException()
        DashboardV2RouteAvailability.Unknown -> {
            var probe: DashboardItemsHttpResult? = null
            val decision = v2RouteMutex.withLock {
                when (readV2RouteAvailability()) {
                    DashboardV2RouteAvailability.Available -> DashboardV2ExecutionDecision.ExecuteV2
                    DashboardV2RouteAvailability.Unavailable -> DashboardV2ExecutionDecision.ExecuteV1Fallback
                    DashboardV2RouteAvailability.Unknown -> {
                        val v2 = execute(plan.request, DashboardItemApiVersion.V2, reservedBytes)
                        probe = v2
                        if (withContext(Dispatchers.Default) { isDashboardApiUnavailable(v2.response) }) {
                            writeV2RouteAvailability(DashboardV2RouteAvailability.Unavailable)
                            DashboardV2ExecutionDecision.ExecuteV1Fallback
                        } else {
                            writeV2RouteAvailability(DashboardV2RouteAvailability.Available)
                            DashboardV2ExecutionDecision.ReturnProbe
                        }
                    }
                }
            }
            when (decision) {
                DashboardV2ExecutionDecision.ExecuteV2 ->
                    execute(plan.request, DashboardItemApiVersion.V2, reservedBytes)
                DashboardV2ExecutionDecision.ExecuteV1Fallback -> fallback?.let {
                    val priorResponseBytes = probe?.combinedResponseBytes ?: 0L
                    try {
                        execute(
                            request = it,
                            apiVersion = DashboardItemApiVersion.V1,
                            maximumBytes = dashboardFallbackResponseBudget(reservedBytes, priorResponseBytes),
                            priorResponseBytes = priorResponseBytes,
                        )
                    } catch (failure: Throwable) {
                        if (failure is CancellationException || priorResponseBytes == 0L) throw failure
                        throw DashboardFallbackReadFailure(priorResponseBytes, failure)
                    }
                } ?: probe ?: throw DashboardV2RouteUnavailableException()
                DashboardV2ExecutionDecision.ReturnProbe -> requireNotNull(probe)
            }
        }
    }
}

@Composable
internal fun NativeDashboardScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    recoveryAttempt: Int = 0,
    installedApps: List<NextcloudAppEntry>,
    pinnedAppIds: List<String> = defaultAppWorkspacePinnedIds(),
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenStatus: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    var refreshAttempt by remember(session) { mutableStateOf(0) }
    val state = rememberNativeDashboardState(
        services = services,
        session = session,
        refreshAttempt = refreshAttempt,
        recoveryAttempt = recoveryAttempt,
    )
    val formFactor = rememberHomeFormFactor()
    val workspaceStorage = rememberHomeWorkspaceLayoutStorage()
    val workspaceRepository = remember(workspaceStorage) {
        HomeWorkspaceLayoutRepository(workspaceStorage)
    }
    val workspacePersistenceScopes = remember(session) { accountPersistenceScopeDigests(session) }
    val workspaceScope = remember(workspacePersistenceScopes.current, formFactor) {
        HomeWorkspaceScope(workspacePersistenceScopes.current, formFactor)
    }
    val workspaceLayoutState = rememberMigratedHomeWorkspaceLayoutState(
        workspaceRepository, workspaceScope, workspacePersistenceScopes.legacy,
    )
    var workspaceLayout by workspaceLayoutState.layout

    NativeDashboardPresentation(
        state = state,
        installedApps = installedApps,
        pinnedAppIds = pinnedAppIds,
        workspaceLayout = workspaceLayout,
        workspaceLayoutAuthoritative = workspaceLayoutState.storageAuthoritative.value,
        onWorkspaceLayoutChanged = { updated ->
            workspaceLayout = updated
            workspaceRepository.save(updated)
        },
        onOpenApp = onOpenApp,
        onOpenStatus = onOpenStatus,
        onOpenLink = onOpenLink,
        onBack = onBack,
        onRefresh = { refreshAttempt += 1 },
        onSearch = onSearch,
        onSettings = onSettings,
    )
}

/**
 * Shared production dashboard presentation.
 *
 * The authenticated host owns transport, cache, account identity, and persistence. Deterministic
 * captures can provide synthetic state without replacing the UI with a separate preview screen.
 */
@Composable
internal fun NativeDashboardPresentation(
    state: DashboardSurfaceState,
    installedApps: List<NextcloudAppEntry>,
    pinnedAppIds: List<String> = defaultAppWorkspacePinnedIds(),
    workspaceLayout: HomeWorkspaceLayout,
    workspaceLayoutAuthoritative: Boolean = true,
    onWorkspaceLayoutChanged: (HomeWorkspaceLayout) -> Boolean,
    onOpenApp: (NextcloudAppEntry) -> Unit,
    onOpenStatus: (() -> Unit)?,
    onOpenLink: (String) -> Unit,
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onSearch: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
) {
    var customizeWorkspace by rememberSaveable(workspaceLayout.scope.persistenceKey) {
        mutableStateOf(false)
    }
    var workspacePersistenceError by remember(workspaceLayout.scope) { mutableStateOf<String?>(null) }
    var activeWorkspaceLayout by remember(workspaceLayout) { mutableStateOf(workspaceLayout) }
    val widgetsAuthoritative = (state as? DashboardSurfaceState.Available)?.widgetsAuthoritative != false
    val workspaceWritesEnabled = widgetsAuthoritative && workspaceLayoutAuthoritative
    LaunchedEffect(workspaceWritesEnabled) {
        if (!workspaceWritesEnabled) {
            customizeWorkspace = false
            workspacePersistenceError = null
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            title = "Home",
            subtitle = when (workspaceLayout.scope.formFactor) {
                HomeFormFactor.Phone -> "What needs you next"
                HomeFormFactor.Tablet -> "Your cloud, ready to continue"
                HomeFormFactor.Desktop -> "Your work across Nextcloud"
            },
            onBack = onBack,
            onRefresh = onRefresh,
            onCustomize = if (workspaceWritesEnabled) {
                { customizeWorkspace = true }
            } else {
                null
            },
            onSearch = onSearch,
            onSettings = onSettings,
        )
        when (val current = state) {
            DashboardSurfaceState.Loading -> DashboardLoading()
            is DashboardSurfaceState.Failed -> DashboardFailure(
                message = current.message,
                onRetry = onRefresh,
            )
            is DashboardSurfaceState.Available -> {
                val bindings = remember(current.snapshot.widgets) {
                    homeDashboardWidgetBindings(current.snapshot.widgets)
                }
                val availableSectionIds = remember(bindings) {
                    buildList {
                        add(HomeSectionIds.QuickActions)
                        bindings.forEach { binding ->
                            if (size < MAX_HOME_WORKSPACE_SECTIONS) add(binding.sectionId)
                        }
                    }
                }
                val effectiveLayout = remember(activeWorkspaceLayout, availableSectionIds) {
                    activeWorkspaceLayout.reconcileAvailableSections(availableSectionIds)
                }
                LaunchedEffect(effectiveLayout, workspaceWritesEnabled) {
                    if (workspaceWritesEnabled && effectiveLayout != activeWorkspaceLayout) {
                        activeWorkspaceLayout = effectiveLayout
                        onWorkspaceLayoutChanged(effectiveLayout)
                    }
                }
                val bindingsBySection = remember(bindings) {
                    bindings.associateBy(HomeDashboardWidgetBinding::sectionId)
                }
                if (!current.widgetsAuthoritative) {
                    DashboardUnavailableNotice(
                        showingSavedContent = current.snapshot.widgets.isNotEmpty(),
                        onRetry = onRefresh,
                    )
                }
                current.status?.let { status ->
                    CurrentStatusStrip(status = status, onClick = onOpenStatus)
                }
                val sectionLabels = remember(bindings) {
                    buildMap {
                        put(HomeSectionIds.QuickActions, "Quick actions")
                        bindings.forEach { put(it.sectionId, it.widget.title) }
                    }
                }
                val updateWorkspaceLayout: (HomeWorkspaceLayout, Boolean) -> Unit = { updated, persist ->
                    activeWorkspaceLayout = updated
                    if (persist && workspaceWritesEnabled) {
                        workspacePersistenceError = if (onWorkspaceLayoutChanged(updated)) {
                            null
                        } else {
                            "Your changes are active, but could not be saved on this device."
                        }
                    }
                }
                if (customizeWorkspace) {
                    HomeWorkspaceEditBar(
                        layout = effectiveLayout,
                        sectionLabels = sectionLabels,
                        persistenceError = workspacePersistenceError,
                        onRestoreDefaults = {
                            updateWorkspaceLayout(
                                effectiveLayout.restoreDefaults().reconcileAvailableSections(
                                    effectiveLayout.sections.map(HomeWorkspaceSection::id),
                                ),
                                true,
                            )
                        },
                        onShow = { sectionId ->
                            updateWorkspaceLayout(effectiveLayout.show(sectionId), true)
                        },
                        onDone = {
                            customizeWorkspace = false
                            workspacePersistenceError = null
                        },
                    )
                }
                HomeWorkspaceSurface(
                    layout = effectiveLayout,
                    editing = customizeWorkspace,
                    sectionLabels = sectionLabels,
                    onLayoutChanged = updateWorkspaceLayout,
                    modifier = Modifier.weight(1f),
                ) { section ->
                    when (section.id) {
                        HomeSectionIds.QuickActions -> DashboardQuickActionsCard(
                            installedApps = installedApps,
                            pinnedAppIds = pinnedAppIds,
                            onOpenApp = onOpenApp,
                        )

                        else -> bindingsBySection[section.id]?.let { binding ->
                            DashboardWidgetCard(
                                widget = binding.widget,
                                items = current.snapshot.itemsByWidget[binding.widget.id].orEmpty(),
                                emptyContentMessage = current.snapshot
                                    .emptyContentMessagesByWidget[binding.widget.id],
                                halfEmptyContentMessage = current.snapshot
                                    .halfEmptyContentMessagesByWidget[binding.widget.id],
                                refreshFailed = binding.widget.id in current.snapshot.failedWidgetIds,
                                apiUnsupported = binding.widget.id in current.snapshot.unsupportedWidgetIds,
                                loading = binding.widget.id in current.snapshot.loadingWidgetIds,
                                size = section.size,
                                onOpenLink = onOpenLink,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun rememberNativeDashboardState(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    refreshAttempt: Int,
    recoveryAttempt: Int = 0,
): DashboardSurfaceState {
    var state by remember(session) {
        mutableStateOf<DashboardSurfaceState>(DashboardSurfaceState.Loading)
    }
    LaunchedEffect(session, refreshAttempt, recoveryAttempt) {
        val now = currentDashboardEpochSeconds()
        val displayed = state as? DashboardSurfaceState.Available
        val cached = sharedDashboardStatusMemoryCache.get(session, now)
        val previousSnapshot = retainedDashboardRefreshSnapshot(cached, displayed?.snapshot)
        val previousStatus = cached?.status ?: displayed?.status
        val cachePolicy = if (refreshAttempt > 0 || recoveryAttempt > 0) {
            NextcloudApiCachePolicy.RefreshNetwork
        } else {
            NextcloudApiCachePolicy.PreferCache
        }
        previousSnapshot?.let {
            state = DashboardSurfaceState.Available(
                snapshot = it,
                status = previousStatus,
                widgetsAuthoritative = cached != null || displayed?.widgetsAuthoritative != false,
            )
        }
        runCatching {
            coroutineScope {
                val widgetsDeferred = async {
                    acquireDashboardWidgets(
                        cachedAvailable = previousSnapshot != null,
                        executeResponse = {
                            services.executeNextcloudApi(session, dashboardWidgetsRequest(cachePolicy))
                        },
                        onDiagnostic = services::recordSupportDiagnostic,
                    )
                }
                val statusDeferred = async {
                    runCatching {
                        val response = services.executeNextcloudApi(session, currentUserStatusRequest())
                        withContext(Dispatchers.Default) { parseCurrentUserStatus(response) }
                    }.onFailure { failure ->
                        if (failure is CancellationException) throw failure
                    }
                }
                val widgetsLoad = widgetsDeferred.await()
                if (!widgetsLoad.authoritative) {
                    val snapshot = dashboardSnapshotForUnavailableWidgets(previousSnapshot)
                    state = DashboardSurfaceState.Available(
                        snapshot,
                        previousStatus,
                        widgetsAuthoritative = false,
                    )
                    val status = statusDeferred.await().getOrElse { previousStatus }
                    state = DashboardSurfaceState.Available(snapshot, status, widgetsAuthoritative = false)
                    return@coroutineScope DashboardLoadResult(snapshot, status, widgetsAuthoritative = false)
                }
                val widgets = widgetsLoad.widgets
                val unsupportedWidgetIds = unsupportedDashboardWidgetIds(widgets)
                if (unsupportedWidgetIds.isNotEmpty()) {
                    services.recordSupportDiagnostic(
                        dashboardLoadFailureDiagnostic(
                            stage = "widget_api_version",
                            code = "DASHBOARD_WIDGET_API_UNSUPPORTED",
                            cachedAvailable = previousSnapshot != null,
                            severity = SupportDiagnosticSeverity.Warning,
                        ),
                    )
                }
                val plans = dashboardItemsRequestPlans(widgets, cachePolicy = cachePolicy)
                val pendingWidgetIds = plans.flatMapTo(mutableSetOf(), DashboardItemsRequestPlan::widgetIds)
                val itemResults = mutableListOf<DashboardItemsFetchResult>()
                var snapshot = mergeDashboardItemFetchResults(
                    widgets = widgets,
                    previousSnapshot = previousSnapshot,
                    results = emptyList(),
                    unsupportedWidgetIds = unsupportedWidgetIds,
                    loadingWidgetIds = pendingWidgetIds,
                )
                state = DashboardSurfaceState.Available(snapshot, previousStatus)

                val completedResults = Channel<DashboardItemsFetchResult>(capacity = plans.size)
                val requestLimiter = Semaphore(MAX_CONCURRENT_DASHBOARD_ITEM_REQUESTS)
                val responseBudget = DashboardResponseBudget()
                val responseBudgetMutex = Mutex()
                val v2RouteMutex = Mutex()
                var v2RouteAvailability = DashboardV2RouteAvailability.Unknown
                val requests = plans.map { plan ->
                    async {
                        requestLimiter.withPermit {
                            val reservedBytes = responseBudgetMutex.withLock {
                                responseBudget.reserve()
                            }
                            if (reservedBytes == 0L) {
                                services.recordSupportDiagnostic(
                                    dashboardLoadFailureDiagnostic(
                                        stage = "widget_items_budget",
                                        code = "DASHBOARD_RESPONSE_BUDGET_EXHAUSTED",
                                        cachedAvailable = previousSnapshot != null,
                                        severity = SupportDiagnosticSeverity.Warning,
                                    ),
                                )
                                completedResults.send(DashboardItemsFetchResult.Failed(plan.widgetIds))
                                return@withPermit
                            }
                            var reservationSettled = false
                            var effectiveApiVersion = plan.apiVersion
                            val result = runCatching {
                                val fetched = executeDashboardItemsPlan(
                                    services = services,
                                    session = session,
                                    plan = plan,
                                    widgets = widgets,
                                    reservedBytes = reservedBytes,
                                    v2RouteMutex = v2RouteMutex,
                                    readV2RouteAvailability = { v2RouteAvailability },
                                    writeV2RouteAvailability = { v2RouteAvailability = it },
                                    onEffectiveApiVersion = { effectiveApiVersion = it },
                                )
                                effectiveApiVersion = fetched.apiVersion
                                val response = fetched.response
                                responseBudgetMutex.withLock {
                                    responseBudget.releaseUnused(reservedBytes, fetched.combinedResponseBytes)
                                }
                                reservationSettled = true
                                val selectedWidgets = widgets.filter { it.id in plan.widgetIds }
                                val payload = withContext(Dispatchers.Default) {
                                    when (effectiveApiVersion) {
                                        DashboardItemApiVersion.V1 -> DashboardItemsPayload(
                                            itemsByWidget = parseDashboardItems(response, selectedWidgets),
                                        )
                                        DashboardItemApiVersion.V2 -> parseDashboardItemsV2(response, selectedWidgets)
                                    }
                                }
                                dashboardItemsFetchResult(plan.widgetIds, payload).also { result ->
                                    if (result is DashboardItemsFetchResult.Failed) {
                                        services.recordSupportDiagnostic(
                                            dashboardLoadFailureDiagnostic(
                                                stage = "widget_items_v${effectiveApiVersion.wireValue}",
                                                code = "DASHBOARD_WIDGET_ITEMS_OMITTED",
                                                cachedAvailable = previousSnapshot != null,
                                                severity = SupportDiagnosticSeverity.Warning,
                                            ),
                                        )
                                    }
                                }
                            }.getOrElse { failure ->
                                if (!reservationSettled) {
                                    responseBudgetMutex.withLock {
                                        responseBudget.settleFailedRead(reservedBytes, failure)
                                    }
                                }
                                if (failure is CancellationException) throw failure
                                services.recordSupportDiagnostic(
                                    dashboardLoadFailureDiagnostic(
                                        stage = "widget_items_v${effectiveApiVersion.wireValue}",
                                        code = "DASHBOARD_WIDGET_ITEMS_V${effectiveApiVersion.wireValue}_FAILED",
                                        cachedAvailable = previousSnapshot != null,
                                        severity = SupportDiagnosticSeverity.Warning,
                                    ),
                                )
                                DashboardItemsFetchResult.Failed(plan.widgetIds)
                            }
                            completedResults.send(result)
                        }
                    }
                }
                repeat(plans.size) {
                    val result = completedResults.receive()
                    itemResults += result
                    pendingWidgetIds.removeAll(result.widgetIds)
                    snapshot = mergeDashboardItemFetchResults(
                        widgets = widgets,
                        previousSnapshot = previousSnapshot,
                        results = itemResults,
                        unsupportedWidgetIds = unsupportedWidgetIds,
                        loadingWidgetIds = pendingWidgetIds,
                    )
                    state = DashboardSurfaceState.Available(snapshot, previousStatus)
                }
                requests.awaitAll()
                completedResults.close()
                DashboardLoadResult(
                    snapshot,
                    statusDeferred.await().getOrElse { previousStatus },
                    widgetsAuthoritative = true,
                )
            }
        }.onSuccess { result ->
            if (result.widgetsAuthoritative) {
                sharedDashboardStatusMemoryCache.store(
                    session = session,
                    dashboard = result.snapshot,
                    status = result.status,
                    nowEpochSeconds = currentDashboardEpochSeconds(),
                )
            }
            state = DashboardSurfaceState.Available(
                result.snapshot,
                result.status,
                widgetsAuthoritative = result.widgetsAuthoritative,
            )
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
            if (state !is DashboardSurfaceState.Available) {
                state = DashboardSurfaceState.Failed(
                    "The dashboard could not be loaded. Try again.",
                )
            }
        }
    }
    return state
}

@Composable
private fun DashboardUnavailableNotice(
    showingSavedContent: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = NextcloudSpacing.Large,
                vertical = NextcloudSpacing.Small,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (showingSavedContent) {
                    "The Dashboard app is unavailable. Showing your saved Home content."
                } else {
                    "The Dashboard app is unavailable. Quick actions remain available."
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun CurrentStatusStrip(
    status: NativeUserStatus,
    onClick: (() -> Unit)?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = NextcloudSpacing.Medium,
                vertical = NextcloudSpacing.XSmall,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(NextcloudSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            StatusPresenceDot(status.presence)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.message?.let { message ->
                        listOfNotNull(status.icon, message).joinToString(" ")
                    } ?: status.presence.displayLabel(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status.message != null) Text(status.presence.displayLabel(), style = MaterialTheme.typography.labelSmall)
            }
            if (onClick != null) {
                Text("Edit status", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

internal data class HomeDashboardWidgetBinding(
    val sectionId: HomeSectionId,
    val widget: NativeDashboardWidget,
)

internal fun homeDashboardWidgetBindings(
    widgets: List<NativeDashboardWidget>,
): List<HomeDashboardWidgetBinding> {
    val occupied = mutableSetOf<HomeSectionId>()
    return widgets.map { widget ->
        val sectionId = widget.availableHomeSectionId(occupied)
        occupied += sectionId
        HomeDashboardWidgetBinding(sectionId = sectionId, widget = widget)
    }
}

@Composable
private fun HomeWorkspaceEditBar(
    layout: HomeWorkspaceLayout,
    sectionLabels: Map<HomeSectionId, String>,
    persistenceError: String?,
    onRestoreDefaults: () -> Unit,
    onShow: (HomeSectionId) -> Unit,
    onDone: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = NextcloudSpacing.XLarge,
            vertical = NextcloudSpacing.Small,
        ),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Arrange Home", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Drag each card where it belongs. Its controls stay with the card.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onRestoreDefaults) { Text("Reset") }
                Button(onClick = onDone) { Text("Done") }
            }
            persistenceError?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            if (layout.hiddenSections.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    layout.hiddenSections.forEach { section ->
                        OutlinedButton(onClick = { onShow(section.id) }) {
                            Text("Show ${sectionLabels[section.id] ?: "section"}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWorkspaceSurface(
    layout: HomeWorkspaceLayout,
    editing: Boolean,
    sectionLabels: Map<HomeSectionId, String>,
    onLayoutChanged: (HomeWorkspaceLayout, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sectionContent: @Composable (HomeWorkspaceSection) -> Unit,
) {
    val sectionBounds = remember(layout.scope) { mutableStateMapOf<HomeSectionId, Rect>() }
    var draggingSectionId by remember(layout.scope) { mutableStateOf<HomeSectionId?>(null) }
    var dragStartLayout by remember(layout.scope) { mutableStateOf<HomeWorkspaceLayout?>(null) }
    var dragOrigin by remember(layout.scope) { mutableStateOf<Offset?>(null) }
    var dragPosition by remember(layout.scope) { mutableStateOf<Offset?>(null) }
    var workspaceViewport by remember(layout.scope) { mutableStateOf<Rect?>(null) }

    fun layoutWithDraggedSectionAt(position: Offset): HomeWorkspaceLayout {
        val sourceId = draggingSectionId ?: return layout
        return homeWorkspaceLayoutAtDragPosition(layout, sourceId, position, sectionBounds)
    }

    fun moveDraggedSectionAcrossAdjacentMidpoint(position: Offset, movementY: Float) {
        val sourceId = draggingSectionId ?: return
        if (movementY == 0f) return
        val visibleIds = layout.visibleSections.map(HomeWorkspaceSection::id)
        val sourceIndex = visibleIds.indexOf(sourceId)
        if (sourceIndex < 0) return
        val direction = if (movementY < 0f) -1 else 1
        val targetId = visibleIds.getOrNull(sourceIndex + direction) ?: return
        val targetBounds = sectionBounds[targetId] ?: return
        val crossedMidpoint = if (direction < 0) {
            position.y <= targetBounds.center.y
        } else {
            position.y >= targetBounds.center.y
        }
        if (!crossedMidpoint) return
        val destinationIndex = layout.sections.indexOfFirst { it.id == targetId }
        if (destinationIndex >= 0) onLayoutChanged(layout.move(sourceId, destinationIndex), false)
    }

    val content: @Composable (HomeWorkspaceSection) -> Unit = { item ->
        DisposableEffect(item.id) {
            onDispose { sectionBounds.remove(item.id) }
        }
        val index = layout.sections.indexOfFirst { it.id == item.id }
        HomeWorkspaceSectionContainer(
            section = item,
            label = sectionLabels[item.id] ?: "Dashboard section",
            index = index,
            sectionCount = layout.sections.size,
            editing = editing,
            dragging = draggingSectionId == item.id,
            onBoundsChanged = { bounds -> sectionBounds[item.id] = bounds },
            onDragStart = { position ->
                draggingSectionId = item.id
                dragStartLayout = layout
                dragOrigin = position
                dragPosition = position
            },
            onDrag = { delta ->
                dragPosition?.let { current ->
                    val position = current + delta
                    dragPosition = position
                    if (layout.scope.formFactor == HomeFormFactor.Phone) {
                        moveDraggedSectionAcrossAdjacentMidpoint(position, delta.y)
                    } else {
                        val movedLayout = layoutWithDraggedSectionAt(position)
                        if (movedLayout != layout) onLayoutChanged(movedLayout, false)
                    }
                }
            },
            onDragEnd = {
                val finalLayout = dragPosition?.let(::layoutWithDraggedSectionAt) ?: layout
                draggingSectionId = null
                dragStartLayout = null
                dragOrigin = null
                dragPosition = null
                onLayoutChanged(finalLayout, true)
            },
            onDragCancel = {
                val restoredLayout = dragStartLayout
                draggingSectionId = null
                dragStartLayout = null
                dragOrigin = null
                dragPosition = null
                if (restoredLayout != null && restoredLayout != layout) {
                    onLayoutChanged(restoredLayout, false)
                }
            },
            onMoveEarlier = {
                onLayoutChanged(layout.move(item.id, index - 1), true)
            },
            onMoveLater = {
                onLayoutChanged(layout.move(item.id, index + 1), true)
            },
            onResize = {
                onLayoutChanged(layout.resize(item.id, item.size.nextHomeSectionSize()), true)
            },
            onHide = {
                onLayoutChanged(layout.hide(item.id), true)
            },
        ) {
            sectionContent(item)
        }
    }

    when (layout.scope.formFactor) {
        HomeFormFactor.Phone -> {
            val state = rememberLazyListState()
            NextcloudVerticalDragAutoScroll(
                activeDragKey = draggingSectionId,
                position = dragPosition,
                dragOrigin = dragOrigin,
                viewport = workspaceViewport,
                scrollState = state,
            )
            MobileHomeWorkspace(
                sections = layout.visibleSections,
                state = state,
                onViewportChanged = { workspaceViewport = it },
                modifier = modifier,
                sectionContent = content,
            )
        }
        HomeFormFactor.Tablet -> {
            val state = rememberLazyStaggeredGridState()
            NextcloudVerticalDragAutoScroll(
                activeDragKey = draggingSectionId,
                position = dragPosition,
                dragOrigin = dragOrigin,
                viewport = workspaceViewport,
                scrollState = state,
            )
            TabletHomeWorkspace(
                sections = layout.visibleSections,
                state = state,
                onViewportChanged = { workspaceViewport = it },
                modifier = modifier,
                sectionContent = content,
            )
        }
        HomeFormFactor.Desktop -> {
            val state = rememberLazyStaggeredGridState()
            NextcloudVerticalDragAutoScroll(
                activeDragKey = draggingSectionId,
                position = dragPosition,
                dragOrigin = dragOrigin,
                viewport = workspaceViewport,
                scrollState = state,
            )
            DesktopHomeWorkspace(
                sections = layout.visibleSections,
                state = state,
                onViewportChanged = { workspaceViewport = it },
                modifier = modifier,
                sectionContent = content,
            )
        }
    }
}

internal fun homeWorkspaceLayoutAtDragPosition(
    layout: HomeWorkspaceLayout,
    sourceId: HomeSectionId,
    position: Offset,
    sectionBounds: Map<HomeSectionId, Rect>,
): HomeWorkspaceLayout {
    val targetId = sectionBounds.entries.firstOrNull { (_, bounds) ->
        bounds.contains(position)
    }?.key ?: return layout
    if (targetId == sourceId) return layout
    val destinationIndex = layout.sections.indexOfFirst { section -> section.id == targetId }
    return if (destinationIndex >= 0) layout.move(sourceId, destinationIndex) else layout
}

@Composable
private fun MobileHomeWorkspace(
    sections: List<HomeWorkspaceSection>,
    state: LazyListState,
    onViewportChanged: (Rect) -> Unit,
    modifier: Modifier,
    sectionContent: @Composable (HomeWorkspaceSection) -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            onViewportChanged(coordinates.boundsInWindow())
        },
        contentPadding = PaddingValues(
            start = NextcloudSpacing.Medium,
            top = NextcloudSpacing.Medium,
            end = NextcloudSpacing.Medium,
            bottom = NextcloudSpacing.XXLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        items(sections, key = { item -> item.id.value }) { item ->
            sectionContent(item)
        }
    }
}

@Composable
private fun TabletHomeWorkspace(
    sections: List<HomeWorkspaceSection>,
    state: LazyStaggeredGridState,
    onViewportChanged: (Rect) -> Unit,
    modifier: Modifier,
    sectionContent: @Composable (HomeWorkspaceSection) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        state = state,
        columns = StaggeredGridCells.Adaptive(300.dp),
        modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            onViewportChanged(coordinates.boundsInWindow())
        },
        contentPadding = PaddingValues(NextcloudSpacing.Large),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        verticalItemSpacing = NextcloudSpacing.Large,
    ) {
        items(sections, key = { item -> item.id.value }) { item ->
            sectionContent(item)
        }
    }
}

@Composable
private fun DesktopHomeWorkspace(
    sections: List<HomeWorkspaceSection>,
    state: LazyStaggeredGridState,
    onViewportChanged: (Rect) -> Unit,
    modifier: Modifier,
    sectionContent: @Composable (HomeWorkspaceSection) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        state = state,
        columns = StaggeredGridCells.Adaptive(340.dp),
        modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            onViewportChanged(coordinates.boundsInWindow())
        },
        contentPadding = PaddingValues(
            start = NextcloudSpacing.XLarge,
            top = NextcloudSpacing.Large,
            end = NextcloudSpacing.XLarge,
            bottom = NextcloudSpacing.XXLarge,
        ),
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Large),
        verticalItemSpacing = NextcloudSpacing.Large,
    ) {
        items(sections, key = { item -> item.id.value }) { item ->
            sectionContent(item)
        }
    }
}

@Composable
private fun HomeWorkspaceSectionContainer(
    section: HomeWorkspaceSection,
    label: String,
    index: Int,
    sectionCount: Int,
    editing: Boolean,
    dragging: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMoveEarlier: () -> Unit,
    onMoveLater: () -> Unit,
    onResize: () -> Unit,
    onHide: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates -> onBoundsChanged(coordinates.boundsInWindow()) }
            .graphicsLayer { alpha = if (dragging) 0.62f else 1f },
        verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
            if (editing) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(NextcloudSpacing.XSmall),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    shadowElevation = 2.dp,
                ) {
                    NextcloudBoardDragHandle(
                        itemLabel = label,
                        dragActive = dragging,
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
            }
        }
        if (editing) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.XSmall),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                ) {
                    OutlinedButton(enabled = index > 0, onClick = onMoveEarlier) {
                        Text("Earlier")
                    }
                    OutlinedButton(enabled = index in 0 until sectionCount - 1, onClick = onMoveLater) {
                        Text("Later")
                    }
                    TextButton(onClick = onResize) {
                        Text("Size: ${section.size.name.lowercase()}")
                    }
                    TextButton(onClick = onHide) { Text("Hide") }
                }
            }
        }
    }
}

private fun HomeSectionSize.nextHomeSectionSize(): HomeSectionSize =
    HomeSectionSize.entries[(ordinal + 1) % HomeSectionSize.entries.size]

@Composable
private fun DashboardWidgetCard(
    widget: NativeDashboardWidget,
    items: List<NativeDashboardItem>,
    emptyContentMessage: String?,
    halfEmptyContentMessage: String?,
    refreshFailed: Boolean,
    apiUnsupported: Boolean,
    loading: Boolean,
    size: HomeSectionSize,
    onOpenLink: (String) -> Unit,
) {
    var expanded by remember(widget.id) { mutableStateOf(false) }
    val collapsedItemCount = dashboardCollapsedItemCount(size)
    val visibleItems = if (expanded) items else items.take(collapsedItemCount)
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(
            min = when (size) {
                HomeSectionSize.Compact -> 112.dp
                HomeSectionSize.Comfortable -> 150.dp
                HomeSectionSize.Dense -> 180.dp
            },
        ),
        colors = CardDefaults.cardColors(containerColor = NextcloudTheme.colors.appTile),
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Surface(
                    shape = CircleShape,
                    color = NextcloudTheme.colors.appIconContainer,
                ) {
                    Icon(
                        imageVector = NextcloudIcons.app(widget.id),
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    widget.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                loading && items.isEmpty() -> Text(
                    "Loading this section...",
                    modifier = Modifier.padding(top = NextcloudSpacing.Large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                apiUnsupported -> Text(
                    "This section requires a newer Dashboard API than this app supports.",
                    modifier = Modifier.padding(top = NextcloudSpacing.Large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                items.isEmpty() && refreshFailed -> Text(
                    "Could not load this section. Refresh to try again.",
                    modifier = Modifier.padding(top = NextcloudSpacing.Large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                items.isEmpty() -> Text(
                    emptyContentMessage ?: "Nothing new",
                    modifier = Modifier.padding(top = NextcloudSpacing.Large),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    if (loading) {
                        Text(
                            "Refreshing. Showing recently loaded items.",
                            modifier = Modifier.padding(top = NextcloudSpacing.Large),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (refreshFailed) {
                        Text(
                            "Could not refresh. Showing recently loaded items.",
                            modifier = Modifier.padding(top = NextcloudSpacing.Large),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    visibleItems.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = NextcloudSpacing.Small),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        DashboardItemRow(item = item, onOpenLink = onOpenLink)
                    }
                    if (items.size > collapsedItemCount) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(
                                if (expanded) {
                                    "Show less"
                                } else {
                                    "Show ${items.size - collapsedItemCount} more"
                                },
                            )
                        }
                    }
                    halfEmptyContentMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(top = NextcloudSpacing.Medium),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (widget.actions.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    items(widget.actions) { action ->
                        TextButton(onClick = { onOpenLink(action.link) }) {
                            Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

internal fun dashboardCollapsedItemCount(size: HomeSectionSize): Int = when (size) {
    HomeSectionSize.Compact -> 2
    HomeSectionSize.Comfortable -> 4
    HomeSectionSize.Dense -> 8
}

private fun NativeDashboardWidget.preferredHomeSectionId(): HomeSectionId = when (id.lowercase()) {
    "activity" -> HomeSectionIds.Activity
    "calendar", "upcoming" -> HomeSectionIds.Upcoming
    "recommendations", "recent-files", "recent_files", "recentfiles" -> HomeSectionIds.RecentFiles
    "photos", "memories", "photo-backup", "photo_backup" -> HomeSectionIds.PhotoBackup
    "favorites", "favourites" -> HomeSectionIds.Favorites
    "quota", "storage", "storage-quota", "storage_quota" -> HomeSectionIds.Storage
    else -> dynamicHomeSectionId()
}

private fun NativeDashboardWidget.availableHomeSectionId(
    occupied: Set<HomeSectionId>,
): HomeSectionId {
    val preferred = preferredHomeSectionId()
    if (preferred !in occupied) return preferred
    repeat(MAX_HOME_WORKSPACE_SECTIONS) { disambiguation ->
        val candidate = dynamicHomeSectionId(disambiguation)
        if (candidate !in occupied) return candidate
    }
    error("The dashboard has no available bounded section ID.")
}

private fun NativeDashboardWidget.dynamicHomeSectionId(
    disambiguation: Int = 0,
): HomeSectionId {
    val readable = id.lowercase()
        .map { character ->
            if (
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.'
            ) {
                character
            } else {
                '-'
            }
        }
        .joinToString("")
        .trim('-')
        .take(MAX_DASHBOARD_SECTION_READABLE_ID_LENGTH)
        .ifEmpty { "widget" }
    val hashSource = if (disambiguation == 0) id else "$id#$disambiguation"
    val hash = hashSource.encodeToByteArray().fold(FNV_OFFSET_BASIS) { current, byte ->
        (current xor byte.toUByte().toUInt()) * FNV_PRIME
    }
    return HomeSectionId("dashboard:$readable:${hash.toString(16).padStart(8, '0')}")
}

@Composable
private fun DashboardItemRow(
    item: NativeDashboardItem,
    onOpenLink: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(item.link?.let { link -> Modifier.clickable { onOpenLink(link) } } ?: Modifier)
            .padding(vertical = NextcloudSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
    ) {
        Icon(
            NextcloudIcons.app(item.widgetId),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.link != null) {
            Icon(
                NextcloudIcons.ChevronRight,
                contentDescription = "Open ${item.title}",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun DashboardLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            "Loading dashboard...",
            modifier = Modifier.padding(top = NextcloudSpacing.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(NextcloudSpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            NextcloudIcons.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(36.dp),
        )
        Text(
            message,
            modifier = Modifier.padding(vertical = NextcloudSpacing.Medium),
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

private enum class StatusExpiryChoice(val label: String, val seconds: Long?) {
    Never("No expiry", null),
    OneHour("1 hour", 60L * 60L),
    FourHours("4 hours", 4L * 60L * 60L),
    OneDay("24 hours", 24L * 60L * 60L),
}

@Composable
internal fun NativeUserStatusScreen(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    onBack: () -> Unit,
) {
    var state by remember(session) {
        mutableStateOf<UserStatusSurfaceState>(
            UserStatusWorkspaceMemoryCache.get(session) ?: UserStatusSurfaceState.Loading,
        )
    }
    var refreshing by remember(session) { mutableStateOf(false) }
    var refreshError by remember(session) { mutableStateOf<String?>(null) }
    var refreshAttempt by remember(session) { mutableStateOf(0) }
    var customMessage by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var customIcon by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf("") }
    var expiryChoiceName by rememberSaveable(session.serverUrl, session.loginName) {
        mutableStateOf(StatusExpiryChoice.Never.name)
    }
    var draftInitialized by rememberSaveable(session.serverUrl, session.loginName) { mutableStateOf(false) }
    val expiryChoice = StatusExpiryChoice.entries.firstOrNull { it.name == expiryChoiceName }
        ?: StatusExpiryChoice.Never
    var pendingEdit by remember(session) { mutableStateOf<NativeUserStatusEdit?>(null) }
    var mutationInProgress by remember(session) { mutableStateOf(false) }
    var mutationError by remember(session) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session, refreshAttempt) {
        val cached = UserStatusWorkspaceMemoryCache.get(session)
        if (cached != null) state = cached
        val retained = cached ?: state as? UserStatusSurfaceState.Available
        refreshError = null
        if (retained == null) {
            state = UserStatusSurfaceState.Loading
        } else {
            refreshing = true
        }
        runCatching {
            val capabilities = parseUserStatusCapabilities(
                services.executeNextcloudApi(session, userStatusCapabilitiesRequest()),
            )
            require(capabilities.enabled) { "User Status is not enabled on this server." }
            coroutineScope {
                val currentDeferred = async {
                    parseCurrentUserStatus(
                        services.executeNextcloudApi(session, currentUserStatusRequest()),
                    )
                }
                val predefinedDeferred = async {
                    parsePredefinedStatuses(
                        services.executeNextcloudApi(session, predefinedStatusesRequest()),
                    )
                }
                UserStatusSurfaceState.Available(
                    capabilities = capabilities,
                    status = currentDeferred.await(),
                    predefined = predefinedDeferred.await(),
                )
            }
        }.onSuccess { loaded ->
            state = loaded
            UserStatusWorkspaceMemoryCache.store(session, loaded)
            if (!draftInitialized) {
                customMessage = loaded.status.message.orEmpty()
                customIcon = loaded.status.icon.orEmpty().takeIf {
                    loaded.capabilities.supportsEmoji
                }.orEmpty()
                draftInitialized = true
            }
        }.onFailure { failure ->
            val message = failure.message ?: "Your status could not be loaded."
            if (retained == null) {
                state = UserStatusSurfaceState.Failed(message)
            } else {
                refreshError = message
            }
        }
        refreshing = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader(
            title = "User Status",
            subtitle = "Choose what other people see",
            onBack = onBack,
            onRefresh = { refreshAttempt += 1 },
        )
        if (refreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        refreshError?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Small,
                ),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(NextcloudRadii.Small),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = NextcloudSpacing.Medium),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { refreshAttempt += 1 }) { Text("Retry") }
                }
            }
        }
        when (val current = state) {
            UserStatusSurfaceState.Loading -> DashboardLoading()
            is UserStatusSurfaceState.Failed -> DashboardFailure(
                message = current.message,
                onRetry = { refreshAttempt += 1 },
            )
            is UserStatusSurfaceState.Available -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XLarge),
            ) {
                item {
                    CurrentUserStatusCard(current.status)
                }
                item {
                    SectionLabel("Presence")
                    LazyRow(
                        contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        items(availableUserPresences(current.capabilities)) { presence ->
                            FilterChip(
                                selected = current.status.presence == presence,
                                onClick = {
                                    pendingEdit = NativeUserStatusEdit.Presence(presence)
                                    mutationError = null
                                },
                                label = { Text(presence.displayLabel()) },
                                leadingIcon = { StatusPresenceDot(presence) },
                            )
                        }
                    }
                }
                if (current.predefined.isNotEmpty()) {
                    item {
                        SectionLabel("Quick statuses")
                        Column(
                            modifier = Modifier.padding(top = NextcloudSpacing.Small),
                            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                        ) {
                            current.predefined.forEach { predefined ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            pendingEdit = NativeUserStatusEdit.PredefinedMessage(
                                                messageId = predefined.id,
                                                clearAtEpochSeconds = expiryChoice.expiryEpochSeconds(),
                                            )
                                            mutationError = null
                                        },
                                    color = NextcloudTheme.colors.appTile,
                                    shape = RoundedCornerShape(NextcloudRadii.Card),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                                    ) {
                                        Text(predefined.icon ?: "•", style = MaterialTheme.typography.titleMedium)
                                        Text(predefined.message, modifier = Modifier.weight(1f))
                                        Icon(
                                            NextcloudIcons.ChevronRight,
                                            contentDescription = "Use ${predefined.message}",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    SectionLabel("Custom status")
                    OutlinedTextField(
                        value = customMessage,
                        onValueChange = { value ->
                            if (value.length <= 512 && value.none(Char::isISOControl)) {
                                customMessage = value
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Small),
                        label = { Text("Message") },
                        supportingText = { Text("${customMessage.length}/512") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    if (current.capabilities.supportsEmoji) {
                        OutlinedTextField(
                            value = customIcon,
                            onValueChange = { value ->
                                if (value.length <= 32 && value.none(Char::isISOControl)) {
                                    customIcon = value
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = NextcloudSpacing.Small),
                            label = { Text("Emoji or icon") },
                            singleLine = true,
                        )
                    }
                }
                item {
                    SectionLabel("Clear automatically")
                    LazyRow(
                        contentPadding = PaddingValues(top = NextcloudSpacing.Small),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        items(StatusExpiryChoice.entries) { choice ->
                            FilterChip(
                                selected = expiryChoice == choice,
                                onClick = { expiryChoiceName = choice.name },
                                label = { Text(choice.label) },
                            )
                        }
                    }
                }
                item {
                    mutationError?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(bottom = NextcloudSpacing.Small),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                        Button(
                            enabled = customMessage.isNotBlank() && !mutationInProgress,
                            onClick = {
                                pendingEdit = NativeUserStatusEdit.CustomMessage(
                                    message = customMessage.trim(),
                                    icon = customIcon.trim().takeIf(String::isNotEmpty),
                                    clearAtEpochSeconds = expiryChoice.expiryEpochSeconds(),
                                )
                                mutationError = null
                            },
                        ) {
                            Text("Save status")
                        }
                        if (current.status.message != null) {
                            OutlinedButton(
                                enabled = !mutationInProgress,
                                onClick = {
                                    pendingEdit = NativeUserStatusEdit.ClearMessage
                                    mutationError = null
                                },
                            ) {
                                Text("Clear message")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingEdit?.let { edit ->
        val capabilities = (state as? UserStatusSurfaceState.Available)?.capabilities
        val requestPlan = capabilities?.let {
            runCatching {
                planUserStatusEdit(
                    edit = edit,
                    capabilities = it,
                    nowEpochSeconds = currentDashboardEpochSeconds(),
                )
            }
        }
        val planningFailure = requestPlan?.exceptionOrNull()
        if (planningFailure != null) {
            AlertDialog(
                onDismissRequest = { pendingEdit = null },
                title = { Text("Status change unavailable") },
                text = {
                    Text(planningFailure.message ?: "This status change is not supported.")
                },
                confirmButton = {
                    TextButton(onClick = { pendingEdit = null }) { Text("Close") }
                },
            )
        }
        val request = requestPlan?.getOrNull()
        if (request != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!mutationInProgress) pendingEdit = null
                },
                title = { Text("Confirm status change") },
                text = {
                    Text(
                        "${edit.confirmationLabel().replaceFirstChar { it.uppercase() }}. " +
                            "This will update your status for people on this server.",
                    )
                },
                dismissButton = {
                    TextButton(
                        enabled = !mutationInProgress,
                        onClick = { pendingEdit = null },
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !mutationInProgress,
                        onClick = {
                            mutationInProgress = true
                            scope.launch {
                                runCatching {
                                    services.executeNextcloudApi(session, request).also { response ->
                                        require(response.status in 200..299) {
                                            "The server rejected this status change (HTTP ${response.status})."
                                        }
                                    }
                                }.onSuccess {
                                    sharedDashboardStatusMemoryCache.invalidate(session)
                                    pendingEdit = null
                                    mutationInProgress = false
                                    mutationError = null
                                    draftInitialized = false
                                    refreshAttempt += 1
                                }.onFailure { failure ->
                                    mutationInProgress = false
                                    mutationError = failure.message ?: "The status could not be updated."
                                    pendingEdit = null
                                }
                            }
                        },
                    ) {
                        if (mutationInProgress) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(if (mutationInProgress) "Updating..." else "Update status")
                    }
                },
            )
        }
    }
}


@Composable
private fun CurrentUserStatusCard(status: NativeUserStatus) {
    Surface(
        color = NextcloudTheme.colors.appTile,
        shape = RoundedCornerShape(NextcloudRadii.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
        ) {
            StatusPresenceDot(status.presence)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.message?.let { message ->
                        listOfNotNull(status.icon, message).joinToString(" ")
                    } ?: "No status message",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    status.presence.displayLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPresenceDot(presence: NativeUserPresence) {
    val color = when (presence) {
        NativeUserPresence.Online -> NextcloudTheme.colors.success
        NativeUserPresence.Away -> MaterialTheme.colorScheme.tertiary
        NativeUserPresence.DoNotDisturb -> MaterialTheme.colorScheme.error
        NativeUserPresence.Invisible,
        NativeUserPresence.Offline,
        -> MaterialTheme.colorScheme.outline
        NativeUserPresence.Busy -> MaterialTheme.colorScheme.error
    }
    Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun StatusExpiryChoice.expiryEpochSeconds(): Long? =
    seconds?.let { currentDashboardEpochSeconds() + it }

private fun currentDashboardEpochSeconds(): Long = Clock.System.now().epochSeconds

private const val MAX_DASHBOARD_SECTION_READABLE_ID_LENGTH = 48
private const val FNV_OFFSET_BASIS: UInt = 2_166_136_261u
private const val FNV_PRIME: UInt = 16_777_619u
