# Native redesign visual QA

This review covers the revived redesign stack on current `main`, including the shared adaptive collection foundation, the active-account Home and sidebar, the Apps command center, Settings, Filesync, generic dynamic collections, Mail, Music, and the existing Deck board specialization.

All implementation captures are deterministic, network-inert Compose scenes using synthetic records. The Filesync reference in the comparison is the supplied Sync Map concept. The before images for Home, Filesync, generic collections, and Mail are preserved captures from before the final redesign pass.

## Comparison set

### Home

- [Desktop before and after](home-desktop-comparison.png)
- [Mobile before and after](home-mobile-comparison.png)
- Result: passed. Desktop cards use independent staggered columns, so short widgets no longer reserve empty row height. The deterministic account now contains eight populated widgets, 23 installed workspaces, recent and pinned navigation, active sync state, unread conversations, mail, calendar, storage, and files. Mobile retains a dedicated single-column hierarchy and bottom navigation.

### Apps

- [Activity design language and Apps implementation](apps-activity-language-comparison.png)
- Result: passed. The former launcher grid is now a desktop command center with search, semantic categories, continue-working shortcuts, rich installed-app cards, native/adaptive status, selection, and a persistent workspace inspector. Dark and light captures use the full active-account fixture.

### Settings

- [Sparse page before and native workspace after](settings-before-after-comparison.png)
- [Folder sync concept language and Settings implementation](settings-filesync-language-comparison.png)
- Result: passed. Settings now uses a desktop category navigator, focused detail workspace, persistent account/server summary, and routes to the complete Folder sync workspace. Existing live theme, startup, capability, update, administration, and sign-out controls remain wired.

### Folder sync

- [Desktop concept and implementation](filesync-desktop-comparison.png)
- [Mobile before and after](filesync-mobile-comparison.png)
- Result: passed. Desktop provides filters, search, a dense mapping table, selected-pair inspector tabs, mapping health, and inline conflict resolution. Mobile exposes the same critical status and conflict actions inline without a reorder or detail modal.

### Generic dynamic collections

- [Desktop before and after](dynamic-desktop-comparison.png)
- Result: passed. Collection-shaped apps use a persistent desktop navigator, dense rows, search and create actions sized for the workspace, plus an overview inspector with record and facet counts. Compact collection, context-menu, and mobile data captures are registered separately in the capture catalog.

### Mail

- [Desktop before and after](mail-desktop-comparison.png)
- [Mobile before and after](mail-mobile-comparison.png)
- Result: passed. Desktop uses account, mailbox, message, and reading panes; mobile opens directly into a readable inbox. Loading, empty, and error targets are also registered in dark and light themes.

### Music

- [Expanded and compact targets](music-adaptive-comparison.png)
- Result: passed after correction. Expanded Music uses a library sidebar, album workspace, track list, and persistent player. Compact Music uses a fixed-height native tab strip above the album and track content. The QA pass caught and fixed an unconstrained tab strip that initially consumed the compact content height.

### Deck

- [Expanded and compact targets](deck-adaptive-comparison.png)
- Result: passed. Expanded screens show the full multi-column board. Compact screens preserve the board metaphor as a horizontally navigable workspace rather than scaling the desktop board down.

## Viewports and themes

| Target | Desktop | Compact/mobile | Dark | Light |
| --- | ---: | ---: | ---: | ---: |
| Home | 1440 x 900 | 1080 x 2200 | passed | passed |
| Apps | 1440 x 900 | adaptive production surface | passed | passed |
| Settings | 1440 x 900 | adaptive production surface | passed | passed |
| Folder sync | 1440 x 900 | 1080 x 2200 | passed | passed |
| Generic collections | 1440 x 900 | 1080 x 1800 | passed | passed |
| Mail | 1440 x 900 | 1080 x 1800 | passed | passed |
| Music | 1440 x 900 | 1080 x 1800 | passed | passed |
| Deck | 1440 x 900 | 1080 x 1800 | passed | passed |

## Checks

- hierarchy and information density
- card and pane alignment at fixed target viewports
- no clipped primary controls or overlapping text
- native desktop master-detail and multi-pane use of width
- compact navigation and touch target sizing
- dark and light theme contrast
- loading, empty, error, attention, playback-error, and conflict states where applicable
- no personal account data in committed screenshots
- atomic capture registry ownership and SHA-256 manifest validation
- live desktop distributable process and mapped window

## Evidence

- 108 real Compose scenarios captured without a device
- focused dashboard, Filesync, dynamic cache/rendering, Mail, Music, semantic presentation, and audio tests passed
- desktop distributable built and launched from a clean staged path
- actual mapped Home window visually checked after launch; the dashboard rendered repeated server cursors without the former duplicate-cursor failure

final result: passed
