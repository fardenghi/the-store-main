# The Store - Assistant Service

| Language | Persistence       |
| -------- | ----------------- |
| Java     | Qdrant (vectores) |

Servicio que concentra la lógica GenAI de la tienda: es el único que habla con
el LLM (NVIDIA, API compatible con OpenAI), con el modelo de embeddings
(`gemini-embedding-001` de Google) y con el vector store (Qdrant). Al arrancar
indexa el catálogo en Qdrant y expone la búsqueda semántica de productos y los
productos similares; el chat y las tools llegan en los changes siguientes.

## Configuración

Los valores no secretos tienen default en `application.yml` y se sobreescriben
con variables de entorno (en el cluster, desde el ConfigMap `assistant`).

| Name                                                  | Description                                          | Default                                 |
| ----------------------------------------------------- | ---------------------------------------------------- | --------------------------------------- |
| `PORT`                                                | Puerto HTTP                                          | `8080`                                  |
| `NVIDIA_API_KEY`                                      | Clave de NVIDIA (chat). Viene del Secret             | `not-configured`                        |
| `GOOGLE_API_KEY`                                      | Clave de la Gemini API (embeddings). Viene del Secret | `not-configured`                        |
| `SPRING_AI_OPENAI_BASE_URL`                           | URL base del proveedor de chat                       | `https://integrate.api.nvidia.com`      |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`                 | Modelo principal (razonamiento + tools)              | `nvidia/nemotron-3-super-120b-a12b`     |
| `SPRING_AI_OPENAI_CHAT_OPTIONS_MAX_TOKENS`            | Límite de tokens de salida (NVIDIA lo exige)         | `1024`                                  |
| `RETAIL_ASSISTANT_MODELS_REWRITE`                     | Modelo de reescritura de consultas                   | `nvidia/nemotron-3.5-lightning-30b-a3b` |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_TEXT_OPTIONS_MODEL` | Modelo de embeddings                                 | `gemini-embedding-001`                  |
| `SPRING_AI_GOOGLE_GENAI_EMBEDDING_TEXT_OPTIONS_DIMENSIONS` | Dimensiones de los embeddings                   | `768`                                   |
| `SPRING_AI_VECTORSTORE_QDRANT_HOST`                   | Host de Qdrant (gRPC)                                | `localhost`                             |
| `SPRING_AI_VECTORSTORE_QDRANT_PORT`                   | Puerto gRPC de Qdrant                                | `6334`                                  |
| `RETAIL_ASSISTANT_ENDPOINTS_CATALOG`                  | Endpoint del servicio `catalog`                      | `http://localhost:8081`                 |
| `RETAIL_ASSISTANT_ENDPOINTS_CARTS`                    | Endpoint del servicio `carts`                        | `http://localhost:8082`                 |
| `SPRING_AI_VECTORSTORE_QDRANT_COLLECTION_NAME`        | Colección de Qdrant con los productos                | `products`                              |
| `RETAIL_ASSISTANT_INDEXING_SYNC_ON_STARTUP`           | Sincronizar el catálogo al arrancar                  | `true`                                  |
| `RETAIL_ASSISTANT_INDEXING_PAGE_SIZE`                 | Productos por página al leer el catálogo             | `50`                                    |
| `RETAIL_ASSISTANT_INDEXING_BATCH_SIZE`                | Textos por request de embeddings (máximo 100)        | `100`                                   |
| `RETAIL_ASSISTANT_INDEXING_MAX_PROVIDER_RETRIES`      | Reintentos por lote ante 429 o 5xx de Gemini         | `5`                                     |
| `RETAIL_ASSISTANT_SEARCH_DEFAULT_K` / `_MAX_K`        | Resultados de la búsqueda (default / máximo)         | `5` / `20`                              |
| `RETAIL_ASSISTANT_SEARCH_SIMILAR_DEFAULT_K` / `_SIMILAR_MAX_K` | Similares (default / máximo)                | `4` / `12`                              |
| `RETAIL_ASSISTANT_SEARCH_QUERY_CACHE_SIZE`            | Entradas del caché de embeddings de consultas        | `256`                                   |

Sin claves el servicio arranca igual y queda listo: al iniciar loguea, sin
mostrar valores, si cada clave está configurada o es el placeholder. Las
llamadas al proveedor con el placeholder fallan con 401/403.

Para pasar al plan B alcanza con cambiar los modelos en el ConfigMap y
reiniciar el pod: `deepseek-ai/deepseek-v4.1-flash` (principal) y
`google/gemma-3-12b-it` (reescritura).

## Indexación del catálogo

Al quedar listo, el servicio sincroniza en segundo plano la colección
`products` de Qdrant (768 dimensiones, coseno) con el catálogo:

1. Crea la colección si no existe, o la recrea si tiene otra dimensión o
   distancia.
2. Lee el catálogo completo paginando `GET /catalog/products`. Si `catalog` no
   responde (o devuelve 5xx), reintenta sin límite con backoff de 2 s a 30 s.
