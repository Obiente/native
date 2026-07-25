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
- the dedicated `/changelog/` page renders the canonical root `CHANGELOG.md` as
  concise chronological Added, Changed, Fixed, and Security records.

The content build uses a clearly marked empty changelog state before the first
root changelog is present. It never manufactures changelog entries from news.
Changelog entries and per-release notes are immutable historical records; later
clarifications belong in a new entry or release, not a rewritten past artifact.

## Product screenshots

The gallery is captured from the real Compose UI using the compile-time
`MarketingDemoFixture`. The fixture contains synthetic names and `.invalid`
addresses, and the capture entry point never constructs account, session,
network, cache, or media services.

```bash
tools/capture-marketing-screenshots.sh
```

This produces the desktop and mobile home screens plus contextual Obsidian
sync, media backup, and adaptive-app scenarios offscreen on the workstation.
Each scenario is rendered from production Compose components with deterministic
synthetic models. Mobile captures use a 1080 by 2400 viewport at Android-like
density, but the workflow does not use adb, an emulator, or a phone. Tests
enforce that every article screenshot appears in the capture manifest and that
the fixture cannot contain credentials, real endpoints, local paths, or user
content.

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
