# semantic-product-search Specification

## Purpose
Permite encontrar productos del catálogo por significado, tolerando sinónimos y errores de tipeo y combinando la consulta con filtros de tags y precio, y obtener los productos más parecidos a uno dado. Lo consumen el chat del asistente, sus tools y la ficha de producto de la `ui`.

## Requirements

### Requirement: Búsqueda semántica por HTTP
El `assistant` SHALL exponer `GET /assistant/products/search` con los parámetros `q` (texto de la consulta, obligatorio), `tags` (lista separada por comas, opcional), `minPrice` y `maxPrice` (enteros, opcionales, inclusivos) y `k` (cantidad máxima de resultados, opcional, por defecto 5, entre 1 y 20). La respuesta SHALL ser `200` con un arreglo JSON de como máximo `k` productos ordenados por similitud decreciente, cada uno con `id`, `name`, `description`, `price`, `tags` (nombres) y `score`. El endpoint MUST estar disponible solo dentro del cluster, como el resto del `assistant`.

#### Scenario: Consulta por significado sin coincidencia léxica
- **WHEN** se consulta `GET /assistant/products/search?q=somewhere cozy to curl up with a book`
- **THEN** la respuesta es `200` con al menos 3 productos reales del catálogo, ordenados por `score` decreciente

#### Scenario: Resultado acotado por k
- **WHEN** se consulta `GET /assistant/products/search?q=lamp&k=3`
- **THEN** la respuesta tiene como máximo 3 productos

### Requirement: Tolerancia a errores de tipeo y sinónimos
La búsqueda SHALL encontrar los mismos productos relevantes ante consultas con errores de tipeo o con sinónimos que ante la grafía correcta.

#### Scenario: Consulta con errores de tipeo
- **WHEN** se busca "mid century velvet armchair" y luego "mid sentury velvit armchiar"
- **THEN** el primer resultado de la consulta correcta aparece entre los 3 primeros resultados de la consulta con errores

#### Scenario: Consulta con sinónimo
- **WHEN** se busca "couch"
- **THEN** entre los 3 primeros resultados hay al menos un producto con el tag `seating` cuyo nombre no contiene la palabra "couch"

### Requirement: Filtro por tags con semántica OR
Cuando se indica `tags`, la búsqueda SHALL devolver solo productos que tengan al menos uno de los tags pedidos, con la misma semántica OR que el parámetro `tags` de `GET /catalog/products`. Un tag que no existe en el catálogo no SHALL producir error: simplemente no coincide con ningún producto.

#### Scenario: Unión de dos tags
- **WHEN** se consulta `GET /assistant/products/search?q=something to sit on&tags=velvet,leather&k=20`
- **THEN** cada producto de la respuesta tiene el tag `velvet` o el tag `leather`

#### Scenario: Tag inexistente
- **WHEN** se consulta `GET /assistant/products/search?q=chair&tags=spaceship`
- **THEN** la respuesta es `200` con un arreglo vacío

### Requirement: Filtro por rango de precio
Cuando se indican `minPrice` y/o `maxPrice`, la búsqueda SHALL devolver solo productos cuyo precio indexado está dentro del rango, con ambos extremos inclusivos. Los filtros de tags y de precio SHALL poder combinarse, y se aplican juntos (un producto debe cumplir ambos).

#### Scenario: Presupuesto máximo
- **WHEN** se consulta `GET /assistant/products/search?q=lamp&maxPrice=100&k=20`
- **THEN** todos los productos de la respuesta tienen `price` menor o igual a 100

#### Scenario: Tags y rango combinados
- **WHEN** se consulta `GET /assistant/products/search?q=comfortable seat&tags=seating&minPrice=100&maxPrice=400&k=20`
- **THEN** todos los productos de la respuesta tienen el tag `seating` y un `price` entre 100 y 400 inclusive

