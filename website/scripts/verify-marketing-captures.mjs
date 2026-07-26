import {
  readCaptureManifest,
  verifyCaptureFreshness,
} from "./marketing-captures.mjs";

const manifest = await readCaptureManifest();
const failures = await verifyCaptureFreshness(manifest);

if (failures.length > 0) {
  console.error("Marketing capture verification failed:");
  for (const failure of failures) console.error(`- ${failure}`);
  console.error("");
  console.error(
    "Run tools/capture-marketing-screenshots.sh with JDK 21, then review and commit the " +
      "updated synthetic screenshots and capture manifest.",
  );
  process.exitCode = 1;
} else {
  console.log(
    `Verified ${manifest.captures.length} synthetic Compose captures against the current inputs.`,
  );
}
