# Compatibility and product-completeness contract

This document records what has been observed to work, against which server and
app versions, and what "complete" means for each app. It is organized by
capability, because opening an app does not prove it is compatible. Read it
before claiming support for an app or platform, and update it when you gather
new device or server evidence.

**Last reviewed: 2026-10-02.** Implementation, server APIs, and app behavior
may have changed. The [GitHub Releases page](https://github.com/obiente/native/releases)
is the source of truth for published compatibility limitations. Planned work
and acceptance gates live in [ROADMAP.md](ROADMAP.md); platform status lives in
[PLATFORMS.md](PLATFORMS.md#platform-status).

## Kinds of evidence

These kinds of evidence are not interchangeable. Always say which one supports
a claim.

| Evidence | What it shows | What it does not show |
| --- | --- | --- |
| Present in source | The code path exists. | That it works on a device or server. |
| Deterministic tests | Behavior against fixtures and fakes. | Compatibility with a real server or device. |
| Opt-in live audit | Behavior against the exact server and app versions tested. | Any other version, or general compatibility. |
| Device observation | Behavior on the named device or emulator. | Other devices, densities, or release artifacts. |
| Release qualification | A published artifact passed its release gates. | Workflows outside those gates. |

Standard CI does not provision a real Nextcloud server or run connected Android
instrumentation. Live-server audits are opt-in and read-only by default: they
do not send Talk messages, save files or notes, delete content, manage apps, or
perform administrator actions.

## Native experiences in source

This table lists native paths present in source at the review date and the
audit evidence below, if any. It is not a promise of compatibility with every
server or app version.

| Experience | Native path in source |
| --- | --- |
| Files | WebDAV folders, list and grid layouts, previews, file details, bounded downloads, ETag-protected UTF-8 editing, copy, move, rename, and sharing |
| Folder sync and virtual files | Early selected-folder sync on Android, Linux, and Windows; Android `DocumentsProvider`; Linux FUSE virtual filesystem; Windows Cloud Files. Conflict recovery and background durability are still in development. |
| Photos and Memories | Media search, thumbnail grid, RAW and server previews, recognized People covers, per-person galleries, full-screen navigation, and zoom |
| Talk | Room list, history loading, typed file previews, call and system events, and shared objects. Sending is an explicit user action. Calls are not implemented. |
| Activity | Read-only OCS activity timeline with refresh and filters |
| Notes | Metadata-only list, Markdown editor and preview, formatting controls, category and favorite state, explicit save, and ETag conflicts |
| Dashboard and User Status | Widget and item feeds, native app routing, a short-lived account-private cache, and capability-gated status editing |
| Contacts, Calendar, and Tasks | CardDAV and CalDAV collections with native lists, editors, recurrence, and ETag-guarded writes |
| Deck | Native boards, lanes, and cards with drafts, attachments, and mutation recovery |
| Office | Native document browser and preview; Direct Editing handoff as described in [ADR 0001](docs/architecture-decisions/0001-android-office-web-integration.md) |
| Unified Search | Provider-driven search with native routing for exact Files results |
| Other installed apps | Discovered at runtime and rendered through the [dynamic descriptor](DYNAMIC_APP_DESCRIPTOR.md), with reviewed presentation for apps such as Mail, Music, Budget, Tables, Pantry, Cookbook, Cospend, and Chores. Writes require verified provenance. |

## Android integration audit

**Last observed: 2026-09-28.** These local development observations may have
changed; the release link above stays authoritative for published artifacts.
An API 36 emulator was exercised against a disposable Nextcloud 34.0.3
instance. This is not release qualification and not a claim of whole-app
parity. Only disposable records were mutated. Personal accounts, live calls,
sync data-loss gates, and every advertised app action were not covered.

**Navigation and layout**

- The Apps browser: search, category filters, opening an app, and its overflow
  menu, at compact width and in landscape at 1.3 font scale.
- Photos showed equal left and right grid spacing. Opening a preview from the
  rightmost column and switching list and grid layouts worked. This does not
  cover every density or screen size.
- Transfer history filters stayed reachable with an empty history. No active
  transfer or background recovery was qualified by that check.

**Files**

- Draft restoration after process restart, conditional text save,
  concurrent-edit conflict preservation, rename, copy, move, image zoom, and
  cached image access without network.
- On this server, Sabre rejected correctly formed collection ETag conditions
  even when the folder ETag matched. Folder rename, move, copy, and delete
  therefore fail safely. The client keeps the guard, explains the limitation,
  and never retries without it. File ETag conditions remain supported. This is
  specific to the tested server.

**Groupware**

- Contacts and Tasks loaded multiple DAV resources. Disposable contact and task
  create, edit, and delete journeys completed, including task completion and
  editor rotation. After correcting deletion verification, a fresh contact
  create and delete returned to the list and Home without restarting the app.
- Calendar: weekly series creation and expanded occurrences; later occurrences
  stayed read-only. A single-event create and delete verified recovery
  completion and navigation. Schedule, Details, and Save/Cancel stayed reachable
  in landscape at 1.3 font scale; that check cancelled an empty draft.
- Notes: create, body and favorite update, save, reopen, and delete.
  Short-height editing and Markdown preview were retested after layout fixes,
  including landscape at 1.3 font scale.
- Deck: card creation, a due date, completion, and confirmed deletion.

**Contract-derived apps**

- Chores 0.2.0 rendered tasks, history, team membership, and invitations.
- Tables rendered its inventory, rows, named row values, and contextual row
  titles after the descriptor-to-detail fix. Row editing is not qualified.
- Cookbook ingredient scaling, Cospend bills and members, Pantry hierarchy, and
  Activity filtering were exercised. Financial writes were not qualified.
- Pantry 0.34.0 displayed reviewed epoch timestamps as readable UTC dates.
- Budget 2.54.0 opened its dashboard and showed account balances and recent
  transactions after the OCS accounts-list fix, confirmed on the final Dev APK.
  The server returned HTTP 404 for the optional account summary; that partial
  failure stays visible, and net worth is not inferred by summing balances.

**Media**

- Music albums, tracks, and playlists loaded after the session-cookie fixes.
  Native playback reached the end of a synthetic track from Music and from
  Files. Queue, Play all, and Stop were exercised. This is not long-duration
  playback qualification.
- Memories timeline and album reads worked. People stayed unavailable because
  the recognition backend was not enabled, and the screen explained the
  required server setup. This does not qualify recognition.

**Communication and documents**

- Mail inbox, thread list, and message bodies rendered. After the message
  identity fix, opening the original message from a thread showed its body.
  Attachment names, types, and sizes rendered; opening or downloading Mail
  attachments is not qualified.
- Talk sent a message in a scoped disposable room. Calls, external
  participants, and attachment workflows are not qualified.
- Native PDF and Office-file previews were exercised. Collaborative Office
  editing was not qualified.

**Activity and Search links**

Exact Files-link routing has deterministic tests. Selecting a synthetic image
from Search opened its native preview directly. Activity uses the same policy,
but its exact-file journey was not exercised separately. App-specific item
links such as Notes and Deck keep the native app fallback.

**Follow-up pass**

A focused follow-up verified Pantry list descriptions and item quantities, and
Tables inventory counts, expandable metadata, projected row summaries, and row
details. Fixtures held five grocery items and five table rows with three
columns. Pantry stayed usable in landscape at 1.3 font scale. After activity
recreation, an ID-only parent showed its resource label instead of a raw ID.
The build passed 120 selected desktop tests and produced a Dev APK and desktop
distributable. This does not qualify Pantry completion writes, Tables row
editing, or all advertised app actions.

## Whole-app parity contract

Compatibility is not complete when an app merely opens. For every installed
app, the native experience must expose every feature that the exact installed
version advertises through a verified contract. The server contract stays
authoritative; this table defines the workspace each feature belongs in.

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

Each feature owns its state and actions on every platform. Layout adapts
without forking the workflow:

- Compact widths use one pane, touch-sized actions, system back, sheets, and
  progressive detail.
- Medium widths use a navigation rail or list-detail layout when both regions
  stay usable.
- Expanded desktop widths use persistent navigation, multi-pane context, dense
  grids, keyboard shortcuts, pointer selection, and inspectors.
- Every feature covers loading, cached refresh, empty, offline,
  permission-denied, unsupported, stale-contract, and partial-failure states.
- Every reachable action has a meaningful accessibility name, logical focus
  order, scalable text, non-color status cues, keyboard access on desktop, and
  touch access on mobile.
- A feature without verified mutation provenance stays visibly read-only.
  Behavior such as Talk calls and Office collaborative editing, which reusable
  schema semantics cannot represent safely, needs an app-specific adapter.
