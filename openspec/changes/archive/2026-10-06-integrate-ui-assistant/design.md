# Design

## Context

La motivación está en `proposal.md` (Why) y el comportamiento exigido en `specs/ui-assistant-integration/spec.md`. Este change parte del siguiente estado:

- **`ui` (Spring Boot 3.5, WebFlux + Thymeleaf, Spring AI 1.0.0, paquete `com.amazon.sample.ui`).**
  - `ChatController` (`POST /chat/submit`) existe solo con `retail.ui.chat.enabled=true`. Usa un bean `ChatClient` que crea `MockChatConfig`, `OpenAIChatConfig` o `BedrockChatConfig` según `retail.ui.chat.provider`, le agrega como system prompt `retail.ui.chat.prompt` (la persona A.G.E.N.T. de gadgets espía) y devuelve eventos SSE sin nombre con `data` `{"text": ...}`. No manda evento de fin.
  - `chat.js` divide cada chunk leído por `\n\n` y parsea como texto cualquier línea `data:`. Se rompe con un evento partido entre dos chunks, con los eventos con nombre y con los comentarios. Renderiza con showdown en `innerHTML`, sin sanitizar.
  - `SessionIDWebFilter` lee la cookie `SESSIONID` o, si no existe, genera un UUID y la crea con `ResponseCookie.from(...)` sin atributos. Después **sobrescribe** el header `X-Session-ID` del request con ese valor, que es lo que leen `CartController` y `CommonAttributesControllerAdvice`. Como la cookie no tiene `Path`, el navegador le asigna el directorio del request: si la primera visita es `/catalog/{id}`, la cookie queda en `/catalog` y `/cart` o `/chat/submit` reciben otra sesión.
  - El contador del carrito de `fragments/layout.html` es un `<span>` sin id con `${cart.numItems}`, que suma las cantidades. La página `/cart` muestra los ítems dentro de `#basket`.
  - `CatalogController.item` arma un atributo `recommendations` (6 productos al azar), pero `detail.html` no lo renderiza. Además, `fragments/product_card.html` toma la imagen de `${item.id}` en lugar de `${product.id}`: funciona en `catalog.html` porque ahí la variable de iteración se llama `item`, pero en la ficha mostraría la imagen del producto exhibido.
  - Las URLs de los servicios están en `EndpointProperties` (`retail.ui.endpoints.*`, por ejemplo `RETAIL_UI_ENDPOINTS_CATALOG=http://catalog` en el `ConfigMap` `ui`). Si un endpoint está vacío, la `ui` usa un mock. El chat está deshabilitado en `dist/kubernetes.yaml`.
- **Contrato del `assistant`, definido por los changes de los que depende:**
  - `add-assistant-chat` (D1): `POST /assistant/chat` con `{"message"}` y `X-Session-ID` (hasta 128 caracteres de `[A-Za-z0-9_-]`). Antes de abrir el stream puede responder `ProblemDetail` `400 invalid-parameter` o `409 session-busy`. El stream trae fragmentos sin nombre `{"text"}`, `products`, `done`, `error` (`{type, detail, retryAfterSeconds?}`) y el comentario `:keepalive` cada 10 s. Si el cliente corta la conexión, el turno se cancela y se libera el lock de la sesión. Tiene `spring.mvc.async.request-timeout=150s` y un tiempo límite de 120 s por turno.
  - `add-assistant-tools` (D7): agrega los eventos `tool` (`{tool, ok, error?, products?}`) y `cart-updated` (`{itemId, name, quantity, unitPrice, cartItemCount?}`). Cuando se emite `cart-updated`, `GET /carts/{customerId}` ya refleja el cambio. Los dos changes dejaron anotado para este que `chat.js` tiene que distinguir los eventos con nombre y manejar `cart-updated`.
  - `add-product-indexing` (D8): `GET /assistant/products/{id}/similar?k=` (k de 1 a 12, por defecto 4) devuelve `[{id, name, description, price, tags: [nombres], score}]`, con `404 product-not-found` y `503 index-unavailable`, y no llama a Gemini. El precio es el del payload de Qdrant, que alcanza para la ficha porque el catálogo es estático en runtime.
  - `add-assistant-service` (D6): `Service` `assistant` en el puerto 80, al que la `ui` llama como `http://assistant`. No tiene endpoint `/topology`.
