import assert from "node:assert/strict";
import test from "node:test";
import { news } from "../src/generated/news.js";
import { metadataFor, siteUrl, socialImageFor } from "../src/server-metadata.js";

test("social cards use the contextual page image", async () => {
  const article = news[0];
  const articleImage = `${siteUrl}${article.image}`;

  assert.equal(socialImageFor(metadataFor(article.path)), articleImage);
  assert.equal(socialImageFor(metadataFor("/")), `${siteUrl}/social-preview.png`);
});
