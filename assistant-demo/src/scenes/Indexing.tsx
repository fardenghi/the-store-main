import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import {
  C,
  Stage,
  Head,
  Enter,
  Tag,
  Glyph,
  FlowLine,
  Photo,
  Note,
  clamp,
} from "../design";
export const Indexing = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  return (
    <Stage step={2} label="Inicio del sistema · Indexación">
      <Head sub="Primer arranque · el catálogo se convierte en una base de conocimiento.">
        Antes de la primera pregunta.
      </Head>
      <FlowLine x1={436} y1={513} x2={552} y2={513} start={1.5} end={3} />
      <FlowLine x1={892} y1={513} x2={1008} y2={513} start={3.5} end={5.5} />
      <FlowLine x1={1348} y1={513} x2={1464} y2={513} start={6} end={8} />
      {(
        [
          {
            x: 96,
            kind: "store",
            name: "Catálogo",
            desc: "80 productos",
            at: 0.4,
          },
          {
            x: 552,
            kind: "vector",
            name: "Assistant",
            desc: "Nombre + tags + descripción",
            at: 1.1,
          },
          {
            x: 1008,
            kind: "model",
            name: "Gemini",
            desc: "gemini-embedding-001",
            at: 1.8,
          },
          {
            x: 1464,
            kind: "db",
            name: "Qdrant",
            desc: "Colección products",
            at: 2.5,
          },
        ] as const
      ).map((n, i) => (
        <Enter
          key={n.name}
          at={n.at}
          style={{
            position: "absolute",
            left: n.x,
            top: 390,
            width: 340,
            height: 256,
            padding: 32,
            boxSizing: "border-box",
            background: C.panel,
            border: `1px solid ${i === 3 && t > 8 ? C.lime : C.line}`,
            borderRadius: 25,
          }}
        >
          <Glyph kind={n.kind} />
          <div style={{ fontSize: 38, marginTop: 20, fontWeight: 500 }}>
            {n.name}
          </div>
          <div style={{ fontSize: 22, color: C.muted, marginTop: 12 }}>
            {n.desc}
          </div>
        </Enter>
      ))}
      <Enter
        at={3.4}
        style={{
          position: "absolute",
          left: 96,
          top: 701,
          display: "flex",
          gap: 18,
        }}
      >
        <Photo index={0} size={105} />
        <div
          style={{
            width: 495,
            padding: "16px 22px",
            background: C.panel,
            borderRadius: 16,
          }}
        >
          <div style={{ fontSize: 24 }}>Curved Brass and Walnut Desk Lamp</div>
          <div style={{ fontSize: 21, color: C.muted, marginTop: 9 }}>
            lighting · office · mid-century · metal
          </div>
        </div>
      </Enter>
      <Enter at={5.4} style={{ position: "absolute", left: 810, top: 710 }}>
        <div
          style={{ display: "flex", gap: 5, alignItems: "center", height: 60 }}
        >
          {Array.from({ length: 40 }, (_, i) => (
            <div
              key={i}
              style={{
                width: 7,
                height: 12 + Math.abs(Math.sin(i * 2.3)) * 45,
                background: C.lime,
                opacity: interpolate(
                  f,
                  [5.4 * fps + i * 0.7, 5.4 * fps + i * 0.7 + 12],
                  [0, 1],
                  clamp,
                ),
              }}
            />
          ))}
        </div>
        <div style={{ fontSize: 21, color: C.muted, marginTop: 12 }}>
          RETRIEVAL_DOCUMENT · 768 dimensiones
        </div>
      </Enter>
      <Enter at={8} style={{ position: "absolute", left: 1464, top: 710 }}>
        <Tag>
          {Math.round(interpolate(f, [8 * fps, 10 * fps], [0, 80], clamp))} / 80
          indexados
        </Tag>
        <div style={{ fontSize: 23, color: C.muted, marginTop: 20 }}>
          Vector + ID + datos del producto
        </div>
      </Enter>
      <Note at={10}>
        Listo para buscar. En los reinicios, solo se vuelven a embeber productos
        nuevos o modificados.
      </Note>
    </Stage>
  );
};
