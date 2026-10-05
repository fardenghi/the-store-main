# Design

## Context

Hoy el catálogo es un JSON de 12 productos con un tag cada uno, y está copiado de forma idéntica en tres lugares:

- `src/catalog/repository/products.json` y `tags.json`, embebidos con `go:embed` (`repository/data.go`). Al arrancar, `NewRepository` inserta los tags con `db.Save` y los productos con `db.Create`, y **saltea los productos cuyo id ya existe**.
- `src/ui/src/main/resources/data/products.json` y `tags.json`, que lee `MockCatalogService` cuando la UI corre sin backend.
- `samples/data/` y `samples/images/`, copia de ejemplo de los datos y de las imágenes.

Las imágenes las sirve la UI como estáticos en `static/assets/img/products/<id>.jpg` (`product_card.html`, `detail.html`, `cart.html`, `order.html`). El servicio `catalog` no sabe nada de imágenes.

El filtro por tags ya es OR: `GetProducts` y `CountProducts` hacen `JOIN product_tags ... WHERE tags.name IN ? GROUP BY products.id`. El `GROUP BY` es lo que evita duplicados cuando un producto coincide con varios tags, pero con un solo tag por producto nunca se ejercitó. GORM resuelve `Count` con `GROUP BY` devolviendo la cantidad de grupos, así que el conteo ya es de productos distintos.

Otros lugares con IDs o nombres del catálogo actual:

- `src/load-generator/helpers.js`: 9 IDs hardcodeados.
- `src/e2e/cypress/e2e/cart.cy.js`: usa "The Quiet Quill" ($150). `catalog.cy.js` ya está desactualizado (espera "Aqua Ace GT", del catálogo original de AWS). El workflow de CI corre los e2e.
- `src/catalog/test/controller_test.go`: pide `/catalog/product/<id>` (singular, ruta que no existe) y espera "Temporal Tickstopper".

En Kubernetes (`dist/kubernetes.yaml`) `catalog` usa `in-memory` (SQLite), así que cada pod arranca con los datos embebidos. Con `docker compose` usa MariaDB sin volumen.

ABO no trae precios, publica los nombres con la marca y las descripciones como `bullet_point` en varios idiomas. Los metadatos están en `listings/metadata/listings_<0-f>.json.gz` y las imágenes se resuelven por `main_image_id` → `images/metadata/images.csv.gz` → `images/original/<path>`, todo en el bucket público `amazon-berkeley-objects`.

## Goals / Non-Goals

**Goals:**
- Generar los datos de las tres copias y las imágenes con un único script reproducible: misma lista curada → mismos bytes.
- Que el script valide las invariantes del spec antes de escribir, para que una curación mal hecha falle en el script y no en la demo.
- Que los tests del servicio `catalog` cubran el filtro OR con productos multi-tag.

**Non-Goals:**
- Cambiar la API de `catalog`, su modelo de datos o el esquema de la base. Los campos siguen siendo `id`, `name`, `description`, `price` y `tags`.
- Agregar filtros por varios tags a la UI. La UI sigue filtrando por un tag a la vez.
- Cambiar el envoltorio spy (textos, hero, avatar, persona A.G.E.N.T.).
- Reindexar Qdrant: lo resuelve `add-product-indexing` cuando exista.

## Decisions

### D1. Script en Python con Pillow, en `scripts/catalog-data/`

Estructura:

```
scripts/catalog-data/
├── generate.py        # subcomandos: candidates, build
├── items.csv          # lista curada (versionada)
├── tags.json          # taxonomía de 18 tags con displayName
├── requirements.txt   # Pillow con versión fija
└── .cache/            # descargas de ABO (gitignored)
```

- `candidates` filtra los metadatos de ABO cacheados por `product_type` y palabras clave, y muestra `item_id`, nombre, marca, cantidad de bullets en inglés y si tiene imagen. Sirve para curar, no escribe nada en el repo.
- `build` lee `items.csv`, descarga lo que falte a `.cache/`, valida, y escribe los datos y las imágenes en todos los destinos.

Alternativas consideradas:
- **Go**, mismo lenguaje que `catalog`. Necesita `golang.org/x/image` para redimensionar con calidad y es bastante más código para leer gzip, JSON y CSV. No aporta nada porque el script no se despliega.
- **Node**, ya presente en `checkout`, `e2e` y `load-generator`. Procesar imágenes requiere `sharp`, con binarios nativos, más frágil que Pillow.

