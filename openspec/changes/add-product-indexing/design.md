# Design

## Context

Ver `proposal.md` (Why) para la motivación. Estado del que parte este change:

- `add-assistant-service` deja el `assistant` (Spring Boot 3.5, Spring AI 1.1.8, paquete `com.amazon.sample.assistant`) con un único `EmbeddingModel` (Google GenAI, `gemini-embedding-001`, 768 dimensiones, `task-type` por defecto `RETRIEVAL_DOCUMENT`), el `QdrantClient` gRPC autoconfigurado contra `qdrant:6334` con `initialize-schema=false`, la propiedad `retail.assistant.endpoints.catalog=http://catalog` y un health de Qdrant fuera de la readiness. Con la clave en `not-configured` el servicio arranca igual y las llamadas al proveedor fallan con 401/403.
- La API de `catalog` (Go/Gin) no cambia:
  - `GET /catalog/products?tags=&order=&page=&size=` devuelve un arreglo de `{id, name, description, price, tags: [{name, displayName}]}`. `page` empieza en 1, `size` por defecto es 10 y no tiene máximo, y sin `order` ordena por `products.name asc`, lo que da una paginación estable.
  - `GET /catalog/size` devuelve `{size}`.
  - El servicio tiene un middleware de chaos que puede devolver errores a propósito.
- `replace-catalog-with-home-furniture` deja ~80 productos con ids UUIDv5 estables, entre 2 y 4 tags cada uno, descripciones de 150 caracteres o más y precios enteros. Ese change dejó pendiente para este que se borren los points de productos que ya no existen.
- En el cluster todos los pods arrancan en paralelo, así que el `assistant` puede estar listo antes que `catalog`.
- Cuota de Gemini: 100 RPM y 1.000 requests por día (ver CLAUDE.md).

## Goals / Non-Goals

**Goals:**
- Colección `products` sincronizada con el catálogo después de cada arranque, gastando cero requests de embeddings cuando nada cambió.
- Un servicio de búsqueda (consulta + filtros) y uno de similares que `add-assistant-chat` y `add-assistant-tools` puedan inyectar directamente, más dos endpoints HTTP internos sobre ellos: uno para la ficha de la `ui` y otro para probar y demostrar la búsqueda sin depender del chat.
- Que la falta de claves, una caída de `catalog` o un 429 de Gemini queden acotados y visibles, sin afectar la readiness.

**Non-Goals:**
- Reescritura de consulta y RAG: es `add-assistant-chat`. Este change busca la consulta tal como llega.
- Búsqueda sin texto (solo tags, orden y precio): la resuelve la tool `searchProducts` de `add-assistant-tools` contra `GET /catalog/products`.
- Reindexado manual por endpoint o periódico. El catálogo es estático en runtime (datos embebidos en `catalog`), así que alcanza con reiniciar el `assistant` (`kubectl rollout restart deployment/assistant`).
- Umbral mínimo de `score` y reranking. La búsqueda siempre devuelve los k más cercanos que pasan los filtros; si el chat necesita cortar resultados poco relevantes, lo decide `add-assistant-chat`.
- Cambios en la `ui`: la sección de similares de la ficha es `integrate-ui-assistant`.

## Decisions

### D1. Repositorio propio sobre `QdrantClient` en lugar de `QdrantVectorStore`

El `VectorStore` de Spring AI no alcanza para tres cosas que necesitamos:

- `similaritySearch(String)` embebe la consulta con las opciones por defecto del `EmbeddingModel`, o sea en `RETRIEVAL_DOCUMENT`, y no deja pasar `RETRIEVAL_QUERY`.
- No tiene búsqueda por vector ni por id de punto, así que no permite calcular similares sin volver a embeber.
- No permite leer el payload de todos los puntos para comparar hashes, ni actualizar el payload sin reenviar el vector.

Por eso se escribe un `ProductVectorRepository` sobre el `QdrantClient` (gRPC) que ya autoconfigura el starter, y un `ProductEmbedder` que llama al `EmbeddingModel` con `GoogleGenAiTextEmbeddingOptions` explícitas por llamada (`RETRIEVAL_DOCUMENT` al indexar y `RETRIEVAL_QUERY` al buscar, `dimensions=768`). El bean `QdrantVectorStore` que crea el starter queda sin usar.

