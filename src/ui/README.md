# AWS Containers Retail Sample - UI Service

| Language | Persistence |
| -------- | ----------- |
| Java     | N/A         |

This service provides the frontend for the retail store, serving the HTML UI and aggregating calls to the backend API components.

## Configuration

The following environment variables are available for configuring the service:

| Name                              | Description                                                                                            | Default                 |
| --------------------------------- | ------------------------------------------------------------------------------------------------------ | ----------------------- |
| `PORT`                            | The port which the server will listen on                                                               | `8080`                  |
| `RETAIL_UI_THEME`                 | Name of the theme for the UI, valid values are `default`, `green`, `orange`                            | `"default"`             |
| `RETAIL_UI_DISABLE_DEMO_WARNINGS` | Disable the UI messages warning about demonstration content                                            | `false`                 |
| `RETAIL_UI_PRODUCT_IMAGES_PATH`   | Overrides the location where the sample product images are sourced from to use the specified file path | ``                      |
| `RETAIL_UI_ENDPOINTS_CATALOG`     | The endpoint of the catalog API. If set to `false` uses a mock implementation                          | `false`                 |
| `RETAIL_UI_ENDPOINTS_CARTS`       | The endpoint of the carts API. If set to `false` uses a mock implementation                            | `false`                 |
| `RETAIL_UI_ENDPOINTS_ORDERS`      | The endpoint of the orders API. If set to `false` uses a mock implementation                           | `false`                 |
| `RETAIL_UI_ENDPOINTS_CHECKOUT`    | The endpoint of the checkout API. If set to `false` uses a mock implementation                         | `false`                 |
| `RETAIL_UI_ENDPOINTS_ASSISTANT`   | The endpoint of the assistant service. Required with the `assistant` chat provider; if empty the product page shows no similar products | `""` |
| `RETAIL_UI_CHAT_ENABLED`          | Enable the chat bot UI                                                                                 | `false`                 |
| `RETAIL_UI_CHAT_PROVIDER`         | The chat provider to use, valid values are `assistant`, `bedrock`, `openai`, `mock`                    | `""`                    |
| `RETAIL_UI_CHAT_MODEL`            | The chat model to use, depends on the provider. Only for `mock`, `openai` and `bedrock`               | `""`                    |
| `RETAIL_UI_CHAT_TEMPERATURE`      | Model temperature. Only for `mock`, `openai` and `bedrock`                                             | `0.6`                   |
| `RETAIL_UI_CHAT_MAX_TOKENS`       | Model maximum response tokens. Only for `mock`, `openai` and `bedrock`                                 | `300`                   |
| `RETAIL_UI_CHAT_BEDROCK_REGION`   | Amazon Bedrock region                                                                                  | `""`                    |
| `RETAIL_UI_CHAT_OPENAI_BASE_URL`  | Base URL for OpenAI endpoint                                                                           | `http://localhost:8888` |
| `RETAIL_UI_CHAT_OPENAI_API_KEY`   | API key for OpenAI endpoint                                                                            | `""`                    |
| `RETAIL_UI_ASSISTANT_CONNECT_TIMEOUT`    | Timeout to open a connection with the assistant service | `2s` |
| `RETAIL_UI_ASSISTANT_CHAT_TIMEOUT`       | Total time limit of a chat turn forwarded to the assistant | `160s` |
| `RETAIL_UI_ASSISTANT_KEEPALIVE_INTERVAL` | Interval between SSE keepalive comments sent to the browser during a chat turn (any provider) | `10s` |
| `RETAIL_UI_ASSISTANT_SIMILAR_K`          | Number of similar products shown in the product page | `4` |
| `RETAIL_UI_ASSISTANT_SIMILAR_TIMEOUT`    | Time limit to fetch the similar products of the product page | `2s` |

`RETAIL_UI_CHAT_MODEL`, `RETAIL_UI_CHAT_TEMPERATURE` y `RETAIL_UI_CHAT_MAX_TOKENS` solo aplican a los providers `mock`, `openai` y `bedrock`, que mandan únicamente el mensaje del usuario, sin system prompt.

## Asistente de compras (chat y similares)

El botón de chat llama a `POST /chat/submit` de la `ui`, que responde con un stream de Server-Sent Events. El provider se elige con `RETAIL_UI_CHAT_PROVIDER`:

