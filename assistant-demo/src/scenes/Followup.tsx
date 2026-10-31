import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, Tag, clamp } from "../design";
import { Tile, Core, Rail, Check, progress } from "../motion";

// Segundo turno, en una sola fila que se recorre de izquierda a derecha:
// el producto del turno anterior (memoria) → LLM 01 reescribe "más barata"
// como filtro (≤ $148) → Qdrant busca con ese filtro → nuevo match a $79.
// Todos los textos de cada etapa quedan en la misma línea (CAPTION).
const Y = 560;
const X = {
  memory: 230,
  rewrite: 590,
  query: 960,
  qdrant: 1330,
  result: 1650,
};
const CAPTION = Y + 115;

const Caption: React.FC<{
  x: number;
  at: number;
  children: React.ReactNode;
}> = ({ x, at, children }) => (
  <Enter
    at={at}
    style={{
      position: "absolute",
      left: x - 160,
      width: 320,
      top: CAPTION,
      textAlign: "center",
      fontSize: 32,
      color: C.ink,
    }}
  >
    {children}
  </Enter>
);

// `part`: el video muestra todo ("full"); el deck lo separa en dos slides, el
// recorrido que queda quieto al terminar ("flow") y el cierre ("closing").
export const Followup = ({
  part = "full",
}: {
  part?: "full" | "flow" | "closing";
}) => {
  const T = useSceneTime();
  const t = T();
  const maxPrice = Math.round(
    interpolate(T(2.7), [2.7, 3.3], [149, 148], clamp),
  );
  return (
    <Stage step={7} label="Memoria · Volver a buscar">
      {part !== "closing" && (
        <div
          style={{
            opacity: part === "full" ? 1 - progress(T(6.8), 6.8, 7.4) : 1,
          }}
        >
          <Head>“Nogal, más barata.”</Head>

          <Rail
            x1={X.memory}
            y1={Y}
            x2={X.rewrite}
            y2={Y}
            at={0.9}
            until={2.4}
          />
          <Rail
            x1={X.rewrite}
            y1={Y}
            x2={X.query}
            y2={Y}
            at={1.9}
            until={3.4}
          />
          <Rail x1={X.query} y1={Y} x2={X.qdrant} y2={Y} at={3.3} until={4.8} />
          <Rail x1={X.qdrant} y1={Y} x2={X.result} y2={Y} at={4.4} until={6} />

          {/* 1. Memoria: el producto que se mostró en el turno anterior. */}
          <Enter
            at={0.4}
            style={{ position: "absolute", left: X.memory - 75, top: Y - 140 }}
          >
            <Tag color={C.lilac}>Memoria</Tag>
          </Enter>
          <Enter
            at={0.4}
            style={{
              position: "absolute",
              left: X.memory - 75,
              top: Y - 75,
              rotate: `${Math.sin(t) * 3}deg`,
            }}
          >
            <Tile index={0} size={150} />
          </Enter>
          <Caption x={X.memory} at={0.5}>
            Turno anterior · <span style={{ color: C.muted }}>$149</span>
          </Caption>

          {/* 2. LLM 01: entiende "más barata" gracias a la memoria. */}
          <Enter at={1.2}>
            <Core
              x={X.rewrite}
              y={Y}
              number="01"
              label="Reescribir"
              size={150}
              active={progress(T(1.6), 1.6, 2.4)}
            />
          </Enter>

          {/* 3. Consulta reescrita con el filtro de precio. */}
          <Enter
            at={2.3}
            style={{
              position: "absolute",
              left: X.query - 160,
              width: 320,
              top: Y - 90,
              height: 180,
              boxSizing: "border-box",
              padding: "24px 30px",
              background: C.panel,
              border: `1px solid ${C.lime}`,
              borderRadius: 26,
            }}
          >
            <div style={{ fontSize: 36, letterSpacing: -1 }}>desk lamp</div>
            <div
              style={{
                fontSize: 64,
                letterSpacing: -3,
                color: C.lime,
                marginTop: 8,
              }}
            >
              ≤ ${maxPrice}
            </div>
          </Enter>
          <Caption x={X.query} at={2.4}>
            Consulta + filtro
          </Caption>

          {/* 4. Qdrant busca con el filtro. */}
          <Enter at={3.7}>
            <Core
              x={X.qdrant}
              y={Y}
              kind="db"
              label="Qdrant"
              size={150}
              active={progress(T(4), 4, 4.8)}
            />
          </Enter>

          {/* 5. Nuevo match, más barato. */}
          <Enter
            at={5}
            style={{ position: "absolute", left: X.result - 75, top: Y - 75 }}
          >
            <Tile index={4} size={150} selected />
            <div
              style={{
                position: "absolute",
                right: -16,
                top: -16,
                width: 52,
                height: 52,
                borderRadius: "50%",
                background: C.lime,
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                scale: interpolate(
                  T(5.4),
                  [5.4, 5.7, 5.9],
                  [0, 1.15, 1],
                  clamp,
                ),
              }}
            >
              <Check size={30} />
            </div>
          </Enter>
          <Caption x={X.result} at={5.1}>
            Nuevo match · <span style={{ color: C.lime }}>$79</span>
          </Caption>
        </div>
      )}
      {part !== "flow" && (
        <Enter
          at={part === "closing" ? 0.2 : 7.3}
          style={{
            position: "absolute",
            left: 96,
            top: 355,
            fontSize: 140,
            lineHeight: 1,
            letterSpacing: -7,
          }}
        >
          Menos búsqueda.
          <br />
          <span style={{ color: C.lime }}>Más match.</span>
        </Enter>
      )}
    </Stage>
  );
};
