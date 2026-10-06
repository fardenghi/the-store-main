# Design

## Context

Ver `proposal.md` (Why) para la motivación y `specs/assistant-chat/spec.md` para el comportamiento exigido. Estado del que parte este change:

- `add-assistant-service` deja el `assistant` (Spring Boot 3.5 con `spring-boot-starter-web`, Spring AI 1.1.8, paquete `com.amazon.sample.assistant`) con el starter de OpenAI apuntando a `https://integrate.api.nvidia.com`: modelo principal en `spring.ai.openai.chat.options.model` (`nvidia/nemotron-3-super-120b-a12b`), `max-tokens` explícito y el modelo de reescritura en `retail.assistant.models.rewrite` (`nvidia/nemotron-3.5-lightning-30b-a3b`). Con la clave en `not-configured` el servicio arranca y las llamadas fallan con 401/403. Ese change ya verificó que `OpenAiChatOptions` de 1.1.8 tiene `extraBody`. El rate limiting y el manejo de 429 del chat quedaron asignados a `add-assistant-tools`.
- `add-product-indexing` deja `ProductSearchService.search(query, tags, minPrice, maxPrice, k)` (tags OR, rango inclusivo, embebe en `RETRIEVAL_QUERY` con caché LRU), `CatalogClient` contra `retail.assistant.endpoints.catalog`, errores `EmbeddingProviderException` (`QUOTA`, `UNAUTHORIZED`, `UNAVAILABLE`), `503 index-unavailable` con la colección vacía, y la convención de rutas `/assistant/...` con errores `ProblemDetail` y `type` estable. La búsqueda no soporta excluir tags, y devuelve el precio del payload (última sincronización).
- La `ui` hoy arma el system prompt con `retail.ui.chat.prompt` (persona A.G.E.N.T. de gadgets espía) y su `ChatController` devuelve `POST /chat/submit` como SSE con eventos sin nombre y `data` `{"text": "..."}`. `chat.js` concatena `data.text` de cada línea `data:`. La `ui` no se toca acá: el provider `assistant`, la propagación del `X-Session-ID` y el retiro de la persona de su configuración son de `integrate-ui-assistant`.
- `catalog` expone `GET /catalog/tags` (`[{name, displayName}]`). La taxonomía de `replace-catalog-with-home-furniture` tiene ~18 tags en tres ejes (tipo, ambiente, estilo/material) y su D9 cambia la frase de la demo "not a vehicle" por "not a lamp".
- Cuotas (CLAUDE.md): NVIDIA 40 RPM; Gemini 100 RPM y 1.000 requests por día.

## Goals / Non-Goals

**Goals:**
- Un pipeline de turno (reescritura → retrieval → respuesta en streaming → memoria) con dos solicitudes a NVIDIA por turno sin tools, y con puntos de extensión para que `add-assistant-tools` registre tools y emita eventos SSE propios sin rediseñar el flujo.
- Que la mejora de la reescritura se pueda mostrar con datos (log por turno y evaluación con un conjunto fijo de consultas), como pide el criterio de la pre-entrega.
- Que cualquier falla de un proveedor termine en un evento de error explícito o en una degradación controlada, nunca en un stream colgado.

**Non-Goals:**
- Tools (`searchProducts`, `getProductDetails`, `addToCart`), el evento de carrito actualizado y el precio en vivo: son `add-assistant-tools`.
- Rate limiter hacia NVIDIA, espera ante 429 y reintentos del chat: son `add-assistant-tools` (D3 de `add-assistant-service`). Acá un 429 se informa como error.
- Traducir categoría y presupuesto a filtros de tags positivos y orden de la API (caso "Filtros estructurados"): es `add-assistant-tools`. La reescritura de este change solo produce los filtros que hacen falta para refinar el turno anterior (D4).
- Cambios en la `ui` y en su configuración: es `integrate-ui-assistant`.
- Memoria persistente entre reinicios o compartida entre réplicas, endpoint para borrar una sesión, umbral de `score` y reranking.

## Decisions

### D1. Endpoint `POST /assistant/chat` y contrato SSE

Ruta bajo `/assistant/` como los endpoints de `add-product-indexing`. Request `{"message": "..."}` con el header `X-Session-ID`. El controller de Spring MVC devuelve `Flux<ServerSentEvent<?>>` (Spring MVC soporta tipos reactivos como respuesta asíncrona y Reactor ya viene con Spring AI), con `spring.mvc.async.request-timeout=150s`.

