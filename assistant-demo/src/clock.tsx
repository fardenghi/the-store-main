import React, { createContext, useContext } from "react";
import { useCurrentFrame, useVideoConfig } from "remotion";

// Paradas del deck. Una parada en el segundo `at` es una compuerta: lo que
// empieza antes de `at` sigue su curso (los recorridos terminan y el movimiento
// de fondo no se congela), y lo que empieza en `at` o después espera.
// `length` es cuánto duró la espera ya cumplida; `null` es la parada abierta,
// la que se está esperando ahora.
export type Hold = { at: number; length: number | null };

const Holds = createContext<Hold[]>([]);

export const HoldsProvider: React.FC<{
  holds: Hold[];
  children: React.ReactNode;
}> = ({ holds, children }) => (
  <Holds.Provider value={holds}>{children}</Holds.Provider>
);

// Tiempo de la escena para una animación que empieza en `start` segundos.
// Sin paradas (el video) es el tiempo real.
export const sceneTime = (t: number, holds: Hold[], start: number) => {
  let time = t;
  for (const hold of holds) {
    if (hold.at > start) continue;
    time = hold.length === null ? Math.min(time, hold.at) : time - hold.length;
  }
  return time;
};

// `T(start)` da el tiempo con el que tiene que animarse algo que empieza en
// `start`. `T()` (o `T(0)`) es el tiempo real, para el movimiento de fondo.
export const useSceneTime = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const holds = useContext(Holds);
  const t = frame / fps;
  return (start = 0) => sceneTime(t, holds, start);
};
