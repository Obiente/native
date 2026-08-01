import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  articleCapture,
  articleCapturePair,
  discoverCaptureSources,
  stableCapturePath,
  validateCaptureManifest,
  websiteCapturePath,
} from "./marketing-captures.mjs";

const digest = "a".repeat(64);

function validManifest() {
  const darkCapture = {
    scenario: "synthetic-ready",
    baseScenario: "synthetic-ready",
    file: "synthetic-ready.png",
    theme: "dark",
    width: 1200,
    height: 800,
    density: 1,
    feature: "Synthetic feature",
    surface: "Synthetic surface",
    state: "Ready",
    purpose: "showcase",
    platform: "desktop",
    viewport: "wide",
    sha256: digest,
  };
  return {
    schemaVersion: 4,
    renderer: "Compose ImageComposeScene",
    identity: "Obiente",
    cloudIdentity: "Nextcloud",
    networkAccess: false,
    captureSources: [
      "tools/marketing-capture-inputs.txt",
      "ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/SyntheticCapture.kt",
    ],
    avatarSha256: digest,
    captures: [
      darkCapture,
      {
        ...darkCapture,
        scenario: "synthetic-ready-light",
        file: "synthetic-ready-light.png",
        theme: "light",
      },
    ],
  };
}

test("capture manifest accepts optional pull request and issue metadata", () => {
  const withoutPullRequest = validManifest();
  assert.equal(validateCaptureManifest(withoutPullRequest).schemaVersion, 4);

  const withPullRequest = validManifest();
  withPullRequest.captures.forEach((capture) => { capture.pullRequest = 123; });
  assert.equal(validateCaptureManifest(withPullRequest).captures[0].pullRequest, 123);

  const invalidPullRequest = validManifest();
  invalidPullRequest.captures.forEach((capture) => { capture.pullRequest = 0; });
  assert.throws(
    () => validateCaptureManifest(invalidPullRequest),
    /pullRequest must be a positive integer/u,
  );

  const withIssue = validManifest();
  withIssue.captures.forEach((capture) => { capture.issue = 456; });
  assert.equal(validateCaptureManifest(withIssue).captures[0].issue, 456);

  const invalidIssue = validManifest();
  invalidIssue.captures.forEach((capture) => { capture.issue = 0; });
  assert.throws(
    () => validateCaptureManifest(invalidIssue),
    /issue must be a positive integer/u,
  );
});

test("website capture URLs are cache-busted while native paths remain stable", () => {
  const manifest = validManifest();
  const capture = manifest.captures[0];
  assert.equal(stableCapturePath(capture), "/screenshots/synthetic-ready.png");
  assert.match(
    websiteCapturePath(manifest, capture),
    /^\/screenshots\/synthetic-ready\.png\?v=[a-f0-9]{64}$/u,
  );
});

test("article heroes require paired showcase captures", () => {
  const manifest = validManifest();
  assert.equal(
    articleCapture(manifest, "synthetic-ready", "article.md").scenario,
    "synthetic-ready",
  );
  assert.deepEqual(
    Object.fromEntries(
      Object.entries(articleCapturePair(manifest, "synthetic-ready", "article.md"))
        .map(([theme, capture]) => [theme, capture.theme]),
    ),
    { dark: "dark", light: "light" },
  );

  manifest.captures.forEach((capture) => { capture.purpose = "state-coverage"; });
  assert.throws(
    () => articleCapture(manifest, "synthetic-ready", "article.md"),
    /must reference a showcase capture/u,
  );
});

test("capture manifest rejects missing, duplicate, and mismatched theme pairs", () => {
  const missing = validManifest();
  missing.captures.pop();
  assert.throws(
    () => validateCaptureManifest(missing),
    /exactly one dark and one light/u,
  );

  const duplicate = validManifest();
  duplicate.captures[1].theme = "dark";
  assert.throws(
    () => validateCaptureManifest(duplicate),
    /exactly one dark and one light/u,
  );

  const mismatched = validManifest();
  mismatched.captures[1].width += 1;
  assert.throws(() => validateCaptureManifest(mismatched), /must share width/u);
});

test("capture manifest rejects unsafe files and incomplete visual QA metadata", () => {
  const unsafe = validManifest();
  unsafe.captures[0].file = "../private.png";
  assert.throws(() => validateCaptureManifest(unsafe), /Invalid capture file/u);

  const incomplete = validManifest();
  incomplete.captures[0].state = "";
  assert.throws(() => validateCaptureManifest(incomplete), /state must not be empty/u);

  const invalidPurpose = validManifest();
  invalidPurpose.captures[0].purpose = "failed-run";
  assert.throws(
    () => validateCaptureManifest(invalidPurpose),
    /purpose must be showcase or state-coverage/u,
  );
});

test("capture manifest rejects duplicate scenarios and obsolete schemas", () => {
  const duplicate = validManifest();
  duplicate.captures.push({
    ...duplicate.captures[0],
    file: "another.png",
    baseScenario: "another-base",
  });
  assert.throws(() => validateCaptureManifest(duplicate), /Duplicate capture scenario/u);

  const obsolete = validManifest();
  obsolete.schemaVersion = 1;
  assert.throws(() => validateCaptureManifest(obsolete), /schemaVersion must be 4/u);
});

test("capture manifest rejects unexpected schema fields", () => {
  const topLevel = validManifest();
  topLevel.internalNote = "not public";
  assert.throws(() => validateCaptureManifest(topLevel), /unexpected or missing fields/u);

  const capture = validManifest();
  capture.captures[0].debugPath = "/private";
  assert.throws(() => validateCaptureManifest(capture), /unexpected or missing fields/u);
});

test("capture source discovery rejects traversal, backslashes, and symlinks", async () => {
  const repository = await mkdtemp(path.join(os.tmpdir(), "capture-source-js-"));
  const outside = await mkdtemp(path.join(os.tmpdir(), "capture-source-outside-js-"));
  try {
    await mkdir(path.join(repository, "tools"), { recursive: true });
    await mkdir(path.join(repository, "ui", "source"), { recursive: true });
    await writeFile(
      path.join(repository, "tools", "marketing-capture-inputs.txt"),
      "ui/source\n",
    );
    await writeFile(path.join(repository, "ui", "source", "A.kt"), "a");
    assert.deepEqual(
      await discoverCaptureSources(repository),
      ["tools/marketing-capture-inputs.txt", "ui/source/A.kt"],
    );

    for (const unsafe of ["../outside", "ui\\source", "/absolute"]) {
      await writeFile(
        path.join(repository, "tools", "marketing-capture-inputs.txt"),
        `${unsafe}\n`,
      );
      await assert.rejects(() => discoverCaptureSources(repository));
    }

    await writeFile(path.join(outside, "private.kt"), "private");
    await symlink(outside, path.join(repository, "linked"));
    await writeFile(
      path.join(repository, "tools", "marketing-capture-inputs.txt"),
      "linked\n",
    );
    await assert.rejects(
      () => discoverCaptureSources(repository),
      /must not be a symbolic link/u,
    );
  } finally {
    await rm(repository, { recursive: true, force: true });
    await rm(outside, { recursive: true, force: true });
  }
});
