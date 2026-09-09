package dev.obiente.nextcloudnative.app

import androidx.compose.runtime.Composable
import dev.obiente.nextcloudnative.nativeui.model.DynamicAction
import dev.obiente.nextcloudnative.nativeui.model.HttpMethod
import dev.obiente.nextcloudnative.nativeui.model.Confidence
import dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMediaArtworkFallback
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMediaArtworkReference
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMediaArtworkResolver
import dev.obiente.nextcloudnative.nativeui.runtime.NativeMediaItemKind
import dev.obiente.nextcloudnative.nativeui.runtime.NativeRecord
import dev.obiente.nextcloudnative.nativeui.runtime.nativeAudioTrack
import dev.obiente.nextcloudnative.nativeui.runtime.nativeMediaPresentation
import kotlinx.coroutines.flow.StateFlow

internal data class NativeAudioSourceCapability(
    private val fileDownloadPrefix: String,
    private val albumCoverTemplate: String? = null,
    private val albumCoverParameter: String? = null,
) {
    fun source(track: NativeAudioTrack): NativeAudioPlaybackSource? {
        val resolved = track.files.firstNotNullOfOrNull { file ->
            resolve(file)?.let { path -> file to path }
        } ?: return null
        val (file, relativePath) = resolved
        return NativeAudioPlaybackSource(
            id = "${track.recordId}:${file.fileId ?: relativePath}:${file.mimeType}",
            relativePath = relativePath,
            mimeType = file.mimeType,
            title = track.title.boundedAudioMetadata(),
            artist = track.artist?.boundedAudioMetadata(),
            album = track.album?.boundedAudioMetadata(),
            artworkRelativePath = resolveAlbumArtwork(track.albumId),
        )
    }

    private fun resolve(
        file: dev.obiente.nextcloudnative.nativeui.runtime.NativeAudioFileReference,
    ): String? {
        file.fileId?.takeIf { it > 0 }?.let { return "$fileDownloadPrefix/$it/download" }
        val advertised = file.advertisedRelativePath ?: return null
        val safePath = runCatching {
            NextcloudApiRequest(NextcloudApiMethod.GET, advertised).requireSafe().relativePath
        }.getOrNull() ?: return null
        val expected = fileDownloadPrefix.split('/').filter(String::isNotBlank)
        val candidate = safePath.split('/').filter(String::isNotBlank)
        val normalizedExpected = expected.withoutOptionalFrontController()
        val normalizedCandidate = candidate.withoutOptionalFrontController()
        if (normalizedCandidate.size != normalizedExpected.size + 2) return null
        if (normalizedCandidate.take(normalizedExpected.size) != normalizedExpected) return null
        if (normalizedCandidate[normalizedExpected.size].toLongOrNull()?.takeIf { it > 0 } == null) return null
        if (normalizedCandidate.last() != "download") return null
        return safePath
    }

    private fun resolveAlbumArtwork(albumId: Long?): String? {
        val template = albumCoverTemplate ?: return null
        val parameter = albumCoverParameter ?: return null
        val safeId = albumId?.takeIf { it > 0 }?.toString() ?: return null
        val token = "{$parameter}"
        if (template.countToken(token) != 1) return null
        val resolved = template.replace(token, safeId)
        return runCatching {
            NextcloudApiRequest(NextcloudApiMethod.GET, resolved).requireSafe().relativePath
        }.getOrNull()
    }
}

private fun String.boundedAudioMetadata(): String? = buildString {
    this@boundedAudioMetadata.forEach { character ->
        when {
            length >= 512 -> return@forEach
            character.isWhitespace() -> {
                if (isNotEmpty() && last() != ' ') append(' ')
            }
            !character.isISOControl() -> append(character)
        }
    }
}.trim().takeIf(String::isNotBlank)

private fun List<String>.withoutOptionalFrontController(): List<String> =
    if (firstOrNull() == "index.php") drop(1) else this

/**
 * Infers only the narrow, reviewed sibling route used by track APIs exposing file-id maps.
 *
 * Metadata-only discovery is intentionally excluded: a guessed collection is not evidence that a
 * binary endpoint exists. No app id participates in this decision.
 */
