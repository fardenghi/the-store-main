import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Glyph, Head, Stage } from "../design";
import { Check, progress } from "../motion";
import { SECTIONS } from "./slides";

// Índice del pitch: las cuatro secciones del deck (las mismas marcas del pie),
// una tarjeta por sección con un ícono que anticipa su contenido. Un riel las
// une y una luz lo recorre en loop, para que la slide no quede quieta mientras
// se habla.
// Títulos partidos en dos líneas para que las cuatro tarjetas queden parejas.
const TITLES = [
  ["El", "problema"],
  ["La", "solución"],
  ["Cómo", "funciona"],
  ["Apuesta", "segura"],
];
const SUBTITLES = [
  "Lo que hoy pierde la tienda",
  "El asistente, en vivo",
  "Un mensaje, punta a punta",
  "Tres riesgos, ya medidos",
];
const LEFT = 96;
const WIDTH = 1728;
const GAP = 28;
const CARD_W = (WIDTH - GAP * 3) / 4;
const RAIL_Y = 410;
const CARD_TOP = 460;
const CARD_H = 400;

const Icon: React.FC<{ index: number; t: number }> = ({ index, t }) => {
  if (index === 0) {
    return (
      <div
        style={{
          width: 84,
          height: 84,
          borderRadius: "50%",
          border: `2px solid ${C.lilac}`,
          color: C.lilac,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          fontSize: 40,
        }}
      >
        ✕
      </div>
    );
  }
  if (index === 1) {
    // Burbuja de chat con los tres puntos de "escribiendo".
    return (
      <div
        style={{
          width: 120,
          height: 84,
          boxSizing: "border-box",
          borderRadius: "30px 30px 30px 8px",
          border: `2px solid ${C.lime}`,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          gap: 12,
        }}
      >
        {[0, 1, 2].map((d) => (
          <span
            key={d}
            style={{
              width: 12,
              height: 12,
              borderRadius: "50%",
              background: C.lime,
              opacity: 0.35 + 0.65 * Math.max(0, Math.sin(t * 5 - d * 0.9)),
            }}
          />
        ))}
      </div>
    );
  }
  if (index === 2) {
    return (
      <div
        style={{
          width: 84,
          height: 84,
          boxSizing: "border-box",
          borderRadius: "50%",
          border: `2px solid ${C.lime}`,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <Glyph kind="vector" size={64} />
      </div>
    );
  }
  return (
    <div
      style={{
        width: 84,
        height: 84,
        borderRadius: "50%",
        background: C.lime,
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        boxShadow: "0 0 30px #d4f77d55",
      }}
    >
      <Check size={50} />
    </div>
  );
};

export const AGENDA_LENGTH = 3;

export const Agenda: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  const rail = progress(T(0.3), 0.3, 1.5);
  // Luz que recorre el riel una vez dibujado, en loop.
  const glow = ((t - 1.5) * 0.28) % 1;
  return (
    <Stage label="Agenda">
      <Head>Qué vamos a ver.</Head>

      <svg
        width={1920}
        height={1080}
        style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
      >
        <path
          d={`M${LEFT} ${RAIL_Y}H${LEFT + WIDTH}`}
          stroke={C.line}
          strokeWidth={2}
          pathLength={1}
          strokeDasharray={1}
          strokeDashoffset={1 - rail}
        />
        {t > 1.5 && (
          <circle
            cx={LEFT + glow * WIDTH}
            cy={RAIL_Y}
            r={7}
            fill={C.lime}
            style={{ filter: "drop-shadow(0 0 12px #d4f77d)" }}
          />
        )}
        {SECTIONS.map((_, i) => {
          const x = LEFT + i * (CARD_W + GAP) + 40;
          const on = progress(T(0.4 + i * 0.3), 0.4 + i * 0.3, 0.8 + i * 0.3);
          return (
            <circle key={i} cx={x} cy={RAIL_Y} r={9 * on} fill={C.lime} />
          );
        })}
      </svg>

      {SECTIONS.map((name, i) => (
        <Enter
          key={name}
          at={0.5 + i * 0.3}
          style={{
            position: "absolute",
            left: LEFT + i * (CARD_W + GAP),
            top: CARD_TOP,
            width: CARD_W,
            height: CARD_H,
            boxSizing: "border-box",
            padding: "36px 40px",
            background: C.panel,
            border: `1px solid ${C.line}`,
            borderRadius: 28,
            display: "flex",
            flexDirection: "column",
          }}
        >
          <div
            style={{
              display: "flex",
              justifyContent: "space-between",
              alignItems: "flex-start",
              height: 96,
            }}
          >
            <span
              style={{
                fontSize: 30,
                letterSpacing: 2,
                color: C.lime,
                fontWeight: 500,
              }}
            >
              0{i + 1}
            </span>
            <Icon index={i} t={t} />
          </div>
          <div
            style={{
              marginTop: "auto",
              fontSize: 54,
              lineHeight: 1.02,
              letterSpacing: -2.5,
              fontWeight: 500,
            }}
          >
            {TITLES[i][0]}
            <br />
            {TITLES[i][1]}
          </div>
          <div
            style={{
              marginTop: 16,
              fontSize: 27,
              lineHeight: 1.3,
              color: C.muted,
            }}
          >
            {SUBTITLES[i]}
          </div>
          {/* Las mismas marcas del pie: así se reconoce la sección después. */}
          <div style={{ display: "flex", gap: 8, marginTop: 30 }}>
            {SECTIONS.map((__, j) => (
              <div
                key={j}
                style={{
                  width: j === i ? 44 : 14,
                  height: 4,
                  background: j === i ? C.lime : C.line,
                }}
              />
            ))}
          </div>
        </Enter>
      ))}
    </Stage>
  );
};
