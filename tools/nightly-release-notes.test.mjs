import assert from "node:assert/strict";
import test from "node:test";

import { parseFragment } from "./changelog-fragments.mjs";
import { composeNightlyReleaseNotes } from "./nightly-release-notes.mjs";

const sourceSha = "0123456789abcdef0123456789abcdef01234567";
const baseOptions = {
  availablePlatforms: new Set(["android", "linux"]),
  fragments: [
    parseFragment(
      [
        "category: feature",
        "issue: 237",
        "pull: none",
        "platforms: android, linux",
        "user-facing: yes",
        "",
        "Direct installs now report when a verified update is available.",
        "",
      ].join("\n"),
    ),
    parseFragment(
      [
        "category: internal",
        "issue: 103",
        "pull: none",
        "platforms: all",
        "user-facing: no",
        "",
        "Release automation now emits richer nightly descriptions.",
        "",
      ].join("\n"),
    ),
  ],
  repository: "Obiente/nc-native",
  sourceRunUrl: "https://github.com/Obiente/nc-native/actions/runs/123456",
  sourceSha,
  tag: "nightly-20260731-1543-run358-01234567",
};

test("nightly notes are useful, traceable, and omit internal fragments", () => {
  const notes = composeNightlyReleaseNotes(baseOptions);

  assert.match(notes, /## Current development highlights/);
  assert.match(notes, /cumulative and are not limited to changes since the previous nightly/);
  assert.match(
    notes,
    /Direct installs now report when a verified update is available \(issue #237\)\./,
  );
  assert.doesNotMatch(notes, /Release automation now emits richer nightly descriptions/);
  assert.match(notes, /\| Android \| \.apk \| Available \|/);
  assert.match(notes, /\| Linux \| \.deb and \.rpm \| Available \|/);
  assert.match(notes, /\| Windows \| \.msi \| Unavailable \|/);
  assert.match(notes, new RegExp(`/commit/${sourceSha.replaceAll("/", "\\/")}`));
  assert.match(notes, /Updates are never downloaded or installed silently\./);
});

test("nightly notes reject an untrusted source URL", () => {
  assert.throws(
    () => composeNightlyReleaseNotes({ ...baseOptions, sourceRunUrl: "https://example.com/run" }),
    /sourceRunUrl must be a GitHub Actions run URL/,
  );
});
