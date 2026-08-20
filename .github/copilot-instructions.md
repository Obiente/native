# Nextcloud Native repository instructions

**Last reviewed: 2026-08-20.** Architecture, toolchains, checks, and release
state may have changed. [`AGENTS.md`](../AGENTS.md) is the authoritative
repository maintenance contract. Also check [`CONTRIBUTING.md`](../CONTRIBUTING.md)
and the [Build and test workflow](workflows/ci.yml) before changing code.

This file is a concise assistant-facing summary. When it conflicts with an
owning source file, manifest, workflow, or `AGENTS.md`, follow the owning source
and update this summary in the same change.

## Product and architecture

Nextcloud Native is an independent, adaptive native client for Nextcloud. It
renders native Compose experiences from typed APIs and semantic models; it does
not embed Nextcloud web pages. Prefer reusable behavior inferred from
capabilities, contracts, data shapes, and field semantics. Add app-specific
knowledge only when it provides verified behavior that cannot be expressed
safely by the generic runtime.

The main modules are:

- `src/` and `tests/`: Rust semantic compiler and platform-neutral schema.
- `contractAcquisition/`: verified contract discovery, acquisition, and cache.
- `ui/src/commonMain/`: shared Compose UI, typed feature models, adaptive
  renderer, and design system.
- `ui/src/androidMain/` and `ui/src/desktopMain/`: platform implementations for
  the shared UI boundary.
- `androidApp/`: Android launcher and platform services such as secure storage,
  WorkManager, MediaStore, notifications, sharing, and DocumentsProvider.
- `website/`: Vue/Vite project website and prerendered documentation.
- `server-companion/`: optional server-side companion work.

Respect the layering in `ADAPTER_ARCHITECTURE.md`: shared UI consumes typed
repositories and must not parse raw JSON/XML, construct endpoint URLs, or own
credentials and filesystem integration. Protocol adapters translate APIs;
platform services own transport execution and operating-system integration.

## Implementation guardrails

- Keep UI native, responsive, accessible, and appropriate to each form factor.
  Desktop layouts should use available width and desktop interaction patterns;
  mobile layouts must handle insets, rotation, back navigation, and touch.
- Prefer semantic, reusable components over app-ID conditionals or generic
  key/value data dumps. Unknown fields must be tolerated and missing fields
  handled explicitly.
- Treat capabilities and verified contracts as authoritative. Never invent an
  endpoint, payload field, permission, or successful mutation.
- Keep reads non-mutating. Require an explicit, verified contract for writes,
  preserve conflict tokens such as ETags, surface permission and failure
  states, and request confirmation for destructive or inferred actions.
- Preserve original user files unless replacement is explicitly requested.
  Keep caches bounded and account-scoped; do not allow one account's state or
  data to appear in another account.
- A content card has one primary open/select action. Put secondary actions in
  the shared overflow menu and long-press interaction. Confirm destructive
  actions separately.
- Keep credentials in platform secure storage. Restrict authenticated requests
  and redirects to the connected server origin unless the feature explicitly
  performs an external handoff.
- Tests, fixtures, screenshots, and documentation must use synthetic data.
  Never add real names, server URLs, account identifiers, credentials, tokens,
  private filenames, messages, API responses, device details, or machine-local
  paths.

## Toolchain and validation

Use JDK 21 and Rust stable. Android builds require SDK Platform 36 and Build
Tools 35.0.0. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` outside the repository;
never commit `local.properties`.

During development, run the smallest relevant tests, for example:

```bash
cargo test --locked <test-name>
./gradlew --no-daemon :ui:desktopTest --tests '*RelevantTest*'
./gradlew --no-daemon :androidApp:testDebugUnitTest --tests '*RelevantTest*'
```

Add regression tests for bug fixes and contract tests for protocol behavior.
Before declaring cross-platform application changes ready, run the baseline
suite:

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

Run every additional check owned by the changed platform, package, companion,
or website. A focused change may mark unrelated targets as not applicable, but
must not claim they passed.

For website changes, use the lockfile and verify the complete prerender:

```bash
npm ci --prefix website
npm run build --prefix website
bash tools/check-repository.sh
```

Default unit, contract, and repository checks must not depend on a live
Nextcloud account, network service, Android device, emulator, or
developer-specific state. Use synthetic fixtures and the repository's isolated
preview and capture paths. Keep explicitly opt-in interoperability, emulator,
and packaging checks separate, document their prerequisites, and use only
disposable synthetic accounts and data.
