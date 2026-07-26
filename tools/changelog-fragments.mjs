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
  if (!/^[A-Z0-9]/.test(summary)) {
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
    if (entry.isDirectory()) {
      files.push(...(await walkMarkdownFiles(absolutePath, repositoryRoot)));
    } else if (entry.isFile() && entry.name.endsWith(".md")) {
      if (!fragmentNamePattern.test(entry.name)) {
        fail(
          `${path.relative(repositoryRoot, absolutePath)}: use a lowercase hyphenated fragment filename.`,
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
      const suffix = includeContext ? contextSuffix(fragment) : "";
      const prefix = platformPrefix(fragment);
      if (suffix) {
        const punctuation = fragment.summary.at(-1);
        const summary = fragment.summary.slice(0, -1);
        sections.push(`- ${prefix}${summary}${suffix}${punctuation}`);
      } else {
        sections.push(`- ${prefix}${fragment.summary}`);
      }
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
  if (!/^0\.[0-9]+\.[0-9]+-(alpha|beta|rc)\.[1-9][0-9]*$/.test(version)) {
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

export async function checkDiffHasFragment(
  repositoryRoot,
  base,
  head = "HEAD",
) {
  const changed = await gitChangedFiles(repositoryRoot, base, head);
  const archiveChanges = changed.filter(
    ({ file, sourceFile }) =>
      file.startsWith("changes/archive/") ||
      sourceFile?.startsWith("changes/archive/"),
  );
  for (const change of archiveChanges) {
    const permittedReleaseMove =
      change.status === "R" &&
      change.sourceFile !== null &&
      /^changes\/unreleased\/[a-z0-9][a-z0-9-]*\.md$/.test(
        change.sourceFile,
      ) &&
      archivedFragmentPattern.test(change.file);
    if (!permittedReleaseMove) {
      fail(
        `${change.file}: archived changelog fragments are immutable; only rename unreleased fragments into a version archive.`,
      );
    }
  }
  const substantive = changed.filter(
    ({ file }) => !file.startsWith("changes/"),
  );
  if (substantive.length === 0) return;

  const fragments = changed.filter(
    ({ file, status }) =>
      status !== "D" &&
      /^changes\/(unreleased|archive\/[^/]+)\/[a-z0-9][a-z0-9-]*\.md$/.test(
        file,
      ),
  );
  if (fragments.length === 0) {
    fail(
      "This change needs a changelog fragment. Add a user-facing fragment or an explicit internal fragment under changes/unreleased/.",
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
