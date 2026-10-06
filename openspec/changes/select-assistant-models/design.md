# Design

## Context

El motivo y el alcance están en `proposal.md` ("Why" y "What Changes"); los requirements, en `specs/assistant-model-evaluation/spec.md`. Este documento fija cómo se mide, con qué umbrales, con cuánta cuota y cómo se registra la decisión.

Estado de partida:

- **Modelos actuales.** `nvidia/nemotron-3-super-120b-a12b` (principal) y `nvidia/nemotron-3.5-lightning-30b-a3b` (reescritura), los dos con `{"chat_template_kwargs": {"enable_thinking": true|false}}`. Se cambian sin reconstruir la imagen: `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`, `RETAIL_ASSISTANT_MODELS_REWRITE` y los `extra-body` vía `SPRING_APPLICATION_JSON` (README del `assistant`, "Razonamiento y plan B").
- **Smoke existentes** (`src/assistant/src/test/java/.../smoke/`):
  - `ModelSpikeSmokeIT` ya acepta `-Dspike.main-model`, `-Dspike.main-on`, `-Dspike.main-off`, `-Dspike.rewrite-model` y `-Dspike.rewrite-extra`.
  - `ChatEndToEndSmokeIT`, `ToolsEndToEndSmokeIT` y `MultiTurnCartSmokeIT` toman los modelos de `application.yml` (se pueden pisar con `-D` de Spring, porque las propiedades del sistema le ganan al YAML) y reutilizan la colección con `-Dsmoke.qdrant.*`. Espacian los turnos (4 s y 7 s) para quedar debajo de 36 RPM.
  - `RewriteEvalSmokeIT` **siempre** indexa en un Qdrant de Testcontainers: 80 embeddings por corrida. Hay que darle `-Dsmoke.qdrant.*`.
- **Medición disponible.** La línea `assistant.turn` trae `rewrite`, `rewriteMs`, `retrievalMs`, `limiterWaitMs`, `firstFragmentMs` (desde el inicio del turno), `totalMs`, `reasoning`, `nvidiaRequests`, `modelCalls`, `tools`, `claimGuard` y `corrections`. La línea `assistant.tool` trae cada tool con su resultado.
- **Límites.** El tiempo máximo de la reescritura es de 12 s. El `max-tokens` de la reescritura es fijo en 256 (`ChatConfiguration`). El modelo principal usa 1024 o 4096 con razonamiento. El limitador permite 36 requests/min por proceso; la cuota de NVIDIA es de 40 RPM, y la de Gemini, de 100 RPM y 1.000 requests por día.
- **Sondeo previo de `meta/muse-glimmer-30b`.** Respondió en 1,1 s, pero con `max_tokens` 8 devolvió `content=null`: el thinking viene prendido por defecto y se consumió los tokens.

### Línea base registrada (no se vuelve a medir)

Sale de los `design.md` archivados (`add-assistant-chat`, `add-assistant-tools`) y del README del `assistant`, todo del 2026-10-06.

