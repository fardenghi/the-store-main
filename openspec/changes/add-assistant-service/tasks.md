# Tasks

## 1. Proyecto `src/assistant`

- [x] 1.1 Crear `src/assistant` con el Maven wrapper (`.mvn/`, `mvnw`, `mvnw.cmd`) copiado de `src/cart`, `.gitignore`, `.dockerignore`, `ATTRIBUTION.md` y un `pom.xml` con parent Spring Boot 3.5.x, Java 21, `groupId` `com.amazon.sample`, `artifactId` `assistant` y el BOM `spring-ai-bom` 1.1.8 (D1). Verificar con `./mvnw -q dependency:resolve`.
- [x] 1.2 Agregar al `pom.xml` las dependencias `spring-boot-starter-web`, `spring-boot-starter-actuator`, `spring-ai-starter-model-openai`, `spring-ai-starter-model-google-genai-embedding`, `spring-ai-starter-vector-store-qdrant` y `spring-boot-starter-test`. Verificar con `./mvnw -q -DskipTests package`.
- [x] 1.3 Crear `AssistantApplication` en el paquete `com.amazon.sample.assistant` y un `application.yml` con `server.port=${port:8080}`, actuator (`health`, `info`, `metrics`) y probes habilitadas (`management.endpoint.health.probes.enabled=true`). Verificar que `./mvnw spring-boot:run` responde `UP` en `/actuator/health/liveness` y `/actuator/health/readiness`.

## 2. Configuración de proveedores

- [x] 2.1 Configurar en `application.yml` el cliente de chat hacia NVIDIA según D2: `spring.ai.openai.base-url=https://integrate.api.nvidia.com`, `api-key=${NVIDIA_API_KEY}`, modelo `nvidia/nemotron-3-super-120b-a12b`, `max-tokens` explícito y la propiedad `retail.assistant.models.rewrite=nvidia/nemotron-3.5-lightning-30b-a3b`.
- [x] 2.2 Configurar el cliente de embeddings: `spring.ai.google.genai.embedding.api-key=${GOOGLE_API_KEY}`, `text.options.model=gemini-embedding-001`, `dimensions=768` y `task-type=RETRIEVAL_DOCUMENT`. Desactivar las autoconfiguraciones de OpenAI que no se usan (`spring.ai.model.embedding=none`, `image`, `audio.speech`, `audio.transcription` y `moderation` en `none`) y fijar `spring.ai.model.embedding.text=google-genai`. Verificar con un test de contexto que hay exactamente un `EmbeddingModel` y que es el de Google GenAI.
- [x] 2.3 Configurar el vector store Qdrant con host y puerto gRPC por propiedad (default `localhost:6334`) e `initialize-schema=false` (D5).
- [x] 2.4 Al arrancar, loguear para cada clave si está configurada o es el placeholder `not-configured`, sin imprimir su valor (D3). Verificar con un test de contexto (`@SpringBootTest`) que arranca con ambas claves en `not-configured` y sin Qdrant disponible, y que la salida capturada no contiene el valor de las claves.
- [x] 2.5 Smoke test manual contra los proveedores reales (con claves válidas en el entorno): un request de chat al modelo principal y otro al de reescritura, y un embedding que devuelva 768 dimensiones. Implementarlo como test de integración excluido por defecto (perfil o tag `smoke`) y verificar con `./mvnw -Psmoke verify`. Registrar los modelos probados en el README del servicio.

## 3. Health de Qdrant

- [x] 3.1 Implementar un `HealthIndicator` `qdrant` que use el health check del `QdrantClient` y reporte `UP` con la versión del servidor o `DOWN` con el error, y dejarlo fuera del grupo de readiness (D4). Verificar con tests unitarios (cliente mockeado) para los casos `UP` y `DOWN`.
- [x] 3.2 Verificar localmente con `docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant:v1.19.2-unprivileged` que `/actuator/health` muestra `qdrant: UP`, y que al detener el contenedor pasa a `DOWN` mientras `/actuator/health/readiness` sigue en `UP`.

## 4. Imagen del contenedor

- [x] 4.1 Crear `src/assistant/Dockerfile` multi-stage sobre Amazon Linux 2023 + Corretto 21, siguiendo el de `src/cart` (usuario `appuser` UID 1000, `SPRING_PROFILES_ACTIVE=prod`, `EXPOSE 8080`). Verificar con `docker build -t the-store-assistant:latest src/assistant` y con `docker run` usando las claves en `not-configured` que el health responde.
- [x] 4.2 Verificar que ni la imagen ni el repo contienen claves: `docker history --no-trunc` y `git grep` sin coincidencias de `nvapi-` ni `AIza`.

## 5. Manifiestos de Kubernetes

