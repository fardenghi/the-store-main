import React from "react";
import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Glyph, Stage, clamp } from "../design";
import { Check, progress } from "../motion";

// Las dos piezas nuevas, a grandes rasgos. A la izquierda, qué es un modelo
// de lenguaje: con el contexto predice el próximo token, y repite. A la
// derecha, qué es una base vectorial: cada producto pasa por el modelo de
// embeddings y queda como un punto; buscar es encontrar los puntos más
// cercanos a la consulta. La parada deja hablar de las dos antes de que entre
// la comparación con las bases que ya usa la tienda (MySQL en catalog y
// DynamoDB en carts, en su versión original).
export const CONCEPTS_STOP = 3.6;
export const CONCEPTS_LENGTH = 6;

const mono = "Menlo, Consolas, monospace";
const LEFT = 96;
const LEFT_W = 784;
const RIGHT = 1000;
const RIGHT_W = 824;
const DIVIDER = 940;

const useShow = () => {
  const T = useSceneTime();
  return (at: number) => {
    const p = progress(T(at), at, at + 0.5);
    return { opacity: p, translate: `0 ${(1 - p) * 18}px` };
  };
};

const Caps: React.FC<{
  children: React.ReactNode;
  color?: string;
  style?: React.CSSProperties;
}> = ({ children, color = C.lime, style }) => (
  <div
    style={{
      fontSize: 20,
      letterSpacing: 2,
      textTransform: "uppercase",
      color,
      ...style,
    }}
  >
    {children}
  </div>
);

const Heading: React.FC<{
  children: React.ReactNode;
  left: number;
  style: React.CSSProperties;
}> = ({ children, left, style }) => (
  <div
    style={{
      position: "absolute",
      left,
      top: 318,
      fontSize: 46,
      fontWeight: 500,
      letterSpacing: -1.5,
      ...style,
    }}
  >
    {children}
  </div>
);

const Bullet: React.FC<{
  children: React.ReactNode;
  style: React.CSSProperties;
}> = ({ children, style }) => (
  <div
    style={{
      display: "flex",
      gap: 16,
      fontSize: 24,
      lineHeight: 1.35,
      color: C.muted,
      ...style,
    }}
  >
    <span
      style={{
        flex: "none",
        width: 8,
        height: 8,
        marginTop: 13,
        borderRadius: 8,
        background: C.lime,
      }}
    />
    <span>{children}</span>
  </div>
);

