# Spec Delta

## ADDED Requirements

### Requirement: Tarjetas de los productos que menciona el asistente
Cuando el texto de un mensaje del asistente nombra un producto conocido, el chat SHALL mostrar una tarjeta de ese producto justo debajo del bloque de texto (párrafo, ítem de lista o encabezado) donde se lo nombra por primera vez en ese mensaje. Un producto es conocido si su id, nombre y precio llegaron en algún evento `products`, `tool` (campo `products`) o `cart-updated` de la conversación visible en el chat, en ese turno o en uno anterior. La mención SHALL detectarse por la aparición del nombre completo del producto en el texto, sin distinguir mayúsculas ni el formato markdown. Cada tarjeta SHALL mostrar la imagen del producto, su nombre y su precio, y SHALL enlazar a la ficha del producto (`/catalog/{id}`). La imagen y el enlace SHALL armarse a partir del id del producto, sobre rutas de la propia tienda, y MUST NOT tomarse del texto del asistente. La `ui` MUST NOT mostrar una tarjeta para un producto cuyo id no tenga formato UUID o cuyo nombre o precio falten o sean inválidos. Cada producto SHALL tener como máximo una tarjeta por mensaje, y cada mensaje como máximo 6 tarjetas, en el orden de la primera mención. Las tarjetas SHALL aparecer a medida que llega el texto que nombra al producto, sin esperar el fin del turno, y MUST NOT interrumpir el turno ni cambiar el texto mostrado. Un producto conocido que el texto no nombra MUST NOT tener tarjeta.

#### Scenario: Producto nombrado en un párrafo
- **WHEN** el turno incluye un evento `products` con el Aiden Mid-Century Velvet Armchair y el asistente responde "For your reading corner, the **Aiden Mid-Century Velvet Armchair** is deep and soft."
- **THEN** debajo de ese párrafo aparece una tarjeta con la imagen, el nombre "Aiden Mid-Century Velvet Armchair" y el precio del producto, que enlaza a su ficha

#### Scenario: Productos en una lista
- **WHEN** el asistente responde con una lista de tres lámparas que llegaron en el evento `tool` de `searchProducts`
- **THEN** debajo de cada ítem de la lista aparece la tarjeta de la lámpara que ese ítem nombra

#### Scenario: Producto recuperado pero no nombrado
- **WHEN** el evento `products` trae cinco productos y la respuesta nombra solo dos
- **THEN** el mensaje muestra solo las tarjetas de esos dos productos

#### Scenario: Producto nombrado más de una vez
- **WHEN** la respuesta nombra el mismo producto en el primer párrafo y en el último
- **THEN** el mensaje muestra una sola tarjeta de ese producto, debajo del primer párrafo

#### Scenario: Producto de un turno anterior
- **WHEN** un turno mostró la tarjeta de un producto y en el turno siguiente, sin eventos con ese producto, la respuesta lo vuelve a nombrar
- **THEN** el mensaje nuevo también muestra la tarjeta de ese producto

#### Scenario: Producto agregado al carrito
- **WHEN** el turno incluye un evento `cart-updated` de un producto y la respuesta lo nombra
- **THEN** el mensaje muestra la tarjeta de ese producto

#### Scenario: Tarjeta durante el streaming
- **WHEN** el asistente nombra un producto en la primera oración de una respuesta de varias oraciones
- **THEN** la tarjeta aparece antes de que el turno termine con `done`

#### Scenario: Ir a la ficha desde la tarjeta
- **WHEN** el usuario hace click en la tarjeta de un producto en el chat
- **THEN** el navegador abre la ficha de ese producto

#### Scenario: Id inválido
- **WHEN** un evento `products` trae un producto con id `../../admin` y la respuesta nombra ese producto
- **THEN** el chat no muestra una tarjeta para ese producto y muestra el texto normalmente

#### Scenario: Imagen no disponible
- **WHEN** la imagen de un producto nombrado no se puede cargar
- **THEN** la tarjeta muestra el nombre, el precio y el enlace, sin una imagen rota

#### Scenario: Provider sin eventos de productos
- **WHEN** la `ui` corre con el provider `mock` y la respuesta del chat nombra un producto del catálogo
- **THEN** el chat muestra solo el texto, sin tarjetas

## MODIFIED Requirements

### Requirement: Presentación de la respuesta y de los errores en el chat
El chat del navegador SHALL mostrar como texto del asistente únicamente la concatenación, en orden, de los fragmentos de texto, renderizada como markdown sin ejecutar HTML ni scripts incluidos en la respuesta y sin cargar imágenes indicadas en el texto. Los eventos con nombre que no son texto (`products`, `tool`, `cart-updated`, `done`, `error`) y los comentarios SSE de keepalive MUST NOT aparecer como texto en el chat. Los datos de productos que llegan en `products`, `tool` y `cart-updated` SHALL usarse solo para las tarjetas de los productos que menciona el asistente. Mientras un turno está en curso, el chat MUST NOT permitir enviar otro mensaje. Si el turno termina con un evento `error`, o el stream se corta sin `done`, el chat SHALL mostrar un mensaje legible acorde a la causa: cuota excedida (indicando los segundos de espera sugeridos cuando vienen), sesión ocupada, mensaje inválido, o asistente no disponible; y SHALL volver a permitir enviar mensajes.

#### Scenario: Eventos con nombre ocultos
- **WHEN** un turno del asistente incluye los eventos `products` y `tool` antes del texto
- **THEN** el chat muestra la respuesta en texto, y como mucho las tarjetas de los productos que nombra, sin JSON ni nombres de eventos

#### Scenario: Respuesta con HTML
- **WHEN** la respuesta del asistente contiene `<img src=x onerror=alert(1)>`
- **THEN** el chat no ejecuta ningún script y muestra el resto de la respuesta

#### Scenario: Imagen en el markdown de la respuesta
- **WHEN** la respuesta del asistente contiene `![lamp](https://example.com/lamp.jpg)`
- **THEN** el navegador no carga esa imagen y el chat muestra el resto de la respuesta

#### Scenario: Cuota excedida
- **WHEN** el turno termina con un evento `error` de tipo `llm-quota-exceeded` con `retryAfterSeconds` 20
- **THEN** el chat muestra un mensaje que indica que el asistente está saturado y que se puede reintentar en unos 20 segundos, y el campo de texto vuelve a estar habilitado

#### Scenario: Envío bloqueado durante un turno
- **WHEN** el asistente todavía está respondiendo
- **THEN** el usuario no puede enviar un nuevo mensaje hasta que llega `done` o `error`
