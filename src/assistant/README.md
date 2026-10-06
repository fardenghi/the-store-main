# The Store - Assistant Service

| Language | Persistence       |
| -------- | ----------------- |
| Java     | Qdrant (vectores) |

Servicio que concentra la lógica GenAI de la tienda: es el único que habla con
el LLM (NVIDIA, API compatible con OpenAI), con el modelo de embeddings
(`gemini-embedding-001` de Google) y con el vector store (Qdrant). Al arrancar
indexa el catálogo en Qdrant y expone la búsqueda semántica de productos, los
productos similares y el chat con el asistente (persona A.G.E.N.T., reescritura
de consulta, RAG, memoria por sesión y tools que buscan con filtros, consultan
el precio vivo y agregan productos al carrito).

## Configuración

Los valores no secretos tienen default en `application.yml` y se sobreescriben
con variables de entorno (en el cluster, desde el ConfigMap `assistant`).

| Name                                                  | Description                                          | Default                                 |
| ----------------------------------------------------- | ---------------------------------------------------- | --------------------------------------- |
| `PORT`                                                | Puerto HTTP                                          | `8080`                                  |
| `NVIDIA_API_KEY`                                      | Clave de NVIDIA (chat). Viene del Secret             | `not-configured`                        |
| `GOOGLE_API_KEY`                                      | Clave de la Gemini API (embeddings). Viene del Secret | `not-configured`                        |
| `SPRING_AI_OPENAI_BASE_URL`                           | URL base del proveedor de chat                       | `https://integrate.api.nvidia.com`      |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`                 | Modelo principal (razonamiento + tools)              | `meta/muse-glimmer-30b`                 |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MAX_TOKENS`            | Límite de tokens de salida (NVIDIA lo exige)         | `1024`                                  |
| `RETAIL_ASSISTANT_MODELS_REWRITE`                     | Modelo de reescritura de consultas                   | `meta/muse-glimmer-30b`                 |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_TEXT_OPTIONS_MODEL` | Modelo de embeddings                                 | `gemini-embedding-001`                  |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_TEXT_OPTIONS_DIMENSIONS` | Dimensiones de los embeddings                   | `768`                                   |
| `SPRING_AI_VECTORSTORE_QDRANT_HOST`                   | Host de Qdrant (gRPC)                                | `localhost`                             |
| `SPRING_AI_VECTORSTORE_QDRANT_PORT`                   | Puerto gRPC de Qdrant                                | `6334`                                  |
| `RETAIL_ASSISTANT_ENDPOINTS_CATALOG`                  | Endpoint del servicio `catalog`                      | `http://localhost:8081`                 |
| `RETAIL_ASSISTANT_ENDPOINTS_CARTS`                    | Endpoint del servicio `carts`                        | `http://localhost:8082`                 |
| `SPRING_AI_VECTORSTORE_QDRANT_COLLECTION_NAME`        | Colección de Qdrant con los productos                | `products`                              |
| `RETAIL_ASSISTANT_INDEXING_SYNC_ON_STARTUP`           | Sincronizar el catálogo al arrancar                  | `true`                                  |
| `RETAIL_ASSISTANT_INDEXING_PAGE_SIZE`                 | Productos por página al leer el catálogo             | `50`                                    |
| `RETAIL_ASSISTANT_INDEXING_BATCH_SIZE`                | Textos por request de embeddings (máximo 100)        | `100`                                   |
| `RETAIL_ASSISTANT_INDEXING_MAX_PROVIDER_RETRIES`      | Reintentos por lote ante 429 o 5xx de Gemini         | `5`                                     |
| `RETAIL_ASSISTANT_SEARCH_DEFAULT_K` / `_MAX_K`        | Resultados de la búsqueda (default / máximo)         | `5` / `20`                              |
| `RETAIL_ASSISTANT_SEARCH_SIMILAR_DEFAULT_K` / `_SIMILAR_MAX_K` | Similares (default / máximo)                | `4` / `12`                              |
| `RETAIL_ASSISTANT_SEARCH_QUERY_CACHE_SIZE`            | Entradas del caché de embeddings de consultas        | `256`                                   |
| `RETAIL_ASSISTANT_REWRITE_TIMEOUT`                    | Tiempo límite de la reescritura; si se excede, se usa el mensaje crudo | `12s`                 |
| `RETAIL_ASSISTANT_REWRITE_HISTORY_TURNS`              | Turnos de la sesión que recibe la reescritura        | `3`                                     |
| `RETAIL_ASSISTANT_REWRITE_MAX_TOKENS`                 | Tokens de salida de la reescritura (incluyen el razonamiento si el modelo no lo apaga; con Nemotron alcanza con `256`) | `1024` |
| `SPRING_HTTP_CLIENT_READ_TIMEOUT`                     | Lectura HTTP de los `RestClient`: la reescritura (sin streaming) y el catálogo al indexar. Tiene que ser mayor que `RETAIL_ASSISTANT_REWRITE_TIMEOUT`; si no, el servicio no arranca | `30s` |
| `RETAIL_ASSISTANT_CHAT_RETRIEVAL_K`                   | Productos que recibe el modelo principal (1 a 20)    | `5`                                     |
| `RETAIL_ASSISTANT_CHAT_MIN_SCORE`                     | Umbral de score de la búsqueda (0 lo desactiva)      | `0`                                     |
| `RETAIL_ASSISTANT_CHAT_MAX_TOKENS`                    | Tokens de salida sin razonamiento                    | `1024`                                  |
| `RETAIL_ASSISTANT_CHAT_MAX_TOKENS_REASONING`          | Tokens de salida con razonamiento (lo incluyen)      | `4096`                                  |
| `RETAIL_ASSISTANT_CHAT_COMPARE_RAW_RETRIEVAL`         | Calcular el top-k de la consulta cruda para el log (1 embedding más por turno) | `true`        |
| `RETAIL_ASSISTANT_CHAT_REASONING_MODE`                | `auto` (solo en comparaciones), `always` o `never`   | `auto`                                  |
| `RETAIL_ASSISTANT_CHAT_REASONING_STRIP_THINK_TAGS`    | Descartar `<think>…</think>` del texto (solo para modelos que lo mezclan) | `false`            |
| `RETAIL_ASSISTANT_CHAT_MEMORY_MAX_TURNS`              | Turnos que se recuerdan por sesión                   | `10`                                    |
| `RETAIL_ASSISTANT_CHAT_MEMORY_IDLE_TTL`               | Inactividad después de la cual se olvida la sesión   | `30m`                                   |
| `RETAIL_ASSISTANT_CHAT_MEMORY_MAX_SESSIONS`           | Máximo de sesiones en memoria                        | `10000`                                 |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_FIRST_TOKEN`          | Espera máxima del primer fragmento sin razonamiento (cuenta el primer fragmento de texto, de `reasoning_content` o un tool call) | `20s` |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_FIRST_TOKEN_REASONING` | Espera máxima del primer fragmento con razonamiento | `60s`                                   |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_TURN`                 | Duración máxima de un turno                          | `120s`                                  |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_KEEPALIVE`            | Intervalo del comentario `:keepalive` sin eventos    | `10s`                                   |
| `RETAIL_ASSISTANT_TOOLS_MAX_MODEL_CALLS`              | Solicitudes al modelo principal por turno; la última va sin poder pedir tools | `4`            |
| `RETAIL_ASSISTANT_TOOLS_MAX_TOOL_CALLS`               | Tools ejecutadas por turno                           | `6`                                     |
| `RETAIL_ASSISTANT_TOOLS_MAX_QUANTITY`                 | Cantidad máxima de `addToCart`                       | `10`                                    |
| `RETAIL_ASSISTANT_TOOLS_SEARCH_DEFAULT_LIMIT` / `_SEARCH_MAX_LIMIT` | Resultados de `searchProducts` (default / máximo, hasta 20) | `5` / `10`                |
| `RETAIL_ASSISTANT_TOOLS_DESCRIPTION_MAX_CHARS`        | Largo máximo de las descripciones en los resultados de las tools | `300`                       |
| `RETAIL_ASSISTANT_TOOLS_HTTP_CONNECT_TIMEOUT` / `_READ_TIMEOUT` | Tiempos límite de las tools hacia `catalog` y `carts` | `2s` / `5s`                     |
| `RETAIL_ASSISTANT_TOOLS_CORRECTIVE_TOOL_CHOICE`       | Vuelta correctiva de un pedido de carrito: `required` (`tool_choice: "required"`, para Nemotron) o `prompt` (el aviso pide el tool call, para modelos que ignoran `required`, como muse) | `prompt` |
| `RETAIL_ASSISTANT_RATE_LIMIT_REQUESTS_PER_MINUTE`     | Solicitudes a NVIDIA en cualquier ventana de 60 s (todas las sesiones) | `36`                  |
| `RETAIL_ASSISTANT_RATE_LIMIT_MAX_WAIT`                | Espera máxima del modelo principal por un lugar en el limitador | `30s`                        |
| `RETAIL_ASSISTANT_RATE_LIMIT_MAX_429_RETRIES`         | Reintentos de una vuelta del modelo principal ante un 429 | `2`                                |
| `RETAIL_ASSISTANT_RATE_LIMIT_DEFAULT_RETRY_AFTER`     | Pausa ante un 429 sin `Retry-After`                  | `5s`                                    |
| `SPRING_APPLICATION_JSON`                             | Campos extra de los requests a NVIDIA (`extra-body`), ver abajo | —                            |

Sin claves el servicio arranca igual y queda listo: al iniciar loguea, sin
mostrar valores, si cada clave está configurada o es el placeholder. Las
llamadas al proveedor con el placeholder fallan con 401/403.

### Razonamiento y plan B (`extra-body`)

Cada modelo activa o desactiva el razonamiento con un campo distinto del
request, así que esos campos (`extra-body`) son mapas configurables. Por
defecto son los de `meta/muse-glimmer-30b`, que no permite apagar el
razonamiento y lo regula por nivel (`low` es su "sin razonamiento"):