| Rol | Métrica | Línea base | Fuente |
| --- | --- | --- | --- |
| Principal | Streaming, tool calling en streaming, thinking on/off por request, `tool_choice` `none` y `required` | Todo OK; razonamiento en `reasoning_content` | Spike D12 y `controlledToolCalling` |
| Principal | Primer fragmento del modelo (spike) | 1,3 s sin razonamiento y 2,5 s con razonamiento | Spike |
| Principal | Primer fragmento de una comparación en el cluster | 18 s | README, "Verificación en el cluster" |
| Principal | `MultiTurnCartSmokeIT`, versión entregada | 3/6 sesiones (corrida 9: 3/3; final: 0/3, con un corte `llm-provider-unavailable` y dos ids equivocados) | `add-assistant-tools`, "Evidencia" |
| Principal | Confirmaciones falsas mostradas al usuario | 0 desde la salvaguarda | Ídem |
| Principal | Producto equivocado agregado (versión entregada) | 0 (los ids equivocados dieron `product-not-found` y no agregaron) | Ídem |
| Principal | Pedido ambiguo: agrega sin preguntar | 1/5 | `add-assistant-tools`, "Limitación conocida" |
| Principal | `ChatEndToEndSmokeIT` | 10/10 en la corrida registrada; inestables: `cheaper`, `notALamp`, `greetingDoesNotSearch` y `justifiedComparisonWithReasoning` (solo con `rewrite=fallback`), y `answersInTheUserLanguage` (en castellano 1/5 y 2/5) | README y `add-assistant-tools` |
| Principal | `ToolsEndToEndSmokeIT` | 8/8 con los prompts finales; inestables: `stalePayloadPriceIsNotShown` y `ambiguousRequestAsksInsteadOfAdding` | Ídem |
| Principal | Requests a NVIDIA por turno | 2 sin tools, 3 con una tool; 3,1 de media en 10 turnos paralelos | README, "Tools en el cluster" |
| Reescritura | JSON válido | 9/10 (el restante fue un timeout de red) | Spike |
| Reescritura | Latencia | Mediana 1,5 s (spike). Bimodal: 9,8 / 1,6 / 8,9 / 1,7 / 1,7 s, con p95 ≈ 10 s | Spike y D4 de `add-assistant-chat` |
| Reescritura | Fallback con 12 s | 2/15 (13 %) en el e2e de chat; 4/33 (12 %) en tres corridas de tools; 22/46 (48 %) en la hora de la corrida final | README y `add-assistant-tools` |
| Reescritura | Top-5 de `RewriteEvalSmokeIT` | 45, 43 y 47 (media 45) contra 42 de la consulta cruda | README |

## Goals / Non-Goals

**Goals:**

- Fijar umbrales numéricos por rol **antes** de la primera corrida (D2 y D3).
- Medir `meta/muse-glimmer-30b` en los dos roles con un benchmark que se pueda repetir, que sume las métricas del log y que se frene solo al llegar al presupuesto de cuota.
- Dejar la decisión, los parámetros del modelo y un plan B verificado en el README, en este design y en la configuración versionada.

**Non-Goals:**

- Evaluar otros candidatos (`kimi-k3`, `glm-5.3`, etc.). Si `muse-glimmer-30b` no pasa en un rol, el change termina reportando y el grupo elige el siguiente candidato en otro change o en una extensión de este.
- Volver a medir la línea base, salvo que falte algo para comparar (con los umbrales elegidos no falta nada; ver D2).
- Cambiar prompts, la salvaguarda o el ciclo de tools para favorecer al candidato. Se mide el `assistant` tal como está. Si el candidato necesita otro prompt, no pasa y se reporta.
- Hedged requests o cambios en el tiempo límite de la reescritura.

## Decisions

### D1. Orden de la evaluación: primero la reescritura, después el principal

1. **Sondeo de parámetros** (D4): cómo se apaga y se prende el thinking de `muse-glimmer-30b`, con un timeout de descarte.
2. **Spike** (`ModelSpikeSmokeIT` completo y `#controlledToolCalling`), con el candidato en los dos roles. Es la puerta de capacidades del principal (M1) y deja 10 muestras de latencia y JSON de la reescritura.
3. **Reescritura:** `RewriteEvalSmokeIT` ×3, con el modelo principal sin cambios (no participa). Se decide el rol con R1 a R4.
4. **Principal:** `ChatEndToEndSmokeIT` ×3, `ToolsEndToEndSmokeIT` ×3 más 2 corridas del escenario ambiguo, y `MultiTurnCartSmokeIT` ×5, con `muse` como principal y, como reescritura, **la que ganó en el paso 3**. Se decide el rol con M1 a M8.
5. **Registro y defaults** (D7).

