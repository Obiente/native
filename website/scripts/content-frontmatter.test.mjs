import assert from "node:assert/strict";
import test from "node:test";
import {
  normalizeNewsArticleBody,
  parseNewsFrontmatter,
} from "./content-frontmatter.mjs";

const metadataLines = [
  "title: A native update",
  "slug: native-update",
  "date: 2026-07-24",
  "lastUpdated: 2026-07-25",
  "description: A fixture-only update.",
  "tags: native, update",
  "captureScenario: mobile-home",
  "imageAlt: A fixture-safe app screen",
  "imageCaption: The app rendered with fixture data.",
];

function fixtureWithLineEnding(lineEnding) {
  return [
    "---",
    ...metadataLines,
    "---",
    "# Update",
    "",
    "Details from the native app.",
  ].join(lineEnding);
}

test("news frontmatter accepts LF and CRLF without leaking carriage returns", () => {
  const lf = parseNewsFrontmatter(fixtureWithLineEnding("\n"), "lf.md");
  const crlf = parseNewsFrontmatter(fixtureWithLineEnding("\r\n"), "crlf.md");

  assert.deepEqual(crlf.metadata, lf.metadata);
  assert.equal(lf.metadata.title, "A native update");
  assert.equal(crlf.body, "# Update\r\n\r\nDetails from the native app.");
  assert.equal(Object.values(crlf.metadata).some((value) => value.includes("\r")), false);
});

test("article body strips leading blanks and exactly one matching title heading", () => {
  const body = "\n\n# A native update\n\nFirst paragraph.\n\n# Keep this heading";
  const normalized = normalizeNewsArticleBody(body, "A native update");

  assert.equal(normalized, "First paragraph.\n\n# Keep this heading");
  assert.equal(normalized.startsWith("# A native update"), false);
  assert.equal(
    normalizeNewsArticleBody("\n# Different title\n\nText", "A native update"),
    "# Different title\n\nText",
  );
});
