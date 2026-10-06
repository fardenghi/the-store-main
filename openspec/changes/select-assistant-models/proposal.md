# Proposal

## Why

Con los 6 changes implementados, el punto débil del asistente pasó a ser el modelo de chat, no el código. `nemotron-3-super-120b-a12b` elige ids equivocados, narra acciones en lugar de llamar a las tools y da resultados distintos entre corridas: `MultiTurnCartSmokeIT` pasó 3/3 en cuatro corridas y 0/3 en la final. `nemotron-3.5-lightning-30b-a3b` tiene una latencia bimodal (1,7 s o ~10 s), y la reescritura cayó al fallback en 22 de 46 turnos. El plan B de CLAUDE.md tampoco sirve: `google/gemma-3-12b-it` da 404 para la cuenta y `deepseek-ai/deepseek-v4.1-flash` no responde. Las presentaciones son el 5 y el 10 de noviembre, así que hace falta elegir los modelos con evidencia medida y dejar un plan B que funcione de verdad.

## What Changes

- **Benchmark reproducible de modelos** sobre los smoke que ya existen en `src/assistant`: `ModelSpikeSmokeIT`, `RewriteEvalSmokeIT`, `ToolsEndToEndSmokeIT`, `ChatEndToEndSmokeIT` y `MultiTurnCartSmokeIT`. Se parametrizan por modelo (principal y de reescritura, con su `extra-body`) y se corren N veces por candidato, reutilizando la colección de Qdrant ya indexada (0 embeddings de indexación).
- **Métricas por candidato:**
  - **Modelo principal:** soporte de streaming, tool calling y thinking por request; tasa de escenarios correctos; tool call correcto en el turno de agregado; producto equivocado; confirmaciones descartadas por la salvaguarda (`claimGuard`); vueltas correctivas; preguntar ante un pedido ambiguo; latencia al primer fragmento (p50/p95) y requests de NVIDIA por turno.
  - **Modelo de reescritura:** JSON válido, latencia p50/p95, tasa de fallback con el timeout vigente y el top-5 de `RewriteEvalSmokeIT`.
- **Candidatos iniciales,** todos en `integrate.api.nvidia.com`; la lista final se confirma leyendo `/v1/models` y descartando los que den 404 para la cuenta:
  - **Principal:** `nvidia/nemotron-3-super-120b-a12b` (línea base), `nvidia/nemotron-3-ultra-550b-a55b`, `openai/gpt-oss-20b`, `google/gemma-4-31b-it` y un reintento de `deepseek-ai/deepseek-v4.1-flash`.
  - **Reescritura:** `nvidia/nemotron-3.5-lightning-30b-a3b` (línea base), `nvidia/nemotron-nano-3-30b-a3b`, `google/gemma-3-4b-it` y `openai/gpt-oss-20b`.
- **Decisión documentada:** una tabla comparativa con un criterio de elección explícito. La corrección del flujo de carrito pesa más que la latencia, y la latencia más que el costo en requests. Se elige un modelo principal y uno de reescritura, más un plan B verificado para cada uno.
- **Nuevos defaults:** se actualizan el `application.yml`, el ConfigMap de `dist/kubernetes.yaml`, el README del `assistant` y las decisiones cerradas de CLAUDE.md con los modelos elegidos y el plan B. Si gana la línea base, solo se actualiza el plan B.
- **Desvío respecto de la pre-entrega:** ninguno nuevo. El proveedor sigue siendo NVIDIA, autorizado por la cátedra; solo cambian los identificadores de modelo dentro de la misma API.

## Capabilities

### New Capabilities
- `assistant-model-evaluation`: benchmark reproducible de modelos de chat del `assistant` (candidatos, métricas, cantidad de corridas, consumo de cuota acotado) y registro de la decisión con su evidencia.

### Modified Capabilities
- `assistant-service`: cambian los escenarios "Valores por defecto" y "Cambio de modelo principal al plan B" del requirement "Configuración de proveedores sin reconstruir la imagen", para reflejar los modelos elegidos y un plan B verificado.
- `assistant-chat`: cambia el escenario "Cambio al plan B" del requirement "Consumo acotado del proveedor de chat", para usar el plan B verificado.

## Impact

- `src/assistant`: la parametrización por modelo de los smoke, un runner del benchmark que corre N veces y agrega las métricas a partir de las líneas `assistant.turn`, y los nuevos defaults en `application.yml`.
- `dist/kubernetes.yaml` (ConfigMap `assistant`), `src/assistant/README.md` y `docs/arquitectura.md`.
- `CLAUDE.md`: la sección "Decisiones cerradas" (modelos y plan B). Es la única modificación de ese archivo y la justifica la evidencia del benchmark.
- **Cuota:**
  - **NVIDIA** (40 RPM): es la que más se consume. Las corridas son secuenciales y respetan el limitador de 36 RPM.
  - **Gemini:** solo los embeddings de las consultas. La colección se reutiliza, sin reindexar.
  - **Presupuesto:** se fija en el design y no puede superarse.
- **Depende de** los changes ya archivados `add-assistant-chat`, `add-assistant-tools` e `integrate-ui-assistant`.
