# Tasks

## 1. Script de generación: base y descarga de ABO

- [ ] 1.1 Crear `scripts/catalog-data/` con `generate.py`, `requirements.txt` (Pillow con versión fija) y `tags.json` con los 18 tags y su `displayName`; agregar `scripts/catalog-data/.cache/` a `.gitignore`. Verificar que `python3 -m venv .venv && .venv/bin/pip install -r scripts/catalog-data/requirements.txt` instala sin errores y que `git status` no muestra `.cache/`
- [ ] 1.2 Implementar la descarga cacheada de `listings/metadata/listings_<0-f>.json.gz` e `images/metadata/images.csv.gz` del bucket público de ABO (D1). Verificar que una segunda ejecución no vuelve a descargar nada
- [ ] 1.3 Implementar el subcomando `candidates` (filtros por `product_type` y palabras clave; muestra `item_id`, nombre, marca, cantidad de bullets en inglés y si tiene `main_image_id`). Verificar con `generate.py candidates --type SOFA --keyword velvet` que lista sofás de terciopelo
- [ ] 1.4 Comprobar que `images/original/<path>` se descarga por HTTPS para un `main_image_id` cualquiera. Si no está disponible, aplicar el fallback de Risks en el design y anotarlo para el how-to

## 2. Script de generación: build y validaciones

- [ ] 2.1 Implementar la lectura de `items.csv` (`item_id`, `group`, `name`, `tags`), la derivación del tag de tipo a partir de `group` (D2) y los ids UUIDv5 con el namespace fijo (D5). Verificar que el mismo `item_id` produce el mismo id en dos ejecuciones
- [ ] 2.2 Implementar la descripción a partir de los bullet points (D3): idioma `en_US` o primer `en_*`, descarte por regex de garantía y devoluciones, quitar la marca, primeros 4 bullets. Verificar sobre 3 ítems reales que la salida no contiene "warranty", "return" ni la marca
- [ ] 2.3 Implementar los precios por grupo con la fórmula de D4. Verificar que para un mismo `item_id` el precio es siempre el mismo y queda dentro del rango del grupo
- [ ] 2.4 Implementar la normalización de imágenes a JPEG 640×640 sobre fondo blanco, calidad 85 y sin EXIF (D6). Verificar con Pillow que la imagen generada mide 640×640 y que generarla dos veces da el mismo hash
- [ ] 2.5 Implementar las validaciones previas a escribir: 70–90 productos, exactamente un tag de tipo y al menos uno de otro eje por producto, todos los tags existen, cada tag con ≥3 productos y cada tag de tipo con ≥5, nombre ≤60 caracteres y sin la marca, descripción ≥150 caracteres y sin "warranty"/"return", precio entero positivo y ≥3 precios distintos por tag de tipo. Verificar con un `items.csv` de prueba que viola cada regla que el script falla sin escribir ningún archivo
- [ ] 2.6 Implementar la escritura en los tres destinos de JSON y los dos de imágenes, borrando los `.jpg` que no correspondan a productos del catálogo (D7). Verificar en un directorio temporal que la salida coincide en los tres destinos (`diff`) y que no quedan imágenes huérfanas

## 3. Curación y generación del catálogo

- [ ] 3.1 Curar `items.csv` con ~80 ítems usando `candidates`, respetando el mix de D2 (sofás 10, sillas y sillones 12, pufs y banquetas 6, mesas 9, escritorios 6, camas y respaldos 7, guardado 10, iluminación 9, alfombras 6, deco 5), con nombres cortos sin marca y tags de ambiente y estilo/material. Incluir los productos que necesitan las frases de D9: al menos un sillón de terciopelo mid-century, varios productos para leer o descansar y lámparas. Verificar que `generate.py build` pasa todas las validaciones
- [ ] 3.2 Correr `generate.py build` y revisar a mano una muestra de 10 productos (nombre, descripción, precio e imagen). Verificar que `git status` muestra los JSON nuevos, ~80 imágenes nuevas y las 12 imágenes spy borradas en `src/ui/.../img/products/` y `samples/images/`
- [ ] 3.3 Correr `generate.py build` por segunda vez. Verificar que `git status` no muestra cambios respecto de la primera ejecución (reproducibilidad)

## 4. Servicio `catalog`: tests

- [ ] 4.1 Agregar `src/catalog/repository/data_test.go` que valide sobre el JSON embebido las invariantes del spec (cantidad, ejes de tags, mínimos por tag, nombres, descripciones, precios distintos por tipo, ids UUID únicos). Verificar con `go test ./repository/...`
- [ ] 4.2 En `src/catalog/test/controller_test.go`, corregir la ruta a `/catalog/products/{id}` y usar un producto del catálogo nuevo; agregar un caso que verifique `404` para el id del "Temporal Tickstopper". Verificar con `go test ./test/...` (requiere Docker)
- [ ] 4.3 Agregar tests del filtro OR contra MariaDB: `tags=velvet,leather` devuelve la unión, `tags=seating,living-room` no repite ids, `GET /catalog/size` con esos tags coincide con la cantidad de productos, y recorrer todas las páginas con `size=6` no repite ni pierde productos. Verificar con `go test ./test/...`

## 5. Consumidores del catálogo

- [ ] 5.1 Reemplazar los IDs de `src/load-generator/helpers.js` por 9 ids del catálogo nuevo de tipos variados. Verificar con `jq` que todos existen en `src/catalog/repository/products.json`
- [ ] 5.2 Actualizar `src/e2e/cypress/e2e/cart.cy.js` (id, nombre y precio de un producto nuevo) y `catalog.cy.js` (nombre del primer producto en orden alfabético). Verificar con `./local.sh e2e-test` sobre el cluster local
- [ ] 5.3 Levantar la UI en modo mock y recorrer el catálogo, filtrar por un tag de cada eje y abrir una ficha. Verificar que se ven imagen, nombre, precio, descripción y todos los tags, y que la paginación (14 páginas) se ve bien; si no, anotarlo para `integrate-ui-assistant`

## 6. Documentación

- [ ] 6.1 Crear la sección del how-to (`docs/how-to.md`) que explique cómo curar y regenerar el catálogo (venv, `candidates`, `build`, reconstruir imágenes con `./local.sh reload-images` y `docker compose down` antes de volver a levantar con MariaDB). Verificar ejecutando los comandos tal como están escritos
- [ ] 6.2 Agregar la atribución a Amazon Berkeley Objects bajo CC BY 4.0, con enlace al dataset y a la licencia, aclarando que nombres, descripciones y precios fueron adaptados; referenciarla también desde el `README.md`. Verificar que ambos enlaces funcionan

## 7. Verificación integral

- [ ] 7.1 Con `./local.sh rebuild-cluster`, verificar contra el cluster: `GET /catalog/size` entre 70 y 90, `GET /catalog/tags` con los 18 tags, `GET /catalog/size?tags=<tipo>` ≥5 para cada tipo, y que la UI sirve `/assets/img/products/<id>.jpg` con `200` para todos los ids
- [ ] 7.2 Correr `openspec validate replace-catalog-with-home-furniture` y verificar que no hay errores
