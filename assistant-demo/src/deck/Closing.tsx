import React from "react";
import { useSceneTime } from "../clock";
import { C, Enter, Stage } from "../design";
import { Core, Tile, progress } from "../motion";

// Cierre: "Gracias. ¿Preguntas?" con la misma animación que la portada (los
// productos orbitando el asistente) y sus focos de luz, para cerrar igual que
// se abrió.
export const Closing: React.FC = () => {
  const T = useSceneTime();
  const t = T();
  return (
    <Stage label="Redes de Información · ITBA · 2C 2026" labelRight>
      {[
        { x: 1400, y: 520, r: 620, a: 0.09, dx: 40, dy: 30, s: 0.13 },
        { x: 420, y: 640, r: 500, a: 0.055, dx: 60, dy: 25, s: 0.09 },
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
        Gracias.
        <br />
        <span style={{ color: C.lime }}>¿Preguntas?</span>
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
        const appear = progress(
          T(0.5 + i * 0.12),
          0.5 + i * 0.12,
          1.5 + i * 0.12,
        );
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
