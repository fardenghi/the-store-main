# Speech de la presentación

Una entrada por slide del deck (`npm run deck`), en orden. **Se ve** describe
la pantalla; **Contar** es la idea a transmitir, no un texto para leer. Salvo
"El problema", cada slide avanza con un solo clic.

---

## 1. Portada

**Se ve:** "Asistente de compras con GenAI", Grupo 3 y los productos orbitando
el asistente.

**Contar:** presentación del grupo y del tema 9: convertir el chat de The Store
en un asistente de compras con GenAI.

---

## 2. El problema — "La tienda no entiende lo que buscás."

Tiene una pausa: el primer clic muestra el chat.

**Se ve (parte 1):** una búsqueda en lenguaje natural que termina en
`GET /catalog/products?tags=somewhere+cozy+to+curl…` y devuelve 0 productos.

**Contar:** el catálogo solo filtra por tags exactos. Si el usuario no conoce
la palabra exacta, no encuentra nada, aunque el producto exista. No hay
sinónimos ni tolerancia a errores de tipeo.

**Se ve (parte 2):** el chat original responde "Cheaper than what?" a "too
pricey, anything cheaper?".

**Contar:** el chat era un LLM con un personaje, sin datos de la tienda.
Entiende las palabras, pero no conoce el catálogo, no recuerda el turno anterior
y no puede actuar sobre la tienda.

---

## 3. La solución — "Ahora, preguntale como lo harías normalmente."

**Se ve:** la caja del chat escribe la misma consulta que antes daba 0
productos y el botón de enviar queda latiendo.

**Contar:** pie para la demo en vivo. Se pasa a la app y se manda esa consulta.
La secuencia sugerida: búsqueda semántica → "too pricey, anything cheaper?" →
errores de tipeo → comparación → filtros → agregar al carrito → productos
similares en la ficha.

---

## 4. Cómo funciona el asistente

**Se ve:** los productos orbitan el asistente y convergen en uno: "Tu match".

**Contar:** abre la sección. Vamos a seguir un mensaje de punta a punta: qué
pasa desde que el usuario escribe hasta que recibe una recomendación.

---

## 5. Indexación — "Catálogo → conocimiento."

**Se ve:** las fotos del catálogo pasan por Gemini, se convierten en barras
(vectores) y llegan a Qdrant; el contador llega a 80/80.

**Contar:** al arrancar, el `assistant` lee los 80 productos del catálogo,
genera con Gemini un embedding de 768 dimensiones del nombre, los tags y la
descripción de cada uno, y lo guarda en Qdrant. Pasa una sola vez: los
reinicios sin cambios en el catálogo no vuelven a gastar cuota.

---

## 6. Reescritura — "Entender."

**Se ve:** "Lámpara para mi escritorio" entra al LLM 01, con la memoria
orbitando, y sale `desk lamp`.

**Contar:** el primer llamado al LLM no responde al usuario. Con el mensaje y
los últimos turnos de la conversación arma una consulta autocontenida en inglés,
extrae filtros (precio, tags a excluir) y clasifica la intención (buscar,
comparar u otra cosa).

---

## 7. Búsqueda vectorial — "Encontrar."

**Se ve:** `desk lamp` pasa por Gemini y se convierte en vector; el punto viaja
al mapa de Qdrant, el radar marca los más cercanos y salen los 5 productos.
Arriba: "80 → Top 5".

**Contar:** la consulta se embebe con el mismo modelo que el catálogo y Qdrant
devuelve los 5 productos más cercanos por similitud coseno, aplicando los
filtros sobre el payload. El mapa es ilustrativo: las posiciones no son las
reales.

---

## 8. Segundo llamado — "Solo los matches."

**Se ve:** de la grilla de 80 productos, solo 5 viajan al LLM 02.

**Contar:** el segundo llamado recibe únicamente esos 5 productos, además de
las instrucciones, la conversación y el mensaje original. Así responde con
productos reales sin cargar el catálogo entero en el prompt. Recibe los datos
en texto: no ve las fotos.

---

## 9. Respuesta — "Tu match."

**Se ve:** la foto del producto, una respuesta corta que se escribe en vivo y
el precio.

**Contar:** la respuesta llega en streaming (SSE) del `assistant` a la `ui` y
al navegador. Cuando hace falta, el modelo usa tools: precio vigente
(`GET /catalog/products/{id}`) y agregar al carrito
(`POST /carts/{customerId}/items`).

---

## 10. Memoria — "Nogal, más barata."

**Se ve:** la lámpara del turno anterior ($149) → Reescribir → `desk lamp`
con el filtro ≤ $148 → Qdrant → nuevo match a $79.

**Contar:** el asistente guarda los últimos 10 turnos de cada sesión. Por eso
"más barata" se resuelve contra el producto anterior: el filtro es su precio
menos uno, y la búsqueda vuelve a correr con ese filtro.

---

## 11. Por qué lo hicimos así

**Se ve:** un camino que se abre en tres; dos opciones se descartan y una queda.

**Contar:** abre la sección de decisiones: tres elecciones de diseño y qué las
respalda.

---

## 12. Un servicio más

**Se ve:** la `ui` se abre a los servicios; `assistant` aparece en el centro y
se conecta a `qdrant`. Debajo, "ollama · local" se tacha y lo reemplaza
"NVIDIA · Gemini · externo · HTTPS".

**Contar:** toda la lógica de GenAI vive en un microservicio nuevo (Java 21,
Spring AI), el único que habla con Qdrant y con los modelos; la `ui` sigue
siendo solo presentación y las fallas del LLM quedan aisladas. La pre-entrega
planteaba Ollama local; se pasó a modelos externos (NVIDIA para el chat,
Gemini para los embeddings), con autorización de la cátedra. Motivo:
_[completar]_.

---

## 13. Modelo, elegido con datos

**Se ve:** dos barras: muse-glimmer-30b 93% (14 de 15 sesiones) y
nemotron-3-super 50% (3 de 6).

**Contar:** el modelo se eligió con un benchmark propio del flujo de carrito
(`MultiTurnCartSmokeIT`): muse agregó el producto pedido en 14 de 15 sesiones,
sin confirmaciones falsas ni productos equivocados. Nemotron quedó como plan B
verificado. El costo es latencia: el primer texto tarda unos 6,8 s (mediana).

---

## 14. Entender primero mejora el resultado

**Se ve:** "too pricey, anything cheaper?" buscado tal cual trae 1 producto
correcto de 5; reescrito como `sofa · ≤ $988` trae 5 de 5.

**Contar:** caso medido de la evaluación de la reescritura
(`RewriteEvalSmokeIT`). En las 10 consultas de la evaluación, la consulta cruda
acierta 42 de 50 y la reescrita 45 en promedio (tres corridas: 45, 43 y 47). La
ganancia está en las referencias al turno anterior; el español y los errores de
tipeo ya los tolera el modelo de embeddings.

---

## 15. Gracias. ¿Preguntas?

**Se ve:** la animación de la portada.

**Contar:** cierre y preguntas.
