# Real Nextcloud compatibility instance

This directory defines a disposable Nextcloud instance for native compatibility
and end-to-end testing. It is isolated from personal accounts, creates only
synthetic data, and keeps credentials, TLS keys, reports, and server state out
of Git.

The default server is Nextcloud 34.0.3 with PostgreSQL, Redis, and an HTTPS
gateway. A second HTTP port is bound exclusively to `127.0.0.1` so browser
automation can exercise the web dashboards without weakening TLS validation or
installing the private demo CA into a desktop profile. Android and physical
devices use only the HTTPS origin. The Nextcloud container retains the normal
system CA roots and adds this instance's generated CA. Nextcloud reaches its
built-in CODE proxy through `http://localhost` inside the same container; the
editor receives the separate public HTTPS URL. The provisioning health check
uses the host's HTTPS localhost gateway with certificate verification, so the
emulator's `10.0.2.2` alias need not resolve inside the container. This separation
is specific to the built-in CODE service, not an external Office provider.
The representative app manifest covers
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

If multiple Compose providers are on `PATH`, select the provider installed for
this Podman environment explicitly. This matters in WSL when the inherited
Windows `PATH` also contains Docker Desktop's Compose executable. For an
installed Linux `podman-compose`, set this before running the commands above or
the integration tests:

```bash
export PODMAN_COMPOSE_PROVIDER="$(command -v podman-compose)"
test -n "$PODMAN_COMPOSE_PROVIDER"
```

The selected executable must be available inside the environment running
Podman. Do not select a Windows provider merely because it appears first on
WSL's inherited `PATH`.

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

When Memories is enabled, seeding also sets this synthetic account's timeline
folder to `/NC Native E2E` and indexes only that folder. The base fixture set
contains no photos, so the timeline remains empty until a test uploads an image
within that scope. This avoids the upstream first-use folder prompt, which a
native API client cannot complete through the web interface.

Memories People requires a compatible Recognize app with face recognition
explicitly enabled on the server. Installing Recognize alone does not satisfy
that prerequisite. The native client explains this setup condition for the
reviewed Memories 9.0.1 response; unrelated precondition errors remain failures.
The demo does not download recognition models or enable face analysis by default.

New demo certificates include explicit CA signing and server authentication
usage extensions and pass strict X.509 chain verification. Existing instance
certificates are not rotated by `seed` or `up`. To replace an older certificate,
retire the disposable instance with the documented reset workflow, initialize
it again, and install its new CA on the isolated test device. Never disable TLS
verification to reuse an incompatible certificate.

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
read-only by default. Import into an empty app or reimport the same existing
read-only test account. The debug bootstrap updates the current account store
atomically with read-only protection; it rejects an existing normal account.
Changing test accounts requires fresh data in the isolated emulator app:

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

Writes require a second, explicit command naming one exact app API subtree,
synthetic CardDAV or CalDAV collection, or disposable Files folder. The app accepts only HTTPS, the
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

For Files workflows, provision the folder first, then scope writes to
`/remote.php/dav/files/nc-native-e2e/NC%20Native%20E2E`. Only descendants can be
created, replaced, copied, moved, or deleted. The scoped folder itself and the
account root remain protected. MOVE and COPY also require a destination inside
the same folder and HTTPS origin. Encoded spaces are accepted; encoded path
separators, dot segments, and other ambiguous encodings are rejected. This does
not authorize background workers, document providers, or chunked uploads.

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
