import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import { C, Stage, Enter, Tag, Glyph, clamp } from "../design";
export const Followup = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  return (
    <Stage step={7} label="Memoria de sesión · El ciclo continúa">
      <div style={{ opacity: interpolate(t, [8, 9], [1, 0], clamp) }}>
        <Enter style={{ position: "absolute", left: 96, top: 220 }}>
          <Tag>La conversación también es contexto</Tag>
          <div
            style={{
              fontSize: 96,
              lineHeight: 1.08,
              letterSpacing: -4,
              marginTop: 35,
            }}
          >
            “¿Algo más barato
            <br />
            que la de nogal?”
          </div>
          <div style={{ fontSize: 33, color: C.muted, marginTop: 35 }}>
            Producto anterior: US$149
            <br />
            El primer llamado resuelve la referencia.
          </div>
        </Enter>
        <Enter
          at={1.8}
          style={{
            position: "absolute",
            left: 1120,
            top: 230,
            width: 700,
            background: C.panel,
            border: `1px solid ${C.line}`,
            borderRadius: 27,
            padding: 40,
            boxSizing: "border-box",
          }}
        >
          <Tag>01 / Reescritura</Tag>
          <div
            style={{
              fontFamily: "Menlo, monospace",
              fontSize: 32,
              lineHeight: 1.8,
              marginTop: 25,
            }}
          >
            query: <span style={{ color: C.lime }}>“desk lamp”</span>
            <br />
            maxPrice: <span style={{ color: C.lime, fontSize: 55 }}>148</span>
            <br />
            <span style={{ fontSize: 24, color: C.muted }}>
              149 − 1 · dólares enteros
            </span>
          </div>
        </Enter>
        <Enter
          at={4}
          style={{
            position: "absolute",
            left: 96,
            right: 96,
            top: 720,
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            fontSize: 31,
          }}
        >
          <span style={{ color: C.lime }}>01 · Reescribir</span>
          <span style={{ color: C.line }}>→</span>
          <span>Embedding</span>
          <span style={{ color: C.line }}>→</span>
          <span>Qdrant + filtro</span>
          <span style={{ color: C.line }}>→</span>
          <span style={{ color: C.lime }}>02 · Responder</span>
        </Enter>
        <Enter
          at={5.5}
          style={{
            position: "absolute",
            left: 96,
            top: 844,
            fontSize: 27,
            color: C.muted,
          }}
        >
          Dos llamados en esta consulta simple. Las acciones con tools pueden
          sumar llamadas.
        </Enter>
      </div>
      <Enter at={8.5} style={{ position: "absolute", left: 96, top: 280 }}>
        <Glyph kind="model" size={95} />
        <div
          style={{
            fontSize: 103,
            letterSpacing: -5,
            lineHeight: 1.05,
            marginTop: 25,
          }}
        >
          Entender. Buscar.
          <br />
          <span style={{ color: C.lime }}>Recomendar.</span>
        </div>
      </Enter>
    </Stage>
  );
};
