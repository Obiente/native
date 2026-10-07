# Adapter architecture

This document defines the boundaries for integrating independently versioned
Nextcloud server apps without embedding their web interfaces. Read it before
changing transport, discovery, repositories, caching, sync, account recovery,
or a native feature adapter.

The first half states rules that apply to every adapter. The second half
records feature-specific contracts that are implemented in source, with the
evidence boundary for each. Product status belongs in
[COMPATIBILITY.md](COMPATIBILITY.md), planned delivery in
[ROADMAP.md](ROADMAP.md), and source-set ownership in
[PLATFORMS.md](PLATFORMS.md). The
[default-branch copy](https://github.com/obiente/native/blob/main/ADAPTER_ARCHITECTURE.md)
is the maintained contract.

Unless a section says otherwise, an implementation note here is backed by
source and deterministic tests only. It does not claim device validation,
live-server compatibility, or inclusion in a published release.

## Core invariant

The native UI consumes stable, typed product models. It never parses protocol
payloads, builds endpoint URLs, infers permissions from navigation, or executes
an operation without verified provenance.

```text
Compose UI
    |
Feature state holders and repositories
    |
Typed adapters and semantic runtime
    |
Authenticated transport and persistence
    |
Platform services
```

Each layer owns one kind of change. A server API change should not force a
Compose screen to parse new JSON. A credential-store change should not alter
repository policy. A layout change should not alter mutation safety.

## Ownership boundaries

### Compose UI

- Renders immutable state and sends explicit user intents.
- Owns presentation state, focus, accessibility semantics, and adaptive layout.
- Does not own network requests, protocol parsing, persistence, retry loops, or
  conflict policy.
- Reuses semantic components for shared interaction patterns. App-specific UI
  is justified only by behavior a shared component cannot represent safely.
- Keeps composables small enough to review. Extract state holders, pure models,
  and reusable surfaces before adding another responsibility to a large screen.

### Repositories and feature state

- Provide the single source of truth for cached and remote feature state.
- Own refresh, pagination, ETags, dirty state, retries, conflicts, and cache
  invalidation.
- Expose typed loading, ready, stale, partial-failure, and blocking-failure
  states instead of throwing protocol exceptions into the UI.
- Merge successful remote responses transactionally.
- Keep usable cached content visible when a refresh fails.

### Adapters

- Translate one verified protocol or app-version family into shared models.
- Stay stateless apart from immutable capability and version configuration.
- Validate required capabilities, versions, endpoint paths, permissions, and
  response shapes before enabling an action.
- Preserve unknown response fields only through an explicit typed extension
  value. `Any` is not a compatibility strategy.
- Fall back to a supported generic adapter when an optimized app-specific path
  is unavailable. Never fall back to a hidden web view.

### Transport

- Owns authentication, product identification, TLS, redirect policy, bounded
  bodies, case-insensitive response headers, and same-origin enforcement.
- Accepts typed request data from adapters. The UI never constructs requests.
- Supports standard HTTP and the required WebDAV methods without placing DAV
  parsing in platform launchers.
- Rejects DTDs and external entities in XML.
- Allows an external origin only through a feature designed as an explicit
  browser or application handoff.

There is no single transport type yet. Shared JVM request policy and OkHttp
helpers live in `ui/src/jvmMain` (for example
`NextcloudAuthenticatedRequestPolicy.kt` and `JvmCancellableHttp.kt`), and the
platform services still own part of request execution. Consolidation is
roadmap milestone M0.

### Persistence and sync

- Scope every record, cache key, queued operation, and diagnostic identifier to
  an opaque local account ID.
- Keep credential material out of metadata databases and diagnostics.
- Publish files atomically after complete writes.
- Preserve originals unless the user explicitly chooses replacement.
- Bound automatic caches. Keep offline files and unresolved conflict copies
  until their documented lifecycle permits removal.
- Queue only operations with defined idempotency and conflict behavior.

### Platform services

Platform source sets own operating-system behavior: credentials, lifecycle,
background scheduling, filesystem providers, notifications, media sessions,
external handoff, packaging, and accessibility integration. Protocol policy and
parsing belong in shared code even when a platform client executes the request.

`NextcloudPlatformServices` (declared in `ui/src/commonMain`, implemented by
`AndroidNextcloudServices` in `androidApp` and `DesktopNextcloudServices` in
`ui/src/desktopMain`) is an integration boundary, not a place to collect every
product feature. When both implementations repeat protocol logic, extract a
shared adapter or repository. Keep separate implementations only when
lifecycle or operating-system semantics genuinely differ.

Existing shared pieces:

- `executeDynamicApiGet` in `DynamicApiCachePolicy.kt` (common) owns GET cache
  selection and request coalescing.
- `executeJvmDynamicApiRequest` in `JvmDynamicApiExecution.kt` shares mutation
  invalidation and cache orchestration through typed callbacks. Each platform
  keeps its own network mapping.
- `JvmDetachedDownload.kt` shares authenticated bounded streaming and
  cancellation. Its explicit ETag option keeps Android's `OC-Etag` fallback and
  desktop's ETag-only behavior.

Sharing code must not erase a verified platform difference.

## Capability and discovery rules

Capabilities and versioned API descriptions are authoritative. Navigation
entries and successful guesses do not prove that an operation is safe.

- Keep a typed capability snapshot per account with its fetch time and
  server and app versions.
- Revalidate cached descriptors when the server version, app version,
  capability fingerprint, OpenAPI fingerprint, or adapter version changes.
- Treat response-shape inference as read-only evidence.
- Require advertised OpenAPI or a reviewed adapter for writes.
- Keep dynamic endpoints relative and inside approved same-origin prefixes.
- Omit behavior whose provenance or permission model is ambiguous.

See [DYNAMIC_APP_DESCRIPTOR.md](DYNAMIC_APP_DESCRIPTOR.md) and
[NATIVE_SCHEMA.md](NATIVE_SCHEMA.md) for the serialized trust boundaries.

### Authenticated application reads

Dynamic application GETs share one bounded account session adapter,
[`JvmAuthenticatedAppReadSession.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmAuthenticatedAppReadSession.kt).

- A read-only OCS profile bootstrap keeps cookies in process memory, scoped to
  the account origin and application path.
- Credential replacement and account retirement invalidate pending and
  prepared sessions.
- A repeated response cookie replaces the earlier value with the same domain,
  path, and name. More specific paths are sent first.
- Mutation and public contract-acquisition clients do not use this cookie
  store.

[Authenticated application reads](DYNAMIC_APP_DESCRIPTOR.md#authenticated-application-reads)
lists the bounds, failure behavior, and test coverage.

### Native audio streams

Native audio uses the same session adapter for application stream GETs
([`JvmNativeAudioRead.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmNativeAudioRead.kt)).