Elegimos Python porque es la herramienta más directa para un pipeline de datos e imágenes. La pre-entrega descartó Python **como servicio** (un cuarto lenguaje en runtime); un script de desarrollo que no se empaqueta en ninguna imagen ni corre en el cluster no contradice esa decisión. Se ejecuta en un `.venv`, que ya está en `.gitignore`.

### D2. Lista curada en CSV con nombre y tags manuales

`items.csv` tiene una fila por producto con columnas `item_id`, `group`, `name` y `tags` (separados por espacios). El orden de las filas define el orden de `products.json`.

- `group` es uno de `sofa`, `chair`, `ottoman`, `table`, `desk`, `bed`, `storage`, `lighting`, `rug`, `decor` y determina el rango de precio (D4). El tag de tipo se deriva del grupo: `sofa`, `chair` y `ottoman` → `seating`; `table` y `desk` → `tables`; el resto, al tag homónimo (`bed` → `beds`, `rug` → `rugs`).
- `name` se escribe a mano: acortar automáticamente nombres como "Rivet Revolve Modern Upholstered Sofa Couch, 80"W, Grey" da resultados pobres.
- `tags` lleva solo ambiente y estilo/material. Derivarlos de los campos `style`, `material` y `item_keywords` de ABO da resultados ruidosos e incompletos, y los tags son lo que define los grupos comparables de la demo.

Mix objetivo (80 productos): sofás 10, sillas y sillones 12, pufs y banquetas 6, mesas 9, escritorios 6, camas y respaldos 7, guardado 10, iluminación 9, alfombras 6, deco 5. Así cada tag de tipo queda con al menos 5 productos.

### D3. Descripción automática a partir de los bullet points

Para cada ítem: tomar los `bullet_point` con `language_tag` `en_US` (o, si no hay, el primer `en_*`), descartar los que mencionan garantía, devoluciones, servicio al cliente o satisfacción (regex sobre `warrant|return|guarantee|customer service|satisf`), quitar las menciones de la marca, quedarse con los primeros 4, cerrar cada uno con punto y unirlos con un espacio.

No hay columna para sobrescribir la descripción: si un ítem queda con menos de 150 caracteres o con texto prohibido, el script falla y se cambia el ítem en la curación. Mantiene la descripción fiel al dataset y el CSV simple.

### D4. Precios por grupo, determinísticos

| Grupo | Rango (USD) |
|---|---|
| sofa | 600 – 1800 |
| chair | 90 – 550 |
| ottoman | 45 – 220 |
| table | 80 – 900 |
| desk | 120 – 700 |
| bed | 180 – 1400 |
| storage | 70 – 650 |
| lighting | 30 – 260 |
| rug | 50 – 480 |
| decor | 15 – 120 |

`h` = primeros 8 dígitos hex de `sha256(item_id)` / 2³²; `precio = min + h × (max − min)`, redondeado a la decena y restando 1 (por ejemplo 649), con piso en `min`. Es determinístico, da precios "de góndola" y reparte valores dentro del rango. Los rangos se solapan entre grupos de un mismo tipo (una silla cara cuesta más que un puf barato), lo que vuelve interesante el caso de "algo más barato".

Alternativa: precio manual en el CSV. Descartada para no tener que justificar 80 precios a mano.

### D5. IDs UUIDv5 con namespace propio

`id = uuid5(NAMESPACE, item_id)`, con `NAMESPACE = uuid5(NAMESPACE_URL, "https://amazon-berkeley-objects.s3.amazonaws.com/")`, fijo en el script. Mismo `item_id` → mismo id en cualquier máquina, lo que permite que el load-generator, los e2e y el índice de Qdrant referencien ids estables.

### D6. Imágenes desde `images/original`, normalizadas a 640×640

Se descarga la imagen original (las de `images/small` tienen como máximo 256 px y habría que agrandarlas), se convierte a RGB, se ajusta dentro de 640×640 manteniendo la proporción (LANCZOS), se centra sobre un lienzo blanco de 640×640 (las fotos de ABO tienen fondo blanco) y se guarda como JPEG calidad 85, sin EXIF. Con la versión de Pillow fija, la salida es byte a byte reproducible. Estimado: ~80 × 50 KB ≈ 4 MB en el repo.

### D7. Destinos y limpieza

`build` escribe:

