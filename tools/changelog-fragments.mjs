#!/usr/bin/env node

import { execFile } from "node:child_process";
import { readFile, readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const moduleDirectory = path.dirname(fileURLToPath(import.meta.url));
export const defaultRepositoryRoot = path.resolve(moduleDirectory, "..");

export const categoryOrder = ["feature", "fix", "platform", "docs", "internal"];
export const categoryHeadings = {
  feature: "Features",
  fix: "Fixes",
  platform: "Platform",
  docs: "Documentation",
  internal: "Internal",
};
export const supportedPlatforms = [
  "all",
  "android",
  "desktop",
  "ios",
  "linux",
  "macos",
  "website",
  "windows",
];

const metadataKeys = ["category", "issue", "pull", "platforms", "user-facing"];
const fragmentNamePattern = /^[a-z0-9][a-z0-9-]{1,79}\.md$/;
const positiveIntegerPattern = /^[1-9][0-9]*$/;
const archivedFragmentPattern =
  /^changes\/archive\/0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[1-9][0-9]*\/[a-z0-9][a-z0-9-]*\.md$/;
const prereleaseVersionPattern =
  /^0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[1-9][0-9]*$/;

function fail(message) {
  throw new Error(message);
}

function parseReference(value, field, relativePath) {
  if (value === "none") return null;
  if (!positiveIntegerPattern.test(value)) {
    fail(`${relativePath}: ${field} must be a positive integer or "none".`);
  }
  const reference = Number(value);
  if (!Number.isSafeInteger(reference)) {
    fail(`${relativePath}: ${field} is larger than a safe integer.`);
  }
  return reference;
}

function assertPlainText(value, relativePath) {
  for (const character of value) {
    const codepoint = character.codePointAt(0);
    if (
      codepoint === 0x00 ||
      (codepoint < 0x20 && character !== "\n") ||
      codepoint === 0x7f
    ) {
      fail(`${relativePath}: fragments must not contain control characters.`);
    }
  }
}

export function parseFragment(source, relativePath = "fragment.md") {
  assertPlainText(source, relativePath);
  if (source.includes("\r")) {
    fail(`${relativePath}: use LF line endings.`);
  }
  if (!source.endsWith("\n")) {
    fail(`${relativePath}: the file must end with a newline.`);
  }

  const separator = source.indexOf("\n\n");
  if (separator < 0) {
    fail(`${relativePath}: add one blank line between metadata and summary.`);
  }
  const metadataLines = source.slice(0, separator).split("\n");
  const summary = source.slice(separator + 2).trim();
  const metadata = new Map();

  for (const line of metadataLines) {
    const match = /^([a-z-]+): (.+)$/.exec(line);
    if (!match) {
      fail(`${relativePath}: invalid metadata line "${line}".`);
    }
    const [, key, value] = match;
    if (!metadataKeys.includes(key)) {
      fail(`${relativePath}: unknown metadata key "${key}".`);
    }
    if (metadata.has(key)) {
      fail(`${relativePath}: duplicate metadata key "${key}".`);
    }
    metadata.set(key, value);
  }

  const actualKeys = [...metadata.keys()];
  if (
    actualKeys.length !== metadataKeys.length ||
    actualKeys.some((key, index) => key !== metadataKeys[index])
  ) {
    fail(
      `${relativePath}: metadata keys must appear once in this order: ${metadataKeys.join(", ")}.`,
    );
  }

  const category = metadata.get("category");
  if (!categoryOrder.includes(category)) {
    fail(
      `${relativePath}: category must be one of ${categoryOrder.join(", ")}.`,
    );
  }

  const issue = parseReference(metadata.get("issue"), "issue", relativePath);
  const pull = parseReference(metadata.get("pull"), "pull", relativePath);
  if (issue === null && pull === null) {
    fail(`${relativePath}: provide an issue or pull request reference.`);
  }

  const platforms = metadata.get("platforms").split(", ");
  if (
    platforms.length === 0 ||
    platforms.some((platform) => !supportedPlatforms.includes(platform))
  ) {
    fail(
      `${relativePath}: platforms must use ${supportedPlatforms.join(", ")}.`,
    );
  }
  if (new Set(platforms).size !== platforms.length) {
    fail(`${relativePath}: platforms must not contain duplicates.`);
  }
  if (platforms.includes("all") && platforms.length !== 1) {
    fail(`${relativePath}: "all" cannot be combined with another platform.`);
  }
  const sortedPlatforms = [...platforms].sort(
    (left, right) =>
      supportedPlatforms.indexOf(left) - supportedPlatforms.indexOf(right),
  );
  if (platforms.some((platform, index) => platform !== sortedPlatforms[index])) {
    fail(
      `${relativePath}: platforms must follow this order: ${supportedPlatforms.join(", ")}.`,
    );
  }

  const userFacingValue = metadata.get("user-facing");
  if (!["yes", "no"].includes(userFacingValue)) {
    fail(`${relativePath}: user-facing must be "yes" or "no".`);
  }
  const userFacing = userFacingValue === "yes";
  if (category === "internal" && userFacing) {
    fail(`${relativePath}: internal fragments must use user-facing: no.`);
  }
  if (category !== "internal" && !userFacing) {
    fail(
      `${relativePath}: non-internal fragments must use user-facing: yes.`,
    );
  }

  if (summary.includes("\n")) {
    fail(`${relativePath}: summary must be one paragraph on one line.`);
  }
  if (summary.length < 20 || summary.length > 240) {
    fail(`${relativePath}: summary must contain between 20 and 240 characters.`);
  }
  if (!/^(?:\p{Lu}|\p{Lt}|\p{N})/u.test(summary)) {
    fail(`${relativePath}: summary must start with an uppercase letter or number.`);
  }
  if (!/[.!?]$/.test(summary)) {
    fail(`${relativePath}: summary must end with punctuation.`);
  }

  return {
    category,
    issue,
    pull,
    platforms,
    relativePath,
    summary,
    userFacing,
  };
}

async function walkMarkdownFiles(directory, repositoryRoot) {
  let entries;
  try {
    entries = await readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }

  const files = [];
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const absolutePath = path.join(directory, entry.name);
    const relativePath = path.relative(repositoryRoot, absolutePath);
    if (entry.isSymbolicLink() || (!entry.isDirectory() && !entry.isFile())) {
      fail(`${relativePath}: changelog fragment entries must be regular files or directories.`);
    }
    if (entry.isDirectory()) {
      files.push(...(await walkMarkdownFiles(absolutePath, repositoryRoot)));
    } else if (entry.isFile() && entry.name.endsWith(".md")) {
      if (!fragmentNamePattern.test(entry.name)) {
        fail(
          `${relativePath}: use a lowercase hyphenated fragment filename.`,
        );
      }
      files.push(absolutePath);
    }
  }
  return files;
}

