# Prerelease checklist

Use this checklist before approving the protected `prerelease` environment.
Every incomplete item must be documented as a known limitation in the release
notes. Data-loss, authentication, privacy, signing, or upgrade failures block a
release.

## Version and source

- [ ] The version is below `1.0.0`, has an alpha, beta, or rc suffix, and
      matches the tag.
- [ ] The tagged commit is contained in `main` and all required checks pass.
- [ ] Release notes lead with user-visible changes and clearly state known
      limitations.

## Privacy and security

- [ ] Repository hygiene and secret scanning pass.
- [ ] Logs, screenshots, fixtures, tests, and release notes contain no account,
      endpoint, credential, contact, message, file, or personal media data.
- [ ] Authentication storage, TLS behavior, external links, and exported
      Android components were reviewed.
- [ ] The Android APK and AAB use the protected release key, and the reported
      certificate fingerprint matches the expected release identity.

## Data safety and upgrades

- [ ] Database, cache, account, and settings migrations were tested from the
      previous prerelease.
- [ ] File, media, DAV, and offline mutations retain revision guards and do not
      silently overwrite or delete data.
- [ ] Interrupted upload, download, sync, and update flows recover safely.
- [ ] Downgrade and incompatible-schema behavior is explicit.

## Permissions and platform behavior

- [ ] Android permissions are requested only when needed and denial, limited
      access, and permanent denial have usable states.
- [ ] Background work, notifications, battery behavior, rotation, process
      death, back navigation, and constrained layouts were checked.
- [ ] Android and desktop smoke tests cover login, Files, media, Talk, settings,
      and logout without exposing private test data.
- [ ] Keyboard navigation, screen-reader labels, contrast, focus order, text
      scaling, and reduced space were reviewed.

## Packaging and public metadata

- [ ] Android, Linux, Windows, and macOS artifacts install or open as expected,
      use the correct icon and application identity, and uninstall cleanly.
- [ ] Package metadata, minimum platform versions, licenses, notices, website,
      source, issue tracker, and privacy/security links are accurate.
- [ ] Mobile and desktop screenshots come from the real application running
      deterministic synthetic data.
- [ ] `SHA256SUMS` and `update-manifest.json` match the published artifacts.

## After publishing

- [ ] GitHub marks the release as a prerelease.
- [ ] Release assets download successfully and checksums verify.
- [ ] A direct-APK installation accepts the verified update only after Android
      user confirmation; store-owned installations remain on their store
      channel.
- [ ] The website and in-app News surface show the plain-language release notes.
- [ ] Rollback or withdrawal steps are understood if a blocking defect appears.