| Evento | `data` | Cuándo |
|---|---|---|
| (sin nombre) | `{"text": "<fragmento>"}` | Cada fragmento de la respuesta |
| `products` | `[{"id", "name", "price"}]` | Una vez, antes del primer fragmento: productos recuperados en este turno (vacío si no hubo búsqueda) |
| `done` | `{}` | Fin correcto |
| `error` | `{"type", "detail", "retryAfterSeconds"?}` | Falla después de haber empezado el stream |
| comentario `:keepalive` | — | Cada 10 s sin otros eventos (por ejemplo, mientras el modelo razona) |

- Los fragmentos de texto conservan el formato `{"text": ...}` de la `ui` actual, así que el provider `assistant` de `integrate-ui-assistant` puede reenviarlos tal cual. Los eventos con nombre son nuevos y la `ui` tiene que manejarlos (hoy `chat.js` parsea cualquier línea `data:` como texto): queda anotado para ese change.
- El evento `products` hace verificable el criterio "responde con datos reales" (cada producto nombrado tiene que estar ahí) y le permite a la `ui` mostrar tarjetas si quiere. `add-assistant-tools` agrega sus eventos (por ejemplo, el de carrito) por el mismo `Sinks.Many` del turno (D2).
- Errores antes de abrir el stream (validación, sesión ocupada) → `ProblemDetail` con `type` `invalid-parameter` (`400`) o `session-busy` (`409`), igual que en `add-product-indexing`. Cualquier error posterior → evento `error` y cierre.
- **Alternativa descartada: `SseEmitter`.** Obliga a manejar a mano el hilo, los timeouts y la cancelación, cuando `ChatClient.stream()` ya devuelve un `Flux`.
- **Alternativa descartada: pasar el `assistant` a WebFlux.** `add-assistant-service` ya lo dejó en Spring MVC; mezclar ambos stacks no aporta nada para un endpoint.

### D2. Pipeline de un turno

```
validar ─► tomar lock de la sesión ─► leer SessionState
   ─► reescribir (modelo compacto, ≤ 5 s, fallback al mensaje crudo)
   ─► intent == other ? sin búsqueda : search(query, minPrice, maxPrice) + filtro de excludeTags
   ─► emitir `products`
   ─► modelo principal en streaming (persona + contexto + historial, thinking según intent)
   ─► emitir fragmentos (sin razonamiento)
   ─► OK: guardar turno en memoria, emitir `done`, loguear línea del turno
       error/cancelación: emitir `error` (si hay cliente), NO guardar
   ─► liberar lock (doFinally)
   ─► en segundo plano: top-k de la consulta cruda para el log comparativo (D9)
```

Lo orquesta un `ChatTurnService` que expone un `Sinks.Many<ServerSentEvent<?>>` por turno. Así, el stream del modelo y cualquier evento que agreguen las tools salen por el mismo canal y en orden. La reescritura y la búsqueda son bloqueantes y corren en `Schedulers.boundedElastic()` antes de suscribirse al stream del modelo.

El lock es un `AtomicBoolean` en el `SessionState`. Si ya está tomado, la respuesta es `409 session-busy` (spec). Si el cliente corta la conexión, la cancelación del `Flux` cancela la llamada al modelo y libera el lock sin guardar el turno.

### D3. Dos `ChatClient` sobre el mismo `OpenAiChatModel`

- `rewriteChatClient`: `OpenAiChatOptions` con `model=${retail.assistant.models.rewrite}`, `temperature=0`, `maxTokens=256` y `extraBody` tomado de `retail.assistant.rewrite.extra-body`, por defecto `{chat_template_kwargs: {enable_thinking: false}}`. Llamada no streaming.
- `mainChatClient`: el modelo de `spring.ai.openai.chat.options.model`, `temperature=0.6` (la que usa hoy la `ui`). Por turno se pasan opciones con el `extraBody` de razonamiento (D7) y el `maxTokens` que corresponda.

Los dos salen del `OpenAiChatModel` autoconfigurado, con distinto `defaultOptions` en el `ChatClient.Builder`: una sola URL base, una sola clave y un solo cliente HTTP.