3. Embebe con `RETRIEVAL_DOCUMENT`, en lotes de hasta 100, solo los productos
   nuevos o cuyo texto cambió (compara un hash del texto, el modelo, las
   dimensiones y la versión de la plantilla). Si solo cambió el precio,
   actualiza el payload sin volver a embeber.
4. Borra los puntos de productos que ya no están en el catálogo (solo si la
   lectura del catálogo fue completa).

El texto embebido es el nombre, los `displayName` de los tags y la descripción
(plantilla versión 2). El primer arranque gasta 1 request a Gemini; los
reinicios sin cambios, 0. Ante un 429 o un 5xx de Gemini reintenta hasta 5
veces por lote; con la clave inválida o sin configurar no reintenta y la
sincronización queda fallida (no se reintenta sola: corregir la causa y
reiniciar).

Al terminar loguea los contadores, por ejemplo:

```
Sincronización del catálogo terminada en 3137 ms: 80 productos en el catálogo, 80 embebidos, 0 con payload actualizado, 0 borrados, 0 sin cambios, 1 requests al proveedor de embeddings, 80 puntos en la colección
```

El catálogo es estático en runtime, así que no hay reindexado periódico ni por
endpoint. Para forzar un reindexado (por ejemplo, después de cambiar los datos
de `catalog`), alcanza con reiniciar el servicio:

```bash
kubectl rollout restart deployment/assistant -n the-store
```

Para reindexar todo desde cero, borrar la colección antes de reiniciar:
`kubectl port-forward -n the-store svc/qdrant 6333:6333` y
`curl -X DELETE localhost:6333/collections/products`.

## API

Los endpoints son internos: el ingress solo enruta hacia la `ui`. Los errores
siguen el formato `ProblemDetail` (RFC 9457, `application/problem+json`) con un
`type` estable.

### `GET /assistant/products/search`

Búsqueda semántica: embebe la consulta con `RETRIEVAL_QUERY` y busca en Qdrant.

| Parámetro  | Descripción                                                              |
| ---------- | ------------------------------------------------------------------------ |
| `q`        | Texto de la consulta. Obligatorio                                        |
| `tags`     | Nombres de tags separados por comas. Semántica OR, como en `catalog`     |
| `minPrice` | Precio mínimo, entero no negativo, inclusivo                             |
| `maxPrice` | Precio máximo, entero no negativo, inclusivo                             |
| `k`        | Cantidad máxima de resultados, entre 1 y 20. Por defecto 5               |

Las consultas iguales salvo mayúsculas y espacios salen de un caché en memoria
(256 entradas) y no gastan cuota.

```bash
curl -s 'localhost:8080/assistant/products/search?q=mid+century+velvet+armchair&k=3'
curl -s 'localhost:8080/assistant/products/search?q=something+to+sit+on&tags=velvet,leather&maxPrice=1000&k=20'
```

```json
[
  {
    "id": "…",
    "name": "Aiden Mid-Century Velvet Armchair",
    "description": "…",
    "price": 139,
    "tags": ["living-room", "mid-century", "seating", "velvet"],
    "score": 0.728117
  }
]
```

### `GET /assistant/products/{id}/similar`

Los `k` productos más parecidos (por defecto 4, entre 1 y 12), sin incluir al
propio producto. Usa el vector ya guardado en Qdrant: no llama a Gemini, así
que funciona aunque no haya clave o se haya agotado la cuota.

```bash
curl -s 'localhost:8080/assistant/products/3600929b-2826-5a98-908f-82a1d50bcf2b/similar?k=4'
```

La respuesta tiene el mismo formato que la búsqueda. Los datos salen del
payload de Qdrant, así que el precio es el de la última sincronización.

### Errores

| Status | `type`                            | Cuándo                                                          |
| ------ | --------------------------------- | --------------------------------------------------------------- |
| `400`  | `invalid-parameter`               | `q` vacía, `k` fuera de rango, precio no entero o negativo, `minPrice > maxPrice`. Indica el parámetro en `parameter` |
| `404`  | `product-not-found`               | El id de los similares no está indexado                         |
| `503`  | `index-unavailable`               | La colección no existe o está vacía (primera sincronización en curso o fallida), o Qdrant no responde |
| `503`  | `embedding-quota-exceeded`        | Gemini respondió 429. Trae el header `Retry-After` (segundos)   |
| `503`  | `embedding-provider-unauthorized` | Clave de Gemini sin configurar o inválida                       |
| `503`  | `embedding-provider-unavailable`  | Gemini respondió 5xx o no hubo red                              |

```bash
curl -s 'localhost:8080/assistant/products/search?q=table&minPrice=500&maxPrice=100'
```

```json
{
  "type": "invalid-parameter",
  "title": "Parámetro inválido",
  "status": 400,
  "detail": "minPrice no puede superar a maxPrice",
  "instance": "/assistant/products/search",
  "parameter": "minPrice"
}
```

## Health

