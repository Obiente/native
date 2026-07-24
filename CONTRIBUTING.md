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

Install JDK 21, Rust stable, and Android SDK Platform 36 with Build Tools
35.0.0. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your local SDK directory.
Do not add local SDK paths to project files.

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

## Pull requests

- Keep changes focused and explain the user-facing outcome.
- Add regression tests for bug fixes and contract tests for API behavior.
- Describe any server versions and apps tested without exposing account data.
- Include screenshots for visible UI changes on the affected form factors.
- Call out destructive or permission-sensitive paths explicitly.
- Do not commit generated build output, IDE state, crash dumps, local
  credentials, or signing material.

By contributing, you agree that your contribution is licensed under the
GNU Affero General Public License, version 3 or later.
