# Compatibility families

This matrix comes from the first real Nextcloud instance selected for testing.
It is intentionally organized by reusable capability instead of app-specific
screens.

## Live implementation status

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

All agent-run live tests are read-only. No Talk message, file save, Notes save,
delete, app-management action or administrator action is issued during automated
testing.

| Installed app | Primary native family | Important secondary capabilities |
| --- | --- | --- |
| Dashboard | Dashboard | Widgets, activity and shortcuts |
| Talk | Conversation list + chat thread | Attachments, realtime events and calls |
| Files | File browser | Preview, upload, sharing, versions and offline state |
| Photos | Media grid | Albums, timeline, selection and sharing |
| Activity | Timeline | Filters, actors and deep links |
| Mail | Mailbox | Threads, composer, attachments and folders |
| Contacts | Contact list | Groups, details, editing and device contacts |
| Calendar | Calendar | Agenda, events, recurrence and attendees |
| Cospend | Collection + form | Projects, members, currency and settlement |
| GitHub | Dashboard + timeline | Repositories, issues, pull requests and links |
| Notes | Document editor | Lists, categories and offline editing |
| Music | Media library | Artists, albums, playlists and playback |
| Deck | Board | Stacks, cards, ordering and assignment |
| Budget | Data table | Categories, totals, charts and editing |
| Tasks | Task list | Lists, recurrence, completion and priorities |
| Tables | Data table | Typed columns, sorting, filtering and forms |
| Cookbook | Recipe list | Ingredients, steps, timers and images |
| Office | Document editor | File locking, collaboration and rich editing |
| Memories | Media grid | Date groups, maps, albums and RAW previews |
| Chores | Task list | Assignment, recurrence and completion |

## First implementation order

1. Collection list, detail and form establish the generic data contract.
2. File browser and media grid cover Files, Photos and Memories.
3. Task list, data table and board cover Tasks, Chores, Tables, Budget and Deck.
4. Conversation list, chat thread and mailbox cover communication data.
5. Calendar, media library, recipe and document editing add specialized
   interaction models.
6. Talk calls and Office collaborative editing require verified adapters rather
   than unconstrained inference.
