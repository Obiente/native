package dev.obiente.nextcloudnative.nativeui.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.obiente.nextcloudnative.app.design.NextcloudIcons
import dev.obiente.nextcloudnative.app.design.NextcloudRadii
import dev.obiente.nextcloudnative.app.design.NextcloudSpacing
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.EvidenceSource
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec

internal enum class NativeMusicSectionKind {
    Albums,
    Artists,
    Tracks,
    Folders,
    Playlists,
    Podcasts,
    Radio,
    Genres,
    Search,
    Settings,
    Other,
}

internal enum class NativeMusicWorkspaceWidthClass {
    Compact,
    Medium,
    Expanded,
}

internal enum class NativeMusicNavigationPlacement {
    TabStrip,
    Rail,
    Sidebar,
}

internal data class NativeMusicWorkspaceDestination(
    val viewId: String,
    val label: String,
    val resourceId: String,
    val pathParameterValues: Map<String, String>,
    val kind: NativeMusicSectionKind,
)

internal data class NativeMusicWorkspaceIntent(
    val destinations: List<NativeMusicWorkspaceDestination>,
    val activeDestination: NativeMusicWorkspaceDestination,
    val placement: NativeMusicNavigationPlacement,
) {
    init {
        require(destinations.isNotEmpty()) { "A music workspace needs at least one destination." }
        require(destinations.map { it.viewId }.distinct().size == destinations.size) {
            "Music workspace destinations must have unique view ids."
        }
        require(activeDestination in destinations) {
            "The selected music destination must be available."
        }
    }
}

/**
 * The complete state change for choosing a root Music destination.
 *
 * Contextual album, artist, and track bindings must be cleared when moving back to a root library
 * section. Returning this data as one value prevents host state from partially retaining a child
 * selection while rendering another collection.
 */
internal data class NativeMusicRootSelection(
    val viewId: String,
    val pathParameterValues: Map<String, String>,
    val navigationHistoryViewIds: List<String> = emptyList(),
    val selectedRecord: NativeRecord? = null,
    val selectedRecordResourceId: String? = null,
)

internal fun selectNativeMusicRoot(
    destination: NativeMusicWorkspaceDestination,
): NativeMusicRootSelection = NativeMusicRootSelection(
    viewId = destination.viewId,
    pathParameterValues = destination.pathParameterValues,
)

/**
 * Plans a native media-library workspace from declared collection shape.
 *
 * The planner deliberately ignores the app id. A workspace is enabled only when at least two
 * core library collections are independently declared, or when a track collection is accompanied
 * by two other media collections. Non-media destinations remain reachable after opt-in.
 */
internal fun planNativeMusicWorkspace(
    destinations: List<Pair<DynamicNavigationDestination, ViewSpec>>,
    selectedViewId: String,
    widthClass: NativeMusicWorkspaceWidthClass,
): NativeMusicWorkspaceIntent? {
    val mapped = destinations
        .distinctBy { (_, view) -> view.id }
        .map { (destination, view) ->
            NativeMusicWorkspaceDestination(
                viewId = view.id,
                label = (destination.label.takeIf(String::isNotBlank) ?: view.title)
                    .removeMusicApiPrefix(),
                resourceId = destination.resourceId,
                pathParameterValues = destination.pathParameterValues,
                kind = nativeMusicSectionKind(destination, view),
            )
        }
    val mediaDestinations = destinations.mapNotNull { (destination, view) ->
        nativeMusicSectionKind(destination, view)
            .takeIf { view.component == NativeComponent.mediaLibrary && it.isMediaLibrarySection() }
    }
    val coreSectionCount = mediaDestinations
        .filter { it in CORE_MUSIC_SECTIONS }
        .distinct()
        .size
    val supportingSectionCount = mediaDestinations
        .filter { it in SUPPORTING_MUSIC_SECTIONS }
        .distinct()
        .size
    val hasTrackCollection = NativeMusicSectionKind.Tracks in mediaDestinations
    if (coreSectionCount < 2 && !(hasTrackCollection && supportingSectionCount >= 2)) return null

    val sorted = mapped.sortedWith(
        compareBy<NativeMusicWorkspaceDestination>(
            { MUSIC_SECTION_ORDER.indexOf(it.kind).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE },
            { it.label.lowercase() },
        ),
    )
    val active = sorted.firstOrNull { it.viewId == selectedViewId } ?: sorted.firstOrNull() ?: return null
    return NativeMusicWorkspaceIntent(
        destinations = sorted,
        activeDestination = active,
        placement = when (widthClass) {
            NativeMusicWorkspaceWidthClass.Compact -> NativeMusicNavigationPlacement.TabStrip
            NativeMusicWorkspaceWidthClass.Medium -> NativeMusicNavigationPlacement.Rail
            NativeMusicWorkspaceWidthClass.Expanded -> NativeMusicNavigationPlacement.Sidebar
        },
    )
}

