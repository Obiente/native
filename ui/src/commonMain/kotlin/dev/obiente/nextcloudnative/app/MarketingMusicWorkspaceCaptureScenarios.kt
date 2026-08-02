package dev.obiente.nextcloudnative.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.obiente.nextcloudnative.app.design.NextcloudDesktopShell
import dev.obiente.nextcloudnative.app.design.NextcloudDestination
import dev.obiente.nextcloudnative.app.design.NextcloudPresentation
import dev.obiente.nextcloudnative.nativeui.model.AppIdentity
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.model.DynamicNavigationDestination
import dev.obiente.nextcloudnative.nativeui.model.NativeAppSchema
import dev.obiente.nextcloudnative.nativeui.model.NativeComponent
import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec
import dev.obiente.nextcloudnative.nativeui.model.ViewSpec
import dev.obiente.nextcloudnative.nativeui.runtime.GenericNativeAppScreen
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutionResult
import dev.obiente.nextcloudnative.nativeui.runtime.NativeActionExecutor
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioRecordPlayer
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeDatasetContext
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMusicAdaptiveNavigationLayout
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.NativeScreenState
import dev.obiente.nextcloudnative.nativeui.runtime.nativeMusicWorkspaceWidthClass
import dev.obiente.nextcloudnative.nativeui.runtime.planNativeMusicWorkspace

/**
 * Synthetic, network-inert captures of the real adaptive Music workspace and generic renderer.
 *
 * The fixture intentionally uses generic collection contracts and placeholder media names. It
 * never reads a session, server, cache, device library, or personal account data.
 */
@Composable
internal fun MarketingMusicWorkspaceScenario(
    scenario: MarketingCaptureScenario,
    assets: MarketingCaptureAssets,
) {
    require(scenario in musicWorkspaceCaptureScenarios) {
        "${scenario.id} is not a Music workspace capture."
    }
    val desktop = scenario.presentation == NextcloudPresentation.Desktop
    val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthClass = nativeMusicWorkspaceWidthClass(maxWidth.value, maxHeight.value)
            val intent = remember(widthClass) {
                requireNotNull(
                    planNativeMusicWorkspace(
                        destinations = marketingMusicDestinations,
                        selectedViewId = "albums-view",
                        widthClass = widthClass,
                    ),
                )
            }
            Column(Modifier.fillMaxSize()) {
                DynamicAppChromeHeader(
                    title = marketingMusicSchema.app.name,
                    subtitle = "Your library",
                    onBack = {},
                    compact = !desktop,
                    onContractInfo = {},
                )
                NativeMusicAdaptiveNavigationLayout(
                    intent = intent,
                    onDestinationSelected = {},
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        GenericNativeAppScreen(
                            schema = marketingMusicSchema,
                            view = marketingMusicTracksView,
                            state = NativeScreenState.Ready(marketingMusicTracks),
                            actionExecutor = NativeActionExecutor {
                                NativeActionExecutionResult.Failure("This fixture is read-only.")
                            },
                            datasetContext = NativeDatasetContext(
                                parentResourceId = "albums",
                                parentRecord = marketingMusicAlbum,
                            ),
                            audioPlayer = NativeAudioRecordPlayer { _, _, _, _ -> },
                            modifier = Modifier.weight(1f),
                        )
                        if (scenario == MarketingCaptureScenario.MusicLibraryPlaybackErrorDesktop) {
                            NativeAudioMiniPlayer(
                                queue = NativeAudioQueueState(
                                    tracks = marketingMusicQueueTracks,
                                    currentIndex = 0,
                                ),
                                engineState = NativeAudioEngineState(
                                    sourceId = "fixture-track-1",
                                    status = NativeAudioEngineStatus.Error,
                                    positionMillis = 84_000,
                                    durationMillis = 232_000,
                                    error = "Playback paused. The stream can be retried without losing the queue.",
                                ),
                                artworkRelativePath = null,
                                imageLoader = null,
                                onPrevious = {},
                                onTogglePlayback = {},
                                onNext = {},
                                onSelectTrack = {},
                                onSeek = {},
                                onStop = {},
                            )
                        }
                    }
                }
            }
        }
    }
    if (desktop) {
        NextcloudDesktopShell(
            selected = NextcloudDestination.Apps,
            onSelected = {},
            identity = marketingDesktopIdentity(avatar = assets.avatar),
            content = content,
        )
    } else {
        content()
    }
}

