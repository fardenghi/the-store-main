import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Stage } from "../design";
import { progress } from "../motion";

// Separador de la sección "Por qué así": un camino que se abre en tres; dos
// opciones se apagan y una queda elegida. Mismo formato de título que la
// apertura de "Cómo funciona" (dos líneas, centrado con la ilustración).
const O = { x: 1120, y: 530 };
const ENDS = [
  { x: 1700, y: 320, chosen: false },
  { x: 1760, y: 530, chosen: true },
  { x: 1700, y: 740, chosen: false },
];

export const WhyDivider: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  const draw = progress(T(0.5), 0.5, 1.6);
  const choose = progress(T(1.7), 1.7, 2.4);
  return (
    <Stage label="Por qué así">
      <Enter
        at={0.1}
        style={{
          position: "absolute",
          left: 96,
          top: 530 - 145,
          fontSize: 148,
          lineHeight: 0.98,
          letterSpacing: -8,
          fontWeight: 500,
        }}
      >
        Por qué lo
        <br />
        <span style={{ color: C.lime }}>hicimos así.</span>
      </Enter>
      <svg
        width={1920}
        height={1080}
        style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
      >
        <path
          d={`M${O.x - 160} ${O.y}H${O.x}`}
          stroke={C.lime}
          strokeWidth={3}
          pathLength={1}
          strokeDasharray={1}
          strokeDashoffset={1 - progress(T(0.3), 0.3, 0.8)}
        />
        {ENDS.map((e, i) => {
          const xm = (O.x + e.x) / 2;
          const off = !e.chosen;
          return (
            <g key={i}>
              <path
                d={`M${O.x} ${O.y}C${xm} ${O.y} ${xm} ${e.y} ${e.x} ${e.y}`}
                fill="none"
                stroke={off ? C.muted : C.lime}
                strokeWidth={3}
                pathLength={1}
                strokeDasharray={1}
                strokeDashoffset={1 - draw}
                opacity={off ? 1 - 0.7 * choose : 1}
              />
              {off ? (
                <text
                  x={e.x + 26}
                  y={e.y + 13}
                  fill={C.lilac}
                  fontSize={40}
                  opacity={choose}
                >
                  ✕
                </text>
              ) : (
                <>
                  {[0, 1].map((r) => (
                    <circle
                      key={r}
                      cx={e.x}
                      cy={e.y}
                      r={28 + r * 22 + ((t * 0.6 + r * 0.5) % 1) * 16}
                      fill="none"
                      stroke={C.lime}
                      strokeWidth={1}
                      opacity={
                        choose * (0.35 - ((t * 0.6 + r * 0.5) % 1) * 0.3)
                      }
                    />
                  ))}
                  <circle
                    cx={e.x}
                    cy={e.y}
                    r={22 * choose}
                    fill={C.lime}
                    style={{ filter: "drop-shadow(0 0 18px #d4f77d80)" }}
                  />
                </>
              )}
            </g>
          );
        })}
        <circle cx={O.x} cy={O.y} r={9} fill={C.lime} opacity={draw} />
      </svg>
    </Stage>
  );
};
