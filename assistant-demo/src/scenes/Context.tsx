import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, clamp } from "../design";
import { Core, Tile, Rail, progress, Check } from "../motion";
export const Context = () => {
  const T = useSceneTime();
  const arrival = progress(T(5.4), 5.4, 6.6);
  return (
    <Stage step={5} label="LLM 02 · Responder">
      <Head>Solo los matches.</Head>
      <Enter
        at={0.3}
        style={{
          position: "absolute",
          left: 1140,
          top: 185,
          color: C.lime,
          fontSize: 56,
          letterSpacing: -2,
        }}
      >
        5 productos
      </Enter>
      <Rail x1={300} y1={550} x2={1510} y2={550} at={1} />
      <div
        style={{
          position: "absolute",
          // Ocupa lo mismo que la columna de las 5 fotos al arrancar (centro
          // x = 300, de y = 324 a 756): 8 × 10 celdas de 33 px con 11 de
          // separación (341 × 429 px).
          left: 300 - 170,
          top: 326,
          display: "grid",
          gridTemplateColumns: "repeat(8, 33px)",
          gap: 11,
          opacity: 0.6,
        }}
      >
        {Array.from({ length: 80 }, (_, i) => (
          <div
            key={i}
            style={{
              height: 33,
              border: `1px solid ${C.line}`,
              borderRadius: 6,
              background: C.panel,
            }}
          />
        ))}
      </div>
      <Enter
        at={0.1}
        style={{
          position: "absolute",
          left: 300 - 150,
          width: 300,
          textAlign: "center",
          top: 790,
          fontSize: 31,
          color: C.muted,
        }}
      >
        Qdrant
      </Enter>
      <Core
        x={1510}
        y={550}
        label="Respuesta"
        number="02"
        size={240}
        active={arrival}
      />
      {Array.from({ length: 5 }, (_, i) => {
        const p = progress(T(1 + i * 0.42), 1 + i * 0.42, 4.3 + i * 0.42);
        const x = interpolate(p, [0, 1], [300, 1510], clamp);
        const y = interpolate(
          p,
          [0, 0.45, 1],
          [370 + i * 85, 390 + i * 72, 550],
          clamp,
        );
        const size = 92;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: x - size / 2,
              top: y - size / 2,
              opacity: 1 - progress(p, 0.86, 1),
              scale: interpolate(
                p,
                [0, 0.45, 0.8, 1],
                [0.85, 1.3, 1, 0.15],
                clamp,
              ),
              rotate: `${Math.sin(p * Math.PI) * (-10 + i * 5)}deg`,
            }}
          >
            <Tile index={i} size={size} selected />
          </div>
        );
      })}
      <div
        style={{
          position: "absolute",
          left: 880,
          top: 360,
          width: 2,
          height: 380,
          background: C.line,
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 845,
          top: 280,
          width: 72,
          height: 72,
          borderRadius: 50,
          background: C.lime,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          opacity: progress(T(1), 1, 2),
        }}
      >
        <Check />
      </div>
      {/* Una marca por producto, que se enciende cuando su foto llega al
          modelo: la foto i se desvanece en el nodo entre 1.98 + 0.42 i s
          (50%) y 2.19 + 0.42 i s, y su marca se enciende en ese tramo. Centradas con el nodo (x = 1510): 5 × 34 px
          con 10 de separación = 210 px. */}
      <Enter
        at={1.2}
        style={{
          position: "absolute",
          left: 1510 - 105,
          top: 840,
          display: "flex",
          gap: 10,
        }}
      >
        {Array.from({ length: 5 }, (_, i) => (
          <div
            key={i}
            style={{
              width: 34,
              height: 8,
              borderRadius: 4,
              background: C.lime,
              opacity: progress(
                T(1.95 + i * 0.42),
                1.95 + i * 0.42,
                2.2 + i * 0.42,
              ),
            }}
          />
        ))}
      </Enter>
    </Stage>
  );
};