const Arrow: React.FC<{ x: number; y: number; w: number; p: number }> = ({
  x,
  y,
  w,
  p,
}) => (
  <svg
    width={w + 12}
    height={20}
    style={{ position: "absolute", left: x, top: y - 10, opacity: p }}
  >
    <path
      d={`M0 10H${w * p}`}
      stroke={C.lime}
      strokeOpacity={0.7}
      strokeWidth={2}
    />
    <path
      d={`M${w * p - 8} 3l8 7-8 7`}
      fill="none"
      stroke={C.lime}
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

// ---- Izquierda: el modelo de lenguaje --------------------------------------

const VIS_Y = 420;
const CANDIDATES = [
  { token: "lamps", p: 0.72 },
  { token: "options", p: 0.19 },
  { token: "chairs", p: 0.04 },
];

const Model: React.FC = () => {
  const T = useSceneTime();
  const show = useShow();
  const t = T();
  const bars = progress(T(1.3), 1.3, 2.1);
  const pick = progress(T(2.1), 2.1, 2.5);
  return (
    <>
      <Caps
        style={{ position: "absolute", left: LEFT, top: 280, ...show(0.2) }}
      >
        Modelo de lenguaje · LLM
      </Caps>
      <Heading left={LEFT} style={show(0.25)}>
        Predice el texto <span style={{ color: C.lime }}>que sigue.</span>
      </Heading>

      {/* Contexto: lo que recibe el modelo. */}
      <div
        style={{
          position: "absolute",
          left: LEFT,
          top: VIS_Y,
          width: 280,
          height: 250,
          boxSizing: "border-box",
          padding: 22,
          borderRadius: 24,
          background: C.panel,
          border: `1px solid ${C.line}`,
          display: "flex",
          flexDirection: "column",
          gap: 14,
          ...show(0.5),
        }}
      >
        <Caps color={C.muted} style={{ fontSize: 16 }}>
          Contexto
        </Caps>
        <div
          style={{
            alignSelf: "flex-start",
            padding: "8px 14px",
            borderRadius: "16px 16px 16px 4px",
            background: C.bg,
            border: `1px solid ${C.line}`,
            fontSize: 20,
          }}
        >
          lamps under $100?
        </div>
        <div style={{ fontSize: 18, color: C.muted }}>
          + productos del catálogo
        </div>
        <div style={{ fontSize: 22, color: C.ink }}>
          Here are three
          <span style={{ color: C.lime, opacity: t % 1 < 0.6 ? 1 : 0 }}>
            {" "}
            ▍
          </span>
        </div>
      </div>
      <Arrow
        x={LEFT + 292}
        y={VIS_Y + 125}
        w={46}
        p={progress(T(0.8), 0.8, 1.1)}
      />

      {/* El modelo, sin abrirlo. */}
      <div
        style={{
          position: "absolute",
          left: LEFT + 360,
          top: VIS_Y + 55,
          width: 140,
          ...show(0.9),
        }}
      >
        <div
          style={{
            width: 140,
            height: 140,
            borderRadius: 34,
            background: C.lime,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            boxShadow: "0 0 60px #d4f77d30",
          }}
        >
          <div style={{ rotate: `${t * 14}deg`, display: "flex" }}>
            <Glyph kind="model" size={84} color={C.bg} />
          </div>
        </div>
        <div
          style={{
            marginTop: 16,
            textAlign: "center",
            fontSize: 17,
            color: C.muted,
          }}
        >
          miles de millones
          <br />
          de parámetros
        </div>
      </div>
      <Arrow
        x={LEFT + 512}
        y={VIS_Y + 125}
        w={34}
        p={progress(T(1.2), 1.2, 1.5)}
      />

      {/* Próximo token: probabilidades. */}
      <div
        style={{
          position: "absolute",
          left: LEFT + 566,
          top: VIS_Y + 22,
          width: LEFT_W - 566,
          ...show(1.2),
        }}
      >
        <Caps color={C.muted} style={{ fontSize: 16 }}>
          Próximo token
        </Caps>
        {CANDIDATES.map((c, i) => {
          const best = i === 0;
          return (
            <div key={c.token} style={{ marginTop: 20 }}>
              <div
                style={{
                  display: "flex",
                  justifyContent: "space-between",
                  fontFamily: mono,
                  fontSize: 20,
                  color: best
                    ? interpolate(pick, [0, 1], [0, 1]) > 0.5
                      ? C.lime
                      : C.ink
                    : C.muted,
                }}
              >
                <span>{c.token}</span>
                <span>{Math.round(c.p * 100 * bars)}%</span>
              </div>
              <div
                style={{
                  marginTop: 8,
                  height: 12,
                  borderRadius: 6,
                  background: C.panel,
                  border: `1px solid ${C.line}`,
                  overflow: "hidden",
                }}
              >
                <div
                  style={{
                    width: `${c.p * 100 * bars}%`,
                    height: "100%",
                    background: best ? C.lime : C.muted,
                    opacity: best ? 0.6 + 0.4 * pick : 0.6,
                    boxShadow: best
                      ? `0 0 ${20 * pick}px #d4f77d80`
                      : undefined,
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>

      <div
        style={{
          position: "absolute",
          left: LEFT,
          top: 730,
          width: LEFT_W,
          display: "flex",
          flexDirection: "column",
          gap: 16,
        }}
      >
        <Bullet style={show(2.3)}>
          Aprendió el lenguaje leyendo muchísimo texto.
        </Bullet>
        <Bullet style={show(2.45)}>
          Genera token por token (pedazos de palabra) hasta armar la respuesta.
        </Bullet>
        <Bullet style={show(2.6)}>
          <span>
            No conoce nuestro catálogo:{" "}
            <span style={{ color: C.ink }}>se lo damos en el contexto.</span>
          </span>
        </Bullet>
      </div>
    </>
  );
};

// ---- Derecha: la base vectorial ---------------------------------------------

const PIPE_Y = 412;
const SCATTER = { x: 1380, y: 400, w: 444, h: 250 };
type Pt = [number, number];
const CLUSTERS: { label: string; at: Pt; points: Pt[] }[] = [
  {
    label: "lámparas",
    at: [110, 32],
    points: [
      [80, 73],
      [110, 95],
      [135, 65],
      [100, 118],
      [145, 100],
    ],
  },
  {
    label: "sillones",
    at: [322, 50],
    points: [
      [295, 95],
      [330, 80],
      [352, 115],
      [315, 132],
      [348, 150],
    ],
  },
  {
    label: "alfombras",
    at: [170, 236],
    points: [
      [135, 195],
      [165, 182],
      [195, 202],
      [175, 216],
    ],
  },
];
// La lámpara nueva cae en su grupo; la consulta queda entre lámparas y
// sillones, y sus vecinos más cercanos son de los dos grupos.
const NEW_POINT: Pt = [124, 140];
const QUERY: Pt = [235, 104];
const NEIGHBORS: Pt[] = [
  [295, 95],
  [315, 132],
  [145, 100],
];
const abs = ([x, y]: Pt): Pt => [SCATTER.x + x, SCATTER.y + y];

const VectorDb: React.FC = () => {
  const T = useSceneTime();
  const show = useShow();
  const t = T();
  const fly = progress(T(1.8), 1.8, 2.5);
  const query = progress(T(2.5), 2.5, 2.9);
  const from: Pt = [1330, PIPE_Y + 168];
  const to = abs(NEW_POINT);
  const flyX = interpolate(fly, [0, 1], [from[0], to[0]]);
  const flyY = interpolate(fly, [0, 1], [from[1], to[1]]);
  const q = abs(QUERY);
  const pulse = 1 + 0.25 * Math.abs(Math.sin(t * 2.4));
  return (
    <>
      <Caps
        style={{ position: "absolute", left: RIGHT, top: 280, ...show(0.4) }}
      >
        Base de datos vectorial · Qdrant
      </Caps>
      <Heading left={RIGHT} style={show(0.45)}>
        Busca por <span style={{ color: C.lime }}>cercanía.</span>
      </Heading>

      {/* Del texto del producto a un vector. */}
      <div
        style={{
          position: "absolute",
          left: RIGHT,
          top: PIPE_Y,
          width: 330,
          ...show(0.8),
        }}
      >
        <div
          style={{
            padding: "12px 16px",
            borderRadius: 14,
            background: C.panel,
            border: `1px solid ${C.line}`,
            fontSize: 20,
          }}
        >
          Art Deco Brass Table Lamp
        </div>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            gap: 12,
            margin: "14px 0 14px 18px",
            fontSize: 17,
            color: C.muted,
            opacity: progress(T(1), 1, 1.4),
          }}
        >
          <span style={{ color: C.lime, fontSize: 22 }}>↓</span>
          modelo de embeddings
        </div>
        <div
          style={{
            padding: "12px 16px",
            borderRadius: 14,
            background: C.bg,
            border: `1px solid ${C.lime}`,
            fontFamily: mono,
            fontSize: 18,
            color: C.lime,
            opacity: progress(T(1.1), 1.1, 1.5),
          }}
        >
          [0.12, −0.83, 0.41, …]
        </div>
        <div
          style={{
            marginTop: 10,
            marginLeft: 18,
            fontSize: 17,
            color: C.muted,
            opacity: progress(T(1.2), 1.2, 1.6),
          }}
        >
          768 números = su significado
        </div>
      </div>

      {/* El espacio: cada producto es un punto. */}
      <div
        style={{
          position: "absolute",
          left: SCATTER.x,
          top: SCATTER.y,
          width: SCATTER.w,
          height: SCATTER.h,
          boxSizing: "border-box",
          borderRadius: 24,
          background: C.panel,
          border: `1px solid ${C.line}`,
          ...show(1.2),
        }}
      >
        {CLUSTERS.map((c) => (
          <div
            key={c.label}
            style={{
              position: "absolute",
              left: c.at[0] - 60,
              top: c.at[1] - 11,
              width: 120,
              textAlign: "center",
              fontSize: 16,
              color: C.muted,
            }}
          >
            {c.label}
          </div>
        ))}
      </div>
      <svg
        width={1920}
        height={1080}
        style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
      >
        <g opacity={progress(T(1.3), 1.3, 1.8)}>
          {CLUSTERS.flatMap((c) => c.points).map((p) => {
            const [x, y] = abs(p);
            return (
              <circle key={`${x}-${y}`} cx={x} cy={y} r={7} fill={C.muted} />
            );
          })}
        </g>
        {/* La lámpara nueva viaja desde su vector hasta su lugar. */}
        {fly > 0 && (
          <g>
            <circle cx={flyX} cy={flyY} r={16} fill={C.lime} opacity={0.12} />
            <circle cx={flyX} cy={flyY} r={8} fill={C.lime} />
          </g>
        )}
        {/* La consulta y sus vecinos más cercanos. */}
        <g opacity={query}>
          {NEIGHBORS.map((n, i) => {
            const [x, y] = abs(n);
            const p = progress(
              T(2.7 + i * 0.12),
              2.7 + i * 0.12,
              3.1 + i * 0.12,
            );
            return (
              <g key={`${x}-${y}`}>
                <line
                  x1={q[0]}
                  y1={q[1]}
                  x2={q[0] + (x - q[0]) * p}
                  y2={q[1] + (y - q[1]) * p}
                  stroke={C.lilac}
                  strokeWidth={2}
                  strokeDasharray="5 5"
                />
                <circle
                  cx={x}
                  cy={y}
                  r={11}
                  fill="none"
                  stroke={C.lilac}
                  strokeWidth={2}
                  opacity={p}
                />
              </g>
            );
          })}
          <circle
            cx={q[0]}
            cy={q[1]}
            r={14 * pulse}
            fill={C.lilac}
            opacity={0.15}
          />
          <circle cx={q[0]} cy={q[1]} r={8} fill={C.lilac} />
        </g>
      </svg>
      <div
        style={{
          position: "absolute",
          left: q[0] - 120,
          top: q[1] - 46,
          width: 240,
          textAlign: "center",
          fontSize: 17,
          color: C.lilac,
          opacity: query,
        }}
      >
        “cozy to read”
      </div>
    </>
  );
};

// ---- Comparación: por qué no otra base -------------------------------------

const OPTIONS = [
  {
    kind: "Relacional",
    example: "MySQL",
    reason: "Compara valores exactos: “cozy” no aparece en ningún nombre.",
    ok: false,
  },
  {
    kind: "Clave-valor",
    example: "DynamoDB",
    reason: "Encuentra por clave; para el más parecido, hay que recorrer todo.",
    ok: false,
  },
  {
    kind: "Vectorial",
    example: "Qdrant",
    reason: "Índice de vecinos cercanos, con filtros por tag y precio.",
    ok: true,
  },
];
const CMP_Y = 684;

const WhyVector: React.FC = () => {
  const T = useSceneTime();
  const show = useShow();
  const start = CONCEPTS_STOP + 0.1;
  return (
    <>
      <Caps
        color={C.muted}
        style={{
          position: "absolute",
          left: RIGHT,
          top: CMP_Y,
          ...show(start),
        }}
      >
        ¿Por qué no otra base para el catálogo?
      </Caps>
      {OPTIONS.map((o, i) => {
        const at = start + 0.3 + i * 0.4;
        const mark = progress(T(at + 0.3), at + 0.3, at + 0.6);
        return (
          <div
            key={o.kind}
            style={{
              position: "absolute",
              left: RIGHT,
              top: CMP_Y + 42 + i * 74,
              width: RIGHT_W,
              height: 66,
              boxSizing: "border-box",
              padding: "0 20px",
              borderRadius: 18,
              background: o.ok ? "#d4f77d10" : C.panel,
              border: `1px solid ${o.ok ? C.lime : C.line}`,
              display: "flex",
              alignItems: "center",
              gap: 18,
              ...show(at),
            }}
          >
            <span
              style={{
                flex: "none",
                width: 36,
                height: 36,
                borderRadius: "50%",
                background: o.ok ? C.lime : "transparent",
                border: o.ok ? "none" : `2px solid ${C.muted}`,
                color: C.muted,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontSize: 18,
                scale: interpolate(mark, [0, 0.7, 1], [0, 1.15, 1], clamp),
              }}
            >
              {o.ok ? <Check size={26} /> : "✕"}
            </span>
            <div style={{ flex: "none", width: 190 }}>
              <div style={{ fontSize: 23, color: o.ok ? C.lime : C.ink }}>
                {o.kind}
              </div>
              <div style={{ fontSize: 15, color: C.muted, marginTop: 2 }}>
                {o.example}
              </div>
            </div>
            <div
              style={{
                fontSize: 19,
                lineHeight: 1.3,
                color: o.ok ? C.ink : C.muted,
              }}
            >
              {o.reason}
            </div>
          </div>
        );
      })}
    </>
  );
};

export const Concepts: React.FC = () => {
  const show = useShow();
  const T = useSceneTime();
  return (
    <Stage label="La solución · Modelo y base vectorial">
      <div
        style={{
          position: "absolute",
          left: 96,
          top: 150,
          fontSize: 72,
          lineHeight: 1.04,
          fontWeight: 500,
          letterSpacing: -3.5,
          ...show(0),
        }}
      >
        Las dos piezas <span style={{ color: C.lime }}>nuevas.</span>
      </div>
      <div
        style={{
          position: "absolute",
          left: DIVIDER,
          top: 285,
          width: 1,
          height: 665,
          background: C.line,
          opacity: progress(T(0.3), 0.3, 0.8),
        }}
      />
      <Model />
      <VectorDb />
      <WhyVector />
    </Stage>
  );
};
