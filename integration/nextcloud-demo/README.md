# Real Nextcloud compatibility instance

This directory defines a disposable Nextcloud instance for native compatibility
and end-to-end testing. It is isolated from personal accounts, creates only
synthetic data, and keeps credentials, TLS keys, reports, and server state out
of Git.

The default server is Nextcloud 34.0.3 with PostgreSQL, Redis, and an HTTPS
gateway. A second HTTP port is bound exclusively to `127.0.0.1` so browser
automation can exercise the web dashboards without weakening TLS validation or
installing the private demo CA into a desktop profile. Android and physical
devices use only the HTTPS origin. The Nextcloud container trusts only this
instance's generated CA so its built-in CODE proxy can use the same HTTPS origin
that the document editor sees. The representative app manifest covers
native DAV and API workspaces, plus Nextcloud Office document editing. Android
embeds only the selected document's Direct Editing session and checks the
certificate already approved inside the app. Desktop opens that session in the
system browser, which must independently trust the demo CA. Office file
selection stays native on both platforms; neither flow opens an app dashboard.
The manifest's `embedded` label denotes this Android Office-only integration,
not a generic web fallback or a desktop embedded runtime.
The optional catalog command can stage every App Store package
compatible with the running server. Staged catalog apps remain disabled so
authentication, administration, and workflow apps cannot silently change the
whole test instance.

The image version follows the [official Nextcloud container
tags](https://hub.docker.com/_/nextcloud). Account and app-password creation use
the documented [Nextcloud 34 `occ` user
commands](https://docs.nextcloud.com/server/stable/admin_manual/occ_users.html).
The built-in CODE app follows the supported [Nextcloud Office installation
model](https://docs.nextcloud.com/server/stable/admin_manual/office/installation.html).

## Start and provision

Podman with a Compose provider, OpenSSL, curl, and jq are required. Run from the
repository root:

```bash
tools/nextcloud-demo.sh init
tools/nextcloud-demo.sh up
tools/nextcloud-demo.sh provision
tools/nextcloud-demo.sh status
```

The default hostname is `10.0.2.2`, the Android emulator alias for its host.
The host-side seeder connects through `localhost`; both names are present on the
generated certificate. To test from a physical device on the same isolated
network, initialize with the workstation's current LAN address instead:

```bash
tools/nextcloud-demo.sh init 198.51.100.24
```

The address above is documentation-only. Use the actual address of the machine
that runs the stack. Do not expose this development instance to the public
internet.

`init` creates a private development CA and random database, administrator, and
test-account passwords under ignored paths. It never prints passwords. The
provisioner creates the `nc-native-e2e` account, mints one named app password,
enables the representative suite, and uploads only these bounded fixtures:

- `NC Native E2E/README.md` in Files;
- one synthetic vCard in the test account's Contacts address book;
- one synthetic event in its Personal calendar and one task in its Tasks calendar.

Repeated seeding overwrites those exact fixture resources. Tests must create
their own unique records underneath an explicitly declared app or DAV scope and
clean only those records.

## App coverage

[`apps/representative.tsv`](apps/representative.tsv) separates native workspaces
from the embedded Nextcloud Office boundary. Required entries fail provisioning
when they cannot be enabled. Optional entries remain visible in the install
report without blocking unrelated coverage.

Install another compatible app and enable it:

```bash
tools/nextcloud-demo.sh install-app polls
```

Stage every package returned by the official App Store for the running server:

```bash
tools/nextcloud-demo.sh stage-catalog
```

For a quick infrastructure check, pass a numeric limit. Results are written to
the ignored `reports/` directory:

```bash
tools/nextcloud-demo.sh stage-catalog 10
```

Catalog staging is not an end-to-end pass. Full catalog testing enables one app
at a time, discovers its signed routes and capabilities, exercises safe reads,
executes only explicitly scoped writes against app-owned fixture records, and
then restores the baseline. Apps that need mail servers, TURN, maps, hardware,
licensed services, or administrator configuration must report that dependency
instead of being counted as functional.

## Android emulator

The generated CA is private to this instance. Copy it to the isolated emulator
and complete Android's interactive CA installation screen:

```bash
tools/nextcloud-demo.sh android-ca compatibility
```

Install a debuggable APK, then import the disposable account. The import is
read-only by default:

```bash
tools/nextcloud-demo.sh android-session compatibility
```

If the host's LAN address changes, use another HTTPS origin already covered by
the generated demo certificate. Android emulators can always reach the host at
the certificate's `10.0.2.2` alias:

```bash
tools/nextcloud-demo.sh android-session compatibility https://10.0.2.2:8443
```

The helper rejects HTTP, paths, a different port, and host names or addresses
that are not in this demo's server certificate. It transforms the private
session only while streaming it into the app; the credential file is not
rewritten or printed. The server readiness check uses the certificate's local
host alias, so an ordinary `up` also remains usable after a LAN address change.

Writes require a second, explicit command naming one exact app API subtree or
one synthetic CardDAV or CalDAV collection. The app accepts only HTTPS, the
same origin, mutation methods it recognizes, and a path under an explicit
`/apps/<app>/api/...`, `/index.php/apps/<app>/api/...`, or
`/ocs/v2.php/apps/<app>/api/...` subtree. DAV scopes accept only descendants of
one exact `/remote.php/dav/addressbooks/users/<user>/<book>` or
`/remote.php/dav/calendars/<user>/<calendar>` collection; the collection itself
cannot be mutated:

```bash
tools/nextcloud-demo.sh android-write-scope compatibility \
  /apps/chores/api/v1.0/team
```

Remove the authorization immediately after that workflow:

```bash
tools/nextcloud-demo.sh android-clear-write-scope compatibility
```

Set `NC_DEMO_ANDROID_PACKAGE=dev.obiente.nextcloudnative.dev` when testing the
dedicated `.dev` package instead of the ordinary debug application ID.

## Lifecycle and recovery

Ordinary shutdown preserves database, app, and file volumes:

```bash
tools/nextcloud-demo.sh down
tools/nextcloud-demo.sh up
```

Reset is deliberately separate and requires an exact confirmation flag:

```bash
tools/nextcloud-demo.sh reset --confirm
```

That command deletes the volumes belonging to the `nc-native-demo` Compose
project together with its ignored `.env`, cached app-password sessions, scoped
write authorizations, and private TLS material. Reports and reusable App Store
metadata remain. Run `tools/nextcloud-demo.sh init [host]` before starting a
fresh demo.

Validate the repository-side contract without launching containers:

```bash
integration/nextcloud-demo/tests/test-nextcloud-demo.sh
tools/nextcloud-demo.sh validate
```
