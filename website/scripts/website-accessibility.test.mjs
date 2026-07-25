import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("interactive preview exposes named controls and a functional mobile app switcher", async () => {
  const [nativePreview, appSurface] = await Promise.all([
    readFile(path.join(websiteRoot, "src", "components", "NativePreview.vue"), "utf8"),
    readFile(path.join(websiteRoot, "src", "components", "PreviewAppSurface.vue"), "utf8"),
  ]);

  assert.match(nativePreview, /@open-switcher="focusAppSwitcher"/);
  assert.match(nativePreview, /function focusAppSwitcher\(\)/);
  assert.match(nativePreview, /prefers-reduced-motion: reduce/);
  assert.match(nativePreview, /reduceMotion \? "auto" : "smooth"/);
  assert.match(appSurface, /@click="\$emit\('open-switcher'\)"/);
  for (const label of [
    "More file actions",
    "More photo actions",
    "Start audio call",
    "Start video call",
    "Send message",
    "More message actions",
    "Previous track",
    "Next track",
    "Previous month",
    "Next month",
  ]) {
    assert.match(appSurface, new RegExp(`aria-label="${label}"`));
  }
  assert.match(appSurface, /:aria-label="`Search \$\{activeMeta\.title\}`"/);
  assert.match(appSurface, /const searchResults = computed/);
  assert.match(appSurface, /v-for="result in searchResults"/);
  assert.match(appSurface, /No matching items in \{\{ activeMeta\.title \}\}/);
  assert.match(appSurface, /:aria-label="musicPlaying \? 'Pause Rain on glass'/);
  assert.match(appSurface, /:aria-label="`More actions for \$\{user\.name\}`"/);
});

test("roadmap links and indexed details remain available with truthful fallback behavior", async () => {
  const [roadmap, articleRoadmap, app] = await Promise.all([
    readFile(path.join(websiteRoot, "src", "components", "RoadmapDashboard.vue"), "utf8"),
    readFile(path.join(websiteRoot, "src", "components", "ArticleRoadmap.vue"), "utf8"),
    readFile(path.join(websiteRoot, "src", "App.vue"), "utf8"),
  ]);

  assert.doesNotMatch(roadmap, /role="row"/);
  assert.doesNotMatch(articleRoadmap, /role="row"/);
  assert.doesNotMatch(roadmap, /role="cell"/);
  assert.doesNotMatch(articleRoadmap, /role="cell"/);
  assert.match(roadmap, /Live sync unavailable/);
  assert.match(articleRoadmap, /Live sync unavailable/);
  assert.match(roadmap, /no potentially stale issue status/);
  assert.match(roadmap, /@media \(max-width: 780px\)/);
  assert.match(app, /class="roadmap-source-document"/);
  assert.match(app, /v-html="currentDoc\.html"/);
});