Así el e2e mide la configuración que va a quedar. Además, la reescritura se decide con lo más barato (unas 40 requests) antes de gastar el grueso de la cuota en el principal.

- **Parada temprana.** Si el spike falla M1, el paso 4 no se corre. Si en el paso 4 aparece una confirmación falsa (M2) o un producto equivocado (M3), o si M4 ya no se puede alcanzar con las sesiones que faltan, se cortan las corridas del principal y se registra lo medido.
- **Alternativa descartada: medir primero el principal con la reescritura actual.** El e2e quedaría medido con la reescritura bimodal, cuyos fallbacks ya se sabe que voltean `cheaper`, `notALamp`, el saludo y la comparación. Habría que volver a correrlo si la reescritura cambia.
- **Alternativa descartada: medir cada rol con el otro fijo en Nemotron.** Duplica las corridas e2e y mide una combinación que, si `muse` gana los dos roles, no va a existir.

### D2. Criterio de aceptación del modelo principal

Se adopta solo si cumple **todas** las condiciones. "Corridas de evaluación" son las del paso 4 de D1.

| Id | Métrica | Umbral | Línea base | Fuente de la medición |
| --- | --- | --- | --- | --- |
| M1 | Capacidades | Streaming, tool calls completos en el stream, segunda vuelta con el resultado, `tool_choice: none` y `required` respetados, thinking on/off por request y razonamiento fuera del texto (o en `<think>`, que se filtra con `strip-think-tags=true`) | Todo OK | Spike, 1 corrida |
| M2 | Confirmaciones falsas mostradas al usuario | **0** en todas las corridas | 0 | Chequeo `ADD_CLAIM` de los smoke sobre el texto recibido |
| M3 | Producto equivocado agregado | **0** | 0 | `cart-updated` y el carrito falso contra el producto pedido |
| M4 | Sesiones de `MultiTurnCartSmokeIT` con el producto pedido en el carrito | **≥ 10/15** (5 corridas × 3 sesiones, 67 %) | 3/6 (50 %) | Smoke |
| M5 | Pedido ambiguo: agrega sin preguntar | **≤ 1/5** | 1/5 | `ambiguousRequestAsksInsteadOfAdding`, 3 corridas completas + 2 sueltas |
| M6 | Escenarios estables | Pasan en **3/3** corridas (ver la lista abajo) | Pasan | Smoke |
| M7 | Latencia del modelo al primer fragmento, `firstFragmentMs − rewriteMs − retrievalMs − limiterWaitMs` | Turnos con `reasoning=off`: **p50 ≤ 3 s y p95 ≤ 8 s**. Turnos con `reasoning=on`: **máximo ≤ 30 s** | 1,3 s (spike) y 18 s en una comparación | Líneas `assistant.turn` de todas las corridas |
| M8 | Requests a NVIDIA por turno | Media **≤ 3,5** | 3,1 | `nvidiaRequests` |

Se informan sin umbral, comparados con la línea base cuando existe: `claimGuard` descartados, `corrections` por motivo, tasa de aprobación de los escenarios inestables y `firstFragmentMs` completo (lo que ve el usuario).

**Escenarios estables (M6):**

- `ChatEndToEndSmokeIT`: `recommendationWithRealProducts`, `productTheStoreDoesNotSell`, `referenceToThePreviousTurnAndIsolatedSessions`, `simpleSearchWithoutReasoning` y `doesNotRevealTheSystemPrompt`.
- `ToolsEndToEndSmokeIT`: `priceRightNowComesFromGetProductDetails`, `lampsUnder100CheapestFirst`, `diningTableUnder300InSpanish`, `addThatOneToMyCart`, `addTwoOfTheFirstOne` y `cartsDownIsNotConfirmed`.

Los inestables (`cheaper`, `notALamp`, `greetingDoesNotSearch`, `justifiedComparisonWithReasoning`, `answersInTheUserLanguage` y `stalePayloadPriceIsNotShown`) se reportan con su tasa contra la línea base, pero no bloquean: hoy ya fallan por la reescritura o por el idioma.

