# Design

## Context

Ver `proposal.md` (Why) para la motivación y `specs/assistant-tools/spec.md` para el comportamiento exigido. Estado del que parte este change:

- `add-assistant-service` deja el `assistant` (Spring Boot 3.5, Spring MVC, Spring AI 1.1.8, paquete `com.amazon.sample.assistant`) con el starter de OpenAI apuntando a NVIDIA, y en el ConfigMap `RETAIL_ASSISTANT_ENDPOINTS_CATALOG=http://catalog` y `RETAIL_ASSISTANT_ENDPOINTS_CARTS=http://carts`. Su D3 asignó a este change el rate limiting y el manejo de 429 del chat.
- `add-product-indexing` deja `ProductSearchService.search(query, tags, minPrice, maxPrice, k)` (tags OR, rango inclusivo, `max-k` 20, precio del payload de la última sincronización), `CatalogClient` con `RestClient` contra `retail.assistant.endpoints.catalog`, `EmbeddingProviderException` y el error `index-unavailable`. Su D8 deja explícito que las tools sacan el precio de `GET /catalog/products/{id}`.
- `add-assistant-chat` deja `POST /assistant/chat` con un `ChatTurnService` que, por turno, toma el lock de la sesión, reescribe (con `rewriteChatClient`), busca (RAG), emite `products` y hace streaming con `mainChatClient` por un `Sinks.Many<ServerSentEvent<?>>` del turno, accesible para que este change emita sus eventos (D2). Además:
  - `CatalogTagsCache` (tags de `GET /catalog/tags`), `SessionStore`/`SessionState` con el snapshot de los productos del último turno, el contexto RAG con el id de cada producto y el precio del payload (D5), y `prompts/system.st` con la persona (D8).
  - `ChatProviderException` con causa `QUOTA` (con `retryAfter`), `UNAUTHORIZED` y `UNAVAILABLE`, `spring.ai.retry.max-attempts=1` y tiempos límite de 20/60 s al primer fragmento y 120 s por turno (D10).
  - El spike (D12) verifica tool calling en streaming con una tool de prueba. Hoy un 429 termina en `llm-quota-exceeded`.
- APIs existentes, que no cambian:
  - `catalog` (Go/Gin): `GET /catalog/products?tags=a,b&order=price_asc|price_desc&page=&size=` (tags OR con `IN`, sin `order` ordena por nombre, sin filtro de precio ni de texto), `GET /catalog/products/{id}` (`404` si no existe) y `GET /catalog/tags`. Tiene un middleware de chaos que puede devolver errores a propósito.
  - `carts` (Spring): `POST /carts/{customerId}/items` con `{itemId, quantity, unitPrice}` (`201`), `GET /carts/{customerId}` con `{customerId, items: [{itemId, quantity, unitPrice}]}`. En el cluster usa `RETAIL_CART_PERSISTENCE_PROVIDER=in-memory`, cuyo `add` agrega una línea nueva aunque el ítem ya esté en el carrito. Con DynamoDB, en cambio, suma la cantidad. La `ui` (`KiotaCartsService.addItem`) hace lo mismo que la tool: consulta el precio en el catálogo y hace el `POST`.
- Cuota de NVIDIA: 40 RPM, compartida por todas las sesiones (CLAUDE.md). Las tools que van a `catalog` y a `carts` son tráfico interno y no consumen cuota. Solo `searchProducts` con texto consume un embedding de Gemini.

## Goals / Non-Goals

**Goals:**
- Un ciclo de tool calling acotado y controlado por el `assistant`, que se integra en el pipeline de turno de `add-assistant-chat` sin rediseñarlo.
- Que cada precio que sale de una tool sea el vigente del catálogo, y que el carrito modificado sea siempre el de la sesión del turno, sin que el modelo pueda cambiarlo.
- Que la cuota de 40 RPM se respete desde el cliente y que un pico de mensajes se traduzca en espera, no en un error en pantalla.

