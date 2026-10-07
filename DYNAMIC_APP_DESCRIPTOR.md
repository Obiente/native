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

## Metadata fallback reasons

When no same-origin or App Store source yields a verified contract, discovery
keeps the metadata-only descriptor and records a typed reason in
[`DynamicContractFallback.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicContractFallback.kt).
The fallback card derives its title, explanation, and action from that reason:

- no verified contract exists for the installed version;
- the server or app version could not be confirmed;
- the App Store could not be reached, or did not provide the release;
- the package or its source failed verification;
- the verified contract cannot be shown natively yet;
- the client could not complete the check.

[`ContractAcquisitionFailure.kt`](contractAcquisition/src/main/kotlin/dev/obiente/nextcloudnative/contracts/ContractAcquisitionFailure.kt)
classifies the module's own failures: HTTP status responses, verification and
origin rejections, malformed request values, network failures, and runtime
linkage errors.
[`JvmAppStoreContractAcquisition.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmAppStoreContractAcquisition.kt)
translates them once for both JVM targets. It also catches `LinkageError`. If
acquisition code cannot initialize on a runtime, for example because the
Android ICU engine rejects a regular expression, the result is a client-fault
fallback, not an exception class name. Diagnostics and product copy never
contain exception text, and a fallback reason is never persisted.

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
  build checks `commonMain`, and `tools/check-kotlin-architecture.sh` checks
  every source set that runs on Android. An Android instrumented test
  initializes every top-level contract-module class and exercises host
  rejection on the Android runtime.
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