- [x] 5.1 Agregar a `dist/kubernetes.yaml` el `ServiceAccount`, el `ConfigMap` (endpoints de `catalog` y `carts`, host y puerto de Qdrant, base URL, modelos, `max-tokens` y dimensiones de embeddings, según D6), el `Service` ClusterIP `assistant` (80 → `http`) y el `Deployment` `assistant` con el mismo patrón de labels, `securityContext`, probes y `/tmp` que `carts`, memoria 768Mi y `envFrom` del ConfigMap y del Secret `assistant-api-keys`. Verificar con `kubectl apply --dry-run=server -f dist/kubernetes.yaml -n the-store`.
- [x] 5.2 Agregar el `StatefulSet` `qdrant` (imagen `qdrant/qdrant:v1.19.2-unprivileged`, `volumeClaimTemplates` de 1Gi en `standard` montado en `/qdrant/storage`, `emptyDir` en `/qdrant/snapshots` y `/tmp`, `readOnlyRootFilesystem`, probes `/readyz` y `/livez`) y el `Service` ClusterIP `qdrant` con los puertos 6333 y 6334 (D5). Verificar con el mismo `--dry-run=server`.

## 6. Despliegue local (`local.sh`)

- [x] 6.1 Sumar `assistant` a `SERVICES` y agregar `create_assistant_secret`: carga `.env` si existe, toma `NVIDIA_API_KEY` y `GOOGLE_API_KEY` del entorno, usa `not-configured` con un warning si falta alguna y aplica el Secret `assistant-api-keys` con `kubectl create secret ... --dry-run=client -o yaml | kubectl apply -f -`. Se llama en `deploy_services` antes del `kubectl apply` (D7). Agregar `.env` a `.gitignore`.
- [x] 6.2 Agregar el comando `update-secrets` (vuelve a aplicar el Secret y hace `kubectl rollout restart deployment/assistant`) y documentarlo en `show_help`. Verificar que después de cambiar una clave en `.env` y correr `./local.sh update-secrets`, `kubectl exec deploy/assistant -- printenv NVIDIA_API_KEY` muestra el valor nuevo.
- [x] 6.3 Documentar en `README.md` (sección Development) cómo definir las claves (variables de entorno o `.env`), el comando `update-secrets` y que sin claves el cluster levanta pero el asistente no responde. Agregar `assistant` y Qdrant a la tabla de servicios. Verificar que los comandos documentados funcionan tal como están escritos.

## 7. Documentación del desvío

- [x] 7.1 Crear `docs/arquitectura.md` con el diagrama Mermaid actualizado (sin `ollama`, con `assistant` → `integrate.api.nvidia.com` y `generativelanguage.googleapis.com` por HTTPS 443) y la tabla de red de la pre-entrega con la fila "Tráfico hacia afuera del cluster" reescrita (camino pod → SNAT del nodo kind → NAT de Docker → internet, DNS vía forward de kube-dns) y la fila de almacenamiento sin el PVC de Ollama (D8). Dejar indicado que reemplaza la sección 4 de `docs/preentrega.md`, que no se edita. Verificar que el Mermaid renderiza (vista previa de GitHub o `mmdc`).

## 8. Verificación integral en el cluster

- [x] 8.1 Con claves válidas, ejecutar `./local.sh rebuild-cluster --skip-tests` y verificar que los pods `assistant-*` y `qdrant-0` quedan Ready, que `kubectl exec deploy/ui -- curl -s http://assistant/actuator/health` muestra `qdrant: UP` y que `http://localhost/actuator/health` responde la `ui` y no el `assistant`.
- [x] 8.2 Verificar la salida a internet desde el pod: `kubectl exec deploy/assistant -- curl -sS -o /dev/null -w '%{http_code}' https://integrate.api.nvidia.com/v1/models` y `https://generativelanguage.googleapis.com/` devuelven un código HTTP (no un error de DNS ni de conexión). Registrar el resultado y el camino de red en `docs/arquitectura.md`.
- [x] 8.3 Verificar la persistencia de Qdrant: crear una colección de prueba por REST (`kubectl port-forward svc/qdrant 6333`), borrar el pod `qdrant-0`, comprobar que la colección sigue existiendo después del reinicio y eliminarla.
- [x] 8.4 Verificar el aislamiento de fallas: `kubectl scale deploy/assistant --replicas=0` y `./local.sh e2e-test` pasa. Después, volver a escalar a 1.
- [ ] 8.5 Verificar el camino de CI sin claves: sin `NVIDIA_API_KEY`, `GOOGLE_API_KEY` ni `.env`, ejecutar `./local.sh rebuild-cluster` (con e2e) y confirmar que todos los pods quedan Ready, que se ve el warning y que los e2e pasan. Confirmar después el mismo resultado en el workflow de GitHub Actions al pushear.
- [x] 8.6 Ejecutar `openspec validate add-assistant-service` sin errores.