**Fallas del proveedor:**

- Un escenario o una sesión que termina con `llm-provider-unavailable` cuenta como falla para M4 y M6, porque en la demo también lo sería.
- M6 admite **un** re-intento de ese escenario por corrida, si la falla fue del proveedor y no del modelo. Queda registrado y sale de la reserva de cuota.
- Un 429 propio (por pasarse de la cuota) invalida la corrida, que se repite.

**Por qué estos números:**

- **M4.** 10/15 es más exigente que el 50 % de la línea base, y con 15 sesiones deja margen sobre una muestra base de 6. El 80 % (12/15) se descartó: rechazaría a un candidato claramente mejor que Nemotron, y el criterio del proposal es "al menos tan correcto como la línea base".
- **M7.** Las latencias se miden sin la reescritura ni el limitador, para no castigar al principal por la reescritura (que se juzga en R2). 3 s y 8 s dejan la respuesta dentro de los 20 s del timeout al primer fragmento, con margen para una o dos vueltas de tools. Los 30 s de las comparaciones quedan por debajo de los 60 s del timeout con razonamiento.
- **M8.** 3,5 de media permite unos 10 turnos por minuto dentro del limitador de 36.
- **Sin re-medición de la línea base.** Todos los umbrales son valores absolutos anclados en números ya registrados, así que no hace falta volver a medir Nemotron. Se descartó medir el p50/p95 de `firstFragmentMs` de Nemotron (unas 200 requests más) porque M7 se compara con un límite de demo, no con Nemotron.

### D3. Criterio de aceptación del modelo de reescritura

Se mide sobre las 40 llamadas del paso 2 y del paso 3 (10 del spike y 3 × 10 de `RewriteEvalSmokeIT`), con el razonamiento apagado y el timeout vigente de 12 s.

| Id | Métrica | Umbral | Línea base |
| --- | --- | --- | --- |
| R1 | JSON válido (parsea y pasa la validación de D4 de `add-assistant-chat`) | **Como máximo 1 salida inválida** en las 40 llamadas que respondieron | 9/10 |
| R2 | Latencia | **p50 < 1,5 s y p95 ≤ 5 s** | Mediana 1,5 s; p95 ≈ 10 s (bimodal) |
| R3 | Fallback (timeout, error o JSON inválido) | **≤ 2/40 (5 %)** | 12 % a 13 % en las mejores corridas; 48 % en la peor hora |
| R4 | Top-5 de `RewriteEvalSmokeIT` | **Media ≥ 45** en 3 corridas, y **cada corrida > 42** (la cruda) | 45, 43 y 47 contra 42 |

- **Confirmación en el e2e.** Si la reescritura se adopta, las corridas del paso 4 la usan. Si ahí el fallback total supera el 10 % de los turnos, la adopción se reporta al grupo antes de cambiar los defaults (task 5.4), porque contradiría R3 con más muestras.
- **Por qué estos números.** R2 y R3 exigen ser mejor que la línea base, que es lo que pidió el grupo. El p95 ≤ 5 s es la mitad del p95 actual, y deja todos los turnos bastante debajo del timeout de 12 s. R4 conserva el requirement "Mejora observable de la reescritura" (la reescrita le gana a la cruda) y no acepta quedar por debajo de la media actual.

### D4. Parámetros del candidato: sondeo del thinking y timeout de descarte

**Fuente sin costo.** Primero se lee la ficha del modelo en build.nvidia.com (la referencia de la API y el ejemplo de request), por si documenta el campo que apaga el razonamiento. Si lo documenta, esa variante se prueba primero.