export async function loadFragments(
  repositoryRoot = defaultRepositoryRoot,
  { includeArchive = false } = {},
) {
  const changesRoot = path.join(repositoryRoot, "changes");
  const directories = [path.join(changesRoot, "unreleased")];
  if (includeArchive) directories.push(path.join(changesRoot, "archive"));

  const files = [];
  for (const directory of directories) {
    files.push(...(await walkMarkdownFiles(directory, repositoryRoot)));
  }

  const fragments = [];
  for (const file of files.sort()) {
    const relativePath = path.relative(repositoryRoot, file);
    if (
      relativePath.startsWith(`changes${path.sep}archive${path.sep}`) &&
      !archivedFragmentPattern.test(relativePath.split(path.sep).join("/"))
    ) {
      fail(
        `${relativePath}: archived fragments must be under changes/archive/<prerelease-version>/.`,
      );
    }
    fragments.push(parseFragment(await readFile(file, "utf8"), relativePath));
  }
  return fragments;
}

function compareAscii(left, right) {
  if (left === right) return 0;
  return left < right ? -1 : 1;
}

export function sortFragments(fragments) {
  return [...fragments].sort((left, right) => {
    const categoryDifference =
      categoryOrder.indexOf(left.category) - categoryOrder.indexOf(right.category);
    if (categoryDifference !== 0) return categoryDifference;
    const summaryDifference = compareAscii(left.summary, right.summary);
    if (summaryDifference !== 0) return summaryDifference;
    return compareAscii(left.relativePath, right.relativePath);
  });
}

