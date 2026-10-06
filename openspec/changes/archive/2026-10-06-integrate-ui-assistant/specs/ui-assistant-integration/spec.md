# Spec Delta

## Purpose

Define cómo la `ui` expone al usuario las capacidades del `assistant`: el chat con streaming a través del provider `assistant` con la sesión del navegador propagada, los productos similares en la ficha de producto y el reflejo inmediato en la interfaz de los cambios de carrito hechos desde el chat, manteniendo a la `ui` como capa de presentación.

## ADDED Requirements

### Requirement: Chat de la tienda con el provider `assistant`
La `ui` SHALL ofrecer un provider de chat `assistant`, seleccionable por configuración junto a los existentes (`mock`, `openai`, `bedrock`). Con ese provider, `POST /chat/submit` de la `ui` SHALL reenviar el mensaje del usuario al endpoint de chat del `assistant` por HTTP con streaming SSE y SHALL retransmitir la respuesta al navegador de forma incremental, a medida que llega del `assistant`, como eventos SSE: los fragmentos de texto sin nombre con `data` `{"text": "<fragmento>"}`, el evento `cart-updated`, y un evento final `done` o `error`. La `ui` MUST NOT llamar a un modelo de lenguaje cuando el provider es `assistant`: toda la lógica de GenAI queda en el `assistant`.

#### Scenario: Respuesta del asistente en el chat
- **WHEN** con el provider `assistant` el usuario escribe "I need a lamp for my desk" en el chat de la tienda
- **THEN** el chat muestra una respuesta del asistente que nombra lámparas del catálogo, y el stream que recibe el navegador termina con el evento `done`

#### Scenario: Entrega incremental hasta el navegador
- **WHEN** el `assistant` genera una respuesta de varias oraciones
- **THEN** el navegador recibe y muestra el primer fragmento de texto antes de que el `assistant` termine de generar la respuesta completa

### Requirement: Sesión compartida entre chat y carrito
Cada mensaje que la `ui` reenvía al `assistant` SHALL llevar el header `X-Session-ID` con el identificador de sesión de la cookie `SESSIONID` del navegador, que es el mismo identificador que la `ui` usa como `customerId` del carrito. Si el navegador no tiene la cookie, la `ui` SHALL generar un identificador nuevo, enviarlo como cookie válida para todas las rutas de la tienda y usarlo en ese mismo request. El navegador MUST NOT poder elegir otro identificador de sesión para el chat mediante headers o el cuerpo del mensaje: el único origen es la cookie.

#### Scenario: Agregado desde el chat visible en el carrito del mismo navegador
- **WHEN** un usuario pide desde el chat agregar un producto al carrito y después abre la página del carrito en el mismo navegador
- **THEN** el carrito muestra ese producto

#### Scenario: Primera visita a la ficha de un producto
- **WHEN** un navegador sin cookie de sesión entra directamente a `/catalog/{id}`, usa el chat para agregar un producto y luego navega a `/cart`
- **THEN** la página del carrito usa la misma sesión que el chat y muestra el producto agregado

#### Scenario: Header de sesión enviado por el navegador
- **WHEN** el navegador envía a `POST /chat/submit` un header `X-Session-ID` distinto del valor de su cookie `SESSIONID`
- **THEN** el `assistant` recibe el valor de la cookie y no el del header enviado por el navegador

#### Scenario: Sesiones aisladas entre navegadores
- **WHEN** dos navegadores distintos conversan con el asistente al mismo tiempo
- **THEN** cada uno recibe respuestas basadas solo en su propia conversación, y lo que uno agrega al carrito desde el chat no aparece en el carrito del otro

### Requirement: Persona fuera de la configuración de la `ui`
La configuración de la `ui` MUST NOT contener el system prompt ni la persona del asistente. Con el provider `assistant`, el pedido que la `ui` envía al `assistant` SHALL contener solo el mensaje del usuario y la sesión, sin instrucciones de sistema; la persona A.G.E.N.T. la aplica el `assistant`.

#### Scenario: Pedido sin system prompt
- **WHEN** la `ui` reenvía un mensaje al `assistant`
- **THEN** el cuerpo del pedido contiene únicamente el mensaje del usuario y el pedido lleva el header `X-Session-ID`, sin system prompt

#### Scenario: Persona aplicada por el `assistant`
- **WHEN** el usuario escribe "a desk for my home office" en el chat de la tienda
- **THEN** la respuesta mantiene el tono de A.G.E.N.T. referido a la guarida, sin que la `ui` tenga configurada ninguna persona

