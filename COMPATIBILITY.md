# Compatibility and product-completeness contract

This document separates a dated implementation snapshot from the target
completeness contract. It is organized by reusable capability instead of
assuming that opening an app proves compatibility.

**Last reviewed: 2026-08-20.** Implementation, server APIs, and app behavior
may have changed. The [GitHub Releases page](https://github.com/obiente/native/releases)
is the source of truth for published compatibility limitations.

## Reviewed implementation snapshot

These entries summarize implemented native paths at the review date. They are
not a promise of complete compatibility with every server or app version.

| Experience | Current native support |
| --- | --- |
| Files | Real WebDAV folders, list/grid layouts, previews, file details, bounded downloads and ETag-protected UTF-8 editing |
| Photos and Memories | Real media search, thumbnail grid, RAW/server previews, recognized People covers, per-person galleries, full-screen navigation and pinch/double-tap zoom |
| Talk | Real room list, read-only history loading, typed file previews, call/system events and shared objects; sending is an explicit user action |
| Activity | Real read-only OCS activity timeline with refresh |
| Notes | Fast metadata-only list, Markdown editor/preview, formatting controls, category and favorite state, explicit save confirmation and ETag conflicts |
| Dashboard and User Status | Real widget/item feeds, native app routing, short-lived account-private cache, and confirmed capability-gated status editing |
| Chores and similar task apps | Verified household hierarchy, native task cards, recurrence, assignment, points and completion-history rendering through reusable semantics |
| Other installed apps | Discovered and mapped to a typed native family while their verified adapters are implemented |

Repository live-server audits are opt-in and read-only by default. They do not
send Talk messages, save files or notes, delete content, manage apps, or perform
administrator actions. A passing audit is evidence for the tested server and
app versions, not a general compatibility guarantee.

Standard CI does not provision a real Nextcloud server or run connected Android
instrumentation. Deterministic tests, opt-in live audits, device tests, and
release qualification provide different evidence and must not be presented as
interchangeable.

## Whole-app parity contract

Compatibility is not complete when an app merely opens. For every installed app, the native
experience must expose every feature that the exact installed version advertises through a
verified contract. The server contract remains authoritative; this table defines the semantic
workspace in which those features belong.

| Installed app | Upstream workspace model | Feature groups that must remain reachable |
| --- | --- | --- |
| Activity | Filtered chronological feed | Filters, actors, object previews, timestamps, pagination and safe deep links |
| Budget | Financial dashboard and ledger | Accounts, categories, transactions, totals, periods, charts and contract-backed editing |
| Calendar | Calendar navigator, time grid and agenda | Calendar visibility, month/week/agenda, search, event create/edit, recurrence, attendees, reminders and attachments |
| Chores | Household task workspace | Lists, assignees, recurrence, points, completion and history |
| Contacts | Address books, people list and contact inspector | Address books, groups, search, contact details, photos and contract-backed editing |
| Cookbook | Category navigator, recipe gallery and recipe reader | Categories, search, recipe images, ingredients, instructions, nutrition, yield and timers |
| Cospend | Project navigator, ledger and bill form | Projects, members, bills, balances, settlement, currencies, categories and reimbursement state |
| Deck | Board navigator and Kanban lanes | Boards, stacks, cards, ordering, labels, assignees, due dates, attachments, archive and activity |
| Files | Folder tree and file browser | List/grid, search, preview, upload, sharing, versions, favorites, offline state and sync |
| Mail | Account/folder/message workspace and composer | Accounts, folders, messages, threads, search, compose, drafts, recipients and attachments |
| Memories | Timeline and media collections | Date groups, albums, people, places, maps, favorites, RAW previews and selection actions |
| Music | Library navigator, queue and player | Artists, albums, tracks, playlists, genres, search, queue and playback controls |
| Office | Document browser and collaborative editor | New/open, format-specific editing, save state, locking, collaboration and version-safe handoff |
| Pantry | Contract-derived collection hierarchy | Every verified collection, relationship, detail, form, action and summary exposed by the installed version |
| Photos | Timeline and album gallery | Timeline, albums, favorites, shared media, tags, locations, selection and sharing |
| Search | Provider filters and result groups | All advertised providers, paging, previews and native deep links |
| Tables | Table/context navigator, typed grid and row form | Tables, contexts, templates, views, columns, rows, sorting, filters, inline editing, forms and sharing |
| Talk | Conversation list, chat thread and call workspace | Rooms, messages, replies, reactions, attachments, participants, calls, screen sharing and call controls |
| Tasks | List navigator and task inspector | Lists, smart filters, task editing, completion, priority, recurrence, due dates and subtasks |

The same semantic feature owns its state and actions on every platform. Layout adapts without
forking the workflow:

- Compact widths use one pane, touch-sized actions, system back, sheets and progressive detail.
- Medium widths use a navigation rail or list-detail layout when both regions remain usable.
- Expanded desktop widths use persistent navigation, multi-pane context, dense grids, keyboard
  shortcuts, pointer selection and inspectors.
- Every feature must cover loading, cached refresh, empty, offline, permission-denied,
  unsupported, stale-contract and partial-failure states.
- Every reachable action needs a meaningful accessibility name, logical focus order, scalable
  text, non-color status cues, keyboard access on desktop and touch access on mobile.
- A feature without verified mutation provenance stays visibly read-only. App-specific adapters
  are required for behavior such as Talk calls and Office collaborative editing that cannot be
  represented safely by reusable schema semantics alone.

Implementation order and acceptance gates belong in [ROADMAP.md](ROADMAP.md),
not in this compatibility contract.
