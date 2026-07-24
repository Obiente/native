# Nextcloud Native adapter architecture

This document defines how Nextcloud Native should support many independently versioned Nextcloud apps without embedding their web interfaces. The UI consumes stable, typed repositories. Protocol-specific adapters translate supported Nextcloud APIs into those types.

The initial supported targets are Android and desktop Linux. The boundaries below must remain usable by future iOS, macOS, and Windows builds.

## Design rules

1. The shared UI never parses JSON, XML, WebDAV properties, or response headers.
2. The shared UI never constructs a Nextcloud endpoint URL.
3. Repositories own cached state and refresh behavior. Adapters own protocol translation.
4. Platform code owns secure credentials, HTTP execution, filesystem/cache paths, external application handoff, and background scheduling.
5. Server and app capabilities are authoritative. Navigation entries are presentation hints, not proof that an API or action is supported.
6. Unknown response fields are ignored. Missing fields from older app versions are handled explicitly.
7. Original files are preserved unless the user explicitly chooses replacement.
8. No mutation runs merely because an app, file, or screen was discovered.

## Layers

```text
Compose UI
    |
Feature repositories
    |-- cached ResourceState<T>
    |-- mutation policy and conflict state
    |
Typed adapters
    |-- Files / Activity / Notes / Photos / Memories
    |-- People / Admin / Client Integration
    |
NextcloudTransport
    |-- authentication and same-origin enforcement
    |-- bounded request and response bodies
    |-- complete response headers
    |
Platform services
    |-- Android: Keystore, WorkManager, cache directory, intents
    `-- Desktop: Secret Service, scheduler, cache directory, xdg-open
```

`NextcloudPlatformServices` should remain the platform boundary. Protocol methods should gradually move out of its Android and desktop implementations and into shared adapters. This prevents every new app integration from being implemented twice.

## Transport boundary

The transport must support standard methods plus WebDAV methods such as `PROPFIND`, `PROPPATCH`, `REPORT`, `SEARCH`, `MKCOL`, `MOVE`, and `COPY`.

Suggested shared types:

```kotlin
enum class NextcloudHttpMethod {
    Get, Post, Put, Patch, Delete,
    PropFind, PropPatch, Report, Search, MkCol, Move, Copy,
}

data class TransportRequest(
    val method: NextcloudHttpMethod,
    val relativeUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val maximumResponseBytes: Long,
)

data class TransportResponse(
    val status: Int,
    val headers: ResponseHeaders,
    val body: ByteArray,
)

interface ResponseHeaders {
    operator fun get(name: String): String?
    fun values(name: String): List<String>
}

interface NextcloudTransport {
    suspend fun execute(
        session: NextcloudSession,
        request: TransportRequest,
    ): TransportResponse
}
```

Header lookup must be case-insensitive. Adapters currently need at least:

- `Content-Type`, `Content-Length`, `ETag`, and `OC-ETag`
- `Last-Modified`
- `Link`
- `X-Activity-First-Known` and `X-Activity-Last-Given`
- `X-Notes-Chunk-Cursor`, `X-Notes-Chunk-Pending`, and `X-Notes-API-Versions`
- `OC-FileId`, `X-NC-Permissions`, and `X-OC-MTime`
- `Content-Range` and `Accept-Ranges`

The transport is responsible for Basic authentication with the stored app password, the product user agent, response size limits, TLS, and redirect safety. Redirects and capability-provided action URLs must remain on the authenticated Nextcloud origin unless a feature explicitly performs an external browser handoff.

The transport should offer JSON and XML helpers above the raw response, but the raw response remains available to binary downloads and previews. XML parsing must continue to reject DTDs and external entities.

## Capability snapshot

`loadServerInfo` currently uses the capabilities response but discards the app-specific structures. Preserve a typed snapshot per account:

```kotlin
data class CapabilitySnapshot(
    val serverVersion: String?,
    val activity: ActivityCapability?,
    val notes: NotesCapability?,
    val clientIntegrations: List<ClientIntegrationCapability>,
    val appCapabilities: Map<String, CapabilityValue>,
    val fetchedAtEpochMillis: Long,
)

data class ActivityCapability(
    val apiV2Features: Set<ActivityFeature>,
)

