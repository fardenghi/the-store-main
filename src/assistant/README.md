# The Store - Assistant Service

| Language | Persistence       |
| -------- | ----------------- |
| Java     | Qdrant (vectores) |

Servicio que concentra la lógica GenAI de la tienda: es el único que habla con
el LLM (NVIDIA, API compatible con OpenAI), con el modelo de embeddings
(`gemini-embedding-001` de Google) y con el vector store (Qdrant). Al arrancar
indexa el catálogo en Qdrant y expone la búsqueda semántica de productos, los
productos similares y el chat con el asistente (persona A.G.E.N.T., reescritura
de consulta, RAG y memoria por sesión); las tools llegan en el change
siguiente.

## Configuración

Los valores no secretos tienen default en `application.yml` y se sobreescriben
con variables de entorno (en el cluster, desde el ConfigMap `assistant`).

| Name                                                  | Description                                          | Default                                 |
| ----------------------------------------------------- | ---------------------------------------------------- | --------------------------------------- |
| `PORT`                                                | Puerto HTTP                                          | `8080`                                  |
| `NVIDIA_API_KEY`                                      | Clave de NVIDIA (chat). Viene del Secret             | `not-configured`                        |
| `GOOGLE_API_KEY`                                      | Clave de la Gemini API (embeddings). Viene del Secret | `not-configured`                        |
| `SPRING_AI_OPENAI_BASE_URL`                           | URL base del proveedor de chat                       | `https://integrate.api.nvidia.com`      |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`                 | Modelo principal (razonamiento + tools)              | `nvidia/nemotron-3-super-120b-a12b`     |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MAX_TOKENS`            | Límite de tokens de salida (NVIDIA lo exige)         | `1024`                                  |
| `RETAIL_ASSISTANT_MODELS_REWRITE`                     | Modelo de reescritura de consultas                   | `nvidia/nemotron-3.5-lightning-30b-a3b` |
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
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_FIRST_TOKEN`          | Espera máxima del primer fragmento sin razonamiento  | `20s`                                   |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_FIRST_TOKEN_REASONING` | Espera máxima del primer fragmento con razonamiento | `60s`                                   |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_TURN`                 | Duración máxima de un turno                          | `120s`                                  |
| `RETAIL_ASSISTANT_CHAT_TIMEOUTS_KEEPALIVE`            | Intervalo del comentario `:keepalive` sin eventos    | `10s`                                   |
| `SPRING_APPLICATION_JSON`                             | Campos extra de los requests a NVIDIA (`extra-body`), ver abajo | —                            |

Sin claves el servicio arranca igual y queda listo: al iniciar loguea, sin
mostrar valores, si cada clave está configurada o es el placeholder. Las
llamadas al proveedor con el placeholder fallan con 401/403.

### Razonamiento y plan B (`extra-body`)

Cada modelo activa o desactiva el razonamiento con un campo distinto del
request, así que esos campos (`extra-body`) son mapas configurables. Por
defecto son los de Nemotron:

| Propiedad                                       | Default                                               |
| ----------------------------------------------- | ----------------------------------------------------- |
| `retail.assistant.rewrite.extra-body`           | `{"chat_template_kwargs": {"enable_thinking": false}}` |
| `retail.assistant.chat.reasoning.on-extra-body` | `{"chat_template_kwargs": {"enable_thinking": true}}`  |
| `retail.assistant.chat.reasoning.off-extra-body`| `{"chat_template_kwargs": {"enable_thinking": false}}` |

Se reemplazan con `SPRING_APPLICATION_JSON` en el ConfigMap. El mapa que se
define reemplaza completo al default (los defaults viven en `ChatProperties` y
no en el YAML porque Spring combina las claves de un mapa definido en varias
fuentes), y un mapa vacío `{}` manda el request sin campos extra. Para pasar al
plan B, en el ConfigMap `assistant`:

```yaml
  SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL: deepseek-ai/deepseek-v4.1-flash
  RETAIL_ASSISTANT_MODELS_REWRITE: google/gemma-3-12b-it
  SPRING_APPLICATION_JSON: >-
    {"retail.assistant.chat.reasoning.on-extra-body": {"chat_template_kwargs": {"thinking": true}},
     "retail.assistant.chat.reasoning.off-extra-body": {"chat_template_kwargs": {"thinking": false}},
     "retail.assistant.rewrite.extra-body": {}}
