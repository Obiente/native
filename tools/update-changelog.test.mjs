import assert from "node:assert/strict";
import test from "node:test";
import { sourceSequenceFromVersionCode } from "./update-changelog.mjs";

test("update version codes expose their main-history sequence", () => {
  assert.equal(sourceSequenceFromVersionCode(20_040_411), 4_041);
  assert.equal(sourceSequenceFromVersionCode(20_040_412), 4_041);
  assert.throws(() => sourceSequenceFromVersionCode(0), /does not contain/);
});
