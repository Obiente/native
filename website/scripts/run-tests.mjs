import { spawn } from "node:child_process";
import { readdir } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptsDirectory = path.dirname(fileURLToPath(import.meta.url));
const testFiles = (await readdir(scriptsDirectory))
  .filter((name) => name.endsWith(".test.mjs"))
  .sort()
  .map((name) => path.join(scriptsDirectory, name));

if (testFiles.length === 0) {
  throw new Error("No website test files were found.");
}

const child = spawn(process.execPath, ["--test", ...testFiles], {
  stdio: "inherit",
  shell: false,
});

child.once("error", (error) => {
  throw error;
});
child.once("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exitCode = code ?? 1;
});