| Propiedad                                       | Default (muse)                  | Plan B (Nemotron)                                      |
| ----------------------------------------------- | ------------------------------- | ------------------------------------------------------ |
| `retail.assistant.rewrite.extra-body`           | `{"reasoning_effort": "low"}`   | `{"chat_template_kwargs": {"enable_thinking": false}}` |
| `retail.assistant.chat.reasoning.on-extra-body` | `{"reasoning_effort": "high"}`  | `{"chat_template_kwargs": {"enable_thinking": true}}`  |
| `retail.assistant.chat.reasoning.off-extra-body`| `{"reasoning_effort": "low"}`   | `{"chat_template_kwargs": {"enable_thinking": false}}` |

Se reemplazan con `SPRING_APPLICATION_JSON` en el ConfigMap. El mapa que se
define reemplaza completo al default (los defaults viven en `ChatProperties` y
no en el YAML porque Spring combina las claves de un mapa definido en varias
fuentes), y un mapa vacío `{}` manda el request sin campos extra.

**Plan B verificado: los Nemotron** (`nvidia/nemotron-3-super-120b-a12b` como
principal y `nvidia/nemotron-3.5-lightning-30b-a3b` como reescritura, los
defaults hasta `select-assistant-models`). Para pasar al plan B, en el
ConfigMap `assistant`:

```yaml
  SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL: nvidia/nemotron-3-super-120b-a12b
  RETAIL_ASSISTANT_MODELS_REWRITE: nvidia/nemotron-3.5-lightning-30b-a3b
  RETAIL_ASSISTANT_REWRITE_MAX_TOKENS: "256"
  RETAIL_ASSISTANT_TOOLS_CORRECTIVE_TOOL_CHOICE: required
  SPRING_APPLICATION_JSON: >-
    {"retail.assistant.chat.reasoning.on-extra-body": {"chat_template_kwargs": {"enable_thinking": true}},
     "retail.assistant.chat.reasoning.off-extra-body": {"chat_template_kwargs": {"enable_thinking": false}},
     "retail.assistant.rewrite.extra-body": {"chat_template_kwargs": {"enable_thinking": false}}}
```

y `kubectl rollout restart deployment/assistant -n the-store`.

Para volver a muse, se sacan esas cinco variables (quedan los defaults). El
plan B anterior (`deepseek-ai/deepseek-v4.1-flash` y `google/gemma-3-12b-it`)
no funciona con la cuenta (ver "Spike de modelos de chat"), y no se documenta
como plan B ningún modelo sin verificar.

**`reasoning_effort` en un `extra-body`.** Un modelo que regula el
razonamiento por nivel (como `meta/muse-glimmer-30b`) lo configura igual que
los demás, por ejemplo `{"reasoning_effort": "low"}` sin razonamiento y
`{"reasoning_effort": "high"}` en las comparaciones. Spring AI 1.1.8 mandaba
duplicado un campo del `extra-body` que también es un campo propio del request
de OpenAI cuando el request lleva tools, y NVIDIA respondía `400` "duplicate
field `reasoning_effort`". Desde el segundo intento de
`select-assistant-models`, `ExtraBody` saca `reasoning_effort` del mapa y lo
manda por la opción nativa `reasoningEffort` del request (una sola vez, con el
nivel del turno). Cualquier otro campo propio del request (`temperature`,
`top_p`, etc.) en un `extra-body` es un error de configuración y el servicio
no arranca.

## Indexación del catálogo

Al quedar listo, el servicio sincroniza en segundo plano la colección
`products` de Qdrant (768 dimensiones, coseno) con el catálogo:

1. Crea la colección si no existe, o la recrea si tiene otra dimensión o
   distancia.
2. Lee el catálogo completo paginando `GET /catalog/products`. Si `catalog` no
   responde (o devuelve 5xx), reintenta sin límite con backoff de 2 s a 30 s.
3. Embebe con `RETRIEVAL_DOCUMENT`, en lotes de hasta 100, solo los productos
   nuevos o cuyo texto cambió (compara un hash del texto, el modelo, las
   dimensiones y la versión de la plantilla). Si solo cambió el precio,
   actualiza el payload sin volver a embeber.
4. Borra los puntos de productos que ya no están en el catálogo (solo si la
   lectura del catálogo fue completa).

El texto embebido es el nombre, los `displayName` de los tags y la descripción
(plantilla versión 2). El primer arranque hace 1 request HTTP a Gemini
(`batchEmbedContents`), pero la cuota cuenta cada texto del lote: son 80 de
las 1.000 requests diarias. Los reinicios sin cambios gastan 0. Ante un 429 o un 5xx de Gemini reintenta hasta 5
veces por lote; con la clave inválida o sin configurar no reintenta y la
sincronización queda fallida (no se reintenta sola: corregir la causa y
reiniciar).

Al terminar loguea los contadores, por ejemplo:

```
Sincronización del catálogo terminada en 3137 ms: 80 productos en el catálogo, 80 embebidos, 0 con payload actualizado, 0 borrados, 0 sin cambios, 1 requests al proveedor de embeddings, 80 puntos en la colección
```

El catálogo es estático en runtime, así que no hay reindexado periódico ni por
endpoint. Para forzar un reindexado (por ejemplo, después de cambiar los datos
de `catalog`), alcanza con reiniciar el servicio:

```bash
kubectl rollout restart deployment/assistant -n the-store
```

Para reindexar todo desde cero, borrar la colección antes de reiniciar:
`kubectl port-forward -n the-store svc/qdrant 6333:6333` y
`curl -X DELETE localhost:6333/collections/products`.

## API

Los endpoints son internos: el ingress solo enruta hacia la `ui`. Los errores
siguen el formato `ProblemDetail` (RFC 9457, `application/problem+json`) con un
`type` estable.

### `GET /assistant/products/search`

Búsqueda semántica: embebe la consulta con `RETRIEVAL_QUERY` y busca en Qdrant.

| Parámetro  | Descripción                                                              |
| ---------- | ------------------------------------------------------------------------ |
| `q`        | Texto de la consulta. Obligatorio                                        |
| `tags`     | Nombres de tags separados por comas. Semántica OR, como en `catalog`     |
| `minPrice` | Precio mínimo, entero no negativo, inclusivo                             |
| `maxPrice` | Precio máximo, entero no negativo, inclusivo                             |
| `k`        | Cantidad máxima de resultados, entre 1 y 20. Por defecto 5               |

Las consultas iguales salvo mayúsculas y espacios salen de un caché en memoria
(256 entradas) y no gastan cuota.

```bash
curl -s 'localhost:8080/assistant/products/search?q=mid+century+velvet+armchair&k=3'
curl -s 'localhost:8080/assistant/products/search?q=something+to+sit+on&tags=velvet,leather&maxPrice=1000&k=20'
```

```json
[
  {
    "id": "…",
    "name": "Aiden Mid-Century Velvet Armchair",
    "description": "…",
    "price": 139,
    "tags": ["living-room", "mid-century", "seating", "velvet"],
    "score": 0.728117
  }
]
```

### `GET /assistant/products/{id}/similar`

Los `k` productos más parecidos (por defecto 4, entre 1 y 12), sin incluir al
propio producto. Usa el vector ya guardado en Qdrant: no llama a Gemini, así
que funciona aunque no haya clave o se haya agotado la cuota.

```bash
curl -s 'localhost:8080/assistant/products/3600929b-2826-5a98-908f-82a1d50bcf2b/similar?k=4'
```

La respuesta tiene el mismo formato que la búsqueda. Los datos salen del
payload de Qdrant, así que el precio es el de la última sincronización.

### Errores

| Status | `type`                            | Cuándo                                                          |
| ------ | --------------------------------- | --------------------------------------------------------------- |
| `400`  | `invalid-parameter`               | `q` vacía, `k` fuera de rango, precio no entero o negativo, `minPrice > maxPrice`. Indica el parámetro en `parameter` |
| `404`  | `product-not-found`               | El id de los similares no está indexado                         |
| `503`  | `index-unavailable`               | La colección no existe o está vacía (primera sincronización en curso o fallida), o Qdrant no responde |
| `503`  | `embedding-quota-exceeded`        | Gemini respondió 429. Trae el header `Retry-After` (segundos)   |
| `503`  | `embedding-provider-unauthorized` | Clave de Gemini sin configurar o inválida                       |
| `503`  | `embedding-provider-unavailable`  | Gemini respondió 5xx o no hubo red                              |

```bash
curl -s 'localhost:8080/assistant/products/search?q=table&minPrice=500&maxPrice=100'
```

```json
{
  "type": "invalid-parameter",
  "title": "Parámetro inválido",
  "status": 400,
  "detail": "minPrice no puede superar a maxPrice",
  "instance": "/assistant/products/search",
  "parameter": "minPrice"
}
```

### `POST /assistant/chat`

Un turno de conversación con el asistente, con la respuesta en streaming
(Server-Sent Events). Es interno, como el resto de la API: lo va a llamar la
`ui` (change `integrate-ui-assistant`).

- Header `X-Session-ID` (obligatorio): identifica la sesión, y es el mismo id
  que la `ui` usa como `customerId` del carrito. Hasta 128 caracteres entre
  letras, dígitos, `-` y `_`.
- Cuerpo `{"message": "..."}`: obligatorio, sin solo espacios, hasta 2000
  caracteres.
- Respuesta `200` con `Content-Type: text/event-stream`.

Cada turno reescribe el mensaje con el modelo compacto (consulta autocontenida
en inglés, filtros de precio y tags a excluir, intención), busca en Qdrant (si
la intención no es `other`), arma el contexto con los productos encontrados y
los del turno anterior, y responde con el modelo principal en streaming, con el
razonamiento activado solo en las comparaciones. La memoria guarda los últimos
10 turnos (mensaje, respuesta final y, si el turno usó tools, cada tool call
con un resultado compacto; sin razonamiento ni contexto) y los productos del
último turno con búsqueda; se olvida a los 30 minutos sin uso y se pierde al
reiniciar el pod. Un turno que termina con error o que el cliente corta no se
guarda.

Los tool calls se reenvían en el historial de los turnos siguientes (un
mensaje del asistente con los tool calls y otro con los resultados, con los
mismos ids), para que el modelo vea qué acciones se ejecutaron de verdad. El
resultado compacto deja solo ids, nombres, precios, cantidades o el tipo de
error: unos 145 tokens por una búsqueda de 5 productos y unos 55 por un
`addToCart`.