- Los `extraBody` son mapas configurables y no constantes en el código, porque cada modelo activa el razonamiento con un parámetro distinto (Nemotron con `chat_template_kwargs.enable_thinking`, otros con otra clave o ninguna). Pasar al plan B es editar el ConfigMap (spec, "Cambio al plan B").
- **Alternativa descartada: dos `OpenAiChatModel` con su propio `OpenAiApi`.** Duplica la configuración de URL y clave que ya resolvió `add-assistant-service` sin ninguna ventaja.

### D4. Reescritura con salida estructurada

El modelo compacto recibe un prompt (`prompts/rewrite.st`) con:
- los últimos `retail.assistant.rewrite.history-turns` (3) turnos de la sesión, con la respuesta del asistente truncada a 500 caracteres;
- los productos mostrados en el turno anterior (id, nombre, precio, tags);
- la lista de tags del catálogo (`GET /catalog/tags`, leída una vez con el `CatalogClient` de `add-product-indexing` y cacheada; si falla se reintenta en el turno siguiente y mientras tanto se omite del prompt);
- el mensaje actual.

Y devuelve un JSON que se mapea con el `BeanOutputConverter` de Spring AI (`.entity(RewriteResult.class)`):

```json
{"intent": "search | compare | other", "query": "...", "minPrice": null, "maxPrice": 299, "excludeTags": ["lighting"]}
```

- `query`: consulta autocontenida en inglés (el catálogo está en inglés y así se reutiliza el caché de embeddings entre idiomas).
- `maxPrice`/`minPrice`: para "cheaper", el modelo pone como `maxPrice` el precio del producto referido menos 1, a partir de los precios que recibe. Se validan: enteros no negativos y `minPrice ≤ maxPrice`; si no, se descartan.
- `excludeTags`: para "not a lamp". Se filtran contra la lista de tags conocida.
- `intent`: `other` (saludos, agradecimientos, preguntas sobre la persona) no busca productos; `compare` activa el razonamiento (D7) y suma al contexto los productos del turno anterior.

Si la llamada tarda más de `retail.assistant.rewrite.timeout` (5 s), falla o el JSON no valida (`query` vacía o de más de 200 caracteres), se usa `{intent: search, query: <mensaje crudo>}` sin filtros, y el log del turno marca `rewrite=fallback`.

- **Por qué la reescritura produce filtros:** con RAG solamente, embeber "cheaper velvet armchair" no restringe el precio y "not a lamp" trae lámparas, porque los embeddings no modelan la negación. Sin estos filtros el caso multi-turno de la pre-entrega dependería de las tools, que llegan en el change siguiente.
- **Por qué no produce tags positivos:** la categoría ya está en la consulta semántica, y forzar un filtro de tags desde la reescritura recortaría resultados por un error de clasificación. Los filtros estructurados positivos (tags y orden de la API) son el caso de uso de la tool `searchProducts`.
- **Alternativa descartada: reescribir solo el texto.** Es lo más simple, pero no cumple "cheaper" ni "not a lamp".
- **Alternativa descartada: que el modelo principal decida la búsqueda vía tool calling.** Mezcla este change con `add-assistant-tools` y sumaría un ida y vuelta más con el modelo grande en cada turno.

### D5. Retrieval y contexto del modelo principal

- `ProductSearchService.search(query, null, minPrice, maxPrice, k)` con `k = retail.assistant.chat.retrieval-k` (5). Si hay `excludeTags`, se pide `min(3·k, 20)` (20 es el `max-k` de la búsqueda) y se filtra en el `assistant` hasta quedarse con `k`. Excluir tags no es parte del contrato de `semantic-product-search`, así que el filtro queda en este change sin modificar la búsqueda.
- Sin umbral de `score` (`retail.assistant.chat.min-score`, por defecto 0, desactivado). Con 80 productos y `k=5`, el modelo puede descartar lo poco relevante, y el prompt le exige no forzar recomendaciones.
- Contexto: una sección del system prompt con una línea por producto, `- [<id>] <name> | $<price> | tags: a, b | <description>`. El id va en el contexto para que `add-assistant-tools` pueda referenciar productos sin otra búsqueda. Los productos del turno anterior van en una sección aparte ("previously shown"), para que el modelo resuelva "the first two" o "the cheapest of those".
- El precio del contexto es el del payload de Qdrant. Como el catálogo es estático en runtime, coincide con `GET /catalog/products/{id}`; la consulta del precio en vivo que exige la pre-entrega es la tool `getProductDetails` de `add-assistant-tools`.
- Si la búsqueda lanza `EmbeddingProviderException` o `index-unavailable`, el turno sigue sin productos y el system prompt recibe un aviso de "catálogo no disponible" que obliga a decirlo y prohíbe nombrar productos (spec).