**Non-Goals:**
- Quitar productos, cambiar cantidades, vaciar el carrito o hacer checkout desde el chat: la pre-entrega solo compromete agregar.
- Que la `ui` refresque el carrito al recibir `cart-updated`: es `integrate-ui-assistant`. Acá se emite el evento y se garantiza que `GET /carts/{customerId}` ya refleja el cambio.
- Cambiar el precio del contexto RAG de `add-assistant-chat`, que sigue siendo el del payload (su D5). El precio en vivo es el de las tools (D6).
- Rate limiting de Gemini: ya lo resuelven el caché y los reintentos de `add-product-indexing`.
- Modificar las APIs de `catalog` o `carts`.

## Decisions

### D1. Ciclo de tools controlado por el `assistant`, no por Spring AI

El modelo principal se llama con `internalToolExecutionEnabled=false`. El `ChatTurnService` de `add-assistant-chat` delega la parte de "modelo principal en streaming" a un `ToolCallingLoop`:

```
vuelta n (n = 1..max-model-calls)
  ─► reservar lugar en el limitador (D8)
  ─► stream del modelo con las tools (en la última vuelta: tool_choice = none)
  ─► reenviar los fragmentos de texto al Sinks del turno (como hoy)
  ─► al cerrar el stream: ¿la respuesta agregada trae tool calls?
       no ─► fin del turno (done)
       sí ─► ejecutar cada tool en orden con ToolCallingManager (boundedElastic)
             emitir `tool` (y `cart-updated`) por el Sinks
             agregar al prompt el AssistantMessage con los tool calls y los ToolResponseMessage
             siguiente vuelta
```

- `OpenAiChatModel` en streaming ya junta los fragmentos de un tool call y entrega un `ChatResponse` con los `toolCalls` completos. Con la ejecución interna desactivada, ese `ChatResponse` vuelve al loop en lugar de ejecutarse solo.
- Defaults: `max-model-calls=4` y `max-tool-calls=6` por turno (spec, "Consumo acotado por turno"). Con la reescritura, el peor caso son 5 solicitudes a NVIDIA por turno, que es el rango de 2 a 5 que anticipó el proposal de `add-assistant-chat`. Si un tool call excede `max-tool-calls`, no se ejecuta y el modelo recibe el error `tool-budget-exhausted`.
- En la última vuelta se envían las mismas definiciones de tools con `tool_choice: "none"`, porque algunos backends compatibles con OpenAI rechazan un historial con `tool_calls` si el request no trae `tools`. Si aun así el modelo devuelve tool calls, se ignoran. Si además no hubo texto, se emite un texto fijo de la persona ("no pude completar la operación"), para que el turno termine con `done` como pide la spec.
- El razonamiento (D7 de `add-assistant-chat`) se decide una vez por turno y se aplica a todas sus vueltas.
- Los tiempos límite de D10 de `add-assistant-chat` (20/60 s al primer fragmento) se miden **por vuelta** y empiezan cuando el limitador otorga el lugar. El de 120 s sigue siendo por turno e incluye las esperas y las tools.
- **Alternativa descartada: ejecución interna de Spring AI** (la recursión dentro de `OpenAiChatModel`). Spring AI 1.1.8 no tiene un máximo de iteraciones, las llamadas recursivas no pasarían por el limitador de D8 (habría que interceptar el HTTP) y no se podría forzar la última vuelta sin tools. El presupuesto por turno quedaría librado al modelo.
- **Alternativa descartada: llamadas no streaming mientras haya tools y streaming solo en la respuesta final.** No se sabe de antemano si una respuesta va a ser texto o tool call, así que habría que pedir la respuesta final dos veces o perder el streaming en los turnos con tools.

### D2. Definición de las tools y contexto del turno

Las tres tools son métodos `@Tool` de un bean `StoreTools`, registrados con `MethodToolCallbackProvider` en el `mainChatClient` (nunca en el `rewriteChatClient`). Las descripciones y los nombres de los parámetros están en inglés, como los prompts (D8 de `add-assistant-chat`).

El contexto del turno viaja en el `ToolContext` de Spring AI (`toolContext` de las opciones del prompt), que **no** se expone al modelo:

- `sessionId` (el `X-Session-ID` ya validado por el chat), que se usa como `customerId`;
- el `Sinks.Many` del turno, para emitir `tool` y `cart-updated`;
- contadores de tools ejecutadas, el conjunto de productos ya agregados en el turno (spec: no se agrega dos veces) y la lista de productos devueltos por las tools (D7).

