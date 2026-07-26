import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";
import {
  checkDiffHasFragment,
  composeChangelogSource,
  composeReleaseNotes,
  loadFragments,
  parseFragment,
  renderFragments,
} from "./changelog-fragments.mjs";

const execFileAsync = promisify(execFile);

function fragment(overrides = {}) {
  const values = {
    category: "feature",
    issue: "42",
    pull: "none",
    platforms: "android, desktop",
    userFacing: "yes",
    summary: "Native collections now provide a focused list and detail workspace.",
    ...overrides,
  };
  return [
    `category: ${values.category}`,
    `issue: ${values.issue}`,
    `pull: ${values.pull}`,
    `platforms: ${values.platforms}`,
    `user-facing: ${values.userFacing}`,
    "",
    values.summary,
    "",
  ].join("\n");
}

test("strict fragment metadata parses into a stable record", () => {
  assert.deepEqual(parseFragment(fragment(), "changes/unreleased/42.md"), {
    category: "feature",
    issue: 42,
    pull: null,
    platforms: ["android", "desktop"],
    relativePath: "changes/unreleased/42.md",
    summary: "Native collections now provide a focused list and detail workspace.",
    userFacing: true,
  });
});

test("internal work requires an explicit no-user-facing marker", () => {
  assert.throws(
    () =>
      parseFragment(
        fragment({ category: "internal", userFacing: "yes" }),
        "changes/unreleased/internal.md",
      ),
    /internal fragments must use user-facing: no/,
  );
  const parsed = parseFragment(
    fragment({
      category: "internal",
      userFacing: "no",
      summary: "Repository checks now validate independent changelog fragments.",
    }),
  );
  assert.equal(parsed.userFacing, false);
});

test("rendering is deterministic and omits internal implementation records", () => {
  const feature = parseFragment(fragment(), "changes/unreleased/z-feature.md");
  const fix = parseFragment(
    fragment({
      category: "fix",
      issue: "none",
      pull: "8",
      platforms: "all",
      summary: "Account switching now preserves the selected workspace.",
    }),
    "changes/unreleased/a-fix.md",
  );
  const internal = parseFragment(
    fragment({
      category: "internal",
      issue: "9",
      platforms: "all",
      userFacing: "no",
      summary: "Repository checks now validate independent changelog fragments.",
    }),
    "changes/unreleased/internal.md",
  );
  assert.equal(
    renderFragments([internal, fix, feature]),
    [
      "### Features",
      "",
      "- Native collections now provide a focused list and detail workspace (issue #42).",
      "",
      "### Fixes",
      "",
      "- Account switching now preserves the selected workspace (PR #8).",
    ].join("\n"),
  );
});

test("website changelog composition replaces only the live Unreleased section", () => {
  const legacy = [
    "# Changelog",
    "",
    "History.",
    "",
    "## Unreleased",
    "",
    "Old generated text.",
    "",
    "## [0.1.0-alpha.1]",
    "",
    "### Added",
    "",
    "- First preview.",
    "",
  ].join("\n");
  const parsed = parseFragment(fragment(), "changes/unreleased/42.md");
  const composed = composeChangelogSource(legacy, [parsed]);
  assert.match(composed, /## Unreleased\n\n### Features/);
  assert.doesNotMatch(composed, /Old generated text/);
  assert.match(composed, /## \[0\.1\.0-alpha\.1\]/);
});

test("release note preparation shares the user-facing aggregation", () => {
  const parsed = parseFragment(fragment(), "changes/unreleased/42.md");
  const notes = composeReleaseNotes("0.2.0-alpha.1", [parsed]);
  assert.match(notes, /^# Nextcloud Native 0\.2\.0-alpha\.1/m);
  assert.match(notes, /^## Features$/m);
  assert.doesNotMatch(notes, /issue #42/);
  assert.match(notes, /^## Known limitations$/m);
});

test("loading validates archived and unreleased fragments", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-changes-"));
  try {
    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await mkdir(path.join(root, "changes", "archive", "0.1.0-alpha.1"), {
      recursive: true,
    });
    await writeFile(
      path.join(root, "changes", "unreleased", "42-feature.md"),
      fragment(),
    );
    await writeFile(
      path.join(
        root,
        "changes",
        "archive",
        "0.1.0-alpha.1",
        "8-fix.md",
      ),
      fragment({
        category: "fix",
        issue: "none",
        pull: "8",
        platforms: "all",
        summary: "The first preview now opens without duplicate navigation.",
      }),
    );
    assert.equal((await loadFragments(root)).length, 1);
    assert.equal(
      (await loadFragments(root, { includeArchive: true })).length,
      2,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("diff enforcement accepts a fragment and rejects unrecorded work", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-diff-"));
  try {
    await execFileAsync("git", ["init", "-q"], { cwd: root });
    await execFileAsync("git", ["config", "user.name", "Test"], { cwd: root });
    await execFileAsync("git", ["config", "user.email", "test@example.invalid"], {
      cwd: root,
    });
    await writeFile(path.join(root, "app.txt"), "one\n");
    await execFileAsync("git", ["add", "app.txt"], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "base"], { cwd: root });
    const { stdout } = await execFileAsync("git", ["rev-parse", "HEAD"], {
      cwd: root,
    });
    const base = stdout.trim();

    await writeFile(path.join(root, "app.txt"), "two\n");
    await execFileAsync("git", ["add", "app.txt"], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "change"], { cwd: root });
    await assert.rejects(
      checkDiffHasFragment(root, base),
      /needs a changelog fragment/,
    );

    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await writeFile(
      path.join(root, "changes", "unreleased", "42-feature.md"),
      fragment(),
    );
    await execFileAsync("git", ["add", "changes"], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "fragment"], { cwd: root });
    await checkDiffHasFragment(root, base);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
