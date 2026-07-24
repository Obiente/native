package dev.obiente.nextcloudnative.nativeui.runtime

import dev.obiente.nextcloudnative.nativeui.model.ResourceSpec

/**
 * One authenticated audio representation advertised by a record.
 *
 * The locator is deliberately kept separate from the media database record id. APIs may expose
 * either a MIME-to-file-id map or an authenticated relative download path.
 */
internal data class NativeAudioFileReference(
    val fileId: Long?,
    val mimeType: String,
    /** Untrusted record value; the route capability must validate it before transport. */
    val advertisedRelativePath: String? = null,
)

internal data class NativeAudioTrack(
    val recordId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val durationMillis: Long?,
    val files: List<NativeAudioFileReference>,
)

enum class NativeAudioCollectionKind {
    Artist,
    Album,
}

/**
 * Display context inherited from the collection which produced a track list.
 *
 * Some media APIs intentionally return compact track rows containing only an artist/album
 * identifier. Carrying the selected collection's label into the queue avoids showing anonymous
 * tracks without treating that observed label as transport or mutation evidence.
 */
data class NativeAudioCollectionContext(
    val kind: NativeAudioCollectionKind,
    val title: String,
)

enum class NativeMediaArtworkFallback {
    Artist,
    Album,
    Track,
    Media,
}

/**
 * One bounded authenticated image selected from verified media semantics.
 *
 * [cacheKey] contains no account or credential material. The host keeps artwork caches scoped to
 * the active account and app, while this stable key deduplicates equivalent route spellings.
 */
data class NativeMediaArtworkReference(
    val relativePath: String?,
    val cacheKey: String,
    val fallback: NativeMediaArtworkFallback,
)

fun interface NativeMediaArtworkResolver {
    fun resolve(resource: ResourceSpec, record: NativeRecord): NativeMediaArtworkReference
}

/**
 * Recognizes a reusable media shape instead of an app id.
 *
 * A playable record must both look like a track and expose either a bounded
 * `files`/`streams`/`sources` object or an unambiguous file-id plus audio-MIME pair. Locators
 * remain untrusted until a signed route capability validates them.
 */
internal fun nativeAudioTrack(
    resource: ResourceSpec,
    record: NativeRecord,
    collectionContext: NativeAudioCollectionContext? = null,
): NativeAudioTrack? {
    val presentation = nativeMediaPresentation(resource, record)
    val sourceObject = record.structuredValues.entries.firstNotNullOfOrNull { (key, value) ->
        value.takeIf {
            key.nativeAudioSemanticKey() in setOf("files", "streams", "sources")
        } as? NativeStructuredValue.ObjectValue
    }
    val semanticKeys = buildSet {
        addAll(record.values.keys.map(String::nativeAudioSemanticKey))
        addAll(record.displayValues.keys.map(String::nativeAudioSemanticKey))
        addAll(record.structuredValues.keys.map(String::nativeAudioSemanticKey))
    }
    val trackShape = semanticKeys.any { it in setOf("title", "name", "track", "song") } &&
        semanticKeys.any { it in setOf("artist", "artists", "artistname", "album", "albumname") }
    if (presentation.kind != NativeMediaItemKind.Track && !trackShape) return null
    val mappedFiles = sourceObject?.entries.orEmpty().mapNotNull { entry ->
        val mimeType = entry.key.trim().lowercase()
        if (!mimeType.startsWith("audio/") || mimeType.length > 128) return@mapNotNull null
        val locator = (entry.value as? NativeStructuredValue.Scalar)
            ?.value
            ?.trim()
            ?: return@mapNotNull null
        val fileId = locator.toLongOrNull()?.takeIf { it > 0 }
        val advertisedPath = locator.takeIf { value ->
            fileId == null && value.length in 2..2_048 && value.startsWith('/') &&
                !value.startsWith("//")
        }
        if (fileId == null && advertisedPath == null) return@mapNotNull null
        NativeAudioFileReference(
            fileId = fileId,
            mimeType = mimeType,
            advertisedRelativePath = advertisedPath,
        )
    }
    val directMime = record.nativeAudioScalar(
        "mimetype", "mimetypeaudio", "audiomimetype", "streammime", "contenttype",
    )?.trim()?.lowercase()?.takeIf { it.startsWith("audio/") && it.length <= 128 }
    val directLocator = record.nativeAudioScalar(
        "fileid", "audiofileid", "mediafileid", "streamid", "downloadid",
    )?.trim()
    val directFile = if (directMime != null && directLocator != null) {
        val fileId = directLocator.toLongOrNull()?.takeIf { it > 0 }
        NativeAudioFileReference(
            fileId = fileId,
            mimeType = directMime,
        ).takeIf { fileId != null }
    } else {
        null
    }
    val files = (mappedFiles + listOfNotNull(directFile))
        .distinctBy { Triple(it.fileId, it.advertisedRelativePath, it.mimeType) }
    if (files.isEmpty()) return null
    val durationMillis = record.nativeAudioScalar("durationmilliseconds", "durationmillis", "durationms")
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.toLong()
    val durationSeconds = record.nativeAudioScalar("duration", "length", "durationseconds", "time")
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 }
    return NativeAudioTrack(
        recordId = record.id,
        title = presentation.title,
        artist = presentation.artist ?: collectionContext
            ?.takeIf { it.kind == NativeAudioCollectionKind.Artist }
            ?.title,
        album = presentation.album ?: collectionContext
            ?.takeIf { it.kind == NativeAudioCollectionKind.Album }
            ?.title,
        albumId = record.nativeAudioIdentifier(
            directAliases = setOf("albumid", "releaseid"),
            objectAliases = setOf("album", "release"),
        ),
        durationMillis = durationMillis ?: durationSeconds?.times(1_000.0)?.toLong(),
        files = files.sortedBy(NativeAudioFileReference::playbackPreference),
    )
}