`addToCart` no tiene un parámetro `customerId`. Así, ni un error del modelo ni un prompt injection pueden modificar el carrito de otra sesión.

| Tool | Argumentos | Resultado (JSON para el modelo) |
|---|---|---|
| `searchProducts` | `query?`, `tags?[]`, `minPrice?`, `maxPrice?`, `order?` (`relevance`/`price_asc`/`price_desc`), `limit?` (1–10, por defecto 5) | `{"products": [{id, name, price, tags, description}], "source": "semantic"/"catalog", "degraded"?}` |
| `getProductDetails` | `productId` | `{id, name, description, price, tags}` |
| `addToCart` | `productId`, `quantity?` (1–`max-quantity`, por defecto 1) | `{"added": {id, name, quantity, unitPrice}, "cartItemCount"?}` |

Los errores se devuelven como `{"error": "<tipo>", "message": "...", ...}` y no como excepciones, para que el modelo los pueda explicar. Los tipos son `invalid-argument` (con `argument` y, para tags, `validTags`), `missing-criteria`, `product-not-found`, `already-added-this-turn`, `tool-budget-exhausted`, `catalog-unavailable`, `cart-unavailable` y `search-unavailable`. Las descripciones del resultado se truncan a `description-max-chars` (300) para no inflar el contexto.

### D3. Validación de argumentos en el servidor

Antes de cualquier llamada (spec, "Validación de los argumentos de las tools"):

- `tags`: en minúsculas, sin repetidos y contra `CatalogTagsCache`. Si hay alguno desconocido, se devuelve `invalid-argument` con `validTags`, lo que permite que el modelo corrija en la vuelta siguiente. Si el caché está vacío porque `catalog` no respondió, se intenta cargarlo. Si sigue sin datos, el resultado es `catalog-unavailable`.
- Precios: enteros ≥ 0 y `minPrice ≤ maxPrice`. `limit` entre 1 y `search-max-limit` (10).
- `productId`: UUID (`UUID.fromString` más el chequeo de formato canónico).
- `quantity`: entre 1 y `max-quantity` (10). Diez unidades alcanzan para una demo y evitan que un error del modelo cargue cientos.
- `query`: hasta 200 caracteres, igual que la consulta reescrita del chat.

### D4. `searchProducts`: dos caminos según haya texto

**Con `query`**: `ProductSearchService.search(query, tags, minPrice, maxPrice, limit)`, con el filtro de tags (OR) y de precio sobre el payload de Qdrant. Después se **hidrata** cada resultado con `GET /catalog/products/{id}` (D6): precio, nombre, descripción y tags vigentes. Sobre los datos vivos se vuelve a aplicar el rango de precio, por si el payload estaba desactualizado. Con `order=price_asc|price_desc` se ordena por el precio vivo y, con `relevance`, se conserva el orden por `score`.

**Sin `query`**: `GET /catalog/products?tags=<tags>&order=<order>&page=N&size=50`, recorriendo páginas hasta que una venga incompleta, con un tope de 10 páginas. Sobre esos productos se filtra el rango de precio y se recorta a `limit`. Con `order=relevance`, no se manda `order` y el catálogo ordena por nombre. Este camino no hace falta hidratarlo, porque ya son datos del catálogo, y no consume embeddings.

Degradación: si con `query` la búsqueda semántica falla (`index-unavailable` o `EmbeddingProviderException`) y hay `tags` o precios, se resuelve por el camino sin texto y el resultado lleva `"degraded": "semantic-search-unavailable"`. Si no hay otro criterio, el resultado es `search-unavailable` con la causa.

- **Por qué dos caminos:** la API del catálogo filtra por tags y ordena por precio, pero no busca por texto ni filtra por precio. Qdrant busca por texto y filtra tags y precio sobre el payload, pero su búsqueda vectorial ordena por similitud. Cada camino usa la fuente que puede resolver el pedido, y entre los dos se usan los tres mecanismos que nombra la pre-entrega (tags y orden de la API, rango de precio en Qdrant). Ver D10.
- **Alternativa descartada: buscar siempre en Qdrant**, también sin texto (scroll con filtro). Duplica una consulta que el catálogo ya resuelve y deja sin usar el `order` de la API que compromete la pre-entrega.
- **Alternativa descartada: intersecar ambos resultados** (Qdrant para el texto, API para tags y orden). Con 80 productos no mejora el resultado y suma una llamada y un caso borde más (intersección vacía).

