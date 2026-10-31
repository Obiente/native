# Compatibility and product-completeness contract

This document records what has been observed to work, against which server and
app versions, and what "complete" means for each app. It is organized by
capability, because opening an app does not prove it is compatible. Read it
before claiming support for an app or platform, and update it when you gather
new device or server evidence.

**Last reviewed: 2026-10-29.** Implementation, server APIs, and app behavior
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
| Files | WebDAV folders, list and grid layouts, previews, file details, bounded downloads, ETag-protected UTF-8 editing, copy, move, rename, and sharing. Deleted files (trash bin DAV, gated by `files.undelete`; permanent delete and empty also honor `files.delete_from_trash`), multi-select with per-item results for favorite, move, copy, and delete, file comments (comments DAV: read, post, edit and delete your own, mark read), and system tag assignment (systemtags-relations DAV) are covered by deterministic tests only; no live-server evidence yet. Uploading from the device's file picker into a Files folder and the Files personal settings (hidden files, sort order) are not implemented. |
| Folder sync and virtual files | Early selected-folder sync on Android, Linux, and Windows; Android `DocumentsProvider`; Linux FUSE virtual filesystem; Windows Cloud Files. Conflict recovery and background durability are still in development. |
| Photos and Memories | Media search, thumbnail grid, RAW and server previews, recognized People covers, per-person galleries, full-screen navigation, and zoom. Album create, add, and remove through Photos DAV; renaming and deleting your own albums and leaving albums shared with you (Photos DAV MOVE and DELETE on the album collection) and setting a favorite from the viewer are covered by deterministic tests only. Album collaborators, public album links, multi-select in the timeline, and Photos settings are not implemented. |
| Talk | Room list beside the conversation on wide windows, history loading, long-polling live updates while visible, typed file previews, call and system events, and shared objects. Replies, reactions, and editing or deleting your own messages are offered only when the server advertises `chat-replies`, `reactions`, `edit-messages`, or `delete-messages` and the conversation permissions allow them; these are covered by deterministic tests, not by a live audit. Sending is an explicit user action. Attachments, new conversations, read markers, and calls are not implemented. |
| Activity | Read-only OCS activity timeline with refresh and filters |
| Notifications | In-app inbox from the Notifications OCS v2 API, gated by the advertised `ocs-endpoints` capability: rich subjects, links through native routing, dismiss one or all with a verifying re-read, and only server-advertised actions. Notification settings open a native screen that describes the Notifications and Activity personal settings read-only: Notifications has only a write route that replaces all three settings, and Activity saves settings only through a web form, so neither can be read or safely changed (checked against both apps' source; Last reviewed: 2026-10-30, may have changed). Deterministic tests only; no live-server evidence yet. No push or system notifications. |
| Notes | Metadata-only list, Markdown editor and preview, formatting controls, category and favorite state, explicit save, and ETag conflicts. A Favorites filter, favorite changes from the note list (read first, then saved with the note's ETag), and the Notes folder and file type settings through the Notes API `/settings` endpoint, gated by the `notes.api_version` capability (1.2 for settings, 1.3 for custom suffixes). Sharing opens the Files share manager only when the Files record with the note's ID is a file at the note's reported `internalPath`; the Notes API documents no file path, so other notes stay unshareable here. Deterministic tests only; no live-server evidence yet (Last reviewed: 2026-10-31, may have changed). |
| Dashboard and User Status | Widget and item feeds, native app routing, a short-lived account-private cache, and capability-gated status editing |
| Personal info | Read-only provisioning profile with per-field visibility, language, locale, groups, and quota. Edits need a server password confirmation that the app-password sign-in cannot provide, so they wait for an explicit authenticated handoff. Devices, sessions, and app passwords have no app-password API and are not shown. Deterministic tests only. |
| Administration | Read-only app catalog, account search and details, groups, and Monitoring app server information. App lifecycle and account or group changes wait for an explicit authenticated handoff for the same reason. Deterministic tests only. |
| Contacts, Calendar, and Tasks | CardDAV and CalDAV collections with native lists, editors, recurrence, and ETag-guarded writes |
| Deck | Native boards, lanes, and cards with drafts, attachments, and mutation recovery. Board sharing with users, groups, and teams (edit, share, and manage permissions, removal with confirmation) through the Deck ACL routes and the core autocomplete search; board archive and unarchive with an archived boards section; list reordering; card archive, an archived cards view with restore and delete; and per-board due date notification and Calendar settings plus the account Calendar default through the Deck config endpoint. Every sharing, settings, and archive write is verified by reading the board or list again. Federated (remote) shares, board cloning, ownership transfer, card dependencies, and the web-only display settings are not offered. Deterministic tests only; no live-server evidence yet (Last reviewed: 2026-10-31, may have changed). |
| News | Reviewed v1-3 adapter: Unread, Starred, and All views, folders and feeds, an article reader that removes images and scripts, and per-article read and star changes. Feed and folder management stays in the web interface. |
| Mail | Reviewed adapter for Mail 5.0.0 through 5.12.x (routes and controllers checked against the v5.0.0, v5.10.9, v5.11.7, and v5.12.6 sources; Last reviewed: 2026-10-31, may have changed): accounts, folder tree, paged and searchable message lists, a reader that removes images and scripts, reply, reply all, forward, and draft editing. The composer has To, Cc, and Bcc suggestions from Mail's address completion, sending identities and aliases, plain or rich text, signatures, quoting, and attachments from the device or from Files. Sending stores the message in Mail's server Outbox: it can be undone for 10 seconds or scheduled, and an unknown result is checked against the Outbox instead of being sent again. Unsent drafts are kept in the device's encrypted draft storage. Message flags, importance, junk, tags, move, archive, and delete; folder create, rename, and delete; signature, signature placement, default editor, and alias settings. Writes are offered only when the installed Mail version is confirmed, which needs an administrator account; otherwise the workspace is read-only. Deterministic tests only; no live-server evidence yet. Downloading or saving received attachments, snooze, filters, out-of-office, S/MIME, PGP, and account setup are not implemented. |
| Office | Native document browser and preview; Direct Editing handoff as described in [ADR 0001](docs/architecture-decisions/0001-android-office-web-integration.md) |
| Unified Search | Provider-driven search with native routing for exact Files results |
| Other installed apps | Discovered at runtime and rendered through the [dynamic descriptor](DYNAMIC_APP_DESCRIPTOR.md). Surfaces follow the shape of the contract and the data, not the app ID or version: column-defined grids (for example Tables), recipe collections (for example Cookbook), expense ledgers (for example Cospend), questionnaires (for example Forms), and others. Writes require the installed app's own published contract. [Contract-driven record writes](DYNAMIC_APP_DESCRIPTOR.md#contract-driven-record-writes) offer grid row create, edit, and delete, child record create, edit, and delete with member and category pickers, and questionnaire responses wherever the contract declares them and its encoding is determinable; these are covered by deterministic tests with synthetic contracts and the Tables 2.4.0 and Cospend OpenAPI excerpts, not by a live audit (Last reviewed: 2026-10-31, may have changed). With those excerpts, Tables row create, edit, and delete are offered; Cospend bill create and edit stay unavailable because its contract sends who a bill was for as one plain string, and bills in a project that states its access only as a numeric level are read-only. Bookmarks has no verified contract and stays unsupported. |

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

## App presentation follow-up

**Last observed: 2026-10-25.** These local development observations may have
changed. A fresh API 36 emulator ran a debug build against the disposable
Nextcloud 34.0.3 instance with the imported read-only test account. Writes were
allowed only for one scoped path, the News article-state API, and touched only
that instance's synthetic and public-feed data. This is not release
qualification, and desktop was covered by deterministic tests only.

**Installed for this pass:** News 29.0.0, Bookmarks 16.2.7, Forms 5.4.1, and
Polls 9.3.0, alongside the representative suite above.

- News opened as a native reader. Unread, Starred, All articles, a folder, and
  three feeds were navigable from the section sheet and, in landscape, the
  rail. Articles showed feed, relative date, and a plain-text excerpt; the
  reader rendered sanitized text without images. With the scoped write, mark
  read, unstar, and star changed the server state, confirmed by a follow-up API
  read. Under the read-only guard the same action was rolled back and
  explained.
- Forms cards showed the form title and submission count instead of raw epoch
  values, an unlabeled state code, and a partial-object flag. Its token read
  and the Polls session read no longer appeared as tabs, nor did Mail's
  account connection test.
- Cospend project cards showed bill counts and totals instead of change
  stamps, access-level codes, and trash counters. The insight label no longer
  repeats "total".
- Bookmarks 16.2.7 has no verified contract. It now explains that it cannot be
  shown natively instead of reporting a load failure and listing its app ID,
  name, and version.
- Mail listed the synthetic INBOX after its local mail server was restarted
  and reseeded.

Not qualified: Bookmarks, Forms, and Polls workflows beyond their list
screens, News feed and folder management, News paging beyond one page on a
device, and desktop News on a real window.

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
| News | Feed navigator, article list and reader | Folders, feeds, unread and starred views, articles, read state, starring and feed management |
| Notifications | Inbox list with a notification detail | Notifications, rich subjects, links, advertised actions, dismiss, dismiss all, notification settings and push delivery |
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
