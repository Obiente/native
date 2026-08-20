# Changelog fragments

**Last reviewed: 2026-08-20.** Fragment fields, release preparation, and CI
enforcement may have changed. The
[`tools/changelog-fragments.mjs`](../tools/changelog-fragments.mjs) validator,
[Build and test workflow](../.github/workflows/ci.yml), and
[prerelease policy](../docs/releases.md) are the current sources of truth.

Every pull request that changes the repository adds one small fragment under
`changes/unreleased/`. Separate files let concurrent changes record release
history without editing the same `Unreleased` section in `CHANGELOG.md`.

Use a lowercase hyphenated filename that is unique to the change:

```text
changes/unreleased/218-raw-preview.md
```

The format is strict and intentionally small. Use ordinary ASCII punctuation;
normal UTF-8 letters remain valid for names and translated text:

```text
category: feature
issue: 85
pull: 218
platforms: android, desktop
user-facing: yes

Standalone RAW photos can now open when the server has no generated preview.
```

Allowed categories are `feature`, `fix`, `security`, `platform`, `docs`, and
`internal`. Use `security` for user-facing fixes to confidentiality, integrity,
authentication, signing, or other security boundaries. Allowed platforms are
`all`, `android`, `desktop`, `ios`, `linux`, `macos`, `website`, and `windows`.
Use `none` when either the issue or pull request does not exist, but always
provide at least one positive reference.

Internal maintenance still needs a fragment so automation does not have to
guess whether a missing entry was intentional:

```text
category: internal
issue: 103
pull: none
platforms: all
user-facing: no

Repository checks now validate independent changelog fragments.
```

Validate and preview the current entries without modifying the repository:

```bash
node tools/changelog-fragments.mjs validate
node tools/changelog-fragments.mjs render
```

At release time, prepare a concise draft from the same entries used by the
website:

```bash
node tools/changelog-fragments.mjs prepare-release \
  --version 0.2.0-alpha.1 \
  --output docs/release-notes/0.2.0-alpha.1.md
```

Review and curate the generated draft, especially its known limitations. Then
move the included fragments to `changes/archive/<version>/` and copy the
rendered categories into the new version section of `CHANGELOG.md`. The
published changelog and archived fragments remain immutable; only unreleased
fragments are edited as work progresses.
