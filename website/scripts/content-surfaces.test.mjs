import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { changelog } from "../src/generated/changelog.js";
import { marketingCaptures } from "../src/generated/captures.js";
import { news } from "../src/generated/news.js";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(websiteRoot, "..");

async function filesBelow(relativeRoot) {
  const root = path.join(repositoryRoot, relativeRoot);
  let entries;
  try {
    entries = await readdir(root, { recursive: true, withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
  return entries
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(repositoryRoot, path.join(entry.parentPath, entry.name)).replaceAll("\\", "/"),
    );
}

test("news stays long-form, visual, and separate from release history", async () => {
  assert.ok(news.length > 0);
  for (const post of news) {
    assert.ok(post.readingMinutes >= 4, `${post.file} is too short to be a product story`);
    assert.match(post.image, /^\/screenshots\/[a-z0-9-]+\.png$/);
    assert.ok(post.imageAlt.length >= 40);
    assert.ok(post.imageCaption.length >= 40);
    assert.ok(post.text.split(/\s+/).filter(Boolean).length >= 700);
    assert.ok(post.headings.length >= 3);
    assert.equal(new Set(post.headings.map((heading) => heading.anchor)).size, post.headings.length);
    assert.doesNotMatch(post.title, /changelog|release notes/i);
    assert.match(post.lastUpdated, /^\d{4}-\d{2}-\d{2}$/);
    assert.ok(post.lastUpdated >= post.date);

    const image = await readFile(
      path.join(websiteRoot, "public", post.image.replace(/^\/+/, "")),
    );
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
    marketingCaptures.map((capture) => capture.scenario),
    [
      "desktop-home",
      "mobile-home",
      "obsidian-vault-sync",
      "media-backup-queue",
      "adaptive-dynamic-data",
    ],
  );
  assert.deepEqual(
    marketingCaptures.map((capture) => capture.path),
    manifest.captures.map((capture) => `/screenshots/${capture.file}`),
  );
  const expectedDimensions = new Map([
    ["desktop-home", [1440, 900]],
    ["mobile-home", [1080, 2400]],
    ["obsidian-vault-sync", [1080, 1000]],
    ["media-backup-queue", [1080, 1800]],
    ["adaptive-dynamic-data", [960, 360]],
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
  const expectedCaptureSources = [
    ...(await filesBelow("ui/src/commonMain/kotlin")),
    ...(await filesBelow("ui/src/commonMain/resources")),
    ...(await filesBelow("ui/src/desktopMain/kotlin/dev/obiente/nextcloudnative/nativeui/preview")),
    ...(await filesBelow("ui/src/desktopMain/resources/marketing")),
    "ui/build.gradle.kts",
  ].sort();
  assert.deepEqual(manifest.captureSources, expectedCaptureSources);
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
