import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import {
  assertSafeScreenshotOutput,
  fixturePath,
  readSyntheticScreenshotFixture,
  screenshotDirectory,
  validateScreenshotFixture,
} from "./screenshot-fixtures.mjs";

test("the committed synthetic fixture passes privacy guardrails", async () => {
  const fixture = await readSyntheticScreenshotFixture();
  assert.equal(fixture.fixtureVersion, 1);
  assert.equal(path.basename(fixturePath), "demo-workspace.json");
});

test("private-looking fixture fields and values are rejected", () => {
  assert.throws(
    () => validateScreenshotFixture({ fixtureVersion: 1, account: { displayName: "Demo", token: "x" } }),
    /forbidden key/,
  );
  assert.throws(
    () =>
      validateScreenshotFixture({
        fixtureVersion: 1,
        account: { displayName: "Demo", source: ["", "home", "sample", "Pictures"].join("/") },
      }),
    /private-looking value/,
  );
  assert.throws(
    () =>
      validateScreenshotFixture({
        fixtureVersion: 1,
        account: { displayName: "Demo", source: "https://cloud.invalid" },
      }),
    /private-looking value/,
  );
});

test("generated files cannot escape the public screenshot directory", () => {
  assert.equal(
    assertSafeScreenshotOutput(path.join(screenshotDirectory, "files.svg")),
    path.join(screenshotDirectory, "files.svg"),
  );
  assert.throws(() => assertSafeScreenshotOutput(path.join(screenshotDirectory, "..", "leak.svg")));
  assert.throws(() => assertSafeScreenshotOutput(path.join(screenshotDirectory, "files.png")));
});

test("generator has no network, environment, home, session, or cache input", async () => {
  const source = await readFile(
    new URL("./generate-screenshots.mjs", import.meta.url),
    "utf8",
  );
  for (const forbidden of [
    "fetch(",
    "process.env",
    "homedir(",
    ".nextcloud",
    "saved session",
    "cacheDir",
    "localStorage",
  ]) {
    assert.equal(source.includes(forbidden), false, `generator must not contain ${forbidden}`);
  }
  assert.match(source, /readSyntheticScreenshotFixture/);
});
