# Changelog fragments

**Last reviewed: 2026-10-02.** Fragment fields, release preparation, and CI
enforcement may have changed. The
[`tools/changelog-fragments.mjs`](../tools/changelog-fragments.mjs) validator,
[Build and test workflow](../.github/workflows/ci.yml), and
[prerelease policy](../docs/releases.md) are the current sources of truth.

Every pull request that changes the repository adds one small fragment under
`changes/unreleased/`. Separate files let concurrent changes record release
history without editing the same `Unreleased` section in `CHANGELOG.md`.

Use a filename that is unique to the change. It may contain only lowercase
letters, digits and hyphens, must start with a letter or digit, and ends in
`.md`. Starting with the issue or pull request number keeps related entries
together:

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
`all`, `android`, `desktop`, `ios`, `linux`, `macos`, `website`, and `windows`;
separate several with a comma and one space (`android, desktop`), list each
only once, and do not combine `all` with another value.
Use `none` when either the issue or pull request does not exist, but always
provide at least one positive reference for release-facing `feature`, `fix`,
`security`, `platform`, and `docs` fragments. Only an `internal` fragment with
`user-facing: no` may use `none` for both references.

Internal maintenance still needs a fragment so automation does not have to
guess whether a missing entry was intentional:

```text
category: internal
issue: none
pull: none
platforms: all
user-facing: no

Repository checks now validate independent changelog fragments.
```

Validate and preview the current entries without modifying the repository:

```bash
node tools/changelog-fragments.mjs validate
node tools/changelog-fragments.mjs render
node tools/changelog-fragments.mjs render --include-internal
```

CI also runs `check-diff`, which requires a newly added fragment whenever a
change touches files outside `changes/`, and rejects edits to archived
fragments other than moving unreleased fragments into a version archive.
Dependabot changes are exempt.

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
