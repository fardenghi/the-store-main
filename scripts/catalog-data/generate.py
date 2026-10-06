#!/usr/bin/env python3
"""Genera el catálogo de The Store a partir de Amazon Berkeley Objects (ABO).

Subcomandos:
  candidates  Busca ítems de ABO para curar items.csv. No escribe nada en el repo.
  build       Lee items.csv, valida y escribe los JSON y las imágenes en todos los destinos.

Los datos de ABO se descargan una sola vez a .cache/ (ver .gitignore).
ABO: https://amazon-berkeley-objects.s3.amazonaws.com/index.html (CC BY 4.0).
"""

import argparse
import csv
import gzip
import hashlib
import html
import io
import json
import re
import sys
import urllib.request
import uuid
from pathlib import Path

from PIL import Image, ImageOps

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
CACHE_DIR = SCRIPT_DIR / ".cache"
ITEMS_CSV = SCRIPT_DIR / "items.csv"
TAGS_JSON = SCRIPT_DIR / "tags.json"

BUCKET_URL = "https://amazon-berkeley-objects.s3.amazonaws.com/"
LISTING_SHARDS = "0123456789abcdef"

# D5: namespace fijo para los ids UUIDv5.
ID_NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, BUCKET_URL)

# D2: el tag de tipo se deriva del grupo.
GROUP_TYPE_TAG = {
    "sofa": "seating",
    "chair": "seating",
    "ottoman": "seating",
    "table": "tables",
    "desk": "tables",
    "bed": "beds",
    "storage": "storage",
    "lighting": "lighting",
    "rug": "rugs",
    "decor": "decor",
}
TYPE_TAGS = set(GROUP_TYPE_TAG.values())

# D4: rango de precio (USD) por grupo.
GROUP_PRICE_RANGE = {
    "sofa": (600, 1800),
    "chair": (90, 550),
    "ottoman": (45, 220),
    "table": (80, 900),
    "desk": (120, 700),
    "bed": (180, 1400),
    "storage": (70, 650),
    "lighting": (30, 260),
    "rug": (50, 480),
    "decor": (15, 120),
}

# D3: bullets que se descartan y cantidad que se conserva.
DISCARDED_BULLET = re.compile(r"warrant|return|guarantee|customer service|satisf", re.IGNORECASE)
FORBIDDEN_DESCRIPTION = re.compile(r"warranty|return", re.IGNORECASE)
MAX_BULLETS = 4

# D6: normalización de imágenes.
IMAGE_SIZE = 640
JPEG_QUALITY = 85

# D7: destinos.
DATA_DIRS = [
    Path("src/catalog/repository"),
    Path("src/ui/src/main/resources/data"),
    Path("samples/data"),
]
IMAGE_DIRS = [
    Path("src/ui/src/main/resources/static/assets/img/products"),
    Path("samples/images"),
]

# Invariantes del spec.
MIN_PRODUCTS, MAX_PRODUCTS = 70, 90
MIN_PER_TAG = 3
MIN_PER_TYPE_TAG = 5
MAX_NAME_LENGTH = 60
MIN_DESCRIPTION_LENGTH = 150
MIN_DISTINCT_PRICES_PER_TYPE = 3


class ValidationError(Exception):
    def __init__(self, errors):
        super().__init__(f"{len(errors)} errores de validación")
        self.errors = errors


# ---------------------------------------------------------------------------
# Descargas (D1)
# ---------------------------------------------------------------------------


def download(relative_path, dest):
    """Descarga BUCKET_URL + relative_path a dest, salvo que ya esté en la caché."""
    if dest.exists():
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    url = BUCKET_URL + relative_path
    print(f"Descargando {url}", file=sys.stderr)
    tmp = dest.with_name(dest.name + ".part")
    with urllib.request.urlopen(url, timeout=120) as response, open(tmp, "wb") as out:
        while chunk := response.read(1 << 16):
            out.write(chunk)
    tmp.rename(dest)
    return dest


def load_listings():
    """Devuelve {item_id: listing} con todos los ítems de ABO."""
    listings = {}
    for shard in LISTING_SHARDS:
        name = f"listings_{shard}.json.gz"
        path = download(f"listings/metadata/{name}", CACHE_DIR / "listings" / name)
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                item = json.loads(line)
                listings[item["item_id"]] = item
    return listings


