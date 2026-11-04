import React from "react";
import { C, Enter, Head, Stage, Tag } from "../design";
import { Check } from "../motion";

// La objeción obvia al problema: "¿y si ponemos un buscador?". Dos filas, una
// por cosa que un buscador no resuelve y el asistente sí: consultas reales
// (intención, no palabras) y contexto (la conversación). La segunda fila
// espera a la parada para poder hablar de la primera.
const COL = { query: 96, search: 730, assistant: 1280 };
const ROW_TOP = 470;
const ROW_GAP = 230;
const CELL_W = { search: 530, assistant: 514 };

const ROWS = [
  {
    label: "Consultas reales",
    query: "algo cómodo para leer de noche",
    search: "Busca por tags.\nNo interpreta queries libres.",
    assistant: "Entiende la intención\ny busca por significado",
  },
  {
    label: "Contexto",
    query: "¿hay algo más barato?",
    search: "No conoce el contexto",
    assistant: "Recuerda la lámpara de $149\ny busca hasta $148",
  },
];

export const ROW_START = [0.5, 2.8];
export const WHY_NOT_SEARCH_LENGTH = 4.8;

export const WhyNotSearch: React.FC = () => (
  <Stage label="El problema">
    <Head>¿Y si ponemos un buscador?</Head>

    <Enter at={0.3} style={{ position: "absolute", left: COL.search, top: 390 }}>
      <Tag color={C.muted}>Buscador</Tag>
    </Enter>
    <Enter
      at={0.3}
      style={{ position: "absolute", left: COL.assistant, top: 390 }}
    >
      <Tag>Asistente</Tag>
    </Enter>

    {ROWS.map((row, i) => {
      const at = ROW_START[i];
      const top = ROW_TOP + i * ROW_GAP;
      return (
        <React.Fragment key={row.label}>
          <div
            style={{
              position: "absolute",
              left: 96,
              right: 96,
              top: top - 24,
              height: 1,
              background: C.line,
              opacity: i === 0 ? 1 : 0,
            }}
          />
          <Enter at={at} style={{ position: "absolute", left: COL.query, top }}>
            <div
              style={{
                fontSize: 24,
                letterSpacing: 2,
                textTransform: "uppercase",
                color: C.muted,
              }}
            >
              {row.label}
            </div>
            <div
              style={{
                display: "inline-block",
                marginTop: 18,
                padding: "20px 28px",
                background: C.ink,
                color: C.bg,
                borderRadius: "26px 26px 26px 6px",
                fontSize: 38,
                letterSpacing: -0.5,
              }}
            >
              {row.query}
            </div>
          </Enter>
          <Enter
            at={at + 0.6}
            style={{
              position: "absolute",
              left: COL.search,
              top: top + 40,
              width: CELL_W.search,
              display: "flex",
              gap: 18,
              fontSize: 36,
              lineHeight: 1.3,
              whiteSpace: "pre",
              color: C.muted,
            }}
          >
            <span style={{ color: C.lilac, width: 28, flexShrink: 0 }}>✕</span>
            {row.search}
          </Enter>
          <Enter
            at={at + 1.1}
            style={{
              position: "absolute",
              left: COL.assistant,
              top: top + 36,
              width: CELL_W.assistant,
              display: "flex",
              gap: 18,
              fontSize: 36,
              lineHeight: 1.3,
              whiteSpace: "pre",
            }}
          >
            <span
              style={{
                width: 40,
                height: 40,
                flexShrink: 0,
                borderRadius: "50%",
                background: C.lime,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
              }}
            >
              <Check size={26} />
            </span>
            {row.assistant}
          </Enter>
          <div
            style={{
              position: "absolute",
              left: 96,
              right: 96,
              top: top + ROW_GAP - 24,
              height: 1,
              background: C.line,
              opacity: i === 0 ? 1 : 0,
            }}
          />
        </React.Fragment>
      );
    })}
  </Stage>
);
