# Spec Delta

## Purpose

Mantiene en el vector store (Qdrant) una representación vectorial de todo el catálogo real de la tienda, sincronizada al arrancar el servicio `assistant` de forma incremental e idempotente, para que la búsqueda semántica y los productos similares trabajen sobre datos vigentes sin desperdiciar la cuota del proveedor de embeddings.

## ADDED Requirements

### Requirement: Colección de productos
El `assistant` SHALL mantener en Qdrant una colección de productos con vectores de 768 dimensiones y distancia coseno. Si la colección no existe, SHALL crearla. Si existe con otra dimensión u otra distancia, SHALL recrearla y volver a indexar todo el catálogo.

#### Scenario: Primer arranque con Qdrant vacío
- **WHEN** el `assistant` arranca y la colección de productos no existe en Qdrant
- **THEN** la colección queda creada con vectores de 768 dimensiones y distancia coseno

#### Scenario: Colección con otra configuración
- **WHEN** el `assistant` arranca y la colección existe con vectores de una dimensión distinta de 768
- **THEN** la colección se recrea con 768 dimensiones y distancia coseno, y al terminar la sincronización contiene todos los productos del catálogo

### Requirement: Un punto por producto con su payload
Cada producto del catálogo SHALL estar representado por exactamente un punto, cuyo identificador es el `id` (UUID) del producto. El payload de cada punto SHALL incluir `id`, `name`, `description`, `price` y `tags` (los `name` de los tags del producto), con los valores que devolvía el catálogo en la última sincronización, y un hash del texto embebido.

#### Scenario: Punto de un producto indexado
- **WHEN** termina una sincronización exitosa y se consulta en Qdrant el punto con el `id` de un producto del catálogo
- **THEN** el punto existe y su payload tiene el mismo `name`, `description`, `price` y nombres de `tags` que `GET /catalog/products/{id}`

#### Scenario: Cantidad de puntos igual al catálogo
- **WHEN** termina una sincronización exitosa
- **THEN** la cantidad de puntos de la colección es igual al `size` de `GET /catalog/size`

### Requirement: Texto embebido
El vector de cada producto SHALL calcularse con el modelo de embeddings configurado, en modo documento, sobre un texto que concatena el nombre, la descripción y los nombres legibles (`displayName`) de los tags del producto.

#### Scenario: Texto con los tres componentes
- **WHEN** se indexa un producto con nombre "Velvet Accent Chair" y tags `seating` y `velvet`
- **THEN** el texto enviado al modelo de embeddings contiene el nombre, la descripción completa y los `displayName` "Seating" y "Velvet"

### Requirement: Sincronización al arrancar sin bloquear el servicio
Al arrancar, el `assistant` SHALL leer el catálogo completo desde el servicio `catalog`, recorriendo todas las páginas de `GET /catalog/products`, y sincronizar la colección. La sincronización MUST ejecutarse en segundo plano: el `assistant` SHALL quedar listo (readiness `UP`) aunque la sincronización no haya terminado o haya fallado. Si el `catalog` no responde, SHALL reintentar con espera creciente hasta poder leerlo.

#### Scenario: Catálogo todavía no disponible
- **WHEN** el `assistant` arranca antes de que el servicio `catalog` esté listo
- **THEN** la readiness del `assistant` responde `UP`, la sincronización reintenta, y cuando el `catalog` queda disponible la colección termina con todos los productos

#### Scenario: Catálogo con más productos que una página
- **WHEN** el catálogo tiene más productos que el tamaño de página usado para leerlo
- **THEN** la sincronización indexa todos los productos, sin omitir ni duplicar ninguno

### Requirement: Sincronización incremental e idempotente
La sincronización SHALL pedir embeddings solo para los productos nuevos o cuyo texto embebido cambió desde la última sincronización, y SHALL pedirlos en lotes. Un producto cuyo texto no cambió MUST NOT generar llamadas al proveedor de embeddings; si cambiaron otros datos del payload (por ejemplo el precio), SHALL actualizarse su payload sin recalcular el vector. Repetir la sincronización sin cambios en el catálogo SHALL dejar la colección en el mismo estado.