data class NotesCapability(
    val appVersion: String?,
    val apiVersions: List<ApiVersion>,
)
```

Keep an opaque typed capability tree for future adapters. It must not use Kotlin `Any`; use a sealed JSON value type such as `CapabilityValue.Object`, `Array`, `StringValue`, `NumberValue`, `BooleanValue`, and `NullValue`.

Capabilities should be refreshed at login, after an explicit refresh, after an app-management handoff, and periodically with a conservative time-to-live. An endpoint probe is acceptable when an app exposes no capability, such as Recognize WebDAV or the Memories describe endpoint.

## Repository boundary

Feature screens consume repositories rather than adapters:

```kotlin
sealed interface ResourceState<out T> {
    data object Loading : ResourceState<Nothing>
    data class Ready<T>(
        val value: T,
        val isRefreshing: Boolean,
        val isStale: Boolean,
    ) : ResourceState<T>
    data class Failed<T>(
        val cachedValue: T?,
        val error: NextcloudError,
    ) : ResourceState<T>
}

interface ActivityRepository {
    fun stream(filterId: String = "all"): Flow<ResourceState<ActivityFeed>>
    suspend fun refresh(filterId: String = "all")
    suspend fun loadNextPage(filterId: String = "all")
}
```

Repositories provide stale-while-revalidate behavior:

1. Emit valid cached content immediately.
2. Mark it as refreshing when the network is available.
3. Merge a successful response transactionally into metadata storage.
4. Keep cached content and expose a non-blocking error when refresh fails.
5. Show a blocking error only when no usable cached content exists.

Adapters remain stateless except for immutable capability/version configuration. Repositories own cursors, ETags, local dirty state, retry policy, and cache invalidation.

## Files adapter

Primary API: authenticated WebDAV below `/remote.php/dav/files/{userId}`.

Typed file metadata must include:

```kotlin
enum class FilePermission {
    Read, Write, CreateFile, CreateDirectory,
    Delete, Rename, Move, Share, Mounted,
}

data class NextcloudFile(
    val fileId: Long?,
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val size: Long?,
    val modifiedAt: String?,
    val etag: String?,
    val permissions: Set<FilePermission>,
    val hasPreview: Boolean,
    val favorite: Boolean,
    val lock: FileLock?,
    val isMountRoot: Boolean,
    val isEncrypted: Boolean,
)
```

Parse `oc:permissions` as follows: `G` readable, `W` writable file, `C` create file, `K` create folder, `D` deletable, `N` renameable, `V` moveable, `R` shareable, `M` mounted, and `S` shared. Request `nc:has-preview`, `oc:favorite`, `nc:lock*`, `nc:is-mount-root`, and `nc:is-encrypted` with normal DAV properties.

Read actions:

- `PROPFIND Depth: 1` for folder contents
- `SEARCH` or `REPORT` for supported collections
- core preview by file ID for thumbnails
- authenticated WebDAV `GET` for the original file
- ranged `GET` when the server supports it for media

Write actions:

- `PUT` for upload and content replacement
- `MKCOL` for folders
- `MOVE` and `COPY` with `Destination`
- `DELETE`
- `PROPPATCH` for favorites

Text edits and replacement uploads must send the last known ETag with `If-Match`. A precondition failure becomes a typed conflict containing the base, local, and current remote states. `MOVE` and `COPY` default to `Overwrite: F`; an existing destination is a user decision, not an automatic overwrite.

Preview cache keys include account ID, file ID, ETag, requested dimensions, crop mode, and decoder variant. Full originals are temporary cache entries unless explicitly marked available offline.

## Activity adapter

Primary endpoints:

- `GET /ocs/v2.php/apps/activity/api/v2/activity`
- `GET /ocs/v2.php/apps/activity/api/v2/activity/{filter}`
- `GET /ocs/v2.php/apps/activity/api/v2/activity/filters`

Use Basic app-password authentication, `Accept: application/json`, and `OCS-APIRequest: true`. Query parameters include `since`, `limit`, `sort`, and, when advertised, `previews=1`.

```kotlin
data class ActivityFilter(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val priority: Int,
)

data class ActivityItem(
    val id: Long,
    val datetimeIso: String,
    val appId: String,
    val type: String,
    val actorId: String?,
    val subject: String,
    val message: String?,
    val iconUrl: String?,
    val linkUrl: String?,
    val objectType: String?,
    val objectId: Long?,
    val objectName: String?,
    val previews: List<ActivityPreview>,
)

