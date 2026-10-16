import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import { C, Stage, Head, Enter, clamp } from "../design";
import { Core, Rail, Tile, VectorBars, progress, Check } from "../motion";
export const Indexing = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  const count = Math.round(interpolate(t, [3.4, 9], [0, 80], clamp));
  return (
    <Stage step={2} label="Arranque · Assistant">
      <Head>Catálogo → conocimiento.</Head>
      <Rail x1={250} y1={530} x2={960} y2={530} at={1} />
      <Rail x1={960} y1={530} x2={1660} y2={530} at={2.4} />
      <Enter at={0.2}>
        <Core x={250} y={530} kind="store" label="Catálogo" size={180} />
      </Enter>
      <Enter at={0.5}>
        <Core x={960} y={530} label="Gemini" size={180} />
      </Enter>
      <Enter at={0.8}>
        <Core x={1660} y={530} kind="db" label="Qdrant" size={180} />
      </Enter>
      {Array.from({ length: 12 }, (_, i) => {
        const start = 1 + i * 0.43;
        const p = interpolate(t, [start, start + 3.4], [0, 1], clamp);
        const x = 250 + p * 1410;
        const morph = progress(p, 0.38, 0.57);
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: x - 48,
              top: 482 + Math.sin(p * Math.PI) * Math.sin(i * 2) * 50,
              opacity: t < start || p >= 1 ? 0 : 1,
              scale: interpolate(p, [0, 0.1, 0.8, 1], [0.4, 1, 1, 0.3], clamp),
            }}
          >
            <div
              style={{
                opacity: 1 - morph,
                rotate: `${Math.sin(p * Math.PI * 2) * 12}deg`,
              }}
            >
              <Tile index={i} size={96} />
            </div>
            <div
              style={{ position: "absolute", left: 0, top: 12, opacity: morph }}
            >
              <VectorBars seed={i} width={96} height={65} />
            </div>
          </div>
        );
      })}
      <Enter
        at={2.8}
        style={{
          position: "absolute",
          left: 830,
          top: 790,
          color: C.lime,
          fontSize: 30,
        }}
      >
        768 dimensiones
      </Enter>
      <Enter
        at={3.5}
        style={{
          position: "absolute",
          left: 1470,
          top: 790,
          display: "flex",
          gap: 18,
          alignItems: "center",
          fontSize: 39,
        }}
      >
        <span>
          {count}
          <span style={{ color: C.muted }}> / 80</span>
        </span>
        {count === 80 && <Check color={C.lime} />}
      </Enter>
      <div
        style={{
          position: "absolute",
          left: 1455,
          top: 875,
          display: "flex",
          gap: 1,
        }}
      >
        {Array.from({ length: 80 }, (_, i) => (
          <div
            key={i}
            style={{
              width: 3,
              height: 14,
              background: i < count ? C.lime : C.line,
              borderRadius: 2,
            }}
          />
        ))}
      </div>
    </Stage>
  );
};
