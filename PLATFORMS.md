# Platform strategy

This document defines which code is shared across platforms and which code
belongs to one operating system. Read it before adding a source file, moving
code between source sets, or implementing a platform service. Shared code must
not erase native security, lifecycle, accessibility, or filesystem behavior.

**Last reviewed: 2026-10-02.** The implementation and release availability may
have changed since then. The [GitHub Releases page](https://github.com/obiente/native/releases)
is the source of truth for published artifacts and their limitations. Planned
work and acceptance gates live in [ROADMAP.md](ROADMAP.md).

## Layers

The app is built from four layers. Only the last one is platform-specific.

1. **Semantic compiler.** The Kotlin compiler turns discovered app contracts
   into the schema the app renders. The Rust crate keeps a smaller reference
   model; see [schema ownership](NATIVE_SCHEMA.md#contract-ownership).
2. **Shared repositories.** Kotlin repositories own account state, caching,
   pagination, conflicts, and actions.
3. **Shared Compose UI.** Compose components render semantic workflows without
   HTML.
4. **Platform launchers and services.** Each platform owns lifecycle, layout
   adaptation, secure storage, background work, files, notifications, media,
   sharing, and packaging.

## Modules and source sets

This section describes where code lives today. The Gradle build has three
modules (`settings.gradle.kts`); the Rust crate is built separately.

| Location | What it owns |
| --- | --- |
| `ui/src/commonMain` | Platform-neutral models, policies, repositories, the dynamic descriptor compiler (`nativeui/model`), the native renderer (`nativeui/runtime`), and Compose screens. The `NextcloudPlatformServices` interface is declared here. |
| `ui/src/jvmMain` | Code that is identical on Android and desktop: OkHttp request helpers, the authenticated app-read session, detached downloads, resumable uploads, text-draft encryption, and support diagnostics. |
| `ui/src/androidMain` | Android `actual` implementations used by the shared UI, such as audio and video playback services, image decoding, back handling, the embedded Office editor, and server certificate trust. |
| `ui/src/desktopMain` | The complete desktop platform: `DesktopNextcloudServices`, secret stores, the desktop sync engine and its SQLite stores, Linux FUSE virtual files, Windows Cloud Files, tray integration, and packaging resources. |
| `androidApp/src/main` | The Android application: `MainActivity`, `AndroidNextcloudServices`, Keystore-backed credentials, WorkManager workers, `NextcloudDocumentsProvider`, folder sync, media backup, and share-target activities. |
| `contractAcquisition` | A plain Kotlin/JVM module that verifies signed App Store packages and extracts app contracts. Both `androidApp` and the desktop target depend on it; `commonMain` does not. |
| `src/` (Rust crate) | The reference schema and descriptor compiler, plus the Windows Explorer shell registrar binary that the desktop Windows package bundles. The Rust compiler is not linked into the app. |

`jvmMain` is not a separate Kotlin Multiplatform source set. The `ui` build adds
`src/jvmMain/kotlin` as an extra source directory to both `androidMain` and
`desktopMain`, so each target compiles its own copy of that code. Files named
`*.jvm.kt` there provide one `actual` implementation for both targets. Because
the code is compiled for Android too, it must work on the Android runtime as
well as on desktop JVMs.

Android platform services live in the `androidApp` module, not in
`ui/src/androidMain`. Desktop platform services live in `ui/src/desktopMain`.
That asymmetry is why some Android and desktop implementations look alike
without being in a shared source set.

### Shared code ownership status

This subsection records which Android and desktop implementations are shared
and which remain duplicated, with their migration status. Keep entries scoped
to code that exists in source and link each one to its tracking issue.

AGENTS.md forbids byte-identical Android and desktop source files. Code that
behaves identically on both JVM targets belongs in `ui/src/jvmMain`. Code that
differs because of lifecycle, storage, or operating-system APIs stays in the
platform module, with the difference documented and tested.

**Last reviewed: 2026-10-02.** This is a source inventory, not a plan. The code
may have changed since; the default branch is the source of truth. Similarity
figures came from comparing function bodies after removing the `Android` and
`Desktop` name prefixes.

**Already shared.** All Compose UI, the sync domain model (planning,
conflicts, checkpoints), groupware and media DAV models, virtual-file eviction
policy, and the `NextcloudPlatformServices` interface live in `commonMain`.
Both service containers implement that interface. `jvmMain` shares login and
authenticated request policy, application read sessions, detached downloads,
the resumable chunk-upload state machine, staging-space reservations, support
diagnostics and intake, text-draft encryption, and WebDAV href decoding.

**Still duplicated.** The gap is below the shared interface: each platform has
its own HTTP request code, protocol adapters, XML parsers, and sync executor.

| Priority | Area | Android | Desktop | Shared owner to aim for |
| --- | --- | --- | --- | --- |
| High | Protocol adapters and HTTP request code. About 57 functions are at least 90% similar; desktop has notes and template functions Android lacks | `AndroidNextcloudServices.kt` | `DesktopNextcloudServices.kt` | One `jvmMain` HTTP gateway plus typed per-domain adapters in `commonMain` |
| High | WebDAV and XML parsing, written four ways with different hardening | `SafeXmlParser.kt`, `NextcloudDocumentWebDav.kt` | `DocumentBuilderFactory` setups in `DesktopNextcloudServices.kt` and `DesktopFileVersionDav.kt`, `DesktopFileSyncDavParser.kt` | One hardened parser and typed multistatus parsers in `jvmMain`, with Android runtime tests |
| High | Same-origin redirect rule, written three times | `AndroidNextcloudRedirect.kt` | `DesktopNextcloudServices.kt` | `NextcloudAuthenticatedRequestPolicy` in `jvmMain` |
| High | Folder sync execution and owned-upload reconciliation. Behavior differs: when a folder replaces a remote file, desktop keeps a backup and Android does not; desktop records the uploaded ETag and Android rescans | `AndroidFileSyncEngine.kt`, `AndroidFileSyncRemoteTree.kt`, `NextcloudChunkUploadWebDav.kt` | `DesktopFileSyncEngine.kt`, `DesktopFileSyncRemoteTree.kt`, `DesktopFileSyncChunkUploadRemote.kt` | Step by step: a `jvmMain` owned-stage reconciler, then one WebDAV file client, then a `commonMain` orchestrator. Each step needs restart, conflict, cancellation, and ambiguous-result tests |
| Medium | Sync persistence. Desktop uses SQLite; Android stores encoded blobs | `AndroidFileSyncStore.kt`, `AndroidFileSyncUploadCleanupStore.kt` | `DesktopFileSyncStore.kt` and related stores | A `commonMain` SQLite store with a tested one-time Android migration |
| Medium | Dynamic discovery cache. Most of the logic is identical | `AndroidDynamicDiscoveryCache.kt` | `DesktopDynamicDiscoveryCache.kt` | One `jvmMain` cache |
| Medium | Deck card draft codec, capacity, and migration policy | `AndroidDeckCardDraftStore.kt` | `DesktopDeckCardDraftStore.kt` | `commonMain` policy behind a storage port |
| Medium | File read cache. Android skips leased entries during eviction; desktop eviction has no lease check | `AndroidFileReadCache.kt`, `AndroidVirtualFileCache.kt` | `DesktopFileReadCache.kt` | A `jvmMain` blob store |
| Medium | Account removal stages and journals | Five `AndroidAccountRemoval*` files | `DesktopAccountRemoval.kt` | A `commonMain` removal state machine |
| Medium | Pure policy kept in platform code | `AndroidFileSyncContentEvidence.kt` | `DesktopFileSyncRetryPolicy.kt`, `DesktopFileSyncBatchResult.kt`, `DesktopFileSyncContentSlices.kt` | `commonMain` |
| Low | Small identical helpers: streaming multipart body, document-editing capability parsing, bounded reads, `sha256Hex` copies, update-channel preferences | Several files | Several files | `jvmMain` |

**Intentionally platform-specific.** Credential storage (Keystore versus
Secret Service, Credential Manager, and Keychain), local file access (SAF and
MediaStore versus `java.nio`), virtual files (DocumentsProvider versus FUSE and
Windows Cloud Files), background scheduling (WorkManager versus the desktop
tray and runtime conditions), and external file launch. Only the logic around
them, such as range hashing and handoff validation, should be shared.

**Test gap.** `jvmMain` code is tested only by `desktopTest`; Android unit tests
do not run it. Several suites exist once per platform and should merge when the
code they test is shared: dynamic discovery cache, Deck draft store, account
operation guard, file read cache, and file sync store.

**Check gap.** The byte-identical check in `tools/check-kotlin-architecture.sh`
compares only same-named files in `ui/src/androidMain` and `ui/src/desktopMain`.
Most Android platform code lives in `androidApp`, and its files carry an
`Android` prefix, so the check cannot detect the duplicates listed above.

## Platform status

This table describes the repository at the review date. It is not a support
guarantee. A platform supports a workflow only after that workflow's platform
acceptance tests pass and its limitations are recorded in
[COMPATIBILITY.md](COMPATIBILITY.md). Packaging alone is not feature parity.

| Platform | Runtime | State in source and packaging | Platform-specific direction |
| --- | --- | --- | --- |
| Android | Compose Multiplatform | Active launcher; signed alpha APK and AAB. Source includes Keystore-backed credentials, WorkManager workers, a `DocumentsProvider`, selected-folder sync, media backup, Media3 playback sessions, and share targets. | Notifications and push, calls, and the platform acceptance gates in the roadmap |
| Linux | Compose Desktop | Primary interactive desktop target; alpha RPM and DEB. Source includes Secret Service storage through `secret-tool`, a StatusNotifier tray, selected-folder sync, and a FUSE virtual filesystem (`LinuxVirtualFileSystem.kt`). | Portals, notifications, media keys, and conventional sync roots |
| Windows | Compose Desktop | Unsigned x86-64 MSI with Credential Manager storage, build provenance, and a Cloud Files provider (`WindowsCloudFilesProvider.kt`) under prerelease qualification. | Explorer validation, trusted signing when available, notifications, media controls, and updates |
| macOS | Compose Desktop | Early DMG packaging artifact. Keychain storage (`MacOsKeychainSecretStore`) is present in source and unit-tested, but authenticated use has not been live-validated or qualified. | Keychain, File Provider and Finder integration, notifications, media controls, and updates |
| iOS / iPadOS | None | No iOS target exists in the Gradle build and no launcher is shipped. | Keychain, File Provider, background transfer, share extension, notifications, media, and CallKit |

Standard CI builds Android packages and runs Android unit tests. It does not
run connected-device instrumentation, although instrumented tests exist under
`androidApp/src/androidTest`. A package or a passing unit-test job is not
evidence that device acceptance criteria have passed.

## Shared boundaries

These rules decide where new code goes:

- Shared modules must not import Android, Apple, Windows, macOS, or Linux APIs.
- Platform services implement interfaces owned by shared domain code.
- Protocol parsing, permission rules, sync policy, retries, conflicts, and
  account identity belong in shared code. Move existing duplication toward
  that boundary instead of designing a new platform-specific copy.
- Credentials stay inside the platform credential store.
- Filesystem providers and sync roots expose shared file and transfer
  semantics while keeping each operating system's native provider model.
- Shared Compose components carry behavior and semantics. Platform layouts may
  arrange them differently.

### Shared application read session

Android and desktop use the same JVM adapter for dynamic application reads,
including native audio stream requests
([`JvmAuthenticatedAppReadSession.kt`](ui/src/jvmMain/kotlin/dev/obiente/nextcloudnative/app/JvmAuthenticatedAppReadSession.kt)).

- Playback refreshes an expired session per request on a stream worker, and
  cancelling the stream cancels its preparation.
- Account retirement clears pending Android requests and stops the matching
  MediaSession queue. Leaving a screen does not stop background playback.
- Desktop retirement cancels the matching download and disposes its player and
  staged file.
- Queue identities prevent a delayed cleanup from stopping a replacement queue.

Deterministic JVM tests cover cookie rotation, path isolation, credential
replacement, cancellation, and retirement. They do not establish Android device
or live-server compatibility; that evidence belongs in
[COMPATIBILITY.md](COMPATIBILITY.md).

### Shared workspace components

This section describes behavior present in source, not availability in a
published package. Shared rendering and deterministic tests do not prove
identical input handling on every operating system or touchpad; native-device
interaction validation is still required.

**Navigation and app shell**

- `NextcloudCollectionWorkspaceScaffold` uses the same typed destinations and
  selection callbacks in every layout. Compact layouts use short text tabs for
  small destination sets. For larger sets, the workspace title opens a section
  chooser, which adds search above seven sections. Tablets and desktop keep
  rails and collapsible sidebars. Changing section never bypasses the host's
  draft or mutation navigation guards.
- The app shell uses Home on every platform. On phone and tablet, the Apps
  navigation slot names the open app and opens an app switcher with pinned and
  recent apps, installed-app search, Folder sync, and the full catalog. Opening
  the switcher keeps the workspace mounted, and switching apps still uses the
  host's guarded callbacks.
- A phone-to-tablet layout change moves the same workspace composition, so
  local drafts survive within the running session.
- Desktop sidebars collapse on request and below 900dp. Both widths keep pinned,
  recent, and current apps with labeled controls and full-name tooltips.
  Settings and the account entry share a utility footer that scrolls in short
  windows. Collapse state is a saved presentation preference, not a server
  setting.
- The compact Apps browser is a searchable list with category filters and
  pinned shortcuts. Long labels truncate explicitly and the header grows with
  text size. Desktop keeps its catalog grid. App tiles open directly; pinning
  and compatibility details are in overflow menus.

**Media**

- `MediaImageCanvas` shares bounded zoom and pan across touch, mouse, touchpad,
  keyboard, and visible controls. Pinch or double-tap zooms, and scrolling zooms
  around the pointer. With keyboard focus, `+` and `-` zoom, `0` fits, `1`
  shows actual size, and arrow keys pan while zoomed. At fit size, Left and
  Right move to the previous or next item.
- Fit and 1:1 controls distinguish fitting the window from one decoded pixel
  per viewport pixel. Percentages describe the decoded image, not an original
  that has not been loaded. These controls never edit the source image.
- Photos places the date scrubber over edge-to-edge timeline content. The thumb
  and narrow rail keep date navigation while the surrounding area still accepts
  photo taps.

**Files, Activity, and Folder sync**

- Desktop Files can hide the library or details pane. At intermediate widths
  only one secondary pane is shown, so the file list stays usable.
- Activity separates actionable notices from the rest of the event history.
  Settings opens account details on demand.
- Folder sync shows queue state and last scan time separately. A completed scan
  is never presented as a completed sync. Phone pair rows open dedicated
  details; desktop keeps a pair table and inspector. Conflict choices explain
  their consequences before the existing confirmation and revision checks.
- Storage distinguishes integration availability, connection state, and
  retained edits. Transfer history uses the verified receipt time for completed
  uploads.

**Editors**

- Existing Calendar events and editable dynamic records use in-place workspace
  editors with the same fields, validation, and mutation paths as their dialog
  versions. Save and Cancel stay with the form. A dirty draft needs an explicit
  discard decision before navigation, and a pending save blocks leaving.
  Creation, pickers, and short confirmations may still use dialogs.
- Notes uses an editor toolbar with Save outside the scrolling content. In
  short windows, title and folder controls move into an accessible Details
  dialog. Shared Markdown typography uses content-sized headings in Notes,
  Files, Deck, Talk, and news. `NoteEditorContentTest` covers the short-window
  controls and preservation of temporary form values.
- Contact detail bodies scroll independently of their Edit and Delete buttons.

**Calendar and Talk**

- Calendar shares view selection, time-first event rows, named calendar
  checkboxes, and event detail facts. Phone Month shows a compact date grid
  above the selected day's events, and Week uses a day strip. Desktop has a
  continuous month grid with event overflow and scrollable week columns.
- Shared date grouping includes multi-day events within a bounded window and
  respects exclusive all-day end dates. The editor puts title and schedule
  first, with compact recurrence and calendar selectors.
- Talk bounds the composer and the desktop message width. A failed send keeps
  the draft. Refresh only reads the conversation, and an uncertain resend needs
  explicit confirmation. Timestamps show UTC and do not imply delivery or read
  state.

**Office**

- Office uses native document selection. Android embeds only a Direct Editing
  session the user asked for, never the Nextcloud dashboard or app navigation.
  Desktop opens editing sessions in the system browser. Preview and Edit are
  separate actions, and Edit is gated by advertised MIME support and current
  permissions. This is not an automatic web fallback for other apps. See
  [ADR 0001](docs/architecture-decisions/0001-android-office-web-integration.md)
  for the authentication, provider selection, and retry boundaries.

Focused scene tests cover many of these interactions. Device rotation, visual
evidence, and dated device observations belong in
[COMPATIBILITY.md](COMPATIBILITY.md).

## Mobile product rules

Every mobile surface must provide:

- correct safe-area insets, system back, touch targets, and permission flows;
- state restoration across rotation, activity recreation, and process death;
- durable background work that is honest about Android and iOS scheduling
  limits;
- progressive layouts for large phones, tablets, foldables, and external
  displays;
- native share and open-with, notifications, media sessions, filesystem
  providers, camera and media discovery, and calling surfaces.

App-specific presentation groups related values around the task. These
surfaces reuse the existing typed models, action bindings, and recovery
policies:

- Budget puts account balance and period spending first, keeps category
  limits and carryover together, and omits unavailable totals instead of
  showing zero.
- Music playlists keep ordered tracks and playback controls together.
- Calendar editors group Schedule and Details, with Save and Cancel outside the
  scrolling body.
- Transfer history puts queue status and the failed-upload filter before the
  records, centered and bounded on wider windows.
- Pantry collection cards emphasize household and list descriptions. Grocery
  rows show quantity without replacing completion recovery.
- Tables cards keep declared counts visible and secondary metadata expandable.
  Record details group typed cells under their primary value without repeating
  it. Compact table controls wrap when space is limited.

View switchers and form choices use shared native components on phone and
desktop. Calendar, compact Chores, Budget categories, and dynamic enum fields
share selection, focus, and overflow behavior without sharing domain state or
write policy. See [shared native choice controls](docs/shared-ui-controls.md).

## Desktop product rules

Every desktop surface should provide, where the workflow benefits:

- resizable master-detail and multi-pane workspaces;
- keyboard navigation, pointer selection, context menus, drag-and-drop, and
  accessibility focus;
- dense tables, persistent inspectors, multi-selection, and broad content views
  instead of phone cards stretched across a window;
- conventional sync roots or native virtual-file providers, according to the
  operating system;
- secret storage, notifications, system media controls, file associations,
  updates, and native packaging.

### Desktop account recovery

These are source and deterministic-test guarantees, not claims about a
published installer.

- **Interrupted account cleanup** has its own retry screen, separate from
  unavailable secure storage. It keeps the saved sign-in and offers no sign-in
  reset while cleanup is pending or needs review. Unrecognized cleanup records
  direct the user to Obiente support. The screen explains how to reopen the app
  for another attempt; its check action only reloads session state.
- **Credential-save recovery** persists a terminal marker before erasing its
  identity fields, so a restart can finish cleanup at any interruption point
  without mistaking a completed save for an incomplete rollback. See
  `DesktopCompletedCredentialSaveCleanupTest`.
- **Legacy Deck drafts** that cannot be attributed, because the keyring or
  encrypted content is unreadable, keep account cleanup pending and the files
  preserved. Restoring keyring access permits a retry. A permanently damaged
  draft can keep blocking cleanup until its ownership or deliberate removal is
  resolved; corrupt encrypted drafts are not recovered automatically.
- **A registry written by a newer format** blocks sign-in with compatibility
  guidance. Credentials and registry data are unchanged, and the user must
  reopen a compatible app version instead of repeating browser login.
- **Malformed registry data** without a recoverable legacy session shows
  retained-data and support guidance, without a reset or an update claim. A
  valid legacy session can still repair malformed registry data.

## Platform delivery rule

Shared code is valuable only when it preserves correct native behavior. Move a
rule into shared code when it is domain policy. Keep an implementation in a
platform source set when it depends on lifecycle, security, scheduling,
filesystem, media, notification, windowing, or accessibility APIs unique to
that operating system.

The dependency order and acceptance gates are defined in
[ROADMAP.md](ROADMAP.md), especially the platform productization milestone.

## Installed display names

The displayed brand is `nati.ve` on every platform:

- Android's application label is `nati.ve`.
- Linux launcher and AppStream names are `nati.ve`, including the launcher
  rewritten into Debian packages.
- macOS packages use `nati.ve.app` and the `nati.ve` Dock name.
- Windows product and shortcut names are described in
  [Windows release packaging](docs/windows-release.md#installed-application-name).

The Android application ID, Linux package and desktop-file IDs, persisted data
paths, and Windows upgrade identity are compatibility identifiers and have not
changed. On macOS, remove the previous `NextcloudNative.app` after installing
`nati.ve.app`; the DMG does not migrate an existing bundle.

These names describe source packaging configuration. Check the
[release artifacts](https://github.com/Obiente/native/releases) to see whether a
published release contains them.

## Desktop SQLite runtime verification

The desktop sync stores use the bundled AndroidX SQLite driver
(`androidx.sqlite:sqlite-bundled`). The version is pinned to 2.6.2 in
`gradle/libs.versions.toml` because the 2.7.0 and 2.7.1 JVM artifacts omit the
Intel macOS native library.

- `DesktopSqliteRuntimeTest` checks the native resources for each packaged
  desktop architecture and opens an in-memory database on the test host.
- An Intel macOS CI job validates relevant dependency and packaging changes.
- Nightly and prerelease macOS packages run their launcher with
  `--verify-sqlite-runtime` before creating application services. This check
  does not read accounts or modify user databases.

A resource check on one operating system does not establish runtime validation
on another.
