# Changelog

All notable changes to Nextcloud Native are recorded here. News articles explain
features in depth, while release notes summarize what people should know before
installing a particular build. Pull requests add independent files under
`changes/unreleased/`; the website renders them here until release preparation
archives them and curates the immutable version section below.

The project remains in prerelease development. Versions stay below `1.0.0`
until the full product sprint is complete.

## Unreleased

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
