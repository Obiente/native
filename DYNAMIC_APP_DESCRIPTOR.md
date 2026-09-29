# Dynamic App Descriptor 1.0

`DynamicAppDescriptor` is the machine-readable boundary between API discovery,
native presentation and HTTP execution. The application uses the serializable
Kotlin model in
`ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAppDescriptor.kt`.
The Rust reference model is `src/dynamic.rs`. Both serialize camel-case JSON with
`descriptorVersion: "1.0"`.

**Last reviewed: 2026-09-28.** Discovery limits may have changed. The
[Kotlin compiler](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAppDescriptorCompiler.kt)
and runtime validation tests define application behavior. The Rust validator and
fixtures are reference-contract evidence, not proof of runtime parity. See
[schema ownership](NATIVE_SCHEMA.md#contract-ownership) before exchanging models.

## Contract

The top-level document contains:

- exact app identity and an endpoint policy containing the server origin and
  approved API path prefixes;
- capability facts and permission requirements with provenance and confidence;
- typed resources and fields;
- explicit list, detail or grid layouts;
- field links, where inferred URLs default to `allowExternal: false`;
- forms bound to explicit mutating actions;
- actions containing method, path, path/query parameters, body media type and
  schema, authentication requirements and OCS envelope metadata;
- warnings describing intentionally omitted or degraded behavior.

Every descriptor must pass validation after compilation and again after
deserialization. Kotlin consumers use `requireValid()`; Rust consumers use
`DynamicAppDescriptor::validate()`. Validation rejects unsupported versions, duplicate
or dangling references, path-parameter mismatches, absolute/cross-origin paths,
paths outside the approved app prefixes and writes without advertised OpenAPI
or verified-adapter provenance.

## Discovery inputs

The runtime `DynamicAppDescriptorCompiler` and Rust reference
`DynamicDescriptorCompiler` accept normalized facts in `DynamicDiscoveryInput`:

1. An OpenAPI 3.x document that the connected app advertised. The fetching
   layer supplies the document and its advertised same-origin URL. The compiler
   never searches arbitrary URLs.
2. An OpenAPI 3.x document extracted from the exact installed release in the
   official Nextcloud App Store. This source is accepted only after local
   certificate-chain, signed-CRL, app-ID, version and archive-signature
   verification. Its provenance is `verifiedAppPackage`.
3. If that verified package contains no OpenAPI file, an OpenAPI 3.x document
   from the exact GitHub release tag linked by the App Store entry. Repository
   identity and tag come only from the official catalog metadata and release
   URL; the tag's `appinfo/info.xml` must reproduce the selected app ID and
   exact version. This unsigned, lower-trust provenance is
   `appStoreLinkedSourceTag`. Every operation still has to stay inside the
   connected server's approved same-origin app endpoint prefixes.
4. Successful 2xx JSON GET observations from an already approved endpoint.
   Response-shape inference creates read-only fields, a read action and a
   list/detail layout. It never creates a form or write action.

OpenAPI is authoritative for explicit write operations. An unnamed OpenAPI
write is omitted; every declared mutation requires confirmation, and deletes
are marked destructive.

Acquisition can retain verified read routes while checking compatible releases
for a richer contract. If an optional later package returns HTTP 404 or 410,
an already verified route contract remains usable. This does not bypass
certificate, signature, app identity, or version checks, and does not turn
unverified routes into a fallback. Without a verified route contract, the missing
package remains an acquisition failure.

Fixtures under `tests/fixtures/` cover an advertised OCS/OpenAPI app and an
unknown app learned from a successful JSON list response.

## Structured row details and form eligibility

A row detail reuses explicit composite table relationships and verified column
reads. `NativeTableRecordScope` selects a single table or view scope from the
returned parent foreign key instead of rejecting every schema with several
composites. Sparse response schemas may leave that key in bounded display data;
using it to read related columns never promotes it to mutation authority. A column
route's generic `id` binds the parent only through its verified parent link.
`NativeTableRecordDetail` renders declared column labels and complete observed
cells in a local display copy. Conflicting identities, ambiguous scopes, duplicate
columns, and truncated structured values keep the existing fallback.

Descriptor navigation withholds forms whose required body fields lack a native
input representation. For example, a required string-or-object `data` union must
not become a form containing only an optional view selector. Action execution
retains its existing body completeness checks. Deterministic projection and
required-input tests establish these source boundaries; server and device
validation remain separate compatibility evidence.

## Authenticated application reads

[`JvmAuthenticatedAppReadSession.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmAuthenticatedAppReadSession.kt)
prepares an account session before dynamic GET requests under the account's
`/apps/` or `/index.php/apps/` prefix. This is required by applications that
resolve their user during filesystem-app bootstrap:
[Nextcloud 34.0.3 loads filesystem apps before Basic login](https://github.com/nextcloud/server/blob/v34.0.3/lib/base.php),
and [Music 3.2.1 resolves its user in application boot](https://github.com/nc-music/music/blob/v3.2.1/lib/AppInfo/Application.php).

Concurrent reads share one authenticated OCS profile GET per account and
credential generation. Cookies remain in process memory, expire within 15
minutes, and are capped at 16 cookies and 8 KiB per account across at most eight
accounts. Repeated cookie names with the same domain and path use the final
response value, including deletion, so login session rotation cannot resend an
obsolete session ID. The adapter checks the exact origin and account path, rejects
cross-account redirects, and supplies cookies only to application reads.
Mutations and public contract downloads retain their existing authentication.
Credential replacement, account retirement, and unauthorized responses invalidate
the session; stale work cannot restore it. No cookies or profile bodies enter
support diagnostics or persistent storage. Bootstrap failures record only a
stable diagnostic stage/code and an HTTP status when available; cancellation
does not create a failure event.

An unavailable bootstrap endpoint (404, 405, or 501), or a valid profile without
cookies, leaves Basic authentication available with a one-minute bootstrap
cooldown. Authentication, network, malformed-response, and other server failures
remain failures. The adapter does not retry a failed application read. Its
[deterministic tests](ui/src/desktopTest/kotlin/dev/obiente/nextcloudnative/app/JvmAuthenticatedAppReadSessionTest.kt)
cover isolation, retirement, cancellation, bounds, redirects, and unsupported
bootstrap behavior. A successful session bootstrap does not establish that an
application's individual operations are compatible.

## Discovery diagnostics

`DynamicDiscoveryDiagnostics` records bounded acquisition, JSON parsing, and
descriptor compilation stage/outcome tokens in the existing support diagnostics.
It records no contract content, exception text, account identity, or request URL.
Cancellation propagates without a failure event; a recorder failure does not discard
a usable contract. Deterministic tests cover those boundaries. An unverified app
version keeps writes disabled without displaying a permanent refresh spinner.

## Read failures

[`DynamicReadFailure.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicReadFailure.kt)
classifies unsuccessful HTTP reads into authentication, permission, missing
content, throttling, server failure, and rejected request states. Ordinary UI
shows the affected collection and a safe next step. It does not show request
methods, resolved endpoints, arbitrary server messages, or exception class names.
The reviewed mailbox-not-synchronized translation remains available without
revealing a mailbox identifier.

The exception retains typed status and method fields. Existing platform network
diagnostics retain status, method, bounded response size, and a redacted URL;
error presentation does not make another request or duplicate that event.
[`DynamicReadFailureTest`](ui/src/commonTest/kotlin/dev/obiente/nextcloudnative/app/DynamicReadFailureTest.kt)
covers message privacy, status classification, malformed and oversized error
bodies, invalid text, and deeply nested JSON. These deterministic fixtures do
not establish compatibility with a deployed server or device validation.

## Reviewed limits

- The Kotlin importer rejects OpenAPI 2/Swagger and ambiguous multiple server
  bases. It permits whole-host server templates only for trusted package
  or App Store-linked contracts. Concrete foreign origins and fixed-host
  templates such as `https://{tenant}.vendor.test` are never rebased onto the
  connected Nextcloud server. Relative and concrete same-origin servers remain
  valid. Trusted whole-host templates retain the authenticated account authority
  when the port is omitted or templated. A concrete declared port must match the
  account port, and the concrete scheme must always match. The importer returns
  only the validated path base, never a replacement transport origin. Tests cover
  nondefault ports, IPv6, explicit mismatches, and untrusted templates. Acquisition rejects concrete
  package-server authorities because it
  cannot establish their ownership by the connected account.
  Portable host placeholders are parsed without a brace regular expression;
  the contract module's ownership boundary is also exercised on Android to
  verify class initialization and host rejection behavior in the shipped runtime.
- Path and operation server overrides must pass the same origin checks and
  resolve to the root server path base. Different override bases are unsupported
  rather than ignored. An absent override inherits the base; an explicit empty
  array resets it to `/` and is rejected if that differs from the document base.
  See the [OpenAPI server and override rules](https://spec.openapis.org/oas/v3.1.1.html#operation-object).
- API-version defaults are bound only after inherited parameters, operation
  overrides, and local schema references resolve. A server-derived version must
  satisfy the effective parameter's declared enum/default. Unknown schema
  constraints prevent server-derived defaults instead of being silently ignored.
- Acquisition requires a declared app-owned server base or app-owned operation
  paths. A root spec filename and `/api/...` paths do not prove an app prefix.
  Missing or empty `servers` lists are never rewritten to `/apps/{appId}`;
  [OpenAPI defaults the server base to `/`](https://spec.openapis.org/oas/v3.1.1.html#openapi-object).
- Command-like GETs are excluded from root and contextual navigation, including
  linked child tabs and automatic child selection. Verified provenance does not
  make reset, delete, toggle, or similar commands safe navigation reads.
  This includes command segments before trailing identifiers and operation IDs
  with command verbs at any word position. Middle command words are accepted
  only when the actual terminal word is status, history, preview, export, or download.
  Repeated words do not change which word is terminal, and read suffixes cannot
  override command prefixes or command paths.
- Object `allOf` flattening accepts only preserved shape keywords and known
  descriptive annotations. Conditional, unknown, and malformed member constraints
  withhold the write action and form. This includes OpenAPI 3.1 `if`/`then`/`else`,
  `const`, and dependent schemas; see the [OpenAPI schema rules](https://spec.openapis.org/oas/v3.1.1.html#schema-object).
- External schema references in trusted Kotlin imports are sanitized before compilation;
  untrusted remote references remain unsupported.
- OpenAPI security alternatives are currently flattened into conservative
  authentication requirements; optional/alternative scheme selection is not
  modeled yet.
- JSON inference samples at most 64 objects and 128 fields. Empty collections
  remain fieldless until a non-empty successful response or schema is available.
- JSON examples cannot establish validation rules, pagination, sync semantics,
  ACL behavior or write payloads.
- GraphQL introspection, DAV XML schemas, HTML/accessibility inspection and
  JavaScript traffic instrumentation are not discovery sources yet.
- Inferred URL fields are display/copy metadata only. External navigation needs
  verified policy or an explicit user handoff.
- `toNativeAppSchema()` adapts the descriptor for the current renderer, but the
  executor must retain the original dynamic action because schema 0.1 cannot
  carry query, authentication, permission or OCS metadata.
- The common Kotlin compiler provides the same focused OpenAPI and read-shape
  paths for Android/desktop today. When both sources are supplied it currently
  prefers advertised OpenAPI instead of merging observed fields into it.
### Message thread read navigation

[Mail 5.12.2 MessagesController](https://github.com/nextcloud/mail/blob/v5.12.2/lib/Controller/MessagesController.php)
provides the reviewed `getThread` and `getBody` identity behavior.

A thread collection can return message envelopes while its generated resource is
named after the thread endpoint. `DynamicMessageReadSelection` recognizes the
reviewed nested message/thread route shape only when both that GET and its sibling
body GET have verified provenance and remain inside the approved endpoint policy.
The exact route resource takes precedence over singular/plural aliases when both
resources exist in a generated descriptor.
An explicit positive `databaseId`, matching parsed record identity, and compatible
mailbox context bind the selected message. Protocol Message-ID, IMAP UID, mailbox
ID, and the previously selected message cannot substitute for this identity.
The rebound selection authorizes reads only; it does not promote observed fields
to mutation authority. Missing evidence keeps the ordinary navigation behavior.
Deterministic tests cover identity separation, stale parent parameters, missing
provenance, unsafe routes, and unavailable body actions. These tests do not by
themselves establish device or published-release compatibility.

### Media container entry points

Album and playlist selections prefer a single existing track or song collection
when a verified read action declares a parent filter or nested parent parameter
and the navigation plan binds it to the selected container. This semantic rule
uses resource meaning and verified parameter bindings, not app IDs or invented
routes. Ambiguous collections, stale parent bindings, and unverified actions stay
explicit choices. The deterministic `DynamicMediaNavigationTest` covers filtered
and nested reads, identity separation, and refusal cases; device playback has a
separate transport and platform validation boundary.
### Reviewed playlist expansion

[Music 3.2.1 PlaylistApiController](https://github.com/nc-music/music/blob/v3.2.1/lib/Controller/PlaylistApiController.php)
declares the `fulltree` read option and the ordered complete track response.
`MusicPlaylistRead` requests this option only for that reviewed version and route,
with signed package provenance and a declared scalar union accepting boolean
values. Explicit caller choices remain intact. `NativePlaylistTracks` uses the
existing media collection UI for complete bounded track objects, preserves
playlist order and repeated track occurrences, and keeps observed identities
read-only. URI-only references, missing playback evidence, ambiguous indices,
and truncated responses are not expanded. Deterministic read/projection tests
cover these boundaries; emulator and release evidence is recorded separately.

### Budget dashboard source selection

`NativeBudgetSemantics.withNativeBudgetDashboard` selects an existing verified
accounts collection GET that needs no path or query context. An account detail
route cannot provide the dashboard's root data, even when its generated view
appears first. Selection uses the verified action even when inference produces
only an account detail view. Tests cover the Budget 2.54.0 descriptor-to-schema
shape, detail-first ordering, and unavailable required context.
Optional cash-flow totals remain unavailable when the verified contract contains
no compatible report summary read; recent transactions are not extrapolated into
whole-account totals. Dashboard account rows select the verified parameter-free
accounts list action, including an OCS primary action whose older non-OCS route
is fallback-only. Account balances remain per-account values; they are not summed
and labeled net worth when the authoritative summary is unavailable. Optional
summary failures remain visible as partial results.
### Table row and view containers

The composite table mapper distinguishes a row collection from a related view
container through its declared child collection read. A container that also has
a cell-map field does not make the leaf rows ambiguous; unrelated candidates
remain ambiguous. `DynamicTableDetailPipelineTest` covers descriptor mapping,
sparse row parsing, table-scoped column loading, and the renderer's named-cell
projection together. This is read-only presentation and never promotes observed
fields into mutation authority.

### Recipe collection pagination

The recipe surface retains the collection's paging state and load-next-page
callback. Filtering searches loaded recipes; it does not claim server-wide
search. An empty filtered page keeps pagination and retry controls reachable,
so a matching recipe on a later page can still appear. Loading and completion
remain repository-owned. `RecipeCollectionPaginationSceneTest` covers the
renderer dispatch, loading, retry, and later-page behavior.

### Reading hierarchy and reviewed timestamp display

Mail reading sections prioritize the existing verified message body and thread
reads. DKIM, raw source, smart-reply and itinerary probe routes remain in the
contract but are excluded from the ordinary section chooser; this policy does
not issue optional reads or invent a diagnostics destination. Attachment cards
show bounded metadata only. Opening an attachment still requires a separate
validated message/attachment binding and native download integration.

Table row details use the projected column presentation to choose a contextual
title. This title is display-only and never replaces the authoritative row ID.
The primary cell appears once with its column label; remaining typed cells stay
together. Tables inventory cards summarize declared row and column counts and
place secondary metadata behind Details. Collection summaries say how many
records are loaded rather than treating a partial page as a server total.
Compact row cards prioritize projected user columns, showing up to three labeled
values and the number of additional fields. Envelope ownership and timestamps
do not displace those columns. Original records still supply selection and edit
identity; display-only resources never replace mutation authority.

The Pantry 0.34.0 presentation adapter marks integer createdAt/updatedAt fields
on houses, lists and items as epoch seconds only with verified contract evidence.
The installed upstream HouseService and ChecklistService assign PHP time() to
these fields; see the [Pantry source repository](https://github.com/chenasraf/nextcloud-pantry).
Rendering shows an explicit UTC date and time while retaining the integer field
kind, original values and all action bindings. Other versions and unverified
shapes are unchanged. Focused tests cover these presentation boundaries; they do
not claim attachment opening or a released compatibility guarantee.

For the same reviewed Pantry version, household and list cards prioritize names
and descriptions. Item rows retain task completion and recovery ownership while
showing quantities and descriptions. Reminder, retention, permission and other
secondary fields remain accessible through the existing details and forms.
These presentation policies neither replace records nor grant action authority.

### Activity and Search links

A server-provided search or activity URL is considered before broad provider or
parent-folder hints when the shared link policy recognizes an exact native Files
destination or installed app root. A web URL containing a board, note, event, or
other item ID is not itself evidence for an API action binding. Unsupported item
routes keep the existing native fallback until a reviewed typed route mapping is
available; no endpoint or parameter is synthesized from the URL.
