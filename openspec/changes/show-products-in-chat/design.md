# Design

## Context

- El navegador ya recibe por SSE, en cada turno del provider `assistant`:
  - `products`: `[{id, name, price}]`, los productos recuperados por el RAG, una vez y antes del texto.
  - `tool`: `{tool, ok, error?, products?}`. Trae `products` (`[{id, name, price}]`) solo en `searchProducts` y `getProductDetails` correctas.
  - `cart-updated`: `{itemId, name, quantity, unitPrice, cartItemCount?}`.
- `chat.js` (`src/ui/src/main/resources/static/assets/js/chat.js`) ignora `products` y `tool` en `dispatchEvent`, y en cada fragmento re-renderiza todo el texto de la burbuja (`updateBotMessage` → `renderMarkdown`, con showdown + DOMPurify y `FORBID_TAGS: ["img", ...]`).
- El `assistant` emite el texto por oración (lo retiene hasta el fin de la oración por `CartClaimFilter`), así que hay pocos re-renders por turno.
- D4 de `integrate-ui-assistant` descartó dibujar los `products` porque son los k recuperados, no los recomendados. Este change resuelve esa objeción mostrando solo los que el texto nombra.
- Las imágenes del catálogo se sirven en `/assets/img/products/{id}.jpg` y la ficha en `/catalog/{id}`, siempre relativas al `contextPath` que recibe `ChatUI.init`.
- No hay tests de JavaScript en la `ui`. Los e2e (Cypress, `src/e2e`) corren en CI sin claves, así que no pueden depender de una respuesta real del modelo.
- Los 80 nombres del catálogo son únicos, tienen hasta 39 caracteres y hoy ninguno es substring de otro.

## Goals / Non-Goals

**Goals:**
- Que las tarjetas no se rompan ni parpadeen mientras llega el texto.
- Que ningún dato que venga del texto del modelo termine en un `src` o un `href`.

**Non-Goals:**
- Detectar menciones parciales o parafraseadas ("the Aiden armchair") o con errores de tipeo del modelo.
- Cambiar el `assistant`, su prompt o el contrato SSE, por ejemplo para que el modelo marque los productos que menciona.
- Conservar las tarjetas al navegar entre páginas: la conversación visible ya se pierde hoy (Non-Goal de `integrate-ui-assistant`).
- Tarjetas con botón de agregar al carrito: el carrito desde el chat sigue siendo por la tool `addToCart`.

## Decisions

### D1. Productos conocidos: un mapa por página, alimentado por los tres eventos

`ChatUI` mantiene un `Map` id → `{id, name, price}` con los productos conocidos de la conversación visible:

- `products` y `tool.products` aportan `{id, name, price}`.
- `cart-updated` aporta `{id: itemId, name, price: unitPrice}`.
- Un evento posterior con el mismo id reemplaza al anterior (el precio de las tools es el vivo del catálogo).

El mapa vive mientras la página esté abierta y el botón "clear" lo vacía junto con los mensajes.

- **Por qué no solo los del turno:** en el turno siguiente el usuario suele seguir hablando de lo que ya vio ("add the second one", "compare those two"), y en ese turno puede no haber búsqueda. El escenario "Producto de un turno anterior" del spec lo exige.
- **Alternativa descartada: pedirle al `assistant` el detalle de cada nombre.** No hace falta (los eventos ya traen id, nombre y precio) y sumaría requests y acoplamiento.

### D2. Validación antes de guardar

Un producto entra al mapa solo si:
- el id cumple `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` (sin distinguir mayúsculas),
- el nombre es un string no vacío,
- y el precio es un número finito y no negativo.

Lo demás se descarta en silencio, sin romper el turno. Con el id validado, las rutas `${contextPath}assets/img/products/${id}.jpg` y `${contextPath}catalog/${id}` no pueden apuntar a otro lado.

### D3. Detección de menciones sobre el DOM ya sanitizado

Después de cada `renderMarkdown`, una función `attachProductCards(textDiv, turn)`:

1. Recorre en orden de documento los bloques del mensaje: `p`, `li`, `h1`–`h6` y `blockquote`. Para un `li` con una lista anidada, usa solo el texto del propio ítem.
2. Normaliza el `textContent` de cada bloque y los nombres de los productos conocidos de la misma forma:
   - minúsculas,
   - guiones Unicode (‐ ‑ – —) a `-`,
   - comillas tipográficas a rectas,
   - espacios colapsados.

   El markdown ya no está en el `textContent`, así que `**Aiden …**` coincide.
3. Busca los nombres de mayor a menor longitud y "consume" cada coincidencia (la reemplaza por espacios en la copia normalizada), para que un nombre que sea substring de otro no genere una segunda tarjeta.
4. A cada producto lo asigna al primer bloque donde aparece, sin repetir productos en el mensaje y con un tope de 6 tarjetas.
5. Inserta las tarjetas del bloque justo después de él (`afterend`). En un `li`, las agrega al final del propio `li`, para no dejar un `div` como hijo directo de `ul`/`ol`.

