# Design

## Context

Ver `proposal.md` (Why) para la motivación. Estado actual que condiciona el diseño:

- `dist/kubernetes.yaml` es un render plano de los charts Helm de la retail-store-sample. Cada servicio tiene `ServiceAccount`, `ConfigMap`, `Service` ClusterIP (puerto 80 → `targetPort: http`, 8080 del contenedor) y `Deployment` con `securityContext` restrictivo (`runAsUser: 1000`, `readOnlyRootFilesystem`, `drop: ALL`), readiness en `/actuator/health/readiness` y `/tmp` como `emptyDir` en memoria.
- Los servicios Java (`ui`, `cart`) usan Spring Boot 3.5, Java 21 y un Dockerfile multi-stage sobre Amazon Linux 2023 + Corretto 21, compilando con el Maven wrapper. La `ui` ya usa Spring AI 1.0.0 (BOM).
- `local.sh` construye y carga en kind las imágenes de `SERVICES`, aplica el manifiesto y espera `available` en todos los Deployments y `ready` en todos los pods (300 s).
- El workflow de CI (`.github/workflows/main.yml`) ejecuta `./local.sh create-cluster --skip-tests --skip-status` y luego los e2e, **sin secrets**. Cualquier pod que no llegue a Ready rompe el pipeline.
- La pre-entrega (`docs/preentrega.md`) es el contrato aprobado: tiene Ollama en un pod y declara que en operación no hay tráfico hacia afuera del cluster.

## Goals / Non-Goals

**Goals:**
- Esqueleto del servicio `assistant` compilando, empaquetado y desplegado, con los clientes de chat (NVIDIA) y de embeddings (Gemini) configurados y probados contra el proveedor real.
- Qdrant desplegado con persistencia y su estado visible desde el health del `assistant`.
- Que el cluster completo se pueda levantar con y sin claves (desarrollo y CI).
- Documentar el desvío respecto de la pre-entrega (fin de Ollama, tráfico saliente).

**Non-Goals:**
- Crear la colección de Qdrant, indexar productos o buscar: es `add-product-indexing`.
- Endpoints de chat, reescritura de consulta, memoria y persona: es `add-assistant-chat`. Acá solo se dejan configurados los nombres de ambos modelos.
- Rate limiting y manejo de 429: es `add-assistant-tools` (chat) y `add-product-indexing` (embeddings).
- Cambios en la `ui`: es `integrate-ui-assistant`.
- NetworkPolicies de egress: el cluster no tiene ninguna hoy y kind sin CNI con soporte de políticas no las aplicaría.

## Decisions

### D1. Spring Boot 3.5 + Spring AI 1.1.8

Se usa el mismo parent que `ui` y `cart` (Spring Boot 3.5.x) con el BOM `spring-ai-bom` 1.1.8, la última de la línea 1.x.

- Verificado en Maven Central: 1.1.8 publica `spring-ai-starter-model-google-genai-embedding` (con `GEMINI_EMBEDDING_001`, `task-type` y `dimensions` configurables), `spring-ai-starter-vector-store-qdrant` y `spring-ai-starter-model-openai`, cuyo `OpenAiChatOptions` ya tiene `extraBody`. Eso resuelve las dos dudas abiertas en los proposals de `add-product-indexing` y `add-assistant-chat`.
- **Alternativa descartada: Spring AI 2.0.x.** Exige Spring Boot 4 / Spring Framework 7, lo que separaría al `assistant` del resto de los servicios Java y suma riesgo de migración sin ganar nada para el TP.
- **Alternativa descartada: 1.0.0, la misma que la `ui`.** No trae `extraBody` ni el starter nativo de Google GenAI para embeddings.

Paquete `com.amazon.sample.assistant` y `groupId` `com.amazon.sample`, igual que los demás servicios del repo.

### D2. Proveedores: starter de OpenAI apuntando a NVIDIA y starter de Google GenAI para embeddings