- **Red.** El ingress-nginx enruta todo `/` del host `localhost` a la `ui`. Su `proxy-read-timeout` por defecto es de 60 s, y una comparación con razonamiento puede tardar hasta 60 s en dar el primer fragmento, más hasta 30 s de espera en el limitador de cuota.
- **CI.** El workflow levanta el cluster **sin claves** y corre los e2e de Cypress (`src/e2e`). En ese escenario el `assistant` está Ready, el chat termina en `llm-provider-unauthorized` y el índice queda vacío, así que los similares responden `503`.

## Goals / Non-Goals

**Goals:**
- Que la `ui` siga siendo solo presentación: reenvía, retransmite y dibuja, sin interpretar el contenido del turno ni decidir nada de GenAI.
- Un único contrato SSE entre la `ui` y el navegador, igual para todos los providers, que siempre termina en `done` o `error`.
- Que ninguna falla del `assistant` degrade el resto de la tienda ni el CI sin claves.

**Non-Goals:**
- Mostrar en el chat tarjetas de los productos de los eventos `products` o `tool` (ver D4).
- Persistir o restaurar la conversación visible al navegar entre páginas. La memoria vive en el `assistant`, así que el contexto se conserva aunque se pierdan las burbujas del DOM. El botón "clear" solo limpia la vista: borrar la memoria de una sesión es un Non-Goal de `add-assistant-chat`.
- Agregar el `assistant` a la página `/topology`, porque no expone `/topology`.
- Cambiar los servicios `catalog` o `carts`, el contrato del `assistant` o el ingress.
- Búsqueda semántica en la página del catálogo. La pre-entrega la ubica en el chat, y el catálogo sigue filtrando por un tag a la vez.

## Decisions

### D1. El provider `assistant` es un `ChatStreamService`, no un `ChatModel` de Spring AI

`ChatController` pasa a depender de una interfaz propia:

```java
Flux<ServerSentEvent<String>> stream(String sessionId, String message)
```

Tiene dos implementaciones, elegidas por `retail.ui.chat.provider`:

- `AssistantChatStreamService` (`provider=assistant`): un `WebClient` contra el `assistant` (D3).
- `SpringAiChatStreamService` (`mock`, `openai`, `bedrock`): envuelve el `ChatClient` que ya crean las configuraciones actuales, emite cada fragmento como `{"text"}` y agrega `done` al final (D8).

`ChatController` toma la sesión con `SessionIDUtil.getSessionId(request)`, igual que `CartController`, delega en el servicio y le suma el keepalive (D5).

- **Alternativa descartada: implementar el `assistant` como un `ChatModel` de Spring AI** para no tocar el `ChatController`. Un `ChatModel` recibe un `Prompt` y devuelve `ChatResponse`. No tiene dónde llevar el `X-Session-ID` por request ni cómo representar `cart-updated`, `done` y `error`, y obligaría a mandar un system prompt que el `assistant` ignora.
- **Alternativa descartada: API compatible con OpenAI usando `base-url`.** Ya la descartó la pre-entrega (sección 5) por los mismos motivos: no propaga la sesión ni los eventos propios.
- **Alternativa descartada: que el navegador llame directo al `assistant`.** Habría que exponerlo en el ingress y confiar en una sesión elegida por el cliente. Además, la pre-entrega fija `ui ↔ assistant` y el `assistant` como servicio interno.

### D2. Configuración: endpoint en `retail.ui.endpoints.assistant` y bloque `retail.ui.assistant`

```yaml
retail.ui:
  endpoints:
    assistant:            # http://assistant en el cluster; vacío = sin assistant
  assistant:
    connect-timeout: 2s
    chat-timeout: 160s     # tiempo total del turno visto desde la ui
    keepalive-interval: 10s
    similar-k: 4
    similar-timeout: 2s
  chat:
    enabled: false
    provider:              # mock | openai | bedrock | assistant
    model:                 # solo para mock/openai/bedrock
    temperature: 0.6       # ídem
    max-tokens: 300        # ídem
```

- El endpoint va junto a los de los demás servicios (`EndpointProperties`, variable `RETAIL_UI_ENDPOINTS_ASSISTANT`), porque lo usan tanto el chat como la ficha. Los similares no dependen de que el chat esté habilitado.
- `chat-timeout` es de 160 s, un poco más que el `request-timeout` de 150 s del `assistant`, para que en condiciones normales el corte lo decida el `assistant` con un evento `error` propio y no la `ui`.
- `provider=assistant` sin endpoint hace fallar el arranque (spec). Con los otros providers, un endpoint vacío solo apaga los similares, igual que hoy un endpoint vacío activa el mock de cada servicio.
- Se elimina `retail.ui.chat.prompt`: el campo de `ChatProperties`, el texto de `application.yml`, la entrada en `additional-spring-configuration-metadata.json` y la fila del `README.md` de la `ui`. `model`, `temperature` y `max-tokens` quedan, pero solo para los providers de Spring AI.
- Todo se mapea con `@ConfigurationProperties` (`AssistantProperties`), con los defaults en `application.yml`.

