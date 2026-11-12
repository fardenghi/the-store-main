import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Glyph, Head, Stage } from "../design";

const CAPABILITIES = [
  "Interpreta el pedido",
  "Consulta el catálogo",
  "Actúa en la tienda",
];

// Puente entre el problema y la arquitectura: el agente y sus tres capacidades.
export const Solution: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  return (
    <Stage label="La solución">
      <Head>La solución.</Head>

      <Enter
        at={0.3}
        style={{
          position: "absolute",
          left: 96,
          top: 370,
          fontSize: 100,
          lineHeight: 1.05,
          letterSpacing: -5,
          fontWeight: 500,
        }}
      >
        Un nuevo asistente
        <br />
        <span style={{ color: C.lime }}>en la tienda.</span>
      </Enter>

      <Enter
        at={0.6}
        style={{
          position: "absolute",
          left: 1250,
          top: 360,
          width: 500,
          height: 340,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        <div
          style={{
            position: "absolute",
            inset: -100,
            borderRadius: "50%",
            background:
              "radial-gradient(circle, #d4f77d16 0%, transparent 65%)",
            opacity: 0.7 + 0.2 * Math.sin(t * 1.5),
          }}
        />
        <Glyph kind="store" size={330} color={C.muted} />
        <div
          style={{
            position: "absolute",
            right: 0,
            top: 0,
            width: 170,
            height: 140,
            borderRadius: "36px 36px 36px 8px",
            background: C.lime,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            boxShadow: "0 0 60px #d4f77d25",
            translate: `0 ${Math.sin(t * 1.5) * 5}px`,
          }}
        >
          <Glyph kind="model" size={84} color={C.bg} />
        </div>
        <div
          style={{
            position: "absolute",
            bottom: -24,
            fontSize: 26,
            letterSpacing: 3,
            color: C.lime,
          }}
        >
          A.G.E.N.T.
        </div>
      </Enter>

      <div
        style={{
          position: "absolute",
          left: 96,
          right: 96,
          top: 820,
          display: "flex",
          gap: 28,
        }}
      >
        {CAPABILITIES.map((capability, i) => (
          <Enter
            key={capability}
            at={1 + i * 0.3}
            style={{
              flex: 1,
              display: "flex",
              alignItems: "center",
              gap: 22,
              padding: "24px 28px",
              borderTop: `1px solid ${C.line}`,
              fontSize: 32,
            }}
          >
            <span style={{ color: C.lime, fontSize: 23 }}>0{i + 1}</span>
            {capability}
          </Enter>
        ))}
      </div>
    </Stage>
  );
};
