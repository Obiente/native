#!/usr/bin/env node

import { execFile } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";
import { defaultRepositoryRoot, loadFragments, sortFragments } from "./changelog-fragments.mjs";

const execFileAsync = promisify(execFile);

export function sourceSequenceFromVersionCode(versionCode) {
  if (!Number.isSafeInteger(versionCode) || versionCode <= 20_000_000) {
    throw new Error("The update version code does not contain a main-history sequence.");
  }
  return Math.floor((versionCode - 20_000_000) / 10);
}

export async function buildUpdateChangelog(
  versionCode,
  repositoryRoot = defaultRepositoryRoot,
) {
  const targetSequence = sourceSequenceFromVersionCode(versionCode);
  const fragments = sortFragments(
    (await loadFragments(repositoryRoot, { includeArchive: true }))
      .filter((fragment) => fragment.userFacing),
  );
  const changes = [];
  for (const fragment of fragments) {
    const { stdout } = await execFileAsync(
      "git",
      ["log", "--follow", "--diff-filter=A", "--format=%H", "--", fragment.relativePath],
      { cwd: repositoryRoot },
    );
    const introduction = stdout.trim().split("\n").filter(Boolean).at(-1);
    if (!introduction) {
      throw new Error(`${fragment.relativePath}: could not find the introducing commit.`);
    }
    const sequenceResult = await execFileAsync(
      "git",
      ["rev-list", "--count", introduction],
      { cwd: repositoryRoot },
    );
    const introducedSourceSequence = Number(sequenceResult.stdout.trim());
    if (!Number.isSafeInteger(introducedSourceSequence) || introducedSourceSequence < 1) {
      throw new Error(`${fragment.relativePath}: has an invalid introducing source sequence.`);
    }
    if (introducedSourceSequence > targetSequence) continue;
    changes.push({
      id: path.basename(fragment.relativePath, ".md"),
      category: fragment.category,
      summary: fragment.summary,
      platforms: fragment.platforms,
      introducedSourceSequence,
    });
  }
  if (changes.length > 1_000 || new Set(changes.map((change) => change.id)).size !== changes.length) {
    throw new Error("Update changelog identifiers must be unique and bounded.");
  }
  return changes;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const versionCode = Number(process.argv[2]);
  buildUpdateChangelog(versionCode)
    .then((changes) => process.stdout.write(`${JSON.stringify(changes)}\n`))
    .catch((error) => {
      process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    });
}
