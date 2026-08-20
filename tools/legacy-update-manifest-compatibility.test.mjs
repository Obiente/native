import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

const execFileAsync = promisify(execFile);
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const repository = "Obiente/nc-native";
const versionName = "0.1.0-alpha.2";
const tag = `v${versionName}`;
const versionCode = 20_003_822;
const packageVersion = "1.0.3822";
const releasePrefix = `https://github.com/${repository}/releases/download/${tag}/`;
const releaseNotesUrl = `https://github.com/${repository}/releases/tag/${tag}`;
const sha256Pattern = /^[a-f0-9]{64}$/;

const androidKeys = [
  "apkSha256",
  "apkSize",
  "apkUrl",
  "channel",
  "minimumAndroidSdk",
  "packageName",
  "releaseNotesUrl",
  "schemaVersion",
  "signingCertificateSha256Digests",
  "versionCode",
  "versionName",
];
const desktopKeys = [
  "assets",
  "channel",
  "packageVersion",
  "releaseNotesUrl",
  "schemaVersion",
  "versionCode",
  "versionName",
];
const desktopAssetKeys = ["architecture", "format", "platform", "sha256", "size", "url"];

function assertPlainObject(value, label) {
  assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
}

function assertExactKeys(value, expected, label) {
  assertPlainObject(value, label);
  assert.deepEqual(Object.keys(value).sort(), [...expected].sort(), `${label} keys changed`);
}

function assertPositiveInteger(value, label, maximum = Number.MAX_SAFE_INTEGER) {
  assert.ok(Number.isSafeInteger(value) && value > 0 && value <= maximum, `${label} must be a bounded integer`);
}

function assertLegacyAndroidManifest(raw) {
  const manifest = JSON.parse(raw);
  assertExactKeys(manifest, androidKeys, "Android manifest");
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.channel, "prerelease-v1");
  assert.equal(manifest.versionName, versionName);
  assert.equal(manifest.versionCode, versionCode, "Android versionCode changed type or value");
  assert.equal(manifest.packageName, "dev.obiente.nextcloudnative");
  assert.equal(manifest.minimumAndroidSdk, 26);
  assertPositiveInteger(manifest.apkSize, "Android APK size", 536_870_912);
  assert.match(manifest.apkSha256, sha256Pattern);
  assert.equal(
    manifest.apkUrl,
    `${releasePrefix}nextcloud-native-${versionName}-android.apk`,
  );
  assert.equal(manifest.releaseNotesUrl, releaseNotesUrl);
  assert.ok(Array.isArray(manifest.signingCertificateSha256Digests));
  assert.ok(
    manifest.signingCertificateSha256Digests.length >= 1 &&
      manifest.signingCertificateSha256Digests.length <= 8,
  );
  assert.equal(
    new Set(manifest.signingCertificateSha256Digests).size,
    manifest.signingCertificateSha256Digests.length,
  );
  manifest.signingCertificateSha256Digests.forEach((digest) => {
    assert.equal(typeof digest, "string");
    assert.match(digest, sha256Pattern);
  });
  return manifest;
}

function assertLegacyDesktopManifest(raw) {
  const manifest = JSON.parse(raw);
  assertExactKeys(manifest, desktopKeys, "Desktop manifest");
  assert.equal(manifest.schemaVersion, 1);
  assert.equal(manifest.channel, "prerelease-v1");
  assert.equal(manifest.versionName, versionName);
  assert.equal(manifest.versionCode, versionCode);
  assert.equal(manifest.packageVersion, packageVersion);
  assert.equal(manifest.releaseNotesUrl, releaseNotesUrl);
  assert.ok(Array.isArray(manifest.assets) && manifest.assets.length >= 1 && manifest.assets.length <= 8);

  const identities = new Set();
  for (const asset of manifest.assets) {
    assertExactKeys(asset, desktopAssetKeys, "Desktop asset");
    assert.ok(asset.platform === "linux" || asset.platform === "windows");
    const formats = asset.platform === "linux" ? ["deb", "rpm"] : ["msi"];
    assert.ok(formats.includes(asset.format));
    assert.equal(asset.architecture, "x86_64");
    assertPositiveInteger(asset.size, "Desktop asset size", 536_870_912);
    assert.equal(typeof asset.sha256, "string");
    assert.match(asset.sha256, sha256Pattern);
    assert.equal(typeof asset.url, "string");
    assert.ok(asset.url.startsWith(releasePrefix));
    assert.ok(asset.url.endsWith(`.${asset.format}`));
    identities.add(`${asset.platform}:${asset.format}:${asset.architecture}`);
  }
  assert.equal(identities.size, manifest.assets.length);
  assert.deepEqual(
    manifest.assets.map((asset) => path.basename(asset.url)).sort(),
    [
      "NextcloudNative-1.0.3822.msi",
      "nextcloudnative-1.0.3822-1.x86_64.rpm",
      "nextcloudnative_1.0.3822_amd64.deb",
    ],
  );
  return manifest;
}

test("generated core manifests satisfy the oldest shipped strict schema", async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "nc-native-legacy-update-"));
  try {
    const androidManifest = path.join(temporary, "update-manifest.json");
    const desktopManifest = path.join(temporary, "desktop-update-manifest.json");
    const apkName = `nextcloud-native-${versionName}-android.apk`;
    const environment = { ...process.env, GITHUB_REPOSITORY: repository };

    await execFileAsync(
      path.join(repositoryRoot, "tools/create-android-update-manifest.sh"),
      [
        androidManifest,
        "prerelease-v1",
        tag,
        versionName,
        String(versionCode),
        apkName,
        "240881691",
        "a".repeat(64),
        JSON.stringify(["b".repeat(64)]),
      ],
      { cwd: repositoryRoot, env: environment },
    );

    await Promise.all([
      writeFile(path.join(temporary, "nextcloudnative-1.0.3822-1.x86_64.rpm"), "rpm fixture\n"),
      writeFile(path.join(temporary, "nextcloudnative_1.0.3822_amd64.deb"), "deb fixture\n"),
      writeFile(path.join(temporary, "NextcloudNative-1.0.3822.msi"), "msi fixture\n"),
    ]);
    await execFileAsync(
      path.join(repositoryRoot, "tools/create-desktop-update-manifest.sh"),
      [
        desktopManifest,
        "prerelease-v1",
        tag,
        versionName,
        String(versionCode),
        packageVersion,
        temporary,
      ],
      { cwd: repositoryRoot, env: environment },
    );

    const android = assertLegacyAndroidManifest(await readFile(androidManifest, "utf8"));
    const desktop = assertLegacyDesktopManifest(await readFile(desktopManifest, "utf8"));

    assert.throws(
      () => assertLegacyAndroidManifest(JSON.stringify({ ...android, versionCode: String(versionCode) })),
      /versionCode/,
    );
    assert.throws(
      () => assertLegacyDesktopManifest(JSON.stringify({
        ...desktop,
        assets: [{ ...desktop.assets[0], futureField: "unsupported" }, ...desktop.assets.slice(1)],
      })),
      /keys changed/,
    );
  } finally {
    await rm(temporary, { force: true, recursive: true });
  }
});
