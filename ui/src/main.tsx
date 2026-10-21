import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

// Fuentes incluidas en el proyecto: la interfaz funciona sin internet.
// Solo los juegos de caracteres que se usan (latín + latín extendido para tildes y ñ).
// El japonés usa una fuente recortada (ver scripts/subset_jp_font.py y theme.css).
import "@fontsource/barlow-condensed/latin-400.css";
import "@fontsource/barlow-condensed/latin-ext-400.css";
import "@fontsource/barlow-condensed/latin-700.css";
import "@fontsource/barlow-condensed/latin-ext-700.css";
import "@fontsource/share-tech-mono/latin-400.css";
import "@fontsource/shippori-mincho-b1/latin-800.css";
import "@fontsource/shippori-mincho-b1/latin-ext-800.css";

import { App } from "./App";
import "./styles/theme.css";

const root = document.getElementById("root");
if (!root) throw new Error("Falta el elemento #root");

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
