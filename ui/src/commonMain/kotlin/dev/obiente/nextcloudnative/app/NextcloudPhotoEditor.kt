package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

@Composable
fun NextcloudPhotoEditor(
    image: ImageBitmap,
    file: NextcloudFile,
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    userId: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editHistory by remember(file.path) { mutableStateOf(PhotoEditHistory()) }
    val recipe = editHistory.current
    var exportState by remember(file.path) { mutableStateOf<PhotoEditorExportState>(PhotoEditorExportState.Idle) }
    var sourceState by remember(file.path) { mutableStateOf<PhotoEditorSourceState>(PhotoEditorSourceState.Loading) }
    var recipeState by remember(file.path, file.etag) {
        mutableStateOf<PhotoEditorRecipeState>(PhotoEditorRecipeState.Loading)
    }
    var sidecarSource by remember(file.path, file.fileId, file.etag) {
        mutableStateOf<NextcloudFile?>(null)
    }
    var recipeTouched by remember(file.path, file.etag) { mutableStateOf(false) }
    var savedRecipeBaseline by remember(file.path, file.etag) { mutableStateOf(PhotoEditRecipe()) }
    var confirmDiscard by remember(file.path) { mutableStateOf(false) }
    var exportFormat by remember(file.path) { mutableStateOf(PhotoExportFormat.Jpeg) }
    var exportQuality by remember(file.path) { mutableStateOf(0.92f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file.path, file.fileId, file.etag, file.originalAccessAllowed, session, userId) {
        sourceState = PhotoEditorSourceState.Loading
        sourceState = runCatching {
            val payload = loadFullResolutionPhotoPayload(
                original = file,
                loadMemories = { sourceFileId, etag ->
                    val response = services.executeNextcloudApi(
                        session,
                        memoriesPhotoDecodableApiRequest(sourceFileId, etag),
                    )
                    check(response.status in 200..299) {
                        "HTTP ${response.status}"
                    }
                    response.body
                },
                loadFilesDav = if (file.originalAccessAllowed && userId.isNotBlank()) {
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
            val decoded = decodePlatformImageSampled(payload.bytes, PHOTO_EDITOR_DISPLAY_MAX_DIMENSION)
                ?: error("The full-resolution image format is unsupported.")
            PhotoEditorSourceState.Ready(decoded, payload.source)
        }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                PhotoEditorSourceState.Error(failure.message ?: "Could not load the full-resolution image.")
            },
        )
    }

    LaunchedEffect(file.path, file.fileId, file.etag, session.serverUrl, session.loginName, userId) {
        if (userId.isBlank()) {
            recipeState = PhotoEditorRecipeState.None
            return@LaunchedEffect
        }
        recipeState = PhotoEditorRecipeState.Loading
        val discovery = discoverPhotoEditSidecar(services, session, userId, file)
        val resolved = discovery.sidecar
        sidecarSource = discovery.davSource
        if (
            resolved != null &&
            resolved.freshness == PhotoEditSidecarFreshness.Current &&
            !recipeTouched
        ) {
            editHistory = PhotoEditHistory.from(resolved.sidecar.recipe)
            savedRecipeBaseline = resolved.sidecar.recipe
            recipeState = PhotoEditorRecipeState.Available(resolved, applied = true)
        } else {
            recipeState = resolved?.let {
                PhotoEditorRecipeState.Available(it, applied = false)
            } ?: PhotoEditorRecipeState.None
        }
    }

    fun updateRecipe(updated: PhotoEditRecipe) {
        editHistory = editHistory.commit(updated)
        recipeTouched = true
        (recipeState as? PhotoEditorRecipeState.Available)?.let { saved ->
            recipeState = saved.copy(applied = false)
        }
        exportState = PhotoEditorExportState.Idle
    }

    fun applyHistory(updated: PhotoEditHistory) {
        if (updated == editHistory) return
        editHistory = updated
        recipeTouched = true
        (recipeState as? PhotoEditorRecipeState.Available)?.let { saved ->
            recipeState = saved.copy(applied = false)
        }
        exportState = PhotoEditorExportState.Idle
    }

    val hasUnsavedChanges = recipe != savedRecipeBaseline
    fun requestClose() {
        if (hasUnsavedChanges && !exportState.isSaving) {
            confirmDiscard = true
        } else if (!exportState.isSaving) {
            onCancel()
        }
    }
    PlatformBackHandler(enabled = true, onBack = ::requestClose)

    Column(modifier = modifier.fillMaxSize().background(PhotoEditorBackground)) {
        val editingImage = (sourceState as? PhotoEditorSourceState.Ready)?.source?.image ?: image
        PhotoEditorPreview(
            image = editingImage,
            recipe = recipe,
            contentDescription = "Edited preview of ${file.name}",
            modifier = Modifier.fillMaxWidth().weight(1f).padding(NextcloudSpacing.Medium),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = NextcloudRadii.Large, topEnd = NextcloudRadii.Large),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(NextcloudSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Edit photo", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "The original stays unchanged",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { applyHistory(editHistory.undo()) },
                            enabled = editHistory.canUndo,
                        ) { Text("Undo") }
                        TextButton(
                            onClick = { applyHistory(editHistory.redo()) },
                            enabled = editHistory.canRedo,
                        ) { Text("Redo") }
                        OutlinedButton(
                            onClick = { updateRecipe(PhotoEditRecipe()) },
                            enabled = !recipe.isIdentity,
                        ) {
                            Icon(NextcloudIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Reset", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
                        }
                    }
                }
                when (val state = recipeState) {
                    PhotoEditorRecipeState.Loading -> Text(
                        "Checking for a saved non-destructive recipe...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PhotoEditorRecipeState.None -> Unit
                    is PhotoEditorRecipeState.Available -> {
                        val freshnessMessage = when (state.resolved.freshness) {
                            PhotoEditSidecarFreshness.Current -> "Saved recipe matches this source version."
                            PhotoEditSidecarFreshness.Unversioned ->
                                "Saved recipe has no source version. Review it before exporting."
                            PhotoEditSidecarFreshness.SourceChanged ->
                                "Saved recipe targets an older source version. Review it before exporting."
                        }
                        Text(
                            if (state.applied) {
                                "Restored ${state.resolved.file.name}"
                            } else {
                                freshnessMessage
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (
                                state.resolved.freshness == PhotoEditSidecarFreshness.Current
                            ) {
                                NextcloudTheme.colors.success
                            } else {
                                NextcloudTheme.colors.warning
                            },
                        )
                        if (!state.applied) {
                            OutlinedButton(
                                onClick = {
                                    updateRecipe(state.resolved.sidecar.recipe)
                                    savedRecipeBaseline = state.resolved.sidecar.recipe
                                    recipeTouched = false
                                    recipeState = state.copy(applied = true)
                                },
                            ) {
                                Text("Load saved recipe")
                            }
                        }
                    }
                }
                PhotoTransformControls(editingImage, recipe, ::updateRecipe)
                PhotoAdjustmentControl("Brightness", recipe.adjustments.brightness) { value ->
                    updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(brightness = value)))
                }
                PhotoAdjustmentControl("Contrast", recipe.adjustments.contrast) { value ->
                    updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(contrast = value)))
                }
                PhotoAdjustmentControl(
                    label = "Hue",
                    value = recipe.adjustments.hue,
                    valueRange = -180f..180f,
                    valueText = { value -> "${value.roundToInt()}°" },
                ) { value ->
                    updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(hue = value)))
                }
                PhotoAdjustmentControl("Saturation", recipe.adjustments.saturation) { value ->
                    updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(saturation = value)))
                }
                PhotoAdjustmentControl(
                    label = "Exposure",
                    value = recipe.adjustments.exposure,
                    valueRange = -2f..2f,
                ) { value ->
                    updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(exposure = value)))
                }
                PhotoAdjustmentControl(
                    label = "Warmth",
                    value = recipe.adjustments.warmth,
                    valueRange = 0f..1f,
                ) { value ->
                    updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(warmth = value)))
                }
                Text("Filter", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    PhotoFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = recipe.filter == filter,
                            onClick = { updateRecipe(recipe.copy(filter = filter)) },
                            label = {
                                Text(
                                    when (filter) {
                                        PhotoFilter.None -> "Original"
                                        PhotoFilter.Monochrome -> "Monochrome"
                                        PhotoFilter.Sepia -> "Sepia"
                                    },
                                )
                            },
                        )
                    }
                }
                Text("Export", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                ) {
                    PhotoExportFormat.entries.forEach { format ->
                        FilterChip(
                            selected = exportFormat == format,
                            onClick = { exportFormat = format },
                            label = { Text(format.label) },
                        )
                    }
                }
                PhotoAdjustmentControl(
                    label = "Quality",
                    value = exportQuality,
                    valueRange = 0.5f..1f,
                    valueText = { value -> "${(value * 100).roundToInt()}%" },
                    onValueChange = { exportQuality = it },
                )
                when (val state = exportState) {
                    PhotoEditorExportState.Idle -> Unit
                    PhotoEditorExportState.SavingRecipe -> Text(
                        "Creating edit recipe...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PhotoEditorExportState.SavingCopy -> Text(
                        "Rendering a new full-resolution copy...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is PhotoEditorExportState.SavedRecipe -> Text(
                        "Recipe saved as ${state.path.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NextcloudTheme.colors.success,
                    )
                    is PhotoEditorExportState.SavedCopy -> Text(
                        "New image saved as ${state.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = NextcloudTheme.colors.success,
                    )
                    is PhotoEditorExportState.Error -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when (val state = sourceState) {
                    PhotoEditorSourceState.Loading -> Text(
                        "Loading the full-resolution source for export...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is PhotoEditorSourceState.Error -> Text(
                        "Full-resolution export unavailable: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is PhotoEditorSourceState.Ready -> Text(
                        "Export source: ${state.source.sourceWidth} × ${state.source.sourceHeight} · " +
                            state.origin.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    enabled = !recipe.isIdentity && sidecarSource != null && !exportState.isSaving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = {
                        exportState = PhotoEditorExportState.SavingRecipe
                        scope.launch {
                            val nonce = Random.nextInt().toUInt().toString(16)
                            val recipeToSave = recipe
                            exportState = runCatching {
                                savePhotoEditSidecar(
                                    services,
                                    session,
                                    userId,
                                    requireNotNull(sidecarSource),
                                    recipeToSave,
                                    nonce,
                                )
                            }.fold(
                                onSuccess = { result -> when (result) {
                                    is PhotoEditExportResult.Created -> {
                                        savedRecipeBaseline = recipeToSave
                                        recipeTouched = recipe != savedRecipeBaseline
                                        PhotoEditorExportState.SavedRecipe(result.path)
                                    }
                                    is PhotoEditExportResult.Failed -> PhotoEditorExportState.Error(result.message)
                                } },
                                onFailure = { PhotoEditorExportState.Error(it.message ?: "Could not save the edit recipe.") },
                            )
                        }
                    },
                ) {
                    Text("Save recipe only")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    OutlinedButton(onClick = ::requestClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Cancel")
                    }
                    Button(
                        enabled = !recipe.isIdentity && sourceState is PhotoEditorSourceState.Ready &&
                            file.fileId != null && !exportState.isSaving,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        onClick = {
                            exportState = PhotoEditorExportState.SavingCopy
                            scope.launch {
                                val nonce = Random.nextInt().toUInt().toString(16)
                                val recipeToSave = recipe
                                exportState = runCatching {
                                    val source = requireNotNull(sourceState as? PhotoEditorSourceState.Ready)
                                    val request = createMemoriesPhotoEditRequest(
                                        originalName = file.name,
                                        sourceWidth = source.source.sourceWidth,
                                        sourceHeight = source.source.sourceHeight,
                                        recipe = recipeToSave,
                                        extension = exportFormat.extension,
                                        quality = exportQuality,
                                        copyNonce = nonce,
                                    )
                                    val fileId = requireNotNull(file.fileId)
                                    val response = services.executeNextcloudApi(
                                        session,
                                        memoriesPhotoEditApiRequest(fileId, request),
                                    )
                                    check(response.status in 200..299) {
                                        "Memories could not save the edited copy (HTTP ${response.status})."
                                    }
                                    request.name
                                }.fold(
                                    onSuccess = { name ->
                                        savedRecipeBaseline = recipeToSave
                                        recipeTouched = recipe != savedRecipeBaseline
                                        PhotoEditorExportState.SavedCopy(name)
                                    },
                                    onFailure = { PhotoEditorExportState.Error(it.message ?: "Could not save the edited copy.") },
                                )
                            }
                        },
                    ) {
                        if (exportState.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(NextcloudIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Save copy", modifier = Modifier.padding(start = NextcloudSpacing.XSmall))
                        }
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard photo edits?") },
            text = { Text("Your unsaved adjustments will be lost. The original photo is unchanged.") },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onCancel()
                    },
                ) {
                    Text("Discard")
                }
            },
        )
    }
}

