# Proposal

## Why

La pre-entrega aprobada define un microservicio nuevo, `assistant`, que concentra toda la lógica GenAI y es el único que habla con el LLM y con el vector store. Antes de implementar indexación, chat o tools hace falta el servicio desplegado en el cluster junto con su infraestructura: Qdrant, credenciales y la salida a internet hacia el proveedor del modelo.

## What Changes

- Nuevo servicio `src/assistant`: Java 21, Spring Boot y Spring AI. Imagen sobre Amazon Linux 2023 + Corretto 21, igual que `ui` y `cart`. Expone el endpoint de health.
- Despliegue en el namespace `the-store`: `ServiceAccount`, `ConfigMap`, `Service` ClusterIP en el puerto 8080 y `Deployment`, con el mismo patrón que el resto de los servicios en `dist/kubernetes.yaml`.
- Qdrant como `Deployment`/`StatefulSet` propio con su imagen oficial, REST :6333 y gRPC :6334, y un PVC sobre la StorageClass `standard`.
- `Secret` con `NVIDIA_API_KEY`, montado como variable de entorno en `assistant`. La clave no se versiona: se crea desde `local.sh` o a mano.
- Configuración del cliente hacia `https://integrate.api.nvidia.com`: URL base, modelos y `max-tokens` explícito, que NVIDIA exige.
- `local.sh`: `assistant` se suma a `SERVICES` (build y `kind load`) y se agrega la creación del Secret.
- **Desvío respecto de la pre-entrega:** se elimina el pod de Ollama y su PVC. La cátedra autorizó usar modelos de proveedores en la nube, así que todo el tráfico de inferencia sale del cluster por HTTPS 443 hacia NVIDIA (pod → SNAT del nodo kind → NAT de Docker → internet; DNS externo vía forward de kube-dns). Hay que actualizar la fila "Tráfico hacia afuera del cluster" y el diagrama.

## Capabilities

### New Capabilities
- `assistant-service`: existencia, despliegue y salud del microservicio `assistant`, su dependencia de Qdrant y de la API de NVIDIA, y el manejo de credenciales.

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- Nuevo: `src/assistant/` (proyecto Maven y Dockerfile).
- `dist/kubernetes.yaml`: recursos nuevos de `assistant` y `qdrant`, más el `Secret`.
- `local.sh`: lista `SERVICES` y creación del Secret.
- Dependencia externa nueva: API de NVIDIA (tier gratuito, 40 RPM). Sin internet el asistente no funciona; el resto de la tienda no se ve afectado.
- Desbloquea `add-product-indexing`, `add-assistant-chat`, `add-assistant-tools` e `integrate-ui-assistant`.
