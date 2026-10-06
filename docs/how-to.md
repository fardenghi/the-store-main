# How-to

## Catálogo de productos

El catálogo de la tienda (~80 productos de hogar y muebles) se genera a partir del dataset [Amazon Berkeley Objects](https://amazon-berkeley-objects.s3.amazonaws.com/index.html) con el script `scripts/catalog-data/generate.py`. El script escribe, con el mismo contenido, todas las copias del catálogo:

| Destino | Qué es |
|---|---|
| `src/catalog/repository/products.json` y `tags.json` | Datos que el servicio `catalog` embebe con `go:embed` y carga al arrancar |
| `src/ui/src/main/resources/data/products.json` y `tags.json` | Datos del modo mock de la UI (`MockCatalogService`) |
| `samples/data/` | Copia de ejemplo de los datos |
| `src/ui/src/main/resources/static/assets/img/products/<id>.jpg` | Imágenes que sirve la UI (JPEG 640×640) |
| `samples/images/<id>.jpg` | Copia de ejemplo de las imágenes |

No hay que editar esos archivos a mano: se cambia la curación y se vuelve a generar.

### Archivos del script

| Archivo | Qué contiene |
|---|---|
| `items.csv` | Lista curada: una fila por producto con `item_id` de ABO, `group`, `name` y `tags` (separados por espacios). El orden de las filas es el orden de `products.json` |
| `tags.json` | Taxonomía de 18 tags con su `displayName` |
| `requirements.txt` | Dependencias (Pillow con versión fija, para que las imágenes salgan byte a byte iguales) |
| `.cache/` | Descargas de ABO (metadatos e imágenes originales). No se versiona |

- `group` es uno de `sofa`, `chair`, `ottoman`, `table`, `desk`, `bed`, `storage`, `lighting`, `rug` o `decor`. Define el rango de precio y el tag de tipo, que el script agrega solo (`sofa`, `chair` y `ottoman` → `seating`; `table` y `desk` → `tables`; `bed` → `beds`; `rug` → `rugs`; el resto, al tag homónimo).
- `tags` lleva solo tags de ambiente (`living-room`, `bedroom`, `office`, `dining`) y de estilo/material (`mid-century`, `modern`, `rustic`, `velvet`, `leather`, `wood`, `metal`).
- `name` se escribe a mano: en inglés, de hasta 60 caracteres y sin la marca.
- La descripción, el precio, el id (UUIDv5 del `item_id`) y la imagen se calculan solos, siempre iguales para el mismo `item_id`.

### Preparar el entorno (una vez)

Desde la raíz del repo:

```bash
python3 -m venv .venv
.venv/bin/pip install -r scripts/catalog-data/requirements.txt
```

La primera ejecución de cualquier subcomando descarga ~90 MB de metadatos de ABO a `scripts/catalog-data/.cache/`. Las siguientes usan la caché.

### Buscar candidatos para la curación

`candidates` busca ítems de ABO por `product_type` y palabras clave (en el nombre, estilo, material o color; todas tienen que aparecer). No escribe nada en el repo.

```bash
.venv/bin/python scripts/catalog-data/generate.py candidates --type SOFA --keyword velvet
.venv/bin/python scripts/catalog-data/generate.py candidates --type LAMP --keyword "floor lamp" --with-image --min-bullets 4
```

La salida tiene una fila por ítem: `item_id`, `product_type`, cantidad de bullets en inglés, si tiene imagen (`img`), marca y nombre. Algunos `product_type` útiles: `SOFA`, `CHAIR`, `OTTOMAN`, `STOOL_SEATING`, `TABLE`, `DESK`, `BED`, `HEADBOARD`, `CABINET`, `SHELF`, `LAMP`, `LIGHT_FIXTURE`, `RUG`, `WALL_ART`, `PLANTER`, `PILLOW`, `HOME_FURNITURE_AND_DECOR`.

### Generar el catálogo

```bash
.venv/bin/python scripts/catalog-data/generate.py build
```

Antes de escribir, `build` valida las reglas del catálogo: entre 70 y 90 productos, exactamente un tag de tipo y al menos uno de otro eje por producto, que todos los tags existan, al menos 3 productos por tag y 5 por tag de tipo, nombres de hasta 60 caracteres sin la marca, descripciones de al menos 150 caracteres sin "warranty" ni "return" y al menos 3 precios distintos por tag de tipo. Si alguna falla, muestra los errores y **no escribe ningún archivo**: hay que cambiar la curación (por ejemplo, reemplazar un ítem cuya descripción queda corta o cuya imagen es un placeholder de ABO).

Si todo está bien, escribe los datos y las imágenes en todos los destinos y borra las imágenes de productos que ya no están en el catálogo. Generar dos veces con la misma curación da exactamente los mismos archivos.

Para probar una curación sin tocar el repo, `--root` escribe los destinos debajo de otro directorio:

```bash
.venv/bin/python scripts/catalog-data/generate.py build --root /tmp/catalogo-prueba
```

Después de regenerar, correr los tests del servicio `catalog`, que validan las mismas reglas sobre el JSON embebido y el filtro por tags contra MySQL (los de `./test/...` necesitan Docker):

```bash
cd src/catalog
go test ./repository/... ./test/...
```

### Ver el catálogo nuevo

El servicio `catalog` y la UI llevan los datos dentro de la imagen, así que hay que reconstruirlas:

- **Cluster local (kind)**: `./local.sh reload-images` y reiniciar los pods (`kubectl rollout restart deployment -n the-store`), o directamente `./local.sh rebuild-cluster`. En Kubernetes `catalog` usa una base en memoria, así que cada pod arranca con el catálogo nuevo.
- **docker compose con MariaDB** (`src/catalog/docker-compose.yml`): el seed solo inserta productos cuyo id no existe, así que si el contenedor de la base sobrevive quedan los productos y tags viejos mezclados con los nuevos. Bajar todo antes de volver a levantar:

  ```bash
  cd src/catalog
  docker compose down
  DB_PASSWORD=<password> docker compose up --build
  ```

- **UI en modo mock** (sin backends): `cd src/ui && ./mvnw spring-boot:run` y abrir `http://localhost:8080/catalog`.

### Atribución

Los datos y las imágenes del catálogo provienen de [Amazon Berkeley Objects (ABO)](https://amazon-berkeley-objects.s3.amazonaws.com/index.html), de Amazon.com, publicado bajo la licencia [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).

Los datos fueron adaptados: los nombres se acortaron y se les quitó la marca, las descripciones se armaron a partir de una selección de los bullet points de cada producto (sin los textos de garantía y devoluciones ni la marca), los precios son ficticios (ABO no trae precios) y las imágenes se redimensionaron a 640×640 sobre fondo blanco.
