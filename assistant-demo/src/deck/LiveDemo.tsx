import React from "react";
import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Enter, Stage, clamp } from "../design";
import { progress } from "../motion";

// La misma consulta que no encontraba nada en la slide del problema.
const QUERY = "somewhere cozy to curl up with a book";

// Transición a la demo en vivo: no explica qué se va a mostrar, la deja
// servida. Se escribe la consulta del problema en la caja del chat y el botón
// de enviar queda latiendo hasta que se pasa a la app.
export const LiveDemo: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  const typed = Math.round(
    interpolate(T(1.3), [1.3, 3], [0, QUERY.length], clamp),
  );
  const done = typed === QUERY.length;
  const ready = progress(T(3), 3, 3.5);
  return (
    <Stage label="La solución">
      <Enter
        at={0.2}
        style={{
          position: "absolute",
          right: 96,
          top: 190,
          display: "flex",
          alignItems: "center",
          gap: 14,
          fontSize: 25,
          letterSpacing: 2,
          color: C.lime,
        }}
      >
        <span
          style={{
            width: 14,
            height: 14,
            borderRadius: 20,
            background: C.lime,
            opacity: 0.35 + 0.65 * Math.abs(Math.sin(t * 2.2)),
            boxShadow: `0 0 18px ${C.lime}`,
          }}
        />
        EN VIVO
      </Enter>
      <Enter
        at={0.1}
        style={{
          position: "absolute",
          left: 96,
          top: 290,
          fontSize: 116,
          lineHeight: 1,
          letterSpacing: -6,
          fontWeight: 500,
        }}
      >
        Ahora, preguntale
        <br />
        <span style={{ color: C.lime }}>como lo harías normalmente.</span>
      </Enter>
      <Enter
        at={0.8}
        style={{
          position: "absolute",
          left: 96,
          right: 96,
          top: 700,
          height: 124,
          boxSizing: "border-box",
          padding: "0 18px 0 48px",
          display: "flex",
          alignItems: "center",
          gap: 24,
          background: C.panel,
          border: `1px solid ${done ? "#d4f77d55" : C.line}`,
          borderRadius: 62,
          boxShadow: `0 0 ${ready * 60}px #d4f77d14`,
        }}
      >
        <span
          style={{
            flex: 1,
            fontSize: 44,
            letterSpacing: -1,
            color: typed ? C.ink : C.muted,
          }}
        >
          {typed ? QUERY.slice(0, typed) : "Ask A.G.E.N.T. anything…"}
          <span style={{ opacity: t % 1 < 0.6 ? 1 : 0, color: C.lime }}>▍</span>
        </span>
        <span
          style={{
            width: 88,
            height: 88,
            borderRadius: "50%",
            background: done ? C.lime : C.line,
            color: C.bg,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            scale: 1 + ready * 0.06 * Math.sin(t * 4),
          }}
        >
          <svg width={40} height={40} viewBox="0 0 40 40" fill="none">
            <path
              d="M20 31V9M10 19l10-10 10 10"
              stroke={done ? C.bg : C.muted}
              strokeWidth={3.5}
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </span>
      </Enter>
    </Stage>
  );
};