@Composable
fun PhotoEditorPreview(
    image: ImageBitmap,
    recipe: PhotoEditRecipe,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colorFilter = remember(recipe.adjustments, recipe.filter) {
        ColorFilter.colorMatrix(photoEditColorMatrix(recipe))
    }
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val layout = calculatePhotoPreviewLayout(
            imageWidth = image.width,
            imageHeight = image.height,
            canvasWidth = size.width,
            canvasHeight = size.height,
            crop = recipe.crop,
            rotationDegrees = recipe.rotationDegrees,
        )
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            // DrawTransform.rotate defaults to the canvas center. After translating to the
            // viewport center that would apply the center twice and move quarter-turns offscreen.
            rotate(recipe.rotationDegrees.toFloat(), pivot = Offset.Zero)
            scale(
                scaleX = if (recipe.flipHorizontal) -1f else 1f,
                scaleY = if (recipe.flipVertical) -1f else 1f,
                pivot = Offset.Zero,
            )
        }) {
            drawImage(
                image = image,
                srcOffset = layout.sourceOffset,
                srcSize = layout.sourceSize,
                dstOffset = IntOffset(-layout.destinationSize.width / 2, -layout.destinationSize.height / 2),
                dstSize = layout.destinationSize,
                colorFilter = colorFilter,
            )
        }
    }
}