### D6. Memoria por sesión en memoria del proceso

`SessionStore` sobre un caché Caffeine (versión gestionada por Spring Boot) con `expireAfterAccess = retail.assistant.chat.memory.idle-ttl` (30 min) y `maximumSize = retail.assistant.chat.memory.max-sessions` (10.000). Cada `SessionState` guarda:
- una ventana de los últimos `retail.assistant.chat.memory.max-turns` (10) turnos, cada uno con el mensaje del usuario y la respuesta final del asistente (sin el razonamiento ni el contexto RAG);
- los productos recuperados en el último turno con búsqueda (snapshot de id, nombre, descripción, precio y tags);
- el lock del turno (D2).

El historial se pasa al modelo principal como mensajes `UserMessage`/`AssistantMessage` explícitos. El turno se agrega **solo** cuando el stream termina bien.

- **Alternativa descartada: `MessageChatMemoryAdvisor` + `MessageWindowChatMemory` de Spring AI.** El advisor guarda el mensaje del usuario antes de la llamada, así que un turno fallido deja un mensaje huérfano en la memoria (la spec lo prohíbe). Además, `InMemoryChatMemoryRepository` no expira sesiones ni guarda los productos mostrados.
- **Alternativa descartada: persistir la memoria (Redis, JDBC o Qdrant).** Sumaría infraestructura que la pre-entrega no tiene en su diagrama. Con una réplica, perder las conversaciones al reiniciar es aceptable para el POC.

### D7. Razonamiento por turno

- `retail.assistant.chat.reasoning.mode`: `auto` (por defecto), `always` o `never`. En `auto`, el razonamiento se activa si el intent es `compare`.
- Activado: `extraBody` de `retail.assistant.chat.reasoning.on-extra-body` (por defecto `{chat_template_kwargs: {enable_thinking: true}}`) y `maxTokens = retail.assistant.chat.max-tokens-reasoning` (4096), porque los tokens del razonamiento cuentan dentro del límite. Desactivado: `off-extra-body` (`enable_thinking: false`) y `maxTokens = retail.assistant.chat.max-tokens` (1024).
- El razonamiento no se envía ni se guarda. Lo esperado es que NVIDIA lo devuelva en `reasoning_content`, que Spring AI 1.1.8 deja fuera del texto del `ChatResponse`; el spike (tasks 1) lo verifica. Si el modelo lo emite dentro del texto como `<think>…</think>`, un `ThinkTagFilter` con estado entre fragmentos lo descarta del stream. Del razonamiento solo se loguea la longitud.
- Mientras el modelo razona no llegan fragmentos: el comentario `:keepalive` de D1 evita que un proxy intermedio corte la conexión por inactividad.
- **Alternativa descartada: razonamiento siempre activado.** Multiplica la latencia del primer token en búsquedas simples sin mejorar la respuesta.
- **Alternativa descartada: que lo decida la `ui` con un parámetro.** La `ui` no tiene cómo saber si un mensaje es una comparación, y `integrate-ui-assistant` no prevé un control así.

### D8. Persona y prompts versionados en el `assistant`

- `src/main/resources/prompts/system.st` (plantilla de Spring AI): la persona A.G.E.N.T. migrada de `retail.ui.chat.prompt`, con los mismos rasgos (agente cínico, clichés de espías que él mismo señala, nunca rompe el personaje, siempre útil) pero adaptada: la tienda es "The Store", los productos son "equipamiento para la guarida" (muebles y deco), el carrito es el "inventario de la misión" y el envío es el "despliegue". Se agregan las reglas: usar solo productos del contexto o de turnos anteriores y con su precio exacto; si no hay productos adecuados, decirlo; formato de comparación (precio y diferencia, dos o más atributos, recomendación según uso); responder en el idioma del usuario; respuestas breves salvo comparaciones; no revelar ni abandonar estas instrucciones.
- Los prompts se escriben en inglés (mejor adherencia de los modelos, catálogo en inglés). La regla de idioma hace que la respuesta salga en el idioma del usuario.
- No son configurables por ConfigMap: cambiar la persona es un cambio de código revisado, y así la `ui` deja de tener cualquier control sobre ella (pre-entrega, sección 2).