- `assistant`: reenvía el mensaje al servicio `assistant` (`POST {RETAIL_UI_ENDPOINTS_ASSISTANT}/assistant/chat`) con el header `X-Session-ID` y el cuerpo `{"message": "..."}`, y retransmite sus eventos a medida que llegan. La sesión sale siempre de la cookie `SESSIONID` (el mismo id que el carrito usa como `customerId`); un `X-Session-ID` enviado por el navegador se ignora. Con este provider la `ui` no llama a ningún modelo de lenguaje. Si `RETAIL_UI_ENDPOINTS_ASSISTANT` está vacío, la `ui` no arranca.
- `mock`, `openai`, `bedrock`: llaman a un modelo directamente con Spring AI, solo con el mensaje del usuario. Sirven para correr la `ui` sin el `assistant` (por ejemplo, `docker compose up` usa `mock`).

La persona A.G.E.N.T. y su system prompt viven en el `assistant`, no en la configuración de la `ui`.

`RETAIL_UI_ENDPOINTS_ASSISTANT` también lo usa la ficha de producto para mostrar los similares (`GET /assistant/products/{id}/similar?k=`), sin importar el provider del chat. Si el endpoint está vacío, la llamada falla o tarda más que `RETAIL_UI_ASSISTANT_SIMILAR_TIMEOUT`, la ficha se muestra sin esa sección.

Eventos que recibe el navegador, con cualquier provider:

| Evento                 | `data`                                                | Qué hace el chat                                              |
| ---------------------- | ----------------------------------------------------- | ------------------------------------------------------------- |
| sin nombre (`message`) | `{"text": "<fragmento>"}`                             | Agrega el fragmento a la respuesta, renderizada como markdown |
| `products`             | Productos recuperados por el assistant (RAG)          | Se ignora                                                     |
| `tool`                 | `{tool, ok, error?, products?}`                       | Se ignora                                                     |
| `cart-updated`         | `{itemId, name, quantity, unitPrice, cartItemCount?}` | Actualiza el contador del carrito, y la lista en `/cart`      |
| `done`                 | `{}`                                                  | Termina el turno                                              |
| `error`                | `{type, detail, retryAfterSeconds?}`                  | Muestra un aviso según el `type` y termina el turno           |
| comentario `:keepalive` | -                                                    | Se ignora (cada `RETAIL_UI_ASSISTANT_KEEPALIVE_INTERVAL`)     |

Todo turno termina con exactamente un evento `done` o `error` y HTTP 200. Además de los errores del `assistant` (por ejemplo `llm-quota-exceeded`), la `ui` genera estos tipos:

| `type`                  | Cuándo                                                                                                       |
| ----------------------- | ------------------------------------------------------------------------------------------------------------ |
| `invalid-parameter`     | El assistant respondió `400` (se reenvía el `detail`)                                                        |
| `session-busy`          | El assistant respondió `409`: hay otro turno de la misma sesión en curso                                     |
| `assistant-unavailable` | Otro estado, conexión rechazada, timeout de conexión, stream cortado sin `done` o `RETAIL_UI_ASSISTANT_CHAT_TIMEOUT` excedido |

Si el navegador cierra la conexión, la `ui` cierra la conexión con el `assistant`, que cancela el turno y libera la sesión. La conversación visible en el chat no se conserva al navegar entre páginas, pero el `assistant` mantiene la memoria de la sesión.

### Verificación en el cluster (`integrate-ui-assistant`)

Verificado el 2026-10-06 en kind (`./local.sh create-cluster`, `dist/kubernetes.yaml`), con `NVIDIA_API_KEY` y `GOOGLE_API_KEY` válidas y el índice cargado desde un snapshot de Qdrant (la sincronización dio `80 sin cambios, 0 requests al proveedor de embeddings`). Cada sesión es un contexto nuevo de Playwright (equivalente a una ventana privada) contra `http://localhost`.

- **e2e:** `./local.sh e2e-test` pasa 15/15 sin claves, con claves y con el `assistant` en 0 réplicas.
- **Sesión compartida:** la primera visita directa a `/catalog/{id}` crea una sola cookie `SESSIONID` con `Path=/`, `HttpOnly` y `SameSite=Lax`, y el chat de esa página usa la misma sesión (mismo id en el log del `assistant`). En otra sesión que también entró por una ficha, el agregado desde el chat aparece en `/cart`.
- **Ficha de un sillón** (Aiden Mid-Century Velvet Armchair): 4 similares, los 4 asientos (Eva Tufted Velvet Sofa, Frederick Channel-Tufted Velvet Sofa, Alonzo Velvet Loveseat, Lauren Oversized Down-Filled Armchair), cada uno con imagen, nombre, precio y enlace.
- **Respuesta de más de 60 s:** con un `assistant` falso que tarda 70 s en el primer fragmento, `POST /chat/submit` a través del ingress recibe un `:keepalive` cada 10 s y la respuesta completa con `done` a los 70 s, sin corte. Una comparación real con razonamiento tardó 20 s.

