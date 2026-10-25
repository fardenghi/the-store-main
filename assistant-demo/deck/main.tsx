import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { createRoot } from "react-dom/client";
import { Player, PlayerRef } from "@remotion/player";
import { Hold } from "../src/clock";
import {
  DeckProps,
  FPS,
  HEIGHT,
  PLAYER_SECONDS,
  SETTLE,
  WIDTH,
  deckComponents,
  slides,
} from "../src/deck/slides";

const NEXT_KEYS = new Set([
  "ArrowRight",
  "ArrowDown",
  "PageDown",
  " ",
  "Enter",
]);
const PREV_KEYS = new Set(["ArrowLeft", "ArrowUp", "PageUp", "Backspace"]);

// Dónde está el deck: la slide, la parada abierta (la que se espera ahora) y
// cuánto duró cada parada anterior de esa slide. `seek` pide reposicionar el
// Player al montar o al volver atrás.
type Position = {
  slide: number;
  stop: number;
  lengths: number[];
  seek: number | null;
};

const at = (slide: number, stop: number, settled: boolean): Position => ({
  slide,
  stop,
  lengths: Array.from({ length: stop }, () => 0),
  // Al volver atrás se muestra la parada con los recorridos ya terminados.
  seek: settled ? slides[slide].stops[stop] + SETTLE : 0,
});

const fromHash = (): Position => {
  const n = Number.parseInt(window.location.hash.replace(/^#\/?/, ""), 10);
  const slide = Number.isFinite(n)
    ? Math.min(Math.max(n - 1, 0), slides.length - 1)
    : 0;
  return at(slide, 0, false);
};

// Tiempo real (del Player) en el que la escena llega a la parada abierta.
const reachesStop = (pos: Position) =>
  slides[pos.slide].stops[pos.stop] + pos.lengths.reduce((a, b) => a + b, 0);

const Deck: React.FC = () => {
  const [pos, setPos] = useState<Position>(fromHash);
  const [hud, setHud] = useState(false);
  // Chrome deja suspendido el reloj del Player (un AudioContext) hasta que el
  // usuario interactúa con la página: sin interacción, la primera tecla solo
  // arranca la animación.
  const [started, setStarted] = useState(
    () => navigator.userActivation?.hasBeenActive ?? true,
  );
  const ref = useRef<PlayerRef>(null);
  const slide = slides[pos.slide];

  const holds = useMemo<Hold[]>(
    () =>
      slide.stops.slice(0, pos.stop + 1).map((s, i) => ({
        at: s,
        length: i < pos.stop ? pos.lengths[i] : null,
      })),
    [slide, pos.stop, pos.lengths],
  );
  const inputProps = useMemo<DeckProps>(() => ({ holds }), [holds]);

  useEffect(() => {
    const hash = `#/${pos.slide + 1}`;
    if (window.location.hash !== hash) {
      window.history.replaceState(null, "", hash);
    }
  }, [pos.slide]);

  // Al montar una slide o volver atrás: posicionar y reproducir.
  useEffect(() => {
    const player = ref.current;
    if (!player || pos.seek === null) return;
    player.seekTo(
      Math.round((started ? pos.seek : reachesStop(pos) + SETTLE) * FPS),
    );
    if (started) {
      player.play();
    } else {
      player.pause();
    }
    setPos((p) => ({ ...p, seek: null }));
  }, [pos, started]);

  const now = () => (ref.current?.getCurrentFrame() ?? 0) / FPS;

  const next = useCallback(() => {
    if (!started) {
      // Primer gesto del usuario: play() dentro del evento reanuda el reloj.
      setStarted(true);
      ref.current?.play();
      return;
    }
    const target = reachesStop(pos);
    const elapsed = now();
    if (elapsed < target) {
      // Todavía animando hacia la parada: saltar a ella.
      ref.current?.seekTo(Math.round(target * FPS));
      return;
    }
    if (pos.stop < slide.stops.length - 1) {
      setPos({
        ...pos,
        stop: pos.stop + 1,
        lengths: [...pos.lengths, elapsed - target],
      });
    } else if (pos.slide < slides.length - 1) {
      setPos(at(pos.slide + 1, 0, false));
    }
  }, [pos, slide, started]);

  const prev = useCallback(() => {
    if (pos.stop > 0) {
      setPos(at(pos.slide, pos.stop - 1, true));
    } else if (pos.slide > 0) {
      setPos(at(pos.slide - 1, slides[pos.slide - 1].stops.length - 1, true));
    }
  }, [pos]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (NEXT_KEYS.has(e.key)) {
        e.preventDefault();
        next();
      } else if (PREV_KEYS.has(e.key)) {
        e.preventDefault();
        prev();
      } else if (e.key === "Home") {
        setPos(at(0, 0, false));
      } else if (e.key === "f") {
        if (document.fullscreenElement) document.exitFullscreen();
        else document.documentElement.requestFullscreen();
      } else if (e.key === "h") {
        setHud((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [next, prev]);

  // Solo en desarrollo, para inspeccionar el deck desde la consola.
  useEffect(() => {
    if (import.meta.env.DEV) {
      (window as unknown as { deck?: unknown }).deck = {
        player: ref.current,
        pos,
        holds,
      };
    }
  });

  return (
    <div
      onClick={next}
      onContextMenu={(e) => {
        e.preventDefault();
        prev();
      }}
      style={{ position: "fixed", inset: 0 }}
    >
      {/* key: cada slide monta su propio Player. */}
      <Player
        key={slide.id}
        ref={ref}
        component={deckComponents.get(slide.id)!}
        inputProps={inputProps}
        durationInFrames={PLAYER_SECONDS * FPS}
        fps={FPS}
        compositionWidth={WIDTH}
        compositionHeight={HEIGHT}
        controls={false}
        clickToPlay={false}
        doubleClickToFullscreen={false}
        spaceKeyToPlayOrPause={false}
        moveToBeginningWhenEnded={false}
        acknowledgeRemotionLicense
        style={{ width: "100%", height: "100%" }}
      />
      {hud && (
        <div
          style={{
            position: "fixed",
            left: 16,
            bottom: 12,
            padding: "6px 12px",
            borderRadius: 8,
            background: "#000a",
            color: "#d4f77d",
            font: "14px 'Helvetica Neue', Helvetica, Arial, sans-serif",
          }}
        >
          {pos.slide + 1}/{slides.length} · paso {pos.stop + 1}/
          {slide.stops.length} · {slide.title}
          {!started && " · presioná una tecla para empezar"}
        </div>
      )}
    </div>
  );
};

createRoot(document.getElementById("root")!).render(<Deck />);