internal fun nativeAudioSourceCapability(
    discovery: DynamicDescriptorDiscovery,
    action: DynamicAction?,
): NativeAudioSourceCapability? {
    if (discovery.acquisition == DynamicDescriptorAcquisition.MetadataFallback) return null
    val binding = action?.binding ?: return null
    if (binding.method != HttpMethod.GET || binding.pathParameters.isNotEmpty()) return null
    val path = runCatching {
        NextcloudApiRequest(NextcloudApiMethod.GET, binding.path).requireSafe().relativePath
    }.getOrNull() ?: return null
    val segments = path.split('/').filter(String::isNotBlank)
    if (segments.lastOrNull()?.lowercase() !in setOf("tracks", "songs")) return null
    val apiIndex = segments.indexOfLast { it.equals("api", ignoreCase = true) }
    if (apiIndex < 2 || apiIndex != segments.lastIndex - 1) return null
    val prefixIsAppScoped = segments.firstOrNull() == "apps" ||
        (segments.take(2) == listOf("index.php", "apps"))
    if (!prefixIsAppScoped) return null
    val apiPrefix = "/" + segments.dropLast(1).joinToString("/")
    val appId = discovery.descriptor.app.id
    val coverRoute = discovery.descriptor.actions.takeIf {
        discovery.acquisition in SIGNED_ARTWORK_ACQUISITIONS
    }.orEmpty().firstOrNull { candidate ->
        val candidateBinding = candidate.binding
        if (candidate.confidence != Confidence.verified ||
            candidateBinding.method != HttpMethod.GET ||
            candidateBinding.pathParameters.size != 1
        ) {
            return@firstOrNull false
        }
        val safeCandidate = runCatching {
            NextcloudApiRequest(NextcloudApiMethod.GET, candidateBinding.path)
                .requireSafe()
                .relativePath
        }.getOrNull() ?: return@firstOrNull false
        if (!safeCandidate.isSameAppArtworkPath(appId)) return@firstOrNull false
        val candidateSegments = safeCandidate.split('/').filter(String::isNotBlank)
        val normalizedPrefix = apiPrefix.split('/').filter(String::isNotBlank)
        candidateSegments.size == normalizedPrefix.size + 3 &&
            candidateSegments.take(normalizedPrefix.size) == normalizedPrefix &&
            candidateSegments[normalizedPrefix.size].lowercase() in setOf("albums", "releases") &&
            candidateSegments[normalizedPrefix.size + 1] ==
            "{${candidateBinding.pathParameters.single().name}}" &&
            candidateSegments.last().lowercase() in setOf("cover", "artwork", "image")
    }?.binding
    return NativeAudioSourceCapability(
        fileDownloadPrefix = "$apiPrefix/files",
        albumCoverTemplate = coverRoute?.path,
        albumCoverParameter = coverRoute?.pathParameters?.singleOrNull()?.name,
    )
}

private data class NativeArtworkRoute(
    val family: String,
    val template: String,
    val parameter: String,
)

/**
 * Builds an app-neutral artwork resolver exclusively from reviewed GET routes.
 *
 * Observed response URLs are display data, not authority. A URL is loadable only when it exactly
 * matches the signed artist/album/track cover template for that record. Tracks without their own
 * cover inherit the verified album cover route through a positive album identifier.
 */
