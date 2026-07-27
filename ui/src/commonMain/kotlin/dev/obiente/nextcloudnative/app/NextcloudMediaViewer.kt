package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 */
@Composable
fun NextcloudMediaViewer(
    media: List<NextcloudFile>,
    selected: NextcloudFile,
    session: NextcloudSession,
    userId: String,
    services: NextcloudPlatformServices,
    taggingAvailable: Boolean,
    sharingCapabilities: NextcloudFileSharingCapabilities,
    onSelect: (NextcloudFile) -> Unit,
    onSourceRemoved: (NextcloudFile) -> Unit,
    onClose: () -> Unit,
    initialZoom: Float = 1f,
    onStateObserved: (MediaViewerStateObservation) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val items = remember(media, selected) {
        if (media.any { it.path == selected.path }) media else listOf(selected)
    }
    val sourcePlan = remember(items, selected) { planMediaSources(items, selected) }
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
    val selectedIndex = items.indexOfFirst { it.path == selected.path }.coerceAtLeast(0)
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
    var zoom by remember(selected.path) {
        mutableStateOf(initialZoom.coerceIn(MINIMUM_MEDIA_ZOOM, MAXIMUM_MEDIA_ZOOM))
    }
    var panOffset by remember(selected.path) { mutableStateOf(Offset.Zero) }
    var editing by remember(selected.path) { mutableStateOf(false) }
    var tagging by remember(selected.path) { mutableStateOf(false) }
    var tagResolution by remember(selected.path) { mutableStateOf<ResolvedMemoriesPhotoTags?>(null) }
    var selectedTagIds by remember(selected.path) { mutableStateOf<Set<Long>>(emptySet()) }
    var tagError by remember(selected.path) { mutableStateOf<String?>(null) }
    var tagSaving by remember(selected.path) { mutableStateOf(false) }
    var tagReloadKey by remember(selected.path) { mutableIntStateOf(0) }
    var externalOpening by remember(selected.path) { mutableStateOf(false) }
    var externalError by remember(selected.path) { mutableStateOf<String?>(null) }
    var nativeVideoError by remember(selected.path) { mutableStateOf<String?>(null) }
    var viewerAction by remember(selected.path) { mutableStateOf<MediaViewerAction?>(null) }
    val scope = rememberCoroutineScope()
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
        if (externalOpening || !selected.hasAuthoritativeMediaDavAccess(userId)) return
        externalOpening = true
        externalError = null
        scope.launch {
            runCatching {
                services.handoffFileToExternalApp(
                    session = session,
                    userId = userId,
                    file = selected,
                    action = action,
                )
            }.onSuccess { result ->
                externalOpening = false
                externalError = when (result) {
                    is ExternalFileHandoffResult.Launched -> null
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

    fun openInMediaApp() = handoffToExternalApp(ExternalFileHandoffAction.OpenWith)

    LaunchedEffect(sourceLoadIdentity, selected.fileId, session, retryKey, sourcePlan.previewCandidates) {
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
                        val fileId = requireNotNull(candidate.fileId) {
                            "The RAW file has no stable server file ID."
                        }
                        val response = services.executeNextcloudApi(
                            session,
                            memoriesPhotoDecodableApiRequest(
                                fileId = fileId,
                                etag = candidate.etag,
                                maximumResponseBytes = MAX_RAW_DISPLAY_PREVIEW_BYTES.toLong(),
                            ),
                        )
                        check(response.status in 200..299) {
                            "The Memories RAW render failed (HTTP ${response.status})."
                        }
                        response.body
                    },
                    loadFileRange = { offset, length, expectedEtag ->
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
        zoom >= FULL_QUALITY_MEDIA_ZOOM_THRESHOLD,
        sourcePlan.fullQualityCandidates,
    ) {
        val qualityCandidates = sourcePlan.fullQualityCandidatesAtZoom(zoom)
        if (qualityCandidates.isEmpty() || fullQualityState !is FullQualityState.Idle) return@LaunchedEffect
        fullQualityState = FullQualityState.Loading
        withFullQualityCancellationRecovery(
            onCancelled = { fullQualityState = FullQualityState.Idle },
        ) {
            val loaded = loadFirstUsableFullResolutionMediaSource(
                candidates = qualityCandidates,
                maximumPayloadBytes = MAX_PHOTO_EDIT_SOURCE_BYTES.toInt(),
                load = { candidate ->
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
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> (!editing && canGoPrevious).also { if (it) selectPrevious() }
                        Key.DirectionRight -> (!editing && canGoNext).also { if (it) selectNext() }
                        Key.Escape -> if (editing) false else true.also { onClose() }
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
        val mediaCanvasModifier = when (viewerLayout.contentLayout) {
            MediaViewerContentLayout.FullCanvasBehindChrome -> Modifier.fillMaxSize()
        }
        if (editing && displayImage != null) {
            NextcloudPhotoEditor(
                image = displayImage,
                file = selected,
                services = services,
                session = session,
                userId = userId,
                onCancel = { editing = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (selected.canUsePlatformNativeVideoPlayback(
                userId = userId,
                nativePlaybackAvailable = platformNativeVideoPlaybackAvailable,
            )
        ) {
            PlatformNativeVideoPlayer(
                session = session,
                userId = userId,
                file = selected,
                onError = { message -> nativeVideoError = message },
                modifier = mediaCanvasModifier,
            )
        } else when (val state = previewState) {
            MediaPreviewState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )

            is MediaPreviewState.Ready -> Image(
                bitmap = displayImage ?: state.image,
                contentDescription = selected.name,
                modifier = mediaCanvasModifier
                    .pointerInput(selected.path) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val nextZoom = (zoom * gestureZoom).coerceIn(
                                MINIMUM_MEDIA_ZOOM,
                                MAXIMUM_MEDIA_ZOOM,
                            )
                            zoom = nextZoom
                            panOffset = if (nextZoom == 1f) Offset.Zero else panOffset + pan
                        }
                    }
                    .pointerInput(selected.path) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (zoom > 1f) {
                                    zoom = 1f
                                    panOffset = Offset.Zero
                                } else {
                                    zoom = 2.5f
                                }
                            },
                        )
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panOffset.x
                        translationY = panOffset.y
                    },
                contentScale = ContentScale.Fit,
            )

            is MediaPreviewState.Error -> PreviewError(
                detail = state.detail,
                onRetry = { retryKey += 1 },
                onOpenExternal = if (selected.hasAuthoritativeMediaDavAccess(userId)) {
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
                selected.mediaAssetFormat() == MediaAssetFormat.Video &&
                !platformNativeVideoPlaybackAvailable &&
                previewState is MediaPreviewState.Ready
            ) {
                Button(
                    onClick = ::openInMediaApp,
                    enabled = !externalOpening && selected.hasAuthoritativeMediaDavAccess(userId),
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
            nativeVideoError?.let { message ->
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = ::openInMediaApp,
                        enabled = !externalOpening && selected.hasAuthoritativeMediaDavAccess(userId),
                    ) {
                        Text(if (externalOpening) "Preparing..." else "Open in another app")
                    }
                }
            }
            ViewerHeader(
                filename = selected.name,
                counter = buildString {
                    append("${selectedIndex + 1} of ${items.size}")
                    append(
                        when (fullQualityState) {
                            FullQualityState.Idle ->
                                if (zoom < FULL_QUALITY_MEDIA_ZOOM_THRESHOLD) " - Preview" else ""
                            FullQualityState.Loading -> " - Loading high-detail render..."
                            is FullQualityState.Ready -> " - High-detail render"
                            FullQualityState.Error -> " - Preview (high-detail render unavailable)"
                        },
                    )
                    val activeSource = fullQuality?.source ?: readyPreview?.source
                    if (activeSource != null) {
                        append(" - ")
                        append(
                            describeMediaDisplaySource(
                                selected = sourcePlan.selected,
                                displayed = activeSource,
                                highDetail = fullQuality != null,
                                payloadKind = when (fullQuality) {
                                    is FullQualityState.Ready -> fullQualityMediaPayloadKind(
                                        displayed = fullQuality.source,
                                        payloadSource = fullQuality.payloadSource,
                                    )
                                    else ->
                                        readyPreview?.payloadKind ?: MediaDisplayPayloadKind.ServerPreview
                                },
                            ),
                        )
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
                    { editing = true }
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
                modifier = Modifier.align(Alignment.TopCenter),
            )

            ViewerNavigationButton(
                previous = true,
                enabled = canGoPrevious,
                onClick = ::selectPrevious,
                modifier = Modifier.align(Alignment.CenterStart).padding(12.dp),
            )
            ViewerNavigationButton(
                previous = false,
                enabled = canGoNext,
                onClick = ::selectNext,
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            )
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

    viewerAction?.let { action ->
        MediaViewerActionDialog(
            action = action,
            file = selected,
            session = session,
            userId = userId,
            services = services,
            sharingCapabilities = sharingCapabilities,
            onDismiss = { viewerAction = null },
            onSourceRemoved = {
                viewerAction = null
                onSourceRemoved(selected)
            },
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

@Composable
private fun ViewerHeader(
    filename: String,
    counter: String,
    sourceChoices: List<MediaSourceChoice>,
    selectedSourcePath: String,
    onSelectSource: (MediaSourceChoice) -> Unit,
    onEdit: (() -> Unit)?,
    onTags: (() -> Unit)?,
    actions: List<MediaViewerAction>,
    onAction: (MediaViewerAction) -> Unit,
    onClose: () -> Unit,
    layout: MediaViewerLayout,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(filename) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.primaryRowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onClose,
                colors = viewerIconButtonColors(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Close preview",
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = filename,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = counter,
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onEdit?.let {
                IconButton(onClick = it, colors = viewerIconButtonColors()) {
                    Icon(NextcloudIcons.Edit, contentDescription = "Edit $filename")
                }
            }
            onTags?.let {
                IconButton(onClick = it, colors = viewerIconButtonColors()) {
                    Icon(NextcloudIcons.Tag, contentDescription = "Edit tags for $filename")
                }
            }
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    colors = viewerIconButtonColors(),
                ) {
                    Icon(NextcloudIcons.More, contentDescription = "More actions for $filename")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(action.label) },
                            onClick = {
                                menuExpanded = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
        if (layout.sourceChoiceLayout == MediaViewerSourceChoiceLayout.SeparateScrollableRow) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(checkNotNull(layout.sourceChoiceRowHeight))
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sourceChoices.forEach { choice ->
                    TextButton(
                        onClick = { onSelectSource(choice) },
                        enabled = choice.file.path != selectedSourcePath,
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = choice.pickerLabel,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal enum class MediaViewerContentLayout {
    FullCanvasBehindChrome,
}

internal enum class MediaViewerSourceChoiceLayout {
    Hidden,
    SeparateScrollableRow,
}

internal data class MediaViewerLayout(
    val contentLayout: MediaViewerContentLayout,
    val sourceChoiceLayout: MediaViewerSourceChoiceLayout,
    val primaryRowHeight: Dp,
    val sourceChoiceRowHeight: Dp?,
) {
    val chromeContentHeight: Dp
        get() = primaryRowHeight + (sourceChoiceRowHeight ?: 0.dp)
}

internal fun resolveMediaViewerLayout(sourceChoiceCount: Int): MediaViewerLayout {
    require(sourceChoiceCount >= 0) { "Source choice count cannot be negative." }
    val showsSourceChoices = sourceChoiceCount > 1
    return MediaViewerLayout(
        contentLayout = MediaViewerContentLayout.FullCanvasBehindChrome,
        sourceChoiceLayout = if (showsSourceChoices) {
            MediaViewerSourceChoiceLayout.SeparateScrollableRow
        } else {
            MediaViewerSourceChoiceLayout.Hidden
        },
        primaryRowHeight = 56.dp,
        sourceChoiceRowHeight = if (showsSourceChoices) 48.dp else null,
    )
}

@Composable
private fun PhotoTagsDialog(
    filename: String,
    state: ResolvedMemoriesPhotoTags?,
    selectedTagIds: Set<Long>,
    error: String?,
    saving: Boolean,
    onToggle: (NextcloudSystemTag, Boolean) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    var search by remember(filename) { mutableStateOf("") }
    val displayedTags = state?.availableTags.orEmpty().filter { tag ->
        tag.userVisible && (search.isBlank() || tag.name.contains(search, ignoreCase = true))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photo tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    filename,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                when {
                    state == null && error == null -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                    state == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(error ?: "Could not load photo tags.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    else -> {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Find a tag") },
                            singleLine = true,
                        )
                        if (state.unresolvedNames.isNotEmpty()) {
                            Text(
                                "${state.unresolvedNames.size} existing tag assignment" +
                                    if (state.unresolvedNames.size == 1) {
                                        " is preserved but cannot be identified safely."
                                    } else {
                                        "s are preserved but cannot be identified safely."
                                    },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (displayedTags.isEmpty()) {
                            Text(
                                if (search.isBlank()) "No visible system tags are available." else "No tags match your search.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                                items(displayedTags, key = NextcloudSystemTag::id) { tag ->
                                    val ambiguous = tag.name in state.ambiguousNames
                                    val enabled = !saving && tag.canAssign && !ambiguous
                                    val checked = tag.id in selectedTagIds
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = if (enabled) {
                                                { value -> onToggle(tag, value) }
                                            } else {
                                                null
                                            },
                                            enabled = enabled,
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tag.name, style = MaterialTheme.typography.bodyLarge)
                                            if (!tag.canAssign || ambiguous) {
                                                Text(
                                                    if (ambiguous) "Duplicate server name · preserved" else "Read only",
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = state != null && !saving && selectedTagIds != state.currentTagIds,
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save")
                }
            }
        },
    )
}

@Composable
private fun ViewerNavigationButton(
    previous: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(52.dp)
            .background(Color.Black.copy(alpha = 0.58f), CircleShape),
        colors = viewerIconButtonColors(),
    ) {
        Icon(
            imageVector = if (previous) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
            contentDescription = if (previous) "Previous media" else "Next media",
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun PreviewError(
    detail: String,
    onRetry: () -> Unit,
    onOpenExternal: (() -> Unit)?,
    openingExternal: Boolean,
    externalError: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Couldn't open this preview",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = detail,
            color = Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Retry",
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        onOpenExternal?.let { open ->
            TextButton(onClick = open, enabled = !openingExternal) {
                if (openingExternal) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(
                    if (openingExternal) "Preparing..." else "Open in another app",
                    modifier = Modifier.padding(start = if (openingExternal) 8.dp else 0.dp),
                )
            }
        }
        externalError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun viewerIconButtonColors() = IconButtonDefaults.iconButtonColors(
    contentColor = Color.White,
    disabledContentColor = Color.White.copy(alpha = 0.24f),
)

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

private const val MAXIMUM_PREVIEW_IMAGE_DIMENSION = 1_600
private const val MAXIMUM_DISPLAY_IMAGE_DIMENSION = 4_096

private val ViewerBackground = Color(0xFF090B0E)
