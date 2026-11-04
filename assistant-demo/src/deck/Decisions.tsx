import React from "react";
import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Glyph, Stage, clamp } from "../design";
import { Core, Rail, Tile, Check, progress } from "../motion";

// "Apuesta segura": tres decisiones, una por slide (un riesgo cada una). Cada una tiene un título
// corto y una ilustración. Fuentes: benchmark de modelos en
// src/assistant/README.md y el log de cada turno (topk contra rawTopk).
// Cada decisión es su propia slide: todas arrancan en 0 y `to` es su
// duración (la parada del deck, al final).
const PHASES = [
  { from: 0, to: 3.8 },
  { from: 0, to: 3.2 },
  { from: 0, to: 5.7 },
];
export const DECISION_LENGTHS = PHASES.map((p) => p.to);

const usePhase = (k: number) => {
  const T = useSceneTime();
  const { from } = PHASES[k];
  return progress(T(from), from, from + 0.5);
};

const Title: React.FC<{ k: number; children: React.ReactNode }> = ({
  k,
  children,
}) => {
  const T = useSceneTime();
  const { from } = PHASES[k];
  const y = interpolate(T(from), [from, from + 0.8], [30, 0], clamp);
  return (
    <div
      style={{
        position: "absolute",
        left: 96,
        top: 157,
        fontSize: 94,
        lineHeight: 1.04,
        fontWeight: 500,
        letterSpacing: -5,
        translate: `0 ${y}px`,
      }}
    >
      {children}
    </div>
  );
};

// 1. Un servicio más: la arquitectura como un árbol acostado. La ui se abre a
// los servicios; assistant, en el centro, entra como uno más y se abre a
// Qdrant y al proveedor externo de los modelos.
const UI = { x: 330, y: 560, w: 170, h: 72 };
const COL = 900;
const services = [
  { name: "catalog", y: 340 },
  { name: "carts", y: 430 },
  { name: "orders", y: 690 },
  { name: "checkout", y: 780 },
];
const SVC = { w: 220, h: 64 };
const ASSIST = { y: 560, w: 330, h: 130 };
const KIDS = 1480;
const QDRANT = { y: 430, w: 260, h: 92 };
const MODEL = { y: 690, h: 110 };

// Rama curva de (x1, y1) a (x2, y2), que se dibuja con `p` de 0 a 1.
const Branch: React.FC<{
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  p: number;
  color?: string;
}> = ({ x1, y1, x2, y2, p, color = C.line }) => {
  const xm = (x1 + x2) / 2;
  return (
    <path
      d={`M${x1} ${y1}C${xm} ${y1} ${xm} ${y2} ${x2} ${y2}`}
      fill="none"
      stroke={color}
      strokeWidth={2}
      pathLength={1}
      strokeDasharray={1}
      strokeDashoffset={1 - p}
    />
  );
};

const Pill: React.FC<{
  x: number;
  y: number;
  w: number;
  h: number;
  opacity: number;
  children: React.ReactNode;
  style?: React.CSSProperties;
}> = ({ x, y, w, h, opacity, children, style }) => (
  <div
    style={{
      position: "absolute",
      left: x - w / 2,
      top: y - h / 2,
      width: w,
      height: h,
      boxSizing: "border-box",
      borderRadius: h / 2,
      background: C.panel,
      border: `1px solid ${C.line}`,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      gap: 18,
      opacity,
      ...style,
    }}
  >
    {children}
  </div>
);

const mono: React.CSSProperties = {
  fontFamily: "Menlo, Consolas, monospace",
  fontSize: 26,
  color: C.muted,
};

