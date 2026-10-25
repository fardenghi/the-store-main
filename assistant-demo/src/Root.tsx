import { Composition, Folder } from "remotion";
import { Demo } from "./Demo";
import { Opening } from "./scenes/Opening";
import { Indexing } from "./scenes/Indexing";
import { Rewriting } from "./scenes/Rewriting";
import { Retrieval } from "./scenes/Retrieval";
import { Context } from "./scenes/Context";
import { Response } from "./scenes/Response";
import { Followup } from "./scenes/Followup";
import {
  FPS,
  HEIGHT,
  SETTLE,
  WIDTH,
  deckComponents,
  slides,
} from "./deck/slides";
import { Resources } from "./deck/Resources";

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="TheStore-Assistant"
        component={Demo}
        durationInFrames={1680}
        fps={30}
        width={1920}
        height={1080}
      />
      <Folder name="Escenas">
        <Composition
          id="Apertura"
          component={Opening}
          durationInFrames={120}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Indexacion"
          component={Indexing}
          durationInFrames={330}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Reescritura"
          component={Rewriting}
          durationInFrames={270}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Busqueda"
          component={Retrieval}
          durationInFrames={330}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Contexto"
          component={Context}
          durationInFrames={270}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Respuesta"
          component={Response}
          durationInFrames={180}
          fps={30}
          width={1920}
          height={1080}
        />
        <Composition
          id="Seguimiento"
          component={Followup}
          durationInFrames={270}
          fps={30}
          width={1920}
          height={1080}
        />
      </Folder>
      {/* Slides del deck con sus paradas, para exportar el PPT. Duran lo
          suficiente para renderizar la última parada ya asentada. */}
      <Folder name="Deck">
        {slides.map((slide) => (
          <Composition
            key={slide.id}
            id={`Deck-${slide.id}`}
            component={deckComponents.get(slide.id)!}
            durationInFrames={slide.durationInFrames + (SETTLE + 1) * FPS}
            fps={FPS}
            width={WIDTH}
            height={HEIGHT}
            defaultProps={{ holds: [], stops: slide.stops }}
          />
        ))}
        <Composition
          id="Deck-Recursos"
          component={Resources}
          durationInFrames={3 * FPS}
          fps={FPS}
          width={WIDTH}
          height={HEIGHT}
          defaultProps={{
            repo: "github.com/fardenghi/the-store-main",
            video: "",
          }}
        />
      </Folder>
    </>
  );
};
