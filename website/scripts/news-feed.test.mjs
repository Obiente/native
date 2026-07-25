import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  assertValidNativeNewsFeed,
  nativeNewsFeedRevision,
  NATIVE_NEWS_FEED_LIMITS,
} from "./news-feed-contract.mjs";

const feedUrl = new URL("../public/news-feed-v1.json", import.meta.url);

test("native news feed is bounded, versioned, canonical, and internally consistent", async () => {
  const bytes = await readFile(feedUrl);
  assert.ok(bytes.length > 0 && bytes.length <= 512 * 1024);
  const feed = JSON.parse(bytes.toString("utf8"));
  assertValidNativeNewsFeed(feed, bytes.length);
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
    assert.equal(entry.bodyMarkdown.startsWith(`# ${entry.title}`), false);
    assert.match(
      entry.image.url,
      /^https:\/\/nc-native\.obiente\.dev\/screenshots\/[a-z0-9-]+\.png$/,
    );
    assert.match(entry.image.sha256, /^[a-f0-9]{64}$/);
    const imageBytes = await readFile(
      new URL(`../public${new URL(entry.image.url).pathname}`, import.meta.url),
    );
    assert.equal(
      entry.image.sha256,
      createHash("sha256").update(imageBytes).digest("hex"),
    );
  }
});

test("generator contract rejects fields outside the native parser bounds", async () => {
  const feed = JSON.parse(await readFile(feedUrl, "utf8"));
  const invalidFeed = (mutate) => {
    const candidate = structuredClone(feed);
    mutate(candidate.entries[0]);
    return candidate;
  };
  const limits = NATIVE_NEWS_FEED_LIMITS;

  assert.throws(() =>
    assertValidNativeNewsFeed(
      invalidFeed((entry) => {
        entry.title = "t".repeat(limits.maximumTitleLength + 1);
      }),
    ),
  );
  assert.throws(() =>
    assertValidNativeNewsFeed(
      invalidFeed((entry) => {
        entry.description = "d".repeat(limits.maximumDescriptionLength + 1);
      }),
    ),
  );
  assert.throws(() =>
    assertValidNativeNewsFeed(
      invalidFeed((entry) => {
        entry.tags = Array.from(
          { length: limits.maximumTagCount + 1 },
          (_, index) => `tag-${index}`,
        );
      }),
    ),
  );
  assert.throws(() =>
    assertValidNativeNewsFeed(
      invalidFeed((entry) => {
        entry.tags = ["t".repeat(limits.maximumTagLength + 1)];
      }),
    ),
  );
});

test("canonical feed revision is cross-language stable and detects metadata changes", async () => {
  const fixture = {
    id: "utf8-news",
    title: "UTF-8 news",
    description: "A non-ASCII fixture.",
    publishedDate: "2026-07-25",
    lastUpdated: null,
    tags: [],
    bodyMarkdown: "Café updates\n\nNative cloud news.",
    webUrl: "https://nc-native.obiente.dev/news/utf8-news/",
    contentSha256: "1ed3e899c261b24e48e36cf15a890d5c106c1141a22ee1e800f26ca881bd7f1a",
    image: {
      url: "https://nc-native.obiente.dev/screenshots/mobile-home.png",
      alt: "A fixture-only native app screen",
      width: 1080,
      height: 1920,
      sha256: "f".repeat(64),
    },
  };
  assert.equal(
    nativeNewsFeedRevision([fixture]),
    "59f390aea5824aaccc91e7ee81a1cd7130dbda746ae28312a9fad4aecd55c1d3",
  );

  const generated = JSON.parse(await readFile(feedUrl, "utf8"));
  for (const mutate of [
    (entry) => {
      entry.title += " updated";
    },
    (entry) => {
      entry.publishedDate = "2026-07-23";
    },
    (entry) => {
      entry.tags = [...entry.tags, "changed"];
    },
  ]) {
    const changed = structuredClone(generated);
    mutate(changed.entries[0]);
    assert.throws(
      () => assertValidNativeNewsFeed(changed),
      /revision does not match/u,
    );
  }
});
