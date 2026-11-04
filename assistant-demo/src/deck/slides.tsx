import React from "react";
import { Hold, HoldsProvider } from "../clock";
import { DeckFooterProvider } from "../design";
import { Opening } from "../scenes/Opening";
import { Indexing } from "../scenes/Indexing";
import { Rewriting } from "../scenes/Rewriting";
import { Retrieval } from "../scenes/Retrieval";
import { Context } from "../scenes/Context";
import { Response } from "../scenes/Response";
import { Followup } from "../scenes/Followup";
import { AGENDA_LENGTH, Agenda } from "./Agenda";
import { Cover } from "./Cover";
import { Problem } from "./Problem";
import {
  ROW_START,
  WHY_NOT_SEARCH_LENGTH,
  WhyNotSearch,
} from "./WhyNotSearch";
import { LiveDemo } from "./LiveDemo";
import {
  DECISION_LENGTHS,
  DecisionModel,
  DecisionSearch,
  DecisionService,
} from "./Decisions";
import { WhyDivider } from "./WhyDivider";
import { Closing } from "./Closing";
import { Pitch } from "./Pitch";

export const FPS = 30;
export const WIDTH = 1920;
export const HEIGHT = 1080;

// Una slide del deck es una composición de Remotion. `stops` son las paradas,
// en segundos: lo que empieza antes de una parada termina su recorrido y lo que
// empieza después espera a que el presentador avance (ver src/clock.tsx). Por
// eso cada parada va justo antes de que arranque la fase siguiente. La última
// es el final de la escena.
export type Slide = {
  id: string;
  title: string;
  // Pie de la slide: índice en SECTIONS (null: sin marcas) y texto.
  section: number | null;
  footer: string;
  component: React.FC;
  durationInFrames: number;
  stops: number[];
};

const sec = (s: number) => Math.round(s * FPS);

// Secciones del deck: una marca por cada una en el pie de todas las slides.
export const SECTIONS = [
  "El problema",
  "La solución",
  "Cómo funciona",
  "Apuesta segura",
];
const HOW = 2;

// La apertura del recorrido abre la sección "Cómo funciona".
const HowItWorks: React.FC = () => (
  <Opening title={["Cómo funciona", "el asistente."]} />
);

// En el deck las palabras del pedido quedan un segundo más para leerlas.
const RewritingSlow: React.FC = () => <Rewriting wait={1} />;

// En el deck, la escena de memoria muestra solo el recorrido, que queda quieto
// al terminar (el cierre "Menos búsqueda. Más match." queda para el video).
const FollowupFlow: React.FC = () => <Followup part="flow" />;

