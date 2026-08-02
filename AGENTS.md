# Nextcloud Native agent and contributor guide

This file is the implementation contract for automated agents and contributors
working in this repository. It applies to the entire tree unless a more
specific `AGENTS.md` narrows a rule for a subdirectory.

## 1. Mission and current phase

Nextcloud Native is an independent Obiente project building one coherent,
native client for a complete Nextcloud account. It is not a launcher for web
pages and it is not an API response browser.

The next phase has four priorities, in this order:

1. Make the shared transport, account identity, cache, and state model
   trustworthy.
2. Deliver best-in-class Files, transfer, media backup, offline, and sync
   foundations without silent data loss.
3. Turn installed Nextcloud apps into useful native experiences through
   verified contracts, reusable semantic components, and small reviewed
   enhancements where inference is insufficient.
4. Productize Android and desktop with platform-native behavior, then extend
   the same domain rules to iOS without pretending every platform is identical.

Feature count never outranks correctness, privacy, accessibility, battery use,
or preservation of originals.

All work must follow [AI_POLICY.md](AI_POLICY.md). The human supplies the idea,
intent, decisions, and active guidance. Agents may assist only inside that
concrete scope and may not operate as autonomous contributors.

## 2. Sources of truth

Use these documents for different decisions:

- GitHub issues and the public GitHub Project are the source of truth for
  current work, priority, ownership, and completion.
- [AI_POLICY.md](AI_POLICY.md) defines human direction, accountability,
  sign-off, and optional disclosure for AI-assisted contributions.
- [ROADMAP.md](ROADMAP.md) defines the dependency order, acceptance gates, and
  long-term product scope. It is not a claim that every listed feature exists.
- [ADAPTER_ARCHITECTURE.md](ADAPTER_ARCHITECTURE.md) defines transport,
  repositories, adapters, actions, caching, and mutation boundaries.
- [DYNAMIC_APP_DESCRIPTOR.md](DYNAMIC_APP_DESCRIPTOR.md) defines dynamic
  discovery and execution evidence.
- [NATIVE_SCHEMA.md](NATIVE_SCHEMA.md) defines the platform-neutral renderer
  contract and confidence model.
- [PLATFORMS.md](PLATFORMS.md) defines shared and platform-specific ownership.
- [COMPATIBILITY.md](COMPATIBILITY.md) records verified server and app
  compatibility.
- [CONTRIBUTING.md](CONTRIBUTING.md) contains contributor setup and emulator
  safety instructions.
- [SECURITY.md](SECURITY.md) defines private vulnerability reporting.

Do not copy issue status into a second local tracker. When the assigned task
includes GitHub publication, update the issue and Project as work advances.
Otherwise report the required status change to the coordinating maintainer.

## 3. What "native" means

A finished feature must help a person complete the workflow for which the
server app exists.

- Do not embed a Nextcloud app's web interface as the automatic fallback.
- Do not present raw JSON, XML, DAV properties, generic key/value lists, or
  endpoint names as finished product UX.
- Do not enlarge a phone screen and call it a desktop experience.
- Prefer reusable semantic surfaces: file browsers, galleries, people views,
  mailboxes, calendars, boards, tables, forms, conversations, recipes, media
  players, dashboards, settings, charts, and inspectors.
- Choose the useful entry point. Recipes open on recipes, mail opens on a
  mailbox, a table opens on its rows, and a board opens on its lanes and cards.
- Show dense summaries first and reveal diagnostics or less important fields
  on demand.
- Use the operating system for secure storage, files, sharing, notifications,
  background work, media controls, permissions, and calling integrations.

Shared domain logic does not require identical layouts:

- Mobile uses touch-sized controls, system back, correct insets, compact
  hierarchy, rotation and process restoration, sheets, and progressive layouts
  on larger screens.
- Desktop uses resizable multi-pane or master-detail layouts where useful,
  keyboard and pointer navigation, selection, context menus, denser tables,
  persistent inspectors, and window-aware state.
- Accessibility includes meaningful names, focus order, keyboard operation,
  scalable text, contrast, reduced-motion behavior, and status cues that do not
  rely on color alone.

## 4. Dynamic app intelligence

Dynamic support is evidence-driven. Use this order:

1. Authenticated capabilities, app navigation, and exact server/app version.
2. An officially advertised OpenAPI, OCS, DAV, or other protocol contract.
3. A contract from the exact signed Nextcloud App Store package after
   certificate, signature, app ID, and version verification.
4. An exact App Store-linked source tag only through the documented lower-trust
   fallback and identity checks.
5. Approved successful `2xx` read observations for read-only shape inference.
6. A reviewed, versioned adapter enhancement for behavior that cannot be
   inferred safely.

Rules:

