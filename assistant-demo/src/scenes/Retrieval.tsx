import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, Tag, clamp } from "../design";
import { Tile, VectorBars, progress } from "../motion";
// Conceptual projection. The animation does not claim measured vector positions.
export const searchPoints = Array.from({ length: 80 }, (_, i) => ({
  x: i < 5 ? 750 + Math.cos(i * 2.399) * 105 : 160 + ((i * 157) % 900),
  y: i < 5 ? 585 + Math.sin(i * 2.399) * 105 : 365 + ((i * 113) % 480),
}));
export const Retrieval = () => {
  const T = useSceneTime();
  const t = T();
  const organize = progress(T(0.2), 0.2, 2.2);
  const select = progress(T(4.1), 4.1, 5.4);
  const target = { x: 750, y: 585 };
  return (
    <Stage step={4} label="Qdrant · Similitud">
      <Head>Encontrar.</Head>
      <Enter
        at={0.2}
        style={{
          position: "absolute",
          left: 1250,
          top: 178,
          fontSize: 94,
          letterSpacing: -5,
          color: C.muted,
        }}
      >
        80 <span style={{ fontSize: 50 }}>→</span>{" "}
        <span style={{ color: C.lime }}>5</span>
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
              rx={90 + p * 460}
              ry={70 + p * 300}
              fill="none"
              stroke={C.lilac}
              strokeWidth={2}
              opacity={T(2.4) < 2.4 ? 0 : (1 - p) * 0.35 * (1 - select * 0.6)}
            />
          );
        })}
        <g
          style={{
            transformOrigin: "750px 585px",
            rotate: `${t * 35}deg`,
            opacity: progress(T(2.1), 2.1, 3) * (1 - select * 0.9),
          }}
        >
          <path d="M750 585L1050 585A300 300 0 0 0 962 373Z" fill="#c9b7ef09" />
          <path
            d="M750 585H1085"
            stroke={C.lilac}
            strokeWidth={1}
            strokeOpacity={0.4}
          />
        </g>
        {searchPoints.map((p, i) => {
          const x = interpolate(
            organize,
            [0, 1],
            [170 + (i % 10) * 92, p.x],
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
          cx={interpolate(progress(T(1.5), 1.5, 3), [0, 1], [270, 750], clamp)}
          cy={interpolate(progress(T(1.5), 1.5, 3), [0, 1], [300, 585], clamp)}
          r={13}
          fill={C.lilac}
          opacity={progress(T(1.3), 1.3, 1.8)}
        />
        {searchPoints.slice(0, 5).map((p, i) => {
          const q = progress(T(5 + i * 0.3), 5 + i * 0.3, 6.5 + i * 0.3);
          return (
            <path
              key={i}
              d={`M${p.x} ${p.y}Q1080 ${p.y} 1320 ${330 + i * 117}`}
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
      <div
        style={{
          position: "absolute",
          left: 96,
          top: 285,
          display: "flex",
          alignItems: "center",
          gap: 22,
          opacity: 1 - progress(T(2.2), 2.2, 3.2),
        }}
      >
        <span style={{ fontSize: 30, color: C.lime }}>desk lamp</span>
        <VectorBars width={120} height={40} color={C.lilac} />
      </div>
      <Enter
        at={3.1}
        style={{
          position: "absolute",
          left: 615,
          top: 770,
          fontSize: 24,
          color: C.lilac,
        }}
      >
        ● Consulta
      </Enter>
      <Enter at={5.1} style={{ position: "absolute", left: 1320, top: 270 }}>
        <Tag>Top 5</Tag>
      </Enter>
      {searchPoints.slice(0, 5).map((p, i) => {
        const q = progress(T(5 + i * 0.3), 5 + i * 0.3, 6.5 + i * 0.3);
        const destY = 360 + i * 117;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: interpolate(q, [0, 1], [p.x - 49, 1320], clamp),
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
      <div
        style={{
          position: "absolute",
          left: 96,
          top: 911,
          color: C.muted,
          fontSize: 19,
        }}
      >
        Proyección y ranking ilustrativos
      </div>
    </Stage>
  );
};
