# Marketing capture assets

**Last reviewed: 2026-08-20.** Capture scenarios, generated website assets, and
workflow behavior may have changed. The
[`captureMarketingScreenshots` task](../../../../build.gradle.kts) and
[capture refresh workflow](../../../../../.github/workflows/refresh-marketing-captures.yml)
are the current sources of truth.

`obiente-avatar.png` is the project-owned Obiente organization mark, used here
with permission. It is the unchanged GitHub organization avatar for
[`Obiente`](https://github.com/Obiente), downloaded from the GitHub organization
API on 2026-07-24. Its SHA-256 digest is
`a20433eeda834a418f92d76853633b4fc9115ad3006c5622ce2611432dc1f14d`.

It is used only as the synthetic Obiente account avatar in deterministic,
offline product captures. It is not the Nextcloud Native app icon.

The asset in this directory is canonical. The website content generator copies it to
`website/public/obiente-avatar.png` for static hosting; that generated copy is
ignored rather than stored as a second repository binary.
