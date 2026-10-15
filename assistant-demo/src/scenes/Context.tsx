import { interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import {
  C,
  Stage,
  Head,
  Enter,
  Tag,
  Glyph,
  Photo,
  products,
  clamp,
  Note,
} from "../design";
export const Context = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  return (
    <Stage step={5} label="RAG · Segundo llamado al modelo">
      <Head sub="El Assistant arma el prompt con los resultados de la búsqueda.">
        Solo lo relevante llega al modelo.
      </Head>
      <Enter at={0.4} style={{ position: "absolute", left: 96, top: 366 }}>
        <Tag color={C.muted}>Qdrant · 80 productos</Tag>
        <div
          style={{
            display: "grid",
            gridTemplateColumns: "repeat(10, 34px)",
            gap: 12,
            marginTop: 33,
          }}
        >
          {Array.from({ length: 80 }, (_, i) => (
            <div
              key={i}
              style={{
                height: 34,
                borderRadius: 7,
                background: i < 5 ? C.lime : C.panel,
                border: `1px solid ${i < 5 ? C.lime : C.line}`,
                opacity: i < 5 ? 1 : 0.65,
              }}
            />
          ))}
        </div>
        <div style={{ fontSize: 28, color: C.muted, marginTop: 30 }}>
          El catálogo permanece acá.
        </div>
      </Enter>
      <svg
        style={{ position: "absolute", inset: 0, width: 1920, height: 1080 }}
      >
        <path
          d="M595 420L850 545L595 765M850 545H1130"
          stroke={C.line}
          strokeWidth={2}
          fill="none"
        />
        <path
          d="M595 420L850 545L595 765"
          stroke={C.lime}
          strokeWidth={2}
          fill="none"
          opacity={interpolate(t, [1, 3], [0, 0.6], clamp)}
        />
      </svg>
      <Enter at={1.5} style={{ position: "absolute", left: 705, top: 372 }}>
        <Tag>5 recuperados</Tag>
      </Enter>
      {products.map((p, i) => {
        const q = interpolate(t, [2 + i * 0.45, 4 + i * 0.45], [0, 1], clamp);
        return (
          <div
            key={p.id}
            style={{
              position: "absolute",
              left: interpolate(q, [0, 1], [605, 1160], clamp),
              top: interpolate(q, [0, 1], [433 + i * 60, 459 + i * 55], clamp),
              width: 140,
              height: 48,
              background: C.lime,
              borderRadius: 10,
              opacity:
                t < 2 + i * 0.45
                  ? 0
                  : interpolate(q, [0, 0.85, 1], [1, 1, 0], clamp),
              color: C.bg,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 21,
            }}
          >
            producto 0{i + 1}
          </div>
        );
      })}
      <Enter
        at={2.4}
        style={{
          position: "absolute",
          left: 1130,
          top: 358,
          width: 690,
          height: 475,
          border: `1px solid ${C.lime}`,
          borderRadius: 26,
          background: C.panel,
          padding: 24,
          boxSizing: "border-box",
        }}
      >
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
          }}
        >
          <Tag>02 / Llamado al LLM</Tag>
          <Glyph kind="model" size={40} />
        </div>
        <div style={{ fontSize: 22, color: C.muted, margin: "16px 0" }}>
          Instrucciones + historial + mensaje original
        </div>
        <div
          style={{
            borderTop: `1px solid ${C.line}`,
            paddingTop: 12,
            fontSize: 22,
          }}
        >
          Contexto recuperado
        </div>
        {products.map((p, i) => (
          <Enter
            key={p.id}
            at={4 + i * 0.45}
            style={{
              display: "flex",
              alignItems: "center",
              gap: 15,
              marginTop: 6,
            }}
          >
            <Photo index={i} size={32} />
            <div style={{ fontSize: 21, flex: 1 }}>{p.name}</div>
            <span style={{ fontSize: 23, color: C.lime }}>${p.price}</span>
          </Enter>
        ))}
      </Enter>
      <Note at={7}>
        Se envían los datos de los productos: ID, nombre, precio, tags y
        descripción. Los vectores quedan en Qdrant.
      </Note>
    </Stage>
  );
};
