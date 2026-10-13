import { readFileSync } from "node:fs";
import path from "node:path";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const BACKEND = "http://localhost:8095";

/** The languages the application's own words are laid out in (`src/i18n/locales.ts`). */
const LANGUAGES = ["en", "ru", "sr"];

/**
 * The application's own words — the namespace "standard", the words of the keys the server sends —
 * laid out beside the interface as `i18n/standard/<language>.json`: the browser extension reads
 * them there, as it reads a plugin's words, and the application serves them from its static output.
 */
function standardWords(): Plugin {
  return {
    name: "standard-words",
    apply: "build",
    generateBundle() {
      for (const language of LANGUAGES) {
        const file = path.resolve(import.meta.dirname, `src/standard/standard-i18n.${language}.json`);
        // Parsed and written anew: a broken file fails the build, not the extension
        const words: unknown = JSON.parse(readFileSync(file, "utf8"));
        this.emitFile({ type: "asset", fileName: `i18n/standard/${language}.json`, source: JSON.stringify(words) });
      }
    },
  };
}

export default defineConfig({
  plugins: [react(), tailwindcss(), standardWords()],
  resolve: {
    alias: { "@": path.resolve(import.meta.dirname, "./src") },
  },
  build: {
    sourcemap: true,
  },
  server: {
    port: 5173,
    // Development talks to the running backend: the data lives in its databases, the plugins'
    // words are laid out by it, and the browser extension's archive is served by it too
    proxy: {
      "/api": BACKEND,
      "/i18n": BACKEND,
      "/extension": BACKEND,
    },
  },
});
