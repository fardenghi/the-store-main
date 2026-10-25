# How-to: asistente de compras con GenAI en The Store

**Redes de Información — ITBA — 2C 2026 — Grupo 3, Tema 9**

Este documento explica cómo está hecha la implementación y cómo reproducirla
desde cero: qué se agregó a The Store, cómo desplegarlo en un cluster kind,
cómo comprobar cada caso de uso y cómo operarlo.

| Para ver… | Ir a |
|---|---|
| La API, la configuración y los detalles del `assistant` | [`src/assistant/README.md`](src/assistant/README.md) |
| La integración del chat en la `ui` | [`src/ui/README.md`](src/ui/README.md) |

## 1. Qué se implementó

The Store pasa de tener un chat con un system prompt ficticio a tener un
asistente de compras que conoce el catálogo, busca por significado, recuerda
la conversación, compara productos y agrega al carrito.

| Componente | Qué es | Cambio |
|---|---|---|
| `assistant` | Microservicio nuevo en Java 21, Spring Boot 3.5 y Spring AI 1.1.8. Concentra toda la lógica GenAI | Nuevo |
| `qdrant` | Vector store con la colección `products` (768 dimensiones, coseno) | Nuevo |
| `ui` | Nuevo provider de chat `assistant`, sección de productos similares en la ficha y contador del carrito que se actualiza desde el chat | Modificado |
| `catalog` | Catálogo de ~80 productos de hogar y muebles (dataset Amazon Berkeley Objects) en lugar del catálogo original | Datos nuevos |
| `carts`, `orders`, `checkout` | — | Sin cambios |
| `dist/kubernetes.yaml` | Recursos del `assistant` y de `qdrant`, y variables del chat en el ConfigMap `ui` | Solo agregados |
| `local.sh` | Crea el Secret con las claves y suma el comando `update-secrets` | Modificado |

Modelos (en la nube, autorizados por la cátedra en lugar de Ollama):

| Rol | Proveedor | Modelo |
|---|---|---|
| Respuesta, razonamiento y tools | NVIDIA (`integrate.api.nvidia.com`, API compatible con OpenAI) | `meta/muse-glimmer-30b` |
| Reescritura de la consulta | NVIDIA | `meta/muse-glimmer-30b` |
| Embeddings | Google Gemini API | `gemini-embedding-001`, 768 dimensiones |
| Plan B verificado | NVIDIA | `nvidia/nemotron-3-super-120b-a12b` (principal) y `nvidia/nemotron-3.5-lightning-30b-a3b` (reescritura) |

## 2. Requisitos

