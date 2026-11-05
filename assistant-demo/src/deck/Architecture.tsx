import React from "react";
import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Glyph, Stage, clamp } from "../design";
import { progress } from "../motion";

// Apertura de "La solución": la arquitectura completa tal como corre en el
// cluster (dist/kubernetes.yaml). Lo que construimos va en lima: assistant,
// Qdrant y el ConfigMap con los modelos; ui y catalog, que modificamos, con
// borde punteado. Los modelos son externos (NVIDIA y Gemini, por HTTPS) y se
// eligen en el ConfigMap `assistant` (lila, ⇄). En este despliegue los
// servicios de la tienda guardan sus datos en memoria.
export const ARCHITECTURE_LENGTH = 4.4;

const mono = "Menlo, Consolas, monospace";

type Box = { x: number; y: number; w: number; h: number };
// Cajas por esquina superior izquierda, salvo los servicios (por centro).
const BROWSER = { cx: 166, cy: 612, w: 140, h: 72 };
const CLUSTER: Box = { x: 262, y: 286, w: 1218, h: 672 };
const UI = { cx: 372, cy: 612, w: 160, h: 84 };
const SVC = { cx: 640, w: 200, h: 78 };
const SERVICES = [
  { name: "catalog", lang: "Go", cy: 372, changed: true },
  { name: "carts", lang: "Java", cy: 468, changed: false },
  { name: "orders", lang: "Java", cy: 756, changed: false },
  { name: "checkout", lang: "Node", cy: 852, changed: false },
];
const ASSIST: Box = { x: 830, y: 336, w: 460, h: 440 };
const TOOLS = [
  {
    name: "searchProducts",
    target: "qdrant + catalog",
    cy: 490,
    to: 0,
    dy: -8,
  },
  { name: "getProductDetails", target: "catalog", cy: 542, to: 0, dy: 10 },
  { name: "addToCart", target: "carts", cy: 594, to: 1, dy: 0 },
];
const QDRANT: Box = { x: 830, y: 806, w: 230, h: 132 };
const CONFIG: Box = { x: 1080, y: 806, w: 380, h: 132 };
const PROV_X = 1520;
const PROV_W = 304;
const NVIDIA: Box = { x: PROV_X, y: 352, w: PROV_W, h: 190 };
const GEMINI: Box = { x: PROV_X, y: 580, w: PROV_W, h: 160 };
const NOTE_Y = 806;

const right = (b: Box) => b.x + b.w;
const midY = (b: Box) => b.y + b.h / 2;

// Aparición de un nodo: sube y se enciende entre `at` y `at + 0.5`.
const useShow = () => {
  const T = useSceneTime();
  return (at: number) => {
    const p = progress(T(at), at, at + 0.5);
    return { opacity: p, translate: `0 ${(1 - p) * 18}px` };
  };
};

// Conexión curva de (x1, y1) a (x2, y2) que se dibuja a partir de `at`. Con
// `flow`, unos puntos la recorren en el sentido del llamado.
const Edge: React.FC<{
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  at: number;
  color?: string;
  flow?: boolean;
  dashed?: boolean;
}> = ({ x1, y1, x2, y2, at, color = C.line, flow = false, dashed = false }) => {
  const T = useSceneTime();
  const t = T(at);
  const p = progress(t, at, at + 0.7);
  const xm = (x1 + x2) / 2;
  const d = `M${x1} ${y1}C${xm} ${y1} ${xm} ${y2} ${x2} ${y2}`;
  const point = (q: number) => {
    const u = 1 - q;
    const a = u * u * u;
    const b = 3 * u * u * q;
    const c = 3 * u * q * q;
    const e = q * q * q;
    return {
      x: a * x1 + b * xm + c * xm + e * x2,
      y: a * y1 + b * y1 + c * y2 + e * y2,
    };
  };
  const run = t - at - 0.7;
  return (
    <g>
      {dashed ? (
        <path
          d={d}
          fill="none"
          stroke={color}
          strokeWidth={2}
          strokeDasharray="7 8"
          opacity={p}
        />
      ) : (
        <path
          d={d}
          fill="none"
          stroke={color}
          strokeOpacity={color === C.line ? 1 : 0.6}
          strokeWidth={2}
          pathLength={1}
          strokeDasharray={1}
          strokeDashoffset={1 - p}
        />
      )}
      {flow &&
        run > 0 &&
        [0, 0.5].map((offset) => {
          const { x, y } = point((run * 0.55 + offset) % 1);
          return (
            <g key={offset}>
              <circle cx={x} cy={y} r={10} fill={color} opacity={0.1} />
              <circle cx={x} cy={y} r={4} fill={color} />
            </g>
          );
        })}
    </g>
  );
};

