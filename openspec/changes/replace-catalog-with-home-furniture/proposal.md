# Proposal

## Why

El catálogo actual tiene 12 productos con un único tag cada uno y precios con un salto de 250 a 9000. Con ese volumen, los casos de uso del asistente (búsqueda semántica, productos similares, reescritura de consulta, comparaciones, filtros por presupuesto) son triviales o no se pueden demostrar. Se necesita un catálogo más grande, con descripciones ricas y varios tags por producto, para que la demo del TPE muestre diferencias reales.

## What Changes

- **BREAKING** Se reemplazan por completo los 12 productos spy actuales por ~80 productos de hogar y muebles tomados del dataset público Amazon Berkeley Objects (ABO, licencia CC BY 4.0).
- Mix curado para la demo, con grupos de productos comparables entre sí: sofás, sillas y sillones, mesas, escritorios, camas y respaldos, pufs y banquetas, guardado, iluminación, alfombras y deco.
- Cada producto tiene **varios tags** de una taxonomía de ~18 tags en tres ejes: tipo (`seating`, `tables`, `storage`, `lighting`, `rugs`, `decor`, `beds`), ambiente (`living-room`, `bedroom`, `office`, `dining`) y estilo/material (`mid-century`, `modern`, `rustic`, `velvet`, `leather`, `wood`, `metal`).
- Nombre acortado y sin marca, y descripción construida a partir de los bullet points de ABO, sin las líneas de garantía y devoluciones.
- Precios enteros asignados por rango según el tipo de producto, con variación determinística, porque ABO no trae precios.
- IDs UUIDv5 derivados del `item_id` de ABO, para que la generación sea reproducible.
- Imágenes de ABO normalizadas a JPG 640×640 con nombre `<uuid>.jpg`.
- Script versionado en el repo que regenera todos los datos a partir de una lista curada de `item_id`s. Se documenta en el how-to.
- Se mantiene el envoltorio spy de la UI (textos, hero, avatar). Solo cambian los datos del catálogo.

## Capabilities

### New Capabilities
- `product-catalog`: contenido del catálogo de la tienda: cantidad y tipo de productos, taxonomía de tags multi-valor, precios, imágenes por producto y semántica del filtro por tags (OR, igual a la actual).

### Modified Capabilities
<!-- No hay specs existentes en openspec/specs/. -->

## Impact

- `src/catalog/repository/products.json` y `tags.json`, embebidos con `go:embed`, por lo que hay que reconstruir la imagen de `catalog`.
- `src/ui/src/main/resources/static/assets/img/products/`: se eliminan las 12 imágenes actuales y se agregan ~80.
- `src/ui/src/main/resources/data/products.json`, usado por `MockCatalogService`.
- `src/load-generator/helpers.js`, que tiene los IDs de productos hardcodeados.
- Nuevo script de generación de datos y atribución CC BY 4.0 en el how-to.
- **Desvío respecto de la pre-entrega:** las frases de ejemplo de la tabla de casos de uso (*"escape a chase…"*, *"umbrela with a grapling hok"*, *"not a vehicle"*) se reemplazan por equivalentes sobre el nuevo catálogo. Los casos de uso y sus criterios no cambian.
- No depende de ningún otro change. Los changes del assistant pueden desarrollarse con el catálogo actual y reindexar después.