internal val musicWorkspaceCaptureScenarios = listOf(
    MarketingCaptureScenario.MusicLibraryAlbumTracksMobile,
    MarketingCaptureScenario.MusicLibraryPlaybackErrorDesktop,
)

private val marketingMusicAlbums = ResourceSpec("albums", "Albums", Confidence.verified)
private val marketingMusicArtists = ResourceSpec("artists", "Artists", Confidence.verified)
private val marketingMusicTracksResource = ResourceSpec("tracks", "Tracks", Confidence.verified)

private val marketingMusicTracksView = ViewSpec(
    id = "tracks-view",
    title = "Tracks",
    resourceId = marketingMusicTracksResource.id,
    component = NativeComponent.mediaLibrary,
    sourceActionId = "tracks-list",
    confidence = Confidence.verified,
)

private val marketingMusicDestinations = listOf(
    DynamicNavigationDestination("albums-view", "Albums", "albums", "albums-list") to
        ViewSpec(
            id = "albums-view",
            title = "Albums",
            resourceId = "albums",
            component = NativeComponent.mediaLibrary,
            sourceActionId = "albums-list",
            confidence = Confidence.verified,
        ),
    DynamicNavigationDestination("artists-view", "Artists", "artists", "artists-list") to
        ViewSpec(
            id = "artists-view",
            title = "Artists",
            resourceId = "artists",
            component = NativeComponent.mediaLibrary,
            sourceActionId = "artists-list",
            confidence = Confidence.verified,
        ),
    DynamicNavigationDestination("tracks-view", "Tracks", "tracks", "tracks-list") to marketingMusicTracksView,
)

private val marketingMusicSchema = NativeAppSchema(
    schemaVersion = "1",
    app = AppIdentity("fixture-media-library", "Music", "1"),
    confidence = Confidence.verified,
    resources = listOf(marketingMusicAlbums, marketingMusicArtists, marketingMusicTracksResource),
    views = marketingMusicDestinations.map { (_, view) -> view },
)

private val marketingMusicAlbum = NativeRecord(
    id = "fixture-album-1",
    values = mapOf(
        "name" to "Evening Signals",
        "coverUrl" to null,
    ),
)

private val marketingMusicTracks = listOf(
    marketingMusicTrack("fixture-track-1", "First light", "1", 232_000),
    marketingMusicTrack("fixture-track-2", "Station windows", "2", 198_000),
    marketingMusicTrack("fixture-track-3", "Before the rain", "3", 245_000),
)

private val marketingMusicQueueTracks: List<NativeAudioTrack> = listOf(
    NativeAudioTrack("fixture-track-1", "First light", "Sample artist", "Evening Signals", null, 232_000, emptyList()),
    NativeAudioTrack("fixture-track-2", "Station windows", "Sample artist", "Evening Signals", null, 198_000, emptyList()),
    NativeAudioTrack("fixture-track-3", "Before the rain", "Sample artist", "Evening Signals", null, 245_000, emptyList()),
)

private fun marketingMusicTrack(
    id: String,
    title: String,
    trackNumber: String,
    durationMillis: Int,
): NativeRecord = NativeRecord(
    id = id,
    values = mapOf(
        "title" to title,
        "artist" to "Sample artist",
        "album" to "Evening Signals",
        "trackNumber" to trackNumber,
        "durationMillis" to durationMillis.toString(),
        "fileId" to (7_000 + trackNumber.toInt()).toString(),
        "mimeType" to "audio/mpeg",
    ),
)
