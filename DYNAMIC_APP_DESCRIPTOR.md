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
the narrow write shapes it can retain.

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
The fallback card derives its title, explanation, and action from that reason:

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
and the shared JVM boundary carries that reason to the fallback card. Catalog
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
  the app's `id`, because no single one can be trusted (identical copies are
  read once);
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
catalog replaces it.

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
memory, so after a restart the cache is served until a refresh records it again. The ordering is kept in memory for each acquirer and never waits for
another acquisition, so a cancelled or failed acquisition cannot hold back
later ones.

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
   HTTP 429, or a successful catalog or contract response that is not readable
   JSON or declares no OpenAPI version (`UnreadableResponse`). An OCS API
   Viewer entry for the app whose spec list is missing, not an array, or has a
   malformed identifier with no usable one left is also `UnreadableResponse`;
   usable identifiers beside malformed ones are still read. When the catalog
   does not list the app, an element that is not an object or has no string
   `id` may have been the app, as in App Store catalogs, so it is also
   `UnreadableResponse`; such elements beside the app's entry are ignored.
4. `UnsupportedContract`: a JSON contract that declares a version other than
   OpenAPI 3, such as Swagger 2, or an OpenAPI 3 contract that cannot be
   compiled.

A 404, another definitive status, a non-JSON response at a static contract
location, an empty OCS API Viewer catalog or one whose entries all name other
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
| Catalog and package requests | No response; non-success status; redirect away from HTTPS | Network; source unavailable; verification failed |
| Catalog and package bodies | Declared or streamed size over the limit | Source unusable |
| Catalog document | Truncated, empty, not JSON, not an array, or only elements without a readable app identity | Unreadable catalog, never cached |
| Catalog app element | Not an object, or an object with a missing or wrong-typed `id`, while the app is not found | Malformed source, not no matching release |
| Catalog app records | More than one differing record with the app's `id` | Malformed source; identical copies are read once |
| Catalog entry | Missing value, a required value of the wrong JSON type, or a download that is not an HTTP URL | Malformed source when it would be selected |
| Candidates exhausted | A catalog was unreadable, or an exact, compatible, or unidentified entry was dropped, and no inspected candidate has a contract | Unreadable catalog or malformed source, not no usable contract |
| Verified contract publication | A newer generation published a contract or ended without one definitively, or an invalidation happened | Not published; the newest accepted result is reported |
| Catalog cache write | Local storage failure | Ignored |
| Explicit retry inside the refresh window without a cached copy | None | No request for that catalog; candidates from the other catalog are still inspected, and recently checked only if none provides a contract |
| Package verification | The package's signature, certificate, chain, revocation, identity, archive structure, or app metadata rejected | Verification failed |
| Package verification | Bundled root certificate or revocation list missing, unreadable, corrupt, expired, of an unsupported format, or inconsistent | Client fault (runtime incompatible); retrying cannot repair it |
| Package archive limits | Entry, expanded, path, or metadata-count limit exceeded | Source unusable |
| Package OpenAPI JSON or YAML | Unreadable or not OpenAPI 3 | Candidate skipped |
| Linked metadata, tree, spec, and schema requests | 404; size over the limit; other status; no response | Skipped; skipped; source unavailable; network |
| Linked archive request | HTTP 403 or 429 | Source temporarily unavailable |
| Linked archive content | Corrupt, unsafe path, identity mismatch, malformed or misencoded `info.xml`, size limit | No candidate |
| Linked JSON or YAML documents | Unreadable | Skipped |
| Acquired contract | Unreadable JSON or a descriptor that cannot be compiled | Unsupported contract; that cached contract is discarded |
| Verified contract cache discard | The cached file cannot be deleted | Marked invalid, or invalidated for the process; never served again |
| Contract module class loading | `LinkageError` | Client fault |
| Same-origin probes | See the precedence list above | Typed probe result |
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

Mail reading sections put the verified message body and thread reads first.
DKIM, raw source, smart-reply, and itinerary probe routes stay in the contract
but are left out of the ordinary section chooser. This issues no optional reads
and invents no diagnostics destination. Attachment cards show bounded metadata
only; opening an attachment needs a separate validated message and attachment
binding plus native download integration.

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
