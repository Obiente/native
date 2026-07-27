import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { changelog } from "../src/generated/changelog.js";
import { marketingCaptures } from "../src/generated/captures.js";
import { news } from "../src/generated/news.js";
import {
  decodePngDimensions,
  discoverCaptureSources,
  readCaptureManifest,
  stableCapturePath,
  verifyCaptureAssets,
  websiteCapturePath,
} from "./marketing-captures.mjs";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = path.resolve(websiteRoot, "..");

test("news stays long-form, visual, and separate from release history", async () => {
  assert.ok(news.length > 0);
  for (const post of news) {
    assert.ok(post.readingMinutes >= 4, `${post.file} is too short to be a product story`);
    assert.match(post.image, /^\/screenshots\/[a-z0-9-]+\.png$/);
    assert.match(post.websiteImage, /^\/screenshots\/[a-z0-9-]+\.png\?v=[a-f0-9]{64}$/);
    assert.match(post.captureScenario, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
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
    assert.equal(nativeArticle.image.url, `https://nc-native.obiente.dev${post.image}`);
  }
});

test("changelog remains a dedicated root-sourced searchable surface", async () => {
  assert.equal(changelog.file, "changes/unreleased and CHANGELOG.md");
  assert.equal(changelog.path, "/changelog/");
  assert.doesNotMatch(changelog.description, /product stor|development note/i);
  assert.match(changelog.html, /Unreleased/);

  const searchIndex = JSON.parse(
    await readFile(path.join(websiteRoot, "public", "search-index.json"), "utf8"),
  );
  const indexed = searchIndex.filter((entry) => entry.path === changelog.path);
  assert.equal(indexed.length, 1);
  assert.equal(indexed[0].contentType, "Changelog");
  assert.ok(news.every((post) => post.path !== changelog.path));

  const generator = await readFile(
    path.join(websiteRoot, "scripts", "generate-content.mjs"),
    "utf8",
  );
  assert.match(
    generator,
    /if \(changelogAvailable\) \{\s+changelogSource = composeChangelogSource/u,
  );
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
  assert.doesNotMatch(captureScript, /desktop-home\.png|mobile-home\.png/);
  assert.match(captureMain, /ImageComposeScene/);
  assert.match(captureMain, /NextcloudNativeMarketingCapture/);

  const manifest = await readCaptureManifest();
  assert.equal(manifest.schemaVersion, 2);
  assert.equal(manifest.identity, "Obiente");
  assert.equal(manifest.cloudIdentity, "Nextcloud");
  assert.equal(manifest.networkAccess, false);
  const captureScenarios = new Set(marketingCaptures.map((capture) => capture.scenario));
  for (const requiredScenario of [
    "desktop-home",
    "mobile-home",
    "obsidian-vault-sync",
    "media-backup-queue",
    "adaptive-dynamic-data",
    "adaptive-dynamic-data-mobile",
  ]) {
    assert.ok(captureScenarios.has(requiredScenario));
  }
  assert.equal(captureScenarios.size, marketingCaptures.length);
  assert.deepEqual(
    marketingCaptures.map((capture) => capture.path),
    manifest.captures.map(stableCapturePath),
  );
  assert.deepEqual(
    marketingCaptures.map((capture) => capture.websitePath),
    manifest.captures.map((capture) => websiteCapturePath(manifest, capture)),
  );
  for (const capture of manifest.captures) {
    assert.ok(Number.isInteger(capture.width) && capture.width > 0);
    assert.ok(Number.isInteger(capture.height) && capture.height > 0);
    assert.ok(Number.isFinite(capture.density) && capture.density > 0);
  }
  const capturedImages = new Set(
    manifest.captures.map((capture) => `/screenshots/${capture.file}`),
  );
  assert.ok(news.every((post) => capturedImages.has(post.image)));
  assert.ok(
    news.every(
      (post) =>
        manifest.captures.find((capture) => capture.scenario === post.captureScenario)
          ?.purpose === "showcase",
    ),
  );
  assert.deepEqual(await verifyCaptureAssets(manifest), []);
  assert.ok(manifest.captures.every((capture) => capture.feature.length > 0));
  assert.ok(manifest.captures.every((capture) => capture.surface.length > 0));
  assert.ok(manifest.captures.every((capture) => capture.state.length > 0));
  assert.ok(
    manifest.captures.every(
      (capture) =>
        capture.purpose === "showcase" || capture.purpose === "state-coverage",
    ),
  );
  assert.ok(manifest.captures.every((capture) => capture.platform.length > 0));
  assert.ok(manifest.captures.every((capture) => capture.viewport.length > 0));
  assert.ok(manifest.captureSources.length > 0);
  assert.equal(new Set(manifest.captureSources).size, manifest.captureSources.length);
  assert.ok(
    manifest.captureSources.every(
      (relative) =>
        relative === "ui/build.gradle.kts" ||
        relative === "build.gradle.kts" ||
        relative === "settings.gradle.kts" ||
        relative === "gradle.properties" ||
        relative === "gradle/libs.versions.toml" ||
        relative === "gradle/wrapper/gradle-wrapper.properties" ||
        relative === "tools/marketing-capture-inputs.txt" ||
        relative.startsWith("ui/src/commonMain/") ||
        relative.startsWith(
          "ui/src/desktopMain/kotlin/dev/obiente/nextcloudnative/nativeui/preview/",
        ) ||
        relative.startsWith("ui/src/desktopMain/resources/marketing/"),
    ),
  );
  for (const capture of manifest.captures) {
    const bytes = await readFile(
      path.join(websiteRoot, "public", "screenshots", capture.file),
    );
    assert.deepEqual(decodePngDimensions(bytes), {
      width: capture.width,
      height: capture.height,
    });
    assert.equal(createHash("sha256").update(bytes).digest("hex"), capture.sha256);
  }
  const validPng = await readFile(
    path.join(websiteRoot, "public", "screenshots", manifest.captures[0].file),
  );
  assert.throws(() => decodePngDimensions(validPng.subarray(0, 40)));
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

test("deploy builds verify committed captures while review CI checks freshness", async () => {
  const packageJson = JSON.parse(
    await readFile(path.join(websiteRoot, "package.json"), "utf8"),
  );
  const captureWrapper = await readFile(
    path.join(repositoryRoot, "tools", "capture-marketing-screenshots.sh"),
    "utf8",
  );
  const ciWorkflow = await readFile(
    path.join(repositoryRoot, ".github", "workflows", "ci.yml"),
    "utf8",
  );

  assert.equal(
    packageJson.scripts["verify:captures"],
    "node scripts/verify-marketing-capture-assets.mjs",
  );
  assert.equal(
    packageJson.scripts["verify:captures:fresh"],
    "node scripts/verify-marketing-captures.mjs",
  );
  assert.match(packageJson.scripts.build, /\bnpm run verify:captures\b/u);
  assert.doesNotMatch(packageJson.scripts.build, /\bverify:captures:fresh\b/u);
  assert.match(captureWrapper, /\bnpm run --prefix website verify:captures:fresh\b/u);
  assert.match(
    ciWorkflow,
    /\bnode website\/scripts\/verify-marketing-captures\.mjs\b/u,
  );
  assert.match(ciWorkflow, /steps\.changes\.outputs\.capture_inputs == 'true'/u);
  assert.match(ciWorkflow, /- "ui\/src\/commonMain\/\*\*"/u);
  assert.match(ciWorkflow, /- "gradle\/libs\.versions\.toml"/u);
  assert.match(ciWorkflow, /- "website\/public\/screenshots\/\*\*"/u);
});

test("capture freshness tracks renderer build configuration", async () => {
  const sources = new Set(await discoverCaptureSources());
  for (const requiredSource of [
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
    "ui/build.gradle.kts",
  ]) {
    assert.ok(sources.has(requiredSource), `${requiredSource} must affect capture freshness`);
  }
});

test("visual QA and mobile navigation are driven by registered captures", async () => {
  const appSource = await readFile(
    path.join(websiteRoot, "src", "App.vue"),
    "utf8",
  );
  const entryServer = await readFile(
    path.join(websiteRoot, "src", "entry-server.js"),
    "utf8",
  );
  const styles = await readFile(
    path.join(websiteRoot, "src", "styles.css"),
    "utf8",
  );

  assert.match(entryServer, /"\/visual-qa\/"/u);
  assert.match(
    styles,
    /\.visual-qa-image\s*\{[^}]*overflow:\s*hidden;/su,
  );
  assert.match(
    styles,
    /\.visual-qa-card figcaption\s*\{[^}]*background:\s*var\(--surface\);/su,
  );
  assert.match(
    styles,
    /@media \(max-width:\s*1100px\)\s*\{[\s\S]*?\.desktop-nav\s*\{[^}]*display:\s*none;/u,
  );
  assert.match(appSource, /aria-controls="mobile-site-navigation"/u);
  assert.equal(
    (appSource.match(/:aria-pressed=/gu) ?? []).length,
    3,
  );
  assert.match(appSource, /class="hero-mobile-capture"/u);
  assert.match(appSource, /:src="mobileHomeCapture\.websitePath"/u);
  assert.match(appSource, /capture\.purpose === visualQaPurpose\.value/u);
  assert.match(appSource, /capture\.pullRequest/u);
  assert.match(appSource, /capture\.issue/u);
  assert.match(appSource, /visualQaGroups/u);
  assert.doesNotMatch(
    appSource,
    /class="hero-mobile-capture"[\s\S]*?src="\/screenshots\/mobile-home\.png"/u,
  );
});
