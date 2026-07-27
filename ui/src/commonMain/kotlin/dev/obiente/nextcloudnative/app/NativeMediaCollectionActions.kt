package dev.obiente.nextcloudnative.app

/** Collection actions shared by native media collection surfaces. */
enum class NativeMediaCollectionAction {
    CreateCollection,
    AddItem,
    RemoveItem,
}

enum class NativeMediaCollectionActionRisk {
    /** Creates a Photos collection but does not modify any file. */
    CollectionStructure,

    /** Changes only collection membership; the original file remains untouched. */
    CollectionMembership,
}

enum class NativeMediaCollectionTransportMethod {
    MKCOL,
    COPY,
    DELETE,
}

/**
 * Narrow same-origin transport for reviewed Photos DAV mutations.
 *
 * A COPY always starts at the authenticated Files DAV tree and ends in the authenticated user's
 * owned- or shared-album Photos tree. It cannot move or delete the source file. Shared albums are
 * still server-authorized at execution time, so a stale collaborator membership fails closed.
 */
data class NativeMediaCollectionTransportRequest(
    val method: NativeMediaCollectionTransportMethod,
    val relativePath: String,
    val destinationRelativePath: String? = null,
    val ifMatch: String? = null,
    val ifNoneMatch: Boolean = false,
    val overwrite: Boolean? = null,
) {
    init {
        require(relativePath.isSafeCollectionDavPath()) { "The media collection DAV path is unsafe." }
        require(destinationRelativePath == null || destinationRelativePath.isSafeCollectionDavPath()) {
            "The media collection destination is unsafe."
        }
        require(ifMatch == null || ifMatch.isStrongDavEtag()) { "The media source ETag is unsafe." }
        when (method) {
            NativeMediaCollectionTransportMethod.MKCOL -> {
                require(relativePath.isOwnAlbumCollectionPath())
                require(destinationRelativePath == null && ifMatch == null && ifNoneMatch && overwrite == null)
            }
            NativeMediaCollectionTransportMethod.COPY -> {
                require(relativePath.startsWith(FILES_DAV_PREFIX))
                require(destinationRelativePath?.isAlbumMemberPath() == true)
                require(ifMatch != null && !ifNoneMatch && overwrite == false)
            }
            NativeMediaCollectionTransportMethod.DELETE -> {
                require(relativePath.isAlbumMemberPath())
                require(destinationRelativePath == null && ifMatch == null && !ifNoneMatch && overwrite == null)
            }
        }
    }
}

data class NativeMediaCollectionActionConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

data class NativeMediaCollectionActionPlan(
    val action: NativeMediaCollectionAction,
    val collectionKey: String,
    val collectionName: String,
    val fileId: Long?,
    val fileName: String?,
    val risk: NativeMediaCollectionActionRisk,
    val confirmation: NativeMediaCollectionActionConfirmation,
    val request: NativeMediaCollectionTransportRequest?,
    val disabledReason: String?,
) {
    val enabled: Boolean get() = request != null && disabledReason == null
}

fun planCreateMediaAlbum(
    name: String,
    currentUserId: String?,
    existingAlbums: List<NativeMediaCollection> = emptyList(),
): NativeMediaCollectionActionPlan {
    val normalizedName = name.trim()
    val base = NativeMediaCollectionActionPlan(
        action = NativeMediaCollectionAction.CreateCollection,
        collectionKey = "album:new:$normalizedName",
        collectionName = normalizedName,
        fileId = null,
        fileName = null,
        risk = NativeMediaCollectionActionRisk.CollectionStructure,
        confirmation = NativeMediaCollectionActionConfirmation(
            title = "Create $normalizedName?",
            message = "A new empty album will be created. No files will be moved, copied, or deleted.",
            confirmLabel = "Create album",
        ),
        request = null,
        disabledReason = null,
    )
    if (name != normalizedName) {
        return base.copy(disabledReason = "Album names cannot begin or end with spaces.")
    }
    if (!isSafeDavSegment(normalizedName)) {
        return base.copy(disabledReason = "Enter a valid album name without slashes or control characters.")
    }
    if (existingAlbums.any {
            it.type == NativeMediaCollectionType.Album && it.name.equals(normalizedName, ignoreCase = true)
        }
    ) {
        return base.copy(disabledReason = "An album with this name already exists.")
    }
    val userId = currentUserId?.takeIf(::isSafeDavIdentity)
        ?: return base.copy(disabledReason = "The authenticated user ID is unavailable.")
    val path = albumCollectionPath(userId, normalizedName)
    return base.copy(
        collectionKey = "album:$userId/$normalizedName",
        request = NativeMediaCollectionTransportRequest(
            method = NativeMediaCollectionTransportMethod.MKCOL,
            relativePath = path,
            ifNoneMatch = true,
        ),
    )
}

