import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, Tag, clamp } from "../design";
import { Core, Rail, progress } from "../motion";
// `wait` son segundos extra con las palabras del pedido quietas antes de que
// viajen al modelo (el deck da tiempo a leerlas; el video, no). Todo lo que
// viene después se corre lo mismo.
export const Rewriting = ({ wait = 0 }: { wait?: number }) => {
  const T = useSceneTime();
  const t = T();
  const w = (s: number) => s + wait;
  const out = progress(T(w(4.8)), w(4.8), w(5.7));
  return (
    <Stage step={3} label="LLM 01 · Reescribir">
      <Head>Entender.</Head>
      <Rail x1={410} y1={530} x2={960} y2={530} at={w(1.3)} until={w(4.5)} />
      <Rail x1={960} y1={530} x2={1510} y2={530} at={w(4.3)} />
      <Enter at={0.2} style={{ position: "absolute", left: 96, top: 351 }}>
        <Tag color={C.muted}>Tu pedido</Tag>
      </Enter>
      {["Lámpara", "para mi", "escritorio"].map((word, i) => {
        const p = progress(
          T(w(1.8 + i * 0.24)),
          w(1.8 + i * 0.24),
          w(3.6 + i * 0.24),
        );
        return (
          <div
            key={word}
            style={{
              position: "absolute",
              left: 160 + p * 700,
              top: 425 + i * 95 - p * (i * 95 - 60),
              padding: "18px 32px",
              background: C.ink,
              color: C.bg,
              borderRadius: 18,
              fontSize: 40,
              opacity:
                (1 - progress(p, 0.65, 1)) *
                progress(T(0.3 + i * 0.14), 0.3 + i * 0.14, 0.8 + i * 0.14),
              scale: 1 - p * 0.65,
              rotate: `${p * 30}deg`,
            }}
          >
            {word}
          </div>
        );
      })}
      <Core x={960} y={530} label="Reescritura" number="01" />
      {[0, 1, 2].map((i) => {
        const a = t * 0.8 + i * 2.09;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: 950 + Math.cos(a) * 185,
              top: 520 + Math.sin(a) * 185,
              width: 18,
              height: 18,
              borderRadius: 7,
              background: C.lilac,
              opacity:
                progress(T(w(2)), w(2), w(3)) *
                (1 - progress(T(w(5.5)), w(5.5), w(6.5))),
            }}
          />
        );
      })}
      <Enter
        at={w(2.1)}
        style={{
          position: "absolute",
          // Centrado con el nodo (x = 960), debajo de su etiqueta.
          left: 960 - 200,
          width: 400,
          top: 740,
          textAlign: "center",
          color: C.lilac,
          fontSize: 34,
        }}
      >
        + memoria
      </Enter>
      <div
        style={{
          position: "absolute",
          left: 1280,
          top: 435,
          scale: interpolate(out, [0, 1], [0.7, 1], clamp),
          opacity: out,
          width: 500,
          height: 190,
          boxSizing: "border-box",
          padding: "30px 38px",
          background: C.panel,
          border: `1px solid ${C.lime}`,
          borderRadius: 28,
        }}
      >
        <Tag>Query</Tag>
        <div
          style={{
            fontSize: 68,
            letterSpacing: -3,
            marginTop: 14,
            color: C.lime,
          }}
        >
          desk lamp
        </div>
      </div>
    </Stage>
  );
};