**Sondeo.** Es un caso nuevo de `ModelSpikeSmokeIT` (`#thinkingProbe`), con HTTP crudo como el de `quotaBurst`, para ver `reasoning_content` tal como llega. Cada variante es **una** request con el prompt "Reply with exactly: OK", sin streaming. Por cada variante se loguea el status HTTP, la latencia, `content`, el largo de `reasoning_content`, si aparece `<think>`, `finish_reason` y `usage.completion_tokens`. Se frena en la primera variante que cumpla el criterio.

- **Variantes para apagar el thinking** (`max_tokens` 64), en este orden:
  1. La documentada en la ficha, si la hay.
  2. `{"chat_template_kwargs": {"enable_thinking": false}}` (estilo Nemotron/Qwen).
  3. `{"chat_template_kwargs": {"thinking": false}}` (estilo DeepSeek).
  4. `{"reasoning_effort": "none"}`.
  5. `{"thinking": {"type": "disabled"}}`.
- **Criterio para "apagado":** `content` trae "OK", `reasoning_content` está vacío o ausente, no hay `<think>`, `finish_reason=stop` y se usan menos de 20 tokens de salida.
- **Variante para prenderlo** (rol principal): la inversa de la ganadora, con `max_tokens` 1024. Se acepta si el razonamiento llega en `reasoning_content`; si llega dentro del texto como `<think>`, se acepta igual con `strip-think-tags=true`.
- **Presupuesto del sondeo:** como máximo 12 requests, contando los reintentos por timeout.

**Timeout de descarte: 30 s por request.** Está por encima de los 12 s de la reescritura y de los 20 s al primer fragmento del principal, así que no descarta a un modelo que igual se podría usar. Y corta antes que los 88 a 120 s que se vieron en el sondeo previo con otros modelos.

- Si la primera request no responde en 30 s, se reintenta una vez después de 60 s. Si vuelve a vencer, el candidato queda **descartado en los dos roles**.
- Un `404`/`403` ("Not found for account") lo descarta en el acto.
- En el spike: si la mediana de la reescritura supera los 12 s, se descarta el rol de reescritura sin correr `RewriteEvalSmokeIT`.

**Si ninguna variante apaga el thinking:**

- **Reescritura:** solo se puede evaluar con thinking. Eso requiere que el `max-tokens` de la reescritura sea configurable (hoy es fijo en 256; la task 1.6 es condicional). Se mide con R1 a R4 sin cambios; con el razonamiento, lo esperable es que falle R2.
- **Principal:** se evalúa con `reasoning.mode=always` y `max-tokens` igual a `max-tokens-reasoning`, con los mismos umbrales.
- En los dos casos se deja registrado que el thinking no se pudo apagar.

### D5. Corridas por prueba y presupuesto de cuota

**Corridas:**

- **1** de spike y de `controlledToolCalling`: verifican capacidades, que no varían entre corridas.
- **3** de `RewriteEvalSmokeIT`, `ChatEndToEndSmokeIT` y `ToolsEndToEndSmokeIT`: las mismas 3 corridas que tiene la línea base de la reescritura, y suficientes para distinguir un escenario estable de uno inestable.
- **5** del escenario ambiguo (3 dentro de las corridas completas y 2 sueltas): la misma muestra que la línea base de 1/5.
- **5** de `MultiTurnCartSmokeIT`: es la métrica que motiva el change (15 sesiones).

Las corridas son **secuenciales**: una sola JVM a la vez, con los espaciados que ya tienen los smoke (≤ 4 requests cada 7 s, unas 34 RPM) y una pausa de 60 s entre corridas, para que la ventana del limitador y la de NVIDIA arranquen vacías.

**Requests a NVIDIA:**

