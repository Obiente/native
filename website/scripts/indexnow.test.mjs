import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { extractIndexNowUrls, prepareIndexNowArtifacts } from "./indexnow.mjs";

const key = "a61c756649c3a8a746baa6820992a817";
const executeFile = promisify(execFile);
const scriptsDirectory = path.dirname(fileURLToPath(import.meta.url));

test("IndexNow URLs stay on the canonical origin", () => {
  const sitemap = [
    "<urlset>",
    "<url><loc>https://nc-native.obiente.dev/</loc></url>",
    "<url><loc>https://nc-native.obiente.dev/guides/?a=1&amp;b=2</loc></url>",
    "</urlset>",
  ].join("");
  assert.deepEqual(extractIndexNowUrls(sitemap, "https://nc-native.obiente.dev"), [
    "https://nc-native.obiente.dev/",
    "https://nc-native.obiente.dev/guides/?a=1&b=2",
  ]);
  assert.throws(
    () =>
      extractIndexNowUrls(
        "<urlset><url><loc>https://example.com/</loc></url></urlset>",
        "https://nc-native.obiente.dev",
      ),
    /different origin/,
  );
});

test("IndexNow deployment artifacts bind the payload to the exact static build", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "nc-native-indexnow-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const distDirectory = path.join(root, "dist");
  const privateDirectory = path.join(root, "private");
  await mkdir(distDirectory);
  await writeFile(path.join(distDirectory, "index.html"), "<!doctype html><title>Native</title>");
  await writeFile(path.join(distDirectory, `${key}.txt`), `${key}\n`);
  const sitemap =
    "<urlset><url><loc>https://nc-native.obiente.dev/</loc></url></urlset>";

  const first = await prepareIndexNowArtifacts({
    distDirectory,
    privateDirectory,
    sitemap,
    siteUrl: "https://nc-native.obiente.dev",
  });
  const payload = JSON.parse(await readFile(path.join(privateDirectory, "payload.json"), "utf8"));
  assert.equal(payload.host, "nc-native.obiente.dev");
  assert.equal(payload.key, key);
  assert.equal(payload.keyLocation, `https://nc-native.obiente.dev/${key}.txt`);
  assert.deepEqual(payload.urlList, ["https://nc-native.obiente.dev/"]);
  assert.equal(
    (await readFile(path.join(distDirectory, "indexnow-deployment.txt"), "utf8")).trim(),
    first.fingerprint,
  );

  await writeFile(path.join(distDirectory, "index.html"), "<!doctype html><title>Updated</title>");
  const second = await prepareIndexNowArtifacts({
    distDirectory,
    privateDirectory,
    sitemap,
    siteUrl: "https://nc-native.obiente.dev",
  });
  assert.notEqual(second.fingerprint, first.fingerprint);
});

test("IndexNow notifier fails closed outside the production deployment", async () => {
  const notifier = path.join(scriptsDirectory, "..", "docker", "indexnow-notify.sh");
  const { stdout, stderr } = await executeFile("sh", [notifier], {
    env: { PATH: process.env.PATH ?? "/usr/bin:/bin" },
  });
  assert.match(stdout, /INDEXNOW_PRODUCTION is not enabled/);
  assert.equal(stderr, "");
});
