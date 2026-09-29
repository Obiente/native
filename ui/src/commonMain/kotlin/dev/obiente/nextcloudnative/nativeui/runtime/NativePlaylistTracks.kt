package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.*

internal data class NativePlaylistTracks(
    val title: String,
    val resource: ResourceSpec,
    val records: List<NativeRecord>,
)

/** Display-only expansion of the reviewed complete playlist response; record URIs are never executed. */
internal fun nativePlaylistTracks(
    schema: NativeAppSchema,
    view: ViewSpec,
    records: List<NativeRecord>,
): NativePlaylistTracks? {
    if (schema.app.id != "music" || schema.app.version != "3.2.1") return null
    val action = schema.actions.singleOrNull { it.id == view.sourceActionId } ?: return null
    if (action.binding.method != HttpMethod.GET || action.risk != ActionRisk.readOnly ||
        action.binding.path.removePrefix("/index.php") != "/apps/music/api/playlists/{id}" ||
        "fulltree" !in action.binding.queryParameterNames || action.confidence < Confidence.high ||
        action.evidence.none { it.source == EvidenceSource.verifiedAppPackage }) return null
    val parent = records.singleOrNull()?.takeIf { it.actionBindingProvenanceValid } ?: return null
    val tracks = parent.structuredValues["tracks"] as? NativeStructuredValue.ListValue ?: return null
    if (tracks.omittedItems != 0 || tracks.items.size > 128) return null
    val resource = schema.resources.singleOrNull { it.id == "tracks" } ?: return null
    val ordered = tracks.items.map { item ->
        val entry = item as? NativeStructuredValue.ObjectValue ?: return null
        if (!entry.isCompletePlaylistValue()) return null
        val scalars = entry.entries.mapNotNull { field ->
            (field.value as? NativeStructuredValue.Scalar)?.value?.let { field.key to it }
        }.toMap()
        val id = scalars["id"]?.takeIf { it.length in 1..19 && it.all { character -> character in '0'..'9' } && it.toLongOrNull()?.let { number -> number > 0 } == true } ?: return null
        val index = scalars["index"]?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val nested = entry.entries.filter { it.value !is NativeStructuredValue.Scalar }.associate { it.key to it.value }
        // Playlist rows show their occurrence position, independently of album track metadata.
        val display = mapOf("tracknumber" to (index + 1).toString()) + scalars.filterKeys { it != "tracknumber" }
        // Queue identity includes the occurrence so repeated tracks retain playlist order.
        val record = NativeRecord("${parent.id}:$index:$id",
            mapOf(NATIVE_SYNTHETIC_RESOURCE_FIELD to resource.id), displayValues = display,
            structuredValues = nested, actionSafeIdentity = false)
        if (nativeAudioTrack(resource, record) == null) return null
        index to record
    }.sortedBy { it.first }
    if (ordered.map { it.first } != tracks.items.indices.toList()) return null
    return NativePlaylistTracks(parent.presentationValue("name") ?: parent.presentationValue("title") ?: "Playlist",
        resource, ordered.map { it.second })
}

private fun NativeStructuredValue.isCompletePlaylistValue(): Boolean = when (this) {
    is NativeStructuredValue.Scalar -> true
    is NativeStructuredValue.ListValue -> omittedItems == 0 && items.all { it.isCompletePlaylistValue() }
    is NativeStructuredValue.ObjectValue -> omittedEntries == 0 && entries.map { it.key }.distinct().size == entries.size &&
        entries.all { it.value.isCompletePlaylistValue() }
}

@Composable
internal fun NativePlaylistTrackCollection(
    playlist: NativePlaylistTracks,
    imageLoader: NativeImageLoader?,
    audioPlayer: NativeAudioRecordPlayer?,
    artworkResolver: NativeMediaArtworkResolver?,
) {
    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
            shape = RoundedCornerShape(NextcloudRadii.Card),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.fillMaxWidth().padding(NextcloudSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                Text(playlist.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small)) {
                    Text(if (playlist.records.size == 1) "1 track" else "${playlist.records.size} tracks",
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val firstPlayable = nativeAudioCollectionFirstPlayableRecord(playlist.resource, playlist.records, null)
                    if (audioPlayer != null && firstPlayable != null) {
                        Button(onClick = { audioPlayer.play(playlist.resource, playlist.records, firstPlayable, null) }) {
                            Icon(NextcloudIcons.Play, null, Modifier.size(20.dp))
                            Text("Play all", Modifier.padding(start = NextcloudSpacing.Small))
                        }
                    }
                }
            }
        }
        if (playlist.records.isEmpty()) {
            Text("This playlist has no tracks.", Modifier.padding(NextcloudSpacing.Large))
        } else Box(Modifier.weight(1f)) {
            GenericMediaLibraryCollection(playlist.resource, playlist.records, null, null,
                imageLoader, audioPlayer, artworkResolver, null, false, null)
        }
    }
}