### D3. Reenvío del chat al `assistant` (`AssistantChatStreamService`)

- **Request:** `POST {endpoint}/assistant/chat` con `Content-Type: application/json`, `Accept: text/event-stream`, el header `X-Session-ID` y el cuerpo `{"message": "<texto>"}`. No se manda ningún system prompt ni otro campo.
- **Respuesta:** `bodyToFlux(ServerSentEvent<String>)`, que conserva el nombre del evento y el `data` como texto crudo. Cada evento con `data` se retransmite **tal cual** (mismo nombre y mismo JSON), incluidos `products` y `tool`. La `ui` no los interpreta; el navegador decide qué dibujar (D4). Los comentarios del `assistant` no se retransmiten, porque la `ui` genera sus propios keepalives (D5).
- **Errores antes del stream**, traducidos a un único evento `error` con HTTP 200, para que el navegador tenga un solo camino:

| Situación | `type` del evento `error` |
|---|---|
| `400` del `assistant` | `invalid-parameter` (con el `detail` del `ProblemDetail`) |
| `409` del `assistant` | `session-busy` |
| Otro estado, conexión rechazada, DNS o `connect-timeout` | `assistant-unavailable` |

- **Durante el stream:** si el flujo termina sin `done` ni `error`, o falla la conexión, la `ui` agrega `error` `assistant-unavailable`. Si se supera `chat-timeout`, cancela el upstream y emite el mismo error. Un `error` del `assistant` (`llm-quota-exceeded`, etc.) se retransmite sin cambios y cierra el stream.
- **Cancelación:** el `Flux` del `WebClient` queda encadenado a la respuesta al navegador. Si el navegador cierra, WebFlux cancela la suscripción, Reactor Netty cierra la conexión con el `assistant` y el `assistant` libera el lock (D2 de `add-assistant-chat`). No hace falta código extra, solo no usar `cache()`, `share()` ni suscripciones sueltas.
- El `WebClient` se construye con `ReactorClientHttpConnector` y `connect-timeout`, sin `responseTimeout` por lectura: un turno con razonamiento puede pasar más de 60 s sin bytes de datos, y el límite total es `chat-timeout`. Igual que `TopologyController`, se arma con `WebClient.builder()` del contexto, para que lleve la instrumentación de OpenTelemetry si está activa.
- Una línea de log por turno (sesión truncada a 8 caracteres, estado del upstream, evento final y duración), sin el texto del mensaje.

### D4. Contrato SSE `ui` ↔ navegador y nuevo `chat.js`

El navegador recibe: fragmentos sin nombre `{"text"}`, `products`, `tool`, `cart-updated`, `done`, `error` y comentarios de keepalive. `chat.js` se reescribe en la parte de lectura:

- **Parser SSE con buffer:** acumula el texto decodificado (`TextDecoder` con `stream: true`), separa eventos por línea en blanco aunque lleguen partidos entre chunks, junta las líneas `data:` de un mismo evento, toma `event:` (sin nombre = `message`) e ignora las líneas que empiezan con `:`.
- **Despacho por evento:**
  - `message`: concatena al texto de la burbuja y re-renderiza.
  - `cart-updated`: actualiza el carrito (D6).
  - `done`: cierra el turno.
  - `error`: muestra el mensaje según el `type` y cierra el turno.
  - `products`, `tool` y los desconocidos: se ignoran.
