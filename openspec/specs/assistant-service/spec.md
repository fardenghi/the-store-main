# assistant-service Specification

## Purpose
Define la existencia, el despliegue y la salud del microservicio `assistant`, que concentra la lógica GenAI de The Store, junto con su vector store (Qdrant), su salida hacia los proveedores de modelos en la nube y el manejo de sus credenciales.

## Requirements

### Requirement: Servicio assistant desplegado en el cluster
El sistema SHALL desplegar el servicio `assistant` en el namespace de la tienda, escuchando en el puerto 8080 del contenedor y publicado por un Service de tipo ClusterIP llamado `assistant`, con el mismo esquema de exposición que el resto de los microservicios (`http://assistant` desde dentro del namespace). El servicio MUST ser accesible solo desde dentro del cluster: no SHALL agregarse ninguna regla de Ingress hacia él.

#### Scenario: Acceso desde otro pod del namespace
- **WHEN** un pod del namespace hace `GET http://assistant/actuator/health`
- **THEN** recibe una respuesta HTTP 200 del servicio `assistant`

#### Scenario: Sin exposición hacia afuera del cluster
- **WHEN** desde el navegador del host se pide cualquier ruta del `assistant` a través del Ingress en `http://localhost`
- **THEN** la petición no llega al `assistant`, porque el Ingress solo enruta hacia la `ui`

### Requirement: Health del servicio
El servicio `assistant` SHALL exponer endpoints de liveness y readiness. La readiness MUST depender solo del estado del propio proceso, y no de la disponibilidad de Qdrant ni de los proveedores de modelos. El endpoint de health general SHALL informar el estado de la conexión con Qdrant como un componente propio.

#### Scenario: Servicio listo con dependencias sanas
- **WHEN** el `assistant` terminó de arrancar y Qdrant está disponible
- **THEN** `GET /actuator/health/readiness` responde `UP` y `GET /actuator/health` incluye el componente de Qdrant en `UP`

#### Scenario: Qdrant caído
- **WHEN** Qdrant no está disponible
- **THEN** `GET /actuator/health/readiness` sigue respondiendo `UP` y el componente de Qdrant en `GET /actuator/health` aparece como `DOWN`

### Requirement: Arranque sin credenciales válidas
El servicio `assistant` SHALL arrancar y quedar listo aunque las credenciales de los proveedores no estén configuradas o sean inválidas, para que el resto del cluster se pueda desplegar y probar sin ellas. En ese caso, cualquier operación que necesite a un proveedor MUST fallar con un error explícito, sin afectar a los otros servicios.

#### Scenario: Despliegue sin claves
- **WHEN** se despliega el cluster sin definir `NVIDIA_API_KEY` ni `GOOGLE_API_KEY`
- **THEN** el pod de `assistant` queda en estado Ready y el script de despliegue avisa que las claves no están configuradas

### Requirement: Manejo de credenciales
Las claves `NVIDIA_API_KEY` (chat) y `GOOGLE_API_KEY` (embeddings) SHALL entregarse al `assistant` únicamente desde un Secret de Kubernetes, como variables de entorno. Las claves MUST NOT estar versionadas en el repositorio ni incluidas en ConfigMaps, en la imagen del contenedor ni en los logs del servicio.

#### Scenario: Repositorio sin claves
- **WHEN** se revisa el contenido versionado del repositorio y la imagen de `assistant`
- **THEN** no aparece el valor de ninguna de las dos claves

#### Scenario: Claves inyectadas desde el Secret
- **WHEN** las variables `NVIDIA_API_KEY` y `GOOGLE_API_KEY` están definidas en la máquina que despliega
- **THEN** el Secret del `assistant` contiene esos valores y el contenedor los recibe como variables de entorno

#### Scenario: Rotación de claves
- **WHEN** se actualizan las claves y se vuelve a aplicar el Secret con el script de despliegue
- **THEN** el `assistant` se reinicia y usa las claves nuevas sin reconstruir la imagen

