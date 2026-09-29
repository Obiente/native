# Sign-in recovery

**Last reviewed: 2026-09-28.** This describes repository implementation, which may
have changed. Check the [current source](https://github.com/obiente/native/blob/main/ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/LoginAttemptState.kt)
and the release notes for the installed build before assuming a released version
includes these controls.

Sign-in distinguishes connecting to the server, awaiting browser approval,
retrying a connection, and completing the account transition. During browser
approval or network retries, **Cancel sign-in** stops the attempt and makes the
server address editable. Android system Back performs the same cancellation.
Cancellation does not revoke credentials already issued by the server.
The form scrolls when a short window, landscape layout, or larger text makes its
controls taller than the available space, and keeps a bounded width in landscape.
Malformed server addresses show correction guidance without displaying the URL
parser's exception text.

A name-resolution message records a DNS failure observed before a request reached
the server. It does not prove the server is down or that the same failure still
exists. Verify the address and the device's network or VPN connection. The app
retries this pre-exchange failure with a bounded delay; a successful pending
response restores the browser-approval status. You can cancel and start a fresh
attempt after changing the address or connection.

The attempt has a five-minute deadline, including network waits. If the response
became ambiguous after the one-time approval exchange, start a new sign-in instead
of replaying that exchange. The app does not automatically retry this outcome.

**Export login diagnostics** remains available before sign-in. Review any support
report privately before sharing it. Do not post credentials, approval URLs,
tokens, private server addresses, or raw account responses in public issues.

Synthetic regression tests in `LoginPollingTest` and `LoginAttemptStateTest`
exercise retry phases, timeout, cancellation, browser-handoff failure, and late
completion after a replacement attempt. They do not establish connectivity to
any particular server or device network.
