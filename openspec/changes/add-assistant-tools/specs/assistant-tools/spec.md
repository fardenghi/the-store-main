# Spec Delta

## Purpose

Define las acciones que el asistente de compras puede ejecutar sobre los microservicios de la tienda mediante function calling (búsqueda con filtros estructurados, detalle con precio en tiempo real y agregar productos al carrito de la sesión), y el control del consumo de solicitudes hacia el proveedor del modelo de lenguaje.

## ADDED Requirements

### Requirement: Tools disponibles para el modelo principal
En cada turno de chat, el modelo principal SHALL tener disponibles exactamente tres tools: `searchProducts`, `getProductDetails` y `addToCart`. El modelo de reescritura de consulta MUST NOT tener tools disponibles. El `assistant` SHALL ejecutar las tools que el modelo pida, devolverle su resultado y volver a llamarlo hasta que responda con texto, dentro de los límites del requirement "Consumo acotado por turno". El resultado de una tool que falla SHALL devolverse al modelo como un error descriptivo, para que el turno pueda continuar y el asistente informe la falla al usuario.

#### Scenario: Turno que usa una tool
- **WHEN** el usuario escribe "how much is the <nombre de un producto del catálogo> right now?"
- **THEN** el modelo pide `getProductDetails`, el `assistant` la ejecuta, y la respuesta final en texto menciona el precio devuelto por la tool

#### Scenario: Turno sin tools
- **WHEN** el usuario escribe "hi there!"
- **THEN** el turno se completa sin ejecutar ninguna tool, con los mismos eventos SSE que define el chat

### Requirement: Búsqueda con consulta de texto
La tool `searchProducts` SHALL aceptar los argumentos opcionales `query` (texto), `tags` (lista de nombres de tags), `minPrice` y `maxPrice` (enteros no negativos, inclusivos), `order` (`relevance`, `price_asc` o `price_desc`, por defecto `relevance`) y `limit` (entre 1 y 10, por defecto 5). Cuando trae `query`, SHALL buscar por significado en el índice semántico aplicando los tags con semántica OR (un producto coincide si tiene al menos uno) y el rango de precio, combinados entre sí con AND. Cada producto devuelto SHALL llevar `id`, `name`, `price`, `tags` y una descripción, con el precio vigente de `GET /catalog/products/{id}` y no el valor almacenado en el índice. Con `order` `price_asc` o `price_desc`, los resultados SHALL ordenarse por ese precio vigente; con `relevance`, por similitud decreciente.

#### Scenario: Consulta con tags y presupuesto
- **WHEN** el modelo llama `searchProducts` con `query` "cozy reading chair", `tags` `["velvet", "leather"]` y `maxPrice` 400
- **THEN** cada producto del resultado tiene el tag `velvet` o el tag `leather` y un precio menor o igual a 400

#### Scenario: Orden por precio con consulta
- **WHEN** el modelo llama `searchProducts` con `query` "desk lamp" y `order` `price_asc`
- **THEN** los productos del resultado están ordenados por precio ascendente

#### Scenario: Precio vigente y no el del índice
- **WHEN** el precio guardado en el índice para un producto difiere del que devuelve `GET /catalog/products/{id}` y una búsqueda con `query` lo incluye en el resultado
- **THEN** el producto aparece con el precio que devuelve `GET /catalog/products/{id}`

### Requirement: Búsqueda sin consulta de texto
Cuando `searchProducts` no trae `query`, SHALL resolverse contra `GET /catalog/products` usando sus parámetros `tags` (OR) y `order` (`price_asc` o `price_desc`; con `relevance`, el orden por defecto del catálogo), recorriendo las páginas necesarias, y SHALL aplicar el rango de precio sobre los productos devueltos por el catálogo antes de recortar a `limit`. Esta variante MUST NOT pedir embeddings al proveedor. Si no trae `query`, ni `tags`, ni rango de precio, SHALL devolver al modelo un error que indique que hace falta al menos un criterio.

#### Scenario: Categoría y presupuesto sin texto
- **WHEN** el modelo llama `searchProducts` con `tags` `["lighting"]`, `maxPrice` 100 y `order` `price_asc`, sin `query`
- **THEN** el resultado contiene solo productos con el tag `lighting` y precio menor o igual a 100, ordenados por precio ascendente, y no se pidió ningún embedding

#### Scenario: Sin ningún criterio
- **WHEN** el modelo llama `searchProducts` sin `query`, sin `tags` y sin precios
- **THEN** la tool devuelve un error que indica que falta un criterio de búsqueda y no se consulta ningún servicio

