# Dynamic App Descriptor 1.0

`DynamicAppDescriptor` is the machine-readable boundary between API discovery,
native presentation, and HTTP execution. It lets the app present an installed
Nextcloud app without a hand-written adapter, while never inventing an
endpoint or a write. Read this before changing contract acquisition, the
descriptor compiler, or how dynamic apps read and present data.

**Last reviewed: 2026-10-02.** Discovery limits may have changed. The
[Kotlin compiler](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAppDescriptorCompiler.kt)
and its runtime validation tests define application behavior. The Rust
validator and fixtures are reference-contract evidence, not proof of runtime
parity. See [schema ownership](NATIVE_SCHEMA.md#contract-ownership) before
exchanging models.

## Where the code lives

| Concern | Location |
| --- | --- |
| Descriptor model | [`DynamicAppDescriptor.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAppDescriptor.kt) |
| Descriptor compiler | `DynamicAppDescriptorCompiler.kt` in the same package |
| Mapping to the native schema | `DynamicDescriptorMapper.kt` (`toNativeAppSchema()`) |
| Signed App Store acquisition | [`SignedAppStoreContractAcquirer.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/SignedAppStoreContractAcquirer.kt) in the `contractAcquisition` module |
| Runtime orchestration | `DynamicNativeRuntime.kt` in `ui/src/commonMain/.../app` |
| Board and notes surface selection | `SemanticBoardContract.kt`, `SemanticNotesContract.kt`, and `SemanticSettingsContract.kt` in `ui/src/commonMain/.../nativeui/runtime` |
| Rust reference model and compiler | [`src/dynamic.rs`](src/dynamic.rs) and [`src/dynamic_compiler.rs`](src/dynamic_compiler.rs) |

Both the Kotlin and Rust models serialize camel-case JSON with
`descriptorVersion: "1.0"`.

## Contract

A descriptor contains:

- the exact app identity and an endpoint policy with the server origin and the
  approved API path prefixes;
- capability facts and permission requirements, each with provenance and
  confidence;
- typed resources and fields;
- explicit list, detail, or grid layouts;
- field links, where inferred URLs default to `allowExternal: false`;
- forms bound to explicit mutating actions;
- actions with method, path, path and query parameters, body media type and
  schema, authentication requirements, and OCS envelope metadata;
- warnings describing omitted or degraded behavior.

A descriptor is validated after compilation and again after deserialization.
Kotlin consumers call `requireValid()`; Rust consumers call
`DynamicAppDescriptor::validate()`. Validation rejects:

- unsupported versions;
- duplicate or dangling references;
- path-parameter mismatches;
- absolute or cross-origin paths, and paths outside the approved app prefixes;
- writes without advertised OpenAPI or verified-adapter provenance.

## Discovery inputs

The runtime `DynamicAppDescriptorCompiler` and the Rust reference
`DynamicDescriptorCompiler` accept normalized facts in `DynamicDiscoveryInput`.
The sources, from strongest to weakest:

1. **Advertised OpenAPI.** An OpenAPI 3.x document that the connected app
   advertised. The fetching layer supplies the document and its advertised
   same-origin URL; the compiler never searches arbitrary URLs.
2. **Signed App Store package.** An OpenAPI 3.x document extracted from a
   release package in the official Nextcloud App Store. It is accepted only
   after local certificate-chain, signed-CRL, app-ID, version, and
   archive-signature verification. Provenance is `verifiedAppPackage`.
3. **App Store-linked source tag.** If a verified package contains no OpenAPI
   file, an OpenAPI 3.x document from the GitHub release tag linked by that App
   Store entry. Repository and tag come only from official catalog metadata,
   and the tag's `appinfo/info.xml` must reproduce the selected app ID and
   version. This source is unsigned and lower trust; provenance is
   `appStoreLinkedSourceTag`. Every operation must still stay inside the
   connected server's approved same-origin app prefixes.
4. **Observed reads.** Successful 2xx JSON GET responses from an already
   approved endpoint. Response-shape inference creates read-only fields, a read
   action, and a list or detail layout. It never creates a form or a write.

OpenAPI is authoritative for explicit writes. An unnamed OpenAPI write is
omitted, every declared mutation requires confirmation, and deletes are marked
destructive.

### Package selection and verified read routes

Acquisition first tries the release that exactly matches the installed
version. If that release has no usable contract, it tries other non-prerelease
releases with the same major and minor version from the server-compatible
catalog, preferring the nearest earlier patch. A contract from such a release
is labeled patch-compatible in the user-visible diagnostics; it still passes the
same signature, app identity, and version checks.

A verified package without OpenAPI can still yield **verified read routes**.
[`StaticRouteContract.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/StaticRouteContract.kt)
parses `appinfo/routes.php` with a bounded literal-array parser and never runs
PHP. It keeps a route only when its controller statically extends a supported
Nextcloud controller, declares the referenced method, and either uses an API
base or declares a JSON-serializable response type. Read the source comment for
the narrow write shapes it can retain; the scalar write rules live in
[`StaticScalarWriteContract.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/StaticScalarWriteContract.kt).
Besides create, update, patch, delete, and a short workflow list, they accept
two verb families for every app: relationship verbs (`assign`, `unassign`,
`add`, or `remove` followed by a related resource whose identity the scalar
signature requires, such as `assignLabel($cardId, $labelId)`) and restore verbs
(`restore`, `undoDelete`, `deleteUndo`, `undelete`) on a route that names its
target. Relationships that name sharing, access control, membership,
permissions, ownership, groups, or teams are excluded, and send and merge verbs
are not accepted. A `DELETE` route may carry only a removal relationship. Risk
and confirmation still follow the HTTP method, so a `DELETE` relationship is
destructive and confirmed.

### Package declaration evidence

The same signed package also yields declaration evidence that is read
statically and never executed. Synthesis no longer needs `appinfo/routes.php`:

- **Route attributes.** `#[ApiRoute]`, `#[FrontpageRoute]`, and
  `#[Route(type: ...)]` on public controller methods directly in
  `lib/Controller/` are read like the server router reads them, through
  namespace imports and aliases such as `Http\Attribute\FrontpageRoute`.
  OCS attribute routes mount under `/ocs/v2.php/apps/{app}`, others under
  `/apps/{app}`. Only literal verbs, URLs, requirement and default maps, and
  postfixes are accepted; a concatenated, interpolated, or constant URL, an
  unknown argument, or a custom `root` drops the route and is counted in
  `x-nextcloud-native-skipped-routes`. When `routes.php` and an attribute
  claim the same route name with different URLs, both are dropped.
- **Route file.** `resources` and `ocs-resources` expand to the router's
  index, show, create, update, and destroy actions. Resource reads are
  published as before; resource writes are recorded as route evidence only.
  An omitted verb defaults to `GET`. A same-file route helper is unwrapped only
  when its whole body is `return $route;` or `return array_merge($route, [...])`
  with keys that cannot change the route name, URL, verb, root, or postfix.
- **Policy facts.** Each operation carries `x-nextcloud-native-policy`: the
  access level the server's security middleware enforces (no
  `NoAdminRequired` means admin only), CSRF and CORS handling, password
  confirmation and its strict flag, user and anonymous rate limits,
  brute-force actions, request headers, the OpenAPI scope, and any
  app-defined attribute as an opaque name with literal arguments. Legacy
  docblock annotations are read the same way.
- **Hidden inputs.** `$this->request->getParam('x')` where `x` is a route
  placeholder binds that path parameter and is marked
  `x-nextcloud-native-request-input`. One `getUploadedFile('f')` read on a
  `POST` declares a multipart body with file field `f`. Any other request
  access keeps the method unproven.
- **Types.** Psalm `@param`, `@return`, `@psalm-type`, and
  `@psalm-import-type` declarations, including `lib/ResponseDefinitions.php`,
  map to OpenAPI schemas for the subset openapi-extractor supports. A psalm
  parameter type narrows the native type only when compatible. Malformed,
  recursive, or oversized types are dropped; the route keeps its weaker
  evidence.
- **Entities.** `jsonSerialize()` and `toAPI()` methods in `lib/Db` that
  return one literal array record their field names, typed by `addType()`
  where stated, in `x-nextcloud-native-entity-serializations`. A response
  links to an entity only through a declared return type naming a
  `JsonSerializable` entity.
- **Merge hint.** A `PUT` or `PATCH` whose non-path parameters are all
  nullable with `null` defaults records
  `x-nextcloud-native-merge-hint`. It is a hint, never a merge guarantee.
- **Capabilities.** Literal key paths in `lib/Capabilities.php`, including
  `version` and API version keys, are recorded in
  `x-nextcloud-native-capability-keys` for comparison with the live response.

Every extracted route, published or not, is listed in
`x-nextcloud-native-static-routes` with its signature and facts, so a later
admission step can evaluate it without re-reading the package. Attribute
routes and methods that read a route placeholder through
`$this->request->getParam()` now pass the same scalar write rules as
`appinfo/routes.php` routes, so some of their writes are published. A method
the package marks as sub-administrator, delegated administrator, external-app,
or password-confirmed access is never published as a write. Administrator
access inferred from a missing `NoAdminRequired` is kept as the operation's
`access` fact for the admission step rather than decided here, because an
inherited or trait annotation the extractor cannot read would otherwise hide a
user-level write. A method that reads an uploaded file stays route evidence
until the write path can verify the stored file. A routes.php route and an
attribute route that share a URL and verb but name different controller
methods are both dropped, because the server dispatches to the attribute
route. Inherited and trait methods are not read, so their routes are missing
rather than wrong. Supplementary files (response definitions, entities,
capabilities) have their own size budget; when it is exhausted the document
states `x-nextcloud-native-supplementary-evidence-truncated`.

