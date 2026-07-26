# Contributing to Nextcloud Native

Nextcloud Native is an independent Obiente project. Contributions are welcome,
especially protocol research, reusable semantic components, accessibility
improvements, compatibility fixtures, and tests against different Nextcloud
versions.

## Before you start

- Discuss large architectural changes in an issue before investing heavily.
- Never include real server URLs, usernames, app passwords, share tokens,
  filenames, message bodies, or private API responses in issues or fixtures.
- Prefer reusable semantic behavior over app-ID conditionals. A small
  app-specific adapter is appropriate when it adds verified behavior that
  cannot be inferred safely.
- Reads may be explored against a test server. Writes require an explicit,
  reviewed contract and tests for permission, conflict, and failure behavior.
- Read [AI_POLICY.md](AI_POLICY.md) before using AI assistance. Contributions
  must be human-led; autonomous agent submissions are not accepted.

## Human authorship and DCO sign-off

The human contributor is responsible for every line they submit, including
code, documentation, and tests prepared with AI assistance. You must understand
the change well enough to explain, defend, debug, and modify it.

Every submitted commit must include your human `Signed-off-by` trailer under
the [Developer Certificate of Origin](https://developercertificate.org/):

```bash
git commit --signoff
```

Cryptographic commit signing and DCO sign-off are separate. Follow the
repository's configured signing policy in addition to using `--signoff`.

AI tools are assistants, not co-authors:

- Do not add an AI tool through a `Co-authored-by` trailer.
- An AI tool must not add or fabricate your `Signed-off-by` trailer. Add it only
  after you have reviewed and accepted the complete commit.
- AI use may be disclosed in the PR description or with an optional
  `Assisted-by: Tool[:model]` trailer. Disclosure is appreciated but not
  required.
- Do not include prompts, credentials, private account data, or sensitive
  context in a disclosure.

The idea, intent, decisions, and communication must remain yours. AI agents may
work only on a concrete task under active human guidance. They may not
autonomously claim issues, create or publish contributions, choose project
direction, or speak on your behalf.

## Card interaction guardrail

- A card surface performs one primary action only: open or select its content.
- Never place rename, delete, move, retry, lifecycle, or other secondary action
  buttons directly in a card's content area.
- Put secondary actions in `NextcloudCardOverflow`. The same menu must open from
  a long press through `nextcloudCardInteractions` for touch users.
- Keep destructive actions visually marked and behind a separate confirmation.
- Inline controls are allowed only when they are the card's content itself,
  such as an ingredient checklist, playback control, or editable form field.

## Development setup

Install JDK 21, Rust stable, Node.js 20 or newer, and Android SDK Platform 36
with Build Tools 35.0.0. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your local
SDK directory. Do not add local SDK paths to project files.

Run the complete local verification suite:

```bash
cargo test --locked
./gradlew --no-daemon \
  :contractAcquisition:test \
  :ui:desktopTest \
  :androidApp:testDebugUnitTest \
  :ui:createDistributable \
  :androidApp:assembleDebug
bash tools/check-repository.sh
```

Focused tests are useful while iterating, but the full suite must pass before a
pull request is ready for review.

Repository-authored text uses ordinary ASCII punctuation. Run
`bash tools/check-repository.sh` to catch smart quotes, typographic dashes,
Unicode ellipses, Unicode minus signs, no-break spaces, and invisible
formatting characters. Normal UTF-8 letters and translations remain supported.

### Isolated Android emulator tests

The emulator helper gives each contributor, worktree, or automated agent a
separate Android data directory, ADB port, and visible emulator window. It discovers the SDK through
`ANDROID_SDK_ROOT`, `ANDROID_HOME`, or the shared `/opt/android-sdk` convention;
no SDK path is stored in the project. The default uses host GPU acceleration;
headless CI hosts may select a supported software backend with
`NC_NATIVE_EMULATOR_GPU`.

Install the API 36 AOSP x86_64 system image, then assign a unique slot to each
concurrent test:

```bash
tools/android-emulator.sh start files-change 0 --fresh
tools/android-emulator.sh start media-change 1 --fresh

./gradlew :androidApp:assembleDebug
tools/android-emulator.sh smoke files-change
tools/android-emulator.sh smoke media-change

tools/android-emulator.sh stop files-change
tools/android-emulator.sh stop media-change
```

The window opens visibly by default and remains available to scripted ADB
checks. Add `--headless` only for unattended runs without a desktop session.

### Shared account safety

An emulator may reuse the account already stored by the desktop app when
authenticated read behavior must be tested:

```bash
tools/android-emulator.sh install files-change
tools/android-emulator.sh reuse-desktop-session files-change
```

This command streams the desktop keyring entry directly into the private data
directory of that debuggable emulator app. It is re-encrypted with the
emulator's Android Keystore key and the temporary import is immediately
deleted. The resulting session is forced into read-only test mode at the
Android HTTP boundary.

Treat this as a strict safety boundary:

- Never inspect, print, copy, record, or commit the imported credentials.
- Never use personal filenames, contacts, messages, photos, server addresses,
  responses, screenshots, or UI dumps as fixtures or review artifacts.
- Never attempt uploads, edits, deletes, moves, shares, app administration,
  calls, messages, sync writes, or any other cloud mutation with a shared
  account.
- Never bypass or weaken read-only test mode. Write-path tests require a
  synthetic account on an isolated test server containing disposable data.
- Stop and ask before any action whose server-side effect is uncertain.

The smoke report contains synthetic screenshots, UI hierarchies, launch timing,
and logcat output under `build/reports/android-emulator/`. Never run it with a
real account or copy those generated artifacts into the repository.

## Pull requests

- Keep changes focused and explain the user-facing outcome.
- Add one small file under `changes/unreleased/` for every pull request. Use a
  user-facing category for product changes or an explicit `internal` fragment
  with `user-facing: no` for maintenance that should not appear in release
  notes. See [changes/README.md](changes/README.md) for the format.
- Add regression tests for bug fixes and contract tests for API behavior.
- Describe any server versions and apps tested without exposing account data.
- Include screenshots for visible UI changes on the affected form factors.
- Call out destructive or permission-sensitive paths explicitly.
- Do not commit generated build output, IDE state, crash dumps, local
  credentials, or signing material.
- If AI helped, ensure the PR still reflects your own reasoning and that you can
  answer reviewer questions without delegating your understanding to the tool.

By contributing, you agree that your contribution is licensed under the
GNU Affero General Public License, version 3 or later.