/**
 * Plans the official Photos DAV COPY used by the Photos web client.
 *
 * A shared album returned for the authenticated user is a Photos DAV collaborator collection.
 * Photos validates collaborator membership again when COPY executes. The source must remain a
 * readable, versioned Files record with an exact path.
 */
fun planAddFileToMediaCollection(
    collection: NativeMediaCollection,
    file: NextcloudFile,
    currentUserId: String?,
): NativeMediaCollectionActionPlan {
    val base = NativeMediaCollectionActionPlan(
        action = NativeMediaCollectionAction.AddItem,
        collectionKey = collection.key,
        collectionName = collection.name,
        fileId = file.fileId,
        fileName = file.name,
        risk = NativeMediaCollectionActionRisk.CollectionMembership,
        confirmation = NativeMediaCollectionActionConfirmation(
            title = "Add ${file.name} to ${collection.name}?",
            message = "A reference will be added to this album. The original stays in Files.",
            confirmLabel = "Add to album",
        ),
        request = null,
        disabledReason = null,
    )
    if (collection.type != NativeMediaCollectionType.Album || !collection.canBrowse) {
        return base.copy(disabledReason = "Choose an available Photos album.")
    }
    val userId = currentUserId?.takeIf(::isSafeDavIdentity)
        ?: return base.copy(disabledReason = "The authenticated user ID is unavailable.")
    val owner = collection.ownerUserId?.takeIf(::isSafeDavIdentity)
        ?: return base.copy(disabledReason = "The album owner could not be verified.")
    if (file.isDirectory || file.fileId == null) {
        return base.copy(disabledReason = "Only an exact media file can be added to an album.")
    }
    if (!file.davPathAuthoritative) {
        return base.copy(disabledReason = "The source file path could not be verified.")
    }
    if (file.permissions == null || 'R' !in file.permissions) {
        return base.copy(disabledReason = "Refresh media permissions before adding this file.")
    }
    val etag = file.etag?.takeIf(String::isStrongDavEtag)
        ?: return base.copy(disabledReason = "Refresh this file before adding it to an album.")
    val sourcePath = runCatching { requireSafeFilePath(file.path, allowRoot = false) }.getOrNull()
        ?: return base.copy(disabledReason = "The source file path is unavailable or unsafe.")
    if (!isSafeDavSegment(collection.name) || !isSafeDavSegment(file.name)) {
        return base.copy(disabledReason = "The server returned an album or filename that cannot be changed safely.")
    }
    val source = "/remote.php/dav/" + (
        listOf("files", userId) + sourcePath.split('/')
        ).joinToString("/") { segment -> encodeDavPathSegment(segment) }
    val albumCollection = if (owner == userId) {
        albumCollectionPath(userId, collection.name)
    } else {
        sharedAlbumCollectionPath(userId, collection.name, owner)
    }
    val destination = "$albumCollection/${encodeDavPathSegment(file.name)}"
    return base.copy(
        request = NativeMediaCollectionTransportRequest(
            method = NativeMediaCollectionTransportMethod.COPY,
            relativePath = source,
            destinationRelativePath = destination,
            ifMatch = etag,
            overwrite = false,
        ),
    )
}