The descriptor compiler copies these facts into
`DynamicAction.contractFacts` only for verified package or exact source-tag
provenance. A same-origin advertised document cannot assert its own access
rules. Psalm-typed bodies, enums, nullability, defaults, and multipart bodies
flow through the existing schema path. Entity serializations, capability
keys, and the static route evidence list remain in the contract document.

Acquisition may keep verified read routes while checking compatible releases
for a richer contract. If an optional later package returns HTTP 404 or 410, an
already verified route contract stays usable. This never bypasses certificate,
signature, app identity, or version checks, and never turns unverified routes
into a fallback. Without a verified route contract, a missing package is an
acquisition failure.

Rust fixtures in `tests/fixtures/` cover an advertised OCS/OpenAPI app and an
unknown app learned from a successful JSON list response.

## Structured row details and form eligibility

A row detail reuses explicit composite table relationships and verified column
reads.

- `NativeTableRecordScope` selects one table or view scope from the returned
  parent foreign key, instead of rejecting every schema with several
  composites.
- Sparse response schemas may leave that key in bounded display data. Using it
  to read related columns never makes it mutation authority. A column route's
  generic `id` binds the parent only through its verified parent link.
- `NativeTableRecordDetail` renders declared column labels and complete
  observed cells in a local display copy. Conflicting identities, ambiguous
  scopes, duplicate columns, and truncated values keep the existing fallback.

Descriptor navigation withholds a form when a required body field has no native
input. For example, a required string-or-object `data` union must not become a
form containing only an optional view selector. Action execution keeps its own
body completeness checks.

## Authenticated application reads

[`JvmAuthenticatedAppReadSession.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmAuthenticatedAppReadSession.kt)
prepares an account session before dynamic GET requests under the account's
`/apps/` or `/index.php/apps/` prefix. Some apps need this because they resolve
their user while the filesystem app boots:
[Nextcloud 34.0.3 loads filesystem apps before Basic login](https://github.com/nextcloud/server/blob/v34.0.3/lib/base.php),
and [Music 3.2.1 resolves its user in application boot](https://github.com/nc-music/music/blob/v3.2.1/lib/AppInfo/Application.php).

**Session and cookie bounds**

- Concurrent reads share one authenticated OCS profile GET per account and
  credential generation.
- Cookies stay in process memory and expire within 15 minutes. At most 16
  cookies and 8 KiB are kept per account, for at most eight accounts.
- A repeated cookie name with the same domain and path uses the final response
  value, including deletion, so session rotation cannot resend an old session
  ID.
- The adapter checks the exact origin and account path, rejects cross-account
  redirects, and sends cookies only with application reads. Mutations and
  public contract downloads keep their existing authentication.
- Credential replacement, account retirement, and unauthorized responses
  invalidate the session, and stale work cannot restore it.
- No cookies or profile bodies reach support diagnostics or persistent storage.
  Bootstrap failures record only a stable stage and code, plus an HTTP status
  when available. Cancellation creates no failure event.

**Failure behavior**

- An unavailable bootstrap endpoint (404, 405, or 501), or a valid profile
  without cookies, leaves Basic authentication in use with a one-minute
  bootstrap cooldown.
- Authentication, network, malformed-response, and other server failures stay
  failures. The adapter never retries a failed application read.

[Deterministic tests](ui/src/desktopTest/kotlin/dev/obiente/nextcloudnative/app/JvmAuthenticatedAppReadSessionTest.kt)
cover isolation, retirement, cancellation, bounds, redirects, and unsupported
bootstrap behavior. A successful bootstrap does not establish that an app's
individual operations are compatible.

## Discovery diagnostics

`DynamicDiscoveryDiagnostics.kt` records bounded stage and outcome tokens for
acquisition, JSON parsing, and descriptor compilation in the existing support
diagnostics. It records no contract content, exception text, account identity,
or request URL. Cancellation propagates without a failure event, and a recorder
failure never discards a usable contract. An unverified app version keeps
writes disabled without showing a permanent refresh spinner.

The contract information dialog shows stable diagnostic codes, such as
`server-probe-inconclusive` or `signed-package-imported`. Discovery records
them as typed `DynamicDiagnosticCode` events, and import codes derive from the
typed acquisition kind, so rewording a diagnostic message never changes a code.

## Metadata fallback reasons

**Last reviewed: 2026-10-17.** This section describes source and deterministic
test behavior; the code may have changed since, and the linked sources are
authoritative. It is not a claim about a published release.

When no same-origin or App Store source yields a verified contract, discovery
keeps the metadata-only descriptor and records a typed reason in
[`DynamicContractFallback.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicContractFallback.kt).
The fallback notice derives its title, explanation, and action from that reason:

- the inspected releases provide no verified contract for the installed version;
- the App Store lists no release matching the installed version;
- the App Store lists the app, but the entry that would be selected lacks a
  required value such as its certificate, download, signature, or signature
  digest, or supplies one with the wrong JSON type;
- an App Store catalog document could not be read at all, so no entries were
  available to check;
- the app's linked source refused or rate limited the last fallback request
  (HTTP 403 or 429), so a contract may still exist;
- an explicit retry came less than a minute after the previous catalog check
  and no saved catalog could be reused;
- the App Store returned data this version cannot use, such as a response over
  its size limit;
- the server's own API catalog or contract probe was inconclusive;
- the server rejected the account's credentials, or the account may not read
  the server's API catalog, during that probe;
- the server or App Store offered a contract that cannot be shown natively,
  such as a Swagger 2 document;
- the server or app version could not be confirmed;
- the App Store could not be reached, or did not provide the release;
- the App Store answered, but the app's linked source repository could not be
  reached; this is kept apart from an App Store outage where the linked request
  is made, never inferred from exception text;
- the package or its source failed verification;
- the client could not complete the check.

[`ContractAcquisitionFailure.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/ContractAcquisitionFailure.kt)
classifies the module's own failures: HTTP status responses, verification and
origin rejections, malformed request values, network failures, and runtime
linkage errors. Writing a downloaded catalog to the local cache is best effort,
so a local storage failure is never reported as an unreachable App Store.
[`JvmAppStoreContractAcquisition.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmAppStoreContractAcquisition.kt)
translates them once for both JVM targets. It also catches `LinkageError`. If
acquisition code cannot initialize on a runtime, for example because the
Android ICU engine rejects a regular expression, the result is a client-fault
fallback, not an exception class name. Discovery diagnostics, fallback copy,
and contract information never contain exception messages, request URLs,
hosts, paths, header values, or response bodies. Same-origin diagnostics name
only a stage (catalog, spec, body, or compilation), a typed probe result, an
HTTP status class such as `HTTP 5xx`, a sanitized spec identifier, and a
bounded media type. A fallback reason is never persisted. The original
exception remains only as the cause of the typed acquisition exception, for
local debugging; it is never written to diagnostics or support reports.

A completed acquisition without a contract is a typed outcome, not a bare
`null`.
[`ContractAcquisitionOutcome.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/ContractAcquisitionOutcome.kt)
separates no matching release, a malformed catalog entry, a temporarily
unavailable linked source, and releases inspected without a usable contract,
and the shared JVM boundary carries that reason to the fallback notice. Catalog
parsing records which required value made each dropped entry unusable. A
required value must have its exact JSON type: a number, boolean, object, array,
or `null` where a string belongs, or an `isNightly` that is not a boolean, is
malformed like a missing value and is never coerced.

These readers also require exact JSON types:

- In the acquisition module's contract readers, a non-string `openapi` version,
  operation ID, parameter name or location, or reference is ignored. A
  non-boolean `required` flag counts as required.
- The same-origin probe accepts only a string `openapi` version, so a number
  such as `3.0` is an unsupported contract. It also matches OCS API Viewer
  entries by a string `id` only.
- The descriptor compiler reads string fields only from JSON strings. These
  include `openapi`, operation IDs, parameter names and locations, schema types
  and formats, summaries, descriptions, titles, and references.

The descriptor compiler does not yet type-check boolean flags such as
`required`, `readOnly`, and `nullable`, or the items of string arrays: required
property lists, tags, `enum` values, and security scopes. Those still accept a
value of another primitive type and read it as text or as a boolean.

A catalog document that is truncated, empty, not JSON, or not an array is an
unreadable catalog, a reason of its own, because no entry could be read. So is
a non-empty array in which no element is an app object with a string `id`. Such
a document is never cached, so checking again downloads it again.
A corrupt or mismatched App Store-linked source archive yields no candidate,
like an archive without a contract, instead of an unexpected client fault.

[`ContractAcquisitionEvidence.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/ContractAcquisitionEvidence.kt)
records what one acquisition actually inspected:

- whether each catalog document was readable;
- the entries dropped as malformed, including a top-level element that is not
  an object or has a missing or wrong-typed `id` while the app was not found,
  because that element may have been the app, and differing records that share
  the app's `id`, because no single one can be trusted (structurally equal
  copies are read once; key order does not matter and numbers compare by
  value, so `1` equals `1.0`);
- the candidates inspected, and what each provided;
- whether a linked source refused a request.

The no-contract reason comes only from that record, in this order:

1. A refused or rate-limited linked source, because a contract may exist there.
2. A catalog the refresh window refused with no cached copy, so it was not read
   at all: `RecentlyChecked`. Waiting out the window is the next step, so this
   ranks above an unreadable catalog.
3. An unreadable catalog.
4. Malformed evidence that could have matched: an entry for the exact version
   or with an unreadable version, a patch-compatible entry in the platform
   catalog, an app without a readable release list, or an unidentified
   top-level element.
