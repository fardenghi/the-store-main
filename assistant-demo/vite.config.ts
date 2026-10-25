import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Deck de la presentación: sirve deck/index.html con las escenas de src/ y
// los assets de public/ en la raíz, como los resuelve staticFile().
export default defineConfig({
  root: "deck",
  publicDir: "../public",
  plugins: [react()],
  server: { port: 3200 },
  build: { outDir: "../dist/deck", emptyOutDir: true },
});
