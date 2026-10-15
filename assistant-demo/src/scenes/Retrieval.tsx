import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import { C, Stage, Head, Enter, Tag, Photo, products, clamp } from "../design";

// A deterministic conceptual projection, not measured embedding coordinates.
const points = Array.from({ length: 80 }, (_, i) => {
  const cluster = i < 5;
  const a = i * 2.399963;
  return {
    x: cluster ? 745 + Math.cos(a) * 75 : 160 + ((i * 157) % 890),
    y: cluster ? 600 + Math.sin(a) * 75 : 400 + ((i * 113) % 420),
  };
});
export const Retrieval = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  const searching = interpolate(t, [4.5, 7.5], [0, 1], clamp);
  return (
    <Stage step={4} label="Gemini → Qdrant · Recuperación semántica">
      <Head sub="La consulta también se transforma en un vector de 768 dimensiones.">
        Buscar por significado.
      </Head>
      <Enter
        at={0.6}
        style={{
          position: "absolute",
          left: 96,
          top: 345,
          display: "flex",
          gap: 20,
          alignItems: "center",
        }}
      >
        <div
          style={{
            padding: "14px 24px",
            borderRadius: 12,
            background: C.lime,
            color: C.bg,
            fontSize: 28,
          }}
        >
          desk lamp
        </div>
        <span style={{ fontSize: 30, color: C.muted }}>→</span>
        <span style={{ fontSize: 27, color: C.ink }}>Gemini</span>
        <span style={{ fontSize: 30, color: C.muted }}>→</span>
        <div
          style={{ fontFamily: "Menlo,monospace", fontSize: 24, color: C.lime }}
        >
          [ 0.12, −0.08, 0.31, … ]
        </div>
      </Enter>
      <svg
        style={{ position: "absolute", inset: 0, width: 1920, height: 1080 }}
      >
        <path
          d="M130 850H1110M130 850V435"
          stroke={C.line}
          fill="none"
          strokeWidth="1.5"
        />
        {[1, 2, 3].map((n) => (
          <ellipse
            key={n}
            cx={745}
            cy={600}
            rx={n * 86 * searching}
            ry={n * 66 * searching}
            fill="none"
            stroke={C.lime}
            strokeOpacity={0.22 / n}
            strokeWidth={1.5}
          />
        ))}
        {points.map((p, i) => (
          <g
            key={i}
            opacity={
              interpolate(t, [1 + i * 0.015, 2 + i * 0.015], [0, 1], clamp) *
              (i < 5 ? 1 : interpolate(t, [6, 8], [0.7, 0.15], clamp))
            }
          >
            <circle
              cx={p.x}
              cy={p.y}
              r={i < 5 ? interpolate(t, [6, 8], [6, 12], clamp) : 5}
              fill={i < 5 && t > 6 ? C.lime : C.muted}
            />
            {i < 5 && t > 7 && (
              <circle
                cx={p.x}
                cy={p.y}
                r={21}
                stroke={C.lime}
                strokeOpacity={0.35}
                fill="none"
              />
            )}
          </g>
        ))}
        <circle
          cx={interpolate(t, [3, 5], [370, 745], { ...clamp })}
          cy={interpolate(t, [3, 5], [470, 600], clamp)}
          r={11}
          fill={C.lilac}
          opacity={interpolate(t, [2.5, 3], [0, 1], clamp)}
        />
        {points.slice(0, 5).map((p, i) => {
          const q = interpolate(t, [8 + i * 0.5, 9 + i * 0.5], [0, 1], clamp);
          return (
            <path
              key={i}
              d={`M${p.x} ${p.y} C1100 ${p.y} 1090 ${447 + i * 85} 1230 ${447 + i * 85}`}
              fill="none"
              stroke={C.lime}
              strokeWidth={1.5}
              pathLength={1}
              strokeDasharray={1}
              strokeDashoffset={1 - q}
              opacity={q * 0.6}
            />
          );
        })}
      </svg>
      <Enter at={5} style={{ position: "absolute", left: 550, top: 742 }}>
        <Tag color={C.lilac}>Vector de la consulta</Tag>
      </Enter>
      <Enter at={7.5} style={{ position: "absolute", left: 1230, top: 340 }}>
        <Tag>Top 5 · similitud coseno</Tag>
      </Enter>
      {products.map((p, i) => (
        <Enter
          key={p.id}
          at={8 + i * 0.5}
          style={{
            position: "absolute",
            left: 1230,
            top: 405 + i * 85,
            width: 590,
            height: 75,
            background: C.panel,
            border: `1px solid ${C.line}`,
            borderRadius: 16,
            display: "flex",
            alignItems: "center",
            gap: 18,
            padding: "7px 18px",
            boxSizing: "border-box",
          }}
        >
          <span style={{ color: C.lime, fontSize: 21 }}>0{i + 1}</span>
          <Photo index={i} size={57} />
          <div style={{ fontSize: 27 }}>{p.name}</div>
        </Enter>
      ))}
      <Enter
        at={11}
        style={{
          position: "absolute",
          left: 1230,
          top: 864,
          fontSize: 23,
          color: C.muted,
        }}
      >
        Hasta 5 resultados · umbral configurable
      </Enter>
      <div
        style={{
          position: "absolute",
          left: 151,
          top: 877,
          fontSize: 21,
          color: C.muted,
        }}
      >
        Qdrant · colección products · proyección 2D y ranking ilustrativos
      </div>
    </Stage>
  );
};
