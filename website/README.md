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

## Product screenshots

The gallery is captured from the real Compose UI using the compile-time
`MarketingDemoFixture`. The fixture contains synthetic names and `.invalid`
addresses, and the capture entry point never constructs account, session,
network, cache, or media services.

```bash
tools/capture-marketing-screenshots.sh
```

This produces `public/screenshots/desktop-home.png` offscreen. Android capture
uses the separate `.demo` application ID and requires an explicit serial:

```bash
tools/capture-marketing-screenshots.sh --android emulator-5554
```

Physical devices are rejected unless
`--allow-physical-demo-capture` is passed deliberately. Tests enforce that the
fixture cannot contain credentials, real endpoints, local paths, or user
content.

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
