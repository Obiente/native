import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import test from "node:test";
import {
  checkDiffHasFragment,
  composeChangelogSource,
  composeReleaseNotes,
  defaultRepositoryRoot,
  loadFragments,
  parseFragment,
  renderFragments,
  validateArchivedReleaseHistory,
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

test("normal UTF-8 letters are accepted while control characters are rejected", () => {
  const localized = parseFragment(
    fragment({
      summary: "Synchronisatie toont nu ge\u00ebxporteerde items in een overzicht.",
    }),
    "changes/unreleased/localized.md",
  );
  assert.equal(localized.summary.includes("ge\u00ebxporteerde"), true);
  assert.equal(
    parseFragment(
      fragment({
        summary: "\u00c9\u00e9n overzicht toont nu alle gesynchroniseerde mappen.",
      }),
      "changes/unreleased/localized-start.md",
    ).summary.startsWith("\u00c9"),
    true,
  );
  assert.throws(
    () =>
      parseFragment(
        fragment({
          summary: "\u00e9\u00e9n overzicht begint niet met een hoofdletter.",
        }),
        "changes/unreleased/lowercase-start.md",
      ),
    /must start with an uppercase letter or number/,
  );
  assert.throws(
    () =>
      parseFragment(
        fragment({
          summary: "A control character\u0007 is not valid changelog text.",
        }),
        "changes/unreleased/control.md",
      ),
    /must not contain control characters/,
  );
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

test("security fragments retain their release category", () => {
  const parsed = parseFragment(
    fragment({
      category: "security",
      platforms: "all",
      summary: "Release verification now rejects an unexpected signing identity.",
    }),
    "changes/unreleased/security.md",
  );
  assert.equal(parsed.category, "security");
  assert.match(renderFragments([parsed]), /^### Security$/m);
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
      "- [Android, Desktop] Native collections now provide a focused list and detail workspace (issue #42).",
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
  assert.match(notes, /\[Android, Desktop\]/);
  assert.doesNotMatch(notes, /issue #42/);
  assert.match(notes, /^## Known limitations$/m);
});

test("contributor Node.js guidance matches the website engine exactly", async () => {
  const packageMetadata = JSON.parse(
    await readFile(
      path.join(defaultRepositoryRoot, "website", "package.json"),
      "utf8",
    ),
  );
  const contributing = await readFile(
    path.join(defaultRepositoryRoot, "CONTRIBUTING.md"),
    "utf8",
  );
  assert.equal(
    contributing.includes(`Node.js \`${packageMetadata.engines.node}\``),
    true,
  );
});

test("CI enforces fragments without blocking GitHub-attributed Dependabot changes", async () => {
  const workflow = await readFile(
    path.join(defaultRepositoryRoot, ".github", "workflows", "ci.yml"),
    "utf8",
  );
  assert.match(workflow, /github\.event_name != 'workflow_dispatch'/);
  assert.match(workflow, /github\.event_name != 'pull_request'/);
  assert.match(
    workflow,
    /github\.event\.pull_request\.user\.login != 'dependabot\[bot\]'/,
  );
  assert.match(
    workflow,
    /github\.event\.head_commit\.author\.username != 'dependabot\[bot\]'/,
  );
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

test("loading rejects symlinks and other non-regular fragment entries", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-symlink-"));
  try {
    const unreleased = path.join(root, "changes", "unreleased");
    await mkdir(unreleased, { recursive: true });
    await writeFile(path.join(root, "outside.md"), fragment());
    await symlink(path.join(root, "outside.md"), path.join(unreleased, "42-feature.md"));
    await assert.rejects(
      loadFragments(root),
      /fragment entries must be regular files or directories/,
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
      /needs a newly added changelog fragment/,
    );

    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await writeFile(
      path.join(root, "changes", "unreleased", "42-feature.md"),
      fragment(),
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "fragment"], { cwd: root });
    await checkDiffHasFragment(root, base);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("diff enforcement requires an added unreleased fragment", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-added-fragment-"));
  try {
    await execFileAsync("git", ["init", "-q"], { cwd: root });
    await execFileAsync("git", ["config", "user.name", "Test"], { cwd: root });
    await execFileAsync("git", ["config", "user.email", "test@example.invalid"], {
      cwd: root,
    });
    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await writeFile(path.join(root, "app.txt"), "one\n");
    await writeFile(
      path.join(root, "changes", "unreleased", "42-feature.md"),
      fragment(),
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "base"], { cwd: root });
    const { stdout } = await execFileAsync("git", ["rev-parse", "HEAD"], {
      cwd: root,
    });
    const base = stdout.trim();

    await writeFile(path.join(root, "app.txt"), "two\n");
    await writeFile(
      path.join(root, "changes", "unreleased", "42-feature.md"),
      fragment({
        summary: "Native collections now provide a revised list and detail workspace.",
      }),
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "modify old fragment"], {
      cwd: root,
    });
    await assert.rejects(
      checkDiffHasFragment(root, base),
      /needs a newly added changelog fragment/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("diff enforcement protects archived fragments and permits release moves", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-archive-diff-"));
  try {
    await execFileAsync("git", ["init", "-q"], { cwd: root });
    await execFileAsync("git", ["config", "user.name", "Test"], { cwd: root });
    await execFileAsync("git", ["config", "user.email", "test@example.invalid"], {
      cwd: root,
    });
    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await mkdir(path.join(root, "changes", "archive", "0.1.0-alpha.1"), {
      recursive: true,
    });
    const archived = path.join(
      root,
      "changes",
      "archive",
      "0.1.0-alpha.1",
      "8-fix.md",
    );
    await writeFile(
      archived,
      fragment({
        category: "fix",
        issue: "none",
        pull: "8",
        platforms: "all",
        summary: "The first preview now opens without duplicate navigation.",
      }),
    );
    const unreleased = path.join(root, "changes", "unreleased", "42-feature.md");
    await writeFile(unreleased, fragment());
    await mkdir(path.join(root, "docs", "release-notes"), { recursive: true });
    await writeFile(
      path.join(root, "CHANGELOG.md"),
      "# Changelog\n\n## Unreleased\n\n## [0.1.0-alpha.1]\n\n### Fixes\n\n- First preview.\n",
    );
    await writeFile(
      path.join(root, "docs", "release-notes", "0.1.0-alpha.1.md"),
      "# Nextcloud Native 0.1.0-alpha.1\n\n## Fixes\n\n- First preview.\n",
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "base"], { cwd: root });
    const { stdout } = await execFileAsync("git", ["rev-parse", "HEAD"], {
      cwd: root,
    });
    const base = stdout.trim();

    await writeFile(
      archived,
      fragment({
        category: "fix",
        issue: "none",
        pull: "8",
        platforms: "all",
        summary: "Published history was rewritten after the release shipped.",
      }),
    );
    await execFileAsync("git", ["add", "changes"], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "rewrite archive"], {
      cwd: root,
    });
    await assert.rejects(
      checkDiffHasFragment(root, base),
      /archived changelog fragments are immutable/,
    );

    await execFileAsync("git", ["reset", "--hard", base], { cwd: root });
    const releaseDirectory = path.join(
      root,
      "changes",
      "archive",
      "0.2.0-alpha.1",
    );
    await mkdir(releaseDirectory, { recursive: true });
    await execFileAsync(
      "git",
      [
        "mv",
        "changes/unreleased/42-feature.md",
        "changes/archive/0.2.0-alpha.1/42-feature.md",
      ],
      { cwd: root },
    );
    await writeFile(
      path.join(root, "CHANGELOG.md"),
      [
        "# Changelog",
        "",
        "## Unreleased",
        "",
        "## [0.2.0-alpha.1]",
        "",
        "### Features",
        "",
        "- Native collections now provide a focused list and detail workspace.",
        "",
        "## [0.1.0-alpha.1]",
        "",
        "### Fixes",
        "",
        "- First preview.",
        "",
      ].join("\n"),
    );
    await writeFile(
      path.join(root, "docs", "release-notes", "0.2.0-alpha.1.md"),
      "# Nextcloud Native 0.2.0-alpha.1\n\n## Features\n\n- Native collections now provide a focused list and detail workspace.\n",
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "archive release"], {
      cwd: root,
    });
    await checkDiffHasFragment(root, base);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("diff enforcement protects released changelog sections and release notes", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-release-history-"));
  try {
    await execFileAsync("git", ["init", "-q"], { cwd: root });
    await execFileAsync("git", ["config", "user.name", "Test"], { cwd: root });
    await execFileAsync("git", ["config", "user.email", "test@example.invalid"], {
      cwd: root,
    });
    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await mkdir(path.join(root, "docs", "release-notes"), { recursive: true });
    await writeFile(path.join(root, "app.txt"), "one\n");
    await writeFile(
      path.join(root, "CHANGELOG.md"),
      "# Changelog\n\n## Unreleased\n\n## [0.1.0-alpha.1]\n\n### Features\n\n- First preview.\n",
    );
    await writeFile(
      path.join(root, "docs", "release-notes", "0.1.0-alpha.1.md"),
      "# Nextcloud Native 0.1.0-alpha.1\n\n## Features\n\n- First preview.\n",
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "base"], { cwd: root });
    const { stdout } = await execFileAsync("git", ["rev-parse", "HEAD"], {
      cwd: root,
    });
    const base = stdout.trim();

    await writeFile(path.join(root, "app.txt"), "two\n");
    await writeFile(
      path.join(root, "changes", "unreleased", "43-feature.md"),
      fragment({ issue: "43" }),
    );
    await writeFile(
      path.join(root, "CHANGELOG.md"),
      "# Changelog\n\n## Unreleased\n\n## [0.1.0-alpha.1]\n\n### Features\n\n- Rewritten preview.\n",
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "rewrite changelog"], {
      cwd: root,
    });
    await assert.rejects(
      checkDiffHasFragment(root, base),
      /released section 0.1.0-alpha.1 is immutable/,
    );

    await execFileAsync("git", ["reset", "--hard", base], { cwd: root });
    await mkdir(path.join(root, "changes", "unreleased"), { recursive: true });
    await writeFile(path.join(root, "app.txt"), "two\n");
    await writeFile(
      path.join(root, "changes", "unreleased", "43-feature.md"),
      fragment({ issue: "43" }),
    );
    await writeFile(
      path.join(root, "docs", "release-notes", "0.1.0-alpha.1.md"),
      "# Nextcloud Native 0.1.0-alpha.1\n\nChanged after release.\n",
    );
    await execFileAsync("git", ["add", "."], { cwd: root });
    await execFileAsync("git", ["commit", "-qm", "rewrite notes"], { cwd: root });
    await assert.rejects(
      checkDiffHasFragment(root, base),
      /existing release-note files are immutable/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("archived fragments reconcile with changelog and release notes", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "nc-native-reconcile-"));
  const version = "0.2.0-alpha.1";
  const expected =
    "[Android, Desktop] Native collections now provide a focused list and detail workspace.";
  try {
    const archive = path.join(root, "changes", "archive", version);
    await mkdir(archive, { recursive: true });
    await mkdir(path.join(root, "docs", "release-notes"), { recursive: true });
    await writeFile(path.join(archive, "42-feature.md"), fragment());
    const changelog = [
      "# Changelog",
      "",
      "## Unreleased",
      "",
      `## [${version}]`,
      "",
      "### Features",
      "",
      `- ${expected.slice(0, -1)} (issue #42).`,
      "",
    ].join("\n");
    await writeFile(path.join(root, "CHANGELOG.md"), changelog);
    const releaseNotePath = path.join(
      root,
      "docs",
      "release-notes",
      `${version}.md`,
    );
    await writeFile(
      releaseNotePath,
      [
        `# Nextcloud Native ${version}`,
        "",
        "## Features",
        "",
        `- ${expected}`,
        "",
        "## Known limitations",
        "",
        "- Testing build.",
        "",
      ].join("\n"),
    );
    await validateArchivedReleaseHistory(root);

    const wrongCategoryChangelog = changelog.replace(
      "### Features",
      "### Fixes",
    );
    const wrongCategoryReleaseNote = [
      `# Nextcloud Native ${version}`,
      "",
      "## Fixes",
      "",
      `- ${expected}`,
      "",
      "## Known limitations",
      "",
      "- Testing build.",
      "",
    ].join("\n");
    await writeFile(path.join(root, "CHANGELOG.md"), wrongCategoryChangelog);
    await writeFile(releaseNotePath, wrongCategoryReleaseNote);
    await assert.rejects(
      validateArchivedReleaseHistory(root),
      /do not match its archived fragments/,
    );
    await writeFile(path.join(root, "CHANGELOG.md"), changelog);
    await writeFile(
      releaseNotePath,
      [
        `# Nextcloud Native ${version}`,
        "",
        "## Features",
        "",
        `- ${expected}`,
        "",
        "## Known limitations",
        "",
        "- Testing build.",
        "",
      ].join("\n"),
    );

    await writeFile(path.join(root, "CHANGELOG.md"), "# Changelog\n\n## Unreleased\n");
    await assert.rejects(
      validateArchivedReleaseHistory(root),
      /needs a corresponding released section/,
    );
    await writeFile(path.join(root, "CHANGELOG.md"), changelog);

    await rm(releaseNotePath);
    await assert.rejects(
      validateArchivedReleaseHistory(root),
      /archived fragments require this immutable release record/,
    );
    await writeFile(
      releaseNotePath,
      `# Nextcloud Native ${version}\n\n## Features\n\n- A different entry.\n`,
    );
    await assert.rejects(
      validateArchivedReleaseHistory(root),
      /release-notes.*do not match its archived fragments/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
