# Login diagnostics

The source implements local diagnostics export before sign-in. Private report
submission remains an authenticated feature.

## Save a login report

1. Select **Export login diagnostics** on the login or account-storage recovery
   screen.
2. Optionally describe what happened. Avoid passwords and private account content.
3. Select **Save login diagnostics**, then choose a destination in the system
   save or share dialog.
4. Review the archive before sharing it privately with support. Saving the report
   does not submit it or create a public issue.

On Android, "Report prepared" means the system share sheet opened. Complete the
save or share action there; dismissing the chooser does not confirm delivery.

Reports contain bounded, sanitized diagnostic history and device information.
Automatically collected diagnostics exclude credentials, cookies, private URLs,
filenames, and file content. Optional notes may still contain sensitive text that
automatic redaction cannot recognize. Check your notes before exporting and
review the archive before sharing it. Treat the exported report as private even
after redaction. The optional draft stays in memory and is not saved as
application state.

The login view uses the local export service directly. It does not load private
support requests, require an account identity, or start a support submission.
Unavailable diagnostics, cancelled exports, and failed saves remain visible and
retryable without asking the user to sign in.
Recovery actions and the diagnostics button scroll together on short windows
and when accessibility text sizes need more space.

## macOS credential storage

When Keychain confirms that a newly authenticated account has no stored secret,
a missing legacy Secret Service executable does not block its first save.
Existing registered accounts and encrypted draft keys retain the legacy
migration requirement. A locked Keychain, an installed but unavailable legacy
store, or cancellation is not treated as an absent credential.

The migration layer retains pending legacy cleanup after Keychain adoption.
Account publication failures retain the existing credential rollback policy.

## Evidence and release status

**Last reviewed: 2026-09-27.** Source and release availability may have changed.
Check [published releases](https://github.com/Obiente/native/releases) for the
build containing this change and its platform limitations.

The implementation is in
[LoginDiagnosticsView](../ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/LoginDiagnosticsView.kt)
and
[DesktopAccountCredentialPersistence](../ui/src/desktopMain/kotlin/dev/obiente/nextcloudnative/app/DesktopAccountCredentialPersistence.kt).
Regression tests exercise credential migration and local export with synthetic
data. The native Keychain test runs only on macOS; passing Windows tests or an
Android compile does not establish a successful live macOS login.
