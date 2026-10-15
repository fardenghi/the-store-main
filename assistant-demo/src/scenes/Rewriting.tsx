import { useCurrentFrame, useVideoConfig, interpolate } from "remotion";
import { C, Stage, Enter, Tag, Glyph, Note, clamp } from "../design";
export const Rewriting = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const query = "desk lamp";
  return (
    <Stage step={3} label="Consulta · Primer llamado al modelo">
      <Enter style={{ position: "absolute", left: 96, top: 200 }}>
        <Tag>01 / Llamado al LLM · NVIDIA</Tag>
        <div
          style={{
            fontSize: 105,
            lineHeight: 1.02,
            letterSpacing: -5,
            marginTop: 32,
          }}
        >
          Una pregunta.
          <br />
          Una consulta
          <br />
          <span style={{ color: C.lime }}>precisa.</span>
        </div>
      </Enter>
      <Enter
        at={1}
        style={{
          position: "absolute",
          left: 96,
          top: 635,
          width: 755,
          padding: 32,
          background: C.ink,
          color: C.bg,
          borderRadius: "24px 24px 24px 4px",
          fontSize: 39,
          lineHeight: 1.25,
        }}
      >
        “Necesito una lámpara
        <br />
        para mi escritorio”
        <div style={{ fontSize: 20, marginTop: 20, color: "#74796e" }}>
          MENSAJE DEL USUARIO
        </div>
      </Enter>
      <Enter
        at={2}
        style={{
          position: "absolute",
          left: 1010,
          top: 220,
          width: 810,
          height: 610,
          border: `1px solid ${C.line}`,
          background: C.panel,
          borderRadius: 30,
          padding: 45,
          boxSizing: "border-box",
        }}
      >
        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
          }}
        >
          <Tag>Query rewriter</Tag>
          <Glyph kind="model" size={55} />
        </div>
        <div
          style={{
            color: C.muted,
            fontSize: 28,
            marginTop: 30,
            lineHeight: 1.45,
          }}
        >
          Mensaje + últimos 3 turnos
          <br />+ productos previos + tags del catálogo
        </div>
        <div style={{ height: 1, background: C.line, margin: "30px 0" }} />
        <div
          style={{
            fontFamily: "Menlo, monospace",
            fontSize: 28,
            lineHeight: 1.7,
            color: C.muted,
          }}
        >
          {"{"}
          <div style={{ paddingLeft: 30 }}>
            intent: <span style={{ color: C.lilac }}>“search”</span>,<br />
            query:{" "}
            <span style={{ color: C.lime }}>
              “
              {query.slice(
                0,
                Math.round(
                  interpolate(f, [5 * fps, 6 * fps], [0, query.length], clamp),
                ),
              )}
              ”
            </span>
            ,<br />
            maxPrice: null,
            <br />
            excludeTags: []
          </div>
          {"}"}
        </div>
      </Enter>
      <Note at={7}>
        El primer llamado entiende la intención, traduce al inglés y extrae
        filtros. Todavía no recomienda.
      </Note>
    </Stage>
  );
};