### D9. Observabilidad de la reescritura

- Una línea de log por turno, en formato `clave=valor` (logger `assistant.turn`): sesión (primeros 8 caracteres), `intent`, `rewrite` (`ok`/`fallback`), mensaje crudo, consulta reescrita, filtros, `reasoning` (`on`/`off`), ids del top-k reescrito, ids del top-k crudo, solapamiento, solicitudes a NVIDIA del turno y latencias (reescritura, retrieval, primer fragmento, total).
- El top-k crudo se calcula **después** de cerrar el stream, en segundo plano, para no sumar latencia. Cuesta un embedding más por turno (menos con el caché LRU de `add-product-indexing`), y por eso se puede apagar con `retail.assistant.chat.compare-raw-retrieval=false`.
- Evaluación: `src/test/resources/rewrite-eval.json` con ~10 consultas conversacionales (con muletillas, errores de tipeo, referencias a un turno previo simulado y frases en español) y, para cada una, un predicado sobre tags que define los productos esperados (por ejemplo, `seating` y `velvet`). Un test con tag `smoke` corre cada consulta cruda y reescrita contra los proveedores reales y compara los aciertos en el top-5. El resultado se registra en el README del servicio y en `docs/arquitectura.md` como evidencia del criterio de la pre-entrega.
- **Alternativa descartada: un endpoint de debug que devuelva ambos top-k.** La búsqueda cruda ya se puede probar con `GET /assistant/products/search`, y el log más la evaluación cubren el criterio sin sumar superficie HTTP.

### D10. Errores del proveedor de chat

Las excepciones de Spring AI y del cliente HTTP (`WebClientResponseException` en streaming, `NonTransientAiException`/`TransientAiException` en la reescritura) se traducen a `ChatProviderException` con causa `QUOTA` (429, con `retryAfter` del header `Retry-After` si viene), `UNAUTHORIZED` (401/403) o `UNAVAILABLE` (5xx, timeout, red), igual que `EmbeddingProviderException` en `add-product-indexing`. En el modelo principal se convierten en el evento `error` con `type` `llm-quota-exceeded`, `llm-provider-unauthorized` o `llm-provider-unavailable`. En la reescritura no se informan al usuario: se aplica el fallback de D4.

- Se fija `spring.ai.retry.max-attempts=1` para el chat: el reintento automático de Spring AI (hasta 10 intentos con backoff) gastaría la cuota de 40 RPM y dejaría al usuario esperando sin aviso. La espera ante 429 con `Retry-After` la implementa `add-assistant-tools` con su rate limiter. La propiedad es global y también alcanza al `EmbeddingModel` de Gemini, lo que es coherente con `add-product-indexing`, que hace sus reintentos de forma explícita (su D6) y no tiene que sumarlos a los de Spring AI.
- Tiempos límite: 60 s hasta el primer fragmento con razonamiento y 20 s sin él, y 120 s por turno. Si se exceden, error `llm-provider-unavailable`.

### D11. Configuración

```yaml
retail.assistant:
  rewrite:
    timeout: 5s
    history-turns: 3
    extra-body: {chat_template_kwargs: {enable_thinking: false}}
  chat:
    retrieval-k: 5
    min-score: 0
    max-tokens: 1024
    max-tokens-reasoning: 4096
    compare-raw-retrieval: true
    reasoning:
      mode: auto
      on-extra-body: {chat_template_kwargs: {enable_thinking: true}}
      off-extra-body: {chat_template_kwargs: {enable_thinking: false}}
    memory:
      max-turns: 10
      idle-ttl: 30m
      max-sessions: 10000
```

Mapeado a un `@ConfigurationProperties` validado. Los valores escalares van como variables de entorno en el ConfigMap `assistant` (`RETAIL_ASSISTANT_CHAT_REASONING_MODE`, etc.). Los mapas de `extra-body` se pueden sobreescribir con `SPRING_APPLICATION_JSON` en el ConfigMap, que es lo que hay que tocar para pasar a un modelo que activa el razonamiento con otra clave.

