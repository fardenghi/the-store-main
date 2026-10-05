# Proposal

## Why

La búsqueda actual de `GET /catalog/products` es solo léxica: filtra por tags exactos y no tolera sinónimos ni errores de tipeo. Para que el asistente encuentre productos por significado, y para mostrar productos similares en la ficha, el catálogo tiene que estar indexado como vectores en Qdrant.

## What Changes

- Implementación propia de `EmbeddingModel` de Spring AI contra `POST https://integrate.api.nvidia.com/v1/embeddings` con el modelo `nvidia/nemotron-3-embed-1b`. Es necesaria porque NVIDIA exige el parámetro `input_type` (`passage` al indexar, `query` al buscar) y el cliente OpenAI de Spring AI no lo envía.
- Colección de Qdrant de **2048 dimensiones** (la dimensión nativa del modelo), distancia coseno, con payload `id`, `name`, `price`, `tags` y un hash del contenido.
- Indexación al arrancar `assistant`: lee el catálogo real paginando `GET /catalog/products`, arma el texto nombre + descripción + tags, y embebe en lotes solo los productos nuevos o modificados (comparando el hash). Es idempotente: el point id es el UUID del producto.
- Búsqueda semántica con filtros de payload: tags con semántica OR (`match any`, igual que el `IN` del catálogo) y rango de precio.
- Endpoint de productos similares: devuelve los k vecinos de un producto usando el vector ya guardado en Qdrant, sin llamar a NVIDIA.
- **Desvío respecto de la pre-entrega:** el modelo de embeddings pasa de `nomic-embed-text` (768d, local) a `nemotron-3-embed-1b` (2048d, NVIDIA). El cambio de dimensión es consecuencia directa del cambio de modelo autorizado.

## Capabilities

### New Capabilities
- `product-indexing`: indexación del catálogo en Qdrant al iniciar el servicio, incremental e idempotente.
- `semantic-product-search`: búsqueda de productos por significado con filtros de tags y precio, y obtención de productos similares a uno dado.

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- `src/assistant`: cliente de embeddings, indexador, repositorio de Qdrant y endpoint de similares.
- Consumo de cuota de NVIDIA: ~3 requests en el primer arranque con 80 productos y 0 en reinicios sin cambios. Los similares no consumen cuota.
- Casos de uso de la pre-entrega que cubre: búsqueda semántica, productos similares y tolerancia a errores de tipeo y sinónimos.
- Depende de `add-assistant-service`. Desbloquea `add-assistant-chat` y la parte de similares de `integrate-ui-assistant`.
