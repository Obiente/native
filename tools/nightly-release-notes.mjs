#!/usr/bin/env node

import { writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import {
  defaultRepositoryRoot,
  loadFragments,
  renderFragments,
} from "./changelog-fragments.mjs";
import { composeReleaseDownloadTable } from "./release-download-table.mjs";

const modulePath = fileURLToPath(import.meta.url);

function fail(message) {
  throw new Error(message);
}

function argumentValue(args, name, fallback = undefined) {
  const index = args.indexOf(name);
  if (index < 0) return fallback;
  if (index + 1 >= args.length) fail(`${name} requires a value.`);
  return args[index + 1];
}

function requireValue(args, name) {
  const value = argumentValue(args, name);
  if (!value) fail(`${name} is required.`);
  return value;
}

const platformRows = [
  { id: "android", label: "Android", packages: ".apk" },
  { id: "linux", label: "Linux", packages: ".deb and .rpm" },
  { id: "windows", label: "Windows", packages: ".msi" },
  { id: "macos", label: "macOS", packages: ".dmg" },
];

function validateSingleLine(value, name) {
  if (value.includes("\n") || value.includes("\r")) {
    fail(`${name} must be a single line.`);
  }
}

function parseAvailablePlatforms(value) {
  if (!value) return new Set();
  const platforms = value.split(",");
  if (platforms.some((platform) => !platformRows.some((row) => row.id === platform))) {
    fail("--available contains an unsupported platform.");
  }
  if (new Set(platforms).size !== platforms.length) {
    fail("--available must not contain duplicate platforms.");
  }
  return new Set(platforms);
}

export function composeNightlyReleaseNotes({
  assetNames = [],
  availablePlatforms,
  fragments,
  repository,
  sourceRunUrl,
  sourceSha,
  tag,
}) {
  for (const [name, value] of Object.entries({
    repository,
    sourceRunUrl,
    sourceSha,
    tag,
  })) {
    validateSingleLine(value, name);
  }
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
    fail("repository must use the owner/name form.");
  }
  if (!/^[0-9a-f]{40}$/.test(sourceSha)) {
    fail("sourceSha must be a full lowercase Git commit SHA.");
  }
  if (!/^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/actions\/runs\/[0-9]+(?:\/attempts\/[0-9]+)?$/.test(sourceRunUrl)) {
    fail("sourceRunUrl must be a GitHub Actions run URL for this repository.");
  }
  if (!/^nightly-[0-9]{8}-[0-9]{4}-run[0-9]+-[0-9a-f]{8}$/.test(tag)) {
    fail("tag is not a valid immutable nightly tag.");
  }

  const highlights = renderFragments(fragments, {
    headingLevel: 3,
    includeContext: true,
    includeInternal: false,
  });
  const shortSha = sourceSha.slice(0, 8);
  const lines = [
    `# Nextcloud Native ${tag}`,
    "",
    composeReleaseDownloadTable({ assetNames, repository, tag }),
    "This is an automated prerelease built from the exact `main` revision that passed the repository's build and test workflow. Nightlies are intended for testing and may include unfinished behavior. Keep a backup of important data.",
    "",
    "## Current development highlights",
    "",
    "These curated notes cover the current unreleased development cycle. They are cumulative and are not limited to changes since the previous nightly.",
    "",
  ];

  if (highlights) {
    lines.push(highlights, "");
  } else {
    lines.push("No user-facing changelog entries are currently recorded.", "");
  }

  if (availablePlatforms.has("windows")) {
    lines.push(
      "",
      "## Windows installation",
      "",
      "The Windows MSI is currently unsigned. Windows may show a Microsoft Defender SmartScreen warning. After confirming that the download came from this release, choose `More info > Run anyway` to continue. Managed devices may block unsigned installers.",
      "",
      `Verify build provenance with \`gh attestation verify <downloaded-msi> --repo ${repository}\` and compare the file with \`SHA256SUMS\` when it is available.`,
    );
  }

  lines.push(
    "",
    "## Updating",
    "",
    "Direct Android APK, Linux package, and Windows MSI installs can check the Nightly channel from Settings > App updates. Updates are never downloaded or installed silently. Store-managed installations continue to update through their store.",
    "",
    "## Build identity",
    "",
    `- Source: [\`${shortSha}\`](https://github.com/${repository}/commit/${sourceSha})`,
    `- Tested by: [Build and test workflow](${sourceRunUrl})`,
    "- Update channel: Nightly",
    "- Integrity: verify downloaded assets with `SHA256SUMS` when it is available",
    "",
  );
  return lines.join("\n");
}

async function runCli() {
  const args = process.argv.slice(2);
  const repositoryRoot = path.resolve(
    argumentValue(args, "--root", defaultRepositoryRoot),
  );
  const repository = requireValue(args, "--repository");
  const sourceRunUrl = requireValue(args, "--source-run-url");
  const sourceSha = requireValue(args, "--source-sha");
  const tag = requireValue(args, "--tag");
  const output = requireValue(args, "--output");
  const availablePlatforms = parseAvailablePlatforms(
    argumentValue(args, "--available", ""),
  );
  const assetsDirectory = argumentValue(args, "--assets");
  const assetNames = assetsDirectory
    ? await (await import("node:fs/promises")).readdir(path.resolve(repositoryRoot, assetsDirectory))
    : [];
  const fragments = await loadFragments(repositoryRoot);
  const notes = composeNightlyReleaseNotes({
    assetNames,
    availablePlatforms,
    fragments,
    repository,
    sourceRunUrl,
    sourceSha,
    tag,
  });
  await writeFile(path.resolve(repositoryRoot, output), notes, "utf8");
}

if (process.argv[1] && path.resolve(process.argv[1]) === modulePath) {
  runCli().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
