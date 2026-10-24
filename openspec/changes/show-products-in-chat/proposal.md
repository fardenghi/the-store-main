# Proposal

## Why

Cuando el asistente recomienda productos en el chat, hoy los nombra solo como texto: para ver cómo es un producto o ir a su ficha, el usuario tiene que buscarlo a mano en el catálogo. La `ui` ya recibe en cada turno los datos de esos productos (id, nombre y precio en los eventos SSE `products`, `tool` y `cart-updated`), pero los descarta. Mostrarlos con su imagen y un enlace a la ficha hace que la recomendación se pueda ver y usar en el momento, y mejora la demo de los casos de búsqueda semántica, comparación y filtros.

## What Changes

- Cuando el texto del asistente nombra un producto del que la `ui` tiene datos, el chat muestra, justo debajo del párrafo o del ítem de lista donde se lo nombra por primera vez en ese mensaje, una tarjeta con la imagen, el nombre y el precio del producto, que enlaza a su ficha (`/catalog/{id}`).
- Las tarjetas aparecen mientras se escribe la respuesta, no recién al terminar el turno.
- Los datos de las tarjetas salen solo de los eventos SSE que ya manda el `assistant` (`products`, `tool` con `products` y `cart-updated`), acumulados durante la conversación visible. La imagen (`/assets/img/products/{id}.jpg`) y el enlace los arma la `ui` a partir del id, y solo para ids con formato UUID.
- La regla actual de no renderizar imágenes que vengan dentro del texto del modelo se mantiene: las imágenes de las tarjetas nunca salen del texto generado por el LLM.
- Solo cambia la `ui` (`chat.js` y estilos). El `assistant`, su prompt, el contrato SSE y el consumo de cuota no cambian.

## Capabilities

### New Capabilities

Ninguna.

### Modified Capabilities

- `ui-assistant-integration`: se agrega un requirement para las tarjetas de los productos que menciona el asistente en el chat, y se ajusta el requirement "Presentación de la respuesta y de los errores en el chat" para que los datos de los eventos `products`, `tool` y `cart-updated` se usen para esas tarjetas, sin dejar de estar ocultos como texto.

## Impact

- **Código:** `src/ui/src/main/resources/static/assets/js/chat.js` (manejo de los eventos `products`, `tool` y `cart-updated`, y armado de las tarjetas después de renderizar el markdown) y `src/ui/src/main/resources/static/assets/css/styles.css`.
- **Tests:** un caso nuevo en `src/e2e/cypress/e2e/assistant.cy.js` con el stream del chat simulado, para que pase sin claves de proveedores, como el CI.
- **Docs:** README de la `ui` (tabla de eventos que recibe el navegador) y `docs/how-to.md`.
- **Sin cambios** en el `assistant`, en `dist/kubernetes.yaml`, en las APIs ni en las dependencias. Con los providers `mock`, `openai` y `bedrock`, que no mandan esos eventos, el chat se ve igual que hoy.
- **Pre-entrega:** es un agregado en la capa de presentación que no contradice `docs/preentrega.md`.