- Chat: `spring-ai-starter-model-openai` con `spring.ai.openai.base-url=https://integrate.api.nvidia.com` (el cliente agrega `/v1/chat/completions`) y `spring.ai.openai.api-key=${NVIDIA_API_KEY}`. Modelo principal en `spring.ai.openai.chat.options.model` y `max-tokens` explícito (NVIDIA lo exige). El modelo de reescritura queda como propiedad propia (`retail.assistant.models.rewrite`), que consume `add-assistant-chat` al crear su segundo `ChatClient`.
- Embeddings: `spring-ai-starter-model-google-genai-embedding` con `spring.ai.google.genai.embedding.api-key=${GOOGLE_API_KEY}`, `model=gemini-embedding-001` y `dimensions=768`. El `task-type` por defecto queda en `RETRIEVAL_DOCUMENT`; `add-product-indexing` lo sobreescribe por llamada para las consultas.
- Como el starter de OpenAI autoconfigura también embeddings, imágenes, audio y moderación, se desactivan con `spring.ai.model.embedding=none`, `spring.ai.model.image=none`, `spring.ai.model.audio.speech=none`, `spring.ai.model.audio.transcription=none` y `spring.ai.model.moderation=none`, y se fija `spring.ai.model.embedding.text=google-genai`. Así queda un único `EmbeddingModel` (el de Gemini) y no se arma por error un cliente de embeddings contra NVIDIA.

Todos los valores no secretos se exponen como variables de entorno en el `ConfigMap` `assistant` (binding relajado de Spring, p. ej. `SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL`), con defaults en `application.yml`. Cambiar al plan B es editar el ConfigMap y reiniciar.

### D3. Arranque sin claves: placeholder en lugar de fallar

Las autoconfiguraciones de Spring AI validan al arrancar que la API key tenga texto: con la variable vacía el contexto no levanta, el pod no llega a Ready y el CI se rompe. Por eso:

- `local.sh` crea el Secret siempre. Si `NVIDIA_API_KEY` o `GOOGLE_API_KEY` no están en el entorno, usa el valor `not-configured` y muestra un warning.
- En el arranque, el servicio loguea, sin mostrar valores, si cada clave está configurada o es el placeholder. Las llamadas al proveedor con el placeholder fallan con 401/403, y es responsabilidad de los changes siguientes devolver un error explícito al usuario.
- **Alternativa descartada:** `secretRef` con `optional: true` y claves vacías. El contexto de Spring no arranca igual.
- **Alternativa descartada:** marcar los beans como `@Lazy` o condicionales a la clave. Agrega complejidad y obliga a los changes siguientes a manejar beans ausentes.

### D4. Health: readiness solo del proceso y Qdrant como componente informativo

- Probes como en el resto: readiness en `/actuator/health/readiness` y liveness en `/actuator/health/liveness` (`management.endpoint.health.probes.enabled=true`).
- Un `HealthIndicator` propio llama a la operación de health check del cliente gRPC de Qdrant y queda como componente `qdrant` de `/actuator/health`. Spring AI no trae uno.
- El componente `qdrant` **no** entra en el grupo de readiness. Si entrara, una caída de Qdrant sacaría al `assistant` del Service y la `ui` recibiría conexiones rechazadas en lugar de un error controlado. Además, al crear el cluster, el orden de arranque entre ambos pods no importa.
- Los proveedores en la nube no tienen health indicator: consultarlos periódicamente consumiría la cuota (40 RPM en NVIDIA y 1.000 requests por día en Gemini).

### D5. Qdrant como StatefulSet con la imagen unprivileged

- `StatefulSet` de 1 réplica con `qdrant/qdrant:v1.19.2-unprivileged` (corre como UID 1000, compatible con el `securityContext` del resto) y `volumeClaimTemplates` de 1 Gi sobre la StorageClass `standard` (local-path de kind), montado en `/qdrant/storage`. `/qdrant/snapshots` y `/tmp` son `emptyDir`, para poder usar `readOnlyRootFilesystem: true`.
- `Service` ClusterIP `qdrant` con los puertos `http` 6333 y `grpc` 6334. Probes en `/readyz` y `/livez` (puerto 6333).
- El `assistant` se conecta por gRPC a `qdrant:6334`, que es lo que usa el `QdrantVectorStore` de Spring AI. El REST 6333 queda para debug (`kubectl port-forward`) y para las probes.
- En este change se incluye el starter del vector store con `spring.ai.vectorstore.qdrant.initialize-schema=false`, para tener el `QdrantClient` configurado (host, puerto y nombre de colección). La colección la crea `add-product-indexing`.
- **Alternativa descartada: Deployment + PVC suelto.** Funciona igual con una réplica, pero el StatefulSet es la forma idiomática para un almacenamiento con estado y el PVC queda atado a la identidad del pod.
- **Alternativa descartada: imagen `qdrant/qdrant` estándar.** Corre como root, rompiendo el patrón de seguridad del manifiesto.

`local.sh` espera `available` solo sobre Deployments. El StatefulSet queda cubierto por el `kubectl wait --for=condition=ready pods --all` que viene después.

### D6. Recursos del assistant en el manifiesto

