# Nextcloud Native website

The project homepage is a provider-neutral Vue site. Its production build
prerenders the homepage and every selected repository Markdown document to
plain HTML, then emits a sitemap and a small client bundle for search and
interactive previews.

Project updates live in `content/news/`. Each Markdown file has strict
frontmatter and becomes a prerendered article, search result, sitemap entry,
structured-data article, and RSS item.

## Local development

```bash
npm ci
npm run dev
```

The content generator reads the canonical Markdown files from the repository
root before starting Vite. Changes to those files require restarting the local
server or running `npm run content`.

The roadmap page also reads the public GitHub project views and milestones at
build time. It never needs or embeds a GitHub token. If GitHub is unavailable
or rate-limited, generation falls back to the repository-owned workstream
links so local and production builds remain deterministic.

## Production build

```bash
npm ci
npm run build
```

The self-contained static output is written to `website/dist/`. It can be
served by any static web server. Directory index support must remain enabled so
routes such as `/roadmap/` resolve to their prerendered `index.html`.

## News, release notes, and changelog

These surfaces intentionally serve different readers:

- `content/news/*.md` contains long, visual product stories for people who use
  Nextcloud and contributors who want the implementation context. News is
  living documentation: keep its `lastUpdated` date, screenshots, capability
  boundaries, and UI wording current when the product changes.
- per-version release notes are short installer-facing summaries and
  limitations under `/releases/`.
- `changes/unreleased/*.md` provides the live user-facing entries contributed
  by individual pull requests without a shared-file merge conflict.
- the dedicated `/changelog/` page combines those validated fragments with the
  immutable release history in the canonical root `CHANGELOG.md`.

The content build never manufactures changelog entries from news or pull
request titles. Changelog entries and per-release notes are immutable
historical records; later clarifications belong in a new entry or release, not
a rewritten past artifact.

## Product screenshots

The gallery is captured from the real Compose UI using the compile-time
`MarketingDemoFixture`. The fixture contains synthetic names and `.invalid`
addresses, and the capture entry point never constructs account, session,
network, cache, or media services.

```bash
tools/capture-marketing-screenshots.sh
```

This renders every entry in the Compose capture registry and writes its image
and metadata to the capture manifest. Adding a registry entry is sufficient;
Gradle, the shell wrapper, website tests, and the gallery do not carry a second
filename list. Each scenario uses production Compose components and
deterministic synthetic models. The workflow does not use adb, an emulator, a
phone, a Nextcloud account, or network-backed application services.

Production and pull request deployments validate the committed manifest, fully
decode each PNG, and check its dimensions and hash without requiring unrelated
UI source changes to regenerate the catalog:

```bash
npm run --prefix website verify:captures
```

The dedicated `Refresh marketing captures` workflow checks whether the catalog
represents the current capture inputs. Normal build and test jobs do not fail
only because the generated catalog is stale. Run the same freshness gate
locally with:

```bash
npm run --prefix website verify:captures:fresh
```

If the freshness command reports stale inputs, run the capture wrapper with
JDK 21 and review the updated synthetic images. The `/visual-qa/` route lists
scenario, feature, surface, state, platform, viewport, and pixel metadata.
Future scenario entries may also identify the pull request they review.

For same-repository pull requests, the refresh workflow prepares an untrusted
patch without write credentials. A separate default-branch workflow validates
the pull request identity, exact head revision, patch paths, regular-file modes,
and patch size before `obiente-automations[bot]` commits it with a short-lived
token restricted to this repository and `Contents: write`. Forks and Dependabot
remain read-only and must commit stale capture updates themselves.

The canonical Obiente organization avatar lives with the desktop capture
resources. Content generation copies it into `public/` for static hosting, so
the repository does not carry two independent binaries.

## Container

Build from the repository root because the documentation source lives there:

```bash
docker build -f website/Dockerfile -t nextcloud-native-homepage .
docker run --rm -p 8080:80 nextcloud-native-homepage
```

For a local check, `docker compose -f website/compose.yml up --build` provides
the same image. In production, place the container behind the Obiente reverse
proxy and terminate TLS there.

The canonical hostname is currently configured as
`https://nc-native.obiente.dev` in the prerender and crawler metadata. Change
that value in `src/entry-server.js`, `scripts/prerender.mjs`,
`public/robots.txt`, and `index.html` together if the final hostname differs.