5. No usable contract, only when every candidate that could have matched was
   inspected.
6. No matching release, only when the catalogs were fully readable, nothing
   relevant was dropped, and nothing matched.

A dropped entry for an unrelated version never changes the reason.

App Store catalogs are cached for six hours. The first discovery of an app
screen uses the cache. A discovery started by, or after, an explicit retry on
that screen carries `AppStoreCachePolicy.Refresh` through the platform
boundary to the acquirer, which downloads each catalog again at most once per
minute per catalog URL. A retry inside that window reuses the cached copy; if
none is available, for example after a cold start or an expired entry, it makes
no request for that catalog. Explicit retries therefore cause at most one App
Store request per catalog URL per window. The window is shared across apps and
versions to protect the App Store, so one catalog can be refused while the other
is downloaded fresh. A refused catalog is uninspected evidence, never absence:
candidates from the catalog that was read are still inspected, and only when
none of them provides a contract does the refused catalog make the result
`RecentlyChecked`. Automatic discovery
without a cached catalog downloads it normally. A forced download that fails or
is unreadable leaves the cached catalog in place, because only a readable
catalog replaces it, and an older download never replaces a newer one; see the
publication order below.

The same policy covers verified contracts. Automatic discovery serves a cached
verified contract. A refresh looks past it and downloads the package or linked
contract again; a newly verified contract replaces the cached one, while a
failed refresh leaves it in place. When the window refused or served from cache
any catalog a refresh needed, and no candidate it could inspect provided a
contract, the refresh serves the cached contract when one exists. When an acquired contract cannot be parsed or
compiled, discovery discards the cached contract for exactly that app, server
version, and installed version, so the next automatic discovery acquires it
again; other cached contracts stay. If the cached file cannot be deleted, an
invalidation marker beside it, or, when that also fails, a record kept for the
life of the process, makes every later read a miss, and each later read or
store retries the deletion. A `VERIFIED_CONTRACT_DELETION_FAILED` warning
diagnostic records which of the two applied, with no path or exception text.

Each cache key has an entry file, an optional invalidation marker, an optional
in-process record, and, while a store runs, a temporary file that reads ignore.
[`VerifiedContractCache.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/VerifiedContractCache.kt)
holds one lock for every operation and moves between these states:

| State | Read | Invalidation | Store |
| --- | --- | --- | --- |
| Absent | Miss | No change | Write the temporary file, then move it into place |
| Valid entry | Hit | Delete the entry; if that fails, add the record and write the marker | Write the temporary file, delete the entry, move the file into place; if a step fails before the move, delete the older entry or else invalidate it |
| Entry with marker or record | Delete the entry, then clear the marker and record; miss | Retry the deletion; stays invalidated until the entry is gone | Delete the entry first; if that fails, change nothing and refuse. Then clear the marker and record; if that fails, refuse. Then write and move |
| Marker without entry | Clear the marker and record; miss | Clear the marker and record | Clear the marker and record (if that fails, refuse), then write and move |
| Leftover temporary file | Ignored | Ignored | Ignored; each store uses a new name |

An invalidation is removed only after the invalidated entry is gone, and a new
entry is written only after the invalidation is gone. Process death or a failed
write, such as a full disk, at any step therefore leaves either the invalidated
entry with its marker, or no invalidated entry; an invalidated contract never
becomes readable again. A valid entry that cannot be replaced is deleted or
invalidated, so an older contract is not served after a restart in place of a
newer one. Only an entry whose deletion and marker both failed relies on the
in-process record alone, which a restart loses. A refused or failed store keeps
the contract in memory, and a `VERIFIED_CONTRACT_NOT_PERSISTED` warning
records `InvalidationNotCleared` or `Failed`.

Results are ordered per app, server version, and installed version by
[`ContractPublicationFence.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/ContractPublicationFence.kt).
Each acquisition takes a generation in the same step that registers it as in
flight, so a later refresh or invalidation always sees it as started; a caller
that joins an acquisition already in flight takes none. A generation that has
not been superseded ends as follows:

| Ending | Effect on older generations |
| --- | --- |
| Verified contract | Published; every older generation is superseded |
| No matching release, no usable contract, or malformed entry | Recorded; every older generation is superseded |
| Refused linked source, unreadable catalog, throttled retry, source or network fault | None |
| Cancellation | None |
| Cached contract served without inspecting releases | None; it is served only while no no-contract result or invalidation is the latest accepted result |

An invalidation supersedes every generation already started. A transient
ending fences nothing because it proves nothing about the contract, so an
older acquisition that verifies a contract afterwards may still publish it. A
superseded acquisition never publishes or reports its own result; it reports
the newest accepted one: the newer contract, the newer no-contract reason, or,
after an invalidation, no usable contract. A slower automatic acquisition that
began before an explicit retry therefore cannot overwrite or contradict the
retry's result. A no-contract result does not remove a previously cached
contract, but while it or an invalidation is the latest accepted result, a cache
read reports that result instead of the cached contract. That record is kept in
memory, so after a restart the cache is served until a refresh records it again.
A contract read from the persistent cache enters the in-memory cache only
through the same ordering: a read that a newer accepted result superseded, or
that follows a no-contract result or an invalidation, does not promote it, and
a promotion never replaces a contract already in memory. The ordering is kept in memory for each acquirer and never waits for
another acquisition, so a cancelled or failed acquisition cannot hold back
later ones.

Catalog downloads are ordered per catalog URL by
[`CatalogPublicationFence.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/CatalogPublicationFence.kt).
A download takes a generation before its request is sent, and its readable
catalog replaces the cached copy, in memory or on disk, only if no download of
the same URL that started later was stored first. As for contracts, only
evidence fences: an unreadable catalog, an HTTP or network fault, a refused
refresh, or a cancellation stores nothing and fences nothing, so an older
download that succeeds afterwards is still stored. The fence records the digest
of the newest catalog it accepted, even when storing it failed, and a cached
copy that does not match is read as a miss.

The two orderings compose. A contract or definitive no-contract result records
the catalogs it was read from, and it is accepted only while each of them is
still the newest catalog for its URL; otherwise the acquisition runs again from
the newer catalog, up to three times, and then reports the source as
temporarily unavailable, which fences nothing. A result read from the newer
catalog outranks an accepted result read from a catalog that was since
replaced, even if that result's acquisition started later. A catalog download
that finishes after a refresh therefore cannot bring back a removed release.

Every cache that contract acquisition writes, and what keeps an older writer
from finishing over a newer one:

| Cache | Key | Writers | Ordering |
| --- | --- | --- | --- |
| App Store catalog, in memory or on disk | Catalog URL; a SHA-256 file name on disk | A readable download in `AppStoreCatalogReader` | `CatalogPublicationFence` by download generation; the file cache also holds one lock for every read and write |
| Verified contract, in memory | App, server version, installed version | Publication, invalidation, and promotion of a disk read | `ContractPublicationFence`: publish, invalidate, and promote each run under its lock and generation rules |
| Verified contract, on disk | SHA-256 of the same key | Publication stores, invalidation removes; a read deletes only an invalidated entry | Stores and removals run inside the contract fence; the cache holds one lock and the state table above |
| Refresh window | Catalog URL | `CatalogRefreshThrottle.tryRefresh` at the start of a refresh | One atomic update per URL that also reads the clock, so a caller that read the clock earlier cannot move the window back |
| Acquisitions in flight | Request and cache policy | Registration and completion in `acquireOutcome` | Registered with its generation under the contract fence lock; removed only by the acquisition that registered it |
| Fence state | Catalog URL; request | The two fences | Counters only grow, each under its fence's lock |

The fences are in memory for each acquirer, so after a restart the first
results are ordered from that point on.

[`DynamicSameOriginDiscovery.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicSameOriginDiscovery.kt)
records same-origin probe results as typed `DynamicServerProbeFailure` values.
When probes disagree, the highest-precedence result wins, and the first one
wins among equals:

1. HTTP 401 (`AuthenticationRequired`): the card asks the person to sign in
   again and offers no retry, because checking again would resend the rejected
   credentials. The app has no reauthentication within a session, so its
   Open account settings action leaves the app for Settings, where Account
   holds Sign out and revoke access; the card also names that route. The action
   only navigates, so nothing is signed out until the person chooses to. A
   screen that cannot open settings shows the explanation without a button.
2. HTTP 403 (`PermissionDenied`): the account may not read the catalog, which
   may be limited to administrators. Its Check again action stays, and the card
   says that checking again helps only after an administrator grants access or
   the App Store adds a release.
3. Inconclusive results that a retry may resolve: no response, a 5xx status,
   HTTP 429, any other unsuccessful status that is not a definitive absence
   (`UnexpectedStatus`, for example 400, 405, 408, 409, 412, an unfollowed 3xx
   redirect, or a 1xx status), or a successful catalog or contract response
   that is not readable JSON or declares no OpenAPI version
   (`UnreadableResponse`). An OCS API
   Viewer entry for the app whose spec list is missing, not an array, or has a
   malformed identifier with no usable one left is also `UnreadableResponse`;
   usable identifiers beside malformed ones are still read. When the catalog
   does not list the app, an element that is not an object or has no string
   `id` may have been the app, as in App Store catalogs, so it is also
   `UnreadableResponse`; such elements beside the app's entry are ignored.
   Differing records with the app's `id` are `UnreadableResponse` too, and
   none of them is read; identical copies, compared as JSON values, are read
   once.
4. `UnsupportedContract`: a JSON contract that declares a version other than
   OpenAPI 3, such as Swagger 2, or an OpenAPI 3 contract that cannot be
   compiled.

