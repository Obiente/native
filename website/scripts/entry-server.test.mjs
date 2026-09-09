import assert from "node:assert/strict";
import test from "node:test";
import { news } from "../src/generated/news.js";
import { guides } from "../src/generated/guides.js";
import {
  metadataFor,
  sharingHeadFor,
  siteUrl,
  socialImageDetailsFor,
  socialImageFor,
} from "../src/server-metadata.js";

test("social cards use the contextual page image", async () => {
  const article = news[0];
  const articleImage = `${siteUrl}${article.websiteImage}`;

  assert.equal(socialImageFor(metadataFor(article.path)), articleImage);
  assert.equal(socialImageFor(metadataFor("/")), `${siteUrl}/social-preview.png`);

  assert.deepEqual(socialImageDetailsFor(metadataFor(article.path)), {
    url: articleImage,
    alt: article.imageAlt,
    width: article.imageWidth,
    height: article.imageHeight,
    type: "image/png",
  });
});

test("platform guide metadata has a distinct canonical route and contextual image", () => {
  const guide = guides.find((entry) => entry.platform === "Android");
  assert.ok(guide);
  const metadata = metadataFor(guide.path);
  assert.equal(metadata.canonical, `${siteUrl}${guide.path}`);
  assert.equal(metadata.image, `${siteUrl}${guide.websiteImageDark}`);

  const hub = metadataFor("/guides/android/");
  assert.equal(hub.canonical, `${siteUrl}/guides/android/`);
  assert.match(hub.title, /Android/u);
  assert.match(hub.description, /Android/u);
});

test("internal visual QA remains reachable without entering the search index", () => {
  const metadata = metadataFor("/visual-qa/");
  assert.equal(metadata.canonical, `${siteUrl}/visual-qa/`);
  assert.equal(metadata.robots, "noindex,follow");
});

test("rendered head contains complete route-specific sharing metadata", async () => {
  const article = news[0];
  const head = sharingHeadFor(metadataFor(article.path));

  for (const expected of [
    `<link rel="canonical" href="${siteUrl}${article.path}">`,
    '<link rel="alternate" hreflang="en"',
    '<meta property="og:locale" content="en_US">',
    `<meta property="og:image" content="${siteUrl}${article.websiteImage}">`,
    `<meta property="og:image:secure_url" content="${siteUrl}${article.websiteImage}">`,
    `<meta property="og:image:width" content="${article.imageWidth}">`,
    `<meta property="og:image:height" content="${article.imageHeight}">`,
    '<meta name="twitter:card" content="summary_large_image">',
    `<meta property="article:published_time" content="${article.date}">`,
    `<meta property="article:modified_time" content="${article.lastUpdated}">`,
    '<meta property="article:author" content="https://obiente.org">',
  ]) {
    assert.match(head, new RegExp(expected.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
  }
});
