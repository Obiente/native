# Contributing to nati.ve

nati.ve is an independent Obiente project. Contributions are welcome,
especially protocol research, reusable semantic components, accessibility
improvements, compatibility fixtures, and tests against different Nextcloud
versions.

**Last reviewed: 2026-10-02.** Toolchain versions, checks and contribution
requirements may have changed. The sources of truth are
[`gradle.properties`](gradle.properties), the
[version catalog](gradle/libs.versions.toml), the
[Build and test workflow](.github/workflows/ci.yml), [`AGENTS.md`](AGENTS.md)
and the [releases page](https://github.com/obiente/native/releases).

## Before you start

- Open an issue to discuss large architectural changes before you invest
  heavily in them.
- Never put real server URLs, usernames, app passwords, share tokens,
  filenames, message bodies or private API responses in issues or fixtures.
- Prefer reusable semantic behavior over code that checks a specific app ID.
  A small app-specific adapter is fine when it adds verified behavior that
  cannot be inferred safely.
- You may explore reads against a test server. Writes need an explicit,
  reviewed contract and tests for permission, conflict and failure behavior.
- Read [AI_POLICY.md](AI_POLICY.md) before using AI assistance. Contributions
  must be human-led; autonomous agent submissions are not accepted.
- [AGENTS.md](AGENTS.md) is the implementation contract for everyone, human
  or agent. It covers layering, error handling, Compose rules, tests and
  documentation.

## Quick start

### Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| JDK | 21 | All Gradle builds. The Gradle daemon is pinned to an Adoptium JDK 21 toolchain in `gradle/gradle-daemon-jvm.properties` and can download it if missing |
| Rust | stable, via `rustup` | `cargo test`, repository checks, and the Windows Explorer helper built on Windows |
| Node.js | 20.19 or newer 20.x, or 22.12 and later | Repository checks, changelog fragments, and the website |
| Android SDK | Platform 36, Build Tools 35.0.0 | Android builds |
| Bash | Any recent version; Git Bash on Windows | Scripts in `tools/` |

The website requires Node.js `^20.19.0 || >=22.12.0`, matching the `engines`
field in `website/package.json`.

Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your SDK directory. Do not add
SDK paths, `local.properties` or other machine-specific paths to project
files. The Gradle wrapper (`./gradlew`, or `gradlew.bat` in PowerShell and
cmd) downloads the right Gradle version for you.

The full repository check also needs Git, Python 3 and `jq`. It lists any
missing tool before it starts. Where `dpkg-deb` is not installed, it skips the
Debian package build check and says so; CI always runs that check. See
[Windows contributors](#windows-contributors) if you work on Windows.

### Build and run

```bash
git clone https://github.com/obiente/native.git
cd native

# Desktop: start the app from source
./gradlew :ui:run

# Desktop: build the runnable app image in ui/build/compose/binaries/main/app/
./gradlew :ui:createDistributable

# Android: build the debug APK
./gradlew :androidApp:assembleDebug

# Android: build and install the dev build on a connected device or emulator
./gradlew :androidApp:installDev
```

The Android `dev` build type installs as `dev.obiente.nextcloudnative.dev`
with the label `nati.ve Dev`. It can sit next to a signed release or nightly
install of `dev.obiente.nextcloudnative`. The `debug` build type uses the
release application ID, so Android refuses to install it over a signed build.

A desktop app started from source uses the same per-user data directory,
credential store and single-instance lock as an installed nati.ve. If an
installed copy is already running, the new launch only brings that window to
the front. Quit the installed app first, and sign in with a test account.

### Run tests

```bash
cargo test --locked                                 # Rust compiler and schema
./gradlew --no-daemon :ui:desktopTest               # shared and desktop tests
./gradlew --no-daemon :androidApp:testDebugUnitTest # Android unit tests
./gradlew --no-daemon :contractAcquisition:test     # contract acquisition
```

Focused runs are faster while you iterate, for example
`./gradlew :ui:desktopTest --tests '*LoginAttemptStateTest'`.

### Run repository checks

```bash
bash tools/check-repository.sh
bash tools/check-kotlin-architecture.sh   # included above; quick on its own
node tools/check-markdown-links.mjs       # included above; quick on its own
```

`check-repository.sh` runs text hygiene, Kotlin architecture and file-size
limits, machine-path and credential scans, changelog fragment validation,
Markdown link checks and the release tooling tests.

## Which checks to run

The table in [AGENTS.md section 12](AGENTS.md#12-development-and-validation)
lists the minimum evidence for each kind of change. Use it to choose your
checks; it is not repeated here.

When a change affects every build target, run the same baseline as CI:

```bash
cargo test --locked
./gradlew --no-daemon \
  :contractAcquisition:test \
  :ui:desktopTest \
  :androidApp:testDebugUnitTest \
  :ui:createDistributable \
  :androidApp:verifyReleaseLintGate \
  :androidApp:assembleDebug
bash tools/check-repository.sh
```

Before you ask for review, run every check relevant to the platforms you
changed and make sure the [Build and test workflow](.github/workflows/ci.yml)
passes. CI skips builds that a change cannot affect, so documentation-only
changes do not rebuild the apps. Packaging, emulator, server-companion,
website and release changes have extra checks, described in their own files
and workflows.

For maintenance changes, measure the work removed as well as the result. Cache
tests cover repeated warm reads without durable index writes, concurrent
preview misses with one producer, and indexed offline status lookups. These
synthetic checks guard specific regressions; they do not replace device
timing, memory, battery, packaging and lifecycle validation. Do not enable
release shrinking or change supported artifact behavior only to improve a
source-size metric.

## Windows contributors

- Run the `tools/*.sh` scripts from Git Bash. Use `gradlew.bat` from
  PowerShell or cmd, or `./gradlew` from Git Bash.
- On Windows, `:ui:createDistributable` also builds the Rust Explorer
  registration helper for the `x86_64-pc-windows-msvc` target, so you need
  Rust with the MSVC toolchain.
- The full desktop suite and repository checks create temporary symbolic
  links for path-safety and Linux service fixtures. Run them from a terminal
  that is allowed to create symbolic links. Without that permission, Windows
  reports that a required privilege is not held, and Node.js reports `EPERM`.
  These are fixture setup failures, not passing safety checks. Keep the tests
  enabled: replacing links with ordinary files would skip the behavior they
  verify.
- Pass `--no-daemon` to Gradle so tests do not reuse a daemon that was started
  without the symbolic-link privilege.
- Install jq with `winget install --exact --id jqlang.jq --source winget`, then
  reopen the terminal so the new PATH is picked up. Release scripts normalize
  native jq line endings; `bash tools/test-jq-output-portability.sh` checks
  this.
- `tools/check-repository.sh` runs from Git Bash. It skips only the Debian
  package build check, which needs `dpkg-deb`; CI runs that part. To run it
  locally too, use a WSL distribution or a Linux container with Debian
  packaging tools installed.
- `tools/android-emulator.sh`, `tools/deploy-local.sh` and the wireless ADB
  deployment helpers are written for Linux hosts.

## Troubleshooting

- **Kotlin compiler runs out of memory.** CI runs Gradle with
  `--max-workers=1` and a Kotlin daemon heap between 4 and 8 GiB so Android and
  desktop sources do not compile at the same time. Locally, retry with
  `--max-workers=1 -Pkotlin.daemon.jvmargs=-Xmx6g` (adjust the size to your
  machine).
- **`SDK location not found` or Android tasks fail.** Set `ANDROID_HOME` or
  `ANDROID_SDK_ROOT`, and install Platform 36 and Build Tools 35.0.0, for
  example with `sdkmanager "platforms;android-36" "build-tools;35.0.0"`.
- **Desktop app from source does not open a window.** Another nati.ve
  instance is running for your user. Quit it and run `./gradlew :ui:run`
  again.
- **`EPERM` or "a required privilege is not held" on Windows.** See
  [Windows contributors](#windows-contributors).
- **Text hygiene check fails.** Repository-authored text uses ordinary ASCII
  punctuation; normal UTF-8 letters and translations are fine. Replace smart
  quotes, typographic dashes, Unicode ellipses, Unicode minus signs, no-break
  spaces and invisible formatting characters.
- **Kotlin file-size check fails.** New production files must stay at or
  below 800 lines and test files at or below 1,200. Split by owner as
  described in [AGENTS.md](AGENTS.md#code-ownership-and-file-boundaries); do
  not raise `tools/kotlin-file-size-baseline.txt`.
- **CI asks for a changelog fragment.** Add one file under
  `changes/unreleased/`. See [changes/README.md](changes/README.md).

## Synthetic UI captures

Synthetic Compose capture scenarios live in `ui/src/commonTest`. Desktop
capture entry points and fixture resources live in `ui/src/desktopTest`. The
capture Gradle tasks use the desktop test compilation and its runtime
dependencies. Keep fixtures out of production source sets, and update
`tools/marketing-capture-inputs.txt` when you move capture inputs. Existing
screenshot manifests describe the sources at capture time; moving fixtures
does not make an old capture freshly rendered. The
[website guide](website/README.md) covers capture builds.

## Isolated Android emulator tests

`tools/android-emulator.sh` gives each concurrent worktree its own Android data
directory, ADB port and visible emulator window. It finds the SDK through
`ANDROID_SDK_ROOT` or `ANDROID_HOME`; no SDK path is stored in the project. It
uses host GPU acceleration by default. Headless CI hosts can select a
supported software backend with `NC_NATIVE_EMULATOR_GPU`.

Install the API 36 AOSP x86_64 system image
(`system-images;android-36;default;x86_64`). Then give each concurrent test
its own slot (0 to 63):

```bash
tools/android-emulator.sh start files-change 0 --fresh
tools/android-emulator.sh start media-change 1 --fresh

./gradlew :androidApp:assembleDebug
tools/android-emulator.sh smoke files-change
tools/android-emulator.sh smoke media-change

tools/android-emulator.sh stop files-change
tools/android-emulator.sh stop media-change
```

The window opens visibly by default and still works for scripted ADB checks.
Add `--headless` only for unattended runs without a desktop session. Run the
script without arguments to see all commands.

Contract parsing in JVM modules must also work on Android. The synthetic
`AppOwnedOpenApiAndroidInstrumentedTest` checks class initialization and
rejects foreign or malformed server authorities without contacting a server.
Run it on an isolated emulator after you build and install both test
artifacts:

```bash
./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
serial="$(tools/android-emulator.sh serial files-change)"
adb -s "$serial" install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s "$serial" install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
adb -s "$serial" shell am instrument -w \
  -e class dev.obiente.nextcloudnative.AppOwnedOpenApiAndroidInstrumentedTest \
  dev.obiente.nextcloudnative.test/androidx.test.runner.AndroidJUnitRunner
```

Smoke reports under `build/reports/android-emulator/` are local build output.
Do not commit them.

For authenticated compatibility and write-path testing, use the disposable
[real Nextcloud compatibility instance](integration/nextcloud-demo/README.md).
It creates a synthetic account, bounded fixtures, a private development CA,
and an explicit per-app Android write scope, without copying a personal
desktop session.

## Test account safety

Use a synthetic account on an isolated test server with disposable data for
authenticated tests. Do not import credentials from a personal desktop session
or test changes against a production account.

- Never print, record or commit credentials or session material.
- Never use personal filenames, contacts, messages, photos, server addresses,
  responses, screenshots or UI dumps as fixtures or review artifacts.
- Keep write-path tests explicit, bounded, revision-guarded and isolated from
  real data.
- Stop before any action whose effect on the server is uncertain.

## Card interaction guardrail

- A card does one primary thing: open or select its content.
- Never put rename, delete, move, retry, lifecycle or other secondary action
  buttons directly in a card's content area.
- Put secondary actions in `NextcloudCardOverflow`. Touch users must be able
  to open the same menu with a long press through `nextcloudCardInteractions`.
- Mark destructive actions visually and put them behind a separate
  confirmation.
- Inline controls are allowed only when they are the card's content, such as
  an ingredient checklist, a playback control or an editable form field.

## Human responsibility and attribution

You are responsible for every line you submit, including code, documentation
and tests prepared with AI assistance. You must understand the change well
enough to explain, defend, debug and modify it.

AI tools are assistants, not co-authors:

- Do not add an AI tool in a `Co-authored-by` trailer.
- Do not add or fabricate a person's authorship, approval, signature or
  certification trailer. Only that person can provide it.
- Keep existing attribution from other contributors.
- You may disclose AI use in the pull request description or with an optional
  `Assisted-by: Tool[:model]` trailer. Disclosure is appreciated but not
  required.
- Do not include prompts, credentials, private account data or sensitive
  context in a disclosure.

The idea, intent, decisions and communication must remain yours. AI agents may
work only on a concrete task under active human guidance. They may not claim
issues, create or publish contributions, choose project direction, or speak
for you on their own.

## Pull requests

- Keep changes focused and explain the user-facing outcome.
- Add one small file under `changes/unreleased/` for every pull request. Use a
  user-facing category for product changes, or an `internal` fragment with
  `user-facing: no` for maintenance that should not appear in release notes.
  See [changes/README.md](changes/README.md) for the format.
- Add regression tests for bug fixes and contract tests for API behavior.
- Name the server versions and apps you tested, without exposing account data.
- Include screenshots of visible UI changes on each affected form factor.
- Call out destructive or permission-sensitive paths explicitly.
- Update every document that describes the behavior you changed, as required
  by [AGENTS.md](AGENTS.md#documentation-synchronization).
- Do not commit build output, IDE state, crash dumps, local credentials or
  signing material.
- If AI helped, make sure the pull request still reflects your own reasoning
  and that you can answer reviewer questions without relying on the tool.

By contributing, you agree that your contribution is licensed under the
GNU Affero General Public License, version 3 or later.