### D5. Clientes REST hacia `catalog` y `carts`

- `CatalogClient` (de `add-product-indexing`) suma `getProduct(id)` y `listProducts(tags, order, page, size)`. Para las tools, los timeouts son de 2 s de conexión y 5 s de lectura, con **un** reintento ante 5xx, timeout o error de red (el chaos middleware de `catalog` falla a propósito). El reintento sin límite de la indexación no se usa acá, porque un turno no puede esperar indefinidamente.
- Nuevo `CartsClient` con `RestClient` contra `retail.assistant.endpoints.carts`: `addItem(customerId, itemId, quantity, unitPrice)` (`POST /carts/{customerId}/items`) y `getCart(customerId)` (`GET /carts/{customerId}`), con los mismos timeouts. El `POST` **no se reintenta**, porque no es idempotente y un reintento después de un timeout podría duplicar la línea. El `GET` del carrito se usa solo para calcular `cartItemCount` (suma de cantidades). Si falla, el evento sale sin ese campo y el agregado igual cuenta como correcto.
- El `customerId` va en el path tal como llega. El chat ya garantiza que tiene solo `[A-Za-z0-9_-]`, así que no hace falta escaparlo.

### D6. Precio vivo y agregado al carrito

- `getProductDetails` siempre hace `GET /catalog/products/{id}`, sin caché. `404` → `product-not-found`, y error persistente después del reintento → `catalog-unavailable`.
- `addToCart`: valida, revisa que el producto no se haya agregado ya en el turno, hace `GET /catalog/products/{id}` y después `POST /carts/{sessionId}/items` con `unitPrice` igual al precio vivo, en ese orden y sin caché entre ambos. Es exactamente lo que hace hoy la `ui` con su botón (`KiotaCartsService.addItem`), así que el carrito queda igual que si el usuario hubiera usado la ficha.
- Con `carts` en `in-memory`, agregar un producto que ya está en el carrito crea una segunda línea, igual que con el botón de la `ui`. Se mantiene el mismo comportamiento para no tocar `carts` (Non-Goal) ni dejar dos caminos de alta con semánticas distintas. `cartItemCount` suma las cantidades de todas las líneas.
- **Alternativa descartada: `PATCH` con la cantidad sumada cuando el ítem ya existe.** Corrige el caso de las dos líneas en memoria, pero se aparta del `POST /carts/{customerId}/items` que compromete la pre-entrega y del comportamiento de la `ui`.
- **Alternativa descartada: pedir confirmación antes de agregar** (un paso de "¿confirmás?" con otra vuelta). La spec exige un pedido explícito del usuario y una pregunta solo si el producto es ambiguo. Una confirmación extra sumaría un turno y una solicitud a NVIDIA por cada agregado.

### D7. Eventos SSE y memoria

Se agregan dos eventos con nombre al contrato de D1 de `add-assistant-chat`, emitidos por el mismo `Sinks.Many` del turno:

| Evento | `data` | Cuándo |
|---|---|---|
| `tool` | `{"tool", "ok", "error"?, "products"?: [{id, name, price}]}` | Después de cada ejecución de tool, antes de la vuelta siguiente del modelo |
| `cart-updated` | `{"itemId", "name", "quantity", "unitPrice", "cartItemCount"?}` | Después de cada `addToCart` correcto, a continuación de su evento `tool` |

