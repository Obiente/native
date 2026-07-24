import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";

const feedUrl = new URL("../public/news-feed-v1.json", import.meta.url);

test("native news feed is bounded, versioned, canonical, and internally consistent", async () => {
  const bytes = await readFile(feedUrl);
  assert.ok(bytes.length > 0 && bytes.length <= 512 * 1024);
  const feed = JSON.parse(bytes.toString("utf8"));
  assert.equal(feed.schemaVersion, 1);
  assert.match(feed.feedRevision, /^[a-f0-9]{64}$/);
  assert.ok(feed.entries.length > 0 && feed.entries.length <= 100);

  const ids = new Set();
  for (const entry of feed.entries) {
    assert.match(entry.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
    assert.equal(ids.has(entry.id), false);
    ids.add(entry.id);
    assert.match(entry.publishedDate, /^\d{4}-\d{2}-\d{2}$/);
    assert.equal(entry.webUrl, `https://nc-native.obiente.dev/news/${entry.id}/`);
    assert.equal(
      entry.contentSha256,
      createHash("sha256").update(entry.bodyMarkdown).digest("hex"),
    );
    assert.ok(entry.bodyMarkdown.length <= 64 * 1024);
  }
});
