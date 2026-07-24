# Changelog

All notable changes to Nextcloud Native are recorded here. News articles explain
features in depth, while release notes summarize what people should know before
installing a particular build.

The project remains in prerelease development. Versions stay below `1.0.0`
until the full product sprint is complete.

## Unreleased

### Added

- Persistent device-media backup states for pending, uploading, backed-up,
  changed, failed, and cloud-only items.
- A protected prerelease workflow for signed Android artifacts and native
  Linux, Windows, and macOS packages.
- Deterministic prerelease version and Android version-code validation.
- SHA-256 checksums and machine-readable Android update metadata.
- A maintained privacy, data-safety, permissions, accessibility, packaging,
  and upgrade checklist for prerelease approval.

### Changed

- Android and desktop builds now read one canonical prerelease version from
  `gradle.properties`.
- Media backup status refreshes cancel stale queries when newer transfer state
  arrives.

### Security

- Android release signing uses a protected GitHub environment and a pinned
  public certificate fingerprint.
- Release publication rejects stable versions, `1.0.0` and higher versions,
  mismatched tags, unsigned Android artifacts, and unexpected signing keys.