internal fun nativeMediaArtworkResolver(
    discovery: DynamicDescriptorDiscovery,
): NativeMediaArtworkResolver? {
    if (discovery.acquisition !in SIGNED_ARTWORK_ACQUISITIONS) return null
    val appId = discovery.descriptor.app.id
    if (appId.isBlank() || appId.any { !it.isLetterOrDigit() && it !in setOf('-', '_') }) return null
    val routes = discovery.descriptor.actions.mapNotNull { action ->
        val binding = action.binding
        if (action.confidence != Confidence.verified ||
            binding.method != HttpMethod.GET ||
            binding.pathParameters.size != 1
        ) {
            return@mapNotNull null
        }
        val parameter = binding.pathParameters.single().name
        val safePath = runCatching {
            NextcloudApiRequest(NextcloudApiMethod.GET, binding.path).requireSafe().relativePath
        }.getOrNull() ?: return@mapNotNull null
        if (!safePath.isSameAppArtworkPath(appId)) return@mapNotNull null
        val segments = safePath.split('/').filter(String::isNotBlank)
        val parameterIndex = segments.indexOf("{$parameter}")
        if (parameterIndex <= 0 || parameterIndex != segments.lastIndex - 1) return@mapNotNull null
        if (segments.last().lowercase() !in setOf("cover", "artwork", "image")) return@mapNotNull null
        val family = segments[parameterIndex - 1].lowercase()
        if (family !in setOf("artists", "composers", "albums", "releases", "tracks", "songs")) {
            return@mapNotNull null
        }
        NativeArtworkRoute(family, safePath, parameter)
    }
    if (routes.isEmpty()) return null
    return NativeMediaArtworkResolver { resource, record ->
        val presentation = nativeMediaPresentation(resource, record)
        val fallback = when (presentation.kind) {
            NativeMediaItemKind.Artist -> NativeMediaArtworkFallback.Artist
            NativeMediaItemKind.Album -> NativeMediaArtworkFallback.Album
            NativeMediaItemKind.Track -> NativeMediaArtworkFallback.Track
            else -> NativeMediaArtworkFallback.Media
        }
        val family = when (presentation.kind) {
            NativeMediaItemKind.Artist -> setOf("artists", "composers")
            NativeMediaItemKind.Album -> setOf("albums", "releases")
            NativeMediaItemKind.Track -> setOf("tracks", "songs")
            else -> emptySet()
        }
        val recordIdentifier = record.id.toLongOrNull()?.takeIf { it > 0 }
        val direct = presentation.coverUrl?.let { candidate ->
            recordIdentifier?.let { identifier ->
                routes.firstNotNullOfOrNull { route ->
                    route.takeIf { it.family in family }
                        ?.resolved(identifier)
                        ?.takeIf { expected -> expected.sameArtworkPath(candidate) }
                }
            }
        }
        val inferred = direct ?: if (presentation.kind == NativeMediaItemKind.Track) {
            val albumId = nativeAudioTrack(resource, record)?.albumId
            albumId?.let { identifier ->
                routes.firstNotNullOfOrNull { route ->
                    route.takeIf { it.family in setOf("albums", "releases") }?.resolved(identifier)
                }
            }
        } else {
            null
        }
        NativeMediaArtworkReference(
            relativePath = inferred,
            cacheKey = stableNativeArtworkCacheKey(
                fallback = fallback,
                recordId = record.id,
                relativePath = inferred,
            ),
            fallback = fallback,
        )
    }
}

private val SIGNED_ARTWORK_ACQUISITIONS = setOf(
    DynamicDescriptorAcquisition.SignedAppStorePackage,
    DynamicDescriptorAcquisition.SignedAppStoreStaticRoutes,
    DynamicDescriptorAcquisition.SignedAppStoreMergedContract,
)

private fun String.isSameAppArtworkPath(appId: String): Boolean =
    startsWith("/apps/$appId/") || startsWith("/index.php/apps/$appId/")

private fun NativeArtworkRoute.resolved(identifier: Long): String? {
    if (identifier <= 0) return null
    val token = "{$parameter}"
    if (template.countToken(token) != 1) return null
    return runCatching {
        NextcloudApiRequest(
            NextcloudApiMethod.GET,
            template.replace(token, identifier.toString()),
        ).requireSafe().relativePath
    }.getOrNull()
}

private fun String.sameArtworkPath(other: String): Boolean =
    stableArtworkRoute() == other.stableArtworkRoute()

private fun String.stableArtworkRoute(): String {
    val path = substringBefore('?')
        .split('/')
        .filter(String::isNotBlank)
        .withoutOptionalFrontController()
        .joinToString("/", prefix = "/")
    val query = substringAfter('?', "")
        .split('&')
        .filter(String::isNotBlank)
        .sorted()
        .joinToString("&")
    return if (query.isBlank()) path else "$path?$query"
}

internal fun stableNativeArtworkCacheKey(
    fallback: NativeMediaArtworkFallback,
    recordId: String,
    relativePath: String?,
): String {
    val boundedId = recordId
        .filter { it.isLetterOrDigit() || it in setOf('-', '_', '.') }
        .take(128)
        .ifBlank { "unknown" }
    val route = relativePath?.stableArtworkRoute() ?: "fallback"
    return "${fallback.name.lowercase()}:$boundedId:$route"
}