Only 404 and 410 are definitive absence statuses; every other unsuccessful
status is classified, so a failed request is never read as absence. A 404 or
410, a non-JSON response at a static contract location, an empty OCS API Viewer catalog or one whose entries all name other
apps by a string `id`, or a well-formed
spec list that is empty or names only other apps is absence and
records nothing. Every App Store path that ends without a usable contract goes
through one precedence point: no release can be selected because the server
version is unavailable, no release or contract exists, acquisition fails with a
typed or unexpected failure, or an acquired contract cannot be parsed or
compiled. Any recorded server result takes precedence over that reason. A
definitive server absence keeps the App Store or version reason. Diagnostics
record both sources either way, and never include request URLs, headers, or
response bodies.

### Failure classification

Every failure caused by remote or source data maps to a typed source outcome,
every network failure maps to a network or temporarily unavailable outcome, and
only genuine client faults remain unexpected. Size limits throw
`ContractSourceTooLargeException`, a source failure.

| Site | Failure | Classification |
| --- | --- | --- |
| Request validation | Malformed app ID, server version, or installed version | Invalid request, shown as an unconfirmed version |
| Verified contract cache | Missing, expired, or unreadable entry | Ignored; acquisition continues |
| Catalog and package requests | No response; non-success status; redirect away from HTTPS | App Store unreachable (network); source unavailable; verification failed |
| Catalog and package bodies | Declared or streamed size over the limit | Source unusable |
| Catalog document | Truncated, empty, not JSON, not an array, or only elements without a readable app identity | Unreadable catalog, never cached |
| Catalog app element | Not an object, or an object with a missing or wrong-typed `id`, while the app is not found | Malformed source, not no matching release |
| Catalog app records | More than one differing record with the app's `id` | Malformed source; identical copies are read once |
| Catalog entry | Missing value, a required value of the wrong JSON type, or a download that is not an HTTP URL | Malformed source when it would be selected |
| Candidates exhausted | A catalog was unreadable, or an exact, compatible, or unidentified entry was dropped, and no inspected candidate has a contract | Unreadable catalog or malformed source, not no usable contract |
| Verified contract publication | A newer generation published a contract or ended without one definitively, or an invalidation happened | Not published; the newest accepted result is reported |
| Catalog cache write | Local storage failure | Ignored; the next read of the older cached copy is a miss |
| Catalog cache write | A download of the same URL that started later was already stored | Not stored; a result read from it is not accepted and the acquisition runs again |
| Explicit retry inside the refresh window without a cached copy | None | No request for that catalog; candidates from the other catalog are still inspected, and recently checked only if none provides a contract |
| Package verification | The package's signature, certificate, chain, revocation, identity, archive structure, or app metadata rejected | Verification failed |
| Package verification | Bundled root certificate or revocation list missing, unreadable, corrupt, expired, of an unsupported format, or inconsistent | Client fault (runtime incompatible); retrying cannot repair it |
| Package archive limits | Entry, expanded, path, or metadata-count limit exceeded | Source unusable |
| Package OpenAPI JSON or YAML | Unreadable or not OpenAPI 3 | Candidate skipped |
| Linked metadata, tree, spec, schema, and archive requests | 404; size over the limit; other status; no response | Skipped; skipped; source unavailable; linked source unreachable, never App Store unreachable or no usable contract |
| Linked archive request | HTTP 403 or 429 | Source temporarily unavailable |
| Linked archive content | Corrupt, unsafe path, identity mismatch, malformed or misencoded `info.xml`, size limit | No candidate |
| Linked JSON or YAML documents | Unreadable | Skipped |
| Acquired contract | Unreadable JSON or a descriptor that cannot be compiled | Unsupported contract; that cached contract is discarded |
| Verified contract cache discard | The cached file cannot be deleted | Marked invalid, or invalidated for the process; never served again |
| Verified contract cache store | An invalidated entry or its marker cannot be removed | Nothing written; the invalidation stays; served from memory; `VERIFIED_CONTRACT_NOT_PERSISTED` warning (`InvalidationNotCleared`) |
| Verified contract cache store | The temporary file cannot be written or moved into place, for example on a full disk | No invalidated or replaced entry stays readable; served from memory; `VERIFIED_CONTRACT_NOT_PERSISTED` warning (`Failed`) |
| Contract module class loading | `LinkageError` | Client fault |
| Same-origin probes | See the precedence list above | Typed probe result |
| Same-origin probe status | 404 or 410 | Absence; records nothing |
| Same-origin probe status | Any other unsuccessful status without a specific kind | Inconclusive (`UnexpectedStatus`); diagnostics carry only the status class |
| Remaining unexpected failures | XML parser configuration, internal invariants | Client fault |

## Read failures

[`DynamicReadFailure.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicReadFailure.kt)
classifies unsuccessful HTTP reads as authentication, permission, missing
content, throttling, server failure, or rejected request.

- Ordinary UI names the affected collection and a safe next step. It never
  shows request methods, resolved endpoints, arbitrary server messages, or
  exception class names.
- The reviewed mailbox-not-synchronized message stays available without
  revealing a mailbox identifier.
- The exception keeps typed status and method fields. Platform network
  diagnostics keep status, method, bounded response size, and a redacted URL.
  Presenting the error never makes another request or duplicates that event.

[`DynamicReadFailureTest`](ui/src/commonTest/kotlin/dev/obiente/nextcloudnative/app/DynamicReadFailureTest.kt)
covers message privacy, status classification, malformed and oversized error
bodies, invalid text, and deeply nested JSON.

## Semantic surfaces: boards and notes

**Last reviewed: 2026-10-31.** This section describes source and deterministic
test behavior; the code may have changed since, and the linked sources are
authoritative. No board or notes write chosen this way has been validated
against a live server or shipped in a published release.

After discovery, the app screen asks whether the discovered contract has the
shape of a board or of a notes collection
([`SemanticAppWorkspace.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/SemanticAppWorkspace.kt)).
The answer never depends on the app ID or its version; anything else stays
with the generic renderer.

- Route shape: mount prefixes (`/index.php`, `/ocs/v2.php`, `/apps/{app}`),
  `api`, and version segments are removed. Each placeholder is bound to the
  entity its name states (`stackId` is a list, `cardId` a card) or, for a
  generic `{id}`, to the collection word before it
  ([`SemanticRouteShape.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/SemanticRouteShape.kt)).
- Board: a board collection read, a list collection read inside a board, and a
  card read, create, or list. Each operation (board, list, and card edits,
  card move, archive, labels, assignees, comments, attachments, sharing) is
  resolved separately
  ([`SemanticBoardContract.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/SemanticBoardContract.kt)).
  A move needs a declared list field in its body, so a contract without one
  offers no move or drag. A label or user addressed inside a card
  (`/cards/{cardId}/label/{labelId}`) relates it to that card: `POST` or `PUT`
  assigns and `DELETE` removes; it is never treated as deleting the label.
- Notes: a collection read and an item read whose records carry a title and a
  text body, and that the contract words as notes or whose records also carry
  two of folder, favorite, and modification fields
  ([`SemanticNotesContract.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/SemanticNotesContract.kt)).
- Settings: a parameter-free settings read plus either one document write that
  declares each setting or a `{key}` write with a single `value` field. A keyed
  write uses only keys the server returned
  ([`SemanticSettingsContract.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/SemanticSettingsContract.kt)).
- Writes: only actions with trusted contract provenance (advertised OpenAPI,
  verified package, or linked source tag), only while the contract is verified
  for the installed version, and each request waits for the contract write
  gate. Every request is built by the shared dynamic request builder from the
  chosen action, so paths stay inside the approved API prefixes.
- Inputs: request fields are matched to roles by name (title, description,
  order, due date, list, label, user, and so on). A required field the client
  cannot name makes a create unavailable. A full-replacement `PUT` fills
  undeclared fields from the server's current record, and a field with a
  single contract-declared value (a one-member enum or `const`) is sent with
  that value. Nothing else is invented. Path, query, and body inputs are kept
  in separate maps, so a move addresses the card in its current list in the
  path while the same-named body field names the destination list. Clearing an
  optional value on an update sends JSON `null` when the contract declares the
  field nullable, and an empty string for a non-nullable text field.
- Permissions: only an observed permission object on the board record grants
  edit, manage, or share actions. A record without one stays readable but
  offers none of them.
- A list query value is sent only when the contract enumerates it or declares
  it as the default; for example, a notes list omits note bodies only when its
  `exclude` parameter declares `content`.
- Duplicates: app API routes win over web routes, then the newest version
  segment, then the route with fewer path inputs.

Requests: [`SemanticBoardRoutes.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/SemanticBoardRoutes.kt)
and [`NotesRepository.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/NotesRepository.kt).
Note saves and deletes send the last known ETag as `If-Match`; list and note
refreshes send the cached ETag as `If-None-Match` and keep the cached copy on
`304`. Notes settings writes use the same contract write gate as note writes.
Tests:
`SemanticBoardContractTest`, `SemanticNotesContractTest`,
`SemanticBoardRoutesTest`, `SemanticNotesRepositoryTest`, and
`SemanticSettingsTest`, including a board-shaped app `kanban_x` at `42.0.0`, a
nested board shape with card relationship and restore routes, and a
notes-shaped app `jotter` at `0.0.1`. The relationship and restore rules are
covered by `StaticRelationshipWriteContractTest` with synthetic package
sources.