- **Fin del turno:** el input se deshabilita desde el envío hasta `done`, `error` o el cierre del stream. Si el stream se cierra sin ninguno de los dos, se muestra el error de "asistente no disponible".
- **Mensajes de error:** en inglés, como el resto de los textos fijos de `chat.js` y la persona. Por ejemplo, `llm-quota-exceeded` con `retryAfterSeconds` muestra "Our operatives are overloaded, try again in ~N seconds". Si el turno ya había mostrado texto, el aviso se agrega debajo y no borra lo recibido.
- **Render seguro:** showdown convierte el markdown y DOMPurify (cdnjs, cargado igual que showdown en `fragments/root.html`) sanitiza el HTML antes de asignarlo a `innerHTML`. El mensaje del usuario sigue con `textContent`. El input suma `maxlength="2000"`, el mismo límite que valida el `assistant`.
- **Por qué no se dibujan `products` ni `tool`:** `products` trae los k productos recuperados por RAG, no los que el asistente recomienda. Mostrarlos como tarjetas contradiría la respuesta cuando el modelo descarta algunos, y el proposal no lo pide. Se retransmiten igual (D3), así que dibujarlos más adelante sería un cambio solo de `chat.js`.
- **Alternativa descartada: `EventSource`.** Solo hace `GET` y no permite mandar el mensaje en el cuerpo de un `POST`.

### D5. Keepalive propio de la `ui` y buffering

- `ChatController` combina el stream del servicio con un `Flux.interval(keepalive-interval)` de comentarios SSE (`ServerSentEvent.builder().comment("keepalive")`) que termina cuando termina el stream principal. Con 10 s (por debajo de los 15 s de la spec y de los 60 s del ingress), la conexión con el navegador no queda inactiva aunque el `assistant` tarde.
- El keepalive no depende de que el decoder de Spring entregue los comentarios del `assistant`, y cubre también los providers de Spring AI.
- La respuesta lleva `Cache-Control: no-cache` y `X-Accel-Buffering: no`, para que ningún proxy nginx acumule el stream. La compresión de la `ui` no aplica, porque `text/event-stream` no está en `server.compression.mime-types`.
- **Alternativa descartada: subir `proxy-read-timeout` con una anotación en el `Ingress`.** Solo cubre ese proxy, y el ingress es un render del chart que este change no necesita tocar. El keepalive resuelve el problema en cualquier camino.

### D6. Reflejo del carrito en el navegador

- `fragments/layout.html` suma `id="cart-count"` al `<span>` del contador.
- Ante `cart-updated` con `cartItemCount`, `chat.js` escribe ese valor en `#cart-count`.
- Sin `cartItemCount`, o si la página actual tiene `#basket` (página del carrito), hace `fetch` de `{contextPath}cart` (el mismo HTML que renderiza `CartController`), lo parsea con `DOMParser`, reemplaza `#basket` si existe y toma el contador del `#cart-count` del HTML recibido. Si llegan varios `cart-updated` en un turno, los refrescos se agrupan en uno (debounce de ~300 ms).
- El `fetch` corre en paralelo al stream del chat y no lo interrumpe. Usa la misma cookie, así que ve el carrito de la sesión: `add-assistant-tools` garantiza que `GET /carts/{customerId}` ya refleja el cambio cuando se emite el evento.
- **Alternativa descartada: `location.reload()`.** Corta el stream en curso y borra la conversación visible, que la spec exige conservar.
- **Alternativa descartada: un endpoint JSON nuevo en la `ui` (`/cart/summary`).** Duplicaría en JavaScript el render de la vista del carrito que ya hace Thymeleaf. Releer el HTML reutiliza la plantilla sin agregar superficie.
- **Alternativa descartada: polling del carrito.** Gasta requests a `carts` sin necesidad cuando el evento ya avisa.

### D7. Sesión: cookie válida para toda la tienda

`SessionIDUtil.addSessionCookie` crea la cookie con `Path=/`, `HttpOnly` y `SameSite=Lax`. El valor sigue siendo un UUID.

- `Path=/` corrige el caso de una primera visita directa a una ficha (`/catalog/{id}`), en el que la cookie quedaba limitada a `/catalog` y el chat (`/chat/submit`) y el carrito (`/cart`) terminaban con sesiones distintas. Ese caso rompe justo el escenario de la demo "agregar desde el chat y verlo en el carrito".
- `HttpOnly`: ningún JavaScript necesita leer la sesión (D6 usa la cookie implícitamente), así que se reduce la superficie ante XSS. `SameSite=Lax` evita que otro sitio dispare `POST /chat/submit` (y con él agregados al carrito) con la cookie del usuario.
- `SessionIDWebFilter` ya sobrescribe cualquier `X-Session-ID` que mande el navegador con el valor de la cookie. Se mantiene así y se cubre con un test, porque es lo que impide elegir otra sesión desde el cliente (spec).
- La `ui` no valida el formato de la cookie: lo valida el `assistant` (`400 invalid-parameter`, que la `ui` traduce por D3). Las cookies existentes sin `Path` siguen funcionando. Si quedan dos, una con `Path=/catalog` y otra con `Path=/`, el caso se resuelve cuando la vieja expira al cerrar el navegador, porque es de sesión.