| Herramienta | Para qué | Versión probada |
|---|---|---|
| [Docker](https://docs.docker.com/get-docker/) (corriendo) | Construir las imágenes y alojar el nodo kind | Docker 29.8 (Ubuntu) y Docker Desktop 28 (macOS) |
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | Cluster Kubernetes local | v0.33.0 (Kubernetes v1.37) |
| [kubectl](https://kubernetes.io/docs/tasks/tools/) | Manejar el cluster | — |
| Conexión a internet | El `assistant` llama a NVIDIA y a Gemini por HTTPS | — |
| Java 21 | Solo para correr el `assistant` o sus tests fuera del cluster | Corretto 21 |

Los puertos 80 y 443 del host tienen que estar libres: kind los publica para
el ingress.

## 3. Conseguir las claves

| Variable | Dónde se saca | Cuota del tier gratuito |
|---|---|---|
| `NVIDIA_API_KEY` | [build.nvidia.com](https://build.nvidia.com/) → iniciar sesión → *Get API Key* | 40 requests por minuto |
| `GOOGLE_API_KEY` | [Google AI Studio](https://aistudio.google.com/apikey) → *Create API key* | 100 por minuto y 1.000 por día |

Las claves **nunca se versionan**. Se guardan en un `.env` en la raíz del repo
(está en el `.gitignore`):

```bash
cat > .env <<'ENV'
NVIDIA_API_KEY=nvapi-...
GOOGLE_API_KEY=AIza...
ENV
```

También se pueden exportar como variables de entorno. Si están en los dos
lugares, gana el `.env`.

## 4. Desplegar desde cero

```bash
git clone https://github.com/fardenghi/the-store-main.git
cd the-store-main
# crear el .env de la sección 3
./local.sh create-cluster
```

La primera vez tarda entre 10 y 15 minutos (compila las seis imágenes). Al
terminar, la tienda está en **http://localhost**.

Qué hace `create-cluster`, en orden:

1. **Crea el cluster kind** `the-store`: un nodo con la label
   `ingress-ready=true` y los puertos 80 y 443 del contenedor publicados en el
   host.
2. **Instala ingress-nginx** v1.13.1 (el manifiesto oficial para kind) y espera
   a que esté listo.
3. **Construye las imágenes** `the-store-<servicio>:latest` de `catalog`,
   `cart`, `checkout`, `orders`, `ui` y `assistant`, y **las carga en el
   nodo** con `kind load docker-image`. Por eso los Deployments usan
   `imagePullPolicy: IfNotPresent`: no hay registry.
4. **Crea el namespace** `the-store` y **el Secret** `assistant-api-keys` con
   las claves del `.env`. Se genera con `kubectl create secret
   --dry-run=client | kubectl apply`, así que es idempotente y no deja las
   claves en ningún archivo.
5. **Aplica `dist/kubernetes.yaml`** y espera a que todos los Deployments estén
   `available` y todos los pods `Ready` (hasta 300 s).
6. **Corre los tests e2e** (Cypress, 15 casos) contra `http://localhost`.

Opciones útiles: `--skip-tests` saltea los e2e, y `./local.sh help` lista todos
los comandos.

**Sin claves**, el cluster igual levanta: `local.sh` avisa y guarda el valor
`not-configured`. Todos los pods quedan `Ready` y la tienda funciona, pero el
asistente no responde. Así corre el CI de GitHub Actions, que no tiene secrets.

## 5. Comprobar que funciona

```bash
# 7 pods Ready: assistant, carts, catalog, checkout, orders, qdrant-0 y ui
kubectl get pods -n the-store

# El PVC de Qdrant quedó Bound
kubectl get pvc -n the-store

# Las claves están configuradas (el log no muestra los valores)
kubectl logs -n the-store deploy/assistant | grep "configurada"
# NVIDIA_API_KEY (...): configurada  /  GOOGLE_API_KEY (...): configurada

# Qdrant y la indexación: qdrant UP y productIndex UP con 80 puntos
kubectl exec -n the-store deploy/ui -- curl -s http://assistant/actuator/health
```

La indexación del catálogo arranca sola cuando el `assistant` queda listo y
tarda unos segundos. Al terminar loguea, por ejemplo, `Sincronización del
catálogo terminada … 80 embebidos … 80 puntos en la colección`.

Para comprobar la salida a internet desde el pod (DNS, TCP 443 y TLS):

```bash
kubectl exec -n the-store deploy/assistant -- \
  curl -sS -o /dev/null -w '%{http_code}\n' https://integrate.api.nvidia.com/v1/models
# 200
```

Camino de ese tráfico: el pod resuelve el nombre con kube-dns (`10.96.0.10`),
que reenvía los nombres externos al resolver del nodo kind. El paquete sale
por el gateway del pod hacia el nodo, que le aplica SNAT (`MASQUERADE`) con su
IP en la red `kind` de Docker; Docker hace un segundo NAT hacia el host, y de
ahí sale a internet por HTTPS (TCP 443). Los pods usan la `10.244.0.0/16` y los
Services la `10.96.0.0/16`, los rangos por defecto de kind. Es el único
tráfico que sale del cluster.

## 6. Recorrer los casos de uso

Abrir http://localhost y usar el botón de chat.

| Caso de uso | Qué hacer | Qué se tiene que ver |
|---|---|---|
| Búsqueda semántica | Escribir *"somewhere cozy to curl up with a book"* | Al menos 3 productos reales (sillones, pufs, lámparas) sin coincidencia de palabras. Debajo del párrafo o del ítem que nombra cada producto aparece su tarjeta con imagen, nombre y precio, que abre la ficha |
| Tolerancia a errores de tipeo | Escribir *"mid sentury velvit armchiar"* | El mismo producto que con *"mid century velvet armchair"* (Aiden Mid-Century Velvet Armchair) |
| Reescritura de la consulta | Seguir la conversación con *"too pricey, anything cheaper?"* | En el log, la consulta reescrita trae `maxPrice` y su top-k es mejor que el de la consulta cruda (`topk` contra `rawTopk`) |
| Conversación multi-turno | Pedir algo para hacer más cómodo un ambiente y después *"not a lamp"* | Mantiene el tema del turno anterior y excluye las lámparas (`excludeTags=[lighting]` en el log) |
| Comparación justificada | Pedir que compare dos productos, por ejemplo dos sillones | Compara con criterios explícitos de precio y atributos. El log muestra `reasoning=on` |
| Filtros estructurados | Escribir *"show me lamps under $100, cheapest first"* | La tool `searchProducts` recibe `tags=[lighting]`, `maxPrice=100` y `order=price_asc` |
| Precio y detalle en tiempo real | Preguntar el precio de un producto | La tool `getProductDetails` lee `GET /catalog/products/{id}` |
| Agregar al carrito desde el chat | Pedir que agregue un producto concreto | La tool `addToCart` llama a `POST /carts/{customerId}/items` y el contador del carrito sube sin recargar |
| Productos similares | Abrir la ficha de un producto, por ejemplo la del Aiden Mid-Century Velvet Armchair | La sección *"Similar gear for your lair"* muestra 4 productos parecidos |

Para ver qué pasó en cada turno (reescritura, filtros, tools, latencias):

```bash
kubectl logs -n the-store deploy/assistant -f | grep -E "assistant\.(turn|tool)"
```

Ejemplo de una línea:

```
session=demo-rea outcome=done intent=other rewrite=ok … nvidiaRequests=3 modelCalls=2 tools=addToCart:ok … firstFragmentMs=7964 totalMs=8987
```

Para comprobar el carrito de una sesión por dentro del cluster (el id es el
valor de la cookie `SESSIONID`):

```bash
kubectl exec -n the-store deploy/ui -- curl -s http://carts/carts/<SESSIONID>
```

## 7. Cómo funciona por dentro

### Recorrido de un mensaje del chat

```
navegador ──POST /chat/submit──► ui ──POST /assistant/chat + X-Session-ID──► assistant
   ▲                              │                                            │
   └──────── SSE (texto, cart-updated, done) ◄─────── SSE ◄───────────────────┘
```

1. **ui.** `ChatController` toma la sesión de la cookie `SESSIONID` (la misma
   que usa el carrito), la manda como `X-Session-ID` y retransmite el stream
   SSE. Cada 10 s agrega un comentario `:keepalive` para que nginx (60 s de
   `proxy-read-timeout`) no corte la conexión mientras el modelo razona.
2. **Reescritura** (`QueryRewriter`, prompt `prompts/rewrite.st`). El modelo
   convierte el mensaje y los últimos 3 turnos en una consulta autocontenida
   en inglés, con filtros de precio, tags a excluir y la intención. Si tarda
   más de 12 s, el turno sigue con el mensaje crudo.
3. **Retrieval** (`ContextRetriever`). Embebe la consulta en Gemini con
   `RETRIEVAL_QUERY` y busca los 5 más cercanos en Qdrant, con los filtros
   sobre el payload.
4. **Respuesta con RAG y tools** (`ToolCallingLoop`, prompt
   `prompts/system.st` con la persona A.G.E.N.T.). El modelo recibe el
   contexto, el historial de la sesión y tres tools. El `assistant` ejecuta
   los tool calls, emite eventos `tool` y `cart-updated`, y vuelve a llamar al
   modelo, como máximo 4 veces por turno. El razonamiento sube a `high` solo en
   las comparaciones.
5. **Salvaguarda** (`CartClaimFilter`). Antes de mandar cada oración, se
   descartan las que dicen que algo se agregó al carrito si en el turno no
   hubo un `addToCart` correcto.
6. **Memoria** (`SessionStore`). Guarda los últimos 10 turnos por sesión, con
   los tool calls, en el proceso del `assistant`. Se borra a los 30 minutos
   sin uso o al reiniciar el pod.

Las tres tools (`StoreTools`):

| Tool | Llama a |
|---|---|
| `searchProducts` | Con texto: Qdrant (tags y precio sobre el payload) y después `GET /catalog/products/{id}` por cada resultado para tener el precio vigente. Sin texto: `GET /catalog/products?tags=&order=` |
| `getProductDetails` | `GET /catalog/products/{id}`, sin caché |
| `addToCart` | `POST /carts/{customerId}/items`. El `customerId` es siempre la sesión del turno: el modelo no lo elige |

### Indexación del catálogo

Al arrancar, `ProductIndexer` lee el catálogo paginando
`GET /catalog/products`, embebe el nombre, los tags y la descripción de cada
producto con `RETRIEVAL_DOCUMENT` y los guarda en Qdrant. Compara un hash del
texto, así que **los reinicios sin cambios en el catálogo no gastan cuota**:
solo el primer arranque cuesta 80 requests de Gemini. Los productos similares
de la ficha usan el vector ya guardado en Qdrant y no llaman a Gemini.

### Cuotas

Todas las llamadas a NVIDIA pasan por un limitador en el proceso
(`ChatRateLimiter`): como máximo 36 por minuto, el 90 % de la cuota. Un turno
usa de 2 a 5 llamadas. Ante un 429, el limitador se pausa por el
`Retry-After` y reintenta hasta 2 veces. Si no consigue lugar en 30 s, el chat
muestra un aviso de cuota agotada.

## 8. Qué se agregó en Kubernetes

Todo vive en el namespace `the-store`, en
[`dist/kubernetes.yaml`](dist/kubernetes.yaml). Los servicios originales no
se tocaron.

| Recurso | Detalle |
|---|---|
| `Deployment` `assistant` | 1 réplica, 768Mi de memoria. `securityContext` igual al resto: UID 1000, filesystem de solo lectura, sin capabilities. Probes en `/actuator/health/readiness` y `/liveness` |
| `Service` `assistant` | ClusterIP, puerto 80 → 8080. Solo accesible desde adentro del cluster |
| `ConfigMap` `assistant` | Toda la configuración no secreta: endpoints internos, modelos, timeouts, límites. Cambiar de modelo es editarlo y reiniciar |
| `Secret` `assistant-api-keys` | Lo crea `local.sh`. No está en el YAML |
| `StatefulSet` `qdrant` | Imagen `qdrant/qdrant:v1.19.2-unprivileged`, PVC de 1Gi en la StorageClass `standard`, telemetría desactivada |
| `Service` `qdrant` | ClusterIP con gRPC 6334 (lo usa el `assistant`) y REST 6333 (probes y debug) |
| `ConfigMap` `ui` | Tres variables nuevas: `RETAIL_UI_ENDPOINTS_ASSISTANT`, `RETAIL_UI_CHAT_ENABLED` y `RETAIL_UI_CHAT_PROVIDER` |

Decisiones principales:

- **El `assistant` no se expone en el ingress.** El navegador solo habla con
  la `ui`.
- **Qdrant no cuenta para la readiness del `assistant`.** Si Qdrant se cae, el
  `assistant` sigue en el Service y devuelve un error controlado.
- **Una réplica a propósito.** La memoria del chat y el limitador viven en el
  proceso. Con más réplicas habría que bajar
  `RETAIL_ASSISTANT_RATE_LIMIT_REQUESTS_PER_MINUTE` en proporción y la memoria
  no se compartiría.
- **Sin NetworkPolicies.** El CNI por defecto de kind no las aplica.

## 9. Operación

| Tarea | Cómo |
|---|---|
| Cambiar o rotar las claves | Editar `.env` y `./local.sh update-secrets` (reaplica el Secret y reinicia el `assistant`) |
| Recompilar después de cambiar código | `./local.sh reload-images` y `kubectl rollout restart deployment -n the-store` |
| Aplicar cambios del manifiesto | `kubectl apply -f dist/kubernetes.yaml -n the-store` |
| Pasar al plan B (Nemotron) | Copiar las variables comentadas del ConfigMap `assistant` (o las de la sección "Razonamiento y plan B" del [README del `assistant`](src/assistant/README.md)), aplicar y `kubectl rollout restart deployment/assistant -n the-store` |
| Reindexar el catálogo | `kubectl rollout restart deployment/assistant -n the-store`. Para empezar de cero, borrar antes la colección (ver el README del `assistant`) |
| Probar la tienda sin el asistente | `kubectl scale deployment/assistant -n the-store --replicas=0`. Los e2e siguen pasando |
| Ver la colección de Qdrant | `kubectl port-forward -n the-store svc/qdrant 6333:6333` y `curl -s localhost:6333/collections/products` |
| Borrar todo | `./local.sh delete-cluster` |

## 10. Problemas comunes

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| El chat responde *"Sorry, there was an error processing your message"* y el log del `assistant` dice `NO configurada (placeholder)` | El Secret tiene el placeholder `not-configured` | Crear el `.env` y `./local.sh update-secrets` |
| El chat responde *"The assistant is unavailable right now"* | El pod `assistant` no está corriendo o no responde | `kubectl get pods -n the-store` y `kubectl logs -n the-store deploy/assistant` |
| `productIndex` en `DOWN` con `embedding-provider-unauthorized` | Falta la clave de Gemini o es inválida | Corregirla y `./local.sh update-secrets`. La sincronización fallida no se reintenta sola |
| Error `embedding-quota-exceeded` o la búsqueda deja de responder | Se agotaron las 1.000 requests diarias de Gemini | Esperar al día siguiente. Evitar recrear el cluster muchas veces el mismo día: cada indexación desde cero gasta 80 |
| *"Our operatives are overloaded"* (`llm-quota-exceeded`) | Más de 36 llamadas a NVIDIA por minuto | Esperar los segundos que indica el aviso |
| Respuestas lentas (más de 20 s) | `muse-glimmer-30b` siempre razona y algunos turnos con tools tardan | Si molesta, pasar al plan B (sección 9) |
| `create-cluster` falla al crear el nodo | El puerto 80 o 443 del host está ocupado | Liberar el puerto y volver a correr |
| *"I'm still working on your previous request"* (`session-busy`) | Llegó un mensaje mientras otro de la misma sesión seguía en curso | Esperar a que termine el turno anterior |

## 11. Tests

```bash
./local.sh e2e-test                          # e2e de la tienda contra el cluster (Cypress)
(cd src/assistant && ./mvnw test)            # unitarios y de integración del assistant, sin claves (necesita Docker)
(cd src/assistant && ./mvnw -Psmoke verify)  # smoke contra NVIDIA y Gemini reales (necesita las claves; gasta cuota)
(cd src/ui && ./mvnw test)                   # tests de la ui
```

El workflow de GitHub Actions (`.github/workflows/main.yml`) crea el cluster
sin claves, corre los e2e y lo borra en cada push a `main`.

## 12. Catálogo de productos

El catálogo de la tienda (~80 productos de hogar y muebles) se genera a partir del dataset [Amazon Berkeley Objects](https://amazon-berkeley-objects.s3.amazonaws.com/index.html) con el script `scripts/catalog-data/generate.py`. El script escribe, con el mismo contenido, todas las copias del catálogo:

| Destino | Qué es |
|---|---|
| `src/catalog/repository/products.json` y `tags.json` | Datos que el servicio `catalog` embebe con `go:embed` y carga al arrancar |
| `src/ui/src/main/resources/data/products.json` y `tags.json` | Datos del modo mock de la UI (`MockCatalogService`) |
| `samples/data/` | Copia de ejemplo de los datos |
| `src/ui/src/main/resources/static/assets/img/products/<id>.jpg` | Imágenes que sirve la UI (JPEG 640×640) |
| `samples/images/<id>.jpg` | Copia de ejemplo de las imágenes |

No hay que editar esos archivos a mano: se cambia la curación y se vuelve a generar.

### Archivos del script

| Archivo | Qué contiene |
|---|---|
| `items.csv` | Lista curada: una fila por producto con `item_id` de ABO, `group`, `name` y `tags` (separados por espacios). El orden de las filas es el orden de `products.json` |
| `tags.json` | Taxonomía de 18 tags con su `displayName` |
| `requirements.txt` | Dependencias (Pillow con versión fija, para que las imágenes salgan byte a byte iguales) |
| `.cache/` | Descargas de ABO (metadatos e imágenes originales). No se versiona |

- `group` es uno de `sofa`, `chair`, `ottoman`, `table`, `desk`, `bed`, `storage`, `lighting`, `rug` o `decor`. Define el rango de precio y el tag de tipo, que el script agrega solo (`sofa`, `chair` y `ottoman` → `seating`; `table` y `desk` → `tables`; `bed` → `beds`; `rug` → `rugs`; el resto, al tag homónimo).
- `tags` lleva solo tags de ambiente (`living-room`, `bedroom`, `office`, `dining`) y de estilo/material (`mid-century`, `modern`, `rustic`, `velvet`, `leather`, `wood`, `metal`).
- `name` se escribe a mano: en inglés, de hasta 60 caracteres y sin la marca.
- La descripción, el precio, el id (UUIDv5 del `item_id`) y la imagen se calculan solos, siempre iguales para el mismo `item_id`.

### Preparar el entorno (una vez)

Desde la raíz del repo:

```bash
python3 -m venv .venv
.venv/bin/pip install -r scripts/catalog-data/requirements.txt
```

La primera ejecución de cualquier subcomando descarga ~90 MB de metadatos de ABO a `scripts/catalog-data/.cache/`. Las siguientes usan la caché.

### Buscar candidatos para la curación

`candidates` busca ítems de ABO por `product_type` y palabras clave (en el nombre, estilo, material o color; todas tienen que aparecer). No escribe nada en el repo.

```bash
.venv/bin/python scripts/catalog-data/generate.py candidates --type SOFA --keyword velvet
.venv/bin/python scripts/catalog-data/generate.py candidates --type LAMP --keyword "floor lamp" --with-image --min-bullets 4
```

La salida tiene una fila por ítem: `item_id`, `product_type`, cantidad de bullets en inglés, si tiene imagen (`img`), marca y nombre. Algunos `product_type` útiles: `SOFA`, `CHAIR`, `OTTOMAN`, `STOOL_SEATING`, `TABLE`, `DESK`, `BED`, `HEADBOARD`, `CABINET`, `SHELF`, `LAMP`, `LIGHT_FIXTURE`, `RUG`, `WALL_ART`, `PLANTER`, `PILLOW`, `HOME_FURNITURE_AND_DECOR`.

### Generar el catálogo

```bash
.venv/bin/python scripts/catalog-data/generate.py build
```

Antes de escribir, `build` valida las reglas del catálogo: entre 70 y 90 productos, exactamente un tag de tipo y al menos uno de otro eje por producto, que todos los tags existan, al menos 3 productos por tag y 5 por tag de tipo, nombres de hasta 60 caracteres sin la marca, descripciones de al menos 150 caracteres sin "warranty" ni "return" y al menos 3 precios distintos por tag de tipo. Si alguna falla, muestra los errores y **no escribe ningún archivo**: hay que cambiar la curación (por ejemplo, reemplazar un ítem cuya descripción queda corta o cuya imagen es un placeholder de ABO).

Si todo está bien, escribe los datos y las imágenes en todos los destinos y borra las imágenes de productos que ya no están en el catálogo. Generar dos veces con la misma curación da exactamente los mismos archivos.

Para probar una curación sin tocar el repo, `--root` escribe los destinos debajo de otro directorio:

```bash
.venv/bin/python scripts/catalog-data/generate.py build --root /tmp/catalogo-prueba
```

Después de regenerar, correr los tests del servicio `catalog`, que validan las mismas reglas sobre el JSON embebido y el filtro por tags contra MySQL (los de `./test/...` necesitan Docker):

```bash
cd src/catalog
go test ./repository/... ./test/...
```

### Ver el catálogo nuevo

El servicio `catalog` y la UI llevan los datos dentro de la imagen, así que hay que reconstruirlas:

- **Cluster local (kind)**: `./local.sh reload-images` y reiniciar los pods (`kubectl rollout restart deployment -n the-store`), o directamente `./local.sh rebuild-cluster`. En Kubernetes `catalog` usa una base en memoria, así que cada pod arranca con el catálogo nuevo.
- **docker compose con MariaDB** (`src/catalog/docker-compose.yml`): el seed solo inserta productos cuyo id no existe, así que si el contenedor de la base sobrevive quedan los productos y tags viejos mezclados con los nuevos. Bajar todo antes de volver a levantar:

  ```bash
  cd src/catalog
  docker compose down
  DB_PASSWORD=<password> docker compose up --build
  ```

- **UI en modo mock** (sin backends): `cd src/ui && ./mvnw spring-boot:run` y abrir `http://localhost:8080/catalog`.

### Atribución

Los datos y las imágenes del catálogo provienen de [Amazon Berkeley Objects (ABO)](https://amazon-berkeley-objects.s3.amazonaws.com/index.html), de Amazon.com, publicado bajo la licencia [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

Los datos fueron adaptados: los nombres se acortaron y se les quitó la marca, las descripciones se armaron a partir de una selección de los bullet points de cada producto (sin los textos de garantía y devoluciones ni la marca), los precios son ficticios (ABO no trae precios) y las imágenes se redimensionaron a 640×640 sobre fondo blanco.