- `products.json` y `tags.json` en `src/catalog/repository/`, `src/ui/src/main/resources/data/` y `samples/data/`, con el mismo contenido (JSON con indentación de 2 espacios y salto de línea final).
- `<id>.jpg` en `src/ui/src/main/resources/static/assets/img/products/` y `samples/images/`, **borrando** los `.jpg` de esos dos directorios que no correspondan a un producto del catálogo.

Antes de escribir, valida todas las invariantes del spec que dependen de los datos (cantidad, tags por eje, mínimos por tag, nombre, descripción, precios distintos por tipo). Si alguna falla, no escribe nada.

### D8. Sin cambios de código en `catalog`, con tests nuevos

El OR, la deduplicación y el conteo ya funcionan (ver Context). Se agregan:

- `repository/data_test.go`: valida las invariantes sobre el JSON embebido, sin base de datos, para que corra rápido con `go test ./repository/...`.
- En `test/controller_test.go`: corregir la ruta a `/catalog/products/{id}`, cambiar el producto esperado y agregar casos de OR sin duplicados, conteo y paginación con varios tags contra MariaDB (testcontainers, como el resto de esos tests).

### D9. Desvío respecto de la pre-entrega: frases de ejemplo de los casos de uso

Las frases de la tabla de casos de uso de `docs/preentrega.md` (sección 3) dependen del catálogo spy y no tienen sentido con muebles. Se reemplazan por equivalentes; **los casos de uso y sus criterios de aceptación no cambian**.

| Caso de uso | Frase de la pre-entrega | Frase nueva |
|---|---|---|
| Búsqueda semántica | "something to escape a chase without being seen" | "somewhere cozy to curl up with a book" (debe devolver al menos 3 productos, p. ej. sillones, pufs y lámparas, sin coincidencia léxica) |
| Tolerancia a errores de tipeo y sinónimos | "umbrela with a grapling hok" | "mid sentury velvit armchiar" (debe encontrar el mismo producto que "mid century velvet armchair") |
| Conversación multi-turno | "cheaper", "not a vehicle" | "cheaper", "not a lamp" |

Justificación: el reemplazo del catálogo es necesario para que los casos de uso sean demostrables (ver proposal), y las frases son ejemplos del criterio, no el criterio. La curación tiene que garantizar que existan los productos que estas frases necesitan: al menos un sillón de terciopelo mid-century, varios productos de lectura y descanso, y lámparas que compitan con otros productos en las mismas búsquedas.

## Risks / Trade-offs

- [ABO no expone `images/original` por HTTPS público, o falta la imagen de algún ítem] → El script falla en ese ítem con un mensaje claro y se reemplaza en la curación. Si el problema es general, fallback a la imagen de mayor resolución disponible, documentado en el how-to.
- [Bullets con la marca o con texto de marketing irrelevante] → Quitar la marca es automático; para el resto, la validación de longitud y la revisión de la curación. Un ítem con descripción pobre se reemplaza.
- [Con MariaDB en `docker compose`, si el contenedor de base sobrevive, los 12 productos y 4 tags viejos siguen ahí porque el seed solo inserta] → Documentar `docker compose down` antes de levantar con el catálogo nuevo. En Kubernetes no aplica (SQLite en memoria).
- [Pedidos o carritos existentes con ids viejos] → Muestran la imagen rota. Aceptable: los datos de carrito y pedidos son efímeros en el entorno de la demo.
- [Con 80 productos y 6 por página, la paginación de la UI tiene 14 páginas] → Verificar que `catalog.html` se vea bien. Si no entra, se ajusta en `integrate-ui-assistant`, no en este change.
- [Points de Qdrant de productos que ya no existen] → `add-product-indexing` tiene que contemplar borrar points ausentes del catálogo o recrear la colección. Se deja anotado para ese change.
- [~4 MB de binarios nuevos en git] → Aceptable para el TP. Las imágenes viejas se borran en el mismo commit.

## Migration Plan

1. Curar `items.csv` con `generate.py candidates` y correr `generate.py build`.
2. Actualizar tests, load-generator y e2e con los ids nuevos.
3. Reconstruir las imágenes de `catalog` y `ui` (`./local.sh reload-images`, o `docker compose down && docker compose up --build`).
4. Rollback: revertir el commit; los datos son estáticos y no hay migraciones de base.

## Open Questions

- Las frases nuevas de D9 se validan contra el asistente real cuando estén `add-product-indexing` y `add-assistant-chat`. Si alguna no cumple el criterio, se ajusta la frase o se agrega un producto a la curación, sin cambiar este diseño.