El texto sale **por oración**: cada oración se retiene hasta que termina, para
que la salvaguarda de agregados al carrito (ver "Tools") pueda descartarla
antes de enviarla.

| Evento                  | `data`                                       | Cuándo                                                        |
| ----------------------- | -------------------------------------------- | ------------------------------------------------------------- |
| `products`              | `[{"id", "name", "price"}]`                  | Una vez, antes del primer fragmento. Vacío si no hubo búsqueda |
| `tool`                  | `{"tool", "ok", "error"?, "products"?: [{"id", "name", "price"}]}` | Después de cada tool ejecutada, antes del texto que el modelo genera con su resultado. `products` solo en `searchProducts` y `getProductDetails` correctas (ver "Tools") |
| `cart-updated`          | `{"itemId", "name", "quantity", "unitPrice", "cartItemCount"?}` | Después de cada `addToCart` correcto, a continuación de su `tool`. `cartItemCount` es el total de unidades del carrito; se omite si no se pudo leer |
| (sin nombre)            | `{"text": "<fragmento>"}`                    | Cada fragmento de la respuesta; concatenados, el texto completo |
| `done`                  | `{}`                                         | Fin correcto                                                  |
| `error`                 | `{"type", "detail", "retryAfterSeconds"?}`   | Falla después de abrir el stream                              |
| comentario `:keepalive` | —                                            | Cada 10 s sin otros eventos (por ejemplo, mientras el modelo razona) |

Tipos del evento `error`:

| `type`                       | Cuándo                                                                 |
| ---------------------------- | ---------------------------------------------------------------------- |
| `llm-quota-exceeded`         | NVIDIA respondió 429 y siguió respondiendo 429 en los 2 reintentos, o el limitador no tenía lugar dentro de 30 s (ver "Limitador y 429"). Trae `retryAfterSeconds` si se conoce la espera |
| `llm-provider-unauthorized`  | Clave de NVIDIA sin configurar o inválida (401/403)                    |
| `llm-provider-unavailable`   | NVIDIA respondió 5xx, no hubo red, o se excedió un tiempo límite (20 s al primer fragmento, 60 s con razonamiento, 120 s por turno) |

Si la reescritura falla o tarda más de 12 s, el turno sigue con el mensaje
crudo como consulta. El tiempo límite era de 5 s en el diseño (D4) y se subió
porque la latencia de `nemotron-3.5-lightning-30b-a3b` es bimodal: el
2026-10-06 cinco llamadas directas con un prompt mínimo tardaron 9,8 / 1,6 /
8,9 / 1,7 / 1,7 s, y en el e2e 8 de 15 reescrituras superaron los 5 s. Con
5 s, la mitad de los turnos perdía la reescritura, y con ella los filtros de
"cheaper" y la intención `other` de los saludos. El costo es que un turno con
la reescritura lenta tarda unos 10 s hasta el evento `products`. En la línea
`assistant.turn`, `rewrite=ok|fallback` y `rewriteMs` muestran si la
reescritura cayó al fallback y cuánto tardó, para ajustar
`RETAIL_ASSISTANT_REWRITE_TIMEOUT`. Si el índice o los embeddings no están disponibles, el
turno sigue sin productos y el asistente dice que no puede consultar el
catálogo. Ninguna de esas fallas afecta la readiness.

Errores antes de abrir el stream (`ProblemDetail`, sin llamar a ningún
proveedor):

| Status | `type`              | Cuándo                                                                   |
| ------ | ------------------- | ------------------------------------------------------------------------ |
| `400`  | `invalid-parameter` | Falta `X-Session-ID` o es inválido, o `message` falta, está vacío o supera 2000 caracteres. Indica el campo en `parameter` |
| `409`  | `session-busy`      | La sesión ya tiene un turno en curso y su cliente sigue conectado        |

Mientras el modelo razona no se escribe nada en la conexión, así que el corte
de un cliente recién se nota al escribir. Por eso, si llega un turno para una
sesión ocupada, el `assistant` escribe dos comentarios `:keepalive` en la
conexión del turno en curso (con 100 ms entre ambos) y espera hasta 500 ms: si
ese cliente ya se fue, la escritura falla, el turno se cancela, libera la
sesión y el turno nuevo entra; si sigue conectado, el nuevo recibe `409`. Un
turno cancelado no puede guardar nada en la memoria aunque su llamada al
modelo termine después.

```bash
curl -sN -H 'X-Session-ID: demo' -H 'Content-Type: application/json' \
  -d '{"message":"I need a lamp for my desk"}' localhost:8080/assistant/chat
```

```
event:products
data:[{"id":"…","name":"Curved Brass and Walnut Desk Lamp","price":149},…]

data:{"text":"Ah, Operative"}

data:{"text":", requesting illumination for your desk?"}

…

event:done
data:{}
```

Verificado el 2026-10-06 tal como está escrito, contra un `assistant` local
(`./mvnw spring-boot:run` con Qdrant en Docker, `catalog` en el puerto 8081 y
claves válidas; ver "Running"): 5 lámparas en `products`, la respuesta en
fragmentos y `done`, con 2 requests a NVIDIA y la primera respuesta a ≈2,3 s.

Pendiente para `integrate-ui-assistant`: hoy `chat.js` de la `ui` toma como
texto cualquier línea `data:`. Tiene que distinguir los eventos con nombre
(`products`, `tool`, `cart-updated`, `done`, `error`) de los fragmentos sin
nombre, e ignorar los comentarios `:keepalive`. En particular, tiene que
manejar `cart-updated` para refrescar el contador y la vista del carrito sin
recargar: cuando llega, `GET /carts/{customerId}` ya refleja el cambio.

#### Log por turno

Cada turno deja una línea en el logger `assistant.turn` con la sesión (8
caracteres), el resultado, la intención, si la reescritura usó el fallback, el
mensaje crudo, la consulta reescrita, los filtros, si hubo razonamiento, los
ids del top-k con la consulta reescrita y con la cruda (calculado después de
cerrar el stream; se apaga con `RETAIL_ASSISTANT_CHAT_COMPARE_RAW_RETRIEVAL`),
el solapamiento, las requests a NVIDIA y las latencias. Con las tools suma
`modelCalls` (vueltas del modelo principal), `tools` (cada tool con su
resultado), `claimGuard` (la salvaguarda de agregados: `-`, o
`dropped:<oraciones>` con `+retry` si hubo vuelta correctiva y `+notice` si se
agregó el aviso), `corrections` (`-` o los motivos de las correcciones:
`claim`, `announce`, `textual`; ver "Tools"), `limiterWaitMs` y `retries429`; `nvidiaRequests` cuenta la
reescritura más todas las vueltas, y `rewrite=rate-limited` indica que el
limitador no tenía lugar y la reescritura se salteó:

```
session=demo-rea outcome=done intent=other rewrite=ok raw="add the Tinted Glass Pendant Light to my cart" query="" minPrice=- maxPrice=- excludeTags=[] searched=false catalog=ok reasoning=off reasoningChars=0 topk=[] rawTopk=- overlap=- nvidiaRequests=3 modelCalls=2 tools=addToCart:ok claimGuard=- corrections=- limiterWaitMs=0 retries429=0 rewriteMs=5679 retrievalMs=0 firstFragmentMs=7964 totalMs=8987
```

## Tools

En cada turno, el modelo principal tiene tres tools (el de reescritura no tiene
ninguna). El ciclo lo controla el `assistant` y no Spring AI: el modelo se
llama en streaming con la ejecución interna de tools desactivada, el
`assistant` ejecuta los tool calls, emite el evento `tool` y vuelve a llamar al
modelo con los resultados, hasta que responde en texto. Por turno hay como
máximo 4 llamadas al modelo principal (la última con `tool_choice: "none"`,
para que termine en texto) y 6 tools; con la reescritura, de 2 a 5 requests a
NVIDIA.

| Tool | Argumentos | Resultado (JSON para el modelo) |
| --- | --- | --- |
| `searchProducts` | `query?`, `tags?[]`, `minPrice?`, `maxPrice?` (enteros, inclusivos), `order?` (`relevance`, `price_asc`, `price_desc`), `limit?` (1 a 10, por defecto 5). Al menos uno de `query`, `tags` o precios | `{"products": [{id, name, price, tags, description}], "source": "semantic"\|"catalog", "degraded"?}` |
| `getProductDetails` | `productId` (UUID) | `{id, name, price, tags, description}` |
| `addToCart` | `productId` (UUID), `quantity?` (1 a 10, por defecto 1) | `{"added": {id, name, quantity, unitPrice}, "cartItemCount"?}` |

- `searchProducts` con `query` busca en Qdrant (tags con OR y rango de precio
  sobre el payload), y después lee cada resultado con
  `GET /catalog/products/{id}`: el precio, el nombre y los tags son los
  vigentes, y el rango y el orden por precio se aplican sobre el precio vivo.
  Sin `query`, usa `GET /catalog/products?tags=&order=` (hasta 10 páginas de
  50), filtra el precio en el `assistant` y no gasta embeddings. Si con
  `query` la búsqueda semántica no está disponible y hay tags o precios,
  responde por el camino sin texto con `"degraded": "semantic-search-unavailable"`.
- `getProductDetails` lee `GET /catalog/products/{id}` en cada llamada, sin
  caché.
- `addToCart` no recibe el cliente: usa el `X-Session-ID` del turno como
  `customerId`, así que el modelo no puede tocar el carrito de otra sesión.
  Lee el precio vivo con `GET /catalog/products/{id}`, hace
  `POST /carts/{customerId}/items` con `{itemId, quantity, unitPrice}` (igual
  que el botón de la `ui`) y lee `GET /carts/{customerId}` para
  `cartItemCount`. Con `carts` en memoria, agregar un producto que ya está en
  el carrito suma una línea, como en la `ui`. En un mismo turno, el mismo
  producto no se agrega dos veces.
- Las lecturas al `catalog` tienen tiempos límite de 2 s (conexión) y 5 s
  (lectura) y se reintentan una vez ante 5xx, timeout o error de red. El
  `POST` al carrito no se reintenta, para no duplicar la línea.