function contextSuffix(fragment) {
  const links = [];
  if (fragment.issue !== null) links.push(`issue #${fragment.issue}`);
  if (fragment.pull !== null) links.push(`PR #${fragment.pull}`);
  return links.length === 0 ? "" : ` (${links.join(", ")})`;
}

const platformLabels = {
  android: "Android",
  desktop: "Desktop",
  ios: "iOS",
  linux: "Linux",
  macos: "macOS",
  website: "Website",
  windows: "Windows",
};

function platformPrefix(fragment) {
  if (fragment.platforms.includes("all")) return "";
  const labels = fragment.platforms.map((platform) => platformLabels[platform]);
  return `[${labels.join(", ")}] `;
}

function renderFragmentEntry(fragment, includeContext) {
  const suffix = includeContext ? contextSuffix(fragment) : "";
  const prefix = platformPrefix(fragment);
  if (!suffix) return `${prefix}${fragment.summary}`;
  const punctuation = fragment.summary.at(-1);
  const summary = fragment.summary.slice(0, -1);
  return `${prefix}${summary}${suffix}${punctuation}`;
}

export function renderFragments(
  fragments,
  { headingLevel = 3, includeInternal = false, includeContext = true } = {},
) {
  const visible = sortFragments(fragments).filter(
    (fragment) => includeInternal || fragment.userFacing,
  );
  const sections = [];

  for (const category of categoryOrder) {
    if (category === "internal" && !includeInternal) continue;
    const entries = visible.filter((fragment) => fragment.category === category);
    if (entries.length === 0) continue;
    sections.push(`${"#".repeat(headingLevel)} ${categoryHeadings[category]}`);
    sections.push("");
    for (const fragment of entries) {
      sections.push(`- ${renderFragmentEntry(fragment, includeContext)}`);
    }
    sections.push("");
  }
  return sections.join("\n").trimEnd();
}

export function composeChangelogSource(legacySource, fragments) {
  const rendered = renderFragments(fragments, {
    headingLevel: 3,
    includeContext: true,
  });
  const unreleased = rendered
    ? `## Unreleased\n\n${rendered}`
    : "## Unreleased";

  const unreleasedMatch = /^## Unreleased\s*$/m.exec(legacySource);
  if (!unreleasedMatch) {
    fail("CHANGELOG.md must contain an Unreleased section.");
  }
  const sectionStart = unreleasedMatch.index;
  const contentStart = sectionStart + unreleasedMatch[0].length;
  const nextSectionMatch = /^## (?!Unreleased\b).+$/m.exec(
    legacySource.slice(contentStart),
  );
  const sectionEnd = nextSectionMatch
    ? contentStart + nextSectionMatch.index
    : legacySource.length;
  const prefix = legacySource.slice(0, sectionStart).trimEnd();
  const suffix = legacySource.slice(sectionEnd).trimStart();
  return `${prefix}\n\n${unreleased}\n\n${suffix}`.trimEnd() + "\n";
}

export function composeReleaseNotes(version, fragments) {
  if (!prereleaseVersionPattern.test(version)) {
    fail(
      "Release version must use 0.x.y-alpha.n, 0.x.y-beta.n, or 0.x.y-rc.n.",
    );
  }
  const body = renderFragments(fragments, {
    headingLevel: 2,
    includeContext: false,
  });
  if (!body) fail("No user-facing unreleased fragments are available.");
  return [
    `# Nextcloud Native ${version}`,
    "",
    body,
    "",
    "## Known limitations",
    "",
    "<!-- Curate limitations before publishing. -->",
    "",
  ].join("\n");
}

