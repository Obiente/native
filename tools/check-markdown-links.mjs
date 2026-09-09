#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(process.argv[2] ?? path.join(scriptDirectory, ".."));
const ignoredDirectories = new Set([
  ".git",
  ".gradle",
  ".kotlin",
  ".idea",
  "build",
  "dist",
  "node_modules",
  "target",
]);

function markdownFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    if (entry.isDirectory() && ignoredDirectories.has(entry.name)) return [];
    const absolute = path.join(directory, entry.name);
    if (entry.isDirectory()) return markdownFiles(absolute);
    return entry.isFile() && entry.name.endsWith(".md") ? [absolute] : [];
  });
}

function localTargets(markdown) {
  const targets = [];
  const patterns = [
    /!?\[[^\]]*\]\(\s*(<[^>]+>|[^\s)]+)(?:\s+["'][^)]*["'])?\s*\)/g,
    /<(?:a|img|source)\b[^>]*\b(?:href|src|srcset)="([^"]+)"[^>]*>/gi,
    /^\s*\[[^\]]+\]:\s*(<[^>]+>|\S+)/gm,
  ];
  for (const pattern of patterns) {
    for (const match of markdown.matchAll(pattern)) {
      targets.push(match[1]);
    }
  }
  return targets;
}

function normalizedLocalTarget(rawTarget) {
  const target = rawTarget.trim().replace(/^<|>$/g, "").split(/\s+/)[0];
  if (
    !target ||
    target.startsWith("#") ||
    target.startsWith("/") ||
    target.startsWith("//") ||
    target.includes("{") ||
    target.includes("}") ||
    /^[a-z][a-z0-9+.-]*:/i.test(target)
  ) {
    return null;
  }
  const withoutFragment = target.split("#", 1)[0].split("?", 1)[0];
  if (!withoutFragment) return null;
  try {
    return decodeURIComponent(withoutFragment);
  } catch {
    return withoutFragment;
  }
}

const failures = [];
for (const markdownFile of markdownFiles(projectRoot)) {
  const markdown = fs.readFileSync(markdownFile, "utf8");
  for (const rawTarget of localTargets(markdown)) {
    const target = normalizedLocalTarget(rawTarget);
    if (target == null) continue;
    const resolved = path.resolve(path.dirname(markdownFile), target);
    if (!resolved.startsWith(`${projectRoot}${path.sep}`) && resolved !== projectRoot) {
      failures.push(`${path.relative(projectRoot, markdownFile)}: link escapes the repository: ${rawTarget}`);
    } else if (!fs.existsSync(resolved)) {
      failures.push(`${path.relative(projectRoot, markdownFile)}: missing local target: ${rawTarget}`);
    }
  }
}

if (failures.length > 0) {
  console.error("Markdown local-link checks failed:");
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exit(1);
}

console.log("Markdown local-link checks passed.");