def load_image_paths():
    """Devuelve {image_id: path} de images/metadata/images.csv.gz."""
    path = download("images/metadata/images.csv.gz", CACHE_DIR / "images.csv.gz")
    with gzip.open(path, "rt", encoding="utf-8", newline="") as f:
        return {row["image_id"]: row["path"] for row in csv.DictReader(f)}


def download_original_image(image_path):
    return download(f"images/original/{image_path}", CACHE_DIR / "images" / image_path)


# ---------------------------------------------------------------------------
# Lectura de campos de ABO
# ---------------------------------------------------------------------------


def english_values(field):
    """Valores en en_US o, si no hay, en el primer en_* que aparezca."""
    if not field:
        return []
    by_language = {}
    for entry in field:
        language = entry.get("language_tag", "")
        if language.startswith("en_"):
            by_language.setdefault(language, []).append(entry["value"])
    if "en_US" in by_language:
        return by_language["en_US"]
    return next(iter(by_language.values()), [])


def first_english(field):
    values = english_values(field)
    return values[0] if values else ""


def product_types(item):
    return [entry["value"] for entry in item.get("product_type", [])]


# ---------------------------------------------------------------------------
# Transformaciones (D3, D4, D5, D6)
# ---------------------------------------------------------------------------


def product_id(item_id):
    return str(uuid.uuid5(ID_NAMESPACE, item_id))


def remove_brand(text, brand):
    if brand:
        text = re.sub(re.escape(brand), "", text, flags=re.IGNORECASE)
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)
    text = re.sub(r"\s{2,}", " ", text)
    return text.strip(" -–—,;:")


def build_description(item):
    brand = first_english(item.get("brand"))
    bullets = []
    for bullet in english_values(item.get("bullet_point")):
        if DISCARDED_BULLET.search(bullet):
            continue
        bullet = remove_brand(" ".join(html.unescape(bullet).split()), brand)
        if not bullet:
            continue
        if bullet[-1] not in ".!?":
            bullet += "."
        bullets.append(bullet)
        if len(bullets) == MAX_BULLETS:
            break
    return " ".join(bullets)


def price_for(item_id, group):
    low, high = GROUP_PRICE_RANGE[group]
    h = int(hashlib.sha256(item_id.encode("utf-8")).hexdigest()[:8], 16) / 2**32
    raw = low + h * (high - low)
    return max(int(raw / 10 + 0.5) * 10 - 1, low)