function normalizeRenderedEntry(entry) {
  return entry
    .replace(/\s+/gu, " ")
    .trim()
    .replace(
      /\s+\((?:issue #[0-9]+|PR #[0-9]+)(?:, (?:issue #[0-9]+|PR #[0-9]+))*\)([.!?])$/u,
      "$1",
    );
}

function extractRenderedEntries(source) {
  const headings = new Set(Object.values(categoryHeadings));
  const entries = [];
  let inCategory = false;
  let current = null;
  const flush = () => {
    if (current !== null) entries.push(normalizeRenderedEntry(current));
    current = null;
  };
  for (const line of source.split("\n")) {
    const heading = /^#{2,4}\s+(.+?)\s*$/u.exec(line);
    if (heading) {
      flush();
      inCategory = headings.has(heading[1]);
      continue;
    }
    if (!inCategory) continue;
    if (line.startsWith("- ")) {
      flush();
      current = line.slice(2);
    } else if (current !== null && line.trim()) {
      current += ` ${line.trim()}`;
    }
  }
  flush();
  return entries;
}

async function readReleaseRecord(repositoryRoot, relativePath) {
  try {
    return await readFile(path.join(repositoryRoot, relativePath), "utf8");
  } catch (error) {
    if (error.code === "ENOENT") {
      fail(`${relativePath}: archived fragments require this immutable release record.`);
    }
    throw error;
  }
}

function assertReleaseEntries(version, recordName, expected, actual) {
  if (
    expected.length !== actual.length ||
    expected.some((entry, index) => entry !== actual[index])
  ) {
    fail(
      `${recordName}: user-facing entries for ${version} do not match its archived fragments. ` +
        `Expected ${JSON.stringify(expected)} but found ${JSON.stringify(actual)}.`,
    );
  }
}

export async function validateArchivedReleaseHistory(
  repositoryRoot = defaultRepositoryRoot,
  fragments = undefined,
) {
  const allFragments =
    fragments ?? (await loadFragments(repositoryRoot, { includeArchive: true }));
  const archivedByVersion = new Map();
  for (const fragment of allFragments) {
    const match = /^changes[\\/]archive[\\/]([^\\/]+)[\\/]/u.exec(
      fragment.relativePath,
    );
    if (!match) continue;
    const entries = archivedByVersion.get(match[1]) ?? [];
    entries.push(fragment);
    archivedByVersion.set(match[1], entries);
  }
  if (archivedByVersion.size === 0) return;

  const changelogSource = await readReleaseRecord(repositoryRoot, "CHANGELOG.md");
  const changelogSections = releasedChangelogSections(changelogSource);
  for (const [version, archived] of [...archivedByVersion.entries()].sort()) {
    const expected = sortFragments(archived)
      .filter((fragment) => fragment.userFacing)
      .map((fragment) => normalizeRenderedEntry(renderFragmentEntry(fragment, false)));
    const changelogSection = changelogSections.get(version);
    if (changelogSection === undefined) {
      fail(`CHANGELOG.md: archived version ${version} needs a corresponding released section.`);
    }
    const releaseNotePath = `docs/release-notes/${version}.md`;
    const releaseNote = await readReleaseRecord(repositoryRoot, releaseNotePath);
    assertReleaseEntries(
      version,
      "CHANGELOG.md",
      expected,
      extractRenderedEntries(changelogSection),
    );
    assertReleaseEntries(
      version,
      releaseNotePath,
      expected,
      extractRenderedEntries(releaseNote),
    );
  }
}

async function gitChangedFiles(repositoryRoot, base, head) {
  const { stdout } = await execFileAsync(
    "git",
    ["diff", "--name-status", "--find-renames", `${base}..${head}`],
    { cwd: repositoryRoot, maxBuffer: 1024 * 1024 },
  );
  return stdout
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line) => {
      const fields = line.split("\t");
      const status = fields[0][0];
      const file = fields.at(-1);
      const sourceFile =
        (status === "R" || status === "C") && fields.length >= 3
          ? fields[1]
          : null;
      return { file, sourceFile, status };
    });
}

