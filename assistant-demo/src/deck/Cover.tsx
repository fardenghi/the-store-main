import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Stage, Tag } from "../design";
import { Core, Tile, progress } from "../motion";

const team = ["Filipo Ardenghi", "Santiago Bassi", "Ezequiel Testoni"];

// Portada del deck. Los productos orbitan el asistente sin converger: la
// convergencia queda para la apertura del recorrido.
export const Cover: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  return (
    <Stage label="Redes de Información · ITBA · 2C 2026" labelRight>
      {/* Focos de luz lima, muy tenues, que derivan despacio. Se apagan antes
          del borde de la slide (el gradiente llega a 0 en el 65% del radio):
          si los cortara el borde, en pantalla se vería el marco del Player. */}
      {[
        { x: 1400, y: 520, r: 620, a: 0.09, dx: 40, dy: 30, s: 0.13 },
        { x: 420, y: 700, r: 500, a: 0.055, dx: 60, dy: 25, s: 0.09 },
        { x: 820, y: 330, r: 400, a: 0.04, dx: 50, dy: 20, s: 0.11 },
      ].map((g, i) => (
        <div
          key={i}
          style={{
            position: "absolute",
            left: g.x - g.r + Math.sin(t * g.s * 2 + i) * g.dx,
            top: g.y - g.r + Math.cos(t * g.s * 2 + i * 2) * g.dy,
            width: g.r * 2,
            height: g.r * 2,
            borderRadius: "50%",
            background: `radial-gradient(circle, rgba(212, 247, 125, ${g.a}) 0%, rgba(212, 247, 125, 0) 65%)`,
            opacity: progress(T(0), 0, 1.5),
          }}
        />
      ))}
      <Enter at={0.1} style={{ position: "absolute", left: 96, top: 230 }}>
        <Tag>Trabajo práctico especial · Tema 9</Tag>
      </Enter>
      <Enter
        at={0.3}
        style={{
          position: "absolute",
          left: 96,
          top: 310,
          fontSize: 128,
          lineHeight: 0.98,
          letterSpacing: -7,
          fontWeight: 500,
        }}
      >
        Asistente
        <br />
        de compras
        <br />
        <span style={{ color: C.lime }}>con GenAI.</span>
      </Enter>
      <Enter
        at={0.9}
        style={{ position: "absolute", left: 96, top: 760 }}
      >
        <div style={{ fontSize: 34, fontWeight: 600, letterSpacing: -1 }}>
          Grupo 3
        </div>
        <div style={{ fontSize: 28, color: C.muted, marginTop: 14 }}>
          {team.join("  –  ")}
        </div>
      </Enter>
      <div
        style={{
          position: "absolute",
          inset: 0,
          opacity: progress(T(0.4), 0.4, 1.4),
        }}
      >
        <Core x={1400} y={520} label="" size={150} />
      </div>
      {Array.from({ length: 5 }, (_, i) => {
        const a = (i * Math.PI * 2) / 5 + t * 0.22;
        const appear = progress(T(0.5 + i * 0.12), 0.5 + i * 0.12, 1.5 + i * 0.12);
        const r = 250 + 40 * (1 - appear);
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: 1400 + Math.cos(a) * r - 60,
              top: 520 + Math.sin(a) * r * 0.92 - 60,
              opacity: appear,
              rotate: `${Math.sin(a) * 8}deg`,
            }}
          >
            <Tile index={i} size={120} />
          </div>
        );
      })}
    </Stage>
  );
};
