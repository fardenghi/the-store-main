import { interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { Stage, Enter, Photo, clamp } from "../design";
export const Response = () => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const text =
    "La Curved Brass and Walnut Desk Lamp combina base de nogal, estructura de latón y pantalla de tela. Una opción para tu escritorio por US$149.";
  return (
    <Stage step={6} label="Respuesta en streaming · SSE" light>
      <Enter style={{ position: "absolute", left: 96, top: 225 }}>
        <div style={{ fontSize: 23, letterSpacing: 3, color: "#65704f" }}>
          DEL CONTEXTO A LA RECOMENDACIÓN
        </div>
        <div
          style={{
            fontSize: 109,
            lineHeight: 1.02,
            letterSpacing: -6,
            marginTop: 35,
          }}
        >
          Una respuesta.
          <br />
          Con productos
          <br />
          <span style={{ color: "#6b843a" }}>reales.</span>
        </div>
        <div
          style={{
            fontSize: 34,
            color: "#6a7162",
            lineHeight: 1.45,
            marginTop: 42,
          }}
        >
          El modelo redacta.
          <br />
          El catálogo aporta los datos.
          <br />
          La UI recibe fragmentos por SSE.
        </div>
      </Enter>
      <Enter
        at={0.5}
        style={{
          position: "absolute",
          left: 975,
          top: 220,
          width: 845,
          height: 675,
          border: "1px solid #ced1c4",
          background: "#f8f8f1",
          borderRadius: 30,
          padding: 38,
          boxSizing: "border-box",
          boxShadow: "0 25px 70px #11141009",
        }}
      >
        <div
          style={{
            fontSize: 26,
            fontWeight: 500,
            paddingBottom: 27,
            borderBottom: "1px solid #d9dccf",
          }}
        >
          A.G.E.N.T.{" "}
          <span style={{ float: "right", fontSize: 19, color: "#71805b" }}>
            ● EN LÍNEA
          </span>
        </div>
        <div
          style={{
            background: "#e1e7d6",
            padding: "22px 26px",
            borderRadius: "18px 18px 4px 18px",
            fontSize: 29,
            marginTop: 26,
            marginLeft: 65,
          }}
        >
          Necesito una lámpara para mi escritorio
        </div>
        <div
          style={{
            fontSize: 29,
            lineHeight: 1.45,
            marginTop: 30,
            minHeight: 170,
          }}
        >
          {text.slice(
            0,
            Math.round(
              interpolate(f, [2 * fps, 5.5 * fps], [0, text.length], clamp),
            ),
          )}
          <span style={{ color: "#71805b", opacity: f / fps < 5.5 ? 1 : 0 }}>
            ▍
          </span>
        </div>
        <Enter
          at={3.5}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 26,
            background: "#fff",
            border: "1px solid #dce0d3",
            borderRadius: 20,
            padding: 20,
          }}
        >
          <Photo index={0} size={145} />
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 26, lineHeight: 1.3 }}>
              Curved Brass and Walnut
              <br />
              Desk Lamp
            </div>
            <div style={{ fontSize: 33, marginTop: 14 }}>US$149</div>
            <div style={{ fontSize: 19, color: "#73815b", marginTop: 10 }}>
              PRODUCTO DEL CATÁLOGO
            </div>
          </div>
        </Enter>
      </Enter>
    </Stage>
  );
};