- Each stream or range request refreshes an expired session on its stream
  worker. The playback queue never stores credentials or cookies in media
  metadata.
- The call owns cancellation of session preparation and streaming. It allows
  only the selected source URL and GET, and refuses redirects.
- DAV audio stays an authenticated file read without application cookies.
- `AccountPlaybackRetirement.kt` registers the current queue against the account
  incarnation. Account cleanup clears pending credentials, cancels preparation
  and streams, and dispatches platform player disposal. Lazy jobs publish under
  the account gate before starting, so a stale queue cannot restart after the
  account is reactivated.

Owner tests cover account isolation, replacement, release, and stale producer
rejection. Platform playback and removal remain separate device checks.

## Mutation policy

Every operation declares its risk before it reaches the UI:

| Level | Meaning | Required behavior |
| --- | --- | --- |
| Read | No intended remote mutation | May run for loading or explicit refresh. |
| Reversible | Small, visible, reversible write | Direct user intent and rollback on failure. |
| Guarded | Content change with concurrency risk | Permission check, revision guard, conflict UI. |
| Destructive | Delete, overwrite, or hard-to-reverse change | Target-specific confirmation and no blind background retry. |
| Privileged handoff | Server administration or primary-password confirmation | Explain the effect and open authenticated server administration. |

Runtime evidence may raise the risk level, for example when a move would
overwrite an existing target. It must never lower the declared level silently.

