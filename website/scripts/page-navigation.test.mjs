import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { pageSections } from "../src/page-outline.js";
import { docsContent } from "../src/generated/docs-content.js";
import { news } from "../src/generated/news.js";

test("page outlines contain major sections without duplicate or empty destinations", () => {
  const first = { title: "Overview", anchor: "overview", level: 2 };
  const legacy = { title: "Getting started", anchor: "getting-started" };
  assert.deepEqual(pageSections([
    first,
    { title: "Details", anchor: "details", level: 3 },
    { title: "Overview repeated", anchor: "overview", level: 2 },
    { title: "Empty anchor", anchor: "", level: 2 },
    { title: "", anchor: "empty-title", level: 2 },
    legacy,
  ]), [first, legacy]);
  assert.deepEqual(pageSections(), []);
  assert.deepEqual(pageSections([]), []);
});

test("every published outline entry points to a rendered document heading", () => {
  for (const page of [...docsContent, ...news]) {
    for (const heading of page.headings) {
      assert.ok([2, 3, 4].includes(heading.level), `${page.path}: missing heading level`);
    }
    for (const section of pageSections(page.headings)) {
      assert.ok(page.html.includes(`id="${section.anchor}"`), `${page.path}: missing #${section.anchor}`);
    }
  }
});

test("section navigation and header menus preserve keyboard behavior and clean up listeners", async () => {
  const app = await readFile(new URL("../src/App.vue", import.meta.url), "utf8");
  const outline = await readFile(new URL("../src/components/PageOutline.vue", import.meta.url), "utf8");
  assert.match(app, /class="site-header-frame"/);
  assert.match(app, /ref="mobileMenuTrigger"/);
  assert.match(app, /mobileMenuTrigger.value\?\.focus\(\)/);
  for (const event of ["pointerdown", "focusin"]) {
    assert.ok(app.includes(`addEventListener("${event}", dismissHeaderMenus)`));
    assert.ok(app.includes(`removeEventListener("${event}", dismissHeaderMenus)`));
  }
  assert.match(outline, /:href="`#\$\{section.anchor\}`"/);
  assert.match(outline, /'location' : undefined/);
  assert.match(outline, /removeEventListener\("hashchange", updateAnchor\)/);
  assert.match(app, /:headings="currentPost.headings"/);
  assert.match(app, /:headings="currentDoc.headings"/);
});

test("search distinguishes loading, failure, fallback, and result types", async () => {
  const app = await readFile(new URL("../src/App.vue", import.meta.url), "utf8");
  assert.match(app, /if \(!response.ok\) throw new Error/);
  assert.match(app, /catch \{[\s\S]*?searchError.value = true/);
  assert.match(app, /finally \{\s*searchLoading.value = false/);
  assert.match(app, /if \(!searchLoaded.value\) await loadSearchIndex\(\)/);
  assert.match(app, /@click="loadSearchIndex"/);
  assert.match(app, /:aria-busy="searchLoading"/);
  assert.match(app, /result.contentType \?\? 'Documentation'/);
});