- `products` dentro de `tool` hace verificable, también con tools, el criterio de "productos reales con su precio" (spec, "Nombres verificables").
- `cart-updated` es lo que `integrate-ui-assistant` necesita para refrescar el contador y la vista del carrito sin recargar. Lleva `cartItemCount` para que la `ui` pueda actualizar el contador sin otra llamada, aunque puede igual releer `GET /carts/{customerId}`. Queda anotado para ese change que `chat.js` tiene que ignorar o manejar los eventos con nombre.
- Memoria: al confirmar el turno, el snapshot de productos de `SessionState` (D6 de `add-assistant-chat`) pasa a ser la unión de los productos de las tools del turno (primero) y los del evento `products`, sin repetidos y hasta 10. Así, "add the second one" o "compare those" en el turno siguiente encuentran los ids aunque los productos hayan salido de una tool. Los mensajes intermedios de tool calls **no** se guardan en el historial: se mantiene la regla del chat de guardar solo el mensaje del usuario y la respuesta final.

### D8. Limitador de solicitudes hacia NVIDIA

`ChatRateLimiter`, un bean singleton con ventana deslizante de 60 s: un `ArrayDeque` de instantes reservados y un `pausedUntil`, protegidos con un lock.

- `reserve(maxWait)` calcula el primer instante en que hay lugar (ventana con menos de `requests-per-minute` reservas y después de `pausedUntil`). Si la espera es ≤ `maxWait`, reserva ese instante y devuelve la espera. Si no, no reserva nada y devuelve `empty` con la espera estimada, que se usa como `retryAfterSeconds`.
- `pauseUntil(instant)` adelanta `pausedUntil` (nunca lo atrasa). Lo llama cualquier 429.
- Defaults: `requests-per-minute=36` (90 % de 40 RPM, como margen ante la diferencia de relojes con NVIDIA y las solicitudes de prueba hechas con la misma clave) y `max-wait=30s`.

Dónde se aplica: en el **código de la aplicación** y no en el cliente HTTP, porque con D1 hay solo dos lugares que llaman a NVIDIA:

- `QueryRewriter` (de `add-assistant-chat`): `reserve(0)`. Si no hay lugar inmediato, no llama y aplica el fallback con `rewrite=rate-limited` en el log. La reescritura tiene un tiempo límite de 5 s y es prescindible: esperar por ella demoraría la solicitud que importa.
- `ToolCallingLoop` (D1): `reserve(max-wait)` antes de cada vuelta, como `Mono.delay` (no bloquea hilos). Si no hay lugar dentro de `max-wait`, el turno termina con `error` `llm-quota-exceeded` y `retryAfterSeconds`, sin llamar.

Mientras se espera, el `:keepalive` de D1 de `add-assistant-chat` mantiene viva la conexión SSE.

- **Alternativa descartada: interceptor HTTP (`ClientHttpRequestInterceptor` + `ExchangeFilterFunction`) sobre la URL de NVIDIA.** Cubre cualquier llamada, pero no sabe si la solicitud es la reescritura (que no debe esperar) o el modelo principal, y la espera quedaría dentro del tiempo límite al primer fragmento. Con D1 no hay llamadas ocultas que justifiquen interceptar el HTTP.
- **Alternativa descartada: Bucket4j o Resilience4j `RateLimiter`.** Suman una dependencia para algo que son ~60 líneas, y ninguno modela la pausa global por `Retry-After`.

### D9. Reintento ante 429

En el `ToolCallingLoop`, cada vuelta es un `Flux` que se reintenta con `retryWhen` solo si se cumplen todas estas condiciones:

- el error es `ChatProviderException` con causa `QUOTA` (D10 de `add-assistant-chat`);
- esa vuelta todavía no emitió ningún fragmento ni tool call, así que el reintento no duplica texto;
- quedan reintentos (`max-429-retries=2`);
- y el limitador puede otorgar un lugar después de la pausa dentro de `max-wait`.

Antes de reintentar: `pauseUntil(now + Retry-After)`, con `default-retry-after=5s` si NVIDIA no manda el header. El header puede venir en segundos o como fecha HTTP, y se interpretan ambos. Después, `reserve(max-wait)`. Si alguna condición falla, el error sigue el camino que ya existe: evento `error` `llm-quota-exceeded`, sin guardar el turno.

En la reescritura, un 429 llama a `pauseUntil` y aplica el fallback, sin reintentar.

