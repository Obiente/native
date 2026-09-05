# Platform strategy

This document defines the platform boundary for nati.ve. It separates
portable product behavior from operating-system integration so shared code does
not erase native security, lifecycle, accessibility, or filesystem semantics.

**Last reviewed: 2026-09-01.** Implementation and release availability may
have changed. The [GitHub Releases page](https://github.com/obiente/native/releases)
is the source of truth for published artifacts and their limitations.

The target architecture has three portable layers and thin platform products:

1. The Rust semantic compiler produces the same validated native schema.
2. Shared Kotlin repositories own account state, caching, pagination,
   conflicts, and actions.
3. Shared Compose components render semantic workflows without HTML.
4. Platform launchers own lifecycle, layout adaptation, secure storage,
   background work, files, notifications, media, sharing, and packaging.

## Reviewed implementation snapshot

This table describes the repository state at the review date. It is not a
support guarantee. A packaged artifact is not supported for a workflow until
its platform acceptance criteria pass and its limitations are documented.

| Platform | Runtime | Current state | Platform-specific direction |
| --- | --- | --- | --- |
| Android | Compose Multiplatform | Active launcher and signed alpha APK/AAB | Keystore, WorkManager, DocumentsProvider, permissions, notifications, shares, media sessions, camera backup, and calls |
| Linux | Compose Desktop | Primary interactive desktop target; alpha RPM/DEB | Secret Service, desktop file integration, notifications, media keys, portals, and conventional sync roots |
| Windows | Compose Desktop | Unsigned x86-64 MSI with Credential Manager, attested builds, and Cloud Files integration under prerelease qualification | Explorer validation, free trusted signing when available, notifications, media controls, and updates |
| macOS | Compose Desktop | Early DMG packaging artifact; Keychain storage is source-tested, but authenticated use has not been live-validated or qualified | Keychain, File Provider/Finder integration, notifications, media controls, and updates |
| iOS / iPadOS | Planned Compose target | No supported launcher is shipped | Keychain, File Provider, background transfer, share extension, notifications, media, and CallKit |

Packaging is not feature parity. A platform becomes supported for a workflow
only after its platform-specific acceptance tests pass and the limitation is
recorded in [COMPATIBILITY.md](COMPATIBILITY.md).

Standard CI builds Android packages and runs Android unit tests, but it does not
run connected-device instrumentation. A package or passing unit-test job is not
evidence that device-specific acceptance criteria have passed.

## Shared boundaries

- Shared modules must not import Android, Apple, Windows, macOS, or Linux APIs.
- Platform services implement interfaces owned by shared domain code.
- Protocol parsing, permission rules, sync policy, retries, conflicts, and
  account identity belong in shared code. Existing duplication should move
  toward that boundary rather than becoming a new platform-specific design.
- Secure credentials remain inside the platform credential store.
- Filesystem providers and sync roots expose shared file and transfer semantics
  while retaining each operating system's native provider model.
- Shared Compose components contain behavior and semantics. Platform layouts
  may arrange them differently.

### Shared workspace interaction components

Source review: 2026-08-30. This section describes the working implementation,
not availability in a published package.

`NextcloudCollectionWorkspaceScaffold` uses the same typed destinations and
selection callbacks across layouts. Compact workspaces use short text tabs for
small destination sets. For larger sets, the current workspace title opens the
section chooser without a second header row. Back remains a separate action.
The bottom sheet includes search when there are more than seven sections.
Tablets and desktop retain rails and collapsible sidebars. A section
change does not bypass the host's draft or mutation navigation guards.

The shared app shell uses Home consistently across platforms. On phone and
tablet, the Apps navigation slot identifies the open app; selecting it opens
the app switcher with pinned and recent apps, installed-app search, Folder sync
and a route to the full app catalog. Opening or dismissing the switcher leaves
the workspace mounted. Switching apps still uses the host's guarded callbacks.
Phone-to-tablet layout changes move the same workspace composition so local
draft state is retained within that running session, not recreated by the
navigation breakpoint.

Desktop sidebars collapse on request and below 900dp. Both widths retain pinned,
recent and current apps, with one selected app, labeled controls and full-name
tooltips. Settings and the clickable account entry share the utility footer.
Short windows scroll that footer with the navigation. Collapse state is a
saveable presentation preference, not a server setting. Phone/tablet navigation
and app switching also scroll at short heights and larger text sizes.

`MediaImageCanvas` shares bounded zoom and pan behavior across touch, mouse,
touchpad scroll, keyboard, and visible controls. Pinch or double-tap zooms;
scrolling zooms around the pointer. When the image has keyboard focus, `+` and
`-` zoom, `0` fits the image, `1` selects actual size, and arrow keys pan while
zoomed. At fit size, Left/Right continue to navigate media through the viewer.
Visible Fit and 1:1 controls distinguish fitting the window from one decoded
image pixel per viewport pixel. Percentages describe the displayed decoded
image, not a higher-resolution original that has not been loaded. These controls do not edit
the source image. OS decoding, EXIF handling, and file access remain in their
existing owners.

Desktop Files can hide the library or details pane. Intermediate window widths
show only one secondary pane at a time so the file list remains usable. App
tiles open directly; pinning and compatibility details remain in overflow
menus. Activity separates actionable notices from the remaining event history,
and Settings opens account details on demand rather than reserving a permanent
inspector for them.

Folder sync presents queue state and last scan time separately. A completed
scan is not presented as a successful completed sync. Phone pair rows open
dedicated details; desktop retains a pair table and inspector. Conflict choices
explain their consequences before the existing confirmation and revision checks.
Storage distinguishes integration availability, connection state and retained
edits, while transfer history uses verified receipt time for completed uploads.

Existing Calendar events and editable dynamic records use in-place workspace
editors. They reuse the same fields, validation and mutation paths as their
dialog presentations. Save and Cancel stay with the form; dirty drafts require
an explicit discard decision before navigation, and pending saves block leaving.
Creation, pickers and short confirmations can still use dialogs.

Calendar uses shared view selection, time-first event rows, named calendar
checkboxes and event detail facts. Phone Month shows a compact date grid above
the selected day's events; Week uses a day strip. Desktop has a continuous month
grid with actionable event overflow and scrollable week columns. Shared date
grouping includes multi-day events within a bounded visible window and respects
exclusive all-day ends. The bounded editor prioritizes title and schedule, with
compact recurrence and calendar selectors. Save/Cancel stay outside its scroll
area. Production and captures use the same components. Talk bounds the
composer and desktop message width. A failed Talk send retains the draft;
refreshing only reads the conversation, and an uncertain resend needs explicit
confirmation. Message timestamps show UTC without inferring delivery or read state.

These source behaviors still require native-device interaction validation;
shared rendering and deterministic tests are not a claim of identical input
delivery on every operating system or touchpad.
Office uses native document selection. Android embeds only user-requested
Direct Editing sessions, not the Nextcloud dashboard or app navigation; desktop
opens editing sessions in the system browser. Preview and Edit are separate
actions, with editing gated by advertised MIME support and current permissions.
This does not provide an automatic web fallback for other apps.
See [ADR 0001](docs/architecture-decisions/0001-android-office-web-integration.md)
for the authentication, provider selection, and retry boundaries. Device-level
Office acceptance remains separate from compiling or packaging this integration.

## Mobile product rules

View switchers and form choices use common native components on phone and
desktop. Calendar, compact Chores, Budget categories and dynamic enum fields
share selection, focus and overflow behavior without sharing domain state or
write policy. See [shared native choice controls](docs/shared-ui-controls.md)
for the component contracts and current consumers.

- Correct safe-area insets, system back, touch targets, and permission flows.
- State restoration across rotation, activity recreation, and process death.
- Durable background work that is honest about Android/iOS scheduling limits.
- Progressive layouts for large phones, tablets, foldables, and external
  displays.
- Native share/open-with, notifications, media sessions, filesystem providers,
  camera/media discovery, and calling surfaces.

## Desktop product rules

- Resizable master-detail and multi-pane workspaces where the workflow benefits.
- Keyboard navigation, pointer selection, context menus, drag-and-drop, and
  accessibility focus.
- Dense tables, persistent inspectors, multi-selection, and broad content views
  instead of phone cards stretched across a window.
- Conventional sync roots or native virtual-file providers according to the
  operating system.
- Secret storage, notifications, system media controls, file associations,
  updates, and native packaging.

## Platform delivery rule

Shared code is valuable only when it preserves correct native behavior. Move a
rule into shared code when it is domain policy. Keep an implementation in a
platform source set when it depends on lifecycle, security, scheduling,
filesystem, media, notification, windowing, or accessibility APIs unique to
that operating system.

The dependency order and acceptance gates are defined in
[ROADMAP.md](ROADMAP.md), especially the platform productization milestone.
