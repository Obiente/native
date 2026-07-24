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

test("sitemap uses the living article update date", () => {
  const route = "/news/updated/";
  const sitemap = buildSitemap(
    [route],
    [{ path: route, date: "2026-07-20", lastUpdated: "2026-07-24" }],
    "https://example.invalid",
  );
  assert.match(sitemap, /<lastmod>2026-07-24<\/lastmod>/);
  assert.doesNotMatch(sitemap, /<lastmod>2026-07-20<\/lastmod>/);
});
