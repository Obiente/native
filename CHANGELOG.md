# Changelog

All notable changes to Nextcloud Native are recorded here. News articles explain
features in depth, while release notes summarize what people should know before
installing a particular build. Pull requests add independent files under
`changes/unreleased/`; the website renders them here until release preparation
archives them and curates the immutable version section below.

The project remains in prerelease development. Versions stay below `1.0.0`
until the full product sprint is complete.

## Unreleased

## [0.1.0-alpha.2]

### Features

- [Android, Desktop] Activity, Calendar, Budget, Tables, and verified dynamic apps now share native data and actions across mobile and desktop while adapting navigation, density, forms, charts, and workspace layout to each screen (issue #252, PR #336).
- [Android, Desktop] Android can render RAW sources through Memories or bounded embedded previews, decode and cache TIFF images, and show useful format and image metadata in the media viewer (issue #84, PR #249).
- [Android, Desktop] Deck boards now open as native lanes and cards with drag-and-drop movement, context-aware editing, durable drafts, attachments, relationships, and safer mutation recovery (issue #52, PR #221).
- [Android] Direct Android installs now check their selected channel automatically, show one notification per available version, and keep download and installation under the user's control (issue #237).
- [Linux] Direct DEB and RPM installs can now check their selected channel, match a download to its advertised checksum, and open it in the system installer while externally managed installs remain untouched (issue #268).
- [Android, Desktop] Dynamic collection apps now use native workspace navigation, search, inline reordering, paging, semantic forms, and verified previews. Desktop gains dedicated Overview, Folder sync, and Activity workspaces plus system folder selection (issue #252, PR #273).
- [Android, Desktop] Dynamic collections now provide adaptive navigation, scoped search and filters, deterministic sorting, and a clear no-results state without carrying browse choices into another account or parent collection (issue #250, PR #273).
- [Android, Desktop] Files and media now share through one context-aware recipient picker with native permission, password, expiration, and note controls (issue #124, PR #219).
- [Android, Desktop] Files now includes early selected-folder sync and writable virtual files on Android, Linux, and Windows, with visible desktop progress and conflicts. Conflict recovery and background durability remain in development (issue #11).
- [Website] Guides now separate Android, Linux, and Windows workflows, explain current alpha limitations, and use task-specific native screenshots throughout (PR #319).
- [Linux] Linux users can choose the drive and folder name for the virtual Nextcloud filesystem, keep selected folders available locally, and safely return them to online-only storage without removing their cloud entries (issue #11).
- [Linux] Linux virtual files can use a fast primary cache and a separate overflow drive for cold cached and offline-pinned content while keeping one stable folder in the file manager (issue #321, PR #323).
- [Android, Desktop] Mail now opens as an adaptive native workspace with accounts, mailbox hierarchy, a virtualized inbox, persistent desktop message detail, mobile navigation, and a safe contract-backed Compose entry (issue #54, PR #273).
- [Android] Media backups now have a bounded transfer center for pending, active, failed, and completed uploads with durable recovery and cached failure states (issue #168, PR #220).
- [Android, Desktop] Music libraries can use adaptive albums, artists, and tracks navigation, collection artwork, a full collection play action, and a queue that remains available after playback errors (issue #56, PR #273).
- [Android, Desktop] Native workspaces now cover Home, Apps, Settings, Calendar, and desktop navigation. Calendar adds searchable Month, Week, and Agenda views with safe event and recurrence editing. App switching and refreshes retain content and state (PR #273).
- [Website] Nextcloud Native now has a timeless product website with clearer platform guidance, living project articles, and native application captures that adapt between dark and light themes (issue #230).
- [Android, Desktop, Website] Nextcloud Native now includes illustrated guides for setup, folder sync, offline files, photo backup, Calendar, and app switching. Each step uses dedicated light and dark captures from the real Compose interface (PR #273).
- [Android, Desktop, Linux, macOS, Windows] Nightly builds now publish signed Android packages and native desktop packages for direct installations that follow the Nightly update track (issue #226, PR #227).
- [Android, Desktop] Pantry and other dynamic collection apps now provide adaptive nested navigation, meaningful icons, option and relation pickers, permission-aware editing, reversible completion, and confirmed archive, restore, trash, and deletion actions (issue #252, PR #258).
- [Android, Desktop] Photo folders now use the dedicated Memories folder endpoints, keep direct and recursive scopes explicit, and retain useful saved content when a refresh fails (issue #243, PR #249).
- [Android, Desktop] Photos now loads the Memories timeline in bounded day batches and provides a full-height edge scrubber for fast, smooth navigation across months and years (issue #242, PR #249).
- [Android, Desktop] Photos now loads the complete timeline in stable pages, groups media by month and year, and adds a draggable date scrubber on phones without keeping the entire library in memory (issue #242, PR #246).
- [Android] Photos now recognizes server-indexed Live Photos and Motion Photos, shows their motion state, and plays the motion component while keeping the still image as a reliable fallback (issue #182, PR #244).
- [Android, Desktop] Photos now uses adaptive Timeline, Folders, Albums, and People navigation. The new folder browser adds folder-path search, direct or recursive media scopes, and grid or list views without splitting RAW pairs into duplicate items (issue #243, PR #245).
- [Android, Desktop, Linux, macOS, Windows] Settings can now export a bounded, anonymized support report with app-wide failure context and optional reproduction steps. Reports stay local until the user explicitly saves or shares them (issue #317).
- [Android, Desktop] Standalone RAW photos can now open through bounded embedded or server-rendered previews while file actions continue to target the original RAW file (issue #85, PR #218).
- [Android, Desktop] Tables now keeps columns, saved views, rows, and sharing permissions reachable from each table across phone and desktop layouts (issue #51, PR #336).
- [Android] The photo viewer paints a fast preview first, then loads a bounded original-quality image automatically and reuses the decoded result from its local preview cache (issue #86, PR #249).
- [Website] The website now provides a filterable visual QA catalog backed by deterministic, privacy-safe captures of the real application UI (issue #224, PR #225).
- [Windows] Windows Cloud Files roots now appear in File Explorer with Nextcloud Native branding. The MSI includes its icon and a supported storage-provider registrar while retaining the existing Cloud Files path as a safe fallback (issue #290).
- [Windows] Windows builds now use Credential Manager, preserve Windows trust prompts for in-app MSI updates, clean up Cloud Files registration during uninstall, publish keyless provenance, and run native Windows packaging tests in CI (issue #102).

### Fixes

- [Android] Android can try a bounded software playback fallback when the device decoder cannot play a video stream, while preserving a clear external-player handoff (issue #83, PR #249).
- [Android, Desktop] Anonymized support reports now distinguish DNS, connection, timeout, TLS, and HTTP/2 stream failures without exposing server names or raw error messages (issue #339, PR #340).
- [Linux, Windows] Connecting native virtual-file integration no longer fails because the account-scoped activation setting exceeds the platform preference key limit (issue #11).
- [Android, Desktop] Deck and compatible reusable boards now scroll between lists and through long lists while a card is dragged near the viewport edge (issue #52, PR #238).
- [Linux, Windows] Desktop folder sync now handles larger trees with indexed state and lighter virtual-file cache writes. Linux and Windows register start-on-login only after it is explicitly enabled; Linux then starts its supervised tray session (issue #11).
- [Linux, Windows] Desktop installs can start background sync at login, reuse one activatable process, remain accessible without a tray, route tray actions correctly, and restart or recover cleanly after Windows updates (issue #286).
- [Desktop, Windows] Desktop shutdown now preserves every queued anonymized diagnostic event, including on slower Windows storage (issue #317).
- [Desktop] Development desktop builds can now update to a newer verified release from their selected remote update channel (issue #327, PR #328).
- [Desktop] Fixed global Files search requests rejected by servers that validate the documented WebDAV SEARCH property set, and report search failures as search errors instead of folder-listing errors (issue #331, PR #332).
- [Android, Desktop, Website] Home and adaptive data captures now use the production dashboard and table presentations, including wrapped shortcuts and native record cards on compact screens (issue #233, PR #235).
- [Linux] In-app Linux updates now use a fresh system-authorized package transaction instead of a stale software-center transaction (issue #288).
- [Linux] Linux DEB and RPM packages now include release details that match the packaged version, and RPM verification inspects the actual metadata and artwork instead of checking filenames alone (issue #267).
- [Linux] Linux file managers now browse large Nextcloud folders from fast, stable metadata snapshots instead of repeating a server listing for every visible file (issue #11).
- [Linux] Linux now uses the desktop StatusNotifier tray, keeps sync running after the window closes when enabled, launches through its supervised user service, and avoids repeated full refreshes for very large virtual folders (issue #11).
- [Linux] Linux packages now provide the application description, license, website, icon, and screenshots used by GNOME Software and other AppStream clients (issue #267).
- [Linux] Linux tray menus now provide activity, app, and quit controls, and quitting cleanly releases the virtual-files mount without leaving the background service stuck (issue #286, PR #325).
- [Android] Live Photos can use a bounded software playback fallback for resolved companion videos and safely indexed embedded motion tails when the device HEVC decoder fails (issue #182, PR #249).
- Nightly releases now explain the user-facing features and fixes in each build with platform and issue context (issue #269).
- [Android, Desktop] Photos keeps ordinary media available when optional RAW discovery is unsupported, isolates each search consumer, and no longer reports cancelled collection loads as failures (issue #248, PR #249).
- [Android, Desktop] Photos now discovers RAW images with bounded WebDAV queries on servers that reject one large combined media search (issue #239, PR #240).
- [Android, Desktop] Photos now loads normal media without probing unsupported RAW searches and keeps the available timeline when optional RAW discovery is rejected (issue #239, PR #241).
- [Android, Desktop] RAW and rendered photo pairs remain one timeline item while the media viewer can open either exact source without duplicating previous and next navigation (issue #74, PR #249).
- [Linux] RPM installers no longer conflict with system Java packages through bundled runtime build-ID links (issue #268).
- [Desktop, Windows] Sign out now clears stale Windows Cloud Files registration state and provider preferences for the previous account, so you can sign in with a different account immediately afterward (issue #303).
- [Windows] Sign out now completes account teardown even if Windows Cloud Files root cleanup fails, so users can immediately sign in again (issue #296, PR #297).
- [Android] Signed Android nightly builds now keep install-source detection compatible with Android API 26 (issue #232).
- [Android] Sync and transfer status now remain available while Android indexes or uploads a media folder (issue #234, PR #236).
- [Windows] The native Windows title bar now follows the app's dark or light theme while retaining system window controls (issue #281).
- [Windows] Windows Cloud Files now repairs stale owned registrations, retries a missing provider connection once, and cleans up safely when a provider root or connection is already absent (issue #296, PR #297).
- [Desktop, Windows] Windows Cloud Files startup no longer rewrites unchanged file placeholders, and a rejected optional directory refresh no longer disables the complete virtual-files provider (issue #329, PR #330).
- [Windows] Windows filesync can now safely unregister an exact branded root during corrupt-metadata recovery without probing the unavailable Cloud Files directory first (issue #341, PR #342).
- [Windows] Windows filesync now preserves the existing root and rebuilds File Explorer integration when corrupt Cloud Files metadata would otherwise block activation, without deleting local data (issue #315, PR #320).
- [Windows] Windows filesync now preserves the existing root and rebuilds File Explorer integration when placeholder refresh reveals corrupt Cloud Files metadata (issue #333, PR #334).
- [Windows] Windows filesync now recovers when Explorer creates the same placeholder concurrently, while preserving ordinary local entries for safe recovery (issue #315).
- [Windows] Windows filesync setup now repairs stale Nextcloud Native registrations while preserving local files and retaining live roots for recovery (issue #312).
- [Windows] Windows now cleans up the legacy Cloud Files path without mistaking the active branded provider root for that obsolete registration (issue #11).
- [Desktop, Windows] Windows virtual files now serialize initial and on-demand placeholder population so concurrent File Explorer requests cannot abort activation with a name collision (PR #324).
- [Desktop, Windows] Windows virtual files now use Cloud Files oplocks while updating existing placeholders so File Explorer access cannot interrupt activation with a handle-sharing race (PR #326).
- [Windows] Windows virtual-file folders now use complete filesystem metadata and migrate away from an unreadable provider root (issue #280).

### Platform

- [Android, Desktop] Direct installations now follow the Nightly update track by default. Existing Alpha selections migrate to Nightly, and channel selection stays locked until curated prereleases match the product's maturity (issue #228).
- [Linux] Linux releases can be assembled into signed native APT and RPM repository trees with software-center AppStream catalogs and room for future host-service packages (issue #102).

## [0.1.0-alpha.1]

### Added

- A customizable Home workspace that can show, hide, reorder, and resize
  available sections, with separate layouts for each account and device size.
- Persistent device-media backup states for files that are pending, uploading,
  backed up, changed, failed, or available only in the cloud.
- A preview of representative photos, videos, counts, and storage size before
  enabling backup for a detected Android media folder.
- A native Nextcloud destination picker for media backup and folder sync,
  including explicit confirmation before creating a missing destination.
- Native Cookbook recipe creation, editing, URL import, and recipe lists
  filtered by category or keyword through the adaptive app runtime.
- An optional face-outline view in recognized-person galleries so people can
  see which face was matched without changing server data.
- Project news inside the app and a resumable direct-APK update flow with
  progress, cancellation, retry, package verification, and Android's install
  confirmation.
- A protected prerelease workflow for signed Android artifacts and native
  Linux, Windows, and macOS packages.
- Deterministic prerelease version and Android version-code validation.
- SHA-256 checksums and machine-readable Android update metadata.
- Isolated Android emulator tooling for repeatable portrait, landscape, and
  process-lifecycle testing with synthetic or technically read-only data.
- A maintained privacy, data-safety, permissions, accessibility, packaging,
  and upgrade checklist for prerelease approval.

### Changed

- Android and desktop builds now read one canonical prerelease version from
  `gradle.properties`.
- Media backup status refreshes cancel stale queries when newer transfer state
  arrives.
- The photo and video viewer uses the available canvas more effectively while
  keeping controls clear of the status bar in portrait and landscape.
- RAW and JPEG choices use their own scrollable row instead of crowding photo
  actions.
- Folder-sync direction labels now distinguish upload-only and download-only
  folders.
- Build validation avoids duplicate branch runs and reuses Gradle and Rust
  caches where safe.

### Fixed

- Suggested Nextcloud destinations that do not exist can recover to the nearest
  existing parent and offer to create the missing folders after confirmation.
- Cookbook actions no longer open empty forms when a verified contract refers
  to a separately defined schema.
- Cookbook categories and keywords now open their matching recipes instead of
  exposing the category or keyword record as the destination.
- Binary recipe images are no longer opened as JSON detail records.
- Android release lint understands Kotlin 2.4 metadata and checks the complete
  release source.
- Android APK and App Bundle signatures are matched against the trusted release
  certificate.
- macOS packages use Apple-compatible version metadata.
- Release pages show platform availability only when a build is missing.

### Security

- Android release signing uses a protected GitHub environment and a pinned
  public certificate fingerprint.
- Release publication rejects stable versions, `1.0.0` and higher versions,
  mismatched tags, unsigned Android artifacts, and unexpected signing keys.
- Direct APK updates verify the download checksum, package identity, version,
  and signing certificate before handing the package to Android.
- Permission to request package installation is limited to the direct-APK
  build; store builds do not request it.
