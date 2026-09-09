import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { guides } from "../src/generated/guides.js";
import { guidesContent } from "../src/generated/guides-content.js";
import { marketingCaptures } from "../src/generated/captures.js";
import { parseGuideFrontmatter } from "./guide-frontmatter.mjs";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const guideDirectory = path.join(websiteRoot, "content", "guides");

test("guide source contract rejects incomplete task instructions", () => {
  const valid = [
    "---",
    "title: A useful guide",
    "slug: useful-guide",
    "description: A sufficiently detailed guide description that explains the complete user outcome.",
    "category: Start here",
    "platform: Android",
    "device: Mobile",
    "platforms: Android",
    "durationMinutes: 5",
    "difficulty: Getting started",
    "lastUpdated: 2026-08-03",
    "captureScenarios: guide-useful-step",
    "prerequisites: A connected account",
    "---",
    "",
    "This introduction is deliberately long enough to explain what the guide helps a person finish safely.",
    "",
    "## 1. Finish the useful step",
    "",
    "@capture-alt: A complete synthetic screen that describes the user interface shown for this useful step",
    "@capture-caption: The real app presents enough context for a person to understand the result of this useful step.",
    "",
    "Follow the visible controls in order and review the resulting state before continuing. This body contains enough practical context to explain what should happen, what should not happen, and how the user can safely recognize completion.",
  ].join("\n");

  assert.equal(parseGuideFrontmatter(valid, "valid.md").steps.length, 1);
  assert.throws(() => parseGuideFrontmatter(valid.replace("@capture-alt:", "Capture:"), "missing-alt.md"));
  assert.throws(() => parseGuideFrontmatter(valid.replace("durationMinutes: 5", "durationMinutes: 0"), "duration.md"));
  assert.throws(() => parseGuideFrontmatter(valid.replace("captureScenarios: guide-useful-step", "captureScenarios: missing, extra"), "count.md"));
});

test("guide library is complete, illustrated, searchable, and privacy safe", async () => {
  const sourceFiles = (await readdir(guideDirectory)).filter((file) => file.endsWith(".md")).sort();
  assert.equal(guides.length, sourceFiles.length);
  assert.equal(guidesContent.length, guides.length);
  assert.ok(guides.length >= 6);

  const captureByBase = new Map();
  for (const capture of marketingCaptures) {
    const pair = captureByBase.get(capture.baseScenario) ?? [];
    pair.push(capture);
    captureByBase.set(capture.baseScenario, pair);
  }

  for (const guide of guidesContent) {
    assert.match(guide.path, /^\/guides\/(android|desktop|linux|windows)\/[a-z0-9-]+\/$/u);
    assert.ok(guide.steps.length >= 3);
    assert.ok(guide.prerequisites.length >= 1);
    assert.ok(guide.platforms.length >= 1);
    assert.ok(["Android", "Desktop", "Linux", "Windows"].includes(guide.platform));
    assert.ok(["Mobile", "Desktop"].includes(guide.device));
    assert.ok(guide.text.split(/\s+/u).filter(Boolean).length >= 250);
    assert.doesNotMatch(guide.text, /Yaro|Doornberg|\/home\/|crunchy/iu);
    assert.ok(guide.introductionHtml.includes("<p>"));
    for (const step of guide.steps) {
      assert.ok(step.html.includes("<p>"));
      assert.ok(step.text.split(/\s+/u).filter(Boolean).length >= 40);
      assert.ok(step.imageAlt.length >= 60);
      assert.ok(step.imageCaption.length >= 60);
      assert.match(step.imageDark, /^\/screenshots\/guide-[a-z0-9-]+\.png$/u);
      assert.match(step.imageLight, /^\/screenshots\/guide-[a-z0-9-]+-light\.png$/u);
      const pair = captureByBase.get(step.captureScenario);
      assert.equal(pair?.length, 2, step.captureScenario);
      assert.deepEqual(new Set(pair.map((capture) => capture.theme)), new Set(["dark", "light"]));
      assert.ok(pair.every((capture) => capture.feature === "Guides"));
      assert.ok(pair.every((capture) => capture.purpose === "showcase"));
    }
  }

  const searchIndex = JSON.parse(
    await readFile(path.join(websiteRoot, "public", "search-index.json"), "utf8"),
  );
  assert.equal(searchIndex.filter((entry) => entry.contentType === "Guide").length, guides.length);
});

test("guide routes expose task navigation and responsive step captures", async () => {
  const app = await readFile(path.join(websiteRoot, "src", "App.vue"), "utf8");
  const styles = await readFile(path.join(websiteRoot, "src", "styles.css"), "utf8");
  const server = await readFile(path.join(websiteRoot, "src", "entry-server.js"), "utf8");

  assert.match(app, /v-else-if="isGuidesLanding"/u);
  assert.match(app, /v-else-if="currentGuide"/u);
  assert.match(app, /aria-label="Guide steps and prerequisites"/u);
  assert.match(app, /class="guide-next-step"/u);
  assert.match(app, /Real Compose UI/u);
  assert.match(styles, /\.guide-layout\s*\{[^}]*grid-template-columns:/su);
  assert.match(styles, /@media \(max-width: 760px\)[\s\S]*\.guide-layout\s*\{[^}]*grid-template-columns:\s*1fr;/su);
  assert.match(server, /"@type": \["TechArticle", "HowTo"\]/u);
  assert.match(server, /"@type": "HowToStep"/u);
  assert.match(server, /"\/guides\/"/u);
});
