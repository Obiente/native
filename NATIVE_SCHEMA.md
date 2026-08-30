# Nextcloud Native Schema 0.1

The schema is the trust boundary between discovery and presentation. Discovery
may use deterministic inspection, verified adapters or local AI, but a renderer
only accepts this typed document.

**Last reviewed: 2026-08-20.** The schema contract may have changed. The
[canonical Rust model](src/schema.rs) is the source of truth for its current
fields, validation, and serialized form.

## Top-level document

- `schemaVersion` identifies the contract version.
- `app` binds the result to an installed app and exact version.
- `confidence` communicates how completely the app was understood.
- `resources` describe data entities and fields.
- `actions` bind intent to real server operations.
- `views` select reusable native components for resources and actions.
- `warnings` explain missing or ambiguous semantics.

## Confidence

| Level | Meaning |
| --- | --- |
| `verified` | A signed or reviewed adapter confirmed the semantics |
| `high` | Typed API metadata provides strong evidence |
| `medium` | Deterministic inference selected a likely interpretation |
| `low` | The runtime has insufficient evidence for normal interaction |

AI inference cannot raise confidence to `verified` by itself.

## Actions and safety

Every action contains an immutable HTTP method, path and operation identifier
discovered from the connected server. The inference engine cannot invent these
values.

| Risk | Default behaviour |
| --- | --- |
| `readOnly` | May execute during discovery or normal browsing |
| `mutating` | Requires confirmation while inferred |
| `destructive` | Always requires confirmation and explicit UI treatment |

An adapter may improve labels, component selection and field semantics. It may
not introduce an operation that was absent from the discovery snapshot.

## Component families

The initial grammar includes dashboards, files, collections, media grids,
details, forms, timelines, calendars, boards, mailboxes, contacts, tasks, data
tables, media libraries, recipes, documents, conversations and chat threads.

New components must represent interaction patterns shared by more than one app
whenever possible. App-specific behaviour belongs in a verified adapter, not in
the generic component library.

## Adaptive collection presentation

Table browsing uses one query for compact record summaries and desktop columns.
Both layouts search the same projected field values and retain collection paging.
Filters and sorting apply to loaded records; these controls do not invent a server
search operation. Records appear first. Inferred charts belong to the separate
Insights view rather than being repeated above a table.

Compact records keep a bounded set of typed quantities, amounts, status and dates.
Desktop tables omit technical identity and ordering columns from the default
presentation without removing those values from action binding. Permission
summaries use explicit known boolean fields and never infer a role or grant.
Boards share lane navigation and action state, with widths adapted to the window.
Compact Mail exposes the same loaded account and mailbox destinations as its
desktop rail; message actions remain limited to verified available contracts.

Editing an existing record uses the current workspace and the shared record
form. Creation and command forms may still use dialogs. Presentation does not
change field validation, exact target bindings, confirmation requirements or
mutation recovery. The inline editor registers an account-scoped navigation
guard for the shell, section changes, Back and incoming links. Leaving a dirty
draft requires confirmation; an in-flight save or unresolved result blocks
navigation until its result has been checked.

## Bounded form restoration

Dynamic enum fields use the shared native choice field, preserving exact wire
values, required/error labels and icon/color previews. Compact Chores navigation
and exclusive Budget category filters use the shared segmented control. These
components own presentation and input behavior only; existing navigation guards,
bindings, mutation recovery and permission checks remain with their callers.
See [shared native choice controls](docs/shared-ui-controls.md) for reuse rules.

Repeatable-object drafts share a 16 Ki-character saved-state budget across all
fields, including JSON escaping and identifiers. Both workspace and record
forms reject edits that exceed it, show a size error, and retain the previous
accepted draft. Existing values outside this budget are not editable until the
user explicitly resets the structured fields where that action is offered.
No oversized value is silently truncated or submitted as an empty replacement.

The native task editor enforces its 32 Ki-character draft budget before accepting
input, including the selected calendar and edit-start ETag. Larger existing tasks
remain unchanged and cannot be edited in this dialog. Durable large-draft storage
is not implemented by these saved-state guards.

## Version invalidation

A stored compiled schema must be keyed against all of the following:

- Server URL and Nextcloud version.
- App ID and app version.
- OpenAPI and capability fingerprints.
- Adapter ID and adapter version, when present.

Any change causes revalidation before write actions are available.
