# Nextcloud Native product and engineering roadmap

Status: working roadmap, 2026-07-23

Nextcloud Native is an independent Obiente project. It is not affiliated with Nextcloud GmbH. The goal is not merely to collect many Nextcloud apps behind one launcher. The goal is to become the most trustworthy, coherent, and useful Nextcloud client on every supported platform.

This roadmap is dependency-driven rather than date-driven. Milestones advance only when their acceptance gates pass. Feature count does not override data safety, battery use, accessibility, protocol correctness, or preservation of originals.

## 1. Product promise

Nextcloud Native should make a self-hosted cloud feel like one operating-system service:

- files appear in native pickers and file managers;
- selected folders remain genuinely available offline and synchronize both ways;
- Markdown vaults and other frequently edited folder trees converge without silent loss;
- messages, calls, photos, documents, calendars, contacts, tasks, and app data share one visual and interaction language;
- every remote action is represented by a typed capability and permission, not guessed from a web page;
- unsupported apps receive an honest native metadata/schema fallback, never an embedded website presented as native;
- originals, local drafts, conflict copies, and version history remain safe;
- credentials and sensitive URLs remain on the device and out of logs;
- Android, iOS, Windows, macOS, and Linux use the same domain rules while respecting each operating system's native integration points.

"Best Nextcloud client" will be measured primarily by trust and integration:

1. **No silent data loss.** Ambiguous writes become visible pending/unknown states. Concurrent edits become explicit conflicts.
2. **Native where it matters.** Filesystem providers, share sheets, notifications, background transfer, calls, media, shortcuts, and accessibility use platform APIs.
3. **Fast from cache.** Previously visited content appears immediately and refreshes without replacing useful content with spinners.
4. **Predictable offline behavior.** Users can distinguish cached, pinned, dirty, conflicted, uploading, and online-only content.
5. **One object, one identity.** A file shared in Talk, shown in Activity, opened in Photos, or synchronized for Office resolves to the same account-scoped file entity.
6. **Transparent limits.** Private or missing APIs, server policy, secure-view restrictions, and unsupported formats are explained rather than bypassed.

## 2. Non-negotiable product rules

- No WebView is used as the automatic fallback for an installed app.
- No endpoint, payload, permission, or idempotency property is invented by AI or UI inference.
- The UI never parses JSON, XML, DAV properties, or protocol headers.
- Every write declares its conflict, retry, confirmation, and offline policy before it is enabled.
- Unknown or ambiguous network outcomes are not blindly retried for non-idempotent operations.
- Full files are not retained merely because the user viewed a preview. "Available offline" is explicit.
- Image and document edits create a new file by default. Replacing an original is a separate, ETag-guarded action.
- A stored Login Flow app password is never treated as the account password.
- Strict administrator operations never cause the primary password to be stored.
- Direct-download, WOPI, push, share, and call tokens are secret material and are redacted from logs, crash reports, notifications, and analytics.
- Telemetry is optional, off by default, locally inspectable, and never contains filenames, paths, message text, contact data, server URLs, tokens, or account identifiers.

These rules extend the boundaries in [ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md), [NATIVE_SCHEMA.md](NATIVE_SCHEMA.md), and [PLATFORMS.md](PLATFORMS.md).

## 3. Current baseline

The repository already provides a meaningful prototype:

- Login Flow v2 and platform credential storage on Android and Linux desktop;
- authenticated native Files, Photos/Memories, People, Activity, Talk messaging, Notes, file preview, text editing, and media viewing;
- ETag-aware text/Notes writes;
- conditional, metadata-first Notes reads with cached ETags, stale-while-revalidate detail loading,
  and a bounded native Markdown edit/preview flow;
- typed Talk rich messages, files, calls, and system events with cursor-paged, read-state-safe
  history requests and preview-only enforcement for hide-download attachments;
- a Rust OpenAPI-to-native-schema compiler plus a Compose adaptive renderer;
- automatic acquisition and local verification of exact-version signed App
  Store packages and exact App Store-linked source tags, with packaged OpenAPI compilation,
  bounded app-local controller inheritance, verified static-route CRUD, and guarded write forms;
- reusable callable-route planning that rejects unresolved detail paths, binds documented API
  versions without guessing, and preserves view/navigation state across Android recreation;
- a live generic Cospend proof: canonical project list/detail reads and
  generated declared actions without a Cospend-specific runtime adapter;
- shape-driven native tables, nested kanban lanes/cards, financial summaries, and chart
  surfaces shared by Tables, Deck, Cospend, Budget, and future similar apps;
- native semantic Mail, Music, and Cookbook flows with mailbox/message bodies, artist/album/track
  hierarchy, artwork, sparse recipe detail, and type-preserving editable settings;
- a native Dashboard with adaptive widget cards, incremental item cursors, safe app deep links,
  and an editable capability-gated User Status surface with rotation-persisted drafts and explicit
  confirmation for every presence or message change;
- reusable household/task semantics proven against Chores 0.1.0, including parent-scoped list
  inference, named-array worklog flattening, recurrence, assignment, points, and completion history;
- null-safe, cursor-paged Activity with refresh preservation and typed notification plans;
- shared CardDAV/CalDAV discovery, sync-token paging, contact/event/task semantics, and
  conflict-safe request planning;
- permission-aware Office document metadata, conditional capability/template discovery, raster
  preview, and explicit same-origin direct-editing handoff without an embedded web surface;
- exact-MIME integration planning for Whiteboard and Draw.io, canonical Office-to-richdocuments
  capability mapping, and honest authentication-required states for external integrations;
- bounded, same-file DAV version-history discovery and ranged historical reads with inventory plus
  reachability fallback when optional Files capability blocks are absent;
- provider-driven global Nextcloud search with per-provider filtering,
  pagination, partial failures, and automatic Mail discovery;
- native Memories album and system-tag collection contracts with paged media
  grids, cover previews, favorites, RAW/JPEG stacking, signature-checked source fallback, and
  explicit zoom-gated full-quality selection;
- verified native Memories routes acquired from its exact tagged source when the signed package
  does not contain OpenAPI, while retaining a metadata-only, zero-action final fallback;
- a native administrator app catalog with read-only inventory/update discovery, capability-gated
  lifecycle plans, destructive uninstall warnings, and strict primary-password/session boundaries;
- Android and desktop builds from shared Compose code;
- official-source research for Files, Activity, Talk, Photos, Memories, Recognize, Notes, Deck, Tasks, Tables, Office, Cookbook, Cospend, Contacts, Calendar, Mail, Music, GitHub integration, and app administration.

The remaining roadmap must complete the sync-client acceptance gates, consolidate shared network and protocol code, replace process-local workflow state where durability is required, complete Files actions, and implement Talk calling. The milestone order below follows those dependencies.

## 4. Target architecture

