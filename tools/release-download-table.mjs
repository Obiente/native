#!/usr/bin/env node

import { readdir, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const modulePath = fileURLToPath(import.meta.url);

const packageRows = [
  { platform: "Android", formats: [{ label: "APK", pattern: /^nextcloud-native-.*-android\.apk$/ }] },
  {
    platform: "Linux",
    formats: [
      { label: "DEB", pattern: /^nextcloudnative_.*_amd64\.deb$/ },
      { label: "RPM", pattern: /^nextcloudnative-.*\.x86_64\.rpm$/ },
    ],
  },
  { platform: "Windows", formats: [{ label: "MSI (x86-64)", pattern: /^NextcloudNative-.*\.msi$/ }] },
  { platform: "macOS preview", formats: [{ label: "DMG (Intel)", pattern: /^NextcloudNative-.*\.dmg$/ }] },
];

function fail(message) {
  throw new Error(message);
}

function validateTag(tag) {
  if (!/^(?:v0\.[0-9]+\.[0-9]+-(?:alpha|beta|rc)\.[1-9][0-9]*|nightly-[A-Za-z0-9.-]+)$/.test(tag)) {
    fail("tag is not a supported immutable release tag.");
  }
}

export function composeReleaseDownloadTable({ assetNames, repository, tag }) {
  validateTag(tag);
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository)) {
    fail("repository must use the owner/name form.");
  }
  const uniqueNames = [...new Set(assetNames)].sort();
  const baseUrl = `https://github.com/${repository}/releases/download/${tag}`;
  const lines = [
    "## Quick downloads",
    "",
    "Choose the installer for your platform. Read the known limitations below before installing this early test release.",
    "",
    "| Platform | Direct download |",
    "| --- | --- |",
  ];
  for (const row of packageRows) {
    const links = row.formats.flatMap(({ label, pattern }) => {
      const name = uniqueNames.find((candidate) => pattern.test(candidate));
      return name ? [`[${label}](${baseUrl}/${encodeURIComponent(name)})`] : [];
    });
    lines.push(`| ${row.platform} | ${links.length > 0 ? links.join(" · ") : "Unavailable"} |`);
  }
  lines.push(
    "",
    `[Checksums and all release assets](${baseUrl.replace("/download/", "/tag/")})`,
    "",
  );
  return lines.join("\n");
}

function argument(args, name) {
  const index = args.indexOf(name);
  if (index < 0 || index + 1 >= args.length) fail(`${name} is required.`);
  return args[index + 1];
}

async function runCli() {
  const args = process.argv.slice(2);
  const assetsDirectory = path.resolve(argument(args, "--assets"));
  const output = path.resolve(argument(args, "--output"));
  const table = composeReleaseDownloadTable({
    assetNames: await readdir(assetsDirectory),
    repository: argument(args, "--repository"),
    tag: argument(args, "--tag"),
  });
  await writeFile(output, table, "utf8");
}

if (process.argv[1] && path.resolve(process.argv[1]) === modulePath) {
  runCli().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