Se copia el bloque del Deployment de `carts` (labels `app.kubernetes.io/*`, `securityContext`, `JAVA_OPTS`, `/tmp` en memoria) con estas diferencias:

- Memoria 768Mi (request = limit), porque Spring AI, el cliente gRPC de Qdrant y el SDK de Google GenAI pesan más que `carts`. CPU request 256m.
- `envFrom` con el ConfigMap `assistant` y el Secret `assistant-api-keys`.
- `ConfigMap` con los endpoints internos que van a usar las tools (`RETAIL_ASSISTANT_ENDPOINTS_CATALOG=http://catalog` y `RETAIL_ASSISTANT_ENDPOINTS_CARTS=http://carts`, mismo formato que la `ui`), el host y puerto de Qdrant y la configuración de modelos de D2.
- El `Service` expone el puerto 80 → `http` (8080), igual que los demás. La pre-entrega dice "puerto 8080 con Service ClusterIP": es el puerto del contenedor y se respeta. La `ui` lo va a llamar como `http://assistant`, con el mismo formato que `http://catalog`.

### D7. Secret y claves en `local.sh`

- Nueva función `create_assistant_secret`, que se llama desde `deploy_services` después de crear el namespace y antes del `kubectl apply`. Si existe un `.env` en la raíz del repo, lo carga (`.env` se agrega al `.gitignore`). Después genera el Secret con `kubectl create secret generic assistant-api-keys --from-literal=... --dry-run=client -o yaml | kubectl apply -f -`, que es idempotente y no deja las claves en ningún archivo del repo.
- Nuevo comando `./local.sh update-secrets`: vuelve a aplicar el Secret y hace `kubectl rollout restart deployment/assistant`, para rotar claves sin recrear el cluster.
- `assistant` se agrega a `SERVICES`, con lo que `build_images`, `load_images` y `reload-images` lo incluyen sin más cambios.
- El Secret **no** va en `dist/kubernetes.yaml`, porque un Secret versionado con valores de ejemplo invita a commitear claves reales.

### D8. Desvío respecto de la pre-entrega: se elimina Ollama y aparece tráfico saliente

La pre-entrega proponía `ollama` en un pod (`llama3.2:3b` + `nomic-embed-text`, solo CPU, PVC de modelos) y declaraba que en operación no salía tráfico del cluster. Con la autorización de la cátedra para usar modelos en la nube:

- **No se despliega Ollama** ni su PVC. El `assistant` sigue siendo el único servicio que habla con el LLM y con Qdrant, así que la arquitectura de la sección 2 de la pre-entrega no cambia: solo se reemplaza el runtime del modelo.
- **Justificación:** en un nodo kind solo con CPU, `llama3.2:3b` tarda decenas de segundos por respuesta y su tool calling es poco confiable, lo que pone en riesgo los casos de uso de razonamiento, comparación y function calling del scope. Los modelos de NVIDIA (120B con razonamiento, 30B rápido para reescribir) y `gemini-embedding-001` cumplen esos casos con latencias aptas para una demo, a costo cero en el tier gratuito.
- **Tráfico saliente nuevo**, que reemplaza la fila "Tráfico hacia afuera del cluster" de la pre-entrega: HTTPS TCP 443 desde el pod `assistant` hacia `integrate.api.nvidia.com` (chat) y `generativelanguage.googleapis.com` (embeddings). El camino es: pod (10.244.0.0/24) → SNAT/masquerade del nodo kind (172.19.0.2) → bridge Docker `kind` → NAT del host → internet. La resolución DNS va del pod a kube-dns (10.96.0.10), que reenvía los nombres externos al resolver del nodo (`forward . /etc/resolv.conf` en el Corefile), que a su vez usa el DNS embebido de Docker. El resto de los servicios sigue sin tráfico saliente.
- **Dónde se documenta:** la pre-entrega no se edita, porque es el contrato aprobado. Se crea `docs/arquitectura.md` con el diagrama Mermaid actualizado (sin `ollama`, con los dos destinos externos y su protocolo) y la tabla de red con la fila de tráfico saliente reescrita y la fila de almacenamiento sin el PVC de Ollama. Se suma `assistant` (y Qdrant) a la tabla de servicios del `README.md`.

## Risks / Trade-offs