### Requirement: Validación de parámetros de búsqueda
El endpoint de búsqueda SHALL responder `400` con un cuerpo de error que indique el parámetro inválido cuando `q` falta o está vacío, cuando `k` está fuera de rango, cuando `minPrice` o `maxPrice` no son enteros no negativos, o cuando `minPrice` es mayor que `maxPrice`.

#### Scenario: Consulta vacía
- **WHEN** se consulta `GET /assistant/products/search?q=`
- **THEN** la respuesta es `400` e indica que `q` es obligatorio

#### Scenario: Rango invertido
- **WHEN** se consulta `GET /assistant/products/search?q=table&minPrice=500&maxPrice=100`
- **THEN** la respuesta es `400` e indica que `minPrice` no puede superar a `maxPrice`

### Requirement: Errores del proveedor de embeddings en la búsqueda
Cada búsqueda SHALL calcular el embedding de la consulta en modo consulta. Si el proveedor de embeddings no está disponible, tiene credenciales inválidas o rechaza la solicitud por cuota, el endpoint SHALL responder `503` con un cuerpo de error que distinga la causa; en el caso de cuota, SHALL incluir el header `Retry-After`. Repetir exactamente la misma consulta en poco tiempo MUST NOT consumir una nueva solicitud al proveedor.

#### Scenario: Cuota de embeddings excedida
- **WHEN** el proveedor de embeddings responde 429 al calcular el embedding de la consulta
- **THEN** la búsqueda responde `503` con el header `Retry-After` y un error que indica cuota excedida

#### Scenario: Sin clave de embeddings
- **WHEN** el `assistant` corre con `GOOGLE_API_KEY` sin configurar y se hace una búsqueda
- **THEN** la respuesta es `503` con un error que indica que el proveedor de embeddings no está configurado

#### Scenario: Consulta repetida
- **WHEN** se hace dos veces seguidas la misma búsqueda
- **THEN** el proveedor de embeddings recibe una sola solicitud

### Requirement: Índice no disponible
Si la colección de productos no existe o está vacía (por ejemplo, porque la primera sincronización todavía no terminó o falló), los endpoints de búsqueda y de similares SHALL responder `503` con un error que indique que el índice no está disponible, sin llamar al proveedor de embeddings.

#### Scenario: Búsqueda antes de la primera sincronización
- **WHEN** se hace una búsqueda y la colección todavía no tiene puntos
- **THEN** la respuesta es `503` e indica que el índice de productos no está disponible

### Requirement: Productos similares
El `assistant` SHALL exponer `GET /assistant/products/{id}/similar` con el parámetro opcional `k` (por defecto 4, entre 1 y 12), que devuelve `200` con un arreglo de como máximo `k` productos, con el mismo formato que la búsqueda, ordenados por similitud decreciente respecto del producto indicado. El propio producto MUST NOT aparecer en la respuesta. El cálculo MUST usar el vector ya guardado del producto, sin llamar al proveedor de embeddings. Si el `id` no corresponde a un producto indexado, la respuesta SHALL ser `404`; si `k` está fuera de rango, `400`.

#### Scenario: Similares de un producto
- **WHEN** se consulta `GET /assistant/products/{id}/similar?k=4` para un producto indexado
- **THEN** la respuesta es `200` con 4 productos distintos del producto consultado, ordenados por `score` decreciente

#### Scenario: Similares sin proveedor de embeddings
- **WHEN** la colección ya está sincronizada, el proveedor de embeddings no está disponible (o la clave no es válida) y se piden los similares de un producto
- **THEN** la respuesta es `200` con los similares, y no se hizo ninguna solicitud al proveedor

#### Scenario: Similares coherentes con el producto
- **WHEN** se piden los 4 similares de un producto cuyo tag de tipo tiene al menos 5 productos en el catálogo
- **THEN** al menos 2 de los 4 similares comparten ese tag de tipo

#### Scenario: Producto inexistente
- **WHEN** se consulta `GET /assistant/products/00000000-0000-0000-0000-000000000000/similar`
- **THEN** la respuesta es `404`