### Requirement: Validación de los argumentos de las tools
El `assistant` SHALL validar en el servidor los argumentos de cada tool antes de llamar a cualquier servicio, sin confiar en el modelo. Un argumento inválido SHALL producir un resultado de error dirigido al modelo que nombre el argumento, sin llamar al servicio. En particular:
- `tags` que no existen en el catálogo SHALL rechazarse, y el error SHALL incluir la lista de tags válidos;
- `minPrice` mayor que `maxPrice`, precios negativos o `limit` fuera de rango SHALL rechazarse;
- un `productId` que no tiene formato de UUID SHALL rechazarse;
- `quantity` fuera del rango configurado (por defecto, de 1 a 10) SHALL rechazarse.

#### Scenario: Tag inexistente
- **WHEN** el modelo llama `searchProducts` con `tags` `["lamps"]`
- **THEN** la tool devuelve un error que indica que `lamps` no es un tag válido e incluye los tags del catálogo, y no se consultó el índice ni el catálogo

#### Scenario: Cantidad fuera de rango
- **WHEN** el modelo llama `addToCart` con `quantity` 500
- **THEN** la tool devuelve un error sobre `quantity` y el carrito de la sesión no cambia

### Requirement: Detalle con precio en tiempo real
La tool `getProductDetails` SHALL recibir un `productId` y consultar `GET /catalog/products/{id}` en cada invocación, devolviendo `id`, `name`, `description`, `price` y `tags` tal como los informa el catálogo en ese momento. El precio MUST NOT tomarse del índice semántico ni de un caché. Si el catálogo responde que el producto no existe, la tool SHALL devolver un error de producto inexistente.

#### Scenario: Pregunta por el precio de un producto
- **WHEN** el usuario pregunta por el precio actual de un producto que el asistente mostró en un turno anterior
- **THEN** el `assistant` hace `GET /catalog/products/{id}` para ese producto durante el turno, y la respuesta informa el precio que devolvió el catálogo

#### Scenario: Producto inexistente
- **WHEN** el modelo llama `getProductDetails` con un UUID que no corresponde a ningún producto
- **THEN** la tool devuelve un error de producto inexistente y la respuesta al usuario no inventa datos de ese producto

### Requirement: Agregar al carrito de la sesión
La tool `addToCart` SHALL recibir `productId` y `quantity` (por defecto 1), consultar el precio vigente con `GET /catalog/products/{id}` y llamar a `POST /carts/{customerId}/items` del servicio `carts` con el cuerpo `{"itemId", "quantity", "unitPrice"}`, donde `unitPrice` es ese precio vigente. El `customerId` SHALL ser el `X-Session-ID` del turno, el mismo identificador con el que la `ui` lee el carrito. El modelo MUST NOT poder elegir ni modificar el `customerId`: la tool no lo recibe como argumento. Dentro de un mismo turno, un segundo `addToCart` del mismo producto MUST NOT llamar al servicio `carts` y SHALL devolver un error que indique que ya se agregó en este turno.

#### Scenario: Agregar un producto mostrado
- **WHEN** en la sesión `s1` el asistente mostró un producto y el usuario escribe "add that one to my cart"
- **THEN** `GET /carts/s1` contiene un ítem con el `id` del producto, cantidad 1 y `unitPrice` igual al precio de `GET /catalog/products/{id}`

#### Scenario: Cantidad pedida por el usuario
- **WHEN** el usuario escribe "add two of the first one to my cart"
- **THEN** `GET /carts/<sesión>` contiene el producto con cantidad 2

#### Scenario: Aislamiento entre sesiones
- **WHEN** la sesión `s1` agrega un producto al carrito desde el chat
- **THEN** el carrito de cualquier otra sesión no cambia, aunque el mensaje del usuario mencione otro identificador de cliente

### Requirement: Confirmación fiel de las acciones
El asistente MUST NOT afirmar que agregó un producto al carrito si `addToCart` no terminó correctamente en ese turno. Si el servicio `carts` o el `catalog` fallan, la tool SHALL devolver un error, el turno SHALL terminar normalmente (con `done`) y la respuesta SHALL informar que no se pudo agregar el producto. El asistente SHALL llamar a `addToCart` solo cuando el usuario pide explícitamente agregar un producto; si el pedido es ambiguo respecto de qué producto agregar, SHALL preguntar en lugar de elegir uno.