```text
Compose feature UI and platform surfaces
    |
Typed feature repositories and use cases
    |-- ResourceState<T>: cached, refreshing, stale, failed
    |-- action policy: permission, risk, idempotency, confirmation
    |
Shared metadata/cache/sync engine
    |-- account-scoped SQLite/SQLDelight database
    |-- sync journal, tombstones, pending operations, conflicts
    |-- blob cache and offline pin manager
    |
Typed adapters
    |-- Files/DAV, Shares, Activity, Notes, Talk, Media, DAV groupware
    |-- productivity adapters, generic schema adapters, admin inventory
    |
Shared transport
    |-- HTTP + OCS + JSON + safe XML + DAV methods + streaming/ranges
    |-- authentication, same-origin redirects, bounded responses, redaction
    |
Platform integrations
    |-- secure storage, filesystem provider, background scheduler
    |-- notifications/push, media/RTC, share/open-with, keychain
    `-- filesystem watchers, file coordination, release/update integration
```

### 4.1 Shared module boundary

The intended repository split is:

| Module | Responsibility | Must not contain |
|---|---|---|
| `core-model` | Account, file, action, conflict, cache, sync, capability, and error types | HTTP or Compose |
| `transport` | Request execution, authentication, streaming, DAV verbs, same-origin policy, redaction | App-specific parsing |
| `storage` | SQLDelight schema, migrations, transactions, blob metadata, encryption hooks | Credentials |
| `sync-engine` | Reconciliation plan, journal, tombstones, retries, conflict generation | Platform UI |
| `adapters-*` | Versioned protocol translation and capability gates | Platform APIs or presentation state |
| `repositories` | Cached state, pagination, refresh, pending writes, cross-adapter identity | Raw JSON/XML |
| `native-schema` | Rust semantic compiler, schema contract, verified adapter registry | Network execution or arbitrary code |
| `ui` | Shared screens/components and accessibility semantics | Endpoint construction |
| platform apps/extensions | Keychain, filesystem provider, workers, notifications, WebRTC, signing | Domain policy duplication |

Near-term runtime repositories, transport, storage, and sync should be Kotlin Multiplatform because the application and platform integrations already use that boundary. The Rust compiler remains the deterministic automatic-adapter engine and exchanges versioned `NativeAppSchema` documents. A later UniFFI/FFI embedding spike is allowed, but production must not maintain two independent semantic compilers.

### 4.2 Account-scoped identity

Every stored object is scoped by a random local account ID, never by username alone. The canonical remote file identity is:

```text
accountId + fileId when supplied by server
accountId + normalized DAV path as a versioned alias/fallback
ETag as content generation, not identity
```

Moves retain file identity when the server retains `oc:fileid`. Path aliases and parent membership update transactionally. Activity objects, Talk attachments, Photos/Memories items, shares, versions, and search results resolve through this identity map.

### 4.3 State vocabulary

All platforms expose the same states:

- online-only;
- metadata-cached;
- preview-cached;
- original-cached temporarily;
- pinned/available offline;
- locally modified;
- upload/download queued;
- synchronizing;
- synchronized;
- conflict;
- blocked by permission/policy;
- failed with retryable or terminal reason;
- delivery/commit outcome unknown.

UI copy and icons must not collapse these into a single "downloaded" or "error" state.

## 5. Foundational dependency gates

Later milestones refer to these gates.

| Gate | Deliverable | Required proof |
|---|---|---|
| G0: transport | One shared streaming transport with DAV verbs, OCS helpers, safe XML, response headers, cancellation, redirect policy, and redaction | Android + desktop contract tests against a mock server and a real test server |
| G1: capabilities | Typed account capability snapshot plus opaque sealed JSON tree, versioned adapter manifests, and refresh invalidation | Unknown fields/versions survive; app enable/disable refresh changes routing |
| G2: metadata store | Account-scoped SQLDelight/SQLite schema, migrations, transactions, indexes, and encrypted-storage hooks | Upgrade/downgrade fixtures, process-kill recovery, no credentials in DB |
| G3: blob store | Atomic content-addressed cache with quotas and pin classes | Partial downloads never appear complete; eviction never removes dirty/conflict data |
| G4: sync journal | Base ETag/hash, local/remote generations, tombstones, pending operations, conflicts, retry history | Deterministic reconciliation tests and crash recovery at every transition |
| G5: background scheduler | Shared work descriptions plus Android/iOS/desktop scheduling drivers | Constraints, cancellation, backoff, reboot/account removal, and user-visible progress tested |
| G6: notification/push | Account-safe push registration, decryption/handoff, notification routing, privacy controls | Multi-account, token rotation, logout, locked-device, and no-content modes tested |
| G7: RTC platform layer | Permission, audio route, camera, screen share, WebRTC peer/media, and call lifecycle interfaces | Platform loopback and real Talk test-room suites |
| G8: release trust | Signing, SBOM, dependency scanning, reproducible configuration, migrations, privacy docs | Release candidate can be independently built and upgraded without data loss |

## 6. Milestone overview

| Milestone | Outcome | Depends on |
|---|---|---|
| M0 | Shared trustworthy core | G0-G4 foundations |
| M1 | Best-in-class online Files | M0 |
| M2 | Android cloud filesystem | M1, G3, G5 |
| M3 | Obsidian-grade selective two-way sync | M0-M2, G4-G5 |
| M4 | Complete Files collaboration and transfer | M1-M3 |
| M5 | Native notification substrate | M0, G5-G6 |
| M6 | Full native Talk messaging and calling | M5, G7 |
| M7 | Photos/Memories/media excellence | M0-M4 |
| M8 | Documents and Office | M1-M4, G3 |
| M9 | Productivity and groupware | M0, M5 |
| M10 | Automatic and verified adapters | G0-G2, stable schema contract |
| M11 | Safe administration | M0, M5, server auth research |
| M12 | Five-platform product and stable release | Feature milestones, G8 |

Work may proceed in parallel only when dependencies are satisfied. For example, media UI can advance while sync is being built, but media offline pinning cannot ship before the shared blob and journal rules exist.

## 7. M0: shared trustworthy core

### Scope

1. Move duplicated Android/desktop HTTP and protocol helpers behind `NextcloudTransport`.
2. Preserve response headers case-insensitively, streaming bodies without whole-file buffering.
3. Add typed error categories: authentication, permission, capability missing, conflict, quota, rate limit, transient server, offline, TLS, malformed response, cancelled, and ambiguous result.
4. Persist capability snapshots and adapter compatibility ranges.
5. Implement the metadata database, migration harness, blob index, sync journal, and operation ledger.
6. Add structured logging with automatic secret/path redaction and per-feature debug export that the user explicitly chooses.
7. Add fake clock, fake filesystem, deterministic network fault injector, and HTTP fixture server.

### Acceptance criteria

- Existing Files, Notes, Activity, Talk, and Memories reads run through the shared transport on Android and desktop.
- No UI code constructs endpoint URLs or parses transport payloads.
- A response can be cancelled while connecting, streaming, decoding, or writing cache state.
- A killed process cannot leave a database row pointing at an incomplete blob as complete.
- Account removal transactionally deletes credentials, scheduled work, metadata, temporary blobs, offline content after confirmation, push registrations, and filesystem roots.
- Logs and test crash reports contain no Basic auth, app password, filename/path, message body, WOPI/direct link, share token, or push payload.
- Migration tests cover every released schema version from the first public alpha onward.

### Principal risks

- A rushed transport migration can regress working prototype screens. Move one adapter at a time behind contract tests.
- SQLDelight support and encryption choices vary by target. Store secrets separately regardless of database encryption, and define an explicit threat model before promising encrypted offline files.

## 8. M1: best-in-class online Files

The protocol source of truth is the official [Nextcloud WebDAV API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html) together with version-pinned compatibility tests in this repository.

### Scope

- Parse the full required DAV property set, including permissions, favorite, preview, file ID, ETag, locks, mount roots, encryption, owner, comments, share types, metadata ETag, and hide-download.
- Add stable folder snapshots, stale-while-revalidate, breadcrumb navigation, server search, sort/filter, grid/list density, multi-selection, recent/favorite/shared/offline/conflict views.
- Add one provider-driven global search entry point for Files and every installed app that registers a Nextcloud unified-search provider. External providers remain explicit opt-in.
- Complete native open routing: media viewer, text/Markdown editor, PDF/document preview, platform open-with, and truthful unavailable state.
- Implement create folder/file, rename, move, copy, favorite, delete-to-trash, restore, permanent delete, version list/download/restore, and explicit original download.
- Map every visible action to `oc:permissions` and server capabilities. Unknown permission flags remain forward-compatible.
- Add multi-account switching without sharing cache/auth state.

### Acceptance criteria

- Cached folder content paints before refresh and retains content on a refresh failure.
- A 10,000-item test folder can be browsed, searched, selected, and refreshed without loading all previews or blocking UI input.
- `MOVE` and `COPY` default to `Overwrite: F`; collision UI names both targets and never chooses for the user.
- Text saves use `If-Match`; HTTP 412 opens a conflict flow with base/local/remote versions.
- Virtual album deletion and source-file deletion have different labels, confirmations, and tests.
- Mounted/read-only/encrypted/locked files expose only valid actions.
- Root and subpath installations, Unicode, normalization, long names, reserved platform names, and case-only renames pass fixtures.

## 9. M2: Android cloud filesystem integration

Android's official [`DocumentsProvider`](https://developer.android.com/reference/android/provider/DocumentsProvider) is designed for durable local or cloud documents and requires stable document IDs, fast metadata queries, capability flags, cancellation, and system-mediated URI grants. The official Nextcloud Android client provides a useful comparison in [`DocumentsStorageProvider.java`](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/owncloud/android/providers/DocumentsStorageProvider.java), including its asynchronous folder refresh and local-cache behavior.

### Product modes

Android exposes two complementary modes:

1. **Cloud filesystem root:** every account appears in the system picker. Metadata comes from the local database; file content hydrates on open.
2. **Offline synced folder:** selected remote folders are mirrored into durable local storage for apps that require normal, repeatedly writable local files.

DocumentsProvider alone is not advertised as sufficient for Obsidian until tested against the actual Obsidian Android filesystem behavior. The offline mirror is the correctness baseline for a vault.

### DocumentsProvider implementation

- One root per account with an opaque stable root ID. Lock/logout/root changes call `notifyChange` on the roots URI.
- Stable document ID based on local account ID + remote file ID where available, with a persisted surrogate for path-only resources. IDs do not change on rename.
- `queryRoots`, `queryDocument`, and `queryChildDocuments` read only the local database and return quickly. They never perform blocking network I/O.
- Expired directory metadata returns cached rows with `DocumentsContract.EXTRA_LOADING`; a worker refreshes and notifies the directory URI.
- Flags derive from DAV permissions: create, write, delete, rename, move, copy, thumbnail, metadata, and subtree support are never over-advertised.
- `openDocumentThumbnail` uses the shared preview cache and respects cancellation.
- Read open hydrates into an atomic cache file, supports cancellation/range resume, and returns a seekable descriptor when the consumer requires it.
- Write open uses a private staging file. The close callback seals content, records its base ETag, and enqueues an ETag-guarded upload. It never exposes a partially uploaded remote file as committed.
- Create, rename, move, copy, and delete update local state only after the remote result is known, or enter a visible pending/unknown state when the result is ambiguous.
- `isChildDocument`, document path, recent, and search behavior are implemented and tested against the Android system picker.
- App lock behavior is explicit: hiding roots is not enough if other apps retain URI grants. The security design documents which previously granted files remain readable and offers account removal/revocation guidance.

### Acceptance criteria

- System Files and at least five independent SAF clients can browse, create, edit, rename, move, and delete supported files.
- Metadata queries make zero network calls and meet a provisional p95 target of 100 ms on a 10,000-node cached account.
- Killing the process during write, close, upload, rename, or notification cannot produce a silent zero-byte remote file.
- Two concurrent opens of one writable document either serialize safely or produce an explicit conflict.
- Network loss during hydration produces a retryable provider error without corrupting a previous cached generation.
- Permission flags match DAV behavior in positive and negative integration tests.
- Account logout removes or invalidates roots immediately and cannot leak another account's cached content.

### Risks

- Provider consumers differ in open modes, descriptor seek behavior, and atomic-save patterns. Maintain a compatibility suite rather than coding only for the Android Files app.
- Binder/provider calls have strict latency expectations. All remote work must remain outside query methods.
- Some consumers treat close as durable commit. UI and notifications must expose uploads that are still pending after close.

## 10. M3: selective, offline, continuous two-way sync

This is the highest-trust feature in the roadmap. The official Nextcloud desktop client maintains a sync journal and creates a local conflicted copy when local and remote both change; see the official [conflict behavior](https://docs.nextcloud.com/server/latest/user_manual/en/desktop/conflicts.html), [`syncengine.cpp`](https://github.com/nextcloud/desktop/blob/master/src/libsync/syncengine.cpp), and [`syncjournaldb.cpp`](https://github.com/nextcloud/desktop/blob/master/src/common/syncjournaldb.cpp). The Android client also has an [`InternalTwoWaySyncWork`](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/nextcloud/client/jobs/InternalTwoWaySyncWork.kt), but Nextcloud Native requires a shared, fully journaled implementation with stronger conflict visibility.

### Sync set model

Each sync set contains:

- account ID and remote root identity/path;
- platform local-root bookmark/URI/path;
- mode: offline mirror, upload-only camera, download-only archive, or bidirectional;
- include/exclude rules and hidden-file policy;
- network policy: any, unmetered, Wi-Fi SSID optional, roaming allowed;
- power policy: charging-only optional, battery-saver behavior;
- file-size and media policies;
- deletion policy and trash behavior;
- schedule plus foreground/manual override;
- last complete scan, partial cursors, and current health.

### Reconciliation algorithm

The journal stores, per path/identity:

```text
remote file ID and path
base ETag, size, mtime and optional content hash
local stable identity where available, path, size, mtime and hash
last synchronized generation
local and remote tombstones
pending operation and retry state
conflict relationship
```

Files DAV does not provide a universal collection sync token. The engine therefore combines local filesystem events, cached directory ETags/metadata, targeted `PROPFIND`, server Activity/push as refresh hints, and periodic full reconciliation. Hints may accelerate a scan but never replace eventual comparison.

One reconciliation cycle is:

1. Freeze a consistent journal snapshot.
2. Scan local changes using watcher hints plus a bounded verification walk.
3. Fetch remote metadata for changed/scheduled directories.
4. Compare local, remote, and base generations.
5. Produce an immutable plan with no I/O side effects.
6. Detect case, normalization, reserved-name, type-change, move, delete, and quota conflicts.
7. Apply operations in dependency order using staging names and atomic local replacement.
8. Commit each successful transition transactionally; retain resumable state after cancellation/crash.
9. Run a verification pass before declaring synchronized.

### Conflict and version semantics

- If only local changed, upload with the base ETag/precondition.
- If only remote changed, download to a temporary file, verify, then atomically replace local content while preserving a recoverable prior generation until commit.
- If both changed, keep the canonical path on the current remote generation and create a local conflict copy containing the local generation. Do not upload the conflict copy by default.
- For UTF-8 text/Markdown with a stored base, offer a three-way merge preview. Automatic merge is opt-in and only commits after a clean merge plus user policy; binary files never auto-merge.
- A rename is identity-preserving only when file ID/journal evidence is strong. Ambiguous delete+create remains separate.
- Delete-vs-edit always conflicts. A tombstone is never allowed to erase an unseen edit.
- Server versions are a recovery aid, not the only local safety mechanism. Version retention is server-policy and quota dependent; see [Nextcloud version control](https://docs.nextcloud.com/server/stable/user_manual/en/files/version_control.html).
- Conflict copies, drafts, and pre-replacement backups are durable until the user resolves them.

### Obsidian vault profile

The vault profile defaults to:

- bidirectional, local-first access;
- Markdown, JSON, canvas, CSS, images, and attachment files included;
- `.obsidian/` included, with configurable cache/workspace exclusions rather than a blanket hidden-file exclusion;
- debounce until a file is stable and closed, followed by a short verification delay;
- atomic temporary-file/save/rename sequences recognized as one logical update where journal evidence permits;
- no remote deletion until the local deletion is stable and the remote base is unchanged;
- conflicts surfaced inside the app and with clearly named local copies;
- manual "sync now," pause, and per-vault health/status;
- no battery-intensive continuous polling when Android has suspended the app.

### Background, battery, and network behavior

- Android uses WorkManager for durable reconciliation with network/battery/storage constraints. Exact alarms are not used.
- A user-started large transfer may use a foreground worker/service with a visible cancelable notification, subject to current Android policy.
- Battery Saver stops non-urgent periodic sync; manual sync and active provider opens remain available.
- Metered/roaming policies apply before hydration and transfer; metadata refresh can have a separate low-bandwidth policy.
- Backoff uses server `Retry-After` where available, jitter, and a per-account circuit breaker. Authentication, permission, quota, and conflict errors do not spin.
- Desktop uses filesystem watchers as hints and periodic verification. Watch overflow degrades to a visible rescan, not silent staleness.
- iOS scheduling accepts that the OS controls background runtime. The File Provider extension and BG tasks must converge when invoked; "continuous" cannot mean an always-running daemon on iOS.

### Acceptance criteria

- A reference Obsidian vault passes create/edit/rapid-save/rename/move/delete/attachment/plugin-config tests online and offline.
- Concurrent local and remote edits produce no silent overwrite in a deterministic 10,000-scenario property/fault suite.
- Every operation can be interrupted before network, mid-transfer, after remote commit, and before local journal commit; restart converges or reports outcome unknown without duplicating/destructively retrying.
- A 100,000-file synthetic tree completes bounded incremental scans without unbounded memory growth.
- Offline edits across a device reboot synchronize when constraints allow.
- Delete-vs-edit, case-only rename, Unicode normalization, clock skew, zero-byte file, large file, insufficient local/server space, server rollback, and file-lock cases have explicit expected results.
- A 24-hour Android idle soak performs no exact alarms, no foreground service without visible work, and no retry loop while offline or unauthorized.
- Users can export a redacted sync diagnostic report and identify every excluded, failed, pending, and conflicted file.

## 11. M4: complete Files collaboration, uploads, and sharing

### Upload engine

- Direct `PUT` for bounded small files.
- Official [chunked upload v2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/chunking.html) for large/resumable uploads under `/remote.php/dav/uploads/{uid}/{uploadId}`.
- Persist upload session, chunk map, destination, base precondition, local source generation, bytes sent, retries, and cleanup status.
- Generate cryptographically random upload IDs. Never derive a secret/path from a filename.
- Detect source changes while uploading and restart or conflict rather than committing mixed generations.
- Use stable temporary destination policy and `Overwrite: F` unless the user explicitly chose replacement.
- Preserve mtime/checksum only when the official server contract supports it and verification succeeds.
- Optional bandwidth limits, transfer priority, pause/resume, Wi-Fi/charging rules, and per-account concurrency.
- Camera auto-upload is a separate upload-only sync profile with duplicate detection and configurable remote organization.

### Sharing and collaboration

- OCS share list/create/update/delete with users, groups, circles/teams where advertised, public links, mail shares, permissions, password, expiry, note, hide-download, and reshare rules.
- Native recipient autocomplete with privacy-preserving debounce and cancellation.
- Share audit/details view distinguishes source owner, reshare, inherited/group access, federated target, and public link.
- Direct-download links are short-lived secrets and never stored in history.
- File comments, unread markers, locks, favorites, versions, and trash integrate into the same detail panel.
- Client Integration file actions are capability-validated and user-initiated as defined in [ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md).

### Acceptance criteria

- Multi-gigabyte uploads survive process death, network changes, and reboot without duplicate final files.
- Changing the local source during upload cannot produce a mixed/corrupt remote generation.
- Share mutation UI always names the target, permissions, expiry, download policy, and resulting exposure.
- Share/delete/version/trash actions are permission-negative tested and never optimistically claim success after an ambiguous response.
- Transfer status is visible and cancelable from app, notification, and relevant provider/file surfaces.

## 12. M5: native notification and push substrate

This milestone supports Activity, Talk, shares, sync, calendar reminders, background transfer, and later app adapters.

### Scope

- Typed Notifications app adapter and action routing.
- Push registration per account/device with key rotation and logout cleanup.
- Android variants for Google push where distributable and UnifiedPush for open distribution, following the official Talk Android patterns in [`UnifiedPushService.kt`](https://github.com/nextcloud/talk-android/blob/master/app/src/main/java/com/nextcloud/talk/services/UnifiedPushService.kt).
- APNs integration on Apple platforms; native Windows/macOS/Linux notification bridges.
- Encrypted/minimal push wakes a bounded worker which fetches authoritative data. Notification text is omitted on lock screen when privacy mode requires it.
- Per-account and per-conversation categories, grouping, mute/DND, actions, and deep links.
- Deduplication across push, polling, Activity, and Talk events.

### Acceptance criteria

- Token rotation, push provider change, server re-registration, multi-account delivery, logout, and account removal pass.
- A push payload cannot directly execute a server mutation.
- Duplicate/out-of-order notifications collapse without losing the newest authoritative state.
- Privacy modes cover full preview, sender/title only, generic notification, and disabled.
- Notification actions validate current permissions/state before execution.

## 13. M6: full native Talk messaging and calling

The route/capability baseline is the official [Talk OpenAPI document](https://github.com/nextcloud/spreed/blob/main/openapi.json) and [Talk capabilities](https://github.com/nextcloud/spreed/blob/main/docs/capabilities.md). Official Talk Android is the platform-behavior reference for signaling, media, push, and calls, not code to duplicate blindly.

### M6.1 Messaging completeness

- Cursor-based history and long polling with read/status/notification side effects disabled for background reads.
- Explicit read markers only when the foreground UI decides content was seen.
- Local drafts and outbox; automatic retry only with server-supported `referenceId` idempotency.
- Markdown/rich strings, replies, threads, reactions, mentions, editing/deletion, pinning, polls, reminders, scheduled messages, shared objects, attachments, voice messages, and recordings.
- Upload attachments through the shared upload engine and resolve them to shared file identity.
- Message states: draft, queued, sending, sent, delivery unknown, failed, edited, deleted, expiring.
- Search/media overview and room notification settings.

### M6.2 Call/signaling engine

- Consume Talk call and signaling settings/capabilities rather than hard-coded STUN/TURN assumptions.
- Platform WebRTC engine behind G7: peer connection, codecs, ICE, DTLS/SRTP handled by the established platform library.
- Signaling v3 websocket/poll behavior, reconnect, resume, participant state, federation, and external signaling tested against compatible Talk versions.
- Join/leave, audio/video toggles, camera switch, speaker/Bluetooth/wired routing, screen share, participant grid, moderation, reactions, raise hand, lobby, recording consent, and call recording where advertised.
- Calls are never queued. Network loss enters reconnecting and eventually ends visibly.
- Android call lifecycle uses a foreground service with microphone/camera service types and an ongoing call notification, as the official client does in [`CallForegroundService.kt`](https://github.com/nextcloud/talk-android/blob/master/app/src/main/java/com/nextcloud/talk/services/CallForegroundService.kt).
- iOS uses CallKit/PushKit only within Apple policy; Android evaluates Telecom/ConnectionService integration; desktop integrates system notifications and media device selection.
- Incoming-call push, answer/decline, timeout, missed call, lock-screen privacy, permission denial, audio focus, GSM interruption, Bluetooth changes, headset removal, and app process death have defined state transitions.

### Acceptance criteria

- Messaging background fetch never changes read marker, notification state, or presence.
- Ambiguous message send with no reference-ID support never auto-duplicates.
- One-to-one, group, public, federated, and lobby rooms pass the compatible server matrix.
- Audio-only and video calls pass Wi-Fi/mobile handoff, brief outage/reconnect, Bluetooth, screen rotation, background/foreground, lock/unlock, and permission-revocation tests.
- The app releases microphone/camera and stops foreground call state on every terminal path.
- A call can run for one hour without unbounded memory growth, thermal runaway beyond the platform baseline, or audio drift detectable by the test harness.
- Signaling and media diagnostics are redacted and user-exported only.

### Risks

- Talk call/signaling behavior evolves independently of chat. Maintain a separate compatibility manifest and test servers.
- WebRTC and background-call restrictions are platform-specific. Shared UI does not imply shared media implementation.
- Calls are a security/privacy boundary. A dedicated threat review is required before beta.

## 14. M7: Photos, Memories, RAW, and media excellence

Photos/Files DAV is the universal fallback; Memories is an optimized, version-gated adapter.

### Scope

- Timeline/day/month, regular/dense/justified grids, folders, albums, shared albums, favorites, videos, places, tags, and people.
- Preview-first fullscreen viewer, pinch/pan, swipe, info/EXIF, Live Photos, video streaming, subtitles where supported, original on demand, share/open-with, and offline pin.
- RAW+JPEG/stack awareness, clear derivative/original labels, server RAW previews, and safe original export.
- Album create/rename/delete, add/remove membership, collaborators, location/filter/cover with explicit source-delete distinction.
- Memories day/cluster APIs with describe/version gate, cache headers, transcode/live-photo, map, archive, tags, EXIF, and non-destructive image edit policy.
- People through Memories first; direct Recognize DAV remains disabled when the current separate API-key gate cannot be satisfied through an official external-client flow.
- Background camera auto-upload reuses M4 rather than a separate transfer stack.

### Acceptance criteria

- Switching layout retains selection and scroll anchor.
- A shared Talk image and Activity preview open the identical cached media entity.
- Viewing a preview never claims the original is offline.
- Deleting album membership cannot delete the original; deleting the original has separate confirmation and trash semantics.
- Large timelines scroll without unbounded decoded bitmap memory; cache pressure tests evict previews but not offline originals.
- Video/RAW/unsupported media failures preserve originals and offer explicit download/open-with alternatives.
- Memories unsupported-version/error fallback returns to generic Photos DAV without opening web UI.

## 15. M8: documents and Office

The public boundary is native reading and safe editing where the client can prove the format and conflict model, with capability-gated external Office handoff for richer formats.

### Phases

1. **Native reading:** literal text/Markdown, native multipage PDF, server raster previews for Office formats, search/selection/accessibility where the format permits.
2. **Safe native editing:** text/Markdown and explicitly supported image operations with ETags, versions, conflict UI, and preserve-original defaults.
3. **External Office editing:** parse `files.directEditing` and `richdocuments` capabilities, request a one-time session only after a user tap, and open the system browser/editor handoff. Secret URLs are never persisted/logged.
4. **Native Office feasibility:** evaluate a supported Collabora/LibreOfficeKit mobile/desktop SDK separately on every target. Require WOPI lifecycle, permissions, collaboration, autosave, reconnect, IME, clipboard, accessibility, and fidelity evidence before product commitment.

Reimplementing OOXML/ODF is out of scope. Embedding the Direct Editing page in a WebView is not accepted as native Office.

### Acceptance criteria

- Secure-view/no-download policy blocks source download even when preview fails.
- PDF temp files are encrypted/protected according to the platform threat model and removed after retention policy.
- Text/Markdown conflicts preserve all three generations.
- Direct Editing appears only for an advertised editor/MIME pair and returns through a deliberate external flow.
- Native Office is not labeled supported until the platform feasibility gate passes format-fidelity and collaborative-save tests.

## 16. M9: productivity, groupware, and communication apps

Adapters follow advertised capabilities, official DAV/OCS protocols, version-pinned app contracts, and the safety rules in [ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md).

### Delivery order

1. Notes completion: chunking, attachments, settings, offline drafts, conflict resolution.
2. CardDAV Contacts and CalDAV Calendar/Tasks with sync-token fallback, ETag writes, recurrence/timezone correctness, invitations, and platform contacts/calendar bridges where the user opts in.
3. Deck and Tasks through reusable schema- and data-shape-driven board/list/detail/edit flows, with related-resource label hydration rather than app-specific ID rendering.
4. Cospend, Budget, and Tables through reusable typed table, amount, participant, metric, chart, and form components. Currency and other parent context propagate into child datasets generically.
5. Cookbook with native recipe, ingredient, step, image, search, and offline cooking mode.
6. Mail discovery, search, and schema-declared reads through the automatic runtime first. MIME attachments, drafts, threading, compose, identities, encryption/signing feasibility, and notifications require version-pinned verified action policies where the generic contract cannot prove safe behavior.
7. Music through the documented Subsonic-compatible boundary first, with secure credential bootstrap and platform media session/download support.
8. Read-only GitHub integration with authenticated web handoff for OAuth/settings and no attempt to extract server-stored tokens.

### Acceptance criteria shared by every adapter

- Adapter manifest declares app/server/protocol versions, capability predicates, stable IDs, pagination, cache, sync, conflict, write, and unsupported behavior.
- Fixtures cover unknown fields, missing optional fields, permission-negative cases, 401/403/404/409/412/429/5xx, disabled app, and version upgrade.
- Offline support is explicit per resource/action. No generic queue accepts an action without defined idempotency/conflict behavior.
- Platform contacts/calendar/media integration is opt-in, reversible, and account-scoped.
- Deleting or disabling a bridge does not delete remote source data without a separate confirmation.

## 17. M10: automatic and verified native adapters

The native schema is a trust boundary, not a plugin permission bypass.

### Discovery pipeline

```text
navigation + capability snapshot + version metadata
                     |
