# Adapter architecture

This document defines the durable boundaries for integrating independently
versioned Nextcloud server apps without embedding their web interfaces. It is
for contributors changing transport, discovery, repositories, caching, sync,
or native feature adapters.

**Last reviewed: 2026-08-20.** Architecture rules may have changed. The
[default-branch document](https://github.com/Obiente/nc-native/blob/main/ADAPTER_ARCHITECTURE.md)
is the source of truth for the maintained contract.

Current product status belongs in [COMPATIBILITY.md](COMPATIBILITY.md). Planned
delivery belongs in [ROADMAP.md](ROADMAP.md). This document contains rules that
must remain true as those documents change.

## Core invariant

The native UI consumes stable, typed product models. It does not parse protocol
payloads, build endpoint URLs, infer permissions from navigation, or execute an
operation that lacks verified provenance.

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
Compose screen to parse new JSON. A platform credential-store change should
not alter repository policy. A layout change should not alter mutation safety.

## Ownership boundaries

### Compose UI

- Renders immutable state and sends explicit user intents.
- Owns presentation state, focus, accessibility semantics, and adaptive layout.
- Does not own network requests, protocol parsing, persistence, retry loops, or
  conflict policy.
- Reuses semantic components for shared interaction patterns. App-specific UI
  is justified only by behavior that cannot be represented safely by a shared
  component.
- Keeps composables small enough to review. Extract state holders, pure models,
  and reusable surfaces before adding another independent responsibility to a
  large screen.

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
- Remain stateless apart from immutable capability and version configuration.
- Validate required capabilities, versions, endpoint paths, permissions, and
  response shapes before enabling an action.
- Preserve unknown response fields only through an explicit typed extension
  value. Do not use `Any` as a compatibility strategy.
- Fall back to a supported generic adapter when an optimized app-specific path
  is unavailable. Never fall back to a hidden web view.

### Transport

- Owns authentication, product identification, TLS, redirect policy, bounded
  bodies, case-insensitive response headers, and same-origin enforcement.
- Accepts typed request data from adapters. The UI never constructs requests.
- Supports standard HTTP and required WebDAV methods without placing DAV
  parsing in platform launchers.
- Rejects DTDs and external entities in XML.
- Allows an external origin only through a feature designed as an explicit
  browser or application handoff.

### Persistence and sync

- Scope every record, cache key, queued operation, and diagnostic identifier to
  an opaque local account ID.
- Keep credential material out of metadata databases and diagnostics.
- Publish files atomically after complete writes.
- Preserve originals unless the user explicitly chooses replacement.
- Bound automatic caches; keep offline files and unresolved conflict copies
  durable until their documented lifecycle permits removal.
- Queue only operations with defined idempotency and conflict behavior.

### Platform services

Platform source sets own operating-system behavior: credentials, lifecycle,
background scheduling, filesystem providers, notifications, media sessions,
external handoff, packaging, and accessibility integration. Protocol policy
and parsing belong in shared code even when execution uses a platform client.

`NextcloudPlatformServices` is an integration boundary, not a place to collect
every product feature. When Android and desktop implementations repeat protocol
logic, extract a shared adapter or repository. Keep separate implementations
when lifecycle or operating-system semantics are genuinely different.

## Capability and discovery rules

Capabilities and versioned API descriptions are authoritative. Navigation
entries and successful guesses are not proof that an operation is safe.

- Preserve a typed capability snapshot per account with its fetch time and
  server/app versions.
- Revalidate cached descriptors when the server version, app version,
  capability fingerprint, OpenAPI fingerprint, or adapter version changes.
- Treat response-shape inference as read-only evidence.
- Require advertised OpenAPI or a reviewed adapter for writes.
- Keep dynamic endpoints relative and inside approved same-origin prefixes.
- Omit behavior whose provenance or permission model is ambiguous.

See [DYNAMIC_APP_DESCRIPTOR.md](DYNAMIC_APP_DESCRIPTOR.md) and
[NATIVE_SCHEMA.md](NATIVE_SCHEMA.md) for the serialized trust boundaries.

## Mutation policy

Every operation declares its risk before it reaches the UI:

| Level | Meaning | Required behavior |
| --- | --- | --- |
| Read | No intended remote mutation | May run for loading or explicit refresh. |
| Reversible | Small, visible, reversible write | Direct user intent and rollback on failure. |
| Guarded | Content change with concurrency risk | Permission check, revision guard, conflict UI. |
| Destructive | Delete, overwrite, or hard-to-reverse change | Target-specific confirmation and no blind background retry. |
| Privileged handoff | Server administration or primary-password confirmation | Explain the effect and open authenticated server administration. |

Runtime evidence may raise the risk level, such as when a move would overwrite
an existing target. It must never lower the declared level silently.

Stored Login Flow app passwords must not be treated as primary passwords for
strict administrator confirmation. The client must not collect or retain a
primary account password to bypass that boundary.

## Error and cancellation rules

Errors must retain enough structured context to support recovery and safe
diagnostics without exposing private data.

- Map transport, authentication, permission, validation, conflict, capacity,
  cancellation, and unexpected failures into distinct typed outcomes.
- Never convert coroutine cancellation into an ordinary failed request. Rethrow
  cancellation before broad exception handling.
- Do not use `getOrNull()` or an empty catch when the caller must distinguish
  unavailable data from a failed operation.
- Add operation and stage identifiers at subsystem boundaries. Do not include
  server URLs, paths, filenames, payloads, credentials, or response bodies.
- Preserve the original cause internally while presenting an actionable,
  non-technical message to the user.
- A retry must be bounded and safe for the operation. Ambiguous delivery of a
  mutation requires reconciliation before another submission.

## Offline and conflict rules

Repositories use stale-while-revalidate behavior:

1. Emit usable cached data immediately.
2. Mark it as refreshing when remote work starts.
3. Commit a successful response transactionally.
4. Keep cached data and expose a non-blocking error when refresh fails.
5. Show a blocking error only when no usable state exists.

Writes require an explicit conflict contract. ETag-protected text or note saves
may be queued when their base revision and payload are durable. Deletes,
administrator actions, Talk messages, and hard-to-reverse recognition changes
must not be queued by default.

Notes mutation validators preserve valid quoted opaque ETags verbatim, including
backslashes and HTTP `obs-text` bytes. They do not apply quoted-string unescaping.
Bare Notes API validators are quoted once; malformed and oversized validators
are rejected before a request. The character grammar follows
[HTTP entity-tags](https://httpwg.org/specs/rfc9110.html#field.etag).

## Test contract

Each boundary has a corresponding test responsibility:

- Protocol fixtures cover parsing, omitted and unknown fields, version gates,
  same-origin checks, size limits, and hostile XML.
- Repository tests cover cache refresh, pagination, transactional merge,
  cancellation, retry limits, conflicts, and process restart recovery.
- Compose tests cover semantics, loading, empty, stale, partial failure,
  permission denial, confirmation, adaptive layout, and keyboard/touch access.
- Platform tests cover credential stores, filesystem paths and providers,
  background scheduling, external handoff, packaging, and lifecycle recovery.
- Live-server audits use synthetic disposable accounts, record exact tested
  versions, and remain separate from deterministic unit and integration tests.

A bug fix adds the smallest regression test at the layer where the invariant
failed. Tests should assert public behavior, not copied implementation details.

## Review checklist

Before merging an adapter or repository change, confirm:

- The responsibility is in the correct layer.
- Shared behavior is implemented once without hiding platform differences.
- Every write has provenance, permission, conflict, and confirmation policy.
- Cancellation survives all broad exception boundaries.
- Cache and queued-operation state survives interruption safely.
- Diagnostics identify the failed stage without private content.
- Tests cover success, empty, partial, offline, denied, malformed, cancelled,
  conflict, and retry-exhausted paths that apply.

## Groupware compatibility failures

Contacts and Tasks fall back from multiget REPORT to individual object reads
only for HTTP 405 or 501. Other server failures and throttling stop the affected
collection refresh instead of multiplying requests across its objects. A failed
collection remains a visible failure, not a successful empty result.

CardDAV and CalDAV multiget retain healthy records when another requested resource has
an explicit 404 or 410 status, and reports the deletion count as a partial
refresh. Every requested href must still be accounted for exactly once; omitted,
duplicate, foreign, malformed, and other failed responses remain errors. A
property-level 404 is not a resource deletion. These boundaries follow the
[CardDAV multiget response example](https://www.rfc-editor.org/rfc/rfc6352.html#section-8.7.1)
and the [CalDAV multiget contract](https://www.rfc-editor.org/rfc/rfc4791.html#section-7.9),
and the [WebDAV resource and property status distinction](https://www.rfc-editor.org/rfc/rfc4918.html#section-13).

Tasks track completed refreshes by calendar href, not display name or aggregate
warning text. A missing selection is cleared after its own calendar completes,
even if another calendar fails. Failed or budget-truncated calendars do not
prove deletion; selection retains its calendar identity across restoration.

Whole-object task deletion requires one balanced VCALENDAR containing exactly
one top-level VTODO and no sibling data components. Supporting VTIMEZONE and
alarms owned by the task are allowed. VEVENT, VJOURNAL, other tasks, and unknown
siblings withhold deletion, following the [iCalendar component structure](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.6).

Dynamic collection header creates open the renderer's durable create form,
never a standalone generic form. Header and inline actions share the same
complete-baseline and postcondition recovery plan and require a pending mutation
store. Missing recovery evidence withholds both actions. The header control is
scoped to the active account and navigation context and cleared on disposal;
pending writes remain in durable storage, not in that control.

Task requests, durable recovery storage, and recovery reads are serialized.
Refresh and recovery-discard controls remain disabled until the active request
finishes. A queued recovery read reloads the durable record after obtaining the
operation lock, never verifying a captured pre-request state. Cancellation
releases the operation lock without discarding the durable recovery record.

Task parsing withholds blank, control-containing, or over-1,024-character UIDs
from editing. Recurrence identity values must have a valid date or date-time
form, at most 16 characters, before entering task selection or saved state.
Malformed exceptions are withheld rather than treated as masters; their raw
components remain intact when another task is edited. Stable task keys include
the DAV object, a length-delimited UID, and a master/exception discriminator.
Duplicate component identities reject the affected calendar response rather than
selecting one component or supplying duplicate list keys. Edits also require one
unique matching component in the retained source. A failed calendar does not
confirm that its selected task was deleted; other calendars remain available.
Task-description normalization changes line endings only; leading,
trailing, and whitespace-only content survives edits and recovery verification.

Loaded DAV object hrefs are opaque: updates and deletes preserve the discovered
href, including extensionless names, and require the loaded ETag. New objects
retain the generated `.ics` or `.vcf` suffix. The mutation builder still rejects
collection hrefs, unsafe paths, and mismatched content before a request.
Task details keep the full title, description, status, and errors in a bounded
scrolling body, with Edit and Delete outside it. Compact landscape and enlarged
text must not hide these actions or bypass read-only and recurrence guards.

## Primary protocol references

- [iCalendar recurrence identity](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.8.4.4)
- [Nextcloud WebDAV](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/WebDAV/basic.html)
- [Nextcloud OCS API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/OCS/ocs-api-overview.html)
- [Nextcloud Activity API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/activity-api.html)
- [Nextcloud Client Integration API](https://docs.nextcloud.com/server/stable/developer_manual/client_apis/ClientIntegration/index.html)
- [Notes API](https://github.com/nextcloud/notes/blob/main/docs/api/README.md)
