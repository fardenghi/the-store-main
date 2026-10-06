# Spec Delta

## Purpose

Define el contenido del catálogo de la tienda (productos de hogar y muebles, taxonomía de tags multi-valor, precios e imágenes) y la semántica del filtro por tags, de forma que la API de `catalog` y la UI ofrezcan datos suficientes para demostrar los casos de uso del asistente.

## ADDED Requirements

### Requirement: Catálogo de hogar y muebles
El catálogo SHALL contener entre 70 y 90 productos de hogar y muebles derivados del dataset Amazon Berkeley Objects (ABO). Ningún producto del catálogo spy anterior SHALL seguir disponible.

#### Scenario: Tamaño del catálogo
- **WHEN** se consulta `GET /catalog/size` sin filtros
- **THEN** la respuesta es `200` con un `size` entre 70 y 90

#### Scenario: Producto spy anterior eliminado
- **WHEN** se consulta `GET /catalog/products/cc789f85-1476-452a-8100-9e74502198e0` (el antiguo "Temporal Tickstopper")
- **THEN** la respuesta es `404`

### Requirement: Grupos de productos comparables
El catálogo SHALL agrupar los productos en las categorías sofás, sillas y sillones, mesas, escritorios, camas y respaldos, pufs y banquetas, guardado, iluminación, alfombras y deco. Cada uno de los siete tags de tipo (`seating`, `tables`, `storage`, `lighting`, `rugs`, `decor`, `beds`) SHALL estar asignado a al menos 5 productos, para que existan alternativas comparables dentro de cada tipo.

#### Scenario: Alternativas dentro de un tipo
- **WHEN** se consulta `GET /catalog/size?tags=lighting`
- **THEN** el `size` es al menos 5

#### Scenario: Todos los tipos tienen alternativas
- **WHEN** se consulta `GET /catalog/size?tags=<t>` para cada tag de tipo `<t>`
- **THEN** cada respuesta tiene un `size` de al menos 5

### Requirement: Taxonomía de tags
`GET /catalog/tags` SHALL devolver exactamente 18 tags organizados en tres ejes: tipo (`seating`, `tables`, `storage`, `lighting`, `rugs`, `decor`, `beds`), ambiente (`living-room`, `bedroom`, `office`, `dining`) y estilo/material (`mid-century`, `modern`, `rustic`, `velvet`, `leather`, `wood`, `metal`). Cada tag SHALL tener un `name` en kebab-case y un `displayName` legible en inglés. Cada tag SHALL estar asignado a al menos 3 productos.

#### Scenario: Listado de tags
- **WHEN** se consulta `GET /catalog/tags`
- **THEN** la respuesta contiene los 18 tags listados, cada uno con `name` y `displayName` no vacíos, y ningún tag del catálogo anterior (`accessories`, `clothing`, `food`, `vehicles`)

#### Scenario: Ningún tag vacío
- **WHEN** se consulta `GET /catalog/size?tags=<t>` para cada tag `<t>` de `GET /catalog/tags`
- **THEN** cada respuesta tiene un `size` de al menos 3

### Requirement: Tags multi-valor por producto
Cada producto SHALL tener exactamente un tag del eje tipo y al menos un tag adicional de los ejes ambiente o estilo/material. Todos los tags de un producto SHALL existir en `GET /catalog/tags`.

#### Scenario: Producto con varios tags
- **WHEN** se consulta `GET /catalog/products/{id}` para cualquier producto del catálogo
- **THEN** el producto tiene al menos 2 tags, exactamente uno de ellos es de tipo, y todos figuran en `GET /catalog/tags`

### Requirement: Filtro por tags con semántica OR
`GET /catalog/products` y `GET /catalog/size` SHALL interpretar el parámetro `tags` (lista separada por comas) como OR: un producto se incluye si tiene al menos uno de los tags pedidos. Un producto que coincide con varios de los tags pedidos SHALL aparecer una sola vez, y `GET /catalog/size` SHALL contar productos distintos.

#### Scenario: Unión de dos tags
- **WHEN** se consulta `GET /catalog/products?tags=velvet,leather&size=100`
- **THEN** la respuesta incluye todo producto con el tag `velvet` o con el tag `leather`, y ningún otro