official OpenAPI/OCS/DAV descriptions when present
                     |
deterministic Rust compiler
                     |
low/medium/high-confidence NativeAppSchema
                     |
optional reviewed adapter enhancement
                     |
typed read-only repository and adaptive native components
```

### Rules

- Navigation proves only that an app is visible, not that an API exists.
- Metadata-only fallback emits a native detail screen and zero actions.
- OpenAPI operations may produce read actions. Inferred writes remain disabled until their permission, confirmation, conflict, and idempotency policies are verified.
- Local AI may propose labels, field roles, and reusable components. It cannot add or alter endpoints, methods, auth, payload fields, or confidence `verified`.
- The generated schema is cached by server URL hash, server/app version, API/capability fingerprint, compiler version, and adapter version.
- Unknown fields/components fail closed to readable generic detail/list forms.
- Returned data shape can select a reusable native surface: typed data table,
  card/list, kanban board, calendar/timeline, metric summary, categorical chart,
  gallery, or detail/form. Selection never changes the declared endpoint or
  grants an undeclared write.
- Generic relationships hydrate foreign-key-like values from already-declared
  related resources and propagate parent display context such as currency.
  There are no app-ID switches for Deck stacks, Cospend projects, or Tables
  columns.
- Unified-search providers are discovered at runtime. Mail and future apps join
  global search without mobile-client code changes when the server advertises
  the standard provider contract.
- Verified adapters are ordinary reviewed source packages with tests, compatibility metadata, and reproducible signatures/hashes. They do not execute downloaded server code.
- A contributor inspector can view raw evidence, inferred resource/action mapping, warnings, and why an action is disabled.

### Acceptance criteria

- Metadata-only, OCS-wrapped OpenAPI, plain JSON OpenAPI, malformed schema, recursive refs, unknown formats, and adversarial descriptions pass compiler fixtures.
- A schema cannot cause cross-origin requests, arbitrary headers, unbounded downloads, credential exposure, or a write absent from discovery.
- Every write-enabled generated adapter undergoes human review and compatibility tests before distribution.
- An unsupported app provides a useful, accessible native metadata screen and development diagnostics without rendering HTML.
- Tables-like nested cells, Deck-like lanes, and finance-like datasets pass
  fixture suites using neutral resource names, proving the renderer is driven
  by schema and returned shape rather than app names.
- Disabling/upgrading an app invalidates its schema and pending incompatible actions.

## 18. M11: safe administration

Administration is valuable but must not weaken server authentication.

### Scope

- Read-only server/app inventory, enabled state, updates, compatibility, dependencies, group restrictions, classic-app versus ExApp identity, and system health where authorized.
- Native explanation and preflight for enable, disable, update, uninstall, bundle, force, and Memories maintenance actions.
- Default mutation flow is an authenticated browser handoff, followed by capability/navigation/inventory refresh.
- Direct mutation remains experimental until strict/lax password confirmation, SSO, 2FA, delegated admin, brute-force delay, maintenance mode, and recovery are integration-tested.
- The primary password, if a future direct strict flow genuinely requires it, exists only in request-scoped memory, is excluded from logs/crash state, and never replaces the stored app-password session.
- ExApps remain read-only/deep-linked until AppAPI asynchronous lifecycle/state is separately modeled.

### Acceptance criteria

- Ordinary users never see unusable admin actions merely because an app ID is present.
- Force enable/install requires a second incompatibility warning and cannot be bulk/default selected.
- Update/uninstall copy does not promise data retention or zero downtime.
- 403 plus `X-NC-Auth-NotConfirmed` cannot trigger a retry loop or app-password substitution.
- After browser return or successful supported action, inventory, capabilities, navigation, adapters, and schemas refresh atomically.

## 19. M12: platform productization

The shared domain contract applies everywhere; filesystem, background, RTC, notifications, credentials, and distribution remain native.

### Android

- Keystore-backed credentials; biometric/device lock integration without leaking DocumentsProvider grants.
- DocumentsProvider plus local synced-folder profiles.
- WorkManager, foreground transfer/call services, system share/open-with, notifications, media session, Telecom evaluation.
- APK and Android App Bundle; GitHub Releases, F-Droid-compatible flavor, and Play flavor without making proprietary push mandatory.

### iOS and iPadOS

- Keychain, File Provider extension, background URLSession/BGTask scheduling, APNs, share/action extensions, Quick Look/PDF, PhotoKit export, CallKit, and platform WebRTC.
- Offline mirror lives inside app/File Provider-managed storage; iOS does not promise a permanently running sync daemon.
- Test extension memory/time limits, account changes, eviction/hydration, coordinated writes, and device restore.
- TestFlight before App Store stable.

### macOS

- Keychain, File Provider where supported, Finder integration, notifications, background login item, Quick Look/open-with, media devices, and platform update/signing.
- A conventional full sync folder remains available alongside placeholders because developer workflows and Git/Obsidian often need ordinary local files.
- Notarized DMG first; App Store sandboxing evaluated separately.

### Windows

- Credential Manager/DPAPI, native notifications, share/open-with, startup/background behavior, media devices.
- Cloud Files API placeholder/hydration integration modeled after the official Nextcloud desktop client's [`vfs/cfapi`](https://github.com/nextcloud/desktop/tree/master/src/libsync/vfs/cfapi), plus conventional sync roots.
- MSIX and signed installer evaluation; update channel and rollback tested.

### Linux

- Secret Service, XDG portals, notifications, MIME/open-with, systemd user scheduling where packaged appropriately, PipeWire media/screen share.
- Conventional sync root is the primary reliable integration. Optional FUSE/GVfs-style on-demand filesystem is a later isolated driver, never required for core sync.
- Flatpak permissions/portals tested; GitHub AppImage/tarball and distro-friendly packages follow reproducible release work.

### Cross-platform acceptance criteria

- Every target builds in CI from a clean environment with no contributor home-directory paths in configuration.
- Login, logout, upgrade, account removal, cache migration, offline edit, conflict, upload resume, notification privacy, and unsupported-server flows pass per platform.
- Accessibility covers screen readers, keyboard/focus, switch input where applicable, scalable text, contrast, reduced motion, and non-color status cues.
- Platform-specific feature absence is shown in compatibility UI and does not silently fall back to web embedding.

## 20. Cache, storage, and privacy policy

### Metadata

Minimum durable tables/equivalents:

- account and server/app capability fingerprints;
- file node and path aliases;
- folder snapshots/search indexes;
- shares, versions, trash metadata, locks, comments;
- Activity/Talk/Notes/media/groupware records and cursors;
- sync sets, journal generations, tombstones, conflicts;
- uploads/downloads and pending operations;
- blob variants, pins, last access, integrity and size;
- adapter schemas/evidence/compatibility;
- notification dedupe/routing state.

All tables carry account ID. Foreign keys and cleanup are enforced. Credential material is forbidden.

### Blob classes

| Class | Creation | Eviction |
|---|---|---|
| Thumbnail/preview | automatic | LRU under per-account/global quota |
| Temporary original | explicit view/open | aggressive after age/pressure |
| Provider staging | open/write transaction | after committed upload or preserved conflict |
| Offline original | explicit pin/sync set | only user action or critical pressure with confirmation/policy |
| Dirty draft/conflict/base | edit/sync engine | never automatic until resolved/retention decision |
| Upload source snapshot | queued upload | after verified commit or cancellation policy |

Every blob is written to a unique temporary path, fsynced where required by platform guarantees, integrity-checked when possible, and atomically promoted. Cache keys include account, remote identity, ETag, variant, transform, and decoder version.

### Security milestones

- Threat model covers malicious server responses, hostile filenames/content, stolen device, compromised other app, logs/backups, MITM/custom CA, WebDAV redirects, push provider, WOPI/direct links, plugin/adapter supply chain, and local database corruption.
- TLS uses the platform trust store. Custom CAs are supported through platform-admin/user trust mechanisms; insecure global certificate bypass is never offered.
- Same-origin redirects are enforced for authenticated requests. External links require an explicit handoff with no Authorization header.
- XML disables DTD/external entities; JSON/XML bodies and decompressed content are bounded.
- Android backup rules, Apple backup protection classes, Windows/macOS/Linux secret stores, screenshots/recent-app privacy, clipboard, and notification contents are reviewed.
- Optional app lock must define interaction with background sync, filesystem providers/extensions, notifications, and already granted external access.
- Dependency review, secret scanning, SBOM, provenance, and reproducible configuration are release gates.

## 21. Testing strategy

### Test pyramid

1. **Pure domain tests:** URL/path normalization, permission parsing, action policy, schema compiler, reconciliation planner, conflict classification, cache keys.
2. **Property/fuzz tests:** sync state machine, hostile filenames, XML/JSON parsers, rich strings, OpenAPI refs, retry/idempotency, random crash points.
3. **Adapter contract tests:** recorded official responses across declared server/app versions, unknown fields, missing capabilities, error statuses.
4. **Mock-server integration:** latency, partial/ranged bodies, redirects, 401/403/404/409/412/423/429/5xx, Retry-After, quota, process cancellation, ambiguous commit.
5. **Real-server matrix:** containerized supported Nextcloud versions with app version families and reverse-proxy/subpath variants.
6. **Platform integration:** DocumentsProvider/File Provider/Cloud Files, workers, notifications, keychain, share/open-with, media/calls.
7. **End-to-end workflows:** Obsidian vault, large upload, conflict resolution, share collaboration, Talk call, media offline, Office handoff.
8. **Long-running/chaos:** large accounts, constrained memory/disk, network flaps, battery saver, reboot, server upgrade/downtime, clock skew.

### Required server matrix

Before stable, publish and continuously test a declared support window. It must include:

- root and subpath installations;
- direct server and supported reverse proxy;
- local storage, external storage/mount, read-only share, group folder, quota pressure;
- app password and representative SSO/2FA login flow;
- supported stable server versions and the next release candidate;
- app versions at each adapter compatibility boundary;
- optional preview providers, Talk internal/external signaling, TURN, Memories, Recognize gate, Office/Collabora;
- server upgrade with active clients and schema/cache invalidation.

### Data-loss gate

No sync/file-write beta ships until:

- every state-machine transition has a crash/restart test;
- ambiguous remote results are represented;
- base ETag/preconditions are verified;
- conflict copies remain durable;
- destructive operations are never retried automatically;
- a reproducible fault suite demonstrates zero silent overwrite/loss across its published scenarios.

## 22. Release and contributor architecture

### Branch and release channels

- `main` stays buildable and migration-tested.
- Small focused pull requests with tests and protocol evidence are preferred.
- Nightly: automated artifacts, developer data only.
- Alpha: migrations supported, debug export, known gaps visible, no data-loss-critical feature without its gate.
- Beta: compatibility matrix and upgrade path published, release signing active, privacy/security review complete for enabled features.
- Stable: rollback/recovery docs, migration support window, reproducible release process, no open critical security/data-loss issue.

### CI/CD

- Rust format/lint/test, Kotlin format/static analysis/test, schema golden compatibility, Android unit/instrumented, desktop tests, and platform builds.
- Containerized Nextcloud integration matrix with cached official app fixtures and nightly live-main compatibility jobs that do not block stable releases without triage.
- Signed artifacts, checksums, SBOM, provenance, dependency/license report, and changelog generated from reviewed inputs.
- Release keys live in protected platform services, not repository/user config.
- Database migrations and adapter schema version invalidation run in upgrade tests before publishing.

### Contributor contract

Every adapter contribution includes:

- official API/source links and observed version range;
- typed models with no `Any`/untyped maps;
- capability and permission gates;
- read/write/destructive/admin inventory;
- pagination/cache/sync/conflict behavior;
- idempotency and ambiguous-result policy;
- fixtures for success, unknown fields, old/new versions, permissions, conflicts, throttling, and server errors;
- native component mapping and accessibility semantics;
- explicit unsupported features and fallback;
- no personal paths, credentials, server names, or generated local configuration.

Architecture decisions that affect persistence, sync semantics, authentication, automatic adapters, WebRTC, Office SDKs, or release trust require a short ADR and migration/compatibility impact section.

## 23. Product checkpoints

### Developer preview checkpoint

- Shared builds remain reproducible.
- Current native browsing/editing/talk/media prototype stays usable.
- Generic unsupported-app screen is schema-driven and zero-action.
- Research and compatibility docs are linked from contributor onboarding.

### Files alpha checkpoint

- M0 and M1 acceptance gates pass.
- Cached native Files and guarded actions are reliable on Android and Linux.
- Transfer/conflict state is visible, but continuous sync may remain experimental.

### Sync beta checkpoint

- M2-M4 data-loss gates pass.
- Android DocumentsProvider and at least one Obsidian-grade offline sync profile are supported.
- Desktop conventional sync root works on Linux and one of Windows/macOS before broader parity.

### Collaboration beta checkpoint

- M5-M7 pass for declared versions.
- Talk message completeness, incoming notifications, stable audio/video calls, and media offline behavior are usable daily.

### 1.0 checkpoint

- G8 and the published platform support matrix pass.
- Android plus supported desktop targets provide trustworthy Files/sync, messaging, media, document viewing, notifications, account/security, and a clear adapter ecosystem.
- iOS availability and platform-specific feature parity are stated explicitly; stable is not delayed by pretending unsupported background behavior can be identical.
- No known critical data-loss, credential, cross-account, call privacy, or migration defect remains.

### Post-1.0 excellence

- Broader groupware/productivity adapters, verified third-party adapter registry, richer system integrations, supported native Office if feasible, delegated administration, and advanced enterprise policy.
- Performance and accessibility budgets become release-blocking alongside correctness.
- User-driven priorities are selected from real workflows, not app-count targets.

## 24. Major risks and mitigations

| Risk | Impact | Mitigation/gate |
|---|---|---|
| Files DAV has no universal directory sync token | Expensive scans/stale remote changes | Journaled incremental hints plus periodic verification; benchmark large trees |
| Mobile background restrictions | "Continuous" sync cannot always run immediately | Honest policy, WorkManager/BGTask/File Provider triggers, visible last-sync state, manual foreground override |
| SAF/File Provider consumer differences | Write/seek/atomic-save failures | Real application compatibility suite and staging/close-commit model |
| Concurrent edits/deletes | Data loss | Base generations, ETags, tombstones, durable conflict copies, no destructive blind retry |
| Large uploads and process death | Duplicate/corrupt files | Persisted chunk sessions, source-generation checks, final verification |
| Server/app API drift | Broken adapters | Version manifests, capability gates, fixtures, schema invalidation, specialized-to-generic fallback |
| Arbitrary apps expose private/no API | Cannot provide full native behavior | Honest metadata fallback, official OpenAPI/DAV first, reviewed adapters, no HTML scraping |
| Talk signaling/WebRTC complexity | Unreliable calls/privacy bugs | Separate compatibility matrix, platform media drivers, soak/chaos/threat tests |
| Office is a web/WOPI integration | False promise of native editing | Native preview first, external Direct Editing, SDK feasibility gate, no WebView branding trick |
| Recognize API-key gate | Direct people DAV unavailable | Memories clusters, explicit unavailable state, wait for official external flow |
| Strict admin password confirmation | App password cannot authorize | Browser handoff first, primary password never stored, direct flow only after auth matrix |
| Client-side E2EE/mounted storage | Incorrect content/permission behavior | Capability-specific adapter; fail closed/read-only until supported and tested |
| Five platform scopes | Quality dilution | Shared domain rules, staged platform tiers, platform acceptance matrix, no fake parity |
| Cache/device loss or theft | Offline data exposure | Threat model, platform protection/encryption, app lock semantics, remote/account removal controls |
| Contributor adapter supply chain | Malicious endpoints/actions | Source review, schema sandbox, signatures/hashes, no executable downloaded adapter code |

## 25. Official engineering references

### Nextcloud protocols and clients

- [Nextcloud WebDAV API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html)
- [Nextcloud chunked upload v2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/WebDAV/chunking.html)
- [Nextcloud trashbin DAV](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/trashbin.html)
- [Nextcloud versions DAV](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/versions.html)
- [Nextcloud OCS Share API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/OCS/ocs-share-api.html)
- [Nextcloud client APIs and Login Flow](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/index.html)
- [Nextcloud Android DocumentsStorageProvider](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/owncloud/android/providers/DocumentsStorageProvider.java)
- [Nextcloud Android two-way sync worker](https://github.com/nextcloud/android/blob/master/app/src/main/java/com/nextcloud/client/jobs/InternalTwoWaySyncWork.kt)
- [Nextcloud desktop sync engine](https://github.com/nextcloud/desktop/blob/master/src/libsync/syncengine.cpp) and [sync journal](https://github.com/nextcloud/desktop/blob/master/src/common/syncjournaldb.cpp)
- [Nextcloud desktop conflict behavior](https://docs.nextcloud.com/server/latest/user_manual/en/desktop/conflicts.html)
- [Nextcloud desktop virtual files](https://github.com/nextcloud/desktop/tree/master/src/libsync/vfs)
- [Talk OpenAPI](https://github.com/nextcloud/spreed/blob/main/openapi.json) and [capabilities](https://github.com/nextcloud/spreed/blob/main/docs/capabilities.md)
- [Talk Android signaling receiver](https://github.com/nextcloud/talk-android/blob/master/app/src/main/java/com/nextcloud/talk/signaling/SignalingMessageReceiver.kt), [call foreground service](https://github.com/nextcloud/talk-android/blob/master/app/src/main/java/com/nextcloud/talk/services/CallForegroundService.kt), and [UnifiedPush service](https://github.com/nextcloud/talk-android/blob/master/app/src/main/java/com/nextcloud/talk/services/UnifiedPushService.kt)
- [Nextcloud Files Direct Editing controller](https://github.com/nextcloud/server/blob/master/apps/files/lib/Controller/DirectEditingController.php)
- [Nextcloud Office capabilities](https://github.com/nextcloud/richdocuments/blob/master/lib/Capabilities.php)

### Platform integration references

- [Android DocumentsProvider](https://developer.android.com/reference/android/provider/DocumentsProvider) and [custom document provider guide](https://developer.android.com/guide/topics/providers/create-document-provider)
- [Android WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)
- [Apple File Provider](https://developer.apple.com/documentation/fileprovider)
- [Microsoft Cloud Files API sync-engine guide](https://learn.microsoft.com/en-us/windows/win32/cfapi/build-a-cloud-file-sync-engine)
- [XDG Desktop Portal documentation](https://flatpak.github.io/xdg-desktop-portal/docs/)
- [libfuse](https://github.com/libfuse/libfuse)

Individual routes and action policies must remain backed by version-pinned
fixtures, compatibility tests, and links to their authoritative upstream
protocol or application source.