/**
 * Plans the Photos DAV membership delete used by Memories itself.
 *
 * This never targets `/files/{user}` and therefore cannot delete the original file.
 */
fun planRemoveItemFromMediaCollection(
    collection: NativeMediaCollection,
    item: NativeMediaItem,
    currentUserId: String?,
): NativeMediaCollectionActionPlan {
    val base = NativeMediaCollectionActionPlan(
        action = NativeMediaCollectionAction.RemoveItem,
        collectionKey = collection.key,
        collectionName = collection.name,
        fileId = item.fileId,
        fileName = item.name,
        risk = NativeMediaCollectionActionRisk.CollectionMembership,
        confirmation = NativeMediaCollectionActionConfirmation(
            title = "Remove from ${collection.name}?",
            message = "${item.name} will leave this album. The original file stays in Files.",
            confirmLabel = "Remove from album",
        ),
        request = null,
        disabledReason = null,
    )
    if (collection.type != NativeMediaCollectionType.Album) {
        return base.copy(
            confirmation = base.confirmation.copy(
                title = "Edit tags for ${item.name}?",
                message = "Tag collections are changed through the photo tag editor.",
                confirmLabel = "Edit tags",
            ),
            disabledReason = "Open the photo and use its tag editor to change tag membership.",
        )
    }
    val userId = currentUserId?.takeIf(::isSafeDavIdentity)
        ?: return base.copy(disabledReason = "The authenticated user ID is unavailable.")
    val owner = collection.ownerUserId?.takeIf(::isSafeDavIdentity)
        ?: return base.copy(disabledReason = "The album owner could not be verified.")
    if (!collection.canBrowse) {
        return base.copy(disabledReason = "This album is not available through the Memories media contract.")
    }
    if (!isSafeDavSegment(collection.name) || !isSafeDavSegment(item.name)) {
        return base.copy(disabledReason = "The server returned an album or filename that cannot be changed safely.")
    }
    val albumCollection = if (owner == userId) {
        albumCollectionPath(userId, collection.name)
    } else {
        sharedAlbumCollectionPath(userId, collection.name, owner)
    }
    return base.copy(
        request = NativeMediaCollectionTransportRequest(
            method = NativeMediaCollectionTransportMethod.DELETE,
            relativePath = "$albumCollection/${item.fileId}-${encodeDavPathSegment(item.name)}",
        ),
    )
}

data class NativeMediaCollectionMutationResult(
    val status: Int,
    val action: NativeMediaCollectionAction,
    val collectionName: String,
    val fileId: Long?,
    val alreadyPresent: Boolean = false,
) {
    val removedFileId: Long
        get() = requireNotNull(fileId) { "This collection result does not contain a removed file ID." }
}

class NativeMediaCollectionMutationService internal constructor(
    private val execute: suspend (NextcloudSession, NativeMediaCollectionTransportRequest) -> NextcloudApiResponse,
) {
    constructor(services: NextcloudPlatformServices) : this(services::executeMediaCollectionMutation)

    suspend fun executeConfirmed(
        session: NextcloudSession,
        plan: NativeMediaCollectionActionPlan,
        confirmed: Boolean,
    ): NativeMediaCollectionMutationResult {
        require(confirmed) { "Collection changes require explicit confirmation." }
        val request = requireNotNull(plan.request) {
            plan.disabledReason ?: "This collection change is unavailable."
        }
        require(plan.enabled && request.matches(plan.action)) { "The collection mutation plan is invalid." }
        val response = execute(session, request)
        val alreadyPresent = plan.action == NativeMediaCollectionAction.AddItem && response.status == 409
        require(response.status in 200..299 || alreadyPresent) {
            response.collectionMutationError(plan.action)
        }
        return NativeMediaCollectionMutationResult(
            status = response.status,
            action = plan.action,
            collectionName = plan.collectionName,
            fileId = plan.fileId,
            alreadyPresent = alreadyPresent,
        )
    }
}