### D8. Providers existentes sin persona

`mock`, `openai` y `bedrock` siguen disponibles (la `ui` sola con `docker compose` usa `mock`), pero ahora mandan solo el mensaje del usuario, sin system prompt, y terminan con `done` por D1. La persona vive solo en el `assistant` (`prompts/system.st`, D8 de `add-assistant-chat`), como pide la pre-entrega ("la persona A.G.E.N.T. que hoy reside en la configuración de la UI"). `MockChatModel` no cambia.

- **Alternativa descartada: conservar `retail.ui.chat.prompt` como opcional para los providers viejos.** Deja dos copias de la persona que se desincronizan y contradice el proposal ("la persona deja de vivir en la configuración de la `ui`").

### D9. Productos similares en la ficha, del lado del servidor

- Nuevo `AssistantClient.similar(productId, k)`: `GET {endpoint}/assistant/products/{id}/similar?k=` con el `WebClient` de D3 y `timeout(similar-timeout)`. Cualquier error (sin endpoint, conexión rechazada, `404`, `503`, `timeout`, JSON inválido) se registra en `debug`/`warn` y devuelve una lista vacía. El resultado se mapea al `Product` de la `ui`, con los tags como `ProductTag(name, name)` porque el endpoint solo devuelve nombres, para reutilizar el fragmento de tarjeta.
- `CatalogController.item` reemplaza el atributo `recommendations` (aleatorio y sin uso) por `similarProducts` con k = `similar-k`. Se descarta además, por las dudas, un resultado con el mismo id que el exhibido, aunque el `assistant` ya lo excluye.
- `detail.html` suma después del bloque del producto una sección "Similar gear for your lair" (texto en `lang/messages.properties`, clave `detail.similar`), con `th:if` sobre la lista no vacía y una grilla de `fragments/product_card :: card(...)`.
- Se corrige `product_card.html` para tomar la imagen de `${product.id}`. En `catalog.html` y `home.html` el resultado es el mismo.
- El precio es el del payload de Qdrant (D8 de `add-product-indexing`). Con el catálogo estático coincide con `catalog`. El precio en vivo que exige la pre-entrega es el de las tools (`add-assistant-tools`).
- **Por qué del lado del servidor:** el cálculo no llama a Gemini y es una consulta a Qdrant local (decenas de ms). El peor caso, con el `assistant` colgado, suma `similar-timeout` (2 s) a la carga de la ficha. Con el `assistant` caído, la conexión se rechaza al instante.
- **Alternativa descartada: cargar la sección por JavaScript después del render**, desde un endpoint de la `ui` que devuelva el fragmento. No demora la ficha, pero suma un endpoint, un salto visual y JavaScript, para ganar como mucho 2 s en un caso de falla.

### D10. Despliegue

- `ConfigMap` `ui` en `dist/kubernetes.yaml`: `RETAIL_UI_CHAT_ENABLED: "true"`, `RETAIL_UI_CHAT_PROVIDER: assistant` y `RETAIL_UI_ENDPOINTS_ASSISTANT: http://assistant`. La `ui` no recibe ninguna clave: el Secret `assistant-api-keys` solo lo monta el `assistant`.
- No se toca el `Ingress`: el `assistant` sigue sin exponerse y la `ui` no suma rutas de proxy hacia él. `ProxyController` no incluye el `assistant`.
- La readiness de la `ui` no cambia: no hay un health indicator del `assistant` en la `ui`.
- `src/ui/README.md`: se actualiza la tabla de variables (sin `RETAIL_UI_CHAT_PROMPT`, con `assistant` como provider válido, `RETAIL_UI_ENDPOINTS_ASSISTANT` y `RETAIL_UI_ASSISTANT_*`).
- `docs/arquitectura.md` (creado por `add-assistant-service`): la flecha `ui → assistant` del diagrama suma "REST · similares de la ficha" junto a "HTTP + SSE · X-Session-ID".

### D11. Pendiente heredado: paginación del catálogo

`replace-catalog-with-home-furniture` dejó para este change verificar `catalog.html` con ~80 productos (14 páginas de 6). Si la barra de páginas no entra en una línea en 1280 px y en mobile, se compacta: primera, última, la actual ±2 y "…". El tamaño de página no cambia (6), y la página sigue filtrando por un tag a la vez.