### Requirement: Vector store Qdrant con persistencia
El sistema SHALL desplegar Qdrant en el namespace de la tienda, accesible solo dentro del cluster mediante un Service `qdrant` con REST en el puerto 6333 y gRPC en el puerto 6334. Los datos de Qdrant MUST persistir en un volumen que sobreviva al reinicio del pod.

#### Scenario: Qdrant accesible desde el assistant
- **WHEN** el `assistant` consulta el estado de Qdrant
- **THEN** obtiene respuesta del Service `qdrant` dentro del namespace

#### Scenario: Persistencia ante reinicio
- **WHEN** se borra el pod de Qdrant y Kubernetes lo vuelve a crear
- **THEN** las colecciones y los puntos que existían antes siguen disponibles

### Requirement: Salida hacia los proveedores de modelos
El `assistant` SHALL poder establecer conexiones HTTPS (TCP 443) desde el cluster hacia `integrate.api.nvidia.com` (chat) y `generativelanguage.googleapis.com` (embeddings), resolviendo los nombres con el DNS del cluster. Ningún otro servicio de la tienda SHALL depender de salida a internet en operación.

#### Scenario: Llamada al modelo de chat
- **WHEN** con una `NVIDIA_API_KEY` válida, el `assistant` envía una solicitud de chat al modelo principal configurado
- **THEN** recibe una respuesta exitosa del proveedor

#### Scenario: Llamada al modelo de embeddings
- **WHEN** con una `GOOGLE_API_KEY` válida, el `assistant` pide el embedding de un texto
- **THEN** recibe un vector de 768 dimensiones

### Requirement: Configuración de proveedores sin reconstruir la imagen
La URL base del proveedor de chat, los identificadores de los modelos (principal y de reescritura), el límite de tokens de salida y el modelo y las dimensiones de los embeddings SHALL ser configurables desde el ConfigMap del `assistant`. Las solicitudes de chat MUST incluir siempre un límite explícito de tokens de salida.

#### Scenario: Cambio de modelo principal al plan B
- **WHEN** se cambia en el ConfigMap el modelo principal a `deepseek-ai/deepseek-v4.1-flash` y se reinicia el `assistant`
- **THEN** las solicitudes de chat siguientes usan ese modelo, sin haber reconstruido la imagen

#### Scenario: Valores por defecto
- **WHEN** el `assistant` se despliega con el ConfigMap versionado
- **THEN** el chat apunta a `https://integrate.api.nvidia.com` con `nvidia/nemotron-3-super-120b-a12b` como modelo principal y `nvidia/nemotron-3.5-lightning-30b-a3b` como modelo de reescritura, y los embeddings usan `gemini-embedding-001` con 768 dimensiones

### Requirement: Aislamiento de fallas del asistente
Una falla del `assistant`, de Qdrant o de los proveedores de modelos MUST NOT afectar la navegación, el carrito ni el checkout de la tienda.

#### Scenario: Assistant fuera de servicio
- **WHEN** el Deployment de `assistant` se escala a 0 réplicas
- **THEN** los tests end-to-end existentes de la tienda pasan igual

### Requirement: Despliegue local reproducible
El script de despliegue local SHALL construir la imagen de `assistant`, cargarla en el cluster kind, crear el Secret con las claves tomadas del entorno de la máquina y desplegar `assistant` y Qdrant junto con el resto de los servicios, con un único comando.

#### Scenario: Creación del cluster desde cero
- **WHEN** se ejecuta `./local.sh create-cluster` con las claves definidas en el entorno
- **THEN** quedan disponibles los pods de `assistant` y `qdrant` junto con los demás servicios, y el Secret contiene las claves

#### Scenario: Pipeline de CI sin claves
- **WHEN** el workflow de CI crea el cluster sin claves configuradas
- **THEN** el despliegue termina con todos los pods listos y los tests end-to-end pasan
