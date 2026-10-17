package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Full-screen preview shared by Files, Photos, Memories, and future media surfaces.
 *
 * Selection remains owned by the calling screen so closing the viewer returns to the same item.
 * Arrow keys navigate on desktop; Escape closes the viewer. Focusable icon buttons also support
 * normal keyboard activation. Image edits are reversible previews until the user explicitly saves
 * a new recipe sidecar; the source file is never passed to a write operation.
 * Deletes run in the account's shared [fileDeletes] owner, like deletes from Files. An applied
 * delete goes to [onItemRemoved], whose owner removes it from the navigable sequence.
 */
@Composable
internal fun NextcloudMediaViewer(
    media: List<NextcloudFile>,
    selected: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    fileDeletes: FileDeleteCoordinator,
    taggingAvailable: Boolean,
    memoriesLivePhotoCapability: MemoriesLivePhotoCapability,
    sharingCapabilities: NextcloudFileSharingCapabilities,
    onSelect: (NextcloudFile) -> Unit,
    onSourceRemoved: (NextcloudFile) -> Unit,
    onItemRemoved: (NextcloudFile) -> Unit,
    onClose: () -> Unit,
    navigationRequest: NextcloudPendingNavigationRequest? = null,
    onNavigationConfirmed: (NextcloudPendingNavigationRequest) -> Unit = {},
    onNavigationCancelled: (NextcloudPendingNavigationRequest) -> Unit = {},
    sourceMembers: List<NextcloudFile> = media,
    initialZoom: Float = 1f,
    onStateObserved: (MediaViewerStateObservation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val items = remember(media, selected) { mediaViewerItems(media, selected) }
    val sourcePlan = remember(sourceMembers, selected) {
        planMediaSources(sourceMembers, selected)
    }
    val fullQualityGeneration = sourcePlan.fullQualityCandidates.map { choice ->
        choice.file.mediaViewerSourceGenerationIdentity()
    }
    val sourceLoadIdentity = remember(
        selected.path,
        userId,
        session.serverUrl,
        session.loginName,
        fullQualityGeneration,
    ) {
        MediaViewerSourceLoadIdentity(
            selectedPath = selected.path,
            filesUserId = userId,
            serverUrl = session.serverUrl,
            loginName = session.loginName,
            candidates = fullQualityGeneration,
        )
    }
    val livePhotoDiscoveryIdentity = remember(selected) {
        selected.livePhotoDiscoveryIdentity()
    }
    val selectedIndex = mediaViewerNavigationIndex(items, selected).coerceAtLeast(0)
    val canGoPrevious = selectedIndex > 0
    val canGoNext = selectedIndex < items.lastIndex
    val focusRequester = remember { FocusRequester() }
    var retryKey by remember(selected.path) { mutableIntStateOf(0) }
    var previewState by remember(selected.path, retryKey) {
        mutableStateOf<MediaPreviewState>(MediaPreviewState.Loading)
    }
    var fullQualityState by remember(sourceLoadIdentity) {
        mutableStateOf<FullQualityState>(FullQualityState.Idle)
    }
    var imageTransform by remember(sourceLoadIdentity) {
        mutableStateOf(MediaViewportTransform(initialZoom.coerceIn(MINIMUM_MEDIA_ZOOM, MAXIMUM_MEDIA_ZOOM)))
    }
    val zoom = imageTransform.zoom
    var editing by remember(selected.path) { mutableStateOf(false) }
    var tagging by remember(selected.path) { mutableStateOf(false) }
    var tagResolution by remember(selected.path) { mutableStateOf<ResolvedMemoriesPhotoTags?>(null) }
    var selectedTagIds by remember(selected.path) { mutableStateOf<Set<Long>>(emptySet()) }
    var tagError by remember(selected.path) { mutableStateOf<String?>(null) }
    var tagSaving by remember(selected.path) { mutableStateOf(false) }
    var activeActionMutations by remember(selected.path) { mutableIntStateOf(0) }
    var tagReloadKey by remember(selected.path) { mutableIntStateOf(0) }
    var externalOpening by remember(selected.path) { mutableStateOf(false) }
    var externalError by remember(selected.path) { mutableStateOf<String?>(null) }
    var nativeVideoFailure by remember(selected.path) {
        mutableStateOf<NativeVideoPlaybackFailure?>(null)
    }
    var compatibilityPlaybackRequested by remember(selected.path) { mutableStateOf(false) }
    var compatibilityPlaybackAttempt by remember(selected.path) { mutableIntStateOf(0) }
    var livePhotoSource by remember(
        session.serverUrl,
        session.loginName,
        livePhotoDiscoveryIdentity,
    ) {
        mutableStateOf<MemoriesLivePhotoSource?>(null)
    }
    var livePhotoCompatibilityRangePlan by remember(
        session.serverUrl,
        session.loginName,
        livePhotoDiscoveryIdentity,
    ) {
        mutableStateOf<NativeVideoCompatibilityRangePlan?>(null)
    }
    var motionPlaying by remember(
        session.serverUrl,
        session.loginName,
        livePhotoDiscoveryIdentity,
    ) {
        mutableStateOf(false)
    }
    var videoOriginalAttempt by remember(selected.path) { mutableIntStateOf(0) }
    var videoOriginal by remember(selected, userId, session.serverUrl, session.loginName) {
        mutableStateOf(
            if (selected.needsVideoOriginalResolution(userId)) {
                MediaVideoOriginalState.Resolving
            } else {
                MediaVideoOriginalState.Direct
            },
        )
    }
    val playbackFile = videoOriginal.playbackFileFor(selected)
    // Survives item changes so swiping through media stays immersive once the user hid controls.
    var chromeVisible by remember { mutableStateOf(true) }
    var viewerAction by remember(selected.path) { mutableStateOf<MediaViewerAction?>(null) }
    var writeBlockNotice by remember(selected.path) { mutableStateOf<FileWriteBlock?>(null) }
    val scope = rememberCoroutineScope()
    val onActionMutationRunningChanged: (Boolean) -> Unit = remember(selected.path) {
        { running ->
            activeActionMutations = (
                activeActionMutations + if (running) 1 else -1
                ).coerceAtLeast(0)
        }
    }
    val viewerActions = remember(
        selected,
        userId,
        taggingAvailable,
        sharingCapabilities,
        services.externalFileHandoffSupport,
    ) {
        val externalActions = (
            services.externalFileHandoffSupport as? ExternalFileHandoffSupport.Available
            )?.capability?.supportedActions.orEmpty()
        availableMediaViewerActions(
            file = selected,
            userId = userId,
            taggingAvailable = taggingAvailable,
            sharingCapabilities = sharingCapabilities,
            externalActions = externalActions,
        )
    }

    fun selectPrevious() {
        if (canGoPrevious) onSelect(items[selectedIndex - 1])
    }

    fun selectNext() {
        if (canGoNext) onSelect(items[selectedIndex + 1])
    }

    fun handoffToExternalApp(action: ExternalFileHandoffAction) {
        val target = playbackFile
        if (externalOpening || !target.hasAuthoritativeMediaDavAccess(userId)) return
        externalOpening = true
        externalError = null
        scope.launch {
            runCatching {
                services.handoffFileToExternalApp(
                    session = session,
                    userId = userId,
                    file = target,
                    action = action,
                )
            }.onSuccess { result ->
                externalOpening = false
                externalError = when (result) {
                    is ExternalFileHandoffResult.Launched, is ExternalFileHandoffResult.Cancelled -> null
                    is ExternalFileHandoffResult.NoCompatibleApplication ->
                        "No installed app can play or display this format."
                    is ExternalFileHandoffResult.Rejected -> result.message
                    is ExternalFileHandoffResult.Unsupported -> result.reason
                }
            }.onFailure { failure ->
                externalOpening = false
                externalError = failure.message ?: "Could not prepare this media file."
            }
        }
    }

    val navigationBlocked = !canConfirmMediaViewerNavigation(
        editing = editing,
        tagSaving = tagSaving,
        activeActionMutations = activeActionMutations,
    )
    LaunchedEffect(navigationRequest?.identity, navigationBlocked) {
        navigationRequest?.takeIf { !navigationBlocked }?.let(onNavigationConfirmed)
    }

    fun openInMediaApp() = handoffToExternalApp(ExternalFileHandoffAction.OpenWith)

    LaunchedEffect(selected, userId, session, services, videoOriginalAttempt) {
        if (!selected.needsVideoOriginalResolution(userId)) return@LaunchedEffect
        videoOriginal = MediaVideoOriginalState.Resolving
        videoOriginal = resolveMediaVideoOriginal(selected, userId) { fileId ->
            services.resolveFilesById(session, userId, listOf(fileId))[fileId]
        }
    }

    LaunchedEffect(
        livePhotoDiscoveryIdentity,
        memoriesLivePhotoCapability,
        platformNativeVideoPlaybackAvailable,
        session,
        services,
        userId,
    ) {
        livePhotoSource = null
        livePhotoCompatibilityRangePlan = null
        motionPlaying = false
        if (
            !selected.shouldDiscoverMemoriesLivePhoto(
                capability = selected.effectiveLivePhotoCapability(memoriesLivePhotoCapability),
                nativePlaybackAvailable = platformNativeVideoPlaybackAvailable,
            )
        ) {
            return@LaunchedEffect
        }
        val resolvedLivePhotoSource = livePhotoLookupOrNull {
            resolveMemoriesLivePhotoSource(
                services = services,
                session = session,
                file = selected,
            )
        }
        livePhotoSource = resolvedLivePhotoSource
        livePhotoCompatibilityRangePlan = resolvedLivePhotoSource?.let { source ->
            livePhotoLookupOrNull {
                resolveMemoriesLivePhotoCompatibilityRangePlan(
                    services = services,
                    session = session,
                    userId = userId,
                    selected = selected,
                    source = source,
                )
            }
        }
    }

    LaunchedEffect(
        sourceLoadIdentity,
        selected.fileId,
        session,
        userId,
        retryKey,
        sourcePlan.previewCandidates,
    ) {
        previewState = MediaPreviewState.Loading
        val loaded = loadFirstUsableMediaPreviewSource(
            candidates = sourcePlan.previewCandidates,
            load = { candidate ->
                loadMediaDisplayPayload(
                    file = candidate,
                    loadCorePreview = {
                        services.loadPreviewCached(session, candidate, width = 1_600, height = 1_600)
                    },
                    loadMemoriesRawRender = {
                        services.loadMemoriesDecodableImageCached(
                            session = session,
                            file = candidate,
                        )
                    },
                    loadNativeRender = {
                        services.loadNativeMediaPreview(
                            session = session,
                            userId = userId,
                            file = candidate,
                            maximumDimension = MAXIMUM_PREVIEW_IMAGE_DIMENSION,
                        )
                    },
                    loadFileRange = { offset, length, expectedEtag ->
                        when {
                            candidate.canUseEmbeddedRafPreview() -> {
                                check(userId.isNotBlank()) {
                                    "The authenticated Files user ID is unavailable."
                                }
                                services.downloadFileRange(
                                    session = session,
                                    userId = userId,
                                    path = candidate.path,
                                    offset = offset,
                                    length = length,
                                    expectedEtag = expectedEtag,
                                )
                            }
                            candidate.canUseEmbeddedRafPreviewFromMemories() ->
                                services.downloadMemoriesFileRange(
                                    session = session,
                                    fileId = requireNotNull(candidate.fileId),
                                    offset = offset,
                                    length = length,
                                    expectedEtag = expectedEtag,
                                    expectedSourceSize = requireNotNull(candidate.size),
                                )
                            else -> error("This media item has no bounded original stream.")
                        }
                    },
                    decode = { payload ->
                        decodePlatformImageSampled(
                            payload.bytes,
                            MAXIMUM_PREVIEW_IMAGE_DIMENSION,
                            payload.kind.orientationPolicy(),
                        )?.image
                    },
                )
            },
        )
        previewState = loaded?.let {
            MediaPreviewState.Ready(it.value, it.source, it.usedFallback, it.payloadKind)
        } ?: MediaPreviewState.Error(
            detail = if (selected.isRawPhoto()) {
                "No server render or embedded camera preview could be loaded or decoded. " +
                    "The RAW original is unchanged."
            } else {
                "No supported preview could be loaded or decoded. The original file is unchanged."
            },
        )
    }

    LaunchedEffect(
        sourceLoadIdentity,
        userId,
        previewState is MediaPreviewState.Ready,
        sourcePlan.fullQualityCandidates,
    ) {
        val qualityCandidates = sourcePlan.fullQualityCandidatesAfterPreview(
            previewReady = previewState is MediaPreviewState.Ready,
        )
        if (qualityCandidates.isEmpty() || fullQualityState !is FullQualityState.Idle) return@LaunchedEffect
        fullQualityState = FullQualityState.Loading
        withFullQualityCancellationRecovery(
            onCancelled = { fullQualityState = FullQualityState.Idle },
        ) {
            val loaded = loadFirstUsableFullResolutionMediaSource(
                candidates = qualityCandidates,
                maximumPayloadBytes = MAX_PHOTO_EDIT_SOURCE_BYTES.toInt(),
                load = { candidate ->
                    try {
                        loadFullResolutionPhotoPayload(
                            original = candidate,
                            loadMemories = { fileId, etag ->
                                val response = services.executeNextcloudApi(
                                    session,
                                    memoriesPhotoDecodableApiRequest(fileId, etag),
                                )
                                check(response.status in 200..299) {
                                    "High-detail Memories render failed (HTTP ${response.status})."
                                }
                                response.body
                            },
                            loadFilesDav = if (candidate.originalAccessAllowed && userId.isNotBlank()) {
                                { path ->
                                    services.downloadFile(
                                        session = session,
                                        userId = userId,
                                        path = path,
                                        maxBytes = MAX_PHOTO_EDIT_SOURCE_BYTES,
                                    ).bytes
                                }
                            } else {
                                null
                            },
                        )
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        val nativeBytes = services.loadNativeMediaPreview(
                            session = session,
                            userId = userId,
                            file = candidate,
                            maximumDimension = MAXIMUM_DISPLAY_IMAGE_DIMENSION,
                        ) ?: throw failure
                        FullResolutionPhotoPayload(
                            bytes = nativeBytes,
                            source = FullResolutionPhotoSource.NativeGenerated,
                        )
                    }
                },
                decode = { payload ->
                    decodePlatformImageSampled(
                        payload.bytes,
                        MAXIMUM_DISPLAY_IMAGE_DIMENSION,
                        payload.source.orientationPolicy(),
                    )?.image
                },
            )
            fullQualityState = loaded?.let {
                FullQualityState.Ready(it.value, it.source, it.usedFallback, it.payloadSource)
            } ?: FullQualityState.Error
        }
    }

    LaunchedEffect(selected.path, previewState, fullQualityState, zoom) {
        onStateObserved(
            mediaViewerStateObservation(
                selectedPath = selected.path,
                previewState = previewState,
                highDetailState = fullQualityState,
                requestedZoom = zoom,
            ),
        )
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(tagging, selected.path, selected.fileId, session, tagReloadKey) {
        if (!tagging) return@LaunchedEffect
        val fileId = selected.fileId ?: return@LaunchedEffect
        tagResolution = null
        tagError = null
        runCatching {
            val allTags = services.listSystemTags(session)
            val response = services.executeNextcloudApi(session, memoriesPhotoTagNamesRequest(fileId))
            val current = parseMemoriesPhotoTagNamesResponse(response, fileId)
            resolveMemoriesPhotoTags(allTags, current.names)
        }.onSuccess { resolution ->
            tagResolution = resolution
            selectedTagIds = resolution.currentTagIds
        }.onFailure { failure ->
            tagError = failure.message ?: "Could not load photo tags."
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ViewerBackground)
            .testTag(MEDIA_VIEWER_ROOT_TEST_TAG)
            .semantics {
                stateDescription = mediaViewerStateObservation(
                    selectedPath = selected.path,
                    previewState = previewState,
                    highDetailState = fullQualityState,
                    requestedZoom = zoom,
                ).readiness.description
            }
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> (!navigationBlocked && canGoPrevious).also {
                            if (it) selectPrevious()
                        }
                        Key.DirectionRight -> (!navigationBlocked && canGoNext).also {
                            if (it) selectNext()
                        }
                        Key.Escape -> if (navigationBlocked) false else true.also { onClose() }
                        else -> false
                    }
                }
            }
            .focusable(),
    ) {
        val readyPreview = previewState as? MediaPreviewState.Ready
        val fullQuality = fullQualityState as? FullQualityState.Ready
        val displayImage = fullQuality?.image ?: readyPreview?.image
        val viewerLayout = remember(sourcePlan.choices.size) {
            resolveMediaViewerLayout(sourcePlan.choices.size)
        }
        val playbackSource = remember(
            playbackFile,
            userId,
            livePhotoSource,
            motionPlaying,
            platformNativeVideoPlaybackAvailable,
        ) {
            playbackFile.nativeVideoPlaybackSource(
                userId = userId,
                nativePlaybackAvailable = platformNativeVideoPlaybackAvailable,
                livePhotoSource = livePhotoSource,
                motionPlaying = motionPlaying,
            )
        }
        // Failures and notices need the header to stay reachable even in immersive mode.
        val showChrome = chromeVisible || nativeVideoFailure != null ||
            previewState is MediaPreviewState.Error
        val mediaCanvasModifier = when (viewerLayout.contentLayout) {
            MediaViewerContentLayout.FullCanvasBehindChrome -> Modifier.fillMaxSize()
        }
        val playbackCompatibilityRangePlan = remember(
            playbackSource,
            playbackFile,
            userId,
            livePhotoCompatibilityRangePlan,
        ) {
            when (playbackSource) {
                is NativeVideoPlaybackSource.MemoriesLivePhoto ->
                    livePhotoCompatibilityRangePlan
                is NativeVideoPlaybackSource.DavFile ->
                    playbackFile.wholeFileVideoCompatibilityPlanOrNull(userId)
                null -> null
            }
        }
        val playbackRangeSource = remember(
            playbackCompatibilityRangePlan,
            compatibilityPlaybackRequested,
            compatibilityPlaybackAttempt,
            session,
            userId,
            services,
        ) {
            playbackCompatibilityRangePlan
                ?.takeIf { compatibilityPlaybackRequested }
                ?.let { plan ->
                    runCatching {
                        plan.openRangeSource(
                            services = services,
                            session = session,
                            userId = userId,
                        )
                    }.getOrNull()
                }
        }
        DisposableEffect(playbackRangeSource) { onDispose { playbackRangeSource?.close() } }
        if (editing && displayImage != null) {
            NextcloudPhotoEditor(
                image = displayImage,
                file = selected,
                services = services,
                session = session,
                userId = userId,
                onCancel = { editing = false },
                navigationRequest = navigationRequest,
                onNavigationConfirmed = onNavigationConfirmed,
                onNavigationCancelled = onNavigationCancelled,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (playbackSource != null && nativeVideoFailure == null) {
            PlatformNativeVideoPlayer(
                session = session,
                userId = userId,
                source = playbackSource,
                rangeSource = playbackRangeSource,
                compatibilityPlaybackRequested = compatibilityPlaybackRequested,
                onPlaybackEnded = {
                    if (playbackSource.restoresStillAfterPlaybackEnds()) {
                        motionPlaying = false
                    }
                },
                onFailure = { failure ->
                    nativeVideoFailure = failure
                },
                onControlsVisibilityChanged = { visible -> chromeVisible = visible },
                modifier = mediaCanvasModifier,
            )
        } else when (val state = previewState) {
            MediaPreviewState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )

            is MediaPreviewState.Ready -> MediaImageCanvas(
                image = displayImage ?: state.image,
                description = selected.name,
                transform = imageTransform,
                onTransform = { imageTransform = it },
                modifier = mediaCanvasModifier,
                controlsVisible = showChrome,
                onTap = { chromeVisible = !showChrome },
                onSwipe = { direction ->
                    if (!navigationBlocked) {
                        when (direction) {
                            MediaSwipeNavigation.Previous -> selectPrevious()
                            MediaSwipeNavigation.Next -> selectNext()
                        }
                    }
                },
            )

            is MediaPreviewState.Error -> PreviewError(
                detail = state.detail,
                onRetry = { retryKey += 1 },
                onOpenExternal = if (playbackFile.hasAuthoritativeMediaDavAccess(userId)) {
                    ::openInMediaApp
                } else {
                    null
                },
                openingExternal = externalOpening,
                externalError = externalError,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 40.dp),
            )
        }

        if (!editing) {
            if (
                livePhotoSource != null &&
                !motionPlaying &&
                showChrome &&
                previewState is MediaPreviewState.Ready &&
                platformNativeVideoPlaybackAvailable
            ) {
                Button(
                    onClick = {
                        nativeVideoFailure = null
                        motionPlaying = true
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
                ) {
                    Icon(NextcloudIcons.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("Play motion", modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (
                selected.mediaAssetFormat() == MediaAssetFormat.Video &&
                !platformNativeVideoPlaybackAvailable &&
                previewState is MediaPreviewState.Ready
            ) {
                Button(
                    onClick = ::openInMediaApp,
                    enabled = !externalOpening && playbackFile.hasAuthoritativeMediaDavAccess(userId),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    if (externalOpening) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(NextcloudIcons.Play, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                    Text(
                        if (externalOpening) "Preparing video..." else "Play video",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                externalError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center).padding(top = 92.dp, start = 32.dp, end = 32.dp),
                    )
                }
            }
            nativeVideoFailure?.let { failure ->
                val motionOnly =
                    playbackSource is NativeVideoPlaybackSource.MemoriesLivePhoto
                val actions = nativeVideoFailureActionPolicy(
                    failure = failure,
                    compatibilityAvailable = playbackCompatibilityRangePlan != null,
                    motionOnly = motionOnly,
                )
                NativeVideoFailureOverlay(
                    failure = failure,
                    motionOnly = motionOnly,
                    showCompatibilityAction = actions.showCompatibilityAction,
                    showExternalAction = actions.showExternalAction,
                    externalActionEnabled =
                        !externalOpening && playbackFile.hasAuthoritativeMediaDavAccess(userId),
                    externalOpening = externalOpening,
                    onCompatibilityPlayback = {
                        compatibilityPlaybackAttempt += 1
                        compatibilityPlaybackRequested = true
                        if (playbackSource is NativeVideoPlaybackSource.MemoriesLivePhoto) {
                            motionPlaying = true
                        }
                        nativeVideoFailure = null
                    },
                    onOpenExternal = ::openInMediaApp,
                    onShowStillPhoto = { motionPlaying = false; nativeVideoFailure = null },
                    modifier = Modifier
                        .align(if (motionOnly) Alignment.BottomCenter else Alignment.Center)
                        .padding(
                            start = if (motionOnly) 16.dp else 32.dp,
                            end = if (motionOnly) 16.dp else 32.dp,
                            bottom = if (motionOnly) 24.dp else 0.dp,
                        ),
                )
            }
            MediaVideoOriginalNotice(
                state = videoOriginal,
                onRetry = { videoOriginalAttempt += 1 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
            )
            MediaViewerChrome(visible = showChrome, modifier = Modifier.align(Alignment.TopCenter)) { ViewerHeader(
                filename = selected.name,
                counter = buildString {
                    append("${selectedIndex + 1} of ${items.size}")
                    val activeSource = fullQuality?.source ?: readyPreview?.source
                    val qualityState = fullQualityState
                    when (qualityState) {
                        FullQualityState.Idle -> {
                            append(" - ")
                            append(
                                activeSource?.let {
                                    conciseMediaDisplaySourceLabel(
                                        selected = sourcePlan.selected,
                                        displayed = it,
                                        highDetail = false,
                                        payloadKind =
                                            readyPreview?.payloadKind
                                                ?: MediaDisplayPayloadKind.ServerPreview,
                                    )
                                } ?: "Preview",
                            )
                        }
                        FullQualityState.Loading ->
                            append(" - Loading high-detail render...")
                        is FullQualityState.Ready -> {
                            append(" - ")
                            append(
                                activeSource?.let {
                                    conciseMediaDisplaySourceLabel(
                                        selected = sourcePlan.selected,
                                        displayed = it,
                                        highDetail = true,
                                        payloadKind = fullQualityMediaPayloadKind(
                                            displayed = qualityState.source,
                                            payloadSource = qualityState.payloadSource,
                                        ),
                                    )
                                } ?: "High detail",
                            )
                        }
                        FullQualityState.Error ->
                            append(" - Preview; high detail unavailable")
                    }
                },
                sourceChoices = sourcePlan.choices,
                selectedSourcePath = selected.path,
                onSelectSource = { choice -> onSelect(choice.file) },
                onEdit = if (
                    canEditMediaPreview(
                        file = selected,
                        payloadKind = readyPreview?.payloadKind,
                        userId = userId,
                        previewSourceFile = readyPreview?.source?.file,
                        highDetailSourceFile = fullQuality?.source?.file,
                    )
                ) {
                    { writeBlockNotice = fileDeletes.writeBlock(selected.path); if (writeBlockNotice == null) editing = true }
                } else {
                    null
                },
                onTags = if (
                    taggingAvailable && selected.fileId != null && selected.isPhotoMedia() &&
                    selected.originalAccessAllowed
                ) {
                    { tagging = true }
                } else {
                    null
                },
                actions = viewerActions,
                onAction = { action ->
                    when (action) {
                        MediaViewerAction.SendCopy ->
                            handoffToExternalApp(ExternalFileHandoffAction.Share)
                        MediaViewerAction.OpenWith -> openInMediaApp()
                        else -> viewerAction = action
                    }
                },
                onClose = onClose,
                layout = viewerLayout,
            ) }

            if (items.size > 1) MediaViewerChrome(showChrome, Modifier.align(Alignment.CenterStart)) {
                ViewerNavigationButton(
                    previous = true,
                    enabled = !navigationBlocked && canGoPrevious,
                    onClick = ::selectPrevious,
                    modifier = Modifier.padding(12.dp),
                )
            }
            if (items.size > 1) MediaViewerChrome(showChrome, Modifier.align(Alignment.CenterEnd)) {
                ViewerNavigationButton(
                    previous = false,
                    enabled = !navigationBlocked && canGoNext,
                    onClick = ::selectNext,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }


    if (tagging) {
        PhotoTagsDialog(
            filename = selected.name,
            state = tagResolution,
            selectedTagIds = selectedTagIds,
            error = tagError,
            saving = tagSaving,
            onToggle = { tag, checked ->
                selectedTagIds = if (checked) selectedTagIds + tag.id else selectedTagIds - tag.id
                tagError = null
            },
            onRetry = { tagReloadKey += 1 },
            onDismiss = { if (!tagSaving) tagging = false },
            onSave = {
                val resolution = tagResolution ?: return@PhotoTagsDialog
                val fileId = selected.fileId ?: return@PhotoTagsDialog
                if (selectedTagIds == resolution.currentTagIds) {
                    tagging = false
                    return@PhotoTagsDialog
                }
                tagSaving = true
                tagError = null
                scope.launch {
                    runCatching {
                        val selectedTags = resolution.availableTags.filter { it.id in selectedTagIds }
                        val update = planMemoriesTagUpdate(fileId, resolution.currentTags, selectedTags)
                        val response = services.executeNextcloudApi(session, update.toNextcloudApiRequest())
                        parseMemoriesTagUpdateResponse(response)
                    }.onSuccess {
                        tagSaving = false
                        tagging = false
                        tagReloadKey += 1
                    }.onFailure { failure ->
                        tagSaving = false
                        tagError = failure.message ?: "Could not save photo tags."
                    }
                }
            },
        )
    }

    writeBlockNotice?.let { block -> MediaWriteBlockedDialog(block) { writeBlockNotice = null } }
    MediaDeleteHost(items, sourceMembers, selected, session, userId, services, fileDeletes, viewerAction == MediaViewerAction.Delete, onClose = { viewerAction = null }) { removed -> viewerAction = null; onItemRemoved(removed) }
    viewerAction?.let { action ->
        MediaViewerActionDialog(
            action = action,
            file = selected,
            session = session, userId = userId,
            services = services, writeFence = fileDeletes,
            sharingCapabilities = sharingCapabilities,
            onDismiss = { if (activeActionMutations == 0) viewerAction = null },
            onSourceRemoved = {
                viewerAction = null
                onSourceRemoved(selected)
            },
            onMutationRunningChanged = onActionMutationRunningChanged,
        )
    }

    externalError?.let { message ->
        AlertDialog(
            onDismissRequest = { externalError = null },
            title = { Text("Could not send media") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { externalError = null }) { Text("Close") }
            },
        )
    }
}

internal fun canConfirmMediaViewerNavigation(
    editing: Boolean,
    tagSaving: Boolean,
    activeActionMutations: Int,
): Boolean {
    require(activeActionMutations >= 0) { "Active media mutation count cannot be negative." }
    return !editing && !tagSaving && activeActionMutations == 0
}

private sealed interface MediaPreviewState {
    data object Loading : MediaPreviewState

    data class Ready(
        val image: ImageBitmap,
        val source: MediaSourceChoice,
        val usedFallback: Boolean,
        val payloadKind: MediaDisplayPayloadKind,
    ) : MediaPreviewState

    data class Error(val detail: String) : MediaPreviewState
}

private sealed interface FullQualityState {
    data object Idle : FullQualityState
    data object Loading : FullQualityState
    data class Ready(
        val image: ImageBitmap,
        val source: MediaSourceChoice,
        val usedFallback: Boolean,
        val payloadSource: FullResolutionPhotoSource,
    ) : FullQualityState
    data object Error : FullQualityState
}

internal suspend fun <T> withFullQualityCancellationRecovery(
    onCancelled: () -> Unit,
    load: suspend () -> T,
): T = try {
    load()
} catch (cancelled: CancellationException) {
    onCancelled()
    throw cancelled
}

internal data class MediaViewerSourceLoadIdentity(
    val selectedPath: String,
    val filesUserId: String,
    val serverUrl: String = "",
    val loginName: String = "",
    val candidates: List<MediaViewerSourceGenerationIdentity> = emptyList(),
)

internal data class MediaViewerSourceGenerationIdentity(
    val path: String,
    val fileId: Long?,
    val etag: String?,
    val size: Long?,
    val lastModified: String?,
    val originalAccessAllowed: Boolean,
    val memoriesRenderAllowed: Boolean,
    val davPathAuthoritative: Boolean,
)

internal fun NextcloudFile.mediaViewerSourceGenerationIdentity(): MediaViewerSourceGenerationIdentity =
    MediaViewerSourceGenerationIdentity(
        path = path,
        fileId = fileId,
        etag = etag,
        size = size,
        lastModified = lastModified,
        originalAccessAllowed = originalAccessAllowed,
        memoriesRenderAllowed = memoriesRenderAllowed,
        davPathAuthoritative = davPathAuthoritative,
    )

enum class MediaViewerReadiness(val description: String) {
    Loading("Loading rendered preview"),
    RenderUnavailable("Rendered preview unavailable"),
    RenderReady("Rendered preview ready"),
    HighDetailLoading("Loading high-detail render"),
    HighDetailReady("High-detail render ready"),
}

data class MediaViewerStateObservation(
    val readiness: MediaViewerReadiness,
    val selectedPath: String,
    val displayedPath: String?,
    val payloadKind: MediaDisplayPayloadKind?,
    val requestedZoom: Float,
)

private fun mediaViewerStateObservation(
    selectedPath: String,
    previewState: MediaPreviewState,
    highDetailState: FullQualityState,
    requestedZoom: Float,
): MediaViewerStateObservation {
    val preview = previewState as? MediaPreviewState.Ready
    val highDetail = highDetailState as? FullQualityState.Ready
    return MediaViewerStateObservation(
        readiness = when {
            highDetail != null -> MediaViewerReadiness.HighDetailReady
            highDetailState is FullQualityState.Loading -> MediaViewerReadiness.HighDetailLoading
            preview != null -> MediaViewerReadiness.RenderReady
            previewState is MediaPreviewState.Error -> MediaViewerReadiness.RenderUnavailable
            else -> MediaViewerReadiness.Loading
        },
        selectedPath = selectedPath,
        displayedPath = highDetail?.source?.file?.path ?: preview?.source?.file?.path,
        payloadKind = when {
            highDetail != null -> fullQualityMediaPayloadKind(
                displayed = highDetail.source,
                payloadSource = highDetail.payloadSource,
            )
            preview != null -> preview.payloadKind
            else -> null
        },
        requestedZoom = requestedZoom,
    )
}

internal const val MEDIA_VIEWER_ROOT_TEST_TAG = "nextcloud-media-viewer"
internal const val MINIMUM_MEDIA_ZOOM = 1f
internal const val MAXIMUM_MEDIA_ZOOM = 5f

internal fun canEditMediaPreview(
    file: NextcloudFile,
    payloadKind: MediaDisplayPayloadKind?,
    userId: String,
    previewSourceFile: NextcloudFile? = null,
    highDetailSourceFile: NextcloudFile? = null,
): Boolean {
    val previewMatchesSelected = previewSourceFile?.mediaViewerSourceGenerationIdentity() ==
        file.mediaViewerSourceGenerationIdentity()
    val highDetailMatchesSelected = highDetailSourceFile?.mediaViewerSourceGenerationIdentity() ==
        file.mediaViewerSourceGenerationIdentity()
    val displayedSourceMatchesSelected = if (highDetailSourceFile != null) {
        highDetailMatchesSelected
    } else {
        previewMatchesSelected
    }
    return payloadKind != null &&
        displayedSourceMatchesSelected &&
        (payloadKind != MediaDisplayPayloadKind.EmbeddedCameraPreview || highDetailMatchesSelected) &&
        userId.isNotBlank() &&
        file.isPhotoMedia() &&
        file.originalAccessAllowed
}

internal fun NextcloudFile.hasAuthoritativeMediaDavAccess(userId: String): Boolean =
    originalAccessAllowed && davPathAuthoritative && userId.isNotBlank()

internal fun NextcloudFile.canUsePlatformNativeVideoPlayback(
    userId: String,
    nativePlaybackAvailable: Boolean,
): Boolean =
    nativePlaybackAvailable &&
        mediaAssetFormat() == MediaAssetFormat.Video &&
        hasAuthoritativeMediaDavAccess(userId)

internal fun NextcloudFile.nativeVideoPlaybackSource(
    userId: String,
    nativePlaybackAvailable: Boolean,
    livePhotoSource: MemoriesLivePhotoSource?,
    motionPlaying: Boolean,
): NativeVideoPlaybackSource? {
    if (!nativePlaybackAvailable) return null
    if (motionPlaying && livePhotoSource != null && livePhotoSource.fileId == fileId) {
        return NativeVideoPlaybackSource.MemoriesLivePhoto(livePhotoSource)
    }
    return takeIf { canUsePlatformNativeVideoPlayback(userId, nativePlaybackAvailable) }
        ?.let { NativeVideoPlaybackSource.DavFile(it) }
}

private const val MAXIMUM_PREVIEW_IMAGE_DIMENSION = 1_600
private const val MAXIMUM_DISPLAY_IMAGE_DIMENSION = 4_096

private val ViewerBackground = Color(0xFF090B0E)
