package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * Where the media viewer reads a video's original bytes from.
 *
 * Memories timeline, folder, collection, and people records carry a synthetic gallery path and
 * are preview-only. Playing or handing off a video needs the authoritative Files record for the
 * same server file ID, which the account must be able to read through DAV.
 */
internal sealed interface MediaVideoOriginalState {
    /** Not a video, or the selected record is already an authoritative Files record. */
    data object Direct : MediaVideoOriginalState

    data object Resolving : MediaVideoOriginalState

    data class Resolved(val file: NextcloudFile) : MediaVideoOriginalState

    /** The account has no readable Files record for this ID, such as an item in another user's album. */
    data object PreviewOnly : MediaVideoOriginalState

    /** The lookup itself failed. Retrying is a read and is always safe. */
    data object LookupFailed : MediaVideoOriginalState
}

/** A video record without Files access, such as a Memories gallery item. */
internal fun NextcloudFile.isPreviewOnlyVideo(userId: String): Boolean =
    !isDirectory && mediaAssetFormat() == MediaAssetFormat.Video && !hasAuthoritativeMediaDavAccess(userId)

internal fun NextcloudFile.needsVideoOriginalResolution(userId: String): Boolean =
    isPreviewOnlyVideo(userId) && (fileId ?: 0L) > 0L && userId.isNotBlank()

/** What the viewer shows before any lookup: a record that cannot be resolved stays preview-only. */
internal fun NextcloudFile.initialVideoOriginalState(userId: String): MediaVideoOriginalState = when {
    needsVideoOriginalResolution(userId) -> MediaVideoOriginalState.Resolving
    isPreviewOnlyVideo(userId) -> MediaVideoOriginalState.PreviewOnly
    else -> MediaVideoOriginalState.Direct
}

/**
 * Accepts [candidate] only when it is the same server file, is still a video, and is readable
 * through the account's Files DAV tree. The DAV record is the current generation, so its ETag and
 * size replace the gallery index values; presentation metadata the DAV record lacks is kept.
 */
internal fun authoritativeVideoOriginalOrNull(
    selected: NextcloudFile,
    candidate: NextcloudFile?,
    userId: String,
): NextcloudFile? {
    if (candidate == null || candidate.isDirectory) return null
    if (selected.fileId == null || candidate.fileId != selected.fileId) return null
    if (!candidate.hasAuthoritativeMediaDavAccess(userId)) return null
    if (!candidate.path.isSafeDavRelativePath()) return null
    if (candidate.mediaAssetFormat() != MediaAssetFormat.Video) return null
    return candidate.copy(
        mediaWidth = candidate.mediaWidth ?: selected.mediaWidth,
        mediaHeight = candidate.mediaHeight ?: selected.mediaHeight,
        capturedAtEpochSeconds = candidate.capturedAtEpochSeconds ?: selected.capturedAtEpochSeconds,
        mediaDurationSeconds = candidate.mediaDurationSeconds ?: selected.mediaDurationSeconds,
    )
}

internal suspend fun resolveMediaVideoOriginal(
    selected: NextcloudFile,
    userId: String,
    lookup: suspend (fileId: Long) -> NextcloudFile?,
): MediaVideoOriginalState {
    if (!selected.needsVideoOriginalResolution(userId)) return selected.initialVideoOriginalState(userId)
    val candidate = try {
        lookup(requireNotNull(selected.fileId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return MediaVideoOriginalState.LookupFailed
    }
    return authoritativeVideoOriginalOrNull(selected, candidate, userId)
        ?.let(MediaVideoOriginalState::Resolved)
        ?: MediaVideoOriginalState.PreviewOnly
}

/** The record playback and external handoff use: the resolved original when one exists. */
internal fun MediaVideoOriginalState.playbackFileFor(selected: NextcloudFile): NextcloudFile =
    (this as? MediaVideoOriginalState.Resolved)?.file ?: selected

@Composable
internal fun MediaVideoOriginalNotice(
    state: MediaVideoOriginalState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (state) {
        MediaVideoOriginalState.Direct, is MediaVideoOriginalState.Resolved -> return
        MediaVideoOriginalState.Resolving -> "Preparing video..."
        MediaVideoOriginalState.PreviewOnly ->
            "Only a preview is available. This video is not in your Files."
        MediaVideoOriginalState.LookupFailed -> "Couldn't find this video's original file."
    }
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = Color(0xE625232B),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state == MediaVideoOriginalState.Resolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            if (state == MediaVideoOriginalState.LookupFailed) {
                TextButton(onClick = onRetry) { Text("Retry", color = Color(0xFFD2BAFF)) }
            }
        }
    }
}