- **Alternativa descartada: `QdrantVectorStore` para escribir y `QdrantClient` solo para similares.** Mezcla dos formatos de payload (el `VectorStore` guarda el texto en `doc_content` y la metadata aparte) y deja igual el problema del `task-type` de las consultas.
- **Alternativa descartada: un segundo `EmbeddingModel` configurado con `RETRIEVAL_QUERY`.** Duplica la configuración del cliente y contradice la decisión de `add-assistant-service` de tener un único `EmbeddingModel`.

Para `add-assistant-chat`: si quiere usar los advisors de RAG de Spring AI, puede envolver `ProductSearchService` en un `DocumentRetriever` propio en vez de usar el `VectorStore`.

### D2. Esquema de la colección

- Nombre: el de `spring.ai.vectorstore.qdrant.collection-name`, con valor `products`.
- Vector sin nombre de 768 dimensiones, distancia `Cosine`. Las dimensiones salen de la misma propiedad que configura el `EmbeddingModel`, para que no puedan quedar desalineadas.
- Point id: el UUID del producto. Con eso el upsert es idempotente y "producto ↔ punto" es 1 a 1.
- Payload: `id`, `name`, `description`, `price` (entero), `tags` (lista de `name` de tags) y `contentHash`.
  - `description` **se agrega** a los atributos que listaba la pre-entrega (id, name, price, tags). Así el RAG de `add-assistant-chat` puede armar el contexto sin hacer un `GET` por producto. Es un agregado y no un desvío: los cuatro campos de la pre-entrega siguen estando.
  - Los `displayName` de los tags no se guardan, porque solo forman parte del texto embebido.
- Índices de payload: `tags` como `keyword` y `price` como `integer` con rango. Con 80 puntos no cambian la latencia, pero son la forma correcta de filtrar en Qdrant y no cuestan nada.
- Al arrancar: si la colección no existe, se crea con sus índices. Si existe con otra dimensión o distancia, se borra y se recrea (con un warning en el log), lo que fuerza un reindexado completo.

### D3. Texto embebido y hash de contenido

```
<name>
<description>
Tags: <displayName 1>, <displayName 2>, ...
```

Los tags van en el orden en que los devuelve el catálogo. Se usan los `displayName` ("Mid-Century", "Living Room") porque son lenguaje natural, lo que coincide con cómo escribe el usuario, y porque la pre-entrega habla de "extraer el nombre de los tags".

`contentHash = sha256(modelo + ":" + dimensiones + ":" + versión de plantilla + "\n" + texto)`. Al incluir el modelo, las dimensiones y una constante de versión de la plantilla, cambiar cualquiera de esos tres invalida todos los vectores sin tener que borrar la colección a mano. El precio no entra en el texto ni en el hash: cambiarlo no requiere volver a embeber.

### D4. Algoritmo de sincronización

1. Asegurar la colección (D2).
2. Leer el catálogo completo: `GET /catalog/products?page=N&size=50` desde `N=1` hasta que una página traiga menos de 50 productos. Se deduplica por `id` por las dudas. Si falla cualquier página, la lectura entera cuenta como fallida (ver D6).
3. Leer de Qdrant el payload de todos los puntos con un `scroll`, sin vectores.
4. Clasificar cada producto:
   - **nuevo o con texto cambiado** (no hay punto o el `contentHash` es distinto) → se embebe.
   - **solo cambió el payload** (mismo hash, pero `name`/`description`/`price`/`tags` distintos; en la práctica, el precio) → `setPayload` sin vector.
   - **sin cambios** → no se hace nada.
5. Embeber los nuevos o cambiados en lotes de hasta 100 textos (el máximo de `batchEmbedContents` de Gemini) y hacer upsert de los puntos con vector y payload. Con ~80 productos, el primer arranque es 1 request.
6. Borrar los puntos cuyo id no está en el catálogo leído. Solo se llega a este paso si el paso 2 terminó completo.
7. Registrar en el log y en el estado (D7) los contadores embebidos / payload actualizado / borrados / sin cambios, las requests al proveedor y la duración.

- **Alternativa descartada: recrear la colección en cada arranque.** Es lo más simple, pero gasta 1 o más requests de embeddings por reinicio (contra 1.000 por día) y deja la búsqueda caída mientras se reindexa.
- **Alternativa descartada: comparar contra `GET /catalog/size` para saber si hay que sincronizar.** No detecta los cambios de contenido.

### D5. Ejecución en segundo plano al arrancar

La sincronización arranca con `ApplicationReadyEvent` en un hilo propio (un `TaskExecutor` de un hilo) para no demorar el arranque ni la readiness, coherente con la D4 de `add-assistant-service`. Una sola sincronización a la vez: si llegara otro disparo mientras corre una, se ignora.

