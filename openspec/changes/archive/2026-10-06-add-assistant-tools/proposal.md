# Proposal

## Why

Un asistente que solo conversa no puede filtrar con precisión por presupuesto o categoría, ni garantizar precios actualizados, ni actuar sobre la tienda. La pre-entrega compromete function calling contra los microservicios reales: búsqueda con filtros estructurados, detalle con precio en tiempo real y agregar productos al carrito.

## What Changes

- Tres tools expuestas al modelo principal (`nemotron-3-super-120b-a12b`):
  - **`searchProducts`** (consulta opcional, tags, precio mínimo/máximo, orden): con texto va a Qdrant (vector + filtro de payload por tags OR y rango de precio); sin texto va a `GET /catalog/products?tags=&order=` y filtra el precio en `assistant`. En ambos casos devuelve los datos vigentes del catálogo.
  - **`getProductDetails`** (id): `GET /catalog/products/{id}`. El precio siempre sale del catálogo y nunca del payload del vector store.
  - **`addToCart`** (id, cantidad): consulta el precio vivo y llama a `POST /carts/{customerId}/items` con `{itemId, quantity, unitPrice}`. El `customerId` es el `X-Session-ID` de la sesión, el mismo con el que la UI identifica el carrito.
- La tool de carrito informa a la capa de chat que el carrito cambió, para que la UI lo refleje.
- Rate limiter del lado del cliente hacia NVIDIA y manejo de 429 con `Retry-After`, para que un pico se traduzca en espera y no en error en pantalla.

## Capabilities

### New Capabilities
- `assistant-tools`: acciones que el asistente puede ejecutar sobre los microservicios (búsqueda con filtros estructurados, detalle con precio vivo, agregar al carrito), y el control de consumo contra el proveedor del LLM.

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- `src/assistant`: definición de tools, clientes REST hacia `catalog` y `carts`, y rate limiter.
- Usa las APIs existentes de `catalog` y `cart` sin modificarlas.
- Casos de uso de la pre-entrega que cubre: agregar al carrito desde el chat, precio y detalle en tiempo real, y filtros estructurados.
- Depende de `add-assistant-chat` (y, a través de él, de `add-product-indexing`). Desbloquea el refresco del carrito en `integrate-ui-assistant`.