- `/actuator/health/liveness` y `/actuator/health/readiness`: solo dependen del
  proceso. Una caída de Qdrant o de los proveedores no saca al pod del Service.
- `/actuator/health`: incluye el componente `qdrant` (`UP` con la versión del
  servidor o `DOWN` con el error). Los proveedores en la nube no tienen health
  check para no gastar cuota.
- `/actuator/health` también incluye `productIndex`, el estado de la
  indexación: `UP` cuando terminó (con `points`, `lastSync` y los contadores
  de la última corrida en `lastRun`), `DOWN` si falló (con el motivo en
  `error`, por ejemplo `embedding-provider-unauthorized: …`) y `UNKNOWN`
  mientras no empezó o está sincronizando (`phase`). Igual que `qdrant`, no
  forma parte de la readiness.

```bash
curl -s localhost:8080/actuator/health | jq .components.productIndex
```

## Running

Pre-requisitos: Java 21 y Docker.

```bash
# Qdrant local
docker run -d --name qdrant -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.19.2-unprivileged

# catalog local en el puerto 8081 (en otra terminal), para la indexación
(cd ../catalog && PORT=8081 go run main.go)

# Claves opcionales (desde el .env de la raíz del repo)
set -a; . ../../.env; set +a

./mvnw spring-boot:run
curl -s localhost:8080/actuator/health
```

Imagen del contenedor:

```bash
docker build -t the-store-assistant:latest .
```

## Tests

```bash
./mvnw test              # unitarios, de contexto y de integración, sin claves
./mvnw -Psmoke verify    # smoke contra los proveedores reales (ver abajo)
```

Los tests de integración con Qdrant (`ProductVectorRepositoryTest`,
`ProductIndexerTest`, `ProductSearchEndToEndTest`) levantan
`qdrant/qdrant:v1.19.2` con Testcontainers, así que necesitan Docker. El
catálogo de prueba sale de `../catalog/repository`.

Los smoke tests necesitan las claves en el entorno; sin ellas se saltean. No
reintentan ante un 429:

- `ProvidersSmokeIT`: una llamada de chat a cada modelo y un embedding
  (2 requests a NVIDIA y 1 a Gemini).
- `EmbeddingTaskTypeSmokeIT`: el mismo texto embebido como documento y como
  consulta da vectores distintos, y la clave placeholder da `UNAUTHORIZED`
  (2 requests a Gemini y 1 rechazada).
- `SearchQualitySmokeIT`: indexa el catálogo real en un Qdrant de
  Testcontainers y mide los criterios de calidad de la búsqueda (5 requests a
  Gemini: 1 para indexar y 4 consultas).

Para correr uno solo:
`./mvnw -Psmoke verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=SearchQualitySmokeIT`.

### Calidad de la búsqueda

Resultado de `SearchQualitySmokeIT` (2026-10-06, catálogo de 80 productos,
`gemini-embedding-001` 768d, consulta sin reescribir):

| Criterio de la spec `semantic-product-search`                       | Plantilla v1 (tags al final) | Plantilla v2 (tags antes de la descripción) |
| ------------------------------------------------------------------- | ---------------------------- | ------------------------------------------- |
| "somewhere cozy to curl up with a book" devuelve 3 o más productos  | OK (chaise lounge, sillón, lounge chair, biblioteca…) | OK |
| Top-1 de "mid century velvet armchair" en el top-3 con typos         | OK (top-1 en ambas)          | OK (top-1 en ambas)                         |
| "couch": un `seating` sin "couch" en el nombre en el top-3           | OK (sofás de cuero)          | OK                                          |
| 4 similares comparten el tag de tipo en al menos 2 casos             | 77 de 80                     | **78 de 80**                                |

Se usa la plantilla v2. Los dos productos que no cumplen el criterio de
similares son de `decor`, y quedan reportados a la curación del catálogo
(`replace-catalog-with-home-furniture`): `decor` tiene solo 5 productos y es
heterogéneo (2 almohadones, 2 macetas y 1 cuadro), así que ni
"Two-Toned Stoneware Planter" (tiene una sola maceta hermana) ni
"Abstract Topographic Print" (no tiene ningún producto parecido) pueden tener 2
similares del mismo tipo. El smoke los lista como excepciones conocidas; si
falla cualquier otro producto, falla.

La request de embeddings con los 80 textos tardó entre 3 s y 1 minuto según
la corrida; como la sincronización corre en segundo plano, no demora el
arranque.

### Modelos probados

| Fecha      | Uso         | Modelo                                  | Resultado                          |
| ---------- | ----------- | --------------------------------------- | ---------------------------------- |
| 2026-10-06 | Principal   | `nvidia/nemotron-3-super-120b-a12b`     | OK, responde con thinking apagado  |
| 2026-10-06 | Reescritura | `nvidia/nemotron-3.5-lightning-30b-a3b` | OK, responde con thinking apagado  |
| 2026-10-06 | Embeddings  | `gemini-embedding-001`                  | OK, vector de 768 dimensiones      |
