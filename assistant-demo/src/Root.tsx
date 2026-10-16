import { Composition, Folder } from "remotion";
import { Demo } from "./Demo";
import { Opening } from "./scenes/Opening";
import { Indexing } from "./scenes/Indexing";
import { Rewriting } from "./scenes/Rewriting";
import { Retrieval } from "./scenes/Retrieval";
import { Context } from "./scenes/Context";
import { Response } from "./scenes/Response";
import { Followup } from "./scenes/Followup";

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
    </>
  );
};