/**
 * Keeps the library navigation visible while a person is inspecting a child collection.
 *
 * A selected album or artist can navigate to a contextual track view that is not itself a root
 * destination. In that case the most recent root destination remains the active library section
 * on compact tabs, medium rails, and expanded sidebars.
 */
internal fun nativeMusicActiveNavigationViewId(
    destinations: List<Pair<DynamicNavigationDestination, ViewSpec>>,
    selectedViewId: String,
    navigationHistoryViewIds: List<String>,
): String {
    val rootViewIds = destinations.mapTo(hashSetOf()) { (_, view) -> view.id }
    return selectedViewId.takeIf(rootViewIds::contains)
        ?: navigationHistoryViewIds.asReversed().firstOrNull(rootViewIds::contains)
        ?: selectedViewId
}

internal fun preferredNativeMusicLandingViewId(
    destinations: List<DynamicNavigationDestination>,
    schema: NativeAppSchema,
): String? {
    val paired = destinations.mapNotNull { destination ->
        schema.views.firstOrNull { view -> view.id == destination.layoutId }
            ?.let { view -> destination to view }
    }
    val intent = planNativeMusicWorkspace(
        destinations = paired,
        selectedViewId = "",
        widthClass = NativeMusicWorkspaceWidthClass.Compact,
    ) ?: return null
    val mediaLibraryViewIds = paired
        .filter { (_, view) -> view.component == NativeComponent.mediaLibrary }
        .mapTo(hashSetOf()) { (_, view) -> view.id }
    return intent.destinations.firstOrNull { destination ->
        destination.viewId in mediaLibraryViewIds &&
            destination.kind in PREFERRED_MUSIC_LANDING_SECTIONS
    }?.viewId
}

internal fun nativeMusicWorkspaceWidthClass(
    widthDp: Float,
    heightDp: Float,
): NativeMusicWorkspaceWidthClass = when {
    widthDp < 700f || heightDp < 420f -> NativeMusicWorkspaceWidthClass.Compact
    widthDp < 1_000f -> NativeMusicWorkspaceWidthClass.Medium
    else -> NativeMusicWorkspaceWidthClass.Expanded
}

@Composable
internal fun NativeMusicAdaptiveNavigationLayout(
    intent: NativeMusicWorkspaceIntent,
    onDestinationSelected: (NativeMusicWorkspaceDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val latestContent = rememberUpdatedState(content)
    val movableContent = remember {
        movableContentOf {
            latestContent.value()
        }
    }
    when (intent.placement) {
        NativeMusicNavigationPlacement.TabStrip -> Column(modifier.fillMaxSize()) {
            NativeMusicTabStrip(intent, onDestinationSelected)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                movableContent()
            }
        }
        NativeMusicNavigationPlacement.Rail -> Row(modifier.fillMaxSize()) {
            NativeMusicRail(intent, onDestinationSelected)
            Box(Modifier.fillMaxHeight().weight(1f)) {
                movableContent()
            }
        }
        NativeMusicNavigationPlacement.Sidebar -> Row(modifier.fillMaxSize()) {
            NativeMusicSidebar(intent, onDestinationSelected)
            Box(Modifier.fillMaxHeight().weight(1f)) {
                movableContent()
            }
        }
    }
}

@Composable
private fun NativeMusicTabStrip(
    intent: NativeMusicWorkspaceIntent,
    onDestinationSelected: (NativeMusicWorkspaceDestination) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .selectableGroup(),
            contentPadding = PaddingValues(
                horizontal = NextcloudSpacing.Medium,
            ),
            horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            items(intent.destinations, key = NativeMusicWorkspaceDestination::viewId) { destination ->
                val selected = destination == intent.activeDestination
                Column(
                    modifier = Modifier
                        .widthIn(min = 88.dp)
                        .height(56.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onDestinationSelected(destination) },
                        )
                        .padding(horizontal = NextcloudSpacing.Medium),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Small),
                    ) {
                        Icon(
                            nativeMusicSectionIcon(destination.kind),
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            ),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun NativeMusicRail(
    intent: NativeMusicWorkspaceIntent,
    onDestinationSelected: (NativeMusicWorkspaceDestination) -> Unit,
) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(104.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(
                    horizontal = NextcloudSpacing.Small,
                    vertical = NextcloudSpacing.Medium,
                ),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            intent.destinations.forEach { destination ->
                NativeMusicRailDestination(
                    destination = destination,
                    selected = destination == intent.activeDestination,
                    onClick = { onDestinationSelected(destination) },
                )
            }
        }
        VerticalDivider()
    }
}

