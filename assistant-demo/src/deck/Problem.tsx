import React from "react";
import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Enter, Head, Stage, Tag, clamp } from "../design";
import { progress } from "../motion";

const QUERY = "somewhere cozy to curl up with a book";
const LEFT = 96;
const RIGHT = 1030;
const TOP = 390;

// Las dos limitaciones de The Store antes del asistente (sección 1 de la
// pre-entrega). Primero la búsqueda; la parada en 3.4 s deja hablar de ella
// antes de que entre el chat.
export const Problem: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  const typed = Math.round(
    interpolate(T(0.9), [0.9, 2.3], [0, QUERY.length], clamp),
  );
  const chatTyping = T(4.2) > 4.2 && T(4.2) < 5;
  return (
    <Stage label="El problema">
      <Head>La tienda no entiende lo que buscás.</Head>

      {/* Búsqueda */}
      <Enter at={0.6} style={{ position: "absolute", left: LEFT, top: TOP }}>
        <Tag color={C.muted}>Búsqueda del catálogo</Tag>
      </Enter>
      <Enter
        at={0.7}
        style={{
          position: "absolute",
          left: LEFT,
          top: TOP + 60,
          width: 800,
          boxSizing: "border-box",
          padding: "28px 34px",
          background: C.panel,
          border: `1px solid ${C.line}`,
          borderRadius: 24,
          fontSize: 36,
          letterSpacing: -0.5,
        }}
      >
        “{QUERY.slice(0, typed)}
        {typed === QUERY.length ? "”" : ""}
        <span style={{ opacity: typed < QUERY.length && t % 1 < 0.6 ? 1 : 0 }}>
          ▍
        </span>
      </Enter>
      <Enter
        at={2.4}
        style={{
          position: "absolute",
          left: LEFT + 4,
          top: TOP + 200,
          fontSize: 24,
          color: C.muted,
          fontFamily: "Menlo, Consolas, monospace",
        }}
      >
        GET /catalog/products?tags=
        <span style={{ color: C.ink }}>somewhere+cozy+to+curl…</span>
      </Enter>
      <div
        style={{
          position: "absolute",
          left: LEFT,
          top: TOP + 265,
          display: "flex",
          gap: 14,
        }}
      >
        {Array.from({ length: 4 }, (_, i) => (
          <div
            key={i}
            style={{
              width: 84,
              height: 84,
              borderRadius: 18,
              border: `2px dashed ${C.line}`,
              opacity:
                progress(T(2.5 + i * 0.08), 2.5 + i * 0.08, 2.9 + i * 0.08) *
                (1 - 0.6 * progress(T(3), 3, 3.6)),
            }}
          />
        ))}
        <Enter
          at={3}
          style={{
            marginLeft: 26,
            fontSize: 72,
            letterSpacing: -3,
            color: C.lilac,
            lineHeight: "84px",
          }}
        >
          0 productos
        </Enter>
      </div>
      <Enter
        at={3.1}
        style={{
          position: "absolute",
          left: LEFT,
          top: TOP + 385,
          fontSize: 28,
          color: C.muted,
          maxWidth: 800,
          lineHeight: 1.4,
        }}
      >
        Solo filtra por tags exactos.
        <br />
        Sin sinónimos ni tolerancia a errores de tipeo.
      </Enter>

      {/* Separador */}
      <div
        style={{
          position: "absolute",
          left: 958,
          top: TOP,
          width: 1,
          height: 470 * progress(T(3.5), 3.5, 4.3),
          background: C.line,
        }}
      />

      {/* Chat */}
      <Enter at={3.6} style={{ position: "absolute", left: RIGHT, top: TOP }}>
        <Tag color={C.muted}>Chat de la tienda</Tag>
      </Enter>
      <Enter
        at={3.8}
        style={{
          position: "absolute",
          right: 96,
          top: TOP + 60,
          padding: "22px 30px",
          background: C.ink,
          color: C.bg,
          borderRadius: "26px 26px 6px 26px",
          fontSize: 32,
        }}
      >
        too pricey, anything cheaper?
      </Enter>
      <Enter
        at={4.2}
        style={{
          position: "absolute",
          left: RIGHT,
          top: TOP + 170,
          padding: "22px 30px",
          background: C.panel,
          border: `1px solid ${C.line}`,
          borderRadius: "26px 26px 26px 6px",
          fontSize: 32,
          minWidth: 120,
        }}
      >
        {chatTyping ? (
          <span style={{ letterSpacing: 6, color: C.muted }}>
            {".".repeat(1 + (Math.floor(t * 4) % 3))}
          </span>
        ) : (
          "Cheaper than what?"
        )}
      </Enter>
      {[
        "No conoce el catálogo",
        "No recuerda la conversación",
        "No compara ni actúa sobre la tienda",
      ].map((text, i) => (
        <Enter
          key={text}
          at={5.3 + i * 0.25}
          style={{
            position: "absolute",
            left: RIGHT,
            top: TOP + 300 + i * 58,
            display: "flex",
            alignItems: "center",
            gap: 18,
            fontSize: 30,
          }}
        >
          <span style={{ color: C.lilac, fontSize: 30, width: 24 }}>✕</span>
          {text}
        </Enter>
      ))}
    </Stage>
  );
};