data class ActivityPage(
    val items: List<ActivityItem>,
    val nextSince: Long?,
    val firstKnown: Long?,
    val etag: String?,
    val hasMore: Boolean,
)
```

Use `X-Activity-Last-Given` or the `Link rel="next"` URL for pagination. Do not blindly follow an absolute `Link`; extract supported parameters after validating the same origin and expected endpoint.

Normal stream and filter reads are safe. Current Activity server code marks corresponding notification records processed when an object-specific request supplies `object_type` and `object_id`. That form is not semantically read-only and is deferred until the UI communicates this behavior.

## Notes adapter

Primary base URL: `/index.php/apps/notes/api/v1`.

Use Basic app-password authentication and JSON request/response bodies. This is not an OCS-wrapped endpoint and does not require `OCS-APIRequest`.

```kotlin
data class NextcloudNote(
    val id: Long,
    val etag: String?,
    val readOnly: Boolean,
    val modifiedEpochSeconds: Long?,
    val title: String,
    val category: String,
    val content: String?,
    val favorite: Boolean,
)

data class NotePatch(
    val content: String? = null,
    val title: String? = null,
    val category: String? = null,
    val favorite: Boolean? = null,
    val modifiedEpochSeconds: Long? = null,
)

data class NotesPage(
    val items: List<NextcloudNote>,
    val nextChunkCursor: String?,
    val pendingCount: Int?,
    val etag: String?,
    val lastModified: String?,
)
```

List fields are nullable where `exclude`, `pruneBefore`, or an older minor API may omit them. Supported versions come from `capabilities.notes.api_version` and the `X-Notes-API-Versions` response header. Prefer API v1. Require at least v1.2 before enabling safe edits because it adds ETags, read-only state, chunking, and settings. API v1.4 adds attachments.

Listing may use `chunkSize` and repeat `chunkCursor` until `X-Notes-Chunk-Cursor` is absent. Delta refresh can use the prior `Last-Modified` value as `pruneBefore` plus `If-None-Match` with the list ETag.

Every update must check `readOnly == false` and send `If-Match` with the last known note ETag. HTTP 412 contains the current server note and must open conflict resolution. Local drafts may be saved offline, but a missing remote note is not silently recreated during synchronization. Deletion requires confirmation and is not queued by default.

## Photos and Memories adapters

`PhotosAdapter` is the universal fallback. It uses WebDAV media search, file properties, previews, and original WebDAV downloads. It should support timeline/grid metadata even if neither Memories nor face recognition is installed.

`MemoriesAdapter` provides an optimized timeline when the Memories app is available:

- probe `GET /index.php/apps/memories/api/describe`
- `GET /index.php/apps/memories/api/days`
- `GET /index.php/apps/memories/api/days/{dayId}`
- image preview, information, stream, and cluster endpoints only for known compatible versions

```kotlin
data class MediaDay(
    val dayId: Long,
    val count: Int,
)

data class MediaItem(
    val fileId: Long,
    val dayId: Long,
    val etag: String?,
    val baseName: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val takenAtEpochSeconds: Long?,
    val durationSeconds: Double?,
    val isFavorite: Boolean,
    val isVideo: Boolean,
    val faceDetectionId: Long?,
    val faceRect: FaceRect?,
)
```

The full-screen viewer loads a sized preview first and the original on demand. The original is not permanently imported into the device gallery. Image edits produce a new file by default. Replacing an original requires a separate explicit choice, ETag protection, and conflict handling.

The Memories API is app-specific. The adapter must be gated by its describe response and tested app-version range. If a request becomes unsupported, disable that optimized feature and fall back to `PhotosAdapter` rather than falling back to a web view.

## People adapter

Official Photos consumes the Recognize virtual WebDAV tree:

- `/remote.php/dav/recognize/{userId}/faces`
- `/remote.php/dav/recognize/{userId}/faces/{person}`
- `/remote.php/dav/recognize/{userId}/unassigned-faces`

Request `nc:face-detections`, `nc:face-preview-image`, `nc:realpath`, and `nc:nbItems`. A safe `PROPFIND` probe determines support; HTTP 404 means unavailable. Memories can provide a more efficient cluster list through `/index.php/apps/memories/api/clusters/recognize` or `/facerecognition` when its versioned adapter is enabled.

```kotlin
enum class PersonBackend { Recognize, FaceRecognition }