Mientras sincroniza, la búsqueda y los similares funcionan con lo que ya haya en la colección. Como Qdrant persiste en su PVC, después del primer arranque eso es el catálogo de la corrida anterior. Si la colección está vacía o no existe, responden `503 index-unavailable` (spec).

### D6. Reintentos y errores

| Falla | Comportamiento |
|---|---|
| `catalog` inaccesible, 5xx o timeout (incluye el chaos middleware) | Reintento sin límite con backoff exponencial de 2 s a 30 s. Es tráfico interno barato, y en el arranque del cluster `catalog` puede tardar. |
| Gemini 429 | Hasta 5 reintentos por lote, esperando el `retryDelay` que informa el error (o el backoff si no viene). Si se agotan, la sincronización queda `FAILED`. |
| Gemini 5xx o error de red | Igual que el 429. |
| Gemini 400/401/403 (clave `not-configured` o inválida) | Sin reintentos. Queda `FAILED` con el motivo `embedding-provider-unauthorized`. |
| Qdrant inaccesible | Reintento con el mismo backoff que `catalog`, hasta 10 intentos. Después, `FAILED`. |

Una sincronización `FAILED` no se reintenta sola: el arreglo es corregir la causa (clave, cuota) y reiniciar. Así se evita que un error de configuración consuma cuota en un bucle.

El tipo de excepción del SDK de Google GenAI (`com.google.genai.errors.ApiException` y subclases, con `code()`) se traduce en un `EmbeddingProviderException` propio con la causa (`QUOTA`, `UNAUTHORIZED`, `UNAVAILABLE`) y el `retryAfter` opcional, para no acoplar el resto del código al SDK. La búsqueda (D8) usa la misma traducción.

### D7. Estado observable: `HealthIndicator` `productIndex`

Un `ProductIndexState` en memoria guarda la fase (`NOT_STARTED`, `SYNCING`, `READY`, `FAILED`), la cantidad de puntos, la fecha de la última sincronización exitosa, los contadores de la última corrida y el último error. Se expone como componente `productIndex` de `/actuator/health`: `UP` en `READY`, `DOWN` en `FAILED` y `UNKNOWN` en las otras dos fases. Igual que `qdrant`, **no** entra en el grupo de readiness.

### D8. API HTTP y servicios

Dos servicios inyectables, que son los que van a usar los changes siguientes:

- `ProductSearchService.search(query, tags, minPrice, maxPrice, k)`: embebe la consulta en `RETRIEVAL_QUERY` y hace una búsqueda en Qdrant con el filtro `must` de `tags` *match any* (OR) y el `range` de `price`, que se combinan entre sí con AND.
- `SimilarProductsService.similar(id, k)`: hace una búsqueda en Qdrant con la Query API usando como consulta el **id del punto** (`nearest(PointId)`), con `must_not has_id(id)` para excluir al producto, sin traer vectores y sin llamar a Gemini. Si el punto no existe, devuelve `404`.

Y un `ProductSearchController`, con rutas bajo `/assistant/` siguiendo la convención de los otros servicios (`/catalog/...`, `/carts/...`):

- `GET /assistant/products/search?q=&tags=&minPrice=&maxPrice=&k=`
- `GET /assistant/products/{id}/similar?k=`

Las respuestas son un arreglo JSON (como `GET /catalog/products`) de `{id, name, description, price, tags, score}`, armado desde el payload. Los errores usan `ProblemDetail` (`spring.mvc.problemdetails.enabled=true`) con un `type` estable: `invalid-parameter`, `index-unavailable`, `embedding-quota-exceeded` (con `Retry-After`), `embedding-provider-unauthorized`, `embedding-provider-unavailable` y `product-not-found`.

Los datos vienen del payload y por lo tanto pueden tener el precio de la última sincronización. Para la ficha de la `ui` alcanza, porque el catálogo es estático en runtime. Las tools de `add-assistant-tools` siguen sacando el precio de `GET /catalog/products/{id}`, como exige la pre-entrega.

- **Alternativa descartada: no exponer la búsqueda por HTTP** y dejarla solo como servicio interno para el chat. La búsqueda semántica no se podría probar, ni comparar consulta cruda contra reescrita, sin pasar por el LLM, y esta capability no tendría una forma de verificarse por sí sola.

### D9. Caché de embeddings de consultas

