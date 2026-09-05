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
    "<url><loc>https://nati.ve/</loc></url>",
    "<url><loc>https://nati.ve/guides/?a=1&amp;b=2</loc></url>",
    "</urlset>",
  ].join("");
  assert.deepEqual(extractIndexNowUrls(sitemap, "https://nati.ve"), [
    "https://nati.ve/",
    "https://nati.ve/guides/?a=1&b=2",
  ]);
  assert.throws(
    () =>
      extractIndexNowUrls(
        "<urlset><url><loc>https://example.com/</loc></url></urlset>",
        "https://nati.ve",
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
    "<urlset><url><loc>https://nati.ve/</loc></url></urlset>";

  const first = await prepareIndexNowArtifacts({
    distDirectory,
    privateDirectory,
    sitemap,
    siteUrl: "https://nati.ve",
  });
  const payload = JSON.parse(await readFile(path.join(privateDirectory, "payload.json"), "utf8"));
  assert.equal(payload.host, "nati.ve");
  assert.equal(payload.key, key);
  assert.equal(payload.keyLocation, `https://nati.ve/${key}.txt`);
  assert.deepEqual(payload.urlList, ["https://nati.ve/"]);
  assert.equal(
    (await readFile(path.join(distDirectory, "indexnow-deployment.txt"), "utf8")).trim(),
    first.fingerprint,
  );

  await writeFile(path.join(distDirectory, "index.html"), "<!doctype html><title>Updated</title>");
  const second = await prepareIndexNowArtifacts({
    distDirectory,
    privateDirectory,
    sitemap,
    siteUrl: "https://nati.ve",
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

test("production notifier polls the canonical host before submitting", {
  skip: process.platform === "win32" ? "POSIX container notifier is exercised on Linux" : false,
}, async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), "native-indexnow-notifier-"));
  try {
    const privateDirectory = path.join(temporary, "private");
    const stateDirectory = path.join(temporary, "state");
    const bin = path.join(temporary, "bin");
    await mkdir(privateDirectory);
    await mkdir(bin);
    await writeFile(path.join(privateDirectory, "fingerprint"), "synthetic-fingerprint");
    await writeFile(path.join(privateDirectory, "payload.json"), "{}");
    const source = await readFile(path.join(scriptsDirectory, "../docker/indexnow-notify.sh"), "utf8");
    const notifier = path.join(temporary, "notify.sh");
    await writeFile(notifier, source.replace("/opt/indexnow", privateDirectory)
      .replace("/var/cache/nginx/indexnow", stateDirectory));
    await writeFile(path.join(bin, "curl"), `#!/bin/sh
for argument do destination="$argument"; done
case "$destination" in
  'https://nati.ve/indexnow-deployment.txt?build=synthetic-fingerprint') printf synthetic-fingerprint ;;
  'https://api.indexnow.org/indexnow') printf 202 ;;
  *) exit 1 ;;
esac
`, { mode: 0o755 });
    await writeFile(path.join(bin, "sleep"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
    const { stdout } = await executeFile("sh", [notifier], {
      env: { ...process.env, INDEXNOW_PRODUCTION: "1", PATH: `${bin}${path.delimiter}${process.env.PATH}` },
    });
    assert.match(stdout, /IndexNow accepted deployment synthetic-fingerprint with HTTP 202/);
    assert.equal(await readFile(path.join(stateDirectory, "synthetic-fingerprint.submitted"), "utf8"), "");
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});