#### Scenario: Servicio de carrito caído
- **WHEN** el servicio `carts` responde con error al `POST /carts/{customerId}/items` y el usuario había pedido agregar un producto
- **THEN** el stream termina con `done`, no se emite el evento de carrito actualizado y la respuesta dice que el producto no se pudo agregar

#### Scenario: Pedido ambiguo
- **WHEN** el asistente mostró tres lámparas y el usuario escribe "add the lamp to my cart"
- **THEN** el asistente pregunta cuál de las lámparas agregar y el carrito no cambia

### Requirement: Eventos SSE de tools y de carrito
Durante un turno, el stream de `POST /assistant/chat` SHALL incluir, además de los eventos que define el chat:
- un evento `tool` por cada tool ejecutada, con `data` `{"tool", "ok", "products"?}`, donde `products` es la lista `[{id, name, price}]` devuelta por `searchProducts` o `getProductDetails` cuando la ejecución fue correcta;
- un evento `cart-updated` después de cada `addToCart` correcto, con `data` `{"itemId", "name", "quantity", "unitPrice", "cartItemCount"}`, donde `cartItemCount` es la cantidad total de unidades del carrito después del cambio, o se omite si no se pudo leer el carrito.

Ambos eventos SHALL emitirse antes del texto que el modelo genere a partir de ese resultado. Los productos devueltos por las tools SHALL contar, junto con los del evento `products`, como productos mostrados en el turno para la memoria de la sesión.

#### Scenario: Carrito actualizado desde el chat
- **WHEN** el usuario pide agregar un producto y `addToCart` termina correctamente
- **THEN** el stream contiene un evento `tool` con `{"tool": "addToCart", "ok": true}` y un evento `cart-updated` con el `itemId`, la cantidad, el `unitPrice` vigente y el total de unidades del carrito, antes del evento `done`

#### Scenario: Nombres verificables
- **WHEN** la respuesta de un turno con `searchProducts` nombra productos con su precio
- **THEN** cada producto nombrado está en el evento `products` o en el `products` de algún evento `tool` del turno, con el mismo nombre y precio

### Requirement: Filtros estructurados desde lenguaje natural
Cuando el usuario expresa una categoría, un presupuesto o un orden de precio en lenguaje natural, el asistente SHALL traducirlos a argumentos de `searchProducts`: la categoría a uno o más tags existentes del catálogo, el presupuesto a `minPrice`/`maxPrice` y el orden a `order`. Los productos que el asistente presenta como respuesta a ese pedido SHALL cumplir esos filtros.

#### Scenario: Categoría, presupuesto y orden
- **WHEN** el usuario escribe "show me lamps under $100, cheapest first"
- **THEN** el turno ejecuta `searchProducts` con `tags` que incluyen `lighting`, `maxPrice` 100 y `order` `price_asc`, y cada producto que la respuesta presenta tiene el tag `lighting` y precio menor o igual a 100, en orden de precio ascendente

#### Scenario: Presupuesto en español
- **WHEN** el usuario escribe "busco una mesa de comedor de menos de 300 dólares"
- **THEN** el turno ejecuta `searchProducts` con `tags` que incluyen `tables` o `dining` y `maxPrice` 300, y la respuesta en español presenta solo productos que cumplen esos filtros

### Requirement: Consumo acotado por turno
Cada turno SHALL hacer como máximo una cantidad configurable de solicitudes al modelo principal (por defecto 4, incluidas las que siguen a la ejecución de tools) y ejecutar como máximo una cantidad configurable de tools (por defecto 6). La última solicitud permitida al modelo principal MUST enviarse sin tools disponibles, para que el turno termine con una respuesta en texto. En total, un turno MUST NOT superar una solicitud de reescritura más el máximo de solicitudes al modelo principal, sin contar los reintentos de solicitudes rechazadas por cuota.

#### Scenario: Modelo que pide tools en cada vuelta
- **WHEN** el modelo principal pide una tool en cada respuesta
- **THEN** el turno hace como máximo 4 solicitudes al modelo principal, la última sin tools, y termina con una respuesta en texto y el evento `done`

#### Scenario: Registro del consumo
- **WHEN** termina un turno que ejecutó `searchProducts` y `addToCart`
- **THEN** la línea de log del turno registra la cantidad de solicitudes al proveedor de chat, las tools ejecutadas con su resultado y el tiempo de espera del limitador