### D12. Spike de modelos antes de construir el pipeline

Primera tarea del change: un test con tag `smoke` contra NVIDIA con Spring AI 1.1.8 que verifica, para el modelo principal, streaming, `enable_thinking` on/off por request (que el `extraBody` por llamada se combine con el de los `defaultOptions`), dónde llega el razonamiento (`reasoning_content` o `<think>`) y tool calling en streaming con una tool de prueba (lo necesita `add-assistant-tools`). Para el modelo de reescritura, verifica JSON válido con thinking desactivado y la latencia. Se repite con los modelos del plan B para dejar sus `extra-body` documentados.

Criterio para pasar al plan B: si el modelo principal falla en streaming, en el cambio de razonamiento por request o en tool calling, se pasa a `deepseek-ai/deepseek-v4.1-flash`. Si la reescritura tarda más de 2 s de mediana o devuelve JSON inválido en más de 1 de 10 intentos, se pasa a `google/gemma-3-12b-it`. El resultado se registra en el README del servicio.

### D13. Desvíos respecto de la pre-entrega

| Pre-entrega | Este change | Justificación |
|---|---|---|
| `llama3.2:3b` en Ollama, dentro del cluster (sección 4) | `nvidia/nemotron-3.5-lightning-30b-a3b` para reescribir y `nvidia/nemotron-3-super-120b-a12b` para responder, vía la API de NVIDIA | Autorizado por la cátedra (ver D8 de `add-assistant-service`). Un 3B solo con CPU no da latencias de demo ni comparaciones razonadas confiables. |
| "Modelo compacto con RAG, modelo intermedio con razonamiento" (sección 3) | El modelo compacto reescribe la consulta. La respuesta con RAG la genera el modelo principal, con el razonamiento desactivado en los turnos simples y activado en las comparaciones | Se mantiene la pareja compacto + modelo con razonamiento. Responder con el modelo grande evita alternar dos modelos dentro del mismo turno y deja una sola persona consistente. El costo de latencia se controla apagando el thinking fuera de las comparaciones (D7). |
| Frase de la demo "*not a vehicle*" | "*not a lamp*" | Ya documentado en D9 de `replace-catalog-with-home-furniture`. El criterio no cambia. |

Agregados que no contradicen la pre-entrega: el evento `products` del SSE (D1) y los filtros de precio y de exclusión que produce la reescritura (D4). La pre-entrega no especifica dónde vive la memoria por sesión; en memoria del proceso (D6) la cumple.

## Risks / Trade-offs

- **[El `extraBody` por request no se combina con el de `defaultOptions` en 1.1.8, o NVIDIA ignora `enable_thinking` en Nemotron Super]** → El spike (D12) lo verifica primero. Si no se combina, se crean dos `ChatClient` principales (razonamiento on/off), cada uno con su `extraBody` por defecto. Si el modelo lo ignora, plan B.
- **[El razonamiento llega mezclado en el texto]** → `ThinkTagFilter` (D7), con tests de etiquetas partidas entre fragmentos.
- **[Latencia de las comparaciones con razonamiento]** → Solo en `compare`, con `:keepalive` y un tiempo límite de 60 s hasta el primer fragmento. Si en la demo es inaceptable, `reasoning.mode=never` por ConfigMap.
- **[La reescritura clasifica mal el intent o inventa filtros]** → Los filtros se validan (D4), un error en el JSON cae al mensaje crudo, y la evaluación de D9 y los escenarios de la spec en el smoke lo miden. Un `intent=other` erróneo solo hace que el turno responda sin productos nuevos, con los del turno anterior todavía en el contexto.
- **[El modelo nombra un producto que no está en el contexto]** → Reglas del system prompt, `temperature` 0.6 y el escenario "Recomendación con productos reales" en el smoke, que compara cada nombre de la respuesta con el evento `products`. Si falla seguido, se baja la temperatura.
- **[Cuota de NVIDIA: 2 requests por turno contra 40 RPM]** → Alcanzan ~20 turnos por minuto para toda la tienda, suficiente para la demo. El rate limiter llega con `add-assistant-tools`. Un 429 se informa con `llm-quota-exceeded`.
- **[Cuota de Gemini: hasta 2 embeddings por turno con la comparación de D9]** → Caché LRU de consultas, y `compare-raw-retrieval=false` si la cuota diaria aprieta.
- **[Memoria perdida al reiniciar o con más de una réplica]** → El manifiesto tiene 1 réplica. Está documentado como límite del POC.
- **[Prompt injection desde el mensaje del usuario]** → En este change el asistente no tiene tools, así que el impacto se limita al texto. Las reglas de D8 cubren no revelar el prompt. `add-assistant-tools` tiene que validar los argumentos de las tools del lado del servidor y no confiar en el modelo.