Un LRU en memoria de 256 entradas, con la consulta normalizada (`trim`, minúsculas, espacios colapsados) como clave y el vector como valor, delante de `ProductEmbedder.embedQuery`. En la demo y en los tests se repiten muchas veces las mismas frases, y cada repetición sin caché gasta una request del límite de 1.000 por día. Se implementa con un `LinkedHashMap` en modo de acceso dentro de un `synchronized`, sin sumar una dependencia de caché. Se pierde al reiniciar, lo cual no es un problema.

### D10. Desvío respecto de la pre-entrega: modelo de embeddings

La pre-entrega proponía `nomic-embed-text` en Ollama dentro del cluster. Se usa `gemini-embedding-001` vía la API de Google (tier gratuito), con la autorización de la cátedra para usar modelos en la nube:

- **Justificación:** `add-assistant-service` (D8) elimina Ollama del cluster. Mantenerlo solo para los embeddings implicaría desplegar un pod con su PVC de modelos únicamente para indexar 80 productos y embeber consultas. Además, `gemini-embedding-001` distingue el `task-type` entre documento y consulta, lo que mejora el retrieval asimétrico (consultas cortas contra descripciones largas) que necesitan los casos de búsqueda semántica y de tolerancia a errores.
- **Lo que se mantiene de la pre-entrega:** Qdrant, 768 dimensiones (Gemini usa Matryoshka y permite truncar su salida de 3072 a 768), distancia coseno, payload con id, name, price y tags (más `description`, ver D2), e indexación al arrancar a partir del catálogo real con el texto nombre + descripción + tags.
- **Tráfico saliente:** las llamadas van a `generativelanguage.googleapis.com` por HTTPS 443, que ya está documentado en `docs/arquitectura.md` por `add-assistant-service`. Este change agrega a ese documento el consumo de cuota (1 request por consulta, 0 por reinicio sin cambios, 0 por similares).

## Risks / Trade-offs

- **[Las opciones por llamada no pisan el `task-type` del `EmbeddingModel` de Spring AI 1.1.8]** → La primera task lo verifica con un test que inspecciona el request (o con el smoke contra Gemini, comparando vectores de consulta y documento del mismo texto, que tienen que ser distintos). Si no funciona, se llama directo al cliente `com.google.genai.Client` que expone el starter, solo para las consultas.
- **[`batchEmbedContents` podría contar como N requests para la cuota de Gemini y no como 1]** → Aun así, el primer arranque son ~80 contra 100 RPM y 1.000 por día, y los reinicios siguientes cuestan 0 por D4. Si aparece un 429 en el primer arranque, D6 reintenta con el `retryDelay`.
- **[Query por id de punto con el cliente Java 1.13 de Qdrant]** → La Query API existe desde la 1.10 del servidor y el cliente 1.13 tiene `QueryFactory.nearest(PointId)`. Si no se comporta como se espera, el plan B es `retrieve` del punto con su vector y después `search` con ese vector: sigue sin llamar a Gemini.
- **[Las frases de la demo podrían no cumplir los criterios de la spec con la consulta cruda]** (typo, sinónimo, "cozy to curl up with a book", similares coherentes) → Se miden con el smoke test (task 6). Si alguna falla, primero se ajusta la plantilla de texto (D3, que sube la versión y reindexa) y, si no alcanza, la curación del catálogo, como prevé el Open Question de `replace-catalog-with-home-furniture`. La reescritura de `add-assistant-chat` mejora además los casos del chat.
- **[Payload desactualizado si cambia el catálogo sin reiniciar el `assistant`]** → Hoy no pasa (catálogo embebido). Las tools usan precio vivo y el procedimiento documentado ante un cambio de datos es reiniciar.
- **[Con 2 réplicas de `assistant`, ambas sincronizan a la vez]** → Es idempotente (upsert por id) y como mucho duplica las requests del primer arranque. El manifiesto tiene 1 réplica, así que no se agrega un lock distribuido.
- **[Una sincronización `FAILED` no se recupera sola]** → Es a propósito (D6). El componente `productIndex` del health y el log lo dejan visible, y `./local.sh update-secrets` ya reinicia el `assistant` después de corregir la clave.

## Migration Plan

1. Desplegar con `./local.sh reload-images` (o `rebuild-cluster`). En el primer arranque se crea la colección `products` y se indexa el catálogo (1 a 3 requests a Gemini).
2. Verificar `productIndex: UP` en `kubectl exec deploy/ui -- curl -s http://assistant/actuator/health`.
3. Rollback: revertir el commit y reiniciar el `assistant`. La colección queda en el PVC de Qdrant sin que nadie la use. Para limpiarla: `curl -X DELETE localhost:6333/collections/products` con un `port-forward` a `svc/qdrant`.