- **[Sin internet o con el proveedor caído, el asistente no funciona]** → Por D3 y D4 el resto de la tienda no se ve afectado (el spec lo verifica escalando `assistant` a 0 y corriendo los e2e). Para la demo, tener las claves probadas el día anterior y los modelos del plan B ya configurables por ConfigMap (D2).
- **[El cliente gRPC de Qdrant de Spring AI 1.1.8 (io.qdrant:client 1.13) es más viejo que el servidor 1.19]** → Qdrant mantiene compatibilidad hacia atrás de la API gRPC dentro de la v1. El cliente puede loguear un warning de versión, que se puede desactivar. Si aparece una incompatibilidad real, se fija el servidor en una 1.13.x o se sobreescribe la versión del cliente en el `pom.xml`.
- **[Los IDs de modelos de NVIDIA cambian o se retiran]** → Son configuración (D2) y existe un plan B definido. El smoke test de las tasks se repite antes de la entrega.
- **[Con el placeholder, el pod está Ready pero el asistente no sirve]** → El warning de `local.sh` y el log de arranque lo dejan explícito. Es el precio de que el CI pase sin secrets.
- **[Imagen de Qdrant desde Docker Hub en CI]** → Se pullea una sola vez por run, con `imagePullPolicy: IfNotPresent`. Si hay rate limit de Docker Hub, se puede precargar con `docker pull` + `kind load`.
- **[768Mi por el assistant y Qdrant en el mismo nodo kind]** → Total adicional de alrededor de 1,3 GiB. Entra en un runner de GitHub (7 GB) y en las máquinas del grupo.

## Migration Plan

1. Mergear el change: el próximo `./local.sh create-cluster` (o `rebuild-cluster`) despliega `assistant` y `qdrant`. En un cluster existente alcanza con `./local.sh reload-images`, `./local.sh update-secrets` y `kubectl apply -f dist/kubernetes.yaml -n the-store`.
2. Rollback: revertir el commit y borrar los recursos (`kubectl delete deploy/assistant sts/qdrant svc/assistant svc/qdrant cm/assistant secret/assistant-api-keys pvc -l app.kubernetes.io/name=qdrant`). Ningún otro servicio depende todavía del `assistant`.

## Notas de implementación

Resultados de los spikes y decisiones menores tomadas al implementar. Ninguna cambia las decisiones D1–D8 ni el spec.

### Spikes

- **Proveedores reales (task 2.5):** `./mvnw -Psmoke verify` hizo 3 requests (sin reintentos) y pasó: `nvidia/nemotron-3-super-120b-a12b` y `nvidia/nemotron-3.5-lightning-30b-a3b` responden por `https://integrate.api.nvidia.com` con `max_tokens` explícito y `extraBody` `{chat_template_kwargs: {enable_thinking: false}}` (el `extraBody` por request de `OpenAiChatOptions` 1.1.8 llega al proveedor), y `gemini-embedding-001` devuelve 768 dimensiones. El modelo informado en la respuesta coincide con el pedido. No hizo falta el plan B.
- **Cliente gRPC de Qdrant 1.13 contra el servidor 1.19.2:** el health check, la conexión y la reconexión después de reiniciar `qdrant-0` funcionan. Como se preveía en Risks, el cliente loguea al arrancar un warning de compatibilidad de versiones (o "Failed to obtain server version" si Qdrant todavía no está listo). No se desactiva en este change, porque la autoconfiguración de Spring AI arma el `QdrantGrpcClient` con el chequeo activado y desactivarlo obliga a redefinir el bean; si `add-product-indexing` encuentra una incompatibilidad real, se aplica lo previsto en Risks.
- **Qdrant con `readOnlyRootFilesystem`:** arranca y responde `/readyz` y `/livez`. Solo loguea que no puede crear `.qdrant-initialized` (un indicador para herramientas externas), sin efecto en el servicio.
- **Binding de las variables del ConfigMap:** el formato `SPRING_AI_OPENAI_CHAT_OPTIONS_MAX_TOKENS` (guion bajo en lugar del guion) se mapea bien a `max-tokens`. Lo cubre `ConfigMapBindingTest`, que además simula el cambio al plan B.

### Decisiones menores

