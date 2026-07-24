import { execFile } from "node:child_process";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

const execFileAsync = promisify(execFile);
const websiteRoot = path.dirname(fileURLToPath(import.meta.url));
const captureManifest = path.resolve(
  websiteRoot,
  "public",
  "screenshots",
  "capture-manifest.json",
);

function captureMetadataPlugin() {
  return {
    name: "nextcloud-native-capture-metadata",
    configureServer(server) {
      let generation = Promise.resolve();
      server.watcher.add(captureManifest);
      server.watcher.on("change", (changedFile) => {
        if (path.resolve(changedFile) !== captureManifest) return;
        generation = generation
          .then(() =>
            execFileAsync(
              process.execPath,
              [path.join(websiteRoot, "scripts", "generate-content.mjs")],
              { cwd: websiteRoot },
            ),
          )
          .then(() => server.ws.send({ type: "full-reload" }))
          .catch((error) => server.config.logger.error(error.message));
      });
    },
  };
}

export default defineConfig({
  plugins: [vue(), captureMetadataPlugin()],
  server: {
    host: "0.0.0.0",
  },
});