- `spring.ai.retry.max-attempts=1` (D10 de `add-assistant-chat`) se mantiene: el único reintento ante 429 es este, que respeta el limitador y el `Retry-After`. Los 5xx no se reintentan.
- Relación con la spec de `assistant-chat`: su escenario "Cuota de chat excedida" sigue valiendo cuando el 429 persiste. Este change agrega la espera y el reintento previos (spec, "Espera ante rechazos por cuota"). No se escribe un delta `MODIFIED` de `assistant-chat` porque esa capability todavía no está archivada en `openspec/specs/` y el proposal de este change no la lista como modificada. Al archivar los dos changes, en orden, conviven ambos requirements sin contradecirse.

### D10. Prompts

- `prompts/system.st` (D8 de `add-assistant-chat`) suma una sección de tools con estas reglas:
  - usar `searchProducts` con tags (de la lista de tags del catálogo, que se inyecta) cuando el usuario expresa una categoría, un presupuesto o un orden de precio;
  - usar `getProductDetails` antes de dar el precio de un producto puntual cuando el usuario lo pregunta, y tomar el precio de una tool por sobre el del contexto;
  - llamar a `addToCart` solo ante un pedido explícito y preguntar si el producto es ambiguo;
  - no decir que algo se agregó sin un resultado `added`;
  - ante un error de una tool, explicarlo sin inventar datos.
- `prompts/rewrite.st` suma una regla: los pedidos de carrito ("add it to my cart") y las preguntas por el precio de un producto ya mostrado se clasifican como `intent=other`. Así no gastan un embedding de Gemini, y el contexto del turno anterior (con los ids) sigue disponible para el modelo.

### D11. Configuración

```yaml
retail.assistant:
  tools:
    max-model-calls: 4
    max-tool-calls: 6
    max-quantity: 10
    search-default-limit: 5
    search-max-limit: 10
    description-max-chars: 300
    http:
      connect-timeout: 2s
      read-timeout: 5s
  rate-limit:
    requests-per-minute: 36
    max-wait: 30s
    max-429-retries: 2
    default-retry-after: 5s
```

Mapeado a `@ConfigurationProperties` validados, y los escalares como variables de entorno en el ConfigMap `assistant` (`RETAIL_ASSISTANT_RATE_LIMIT_REQUESTS_PER_MINUTE`, etc.). `retail.assistant.endpoints.carts` ya existe por D6 de `add-assistant-service`.

### D12. Observabilidad

- Una línea por tool ejecutada (logger `assistant.tool`, `clave=valor`) con la sesión truncada a 8 caracteres, la tool, los argumentos (sin la sesión), `ok` o el tipo de error, la cantidad de productos, las llamadas a `catalog`/`carts` y la latencia.
- La línea del turno (D9 de `add-assistant-chat`) suma `modelCalls`, `tools` (por ejemplo `searchProducts:ok,addToCart:ok`), `limiterWaitMs`, `retries429` y el valor `rate-limited` en `rewrite`. Las "solicitudes a NVIDIA del turno" pasan a contar la reescritura más todas las vueltas.
- Un gauge de Micrometer `assistant.ratelimit.window` (reservas en la ventana actual) en `/actuator/metrics`, para ver la cuota consumida en la demo.

### D13. Desvíos respecto de la pre-entrega

| Pre-entrega | Este change | Justificación |
|---|---|---|
| "búsqueda con filtros estructurados que combina tags y ordenamiento de `GET /catalog/products` junto con filtros de rango de precio sobre el payload en `qdrant`" (sección 2) | Dos caminos según haya texto (D4). Con texto: tags y rango de precio sobre el payload de Qdrant, y orden por precio vivo en el `assistant`. Sin texto: tags y orden de `GET /catalog/products`, y rango de precio filtrado en el `assistant` | La API no busca por texto ni filtra por precio, y la búsqueda vectorial de Qdrant no ordena por precio. Se usan los tres mecanismos de la pre-entrega, cada uno donde puede resolver el pedido. El resultado del caso de uso "Filtros estructurados" es el mismo: categoría → tags reales, presupuesto → rango de precio, orden → orden por precio. |
| Modelo local en Ollama para el function calling | `nvidia/nemotron-3-super-120b-a12b` vía NVIDIA | Ya documentado en D8 de `add-assistant-service` y D13 de `add-assistant-chat`. Este change agrega el limitador y la espera ante 429 (D8, D9) que exige usar un proveedor con cuota. |

