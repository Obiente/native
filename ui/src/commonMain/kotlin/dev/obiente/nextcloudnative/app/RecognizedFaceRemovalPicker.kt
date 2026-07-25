package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.app.design.NextcloudTheme
import kotlin.math.min

/**
 * Reusable native face picker. The rectangle makes the selected detection explicit when a source
 * photo contains multiple people. Selection only emits a reviewed [PeopleActionPlan]; it never
 * executes the Recognize DAV delete.
 */
@Composable
internal fun RecognizedFaceRemovalPicker(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    person: PersonMediaReference,
    personDisplayName: String,
    faces: List<RecognizedFaceMedia>,
    support: PeopleActionSupport,
    loadingMore: Boolean,
    canLoadMore: Boolean,
    loadMoreError: String?,
    onLoadMore: () -> Unit,
    onPlanSelected: (PeopleActionPlan) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedDetectionId by remember(person) { mutableStateOf<Long?>(null) }
    val gridState = rememberLazyGridState()
    val plans = remember(faces, person, personDisplayName, support) {
        faces.associate { face ->
            face.detectionId to planRemoveRecognizedFace(
                media = face,
                person = person,
                personDisplayName = personDisplayName,
                support = support,
            )
        }
    }
    val selectedPlan = selectedDetectionId?.let { detectionId ->
        val selectedFace = recognizedFaceByDetectionId(faces, detectionId)
        plans[selectedFace.detectionId]
    }
    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = NextcloudTheme.colors.appTile) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = NextcloudSpacing.Large,
                    vertical = NextcloudSpacing.Medium,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
            ) {
                Icon(
                    NextcloudIcons.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Choose the exact face", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The source photo stays in Files. Only this Recognize assignment is removed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        selectedPlan?.let { plan ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Small),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(NextcloudRadii.Medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                ) {
                    Text(
                        "Face ${selectedDetectionId} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        enabled = plan.enabled,
                        onClick = { onPlanSelected(plan) },
                    ) {
                        Text("Review removal")
                    }
                }
            }
        }
        if (faces.isEmpty() && !loadingMore && !canLoadMore && loadMoreError == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No actionable face detections were returned.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val disabledReason = plans.values.firstOrNull { !it.enabled }?.disabledReason
            disabledReason?.let { reason ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                ) {
                    Text(
                        reason,
                        modifier = Modifier.padding(NextcloudSpacing.Medium),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(NextcloudSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
            ) {
                items(faces, key = RecognizedFaceMedia::detectionId) { face ->
                    val plan = requireNotNull(plans[face.detectionId])
                    RecognizedFaceTile(
                        services = services,
                        session = session,
                        face = face,
                        enabled = plan.enabled,
                        selected = selectedDetectionId == face.detectionId,
                        onClick = { selectedDetectionId = face.detectionId },
                    )
                }
                loadMoreItem(
                    loadingMore = loadingMore,
                    canLoadMore = canLoadMore,
                    error = loadMoreError,
                    onLoadMore = onLoadMore,
                )
            }
        }
    }
}

@Composable
private fun RecognizedFaceTile(
    services: NextcloudPlatformServices,
    session: NextcloudSession,
    face: RecognizedFaceMedia,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var image by remember(face.detectionId, face.file.etag) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(face.detectionId, face.file.etag) {
        image = runCatching {
            decodePlatformImage(
                services.loadPreviewCached(
                    session = session,
                    file = face.file,
                    width = 512,
                    height = 512,
                ),
                EncodedImageOrientationPolicy.PixelsAlreadyUpright,
            )
        }.getOrNull()
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else NextcloudTheme.colors.appTile,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                image?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = face.file.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    val outlineColor = MaterialTheme.colorScheme.primary
                    FaceRectangleOverlay(
                        geometry = nativeFaceOutlineGeometryOrNull(
                            rectangle = face.rectangle,
                            sourceWidth = face.sourceWidth,
                            sourceHeight = face.sourceHeight,
                        ),
                        color = outlineColor,
                    )
                } ?: Icon(
                    NextcloudIcons.Image,
                    contentDescription = face.file.name,
                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        if (selected) "Selected" else "Face",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                face.file.name,
                modifier = Modifier.padding(NextcloudSpacing.Small),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun FaceRectangleOverlay(
    geometry: NativeFaceOutlineGeometry?,
    color: Color,
) {
    if (geometry == null) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = min(
            size.width / geometry.sourceWidth.toFloat(),
            size.height / geometry.sourceHeight.toFloat(),
        )
        val displayedWidth = geometry.sourceWidth * scale
        val displayedHeight = geometry.sourceHeight * scale
        val offsetX = (size.width - displayedWidth) / 2f
        val offsetY = (size.height - displayedHeight) / 2f
        drawRect(
            color = color,
            topLeft = Offset(
                x = offsetX + geometry.rectangle.x * displayedWidth,
                y = offsetY + geometry.rectangle.y * displayedHeight,
            ),
            size = Size(
                width = geometry.rectangle.width * displayedWidth,
                height = geometry.rectangle.height * displayedHeight,
            ),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}
