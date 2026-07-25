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
  assert.match(appSurface, /:aria-label="musicPlaying \? 'Pause Rain on glass'/);
  assert.match(appSurface, /:aria-label="`More actions for \$\{user\.name\}`"/);
});

test("roadmap links keep native anchor semantics", async () => {
  const [roadmap, articleRoadmap] = await Promise.all([
    readFile(path.join(websiteRoot, "src", "components", "RoadmapDashboard.vue"), "utf8"),
    readFile(path.join(websiteRoot, "src", "components", "ArticleRoadmap.vue"), "utf8"),
  ]);

  assert.doesNotMatch(roadmap, /role="row"/);
  assert.doesNotMatch(articleRoadmap, /role="row"/);
  assert.doesNotMatch(roadmap, /role="cell"/);
  assert.doesNotMatch(articleRoadmap, /role="cell"/);
  assert.match(roadmap, /Live sync unavailable/);
  assert.match(articleRoadmap, /Live sync unavailable/);
});