Agregados que no contradicen la pre-entrega:
- los eventos SSE `tool` y `cart-updated` (D7);
- el límite de vueltas y tools por turno (D1);
- la validación de argumentos en el servidor (D3);
- la degradación de `searchProducts` al catálogo cuando la búsqueda semántica no está disponible (D4).

El contexto RAG del chat sigue mostrando el precio del payload (D5 de `add-assistant-chat`). El caso de uso "Precio y detalle en tiempo real" se cumple con `getProductDetails`, con `addToCart` y con la hidratación de `searchProducts`, que siempre consultan `GET /catalog/products/{id}`.

## Risks / Trade-offs

- **[Nemotron no respeta `tool_choice: "none"` o falla con `internalToolExecutionEnabled=false` en streaming]** → La primera task es un spike que lo verifica, sobre el de D12 de `add-assistant-chat`. Si `tool_choice` no se respeta, la última vuelta se envía sin `tools`. Si eso también falla, se descartan los tool calls de la última vuelta con el texto fijo de D1. Si el tool calling en streaming no funciona, se aplica el plan B del modelo principal (`deepseek-ai/deepseek-v4.1-flash`), que ya está previsto por configuración.
- **[El modelo no llama a `getProductDetails` y repite el precio del contexto]** → Con el catálogo estático, los precios coinciden. Las reglas de D10 y el escenario de la spec en el smoke test lo miden. Si falla seguido, se refuerza la regla del prompt.
- **[El modelo elige tags equivocados o inventa tags]** → La validación de D3 devuelve los tags válidos y el modelo corrige en la vuelta siguiente, a costa de una solicitud más dentro del presupuesto de 4.
- **[Latencia de los turnos con tools]** → Cada vuelta es una solicitud más al modelo grande. Se mitiga con el presupuesto de vueltas, el razonamiento apagado fuera de las comparaciones y con el `rewrite.st` que evita búsquedas inútiles en los pedidos de carrito. Los turnos de la demo con tools se miden en el smoke test.
- **[Producto agregado dos veces por la semántica de `carts` en memoria]** → Es el mismo comportamiento que el botón de la `ui` (D6). En un mismo turno, el control de `already-added-this-turn` evita duplicados por repetición del modelo.
- **[Timeout del `POST` al carrito después de que `carts` lo procesó]** → No se reintenta, para no duplicar. La tool informa `cart-unavailable` y el usuario puede ver el carrito real. Es un caso raro con un servicio interno en el mismo nodo.
- **[El limitador es por proceso]** → Con 2 réplicas, cada una permitiría 36 RPM. El manifiesto tiene 1 réplica (igual que la memoria de sesión del chat). Si se escalara, se baja `requests-per-minute` a la mitad por ConfigMap.
- **[Prompt injection que intenta agregar productos o tocar otro carrito]** → El `customerId` sale del `ToolContext` y no de un argumento (D2), la cantidad está acotada (D3) y no hay tools para borrar ni comprar. El peor caso es que se agregue un producto al carrito de la propia sesión, que el usuario ve en el evento `cart-updated` y en la `ui`.
- **[Payload desactualizado en el filtro de precio de Qdrant]** → El rango se vuelve a aplicar sobre el precio vivo (D4). Un producto cuyo precio vivo entró en el rango pero el del payload no queda afuera hasta el siguiente reinicio del `assistant`. Con el catálogo estático, esto no pasa.

## Migration Plan

1. Desplegar con `./local.sh reload-images` y `kubectl apply -f dist/kubernetes.yaml -n the-store` (ConfigMap `assistant` con las variables de D11). No hay datos que migrar.
2. Verificar desde el pod de la `ui` con `curl -N` a `http://assistant/assistant/chat` un pedido de agregar al carrito, y después `curl http://carts/carts/<sesión>`.
3. Rollback: revertir el commit y reiniciar el `assistant`. El chat vuelve a funcionar sin tools, con el comportamiento de `add-assistant-chat`. Los ítems agregados quedan en `carts` (en memoria) hasta que se reinicie.
