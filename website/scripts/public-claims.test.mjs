import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("machine-readable product claims match current Android and Linux alpha availability", async () => {
  const [entryServer, app, manifest] = await Promise.all([
    readFile(path.join(websiteRoot, "src", "entry-server.js"), "utf8"),
    readFile(path.join(websiteRoot, "src", "App.vue"), "utf8"),
    readFile(path.join(websiteRoot, "public", "site.webmanifest"), "utf8"),
  ]);

  assert.match(entryServer, /operatingSystem: "Android, Linux"/);
  assert.doesNotMatch(entryServer, /operatingSystem: "[^"]*(iOS|Windows|macOS)/);
  assert.doesNotMatch(entryServer, /Native Nextcloud user, app, and server administration/);
  assert.match(entryServer, /folder-pair sync is in active development/);
  assert.match(entryServer, /photo backup and safe storage recovery are in active development/);

  assert.match(
    app,
    /name: "Windows and macOS",[\s\S]*?status: "Packaging preview",[\s\S]*?authenticated use are not implemented yet/,
  );
  assert.match(app, /name: "iOS and iPadOS",[\s\S]*?status: "Planned"/);
  assert.match(app, /name: "Android",[\s\S]*?status: "Alpha build"/);
  assert.match(app, /name: "Linux",[\s\S]*?status: "Alpha build"/);
  assert.match(app, /href="https:\/\/github\.com\/Obiente\/nc-native\/releases"/);
  assert.doesNotMatch(app, /releases\/latest/);
  assert.match(app, /completion status tracked on the public roadmap/);

  const parsedManifest = JSON.parse(manifest);
  assert.match(parsedManifest.description, /early alpha/i);
  assert.match(parsedManifest.description, /Android and Linux/);
});