function releasedChangelogSections(source) {
  const sections = new Map();
  const matches = [
    ...source.matchAll(
      /^## \[(0\.[0-9]+\.[0-9]+-(?:alpha|beta|rc)\.[1-9][0-9]*)\]\s*$/gm,
    ),
  ];
  for (const [index, match] of matches.entries()) {
    const start = match.index;
    const end = matches[index + 1]?.index ?? source.length;
    sections.set(match[1], source.slice(start, end).trimEnd());
  }
  return sections;
}

async function gitFileAtRevision(repositoryRoot, revision, relativePath) {
  try {
    const { stdout } = await execFileAsync(
      "git",
      ["show", `${revision}:${relativePath}`],
      { cwd: repositoryRoot, maxBuffer: 4 * 1024 * 1024 },
    );
    return stdout;
  } catch (error) {
    if (error.code === 128 || error.stderr?.includes("does not exist")) return null;
    throw error;
  }
}

async function protectImmutableReleaseHistory(
  repositoryRoot,
  base,
  head,
  changed,
) {
  const releaseMoves = changed.filter(isPermittedReleaseMove);
  const releaseVersions = new Set(
    releaseMoves.map(({ file }) => file.split("/")[2]),
  );
  if (releaseVersions.size > 1) {
    fail("Release preparation may archive fragments for only one new version.");
  }
  const noteChanges = changed.filter(
    ({ file, sourceFile }) =>
      file.startsWith("docs/release-notes/") ||
      sourceFile?.startsWith("docs/release-notes/"),
  );
  for (const change of noteChanges) {
    if (change.status !== "A") {
      fail(
        `${change.file}: existing release-note files are immutable; release preparation may add one new version file.`,
      );
    }
  }
  if (noteChanges.filter(({ status }) => status === "A").length > 1) {
    fail("Release preparation may add only one new release-note file.");
  }
  const addedNotes = noteChanges.filter(({ status }) => status === "A");
  if (addedNotes.length > 0 && releaseMoves.length === 0) {
    fail("A new release-note file requires archived unreleased fragments in the same change.");
  }

  const changelogChange = changed.find(
    ({ file, sourceFile }) => file === "CHANGELOG.md" || sourceFile === "CHANGELOG.md",
  );
  if (!changelogChange) {
    if (releaseMoves.length > 0) {
      fail("Release preparation must add the matching CHANGELOG.md version section.");
    }
    return;
  }
  if (changelogChange.status === "D" || changelogChange.file !== "CHANGELOG.md") {
    fail("CHANGELOG.md and its released sections are immutable and cannot be deleted or renamed.");
  }
  const baseSource = await gitFileAtRevision(repositoryRoot, base, "CHANGELOG.md");
  const headSource = await gitFileAtRevision(repositoryRoot, head, "CHANGELOG.md");
  if (baseSource === null || headSource === null) {
    fail("CHANGELOG.md must exist at both revisions.");
  }
  const baseSections = releasedChangelogSections(baseSource);
  const headSections = releasedChangelogSections(headSource);
  for (const [version, section] of baseSections) {
    if (headSections.get(version) !== section) {
      fail(`CHANGELOG.md: released section ${version} is immutable.`);
    }
  }
  const addedVersions = [...headSections.keys()].filter(
    (version) => !baseSections.has(version),
  );
  if (addedVersions.length > 1) {
    fail("Release preparation may add only one new CHANGELOG.md version section.");
  }
  if (addedVersions.length > 0 && releaseMoves.length === 0) {
    fail("A new CHANGELOG.md version section requires archived unreleased fragments.");
  }
  if (releaseMoves.length > 0) {
    const [releaseVersion] = releaseVersions;
    if (
      addedVersions.length !== 1 ||
      addedVersions[0] !== releaseVersion ||
      addedNotes.length !== 1 ||
      addedNotes[0].file !== `docs/release-notes/${releaseVersion}.md`
    ) {
      fail(
        `Release preparation for ${releaseVersion} must add its matching CHANGELOG.md section and release-note file.`,
      );
    }
  }
}