const ServicePhase: React.FC = () => {
  const T = useSceneTime();
  const opacity = usePhase(0);
  const svcAt = (i: number) => 0.4 + i * 0.12;
  const assist = progress(T(1.2), 1.2, 2);
  const qdrant = progress(T(2), 2, 2.6);
  const cloud = progress(T(2.6), 2.6, 3.2);
  const uiRight = UI.x + UI.w / 2;
  const assistRight = COL + ASSIST.w / 2;
  return (
    <div style={{ position: "absolute", inset: 0, opacity }}>
      <Title k={0}>
        Un servicio <span style={{ color: C.lime }}>más.</span>
      </Title>
      <svg
        width={1920}
        height={1080}
        style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
      >
        {services.map((s, i) => (
          <Branch
            key={s.name}
            x1={uiRight}
            y1={UI.y}
            x2={COL - SVC.w / 2}
            y2={s.y}
            p={progress(T(svcAt(i)), svcAt(i), svcAt(i) + 0.6)}
          />
        ))}
        <Branch
          x1={uiRight}
          y1={UI.y}
          x2={COL - ASSIST.w / 2}
          y2={ASSIST.y}
          p={assist}
          color={C.lime}
        />
        <Branch
          x1={assistRight}
          y1={ASSIST.y}
          x2={KIDS - QDRANT.w / 2}
          y2={QDRANT.y}
          p={qdrant}
          color={C.lime}
        />
        <Branch
          x1={assistRight}
          y1={ASSIST.y}
          x2={KIDS - 200}
          y2={MODEL.y}
          p={cloud}
          color={C.lime}
        />
      </svg>

      <Pill {...UI} opacity={progress(T(0.2), 0.2, 0.6)}>
        <span style={{ ...mono, color: C.ink }}>ui</span>
      </Pill>
      {services.map((s, i) => (
        <Pill
          key={s.name}
          x={COL}
          y={s.y}
          {...SVC}
          opacity={progress(T(svcAt(i) + 0.3), svcAt(i) + 0.3, svcAt(i) + 0.7)}
        >
          <span style={mono}>{s.name}</span>
        </Pill>
      ))}

      {/* assistant: el nodo protagonista, en el centro. */}
      <Pill
        x={COL}
        y={ASSIST.y}
        w={ASSIST.w}
        h={ASSIST.h}
        opacity={progress(T(1.4), 1.4, 1.8)}
        style={{
          background: C.lime,
          border: "none",
          gap: 22,
          scale: interpolate(assist, [0, 1], [0.8, 1], clamp),
          boxShadow: `0 0 ${70 * assist}px #d4f77d30`,
        }}
      >
        <Glyph kind="model" size={74} color={C.bg} />
        <span style={{ fontSize: 40, letterSpacing: -1, color: C.bg }}>
          assistant
        </span>
      </Pill>

      <Pill
        x={KIDS}
        y={QDRANT.y}
        w={QDRANT.w}
        h={QDRANT.h}
        opacity={qdrant}
        style={{ border: `1px solid ${C.lime}` }}
      >
        <Glyph kind="db" size={50} />
        <span style={{ fontSize: 30 }}>qdrant</span>
      </Pill>

      {/* Modelos: proveedor externo. */}
      <Pill
        x={KIDS}
        y={MODEL.y}
        w={400}
        h={MODEL.h}
        opacity={cloud}
        style={{
          justifyContent: "flex-start",
          padding: "0 30px",
          gap: 20,
          whiteSpace: "nowrap",
          border: `1px solid ${C.lime}`,
          translate: `${50 * (1 - cloud)}px 0`,
          boxShadow: `0 0 ${50 * cloud}px #d4f77d20`,
        }}
      >
        <svg width={64} height={44} viewBox="0 0 64 44" fill="none">
          <path
            d="M18 40h30a12 12 0 0 0 1-24A17 17 0 0 0 16 13 13.5 13.5 0 0 0 18 40Z"
            stroke={C.lime}
            strokeWidth={3}
            strokeLinejoin="round"
          />
        </svg>
        <div>
          <div style={{ fontSize: 30, color: C.lime }}>NVIDIA · Gemini</div>
          <div style={{ fontSize: 21, color: C.muted, marginTop: 4 }}>
            externo · HTTPS
          </div>
        </div>
      </Pill>
    </div>
  );
};

