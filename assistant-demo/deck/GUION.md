# Guion de la presentación (borrador para revisar)

30 minutos como máximo, remota, con demo en vivo. Hablan los tres integrantes.
Las slides **ya hechas** son las 7 escenas del video. El resto son **nuevas**:
se arman con los mismos componentes (`src/design.tsx`, `src/motion.tsx`).

Los bloques y los tiempos son una propuesta: ajustar quién presenta cada uno.
Lo que dice cada integrante en cada slide está en [`DISCURSO.md`](./DISCURSO.md).
No se mencionan los desvíos respecto de la pre-entrega.

## Bloque A — Problema y solución (≈ 6 min) · Integrante 1

| # | Slide | Qué muestra | Paradas | Estado |
|---|---|---|---|---|
| 1 | Portada | "The Store / A.G.E.N.T. — Asistente de compras con GenAI". Tema 9, Grupo 3, nombres. Productos orbitando | 1 | Nueva |
| 2 | El problema | La búsqueda de hoy solo filtra por tags exactos: "somewhere cozy to curl up with a book" → 0 resultados. Y el chat no conoce la tienda: un system prompt sin catálogo, sin memoria y sin acciones | 2: búsqueda léxica que falla → chat que no sabe nada | Nueva |
| 3 | Qué hace el asistente | Los 9 casos de uso de la pre-entrega en una grilla que se ilumina de a grupos: buscar (semántica, typos, similares), conversar (reescritura, multi-turno, comparación) y actuar (filtros, precio en vivo, carrito) | 3, uno por grupo | Nueva |
| 4 | Arquitectura | Los servicios de The Store (ui, catalog, carts, orders, checkout). Aparecen `assistant` y Qdrant, y después los dos destinos en la nube (NVIDIA y Gemini). Flechas con el protocolo de cada conexión | 3: tienda original → + assistant y Qdrant → + nube | Nueva |
| 5 | Los modelos | Por qué modelos en la nube (latencia y tool calling confiable). `muse-glimmer-30b` vía NVIDIA en dos roles (reescribir y responder), `gemini-embedding-001` (768d) para los embeddings y plan B Nemotron probado | 2: por qué en la nube → qué modelos | Nueva |

## Bloque B — Cómo funciona por atrás (≈ 10 min) · Integrante 2

El recorrido completo de un mensaje, que pidió el profe.

| # | Slide | Qué muestra | Paradas | Estado |
|---|---|---|---|---|
| 6 | Apertura | "Pedilo. Encontralo." | 1 | Hecha |
| 7 | Arranque e indexación | Catálogo → Gemini → Qdrant, 80 productos en 768 dimensiones | 2 | Hecha |
| 8 | Primer llamado: reescritura | El pedido + memoria → LLM 01 → `desk lamp` | 3 | Hecha |
| 9 | Búsqueda vectorial | El espacio vectorial, la consulta y el top 5 | 2 | Hecha |
| 10 | Segundo llamado: contexto | Solo los 5 productos viajan al LLM 02 | 2 | Hecha |
| 11 | Respuesta | El producto, la respuesta en streaming (SSE) y el precio | 1 | Hecha |
| 12 | Seguimiento con memoria | "Nogal, más barata" → filtro `≤ $148` → recorrido 01 → Qdrant → 02 | 2 | Hecha |
| 13 | Actuar: tools | El LLM 02 pide una tool, el `assistant` la ejecuta contra `catalog` o `carts` y le devuelve el resultado. Las tres tools: `searchProducts`, `getProductDetails`, `addToCart`. Termina con el contador del carrito que sube | 3: pedido → tool call → resultado y carrito | Nueva |
| 14 | Cuidados | Hasta 4 vueltas al modelo por turno, limitador de 36 RPM con espera ante un 429, la salvaguarda que no deja decir "lo agregué" si no se agregó, y las tarjetas de producto en el chat | 2 | Nueva |

## Bloque C — Infraestructura, red y demo (≈ 5 min + 8 min de demo) · Integrante 3

| # | Slide | Qué muestra | Paradas | Estado |
|---|---|---|---|---|
| 15 | Kubernetes | Namespace `the-store`: Deployments, Services ClusterIP, StatefulSet de Qdrant con PVC, ConfigMap y Secret. Decisiones: 1 réplica, la readiness no depende de Qdrant, el `assistant` no se expone en el ingress | 2: recursos → decisiones | Nueva |
| 16 | Red | Nodo kind en la red bridge `kind` de Docker (sin IP concreta: depende de la máquina), pods `10.244.0.0/16`, services `10.96.0.0/16`, ingress en el puerto 80 del host. Un paquete recorre la salida: pod → SNAT del nodo → NAT de Docker → internet, y el DNS: kube-dns → resolver del nodo | 3: adentro del cluster → navegador ↔ ui ↔ assistant (SSE) → salida a internet | Nueva |
| 17 | Demo | Slide de transición con la lista de casos que se van a mostrar | 1 | Nueva |
| 18 | Cierre | Qué aprendimos, límites (memoria en el proceso, 1 réplica, cuotas) y preguntas | 2 | Nueva |

## Demo en vivo (≈ 8 min)

En el orden del ensayo (`docs/how-to.md`, sección 6): ficha con similares →
búsqueda semántica → "cheaper" → "not a lamp" → errores de tipeo →
comparación → filtros → precio en vivo → agregar al carrito. Tener abierta una
terminal con los logs del `assistant` para mostrar la reescritura y las tools.

Respaldo: si NVIDIA tarda o falla, pasar el video (`out/the-store-assistant-v2.mp4`).

## Para el Campus

El enunciado pide subir la presentación como PPT. Se genera desde el mismo
código: una imagen por parada (y los clips de las animaciones) en un `.pptx`.