const Caps: React.FC<{
  children: React.ReactNode;
  color?: string;
  style?: React.CSSProperties;
}> = ({ children, color = C.muted, style }) => (
  <div
    style={{
      fontSize: 17,
      letterSpacing: 2,
      textTransform: "uppercase",
      color,
      ...style,
    }}
  >
    {children}
  </div>
);

const Cloud: React.FC<{ size?: number }> = ({ size = 44 }) => (
  <svg width={size} height={size * 0.69} viewBox="0 0 64 44" fill="none">
    <path
      d="M18 40h30a12 12 0 0 0 1-24A17 17 0 0 0 16 13 13.5 13.5 0 0 0 18 40Z"
      stroke={C.lime}
      strokeWidth={3}
      strokeLinejoin="round"
    />
  </svg>
);

// Chip lila de un modelo: lo que se intercambia desde el ConfigMap.
const Swap: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <div
    style={{
      display: "inline-flex",
      alignItems: "center",
      gap: 8,
      padding: "5px 10px",
      borderRadius: 10,
      border: `1px solid ${C.lilac}`,
      background: "#c9b7ef14",
      color: C.lilac,
      fontFamily: mono,
      fontSize: 17,
      whiteSpace: "nowrap",
    }}
  >
    ⇄ {children}
  </div>
);

const Service: React.FC<{
  s: (typeof SERVICES)[number];
  style: React.CSSProperties;
}> = ({ s, style }) => (
  <div
    style={{
      position: "absolute",
      left: SVC.cx - SVC.w / 2,
      top: s.cy - SVC.h / 2,
      width: SVC.w,
      height: SVC.h,
      boxSizing: "border-box",
      borderRadius: 18,
      background: C.panel,
      border: s.changed ? `2px dashed ${C.lime}` : `1px solid ${C.line}`,
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      paddingLeft: 22,
      gap: 6,
      ...style,
    }}
  >
    <div style={{ fontFamily: mono, fontSize: 24, color: C.ink }}>{s.name}</div>
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        fontSize: 16,
        color: C.muted,
      }}
    >
      <Glyph kind="db" size={18} color={C.muted} />
      {s.lang} · en memoria
    </div>
  </div>
);

const Provider: React.FC<{
  box: Box;
  name: string;
  api: string;
  model: string;
  role: string;
  style: React.CSSProperties;
}> = ({ box, name, api, model, role, style }) => (
  <div
    style={{
      position: "absolute",
      left: box.x,
      top: box.y,
      width: box.w,
      height: box.h,
      boxSizing: "border-box",
      padding: "0 22px",
      borderRadius: 24,
      background: C.panel,
      border: `1px solid ${C.line}`,
      display: "flex",
      flexDirection: "column",
      justifyContent: "center",
      gap: 10,
      ...style,
    }}
  >
    <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
      <Cloud />
      <span style={{ fontSize: 28, letterSpacing: -0.5 }}>{name}</span>
    </div>
    {api && <div style={{ fontSize: 17, color: C.muted }}>{api}</div>}
    <div>
      <Swap>{model}</Swap>
    </div>
    <div style={{ fontSize: 18, color: C.ink }}>{role}</div>
  </div>
);

const LegendItem: React.FC<{
  mark: React.ReactNode;
  children: React.ReactNode;
}> = ({ mark, children }) => (
  <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
    {mark}
    {children}
  </div>
);

const swatch = (border: string, glow = false): React.ReactNode => (
  <span
    style={{
      width: 26,
      height: 18,
      borderRadius: 6,
      boxSizing: "border-box",
      background: C.panel,
      border,
      boxShadow: glow ? "0 0 12px #d4f77d55" : undefined,
    }}
  />
);