**Limitación conocida.** Con la memoria que reenvía los tool calls, ante un
pedido ambiguo ("add the lamp to my cart" después de mostrar tres lámparas) el
modelo agrega la primera en lugar de preguntar en alrededor de 1 de cada 5
intentos (0 de 5 sin esa memoria). El agregado se ve en `cart-updated`. Las
mediciones están en el `design.md` de `add-assistant-tools` ("Correcciones
posteriores").

**Salvaguarda de agregados al carrito.** El texto del modelo pasa por un
filtro por oración (`CartClaimFilter`): si una oración afirma que algo se
agregó al carrito ("have been added to your cart", "*adds two lamps to cart*",
"agregué … al carrito") y en el turno no hubo un `addToCart` correcto, no se
envía. Las preguntas, negaciones, condiciones y ofrecimientos ("want me to add
it?", "I didn't add anything", "you can add it from the product page") pasan.
Si la vuelta que cierra el turno tuvo una afirmación descartada, el
`assistant` hace una vuelta correctiva con un aviso al modelo: si el usuario
pidió agregar, el modelo llama a `addToCart`; si no, corrige sin afirmar nada.
Lo mismo pasa si la respuesta termina anunciando una acción que no hizo ("Let
me check our inventory…", "*getting current price*"), con un aviso que le pide
llamar a la tool. Como máximo hay dos vueltas correctivas por turno, si quedan
vueltas. Si el usuario pidió agregar al carrito y el modelo no le estaba
preguntando nada, la vuelta correctiva va con `tool_choice: required`. Si igual no hubo agregado, la respuesta termina con "Heads-up,
Operative: nothing was added to your cart in this turn…". Y si el modelo
escribe un tool call como texto (`[addToCart: {"productId": "…"}]`) en lugar
de pedirlo, ese texto no se muestra y se ejecuta como tool call. La memoria
guarda lo que vio el usuario, sin las afirmaciones descartadas ni los avisos al
modelo. Cada oración descartada queda en el log.

Los argumentos se validan en el servidor antes de llamar a cualquier servicio,
y los errores vuelven al modelo como resultado, para que los explique:
`{"error": "<tipo>", "message": "...", ...}`.

| `error` | Cuándo |
| --- | --- |
| `invalid-argument` | Un argumento inválido; `argument` lo nombra. Para tags desconocidos trae `validTags`. También para un JSON ilegible o una tool inexistente |
| `missing-criteria` | `searchProducts` sin `query`, ni tags, ni precios |
| `product-not-found` | `catalog` respondió 404 |
| `already-added-this-turn` | El producto ya se agregó en este turno; no se llama a `carts` |
| `tool-budget-exhausted` | Ya se ejecutaron 6 tools en el turno |
| `catalog-unavailable` | `catalog` falló también en el reintento, o no se pudieron leer los tags |
| `cart-unavailable` | `carts` falló en el `POST`: el producto no se agregó |
| `search-unavailable` | La búsqueda semántica no está disponible y no hay otro criterio |

Cada tool deja una línea en el logger `assistant.tool`, con la sesión truncada
a 8 caracteres (nunca completa) y sin la sesión en los argumentos:

```
session=demo-rea tool=searchProducts args={tags=[lighting], maxPrice=100, order="price_asc", limit=10} outcome=ok products=3 catalogCalls=1 cartsCalls=0 latencyMs=11
session=demo-rea tool=addToCart args={productId="84677bb1-a318-585a-b01c-565b4463cc4b"} outcome=ok products=0 catalogCalls=1 cartsCalls=2 latencyMs=156
```

Ejemplo, con el `assistant` local y `catalog` y `carts` en Docker (ver
"Running"): buscar con presupuesto, agregar y comprobar el carrito.

```bash
curl -sN -H 'X-Session-ID: demo-readme' -H 'Content-Type: application/json' \
  -d '{"message":"show me lamps under $100, cheapest first"}' localhost:8080/assistant/chat
curl -sN -H 'X-Session-ID: demo-readme' -H 'Content-Type: application/json' \
  -d '{"message":"add the Tinted Glass Pendant Light to my cart"}' localhost:8080/assistant/chat
curl -s localhost:8082/carts/demo-readme
```

```
event:products
data:[…]

event:tool
data:{"tool":"addToCart","ok":true}

event:cart-updated
data:{"itemId":"84677bb1-a318-585a-b01c-565b4463cc4b","name":"Tinted Glass Pendant Light","quantity":1,"unitPrice":89,"cartItemCount":1}

data:{"text":"Mission"}

…

event:done
data:{}
```

```json
{"customerId":"demo-readme","items":[{"itemId":"84677bb1-a318-585a-b01c-565b4463cc4b","quantity":1,"unitPrice":89}]}
```

En el cluster, el carrito se comprueba desde el pod de la `ui`:
`kubectl exec -n the-store deploy/ui -- curl -s http://carts/carts/demo-readme`.
Verificado el 2026-10-06 tal como está escrito, contra el `assistant` local
(`./mvnw spring-boot:run`) con Qdrant, `catalog` y `carts` en Docker.

### Limitador y 429

Todas las requests a NVIDIA (reescritura y vueltas del modelo principal, de
todas las sesiones) pasan por un limitador en el proceso: como máximo 36 en
cualquier ventana de 60 s (la cuota es de 40 RPM). La reescritura no espera:
si no hay lugar, el turno sigue con el mensaje crudo (`rewrite=rate-limited`).
Cada vuelta del modelo principal espera su lugar hasta 30 s; si la espera
sería mayor, el turno termina con `error` `llm-quota-exceeded` y
`retryAfterSeconds`, sin llamar a NVIDIA. Mientras espera, el `:keepalive`
mantiene viva la conexión, y el tiempo límite al primer fragmento empieza a
contar cuando el limitador otorga el lugar.

Si NVIDIA responde 429 a una vuelta antes de que llegue algún fragmento, el
limitador se pausa para todas las sesiones por el `Retry-After` (en segundos o
como fecha HTTP; 5 s si no viene) y la vuelta se reintenta hasta 2 veces. Un
429 en la reescritura pausa el limitador y usa el mensaje crudo, sin
reintentar. El limitador es por proceso: con más de una réplica, bajar
`RETAIL_ASSISTANT_RATE_LIMIT_REQUESTS_PER_MINUTE` en proporción.

Las reservas de la ventana actual están en el gauge
`assistant.ratelimit.window`:

```bash
curl -s localhost:8080/actuator/metrics/assistant.ratelimit.window
```

## Health

- `/actuator/health/liveness` y `/actuator/health/readiness`: solo dependen del
  proceso. Una caída de Qdrant o de los proveedores no saca al pod del Service.
- `/actuator/health`: incluye el componente `qdrant` (`UP` con la versión del
  servidor o `DOWN` con el error). Los proveedores en la nube no tienen health
  check para no gastar cuota.
- `/actuator/health` también incluye `productIndex`, el estado de la
  indexación: `UP` cuando terminó (con `points`, `lastSync` y los contadores
  de la última corrida en `lastRun`), `DOWN` si falló (con el motivo en
  `error`, por ejemplo `embedding-provider-unauthorized: …`) y `UNKNOWN`
  mientras no empezó o está sincronizando (`phase`). Igual que `qdrant`, no
  forma parte de la readiness.

```bash
curl -s localhost:8080/actuator/health | jq .components.productIndex
```

## Running

Pre-requisitos: Java 21 y Docker.

```bash
# Qdrant local
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.19.2-unprivileged

# catalog local en el puerto 8081 (en otra terminal), para la indexación
(cd ../catalog && PORT=8081 go run main.go)
# o catalog y carts en Docker, con las imágenes de ./local.sh reload-images
docker run -d --name catalog -p 8081:8080 the-store-catalog:latest
docker run -d --name carts -p 8082:8080 the-store-cart:latest

# Claves opcionales (desde el .env de la raíz del repo)
set -a; . ../../.env; set +a

./mvnw spring-boot:run
curl -s localhost:8080/actuator/health
```

Imagen del contenedor:

```bash
docker build -t the-store-assistant:latest .
```

## Tests

```bash
./mvnw test              # unitarios, de contexto y de integración, sin claves
./mvnw -Psmoke verify    # smoke contra los proveedores reales (ver abajo)
```

Los tests de integración con Qdrant (`ProductVectorRepositoryTest`,
`ProductIndexerTest`, `ProductSearchEndToEndTest`) levantan
`qdrant/qdrant:v1.19.2` con Testcontainers, así que necesitan Docker. El
catálogo de prueba sale de `../catalog/repository`.

Los smoke tests necesitan las claves en el entorno; sin ellas se saltean. No
reintentan ante un 429:

- `ProvidersSmokeIT`: una llamada de chat a cada modelo y un embedding
  (2 requests a NVIDIA y 1 a Gemini).
- `EmbeddingTaskTypeSmokeIT`: el mismo texto embebido como documento y como
  consulta da vectores distintos, y la clave placeholder da `UNAUTHORIZED`
  (2 requests a Gemini y 1 rechazada).
- `SearchQualitySmokeIT`: indexa el catálogo real en un Qdrant de
  Testcontainers y mide los criterios de calidad de la búsqueda (84 requests a
  Gemini: 80 para indexar, porque la cuota cuenta cada texto del lote, y 4
  consultas).
- `ModelSpikeSmokeIT`: spike de los modelos de chat (15 requests a NVIDIA, ver
  abajo).
- `RewriteEvalSmokeIT`: evaluación de la reescritura (10 requests a NVIDIA; en
  Gemini, 80 para indexar y unas 20 búsquedas, ver abajo). Con
  `-Dsmoke.qdrant.*` reutiliza una colección ya indexada y gasta solo las
  búsquedas.
- `ChatEndToEndSmokeIT`: los escenarios de la spec `assistant-chat` de punta a
  punta por `POST /assistant/chat` (unas 28 requests a NVIDIA; en Gemini, 80
  para indexar y 1 por turno con búsqueda).
- `ToolsEndToEndSmokeIT`: los escenarios de la spec `assistant-tools`, con
  `catalog` y `carts` falsos en el proceso (unas 40 requests a NVIDIA; en
  Gemini, 1 por turno con búsqueda y 1 por consulta de `searchProducts`). Acepta
  las mismas propiedades `-Dsmoke.qdrant.*` para no reindexar. Con `-Dsmoke.qdrant.host`,
  `-Dsmoke.qdrant.port` y `-Dsmoke.qdrant.collection` reutiliza una colección
  ya indexada (por ejemplo, la del `assistant` local) y no gasta las 80 de la
  indexación.
- `MultiTurnCartSmokeIT`: las tres sesiones largas del reporte de
  `integrate-ui-assistant` en las que el modelo confirmaba agregados sin llamar
  a `addToCart` (14 turnos, unas 35 requests a NVIDIA). Exige el `addToCart`, el
  `cart-updated` y el ítem en el carrito al final de cada sesión, revisa que
  ninguna respuesta afirme un agregado sin la tool ni nombre precios que no
  sean del catálogo, e imprime la memoria con los caracteres de los tool calls
  de cada turno. Acepta las mismas propiedades `-Dsmoke.qdrant.*`.

Para correr uno solo:
`./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=SearchQualitySmokeIT`.

### Benchmark de modelos (`select-assistant-models`)

Para comparar un modelo de chat contra los criterios de aceptación del change
`select-assistant-models` (D2 y D3 de su `design.md`), los smoke imprimen
líneas parseables, sin cambiar lo que afirman:

- `bench.result` (`ChatEndToEndSmokeIT`, `ToolsEndToEndSmokeIT` y
  `MultiTurnCartSmokeIT`): una por escenario o sesión, con `outcome`
  (`pass`, `fail` o `provider-error`), `falseClaims` (oraciones que afirman un
  agregado sin un `addToCart` correcto, con el chequeo `ADD_CLAIM` del smoke),
  `added` (`ok`, `none` o `wrong`) en los de carrito y `askedInsteadOfAdding`
  en el pedido ambiguo.
- `bench.rewrite` (`RewriteEvalSmokeIT` y el spike): una por reescritura, con
  `outcome` (`ok`, `fallback` o `invalid`), la latencia y, en la evaluación,
  los aciertos del top-5 crudo y reescrito.
- `bench.capability` (`ModelSpikeSmokeIT`): una por capacidad del modelo
  principal (streaming, razonamiento on/off, tool calling en streaming, ciclo
  controlado, segunda vuelta, `tool_choice` `none` y `required`).
- `bench.usage`: las requests a NVIDIA y a Gemini de la corrida.

`ModelSpikeSmokeIT#thinkingProbe` (D4) busca con qué `extra-body` se apaga o
se prende el razonamiento de un modelo nuevo: una request sin streaming por
variante, en orden, hasta la primera que cumple el criterio, con un timeout de
30 s y un reintento a los 60 s. Solo corre con `-Dspike.probe-variants`.

El runner `scripts/model-bench.sh` corre un smoke N veces con los modelos y
sus `extra-body` elegidos por `-D` (`spring.ai.openai.chat.options.model`,
`retail.assistant.models.rewrite` y `spring.application.json`, o `-Dspike.*`
en el spike), sin tocar los defaults versionados:

```bash
scripts/model-bench.sh --smoke MultiTurnCartSmokeIT --runs 5 \
  --main meta/muse-glimmer-30b \
  --main-on '{"chat_template_kwargs":{"enable_thinking":true}}' \
  --main-off '{"chat_template_kwargs":{"enable_thinking":false}}' \
  --rewrite meta/muse-glimmer-30b \
  --rewrite-extra '{"chat_template_kwargs":{"enable_thinking":false}}' \
  --qdrant localhost:6334/products --dry-run
```

| Flag | Qué hace |
| --- | --- |
| `--smoke <Clase[#método]>` | Smoke a correr |
| `--runs <n>` | Corridas secuenciales, con `--pause` segundos entre una y otra (default 60) |
| `--main`, `--main-on`, `--main-off` | Modelo principal y sus `extra-body` con el razonamiento prendido y apagado |
| `--rewrite`, `--rewrite-extra` | Modelo de reescritura y su `extra-body` |
| `--qdrant host:port/colección` | Colección ya indexada (`-Dsmoke.qdrant.*`). Antes de cada corrida verifica por REST (puerto 6333) que tenga 80 puntos |
| `--ledger <archivo>` | Ledger acumulado (default `target/model-bench/ledger.tsv`) |
| `--dry-run` | Imprime los comandos y el control del tope, sin correr nada |
| `-- <args>` | Argumentos extra para `./mvnw` (por ejemplo `-Dspike.probe-variants=...`) |

Cada corrida queda en `target/model-bench/<fecha>-<smoke>-<i>.log` y suma una
fila al ledger con las requests medidas (`bench.usage` y `nvidiaRequests` de
las líneas `assistant.turn`). El ledger fija los topes al crearse
(`MODEL_BENCH_CAP_NVIDIA`, 500, y `MODEL_BENCH_CAP_GEMINI`, 250, los de D5), y
el runner no arranca una corrida si lo consumido más su estimación los
superaría. También corta la serie si la sincronización embebió algún producto
(la colección tiene que dar `unchanged=80`, `providerRequests=0`).

El reporte arma en Markdown una tabla por corrida y otra total con M1 a M8 y R1
a R4 (valor, umbral, línea base y ✔/✘, con percentiles nearest-rank):

```bash
python3 scripts/model_bench_report.py target/model-bench            # todo
python3 scripts/model_bench_report.py --role rewrite target/model-bench/*Rewrite*.log
(cd scripts && python3 -m unittest test_model_bench_report)        # tests, con logs de ejemplo
```

Corridas del change (D5): 1 de spike, 3 de `RewriteEvalSmokeIT`,
`ChatEndToEndSmokeIT` y `ToolsEndToEndSmokeIT`, 2 sueltas del escenario
ambiguo y 5 de `MultiTurnCartSmokeIT`.

### Calidad de la búsqueda

Resultado de `SearchQualitySmokeIT` (2026-10-06, catálogo de 80 productos,
`gemini-embedding-001` 768d, consulta sin reescribir):

| Criterio de la spec `semantic-product-search`                       | Plantilla v1 (tags al final) | Plantilla v2 (tags antes de la descripción) |
| ------------------------------------------------------------------- | ---------------------------- | ------------------------------------------- |
| "somewhere cozy to curl up with a book" devuelve 3 o más productos  | OK (chaise lounge, sillón, lounge chair, biblioteca…) | OK |
| Top-1 de "mid century velvet armchair" en el top-3 con typos         | OK (top-1 en ambas)          | OK (top-1 en ambas)                         |
| "couch": un `seating` sin "couch" en el nombre en el top-3           | OK (sofás de cuero)          | OK                                          |
| 4 similares comparten el tag de tipo en al menos 2 casos             | 77 de 80                     | **78 de 80**                                |

Se usa la plantilla v2. Los dos productos que no cumplen el criterio de
similares son de `decor`, y quedan reportados a la curación del catálogo
(`replace-catalog-with-home-furniture`): `decor` tiene solo 5 productos y es
heterogéneo (2 almohadones, 2 macetas y 1 cuadro), así que ni
"Two-Toned Stoneware Planter" (tiene una sola maceta hermana) ni
"Abstract Topographic Print" (no tiene ningún producto parecido) pueden tener 2
similares del mismo tipo. El smoke los lista como excepciones conocidas; si
falla cualquier otro producto, falla.

La request de embeddings con los 80 textos tardó entre 3 s y 1 minuto según
la corrida; como la sincronización corre en segundo plano, no demora el
arranque.

### Spike de modelos de chat (`ModelSpikeSmokeIT`)

Verifica, contra NVIDIA y con Spring AI 1.1.8, lo que da por hecho el
pipeline de chat (D12 de `add-assistant-chat`): para el modelo principal,
streaming con `ChatClient.stream()`, razonamiento activado y desactivado por
request con `extraBody`, dónde llega el razonamiento y tool calling en
streaming; para el de reescritura, 10 llamadas que devuelven el JSON de la
reescritura y su latencia mediana. Gasta 15 requests a NVIDIA.

```bash
./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=ModelSpikeSmokeIT
```

Los modelos y sus `extra-body` se cambian con `-Dspike.main-model`,
`-Dspike.main-on`, `-Dspike.main-off`, `-Dspike.rewrite-model` y
`-Dspike.rewrite-extra` (JSON), para repetirlo con el plan B.

Resultado del 2026-10-06:

| Modelo | Uso | Streaming | Thinking on/off por request | Dónde llega el razonamiento | Tool calling en streaming | Latencia |
| --- | --- | --- | --- | --- | --- | --- |
| `nvidia/nemotron-3-super-120b-a12b` | Principal | OK (50 fragmentos) | OK: `{"chat_template_kwargs":{"enable_thinking":true\|false}}` | `reasoning_content`, fuera del texto (Spring AI lo deja en la metadata `reasoningContent`); nunca `<think>` en el texto | OK (1 invocación, respuesta con el precio de la tool) | Primer fragmento a 1,3 s sin razonamiento y a 2,5 s con razonamiento |
| `nvidia/nemotron-3.5-lightning-30b-a3b` | Reescritura | — (llamada sin streaming) | Off: `{"chat_template_kwargs":{"enable_thinking":false}}` | — | — | Mediana 1,5 s; 9 de 10 JSON válidos (el restante fue un timeout de red de 10 s) |

Los dos cumplen el criterio de D12, así que los defaults no cambian. Como el
razonamiento nunca llega dentro del texto, el `ThinkTagFilter` queda
desactivado por defecto (`retail.assistant.chat.reasoning.strip-think-tags`).

#### Tool calling controlado y 429 (`add-assistant-tools`)

Dos casos más del mismo spike, que se corren aparte:

```bash
./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='ModelSpikeSmokeIT#controlledToolCalling'
# la ráfaga agota la cuota de chat durante un minuto: solo con -Dspike.quota-burst=true
./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test='ModelSpikeSmokeIT#quotaBurst' -Dspike.quota-burst=true
```

`controlledToolCalling` (D1 de `add-assistant-tools`, 3 requests) llama al
modelo principal en streaming con `internalToolExecutionEnabled=false`, ejecuta
los tool calls con `ToolCallingManager` y hace una vuelta con
`tool_choice: "none"`. Resultado del 2026-10-06:

| Modelo | Tool calls en el stream | Ejecución con `ToolCallingManager` | Segunda vuelta | Vuelta con `tool_choice: "none"` |
| --- | --- | --- | --- | --- |
| `nvidia/nemotron-3-super-120b-a12b` | OK: llegan completos en un solo `ChatResponse` (id, nombre y argumentos JSON), sin que Spring AI los ejecute (1,6 s) | OK: historial `USER, ASSISTANT, TOOL` y el `ToolContext` llega a la tool sin figurar en el schema | OK: texto con el precio de la tool (0,9 s) | OK: 0 tool calls y respuesta en texto, con las tools definidas en el request |
| `deepseek-ai/deepseek-v4.1-flash` | Sin respuesta: el request no devolvió nada en 120 s (igual que en el spike de `add-assistant-chat`) | — | — | — |

Como Nemotron respeta `tool_choice: "none"`, la última vuelta del ciclo se
manda con las tools y `tool_choice: "none"`, como dice D1 (no hizo falta la
variante sin `tools`).

`quotaBurst` (D9) manda 45 requests mínimas (1 token de salida) en paralelo
al modelo de reescritura para provocar un 429 y ver si trae `Retry-After`.
Resultado del 2026-10-06 (se corrió una sola vez): **las 45 respondieron 200**,
en 34 s. NVIDIA no rechazó la ráfaga, sino que la demoró, así que no se pudo
ver el formato de un 429 real. Quedan el `default-retry-after=5s` de D9 para
el caso sin header y el parseo de los dos formatos de `Retry-After` (segundos y
fecha HTTP), cubierto por tests unitarios con un proveedor falso. El
limitador de 36 RPM sigue haciendo falta: la cuota que autorizó la cátedra es
de 40 RPM y NVIDIA puede empezar a aplicarla en cualquier momento.

#### Plan B

Los modelos del plan B no se pudieron verificar con la cuenta del grupo. Se
probaron dos veces el 2026-10-06 (de madrugada con el spike completo y, al
mediodía, con un único request mínimo de `max_tokens` 32 a cada uno):

| Modelo | Uso | Streaming | Thinking on/off por request | Tool calling en streaming | Latencia |
| --- | --- | --- | --- | --- | --- |
| `nvidia/nemotron-3-super-120b-a12b` | Principal | OK | OK (`enable_thinking`) | OK | Primer fragmento a 1,3 s (2,5 s con razonamiento) |
| `nvidia/nemotron-3.5-lightning-30b-a3b` | Reescritura | — | Off OK (`enable_thinking: false`) | — | Mediana 1,5 s |
| `deepseek-ai/deepseek-v4.1-flash` | Plan B anterior (descartado) | Sin verificar | Sin verificar | Sin verificar | Sin respuesta: el request no devolvió ni un byte en 100 s (spike) ni en 90 s (reintento) |
| `google/gemma-3-12b-it` | Plan B anterior (descartado) | Sin verificar | Sin verificar | Sin verificar | `404` "Function '…': Not found for account" en 0,5 s: el modelo figura en `/v1/models` pero la cuenta no tiene acceso |

Los `extra-body` que se habían propuesto para ellos (`chat_template_kwargs.thinking`
para DeepSeek y `{}` para Gemma) eran los de sus chat templates, sin verificar,
y se sacaron de la configuración documentada. Como los modelos principales
cumplen D12, los defaults no cambian. Ese plan B no es usable con esta cuenta,
y su reemplazo se evaluó en `select-assistant-models` (ver "Selección de
modelos" más abajo); candidatos
que la cuenta lista en `/v1/models` (solo leídos, sin llamarlos):
`google/gemma-4-31b-it`, `google/gemma-3-4b-it`, `openai/gpt-oss-20b`,
`nvidia/nemotron-nano-3-30b-a3b` y `nvidia/nemotron-3-ultra-550b-a55b`.
Elegir el reemplazo es una decisión del grupo; para probar uno alcanza con
`ModelSpikeSmokeIT` y las propiedades `-Dspike.*`.

### Modelos probados

| Fecha      | Uso         | Modelo                                  | Resultado                          |
| ---------- | ----------- | --------------------------------------- | ---------------------------------- |
| 2026-10-06 | Principal   | `nvidia/nemotron-3-super-120b-a12b`     | OK, responde con thinking apagado. Plan B del principal |
| 2026-10-06 | Reescritura | `nvidia/nemotron-3.5-lightning-30b-a3b` | OK, responde con thinking apagado. Plan B de la reescritura |
| 2026-10-06 | Embeddings  | `gemini-embedding-001`                  | OK, vector de 768 dimensiones      |
| 2026-10-06 | Principal y reescritura | `meta/muse-glimmer-30b`   | Primer intento: no adoptado. Segundo intento: adoptado en los dos roles por decisión del grupo (default; ver abajo) |

### Selección de modelos (`select-assistant-models`)

Evaluación secuencial de un único candidato, `meta/muse-glimmer-30b`, en los
dos roles, con los umbrales de D2 y D3 del change fijados antes de la primera
corrida y el benchmark de "Tests". **Resultado del primer intento: no se
adoptó en ningún rol. Resultado final (segundo intento, decisión del grupo):
`muse-glimmer-30b` se adopta en los dos roles, con los Nemotron como plan B**
(ver "Segundo intento" más abajo).

`muse-glimmer-30b` no permite apagar el razonamiento: con
`chat_template_kwargs.enable_thinking: false`, `chat_template_kwargs.thinking: false`,
`reasoning_effort: "none"` y `thinking.type: "disabled"`, siempre manda
`reasoning_content` (118 a 228 caracteres y 40 a 63 tokens para responder
"OK"). La ficha de build.nvidia.com solo documenta niveles (`low`, `medium`,
`high` y `xhigh`), y el modelo acepta `reasoning_effort` por request (`low`:
122 caracteres de razonamiento; `high`: 283). Por eso, el grupo decidió
evaluarlo con el esfuerzo mínimo como "sin razonamiento" (`low`) y `high` en
las comparaciones.

| Fecha | Rol | Candidato | `extra-body` off / on | Métrica | Umbral | Línea base | Candidato | ✔/✘ |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-06 | Principal | `meta/muse-glimmer-30b` | `{"reasoning_effort":"low"}` / `{"reasoning_effort":"high"}` | M1 Capacidades | Todas OK | Todas OK | Streaming OK; nivel por request OK, con el razonamiento en `reasoning_content` (985 / 2053 caracteres). Tool calling: **400** "duplicate field `reasoning_effort`" en todos los requests con tools (Spring AI 1.1.8 duplica el campo del `extra-body`). Con la opción nativa `reasoning-effort=low`: tool calls completos, segunda vuelta y `tool_choice: "none"` OK, pero **`tool_choice: "required"` ignorado** (0 tool calls) | ✘ |
| 2026-10-06 | Principal | | | M2 a M8 | D2 | — | No se midieron: parada temprana por M1 (D1) | — |
| 2026-10-06 | Principal | | | Primer fragmento de texto en el spike (sin umbral) | — | 1,3 s / 2,5 s | 9,6 s con `low` / 20,5 s con `high` | — |
| 2026-10-06 | Principal | | | **Resultado** | | | **No adoptado**: falla M1 (`tool_choice: "required"`, del que depende la vuelta correctiva de los pedidos de carrito, y tool calling incompatible con `reasoning_effort` en el `extra-body`) | ✘ |
| 2026-10-06 | Reescritura | `meta/muse-glimmer-30b` | `{"reasoning_effort":"low"}` (no se puede apagar), `max-tokens` 1024 | R1 JSON válido | ≤ 1 inválida | 9/10 | 0 inválidas de 15 que respondieron | ✔ |
| 2026-10-06 | Reescritura | | | R2 Latencia | p50 < 1,5 s y p95 ≤ 5 s | Mediana 1,5 s; p95 ≈ 10 s | p50 5,0 s, p95 10,1 s (20 llamadas; ninguna debajo de 1,5 s) | ✘ |
| 2026-10-06 | Reescritura | | | R3 Fallback | ≤ 2/40 | 12-13 % | 5/20 (25 %): cortes a los 10 s | ✘ |
| 2026-10-06 | Reescritura | | | R4 Top-5 | Media ≥ 45 y cada corrida > 42 | 45, 43, 47 | 44 contra 42 en la única corrida (no se completaron las 3) | — |
| 2026-10-06 | Reescritura | | | **Resultado** | | | **No adoptado**: fallan R2 y R3 (parada temprana después de 1 de las 3 corridas de `RewriteEvalSmokeIT`) | ✘ |

Consumo de la evaluación (ledger): 45 requests a NVIDIA de 500 y 18 a Gemini
de 250, con 0 embeddings de indexación (la colección se restauró desde un
snapshot y cada sincronización dio `unchanged=80`, `providerRequests=0`).

Observaciones para el próximo candidato:

- Los 5 fallbacks de la reescritura cortaron a los **10 s**, no a los 12 s de
  `RETAIL_ASSISTANT_REWRITE_TIMEOUT`: el `read-timeout` de los `RestClient`
  (`spring.http.client.read-timeout: 10s`) también alcanza al cliente de
  NVIDIA, así que el tiempo máximo efectivo de la reescritura era 10 s.
  Corregido en el segundo intento (`read-timeout` de 30 s, validado al arrancar).
- Un modelo con `reasoning_effort` no podía llevarlo en el `extra-body`.
  Corregido en el segundo intento (ver "Razonamiento y plan B").
- Con `low`, el razonamiento de muse crece con el pedido: 985 caracteres
  para una descripción de cuatro oraciones, y eso explica los 9,6 s al primer
  fragmento.

#### Segundo intento

Corridas del 2026-10-06 (19:29 a 20:24) con `scripts/model-bench.sh`, un ledger aparte (`target/model-bench/intento2/ledger.tsv`, topes 550 NVIDIA y 280 Gemini aprobados por el grupo) y la colección `products` restaurada desde el snapshot (cada sincronización: `unchanged=80`, `providerRequests=0`). Antes de medir se corrigieron los tres problemas de integración del primer intento:

- **`reasoning_effort` duplicado (400 con tools):** `ExtraBody` lo manda por la opción nativa del request, una sola vez y con el nivel del turno (`ReasoningEffortRequestTest` mira el cuerpo crudo).
- **`tool_choice: "required"` ignorado por muse:** `retail.assistant.tools.corrective-tool-choice=prompt`, en el que la vuelta correctiva de un pedido de carrito no manda `tool_choice` y le pide el tool call en el aviso. `CartClaimFilter` sigue bloqueando las confirmaciones falsas.
- **`read-timeout` de 10 s contra los 12 s de la reescritura:** pasa a 30 s, y el servicio no arranca si no es mayor que el tiempo límite de la reescritura.

Configuración medida: principal `meta/muse-glimmer-30b` con `{"reasoning_effort":"low"}` / `{"reasoning_effort":"high"}` y `corrective-tool-choice=prompt`; reescritura por defecto de ese momento (`nemotron-3.5-lightning`). M7 no fue parada temprana: se midió para que el grupo decida el trade-off entre calidad y latencia.

**Rol principal** (M1 a M8 contra la línea base registrada):

| Métrica | Umbral (D2) | Línea base (Nemotron) | `muse-glimmer-30b` | ✔/✘ |
| --- | --- | --- | --- | --- |
| M1 Capacidades | Todas OK | Todas OK | Con el fix, tool calls en streaming, ciclo controlado, segunda vuelta y `tool_choice: "none"` OK, y el nivel por request OK (primer intento). `tool_choice: "required"` sigue ignorado: se reemplaza por el modo `prompt` | ✔ con la adaptación (`required` ✘) |
| M2 Confirmaciones falsas | 0 | 0 | **0** en todas las corridas. Una afirmación sin `addToCart` la descartó el filtro, y la vuelta correctiva en modo `prompt` terminó en `addToCart:ok` | ✔ |
| M3 Producto equivocado | 0 | 0 | **0** de 20 agregados | ✔ |
| M4 `MultiTurnCartSmokeIT` | ≥ 10/15 | 3/6 (50 %) | **14/15** (93 %). La restante fue un timeout de 20 s al primer fragmento ("cheaper") | ✔ |
| M5 Ambiguo: agrega sin preguntar | ≤ 1/5 | 1/5 | 0 de 2 muestras válidas; las otras 3 se cortaron por el timeout de 20 s | — (no concluyente) |
| M6 Escenarios estables 3/3 | 3/3 | Pasan | Chat: los 5 estables 3/3. Tools: `lampsUnder100CheapestFirst`, `diningTableUnder300InSpanish` y `cartsDownIsNotConfirmed` 3/3; `priceRightNowComesFromGetProductDetails` 1/3, `addThatOneToMyCart` 2/3 y `addTwoOfTheFirstOne` 2/3, todas las fallas por el timeout de 20 s al primer fragmento (ninguna respuesta incorrecta) | ✘ (por latencia) |
| M7 Latencia del modelo al primer texto visible | `low` p50 ≤ 3 s y p95 ≤ 8 s; `high` máx ≤ 30 s | 1,3 s (spike); 18 s en una comparación | `low`: p50 **6,8 s**, p95 **19,6 s**, máx 51,1 s (137 turnos). `high`: máx 49,6 s (8 turnos). **Primer evento de razonamiento:** `low` p50 1,2 s y p95 4,7 s; `high` p50 1,3 s. Lo que ve el usuario (`firstFragmentMs` completo): p50 9,3 s y p95 24,1 s | ✘ |
| M8 Requests a NVIDIA por turno | ≤ 3,5 | 3,1 | **2,43** (145 turnos) | ✔ |
| **Resultado** | | | **Adoptado por decisión del grupo** (mejor que Nemotron en calidad del carrito; peor en latencia) | |

Sin umbral: inestables `cheaper` 3/3, `notALamp` 3/3, `justifiedComparisonWithReasoning` 3/3, `answersInTheUserLanguage` 3/3 (Nemotron: 1/5 y 2/5 en castellano), `greetingDoesNotSearch` 2/3 y `stalePayloadPriceIsNotShown` 2/3. `ChatEndToEndSmokeIT`: 29/30 escenarios en 3 corridas. Correcciones: `claim+prompt` 1 y `announce` 1. Reescritura (Nemotron) en esos turnos: fallback 6/145 (4 %).

**Rol de reescritura:** la comparación lado a lado con Nemotron en la misma sesión (spike de reescritura y `RewriteEvalSmokeIT`, ×3 cada uno) **no se corrió, por decisión del grupo**. Quedan los valores del primer intento, medidos con el `read-timeout` de 10 s que cortaba antes de tiempo: R1 0 inválidas de 15 (✔), R2 p50 5,0 s y p95 10,1 s (✘), R3 5/20 fallbacks, todos cortes a los 10 s (✘), R4 44 contra 42 en una sola corrida. **Adoptado por decisión del grupo**, sin R1 a R4 medidos con el fix: el grupo prioriza un único modelo en los dos roles (D8). El riesgo conocido es la latencia de la reescritura, que con el razonamiento obligatorio puede caer al fallback de 12 s.

**Fallas por latencia y fix posterior.** Las 8 fallas `llm-provider-unavailable` (1 en MultiTurn, 6 en Tools y 1 en el ambiguo suelto) fueron el tiempo límite de 20 s al primer fragmento: muse razona siempre y el primer texto visible llegaba después. Después de las corridas, el tiempo límite cuenta como primer fragmento al primer chunk con texto, razonamiento o un tool call (`reasoningContentCountsAsTheFirstFragment`). En los turnos que sí terminaron, el primer razonamiento llegó con un p95 de 4,7 s, así que es esperable que la mayoría de esos cortes desaparezcan, pero no se volvió a medir.

**Eventos SSE y latencia percibida (sin cambios en la `ui`).** El `assistant` no expone el razonamiento: emite `products` (después de la reescritura y la búsqueda, antes del modelo), los fragmentos de texto, `tool` (cada tool ejecutada, con su nombre y resultado), `cart-updated`, `done` o `error`, y comentarios `:keepalive`. El razonamiento solo se cuenta (`reasoningChars` y, desde este intento, `firstReasoningMs` en la línea `assistant.turn`). La `ui` reenvía los eventos con `data` y muestra un spinner hasta el primer texto. `products` y `tool` llegan al navegador, pero `chat.js` los ignora (D4 de `integrate-ui-assistant`). Hoy la `ui` podría mostrar "buscando…" o "consultando el precio…" con esos dos eventos sin cambiar el `assistant`. Para un "pensando…" haría falta un evento nuevo del `assistant`, por ejemplo uno sin contenido al llegar el primer `reasoning_content` (sería ≈ 1,2 s de p50 contra 6,8 s del primer texto). No se recomienda mandar el contenido del razonamiento: rompería la persona y podría filtrar el prompt.

**Cuota del segundo intento (ledger):**

| Etapa | NVIDIA | Gemini |
| --- | --- | --- |
| Revalidación de M1 (`controlledToolCalling` + `mainCallsToolsWhileStreaming`) | 6 | 0 |
| `MultiTurnCartSmokeIT` ×5 | 170 | 35 |
| `ToolsEndToEndSmokeIT` ×3 | 83 | 20 |
| `#ambiguousRequestAsksInsteadOfAdding` ×2 | 7 | 2 |
| `ChatEndToEndSmokeIT` ×3 | 92 | 38 |
| **Total** | **358 de 550** | **95 de 280** |

Embeddings de indexación: 0. Con el primer intento, el change suma 403 requests a NVIDIA y 113 a Gemini. Docker y Qdrant local quedaron apagados.

### Evaluación de la reescritura (`RewriteEvalSmokeIT`)

`src/test/resources/rewrite-eval.json` tiene 10 consultas (conversacionales,
con muletillas, con errores de tipeo, con referencia a un turno previo simulado
y en español) y, para cada una, el predicado que cumplen los productos
esperados (tags y precio). El test busca cada consulta cruda y reescrita y
cuenta los productos esperados en el top-5. Resultado de la última corrida
(2026-10-06):

| Consulta | Tipo | Mensaje | Consulta reescrita | Top-5 crudo | Top-5 reescrito |
| --- | --- | --- | --- | --- | --- |
| conversational-reading-corner | conversacional con muletillas | `hey, so my reading corner is kinda sad, got anything comfy to sink into?` | `comfy reading chair` | 5 | 5 |
| filler-desk-lamp | muletillas | `ummm i guess i need like a lamp or something for my desk lol` | (fallback: el mensaje crudo) | 5 | 5 |
| typos-velvet-armchair | errores de tipeo | `lookin for a mid sentury velvit armchiar` | `mid-century velvet armchair` | 5 | 5 |
| previous-turn-leather | referencia a un turno previo | `something similar but in leather` | `leather armchair` | 5 | 4 |
| previous-turn-cheaper | referencia a un turno previo | `too pricey, anything cheaper?` | `sofa`, `maxPrice=988` | 1 | 5 |
| previous-turn-not-a-lamp | referencia a un turno previo | `nah, not a lamp. what else could make it cozier?` | `cozy home decor seating tables storage rugs beds`, excluye `lighting` | 3 | 5 |
| spanish-rug | en español | `busco una alfombra para el living` | `rug for living room` | 5 | 5 |
| spanish-desk | en español | `necesito un escritorio para trabajar desde casa` | `home office desk` | 5 | 5 |
| filler-bookshelf | muletillas y abreviaturas | `smth to put my books on, like a shelf i guess` | `bookshelf` | 4 | 4 |
| conversational-bed | conversacional | `ok so where do I even sleep in this lair? need something big enough for two` | `bed for two people` | 4 | 4 |
| **Total** | | | | **42** | **47** |

En tres corridas la reescrita acertó 45, 43 y 47 contra 42 de la cruda. La
ganancia está en las referencias al turno anterior ("cheaper", "not a lamp"),
que sin reescritura no tienen de dónde sacar el precio ni la exclusión; con
consultas autocontenidas, los embeddings de `gemini-embedding-001` ya toleran
muletillas, errores de tipeo y español, y las dos dan lo mismo. La única
pérdida es `previous-turn-leather` (5 → 4).

### Escenarios de punta a punta (`ChatEndToEndSmokeIT`)

Recorre los escenarios de la spec `assistant-chat` contra el `assistant`
completo, con NVIDIA y Gemini reales:

```bash
./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ChatEndToEndSmokeIT \
  -Dsmoke.qdrant.host=localhost -Dsmoke.qdrant.port=6334 -Dsmoke.qdrant.collection=products
```

Resultado del 2026-10-06, reutilizando la colección del `assistant` local (la
sincronización dio 80 sin cambios y 0 requests a Gemini): 10 de 10, con 30
requests a NVIDIA (15 turnos de 2) y un embedding por turno con búsqueda.

| Escenario | Resultado | Intentos |
| --- | --- | --- |
| Saludo sin búsqueda ni embeddings | OK (`intent=other`) | 3: la primera corrida reveló que el prompt de reescritura clasificaba los saludos como búsquedas (se corrigió pasando el mensaje como user); la segunda falló porque la reescritura superó los 5 s y cayó al fallback |
| Recomendación con productos reales (cada nombre y precio está en `products`) | OK | 1 |
| "a gaming laptop": sin productos inventados | OK | 1 |
| "cheaper": todos los productos del segundo turno más baratos | OK (`maxPrice=128` sobre un recomendado de $139) | 2: en la segunda corrida la reescritura cayó al fallback por el tiempo límite de 5 s |
| "not a lamp": ningún `lighting` | OK (`excludeTags=[lighting]`) | 1 |
| "which of those is the cheapest?" y sesiones aisladas | OK | 1 |
| Comparación con precios, diferencia, dos atributos y recomendación condicionada | OK (`intent=compare`, `reasoning=on`, sin razonamiento en el stream) | 1 |
| Búsqueda simple sin razonamiento (2 requests a NVIDIA) | OK | 1 |
| Respuesta en español | OK | 1 |
| Intento de revelar el system prompt | OK (`intent=other`, no filtra el prompt) | 1 |

Por la latencia bimodal de la reescritura (ver `POST /assistant/chat`), en la corrida que pasó 2 de las 15 reescrituras igual
superaron los 12 s y usaron el mensaje crudo ("a gaming laptop" y la sesión
aislada); ninguno de esos dos escenarios depende de los filtros.


### Escenarios de las tools (`ToolsEndToEndSmokeIT`)

Recorre los escenarios de la spec `assistant-tools` contra el `assistant`
completo, con NVIDIA y Gemini reales, el catálogo real servido por HTTP y un
`carts` en memoria que se puede "tirar":

```bash
./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ToolsEndToEndSmokeIT \
  -Dsmoke.qdrant.host=localhost -Dsmoke.qdrant.port=6334 -Dsmoke.qdrant.collection=products
```

Resultado del 2026-10-06, reutilizando la colección local (0 requests a Gemini
para indexar). Se corrió 3 veces completo; entre corridas se ajustaron los
prompts, y con los prompts finales pasaron los 8 escenarios (el de `carts`
caído, en una cuarta corrida suelta, porque en la tercera la respuesta era
correcta pero el test buscaba otras palabras).

| Escenario | Resultado | Intentos |
| --- | --- | --- |
| "how much is the Aiden Mid-Century Velvet Armchair right now?": `getProductDetails` y el precio de la tool | OK: `GET /catalog/products/{id}` registrado y $139 en la respuesta | 3: en las dos primeras la reescritura lo clasificó como `other` (sin contexto), el modelo inventó un id (`invalid-argument`) y respondió con el precio vivo de `searchProducts`, sin llamar a `getProductDetails`. Se corrigió la regla de la reescritura y el mensaje de error de `productId` ("si no tenés el id, buscá primero"); en la tercera, 4 vueltas: id inválido, búsqueda, `getProductDetails` y texto |
| "show me lamps under $100, cheapest first" | OK: `tags=[lighting]`, `maxPrice=100`, `order=price_asc`, 3 lámparas ordenadas | 1 (en la prueba manual previa, el modelo usó `maxPrice=99` y no pasó tags: se reforzó el prompt) |
| "busco una mesa de comedor de menos de 300 dólares" | OK: `tags=[dining,tables]`, `maxPrice=300`, respuesta en español | 1 |
| "add that one to my cart" | OK: el carrito tiene el sillón a $139, evento `cart-updated` con `cartItemCount=1`, y los carritos de otras sesiones no cambian | 1 |
| "add two of the first one to my cart" | OK: una línea con cantidad 2 y el precio del catálogo | 2: en la segunda corrida el modelo dijo "Mission accomplished… added" **sin llamar a `addToCart`**. Se agregó la regla 8 del system prompt (solo se agrega con `addToCart` y no se confirma sin `added`) |
| "add the lamp to my cart" con tres lámparas mostradas | OK: pregunta cuál y el carrito no cambia | 1 |
| `carts` caído | OK: `addToCart` con `cart-unavailable`, sin `cart-updated`, `done`, y la respuesta dice que no se agregó | 2: en la tercera corrida la respuesta era correcta ("systems are currently offline… retry") pero el test no reconocía esas palabras; se amplió el patrón |
| Precio del payload alterado en Qdrant (`setPayload` a $7) | OK: la respuesta da el precio vivo ($149) y no el del payload | 2: en la segunda corrida la respuesta daba $149 pero mencionaba "the $7 field report I had on file". Se agregó al prompt que, con el precio de una tool, no se menciona el del contexto |

Limitaciones observadas: el modelo de reescritura a veces clasifica como
`other` una pregunta por un producto que no se mostró, y entonces el turno
gasta una o dos vueltas más (id inválido y búsqueda) dentro del presupuesto de
4; la latencia bimodal de la reescritura (ver `POST /assistant/chat`) hizo caer
al fallback 0, 3 y 1 de las 11 reescrituras de cada corrida.

### Verificación en el cluster

Verificado el 2026-10-06 en kind (`./local.sh reload-images` y
`dist/kubernetes.yaml`):

1. **Sin reindexar:** se subió al Qdrant del cluster un snapshot de la
   colección ya indexada en local (`POST /collections/products/snapshots/upload`,
   con el `contentHash` en el payload). Al arrancar con las claves, la
   sincronización dio `80 sin cambios, 0 requests al proveedor de embeddings`.
2. **Conversación de tres turnos** desde el pod de la `ui`, con la misma
   sesión:

   ```bash
   kubectl exec -n the-store deploy/ui -- curl -sN -H 'X-Session-ID: cluster-demo' \
     -H 'Content-Type: application/json' -d '{"message":"I'"'"'m looking for a velvet armchair"}' \
     http://assistant/assistant/chat
   ```

   | Turno | Reescritura | Resultado |
   | --- | --- | --- |
   | "I'm looking for a velvet armchair" | `velvet armchair` | Recomienda el Aiden Mid-Century Velvet Armchair ($139). Top-k reescrito y crudo con solapamiento 4 |
   | "cheaper" | `velvet armchair`, `maxPrice=128` | Todos los productos por debajo de $139; recomienda la Allie Velvet Dining Chair ($109). Solapamiento 0: la consulta cruda "cheaper" trae alfombras y macetas |
   | "compare the first two" | `intent=compare`, `reasoning=on` (3.045 caracteres de razonamiento, fuera del stream) | Compara los dos primeros del turno anterior con precios y diferencia ($90), recomendación condicionada. Primer fragmento a 18 s |

   Cada turno dejó su línea `assistant.turn` en `kubectl logs deploy/assistant`
   con `topk` y `rawTopk`.
3. **No expuesto:** `POST http://localhost/assistant/chat` (por el ingress)
   responde el `404` de la `ui` y no llega al `assistant`.
4. **Sin clave de NVIDIA** (`./local.sh update-secrets` sin `NVIDIA_API_KEY`):
   el turno manda `products` y termina con
   `event:error` / `{"type":"llm-provider-unauthorized",…}`; el pod sigue
   `1/1 Running` y `/actuator/health/readiness` en `UP`. Después se restauró la
   clave con `./local.sh update-secrets`.

#### Tools en el cluster (`add-assistant-tools`)

Verificado el 2026-10-06 en kind (`./local.sh reload-images` y
`dist/kubernetes.yaml`, con el ConfigMap con las variables de las tools y del
limitador; `kubectl apply --dry-run=server` sin errores). Igual que antes, se
subió el snapshot de la colección con el Secret en placeholder y después se
cargaron las claves: la sincronización dio `80 sin cambios, 0 requests al
proveedor de embeddings`.

1. **Conversación de tres turnos** desde el pod de la `ui`, con la sesión
   `cluster-tools`:

   | Turno | Tools | Resultado |
   | --- | --- | --- |
   | "I need a rug for the living room under $300, cheapest first" | `searchProducts` con `tags=[living-room,rugs]`, `maxPrice=300`, `order=price_asc` | Presenta las dos alfombras más baratas ($59) |
   | "how much is the first one right now?" | `getProductDetails` del Geometric Wool Area Rug | "remains at $59", con el precio de la tool |
   | "add it to my cart" | `addToCart` | `cart-updated` con `unitPrice` 59 y `cartItemCount` 1 |

   ```bash
   kubectl exec -n the-store deploy/ui -- curl -s http://carts/carts/cluster-tools
   # {"customerId":"cluster-tools","items":[{"itemId":"1077927c-…","quantity":1,"unitPrice":59}]}
   ```

   `kubectl logs deploy/assistant` tiene una línea `assistant.tool` por tool y
   las líneas `assistant.turn` con `nvidiaRequests=3 modelCalls=2` y
   `tools=searchProducts:ok`, `tools=getProductDetails:ok` y
   `tools=addToCart:ok`.
2. **Diez turnos en paralelo** con sesiones distintas (búsquedas con
   presupuesto y orden), lanzados a la vez desde el pod de la `ui` mientras se
   leía `assistant.ratelimit.window` cada segundo: los 10 terminaron con `done`
   en 5 a 15 s, con 31 requests a NVIDIA en total (3 por turno, 4 en uno que
   buscó dos veces). El gauge llegó a 31 y nunca superó 36; en una ráfaga
   anterior de 9 turnos, sumada a la conversación de arriba, llegó a 35. Como
   esa carga no alcanza el tope, el limitador no tuvo que demorar ningún turno
   (`limiterWaitMs=0`, ninguna reescritura `rate-limited`): la espera y el
   rechazo con `llm-quota-exceeded` están cubiertos por los tests de
   `ChatRateLimiterTest` y `ToolCallingLoopTest`.
