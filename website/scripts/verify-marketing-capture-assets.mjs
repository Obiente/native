import {
  readCaptureManifest,
  verifyCaptureAssets,
} from "./marketing-captures.mjs";

const manifest = await readCaptureManifest();
const failures = await verifyCaptureAssets(manifest);

if (failures.length > 0) {
  console.error("Committed marketing capture asset verification failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log(
    `Verified ${manifest.captures.length} committed synthetic Compose capture assets.`,
  );
}