const ArchitectureMap: React.FC = () => {
  const T = useSceneTime();
  const show = useShow();
  const t = T();
  const assist = progress(T(1), 1, 1.7);
  const uiRight = UI.cx + UI.w / 2;
  const svcLeft = SVC.cx - SVC.w / 2;
  const svcRight = SVC.cx + SVC.w / 2;
  const assistCx = ASSIST.x + 115;
  const configCx = CONFIG.x + 190;
  return (
    <div style={{ position: "absolute", inset: 0 }}>
      {/* Título y leyenda. */}
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
        Toda la tienda, <span style={{ color: C.lime }}>y lo que sumamos.</span>
      </div>
      <div
        style={{
          position: "absolute",
          right: 96,
          top: 150,
          display: "grid",
          gridTemplateColumns: "auto auto",
          columnGap: 34,
          rowGap: 12,
          fontSize: 20,
          color: C.muted,
          ...show(3.6),
        }}
      >
        <LegendItem mark={swatch(`2px solid ${C.lime}`, true)}>
          <span style={{ color: C.ink }}>Nuevo</span>
        </LegendItem>
        <LegendItem mark={swatch(`2px dashed ${C.lime}`)}>
          Modificado
        </LegendItem>
        <LegendItem mark={swatch(`1px solid ${C.line}`)}>
          Sin cambios
        </LegendItem>
        <LegendItem
          mark={
            <span style={{ color: C.lilac, fontSize: 22, width: 26 }}>⇄</span>
          }
        >
          <span style={{ color: C.lilac }}>Intercambiable</span>
        </LegendItem>
      </div>

      {/* Límite del cluster y zona externa. */}
      <div
        style={{
          position: "absolute",
          left: CLUSTER.x,
          top: CLUSTER.y,
          width: CLUSTER.w,
          height: CLUSTER.h,
          boxSizing: "border-box",
          border: `1.5px dashed ${C.line}`,
          borderRadius: 28,
          opacity: progress(T(0.1), 0.1, 0.6),
        }}
      >
        <Caps style={{ position: "absolute", left: 24, top: 16 }}>
          Cluster kind · namespace the-store
        </Caps>
      </div>
      <Caps
        style={{
          position: "absolute",
          left: PROV_X,
          top: CLUSTER.y + 16,
          ...show(2.5),
        }}
      >
        Externo · internet
      </Caps>

      {/* Conexiones, debajo de los nodos. */}
      <svg
        width={1920}
        height={1080}
        style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
      >
        <Edge
          x1={BROWSER.cx + BROWSER.w / 2}
          y1={BROWSER.cy}
          x2={UI.cx - UI.w / 2}
          y2={UI.cy}
          at={0.25}
        />
        {SERVICES.map((s, i) => (
          <Edge
            key={s.name}
            x1={uiRight}
            y1={UI.cy}
            x2={svcLeft}
            y2={s.cy}
            at={0.45 + i * 0.1}
          />
        ))}
        <Edge
          x1={uiRight}
          y1={UI.cy}
          x2={ASSIST.x}
          y2={692}
          at={1}
          color={C.lime}
          flow
        />
        {TOOLS.map((tool, i) => (
          <Edge
            key={tool.name}
            x1={ASSIST.x}
            y1={tool.cy}
            x2={svcRight}
            y2={SERVICES[tool.to].cy + tool.dy}
            at={1.6 + i * 0.15}
            color={C.lime}
            flow
          />
        ))}
        <Edge
          x1={assistCx}
          y1={ASSIST.y + ASSIST.h}
          x2={assistCx}
          y2={QDRANT.y}
          at={2.2}
          color={C.lime}
        />
        <Edge
          x1={right(ASSIST)}
          y1={midY(NVIDIA)}
          x2={NVIDIA.x}
          y2={midY(NVIDIA)}
          at={2.5}
          color={C.lime}
          flow
        />
        <Edge
          x1={right(ASSIST)}
          y1={midY(GEMINI)}
          x2={GEMINI.x}
          y2={midY(GEMINI)}
          at={2.7}
          color={C.lime}
          flow
        />
        <Edge
          x1={configCx}
          y1={CONFIG.y}
          x2={configCx}
          y2={ASSIST.y + ASSIST.h}
          at={3.2}
          color={C.lilac}
          dashed
        />
        <Edge
          x1={right(CONFIG)}
          y1={midY(CONFIG)}
          x2={PROV_X}
          y2={midY(CONFIG)}
          at={3.3}
          color={C.lilac}
          dashed
        />
      </svg>
      {[midY(NVIDIA), midY(GEMINI)].map((y, i) => (
        <div
          key={y}
          style={{
            position: "absolute",
            left: right(ASSIST) + 30,
            top: y - 34,
            fontSize: 16,
            letterSpacing: 2,
            color: C.lime,
            ...show(2.6 + i * 0.2),
          }}
        >
          HTTPS
        </div>
      ))}

      {/* Navegador y ui. */}
      <div
        style={{
          position: "absolute",
          left: BROWSER.cx - BROWSER.w / 2,
          top: BROWSER.cy - BROWSER.h / 2,
          width: BROWSER.w,
          ...show(0.15),
        }}
      >
        <div
          style={{
            height: BROWSER.h,
            boxSizing: "border-box",
            borderRadius: BROWSER.h / 2,
            background: C.panel,
            border: `1px solid ${C.line}`,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            fontSize: 22,
          }}
        >
          navegador
        </div>
        <div
          style={{
            marginTop: 14,
            textAlign: "center",
            fontSize: 16,
            color: C.muted,
          }}
        >
          ingress · localhost
        </div>
      </div>
      <div
        style={{
          position: "absolute",
          left: UI.cx - UI.w / 2,
          top: UI.cy - UI.h / 2,
          width: UI.w,
          height: UI.h,
          boxSizing: "border-box",
          borderRadius: 20,
          background: C.panel,
          border: `2px dashed ${C.lime}`,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          paddingLeft: 22,
          gap: 6,
          ...show(0.35),
        }}
      >
        <div style={{ fontFamily: mono, fontSize: 26 }}>ui</div>
        <div style={{ fontSize: 16, color: C.muted }}>Java · chat</div>
      </div>

      {/* Servicios de la tienda. */}
      {SERVICES.map((s, i) => (
        <Service key={s.name} s={s} style={show(0.55 + i * 0.1)} />
      ))}

      {/* assistant: el servicio nuevo, con sus tools y su flujo. */}
      <div
        style={{
          position: "absolute",
          left: ASSIST.x,
          top: ASSIST.y,
          width: ASSIST.w,
          height: ASSIST.h,
          boxSizing: "border-box",
          padding: "22px 22px",
          borderRadius: 30,
          background: C.panel,
          border: `2px solid ${C.lime}`,
          boxShadow: `0 0 ${80 * assist}px #d4f77d26`,
          opacity: progress(T(1), 1, 1.4),
          scale: interpolate(assist, [0, 1], [0.93, 1], clamp),
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
          <div
            style={{
              width: 64,
              height: 64,
              borderRadius: 18,
              background: C.lime,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            <div style={{ rotate: `${t * 14}deg`, display: "flex" }}>
              <Glyph kind="model" size={44} color={C.bg} />
            </div>
          </div>
          <div>
            <div style={{ fontSize: 38, letterSpacing: -1 }}>assistant</div>
            <div style={{ fontSize: 17, color: C.muted, marginTop: 2 }}>
              Java 21 · Spring Boot · Spring AI
            </div>
          </div>
        </div>
        <Caps color={C.lime} style={{ marginTop: 26, ...show(1.5) }}>
          Tools del modelo
        </Caps>
        {TOOLS.map((tool, i) => (
          <div
            key={tool.name}
            style={{
              position: "absolute",
              left: 22,
              right: 22,
              top: tool.cy - ASSIST.y - 21,
              height: 42,
              boxSizing: "border-box",
              padding: "0 16px",
              borderRadius: 12,
              background: C.bg,
              border: `1px solid ${C.line}`,
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              ...show(1.55 + i * 0.15),
            }}
          >
            <span style={{ fontFamily: mono, fontSize: 19, color: C.lime }}>
              {tool.name}
            </span>
            <span style={{ fontSize: 16, color: C.muted }}>
              → {tool.target}
            </span>
          </div>
        ))}
        <div
          style={{
            position: "absolute",
            left: 22,
            right: 22,
            top: 290,
            borderTop: `1px solid ${C.line}`,
            ...show(1.9),
          }}
        />
        <div
          style={{
            position: "absolute",
            left: 22,
            right: 22,
            top: 306,
            ...show(1.95),
          }}
        >
          <Caps color={C.lime}>Cada mensaje</Caps>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 10,
              marginTop: 12,
              fontSize: 19,
            }}
          >
            {["reescribe", "busca", "responde"].map((step, i) => (
              <React.Fragment key={step}>
                {i > 0 && <span style={{ color: C.muted }}>›</span>}
                <span
                  style={{
                    padding: "6px 14px",
                    borderRadius: 20,
                    border: `1px solid ${C.line}`,
                    background: C.bg,
                  }}
                >
                  {step}
                </span>
              </React.Fragment>
            ))}
          </div>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              marginTop: 14,
              fontSize: 16,
              color: C.muted,
            }}
          >
            <Glyph kind="db" size={18} color={C.muted} />
            memoria por sesión · 10 turnos
          </div>
        </div>
      </div>

      {/* Qdrant: la base vectorial nueva. */}
      <div
        style={{
          position: "absolute",
          left: QDRANT.x,
          top: QDRANT.y,
          width: QDRANT.w,
          height: QDRANT.h,
          boxSizing: "border-box",
          padding: "0 20px",
          borderRadius: 24,
          background: C.panel,
          border: `2px solid ${C.lime}`,
          boxShadow: "0 0 40px #d4f77d1c",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          gap: 6,
          ...show(2.3),
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <Glyph kind="db" size={38} />
          <span style={{ fontSize: 28 }}>qdrant</span>
        </div>
        <div style={{ fontSize: 16, color: C.muted }}>products · 768 dim.</div>
        <div style={{ fontSize: 16, color: C.muted }}>volumen persistente</div>
      </div>

      {/* ConfigMap: donde se eligen los modelos. */}
      <div
        style={{
          position: "absolute",
          left: CONFIG.x,
          top: CONFIG.y,
          width: CONFIG.w,
          height: CONFIG.h,
          boxSizing: "border-box",
          padding: "0 20px",
          borderRadius: 24,
          background: C.panel,
          border: `2px solid ${C.lilac}`,
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          gap: 7,
          ...show(3.2),
        }}
      >
        <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
          <Caps color={C.lilac}>⇄ ConfigMap</Caps>
          <span style={{ fontFamily: mono, fontSize: 20 }}>assistant</span>
        </div>
        {[
          ["chat   ", "meta/muse-glimmer-30b"],
          ["rewrite", "meta/muse-glimmer-30b"],
          ["embed  ", "gemini-embedding-001"],
        ].map(([key, value]) => (
          <div
            key={key}
            style={{
              fontFamily: mono,
              fontSize: 17,
              whiteSpace: "pre",
              color: C.muted,
            }}
          >
            {key} <span style={{ color: C.lilac }}>{value}</span>
          </div>
        ))}
      </div>

      {/* Proveedores externos de los modelos. */}
      <Provider
        box={NVIDIA}
        name="NVIDIA"
        api="API compatible con OpenAI"
        model="meta/muse-glimmer-30b"
        role="chat · tools · reescritura"
        style={show(2.6)}
      />
      <Provider
        box={GEMINI}
        name="Google Gemini"
        api=""
        model="gemini-embedding-001"
        role="embeddings"
        style={show(2.8)}
      />
      <div
        style={{
          position: "absolute",
          left: PROV_X,
          top: NOTE_Y,
          width: PROV_W,
          height: CONFIG.h,
          display: "flex",
          alignItems: "center",
          fontSize: 22,
          lineHeight: 1.3,
          color: C.ink,
          ...show(3.4),
        }}
      >
        <span>
          <span style={{ color: C.lilac }}>Modelo o proveedor</span> se cambian
          en el ConfigMap, sin recompilar.
        </span>
      </div>
    </div>
  );
};

export const Architecture: React.FC = () => (
  <Stage label="La solución · Arquitectura">
    <ArchitectureMap />
  </Stage>
);
