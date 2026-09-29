import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
// Match the actual paths-filter lists, without depending on YAML package installation.
function scope(name) {
  const match = workflow.match(new RegExp(`^            ${name}:\\r?\\n((?:              - .*\\r?\\n)+)`, 'm'));
  assert.ok(match, `Missing build scope: ${name}`);
  return [...match[1].matchAll(/- "([^"]+)"/g)].map(match => match[1]);
}
for (const platform of ['desktop', 'android', 'windows']) {
  test(`${platform} builds shared source and test changes`, () => {
    const paths = scope(platform);
    for (const sourceSet of ['commonMain', 'commonTest', 'jvmMain', 'jvmTest']) {
      assert.ok(paths.includes(`ui/src/${sourceSet}/**`), `${platform} misses ${sourceSet}`);
    }
  });
}
test('desktop and Windows cover desktop tests and resources', () => {
  for (const platform of ['desktop', 'windows']) {
    assert.ok(scope(platform).includes('ui/src/desktopTest/**'));
  }
});