private fun NativeMediaCollectionTransportRequest.matches(action: NativeMediaCollectionAction): Boolean =
    method == when (action) {
        NativeMediaCollectionAction.CreateCollection -> NativeMediaCollectionTransportMethod.MKCOL
        NativeMediaCollectionAction.AddItem -> NativeMediaCollectionTransportMethod.COPY
        NativeMediaCollectionAction.RemoveItem -> NativeMediaCollectionTransportMethod.DELETE
    }

private fun NextcloudApiResponse.collectionMutationError(action: NativeMediaCollectionAction): String = when (status) {
    401 -> "Sign in again before changing this album."
    403 -> "You do not have permission to change this album."
    404 -> "The file or album no longer exists. Refresh and try again."
    405, 409, 412 -> "The album changed on the server. Refresh and try again."
    else -> "${action.failureLabel()} failed (HTTP $status)."
}

private fun NativeMediaCollectionAction.failureLabel(): String = when (this) {
    NativeMediaCollectionAction.CreateCollection -> "Creating the album"
    NativeMediaCollectionAction.AddItem -> "Adding the item to the album"
    NativeMediaCollectionAction.RemoveItem -> "Removing the item from the album"
}

private fun albumCollectionPath(userId: String, albumName: String): String =
    "/remote.php/dav/" + listOf("photos", userId, "albums", albumName)
        .joinToString("/") { segment -> encodeDavPathSegment(segment) }

private fun sharedAlbumCollectionPath(userId: String, albumName: String, ownerUserId: String): String =
    "/remote.php/dav/" + listOf(
        "photos",
        userId,
        "sharedalbums",
        "$albumName ($ownerUserId)",
    ).joinToString("/") { segment -> encodeDavPathSegment(segment) }

private fun String.isSafeCollectionDavPath(): Boolean {
    val normalized = lowercase()
    return startsWith("/remote.php/dav/") &&
        length <= MAX_COLLECTION_DAV_PATH_LENGTH &&
        none { it.isISOControl() || it == '\\' || it == '#' || it == '?' || it.isWhitespace() } &&
        split('/').none { it == "." || it == ".." } &&
        listOf("%2e", "%2f", "%5c", "%00").none(normalized::contains)
}

private fun String.isOwnAlbumCollectionPath(): Boolean {
    val segments = removePrefix("/remote.php/dav/").split('/')
    return segments.size == 4 && segments[0] == "photos" && segments[2] == "albums" && segments[3].isNotBlank()
}

private fun String.isAlbumMemberPath(): Boolean {
    val segments = removePrefix("/remote.php/dav/").split('/')
    return segments.size == 5 && segments[0] == "photos" &&
        segments[2] in setOf("albums", "sharedalbums") &&
        segments[3].isNotBlank() && segments[4].isNotBlank()
}

private fun String.isStrongDavEtag(): Boolean =
    isNotBlank() && length <= MAX_COLLECTION_ETAG_LENGTH && none(Char::isISOControl) &&
        !startsWith("W/", ignoreCase = true)

private fun isSafeDavIdentity(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_DAV_SEGMENT_LENGTH &&
        '/' !in value && '\\' !in value && value.none(Char::isISOControl) &&
        value != "." && value != ".."

private fun isSafeDavSegment(value: String): Boolean = isSafeDavIdentity(value)

private fun encodeDavPathSegment(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val unsigned = byte.toInt() and 0xff
        val unreserved = unsigned in 'a'.code..'z'.code ||
            unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code ||
            unsigned == '-'.code || unsigned == '.'.code ||
            unsigned == '_'.code || unsigned == '~'.code
        if (unreserved) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(DAV_HEX[unsigned ushr 4])
            append(DAV_HEX[unsigned and 0x0f])
        }
    }
}

private const val FILES_DAV_PREFIX = "/remote.php/dav/files/"
private const val MAX_COLLECTION_DAV_PATH_LENGTH = 4_096
private const val MAX_COLLECTION_ETAG_LENGTH = 1_024
private const val MAX_DAV_SEGMENT_LENGTH = 1_024
private const val DAV_HEX = "0123456789ABCDEF"