- Deterministic contracts and typed models outrank naming heuristics.
- Reusable semantic inference outranks app-ID conditionals.
- A small app-specific adapter is valid when it provides verified behavior,
  protocol compatibility, or substantially better UX that cannot be expressed
  generically.
- Prefer reusable concepts inside adapters: parent-child relationships,
  record collections, money, dates, participants, status, attachments,
  pagination, settings, and mutations.
- AI or heuristic analysis may propose labels, field roles, relationships, and
  component choices. It may never invent an endpoint, method, path parameter,
  payload field, permission, target identity, or idempotency guarantee.
- Observing a successful read does not authorize a write.
- When evidence is insufficient, provide an honest native read-only fallback
  with useful diagnostics. Do not fabricate a nearly-working action.

## 5. Layer and type boundaries

Maintain this direction:

```text
platform-native Compose UI
        |
typed repositories and use cases
        |
versioned protocol and semantic adapters
        |
shared authenticated transport
        |
Nextcloud server and installed apps
```

- UI consumes typed state and invokes typed actions. It does not parse protocol
  payloads, construct URLs, substitute IDs, or interpret HTTP headers.
- Repositories own cached state, pagination, refresh, local dirty state,
  cursors, pending work, retries, and conflicts.
- Adapters own protocol translation, version compatibility, capabilities,
  permissions, and semantic relationships.
- Transport owns authentication, same-origin redirects, bounded bodies,
  streaming, DAV verbs, response classification, and secret redaction.
- Platform services own keychains/keystores, filesystem providers, background
  schedulers, notifications, share/open-with, RTC, and system media controls.
- Shared modules must not import Android, Apple, Windows, macOS, or Linux APIs.
- Do not introduce `Any`, unchecked casts, or untyped maps as protocol or domain
  models. Boundary parsing may be flexible, but it must validate into typed
  values before entering repositories or UI.

## 6. Resource identity and navigation context

Every request must bind the correct resource at the correct level.

- Keep account, app, container, parent, item, attachment, and action identities
  distinct.
- Never substitute a bill ID for a project ID, a row ID for a table ID, a card
  ID for a board ID, or a message ID for a conversation token.
- Resolve nested actions from typed relationships and active navigation
  context, not from whichever field is named `id`.
- Required path parameters must be resolved before an action is shown as
  available.
- Parent lists, child lists, detail records, and action targets must not become
  separate dead-end navigation loops.
- Account-scoped local identities must use a non-secret digest or random local
  ID, never a raw server URL, username, or app password.
- Disable writes while identity, permissions, version, or post-mutation state
  is stale or ambiguous.

## 7. Writes, destructive actions, and retries

Do not enable a write until all of the following are known:

- authoritative operation provenance and supported version range;
- current permission/capability;
- exact account and target identity;
- validated request shape;
- confirmation and risk level;
- conflict/precondition behavior;
- idempotency and retry policy;
- offline policy;
- ambiguous-result recovery and postcondition verification.

Additional rules:

- Use ETags, sync tokens, stable references, idempotency keys, or protocol
  preconditions where the upstream contract supports them.
- Never blindly retry a non-idempotent action after an unknown network result.
- Delete, merge, send, share, app lifecycle, and strict administration actions
  require action-specific recovery.
- A `2xx` response is not always completion. Re-read the authoritative state
  when the protocol requires reconciliation, and verify the requested
  postcondition rather than accepting an unchanged cached response.
- Preserve originals by default. Image/document edits create a new file unless
  the user explicitly chooses an ETag-guarded replacement.
- Strict administrator operations use explicit authenticated handoff when an
  app password is insufficient. Never store or reinterpret the primary account
  password.

## 8. Cache, offline, transfer, and sync rules

- Scope every record, cache key, transfer, and pending operation to the local
  account identity.
- Cache keys include relevant remote identity, ETag/generation, variant,
  transform, and decoder information.
- Paint useful cached content first, then refresh in place. Do not replace a
  valid screen with a long blocking spinner while contracts are rediscovered.
- Persist verified app contracts by app ID and exact version, and invalidate
  them on upgrades or provenance changes.
- Write downloads and generated media to temporary files, validate them, then
  atomically promote them.
- Never expose a partial file as complete.
- Keep previews and temporary originals bounded and evictable.
- Treat offline pins, local drafts, conflict copies, upload snapshots, and
  user-created files as stronger retention classes than previews.
- "Viewed," "cached," "available offline," "uploaded," and "synchronized" are
  different states and must never be presented as synonyms.
- Transfer history must be paged or virtualized. Large queues must not require
  thousands of live UI nodes.
- Sync work must survive process death and reboot, make conflicts visible, and
  preserve a durable recovery path. Never claim continuous or two-way sync
  before its crash and data-loss gates pass.

## 9. UX guardrails

- A card has one primary action: open or select its content.
- Put rename, move, delete, retry, share, lifecycle, and similar secondary
  actions in the shared overflow/context menu. The same menu must be reachable
  by long press on touch devices.