### Requirement: Presentación de la respuesta y de los errores en el chat
El chat del navegador SHALL mostrar como texto del asistente únicamente la concatenación, en orden, de los fragmentos de texto, renderizada como markdown sin ejecutar HTML ni scripts incluidos en la respuesta. Los eventos con nombre que no son texto (`products`, `tool`, `cart-updated`, `done`, `error`) y los comentarios SSE de keepalive MUST NOT aparecer como texto en el chat. Mientras un turno está en curso, el chat MUST NOT permitir enviar otro mensaje. Si el turno termina con un evento `error`, o el stream se corta sin `done`, el chat SHALL mostrar un mensaje legible acorde a la causa: cuota excedida (indicando los segundos de espera sugeridos cuando vienen), sesión ocupada, mensaje inválido, o asistente no disponible; y SHALL volver a permitir enviar mensajes.

#### Scenario: Eventos con nombre ocultos
- **WHEN** un turno del asistente incluye los eventos `products` y `tool` antes del texto
- **THEN** el chat muestra solo la respuesta en texto, sin JSON ni nombres de eventos

#### Scenario: Respuesta con HTML
- **WHEN** la respuesta del asistente contiene `<img src=x onerror=alert(1)>`
- **THEN** el chat no ejecuta ningún script y muestra el resto de la respuesta

#### Scenario: Cuota excedida
- **WHEN** el turno termina con un evento `error` de tipo `llm-quota-exceeded` con `retryAfterSeconds` 20
- **THEN** el chat muestra un mensaje que indica que el asistente está saturado y que se puede reintentar en unos 20 segundos, y el campo de texto vuelve a estar habilitado

#### Scenario: Envío bloqueado durante un turno
- **WHEN** el asistente todavía está respondiendo
- **THEN** el usuario no puede enviar un nuevo mensaje hasta que llega `done` o `error`

### Requirement: Aislamiento de fallas del `assistant`
Si el `assistant` no está disponible (sin réplicas, conexión rechazada, error 5xx o sin respuesta dentro del tiempo límite de la `ui`) o rechaza el mensaje antes de abrir el stream (`400` o `409`), la `ui` SHALL responder al navegador con un evento `error` cuyo `type` distingue la causa (`assistant-unavailable`, `invalid-parameter` o `session-busy`), en lugar de un error HTTP o de un stream colgado. Una falla del `assistant` MUST NOT afectar al resto de la tienda: catálogo, ficha, carrito y checkout SHALL seguir funcionando, y la readiness de la `ui` MUST NOT depender del `assistant`.

#### Scenario: `assistant` caído
- **WHEN** el `assistant` está escalado a 0 réplicas y el usuario envía un mensaje en el chat
- **THEN** el chat muestra que el asistente no está disponible, y el catálogo, la ficha de producto y el carrito siguen funcionando

#### Scenario: Stream cortado por el `assistant`
- **WHEN** la conexión con el `assistant` se cierra después de algunos fragmentos y sin evento `done`
- **THEN** el navegador recibe un evento `error` de tipo `assistant-unavailable` y el chat lo informa

### Requirement: Conexión del chat viva y cancelable
Mientras espera fragmentos del `assistant` (por ejemplo, durante el razonamiento de una comparación o la espera del limitador de cuota), la `ui` SHALL mantener viva la conexión con el navegador enviando comentarios SSE al menos cada 15 segundos, para que el ingress no la corte por inactividad. Si el navegador cierra la conexión antes de que termine el turno, la `ui` SHALL cerrar la conexión con el `assistant`, para que el turno se cancele y la sesión quede libre.

#### Scenario: Respuesta que tarda más que el timeout del ingress
- **WHEN** el primer fragmento del asistente tarda 70 segundos en llegar, a través del ingress de la tienda
- **THEN** el navegador recibe la respuesta completa y el evento `done`, sin que la conexión se corte

#### Scenario: Usuario que abandona la página
- **WHEN** el usuario cierra la pestaña mientras el asistente está respondiendo y, desde otra pestaña con la misma sesión, envía un mensaje nuevo unos segundos después
- **THEN** el mensaje nuevo se responde normalmente y no se rechaza por sesión ocupada

### Requirement: Productos similares en la ficha de producto
La ficha de producto (`/catalog/{id}`) SHALL mostrar una sección con los productos más similares al producto exhibido, obtenidos del endpoint de similares del `assistant`, en el orden de similitud que devuelve, hasta una cantidad configurable k (por defecto 4). Cada producto de la sección SHALL mostrar imagen, nombre y precio, y enlazar a su propia ficha. El producto exhibido MUST NOT aparecer en la sección. Si el `assistant` no está configurado, no responde dentro de un tiempo límite corto, responde con error (por ejemplo, índice no disponible o producto no indexado) o devuelve una lista vacía, la ficha SHALL mostrarse completa y funcional, sin la sección y sin mensajes de error.

#### Scenario: Ficha con similares
- **WHEN** el índice de productos está sincronizado y se abre la ficha de un producto
- **THEN** la ficha muestra una sección con 4 productos distintos del exhibido, cada uno con imagen, nombre, precio y enlace a su ficha, en el orden que devuelve el `assistant`

