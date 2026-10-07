# Sign-in recovery

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

## Sharing diagnostics safely

**Export login diagnostics** is available before you sign in. Review any
support report privately before sharing it. Do not post credentials, approval
URLs, tokens, private server addresses or raw account responses in public
issues.

## Test coverage

Synthetic regression tests in `LoginPollingTest` and `LoginAttemptStateTest`
exercise retry phases, timeout, cancellation, browser-handoff failure, and late
completion after a replacement attempt. They do not establish connectivity to
any particular server or device network.