- Destructive actions need explicit visual treatment and a separate
  confirmation.
- Inline controls are appropriate only when they are the content itself, such
  as playback, an ingredient checklist, a form field, or a board interaction.
- Forms derive defaults and hidden IDs from context. Do not ask users to type
  source IDs, destination IDs, account IDs, or remote paths that the app can
  resolve.
- Use native pickers for local sources and a native remote file/folder browser
  for Nextcloud destinations.
- Empty, loading, offline, permission-denied, unsupported, stale-cache, and
  partial-failure states must each explain what happened and what the user can
  safely do next.
- Charts, metadata summaries, and diagnostics are collapsible when they would
  displace the primary task.
- Headers and content respect safe drawing areas. Landscape and large-screen
  layouts must remain scrollable and interactive.

## 10. Lifecycle and cross-platform state

- Navigation, scroll position, selections, editor drafts, pending actions,
  upload state, and post-mutation recovery must survive the lifecycle that
  matters for that platform.
- On Android, test rotation, activity recreation, process restoration,
  permissions, background/foreground transitions, and system back.
- Do not keep critical recovery only in a screen-local coroutine or transient
  composable state.
- On desktop, test resize, minimum usable window size, pointer/keyboard focus,
  multiple panes, and relaunch persistence where the state is durable.
- Apply EXIF orientation exactly once: server-rendered previews are already
  oriented, while original encoded bytes, zoom, edit input, and exports must
  normalize orientation while preserving alpha and color space.
- Media playback exposes system notifications and previous/next/play/pause
  controls when the platform supports them.

## 11. Data and privacy safety

### Deterministic fixtures

- CI, screenshots, documentation, and regression tests use synthetic data.
- Do not commit credentials, server URLs, usernames, account identifiers,
  personal filenames, messages, contacts, photos, responses, UI dumps, logs,
  or machine-specific paths.
- Public screenshots must be produced from the real app UI with mock services,
  not hand-drawn imitations and not a personal account.

### Isolated write testing

- Write-path tests use disposable synthetic accounts on isolated test servers.
- Fixtures cover permissions, stale preconditions, conflicts, quota/disk
  failures, throttling, cancellation, process death, and ambiguous results.
- Tests clean up only the disposable data they created.

### Optional real-account audit

A maintainer may explicitly authorize read-only verification against an
existing account. In that mode:

- enforce read-only behavior at the transport boundary;
- do not upload, edit, delete, move, share, send, call, administer, or start
  sync writes;
- do not inspect, print, record, or copy credentials or private response data;
- do not capture screenshots, UI hierarchies, logcat, or reports containing
  account data;
- stop when the side effect of a request is uncertain.

Real-account inspection is supplementary evidence. It never replaces
deterministic fixtures, contract tests, or the platform test matrix.

## 12. Development and validation

The supported build JDK is 21. Respect the Gradle wrapper and repository
toolchain configuration. Do not bypass the supported JDK or add a contributor's
SDK/JDK path to repository configuration.

Repository-authored prose, source comments, and UI copy use ordinary ASCII
punctuation. The Rust text-hygiene check rejects smart quotes, typographic
dashes, Unicode ellipses, Unicode minus signs, no-break spaces, and invisible
formatting characters while allowing normal UTF-8 letters and translations.
When code must recognize such input from an external source, express the
codepoint with an ASCII Unicode escape and test the behavior explicitly.

Use focused tests while iterating, then validate in proportion to the change:

| Change | Minimum evidence before review |
| --- | --- |
| Rust schema/compiler | Focused and full Rust tests; adversarial or golden fixtures |
| Contract acquisition | Exact-version, signature, provenance, malformed, and downgrade-negative tests |
| Shared domain/UI | Focused desktop tests plus Android compile or unit coverage |
| Android integration | Unit test, relevant visible emulator/instrumented test, APK build |
| Desktop integration | Desktop tests and distributable; affected OS packaging when applicable |
| Protocol adapter | Official-source links, versioned fixtures, permissions and error cases |
| Cache/sync/write path | Restart, conflict, cancellation, ambiguous result, disk/quota, and network faults |
| Responsive UI | Compact phone, large phone/tablet, and desktop evidence as affected |
| Website/docs | Locked install, tests, client/SSR/prerender build, privacy-safe assets |
| Release/signing | Dedicated signing, lint, fingerprint, artifact, and upgrade gates |

Common verification commands:

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

Use the isolated visible emulator workflow in
[CONTRIBUTING.md](CONTRIBUTING.md) for lifecycle and visual QA. Each concurrent
agent or worktree uses a separate emulator slot. A phone is supplementary
final-device QA, not the only way to test.

## 13. Issue, branch, PR, and Project discipline