#### Scenario: Similares coherentes en la demo
- **WHEN** se abre la ficha de un sillón de terciopelo
- **THEN** al menos 2 de los productos de la sección son asientos

#### Scenario: `assistant` caído
- **WHEN** el `assistant` está escalado a 0 réplicas y se abre la ficha de un producto
- **THEN** la ficha se muestra con nombre, descripción, precio y botón de agregar al carrito, sin la sección de similares, y agregar al carrito funciona

#### Scenario: Índice no disponible
- **WHEN** el `assistant` responde `503` porque el índice todavía no tiene productos
- **THEN** la ficha se muestra sin la sección de similares y sin mensajes de error

#### Scenario: `assistant` lento
- **WHEN** el `assistant` no responde al pedido de similares
- **THEN** la ficha termina de cargar dentro del tiempo límite configurado para los similares, sin la sección

### Requirement: Reflejo en la interfaz de los cambios de carrito hechos desde el chat
Cuando el stream del chat incluye un evento `cart-updated`, el navegador SHALL actualizar el contador de unidades del carrito de la barra superior sin recargar la página: con el `cartItemCount` del evento cuando viene, o releyendo el carrito de la sesión cuando no viene. Si la página abierta es la del carrito, la vista del carrito SHALL actualizarse con los ítems vigentes, también sin recargar la página. La actualización MUST NOT interrumpir el turno en curso ni borrar la conversación visible en el chat. Si el turno no emite `cart-updated` (por ejemplo, porque el agregado falló), el contador MUST NOT cambiar.

#### Scenario: Contador actualizado desde la ficha o el catálogo
- **WHEN** el carrito tiene 1 unidad, el usuario está en el catálogo y pide desde el chat "add two of the first one to my cart"
- **THEN** sin recargar la página, el contador del carrito pasa a 3 y la conversación sigue visible en el chat

#### Scenario: Vista del carrito actualizada
- **WHEN** el usuario está en la página del carrito y pide desde el chat agregar un producto
- **THEN** sin recargar la página, la lista del carrito muestra el producto con su cantidad y precio, y el chat sigue abierto con la respuesta completa

#### Scenario: Agregado fallido
- **WHEN** el usuario pide agregar un producto y el servicio `carts` falla, por lo que el turno termina con `done` sin `cart-updated`
- **THEN** el contador del carrito no cambia y el chat muestra la respuesta del asistente indicando que no se pudo agregar

### Requirement: El navegador no accede directamente al `assistant`
El navegador SHALL comunicarse con el asistente solo a través de la `ui`. El `assistant` MUST NOT quedar expuesto por el ingress de la tienda, y la `ui` MUST NOT ofrecer rutas que reenvíen pedidos arbitrarios al `assistant`.

#### Scenario: Ruta del `assistant` desde afuera del cluster
- **WHEN** se consulta `http://localhost/assistant/products/search?q=lamp` a través del ingress
- **THEN** el pedido no llega al `assistant`

### Requirement: Despliegue con el asistente habilitado
El manifiesto del cluster SHALL desplegar la `ui` con el chat habilitado, el provider `assistant` y el endpoint interno del `assistant` configurados en el `ConfigMap` de la `ui`, sin claves de proveedores de modelos en la configuración de la `ui`. Con el provider `assistant` y sin endpoint configurado, la `ui` MUST NOT arrancar. El cluster SHALL poder levantarse y pasar los tests end-to-end sin claves de proveedores (como en CI): en ese caso el chat informa el error del `assistant` y el resto de la tienda funciona. Los providers `mock`, `openai` y `bedrock` SHALL seguir disponibles para correr la `ui` sin el `assistant`.

#### Scenario: Cluster con claves
- **WHEN** se crea el cluster con `NVIDIA_API_KEY` y `GOOGLE_API_KEY` válidas y se abre la tienda en `http://localhost`
- **THEN** el botón de chat está visible en todas las páginas, el chat responde con el asistente y la ficha de producto muestra similares

#### Scenario: Cluster sin claves
- **WHEN** se crea el cluster sin claves de proveedores y se ejecutan los tests end-to-end
- **THEN** todos los pods llegan a Ready, los tests pasan y un mensaje en el chat termina con un aviso de error del asistente

#### Scenario: Provider `assistant` sin endpoint
- **WHEN** la `ui` arranca con el chat habilitado, el provider `assistant` y sin endpoint del `assistant`
- **THEN** el arranque falla con un mensaje que indica que falta el endpoint del `assistant`

#### Scenario: `ui` sola con el provider `mock`
- **WHEN** la `ui` corre sin endpoints de servicios y con el provider `mock`
- **THEN** el chat responde con la respuesta del mock terminada en `done`, y la ficha de producto se muestra sin la sección de similares
