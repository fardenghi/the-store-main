import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Head, Enter, clamp } from "../design";
import { Tile, Core, Rail, progress } from "../motion";
export const Followup = () => {
  const T = useSceneTime();
  const t = T();
  return (
    <Stage step={7} label="Memoria · Volver a buscar">
      <div style={{ opacity: 1 - progress(T(6.3), 6.3, 7) }}>
        <Head>“Nogal, más barata.”</Head>
        <div
          style={{
            position: "absolute",
            left: 160,
            top: 408,
            rotate: `${Math.sin(t) * 3}deg`,
          }}
        >
          <Tile index={0} size={180} />
          <div
            style={{
              fontSize: 35,
              textAlign: "center",
              marginTop: 25,
              color: C.muted,
            }}
          >
            $149
          </div>
        </div>
        <Rail x1={340} y1={500} x2={960} y2={500} at={0.7} until={3} />
        <Core x={960} y={500} number="01" label="Reescribir" size={160} />
        <Enter
          at={1.4}
          style={{
            position: "absolute",
            left: 1300,
            top: 415,
            color: C.lime,
            fontSize: 88,
            letterSpacing: -4,
          }}
        >
          ≤ ${Math.round(interpolate(T(1.5), [1.5, 2.8], [149, 148], clamp))}
        </Enter>
        <Enter
          at={1.5}
          style={{
            position: "absolute",
            left: 1320,
            top: 542,
            fontSize: 26,
            color: C.muted,
          }}
        >
          desk lamp
        </Enter>
        <div
          style={{
            position: "absolute",
            left: 200,
            top: 810,
            display: "flex",
            alignItems: "center",
            gap: 95,
            fontSize: 31,
          }}
        >
          {["01", "Qdrant", "02"].map((label, i) => (
            <div
              key={label}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 95,
                color: T(2.5 + i * 0.8) > 2.5 + i * 0.8 ? C.lime : C.line,
              }}
            >
              <span
                style={{
                  border: `1px solid ${T(2.5 + i * 0.8) > 2.5 + i * 0.8 ? C.lime : C.line}`,
                  borderRadius: 18,
                  padding: "15px 35px",
                }}
              >
                {label}
              </span>
              {i < 2 && <span>→</span>}
            </div>
          ))}
        </div>
        <Enter
          at={4.6}
          style={{
            position: "absolute",
            left: 1470,
            top: 720,
            display: "flex",
            gap: 22,
            alignItems: "center",
          }}
        >
          <Tile index={4} size={145} selected />
          <span style={{ fontSize: 49, color: C.lime }}>$79</span>
        </Enter>
      </div>
      <Enter
        at={6.9}
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
    </Stage>
  );
};