- **Defaults de las claves en `application.yml`:** `${NVIDIA_API_KEY:not-configured}` y `${GOOGLE_API_KEY:not-configured}`, para que `./mvnw spring-boot:run` y los tests arranquen sin claves con el mismo comportamiento que en el cluster (D3).
- **Log de claves (D3):** se emite en `ApplicationReadyEvent`; `INFO` "configurada" o `WARN` "NO configurada (placeholder)". No imprime ni el valor ni el placeholder, y los tests verifican con `OutputCaptureExtension` que la salida no contiene ninguno de los dos.
- **Health:** `management.endpoint.health.group.readiness.include=readinessState` deja explícito que Qdrant no entra en la readiness, y `show-details: always` muestra el componente `qdrant` en `/actuator/health`. Con Qdrant caído el estado agregado de `/actuator/health` es `DOWN` (HTTP 503), mientras liveness y readiness siguen en `UP` (200). El health check usa un timeout de 3 s.
- **Probes del `assistant`:** además de la readiness de `carts`, se agregó una `livenessProbe` en `/actuator/health/liveness` (`initialDelaySeconds: 60`), porque D4 y el spec piden liveness.
- **Labels:** los recursos nuevos usan las mismas `app.kubernetes.io/*` que el resto, sin `helm.sh/chart` ni `managed-by: Helm`, porque no vienen de un chart. Qdrant usa `component: vector-store`.
- **Recursos de Qdrant:** 512Mi de memoria (request = limit) y 128m de CPU, que no estaban fijados en D5.
- **Telemetría de Qdrant desactivada** (`QDRANT__TELEMETRY_DISABLED=true`): por defecto Qdrant reporta telemetría a internet, y D8 establece que el único tráfico saliente es el del `assistant`.
- **Secret en `local.sh`:** en lugar de `--from-literal`, las claves se pasan con `--from-env-file=<(printf ...)` (process substitution de bash), para que no aparezcan en los argumentos de `kubectl` ni en la lista de procesos. El resultado es el mismo Secret y sigue siendo idempotente (`--dry-run=client -o yaml | kubectl apply -f -`). Si existe `.env`, sus valores tienen precedencia sobre las variables del entorno. `.env` ya estaba en el `.gitignore` de la raíz.
- **Smoke test:** `ProvidersSmokeIT` (tag `smoke`) corre con `maven-failsafe-plugin` solo en el perfil `smoke`, se saltea si faltan las claves y fija `spring.ai.retry.max-attempts=1` para no multiplicar requests ante un 429.
- **`ATTRIBUTION.md`:** lista las dependencias directas (Spring Boot, Spring AI, cliente de Qdrant y SDK de Google GenAI, todas Apache 2.0). Los archivos Java nuevos no llevan el encabezado de copyright de Amazon de los servicios originales, porque son del grupo.
- **Endpoints por defecto:** `retail.assistant.endpoints.catalog` y `carts` apuntan a `http://localhost:8081` y `:8082` para correr fuera del cluster; en el cluster los define el ConfigMap (`http://catalog`, `http://carts`).
- **Documentación:** la sección nueva del `README.md` está en inglés, como el resto del archivo; `docs/arquitectura.md` y el README del servicio, en español.

### Verificación en el cluster

Con kind v0.33.0 (Kubernetes v1.37.0) en macOS con Docker Desktop:

- **8.1:** `./local.sh rebuild-cluster --skip-tests` con claves: los 7 pods quedan Ready (`assistant-*` y `qdrant-0` incluidos) y el PVC `qdrant-storage-qdrant-0` (1Gi, `standard`) queda `Bound`. Desde la `ui`, `curl http://assistant/actuator/health` muestra `qdrant: UP` con la versión 1.19.2. `http://localhost/actuator/health` responde la `ui` (sin el componente `qdrant`). `kubectl apply --dry-run=server` y `kubectl diff` no muestran cambios.
- **8.2:** desde el pod, NVIDIA `/v1/models` responde `200` y la raíz de `generativelanguage.googleapis.com` responde `404` (DNS, TCP y TLS OK). El camino quedó en `docs/arquitectura.md`. En esta máquina la red `kind` es `172.23.0.0/16` y no `172.19.0.0/16` como en el host Ubuntu de la pre-entrega; el camino es el mismo.
- **8.3:** una colección de prueba con un punto sobrevive al borrado de `qdrant-0`; después se eliminó.
- **6.2:** con un valor de prueba en `.env`, `./local.sh update-secrets` reinicia el `assistant` y `printenv NVIDIA_API_KEY` en el pod muestra el valor nuevo. Después se restauró el `.env` original (mismo SHA) y se volvió a aplicar.
- **8.4:** con `assistant` en 0 réplicas, `./local.sh e2e-test` pasa (13/13). Después se volvió a escalar a 1.
- **8.5 (parte local):** en una copia del repo con solo los archivos versionables (sin `.env`) y sin `NVIDIA_API_KEY` ni `GOOGLE_API_KEY` en el entorno, `./local.sh rebuild-cluster` (con e2e) muestra los dos warnings, crea el Secret con el placeholder, deja los 7 pods Ready, el `assistant` loguea "NO configurada (placeholder)" para ambas claves y los e2e pasan (13/13). **Pendiente:** confirmarlo en el workflow de GitHub Actions, que corre al pushear a `main`; el push queda a cargo del grupo.