def normalize_image(source_path):
    """JPEG 640×640 sobre fondo blanco, calidad 85 y sin EXIF."""
    with Image.open(source_path) as original:
        image = ImageOps.exif_transpose(original)
        if image.mode in ("RGBA", "LA", "P"):
            image = image.convert("RGBA")
            background = Image.new("RGBA", image.size, (255, 255, 255, 255))
            image = Image.alpha_composite(background, image)
        image = image.convert("RGB")
        image = ImageOps.contain(image, (IMAGE_SIZE, IMAGE_SIZE), Image.Resampling.LANCZOS)
        canvas = Image.new("RGB", (IMAGE_SIZE, IMAGE_SIZE), (255, 255, 255))
        canvas.paste(image, ((IMAGE_SIZE - image.width) // 2, (IMAGE_SIZE - image.height) // 2))
    out = io.BytesIO()
    canvas.save(out, format="JPEG", quality=JPEG_QUALITY)
    return out.getvalue()


# ---------------------------------------------------------------------------
# build
# ---------------------------------------------------------------------------


def read_items(path):
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    items = []
    for line, row in enumerate(rows, start=2):
        items.append(
            {
                "line": line,
                "item_id": (row.get("item_id") or "").strip(),
                "group": (row.get("group") or "").strip(),
                "name": (row.get("name") or "").strip(),
                "tags": (row.get("tags") or "").split(),
            }
        )
    return items


def read_tags(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def make_products(items, listings, image_paths):
    """Arma los productos y sus imágenes en memoria. Devuelve (products, images, brands, errors)."""
    products, images, brands, errors = [], {}, {}, []
    for item in items:
        where = f"items.csv:{item['line']} ({item['item_id'] or 'sin item_id'})"
        listing = listings.get(item["item_id"])
        if listing is None:
            errors.append(f"{where}: el item_id no existe en ABO")
            continue
        if item["group"] not in GROUP_TYPE_TAG:
            errors.append(f"{where}: grupo desconocido '{item['group']}'")
            continue
        image_path = image_paths.get(listing.get("main_image_id", ""))
        if image_path is None:
            errors.append(f"{where}: el ítem no tiene main_image_id con imagen en ABO")
            continue

        pid = product_id(item["item_id"])
        products.append(
            {
                "id": pid,
                "name": item["name"],
                "description": build_description(listing),
                "price": price_for(item["item_id"], item["group"]),
                "tags": [GROUP_TYPE_TAG[item["group"]]] + item["tags"],
            }
        )
        brands[pid] = first_english(listing.get("brand"))
        try:
            images[pid] = normalize_image(download_original_image(image_path))
        except Exception as e:  # imagen faltante o corrupta: se reemplaza el ítem en la curación
            errors.append(f"{where}: no se pudo obtener la imagen {image_path}: {e}")
    return products, images, brands, errors


def validate(products, tags, brands, item_ids):
    errors = []
    tag_names = [t["name"] for t in tags]

    if len(set(tag_names)) != len(tag_names):
        errors.append("tags.json tiene tags repetidos")
    for tag in tags:
        if not tag.get("name") or not tag.get("displayName"):
            errors.append(f"tags.json: tag sin name o displayName: {tag}")
    if not TYPE_TAGS <= set(tag_names):
        errors.append(f"tags.json no incluye los tags de tipo {sorted(TYPE_TAGS - set(tag_names))}")

    if not MIN_PRODUCTS <= len(products) <= MAX_PRODUCTS:
        errors.append(f"hay {len(products)} productos; tienen que ser entre {MIN_PRODUCTS} y {MAX_PRODUCTS}")

    duplicated = {i for i in item_ids if item_ids.count(i) > 1}
    if duplicated:
        errors.append(f"item_id repetidos en items.csv: {sorted(duplicated)}")
    names = [p["name"].lower() for p in products]
    duplicated = {n for n in names if names.count(n) > 1}
    if duplicated:
        errors.append(f"nombres repetidos: {sorted(duplicated)}")

    for p in products:
        where = f"producto '{p['name']}' ({p['id']})"
        product_tags = p["tags"]
        type_count = sum(1 for t in product_tags if t in TYPE_TAGS)
        if type_count != 1:
            errors.append(f"{where}: tiene {type_count} tags de tipo; tiene que tener exactamente uno")
        if not any(t not in TYPE_TAGS for t in product_tags):
            errors.append(f"{where}: necesita al menos un tag de ambiente o estilo/material")
        if len(set(product_tags)) != len(product_tags):
            errors.append(f"{where}: tiene tags repetidos")
        unknown = [t for t in product_tags if t not in tag_names]
        if unknown:
            errors.append(f"{where}: tags inexistentes {unknown}")

        if not p["name"]:
            errors.append(f"{where}: nombre vacío")
        if len(p["name"]) > MAX_NAME_LENGTH:
            errors.append(f"{where}: el nombre tiene {len(p['name'])} caracteres (máximo {MAX_NAME_LENGTH})")
        brand = brands.get(p["id"], "")
        if brand and brand.lower() in p["name"].lower():
            errors.append(f"{where}: el nombre contiene la marca '{brand}'")

        if len(p["description"]) < MIN_DESCRIPTION_LENGTH:
            errors.append(
                f"{where}: la descripción tiene {len(p['description'])} caracteres (mínimo {MIN_DESCRIPTION_LENGTH})"
            )
        forbidden = FORBIDDEN_DESCRIPTION.search(p["description"])
        if forbidden:
            errors.append(f"{where}: la descripción contiene '{forbidden.group(0)}'")
        if brand and brand.lower() in p["description"].lower():
            errors.append(f"{where}: la descripción contiene la marca '{brand}'")

        if not isinstance(p["price"], int) or p["price"] <= 0:
            errors.append(f"{where}: precio inválido {p['price']!r}")

    for tag in tag_names:
        count = sum(1 for p in products if tag in p["tags"])
        minimum = MIN_PER_TYPE_TAG if tag in TYPE_TAGS else MIN_PER_TAG
        if count < minimum:
            errors.append(f"tag '{tag}': tiene {count} productos (mínimo {minimum})")

    for tag in sorted(TYPE_TAGS):
        prices = {p["price"] for p in products if tag in p["tags"]}
        if len(prices) < MIN_DISTINCT_PRICES_PER_TYPE:
            errors.append(f"tag '{tag}': tiene {len(prices)} precios distintos (mínimo {MIN_DISTINCT_PRICES_PER_TYPE})")

    return errors


def to_json(data):
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def write_outputs(root, products, tags, images):
    products_json = to_json(products)
    tags_json = to_json(tags)
    for data_dir in DATA_DIRS:
        target = root / data_dir
        target.mkdir(parents=True, exist_ok=True)
        (target / "products.json").write_text(products_json, encoding="utf-8")
        (target / "tags.json").write_text(tags_json, encoding="utf-8")

    for image_dir in IMAGE_DIRS:
        target = root / image_dir
        target.mkdir(parents=True, exist_ok=True)
        for stale in target.glob("*.jpg"):
            if stale.stem not in images:
                stale.unlink()
        for pid, data in images.items():
            path = target / f"{pid}.jpg"
            if not path.exists() or path.read_bytes() != data:
                path.write_bytes(data)


def cmd_build(args):
    items = read_items(args.items)
    tags = read_tags(args.tags)
    listings = load_listings()
    image_paths = load_image_paths()

    products, images, brands, errors = make_products(items, listings, image_paths)
    errors += validate(products, tags, brands, [i["item_id"] for i in items])
    if errors:
        print("La validación falló; no se escribió ningún archivo:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    write_outputs(Path(args.root), products, tags, images)
    print(f"Catálogo generado: {len(products)} productos, {len(tags)} tags, {len(images)} imágenes en {args.root}")
    return 0


# ---------------------------------------------------------------------------
# candidates
# ---------------------------------------------------------------------------


def cmd_candidates(args):
    listings = load_listings()
    wanted_types = {t.upper() for t in args.type}
    keywords = [k.lower() for k in args.keyword]
    shown = 0
    for item_id, item in listings.items():
        if wanted_types and not wanted_types & set(product_types(item)):
            continue
        name = first_english(item.get("item_name"))
        if not name:
            continue
        searchable = " ".join(
            [name]
            + english_values(item.get("style"))
            + english_values(item.get("material"))
            + english_values(item.get("color"))
        ).lower()
        if any(k not in searchable for k in keywords):
            continue
        has_image = bool(item.get("main_image_id"))
        if args.with_image and not has_image:
            continue
        bullets = len(english_values(item.get("bullet_point")))
        if bullets < args.min_bullets:
            continue
        brand = first_english(item.get("brand"))
        print(f"{item_id}\t{','.join(product_types(item))}\t{bullets}\t{'img' if has_image else '-'}\t{brand}\t{name}")
        shown += 1
        if shown >= args.limit:
            break
    print(f"{shown} candidatos", file=sys.stderr)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    candidates = sub.add_parser("candidates", help="busca ítems de ABO para curar items.csv")
    candidates.add_argument("--type", action="append", default=[], help="product_type de ABO (p. ej. SOFA); repetible")
    candidates.add_argument("--keyword", action="append", default=[], help="palabra en nombre, estilo, material o color; repetible (AND)")
    candidates.add_argument("--min-bullets", type=int, default=0, help="mínimo de bullets en inglés")
    candidates.add_argument("--with-image", action="store_true", help="solo ítems con main_image_id")
    candidates.add_argument("--limit", type=int, default=50)
    candidates.set_defaults(func=cmd_candidates)

    build = sub.add_parser("build", help="valida items.csv y escribe datos e imágenes")
    build.add_argument("--items", default=str(ITEMS_CSV), help="lista curada (default: items.csv)")
    build.add_argument("--tags", default=str(TAGS_JSON), help="taxonomía (default: tags.json)")
    build.add_argument("--root", default=str(REPO_ROOT), help="raíz donde escribir los destinos (default: el repo)")
    build.set_defaults(func=cmd_build)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
