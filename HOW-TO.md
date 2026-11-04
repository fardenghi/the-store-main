# How-to: asistente de compras con GenAI en The Store

**Redes de Información — ITBA — 2C 2026 — Grupo 3, Tema 9**

Cómo levantar The Store con el asistente de compras en un cluster kind local y
comprobar cada caso de uso.


| Para ver…                                                            | Ir a                                                               |
| -------------------------------------------------------------------- | ------------------------------------------------------------------ |
| La API, la configuración y el funcionamiento interno del `assistant` | [`src/assistant/README.md`](src/assistant/README.md)               |
| La integración del chat en la `ui`                                   | [`src/ui/README.md`](src/ui/README.md)                             |
| Cómo se genera el catálogo                                           | [`scripts/catalog-data/README.md`](scripts/catalog-data/README.md) |


## 1. Qué se implementó

The Store pasa de tener un chat con un system prompt ficticio a tener un
asistente de compras que conoce el catálogo, busca por significado, recuerda
la conversación, compara productos y agrega al carrito.


| Componente                    | Qué es                                                                                                                                           | Cambio       |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ------------ |
| `assistant`                   | Microservicio nuevo en Java 21, Spring Boot 3.5 y Spring AI 1.1.8. Concentra toda la lógica GenAI                                                | Nuevo        |
| `qdrant`                      | Vector store con la colección `products` (768 dimensiones, coseno)                                                                               | Nuevo        |
| `ui`                          | Provider de chat `assistant`, productos similares en la ficha y contador del carrito que se actualiza desde el chat                              | Modificado   |
| `catalog`                     | \~80 productos de hogar y muebles del dataset [Amazon Berkeley Objects](https://amazon-berkeley-objects.s3.amazonaws.com/index.html) (CC BY 4.0) | Datos nuevos |
| `carts`, `orders`, `checkout` | —                                                                                                                                                | Sin cambios  |


Modelos (en la nube; ver [sección 8](#8-diferencias-con-la-pre-entrega)):


| Rol                                           | Proveedor                          | Modelo                  |
| --------------------------------------------- | ---------------------------------- | ----------------------- |
| Respuesta, tools y reescritura de la consulta | NVIDIA (API compatible con OpenAI) | `meta/muse-glimmer-30b` |
| Embeddings                                    | Google Gemini API                  | `gemini-embedding-001`  |


## 2. Requisitos


| Herramienta                                                          | Versión probada                                    |
| -------------------------------------------------------------------- | -------------------------------------------------- |
| [Docker](https://docs.docker.com/get-docker/) (corriendo)            | Docker 29.8 (Ubuntu) y Docker Desktop 28 (macOS)   |
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | v0.33.0 (Kubernetes v1.37)                         |
| [kubectl](https://kubernetes.io/docs/tasks/tools/)                   | —                                                  |
| Conexión a internet                                                  | El `assistant` llama a NVIDIA y a Gemini por HTTPS |


Los puertos 80 y 443 del host tienen que estar libres: kind los publica para
el ingress.

## 3. Conseguir las claves


| Variable         | Dónde se saca                                                                  | Cuota del tier gratuito        |
| ---------------- | ------------------------------------------------------------------------------ | ------------------------------ |
| `NVIDIA_API_KEY` | [build.nvidia.com](https://build.nvidia.com/) → iniciar sesión → *Get API Key* | 40 requests por minuto         |
| `GOOGLE_API_KEY` | [Google AI Studio](https://aistudio.google.com/apikey) → *Create API key*      | 100 por minuto y 1.000 por día |


Se guardan en un `.env` en la raíz del repo, que está en el `.gitignore`:

```bash
cat > .env <<'ENV'
NVIDIA_API_KEY=nvapi-...
GOOGLE_API_KEY=AIza...
ENV
```

## 4. Desplegar

```bash
git clone https://github.com/fardenghi/the-store-main.git
cd the-store-main
# crear el .env de la sección 3
./local.sh create-cluster
```

La primera vez tarda entre 10 y 15 minutos. `create-cluster` crea el cluster
kind, instala ingress-nginx, construye y carga las imágenes, crea el Secret
`assistant-api-keys` con las claves del `.env`, aplica
`dist/kubernetes.yaml` y corre los tests e2e. Con `--skip-tests` se saltean
los e2e.

Al terminar, la tienda está en [**http://localhost**](http://localhost).

Sin claves el cluster igual levanta y la tienda funciona, pero el asistente no
responde.

## 5. Comprobar que funciona

```bash
# 7 pods Ready: assistant, carts, catalog, checkout, orders, qdrant-0 y ui
kubectl get pods -n the-store

# Las claves están configuradas (el log no muestra los valores)
kubectl logs -n the-store deploy/assistant | grep "configurada"

# Qdrant UP y productIndex UP con 80 puntos
kubectl exec -n the-store deploy/ui -- curl -s http://assistant/actuator/health
```

La indexación del catálogo arranca sola cuando el `assistant` queda listo y
tarda unos segundos.

## 6. Recorrer los casos de uso

Abrir [http://localhost](http://localhost) y usar el botón de chat.


| Caso de uso                      | Qué hacer                                                                           | Qué se tiene que ver                                                                                                       |
| -------------------------------- | ----------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Búsqueda semántica               | Escribir *"somewhere cozy to curl up with a book"*                                  | Al menos 3 productos reales (sillones, pufs, lámparas) sin coincidencia de palabras, cada uno con su tarjeta               |
| Tolerancia a errores de tipeo    | Escribir *"mid sentury velvit armchiar"*                                            | El mismo producto que con *"mid century velvet armchair"* (Aiden Mid-Century Velvet Armchair)                              |
| Reescritura de la consulta       | Seguir la conversación con *"too pricey, anything cheaper?"*                        | En el log, la consulta reescrita trae `maxPrice` y su top-k es mejor que el de la consulta cruda (`topk` contra `rawTopk`) |
| Conversación multi-turno         | Pedir algo para hacer más cómodo un ambiente y después *"not a lamp"*               | Mantiene el tema del turno anterior y excluye las lámparas (`excludeTags=[lighting]` en el log)                            |
| Comparación justificada          | Pedir que compare dos productos, por ejemplo dos sillones                           | Compara con criterios explícitos de precio y atributos. El log muestra `reasoning=on`                                      |
| Filtros estructurados            | Escribir *"show me lamps under $100, cheapest first"*                               | La tool `searchProducts` recibe `tags=[lighting]`, `maxPrice=100` y `order=price_asc`                                      |
| Precio y detalle en tiempo real  | Preguntar el precio de un producto                                                  | La tool `getProductDetails` lee `GET /catalog/products/{id}`                                                               |
| Agregar al carrito desde el chat | Pedir que agregue un producto concreto                                              | La tool `addToCart` llama a `POST /carts/{customerId}/items` y el contador del carrito sube sin recargar                   |
| Productos similares              | Abrir la ficha de un producto, por ejemplo la del Aiden Mid-Century Velvet Armchair | La sección *"Similar gear for your lair"* muestra 4 productos parecidos                                                    |


## 7. Diferencias con la pre-entrega

En la defensa de la pre-entrega acordamos con los docentes usar modelos de
proveedores externos en lugar de correrlos localmente con Ollama, para mejorar
el funcionamiento del asistente. La pre-entrega planteaba un modelo chico
(`llama3.2:3b`) corriendo solo en CPU dentro del cluster. Con modelos en la
nube, el asistente puede usar un modelo más grande para razonar, llamar a las
tools y reescribir las consultas, sin depender del hardware de la máquina que
corre el cluster.

### Catálogo

El catálogo original se reemplazó por ~80 productos de hogar y muebles de
Amazon Berkeley Objects ([`scripts/catalog-data/README.md`](scripts/catalog-data/README.md)). La `ui` y la persona A.G.E.N.T. no
cambiaron. Como las frases de ejemplo de la pre-entrega nombraban productos
del catálogo anterior, los casos de uso se demuestran con frases equivalentes
(sección 6):


| Caso de uso                   | Frase de la pre-entrega                            | Frase con el catálogo nuevo                       |
| ----------------------------- | -------------------------------------------------- | ------------------------------------------------- |
| Búsqueda semántica            | *"something to escape a chase without being seen"* | *"somewhere cozy to curl up with a book"*         |
| Tolerancia a errores de tipeo | *"umbrela with a grapling hok"*                    | *"mid sentury velvit armchiar"*                   |
| Conversación multi-turno      | *"cheaper"*, *"not a vehicle"*                     | *"too pricey, anything cheaper?"*, *"not a lamp"* |