### D12. Tests

- Unitarios y de slice en `src/ui`, con `okhttp3:mockwebserver` en scope `test` (la versión del `okhttp` que ya trae Kiota) para simular al `assistant`: streams con eventos partidos, con eventos con nombre, cortados sin `done`, `400`/`409`/`503`, respuestas colgadas y verificación de que el request no lleva system prompt. `WebTestClient` para `ChatController`, `CatalogController` y la cookie. `StepVerifier` con tiempo virtual para el keepalive y `chat-timeout`.
- e2e en Cypress (`src/e2e`), que tienen que pasar con y sin claves:
  - la ficha carga y agrega al carrito, y si hay sección de similares tiene como máximo 4 tarjetas y ninguna es el producto exhibido;
  - el chat muestra una respuesta del bot, con texto o con un aviso de error, sin JSON crudo.
- La verificación de la demo con claves (búsqueda, agregado desde el chat, contador, carrito y similares) es manual en el cluster (tasks).

### D13. Desvíos respecto de la pre-entrega

No hay desvíos: el change implementa lo que la pre-entrega propone para la `ui`. Eso incluye el provider de chat `assistant`, HTTP + SSE con `X-Session-ID` entre `ui` y `assistant`, la persona fuera de la `ui`, la ficha con los k vecinos más cercanos y el carrito de la interfaz que refleja lo agregado desde el chat.

Agregados que no contradicen la pre-entrega:

| Agregado | Justificación |
|---|---|
| La `ui` también llama al `assistant` por REST (`GET /assistant/products/{id}/similar`), no solo por SSE | Es la forma de cumplir el caso "Productos similares" con la `ui` como presentación. El diagrama de la pre-entrega solo rotula la flecha `ui → assistant` con el chat, y `docs/arquitectura.md` la completa (D10). |
| Los providers `mock`, `openai` y `bedrock` quedan sin persona (D8) | La pre-entrega mueve la persona al `assistant`. Esos providers quedan solo para desarrollo sin `assistant`. |
| Cookie `SESSIONID` con `Path=/`, `HttpOnly` y `SameSite=Lax` (D7) | Garantiza que chat y carrito usen la misma sesión, que es la premisa del caso "Agregar al carrito desde el chat". |
| Eventos `error` generados por la `ui` (`assistant-unavailable`, `session-busy`, `invalid-parameter`) y keepalive propio (D3, D5) | Robustez del canal SSE que la pre-entrega declara entre navegador, `ui` y `assistant`. |
| Sanitización del markdown con DOMPurify (D4) | Seguridad del render de texto generado por un LLM. |

Los desvíos de modelos y proveedores (NVIDIA, Gemini, sin Ollama) son de `add-assistant-service`, `add-product-indexing` y `add-assistant-chat`, y no cambian nada de la `ui`.

## Risks / Trade-offs

- **[`bodyToFlux(ServerSentEvent<String>)` entrega `data` ya des-serializado o partido en varias líneas]** → Con `String` como tipo de dato, Spring junta las líneas `data:` y entrega el texto sin parsear, que se reenvía tal cual. Un test con `MockWebServer` lo fija para `{"text"}` con saltos de línea dentro del fragmento.
- **[Tiempo de carga de la ficha atado al `assistant`]** → Tiempo límite de 2 s y fallback a la ficha sin la sección (D9). Con el `assistant` caído, la conexión se rechaza al instante. Si en la demo molesta, `RETAIL_UI_ASSISTANT_SIMILAR_TIMEOUT` se baja por ConfigMap.
- **[Burbujas del chat perdidas al navegar]** → Non-Goal. La memoria del `assistant` conserva el contexto, así que "cheaper" sigue funcionando después de cambiar de página, aunque la conversación anterior ya no se vea. Queda documentado en el README de la `ui`.
- **[`session-busy` si el usuario abre dos pestañas y escribe en ambas a la vez]** → Es el comportamiento del `assistant` (un turno por sesión). La `ui` lo muestra como "wait for the current answer" y no reintenta sola.
- **[Cookies viejas con `Path=/catalog` en navegadores que ya visitaron la tienda]** → Son cookies de sesión y desaparecen al cerrar el navegador. Para la demo, abrir una ventana privada.
- **[Precio de los similares desactualizado si cambia el catálogo sin reiniciar el `assistant`]** → No pasa con el catálogo embebido. La ficha muestra el precio vivo del producto exhibido, y el agregado al carrito usa siempre el precio de `catalog`.
- **[DOMPurify o showdown no cargan desde cdnjs]** → Ya existe la dependencia de cdnjs con showdown y Tailwind. Si DOMPurify no está, `chat.js` cae a `textContent` (sin markdown) en lugar de asignar HTML sin sanitizar.
- **[El e2e del chat depende del `assistant` en CI]** → El test acepta tanto una respuesta como un aviso de error, y solo verifica que el turno termina y que no aparece JSON crudo. Con un timeout de Cypress de 30 s, alcanza para el `llm-provider-unauthorized` inmediato del CI.

