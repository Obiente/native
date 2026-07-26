import assert from "node:assert/strict";
import test from "node:test";
import {
  articleCapture,
  stableCapturePath,
  validateCaptureManifest,
  websiteCapturePath,
} from "./marketing-captures.mjs";

const digest = "a".repeat(64);

function validManifest() {
  return {
    schemaVersion: 2,
    renderer: "Compose ImageComposeScene",
    identity: "Obiente",
    cloudIdentity: "Nextcloud",
    networkAccess: false,
    captureSources: [
      "tools/marketing-capture-inputs.txt",
      "ui/src/commonMain/kotlin/dev/obiente/nextcloudnative/app/SyntheticCapture.kt",
    ],
    captureSourceSha256: digest,
    avatarSha256: digest,
    captures: [
      {
        scenario: "synthetic-ready",
        file: "synthetic-ready.png",
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
      },
    ],
  };
}

test("capture manifest accepts optional pull request metadata", () => {
  const withoutPullRequest = validManifest();
  assert.equal(validateCaptureManifest(withoutPullRequest).schemaVersion, 2);

  const withPullRequest = validManifest();
  withPullRequest.captures[0].pullRequest = 123;
  assert.equal(validateCaptureManifest(withPullRequest).captures[0].pullRequest, 123);

  const invalidPullRequest = validManifest();
  invalidPullRequest.captures[0].pullRequest = 0;
  assert.throws(
    () => validateCaptureManifest(invalidPullRequest),
    /pullRequest must be a positive integer/u,
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

test("article heroes accept showcase captures and reject state coverage", () => {
  const manifest = validManifest();
  assert.equal(
    articleCapture(manifest, "synthetic-ready", "article.md").scenario,
    "synthetic-ready",
  );

  manifest.captures[0].purpose = "state-coverage";
  assert.throws(
    () => articleCapture(manifest, "synthetic-ready", "article.md"),
    /must reference a showcase capture/u,
  );
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
  duplicate.captures.push({ ...duplicate.captures[0], file: "another.png" });
  assert.throws(() => validateCaptureManifest(duplicate), /Duplicate capture scenario/u);

  const obsolete = validManifest();
  obsolete.schemaVersion = 1;
  assert.throws(() => validateCaptureManifest(obsolete), /schemaVersion must be 2/u);
});