@Composable
private fun NativeMusicRailDestination(
    destination: NativeMusicWorkspaceDestination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            ),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = RoundedCornerShape(NextcloudRadii.Medium),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = NextcloudSpacing.XSmall,
                vertical = NextcloudSpacing.Small,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Icon(
                nativeMusicSectionIcon(destination.kind),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                destination.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NativeMusicSidebar(
    intent: NativeMusicWorkspaceIntent,
    onDestinationSelected: (NativeMusicWorkspaceDestination) -> Unit,
) {
    Row(Modifier.fillMaxHeight()) {
        Column(
            modifier = Modifier
                .width(224.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(NextcloudSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(NextcloudSpacing.XSmall),
        ) {
            Text(
                "Your library",
                modifier = Modifier.padding(
                    horizontal = NextcloudSpacing.Medium,
                    vertical = NextcloudSpacing.Small,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            intent.destinations.forEach { destination ->
                val selected = destination == intent.activeDestination
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onDestinationSelected(destination) },
                    ),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(NextcloudRadii.Medium),
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = NextcloudSpacing.Medium,
                            vertical = NextcloudSpacing.Medium,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NextcloudSpacing.Medium),
                    ) {
                        Icon(
                            nativeMusicSectionIcon(destination.kind),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            destination.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        VerticalDivider()
    }
}

private fun nativeMusicSectionKind(
    destination: DynamicNavigationDestination,
    view: ViewSpec,
): NativeMusicSectionKind {
    val stableWords = semanticMusicWords(
        destination.resourceId,
        destination.actionId,
        view.resourceId,
        view.sourceActionId,
    )
    val verifiedWords = view.evidence
        .asSequence()
        .filter { evidence -> evidence.source in VERIFIED_MUSIC_SEMANTIC_SOURCES }
        .flatMap { evidence -> evidence.detail.splitMusicSemanticWords().asSequence() }
        .toSet()
    val contractKind = nativeMusicSectionKindFromWords(stableWords + verifiedWords)
    if (view.component != NativeComponent.mediaLibrary) {
        return contractKind.takeIf { it in NON_LIBRARY_MUSIC_SECTIONS }
            ?: destination.label.boundedMusicLabelKind()
                .takeIf { it in NON_LIBRARY_MUSIC_SECTIONS }
            ?: NativeMusicSectionKind.Other
    }
    return contractKind
        ?: destination.label.boundedMusicLabelKind()
        ?: NativeMusicSectionKind.Other
}

private fun nativeMusicSectionKindFromWords(words: Set<String>): NativeMusicSectionKind? = when {
    words.any { it in setOf("track", "tracks", "song", "songs") } -> NativeMusicSectionKind.Tracks
    words.any { it in setOf("album", "albums", "release", "releases") } -> NativeMusicSectionKind.Albums
    words.any { it in setOf("artist", "artists", "composer", "composers") } -> NativeMusicSectionKind.Artists
    words.any { it in setOf("folder", "folders", "directory", "directories") } -> NativeMusicSectionKind.Folders
    words.any { it in setOf("playlist", "playlists", "queue", "queues") } -> NativeMusicSectionKind.Playlists
    words.any { it in setOf("podcast", "podcasts", "episode", "episodes", "channel", "channels") } ->
        NativeMusicSectionKind.Podcasts
    words.any { it in setOf("radio", "station", "stations") } -> NativeMusicSectionKind.Radio
    words.any { it in setOf("genre", "genres") } -> NativeMusicSectionKind.Genres
    words.any { it in setOf("search", "find", "query") } -> NativeMusicSectionKind.Search
    words.any { it in setOf("setting", "settings", "preference", "preferences") } ->
        NativeMusicSectionKind.Settings
    else -> null
}

private fun NativeMusicSectionKind.isMediaLibrarySection(): Boolean =
    this in CORE_MUSIC_SECTIONS || this in SUPPORTING_MUSIC_SECTIONS

private fun String.removeMusicApiPrefix(): String =
    removePrefix("API ").removePrefix("Api ").removePrefix("api ").trim()

/**
 * Human-facing labels are a last resort because they are localized and often describe a detail
 * view. Restrict the fallback to exact, short collection labels on a declared media-library view.
 */
private fun String.boundedMusicLabelKind(): NativeMusicSectionKind? = when (
    removeMusicApiPrefix().lowercase().filter(Char::isLetterOrDigit)
) {
    "album", "albums", "allalbums", "release", "releases" -> NativeMusicSectionKind.Albums
    "artist", "artists", "composer", "composers", "allartists" -> NativeMusicSectionKind.Artists
    "track", "tracks", "song", "songs", "allsongs", "alltracks" -> NativeMusicSectionKind.Tracks
    "folder", "folders", "directory", "directories" -> NativeMusicSectionKind.Folders
    "playlist", "playlists", "queue", "queues" -> NativeMusicSectionKind.Playlists
    "podcast", "podcasts", "episode", "episodes", "channel", "channels" -> NativeMusicSectionKind.Podcasts
    "radio", "station", "stations" -> NativeMusicSectionKind.Radio
    "genre", "genres" -> NativeMusicSectionKind.Genres
    "search", "find" -> NativeMusicSectionKind.Search
    "setting", "settings", "preference", "preferences" -> NativeMusicSectionKind.Settings
    else -> null
}

private fun semanticMusicWords(vararg values: String): Set<String> = values
    .asSequence()
    .flatMap { value -> value.splitMusicSemanticWords().asSequence() }
    .toSet()

private fun String.splitMusicSemanticWords(): List<String> {
    if (isBlank()) return emptyList()
    val separated = buildString(length + 8) {
        this@splitMusicSemanticWords.forEachIndexed { index, character ->
            val previous = this@splitMusicSemanticWords.getOrNull(index - 1)
            if (
                index > 0 &&
                character.isUpperCase() &&
                previous != null &&
                (previous.isLowerCase() || previous.isDigit())
            ) {
                append(' ')
            }
            append(character.takeIf(Char::isLetterOrDigit) ?: ' ')
        }
    }
    return separated.lowercase().split(' ').filter(String::isNotBlank)
}

private fun nativeMusicSectionIcon(kind: NativeMusicSectionKind): ImageVector = when (kind) {
    NativeMusicSectionKind.Albums -> NextcloudIcons.Image
    NativeMusicSectionKind.Artists -> NextcloudIcons.People
    NativeMusicSectionKind.Tracks -> NextcloudIcons.Play
    NativeMusicSectionKind.Folders -> NextcloudIcons.Folder
    NativeMusicSectionKind.Playlists -> NextcloudIcons.ListView
    NativeMusicSectionKind.Podcasts -> NextcloudIcons.Activity
    NativeMusicSectionKind.Radio -> NextcloudIcons.Cloud
    NativeMusicSectionKind.Genres -> NextcloudIcons.Tag
    NativeMusicSectionKind.Search -> NextcloudIcons.Search
    NativeMusicSectionKind.Settings -> NextcloudIcons.Settings
    NativeMusicSectionKind.Other -> NextcloudIcons.Apps
}

private val CORE_MUSIC_SECTIONS = setOf(
    NativeMusicSectionKind.Albums,
    NativeMusicSectionKind.Artists,
    NativeMusicSectionKind.Tracks,
)

private val SUPPORTING_MUSIC_SECTIONS = setOf(
    NativeMusicSectionKind.Folders,
    NativeMusicSectionKind.Playlists,
    NativeMusicSectionKind.Podcasts,
    NativeMusicSectionKind.Radio,
    NativeMusicSectionKind.Genres,
)

private val PREFERRED_MUSIC_LANDING_SECTIONS = listOf(
    NativeMusicSectionKind.Albums,
    NativeMusicSectionKind.Artists,
    NativeMusicSectionKind.Tracks,
    NativeMusicSectionKind.Folders,
    NativeMusicSectionKind.Playlists,
    NativeMusicSectionKind.Podcasts,
    NativeMusicSectionKind.Radio,
    NativeMusicSectionKind.Genres,
)

private val NON_LIBRARY_MUSIC_SECTIONS = setOf(
    NativeMusicSectionKind.Search,
    NativeMusicSectionKind.Settings,
)

private val VERIFIED_MUSIC_SEMANTIC_SOURCES = setOf(
    EvidenceSource.capability,
    EvidenceSource.openApi,
    EvidenceSource.verifiedAdapter,
    EvidenceSource.verifiedAppPackage,
    EvidenceSource.appStoreLinkedSourceTag,
)

private val MUSIC_SECTION_ORDER = PREFERRED_MUSIC_LANDING_SECTIONS + listOf(
    NativeMusicSectionKind.Search,
    NativeMusicSectionKind.Settings,
    NativeMusicSectionKind.Other,
)