- Start nontrivial work from a focused issue with a user problem, acceptance
  criteria, priority, area/platform, and milestone.
- Do not autonomously discover or claim work. Every implementation must trace
  back to a concrete human request or a human-approved issue and scope.
- Keep one independently reviewable outcome per branch and pull request.
- A delegated implementation or review subtask does not by itself authorize a
  commit, push, PR, issue or Project edit, label or milestone change, review
  comment, thread resolution, merge, tag, release, or deployment. Follow the
  publication scope given by the maintainer or coordinating agent.
- Amending, rebasing, force-pushing, deleting branches/tags/releases, or
  rewriting shared history requires explicit authority for that exact action.
- Use Conventional Commit subjects.
- Authorship and DCO are submission metadata, not code-review findings. Automated
  reviewers must not post review comments about an author, committer, or missing
  sign-off. Repository checks and maintainers own those submission gates.
- Never infer Git identity from an agent sandbox, a local worktree, synthetic
  patch commits, or rewritten review fixtures. If an operational workflow must
  inspect PR commit identity, it must use the exact remote PR commits returned by
  GitHub's API. If that evidence is unavailable or inconsistent, leave the
  metadata decision to a maintainer rather than reporting a finding.
- The human contributor remains the author and is responsible for the complete
  change. Automated tools must not become author or committer and must not add
  bot/assistant `Co-authored-by` trailers.
- DCO sign-off is controlled by the human contributor. When a maintainer or a
  protected repository check requires it, an agent may add the human's
  `Signed-off-by` trailer only after that human explicitly confirms review and
  authorizes the sign-off. An automated reviewer must never request an agent to
  manufacture or repair a human sign-off. Cryptographic commit signing remains
  a separate repository requirement.
- Optional AI disclosure is appreciated in the PR description or an
  `Assisted-by: Tool[:model]` trailer, but it is not mandatory. Never disclose
  private prompts or account data.
- Preserve external contributors' real authorship.
- Do not include internal tracking notes, generated design explorations,
  private test data, local credentials, or unrelated context in commits, PRs,
  issues, reviews, or release notes.
- Use `Closes #N` only when all acceptance criteria are implemented and
  verified. Use `Advances #N` for a partial but useful slice.
- Within the granted GitHub publication scope, keep the linked Project status
  accurate: active work is `In Progress`; implemented but unverified work is
  not `Done`; completed acceptance moves to `Done`.
- PR descriptions state the user-facing outcome, exact validation, tested
  platforms/server-app versions, risks, limitations, and visual evidence for
  affected form factors.
- Evaluate automated review findings against actual behavior. Do not apply them
  blindly, and do not post noisy validation or identity details.
- Do not merge with unresolved correctness findings, failing exact-head checks,
  incomplete acceptance evidence, or unrelated branch history.
- Keep `main` buildable. Merge updated `main` into longer-lived work before
  final validation.

## 14. Review priorities

Review for concrete user and system harm, especially:

- silent data loss, overwrite, duplicate mutation, or destructive retry;
- credential, token, URL, account, or private-content exposure;
- cross-account cache or identity collisions;
- wrong parent/child/resource ID binding;
- unresolved required path or payload values;
- permission or capability bypass;
- stale reads accepted as successful write reconciliation;
- lifecycle loss of navigation, drafts, transfers, or recovery state;
- blocking contract acquisition, unbounded lists, memory/resource leaks, or
  main-thread network/decoding;
- incorrect media orientation, alpha, color profile, original-quality, RAW,
  video, or Live Photo handling;
- behavior that is only an API browser instead of a useful native workflow;
- platform claims or structured metadata that describe planned work as shipped.

Report actionable findings with a concrete failure path and the smallest safe
correction. Avoid style-only churn unless it affects accessibility,
maintainability, or correctness.

## 15. Release rules

- All versions remain prereleases until the product gates for stable are met.
- A release tag must correspond to reviewed `main` and the canonical version.
- Build platforms independently so one failed platform does not discard
  successful artifacts, but publish only under the repository's release quorum
  and integrity rules.
- Android release artifacts use the protected signing environment and verified
  certificate fingerprint. Never place signing secrets in the repository.
- Release notes explain product changes and known limitations concisely.
  Internal workflow mechanics belong in CI logs, not user-facing notes.
- Update [CHANGELOG.md](CHANGELOG.md), compatibility data, and public roadmap
  state from reviewed evidence.

## 16. Definition of done

A change is done only when:

- the useful native workflow works at the correct entry point;
- identity, permissions, version, conflicts, and failure behavior are explicit;
- relevant lifecycle and form factors are tested;
- cache/offline behavior is truthful;
- accessibility semantics are present;
- deterministic tests and repository checks pass;
- no private or machine-specific data entered the diff or artifacts;
- the issue, PR, Project, compatibility record, and public claims accurately
  reflect what was delivered.