#### Scenario: Sin duplicados cuando un producto coincide con varios tags
- **WHEN** se consulta `GET /catalog/products?tags=seating,living-room&size=100`
- **THEN** ningún `id` aparece más de una vez en la respuesta
- **AND** `GET /catalog/size?tags=seating,living-room` devuelve la misma cantidad de productos que la respuesta anterior

#### Scenario: Paginación estable con filtro
- **WHEN** se recorren todas las páginas de `GET /catalog/products?tags=seating,living-room&size=6`
- **THEN** la cantidad total de productos recorridos es igual al `size` de `GET /catalog/size?tags=seating,living-room` y no hay repetidos entre páginas

### Requirement: Nombres y descripciones de producto
Cada producto SHALL tener un nombre en inglés de como máximo 60 caracteres que no incluya la marca de ABO. Cada descripción SHALL estar en inglés, tener al menos 150 caracteres, describir el producto (materiales, medidas o uso) y no incluir texto sobre garantía ni devoluciones.

#### Scenario: Nombre corto y sin marca
- **WHEN** se lee cualquier producto del catálogo
- **THEN** su `name` tiene como máximo 60 caracteres y no contiene el valor del campo `brand` del ítem ABO de origen

#### Scenario: Descripción sin garantía
- **WHEN** se lee cualquier producto del catálogo
- **THEN** su `description` tiene al menos 150 caracteres y no contiene las palabras "warranty" ni "return"

### Requirement: Precios por tipo de producto
Cada producto SHALL tener un precio entero positivo, en dólares, dentro de un rango acorde a su tag de tipo. Dentro de cada tag de tipo SHALL haber al menos 3 precios distintos, de modo que pedir una alternativa más barata del mismo tipo tenga respuesta. Los precios SHALL ser los mismos en cada regeneración del catálogo.

#### Scenario: Alternativa más barata del mismo tipo
- **WHEN** se consulta `GET /catalog/products?tags=seating&order=price_asc&size=100`
- **THEN** la respuesta tiene al menos 3 precios distintos, ordenados de menor a mayor

#### Scenario: Precios reproducibles
- **WHEN** se regenera el catálogo dos veces a partir de la misma lista curada
- **THEN** cada producto tiene el mismo precio en ambas generaciones

### Requirement: Identificadores reproducibles
El `id` de cada producto SHALL ser un UUIDv5 derivado del `item_id` de ABO con un namespace fijo, de modo que regenerar el catálogo produzca los mismos ids.

#### Scenario: Regeneración sin cambios
- **WHEN** se regenera el catálogo a partir de la misma lista curada
- **THEN** los archivos de datos y las imágenes generadas son idénticos a los versionados

### Requirement: Imagen por producto
Cada producto SHALL tener una imagen JPG de 640×640 píxeles servida por la UI en `/assets/img/products/<id>.jpg`. No SHALL quedar imágenes de productos que no estén en el catálogo.

#### Scenario: Imagen disponible
- **WHEN** se pide `/assets/img/products/<id>.jpg` a la UI para cualquier `id` del catálogo
- **THEN** la respuesta es `200` con una imagen JPEG de 640×640

#### Scenario: Ficha de producto con imagen
- **WHEN** se abre la ficha de cualquier producto en la UI
- **THEN** se muestra su imagen, nombre, precio, descripción y todos sus tags

### Requirement: Mismo catálogo en todos los consumidores
Los datos del catálogo usados por el servicio `catalog`, por el modo mock de la UI y por los datos de ejemplo del repositorio SHALL ser idénticos, y SHALL poder regenerarse con un único script versionado a partir de una lista curada de `item_id`s de ABO.

#### Scenario: UI en modo mock
- **WHEN** la UI corre con el servicio de catálogo mock
- **THEN** muestra los mismos productos, precios y tags que devuelve el servicio `catalog`

### Requirement: Atribución de ABO
La documentación del proyecto SHALL atribuir los datos e imágenes del catálogo al dataset Amazon Berkeley Objects bajo licencia CC BY 4.0, con enlace al dataset y a la licencia, e indicar que los nombres, descripciones y precios fueron modificados.

#### Scenario: Atribución presente
- **WHEN** se lee la documentación de cómo regenerar el catálogo
- **THEN** incluye la atribución a ABO, el enlace a CC BY 4.0 y la aclaración de que los datos fueron adaptados