## Migration Plan

1. Mergear después de `add-product-indexing`, `add-assistant-chat` y `add-assistant-tools`, y desplegar con `./local.sh reload-images` y `kubectl apply -f dist/kubernetes.yaml -n the-store` (`ConfigMap` `ui` nuevo y reinicio de la `ui` con `kubectl rollout restart deployment/ui`).
2. Verificar en `http://localhost` el chat, la ficha con similares y el agregado desde el chat (tasks).
3. Rollback rápido sin revertir código: en el `ConfigMap` `ui`, `RETAIL_UI_CHAT_ENABLED: "false"` (oculta el chat) o `RETAIL_UI_ENDPOINTS_ASSISTANT: ""` con otro provider (oculta los similares), y reiniciar la `ui`. Rollback completo: revertir el commit y `./local.sh reload-images`. No hay datos que migrar.

## Notas del apply

Decisiones menores tomadas durante la implementación, con un default convencional, y resultados de las verificaciones. Ninguna cambia la spec ni la pre-entrega.

- **Errores de los providers de Spring AI.** `SpringAiChatStreamService` (`mock`, `openai`, `bedrock`) traduce cualquier error del modelo a un evento `error` `assistant-unavailable`, para respetar el contrato de D1 (todo turno termina en `done` o `error`). Desde el navegador, el "asistente" es A.G.E.N.T. sin importar el provider.
- **Línea de log por turno (D3).** Formato `Chat turn session=<8 caracteres> upstream=<estado HTTP o none> final=<done | error:<type> | cancelled> durationMs=<n>`, en `INFO`, sin el texto del mensaje.
- **DOMPurify sin imágenes (D4).** Además de sanitizar, `chat.js` descarta `img`, `style`, `form` e `input` (`FORBID_TAGS`). Con la respuesta `<img src=x onerror=alert(1)> **ok**`, DOMPurify ya quitaba el `onerror`, pero el navegador igual pedía `/x` y dejaba un 404 en la consola. Como el texto lo genera un LLM, no cargar imágenes también evita que una URL inyectada en la respuesta haga que el navegador llame a un tercero. El markdown que usa la persona (negritas, listas, párrafos) no cambia.
- **Clases de las burbujas del chat.** Cada mensaje suma `chat-message chat-message-<bot|user>` y el aviso de error usa `chat-error`, para que los e2e (7.1) seleccionen la última respuesta del bot sin depender de la estructura del DOM.
- **Test de cancelación (3.3) con un socket crudo.** El `assistant` falso de ese test es un `ServerSocket` que responde un primer fragmento y bloquea en `read()` hasta el EOF, en lugar de `MockWebServer`: `MockWebServer` no expone cuándo el cliente cierra la conexión mientras escribe un cuerpo lento. El resto de los tests de la `ui` usan `MockWebServer` (D12). Resultado: la conexión con el `assistant` se cierra en menos de 1 s después de cancelar `POST /chat/submit`. Se agregó un segundo caso, con la cancelación **antes** de recibir los headers de respuesta (el `assistant` no los manda hasta su primer evento, así que es lo que pasa si se cierra la pestaña durante la reescritura o el razonamiento), con el mismo resultado.
- **Verificación de `chat.js` en el navegador (4.1 a 4.3).** Se hizo con Playwright contra la `ui` real (`java -jar`) y un `assistant` falso en Python (fuera del repo) en lugar de `MockWebServer`, porque para el caso de `/cart` el falso tiene que agregar el ítem al carrito mock de la `ui` (`POST /cart` con la cookie de la sesión) antes de emitir `cart-updated`. Para los eventos partidos en el navegador (el `assistant` falso parte el evento hacia la `ui`, pero la `ui` lo reenvía entero) se reemplazó `ChatUI.fetchBotResponse` por un `ReadableStream` con chunks cortados a mano: un evento partido a la mitad, varias líneas `data:`, `\r\n`, comentarios, un evento desconocido, un carácter multibyte partido entre chunks y datos después de `done`. Resultados:
  - provider `mock`: la burbuja muestra "This is a mock response" y el input se vuelve a habilitar;
  - `products`, `tool` y el fragmento partido → solo el texto, y el `error` `llm-quota-exceeded` con `retryAfterSeconds: 20` → "Our operatives are overloaded, try again in ~20 seconds." debajo del texto;
  - input y botón de enviar deshabilitados durante el turno;
  - stream cerrado sin evento final → "The assistant is unavailable right now. Please try again later.";
  - `<img src=x onerror=alert(1)> **ok**` → sin diálogo ni errores de consola, "ok" en negrita;
  - sin DOMPurify → texto plano (`textContent`);
  - catálogo: `cart-updated` con `cartItemCount` 0 → 2 y sin `cartItemCount` 2 → 4, sin recargar y con la conversación visible; un turno con `done` sin `cart-updated` deja el contador en 4;
  - `/cart`: la lista pasa de "Alonzo Velvet Loveseat x4" a "x6" y el subtotal se actualiza a mitad del turno, que sigue y termina con el chat abierto.