| Etapa | Corridas | Requests por corrida | Total |
| --- | --- | --- | --- |
| Sondeo (D4) | 1 | ≤ 12 | 12 |
| `ModelSpikeSmokeIT` + `#controlledToolCalling` | 1 | 15 + 3 | 18 |
| `RewriteEvalSmokeIT` | 3 | 10 | 30 |
| `ChatEndToEndSmokeIT` | 3 | ~30 | 90 |
| `ToolsEndToEndSmokeIT` | 3 | ~40 | 120 |
| Escenario ambiguo suelto | 2 | ~5 | 10 |
| `MultiTurnCartSmokeIT` | 5 | ~35 | 175 |
| Verificación en el cluster con los defaults nuevos y un turno con el plan B | 1 | ~13 | 13 |
| Reserva: ensayo del pipeline, re-intentos por fallas del proveedor y corridas invalidadas por un 429 | — | — | 32 |
| **Tope** | | | **500** |

**Requests de embeddings a Gemini** (consultas; 0 de indexación):

| Etapa | Total |
| --- | --- |
| `RewriteEvalSmokeIT` ×3 (10 crudas + 10 reescritas) | 60 |
| `ChatEndToEndSmokeIT` ×3 (turnos con búsqueda; `compare-raw-retrieval=false`) | ~45 |
| `ToolsEndToEndSmokeIT` ×3 + 2 sueltas | ~50 |
| `MultiTurnCartSmokeIT` ×5 | ~60 |
| Reserva | ~35 |
| **Tope** | **250** |

Con 250 queda un 75 % de la cuota diaria (1.000) para desarrollo y demo. La colección se reutiliza siempre (`-Dsmoke.qdrant.*`); la sincronización de cada arranque tiene que dar `80 sin cambios, 0 requests`, y si no, la corrida se aborta.

**Control del presupuesto.** El runner (D6) lleva un registro acumulado (`target/model-bench/ledger.tsv`) con las requests de cada corrida, sumadas de `nvidiaRequests` de las líneas `assistant.turn` (o del conteo fijo del spike y del sondeo) y de los embeddings de consulta. Antes de cada corrida, compara lo consumido más la estimación de la tabla contra el tope, y no la arranca si se pasaría (requirement "Consumo de cuota acotado").

- **Alternativa descartada: N=5 en todos los smoke.** Serían unas 780 requests a NVIDIA, y para los escenarios que no son de carrito no cambia la decisión: un escenario estable que falla 1 de 3 ya es una regresión.

### D6. Benchmark: parametrización, runner y reporte

**Parametrización sin tocar los defaults.** El runner pisa estas propiedades con `-D`:

- `spring.ai.openai.chat.options.model`
- `retail.assistant.models.rewrite`
- `spring.application.json`, con los tres `extra-body`: el mapa definido reemplaza completo al default de `ChatProperties`, como en el ConfigMap.

Para el spike traduce lo mismo a `-Dspike.*`. Los smoke ya propagan los `-D` a la JVM de failsafe (así funciona hoy `-Dsmoke.qdrant.host`). A `RewriteEvalSmokeIT` se le agrega `-Dsmoke.qdrant.*`, igual que a los otros, y con la colección reutilizada no indexa.

**Salida parseable de los smoke.** Cada escenario o sesión imprime una línea `bench.result` con:

- `smoke`, `scenario` y `outcome` (`pass`, `fail`, `provider-error`);
- para el carrito, `added=<ok|none|wrong>`;
- `falseClaims=<n>`, de `ADD_CLAIM` y `NOT_A_CLAIM`, el chequeo propio del test, independiente del filtro del `assistant`;
- para el ambiguo, `askedInsteadOfAdding=<true|false>`.

`RewriteEvalSmokeIT` imprime una línea `bench.rewrite` por consulta (`ok`, `fallback` o `invalid`, latencia y aciertos del top-5 crudo y reescrito). El spike imprime una por capacidad.

**Runner (`src/assistant/scripts/model-bench.sh`):**

```bash
scripts/model-bench.sh --smoke MultiTurnCartSmokeIT --runs 5 \
  --main meta/muse-glimmer-30b --main-on '<json>' --main-off '<json>' \
  --rewrite meta/muse-glimmer-30b --rewrite-extra '<json>' \
  --qdrant localhost:6334/products
```

