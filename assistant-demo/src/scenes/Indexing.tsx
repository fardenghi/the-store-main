import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, clamp } from "../design";
import { Core, Rail, Tile, VectorBars, progress, Check } from "../motion";
export const Indexing = () => {
  const T = useSceneTime();
  const count = Math.round(interpolate(T(3.4), [3.4, 9], [0, 80], clamp));
  return (
    <Stage step={2} label="Arranque · Assistant">
      <Head>
        Catálogo → <span style={{ color: C.lime }}>conocimiento.</span>
      </Head>
      {/* Todo el diagrama, 30 px más abajo para despegarlo del título. */}
      <div style={{ position: "absolute", inset: 0, translate: "0 30px" }}>
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
          const ts = T(start);
          const p = interpolate(ts, [start, start + 3.4], [0, 1], clamp);
          const x = 250 + p * 1410;
          const morph = progress(p, 0.38, 0.57);
          return (
            <div
              key={i}
              style={{
                position: "absolute",
                left: x - 48,
                top: 482 + Math.sin(p * Math.PI) * Math.sin(i * 2) * 50,
                opacity: ts < start || p >= 1 ? 0 : 1,
                scale: interpolate(
                  p,
                  [0, 0.1, 0.8, 1],
                  [0.4, 1, 1, 0.3],
                  clamp,
                ),
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
                style={{
                  position: "absolute",
                  left: 0,
                  top: 12,
                  opacity: morph,
                }}
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
            // Centrado bajo Gemini (x = 960), del tamaño del contador.
            left: 960 - 400,
            width: 800,
            top: 790,
            textAlign: "center",
            color: C.lime,
            fontSize: 39,
          }}
        >
          <span style={{ color: C.muted }}>Embeddings:</span> 768 dimensiones
        </Enter>
        <Enter
          at={3.5}
          style={{
            // Contador y barra arriba de Qdrant, centrados con el nodo (x = 1660).
            position: "absolute",
            left: 1660 - 200,
            width: 400,
            top: 235,
            display: "flex",
            justifyContent: "center",
            gap: 18,
            alignItems: "center",
            fontSize: 39,
          }}
        >
          <span>
            {count}
            <span style={{ color: C.muted }}> / 80</span>
          </span>
          {/* Siempre presente (invisible hasta 80) para que el número no se corra. */}
          <span style={{ opacity: count === 80 ? 1 : 0, display: "flex" }}>
            <Check color={C.lime} />
          </span>
        </Enter>
        <div
          style={{
            position: "absolute",
            // 80 marcas de 3 px con 1 px de separación: 319 px de ancho.
            left: 1660 - 160,
            top: 305,
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
      </div>
    </Stage>
  );
};