## Migration Plan

1. Desplegar con `./local.sh reload-images` y `kubectl apply -f dist/kubernetes.yaml -n the-store` (ConfigMap `assistant` con las variables nuevas). No hay datos que migrar.
2. Verificar desde el pod de la `ui` con `curl -N -H 'X-Session-ID: demo' -H 'Content-Type: application/json' -d '{"message":"..."}' http://assistant/assistant/chat`.
3. Rollback: revertir el commit y reiniciar el `assistant`. Ningún otro servicio consume el endpoint hasta `integrate-ui-assistant`.

## Open Questions

- Los `extra-body` exactos de razonamiento de los modelos del plan B (`deepseek-ai/deepseek-v4.1-flash` y `google/gemma-3-12b-it`) los define el spike (D12). Solo cambian valores de configuración, no el diseño.

## Resultados del apply (2026-10-06)

- **Spike (D12):** `nemotron-3-super` cumple streaming, thinking on/off por request y tool calling en streaming; el razonamiento llega en `reasoning_content`, fuera del texto, así que `ThinkTagFilter` queda apagado por defecto (`reasoning.strip-think-tags=false`). `nemotron-3.5-lightning`: mediana 1,5 s, 1 de 10 inválido. Defaults sin cambios. Plan B no verificable: `deepseek-ai/deepseek-v4.1-flash` no respondió en 100 s y `google/gemma-3-12b-it` devuelve 404 "Function not found for account". Pendiente del grupo revisar el plan B (en `/v1/models` aparecen, sin probar, `google/gemma-4-31b-it` y `google/gemma-3-4b-it`); cambiarlo es una decisión cerrada de CLAUDE.md.
- **Opciones por request:** `.options()` del `ChatClient` reemplaza los `defaultOptions` del cliente, así que `ReasoningPolicy` arma una copia de las opciones base con el `extraBody` y `maxTokens` del turno (no hizo falta crear dos clientes principales).
- **`extra-body`:** los defaults viven en `ChatProperties` y no en el YAML, porque Spring combina las claves de un mapa definido en varias fuentes y un `SPRING_APPLICATION_JSON` no podría reemplazarlo. Un mapa vacío explícito manda el request sin campos extra.
- **Reescritura:** las instrucciones van como system y el mensaje como user; con el mensaje embebido en el prompt, el modelo clasificaba los saludos como búsquedas. Si la lista de tags no está disponible, los `excludeTags` no se pueden validar y se ignoran. La latencia del modelo compacto tiene cola larga (≈1 de 10 llamadas supera los 5 s de D4 y cae al fallback).
- **Tiempos límite y keepalive** configurables en `retail.assistant.chat.timeouts` (defaults de D10/D1).
- **Smoke tests** con sufijo `SmokeIT` (convención del servicio: los corre failsafe solo con `-Psmoke`). El catálogo de los smoke se sirve con un `HttpServer` del JDK y no con `MockRestServiceServer`, porque el `RestClient.Builder` autoconfigurado también lo usa el cliente de NVIDIA.
- **Cuota de Gemini:** `batchEmbedContents` cuenta **cada texto** como una request, tanto para el RPM como para el límite diario (`EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier`, 1.000): una reindexación completa son 80 requests. El 2026-10-06 se agotó la cuota diaria durante la verificación. Recomendación: que los smoke/e2e reutilicen una colección ya indexada (`ChatEndToEndSmokeIT` acepta `-Dsmoke.qdrant.host/port/collection`) y corran con `compare-raw-retrieval=false`.
- **Evaluación (9.2):** tres corridas, reescrita 45 / 43 / 47 contra cruda 42 aciertos en el top-5.