// 2. Elegido con datos: el benchmark del flujo de carrito.
const bench = [
  { name: "muse-glimmer-30b", ok: 14, of: 15, best: true },
  { name: "nemotron-3-super", ok: 3, of: 6, best: false },
];
const ModelPhase: React.FC = () => {
  const T = useSceneTime();
  const opacity = usePhase(1);
  const { from } = PHASES[1];
  return (
    <div style={{ position: "absolute", inset: 0, opacity }}>
      <Title k={1}>
        Modelo, elegido con <span style={{ color: C.lime }}>datos.</span>
      </Title>
      {bench.map((b, k) => {
        const at = from + 0.5 + k * 0.35;
        const fill = progress(T(at), at, at + 1.4);
        const y = 400 + k * 190;
        return (
          <div
            key={b.name}
            style={{ opacity: progress(T(at - 0.2), at - 0.2, at + 0.3) }}
          >
            <div
              style={{
                position: "absolute",
                left: 96,
                top: y,
                fontSize: 34,
                color: b.best ? C.ink : C.muted,
              }}
            >
              {b.name}
            </div>
            <div
              style={{
                position: "absolute",
                left: 96,
                top: y + 62,
                width: 1380,
                height: 34,
                borderRadius: 17,
                background: C.panel,
                border: `1px solid ${C.line}`,
                overflow: "hidden",
              }}
            >
              <div
                style={{
                  width: `${(b.ok / b.of) * 100 * fill}%`,
                  height: "100%",
                  borderRadius: 17,
                  background: b.best ? C.lime : C.muted,
                  boxShadow: b.best ? "0 0 30px #d4f77d40" : undefined,
                }}
              />
            </div>
            <div
              style={{
                position: "absolute",
                left: 1520,
                top: y + 18,
                fontSize: 96,
                letterSpacing: -4,
                color: b.best ? C.lime : C.muted,
              }}
            >
              {/* Porcentaje: los dos modelos se midieron con distinta cantidad
                  de sesiones (15 y 6), así que el "x/n" no se compara. */}
              {Math.round((b.ok / b.of) * 100 * fill)}
              <span style={{ fontSize: 48, color: C.muted }}>%</span>
            </div>
          </div>
        );
      })}
      <div
        style={{
          position: "absolute",
          left: 96,
          top: 810,
          fontSize: 22,
          letterSpacing: 2,
          textTransform: "uppercase",
          color: C.muted,
          opacity: progress(T(from + 1.6), from + 1.6, from + 2.1),
        }}
      >
        Pedidos de carrito resueltos bien
      </div>
    </div>
  );
};

