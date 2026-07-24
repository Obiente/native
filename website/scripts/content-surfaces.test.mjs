import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { changelog } from "../src/generated/changelog.js";
import { news } from "../src/generated/news.js";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(websiteRoot, "..");

test("news stays long-form, visual, and separate from release history", async () => {
  assert.ok(news.length > 0);
  for (const post of news) {
    assert.ok(post.readingMinutes >= 4, `${post.file} is too short to be a product story`);
    assert.match(post.image, /^\/screenshots\/[a-z0-9-]+\.png$/);
    assert.ok(post.imageAlt.length >= 40);
    assert.ok(post.imageCaption.length >= 40);
    assert.match(post.html, /why/i);
    assert.match(post.html, /what changed/i);
    assert.match(post.html, /walkthrough/i);
    assert.match(post.html, /limitation|does not/i);
    assert.match(post.html, /what comes next/i);
    assert.ok(
      (post.html.match(/<h2\b/g) ?? []).length >= 6,
      `${post.file} needs enough structure to explain the workflow and engineering`,
    );
    assert.doesNotMatch(post.title, /changelog|release notes/i);
    assert.match(post.lastUpdated, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(post.lastUpdated >= post.date);

    const image = await readFile(path.join(websiteRoot, "public", post.image));
    assert.deepEqual([...image.subarray(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10]);
  }
});

test("living news metadata and native feed share the canonical Markdown source", async () => {
  const nativeFeed = JSON.parse(
    await readFile(path.join(websiteRoot, "public", "news-feed-v1.json"), "utf8"),
  );
  assert.equal(nativeFeed.entries.length, news.length);
  for (const post of news) {
    const source = await readFile(
      path.join(websiteRoot, "content", "news", post.file),
      "utf8",
    );
    assert.match(source, new RegExp(`^lastUpdated: ${post.lastUpdated}$`, "m"));
    const nativeArticle = nativeFeed.entries.find(
      (entry) => entry.id === post.path.split("/").filter(Boolean).at(-1),
    );
    assert.ok(nativeArticle);
    assert.equal(nativeArticle.lastUpdated, post.lastUpdated);
    assert.equal(nativeArticle.title, post.title);
    assert.equal(nativeArticle.description, post.description);
  }
});

test("changelog remains a dedicated root-sourced searchable surface", async () => {
  assert.equal(changelog.file, "CHANGELOG.md");
  assert.equal(changelog.path, "/changelog/");
  assert.doesNotMatch(changelog.description, /product stor|development note/i);

  const searchIndex = JSON.parse(
    await readFile(path.join(websiteRoot, "public", "search-index.json"), "utf8"),
  );
  const indexed = searchIndex.filter((entry) => entry.path === changelog.path);
  assert.equal(indexed.length, 1);
  assert.equal(indexed[0].contentType, "Changelog");
  assert.ok(news.every((post) => post.path !== changelog.path));
});

test("marketing screenshots are rendered offscreen without an Android device", async () => {
  const captureScript = await readFile(
    path.join(repositoryRoot, "tools", "capture-marketing-screenshots.sh"),
    "utf8",
  );
  const captureMain = await readFile(
    path.join(
      repositoryRoot,
      "ui",
      "src",
      "desktopMain",
      "kotlin",
      "dev",
      "obiente",
      "nextcloudnative",
      "nativeui",
      "preview",
      "MarketingCaptureMain.kt",
    ),
    "utf8",
  );

  assert.doesNotMatch(captureScript, /\badb\b|ANDROID_HOME|--android|assembleScreenshot/);
  assert.match(captureScript, /:ui:captureMarketingScreenshots/);
  assert.match(captureMain, /ImageComposeScene/);
  assert.match(captureMain, /NextcloudNativeMarketingCapture/);

  const manifest = JSON.parse(
    await readFile(
      path.join(websiteRoot, "public", "screenshots", "capture-manifest.json"),
      "utf8",
    ),
  );
  assert.equal(manifest.identity, "Obiente");
  assert.equal(manifest.cloudIdentity, "Nextcloud");
  assert.equal(manifest.networkAccess, false);
  assert.deepEqual(
    manifest.captures.map((capture) => capture.scenario),
    [
      "desktop-home",
      "mobile-home",
      "obsidian-vault-sync",
      "media-backup-queue",
      "adaptive-dynamic-data",
    ],
  );
  const expectedDimensions = new Map([
    ["desktop-home", [1440, 900]],
    ["mobile-home", [1080, 2400]],
    ["obsidian-vault-sync", [1080, 1000]],
    ["media-backup-queue", [1080, 1800]],
    ["adaptive-dynamic-data", [1440, 360]],
  ]);
  for (const capture of manifest.captures) {
    assert.deepEqual(
      [capture.width, capture.height],
      expectedDimensions.get(capture.scenario),
    );
  }
  const capturedImages = new Set(
    manifest.captures.map((capture) => `/screenshots/${capture.file}`),
  );
  assert.ok(news.every((post) => capturedImages.has(post.image)));
  const sourceDigest = createHash("sha256");
  for (const relative of manifest.captureSources) {
    sourceDigest.update(relative);
    sourceDigest.update(new Uint8Array([0]));
    sourceDigest.update(await readFile(path.join(repositoryRoot, relative)));
  }
  assert.equal(manifest.captureSourceSha256, sourceDigest.digest("hex"));
  for (const capture of manifest.captures) {
    const bytes = await readFile(
      path.join(websiteRoot, "public", "screenshots", capture.file),
    );
    assert.equal(createHash("sha256").update(bytes).digest("hex"), capture.sha256);
  }
  const avatar = await readFile(
    path.join(repositoryRoot, "ui", "src", "desktopMain", "resources", "marketing", "obiente-avatar.png"),
  );
  assert.equal(
    createHash("sha256").update(avatar).digest("hex"),
    "a20433eeda834a418f92d76853633b4fc9115ad3006c5622ce2611432dc1f14d",
  );
  const websiteAvatar = await readFile(path.join(websiteRoot, "public", "obiente-avatar.png"));
  assert.deepEqual(websiteAvatar, avatar);
});