- Corre `./mvnw -Psmoke verify -Dit.test=<smoke>[#método]` N veces, de forma secuencial, con la pausa de D5.
- Guarda la salida de cada corrida en `target/model-bench/<fecha>-<smoke>-<i>.log`.
- Actualiza el ledger y frena antes de pasarse del tope.

**Reporte (`src/assistant/scripts/model_bench_report.py`).** Usa solo la biblioteca estándar, como `scripts/catalog-data/generate.py`.

- Lee los `.log` y parsea las líneas `assistant.turn`, `bench.result` y `bench.rewrite`.
- Imprime en Markdown una tabla por corrida y otra total, con M1 a M8 y R1 a R4: valor, umbral, línea base y ✔/✘.
- Los percentiles se calculan con el método nearest-rank. Se prueba con logs de ejemplo, sin llamar a ningún proveedor.

**Alternativas descartadas:**

- **Un runner en Java dentro de los tests.** Mezcla la orquestación de N corridas (JVMs separadas, pausas) con los tests, y el parseo de logs es más simple en un script.
- **Correr las N iteraciones dentro de una misma JVM.** Compartiría el limitador y el caché de embeddings, y abarataría Gemini. Pero cambiaría el comportamiento de los smoke, que hoy asumen una sesión por corrida, y la memoria de chat quedaría compartida entre corridas.

### D7. Registro de la decisión y nuevos defaults

**README del `assistant`.** Se agrega la sección "Selección de modelos (`select-assistant-models`)" con una tabla por rol:

| Fecha | Rol | Candidato | `extra-body` off / on | Métrica | Umbral | Línea base | Candidato | ✔/✘ |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

La tabla cierra con una fila de **resultado**: adoptado, no adoptado o descartado, y en los dos últimos casos, el motivo.

**Otros cambios en el README:**

- Una fila nueva en "Modelos probados".
- La sección "Razonamiento y plan B", con el ejemplo del ConfigMap del plan B real: los Nemotron y `enable_thinking`.
- Los defaults de la tabla de configuración.

**Este design.** Se agrega la sección "Resultados de la evaluación" con las mismas tablas, el consumo final del ledger (NVIDIA y Gemini contra los topes) y las observaciones cualitativas (modos de falla vistos, como en "Correcciones posteriores" de `add-assistant-tools`). Los `.log` de `target/` no se versionan; las tablas son la evidencia.

**Defaults** (solo en los roles adoptados):

- `application.yml`: el modelo y, si el `extra-body` del candidato no es el de Nemotron, los defaults de `ChatProperties` de ese rol.
- `dist/kubernetes.yaml` (ConfigMap `assistant`): el modelo y `SPRING_APPLICATION_JSON` si hace falta.
- `docs/arquitectura.md`: el diagrama y la tabla de desvíos.
- `CLAUDE.md`, "Decisiones cerradas": modelos y planes B.

**Plan B.** En cada rol adoptado, el plan B es el Nemotron que se reemplazó, con `enable_thinking`, ya verificado por el spike de `add-assistant-chat` y las corridas de `add-assistant-tools`. En un rol no adoptado, el modelo actual sigue como default. El plan B de ese rol queda **pendiente del grupo** y se documenta así: `deepseek` y `gemma-3-12b` se sacan del README y de CLAUDE.md, porque no funcionan con la cuenta, y no se reemplazan por un modelo sin verificar.

**Specs.** Las deltas de `assistant-service` y `assistant-chat` están escritas para el resultado esperado: `muse` en los dos roles y los Nemotron como plan B. La task 6.1 las ajusta al resultado real:

- **Solo uno de los dos roles adoptado:** los defaults conservan el modelo actual en el otro rol, y el plan B de ese rol queda sin escenario.
- **Ningún rol adoptado:** los defaults no cambian, y el escenario "Cambio al plan B" se reescribe sin modelos sin verificar.

