# Compatibility and product-completeness contract

This document separates a dated implementation snapshot from the target
completeness contract. It is organized by reusable capability instead of
assuming that opening an app proves compatibility.

**Last reviewed: 2026-09-28.** Implementation, server APIs, and app behavior
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

### Android integration audit

**Last reviewed: 2026-09-28.** These local development observations may change;
the release link above remains authoritative for published artifacts. An API 36
emulator was exercised against a disposable Nextcloud 34.0.3 instance. This is
neither release qualification nor a claim of whole-app parity.

- The Apps browser exercised search, category filters, opening an app, and its
  overflow menu at compact width and in landscape at 1.3 font scale.
- Photos showed equal left and right grid spacing in the emulator capture.
  Opening a preview from the rightmost column and switching list/grid layouts
  worked. These checks do not establish every device density or screen size.
- Transfer history filters remained reachable with an empty history; no active
  transfer or background recovery behavior was qualified by that layout check.
- Contacts and Tasks loaded multiple DAV resources. Disposable contact and task
  create/edit/delete journeys completed, including task completion and editor
  rotation. After correcting deletion verification, a fresh contact create/delete
  journey returned to the original list and Home without restarting the app.
- Files exercised draft restoration after process restart, conditional text
  save, concurrent-edit conflict preservation, rename, copy, move, image zoom,
  and cached image access without network connectivity.
  On this Nextcloud 34.0.3 instance, Sabre rejected correctly formed collection
  ETag conditions even when the folder ETag matched. Folder rename, move, copy,
  and delete therefore fail safely when that condition is rejected. The client
  keeps the guard and explains the limitation; it never retries without it.
  File ETag conditions remain supported. This observation is specific to the
  tested server and does not establish behavior for other Nextcloud versions.
- Notes exercised create, body/favorite update, save, reopen, and delete.
  Short-height editing and Markdown preview were retested after layout and
  heading-size corrections, including landscape editing at 1.3 font scale.
- Calendar exercised weekly series creation and expanded occurrences. Later
  occurrences remained read-only. A subsequent single-event create/delete
  journey verified recovery completion and navigation after deletion.
  Schedule/Details navigation and scrolling to Save/Cancel remained reachable
  in landscape at 1.3 font scale. That visual check cancelled an empty draft
  and did not submit another event.
- Deck exercised card creation, a due date, completion, and confirmed deletion.
  Chores 0.2.0 rendered tasks, history, team membership, and invitations. Tables
  rendered its inventory, rows, and named row values after correcting the
  descriptor-to-detail projection. Its contextual row title was also verified
  on the emulator. Row editing remains unqualified.
- Music albums, tracks, and playlists loaded after session-cookie corrections.
  Native playback reached the end of a synthetic track from both Music and
  Files after the shared stream authentication correction. Queue and Stop
  controls were exercised, including Play all and Stop from the revised playlist
  screen. This is not a long-duration playback qualification.
- Memories timeline and album reads worked after configuring the disposable
  server. People remained unavailable because its recognition backend was not
  enabled; the corrected screen explained the required server setup. This does
  not identify a particular missing model or qualify recognition.
- Mail inbox, thread list, and message bodies rendered. After correcting message
  identity selection, opening the original message from a thread showed its body.
  Message/Conversation navigation and the corrected attachment metadata separator
  were verified. Attachment names, media types, and sizes rendered; opening or downloading Mail
  attachments is not qualified by this audit.
  Talk sent a message in a scoped disposable
  room. Calls, external participants, and attachment workflows remain unqualified.
- Cookbook ingredient scaling, Cospend bills/members, Pantry hierarchy, Activity
  filtering, and native PDF/Office-file previews were exercised. Collaborative
  Office editing and financial writes were not qualified. Pantry 0.34.0 displayed
  reviewed epoch timestamps as readable UTC dates on the emulator.
- Budget 2.54.0 opened its dashboard, verified account balances, and recent
  transactions after the OCS accounts-list correction. The final Dev APK
  confirmed the account row and transaction on the emulator. The disposable server returned HTTP 404 for the optional account
  summary endpoint; that partial failure remains visible. The client does not
  infer net worth by summing account balances when the summary is unavailable.

Only disposable records were mutated. Personal accounts, live calls, sync data
loss gates, and every advertised app action were not covered by these checks.

Activity/Search exact Files-link precedence is implemented with deterministic
routing tests. On the isolated Android emulator, selecting a synthetic image
from Search opened its native preview directly. Activity uses the same routing
policy, but its exact-file journey was not separately exercised in this pass.
App-specific item links such as Notes and Deck retain the existing native app
fallback; exact-record support for those links is not established by these tests.

A focused follow-up verified Pantry list descriptions and item quantities, and
Tables inventory counts, expandable metadata, projected row summaries and row
details on the isolated emulator. Fixtures contained five grocery items and five
table rows with three columns. Pantry remained usable in landscape at 1.3 font
scale. After activity recreation, an ID-only parent showed its resource label
instead of a raw ID; its original name returns after the parent is loaded again.
The final build passed 120 selected desktop tests and produced a Dev APK and
desktop distributable. These observations do not qualify new Pantry completion
writes, Tables row editing, or all advertised app actions.

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