### Requirement: Limitador de solicitudes hacia el proveedor de chat
Todas las solicitudes del `assistant` al proveedor de chat (reescritura, modelo principal y vueltas posteriores a tools, de todas las sesiones) SHALL pasar por un limitador del lado del cliente que no permite más de una cantidad configurable de solicitudes en cualquier ventana de 60 segundos (por defecto 36, por debajo del límite de 40 RPM del proveedor). Cuando el límite está alcanzado, una solicitud del modelo principal SHALL esperar su turno, hasta una espera máxima configurable; si la espera necesaria supera ese máximo, el turno SHALL terminar con el evento `error` de tipo `llm-quota-exceeded` e indicar los segundos sugeridos de espera, sin enviar la solicitud. La reescritura MUST NOT esperar: si no hay lugar inmediato, el turno SHALL continuar con el mensaje original como consulta.

#### Scenario: Pico de mensajes
- **WHEN** varias sesiones envían, en menos de un minuto, mensajes que suman más solicitudes que el límite configurado
- **THEN** en ninguna ventana de 60 segundos salen más solicitudes al proveedor que el límite, y los turnos cuya espera no supera el máximo terminan con `done`

#### Scenario: Espera mayor que el máximo
- **WHEN** el límite está alcanzado y la espera necesaria para el modelo principal supera la espera máxima configurada
- **THEN** el stream termina con un evento `error` de tipo `llm-quota-exceeded` con los segundos sugeridos, y no se envió esa solicitud al proveedor

#### Scenario: Reescritura sin lugar disponible
- **WHEN** el límite está alcanzado al momento de reescribir la consulta
- **THEN** el turno usa el mensaje original como consulta, sin esperar ni llamar al modelo de reescritura, y la línea de log del turno lo indica

### Requirement: Espera ante rechazos por cuota
Si el proveedor de chat rechaza una solicitud del modelo principal con HTTP 429 antes de haber enviado cualquier fragmento de esa solicitud, el `assistant` SHALL esperar el tiempo indicado por el header `Retry-After` (o una espera por defecto configurable si no viene), suspender durante ese tiempo todas las solicitudes al proveedor desde el limitador, y reintentar la solicitud, hasta una cantidad configurable de reintentos (por defecto 2) y siempre que la espera no supere la espera máxima. Solo cuando se agotan los reintentos o la espera supera el máximo, el turno SHALL terminar con el evento `error` de tipo `llm-quota-exceeded` que define el chat. Un 429 en la reescritura MUST NOT reintentarse: el turno continúa con el mensaje original.

#### Scenario: 429 transitorio
- **WHEN** el proveedor responde 429 con `Retry-After: 3` a la solicitud del modelo principal y acepta el reintento
- **THEN** el `assistant` espera al menos 3 segundos, reintenta, y el stream termina con la respuesta completa y `done`, sin evento `error`

#### Scenario: 429 persistente
- **WHEN** el proveedor responde 429 a la solicitud del modelo principal y a todos sus reintentos
- **THEN** el stream termina con un evento `error` de tipo `llm-quota-exceeded` y el turno no queda guardado en la memoria de la sesión

#### Scenario: Suspensión compartida
- **WHEN** el proveedor responde 429 con `Retry-After: 5` a una solicitud de la sesión `s1`
- **THEN** durante esos 5 segundos el `assistant` no envía ninguna solicitud al proveedor de chat para ninguna sesión

### Requirement: Fallas de los servicios de la tienda durante las tools
Si `catalog` o `carts` no responden, responden con error o exceden su tiempo límite durante la ejecución de una tool, la tool SHALL devolver al modelo un error que distinga la causa (servicio no disponible o producto inexistente), y el turno SHALL continuar. Las lecturas al `catalog` SHALL reintentarse una vez ante errores 5xx o de red; la escritura al carrito MUST NOT reintentarse, para no duplicar ítems. Una falla de estos servicios MUST NOT afectar la readiness del `assistant`.

#### Scenario: Catálogo con error transitorio
- **WHEN** `GET /catalog/products/{id}` responde 500 y al reintento responde 200
- **THEN** `getProductDetails` devuelve el producto con su precio, y el turno no informa error

#### Scenario: Catálogo caído
- **WHEN** el `catalog` no responde durante un `getProductDetails`
- **THEN** la tool devuelve un error de servicio no disponible, la respuesta al usuario lo indica sin inventar el precio, el stream termina con `done` y `GET /actuator/health/readiness` sigue en `UP`