#### Scenario: Reinicio sin cambios en el catálogo
- **WHEN** el `assistant` se reinicia con la colección ya sincronizada y el catálogo sin cambios
- **THEN** la sincronización termina sin ninguna llamada al proveedor de embeddings y la colección queda con los mismos puntos y payloads

#### Scenario: Primer arranque
- **WHEN** se sincroniza un catálogo de alrededor de 80 productos con la colección vacía
- **THEN** los embeddings se piden en lotes, con como máximo 3 llamadas al proveedor

#### Scenario: Producto con descripción modificada
- **WHEN** cambió la descripción de un producto y el `assistant` se reinicia
- **THEN** se recalcula el embedding de ese producto solamente y su payload refleja la descripción nueva

#### Scenario: Cambio solo de precio
- **WHEN** cambió solo el precio de un producto y el `assistant` se reinicia
- **THEN** el payload del punto refleja el precio nuevo sin ninguna llamada al proveedor de embeddings

### Requirement: Productos eliminados del catálogo
Los puntos cuyos identificadores no corresponden a ningún producto del catálogo SHALL eliminarse al terminar una sincronización. La eliminación MUST ocurrir solo si la lectura del catálogo fue completa: una lectura interrumpida o fallida MUST NOT borrar puntos.

#### Scenario: Producto retirado del catálogo
- **WHEN** existe en la colección un punto cuyo `id` ya no está en el catálogo y se completa una sincronización
- **THEN** ese punto deja de existir en la colección

#### Scenario: Lectura del catálogo fallida a mitad de camino
- **WHEN** la lectura del catálogo falla después de haber obtenido algunas páginas
- **THEN** no se elimina ningún punto de la colección

### Requirement: Errores del proveedor de embeddings durante la indexación
Si el proveedor de embeddings rechaza las solicitudes por cuota (HTTP 429) o por un error transitorio, la sincronización SHALL reintentar respetando la espera que indique el proveedor, con un número acotado de intentos. Si las rechaza por credenciales inválidas o ausentes, MUST NOT reintentar y SHALL dejar registrado el error. En ningún caso un error del proveedor SHALL detener el `assistant` ni afectar su readiness.

#### Scenario: Arranque sin clave de embeddings válida
- **WHEN** el `assistant` arranca con `GOOGLE_API_KEY` sin configurar y la colección vacía
- **THEN** el pod queda Ready, la sincronización termina en estado fallido con un error de credenciales, y no hay reintentos contra el proveedor

#### Scenario: Cuota excedida transitoriamente
- **WHEN** el proveedor responde 429 a un lote durante la sincronización y luego vuelve a aceptar solicitudes
- **THEN** el lote se reintenta después de la espera indicada y la sincronización termina con todos los productos indexados

### Requirement: Estado de la indexación observable
El endpoint de health general del `assistant` SHALL informar el estado de la indexación como un componente propio, con la fase (en curso, completa o fallida), la cantidad de puntos indexados y la fecha de la última sincronización exitosa. Este componente MUST NOT formar parte de la readiness. Al terminar cada sincronización, el servicio SHALL registrar en el log cuántos productos se embebieron, se actualizaron sin embeber, se eliminaron y quedaron sin cambios, y cuántas llamadas al proveedor se hicieron.

#### Scenario: Sincronización completa
- **WHEN** termina una sincronización exitosa
- **THEN** `GET /actuator/health` muestra el componente de indexación en `UP` con la cantidad de puntos y la fecha de la sincronización

#### Scenario: Sincronización fallida
- **WHEN** la sincronización falló
- **THEN** `GET /actuator/health` muestra el componente de indexación en `DOWN` con el motivo, y `GET /actuator/health/readiness` sigue en `UP`
