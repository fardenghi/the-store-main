import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Stage, Tag } from "../design";
import { progress } from "../motion";

// Cierre del pitch: el producto en una frase, partida en tres promesas que
// entran una por vez. La última (lo que le importa al negocio) va en lima.
const LINES = [
  "Entiende lo que tu cliente pide.",
  "Conoce todo tu catálogo.",
  "Lo lleva hasta el carrito.",
];

export const Pitch: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  return (
    <Stage label="El producto">
      <div
        style={{
          position: "absolute",
          left: 1500 - 700 + Math.sin(t * 0.26) * 40,
          top: 560 - 700 + Math.cos(t * 0.2) * 30,
          width: 1400,
          height: 1400,
          borderRadius: "50%",
          background:
            "radial-gradient(circle, rgba(212, 247, 125, 0.08) 0%, rgba(212, 247, 125, 0) 65%)",
          opacity: progress(T(0), 0, 1.5),
        }}
      />
      <Enter at={0.1} style={{ position: "absolute", left: 96, top: 300 }}>
        <Tag>Asistente de compras con GenAI</Tag>
      </Enter>
      {LINES.map((line, i) => (
        <Enter
          key={line}
          at={0.4 + i * 0.6}
          style={{
            position: "absolute",
            left: 96,
            top: 380 + i * 150,
            fontSize: 104,
            lineHeight: 1,
            letterSpacing: -5,
            fontWeight: 500,
            whiteSpace: "nowrap",
            color: i === LINES.length - 1 ? C.lime : C.ink,
          }}
        >
          {line}
        </Enter>
      ))}
    </Stage>
  );
};