- **Por qué sobre el DOM y no sobre el markdown crudo:** el texto ya pasó por DOMPurify, los bloques ya existen y el `textContent` no tiene sintaxis markdown.
- **Por qué nombre completo:** con 80 nombres únicos y descriptivos, el nombre completo casi no da falsos positivos. Ignorar mayúsculas y formato cubre la forma en que el modelo los suele escribir (en negrita, a veces en title case). Las menciones parciales quedan como Non-Goal.
- **Alternativa descartada: marcadores del modelo (`[[product:id]]`).** Exige cambiar el prompt y el `assistant`, gasta tokens y deja que el modelo escriba ids, que puede inventar.

### D4. Tarjetas estables durante el streaming

Como `renderMarkdown` reemplaza el `innerHTML` en cada fragmento, las tarjetas se destruirían en cada re-render. Para evitar que la imagen se vuelva a pedir o parpadee, cada turno guarda un `Map` id → nodo de tarjeta. En cada pasada se reutiliza el mismo nodo y se lo mueve a su posición: mover un `<img>` ya cargado no lo vuelve a descargar.

El cálculo se repite en cada fragmento, así que una tarjeta aparece en cuanto llega la oración que nombra al producto, y queda fija en su bloque porque la primera mención no cambia de lugar al agregarse texto después.

### D5. La tarjeta se arma con `createElement`, nunca con HTML

La tarjeta es un `<a class="chat-product-card" href="…/catalog/{id}">` con:
- `<img src="…/assets/img/products/{id}.jpg" alt="{name}" loading="lazy">`,
- el nombre por `textContent`,
- y el precio por `textContent`, con el mismo formato que el resto del sitio (`$139`).

Si la imagen falla (`error`), se oculta y la tarjeta queda con nombre y precio. No se toca la configuración de DOMPurify: las imágenes del texto del modelo siguen prohibidas, y la de la tarjeta se agrega fuera del HTML sanitizado.

- **Enlace en la misma pestaña**, como el resto de los enlaces a fichas de la tienda. La conversación visible se pierde al navegar, pero la memoria del `assistant` conserva el contexto. Abrir en otra pestaña (`target="_blank"`) es un cambio de una línea si en la demo resulta más cómodo.

### D6. Estilos

Las clases nuevas van en `styles.css`:
- la tarjeta: fila compacta con la imagen de 48×48 con `object-fit: cover`, el nombre y el precio a la derecha, fondo blanco y texto oscuro sobre la burbuja `bg-primary-500`, bordes redondeados, ancho máximo del contenido de la burbuja y hover sutil;
- separación vertical entre la tarjeta y el texto siguiente.

Se usan las variables de color del tema activo donde existan, para que se vea bien con los tres temas.

### D7. Test e2e con el stream simulado

Un caso nuevo en `src/e2e/cypress/e2e/assistant.cy.js` usa `cy.intercept("POST", "**/chat/submit", …)` para responder un cuerpo `text/event-stream` fijo con:
- un evento `products` con dos productos reales del catálogo, uno con un id inválido,
- fragmentos de texto que nombran a uno de los reales y al del id inválido,
- y `done`.

Comprueba que:
- hay una sola tarjeta, debajo del párrafo que nombra al producto real,
- su `href` termina en `/catalog/{id}` y su imagen apunta a `/assets/img/products/{id}.jpg`,
- no hay tarjeta del producto no nombrado ni del id inválido,
- no hay `img` dentro de `.message-text`.

Así el test es determinista y pasa en CI sin claves.

- **Alternativa descartada: un framework de tests unitarios de JS (Jest, Vitest).** La `ui` no tiene toolchain de Node, y sumarlo para un archivo no se justifica. Cypress ya corre en CI contra el cluster.

## Risks / Trade-offs

- **[El modelo parafrasea el nombre y no aparece la tarjeta]** → Se acepta (Non-Goal). Si en el ensayo pasa seguido, una mejora posterior sería pedir en el prompt que use los nombres completos, en otro change.
- **[Falso positivo: un nombre que aparece en un texto que no lo recomienda]** ("I didn't find anything like the X") → Igual muestra la tarjeta. Con nombres completos y descriptivos es raro, y la tarjeta es informativa: no agrega al carrito.
- **[Costo de recalcular en cada fragmento]** → A lo sumo unos 80 nombres contra unos pocos bloques, unas decenas de veces por turno. Es despreciable.
- **[El precio de la tarjeta puede ser el del payload de Qdrant y no el vivo]** → En `products` (RAG) el precio es el de la última sincronización. El de `tool` y `cart-updated` es el vivo y reemplaza al anterior (D1). Con el catálogo estático en runtime, coinciden.
- **[Imagen faltante para algún producto]** → El generador del catálogo garantiza una imagen por producto. Igual, D5 oculta la imagen rota.

## Migration Plan

1. Implementar en la `ui` y desplegar con `./local.sh reload-images` y `kubectl rollout restart deployment/ui -n the-store`. No hay datos que migrar ni cambios en el manifiesto.
2. Rollback: revertir el commit y volver a desplegar la `ui`. Como solo cambia el front, no afecta al `assistant` ni a la cuota.
