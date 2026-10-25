import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Enter, Photo, clamp } from "../design";
import { Check, progress } from "../motion";
export const Response = () => {
  const T = useSceneTime();
  const t = T();
  const text = "Nogal. Latón. Para tu escritorio.";
  return (
    <Stage step={6} label="Streaming · SSE" light>
      <div
        style={{
          position: "absolute",
          left: 160,
          top: 240,
          width: 610,
          height: 610,
          borderRadius: "50%",
          border: "1px solid #c9d2b7",
          scale: 1 + 0.02 * Math.sin(t * 2),
        }}
      />
      <Enter
        at={0.1}
        style={{
          position: "absolute",
          left: 215,
          top: 280,
          rotate: `${interpolate(T(0.1), [0, 3], [-9, 0], clamp)}deg`,
          scale: interpolate(T(0.1), [0, 1], [0.7, 1], clamp),
        }}
      >
        <Photo index={0} size={500} />
      </Enter>
      <div
        style={{
          position: "absolute",
          left: 655,
          top: 725,
          width: 108,
          height: 108,
          borderRadius: "50%",
          background: C.lime,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          scale: interpolate(T(1.5), [1.5, 1.9, 2.2], [0, 1.15, 1], clamp),
        }}
      >
        <Check size={58} />
      </div>
      <Enter
        at={0.4}
        style={{
          position: "absolute",
          left: 940,
          top: 280,
          fontSize: 130,
          letterSpacing: -7,
        }}
      >
        Tu match.
      </Enter>
      <Enter
        at={1.4}
        style={{
          position: "absolute",
          left: 950,
          top: 466,
          fontSize: 35,
          color: "#6b765b",
          maxWidth: 760,
          minHeight: 100,
        }}
      >
        {text.slice(
          0,
          Math.round(interpolate(T(1.4), [1.4, 3.6], [0, text.length], clamp)),
        )}
        <span style={{ opacity: T(1.4) < 3.6 ? 1 : 0 }}>▍</span>
      </Enter>
      <Enter
        at={2.5}
        style={{
          position: "absolute",
          left: 942,
          top: 610,
          fontSize: 98,
          letterSpacing: -5,
        }}
      >
        US$149
      </Enter>
      <div
        style={{
          position: "absolute",
          left: 950,
          top: 785,
          display: "flex",
          gap: 8,
          opacity: 1 - progress(T(4.2), 4.2, 5),
        }}
      >
        {Array.from({ length: 16 }, (_, i) => (
          <div
            key={i}
            style={{
              width: 10,
              height: 8 + Math.abs(Math.sin(t * 4 - i * 0.6)) * 30,
              borderRadius: 5,
              background: "#6b843a",
              opacity: progress(
                T(1.5 + i * 0.07),
                1.5 + i * 0.07,
                1.7 + i * 0.07,
              ),
            }}
          />
        ))}
      </div>
    </Stage>
  );
};
