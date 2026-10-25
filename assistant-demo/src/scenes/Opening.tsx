import { interpolate } from "remotion";
import { useSceneTime } from "../clock";
import { C, Stage, Enter, clamp } from "../design";
import { Tile, Core, progress } from "../motion";
export const Opening = () => {
  const T = useSceneTime();
  const t = T();
  return (
    <Stage step={1} label="Tu próximo match">
      <Enter
        at={0.1}
        style={{
          position: "absolute",
          left: 96,
          top: 320,
          fontSize: 148,
          lineHeight: 0.98,
          letterSpacing: -8,
          fontWeight: 500,
        }}
      >
        Pedilo.
        <br />
        <span style={{ color: C.lime }}>Encontralo.</span>
      </Enter>
      <div
        style={{
          position: "absolute",
          inset: 0,
          opacity: 1 - progress(T(2.7), 2.7, 3.6),
        }}
      >
        <Core x={1400} y={530} label="" size={165} />
      </div>
      {Array.from({ length: 5 }, (_, i) => {
        const a = (i * Math.PI * 2) / 5 + t * 0.32;
        const shrink = progress(T(1.8), 1.8, 3.1);
        const winner = i === 0;
        return (
          <div
            key={i}
            style={{
              position: "absolute",
              left: 1400 + Math.cos(a) * 290 * (1 - shrink) - 65,
              top: 530 + Math.sin(a) * 275 * (1 - shrink) - 65,
              opacity: winner
                ? progress(T(0.1), 0.1, 0.5)
                : 1 - progress(shrink, 0, 0.55),
              scale: winner
                ? interpolate(shrink, [0, 1], [1, 3.1], clamp)
                : 1 - shrink * 0.6,
              rotate: `${(1 - shrink) * Math.sin(a) * 10}deg`,
            }}
          >
            <Tile index={i} size={130} selected={winner && T(3) > 3} />
          </div>
        );
      })}
      <Enter
        at={2.9}
        style={{
          position: "absolute",
          left: 1260,
          top: 800,
          color: C.lime,
          fontSize: 29,
        }}
      >
        Tu match. ✦
      </Enter>
    </Stage>
  );
};
