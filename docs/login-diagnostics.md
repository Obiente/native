# Sign-in recovery and login diagnostics

**Last reviewed: 2026-09-28.** This describes repository implementation, which may
have changed. Check the [current source](https://github.com/obiente/native/blob/main/ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/LoginAttemptState.kt)
and the release notes for the installed build before assuming a released version
includes these controls.

## Sign-in stages

Sign-in shows which stage it is in: connecting to the server, waiting for you
to approve in the browser, retrying a connection, or finishing the account
setup.

While the app waits for browser approval or retries the connection,
**Cancel sign-in** stops the attempt and lets you edit the server address
again. On Android, the system Back action does the same. Cancelling does not
revoke credentials the server has already issued.

The form scrolls when a short window, landscape layout or larger text makes it
taller than the screen. In landscape it keeps a bounded width. If the server
address is malformed, the app explains how to correct it instead of showing
the URL parser's error text.

## Connection problems

A name-resolution message means a DNS lookup failed before any request reached
the server. It does not prove the server is down, or that the failure is still
happening. Check the address and the device's network or VPN connection.

The app retries this kind of failure after a bounded delay. When the server
answers again, the browser-approval status returns. You can also cancel, change
the address or connection, and start a fresh attempt.

## Time limit and uncertain results

Each attempt has a five-minute deadline, including network waits.

If the result became uncertain after the one-time approval exchange, start a
new sign-in. Do not try to replay that exchange. The app does not retry this
case automatically.

## Save a login report

You can export local diagnostics before you sign in. Sending a private report
to support still requires a signed-in account.

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

## Test coverage

Synthetic regression tests in `LoginPollingTest` and `LoginAttemptStateTest`
exercise retry phases, timeout, cancellation, browser-handoff failure, and late
completion after a replacement attempt. They do not establish connectivity to
any particular server or device network.

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
