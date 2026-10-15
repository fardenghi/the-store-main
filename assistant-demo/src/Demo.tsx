import { TransitionSeries, linearTiming } from "@remotion/transitions";
import { fade } from "@remotion/transitions/fade";
import { useVideoConfig } from "remotion";
import { Opening } from "./scenes/Opening";
import { Indexing } from "./scenes/Indexing";
import { Rewriting } from "./scenes/Rewriting";
import { Retrieval } from "./scenes/Retrieval";
import { Context } from "./scenes/Context";
import { Response } from "./scenes/Response";
import { Followup } from "./scenes/Followup";
export const Demo = () => {
  const { fps } = useVideoConfig();
  return (
    <TransitionSeries>
      <TransitionSeries.Sequence
        name="01 · Apertura"
        durationInFrames={6 * fps}
        premountFor={fps}
      >
        <Opening />
      </TransitionSeries.Sequence>
      <TransitionSeries.Transition
        presentation={fade()}
        timing={linearTiming({ durationInFrames: 15 })}
      />
      <TransitionSeries.Sequence
        name="02 · Arranque e indexación"
        durationInFrames={14 * fps}
        premountFor={fps}
      >
        <Indexing />
      </TransitionSeries.Sequence>
      <TransitionSeries.Transition
        presentation={fade()}
        timing={linearTiming({ durationInFrames: 15 })}
      />
      <TransitionSeries.Sequence
        name="03 · Primer llamado: reescritura"
        durationInFrames={14 * fps}
        premountFor={fps}
      >
        <Rewriting />
      </TransitionSeries.Sequence>
      <TransitionSeries.Transition
        presentation={fade()}
        timing={linearTiming({ durationInFrames: 15 })}
      />
      <TransitionSeries.Sequence
        name="04 · Búsqueda vectorial"
        durationInFrames={16 * fps}
        premountFor={fps}
      >
        <Retrieval />
      </TransitionSeries.Sequence>
      <TransitionSeries.Transition
        presentation={fade()}
        timing={linearTiming({ durationInFrames: 15 })}
      />
      <TransitionSeries.Sequence
        name="05 · Segundo llamado: contexto"
        durationInFrames={12 * fps}
        premountFor={fps}
      >
        <Context />
      </TransitionSeries.Sequence>
      <TransitionSeries.Transition
        presentation={fade()}
        timing={linearTiming({ durationInFrames: 15 })}
      />
      <TransitionSeries.Sequence
        name="06 · Respuesta"
        durationInFrames={8 * fps}
        premountFor={fps}
      >
        <Response />
      </TransitionSeries.Sequence>
      <TransitionSeries.Transition
        presentation={fade()}
        timing={linearTiming({ durationInFrames: 15 })}
      />
      <TransitionSeries.Sequence
        name="07 · Seguimiento con memoria"
        durationInFrames={13 * fps}
        premountFor={fps}
      >
        <Followup />
      </TransitionSeries.Sequence>
    </TransitionSeries>
  );
};