## Notas de implementación

### Desvío: los embeddings no pasan por el `EmbeddingModel` de Spring AI (spike de la task 2.2)

- **Hallazgo:** en Spring AI 1.1.8, `GoogleGenAiTextEmbeddingModel.call()` nunca envía el `taskType` a Gemini: arma el `EmbedContentConfig` solo con el modelo y `outputDimensionality`, y deja un comentario en su lugar (`spring-ai-google-genai-embedding-1.1.8-sources.jar`, `GoogleGenAiTextEmbeddingModel.java`, línea 149: *"Set task type if specified - this might need to be handled differently"*). O sea que ni `RETRIEVAL_DOCUMENT` ni `RETRIEVAL_QUERY` se aplicaban: el primer riesgo de este design se materializó para los documentos **y** para las consultas, no solo para las consultas como preveía el plan B.
- **Además:** el `Client` de `google-genai` 1.37 trae un `RetryInterceptor` por defecto (5 intentos ante 408, 429 y 5xx), que multiplicaría las requests por debajo de los reintentos de D6.
- **Decisión (consultada con el coordinador, opción A):** `ProductEmbedder` llama directo al `com.google.genai.Client` que expone el bean autoconfigurado `GoogleGenAiEmbeddingConnectionDetails` (misma clave y misma conexión), detrás de una interfaz `EmbeddingGateway`, para documentos y consultas. El `EmbedContentConfig` lleva el `taskType`, las dimensiones (de `spring.ai.google.genai.embedding.text.options.*`, las mismas que configuran el `EmbeddingModel`) y `httpOptions` con `retryOptions.attempts=1`, para que cada llamada sea una sola request y los reintentos los decida D6. El starter `spring-ai-starter-model-google-genai-embedding`, su autoconfiguración y el bean `EmbeddingModel` quedan intactos (sigue siendo el único `EmbeddingModel`, como pide `add-assistant-service`): el desvío es solo que la indexación y la búsqueda no pasan por él. Los tests que el `tasks.md` describe con el `EmbeddingModel` mockeado mockean `EmbeddingGateway`.
- **Verificación:** `EmbeddingTaskTypeSmokeIT` embebe el mismo texto como documento y como consulta: ambos vectores tienen 768 dimensiones y su coseno es 0,880 (distintos). Con la clave `not-configured`, Gemini responde 400 y la causa es `UNAUTHORIZED`.

### Plantilla de texto v2 (task 7.1)

`SearchQualitySmokeIT` midió los criterios de `semantic-product-search` con el catálogo real, Gemini y la consulta sin reescribir. Con la plantilla de D3 (v1, tags al final) pasaban los tres criterios de búsqueda y el de similares fallaba en 3 de 80 productos, todos `decor`. Se probó una sola vez la v2, con los tags antes de la descripción:

```
<name>
Tags: <displayName 1>, <displayName 2>, ...
<description>
```

La v2 deja pasar 78 de 80 (se arregla "Velvet Texture Throw Pillow") sin empeorar los otros criterios ni los productos que ya pasaban, así que **se usa la v2** (`TEMPLATE_VERSION = "2"`; el hash incluye la versión, así que una colección indexada con la v1 se reembebe sola). Los dos productos que siguen fallando, "Two-Toned Stoneware Planter" y "Abstract Topographic Print", quedan reportados a la curación del catálogo: `decor` tiene solo 5 productos y es heterogéneo (2 almohadones, 2 macetas y 1 cuadro), así que ninguno de los dos tiene 2 "hermanos" de tipo. El smoke los lista como excepciones conocidas y falla si aparece cualquier otro producto. El detalle está en el README del servicio.

### Decisiones menores

