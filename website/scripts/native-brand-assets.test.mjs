import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { Resvg } from "@resvg/resvg-js";
import { socialImageDetailsFor } from "../src/server-metadata.js";

test("social preview pixels match declared metadata and render without system fonts", async () => {
  const root = new URL("../../", import.meta.url);
  const vector = await readFile(new URL("design/brand/social-preview.svg", root), "utf8");
  assert.doesNotMatch(vector, /<text\b/i);
  const rendered = new Resvg(vector, { font: { loadSystemFonts: false } }).render();
  const metadata = socialImageDetailsFor({});
  assert.equal(rendered.width, metadata.width);
  assert.equal(rendered.height, metadata.height);
  for (const asset of ["website/public/social-preview.png", ".github/social-preview.png"]) {
    assert.deepEqual(await readFile(new URL(asset, root)), rendered.asPng());
  }
});

test("crawler discovery uses the canonical sitemap origin", async () => {
  const robots = await readFile(new URL("../public/robots.txt", import.meta.url), "utf8");
  assert.match(robots, /^Sitemap: https:\/\/nati\.ve\/sitemap\.xml$/m);
});