A stored Login Flow app password is never treated as the primary password for
strict administrator confirmation. The client must not collect or retain a
primary account password to bypass that boundary.

## Error and cancellation rules

Errors keep enough structured context for recovery and safe diagnostics without
exposing private data.

- Map transport, authentication, permission, validation, conflict, capacity,
  cancellation, and unexpected failures into distinct typed outcomes.
- Never turn coroutine cancellation into an ordinary failed request. Rethrow
  cancellation before broad exception handling.
- Do not use `getOrNull()` or an empty catch when the caller must distinguish
  unavailable data from a failed operation.
- Add operation and stage identifiers at subsystem boundaries. Never include
  server URLs, paths, filenames, payloads, credentials, or response bodies.
- Keep the original cause internally and show an actionable, non-technical
  message to the user.
- Bound every retry and make it safe for the operation. Ambiguous delivery of a
  mutation requires reconciliation before another submission.

Android account transitions separate durable credential changes from later
observer notifications and maintenance. A notification or diagnostic failure
must not roll back a committed transition or replace an outstanding
cancellation. Pending cleanup keeps its durable retry evidence, and required
activation failures stay observable.

## Offline and conflict rules

Repositories use stale-while-revalidate:

1. Emit usable cached data immediately.
2. Mark it as refreshing when remote work starts.
3. Commit a successful response transactionally.
4. Keep cached data and show a non-blocking error when refresh fails.
5. Show a blocking error only when no usable state exists.

Writes need an explicit conflict contract. ETag-protected text or note saves
may be queued when their base revision and payload are durable. Deletes,
administrator actions, Talk messages, and hard-to-reverse recognition changes
are not queued by default.