data class PhotoPreviewLayout(
    val sourceOffset: IntOffset,
    val sourceSize: IntSize,
    val destinationSize: IntSize,
)

internal fun calculatePhotoPreviewLayout(
    imageWidth: Int,
    imageHeight: Int,
    canvasWidth: Float,
    canvasHeight: Float,
    crop: NormalizedPhotoCrop,
    rotationDegrees: Int,
): PhotoPreviewLayout {
    require(imageWidth > 0 && imageHeight > 0)
    require(canvasWidth.isFinite() && canvasWidth > 0f && canvasHeight.isFinite() && canvasHeight > 0f)
    require(rotationDegrees in setOf(0, 90, 180, 270))
    val sourceOffset = IntOffset(
        (imageWidth * crop.left).roundToInt().coerceIn(0, imageWidth - 1),
        (imageHeight * crop.top).roundToInt().coerceIn(0, imageHeight - 1),
    )
    val sourceSize = IntSize(
        (imageWidth * crop.width).roundToInt().coerceIn(1, imageWidth - sourceOffset.x),
        (imageHeight * crop.height).roundToInt().coerceIn(1, imageHeight - sourceOffset.y),
    )
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val rotatedWidth = if (quarterTurn) sourceSize.height else sourceSize.width
    val rotatedHeight = if (quarterTurn) sourceSize.width else sourceSize.height
    val fit = min(canvasWidth / rotatedWidth, canvasHeight / rotatedHeight)
    return PhotoPreviewLayout(
        sourceOffset = sourceOffset,
        sourceSize = sourceSize,
        destinationSize = IntSize(
            (sourceSize.width * fit).roundToInt().coerceAtLeast(1),
            (sourceSize.height * fit).roundToInt().coerceAtLeast(1),
        ),
    )
}

