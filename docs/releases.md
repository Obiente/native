# Prerelease policy

Nextcloud Native remains on the `0.x.y` release line until the full pre-release
sprint is complete. Every GitHub release must be marked as a prerelease. The
release workflow rejects stable versions and all `1.0.0` or higher tags.

Supported versions use one of these forms:

- `0.x.y-alpha.n` for early testing
- `0.x.y-beta.n` for feature-complete testing
- `0.x.y-rc.n` for release candidates

The canonical version lives in `gradle.properties`. Android and desktop builds
read it from there. `ncDesktopPackageVersion` contains the numeric `0.x.y`
portion because native desktop packagers do not consistently accept SemVer
prerelease suffixes.

Android version codes are deterministic:

```text
minor * 10,000,000 + patch * 3,000 + phase offset + prerelease number
```

Phase offsets are `0` for alpha, `1,000` for beta, and `2,000` for release
candidates. Each prerelease number is between 1 and 999.

## Creating a prerelease

1. Update the three `ncVersion*` values in `gradle.properties`.
2. Move the completed entries from `Unreleased` in `CHANGELOG.md` into a
   versioned section and start a new empty `Unreleased` section.
3. Add plain-language notes at `docs/release-notes/<version>.md`. Lead with
   user-visible changes and known limitations; put implementation details last.
4. Run `bash tools/test-prerelease-version.sh`.
5. Merge the version change through the normal reviewed pull-request workflow.
6. Create the matching tag, such as `v0.1.0-alpha.2`, from the intended commit.
7. Push the tag.

The protected `prerelease` GitHub environment should require approval. The
workflow tests the source again, builds platform artifacts, verifies Android
signing, creates checksums and update metadata, and publishes a GitHub
prerelease. It refuses tags that do not match the checked-in version or Android
artifacts whose signing identity differs from
`release/android-signing-certificate.sha256`.

Android signing secrets belong only in the protected GitHub environment:

- `ANDROID_RELEASE_KEYSTORE_BASE64`
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

Release artifacts, keystores, passwords, certificates, and generated update
metadata must never be committed.