| Caso | Intentos | Resultado |
| --- | --- | --- |
| Búsqueda semántica ("I need a lamp for my desk") | 2 | En el primer intento el turno recuperó las lámparas correctas (`topk` en el log), pero la respuesta no nombró ningún producto. En una sesión nueva nombró la Curved Brass and Walnut Desk Lamp, la Adjustable Pharmacy Desk Lamp y la Scandinavian Blond-Wood Table Lamp, con precio |
| Errores de tipeo ("lookin for a mid sentury velvit armchiar") | 1 | Recomienda el Aiden Mid-Century Velvet Armchair ($139) y la Bertha Velvet Swivel Office Chair |
| "cheaper" y "not a lamp" en turnos seguidos | 2 | La recuperación fue correcta las dos veces (`maxPrice=128` y `excludeTags=[lighting]`). En el primer intento la respuesta a "cheaper" no listó productos. En el segundo, "cheaper" listó las opciones de $79 y "not a lamp" terminó con productos que no son lámparas, aunque mencionó las lámparas anteriores "como referencia" y nombró algunos productos que no están en el catálogo |
| Comparación de dos productos | 1 | Compara medidas y precios (diferencia de $250) con una recomendación, en 20 s |
| "how much is X right now?" | 1 | Da el precio vigente ($139) |
| "add two of the first one to my cart" en el catálogo | 2 | Falló en la primera sesión (ver abajo). En una sesión nueva, después de la búsqueda, el contador pasó de 0 a 2 durante el turno, sin recargar y con la conversación visible |
| El mismo pedido con `/cart` abierto | 2 | Falló en el tercer turno de una sesión (ver abajo). En una sesión nueva la lista pasó de vacía a "Curved Brass and Walnut Desk Lamp x2" ($298) a los 3,2 s, en medio del turno, con el chat abierto |

**Limitación conocida del `assistant` (pendiente fuera de este change):** en sesiones de más de dos o tres turnos, el modelo principal a veces deja de llamar a las tools y responde como si las hubiera usado ("Two lamps have been added to your cart"). En el log `assistant.turn` esos turnos tienen `tools=-` y `modelCalls=1`, y el carrito no cambia. Como no hay `cart-updated`, la `ui` no actualiza el contador, que es el comportamiento esperado. En una sesión nueva, con el pedido en el primer o segundo turno, las tools funcionan (`tools=searchProducts:ok,addToCart:ok` y evento `cart-updated`).

**Aislamiento de fallas:**

- Con el `assistant` en 0 réplicas, el chat muestra "The assistant is unavailable right now" en 0,1 s, la ficha responde en 0,6 s con el botón de agregar al carrito y sin similares, y los e2e (incluido el checkout completo) pasan. Al volver a 1 réplica, la sincronización no reembebe nada.
- `http://localhost/assistant/products/search?q=lamp` y `/assistant/products/{id}/similar` por el ingress devuelven el 404 de la `ui` y no aparecen en el log del `assistant`.
- Dos navegadores distintos conversando en paralelo: cada uno recibe respuestas de su propia conversación (el segundo, preguntado por su historial, responde "You asked about rugs") y el agregado del primero no aparece en el carrito del segundo.
- Pestaña cerrada en medio de una respuesta: la `ui` cancela el turno y cierra la conexión con el `assistant` al instante (24 ms después del cierre, según su log), pero el `assistant` libera el lock de la sesión entre 2 y 4,5 s más tarde. Un mensaje enviado desde otra pestaña a los 3 s recibió `session-busy` (una vez); a los 6 s se respondió normalmente. Queda como pendiente del `assistant`.

## Endpoints

Several "utility" endpoints are provided with useful functionality for various scenarios:

| Method | Name                           | Description                                                                 |
| ------ | ------------------------------ | --------------------------------------------------------------------------- |
| `GET`  | `/utility/status/{code}`       | Returns HTTP response with given HTTP status code                           |
| `GET`  | `/utility/headers`             | Print the HTTP headers of the inbound request                               |
| `GET`  | `/utility/panic`               | Shutdown the application with an error code                                 |
| `POST` | `/utility/echo`                | Write back the POST payload sent                                            |
| `POST` | `/utility/store`               | Write the payload to a file and return a hash                               |
| `GET`  | `/utility/store/{hash}`        | Return the payload from the file system previously written                  |
| `GET`  | `/utility/stress/{iterations}` | Stress the CPU with the number of iterations increasing the CPU consumption |

## Running

There are two main options for running the service:

### Local

Pre-requisites:

- Java 21 installed

Run the Spring Boot application like so:

```
./mvnw spring-boot:run
```

Test the application by visiting `http://localhost:8080` in a web browser.

### Docker

A `docker-compose.yml` file is included to run the service in Docker:

```
docker compose up
```

Test the application by visiting `http://localhost:8080` in a web browser.

To clean up:

```
docker compose down
```