private fun String.countToken(token: String): Int {
    if (token.isEmpty()) return 0
    var count = 0
    var offset = 0
    while (true) {
        val match = indexOf(token, offset)
        if (match < 0) return count
        count += 1
        offset = match + token.length
    }
}

internal enum class NativeAudioEngineStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Ended,
    Error,
}

internal data class NativeAudioEngineState(
    val sourceId: String? = null,
    val status: NativeAudioEngineStatus = NativeAudioEngineStatus.Idle,
    val positionMillis: Long = 0,
    val durationMillis: Long? = null,
    val error: String? = null,
)

internal data class NativeAudioPlaybackSource(
    val id: String,
    val relativePath: String,
    val mimeType: String,
    val knownSize: Long? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkRelativePath: String? = null,
) {
    init {
        NextcloudApiRequest(NextcloudApiMethod.GET, relativePath).requireSafe()
        require(mimeType.startsWith("audio/") && mimeType.length <= 128) {
            "Playback sources require a bounded audio MIME type."
        }
        require(knownSize == null || knownSize > 0L) { "Playback source size must be positive." }
        listOfNotNull(title, artist, album).forEach { metadata ->
            require(metadata.length <= 512 && metadata.none(Char::isISOControl)) {
                "Playback metadata must be bounded display text."
            }
        }
        artworkRelativePath?.let { path ->
            NextcloudApiRequest(NextcloudApiMethod.GET, path).requireSafe()
        }
    }
}

internal fun nativeAudioPlaybackUrl(
    session: NextcloudSession,
    source: NativeAudioPlaybackSource,
): String {
    val server = session.serverUrl.trim().trimEnd('/')
    require(server.startsWith("https://") || server.startsWith("http://")) {
        "The account server must use HTTP or HTTPS."
    }
    val authority = server.substringAfter("://").substringBefore('/')
    require(authority.isNotBlank() && '@' !in authority) {
        "Credentials cannot be embedded in the account server URL."
    }
    return buildNextcloudApiUrl(
        serverUrl = server,
        request = NextcloudApiRequest(NextcloudApiMethod.GET, source.relativePath),
    )
}

internal data class NativeAudioQueueState(
    val tracks: List<NativeAudioTrack> = emptyList(),
    val currentIndex: Int? = null,
)

internal fun startNativeAudioQueue(
    tracks: List<NativeAudioTrack>,
    selectedRecordId: String,
): NativeAudioQueueState {
    val playable = tracks.filter { it.files.isNotEmpty() }.distinctBy(NativeAudioTrack::recordId)
    val selectedIndex = playable.indexOfFirst { it.recordId == selectedRecordId }
    return NativeAudioQueueState(
        tracks = playable,
        currentIndex = selectedIndex.takeIf { it >= 0 },
    )
}

internal fun NativeAudioQueueState.next(wrap: Boolean = false): NativeAudioQueueState {
    val index = currentIndex ?: return this
    val next = index + 1
    return when {
        next < tracks.size -> copy(currentIndex = next)
        wrap && tracks.isNotEmpty() -> copy(currentIndex = 0)
        else -> copy(currentIndex = null)
    }
}

internal fun NativeAudioQueueState.previous(): NativeAudioQueueState {
    val index = currentIndex ?: return this
    return copy(currentIndex = (index - 1).coerceAtLeast(0))
}

internal val NativeAudioQueueState.currentTrack: NativeAudioTrack?
    get() = currentIndex?.let(tracks::getOrNull)

internal interface PlatformAudioPlaybackEngine {
    val state: StateFlow<NativeAudioEngineState>

    fun play(session: NextcloudSession, source: NativeAudioPlaybackSource)

    fun playQueue(
        session: NextcloudSession,
        sources: List<NativeAudioPlaybackSource>,
        currentIndex: Int,
    ) {
        sources.getOrNull(currentIndex)?.let { source -> play(session, source) }
    }

    fun pause()

    fun resume()

    fun seekTo(positionMillis: Long)

    fun stop()

    fun release()
}

@Composable
internal expect fun rememberPlatformAudioPlaybackEngine(): PlatformAudioPlaybackEngine
