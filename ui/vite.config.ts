import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

import react from "@vitejs/plugin-react";
import type { Plugin } from "vite";
import { defineConfig } from "vitest/config";

const TOKEN_FILE = fileURLToPath(new URL("../config/token.txt", import.meta.url));

/**
 * En desarrollo (`npm run dev`) mete el token en la página, igual que hace el
 * servidor de FastAPI en producción. El token nunca se escribe en el bundle.
 */
function devToken(): Plugin {
  return {
    name: "dev-token",
    apply: "serve",
    transformIndexHtml(html) {
      let token = "";
      try {
        token = readFileSync(TOKEN_FILE, "utf-8").trim();
      } catch {
        console.warn("No encuentro config/token.txt: arranca el servidor una vez para crearlo.");
      }
      return html.replace("__ASSISTANT_TOKEN__", token);
    },
  };
}

export default defineConfig({
  plugins: [react(), devToken()],
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    // El WebSocket va al servidor de Python.
    proxy: { "/ws": { target: "ws://127.0.0.1:8000", ws: true } },
  },
  build: { outDir: "dist", emptyOutDir: true },
  test: { environment: "node" },
});