@Composable
private fun PhotoTransformControls(
    image: ImageBitmap,
    recipe: PhotoEditRecipe,
    onChange: (PhotoEditRecipe) -> Unit,
) {
    val sourceAspect = image.width.toFloat() / image.height.toFloat()
    val quarterTurn = recipe.rotationDegrees == 90 || recipe.rotationDegrees == 270
    val displayedCropAspect = cropAspect(recipe.crop, sourceAspect).let { if (quarterTurn) 1f / it else it }
    fun sourceCropAspect(displayAspect: Float): Float = if (quarterTurn) 1f / displayAspect else displayAspect
    Column(verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
        ) {
            OutlinedButton(onClick = { onChange(recipe.rotateCounterClockwise()) }) { Text("Rotate left") }
            OutlinedButton(onClick = { onChange(recipe.rotateClockwise()) }) { Text("Rotate right") }
            FilterChip(
                selected = recipe.flipHorizontal,
                onClick = { onChange(recipe.toggleHorizontalFlip()) },
                label = { Text("Flip H") },
            )
            FilterChip(
                selected = recipe.flipVertical,
                onClick = { onChange(recipe.toggleVerticalFlip()) },
                label = { Text("Flip V") },
            )
            PhotoCropChip("Full", recipe.crop == NormalizedPhotoCrop.Full) {
                onChange(recipe.copy(crop = NormalizedPhotoCrop.Full))
            }
            listOf("Square" to 1f, "4:3" to 4f / 3f, "3:2" to 3f / 2f, "16:9" to 16f / 9f)
                .forEach { (label, aspect) ->
                    PhotoCropChip(
                        label,
                        recipe.crop != NormalizedPhotoCrop.Full && displayedCropAspect.isNear(aspect),
                    ) {
                        onChange(
                            recipe.copy(
                                crop = NormalizedPhotoCrop.centered(
                                    sourceCropAspect(aspect),
                                    sourceAspect,
                                ),
                            ),
                        )
                    }
                }
        }
        if (recipe.crop != NormalizedPhotoCrop.Full) {
            PhotoAdjustmentControl(
                label = "Crop horizontal position",
                value = recipe.crop.centerX,
                valueRange = 0f..1f,
                valueText = { value -> "${(value * 100).roundToInt()}%" },
            ) { value ->
                onChange(recipe.copy(crop = recipe.crop.reposition(value, recipe.crop.centerY)))
            }
            PhotoAdjustmentControl(
                label = "Crop vertical position",
                value = recipe.crop.centerY,
                valueRange = 0f..1f,
                valueText = { value -> "${(value * 100).roundToInt()}%" },
            ) { value ->
                onChange(recipe.copy(crop = recipe.crop.reposition(recipe.crop.centerX, value)))
            }
        }
    }
}