- **`retryDelay` de Gemini:** el SDK conserva solo `error.message` del cuerpo del 429 y descarta el detalle `RetryInfo`. La espera se toma del texto del mensaje ("Please retry in 41.27s.") o de un `"retryDelay": "41s"` si viniera; si no hay ninguno, se usa el backoff de D6. En la búsqueda, el `Retry-After` por defecto es 60 s (la ventana de RPM).
- **Otros 4xx de Gemini** (distintos de 400, 401, 403 y 429) se tratan como `UNAVAILABLE`.
- **Lectura del catálogo:** ante un error reintentable (conexión, timeout, 5xx) se vuelve a leer desde la página 1, así que un error en cualquier página nunca deja un resultado parcial; un 4xx o una respuesta ilegible terminan la sincronización como `FAILED` (`catalog-unavailable`). La paginación también corta si una página llena no suma ningún id nuevo, para no paginar sin fin. Los timeouts del `RestClient` se configuran con `spring.http.client.connect-timeout=2s` y `read-timeout=10s`.
- **Qdrant:** el reintento de D6 (hasta 10 intentos con el backoff de 2 s a 30 s) se aplica a cada operación de la sincronización, no solo al primer acceso. En la búsqueda y los similares, un Qdrant caído responde `503 index-unavailable`.
- **Similares:** `nearest(PointId)` del cliente 1.13 funciona contra el servidor 1.19.2, así que no hizo falta el plan B. Antes de la consulta se hace un `retrieve` sin payload ni vector para distinguir el `404` de otros errores. Un id que no es un UUID también responde `404`.
- **Propiedad `retail.assistant.indexing.sync-on-startup`** (por defecto `true`): los tests la apagan en `src/test/resources/application.properties` para que los tests de contexto no intenten sincronizar contra `localhost`; los que prueban el arranque la prenden.
- **Ejecutor de un hilo** creado dentro de la configuración y no como bean `TaskExecutor`, para no reemplazar el executor que autoconfigura Spring Boot. Al apagar el servicio se interrumpe la sincronización en curso.
- **Health:** además de `phase`, `points`, `lastSync` y `error`, el componente `productIndex` muestra los contadores de la última corrida exitosa en `lastRun`.
- **Validación de las propiedades:** se agrega `spring-boot-starter-validation` para validar los `@ConfigurationProperties`.
- **Tests de integración:** los que usan Qdrant (`ProductVectorRepositoryTest`, `ProductIndexerTest`, `ProductSearchEndToEndTest`) corren en `./mvnw test` y necesitan Docker; levantan `qdrant/qdrant:v1.19.2` con Testcontainers. El catálogo de prueba sale de `src/catalog/repository`, con los tags ordenados por nombre como los devuelve `GET /catalog/products`.

### Verificación en el cluster (tasks 6.4 y 7.2 a 7.4)

- **6.4:** con Qdrant en Docker, `catalog` con `go run` en el puerto 8081 y `./mvnw spring-boot:run`, los `curl` del README funcionan tal como están escritos (la primera sincronización tardó 3 s, con 1 request a Gemini).
- **7.2:** no había cluster, así que se usó `./local.sh create-cluster --skip-tests`, que construye y carga las imágenes igual que `reload-images` y además despliega. Desde la `ui`: `productIndex` queda `UP` con `points: 80` igual a `GET http://catalog/catalog/size`; la búsqueda (`q=lamp&maxPrice=100&k=3`) y los similares responden; el log muestra la primera sincronización con 1 request al proveedor.
- **7.3:** después de `kubectl rollout restart deployment/assistant`, la segunda sincronización hace 0 requests y deja 80 puntos. Con el `assistant` sin `GOOGLE_API_KEY` (se corrió `update-secrets` desde una copia de `local.sh` en un directorio sin `.env`, para no tocar el `.env` del repo), la sincronización termina `READY` sin llamar a Gemini (los hashes no cambian), los similares responden `200`, la búsqueda responde `503 embedding-provider-unauthorized` y el pod sigue Ready. Después se restauró la clave con `./local.sh update-secrets` y la búsqueda volvió a responder `200`.
- **7.4:** con `./local.sh rebuild-cluster --skip-tests`, el `assistant` arrancó antes que Qdrant: 3 reintentos (2, 4 y 8 s) y la sincronización terminó en `READY` con 80 puntos sin intervención. En esa corrida `catalog` ya estaba listo, así que se forzó el caso: con `catalog` escalado a 0 y el `assistant` reiniciado, la readiness respondió `200` con `productIndex` en `SYNCING`, hubo 4 reintentos contra `catalog` (2 a 16 s) y, al volver a escalarlo a 1, la sincronización terminó en `READY` (0 requests, 80 puntos). Al terminar se borró el cluster.

### Consumo de cuota durante el apply

- **Gemini:** 27 requests aceptadas y 3 rechazadas por la clave placeholder (que no consumen cuota): 4 de dos corridas de `EmbeddingTaskTypeSmokeIT`, 15 de tres corridas de `SearchQualitySmokeIT` (v1, v2 y la final), 1 de `ProvidersSmokeIT`, 3 de la verificación local y 4 del cluster.
- **NVIDIA:** 2 requests (`ProvidersSmokeIT`).
