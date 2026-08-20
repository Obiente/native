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

## Version invalidation

A stored compiled schema must be keyed against all of the following:

- Server URL and Nextcloud version.
- App ID and app version.
- OpenAPI and capability fingerprints.
- Adapter ID and adapter version, when present.

Any change causes revalidation before write actions are available.
