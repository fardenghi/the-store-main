# Proposal

## Why

El chat actual de la UI es solo un system prompt sobre un modelo genérico: no conoce el catálogo, no recuerda turnos anteriores y no razona sobre los productos. El asistente tiene que responder con datos reales del catálogo, mantener el contexto de la conversación y justificar sus comparaciones.

## What Changes

- Endpoint de chat en `assistant` con streaming SSE (`text/event-stream`). La sesión se identifica con el header `X-Session-ID`.
- **Reescritura de consulta** previa al retrieval con un modelo compacto y rápido: `nvidia/nemotron-3.5-lightning-30b-a3b` con thinking desactivado (`chat_template_kwargs.enable_thinking=false`). Para hacer observable la mejora, se registra el top-k obtenido con la consulta cruda y con la reescrita.
- **RAG**: los productos recuperados de Qdrant se inyectan como contexto en el prompt.
- **Modelo principal** con razonamiento: `nvidia/nemotron-3-super-120b-a12b`, con thinking que se puede activar por turno (por ejemplo, en comparaciones).
- **Memoria por sesión**, para que expresiones como "cheaper" o "not a chair" mantengan el contexto del turno anterior.
- **Persona A.G.E.N.T.** migrada desde la configuración de la UI al `assistant`, y ajustada para que el agente venda muebles y deco "para la guarida" en lugar de gadgets.
- Spike inicial de modelos: verificar con las consultas de la demo que tool calling, streaming y thinking funcionan con Spring AI. Plan B: `deepseek-ai/deepseek-v4.1-flash` como modelo principal y `google/gemma-3-12b-it` para la reescritura.
- **Desvío respecto de la pre-entrega:** los modelos pasan de `llama3.2:3b` local a dos modelos de NVIDIA. La pareja "modelo compacto + modelo intermedio con razonamiento" se cumple con dos modelos distintos.

## Capabilities

### New Capabilities
- `assistant-chat`: conversación con el asistente: streaming, reescritura de consulta, respuestas basadas en el catálogo (RAG), memoria por sesión, persona y comparaciones justificadas.

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- `src/assistant`: endpoint SSE, dos `ChatClient` sobre la misma API de NVIDIA (con distinto modelo y `extraBody`), memoria de chat y prompts.
- Hay que verificar que la versión de Spring AI elegida soporte `extraBody` en `OpenAiChatOptions`, porque la `ui` usa la 1.0.0.
- Cuota: cada turno consume entre 2 y 5 requests de NVIDIA, sobre un límite de 40 RPM. El embedding de la consulta va por la cuota de Gemini.
- Casos de uso de la pre-entrega que cubre: reescritura de consulta, comparación justificada y conversación multi-turno.
- Depende de `add-product-indexing`. Desbloquea `add-assistant-tools` e `integrate-ui-assistant`.
