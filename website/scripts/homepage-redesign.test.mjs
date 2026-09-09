import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { Resvg } from "@resvg/resvg-js";
import { PNG } from "pngjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("the visual refresh retains the complete existing homepage content", async () => {
  const app = await readFile(path.join(root, "src/App.vue"), "utf8");
  for (const title of [
    "Download for your platform.",
    "Open a file. Review a photo. Read a message.",
    "Native is a behavior, not a coat of paint.",
    "Built for each device, not merely resized.",
    "The work is public.",
    "Follow the whole task, not a list of controls.",
    "Read how the product is put together.",
    "Notes from building the client.",
    "Before you connect a server.",
    "Use it. Read it. Help shape it.",
  ]) assert.ok(app.includes(title), `Missing retained section: ${title}`);
  for (const anchor of ["experience", "downloads", "native", "docs"]) {
    assert.ok(app.includes(`id="${anchor}"`), `Missing existing anchor: ${anchor}`);
  }
  assert.match(app, /v-for="guide in guides"/);
  assert.match(app, /v-for="doc in docs.slice\(0, 6\)"/);
  assert.match(app, /v-for="\(item, index\) in frequentlyAsked"/);
  assert.match(app, /v-for="platform in platforms"/);
  assert.match(app, /githubStarLabel/);
});

test("app exploration and downloads expose keyboard and native dialog semantics", async () => {
  const app = await readFile(path.join(root, "src/App.vue"), "utf8");
  const home = await readFile(path.join(root, "src/components/NativeHome.vue"), "utf8");
  assert.match(home, /<slot\s*\/>/);
  assert.match(home, /role="tablist"/);
  assert.match(home, /role="tabpanel"/);
  assert.match(home, /:aria-selected="activeAppFamily === index"/);
  assert.match(home, /:tabindex="activeAppFamily === index \? 0 : -1"/);
  for (const key of ["ArrowLeft", "ArrowRight", "Home", "End"]) assert.ok(home.includes(`"${key}"`));
  assert.match(home, /selectedAppFamily.apps/);
  assert.match(app, /<dialog id="download-chooser"/);
  assert.match(app, /showModal\(\)/);
  assert.match(app, /aria-label="Close downloads"/);
  assert.match(app, /@click="onDownloadBackdropClick"/);
  assert.match(app, /@keydown\.esc\.prevent="closeDownloads"/);
});

test("both hero captures follow the resolved site theme", async () => {
  const home = await readFile(path.join(root, "src/components/NativeHome.vue"), "utf8");
  assert.match(home, /const heroDesktopCapture = computed\(\(\) => captures.get\(props.theme === "light"\s*\? "homepage-files-desktop-light" : "homepage-files-desktop-dark"\)/);
  assert.match(home, /const mobileHomeCapture = computed\(\(\) => captures.get\(props.theme === "light"\s*\? "homepage-overview-mobile-light" : "homepage-overview-mobile-dark"\)/);
});

test("the website mark has transparency and the font is self-hosted with its license", async () => {
  const svg = await readFile(path.join(root, "public/brand/native-mark.svg"), "utf8");
  const mark = PNG.sync.read(new Resvg(svg).render().asPng());
  assert.equal(mark.width, 128);
  assert.equal(mark.height, 112);
  assert.equal(mark.data[3], 0);
  assert.ok(mark.data.some((value, index) => index % 4 === 3 && value === 255));
  const font = await readFile(path.join(root, "public/fonts/inter-variable.woff2"));
  assert.equal(font.subarray(0, 4).toString(), "wOF2");
  const license = await readFile(path.join(root, "public/fonts/OFL.txt"), "utf8");
  assert.match(license, /SIL OPEN FONT LICENSE Version 1.1/);
});

test("the site-wide refinement preserves article and documentation bodies with accessible navigation", async () => {
  const app = await readFile(path.join(root, "src/App.vue"), "utf8");
  for (const body of ["currentPost.html", "currentDoc.html", "changelog.html", "step.html"]) {
    assert.ok(app.includes(`v-html="${body}"`), `Missing content body: ${body}`);
  }
  assert.match(app, /v-for="post in news"/);
  assert.match(app, /aria-label="Footer navigation"/);
  assert.match(app, /aria-label="Browse documentation"/);
  assert.match(app, /:aria-current="doc.path === currentDoc.path \? 'page' : undefined"/);
  assert.match(app, /v-model="guideSearch" type="search"/);
  assert.match(app, /role="status"/);
  assert.match(app, /v-for="\(guide, guideIndex\) in filteredGuideLibrary"/);
  assert.match(app, /No matching guides/);
  assert.match(app, /@click="guideSearch = ''"/);
  const styles = await readFile(path.join(root, "src/site-refinement.css"), "utf8");
  assert.match(styles, /\.guide-index-media img \{[^}]*object-fit: contain/);
  assert.match(styles, /\.news-index-grid \.news-card-media img \{[^}]*object-fit: contain/);
  assert.match(styles, /\.site-shell.motion-enhanced \[data-reveal\] \{ opacity: 1/);
});
