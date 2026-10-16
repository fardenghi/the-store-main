import React from "react";
import { interpolate, useCurrentFrame, useVideoConfig } from "remotion";
import { C, Glyph, Photo, clamp, ease } from "./design";

export const progress = (t: number, from: number, to: number) =>
  interpolate(t, [from, to], [0, 1], { ...clamp, easing: ease });
export const VectorBars: React.FC<{
  seed?: number;
  color?: string;
  width?: number;
  height?: number;
  active?: boolean;
}> = ({
  seed = 0,
  color = C.lime,
  width = 120,
  height = 64,
  active = true,
}) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        gap: width / 32,
        width,
        height,
      }}
    >
      {Array.from({ length: 12 }, (_, i) => (
        <div
          key={i}
          style={{
            width: width / 24,
            borderRadius: 8,
            background: color,
            height:
              height *
              (0.18 +
                Math.abs(
                  Math.sin(i * 1.73 + seed + (active ? (f / fps) * 2 : 0)),
                ) *
                  0.78),
          }}
        />
      ))}
    </div>
  );
};
export const Tile: React.FC<{
  index: number;
  size?: number;
  selected?: boolean;
  style?: React.CSSProperties;
}> = ({ index, size = 120, selected = false, style }) => (
  <div
    style={{
      width: size,
      height: size,
      padding: 10,
      boxSizing: "border-box",
      background: C.ink,
      borderRadius: 24,
      border: `2px solid ${selected ? C.lime : "transparent"}`,
      boxShadow: selected ? "0 0 0 6px #d4f77d24" : "0 16px 45px #0002",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      ...style,
    }}
  >
    <Photo index={index} size={size - 24} />
  </div>
);
export const Core: React.FC<{
  x: number;
  y: number;
  kind?: "model" | "db" | "store";
  label: string;
  active?: number;
  number?: string;
  size?: number;
}> = ({ x, y, kind = "model", label, active = 1, number, size = 210 }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <div
      style={{
        position: "absolute",
        left: x - size / 2,
        top: y - size / 2,
        width: size,
        height: size,
      }}
    >
      {[0, 1].map((i) => (
        <div
          key={i}
          style={{
            position: "absolute",
            inset: -30 - i * 24,
            border: `1px solid ${C.lime}`,
            borderRadius: "50%",
            scale: 1 + (((f / fps) * 0.3 + i * 0.5) % 1) * 0.12,
            opacity: active * (0.24 - (((f / fps) * 0.3 + i * 0.5) % 1) * 0.18),
          }}
        />
      ))}
      <div
        style={{
          position: "absolute",
          inset: -12,
          border: `1px solid ${C.line}`,
          borderRadius: 50,
          rotate: `${(f / fps) * 7}deg`,
        }}
      />
      <div
        style={{
          position: "absolute",
          inset: 0,
          background: kind === "model" ? C.lime : C.panel,
          border: `1px solid ${kind === "model" ? C.lime : C.line}`,
          borderRadius: 42,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          boxShadow: `0 0 ${active * 65}px #d4f77d12`,
        }}
      >
        <div
          style={{
            rotate: kind === "model" ? `${(f / fps) * 14}deg` : undefined,
          }}
        >
          <Glyph
            kind={kind}
            size={size * 0.56}
            color={kind === "model" ? C.bg : C.lime}
          />
        </div>
        {number && (
          <div
            style={{
              position: "absolute",
              right: -18,
              top: -18,
              width: 67,
              height: 67,
              borderRadius: 22,
              background: C.lilac,
              color: C.bg,
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              fontSize: 32,
              fontWeight: 600,
            }}
          >
            {number}
          </div>
        )}
      </div>
      <div
        style={{
          position: "absolute",
          top: size + 40,
          left: -100,
          right: -100,
          textAlign: "center",
          fontSize: 32,
          color: C.ink,
        }}
      >
        {label}
      </div>
    </div>
  );
};
export const Rail: React.FC<{
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  at?: number;
  until?: number;
  color?: string;
}> = ({ x1, y1, x2, y2, at = 0, until = 100, color = C.lime }) => {
  const f = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = f / fps;
  const p = progress(t, at, at + 1);
  return (
    <svg
      width={1920}
      height={1080}
      style={{ position: "absolute", inset: 0, pointerEvents: "none" }}
    >
      <path
        d={`M${x1} ${y1}L${x2} ${y2}`}
        fill="none"
        stroke={C.line}
        strokeWidth={2}
      />
      <path
        d={`M${x1} ${y1}L${x2} ${y2}`}
        fill="none"
        stroke={color}
        strokeOpacity={0.5}
        strokeWidth={2}
        pathLength={1}
        strokeDasharray={1}
        strokeDashoffset={1 - p}
      />
      {Array.from({ length: 5 }, (_, i) => {
        const q = ((t - at) * 0.43 - i * 0.19) % 1;
        return t < at || t > until || q < 0 ? null : (
          <g key={i}>
            <circle
              cx={x1 + (x2 - x1) * q}
              cy={y1 + (y2 - y1) * q}
              r={11}
              fill={color}
              opacity={0.09}
            />
            <circle
              cx={x1 + (x2 - x1) * q}
              cy={y1 + (y2 - y1) * q}
              r={4}
              fill={color}
            />
          </g>
        );
      })}
    </svg>
  );
};
export const Check = ({
  size = 40,
  color = C.bg,
}: {
  size?: number;
  color?: string;
}) => (
  <svg width={size} height={size} viewBox="0 0 40 40" fill="none">
    <path
      d="m9 20 8 8 16-17"
      stroke={color}
      strokeWidth={3}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);
