import React from "react";
import { Hold, HoldsProvider } from "../clock";
import { Opening } from "../scenes/Opening";
import { Indexing } from "../scenes/Indexing";
import { Rewriting } from "../scenes/Rewriting";
import { Retrieval } from "../scenes/Retrieval";
import { Context } from "../scenes/Context";
import { Response } from "../scenes/Response";
import { Followup } from "../scenes/Followup";

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
  component: React.FC;
  durationInFrames: number;
  stops: number[];
};

const sec = (s: number) => Math.round(s * FPS);

export const slides: Slide[] = [
  {
    id: "Apertura",
    title: "Recorrido · Apertura",
    component: Opening,
    durationInFrames: sec(4),
    stops: [1.7, 4],
  },
  {
    id: "Indexacion",
    title: "Recorrido · Arranque e indexación",
    component: Indexing,
    durationInFrames: sec(11),
    stops: [0.95, 11],
  },
  {
    id: "Reescritura",
    title: "Recorrido · Primer llamado: reescritura",
    component: Rewriting,
    durationInFrames: sec(9),
    stops: [1.25, 4.25, 9],
  },
  {
    id: "Busqueda",
    title: "Recorrido · Búsqueda vectorial",
    component: Retrieval,
    durationInFrames: sec(11),
    stops: [4.05, 11],
  },
  {
    id: "Contexto",
    title: "Recorrido · Segundo llamado: contexto",
    component: Context,
    durationInFrames: sec(9),
    stops: [0.95, 9],
  },
  {
    id: "Respuesta",
    title: "Recorrido · Respuesta",
    component: Response,
    durationInFrames: sec(6),
    stops: [6],
  },
  {
    id: "Memoria",
    title: "Recorrido · Seguimiento con memoria",
    component: Followup,
    durationInFrames: sec(9),
    stops: [0.65, 6.25, 9],
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

// La escena envuelta con las paradas que le pasa el deck por inputProps.
const withHolds = (Scene: React.FC): React.FC<DeckProps> => {
  const Wrapped: React.FC<DeckProps> = ({ holds }) => (
    <HoldsProvider holds={holds}>
      <Scene />
    </HoldsProvider>
  );
  return Wrapped;
};

export const deckComponents = new Map(
  slides.map((slide) => [slide.id, withHolds(slide.component)]),
);
