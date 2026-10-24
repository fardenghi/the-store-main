# Tasks

## 1. Productos conocidos

- [x] 1.1 En `chat.js`, agregar a `ChatUI` el mapa de productos conocidos y su validación (D1, D2): `products` y `tool.products` aportan `{id, name, price}`, `cart-updated` aporta `{itemId, name, unitPrice}`, un id repetido reemplaza al anterior, y solo entran ids UUID con nombre no vacío y precio finito no negativo. Manejar `products` y `tool` en `dispatchEvent` sin mostrar nada como texto, y vaciar el mapa en `clearMessages`. Verificar con la `ui` sola (`provider=mock`, `cd src/ui && ./mvnw spring-boot:run`) que el chat sigue respondiendo igual y sin errores en la consola del navegador.

## 2. Tarjetas en los mensajes

- [x] 2.1 Implementar `attachProductCards(textDiv, turn)` después de cada `renderMarkdown` del bot (D3): recorrer `p`, `li` (solo el texto propio), `h1`–`h6` y `blockquote` en orden, normalizar el texto y los nombres (minúsculas, guiones y comillas tipográficas, espacios), buscar de mayor a menor longitud consumiendo cada coincidencia, asignar cada producto a su primer bloque, sin repetir productos y con un tope de 6 por mensaje, e insertar las tarjetas `afterend` del bloque (al final del propio `li` en las listas). Verificar en el navegador, con un stream simulado desde la consola o con el test 3.1, que un producto nombrado en negrita y en otra capitalización tiene su tarjeta debajo del párrafo correcto, y que un producto conocido no nombrado no la tiene.
- [x] 2.2 Armar la tarjeta con `createElement` y `textContent` (D5): `<a class="chat-product-card" href="{contextPath}catalog/{id}">` con `<img src="{contextPath}assets/img/products/{id}.jpg" alt="{name}" loading="lazy">` que se oculta ante `error`, el nombre y el precio con el formato `$139`. Reutilizar el nodo de cada tarjeta entre re-renders con un `Map` por turno (D4). Sin cambiar la configuración de DOMPurify. Verificar en el navegador que, mientras llegan varios fragmentos, la imagen de una tarjeta se pide una sola vez (pestaña Network), que el click abre la ficha y que una imagen inexistente no muestra el ícono de imagen rota.
- [x] 2.3 Agregar los estilos de la tarjeta en `styles.css` (D6): fila compacta con la imagen de 48×48 (`object-fit: cover`), el nombre y el precio, fondo claro con texto oscuro sobre la burbuja del bot, bordes redondeados, hover y separación con el texto siguiente. Verificar a simple vista en los temas default, green y orange (`RETAIL_UI_THEME`), en ancho de escritorio y de celular, que la tarjeta se lee bien y no desborda la burbuja.

## 3. Tests y documentación

- [x] 3.1 Agregar a `src/e2e/cypress/e2e/assistant.cy.js` un caso con `cy.intercept("POST", "**/chat/submit", …)` que responde un cuerpo `text/event-stream` fijo (D7). El cuerpo trae: un `products` con dos productos reales del catálogo y uno con id `../../admin`; texto en dos párrafos que nombra a uno de los reales en negrita y al del id inválido; y `done`. El test comprueba que:
  - hay exactamente una `.chat-product-card`, ubicada después del párrafo que nombra al producto;
  - su `href` termina en `/catalog/{id}` y su imagen en `/assets/img/products/{id}.jpg`;
  - no hay tarjeta para el producto no nombrado ni para el del id inválido;
  - `.message-text` no contiene ningún `img`.

  Sumar los selectores que haga falta en `src/e2e/cypress/pages/Chat.js`. Verificar con `./local.sh e2e-test` en el cluster que pasan todos los casos (los 15 existentes más el nuevo).
- [x] 3.2 Documentar el comportamiento en el `README.md` de la `ui`: en la tabla de eventos que recibe el navegador, `products` y `tool` pasan de "Se ignora" a alimentar las tarjetas; y un párrafo sobre cómo se detecta la mención y la validación de ids. Sumar la tarjeta a la fila de los casos de uso de búsqueda en `docs/how-to.md` (sección 6). Verificar que los dos archivos describen el mismo comportamiento que el spec.

## 4. Verificación en el cluster

- [x] 4.1 Desplegar con `./local.sh reload-images` y `kubectl rollout restart deployment/ui -n the-store` y repetir con el `assistant` real los casos del ensayo:
  - "somewhere cozy to curl up with a book";
  - "show me lamps under $100, cheapest first", con la respuesta en lista;
  - la comparación de dos sillones;
  - "add the Tinted Glass Pendant Light to my cart".

  Verificar que cada producto que la respuesta nombra con su nombre completo tiene su tarjeta debajo de la mención, y que el chat no muestra tarjetas de productos que no nombra. Anotar los casos en que el modelo parafraseó un nombre y no apareció la tarjeta (riesgo aceptado en el design).
- [x] 4.2 Correr `openspec validate show-products-in-chat` y verificar que no reporta errores.
