# Prerelease policy

**Last reviewed: 2026-09-27.** The active version and release policy may have
changed. The `ncVersion*` values in [`gradle.properties`](../gradle.properties),
the [Publish prerelease workflow](../.github/workflows/prerelease.yml), and the
[latest releases](https://github.com/obiente/native/releases) are the current
sources of truth.

Curated releases use the `0.x.y` prerelease line. Every GitHub release must be
marked as a prerelease. The release workflow rejects stable versions and all
`1.0.0` or higher tags.

Supported versions use one of these forms:

- `0.x.y-alpha.n` for early testing
- `0.x.y-beta.n` for feature-complete testing
- `0.x.y-rc.n` for release candidates

The canonical product version lives in `gradle.properties`. Android and desktop
development builds read their defaults from there. `ncDesktopPackageVersion`
contains the numeric `0.x.y` portion because native desktop packagers do not
consistently accept SemVer prerelease suffixes.

Signed installable builds derive their monotonically ordered package versions
from the full Git history reachable from the immutable tagged commit. This
makes the shipped package identity reproducible from the tagged repository
instead of depending on mutable workflow-run metadata.

Android version codes use:

```text
20,000,000 + main history sequence * 10 + channel lane
```

Channel lanes are `1` for nightly, `2` for alpha, `3` for beta, and `4` for
release candidates. Desktop packages use the same source sequence and channel
lane, mapped into a native packager-compatible numeric version.

To reproduce the package identities for a tag:

```bash
source_sequence="$(git rev-list --count v0.2.0-alpha.1)"
tools/derive-android-version-code.sh "$source_sequence" alpha
tools/derive-desktop-package-version.sh "$source_sequence" alpha
```

The schema-1 Android and desktop update manifests keep their original top-level
key sets because installed clients parse them strictly. Do not add optional
fields to these documents. Publish future cumulative changelogs or other
extensible metadata as separately versioned sidecars, and test mutable channel
pointers against every supported client parser before promotion.

## Nightly builds and recovery

[Publish nightly](../.github/workflows/nightly.yml) starts after
[Build and test](../.github/workflows/ci.yml) completes successfully for a
trusted `push` to `main`. Pull-request builds and manually dispatched builds
do not qualify, even when they test the same commit. There is no scheduled
nightly timer.

Build concurrency is scoped by workflow, event, and PR number or ref. A new
run can cancel obsolete work for the same target, but PR and manual runs
cannot cancel the main push build used by the nightly publisher. PR numbers
keep different PRs isolated even when their event ref becomes `main` after
merging.

Before retrying, check the newest `push` build for `main` and compare its
source SHA with current `main`. Let any queued or running main push build
finish. If `main` has advanced, use its newer push build instead of rerunning
the older commit. Retry only the newest failed or cancelled run whose SHA
still matches `main`, rechecking immediately before retrying. Main push runs
still share a cancellation group, so coordinate retries with ongoing merges;
this check is not atomic with a new push. If the newest source build already
succeeded, inspect its nightly run instead of rerunning successful CI.

If the qualifying push build was cancelled or failed because of a transient
infrastructure problem, inspect the run, address that problem, then use
**Re-run all jobs** on the original push run. This preserves its source event
and commit. A deterministic source or workflow defect instead requires a
corrective commit merged into `main`; that new push must pass its own build.
Rerunning the original commit cannot include the correction.

Starting **Run workflow** creates a `workflow_dispatch` run and will not
publish a nightly. Retrying the skipped nightly alone does not make an
unsuccessful source build eligible.

A successful eligible build starts the existing signing, artifact verification,
release quorum, and channel-promotion gates; it does not bypass them. A curated
prerelease already tagged at that source commit suppresses nightly publication.

## Creating a prerelease

1. Update the three `ncVersion*` development defaults in `gradle.properties`.
2. Prepare the release-note draft from the validated unreleased fragments:

   ```bash
   node tools/changelog-fragments.mjs prepare-release \
     --version 0.2.0-alpha.1 \
     --output docs/release-notes/0.2.0-alpha.1.md
   ```

3. Review and curate the draft. Lead with user-visible changes and add accurate
   known limitations; do not expose implementation or workflow mechanics.
4. Move the included files from `changes/unreleased/` to
   `changes/archive/<version>/`.
5. Copy the same rendered categories into a new versioned section in
   `CHANGELOG.md`, leaving a new empty `Unreleased` section.
6. Run `bash tools/test-prerelease-version.sh` and
   `node tools/changelog-fragments.mjs validate`.
7. Merge the version change through the normal reviewed pull-request workflow.
8. Create the matching tag, such as `v0.2.0-alpha.1`, from the intended commit.
9. Push the tag.

The fragment files are the canonical source for changes since the previous
release. Pull request titles are not scraped, and concurrent work does not edit
the shared root changelog. Published `CHANGELOG.md` sections and archived
fragments remain immutable.

The protected `prerelease` GitHub environment should require approval. The
workflow tests the source again, derives package identities from the tagged
commit's full history, builds platform artifacts, verifies Android signing,
creates checksums and update metadata, and publishes a GitHub prerelease. It
refuses tags that do not match the checked-in product version or Android
artifacts whose signing identity differs from
`release/android-signing-certificate.sha256`.

Android signing secrets belong only in the protected GitHub environment:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Private keys, keystores, passwords, release artifacts, and generated update
metadata must never be committed. The public Android signing-identity digest in
[`release/android-signing-certificate.sha256`](../release/android-signing-certificate.sha256)
is intentionally versioned so release workflows can reject an unexpected
signer.