export const slides: Slide[] = [
  {
    id: "Portada",
    section: null,
    footer: "Redes de Información · ITBA · 2C 2026",
    title: "Portada",
    component: Cover,
    durationInFrames: sec(3),
    stops: [3],
  },
  {
    id: "Agenda",
    section: null,
    footer: "Agenda",
    title: "Agenda",
    component: Agenda,
    durationInFrames: sec(AGENDA_LENGTH),
    stops: [AGENDA_LENGTH],
  },
  {
    id: "Problema",
    section: 0,
    footer: "El problema",
    title: "El problema",
    component: Problem,
    durationInFrames: sec(7),
    stops: [3.4, 7],
  },
  {
    id: "Buscador",
    section: 0,
    footer: "El problema · ¿Y un buscador?",
    title: "El problema · ¿Y si ponemos un buscador?",
    component: WhyNotSearch,
    durationInFrames: sec(WHY_NOT_SEARCH_LENGTH),
    stops: [ROW_START[1] - 0.1, WHY_NOT_SEARCH_LENGTH],
  },
  {
    id: "Demo",
    section: 1,
    footer: "La solución · Demo en vivo",
    title: "La solución · Demo en vivo",
    component: LiveDemo,
    durationInFrames: sec(4),
    stops: [4],
  },
  {
    id: "Apertura",
    section: HOW,
    footer: "Cómo funciona",
    title: "Cómo funciona · Apertura",
    component: HowItWorks,
    durationInFrames: sec(4),
    stops: [4],
  },
  {
    id: "Indexacion",
    section: HOW,
    footer: "Cómo funciona · Indexación",
    title: "Recorrido · Arranque e indexación",
    component: Indexing,
    durationInFrames: sec(11),
    stops: [11],
  },
  {
    id: "Reescritura",
    section: HOW,
    footer: "Cómo funciona · Reescritura",
    title: "Recorrido · Primer llamado: reescritura",
    component: RewritingSlow,
    durationInFrames: sec(10),
    stops: [10],
  },
  {
    id: "Busqueda",
    section: HOW,
    footer: "Cómo funciona · Búsqueda vectorial",
    title: "Recorrido · Búsqueda vectorial",
    component: Retrieval,
    durationInFrames: sec(11),
    stops: [11],
  },
  {
    id: "Contexto",
    section: HOW,
    footer: "Cómo funciona · Segundo llamado",
    title: "Recorrido · Segundo llamado: contexto",
    component: Context,
    durationInFrames: sec(9),
    stops: [9],
  },
  {
    id: "Respuesta",
    section: HOW,
    footer: "Cómo funciona · Respuesta",
    title: "Recorrido · Respuesta",
    component: Response,
    durationInFrames: sec(6),
    stops: [6],
  },
  {
    id: "Memoria",
    section: HOW,
    footer: "Cómo funciona · Memoria",
    title: "Recorrido · Seguimiento con memoria",
    component: FollowupFlow,
    durationInFrames: sec(6.5),
    stops: [6.5],
  },
  {
    id: "PorQue-separador",
    section: 3,
    footer: "Apuesta segura",
    title: "Apuesta segura · Separador",
    component: WhyDivider,
    durationInFrames: sec(3),
    stops: [3],
  },
  {
    id: "PorQue-servicio",
    section: 3,
    footer: "Apuesta segura · Riesgo 1: que rompa la tienda",
    title: "Apuesta segura · Un servicio más",
    component: DecisionService,
    durationInFrames: sec(DECISION_LENGTHS[0]),
    stops: [DECISION_LENGTHS[0]],
  },
  {
    id: "PorQue-modelo",
    section: 3,
    footer: "Apuesta segura · Riesgo 2: que el modelo se equivoque",
    title: "Apuesta segura · Modelo",
    component: DecisionModel,
    durationInFrames: sec(DECISION_LENGTHS[1]),
    stops: [DECISION_LENGTHS[1]],
  },
  {
    id: "PorQue-busqueda",
    section: 3,
    footer: "Apuesta segura · Riesgo 3: que no entienda",
    title: "Apuesta segura · Primero entender",
    component: DecisionSearch,
    durationInFrames: sec(DECISION_LENGTHS[2]),
    stops: [DECISION_LENGTHS[2]],
  },
  {
    id: "Producto",
    section: null,
    footer: "El producto",
    title: "El producto en una frase",
    component: Pitch,
    durationInFrames: sec(2.5),
    stops: [2.5],
  },
  {
    id: "Cierre",
    section: null,
    footer: "Redes de Información · ITBA · 2C 2026",
    title: "Cierre · Gracias, preguntas",
    component: Closing,
    durationInFrames: sec(3.5),
    stops: [3.5],
  },
];

// Segundos que se dejan correr después de una parada para que terminen los
// recorridos que estaban en curso (al volver atrás o al exportar).
export const SETTLE = 4;

// El Player no se pausa en las paradas: sigue corriendo para que el movimiento
// de fondo no se congele. Por eso dura mucho más que la escena.
export const PLAYER_SECONDS = 60 * 60;

// `stops` solo lo usa el exportador del PPT (scripts/export-pptx.mjs).
export type DeckProps = { holds: Hold[]; stops?: number[] };

// La escena envuelta con las paradas que le pasa el deck por inputProps y con
// el pie de su sección.
const withHolds = (slide: Slide): React.FC<DeckProps> => {
  const Scene = slide.component;
  const footer = {
    label: slide.footer,
    section: slide.section,
    total: SECTIONS.length,
  };
  const Wrapped: React.FC<DeckProps> = ({ holds }) => (
    <DeckFooterProvider value={footer}>
      <HoldsProvider holds={holds}>
        <Scene />
      </HoldsProvider>
    </DeckFooterProvider>
  );
  return Wrapped;
};

export const deckComponents = new Map(
  slides.map((slide) => [slide.id, withHolds(slide)]),
);
