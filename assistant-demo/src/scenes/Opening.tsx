import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import { C, Stage, Enter, Tag, Glyph, clamp } from "../design";
export const Opening = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <Stage step={1} label="De una pregunta a una recomendación">
      <Enter at={0.15} style={{ position: "absolute", left: 96, top: 235 }}>
        <Tag>Inteligencia conectada al catálogo</Tag>
        <div
          style={{
            fontSize: 153,
            lineHeight: 0.99,
            letterSpacing: -9,
            fontWeight: 500,
            marginTop: 38,
          }}
        >
          Encontrar.
          <br />
          <span style={{ color: C.lime }}>Sin buscar.</span>
        </div>
        <div
          style={{
            fontSize: 36,
            color: C.muted,
            marginTop: 40,
            lineHeight: 1.4,
          }}
        >
          Así funciona el asistente
          <br />
          de compras de The Store.
        </div>
      </Enter>
      <div
        style={{
          position: "absolute",
          left: 1150,
          top: 265,
          width: 600,
          height: 600,
        }}
      >
        {[230, 380, 560].map((n, i) => (
          <div
            key={n}
            style={{
              position: "absolute",
              left: (600 - n) / 2,
              top: (600 - n) / 2,
              width: n,
              height: n,
              border: `1px solid ${C.line}`,
              borderRadius: "50%",
              scale: interpolate(frame, [0, 2 * fps], [0.85, 1], clamp),
              opacity: interpolate(frame, [i * 10, i * 10 + 30], [0, 1], clamp),
            }}
          />
        ))}
        <div
          style={{
            position: "absolute",
            left: 225,
            top: 225,
            width: 150,
            height: 150,
            borderRadius: 42,
            background: C.lime,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            rotate: `${interpolate(frame, [0, 6 * fps], [-12, 8], clamp)}deg`,
          }}
        >
          <Glyph kind="model" color={C.bg} size={100} />
        </div>
        <Enter
          at={0.6}
          style={{
            position: "absolute",
            top: 0,
            left: 195,
            background: C.panel,
            border: `1px solid ${C.line}`,
            borderRadius: 22,
            padding: 25,
          }}
        >
          <Glyph kind="store" size={54} />
        </Enter>
        <Enter
          at={1}
          style={{
            position: "absolute",
            bottom: 45,
            right: 0,
            background: C.panel,
            border: `1px solid ${C.line}`,
            borderRadius: 22,
            padding: 25,
          }}
        >
          <Glyph kind="db" size={54} color={C.lilac} />
        </Enter>
        <Enter
          at={1.5}
          style={{
            position: "absolute",
            left: 0,
            top: 630,
            color: C.lime,
            fontSize: 22,
            letterSpacing: 2,
          }}
        >
          PREGUNTA → CONTEXTO → RESPUESTA
        </Enter>
      </div>
    </Stage>
  );
};