internal fun nativeAudioCollectionContext(
    parentResourceId: String?,
    parentRecord: NativeRecord?,
): NativeAudioCollectionContext? {
    val record = parentRecord ?: return null
    val semanticResource = parentResourceId?.nativeAudioSemanticKey().orEmpty()
    val kind = when {
        semanticResource.contains("album") || semanticResource.contains("release") ->
            NativeAudioCollectionKind.Album
        semanticResource.contains("artist") || semanticResource.contains("composer") ->
            NativeAudioCollectionKind.Artist
        else -> return null
    }
    val title = record.nativeAudioScalar("name", "title", "displayname")
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: return null
    return NativeAudioCollectionContext(kind, title)
}

private fun NativeRecord.nativeAudioScalar(vararg aliases: String): String? {
    val wanted = aliases.mapTo(mutableSetOf()) { it.nativeAudioSemanticKey() }
    values.entries.firstOrNull { (key, value) ->
        value != null && key.nativeAudioSemanticKey() in wanted
    }?.value?.let { return it }
    structuredValues.entries.firstOrNull { (key, value) ->
        key.nativeAudioSemanticKey() in wanted && value is NativeStructuredValue.Scalar
    }?.value?.let { value ->
        return (value as NativeStructuredValue.Scalar).value
    }
    return null
}

private fun NativeRecord.nativeAudioIdentifier(
    directAliases: Set<String>,
    objectAliases: Set<String>,
): Long? {
    values.entries.firstNotNullOfOrNull { (key, value) ->
        value
            ?.takeIf { key.nativeAudioSemanticKey() in directAliases }
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
    }?.let { return it }
    structuredValues.entries.firstNotNullOfOrNull { (key, value) ->
        if (key.nativeAudioSemanticKey() !in directAliases) return@firstNotNullOfOrNull null
        (value as? NativeStructuredValue.Scalar)
            ?.value
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
    }?.let { return it }
    return structuredValues.entries.firstNotNullOfOrNull { (key, value) ->
        if (key.nativeAudioSemanticKey() !in objectAliases) return@firstNotNullOfOrNull null
        (value as? NativeStructuredValue.ObjectValue)
            ?.entries
            ?.firstNotNullOfOrNull { entry ->
                entry.takeIf { it.key.nativeAudioSemanticKey() in setOf("id", "albumid", "releaseid") }
                    ?.value
                    ?.let { it as? NativeStructuredValue.Scalar }
                    ?.value
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
            }
    }
}

private fun NativeAudioFileReference.playbackPreference(): Int = when (mimeType) {
    "audio/mpeg", "audio/mp3" -> 0
    "audio/mp4", "audio/aac", "audio/x-m4a" -> 1
    "audio/ogg", "audio/opus" -> 2
    "audio/flac", "audio/x-flac" -> 3
    "audio/wav", "audio/x-wav" -> 4
    else -> 10
}

private fun String.nativeAudioSemanticKey(): String =
    lowercase().filter(Char::isLetterOrDigit)

fun interface NativeAudioRecordPlayer {
    fun play(
        resource: ResourceSpec,
        records: List<NativeRecord>,
        selected: NativeRecord,
        collectionContext: NativeAudioCollectionContext?,
    )
}