```

y `kubectl rollout restart deployment/assistant -n the-store`. Los
`extra-body` de DeepSeek y Gemma son los esperados según su chat template: no
se pudieron verificar (ver "Spike de modelos de chat" más abajo).

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
10 turnos (mensaje y respuesta final, sin razonamiento ni contexto) y los
productos del último turno con búsqueda; se olvida a los 30 minutos sin uso y
se pierde al reiniciar el pod. Un turno que termina con error o que el cliente
corta no se guarda.

| Evento                  | `data`                                       | Cuándo                                                        |
| ----------------------- | -------------------------------------------- | ------------------------------------------------------------- |
| `products`              | `[{"id", "name", "price"}]`                  | Una vez, antes del primer fragmento. Vacío si no hubo búsqueda |
| (sin nombre)            | `{"text": "<fragmento>"}`                    | Cada fragmento de la respuesta; concatenados, el texto completo |
| `done`                  | `{}`                                         | Fin correcto                                                  |
| `error`                 | `{"type", "detail", "retryAfterSeconds"?}`   | Falla después de abrir el stream                              |
| comentario `:keepalive` | —                                            | Cada 10 s sin otros eventos (por ejemplo, mientras el modelo razona) |

Tipos del evento `error`:

| `type`                       | Cuándo                                                                 |
| ---------------------------- | ---------------------------------------------------------------------- |
| `llm-quota-exceeded`         | NVIDIA respondió 429. Trae `retryAfterSeconds` si NVIDIA mandó `Retry-After` |
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
| `409`  | `session-busy`      | La sesión ya tiene un turno en curso                                     |

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
(`products`, `done`, `error`) de los fragmentos sin nombre, e ignorar los
comentarios `:keepalive`.

#### Log por turno

Cada turno deja una línea en el logger `assistant.turn` con la sesión (8
caracteres), el resultado, la intención, si la reescritura usó el fallback, el
mensaje crudo, la consulta reescrita, los filtros, si hubo razonamiento, los
ids del top-k con la consulta reescrita y con la cruda (calculado después de
cerrar el stream; se apaga con `RETAIL_ASSISTANT_CHAT_COMPARE_RAW_RETRIEVAL`),
el solapamiento, las requests a NVIDIA y las latencias:

```
session=demo outcome=done intent=search rewrite=ok raw="ummm i need like a lamp for my desk lol" query="desk lamp" minPrice=- maxPrice=- excludeTags=[] searched=true catalog=ok reasoning=off reasoningChars=0 topk=[…] rawTopk=[…] overlap=4 nvidiaRequests=2 rewriteMs=1210 retrievalMs=310 firstFragmentMs=2650 totalMs=4100
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
  Gemini, 80 para indexar y unas 20 búsquedas, ver abajo).
- `ChatEndToEndSmokeIT`: los escenarios de la spec `assistant-chat` de punta a
  punta por `POST /assistant/chat` (unas 28 requests a NVIDIA; en Gemini, 80
  para indexar y 1 por turno con búsqueda). Con `-Dsmoke.qdrant.host`,
  `-Dsmoke.qdrant.port` y `-Dsmoke.qdrant.collection` reutiliza una colección
  ya indexada (por ejemplo, la del `assistant` local) y no gasta las 80 de la
  indexación.

Para correr uno solo:
`./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=SearchQualitySmokeIT`.

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

#### Plan B

Los modelos del plan B no se pudieron verificar con la cuenta del grupo. Se
probaron dos veces el 2026-10-06 (de madrugada con el spike completo y, al
mediodía, con un único request mínimo de `max_tokens` 32 a cada uno):

| Modelo | Uso | Streaming | Thinking on/off por request | Tool calling en streaming | Latencia |
| --- | --- | --- | --- | --- | --- |
| `nvidia/nemotron-3-super-120b-a12b` | Principal | OK | OK (`enable_thinking`) | OK | Primer fragmento a 1,3 s (2,5 s con razonamiento) |
| `nvidia/nemotron-3.5-lightning-30b-a3b` | Reescritura | — | Off OK (`enable_thinking: false`) | — | Mediana 1,5 s |
| `deepseek-ai/deepseek-v4.1-flash` | Plan B principal | Sin verificar | Sin verificar | Sin verificar | Sin respuesta: el request no devolvió ni un byte en 100 s (spike) ni en 90 s (reintento) |
| `google/gemma-3-12b-it` | Plan B reescritura | Sin verificar | Sin verificar | Sin verificar | `404` "Function '…': Not found for account" en 0,5 s: el modelo figura en `/v1/models` pero la cuenta no tiene acceso |

Los `extra-body` del ejemplo de arriba (`chat_template_kwargs.thinking` para
DeepSeek y `{}` para Gemma, que no tiene modo de razonamiento) son los de sus
chat templates, sin verificar. Como los modelos principales cumplen D12, los
defaults no cambian. El plan B de hoy no es usable con esta cuenta; candidatos
que la cuenta lista en `/v1/models` (solo leídos, sin llamarlos):
`google/gemma-4-31b-it`, `google/gemma-3-4b-it`, `openai/gpt-oss-20b`,
`nvidia/nemotron-nano-3-30b-a3b` y `nvidia/nemotron-3-ultra-550b-a55b`.
Elegir el reemplazo es una decisión del grupo; para probar uno alcanza con
`ModelSpikeSmokeIT` y las propiedades `-Dspike.*`.

### Modelos probados

| Fecha      | Uso         | Modelo                                  | Resultado                          |
| ---------- | ----------- | --------------------------------------- | ---------------------------------- |
| 2026-10-06 | Principal   | `nvidia/nemotron-3-super-120b-a12b`     | OK, responde con thinking apagado  |
| 2026-10-06 | Reescritura | `nvidia/nemotron-3.5-lightning-30b-a3b` | OK, responde con thinking apagado  |
| 2026-10-06 | Embeddings  | `gemini-embedding-001`                  | OK, vector de 768 dimensiones      |

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