Not derivable from a contract, so not offered: board-scoped settings whose keys
need an app-specific key format (shown read-only from the board record), an
attachment type value that the contract does not fix, and writes the static
route rules do not retain (for example a controller method that reads its
inputs from the request object instead of its signature, as the signed Deck
package's API label routes do; its web label routes are retained).

## Contract-driven record writes

**Last reviewed: 2026-11-01.** This section describes source and deterministic
test behavior; the code may have changed since, and the linked sources are
authoritative. None of these writes has been validated against a live server or
shipped in a published release. See [COMPATIBILITY.md](COMPATIBILITY.md) for
recorded device and server evidence.

Record editors are semantic patterns, not per-app adapters. They never test an
app ID, an exact version, a version range, or a reviewed source tag, so any app
whose published contract and data match a pattern gets its editor. Every
operation comes from the installed app's own contract: a server-advertised
OpenAPI document, or the verified package or exact source tag acquired for that
installation. Observed reads never authorize a write. A replacement (PUT) edit
also needs the same-route read that the descriptor compiler pairs with it
(`resultRecoveryActionId`). The compiler records that pairing for a declared
contract: the app's advertised OpenAPI document (E2) or its verified package or
exact source tag (E3). Every editor write passes the admission and verified
write path described in [Write admission and verification](#write-admission-and-verification).

These editors are not the whole dynamic path. A few app-specific rules outside
them still test an app ID and exact version and are awaiting conversion to
contract-shaped rules: the Chores 0.1.0 and 0.2.0 version gate in
[`ChoresContractPolicy.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/ChoresContractPolicy.kt),
used by its navigation, workspace, invitation, work completion, and create
recovery rules,
the Pantry 0.23.0 owner reorder authority in
[`NativeRecordActions.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeRecordActions.kt),
and the Pantry 0.34.0 timestamp presentation in
[`NativePantryPresentation.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/NativePantryPresentation.kt).

[`NativeContractActions.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeContractActions.kt)
starts from the collection read that opened the screen, a route ending in
`.../{container}/{parent}/{collection}`, and selects writes at the matching
positions: create is a POST on exactly that route; an item write is the route
followed by one identity placeholder, or the collection directly below the
route base followed by one placeholder. Each candidate needs trusted
provenance, the matching intent, and matching path parameters. Two candidates
for one position are ambiguous and select none, and a restored read-only
contract offers nothing.

| Pattern | Recognized from | Editor |
| --- | --- | --- |
| Column-defined grid rows ([`NativeGridRowWrites.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeGridRowWrites.kt)) | A grid view whose parent record has column records and rows that store `[{column, value}]` cells; one write body property declared as an open key-value map | New, edit, and delete rows; one input per column type word (`text`, `number`, `selection`, `datetime` and their variants) with the options, limits, and mandatory flag the column states |
| Child records in a parent ([`NativeChildRecordWrites.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeChildRecordWrites.kt)) | A parent-scoped collection whose write bodies declare typed properties | New, edit, and delete with inputs derived from the body schema: text, money, date, choice or enum, one person, several people, and yes or no; choices come from the parent record or a related resource that lists them, such as a project's members or categories, under the choice-source rule below |
| Questionnaire responses ([`NativeQuestionnaireSubmission.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeQuestionnaireSubmission.kt)) | A record holding a `questions` list, and a POST below its item route whose body declares exactly one map of string arrays | Fill in and send answers keyed by question identity |

Rules shared by the editors:

- Field names only decide presentation and choice sources. The schema decides
  what is sent: property names, JSON types, enums, formats, limits, and the
  required list. A required field missing from the draft blocks the request.
- A field whose meaning is recognized but whose encoding the contract does not
  determine, for example several people packed into one string, makes that
  write unavailable with an explanation. Other optional fields without a
  recognized role are not shown and not sent.
- A reference field takes its choices from a collection named after its own
  stem: `categoryId` from `categories`, `member` from `members`. A person
  field named otherwise, such as `payer` or `userId`, takes another people
  collection (members, participants, users) only when at least one loaded
  record's stored value for that field resolves to identities in it. Without
  that evidence there is no picker, so the field is not editable, and a create
  that needs it is unavailable, for example in an empty collection.
- Identities come from authoritative records: the parent, the row, the child
  record, the questionnaire. A row ID never replaces a table or view ID, and a
  child ID never replaces a parent ID. Route values bind by position. A global
  item route is used only when the contract lets it name the parent, or the
  item record itself names that parent.
- Permissions come from what the parent record states: an owner field naming
  the signed-in account, `isShared: false`, a permission object, or `can...`
  flags. A parent that states nothing, or states only a value whose meaning the
  contract does not define, such as a numeric access level, offers no write.
  Questionnaires need a `canSubmit`-style flag, and a permission list or a
  closed or limit flag that refuses a response is explained.
- PATCH sends only the changed values; after the write the record is read
  back and the untouched values are compared with what the editor opened. PUT
  replaces the record, so it is offered only when the contract declares the
  same-route read, and it is a full echo
  ([`NativeFullEchoUpdatePlan.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeFullEchoUpdatePlan.kt)):
  changed fields are encoded from the draft and every other declared body
  field travels as the exact JSON the read returned, never re-encoded (a
  stored date-time is not shortened to the date the editor shows, and a stored
  `null` stays `null`). A body field the read did not return, or whose stored
  value does not fit its declared type, makes the record read-only for that
  route. A child-record field the editor reads under a reference alias
  (`payer` stored as `payer_id`) is echoed only when exactly one alias holds a
  value of the declared type. Grid cells the editor cannot show, such as a
  choice whose option was removed or a date-time with seconds, also travel
  exactly as read; a grid row holding a non-empty cell for a column the grid
  does not list (as in a filtered view), or a value of a column type it cannot
  encode, is not replaced.
- Immediately before a PUT, the executor re-reads the target through that
  same-route read, bypassing caches
  ([`DynamicReplacementWriteGuard.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicReplacementWriteGuard.kt)).
  When any stored body value differs from the copy the editor opened, nothing
  is sent and the editor offers a refresh; when the read fails, nothing is
  sent either. This narrows but does not close the window for a lost update:
  these contracts declare no ETag or version precondition, so a change that
  lands between the check and the write can still be overwritten.
- Grid cells whose representation neither the contract nor the data fixes are
  never encoded: links, rich text, and people columns, and boolean or
  date-time columns until a stored cell shows their representation. The
  generic inline cell editor is off for every column-defined grid, because its
  row-object reconstruction would send the `{column, value}` wrapper as a
  value.
- Questionnaire questions of type file, grid, ranking, color, combined date and
  time, and date or time range are not answered natively. If such a question
  is required, the questionnaire explains that instead of offering a send.
- After a grid or child-record update the executor reads the target back
  and compares it with the request: an edited value that reads back as sent
  confirms the write; one that reads back as the previous value after a
  successful response means the app did not apply it, which pauses changes to
  that route on this device; an untouched value that changed is drift, which
  saves the edit, pauses the route, and offers **Restore previous values**, a
  full-echo write of the baseline that is sent only when the person chooses
  it. Grid rows compare cell by cell.
- [`NativeEditorWriteSession`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeEditorWriteSession.kt)
  sends each request once. A rejection keeps the draft. An unconfirmed create
  or response triggers an authoritative refresh and blocks sending again until
  that refresh arrives, so the person can check for the change first. An
  unconfirmed PUT edit also waits for that refresh, and sending it again
  repeats the pre-write check, which refuses the stale copy if the first
  attempt was applied. Repeating an unconfirmed PATCH edit or delete is
  allowed because it is idempotent. Cancellation is rethrown and the result
  stays unconfirmed.
- Drafts are ordinary remembered UI state and are not kept across process
  death. Nothing is stored on the server until a request is confirmed.

Writes this model leaves unavailable for contracts verified in this
repository's fixtures, because the contract does not express what the write
needs:

- Expense create and edit when the people a bill was for are sent as one plain
  string (the Cospend OpenAPI excerpt), and expense edit when the contract
  offers only an unverifiable replacement, which includes every PUT edit known
  only from a server-advertised OpenAPI document.
- Any parent-scoped write in a container that states its access only as a
  numeric level.
- Questionnaire answers to combined date and time questions, and a
  questionnaire's closed state when it is stated only as a numeric code that
  the contract does not label. A `state` or `status` field whose contract enum
  labels name the current value closed, expired, or archived is explained as
  closed; otherwise the server remains the authority that refuses a response.
- Grid link, rich text, and people cells.

Not covered yet: grid table, column, and view management beyond the generic
contract forms; member management, recurring entries, and settlement actions
beyond the generic contract surfaces; questionnaire results for owners; and a
new child record in an empty collection, because the empty list uses the
generic empty state and no loaded record proves a person field's collection.

## Write admission and verification

**Last reviewed: 2026-11-06.** This section describes source and deterministic
test behavior on the integration branch; it has not been validated against a
live server or shipped in a published release, and the linked sources are
authoritative. The rules are in [AGENTS.md section 4](AGENTS.md#4-dynamic-app-intelligence).

Every compiled action carries its risk class (`riskClass`, R0 to R10, from
[`DynamicOperationRiskClass.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicOperationRiskClass.kt)),
the verified read of its route family (`readBackActionId`: the item read below
a terminal command segment, or the collection the target is one placeholder
below, from
[`DynamicWriteRouteFamily.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicWriteRouteFamily.kt)),
a declared `Idempotency-Key` header (`idempotencyKeyHeader`), and for sharing
the declared field proving that the actor may share (`sharePermissionFieldId`,
such as `canShare`). The evidence tier comes from provenance
([`DynamicEvidenceTier.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicEvidenceTier.kt)):
the advertised OpenAPI document is E2, the verified package or linked source
tag (and a reviewed adapter contract until it becomes a rule) is E3, and a
successful read is E6. A write whose verified package facts (`contractFacts`)
limit it to administrators, such as `access: admin` inferred from a missing
`NoAdminRequired`, is classified as administration (R9) and never offered;
missing facts, as for a same-origin advertisement, count as unknown rather than
administrator-only. The package's merge hint
(`nullable-null-default-parameters`) is recorded but does not unlock sparse
updates, and upload methods are not published as writes. The admission matrix
is the pure function
[`admitDynamicWrite`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicWriteAdmission.kt).

The compiler no longer withholds send, share, and merge operations. It tags
them: external effects (R6), administration (R9), credentials (R10), and
sharing without a declared permission field (R8) are moved out of the schema's
generic action list into `NativeAppSchema.dedicatedActions`, and no generic
form is compiled for them. Credential-creating operations are still dropped
with the `ignored-sensitive-credential-write` warning.

At the transport boundary
([`DynamicWriteExecution.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicWriteExecution.kt))
every dynamic write is admitted before anything is sent. This phase enforces
the refusals no surface may override: administration and credentials, a send
without a durable attempt record or a declared idempotency key, a send whose
required body fields are not all supplied, writes without a declared
operation, and a quarantined route. A share is sent only when the contract
declares a permission field and a fresh, bounded read of the target record
returns that field as the JSON boolean `true`; `false` refuses it, and a
missing, non-boolean, or unreadable value, or a read that does not identify
exactly the target, refuses it as unverified. The other safeguards in the
matrix (full echo for every replacement, a verified collection read for every
create, permanent labeling for every delete) are applied by the surfaces that
opt in through the request's read-back and are recorded, not yet enforced,
for the remaining surfaces listed below. One write per target record may be
in flight at a time.

The first bounded rule slice covers feed item state changes. The registered
`ItemStatePairRule` requires both declared inverse, body-free commands under
one item route (read/unread, seen/unseen, star/unstar, and the reviewed spelling
pairs in the rule). A separate declared-boolean rule preserves single-boolean
item updates. Both require E2/E3 operation evidence, a current authoritative
load, unique typed identity, navigation-bound parents, and a declared
verification read. Mark-all and feed-wide commands do not qualify. No app ID
or version selects this behavior; exact versions partition safety evidence.
The boolean update accepts only its state field and proven required identity
inputs; optional identity-like inputs and read-only properties do not qualify.
Generic record and mailbox planners omit commands and flat boolean updates
requiring these plans until those surfaces carry rule authority and recovery.
Nested mailbox flag updates are outside this rule and retain their existing
limitations below.

[`NativePatternRulePlan.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativePatternRulePlan.kt)
contains typed target, rule, identity, generation, baseline and expected-state
evidence, with separate write and read bindings. The executor rebuilds the
plan from its descriptor and current load. Observed identities receive only
per-action proof; their records do not become globally write-safe. A declared
item read must confirm the identity before sending. Sibling commands share a
canonical target gate, and unresolved operations block the canonical route
across rule and app versions. Routes with item-state candidates also share a
process admission gate with ordinary sibling writes, from the initial safety
read through completion. Different targets on such a route cannot race its
pending reservation; unrelated routes retain per-target concurrency.

The feed applies item state optimistically. Network read-back must identify
exactly one item and return the exact JSON boolean requested. A successful
response with unchanged or differently typed state quarantines the rule;
it cannot count as an adjusted success. Failure rolls back only the current
item mutation, and newer authoritative loads fence stale completions. A verified
completion still refreshes the surface after pagination changes the generation;
an unknown completion still checks durable recovery without rolling back newer
local state.
Other R1 surfaces retain the limitations listed below.

OCS failures reported inside an HTTP `2xx` response are classified by status
code ([`DynamicOcsFailure.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicOcsFailure.kt)).
Client errors (`400` to `499`, the OCS v1 app failure codes `101` to `199`,
`997` unauthorised, and `998` not found) are rejections. A server error
(`996`, `500` and above), the unknown error `999`, any other code, or
metadata that contradicts itself is an unknown result, so the exactly-once
send keeps its attempt record and never resends without confirmation.

Dynamic API requests other than `GET`, including multipart uploads and
exactly-once sends, use an HTTP client with connection-failure retries
disabled on desktop and Android
([`JvmDynamicApiClients.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmDynamicApiClients.kt)),
and a per-call network attempt guard. The guard also blocks automatic HTTP
follow-ups such as `503` with `Retry-After: 0` before a second request reaches
the server, including body-free writes. A blocked repeat surfaces as an
unknown result; only a new explicit call can make another attempt.

[`NativeVerifiedWrite.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeVerifiedWrite.kt)
is the one verified write path: an optional pre-read compare, one send, and a
read-back through the exact same-route read or the route-family read, with
outcomes Confirmed, Adjusted, NotApplied, and Unknown. A definite rejection is
not read back. A successful response whose read-back shows the previous value,
or an untouched field that drifted, contradicts the postcondition and
quarantines the route. Server bookkeeping fields (modification time, ETag,
revision) are not drift. Deck's board writes call the same verified write
function, but they run through the reviewed Deck adapter's own transport
rather than the descriptor executor, so the admission check, the in-flight
gate, quarantine, and write diagnostics described here do not apply to them.

Quarantine and probation state is stored per account identity digest, app ID,
exact app version, route template, rule ID, and rule version
([`DynamicWriteQuarantine.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicWriteQuarantine.kt),
file-backed on desktop and Android by
[`FileDynamicWriteQuarantineStore.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/FileDynamicWriteQuarantineStore.kt),
written to a temporary file and moved into place). Resolved state can be
cleared by the person or an app-version change, except a stopped unknown
operation stays quarantined across versions until explicit Resume. Pending operations cannot:
Resume changes, ordinary confirmation, version observation and capacity
eviction preserve them. Unreadable safety files fail closed, including when
the person selects Resume changes. Account retirement removes safety and
encrypted recovery through generation fencing. Store instances sharing a
storage directory reload under one synchronized owner before each transition.

Before any rule write, complete typed recovery evidence is saved using the
platform's encrypted `TextEditorDraftStore`, then an opaque random operation
token is atomically reserved in the safety store. Safety JSON and diagnostics
contain no bound IDs, state values, content or payload fingerprints. A failed
reservation sends nothing. Cancellation, unreadable read-back, restart, and
missing, corrupt or incompatible encrypted evidence retain the pending token.
Exact confirmation atomically resolves only its matching token before deleting
the encrypted evidence. Definite first-attempt rejection clears only that token
and leaves probation. Pending and quarantined entries are protected from
capacity eviction; exhausted protected capacity refuses new operations.
Cancelled preparation removes evidence that has no durable reservation. Before
new writes and status checks, bounded account-local maintenance removes
recognized orphan evidence while preserving every pending token across apps and
versions. Save/reserve and maintenance share a lock. Unreadable drafts and drafts
whose stored identity does not match the recovery token are never swept.
Transient deletion failures are retried by the next maintenance pass, so resolved
rule evidence does not permanently consume the shared text/mail draft capacity.
An offline status read leaves verification pending; it does not claim that the
encrypted recovery information is corrupt.

**Check earlier changes** in the feed performs network reads only, including
after restart. An unresolved operation can be repeated only by an explicit
**Repeat same change**, after status reconciliation and current authority
checks, with the same logical token, target, action, bindings and expected
state. An inverse command or another target is not the same operation. A
rejected repeat after an earlier unknown result still needs read-back. An
authoritative unchanged baseline after that rejection resolves the pending
intent, without claiming that the first attempt was never delivered.
There is no automatic replay. A changed app or rule contract cannot reinterpret
old encrypted evidence; the operation stays pending for compatible recovery or
an explicit terminal decision.

For a deleted target, changed contract, unreadable evidence or another operation
that can no longer be verified, **Stop checking** in Contract info asks for a
separate confirmation. It clears only the chosen token, deletes its encrypted
evidence and records `write-recovery-stopped`, without any transport request.
Its result remains unknown and may still take effect. The canonical route stays
quarantined, including across versions, until the person reviews the affected
item and separately confirms **Resume changes**. A stale or foreign token cannot
end another operation. Evidence is deleted before resolving the token, so a
failed deletion keeps the operation pending for another explicit Stop checking,
even when damaged evidence cannot be recognized by ordinary cleanup.

An app ID, exact version or route template outside the safe key alphabet or
length bound is keyed by its SHA-256 digest. Contract-declared editor writes
keep `contract-declared` version 1. The contract information dialog shows
paused, probationary and pending-verification routes. **Resume changes** asks
for confirmation and clears only resolved safety records.

After drift, the person may send the offered baseline restore: a full echo of
every value from before the drifted write. It is the only write a quarantined
route accepts, only as the exact request the executor offered for that account,
app version, and route, and only once a transport attempt starts. A failed
pre-read leaves the restore action available for another explicit attempt.
It still runs the pre-read compare against the drifted state the person saw,
and its read-back checks every baseline field, including the complete cell map
for grid rows. An ignored or partial restore is a failure, not an adjusted
success. A restore is not offered when an original field was absent or a
custom-shaped field has no baseline verifier. Offers live only in process
memory, with at most 32 retained across accounts; closing the editor, eviction,
or restarting the app can lose the offer. Quarantine persists independently,
and the route stays paused until the person resumes changes.

Edited fields are tracked separately from request-context inputs. A field
absent from the original read is distinct from an explicit JSON null and is
still verified after a partial update.

Diagnostics record the evidence tier, risk class,
rule ID, and rule version of each write without payload values, and use the
codes `write-quarantined`, `write-postcondition-mismatch`,
`identity-binding-refused`, `write-rule-probation`, and `write-rule-confirmed`.
Rule execution records the registered rule identity and lifecycle codes without payload values.

The rule registry and observed identity binding are used by the feed surface
in this source slice. Synthetic executor, persistence and session fixtures
cover two app identities and unknown versions. This is not live News/server
qualification or a published artifact. Deck membership, member-list encoding,
Mail outbox, trash semantics and Phase 4 remain later scopes.

Writes that pass admission and the verified write path: grid row updates
(full echo or cell-checked PATCH) and child record updates (full echo or
read-back PATCH). A replacement whose body is not JSON cannot carry stored
values back exactly, so such a record stays read-only. Writes that pass
admission but are not yet read back: other R1 actions outside the bounded item-state
rules above, generic record, collection, and board actions, mailbox state
changes, notes, questionnaire responses, creates (which keep their durable
pending records), deletes, and the mail composer's send (which uses the
exactly-once path instead and is refused when no durable draft storage can
record its attempt). Deck board writes are read back through the verified
write function but are not admitted by the generic write path.
The item-state candidates explicitly omitted from generic record and mailbox
planners above are unavailable, rather than admitted without verification.

## Reviewed limits

**Server bases and origins**

- The Kotlin importer rejects OpenAPI 2 (Swagger) and ambiguous multiple server
  bases.
- Whole-host server templates are allowed only for trusted package or App
  Store-linked contracts. Concrete foreign origins and fixed-host templates such
  as `https://{tenant}.vendor.test` are never rebased onto the connected server.
  Relative and concrete same-origin servers stay valid.
- Trusted whole-host templates keep the authenticated account authority when the
  port is omitted or templated. A concrete declared port must match the account
  port, and a concrete scheme must always match. The importer returns only the
  validated path base, never a replacement transport origin. Tests cover
  non-default ports, IPv6, explicit mismatches, and untrusted templates.
- Acquisition rejects concrete package-server authorities because it cannot
  prove the connected account owns them.
- Host placeholders are parsed without a brace regular expression. The `ui`
  build checks `commonMain`. `tools/check-kotlin-architecture.sh` checks the
  shared `ui` source sets compiled for Android, the contract module, and every
  `androidApp` source set except JVM unit tests.
- `AppOwnedOpenApiAndroidInstrumentedTest` initializes every top-level
  contract-module class, exercises host rejection, the real catalog path with
  a failing cache, and YAML parsing on the Android runtime. CI does not run
  instrumented tests; run it with the emulator workflow in
  [CONTRIBUTING.md](CONTRIBUTING.md). The JVM unit test
  `ContractModuleInitializationTest` loads every facade and fails when one is
  missing from the instrumented list. JVM loading does not prove ART or ICU
  compatibility.
- `ContractModuleAndroidJsonApiTest` checks the contract module's compiled
  `org.json` references against the Android SDK API. Package response extraction
  uses Android-compatible key iteration; JVM JSON-java tests alone cannot catch
  methods absent from Android's framework implementation.
- Path and operation server overrides must pass the same origin checks and
  resolve to the root server path base. Different override bases are rejected,
  not ignored. An absent override inherits the base; an explicit empty array
  resets it to `/` and is rejected if that differs from the document base. See
  the [OpenAPI server and override rules](https://spec.openapis.org/oas/v3.1.1.html#operation-object).
- Acquisition requires a declared app-owned server base or app-owned operation
  paths. A root spec filename and `/api/...` paths do not prove an app prefix.
  Missing or empty `servers` lists are never rewritten to `/apps/{appId}`,
  because [OpenAPI defaults the server base to `/`](https://spec.openapis.org/oas/v3.1.1.html#openapi-object).

**Parameters and schemas**

- API-version defaults are bound only after inherited parameters, operation
  overrides, and local schema references resolve. A server-derived version must
  satisfy the parameter's declared enum or default. Unknown schema constraints
  prevent server-derived defaults instead of being ignored.
- Object `allOf` flattening accepts only preserved shape keywords and known
  descriptive annotations. Conditional, unknown, and malformed member
  constraints withhold the write action and form. This includes OpenAPI 3.1
  `if`/`then`/`else`, `const`, and dependent schemas; see the
  [OpenAPI schema rules](https://spec.openapis.org/oas/v3.1.1.html#schema-object).
- External schema references in trusted Kotlin imports are sanitized before
  compilation. Untrusted remote references are unsupported.
- OpenAPI security alternatives are flattened into conservative authentication
  requirements. Optional or alternative scheme selection is not modeled.

**Navigation safety**

- Command-like GETs are excluded from root and contextual navigation, including
  linked child tabs and automatic child selection. Verified provenance does not
  make reset, delete, toggle, or similar commands safe navigation reads.
- This covers command segments before trailing identifiers and operation IDs
  with a command verb at any word position. A middle command word is accepted
  only when the terminal word is status, history, preview, export, or download.
  Repeated words do not change which word is terminal, and read suffixes cannot
  override command prefixes or command paths.

**Inference and rendering**

- JSON inference samples at most 64 objects and 128 fields. Empty collections
  stay fieldless until a non-empty response or a schema is available.
- JSON examples cannot establish validation rules, pagination, sync semantics,
  ACL behavior, or write payloads.
- GraphQL introspection, DAV XML schemas, HTML or accessibility inspection, and
  JavaScript traffic instrumentation are not discovery sources.
- Inferred URL fields are display and copy metadata only. External navigation
  needs verified policy or an explicit user handoff.
- `toNativeAppSchema()` adapts the descriptor for the renderer, but the executor
  must keep the original dynamic action, because schema 0.1 cannot carry query,
  authentication, permission, or OCS metadata.
- The common Kotlin compiler provides the same OpenAPI and read-shape paths on
  Android and desktop. When both sources are supplied, it prefers advertised
  OpenAPI and does not merge observed fields into it.

## Reviewed read semantics

The sections below describe narrow, reviewed rules that improve navigation and
presentation for specific shapes. None of them grants write authority. Unless
stated otherwise, the evidence is deterministic tests; device and release
evidence is recorded in [COMPATIBILITY.md](COMPATIBILITY.md).

### Message thread read navigation

[Mail 5.12.2 MessagesController](https://github.com/nextcloud/mail/blob/v5.12.2/lib/Controller/MessagesController.php)
provides the reviewed `getThread` and `getBody` identity behavior.

A thread collection can return message envelopes while its generated resource is
named after the thread endpoint. `DynamicMessageReadSelection` recognizes the
reviewed nested message and thread route only when that GET and its sibling
body GET both have verified provenance and stay inside the endpoint policy. The
exact route resource wins over singular or plural aliases.

- An explicit positive `databaseId`, a matching parsed record identity, and a
  compatible mailbox context bind the selected message.
- Protocol Message-ID, IMAP UID, mailbox ID, and the previously selected message
  cannot substitute for this identity.
- The rebound selection authorizes reads only. Missing evidence keeps ordinary
  navigation.

Tests cover identity separation, stale parent parameters, missing provenance,
unsafe routes, and unavailable body actions.

### Media container entry points

Album and playlist selections open a single existing track or song collection
when a verified read action declares a parent filter or nested parent parameter
and the navigation plan binds it to the selected container. The rule uses
resource meaning and verified parameter bindings, not app IDs or invented
routes. Ambiguous collections, stale parent bindings, and unverified actions
stay explicit choices. `DynamicMediaNavigationTest` covers filtered and nested
reads, identity separation, and refusal cases.

### Reviewed playlist expansion

[Music 3.2.1 PlaylistApiController](https://github.com/nc-music/music/blob/v3.2.1/lib/Controller/PlaylistApiController.php)
declares the `fulltree` read option and the ordered complete track response.

- `MusicPlaylistRead.kt` requests this option only for that reviewed version and
  route, with signed package provenance and a declared scalar union that accepts
  booleans. Explicit caller choices stay intact.
- `NativePlaylistTracks` uses the existing media collection UI for complete,
  bounded track objects. It keeps playlist order and repeated tracks, and keeps
  observed identities read-only.
- URI-only references, missing playback evidence, ambiguous indices, and
  truncated responses are not expanded.

### Budget dashboard source selection

`withNativeBudgetDashboard` in `NativeBudgetSemantics.kt` selects an existing
verified accounts-collection GET that needs no path or query context.

- An account detail route cannot supply the dashboard's root data, even when its
  generated view appears first.
- Dashboard account rows use the verified parameter-free accounts list,
  including an OCS primary action whose older non-OCS route is fallback-only.
- Optional cash-flow totals stay unavailable when the contract has no
  compatible report summary read. Recent transactions are not extrapolated into
  totals, and per-account balances are never summed and labeled net worth.
- Optional summary failures stay visible as partial results.

Tests cover the Budget 2.54.0 descriptor-to-schema shape, detail-first
ordering, and unavailable required context.

### Table row and view containers

The composite table mapper tells a row collection from a related view container
by its declared child collection read. A container that also has a cell-map
field does not make the leaf rows ambiguous; unrelated candidates stay
ambiguous. `DynamicTableDetailPipelineTest` covers descriptor mapping, sparse
row parsing, table-scoped column loading, and named-cell projection together.

Table row details use the projected columns to choose a display-only title,
which never replaces the authoritative row ID.

- The primary cell appears once with its column label, and the remaining typed
  cells stay together.
- Inventory cards summarize declared row and column counts and put secondary
  metadata behind Details.
- Collection summaries say how many records are loaded rather than presenting a
  partial page as a server total.
- Compact row cards show up to three labeled user columns and the number of
  additional fields. Envelope ownership and timestamps do not displace them.
  Original records still supply selection and edit identity.

### Recipe collection pagination

The recipe surface keeps the collection's paging state and load-next-page
callback. Filtering searches loaded recipes and does not claim server-wide
search. An empty filtered page keeps pagination and retry reachable, so a
matching recipe on a later page can still appear. Loading stays
repository-owned. `RecipeCollectionPaginationSceneTest` covers dispatch,
loading, retry, and later pages.

### Mail reading hierarchy

Every app whose contract has mailbox semantics, including Nextcloud Mail,
opens in the same mailbox workspace; no app identifier or version selects it.
Mail reading sections put the verified message body and thread reads first.
DKIM, raw source, smart-reply, and itinerary probe routes stay in the contract
but are left out of the ordinary section chooser. This issues no optional reads
and invents no diagnostics destination. Attachment cards show bounded metadata
only; opening an attachment needs a separate validated message and attachment
binding plus native download integration.

### Mailbox composing

[`NativeMailSendPlanning.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeMailSendPlanning.kt)
offers Compose, Reply, Reply all, and Forward only when the contract describes
exactly one send operation that the composer can fill without inventing a
value.

- The descriptor compiler retains a declared send as an external effect (risk
  class R6) and keeps it out of every generic form and action list
  (`NativeAppSchema.dedicatedActions`). The composer is the dedicated surface
  that may offer it, because it sends through the exactly-once path below. The
  planner accepts a POST, or an idempotent PUT with an exact recovery read,
  with high or verified confidence, a JSON body, and the word `send` in its ID,
  label, operation ID, or route. Draft, test, preview, invitation, and share
  routes are excluded. A synchronous send with no recovery read, such as the
  OCS `message/send` operation in Mail's signed package, is therefore offered
  as a composer; this has deterministic test coverage only and has not been
  validated against a live Mail server (Last reviewed: 2026-11-02).
- Every required body, path, and query input maps to a mail role:
  recipients, Cc, Bcc, subject, body, an HTML flag (sent as false), the
  sending account's identity or address, or a threading header (In-Reply-To
  or References). Recipients
  use the declared shape: an array of objects with an address field and an
  optional label field, an array of strings, or a single address string,
  which accepts one recipient.
- The sending account comes from a loaded record of a resource named as an
  account, with an action-safe identity; its address comes from the record's
  email field. Contacts, suggestions, and other records with addresses are
  never senders. Without such a record the composer is not offered.
- Each identity input takes the value of the one role its name states. Account
  inputs (`accountId`, `account`) take the sending account's identity. A
  sender-identity input (`identityId`, `aliasId`) would need a record from
  another resource: when required, the composer is not offered; when optional,
  it is left out so the server uses its default.
- A reply uses Reply-To when present and leaves the sender's own addresses out
  of Reply all. A declared In-Reply-To field takes the original Message-ID, and
  a declared References field takes the original's References chain followed
  by that Message-ID (at most 20 identifiers, keeping the thread root). Only
  identifiers of the `<...@...>` form are used.
- A send is not idempotent, so it runs through
  [`NativeExactlyOnceSend`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeExactlyOnceSend.kt):
  an attempt record (attempt ID, time, a content-free target summary such as
  "2 recipients", and a SHA-256 fingerprint of the exact request values) is
  stored with the draft before the request can leave the device, and the
  request is sent once and never retried automatically. When the operation
  declares an `Idempotency-Key` request header, a key is generated once per
  attempt and reused only on a confirmed resend of the same payload; an
  undeclared key is never sent. A rejected send keeps the draft for editing. An
  unknown outcome, a transport failure, or cancellation after the request may
  have left keeps the draft and shows the recorded attempt as evidence. The composer then runs the operation's declared
  recovery read with the same bindings: a status that names a sent state, or
  a record holding exactly the attempted body and subject, closes the draft as
  sent; a queued state or a failed read keeps sending again unavailable and
  offers Check status; any other answer, or an operation without a recovery
  read, allows another attempt after an explicit confirmation.
- Drafts and running sends belong to a per-account owner outside every screen,
  so leaving the composer or recreating the activity does not cancel a send.
  The draft is saved shortly after each edit to the encrypted, account-scoped
  text draft storage, and an attempt is recorded before its request starts, so
  after process death the draft returns with its unconfirmed outcome. If the
  attempt cannot be recorded, nothing is sent.

`NativeMailSemanticPatternTest`, `NativeMailComposerStateTest`, and
`NativeMailComposeTest` cover the same contracts under several app IDs and
versions, a synchronous send reserved for the exactly-once composer, a missing send, an unknown required
input, a missing account,
a contract that only mentions mail in a field, sender identity roles,
contacts that are not senders, threading and References chains, every send
outcome and status read, and draft save, restore, and removal after a
confirmed send. `NativeMailComposerRegistryTest` covers the encrypted text
draft binding and account retirement.

### Feed readers

[`DynamicFeedSemantics.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicFeedSemantics.kt)
marks an article collection as a `feedReader` view, and
[`NativeFeedReader.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeFeedReader.kt)
renders it, whatever the app identifier or version.

- An article collection has a title, a link, content, and a publication
  marker or feed reference. When a sparse contract declares no response
  fields, an `items`, `articles`, or `entries` collection beside a feed or
  subscription collection qualifies, and the records are checked again when
  they arrive. A titled link list without content or publication markers is
  not a feed.
- The reader opens on articles, ahead of the subscription list. Parameter-free
  verified feed and folder reads load beside it through the bounded relation
  cache. Articles are grouped by feed only through their feed reference and a
  loaded feed record; this is display only.
- Unread, Starred, and feed filters work on loaded pages and say so in a
  caption above the list. Reaching the end of a filtered list loads further
  pages automatically until three pages in a row add nothing visible; Load
  more stays available. Article links other than HTTP(S) are dropped, and
  bodies use the mail HTML sanitizer. An integer publication time is shown
  only when its field's format declares epoch seconds; other integers are not
  guessed.
- [`NativeFeedItemActions.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeFeedItemActions.kt)
  offers read, unread, star, and unstar for verified writes on an article
  with a contract-declared identity: a body-free route ending in the state
  word, or a write whose only non-identity body field is one boolean state
  field. The route must take the article's identity as a required path
  parameter, named for the article resource or placed after the article
  collection; a route without one (`POST /items/read`) or bound to a feed
  marks many articles and is never used. Changes apply optimistically and roll
  back for that article alone; an unknown result also reads the articles
  again. A pending or unconfirmed change survives loading another page. Opening
  an unread article marks it read when that per-article change is available.

`NativeFeedSemanticPatternTest` covers the same contract under two app IDs and
versions, the entry point, observed identities, missing state routes, bulk and
feed-wide routes, declared epoch dates, bookmark lists, and unsafe links.
`NativeFeedReaderSessionTest` covers local state across page appends and
refreshes, automatic loading under a filter, and the filter caption.

### Pantry timestamps and cards

For Pantry 0.34.0 only, the presentation adapter marks integer `createdAt` and
`updatedAt` fields on houses, lists, and items as epoch seconds, and only with
verified contract evidence. The upstream HouseService and ChecklistService
assign PHP `time()` to these fields; see the
[Pantry source repository](https://github.com/chenasraf/nextcloud-pantry).
Rendering shows an explicit UTC date and time while keeping the integer field
kind, original values, and action bindings. Other versions and shapes are
unchanged.

For the same version, household and list cards put names and descriptions
first. Item rows keep task completion and recovery ownership while showing
quantities and descriptions. Reminder, retention, permission, and other
secondary fields stay reachable through details and forms.

### Activity and Search links

A server-provided search or activity URL is considered before broad provider or
parent-folder hints when the shared link policy recognizes an exact native
Files destination or installed app root. A web URL containing a board, note,
event, or other item ID is not evidence for an API action binding. Unsupported
item routes keep the native fallback until a reviewed typed route mapping
exists; no endpoint or parameter is synthesized from the URL. The navigation
rules are in
[ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md#activity-and-search-navigation).

### Compact record summaries

Card subtitles and inline facts show a few values without much context, so
[`NativeSummaryFieldPolicy.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/runtime/NativeSummaryFieldPolicy.kt)
keeps values that cannot explain themselves out of those slots. The excluded
fields stay available in the record's Details section.

- An integer named as a point in time, such as `lastUpdated`, `lockedUntil`, or
  `created_at`, is excluded unless a reviewed adapter declared its epoch unit.
  The unit is never guessed from the value's magnitude.
- An integer status, state, type, mode, or access-level code is excluded unless
  the contract declares labels for its values. A bare integer is never a
  subtitle.
- Response bookkeeping such as integrity hashes, partial-object flags, lock
  holders, trash counters, and negative configuration flags such as
  `deletiondisabled` is excluded.
- A count without a noun, such as `2 items`, and a single-character code are
  not subtitles. Sort-order configuration fields are excluded.
- Count labels drop API prefixes, so `Nb Bills` reads as `Bills`.

`NativeSummaryFieldPolicyTest` covers these rules with Forms- and
Cospend-shaped records.

### Auxiliary protocol destinations

Session, token, connection-test, and keep-alive reads exist for web clients.
[`DynamicAuxiliaryDestinations.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAuxiliaryDestinations.kt)
removes them from root and contextual navigation tabs. It matches whole words in
the resource ID and label, never substrings or the app ID, and never removes the
only remaining destination. `DynamicAuxiliaryDestinationsTest` covers the
matching and the last-destination rule.

### Metadata-only fallback

When discovery finds no verified contract, the workspace shows a notice in
place of the app's content, built from the typed fallback reason above. When
the installed version publishes no interface the client can verify, the notice
names the app, says so, and offers **Check again**. Every other reason keeps its
own explanation and action, so an unreachable source asks to try again and
rejected credentials lead to account settings. The notice does not render the
app identity record as content, and it does not show discovery diagnostics,
which can contain exception text; those stay in support diagnostics.
