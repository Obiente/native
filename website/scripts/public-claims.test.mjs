import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const websiteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

test("machine-readable product claims present the stable complete product experience", async () => {
  const [entryServer, app, manifest] = await Promise.all([
    readFile(path.join(websiteRoot, "src", "entry-server.js"), "utf8"),
    readFile(path.join(websiteRoot, "src", "App.vue"), "utf8"),
    readFile(path.join(websiteRoot, "public", "site.webmanifest"), "utf8"),
  ]);

  assert.match(entryServer, /operatingSystem: "Android, iOS, iPadOS, Linux, Windows, macOS"/);
  assert.match(entryServer, /Files, previews, sharing, version history, offline folders, and two-way sync/);
  assert.match(entryServer, /Talk messaging and calls, Mail, Contacts, Calendar, Tasks/);
  assert.match(entryServer, /Recognize people, albums, Live Photos, backup, sharing, and non-destructive editing/);
  assert.match(entryServer, /administration/);
  assert.doesNotMatch(entryServer, /early alpha|active development|coming soon|not implemented|planned platform/i);

  assert.match(app, /name: "Mobile and tablet"/);
  assert.match(app, /Android, iPhone, and iPad/);
  assert.match(app, /name: "Desktop"/);
  assert.match(app, /Linux, Windows, and macOS/);
  assert.match(app, /href="https:\/\/github\.com\/Obiente\/nc-native\/releases"/);
  assert.doesNotMatch(app, /releases\/latest/);
  assert.match(app, /Your Nextcloud deserves/);
  assert.match(app, /Available for/);
  assert.match(app, /class="platform-pending"[^>]*>.*<span>macOS<\/span>/);
  assert.match(app, /class="platform-pending"[^>]*>.*<span>iOS<\/span>/);
  assert.match(app, /AGPL-3\.0-or-later/);
  assert.doesNotMatch(app, /No Obiente account|No hosted intermediary|Obiente never carries your data/);
  assert.match(app, /Built by <strong>Obiente<\/strong>\. Independent and/);
  assert.match(app, /Packages on GitHub/);
  assert.doesNotMatch(app, /Independent\. Open source\. Yours\./);
  assert.match(app, /href="\/roadmap\/"/);
  assert.match(app, /href="\/news\/"/);
  assert.doesNotMatch(app, /alpha build|active development|packaging preview|status: "Planned"|completion status/i);

  const parsedManifest = JSON.parse(manifest);
  assert.equal(
    parsedManifest.description,
    "One genuinely native client for your complete Nextcloud account.",
  );
});
