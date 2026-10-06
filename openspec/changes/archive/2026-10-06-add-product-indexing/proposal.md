# Proposal

## Why

La búsqueda actual de `GET /catalog/products` es solo léxica: filtra por tags exactos y no tolera sinónimos ni errores de tipeo. Para que el asistente encuentre productos por significado, y para mostrar productos similares en la ficha, el catálogo tiene que estar indexado como vectores en Qdrant.

## What Changes

- Embeddings con `gemini-embedding-001` de Google (tier gratuito) a través del starter nativo de Spring AI `spring-ai-starter-model-google-genai-embedding`. Se usa `task-type` `RETRIEVAL_DOCUMENT` al indexar y `RETRIEVAL_QUERY` al buscar, y `dimensions=768`. No hace falta un `EmbeddingModel` propio.
- Colección de Qdrant de **768 dimensiones**, distancia coseno, con payload `id`, `name`, `price`, `tags` y un hash del contenido.
- Indexación al arrancar `assistant`: lee el catálogo real paginando `GET /catalog/products`, arma el texto nombre + descripción + tags, y embebe en lotes solo los productos nuevos o modificados (comparando el hash). Es idempotente: el point id es el UUID del producto.
- Búsqueda semántica con filtros de payload: tags con semántica OR (`match any`, igual que el `IN` del catálogo) y rango de precio.
- Endpoint de productos similares: devuelve los k vecinos de un producto usando el vector ya guardado en Qdrant, sin llamar al proveedor de embeddings.
- **Desvío respecto de la pre-entrega:** el modelo de embeddings pasa de `nomic-embed-text` (local) a `gemini-embedding-001` (Google, en la nube), con la autorización de la cátedra para usar modelos en la nube. Se mantienen las 768 dimensiones y la distancia coseno del doc.

## Capabilities

### New Capabilities
- `product-indexing`: indexación del catálogo en Qdrant al iniciar el servicio, incremental e idempotente.
- `semantic-product-search`: búsqueda de productos por significado con filtros de tags y precio, y obtención de productos similares a uno dado.

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- `src/assistant`: configuración del starter de Google GenAI, indexador, repositorio de Qdrant y endpoint de similares.
- Hay que verificar que el starter acepte el modelo `gemini-embedding-001`, ya que la documentación de Spring AI lista `text-embedding-004` como ejemplo.
- Cuota de Gemini, separada de la de NVIDIA: 100 RPM, 30k TPM y 1.000 requests por día. La indexación consume 1–3 requests en el primer arranque y 0 en reinicios sin cambios; cada consulta consume 1. Los similares no consumen cuota.
- Casos de uso de la pre-entrega que cubre: búsqueda semántica, productos similares y tolerancia a errores de tipeo y sinónimos.
- Depende de `add-assistant-service`. Desbloquea `add-assistant-chat` y la parte de similares de `integrate-ui-assistant`.
