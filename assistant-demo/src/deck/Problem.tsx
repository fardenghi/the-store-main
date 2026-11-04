import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Head, Stage, Tag } from "../design";
import { Tile, progress } from "../motion";

const LEFT = 96;
const RIGHT = 1030;
const TOP = 390;
const PANEL = { w: 800, h: 330 };
const TILE = 104;
const GAP = 22;
const COLS = 6;

// Las dos limitaciones de The Store antes del asistente. Primero la tienda sin
// buscador (solo se puede recorrer el catálogo); la parada en 3.4 s deja hablar
// de eso antes de que entre el chat, que era un LLM genérico sin datos de la
// tienda: le preguntan por un producto del catálogo y ni sabe de qué le hablan. El
// contexto (la conversación) se deja para la slide del buscador.
export const Problem: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  const chatTyping = T(4.2) > 4.2 && T(4.2) < 5;
  // El catálogo se desplaza solo: el cliente recorre productos sin poder pedir.
  const scroll = (t * 26) % (TILE + GAP);
  return (
    <Stage label="El problema">
      <Head>
        Tu cliente sabe lo que quiere.
        <br />
        <span style={{ color: C.lime }}>La tienda no lo deja pedirlo.</span>
      </Head>

      {/* Tienda sin buscador */}
      <Enter at={0.6} style={{ position: "absolute", left: LEFT, top: TOP }}>
        <Tag color={C.muted}>La tienda</Tag>
      </Enter>
      <Enter
        at={0.7}
        style={{
          position: "absolute",
          left: LEFT,
          top: TOP + 60,
          width: PANEL.w,
          height: PANEL.h,
          boxSizing: "border-box",
          background: C.panel,
          border: `1px solid ${C.line}`,
          borderRadius: 24,
          overflow: "hidden",
        }}
      >
        <div
          style={{
            height: 92,
            padding: "0 30px",
            display: "flex",
            alignItems: "center",
            borderBottom: `1px solid ${C.line}`,
            fontSize: 26,
          }}
        >
          <span style={{ fontWeight: 650, letterSpacing: -1 }}>the store</span>
        </div>
        <div
          style={{
            position: "absolute",
            left: 0,
            right: 0,
            top: 92,
            bottom: 0,
            overflow: "hidden",
          }}
        >
          <div
            style={{
              position: "absolute",
              left: 30,
              top: GAP - scroll,
              display: "grid",
              gridTemplateColumns: `repeat(${COLS}, ${TILE}px)`,
              gap: GAP,
              opacity: progress(T(1), 1, 1.6),
            }}
          >
            {Array.from({ length: COLS * 3 }, (_, i) => (
              <Tile
                key={i}
                index={(i + Math.floor(i / COLS)) % 5}
                size={TILE}
              />
            ))}
          </div>
        </div>
      </Enter>
      <Enter
        at={2.2}
        style={{
          position: "absolute",
          left: LEFT,
          top: TOP + 420,
          display: "flex",
          alignItems: "center",
          gap: 22,
          fontSize: 58,
          letterSpacing: -2.5,
          color: C.lilac,
        }}
      >
        <span style={{ fontSize: 44 }}>✕</span>
        No hay dónde buscar.
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
        <Tag color={C.muted}>El chat: un LLM genérico</Tag>
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
        How much is the Walnut lamp?
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
          "What Walnut lamp?"
        )}
      </Enter>
      {[
        "No conoce los productos ni sus precios",
        "No puede actuar sobre la tienda",
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
