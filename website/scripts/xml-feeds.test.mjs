import assert from "node:assert/strict";
import test from "node:test";
import { buildRss, buildSitemap, escapeXml } from "./xml-feeds.mjs";

test("XML output escapes every reserved character", () => {
  assert.equal(
    escapeXml(`&<>"'`),
    "&amp;&lt;&gt;&quot;&apos;",
  );
  const entry = {
    path: "/news/a&b/",
    title: `A < B & "quoted"`,
    description: `Use >, <, &, "quotes", and 'apostrophes'`,
    date: "2026-07-20",
    lastUpdated: "2026-07-24",
  };
  const rss = buildRss([entry], "https://example.invalid");
  assert.match(rss, /A &lt; B &amp; &quot;quoted&quot;/);
  assert.match(rss, /Use &gt;, &lt;, &amp;, &quot;quotes&quot;, and &apos;apostrophes&apos;/);
  assert.match(rss, /a&amp;b/);
});

test("sitemap uses accurate update metadata for every content route kind", () => {
  const newsRoute = "/news/updated/";
  const guideRoute = "/guides/calendar/";
  const sitemap = buildSitemap(
    [newsRoute, guideRoute, "/architecture/"],
    [
      { path: newsRoute, date: "2026-07-20", lastUpdated: "2026-07-24" },
      { path: guideRoute, lastUpdated: "2026-08-03" },
    ],
    "https://example.invalid",
  );
  assert.match(sitemap, /<lastmod>2026-07-24<\/lastmod>/);
  assert.doesNotMatch(sitemap, /<lastmod>2026-07-20<\/lastmod>/);
  assert.match(
    sitemap,
    /<loc>https:\/\/example\.invalid\/guides\/calendar\/<\/loc><lastmod>2026-08-03<\/lastmod>/,
  );
  assert.match(sitemap, /<loc>https:\/\/example\.invalid\/architecture\/<\/loc><\/url>/);
});
