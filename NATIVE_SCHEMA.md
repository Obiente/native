# nati.ve Schema 0.1

The native schema is the trust boundary between discovery and presentation.
Discovery may use deterministic inspection, verified adapters, or local AI, but
the renderer accepts only this typed document. Read this before changing a
schema field, a component family, or how the renderer presents records and
forms.

**Last reviewed: 2026-10-02.** The schema contract may have changed. The
[Kotlin runtime model](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/NativeSchema.kt)
defines the document the app consumes. The [Rust reference model](src/schema.rs)
and compiler cover a smaller contract. They are not the app's runtime compiler
and cannot serialize Kotlin extensions without loss. Check the linked source and
contract tests before changing a wire field.

## Contract ownership

The app compiles discovered contracts with
[`DynamicAppDescriptorCompiler`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/nativeui/model/DynamicAppDescriptorCompiler.kt)
and maps the result to this schema in `DynamicDescriptorMapper.kt`. The Kotlin
model has features the Rust reference model lacks: resource relationships,
record image previews, enum labels, repeatable object inputs, and composite data
grids. A passing reference-schema test therefore says nothing about these
extensions in another consumer.

Rules for changing the contract:

- Keep shared wire fields compatible, and test representative serialized
  documents at the consumer that actually reads them.
- Before claiming two-way compatibility, test extension preservation, unknown
  fields, unsupported versions, and invalid bindings in both directions.
- Never route an extended Kotlin document through the Rust model when that would
  discard fields.
- The two compilers may share fixtures without sharing runtime ownership or
  requiring a native bridge.

## Top-level document

| Field | Purpose |
| --- | --- |
| `schemaVersion` | The contract version (`"0.1"`). |
| `app` | Binds the result to an installed app and its exact version. |
| `confidence` | How completely the app was understood. |
| `resources` | Data entities and their fields. |
| `actions` | Bind an intent to a real server operation. |
| `views` | Select reusable native components for resources and actions. |
| `relationships` | Bind parent and child resources. Kotlin runtime only. |
| `warnings` | Explain missing or ambiguous semantics. |

## Confidence

| Level | Meaning |
| --- | --- |
| `verified` | A signed or reviewed adapter confirmed the semantics. |
| `high` | Typed API metadata provides strong evidence. |
| `medium` | Deterministic inference selected a likely interpretation. |
| `low` | The evidence is insufficient for normal interaction. |

AI inference can never raise confidence to `verified` by itself.

## Actions and safety

Every action carries an immutable HTTP method, path, and operation identifier
taken from verified contract evidence (see
[DYNAMIC_APP_DESCRIPTOR.md](DYNAMIC_APP_DESCRIPTOR.md#discovery-inputs)). The
inference engine cannot invent these values.

| Risk | Default behavior |
| --- | --- |
| `readOnly` | May run during discovery or normal browsing. |
| `mutating` | Requires confirmation while inferred. |
| `destructive` | Always requires confirmation and explicit visual treatment. |

An adapter may improve labels, component choice, and field semantics. It may
not add an operation that was absent from the discovery snapshot.

## Component families

The `NativeComponent` grammar covers dashboards, file browsers, collection
lists, media grids, details, forms, timelines, calendars, boards, mailboxes,
contact lists, task lists, data tables, media libraries, recipe lists, document
editors, conversation lists, and chat threads.

A new component should represent an interaction pattern shared by more than one
app. App-specific behavior belongs in a verified adapter, not in the generic
component library.

## Adaptive collection presentation

**Tables and records**

- Compact record summaries and desktop columns come from one query. Both search
  the same projected field values and keep collection paging.
- Filters and sorting apply to loaded records only. They never invent a server
  search operation.
- Records appear first. Inferred charts live in the separate Insights view
  rather than above a table.
- Compact records show a bounded set of typed quantities, amounts, status, and
  dates. Desktop tables hide technical identity and ordering columns by
  default, but those values still drive action binding.
- Permission summaries use explicit known boolean fields and never infer a role
  or grant.

**Boards and Mail**

- Boards share lane navigation and action state, with widths adapted to the
  window.
- Compact Mail exposes the same loaded accounts and mailboxes as the desktop
  rail. Message actions stay limited to verified available contracts.

**Editing**

- Editing an existing record uses the current workspace and the shared record
  form. Creation and command forms may still use dialogs.
- Presentation never changes field validation, target bindings, confirmation
  requirements, or mutation recovery.
- The inline editor registers an account-scoped navigation guard for the
  shell, section changes, Back, and incoming links. Leaving a dirty draft needs
  confirmation. An in-flight save or an unresolved result blocks navigation
  until the result has been checked.

## Shared choice controls

Dynamic enum fields use the shared native choice field. It keeps exact wire
values, required and error labels, and icon or color previews. Compact Chores
navigation and exclusive Budget category filters use the shared segmented
control. These components own presentation and input only; navigation guards,
bindings, mutation recovery, and permission checks stay with their callers. See
[shared native choice controls](docs/shared-ui-controls.md) for reuse rules.

## Bounded form restoration

Saved form state has fixed budgets so restoration can never truncate or submit
a value silently.

- Repeatable-object drafts share a 16 Ki-character saved-state budget across
  all fields, including JSON escaping and identifiers. Workspace and record
  forms reject an edit that exceeds it, show a size error, and keep the last
  accepted draft.
- An existing value larger than the budget is not editable until the user
  explicitly resets the structured fields, where that action is offered.
- No oversized value is silently truncated or submitted as an empty
  replacement.
- The native task editor enforces a 32 Ki-character draft budget, including
  the selected calendar and the edit-start ETag, before accepting input. Larger
  existing tasks stay unchanged and cannot be edited in that dialog.

These guards limit saved UI state. They do not provide durable storage for
large drafts.

## Version invalidation

The contract requires a stored compiled contract to be revalidated before write
actions become available whenever any of these change:

- server URL or Nextcloud version;
- app ID or app version;
- OpenAPI or capability fingerprint;
- adapter ID or adapter version, when present.

What the source does today
([`DynamicDiscoveryPersistence.kt`](ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/DynamicDiscoveryPersistence.kt)):

- Only verified, current descriptors are persisted, per account and app ID.
  The server origin is replaced with a placeholder before storage.
- A restored descriptor is always read-only (`LastKnownReadOnly`) until the live
  server and installed app version have been checked again. When the installed
  app version is known and differs from the cached contract's version, the
  cached contract is not shown.

The persisted entry is not yet keyed by OpenAPI or capability fingerprints.
Until it is, the read-only restore and the live installed-version check are what
keep stale writes disabled.