Notes mutation validators keep valid quoted opaque ETags verbatim, including
backslashes and HTTP `obs-text` bytes, without quoted-string unescaping. Bare
Notes API validators are quoted once. Malformed and oversized validators are
rejected before a request. The grammar follows
[HTTP entity-tags](https://httpwg.org/specs/rfc9110.html#field.etag).

## Test contract

Each boundary has a test responsibility:

- Protocol fixtures cover parsing, omitted and unknown fields, version gates,
  same-origin checks, size limits, and hostile XML.
- Repository tests cover cache refresh, pagination, transactional merge,
  cancellation, retry limits, conflicts, and restart recovery.
- Compose tests cover semantics, loading, empty, stale, partial failure,
  permission denial, confirmation, adaptive layout, and keyboard and touch
  access.
- Platform tests cover credential stores, filesystem paths and providers,
  background scheduling, external handoff, packaging, and lifecycle recovery.
- Live-server audits use synthetic disposable accounts, record exact tested
  versions, and stay separate from deterministic unit and integration tests.

A bug fix adds the smallest regression test at the layer where the invariant
failed. Tests assert public behavior, not copied implementation details.

## Review checklist

Before merging an adapter or repository change, confirm that:

- the responsibility is in the correct layer;
- shared behavior is implemented once without hiding platform differences;
- every write has provenance, permission, conflict, and confirmation policy;
- cancellation survives every broad exception boundary;
- cache and queued-operation state survives interruption safely;
- diagnostics identify the failed stage without private content;
- tests cover the success, empty, partial, offline, denied, malformed,
  cancelled, conflict, and retry-exhausted paths that apply.

## Feature contracts

The sections below record implemented contracts for specific features. They
follow the general rules above and add the details a reviewer needs.

### Browser sign-in

`LoginAttemptState` owns one cancellable browser approval attempt. A five-minute
deadline covers challenge creation, browser handoff, polling, and network
waits. The screen distinguishes contacting the server, waiting for approval,
retrying a connection, and completing sign-in.

- Cancellation releases challenge bookkeeping and rejects late results before
  they can persist credentials. The existing authenticated-session transition
  still owns the final credential commit.
- Only a classified DNS failure before an HTTP exchange may retry
  automatically. An ambiguous response after the one-time exchange requires a
  new sign-in attempt.
- Retry copy describes the last observed failure. It cannot report the current
  health of DNS, the server, or a VPN.

See [sign-in recovery](docs/login-diagnostics.md).

### Preview and image caches

- Encoded previews coalesce concurrent requests by account incarnation and
  resource revision.
- Decoded thumbnails use a separate bounded cache keyed by source, revision,
  variant, and requested size. `MediaImageDecoder` runs at most two native
  decodes at once, off the presentation dispatcher.
- `MediaThumbnailCache` keeps at most 256 images within a 24 MiB budget,
  counted at eight bytes per pixel. Mounted views may hold extra references.
  Eviction drops cache references and never recycles an image Compose still
  displays.
- Account retirement clears both caches and stops old producers from
  repopulating them.

Desktop read-cache hits update recency in memory, and the next cache mutation
persists those hints. A restart may lose recent access order but cannot turn
partial content into a complete entry. A warm read never publishes the index or
scans the cache directory just to record access.

Android offline-availability reads inspect queue state without scheduling work
on each UI poll. Recovery is scheduled explicitly when the account opens;
enqueue and worker transitions remain the scheduling owners. Queue snapshots
index records and jobs for repeated lookups. This does not remove persistence
reads and is not a fully reactive offline status stream.

### Text editing recovery

`TextEditorState` owns the original content, local edits, revision readiness,
and ambiguous-save verification.

- An upload checkpoint is persisted before PUT. Restoration compares the
  authoritative remote content before another write.
- A successful response without a replacement ETag clears the old revision.
- A failed favorite toggle restores only that flag. Share dialog responses must
  match the current request generation before entering UI state.

Both platforms bind text recovery to the opaque account identity and remote
path. `JvmTextEditorDraftStorage` encrypts and authenticates bounded files with
AES-GCM, publishes them atomically, and rejects old account-generation
bindings. Android supplies a Keystore key; desktop supplies a key from its
secret store. Unreadable recovery is preserved, not overwritten, and a capacity
failure is reported without evicting another draft. Persisted edits survive a
new editor owner; edits still waiting to be persisted are not crash-safe.

### File copy and move

Copy and Move use the shared `FileTransferDialog` and remote folder picker. The
dialog owns only the temporary name and destination choice. The parent captures
an immutable validated destination for the typed mutation, and the transport
keeps the source ETag and no-overwrite preconditions.

### Groupware (CardDAV and CalDAV)

**Multiget requests**

- Request builders append escaped object hrefs without indenting an XML
  declaration through multiline interpolation. Strict XML tests cover one-item
  and multi-item batches, namespace ownership, and escaped hrefs.
- Contacts and Tasks fall back from multiget REPORT to individual object reads
  only for HTTP 405 or 501. Other failures and throttling stop that collection's
  refresh instead of multiplying requests. A failed collection stays a visible
  failure, never an empty result.
- Multiget keeps healthy records when another requested resource has an
  explicit 404 or 410 status and reports the deletion count as a partial
  refresh. Every requested href must be accounted for exactly once; omitted,
  duplicate, foreign, malformed, and other failed responses stay errors. A
  property-level 404 is not a resource deletion. See the
  [CardDAV multiget example](https://www.rfc-editor.org/rfc/rfc6352.html#section-8.7.1),
  the [CalDAV multiget contract](https://www.rfc-editor.org/rfc/rfc4791.html#section-7.9),
  and the [WebDAV resource and property status distinction](https://www.rfc-editor.org/rfc/rfc4918.html#section-13).

**Object identity and writes**

- Loaded DAV hrefs are opaque. Updates and deletes keep the discovered href,
  including extensionless names, and require the loaded ETag. New objects get a
  generated `.ics` or `.vcf` suffix. The mutation builder rejects collection
  hrefs, unsafe paths, and mismatched content before a request.
- After a successful contact DELETE, the mutation coroutine reads the object
  again before the detail view closes. Only 404 or 410 proves absence and
  allows clearing the exact durable recovery record. An unchanged object,
  failed verification, or cancellation keeps recovery, and screen effects
  reconcile that record after restart.

**Tasks**

- Completed refreshes are tracked by calendar href, not display name or warning
  text. A missing selection is cleared after its own calendar completes, even if
  another calendar fails. Failed or budget-truncated calendars do not prove
  deletion, and the selection keeps its calendar identity across restoration.
- If every task list fails on the first refresh, the screen shows an error, not
  an empty count. Later failures keep previously loaded tasks within the memory
  budget, mark the result incomplete, and withhold writes for calendars that
  did not refresh.
- Whole-object deletion requires one balanced VCALENDAR with exactly one
  top-level VTODO and no sibling data components. VTIMEZONE and alarms owned by
  the task are allowed. VEVENT, VJOURNAL, other tasks, and unknown siblings
  withhold deletion; see the
  [iCalendar component structure](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.6).
- UIDs that are blank, contain control characters, or exceed 1,024 characters
  are withheld from editing. Recurrence identity values must be a valid date or
  date-time of at most 16 characters. Malformed exceptions are withheld rather
  than treated as masters, and their raw components stay intact when another
  task is edited.
- Stable task keys combine the DAV object, a length-delimited UID, and a
  master or exception marker. Duplicate component identities reject the
  affected calendar response. Edits require one unique matching component in
  the retained source.
- Description normalization changes line endings only; leading, trailing, and
  whitespace-only content survives edits and recovery verification.
- Task requests, durable recovery storage, and recovery reads are serialized.
  Refresh and discard controls stay disabled until the active request finishes.
  A queued recovery read reloads the durable record after taking the operation
  lock. Cancellation releases the lock without discarding the record.
- Task details keep the full title, description, status, and errors in a
  bounded scrolling body, with Edit and Delete outside it. Compact landscape and
  large text must not hide these actions or bypass read-only and recurrence
  guards.

### Dynamic collection creates

A collection header's create action opens the renderer's durable create form,
never a standalone generic form. Header and inline actions share one
complete-baseline and postcondition recovery plan and require a pending
mutation store; missing recovery evidence withholds both. The header control is
scoped to the active account and navigation context and cleared on disposal.
Pending writes stay in durable storage, not in the control.

Android publishes a pending mutation record only after syncing the record and
its directory. A publication failure withholds the request.

### Activity and Search navigation

Activity and unified Search first pass a supplied link through the shared
account-aware link policy.

- Supported Files IDs and paths keep their exact identity instead of being
  replaced by a parent folder or provider-app hint. The asynchronous Files ID
  resolver stays the authority for the current location; no DAV path or
  resource ID is inferred from a label.
- Unsupported app-specific record URLs keep the native app-root or folder
  fallback. Exact-record navigation for Notes, Deck, Calendar, and other web
  routes needs a reviewed mapping to a typed native destination.
- Foreign origins, malformed links, unknown query semantics, and conflicting
  file IDs never become exact native destinations.

### Dynamic navigation restoration

Restoration saves bounded record IDs, route parameters, and history only, never
record titles or payloads. Restored records stay unsafe for actions until the
authoritative read path renews their data. With only a restored ID, headers and
menus use the verified resource label, or "Selected item" when none exists. A
parent's name is not recovered from a child's response; it returns when the
parent is loaded again.

### Dashboard response links

Dashboard widget metadata and both item API versions use the account-scoped
[DashboardLinkPolicy](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DashboardLinkPolicy.kt).
Absolute links default to HTTPS. An account explicitly approved for HTTP may
also receive HTTP links with the same scheme, host, and effective port as its
server URL. Relative links retain their traversal and scheme-relative guards.
Invalid actionable links reject the response; invalid optional widget, item,
and overlay icons are omitted. This policy does not change transport consent
or authorize requests to another HTTP origin.

### Administration visibility

`AdministrationAccessRepository` owns session-scoped, in-memory permission
evidence from the existing read-only administrator catalog contract. It reuses
that catalog for the Server apps screen and caches both allowed and denied
results for five minutes using a monotonic clock. Opening Settings checks the
cache; while the root Settings screen or Server apps is visible, expired evidence
is refreshed. Opening a child screen from Settings stops automatic polling.
Polling also stops when Android leaves the started lifecycle or the desktop
window is hidden or minimized. Returning to a visible screen reuses fresh
evidence or revalidates expired evidence. Settings retains the requested section
while Administration is hidden during a check, without rendering its controls.
No permission result survives logout, account replacement, or process restart.
Both catalog request paths use `ForceNetwork` so revalidation cannot renew access
from the transport's persisted response cache.

Administration, its installed-workspace summary, and the Server apps catalog
require fresh successful evidence. Unknown, expired, denied, malformed, and
unavailable results hide those surfaces, including restored navigation. The
Server apps route keeps a loading or typed failure view with retry and Back
instead of navigating away silently when permission revalidation fails. An
explicit refresh removes old access before requesting new evidence. Concurrent
checks share the cached result, and cancellation cannot publish a late success.
The ordinary Apps workspace remains available to regular users. Cached visibility
never authorizes a mutation; strict operations still use authenticated browser
handoff and server-side authorization.

## Account removal and recovery

These contracts protect credentials, local edits, and granted access while an
account is removed, replaced, or recovered after a crash. They are source and
deterministic-test guarantees; device lifecycle and published-installer
behavior are separate evidence. Desktop startup recovery screens are described
in [PLATFORMS.md](PLATFORMS.md#desktop-account-recovery).

### Saved sign-in recovery

- Android and desktop startup distinguish an unreadable saved sign-in from a
  new installation. The recovery screen offers retry and an explicit sign-in
  reset. Background account lookup returns unavailable without activating
  unreadable credentials. Unsupported credential versions are protected from
  reset by older builds.
- An explicit Android reset keeps malformed account-removal journal rows in
  private storage and writes a durable recovery fence before removing them from
  the active journal. Each account stays unavailable until its owned-state
  cleanup finishes with that account's supplied session. Failed cleanup keeps
  the fence and pending work, and new malformed rows invalidate earlier
  recovery decisions. Unresolved local document changes are not discarded.
- Android cleanup review markers are pruned against the retained account
  registry before the recovering account is recorded. Removing or replacing
  another account must not evict a retained account's marker. If registry
  ownership is unreadable, existing markers are kept.
- Desktop account removal verifies retirement of recognized temporary
  file-sync staging files under the sync engine lock and keeps user originals.
  When a prepared removal rolls back against retained credentials, desktop
  reopens the memory and persisted app-contract caches; producers captured
  before retirement stay invalid.
- Desktop legacy credential migration propagates cancellation without
  returning an active session or reporting a credential-store failure. The
  legacy secret stays available for a later migration.
- On macOS, a freshly authenticated account with no registered credential may
  save to Keychain when Keychain confirms absence and the legacy executable is
  missing. Existing account credentials and encrypted data retain their
  migration gates; locked storage and cancellation must not be treated as
  absence.
- Credential-free legacy cleanup uses the recorded document identity to match
  that account's legacy and full discovery-cache digests. A missing full digest
  is never a wildcard for other accounts' contracts.

### Android queued uploads during credential recovery

Queued uploads keep their rows while saved credentials need recovery.

- Malformed preference values, damaged ciphertext, and invalid decoded
  credential records pause timed retries when no usable or temporarily
  inaccessible fallback remains.
- Temporary Keystore failures keep retrying, including for inactive accounts.
- A malformed registry with a missing or permanently damaged aggregate also
  pauses retries; temporary aggregate access failures stay retryable.
- Unsupported credential versions require an upgrade.
- Optional credential-slot repair propagates cancellation before publishing its
  aggregate-backed session.

### Android folder-sync capability lifecycle

**Scheduling**

- Services created by the `DocumentsProvider` defer WorkManager lookup until
  scheduling or reconciliation needs it, because provider construction can
  precede AndroidX Startup initialization.
- Folder capability cleanup uses demand-driven one-time WorkManager work.
  Startup begins reconciliation after AndroidX provider initialization;
  constructing a file-sync engine never acquires or schedules work.
- Empty stores and committed pairs do not keep cleanup work alive. New
  acquisitions and cleanup requests schedule recovery, and unfinished cleanup
  keeps bounded WorkManager backoff.

**Folder picker acquisition**

- Acquisition and durable scheduling run on the picker's owned IO scope, with
  results delivered on Main and cancellation cleanup kept on IO.
- Provider metadata queries run without account leases. Before taking a
  persisted grant, acquisition rechecks the exact active session and
  cancellation under both account identity leases.
- Outstanding selections keep a retry owner until bound or abandoned.
  Reconciliation preserves selections already delivered to an open setup, and
  ordinary reconciliation keeps live reselections abandonable.
- A cancelled chooser keeps its request slot until the platform returns its
  result, so the result cannot reach a newer request.
- A process-restoration grace period protects pending folder drafts only while
  acquiring or ready selections remain. Completed setup and cleanup return
  without waiting.
- An unavailable restored grant abandons its exact saved reference without
  clearing a newer draft.

**Account retirement and pair removal**

- Retirement invalidates outstanding chooser generations before cleanup, even
  if cleanup fails or no longer has credentials. Re-adding the same account,
  including with an equivalent server URL spelling, does not revive an earlier
  chooser result.
- Retirement stays strict until capability cleanup finishes, including
  acquiring selections in the current process. It adopts matching
  configured-pair ownership before retiring drafts.
- A cancelled pair save preserves authoritative ownership recovery and then
  rethrows cancellation, even when the save committed.
- A durably removed pair reports completion while its already-scheduled
  recovery worker retries any remaining permission cleanup. Ambiguous
  coordinator saves still need authoritative confirmation of removal.
- Abandoned acquisitions and removed pairs keep cleanup evidence until access is
  released. Cleanup retries never transfer or delete user file contents.
- Under the coordinator lock, authoritative pair snapshots reclaim ownerless
  current-process capability records after failed ownership transitions.
  Configured pairs keep their grants.
- Reconciliation records independent cleanup progress before reporting a failed
  provider, so one unavailable grant cannot hold unrelated grants forever.
- A legacy shared root can regain expired access only for an account that still
  owns a recorded pair at that exact root.
- Cancellation from grant, storage, and cipher adapters stays cancellation. It
  is never reported as damaged metadata or deferred cleanup.

**Schedule restoration**

Restoring a folder-sync schedule validates the exact session while holding the
account operation lease, including server discovery. Permanent protocol or
malformed-state failures stop the one-time job. Temporary transport failures,
including truncated responses, get at most two retries. A typed local-store
truncation failure stops retries. Configured pairs stay intact for explicit
recovery.

### Android document writeback

- Writeback initialization holds the credential operation lease through
  metadata resolution and durable staging. Descriptor lifetime uses the removal
  fence, and close-time commits revalidate the exact session.
- Retained edits use canonical local account ownership. Verified historical
  manifest owners migrate without changing staged bytes.
- Document change notifications include verified, incarnation-scoped legacy
  IDs. Malformed optional aliases cannot block canonical reauthentication and
  may be discarded only after incarnation retirement. Before retirement they
  also do not block recovery through the verified canonical account. Unknown
  historical aliases stay unauthorized, and alias-storage IO failures leave
  recovery pending.
- A failed document cleanup retry resumes its validated incarnation-retirement
  token in the same process. A mistyped preference keeps a durable invalid prior
  state for fail-closed rollback; storage IO and cancellation errors still
  propagate.

### Android external handoff

- Remote handoff producers capture a process generation under the current
  session guard before probing or staging. Account cleanup invalidates that
  generation even if durable clearing fails.
- Registration and managed-content publication check the generation under the
  registry lock, so late producers cannot republish cleared metadata or content.
- Memory, streamed, Deck attachment, and historical-version fallbacks guard
  private staging creation, promotion, and the final external-app launch with
  the same generation. Large staging validates the generation before file
  creation and promotion. Downloads run outside the registry lock.
- A durable cleanup marker fences restoration after a failed clear and process
  restart. Handoff records cannot be restored or newly persisted while it is
  pending.
- Cancelled registration keeps the persisted record across IO result delivery
  so it can revoke the capability and discard the staged copy before
  propagating cancellation.

### Android diagnostics during removal

- Canonical writeback ownership is separate from the support diagnostic scope.
  Writeback and document or root resolver failures resolve the retained
  account's current diagnostic identity, independently of the rejected document
  incarnation. Canonical document keys and aliases never become diagnostic
  storage keys.
- Publication tries the current account operation lease, rechecks the exact
  session, and holds the lease through the sink's generation capture. The
  asynchronous sink rejects retired or stale generations under its persistence
  lock. A missing, ambiguous, removed, rotated, or busy account publishes
  nothing rather than keeping a path-bearing event under an obsolete scope.
- Account removal purges diagnostic scopes named by verified document aliases
  for that account and incarnation. Both diagnostic sinks must finish before
  document grants and alias provenance are retired. A failure keeps those
  aliases for restart recovery. Unrelated accounts and unknown hashes are never
  attributed to the removed account.

### Android provider recovery

**Authority**

- Account removal and folder recovery share one verified authority boundary. A
  supplied recovery session may address canonical document IDs, or explicitly
  retained historical aliases, only for the exact active or retired
  incarnation.
- The caller holds the account operation lease. Recovery does not reload
  ordinary credentials or take a second blocking lease. Deferred provider
  recovery uses the supplied session under the removal lease before credentials
  are persisted.
- Recovery through another local account's legacy tree tries that account's
  lease without waiting, using both canonical and legacy operation identities,
  and verifies its exact active session. It stays pending when the account is
  busy. The lease spans content authentication and reconciliation, and recovery
  keeps the original tree URI, grant, and discovery scope.
- Download retirement and the grouped diagnostic and grant cleanup run in one
  failure boundary. Unresolved downloads keep grants and verified alias
  provenance for the next attempt.

**Trees and grants**

- A sync pair keeps its SAF grant until its pending local transactions and
  retirement complete.
- Provider reconciliation runs outside the sync engine lock. Before retiring an
  account or removing a pair, the engine retakes the lock and verifies that the
  selected pair snapshots have not changed; unrelated account state is kept.
- Legacy self-provider trees owned by another account recover through their
  original provider URI and retained tree permission, using authoritative
  provider reads. Only a root matching the removing account may receive its
  recovery session or a local authority rewrite. An unavailable original
  provider, or a busy, unavailable, or unverified cross-profile account, leaves
  recovery pending. Ordinary external providers keep their existing grant
  behavior.

**Recovery-token discovery**

- When the removal session is bound to the local provider's account, pending
  owned recovery tokens are discovered from that account's root, including
  directories moved outside the old sync subtree. The scan keeps its depth,
  count, ownership, content-authentication, and cancellation bounds.
- Expanded discovery indexes only the selected tree's transactions and legacy
  transactions not proven to belong elsewhere. Seeing another tree's token never
  authorizes its reconciliation.
- Multiple observed locations for an owned token are rejected without retiring
  its ownership record. Recovery listings keep the validated parent identity so
  alias-scoped ownership can still find relocated children.

**Content verification**

- Self-provider recovery bypasses unversioned offline content and cached
  fallback reads. It requires a network listing before accepting
  generation-matched virtual content or opening an ETag-bound range source.
- Relocated recovery can be attributed by an authenticated stage or an
  authenticated backup. Backup-only delete transactions need no stage, but still
  need both the recorded document identity and content identity.
- Path-changing stage IDs need the original stage name and matching recorded
  content. Renamed backup IDs need matching recorded content even when the name
  contains the recovery token. Unverified token-bearing candidates keep the
  ownership row without authorizing a rename or deletion.
- Recovery keeps the exact ETag of a successful content verification and uses
  it as the later delete or rename precondition. Failed or cancelled
  verification invalidates that proof, and a concurrent replacement cannot
  supply its newer ETag.
- Directory verification captures the authoritative collection generation
  before reading the tree, and restoration uses that generation. Cleanup moves
  a directory with a conditional, no-overwrite MOVE to
  `Recovered folder - <unique suffix>` in the original parent instead of
  recursively deleting descendants. Failed or ambiguous moves keep a recovery
  path, and no original or unrelated content is overwritten.

## Primary protocol references

- [iCalendar recurrence identity](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.8.4.4)
- [Nextcloud WebDAV](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html)
- [Nextcloud OCS API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/OCS/ocs-api-overview.html)
- [Nextcloud Activity API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/activity-api.html)
- [Nextcloud Client Integration API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/ClientIntegration/index.html)
- [Notes API](https://github.com/nextcloud/notes/blob/main/docs/api/README.md)
