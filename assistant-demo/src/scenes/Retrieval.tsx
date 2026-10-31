import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, clamp } from "../design";
import { Core, Rail, Tile, VectorBars, progress } from "../motion";
// Layout: a la izquierda, la consulta pasa por Gemini (columna centrada en
// GEMINI_X); en el medio, el mapa de Qdrant (de MAP_X a MAP_X + MAP_W, con la
// consulta en TARGET); a la derecha, las fotos del top 5 desde PHOTOS_X.
const GEMINI_X = 290;
const MAP_X = 600;
const MAP_W = 760;
const TARGET = { x: 1100, y: 585 };
const PHOTOS_X = 1560;

// Conceptual projection. The animation does not claim measured vector positions.
export const searchPoints = Array.from({ length: 80 }, (_, i) => ({
  x:
    i < 5
      ? TARGET.x + Math.cos(i * 2.399) * 105
      : MAP_X + (((i * 157) % 900) * MAP_W) / 900,
  y: i < 5 ? TARGET.y + Math.sin(i * 2.399) * 105 : 365 + ((i * 113) % 480),
}));
export const Retrieval = () => {
  const T = useSceneTime();
  const t = T();
  const organize = progress(T(0.2), 0.2, 2.2);
  const select = progress(T(4.1), 4.1, 5.4);
  const target = TARGET;
  return (
    <Stage step={4} label="Qdrant · Similitud">
      <Head>Encontrar.</Head>
      <Enter
        // Aparece cuando el punto de la consulta llega al mapa (1.5 → 3 s).
        at={2.8}
        style={{
          position: "absolute",
          // Centrado con el mapa (x de la consulta).
          left: TARGET.x - 400,
          width: 800,
          textAlign: "center",
          whiteSpace: "nowrap",
          top: 178,
          fontSize: 94,
          letterSpacing: -5,
          color: C.muted,
        }}
      >
        80 {/* Subida para quedar a media altura de los números. */}
        <span
          style={{
            fontSize: 50,
            position: "relative",
            top: -17,
            margin: "0 12px",
          }}
        >
          →
        </span>{" "}
        <span style={{ color: C.lime }}>Top 5</span>
      </Enter>
      <svg
        width={1920}
        height={1080}
        style={{ position: "absolute", inset: 0 }}
      >
        {[0, 1, 2].map((i) => {
          const p = (Math.max(0, T(2.4) - 2.4) * 0.44 + i / 3) % 1;
          return (
            <ellipse
              key={i}
              cx={target.x}
              cy={target.y}
              rx={90 + p * 360}
              ry={70 + p * 260}
              fill="none"
              stroke={C.lilac}
              strokeWidth={2}
              opacity={T(2.4) < 2.4 ? 0 : (1 - p) * 0.35 * (1 - select * 0.6)}
            />
          );
        })}
        <g
          style={{
            transformOrigin: `${target.x}px ${target.y}px`,
            rotate: `${t * 35}deg`,
            opacity: progress(T(2.1), 2.1, 3) * (1 - select * 0.9),
          }}
        >
          <path
            d={`M${target.x} ${target.y}l300 0a300 300 0 0 0 -88 -212Z`}
            fill="#c9b7ef09"
          />
          <path
            d={`M${target.x} ${target.y}h335`}
            stroke={C.lilac}
            strokeWidth={1}
            strokeOpacity={0.4}
          />
        </g>
        {searchPoints.map((p, i) => {
          const x = interpolate(
            organize,
            [0, 1],
            [MAP_X + 10 + (i % 10) * 80, p.x],
            clamp,
          );
          const y = interpolate(
            organize,
            [0, 1],
            [360 + Math.floor(i / 10) * 67, p.y],
            clamp,
          );
          return (
            <g
              key={i}
              opacity={
                progress(T(i * 0.007), i * 0.007, 0.8 + i * 0.007) *
                (i < 5 ? 1 : 1 - select * 0.88)
              }
            >
              <circle
                cx={x + Math.sin(t * 0.5 + i) * 3}
                cy={y + Math.cos(t * 0.4 + i) * 3}
                r={i < 5 ? 6 + select * 8 : 5}
                fill={i < 5 && select > 0.3 ? C.lime : C.muted}
              />
              {i < 5 && (
                <circle
                  cx={x}
                  cy={y}
                  r={18 + select * 9}
                  fill="none"
                  stroke={C.lime}
                  strokeWidth={1}
                  opacity={select}
                />
              )}
            </g>
          );
        })}
        <circle
          cx={interpolate(
            progress(T(1.5), 1.5, 3),
            [0, 1],
            [GEMINI_X + 120, target.x],
            clamp,
          )}
          cy={interpolate(
            progress(T(1.5), 1.5, 3),
            [0, 1],
            [target.y, target.y],
            clamp,
          )}
          r={13}
          fill={C.lilac}
          opacity={progress(T(1.3), 1.3, 1.8)}
        />
        {searchPoints.slice(0, 5).map((p, i) => {
          const q = progress(T(5 + i * 0.3), 5 + i * 0.3, 6.5 + i * 0.3);
          return (
            <path
              key={i}
              d={`M${p.x} ${p.y}Q${(p.x + PHOTOS_X) / 2} ${p.y} ${PHOTOS_X} ${360 + i * 117}`}
              fill="none"
              stroke={C.lime}
              strokeWidth={2}
              strokeOpacity={0.45}
              pathLength={1}
              strokeDasharray={1}
              strokeDashoffset={1 - q}
            />
          );
        })}
      </svg>
      {/* Columna de Gemini: la consulta reescrita se convierte en vector. */}
      <Enter
        at={0.1}
        style={{
          position: "absolute",
          left: GEMINI_X - 145,
          width: 290,
          top: 365,
          boxSizing: "border-box",
          padding: "18px 0",
          textAlign: "center",
          background: C.panel,
          border: `1px solid ${C.lime}`,
          borderRadius: 22,
          fontSize: 38,
          letterSpacing: -1.5,
          color: C.lime,
        }}
      >
        desk lamp
      </Enter>
      <Rail
        x1={GEMINI_X}
        y1={455}
        x2={GEMINI_X}
        y2={505}
        at={0.3}
        until={1.4}
      />
      <Enter at={0.3}>
        <Core x={GEMINI_X} y={585} label="Gemini" size={140} />
      </Enter>
      <Enter
        at={0.9}
        style={{
          position: "absolute",
          left: GEMINI_X - 145,
          width: 290,
          top: 745,
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 14,
        }}
      >
        <VectorBars width={170} height={54} color={C.lilac} />
        <span style={{ fontSize: 23, color: C.muted }}>
          Embedding · 768 dim
        </span>
      </Enter>
      <Enter
        at={3.1}
        style={{
          position: "absolute",
          // Centrado con el punto de la consulta.
          left: target.x - 150,
          width: 300,
          textAlign: "center",
          top: 770,
          fontSize: 24,
          color: C.lilac,
        }}
      >
        ● Consulta
      </Enter>
      {searchPoints.slice(0, 5).map((p, i) => {
        const q = progress(T(5 + i * 0.3), 5 + i * 0.3, 6.5 + i * 0.3);
        const destY = 360 + i * 117;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: interpolate(q, [0, 1], [p.x - 49, PHOTOS_X], clamp),
              top: interpolate(q, [0, 1], [p.y - 49, destY - 49], clamp),
              opacity: progress(T(5 + i * 0.3), 5 + i * 0.3, 5.4 + i * 0.3),
              scale: 0.25 + q * 0.75,
            }}
          >
            <Tile index={i} size={98} selected />
            <span
              style={{
                position: "absolute",
                left: 130,
                top: 25,
                fontSize: 36,
                color: C.lime,
                opacity: q,
              }}
            >
              0{i + 1}
            </span>
          </div>
        );
      })}
    </Stage>
  );
};
