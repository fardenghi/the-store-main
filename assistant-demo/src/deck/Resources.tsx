import React from "react";
import { C, Enter, Head, Stage, Tag } from "../design";

export type ResourcesProps = { repo: string; video: string };

// Última slide del PPT entregable: dónde ver las animaciones y el código.
// No es parte del deck en vivo.
export const Resources: React.FC<ResourcesProps> = ({ repo, video }) => {
  const rows: [string, string, string][] = [
    [
      "Video de la demo",
      video || "—",
      "Las animaciones de estas slides, en movimiento",
    ],
    [
      "Código y how-to",
      repo,
      "HOW-TO.md: cómo desplegarlo y probar cada caso",
    ],
    [
      "Presentación interactiva",
      "assistant-demo/ · npm run deck",
      "El deck con las paradas, tal como se presentó",
    ],
  ];
  return (
    <Stage label="Recursos">
      <Head>En movimiento.</Head>
      {rows.map(([label, value, note], i) => (
        <Enter
          key={label}
          at={0.3 + i * 0.25}
          style={{ position: "absolute", left: 96, top: 380 + i * 170 }}
        >
          <Tag color={i === 0 ? C.lime : C.muted}>{label}</Tag>
          <div
            style={{
              fontSize: 46,
              letterSpacing: -1.5,
              marginTop: 14,
              color: i === 0 ? C.lime : C.ink,
            }}
          >
            {value}
          </div>
          <div style={{ fontSize: 26, color: C.muted, marginTop: 8 }}>
            {note}
          </div>
        </Enter>
      ))}
    </Stage>
  );
};