data class PersonCluster(
    val backend: PersonBackend,
    val id: String,
    val displayName: String,
    val count: Int,
    val coverFileId: Long?,
    val coverEtag: String?,
    val userId: String?,
)
```

Recognize actions map to virtual WebDAV operations: `MKCOL` creates a person, `MOVE` renames a person or assigns a detection, and `DELETE` removes an assignment or person. These actions change recognition metadata rather than the source image, but removal and merge can be hard to reverse and require confirmation.

FaceRecognition uses a different API and receives its own versioned implementation. Do not infer that Recognize actions work for both backends.

## Client Integration adapter

The official Client Integration capability is the primary safe mechanism for understanding file actions exposed by arbitrary server apps.

Parse `capabilities.client_integration` entries with version `0.1` and the `context-menu` hook:

```kotlin
data class ClientFileAction(
    val providerAppId: String,
    val name: String,
    val relativeUrlTemplate: String,
    val method: ClientActionMethod,
    val mimeTypePrefixes: List<String>,
    val parameters: Map<String, String>,
    val iconRelativeUrl: String?,
)
```

Only `GET` and `POST` are currently valid. Only `{fileId}` and `{filePath}` placeholders are substituted. URLs must remain relative and same-origin. MIME filters are prefixes, not regular expressions.

Actions appear only after capability, version, URL, method, and MIME validation. Execution is always initiated by a visible user tap, including advertised GET actions because they may start server work. Initially render only the documented tooltip response and declarative vertical rows containing text and relative URL elements. Unknown response versions or elements are not rendered.

## Admin adapter

Read-only discovery can use:

- legacy `GET /ocs/v1.php/cloud/apps?filter=enabled|disabled`
- legacy `GET /ocs/v1.php/cloud/apps/{appId}`
- newer `GET /ocs/v2.php/apps/appstore/api/v1/apps`

The newer app-store response exposes compatibility, missing dependencies, internal status, group restrictions, available updates, and optional catalogue details. Authorization must be determined from the endpoint response. Do not infer administrator rights from a group name because delegated administration and server policy vary.

App installation, enabling, disabling, uninstalling, and updating are privileged server mutations. The official provisioning and app-store controllers apply `PasswordConfirmationRequired`; strict operations validate the real account password against a recently authenticated server session.

**A stored Login Flow app password cannot satisfy strict administrator password confirmation.** Nextcloud Native must not collect, store, or reuse the user's primary account password for v1 administration features.

Therefore the v1 admin experience is:

1. Display installed/enabled state and compatible catalogue details when authorized.
2. Explain dependencies and the effect of the requested change.
3. Ask for explicit confirmation before leaving the app.
4. Open the server's authenticated administration page for installation, enable, disable, uninstall, or update.
5. Refresh capabilities and navigation after the user returns.

Authenticated web handoff is required for v1 admin mutations. Direct mutation endpoints remain unavailable even if an app password happens to receive a non-strict response on a particular server version.

## Cache and offline model

Use a shared multiplatform database, preferably SQLDelight, for metadata and operation state. Platform filesystem implementations store blobs.

### Metadata store

All records are scoped by an opaque local account ID. Minimum tables or equivalent stores:

- capability snapshots and fetch time
- file nodes keyed by account, file ID, and path
- activity items, filters, and pagination cursors
- notes, base snapshots, ETags, local dirty state, and deletion state
- media days/items and person clusters
- blob-cache metadata and last access time
- pending operations and attempt history

Credential material never enters this database.

### Blob cache

Use content-addressed or deterministic keys containing account ID, remote identity, ETag, and variant. Write into a temporary file and atomically rename only after a complete successful response. Enforce per-account and global size limits using least-recently-used eviction.

Cache classes:

- thumbnails and previews: automatic, bounded, evictable
- temporary originals: automatic after explicit view, aggressively evictable
- available-offline originals: explicit, not evicted without user action or storage pressure confirmation
- local drafts and conflict copies: durable until resolved

Never modify a downloaded original in place.

### Pending operations

```kotlin
data class PendingMutation(
    val id: String,
    val accountId: String,
    val kind: PendingMutationKind,
    val target: RemoteTarget,
    val baseEtag: String?,
    val payloadReference: String?,
    val createdAtEpochMillis: Long,
    val attemptCount: Int,
)
```

Only operations with defined idempotency or conflict behavior may be queued. Suitable early candidates are ETag-guarded note/text saves and uploads with stable temporary names. Do not queue Talk messages, deletes, face removal/merge, or administrator changes by default.

Android schedules synchronization with WorkManager and network constraints. Desktop uses a lifecycle-bound scheduler and may later add operating-system background integration. Both call the same repository synchronization logic.

## Mutation and confirmation levels

Every adapter operation declares a policy level:

| Level | Meaning | Examples | UI rule |
|---|---|---|---|
| 0: Read | No intended remote mutation | list files, previews, normal Activity feed, read notes | Run on screen load or refresh. |
| 1: Reversible explicit | Small, visible, readily reversible write | favorite toggle, non-overwriting rename | Require a direct user gesture; optimistic UI may roll back on failure. |
| 2: Guarded write | Changes user content with concurrency protection | save text/note, upload, move | Require a user gesture, permission check, base ETag where applicable, and conflict UI. |
| 3: Destructive | Deletes, overwrites, or hard-to-reverse metadata changes | recursive delete, replace original, remove/merge person, delete note | Show a target-specific confirmation and consequences. Never run in background retry without renewed intent. |
| 4: Privileged handoff | Server administration or primary-password confirmation | install, enable, disable, uninstall, update app | Explain and confirm, then hand off to authenticated server administration. |

An adapter response can raise the level at runtime, for example when a move would overwrite an existing target. It can never silently lower the declared level.

## Incremental implementation order

1. Add case-insensitive response headers to the transport and preserve a typed capability snapshot.
2. Extend file metadata with permissions, favorite, lock, mount-root, and encryption state.
3. Complete Files previews, full original viewing, native text editing, ETag conflicts, and non-overwriting move/copy.
4. Add the read-only Activity adapter, filters, preview capability, and cursor pagination.
5. Add Notes list/detail, then v1.2 guarded save and conflict resolution. Add offline drafts after online behavior is verified.
6. Add Photos full-screen media and layout preferences, then the version-gated Memories day timeline.
7. Add read-only Recognize people and contents, followed by explicit rename and assignment. Add remove/merge only with confirmation UX.
8. Add Client Integration capability actions with the documented v0.1 response renderer.
9. Introduce the shared metadata store, stale-while-revalidate repositories, blob cache, and a narrowly scoped pending-operation queue.
10. Add read-only admin status/catalogue with authenticated web handoff. Direct administrator mutation remains outside v1.

Each step must build and test Android and desktop. Protocol parsers and repository merge/conflict behavior require shared unit tests. Platform integration tests cover secure storage, cache paths, external handoff, and scheduling.

## Primary references

- [Nextcloud WebDAV operations and properties](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html)
- [Nextcloud Activity API v2](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/activity-api.html)
- [Activity API controller behavior](https://github.com/nextcloud/activity/blob/master/lib/Controller/APIv2Controller.php)
- [Nextcloud OCS authentication and headers](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/OCS/ocs-api-overview.html)
- [Notes API capabilities and versioning](https://github.com/nextcloud/notes/blob/main/docs/api/README.md)
- [Notes API v1 endpoints and concurrency](https://github.com/nextcloud/notes/blob/main/docs/api/v1.md)
- [Nextcloud Client Integration API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/ClientIntegration/index.html)
- [Photos Recognize discovery](https://github.com/nextcloud/photos/blob/master/src/mixins/FetchFacesMixin.ts)
- [Photos Recognize actions](https://github.com/nextcloud/photos/blob/master/src/store/faces.ts)
- [Recognize virtual WebDAV implementation](https://github.com/nextcloud/recognize/tree/main/lib/Dav/Faces)
- [Memories routes](https://github.com/pulsejet/memories/blob/master/appinfo/routes.php)
- [Memories API endpoint definitions](https://github.com/pulsejet/memories/blob/master/src/services/API.ts)
- [Memories media and cluster models](https://github.com/pulsejet/memories/blob/master/src/typings/data.d.ts)
- [Nextcloud provisioning app controller](https://github.com/nextcloud/server/blob/master/apps/provisioning_api/lib/Controller/AppsController.php)
- [Nextcloud app-store administration controller](https://github.com/nextcloud/server/blob/master/apps/appstore/lib/Controller/ApiController.php)
- [Nextcloud password-confirmation middleware](https://github.com/nextcloud/server/blob/master/lib/private/AppFramework/Middleware/Security/PasswordConfirmationMiddleware.php)
- [Android offline operation worker](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/nextcloud/client/jobs/offlineOperations/OfflineOperationsWorker.kt)
- [Android thumbnail cache](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/owncloud/android/datamodel/ThumbnailsCacheManager.java)
- [Desktop sync journal](https://github.com/nextcloud/desktop/blob/master/src/common/syncjournaldb.h)
- [Notes Android synchronization state machine](https://github.com/nextcloud/notes-android/blob/main/app/src/main/java/it/niedermann/owncloud/notes/persistence/NotesServerSyncTask.java)
- [Memories Android local media synchronization](https://github.com/pulsejet/memories/blob/master/android/app/src/main/java/gallery/memories/service/TimelineQuery.kt)