- **Paginación del catálogo (D11).** Con 14 páginas, la barra medía 642 px: entraba en 1280 px, pero en 375 px generaba scroll horizontal. Se compactó a primera, última, actual ±2 y "…" (aunque el salto sea de una sola página), y en mobile los botones bajan de 40 a 28 px (`w-7 sm:w-10`): con el peor caso de 11 elementos la barra mide 310 px en 375 px y 442 px en 1280 px, en una línea y sin scroll horizontal. El tamaño de página sigue en 6.
- **Diagrama de `docs/arquitectura.md` (6.2).** Sin acceso a la vista previa de GitHub, se verificó que el bloque Mermaid renderiza con `@mermaid-js/mermaid-cli` (el mismo motor), y el SVG contiene las etiquetas nuevas de la flecha `ui → assistant`.
- **`grep` de la 1.2.** `grep -rIn "You are A.G.E.N.T\|chat.prompt\|CHAT_PROMPT" src/ui --exclude-dir=target` solo encuentra los imports del paquete `org.springframework.ai.chat.prompt` (el `.` de la expresión coincide con cualquier carácter), que necesitan `MockChatModel` y su test. La búsqueda literal de `retail.ui.chat.prompt` y de `CHAT_PROMPT` no da resultados.
- **Cluster sin claves (7.1, 6.3).** `local.sh` carga el `.env` de la raíz si existe, así que para levantar el cluster sin claves se corrió una copia de `local.sh` desde un directorio del scratchpad sin `.env` (con enlaces a `dist/` y `src/`): el Secret quedó con el placeholder, como en CI. Después se cargó el snapshot de Qdrant y las claves con `./local.sh update-secrets`. Los e2e pasaron 15/15 sin claves, con claves y con el `assistant` en 0 réplicas. `kubectl apply --dry-run=server` del manifiesto no dio errores (los `configured` de `secret/orders-rabbitmq` y `statefulset/qdrant` son diferencias previas del render, no de este change).
- **Respuesta de más de 60 s por el ingress (8.1).** La comparación real tardó 20 s, así que se verificó el caso con un `assistant` falso temporal dentro del cluster (pod `python:3.12-alpine` que tarda 70 s en el primer fragmento, con el `ConfigMap` `ui` apuntando a él): a través del ingress llegaron 7 comentarios `:keepalive`, uno cada 10 s, y la respuesta completa con `done` a los 70 s. Después se restauró el `ConfigMap` y se borró el falso.
- **Hallazgos del `assistant` en la demo (8.1 y 8.2), pendientes fuera de este change.** Registrados en el README de la `ui`: (1) en sesiones de más de dos o tres turnos, el modelo principal a veces deja de llamar a las tools y responde como si las hubiera usado (`tools=-`, `modelCalls=1` en `assistant.turn`), por lo que el agregado desde el chat necesitó una sesión nueva; (2) al cerrar la pestaña, la `ui` corta la conexión al instante, pero el `assistant` libera el lock de la sesión entre 2 y 4,5 s después, así que un mensaje a los 3 s recibió `session-busy` (a los 6 s, no). El comportamiento de la `ui` en los dos casos es el especificado: sin `cart-updated` no cambia el contador, y el `session-busy` se muestra con su aviso.