function isPermittedReleaseMove(change) {
  return (
    change.status === "R" &&
    change.sourceFile !== null &&
    /^changes\/unreleased\/[a-z0-9][a-z0-9-]*\.md$/.test(
      change.sourceFile,
    ) &&
    archivedFragmentPattern.test(change.file)
  );
}

export async function checkDiffHasFragment(
  repositoryRoot,
  base,
  head = "HEAD",
) {
  const changed = await gitChangedFiles(repositoryRoot, base, head);
  await protectImmutableReleaseHistory(repositoryRoot, base, head, changed);
  const archiveChanges = changed.filter(
    ({ file, sourceFile }) =>
      file.startsWith("changes/archive/") ||
      sourceFile?.startsWith("changes/archive/"),
  );
  for (const change of archiveChanges) {
    if (!isPermittedReleaseMove(change)) {
      fail(
        `${change.file}: archived changelog fragments are immutable; only rename unreleased fragments into a version archive.`,
      );
    }
  }
  const substantive = changed.filter(
    ({ file }) => !file.startsWith("changes/"),
  );
  if (substantive.length === 0) return;

  const addedFragments = changed.filter(
    ({ file, status }) =>
      status === "A" &&
      /^changes\/unreleased\/[a-z0-9][a-z0-9-]*\.md$/.test(file),
  );
  const releaseMoves = archiveChanges.filter(isPermittedReleaseMove);
  if (addedFragments.length === 0 && releaseMoves.length === 0) {
    fail(
      "This change needs a newly added changelog fragment. Add a user-facing fragment or an explicit internal fragment under changes/unreleased/.",
    );
  }
}

function argumentValue(args, name, fallback = undefined) {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  if (index + 1 >= args.length) fail(`${name} requires a value.`);
  return args[index + 1];
}

async function runCli() {
  const [, , command, ...args] = process.argv;
  const repositoryRoot = path.resolve(
    argumentValue(args, "--root", defaultRepositoryRoot),
  );

  if (command === "validate") {
    const fragments = await loadFragments(repositoryRoot, { includeArchive: true });
    await validateArchivedReleaseHistory(repositoryRoot, fragments);
    process.stdout.write(`Validated ${fragments.length} changelog fragments.\n`);
    return;
  }

  if (command === "render") {
    const fragments = await loadFragments(repositoryRoot);
    const rendered = renderFragments(fragments, {
      headingLevel: 3,
      includeInternal: args.includes("--include-internal"),
      includeContext: !args.includes("--without-context"),
    });
    process.stdout.write(`${rendered}\n`);
    return;
  }

  if (command === "prepare-release") {
    const version = argumentValue(args, "--version");
    if (!version) fail("prepare-release requires --version.");
    const fragments = await loadFragments(repositoryRoot);
    const notes = composeReleaseNotes(version, fragments);
    const output = argumentValue(args, "--output");
    if (output) {
      await writeFile(path.resolve(repositoryRoot, output), notes, {
        encoding: "utf8",
        flag: "wx",
      });
      process.stdout.write(`Wrote ${output}.\n`);
    } else {
      process.stdout.write(notes);
    }
    return;
  }

  if (command === "check-diff") {
    const base = argumentValue(args, "--base");
    if (!base) fail("check-diff requires --base.");
    const head = argumentValue(args, "--head", "HEAD");
    await checkDiffHasFragment(repositoryRoot, base, head);
    process.stdout.write("Changelog fragment diff check passed.\n");
    return;
  }

  fail(
    "Usage: changelog-fragments.mjs <validate|render|prepare-release|check-diff> [options]",
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runCli().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
