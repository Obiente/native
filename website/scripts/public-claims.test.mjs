import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("machine-readable product claims match current supported platforms and alpha capability", async () => {
  const [entryServer, app, manifest] = await Promise.all([
    readFile(path.join(websiteRoot, "src", "entry-server.js"), "utf8"),
    readFile(path.join(websiteRoot, "src", "App.vue"), "utf8"),
    readFile(path.join(websiteRoot, "public", "site.webmanifest"), "utf8"),
  ]);

  assert.match(entryServer, /operatingSystem: "Android, Linux, Windows"/);
  assert.match(entryServer, /applicationCategory: "UtilitiesApplication"/);
  assert.match(entryServer, /offers:\s*\{\s*"@type": "Offer",\s*price: 0,/s);
  assert.match(entryServer, /Android offline storage, and guarded folder sync/);
  assert.match(entryServer, /Native Talk history, Notes editing, Calendar, Activity/);
  assert.doesNotMatch(entryServer, /Talk messaging and calls|iOS, iPadOS|non-destructive editing/);

  assert.match(app, /name: "Android mobile and tablet"/);
  assert.match(app, /iPhone and iPad builds are planned but not available/);
  assert.match(app, /name: "Linux and Windows desktop"/);
  assert.match(app, /macOS has an early packaging artifact/);
  assert.match(app, /href="https:\/\/github\.com\/Obiente\/nc-native\/releases"/);
  assert.doesNotMatch(app, /releases\/latest/);
  assert.match(app, /Your Nextcloud deserves/);
  assert.match(app, /Platform status/);
  assert.match(app, /aria-label="macOS preview"[^>]*>.*<span aria-hidden="true">macOS<\/span>/);
  assert.match(app, /aria-label="iOS unavailable"[^>]*>.*<span aria-hidden="true">iOS<\/span>/);
  assert.match(app, /name: "macOS",/);
  assert.doesNotMatch(app, /name: "macOS preview",/);
  assert.doesNotMatch(app, /Nightly is the (?:default|current) release path|short links|curated Stable release/i);
  assert.match(app, /AGPL-3\.0-or-later/);
  assert.doesNotMatch(app, /No Obiente account|No hosted intermediary|Obiente never carries your data/);
  assert.match(app, /Built by <strong>Obiente<\/strong>\. Independent and/);
  assert.match(app, /Packages on GitHub/);
  assert.doesNotMatch(app, /Independent\. Open source\. Yours\./);
  assert.match(app, /href="\/roadmap\/"/);
  assert.match(app, /href="\/news\/"/);
  assert.match(app, /current Android, Linux, and Windows alpha builds/);
  assert.doesNotMatch(app, /Answer a call|Storage cleanup is offered/);

  const parsedManifest = JSON.parse(manifest);
  assert.equal(
    parsedManifest.description,
    "Open-source native Nextcloud alpha for Android, Linux, and Windows.",
  );
});
