// Genera el PPT entregable a partir del deck: una slide por parada, con la
// imagen de la parada ya asentada (lo que ve el público mientras se habla).
// Sin notas del orador: el discurso no va en el entregable. Cierra con la slide
// de recursos (video y repositorio).
//
//   npm run pptx                                  # sin link al video
//   VIDEO_URL=https://... npm run pptx            # con link al video
//
// Salida: out/the-store-presentacion.pptx (las imágenes quedan en out/pptx/).

import { mkdir, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { bundle } from "@remotion/bundler";
import {
  getCompositions,
  openBrowser,
  renderStill,
  selectComposition,
} from "@remotion/renderer";
import pptxgen from "pptxgenjs";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const OUT = path.join(ROOT, "out");
const IMAGES = path.join(OUT, "pptx");
const PPTX = path.join(OUT, "the-store-presentacion.pptx");
// Igual que SETTLE en src/deck/slides.tsx: segundos después de la parada.
const SETTLE = 4;
const VIDEO_URL = process.env.VIDEO_URL ?? "";

const main = async () => {
  await rm(IMAGES, { recursive: true, force: true });
  await mkdir(IMAGES, { recursive: true });

  console.log("Armando el bundle de Remotion...");
  const serveUrl = await bundle({
    entryPoint: path.join(ROOT, "src", "index.ts"),
  });
  const browser = await openBrowser("chrome");
  try {
    const compositions = (
      await getCompositions(serveUrl, { puppeteerInstance: browser })
    ).filter((c) => c.id.startsWith("Deck-"));

    const pptx = new pptxgen();
    pptx.layout = "LAYOUT_16x9";
    pptx.title = "The Store · A.G.E.N.T. — Asistente de compras con GenAI";
    pptx.author = "Grupo 3 · Redes de Información · ITBA";

    // Las props de la imagen (por ejemplo, las paradas) tienen que pasar por
    // selectComposition: renderStill usa las props de la composición resuelta.
    const still = async (id, props, frame, output) => {
      const composition = await selectComposition({
        serveUrl,
        id,
        inputProps: props,
        puppeteerInstance: browser,
      });
      await renderStill({
        composition,
        serveUrl,
        output,
        frame,
        inputProps: props,
        imageFormat: "jpeg",
        jpegQuality: 92,
        puppeteerInstance: browser,
      });
    };

    const addSlide = (image) => {
      const slide = pptx.addSlide();
      slide.background = { color: "111410" };
      slide.addImage({ path: image, x: 0, y: 0, w: 10, h: 5.625 });
    };

    let n = 0;
    for (const composition of compositions) {
      const { stops } = composition.props;
      if (!stops) continue;
      for (let k = 0; k < stops.length; k++) {
        // La parada k abierta, con las anteriores cumplidas sin espera.
        const holds = stops
          .slice(0, k + 1)
          .map((at, i) => ({ at, length: i < k ? 0 : null }));
        const frame = Math.min(
          Math.round((stops[k] + SETTLE) * composition.fps),
          composition.durationInFrames - 1,
        );
        const image = path.join(
          IMAGES,
          `${String(++n).padStart(2, "0")}-${composition.id}-${k + 1}.jpg`,
        );
        await still(
          composition.id,
          { ...composition.props, holds },
          frame,
          image,
        );
        addSlide(image);
        console.log(`  ${path.basename(image)}`);
      }
    }

    const resources = compositions.find((c) => c.id === "Deck-Recursos");
    if (resources) {
      const image = path.join(
        IMAGES,
        `${String(++n).padStart(2, "0")}-Deck-Recursos.jpg`,
      );
      await still(
        resources.id,
        { ...resources.props, video: VIDEO_URL },
        resources.durationInFrames - 1,
        image,
      );
      addSlide(image);
      console.log(`  ${path.basename(image)}`);
    }

    await pptx.writeFile({ fileName: PPTX });
    console.log(`\n${n} slides → ${path.relative(ROOT, PPTX)}`);
    if (!VIDEO_URL) {
      console.log(
        "Sin VIDEO_URL: la slide de recursos no tiene el link al video.",
      );
    }
  } finally {
    await browser.close({ silent: true });
  }
};

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