// 3. Primero entender: un caso medido de RewriteEvalSmokeIT
// (src/assistant/README.md). "too pricey, anything cheaper?" crudo trae 1 de 5
// productos correctos (alfombras y macetas); reescrito como "sofa" con
// maxPrice 988 trae 5 de 5. Las fotos son asientos reales del catálogo por
// debajo de $988. (Total de las 10 consultas: 42/50 crudo
// contra 45/50 reescrito, promedio de 3 corridas: 45, 43 y 47.)
const SEATS = [
  "fad3214a-b0f1-53fc-9d14-c5963ad4eda2", // Sloane Tufted Leather Loveseat, $869
  "bff46bca-ec50-582f-9cde-6d843c1de176", // Aiden Mid-Century Velvet Armchair, $139
  "c0fe4c99-af31-5924-acb9-253adf39315c", // Lauren Oversized Armchair, $229
  "8c215b43-9f6f-5f0e-ae47-bbc408b7d740", // Classic Tufted Chaise Lounge, $479
  "efa779a8-7351-59dc-ab26-46e755b02bea", // Villain Leather Lounge Chair, $479
];
const SearchPhase: React.FC = () => {
  const T = useSceneTime();
  const opacity = usePhase(2);
  const { from } = PHASES[2];
  const lanes = [
    {
      y: 490,
      title: "Query original",
      query: "too pricey, anything cheaper?",
      good: false,
      rewrite: false,
    },
    {
      y: 760,
      title: "Query procesada",
      query: "sofa · ≤ $988",
      good: true,
      rewrite: true,
    },
  ];
  // Las dos consultas, centradas en la misma columna.
  const chipW = 480;
  const chipX = 96 + chipW / 2;
  return (
    <div style={{ position: "absolute", inset: 0, opacity }}>
      <Title k={2}>
        <span style={{ color: C.lime }}>Entender primero</span> mejora el
        resultado.
      </Title>
      {lanes.map((lane, k) => {
        // La segunda fila arranca cuando terminó la primera (la última foto
        // de la fila entra a los ~2 s), para que se vea la diferencia.
        const at = from + 0.4 + k * 2.4;
        return (
          <React.Fragment key={lane.query}>
            <div
              style={{
                position: "absolute",
                left: chipX - chipW / 2,
                width: chipW,
                top: lane.y - 92,
                textAlign: "center",
                fontSize: 21,
                letterSpacing: 2,
                textTransform: "uppercase",
                color: lane.good ? C.lime : C.muted,
                opacity: progress(T(at), at, at + 0.5),
              }}
            >
              {lane.title}
            </div>
            {/* La línea aparece con su fila: Rail dibuja el trazo de base desde el
                principio, y sin esto la segunda fila se vería antes de tiempo. */}
            <div
              style={{
                position: "absolute",
                inset: 0,
                opacity: progress(T(at), at, at + 0.5),
              }}
            >
              <Rail
                x1={chipX + chipW / 2}
                y1={lane.y}
                x2={1110}
                y2={lane.y}
                at={at + 0.3}
                color={lane.good ? C.lime : C.muted}
              />
            </div>
            <div
              style={{
                position: "absolute",
                left: chipX - chipW / 2,
                width: chipW,
                top: lane.y - 40,
                height: 80,
                boxSizing: "border-box",
                padding: "0 24px",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                gap: 18,
                borderRadius: 40,
                background: C.panel,
                border: `1px solid ${lane.good ? C.lime : C.line}`,
                fontSize: lane.good ? 36 : 30,
                color: lane.good ? C.lime : C.muted,
                opacity: progress(T(at), at, at + 0.5),
              }}
            >
              {lane.rewrite && (
                <span
                  style={{
                    width: 40,
                    height: 40,
                    borderRadius: 13,
                    background: C.lilac,
                    color: C.bg,
                    fontSize: 20,
                    fontWeight: 600,
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  01
                </span>
              )}
              {lane.query}
            </div>
            <div style={{ opacity: progress(T(at + 0.3), at + 0.3, at + 0.8) }}>
              <Core
                x={760}
                y={lane.y}
                kind="db"
                label=""
                size={96}
                active={lane.good ? 1 : 0.2}
              />
            </div>
            {Array.from({ length: 5 }, (_, i) => {
              const tAt = at + 1 + i * 0.12;
              const hit = lane.good || i === 1;
              return (
                <div
                  key={i}
                  style={{
                    position: "absolute",
                    left: 1110 + i * 128,
                    top: lane.y - 54,
                    opacity:
                      progress(T(tAt), tAt, tAt + 0.4) * (hit ? 1 : 0.28),
                    scale: interpolate(
                      T(tAt),
                      [tAt, tAt + 0.4],
                      [0.6, 1],
                      clamp,
                    ),
                  }}
                >
                  {hit ? (
                    <Tile
                      index={0}
                      id={SEATS[i]}
                      size={108}
                      selected={lane.good}
                    />
                  ) : (
                    <div
                      style={{
                        width: 108,
                        height: 108,
                        borderRadius: 24,
                        border: `2px dashed ${C.muted}`,
                      }}
                    />
                  )}
                </div>
              );
            })}
            {lane.good && (
              <div
                style={{
                  position: "absolute",
                  left: 1110 + 5 * 128 - 8,
                  top: lane.y - 26,
                  width: 52,
                  height: 52,
                  borderRadius: "50%",
                  background: C.lime,
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  scale: interpolate(
                    T(at + 1.8),
                    [at + 1.8, at + 2.1, at + 2.3],
                    [0, 1.15, 1],
                    clamp,
                  ),
                }}
              >
                <Check size={30} />
              </div>
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
};

export const DecisionService: React.FC = () => (
  <Stage label="Apuesta segura">
    <ServicePhase />
  </Stage>
);
export const DecisionModel: React.FC = () => (
  <Stage label="Apuesta segura">
    <ModelPhase />
  </Stage>
);
export const DecisionSearch: React.FC = () => (
  <Stage label="Apuesta segura">
    <SearchPhase />
  </Stage>
);
