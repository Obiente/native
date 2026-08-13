import assert from "node:assert/strict";
import test from "node:test";

import { composeReleaseDownloadTable } from "./release-download-table.mjs";

test("release download table links only assets that actually exist", () => {
  const table = composeReleaseDownloadTable({
    assetNames: [
      "nextcloud-native-0.1.0-alpha.2-android.apk",
      "nextcloudnative_1.0.3822_amd64.deb",
      "nextcloudnative-1.0.3822-1.x86_64.rpm",
      "NextcloudNative-1.0.3822.msi",
    ],
    repository: "Obiente/nc-native",
    tag: "v0.1.0-alpha.2",
  });

  assert.match(table, /^<!-- quick-downloads:start -->\n## Quick downloads/);
  assert.match(table, /\| Android \| \[APK\]\(.*android\.apk\) \|/);
  assert.match(table, /\| Linux \| \[DEB\]\(.*\.deb\) \/ \[RPM\]\(.*\.rpm\) \|/);
  assert.match(table, /\| Windows \| \[MSI \(x86-64\)\]\(.*\.msi\) \|/);
  assert.match(table, /\| macOS preview \| Unavailable \|/);
});

test("release download table percent-encodes asset names", () => {
  const table = composeReleaseDownloadTable({
    assetNames: ["NextcloudNative-preview build.dmg"],
    repository: "Obiente/nc-native",
    tag: "nightly-20260813-0800-run1-01234567",
  });
  assert.doesNotMatch(table, /preview build/);
});