### D8. Relación con la pre-entrega

El proveedor sigue siendo NVIDIA, autorizado por la cátedra, y solo cambian identificadores de modelo. La pre-entrega anticipa esta etapa: "se va a experimentar con diferentes modelos para generación, razonamiento y tool calling, para luego determinar qué modelo queda en producción" (sección de arquitectura).

Si `muse-glimmer-30b` gana los dos roles, la pareja "modelo compacto con RAG, modelo intermedio con razonamiento" (sección 3) pasa a ser **un mismo modelo en dos roles**:

- en la reescritura, sin razonamiento y con salida corta (el papel del compacto);
- en las respuestas, con razonamiento en las comparaciones (el papel del intermedio).

Los dos roles, sus prompts y su configuración siguen separados, así que se puede volver a dos modelos cambiando una variable. Se documenta en la tabla de desvíos de `docs/arquitectura.md` (la fila que hoy dice "se mantiene la pareja compacto + modelo con razonamiento") y en este design, con la evidencia del benchmark como justificación.

## Risks / Trade-offs

- **[Variabilidad de NVIDIA entre horas]** (la corrida final de `add-assistant-tools` dio 0/3 con 48 % de fallback en la reescritura) → Las corridas del principal se reparten en al menos dos franjas horarias. Cada corrida guarda su hora en el ledger. Las fallas del proveedor se registran aparte de las del modelo (`provider-error`).
- **[Muestra chica: 15 sesiones y 5 ambiguos]** → Los umbrales se fijan antes, y con esta muestra se pide un margen sobre la línea base (M4 en 67 % contra 50 %), no una diferencia estadísticamente significativa. Se acepta, por la fecha de las presentaciones y por la cuota.
- **[El candidato pasa los smoke pero falla en la demo]** → El plan B queda a un cambio de ConfigMap, con los valores exactos en el README. La verificación en el cluster (task 6.4) corre una conversación de tres turnos con los defaults nuevos.
- **[Los prompts están afinados para Nemotron]** → Es una desventaja para el candidato, aceptada: cambiar prompts mezclaría dos variables. Si falla por un modo de falla que un prompt corregiría, se registra como observación para el grupo.
- **[Thinking imposible de apagar]** → Lo cubre D4. Cuesta la task condicional 1.6 y, probablemente, el rol de reescritura.
- **[El tope de 500 se alcanza antes de terminar]** → La parada temprana de D1 libera cuota. Si igual no alcanza, el requirement obliga a decidir con lo medido o a postergar, dejándolo escrito.
- **[Las métricas dependen de que los smoke impriman bien sus líneas]** → El reporte se prueba con logs de ejemplo, y una corrida de ensayo con el modelo actual, sobre un solo escenario (≈ 3 requests, dentro de la reserva), valida el pipeline completo antes de gastar con el candidato.

## Migration Plan

1. Se implementan la parametrización, el runner y el reporte, sin tocar los defaults (grupo 1 de tasks).
2. Se corre la evaluación (grupos 2 a 5 de tasks).
3. Solo en los roles adoptados: se cambian los defaults en `application.yml` y en el ConfigMap, y se verifica en el cluster con `./local.sh reload-images` y `kubectl rollout restart deployment/assistant -n the-store`.
4. **Rollback:** volver al plan B es cambiar `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`, `RETAIL_ASSISTANT_MODELS_REWRITE` y `SPRING_APPLICATION_JSON` en el ConfigMap y reiniciar el `assistant`, sin reconstruir la imagen. En el repo, revertir el commit de defaults.

## Open Questions

- Si `muse-glimmer-30b` pasa el principal y falla la reescritura solo por latencia (R2), ¿sirve igual como plan B verificado de la reescritura? No cambia las tasks: la tabla registra la medición, y lo decide el grupo al elegir el siguiente candidato.