@Composable
private fun PhotoCropChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun PhotoAdjustmentControl(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    valueText: (Float) -> String = { current -> "${(current * 100).roundToInt()}" },
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                valueText(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

internal fun photoEditColorMatrix(adjustments: PhotoAdjustments): ColorMatrix =
    photoEditColorMatrix(PhotoEditRecipe(adjustments = adjustments))

internal fun photoEditColorMatrix(recipe: PhotoEditRecipe): ColorMatrix {
    val adjustments = recipe.adjustments
    val hueRadians = ((adjustments.hue + 360f) % 360f) * (kotlin.math.PI.toFloat() / 180f)
    val saturation = 2f.pow(adjustments.saturation)
    val exposure = 2f.pow(adjustments.exposure)
    val vsu = exposure * saturation * kotlin.math.cos(hueRadians)
    val vsw = exposure * saturation * kotlin.math.sin(hueRadians)
    val contrast = (1f + adjustments.contrast).pow(2)
    val warmth = adjustments.warmth
    val redWarmth = 1f + (warmth * 0.2f)
    val greenWarmth = 1f + (warmth * 0.04f)
    val blueWarmth = 1f - (warmth * 0.2f)
    val offset = (0.5f * (1f - contrast) + adjustments.brightness) * 255f
    val base = floatArrayOf(
            (0.299f * exposure + 0.701f * vsu + 0.167f * vsw) * contrast * redWarmth,
            (0.587f * exposure - 0.587f * vsu + 0.330f * vsw) * contrast * redWarmth,
            (0.114f * exposure - 0.114f * vsu - 0.497f * vsw) * contrast * redWarmth,
            0f,
            offset,
            (0.299f * exposure - 0.299f * vsu - 0.328f * vsw) * contrast * greenWarmth,
            (0.587f * exposure + 0.413f * vsu + 0.035f * vsw) * contrast * greenWarmth,
            (0.114f * exposure - 0.114f * vsu + 0.293f * vsw) * contrast * greenWarmth,
            0f,
            offset,
            (0.299f * exposure - 0.300f * vsu + 1.250f * vsw) * contrast * blueWarmth,
            (0.587f * exposure - 0.586f * vsu - 1.050f * vsw) * contrast * blueWarmth,
            (0.114f * exposure + 0.886f * vsu - 0.200f * vsw) * contrast * blueWarmth,
            0f,
            offset,
            0f, 0f, 0f, 1f, 0f,
        )
    val filter = when (recipe.filter) {
        PhotoFilter.None -> null
        PhotoFilter.Monochrome -> floatArrayOf(
            0.2126f, 0.7152f, 0.0722f,
            0.2126f, 0.7152f, 0.0722f,
            0.2126f, 0.7152f, 0.0722f,
        )
        PhotoFilter.Sepia -> floatArrayOf(
            0.393f, 0.769f, 0.189f,
            0.349f, 0.686f, 0.168f,
            0.272f, 0.534f, 0.131f,
        )
    }
    return ColorMatrix(filter?.let { composePhotoRgbFilter(base, it) } ?: base)
}

private fun composePhotoRgbFilter(base: FloatArray, filter: FloatArray): FloatArray {
    require(base.size == 20 && filter.size == 9)
    val output = base.copyOf()
    for (row in 0..2) {
        for (column in 0..4) {
            output[row * 5 + column] = (0..2).sumOf { sourceRow ->
                (filter[row * 3 + sourceRow] * base[sourceRow * 5 + column]).toDouble()
            }.toFloat()
        }
    }
    return output
}

private fun cropAspect(crop: NormalizedPhotoCrop, sourceAspect: Float): Float =
    sourceAspect * crop.width / crop.height

private fun Float.isNear(other: Float): Boolean = kotlin.math.abs(this - other) < 0.01f

private sealed interface PhotoEditorExportState {
    data object Idle : PhotoEditorExportState
    data object SavingRecipe : PhotoEditorExportState
    data object SavingCopy : PhotoEditorExportState
    data class SavedRecipe(val path: String) : PhotoEditorExportState
    data class SavedCopy(val name: String) : PhotoEditorExportState
    data class Error(val message: String) : PhotoEditorExportState
}

private val PhotoEditorExportState.isSaving: Boolean
    get() = this is PhotoEditorExportState.SavingRecipe || this is PhotoEditorExportState.SavingCopy

private sealed interface PhotoEditorSourceState {
    data object Loading : PhotoEditorSourceState
    data class Ready(
        val source: PlatformDecodedImage,
        val origin: FullResolutionPhotoSource,
    ) : PhotoEditorSourceState
    data class Error(val message: String) : PhotoEditorSourceState
}

private sealed interface PhotoEditorRecipeState {
    data object Loading : PhotoEditorRecipeState
    data object None : PhotoEditorRecipeState
    data class Available(
        val resolved: ResolvedPhotoEditSidecar,
        val applied: Boolean,
    ) : PhotoEditorRecipeState
}

private const val PHOTO_EDITOR_DISPLAY_MAX_DIMENSION = 4_096
private val PhotoEditorBackground = Color(0xFF090B0E)
