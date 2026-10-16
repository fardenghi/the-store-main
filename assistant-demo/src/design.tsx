import React from "react";
import {
  AbsoluteFill,
  CanvasImage,
  Easing,
  interpolate,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";

export const C = {
  bg: "#111410",
  panel: "#1b1f19",
  line: "#363e31",
  ink: "#f3f2e9",
  muted: "#a1a997",
  lime: "#d4f77d",
  lilac: "#c9b7ef",
  paper: "#eeeee5",
};
export const clamp = {
  extrapolateLeft: "clamp",
  extrapolateRight: "clamp",
} as const;
export const ease = Easing.bezier(0.16, 1, 0.3, 1);
export const products = [
  {
    id: "7c931d3b-2776-521b-9bd1-a04c1f2b95c9",
    name: "Curved Brass & Walnut",
    full: "Curved Brass and Walnut Desk Lamp",
    price: 149,
  },
  {
    id: "621188c6-c6f6-5532-be0a-f4fc3d87567a",
    name: "Adjustable Pharmacy",
    full: "Adjustable Pharmacy Desk Lamp",
    price: 219,
  },
  {
    id: "f65310d9-f11a-577e-9367-f2408f7d5f72",
    name: "Vintage 3-Joint",
    full: "Vintage 3-Joint Floor Lamp",
    price: 229,
  },
  {
    id: "da9e13ca-87cb-553c-a590-cc873fc68a62",
    name: "Art Deco Brass",
    full: "Art Deco Brass Table Lamp",
    price: 239,
  },
  {
    id: "2d0690a7-24a4-57f0-9801-ed97cbeaf85a",
    name: "Scandinavian Wood",
    full: "Scandinavian Blond-Wood Table Lamp",
    price: 79,
  },
];
export const Photo: React.FC<{ index: number; size?: number }> = ({
  index,
  size = 80,
}) => (
  <CanvasImage
    src={staticFile(`products/${products[index % 5].id}.jpg`)}
    style={{
      width: size,
      height: size,
      objectFit: "contain",
      background: "#fff",
      borderRadius: 14,
    }}
  />
);

export const Enter: React.FC<{
  children: React.ReactNode;
  at?: number;
  style?: React.CSSProperties;
}> = ({ children, at = 0, style }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  return (
    <div
      style={{
        opacity: interpolate(
          frame,
          [at * fps, (at + 0.7) * fps],
          [0, 1],
          clamp,
        ),
        translate: `0 ${interpolate(frame, [at * fps, (at + 0.9) * fps], [30, 0], { ...clamp, easing: ease })}px`,
        ...style,
      }}
    >
      {children}
    </div>
  );
};
export const Tag: React.FC<{ children: React.ReactNode; color?: string }> = ({
  children,
  color = C.lime,
}) => (
  <div
    style={{
      display: "inline-flex",
      alignItems: "center",
      gap: 12,
      color,
      fontSize: 25,
      fontWeight: 500,
      letterSpacing: 2,
      textTransform: "uppercase",
    }}
  >
    <span
      style={{ width: 8, height: 8, background: color, borderRadius: 20 }}
    />
    {children}
  </div>
);
export const Head: React.FC<{ children: React.ReactNode; sub?: string }> = ({
  children,
  sub,
}) => (
  <Enter style={{ position: "absolute", left: 96, top: 157 }}>
    <div
      style={{
        fontSize: 94,
        lineHeight: 1.04,
        fontWeight: 500,
        letterSpacing: -5,
        maxWidth: 1740,
      }}
    >
      {children}
    </div>
    {sub && (
      <div
        style={{
          fontSize: 34,
          color: C.muted,
          marginTop: 25,
          letterSpacing: -0.5,
        }}
      >
        {sub}
      </div>
    )}
  </Enter>
);
export const Note: React.FC<{ children: React.ReactNode; at?: number }> = ({
  children,
  at = 0,
}) => (
  <Enter
    at={at}
    style={{
      position: "absolute",
      left: 96,
      right: 96,
      bottom: 119,
      fontSize: 32,
      color: C.muted,
      lineHeight: 1.4,
    }}
  >
    {children}
  </Enter>
);

export const Stage: React.FC<{
  children: React.ReactNode;
  step: number;
  label: string;
  light?: boolean;
}> = ({ children, step, label, light = false }) => {
  return (
    <AbsoluteFill
      style={{
        background: light ? C.paper : C.bg,
        color: light ? C.bg : C.ink,
        fontFamily: '"Helvetica Neue", Helvetica, Arial, sans-serif',
        overflow: "hidden",
      }}
    >
      <div
        style={{
          position: "absolute",
          inset: 0,
          backgroundImage: `radial-gradient(${light ? "#1114100a" : "#d4f77d08"} 1px, transparent 1px)`,
          backgroundSize: "32px 32px",
        }}
      />
      <div
        style={{
          position: "absolute",
          left: 96,
          right: 96,
          top: 62,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          fontSize: 27,
        }}
      >
        <div style={{ fontWeight: 650, letterSpacing: -1 }}>
          the store
          <span style={{ color: light ? "#58762a" : C.lime }}> / </span>
          <span style={{ fontWeight: 400 }}>A.G.E.N.T.</span>
        </div>
        <span
          style={{
            fontSize: 21,
            letterSpacing: 2,
            color: light ? "#6a7162" : C.muted,
          }}
        >
          ASISTENTE DE COMPRAS
        </span>
      </div>
      {children}
      <div
        style={{
          position: "absolute",
          left: 96,
          right: 96,
          bottom: 56,
          display: "flex",
          justifyContent: "space-between",
          alignItems: "center",
          borderTop: `1px solid ${light ? "#cdd0c3" : C.line}`,
          paddingTop: 23,
        }}
      >
        <div
          style={{ fontSize: 20, letterSpacing: 2, textTransform: "uppercase" }}
        >
          <span style={{ color: light ? "#58762a" : C.lime, marginRight: 22 }}>
            0{step}
          </span>
          {label}
        </div>
        <div style={{ display: "flex", gap: 9 }}>
          {Array.from({ length: 7 }, (_, i) => (
            <div
              key={i}
              style={{
                width: i === step - 1 ? 56 : 18,
                height: 4,
                background:
                  i === step - 1
                    ? light
                      ? "#58762a"
                      : C.lime
                    : light
                      ? "#cdd0c3"
                      : C.line,
              }}
            />
          ))}
        </div>
      </div>
    </AbsoluteFill>
  );
};

export const Glyph: React.FC<{
  kind: "store" | "model" | "db" | "vector";
  size?: number;
  color?: string;
}> = ({ kind, size = 70, color = C.lime }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 80 80"
    fill="none"
    stroke={color}
    strokeWidth="2.5"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    {kind === "store" ? (
      <>
        <path d="M13 31h54l-6-15H19zM18 33v33h44V33M31 66V46h18v20" />
        <path d="M13 31v7q7 9 14 0 7 9 13 0 7 9 13 0 7 9 14 0v-7" />
      </>
    ) : kind === "db" ? (
      <>
        <ellipse cx="40" cy="19" rx="25" ry="10" />
        <path d="M15 19v40c0 14 50 14 50 0V19M15 39c0 14 50 14 50 0" />
      </>
    ) : kind === "model" ? (
      <>
        <path d="M40 10v60M10 40h60M19 19l42 42M19 61l42-42" />
        <circle cx="40" cy="40" r="22" />
        <circle cx="40" cy="40" r="6" fill={color} />
      </>
    ) : (
      <>
        {[0, 1, 2, 3, 4].map((n) => (
          <path
            key={n}
            d={`M${17 + n * 11} ${30 - Math.sin(n * 1.7) * 13}v${24 + Math.sin(n * 1.7) * 20}`}
          />
        ))}
      </>
    )}
  </svg>
);

export const FlowLine: React.FC<{
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  start: number;
  end: number;
  color?: string;
  particles?: number;
}> = ({ x1, y1, x2, y2, start, end, color = C.lime, particles = 3 }) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();
  const t = frame / fps;
  const p = interpolate(t, [start, end], [0, 1], clamp);
  return (
    <svg
      style={{
        position: "absolute",
        inset: 0,
        width: 1920,
        height: 1080,
        pointerEvents: "none",
      }}
    >
      <line x1={x1} y1={y1} x2={x2} y2={y2} stroke={C.line} strokeWidth={2} />
      <line
        x1={x1}
        y1={y1}
        x2={x1 + (x2 - x1) * p}
        y2={y1 + (y2 - y1) * p}
        stroke={color}
        strokeWidth={2}
      />
      {Array.from({ length: particles }, (_, i) => {
        const q = (t - start - i * 0.45) / (end - start);
        return q < 0 || q > 1 ? null : (
          <circle
            key={i}
            cx={x1 + (x2 - x1) * q}
            cy={y1 + (y2 - y1) * q}
            r={5}
            fill={color}
          />
        );
      })}
    </svg>
  );
};
